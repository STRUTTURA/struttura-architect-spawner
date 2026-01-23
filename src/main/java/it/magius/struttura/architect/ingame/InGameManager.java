package it.magius.struttura.architect.ingame;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.api.ApiClient;
import it.magius.struttura.architect.config.ArchitectConfig;
import it.magius.struttura.architect.ingame.cache.BuildingCache;
import it.magius.struttura.architect.ingame.cache.BuildingDownloader;
import it.magius.struttura.architect.ingame.model.InGameListInfo;
import it.magius.struttura.architect.ingame.spawn.OccupiedChunks;
import it.magius.struttura.architect.ingame.spawn.SpawnQueue;
import it.magius.struttura.architect.ingame.model.SpawnableList;
import it.magius.struttura.architect.network.InGameListsPacket;
import it.magius.struttura.architect.network.NetworkHandler;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central manager for the InGame spawner system.
 * Handles initialization, state management, and provides access to InGame features.
 */
public class InGameManager {

    private static InGameManager instance;

    private InGameStorage storage;
    private MinecraftServer server;
    private boolean worldLoaded = false;

    // Cached lists for showing to players
    private List<InGameListInfo> cachedLists = null;
    private boolean listsLoading = false;

    // Track which players have been shown the setup screen
    private final ConcurrentHashMap<UUID, Boolean> playerSetupPending = new ConcurrentHashMap<>();

    // Spawner activation timestamp (when download completes and spawner starts working)
    private long spawnerActivationTime = 0;

    private InGameManager() {}

    /**
     * Gets the singleton instance of the InGame manager.
     */
    public static InGameManager getInstance() {
        if (instance == null) {
            instance = new InGameManager();
        }
        return instance;
    }

    /**
     * Initializes the InGame system when a world is loaded.
     * Called from ServerWorldEvents.LOAD.
     */
    public void onWorldLoad(MinecraftServer server, ServerLevel level) {
        // Only initialize once per world load (use overworld as trigger)
        if (level.dimension() != Level.OVERWORLD) {
            return;
        }

        this.server = server;
        Path worldPath = server.getWorldPath(LevelResource.ROOT);

        // Initialize storage
        storage = new InGameStorage(worldPath);
        storage.load();

        worldLoaded = true;

        // Initialize LikeManager
        String worldSeed = String.valueOf(server.overworld().getSeed());
        LikeManager.getInstance().init(worldPath, worldSeed);

        Architect.LOGGER.info("InGame system initialized for world: {}", storage.getState());

        // Check for auto-initialization (dedicated server config)
        checkAutoInitialize();

        // If not initialized, pre-fetch available lists
        if (!storage.isInitialized()) {
            fetchAvailableLists();
        }
    }

    /**
     * Called when the world is unloaded.
     */
    public void onWorldUnload(ServerLevel level) {
        // Only handle overworld unload
        if (level.dimension() != Level.OVERWORLD) {
            return;
        }

        if (storage != null) {
            storage.save();
        }

        // Clear LikeManager
        LikeManager.getInstance().clear();

        // Reset building downloader, cache, spawn queue, and occupied chunks
        BuildingDownloader.getInstance().reset();
        BuildingCache.getInstance().clear();
        SpawnQueue.getInstance().clear();
        OccupiedChunks.clear();

        worldLoaded = false;
        storage = null;
        server = null;
        cachedLists = null;
        listsLoading = false;
        playerSetupPending.clear();
        spawnerActivationTime = 0;

        Architect.LOGGER.info("InGame system unloaded");
    }

    /**
     * Checks if InGame should be auto-initialized from config.
     * Used for dedicated servers with pre-configured list IDs.
     */
    private void checkAutoInitialize() {
        if (storage == null || storage.isInitialized()) {
            return;
        }

        ArchitectConfig config = ArchitectConfig.getInstance();
        Long configListId = config.getInGameListId();

        if (configListId != null) {
            Architect.LOGGER.info("Auto-initializing InGame with config list ID: {}", configListId);
            // Fetch list from API and initialize
            ApiClient.fetchSpawnableList(configListId, response -> {
                if (response != null && response.success() && response.spawnableList() != null) {
                    server.execute(() -> {
                        initialize(configListId, "Config List", InGameState.AuthType.API_KEY);
                        setSpawnableList(response.spawnableList());
                    });
                }
            });
        }
    }

