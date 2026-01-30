package it.magius.struttura.architect.ingame;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.api.ApiClient;
import it.magius.struttura.architect.config.ArchitectConfig;
import it.magius.struttura.architect.ingame.cache.BuildingCache;
import it.magius.struttura.architect.ingame.cache.BuildingDownloader;
import it.magius.struttura.architect.ingame.cache.NbtCacheStorage;
import it.magius.struttura.architect.ingame.model.InGameListInfo;
import it.magius.struttura.architect.ingame.model.SpawnableBuilding;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central manager for the InGame spawner system.
 * Handles initialization, state management, and provides access to InGame features.
 */
public class InGameManager {

    private static InGameManager instance;

    private InGameStorage storage;
    private SpawnableListStorage listStorage;
    private NbtCacheStorage nbtCacheStorage;
    private MinecraftServer server;
    private boolean worldLoaded = false;

    // Cached lists for showing to players
    private List<InGameListInfo> cachedLists = null;
    private boolean listsLoading = false;
    private boolean connectionError = false;
    private long cachedUserId = 0;  // Current user ID from API (for ownership checks)

    // Track which players have been shown the setup screen
    private final ConcurrentHashMap<UUID, Boolean> playerSetupPending = new ConcurrentHashMap<>();

    // Spawner activation timestamp (when download completes and spawner starts working)
    private long spawnerActivationTime = 0;

    // List refresh state
    private boolean listRefreshInProgress = false;
    private long lastRefreshCheckTime = 0;
    private static final long REFRESH_CHECK_INTERVAL_TICKS = 20 * 60; // Check every minute if refresh is needed

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

        // Initialize list storage
        listStorage = new SpawnableListStorage(worldPath);

        // Initialize NBT cache storage
        nbtCacheStorage = new NbtCacheStorage(worldPath);

        worldLoaded = true;

        // Initialize LikeManager
        String worldSeed = String.valueOf(server.overworld().getSeed());
        LikeManager.getInstance().init(worldPath, worldSeed);

        // InGame system initialized

        // Check for auto-initialization (dedicated server config)
        checkAutoInitialize();

        // If already initialized and active, try to load the spawnable list from disk
        if (storage.isActive()) {
            loadSpawnableListFromDisk();
        }

