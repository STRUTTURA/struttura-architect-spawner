package it.magius.struttura.architect.ingame.cache;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.ChatMessages;
import it.magius.struttura.architect.api.ApiClient;
import it.magius.struttura.architect.ingame.model.SpawnableBuilding;
import it.magius.struttura.architect.ingame.model.SpawnableList;
import net.minecraft.server.MinecraftServer;

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
            return;
        }

        if (list == null || !list.hasBuildings()) {
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

        // Notify players
        ChatMessages.broadcastRaw(server, ChatMessages.Level.INFO, "Downloading " + totalBuildings + " buildings...");

        BuildingCache cache = BuildingCache.getInstance();

        // Build queue of buildings that need to be downloaded
        for (SpawnableBuilding building : list.getBuildings()) {
            if (cache.contains(building.getRdns())) {
                // Already cached
                downloadedCount.incrementAndGet();
            } else {
                downloadQueue.add(building);
            }
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

        // Use downloadConstruction instead of pullConstruction to avoid global lock
        String hash = building.getHash();
        ApiClient.downloadConstruction(rdns, response -> {
            if (response.success() && response.construction() != null) {
                BuildingCache.getInstance().put(rdns, response.construction(), hash);
                int done = downloadedCount.incrementAndGet();
                ChatMessages.broadcastRaw(server, ChatMessages.Level.INFO, "Downloaded " + done + "/" + totalBuildings + ": " + rdns);
            } else {
                failedCount.incrementAndGet();
                downloadedCount.incrementAndGet(); // Count as done even if failed
                Architect.LOGGER.warn("Failed to download building {}: {}", rdns, response.message());
                ChatMessages.broadcastRaw(server, ChatMessages.Level.ERROR, "Failed to download: " + rdns);
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
        state = DownloadState.READY;

        if (failed > 0) {
            ChatMessages.broadcastRaw(server, ChatMessages.Level.ERROR, "Download complete with " + failed + " failures (" + duration + "ms)");
        } else {
            ChatMessages.broadcastRaw(server, ChatMessages.Level.INFO, "All " + totalBuildings + " buildings downloaded! (" + duration + "ms)");
        }
        ChatMessages.broadcastRaw(server, ChatMessages.Level.INFO, "Spawner is now active!");

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
    }

    /**
     * Marks the downloader as ready without actually downloading anything.
     * Used when reloading a world where downloads were already completed.
     * Buildings will be downloaded on-demand from cache when needed.
     */
    public void markReady() {
        state = DownloadState.READY;
    }

}