    /**
     * Fetches available InGame lists from the API.
     */
    private void fetchAvailableLists() {
        if (listsLoading) {
            return;
        }
        listsLoading = true;

        Architect.LOGGER.debug("Fetching available InGame lists...");

        ApiClient.fetchInGameLists(response -> {
            listsLoading = false;
            if (response != null && response.success() && response.lists() != null && !response.lists().isEmpty()) {
                cachedLists = response.lists();
                Architect.LOGGER.info("Loaded {} available InGame lists", cachedLists.size());

                // Send to any players waiting for setup
                if (server != null) {
                    server.execute(this::notifyPendingPlayers);
                }
            } else {
                Architect.LOGGER.debug("No InGame lists available");
                cachedLists = List.of();
            }
        });
    }

    /**
     * Called when a player joins the world.
     * Sends setup screen if InGame not initialized.
     */
    public void onPlayerJoin(ServerPlayer player) {
        if (storage == null) {
            return;
        }

        // If already initialized or declined, do nothing
        if (storage.isInitialized()) {
            // If active, load the spawnable list if not already loaded
            if (isActive() && getSpawnableList() == null) {
                Long listId = storage.getState().getListId();
                if (listId != null) {
                    ApiClient.fetchSpawnableList(listId, response -> {
                        if (response != null && response.success() && response.spawnableList() != null && server != null) {
                            server.execute(() -> setSpawnableList(response.spawnableList()));
                        }
                    });
                }
            }
            return;
        }

        // Mark player as needing setup
        playerSetupPending.put(player.getUUID(), true);

        // If lists already loaded, send now
        if (cachedLists != null) {
            sendSetupScreen(player);
        }
        // Otherwise wait for lists to load (notifyPendingPlayers will be called)
    }

