package it.magius.struttura.architect.ingame.cache;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.api.ApiClient;
import it.magius.struttura.architect.ingame.model.SpawnableBuilding;
import it.magius.struttura.architect.ingame.model.SpawnableList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles sequential downloading of all buildings in a spawnable list.
 * Downloads buildings one at a time (ApiClient only supports one concurrent request).
 * Tracks download progress and notifies when all downloads are complete.
 */
public class BuildingDownloader {

    private static BuildingDownloader instance;

    private DownloadState state = DownloadState.IDLE;
    private long downloadStartTime = 0;
    private long downloadEndTime = 0;
    private int totalBuildings = 0;
    private AtomicInteger downloadedCount = new AtomicInteger(0);
    private AtomicInteger failedCount = new AtomicInteger(0);
    private Runnable onCompleteCallback = null;

    // Queue of buildings to download
    private List<SpawnableBuilding> downloadQueue = new ArrayList<>();
    private int currentIndex = 0;

    // Server reference for sending chat messages
    private MinecraftServer server = null;

    public enum DownloadState {
        IDLE,           // No download in progress
        DOWNLOADING,    // Currently downloading buildings
        READY,          // All buildings downloaded, spawner can start
        FAILED          // Download failed (some buildings couldn't be downloaded)
    }

    private BuildingDownloader() {}

    public static BuildingDownloader getInstance() {
        if (instance == null) {
            instance = new BuildingDownloader();
        }
        return instance;
    }

    /**
     * Starts downloading all buildings in the spawnable list.
     * Buildings are downloaded sequentially (one at a time).
     * @param list the spawnable list containing buildings to download
     * @param server the server instance for sending chat messages
     * @param onComplete callback when all downloads are complete (called on any thread)
     */
    public void startDownload(SpawnableList list, MinecraftServer server, Runnable onComplete) {
        this.server = server;
        if (state == DownloadState.DOWNLOADING) {
            Architect.LOGGER.warn("Download already in progress, ignoring new request");
            return;
        }

        if (list == null || !list.hasBuildings()) {
            Architect.LOGGER.info("No buildings to download, spawner ready immediately");
            state = DownloadState.READY;
            downloadStartTime = System.currentTimeMillis();
            downloadEndTime = downloadStartTime;
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }

        // Reset state
        state = DownloadState.DOWNLOADING;
        downloadStartTime = System.currentTimeMillis();
        downloadEndTime = 0;
        totalBuildings = list.getBuildingCount();
        downloadedCount.set(0);
        failedCount.set(0);
        onCompleteCallback = onComplete;
        downloadQueue.clear();
        currentIndex = 0;

        Architect.LOGGER.info("[DOWNLOAD START] timestamp={} - Starting download of {} buildings",
            downloadStartTime, totalBuildings);

        // Notify players
        broadcastMessage("§e[STRUTTURA]§f Downloading " + totalBuildings + " buildings...");

        BuildingCache cache = BuildingCache.getInstance();

        // Build queue of buildings that need to be downloaded
        for (SpawnableBuilding building : list.getBuildings()) {
            if (cache.contains(building.getRdns())) {
                // Already cached
                downloadedCount.incrementAndGet();
                Architect.LOGGER.debug("Building {} already cached", building.getRdns());
            } else {
                downloadQueue.add(building);
            }
        }

        int alreadyCached = downloadedCount.get();
        if (alreadyCached > 0) {
            Architect.LOGGER.info("Found {} buildings already cached, {} to download",
                alreadyCached, downloadQueue.size());
        }

        // Check if all already cached
        if (downloadQueue.isEmpty()) {
            completeDownload();
            return;
        }

        // Start downloading the first building
        downloadNext();
    }