        // If not initialized, pre-fetch available lists
        if (!storage.isInitialized()) {
            fetchAvailableLists();
        }
    }

    /**
     * Loads the spawnable list from disk storage.
     * Called on world load when InGame is already active.
     */
    private void loadSpawnableListFromDisk() {
        if (listStorage == null) {
            return;
        }

        SpawnableList list = listStorage.load();
        if (list != null) {
            // Load NBT cache from disk (only entries with matching hashes)
            if (nbtCacheStorage != null) {
                Map<String, String> expectedHashes = new HashMap<>();
                for (SpawnableBuilding building : list.getBuildings()) {
                    if (building.getHash() != null) {
                        expectedHashes.put(building.getRdns(), building.getHash());
                    }
                }
                nbtCacheStorage.load(expectedHashes);
            }

            activateSpawnableList(list, false);
        } else {
            // Fallback: try to fetch from API (backwards compatibility for existing worlds)
            String listId = storage.getState().getListId();
            if (listId != null) {
                ApiClient.fetchSpawnableList(listId, getWorldSeed(), response -> {
                    if (response != null && response.success() && response.spawnableList() != null && server != null) {
                        server.execute(() -> {
                            // Update user ID if received
                            if (response.userId() > 0) {
                                cachedUserId = response.userId();
                                storage.getState().setCurrentUserId(cachedUserId);
                            }
                            // Save to disk for future loads
                            listStorage.save(response.spawnableList());
                            activateSpawnableList(response.spawnableList(), false);
                        });
                    }
                });
            }
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

        // Save NBT cache to disk before clearing
        if (nbtCacheStorage != null && BuildingCache.getInstance().size() > 0) {
            nbtCacheStorage.save();
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
        listStorage = null;
        nbtCacheStorage = null;
        server = null;
        cachedLists = null;
        listsLoading = false;
        connectionError = false;
        playerSetupPending.clear();
        spawnerActivationTime = 0;
        listRefreshInProgress = false;
        lastRefreshCheckTime = 0;
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
        String configListId = config.getInGameListId();

        if (configListId != null) {
            // Fetch list from API and initialize
            ApiClient.fetchSpawnableList(configListId, getWorldSeed(), response -> {
                if (response != null && response.success() && response.spawnableList() != null) {
                    server.execute(() -> {
                        // Update user ID if received
                        if (response.userId() > 0) {
                            cachedUserId = response.userId();
                        }
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

        ApiClient.fetchInGameLists(response -> {
            listsLoading = false;
            if (response != null && response.success() && response.lists() != null) {
                connectionError = false;
                cachedLists = response.lists();
                cachedUserId = response.userId();  // Save user ID for ownership checks

                // Send to any players waiting for setup
                if (server != null) {
                    server.execute(this::notifyPendingPlayers);
                }
            } else {
                // Check if this is a connection error (statusCode 0 or exception message)
                if (response == null || response.statusCode() == 0 ||
                    (response.message() != null && response.message().startsWith("Error:"))) {
                    connectionError = true;
                } else {
                    connectionError = false;
                }
                cachedLists = List.of();

                // Still notify players so they see the error message
                if (server != null) {
                    server.execute(this::notifyPendingPlayers);
                }
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
        // The spawnable list is loaded from disk in onWorldLoad
        if (storage.isInitialized()) {
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
     * Uses cached data if available; use sendSetupScreenWithRetry for manual retry.
     */
    public void sendSetupScreen(ServerPlayer player) {
        sendSetupScreenInternal(player, false);
    }

    /**
     * Sends the InGame setup screen to a player, forcing a retry if there was a connection error.
     * Used when user manually triggers /struttura adventure init.
     */
    public void sendSetupScreenWithRetry(ServerPlayer player) {
        sendSetupScreenInternal(player, true);
    }

    private void sendSetupScreenInternal(ServerPlayer player, boolean forceRetry) {
        // If forcing retry, always clear cache and refetch
        // This ensures that if the user changed their API key, we get fresh lists
        if (forceRetry && !listsLoading) {
            cachedLists = null;
            connectionError = false;
        }

        if (cachedLists == null) {
            // Lists not loaded yet, add player to pending and fetch
            playerSetupPending.put(player.getUUID(), true);
            if (!listsLoading) {
                fetchAvailableLists();
            }
            return;
        }

        // Remove from pending
        playerSetupPending.remove(player.getUUID());

        // Determine if this is a new world (not yet initialized)
        boolean isNewWorld = storage != null && !storage.isInitialized();

        // Send packet with connection error status
        InGameListsPacket packet;
        if (connectionError) {
            packet = InGameListsPacket.connectionError(isNewWorld);
        } else {
            packet = InGameListsPacket.fromListInfos(cachedLists, isNewWorld);
        }
        ServerPlayNetworking.send(player, packet);
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
     * @param listId the list ID from the API (can be numeric like "123" or alphanumeric like "most-popular")
     * @param listName the list name for display
     * @param authType how the user authenticated
     */
    public void initialize(String listId, String listName, InGameState.AuthType authType) {
        if (storage == null) {
            Architect.LOGGER.error("Cannot initialize InGame: no storage loaded");
            return;
        }

        // Get world seed
        String worldSeed = server != null ?
            String.valueOf(server.overworld().getSeed()) : "unknown";

        storage.getState().initialize(listId, listName, authType, worldSeed);
        storage.getState().setCurrentUserId(cachedUserId);  // Save user ID for ownership checks
        storage.save();
    }

    /**
     * Gets the current authenticated user ID.
     * @return the user ID, or 0 if anonymous
     */
    public long getCurrentUserId() {
        if (storage != null) {
            return storage.getState().getCurrentUserId();
        }
        return cachedUserId;
    }

    /**
     * Gets the current world seed as a string.
     * @return the world seed, or "unknown" if server is not available
     */
    public String getWorldSeed() {
        return server != null ? String.valueOf(server.overworld().getSeed()) : "unknown";
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
    }

    /**
     * Resets InGame state (allows re-initialization).
     * @return true if reset was successful, false if blocked (list is locked)
     */
    public boolean reset() {
        if (storage == null) {
            Architect.LOGGER.error("Cannot reset InGame: no storage loaded");
            return false;
        }

        // Cannot reset if the list is locked to this world
        if (storage.isListLocked()) {
            Architect.LOGGER.warn("Cannot reset InGame: list is locked to this world");
            return false;
        }

        storage.getState().reset();
        storage.save();

        // Delete persisted spawnable list
        if (listStorage != null) {
            listStorage.delete();
        }

        // Delete NBT cache
        if (nbtCacheStorage != null) {
            nbtCacheStorage.deleteAll();
        }

        // Reset downloader and cache
        BuildingDownloader.getInstance().reset();
        BuildingCache.getInstance().clear();
        SpawnQueue.getInstance().clear();
        spawnerActivationTime = 0;

        // Clear cached lists to force re-fetch
        cachedLists = null;
        listsLoading = false;

        return true;
    }

    /**
     * Checks if the list is locked to this world.
     */
    public boolean isListLocked() {
        return storage != null && storage.isListLocked();
    }

    /**
     * Sets the spawnable list data (downloaded from API).
     * This is called when the user selects a list from the setup screen.
     * The list is saved to disk for future world loads.
     */
    public void setSpawnableList(SpawnableList list) {
        if (storage == null || storage.getState() == null) {
            Architect.LOGGER.error("Cannot set spawnable list: no state loaded");
            return;
        }

        // Save the list to disk for persistence
        if (listStorage != null) {
            listStorage.save(list);
        }

        // Activate the list (this is a fresh selection, so force download)
        activateSpawnableList(list, true);
    }

    /**
     * Activates a spawnable list (either freshly downloaded or loaded from disk).
     * Buildings are downloaded on-demand when spawning, not upfront.
     * @param list the list to activate
     * @param forceDownload ignored (kept for API compatibility)
     */
    private void activateSpawnableList(SpawnableList list, boolean forceDownload) {
        storage.getState().setSpawnableList(list);

        // Mark spawner as ready immediately - buildings will be downloaded on-demand when needed
        BuildingDownloader.getInstance().markReady();
        spawnerActivationTime = System.currentTimeMillis();

        // Notify players
        if (server != null) {
            for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§a[STRUTTURA]§f Spawner activated with " + list.getBuildingCount() + " buildings"));
            }
        }
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

    /**
     * Called every server tick. Handles periodic list refresh checks.
     */
    public void onServerTick(MinecraftServer server) {
        if (!isActive() || !isSpawnerReady()) {
            return;
        }

        // Check if it's time to check for list refresh
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastRefreshCheckTime < REFRESH_CHECK_INTERVAL_TICKS * 50) {
            return;
        }
        lastRefreshCheckTime = currentTime;

        // Check if list needs refresh
        SpawnableList list = getSpawnableList();
        if (list == null) {
            return;
        }

        ArchitectConfig config = ArchitectConfig.getInstance();
        if (list.needsRefreshCheck(config.getListRefreshIntervalMinutes())) {
            checkForListUpdates();
        }
    }

    /**
     * Asynchronously checks for list updates using hash validation.
     * If the list has changed, downloads the new version and invalidates changed NBT cache entries.
     */
    private void checkForListUpdates() {
        if (listRefreshInProgress) {
            return;
        }

        SpawnableList currentList = getSpawnableList();
        if (currentList == null || storage == null) {
            return;
        }

        String listId = storage.getState().getListId();
        if (listId == null) {
            return;
        }

        String currentHash = currentList.getListHash();
        listRefreshInProgress = true;

        ApiClient.fetchSpawnableList(listId, currentHash, getWorldSeed(), response -> {
            if (server != null) {
                server.execute(() -> handleListRefreshResponse(response, currentList));
            } else {
                listRefreshInProgress = false;
            }
        });
    }

    /**
     * Handles the response from a list refresh check.
     */
    private void handleListRefreshResponse(ApiClient.SpawnableListResponse response, SpawnableList currentList) {
        listRefreshInProgress = false;

        if (response == null) {
            // Update download time even on failure to avoid constant retries
            currentList.setDownloadTime(System.currentTimeMillis());
            if (listStorage != null) {
                listStorage.save(currentList);
            }
            return;
        }

        // 204 = no changes, list is up to date
        if (response.statusCode() == 204) {
            currentList.setDownloadTime(System.currentTimeMillis());
            if (listStorage != null) {
                listStorage.save(currentList);
            }
            return;
        }

        // Error response
        if (!response.success()) {
            // Update download time even on error to avoid constant retries
            currentList.setDownloadTime(System.currentTimeMillis());
            if (listStorage != null) {
                listStorage.save(currentList);
            }
            return;
        }

        // New list received - apply updates
        SpawnableList newList = response.spawnableList();
        if (newList == null) {
            return;
        }

        // Update cached user ID from response (in case API key was added/changed)
        if (response.userId() > 0) {
            cachedUserId = response.userId();
            if (storage != null) {
                storage.getState().setCurrentUserId(cachedUserId);
            }
        }

        // Find buildings with changed hashes and invalidate their NBT cache
        invalidateChangedBuildings(currentList, newList);

        // Save and activate the new list
        if (listStorage != null) {
            listStorage.save(newList);
        }

        // Update state with new list (preserves spawn counts from current session)
        storage.getState().setSpawnableList(newList);
    }

    /**
     * Invalidates NBT cache entries for buildings whose hash has changed.
     * Also clears download failure penalties for updated buildings.
     */
    private void invalidateChangedBuildings(SpawnableList oldList, SpawnableList newList) {
        BuildingCache cache = BuildingCache.getInstance();

        // Build a map of old building hashes
        Set<String> invalidatedRdns = new HashSet<>();

        for (SpawnableBuilding newBuilding : newList.getBuildings()) {
            SpawnableBuilding oldBuilding = oldList.getBuildingByRdns(newBuilding.getRdns());

            if (oldBuilding == null) {
                // New building added - no invalidation needed
                continue;
            }

            String oldHash = oldBuilding.getHash();
            String newHash = newBuilding.getHash();

            // If hash changed, invalidate the cached NBT and clear download failure penalty
            if (oldHash != null && newHash != null && !oldHash.equals(newHash)) {
                cache.remove(newBuilding.getRdns());
                invalidatedRdns.add(newBuilding.getRdns());
                // Clear download failure penalty since building was updated
                if (oldBuilding.hasDownloadFailure()) {
                    oldBuilding.clearDownloadFailure();
                }
            }
        }

        // Also remove buildings that no longer exist in the new list
        for (SpawnableBuilding oldBuilding : oldList.getBuildings()) {
            if (newList.getBuildingByRdns(oldBuilding.getRdns()) == null) {
                cache.remove(oldBuilding.getRdns());
                invalidatedRdns.add(oldBuilding.getRdns());
            }
        }
    }
}