    /**
     * Sends the setup screen packet to pending players.
     */
    private void notifyPendingPlayers() {
        if (server == null || cachedLists == null) {
            return;
        }

        for (UUID playerId : playerSetupPending.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                sendSetupScreen(player);
            }
        }
    }

    /**
     * Sends the InGame setup screen to a player.
     */
    public void sendSetupScreen(ServerPlayer player) {
        if (cachedLists == null) {
            // Lists not loaded yet, fetch them first
            if (!listsLoading) {
                fetchAvailableLists();
            }
            return;
        }

        // Remove from pending
        playerSetupPending.remove(player.getUUID());

        // Determine if this is a new world (not yet initialized)
        boolean isNewWorld = storage != null && !storage.isInitialized();

        // Send packet
        InGameListsPacket packet = InGameListsPacket.fromListInfos(cachedLists, isNewWorld);
        ServerPlayNetworking.send(player, packet);

        Architect.LOGGER.debug("Sent InGame setup screen to {} with {} lists",
            player.getName().getString(), cachedLists.size());
    }

    // ===== State accessors =====

    /**
     * Checks if the InGame system is ready (world loaded).
     */
    public boolean isReady() {
        return worldLoaded && storage != null;
    }

    /**
     * Checks if InGame mode has been initialized for the current world.
     */
    public boolean isInitialized() {
        return storage != null && storage.isInitialized();
    }

    /**
     * Checks if InGame mode is active (initialized with a list selected).
     */
    public boolean isActive() {
        return storage != null && storage.isActive();
    }

    /**
     * Gets the current InGame state.
     */
    public InGameState getState() {
        return storage != null ? storage.getState() : null;
    }

    /**
     * Gets the storage handler.
     */
    public InGameStorage getStorage() {
        return storage;
    }

    /**
     * Gets the current server instance.
     */
    public MinecraftServer getServer() {
        return server;
    }

    // ===== State modification =====

    /**
     * Initializes InGame mode with a selected list.
     * @param listId the list ID from the API
     * @param listName the list name for display
     * @param authType how the user authenticated
     */
    public void initialize(long listId, String listName, InGameState.AuthType authType) {
        if (storage == null) {
            Architect.LOGGER.error("Cannot initialize InGame: no storage loaded");
            return;
        }

        // Get world seed
        String worldSeed = server != null ?
            String.valueOf(server.overworld().getSeed()) : "unknown";

        storage.getState().initialize(listId, listName, authType, worldSeed);
        storage.save();

        Architect.LOGGER.info("InGame initialized with list '{}' (ID: {})", listName, listId);
    }

    /**
     * Marks InGame mode as declined.
     */
    public void decline() {
        if (storage == null) {
            Architect.LOGGER.error("Cannot decline InGame: no storage loaded");
            return;
        }

        storage.getState().decline();
        storage.save();

        Architect.LOGGER.info("InGame mode declined by user");
    }

    /**
     * Resets InGame state (allows re-initialization).
     */
    public void reset() {
        if (storage == null) {
            Architect.LOGGER.error("Cannot reset InGame: no storage loaded");
            return;
        }

        storage.getState().reset();
        storage.save();

        // Reset downloader and cache
        BuildingDownloader.getInstance().reset();
        BuildingCache.getInstance().clear();
        SpawnQueue.getInstance().clear();
        spawnerActivationTime = 0;

        // Clear cached lists to force re-fetch
        cachedLists = null;
        listsLoading = false;

        Architect.LOGGER.info("InGame state reset");
    }

    /**
     * Sets the spawnable list data (downloaded from API).
     * If downloads were already completed in a previous session, just marks as ready.
     * Otherwise, triggers the download of all buildings in the list.
     */
    public void setSpawnableList(SpawnableList list) {
        if (storage == null || storage.getState() == null) {
            Architect.LOGGER.error("Cannot set spawnable list: no state loaded");
            return;
        }

        storage.getState().setSpawnableList(list);
        Architect.LOGGER.info("Loaded spawnable list with {} buildings", list.getBuildingCount());

        // Check if downloads were already completed in a previous session
        if (storage.getState().isDownloadsCompleted()) {
            Architect.LOGGER.info("Downloads already completed in previous session, skipping re-download");
            BuildingDownloader.getInstance().markReady();
            spawnerActivationTime = System.currentTimeMillis();
            Architect.LOGGER.info("[SPAWNER ACTIVATED] timestamp={} - Spawner is now active (restored from previous session)",
                spawnerActivationTime);
            return;
        }

        // Start downloading all buildings
        BuildingDownloader downloader = BuildingDownloader.getInstance();
        downloader.startDownload(list, server, () -> {
            // This callback is called when all downloads complete (on any thread)
            if (server != null) {
                server.execute(() -> {
                    // Mark downloads as completed and persist
                    storage.getState().setDownloadsCompleted(true);
                    storage.save();

                    spawnerActivationTime = System.currentTimeMillis();
                    Architect.LOGGER.info("[SPAWNER ACTIVATED] timestamp={} - Spawner is now active, will spawn on NEW chunks only",
                        spawnerActivationTime);
                });
            }
        });
    }

    /**
     * Gets the current spawnable list.
     */
    public SpawnableList getSpawnableList() {
        if (storage == null || storage.getState() == null) {
            return null;
        }
        return storage.getState().getSpawnableList();
    }

    /**
     * Checks if the spawner is ready to spawn buildings.
     * Returns true only when InGame is active AND all buildings have been downloaded.
     */
    public boolean isSpawnerReady() {
        return isActive() && BuildingDownloader.getInstance().isReady();
    }

    /**
     * Checks if buildings are currently being downloaded.
     */
    public boolean isDownloading() {
        return BuildingDownloader.getInstance().isDownloading();
    }

    /**
     * Gets the timestamp when the spawner was activated (all downloads complete).
     */
    public long getSpawnerActivationTime() {
        return spawnerActivationTime;
    }
}