    /**
     * Downloads the next building in the queue.
     */
    private void downloadNext() {
        if (currentIndex >= downloadQueue.size()) {
            // All done
            completeDownload();
            return;
        }

        SpawnableBuilding building = downloadQueue.get(currentIndex);
        String rdns = building.getRdns();

        Architect.LOGGER.info("Downloading building {}/{}: {}",
            currentIndex + 1, downloadQueue.size(), rdns);

        // Use downloadConstruction instead of pullConstruction to avoid global lock
        ApiClient.downloadConstruction(rdns, response -> {
            if (response.success() && response.construction() != null) {
                BuildingCache.getInstance().put(rdns, response.construction());
                int done = downloadedCount.incrementAndGet();
                Architect.LOGGER.info("Downloaded building {} ({}/{})", rdns, done, totalBuildings);
                broadcastMessage("§7[STRUTTURA]§f Downloaded " + done + "/" + totalBuildings + ": " + rdns);
            } else {
                failedCount.incrementAndGet();
                downloadedCount.incrementAndGet(); // Count as done even if failed
                Architect.LOGGER.warn("Failed to download building {}: {}", rdns, response.message());
                broadcastMessage("§c[STRUTTURA]§f Failed to download: " + rdns);
            }

            // Move to next building
            currentIndex++;
            downloadNext();
        });
    }

    /**
     * Marks the download as complete and triggers the callback.
     */
    private void completeDownload() {
        downloadEndTime = System.currentTimeMillis();
        long duration = downloadEndTime - downloadStartTime;

        int failed = failedCount.get();
        if (failed > 0) {
            state = DownloadState.READY; // Still mark as ready, some buildings may fail
            Architect.LOGGER.warn("[DOWNLOAD END] timestamp={} - Download completed with {} failures ({}/{} buildings) in {}ms",
                downloadEndTime, failed, downloadedCount.get() - failed, totalBuildings, duration);
            broadcastMessage("§e[STRUTTURA]§f Download complete with " + failed + " failures (" + duration + "ms)");
        } else {
            state = DownloadState.READY;
            Architect.LOGGER.info("[DOWNLOAD END] timestamp={} - All {} buildings downloaded successfully in {}ms",
                downloadEndTime, totalBuildings, duration);
            broadcastMessage("§a[STRUTTURA]§f All " + totalBuildings + " buildings downloaded! (" + duration + "ms)");
        }
        broadcastMessage("§a[STRUTTURA]§f Spawner is now active!");

        // Trigger callback
        if (onCompleteCallback != null) {
            onCompleteCallback.run();
            onCompleteCallback = null;
        }
    }

    /**
     * Gets the current download state.
     */
    public DownloadState getState() {
        return state;
    }

    /**
     * Checks if the spawner is ready to start (all downloads complete).
     */
    public boolean isReady() {
        return state == DownloadState.READY;
    }

    /**
     * Checks if a download is currently in progress.
     */
    public boolean isDownloading() {
        return state == DownloadState.DOWNLOADING;
    }

    /**
     * Gets the timestamp when download started.
     */
    public long getDownloadStartTime() {
        return downloadStartTime;
    }

    /**
     * Gets the timestamp when download ended.
     */
    public long getDownloadEndTime() {
        return downloadEndTime;
    }

    /**
     * Gets the download progress as a percentage (0.0 - 1.0).
     */
    public double getProgress() {
        if (totalBuildings == 0) {
            return 1.0;
        }
        return (double) downloadedCount.get() / totalBuildings;
    }

    /**
     * Gets the number of buildings downloaded so far.
     */
    public int getDownloadedCount() {
        return downloadedCount.get();
    }

    /**
     * Gets the total number of buildings to download.
     */
    public int getTotalBuildings() {
        return totalBuildings;
    }

    /**
     * Resets the downloader state.
     * Called when world unloads or InGame is reset.
     */
    public void reset() {
        state = DownloadState.IDLE;
        downloadStartTime = 0;
        downloadEndTime = 0;
        totalBuildings = 0;
        downloadedCount.set(0);
        failedCount.set(0);
        onCompleteCallback = null;
        downloadQueue.clear();
        currentIndex = 0;
        server = null;
        Architect.LOGGER.debug("BuildingDownloader reset");
    }

    /**
     * Marks the downloader as ready without actually downloading anything.
     * Used when reloading a world where downloads were already completed.
     * Buildings will be downloaded on-demand from cache when needed.
     */
    public void markReady() {
        state = DownloadState.READY;
        Architect.LOGGER.info("BuildingDownloader marked as ready (downloads were completed in previous session)");
    }

    /**
     * Broadcasts a message to all players on the server.
     */
    private void broadcastMessage(String message) {
        if (server == null) {
            return;
        }
        server.execute(() -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.sendSystemMessage(Component.literal(message));
            }
        });
    }
}
