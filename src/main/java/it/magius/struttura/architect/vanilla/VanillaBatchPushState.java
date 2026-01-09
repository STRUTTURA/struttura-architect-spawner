package it.magius.struttura.architect.vanilla;

import it.magius.struttura.architect.model.Construction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Tracks the state of a vanilla batch push operation.
 * This is needed because the screenshot process is asynchronous (server -> client -> server).
 *
 * New flow:
 * 1. LOADING_ALL: Load all constructions into registry (not spawned in world)
 * 2. PROCESSING: For each construction:
 *    - Spawn in world
 *    - Request screenshot (wait for client response)
 *    - On screenshot received: destroy, unregister, queue async upload, continue to next
 * 3. Async uploads run in background; if any fails, abort the current iteration
 */
public class VanillaBatchPushState {

    // Active batch push states by player UUID
    private static final Map<UUID, VanillaBatchPushState> activeStates = new ConcurrentHashMap<>();

    private final ServerPlayer player;
    private final ServerLevel level;
    private final List<VanillaStructureLoader.VanillaStructureInfo> structures;
    private int currentIndex;
    private int successCount;
    private int failCount;

    // Loaded constructions (phase 1 result)
    private final List<LoadedConstruction> loadedConstructions = new ArrayList<>();

    // Current structure being processed (phase 2)
    private Construction currentConstruction;
    private BlockPos spawnPosition;
    private String currentConstructionId;

    // Track async upload errors
    private final AtomicBoolean uploadError = new AtomicBoolean(false);
    private volatile String uploadErrorMessage = null;

    // State machine
    public enum State {
        IDLE,
        LOADING_ALL,        // Phase 1: loading all constructions
        PROCESSING,         // Phase 2: spawn -> screenshot -> destroy loop
        WAITING_SCREENSHOT, // Waiting for client screenshot response
        COMPLETED,
        FAILED
    }

    private State state = State.IDLE;

    // Callback for when batch is complete
    private Consumer<VanillaBatchPushState> onComplete;

    /**
     * Holds a loaded construction with its metadata.
     */
    public record LoadedConstruction(
        VanillaStructureLoader.VanillaStructureInfo info,
        Construction construction,
        String screenshotTitle
    ) {}

    public VanillaBatchPushState(ServerPlayer player, ServerLevel level,
                                  List<VanillaStructureLoader.VanillaStructureInfo> structures) {
        this.player = player;
        this.level = level;
        this.structures = structures;
        this.currentIndex = 0;
        this.successCount = 0;
        this.failCount = 0;
    }

    /**
     * Starts or resumes the batch push state for a player.
     */
    public static VanillaBatchPushState start(ServerPlayer player, ServerLevel level,
                                               List<VanillaStructureLoader.VanillaStructureInfo> structures) {
        VanillaBatchPushState state = new VanillaBatchPushState(player, level, structures);
        activeStates.put(player.getUUID(), state);
        return state;
    }

    /**
     * Gets the active batch push state for a player, if any.
     */
    public static VanillaBatchPushState getState(ServerPlayer player) {
        return activeStates.get(player.getUUID());
    }

    /**
     * Gets the active batch push state for a player UUID, if any.
     */
    public static VanillaBatchPushState getState(UUID playerUuid) {
        return activeStates.get(playerUuid);
    }

    /**
     * Checks if a player has an active batch push state.
     */
    public static boolean hasActiveState(ServerPlayer player) {
        return activeStates.containsKey(player.getUUID());
    }

    /**
     * Removes the active state for a player.
     */
    public static void removeState(ServerPlayer player) {
        activeStates.remove(player.getUUID());
    }

    /**
     * Removes the active state for a player UUID.
     */
    public static void removeState(UUID playerUuid) {
        activeStates.remove(playerUuid);
    }

    // Getters and setters

    public ServerPlayer getPlayer() {
        return player;
    }

    public ServerLevel getLevel() {
        return level;
    }

    public List<VanillaStructureLoader.VanillaStructureInfo> getStructures() {
        return structures;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public void setCurrentIndex(int currentIndex) {
        this.currentIndex = currentIndex;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public void incrementSuccessCount() {
        this.successCount++;
    }

    public int getFailCount() {
        return failCount;
    }

    public void incrementFailCount() {
        this.failCount++;
    }

    public Construction getCurrentConstruction() {
        return currentConstruction;
    }

    public void setCurrentConstruction(Construction currentConstruction) {
        this.currentConstruction = currentConstruction;
    }

    public BlockPos getSpawnPosition() {
        return spawnPosition;
    }

    public void setSpawnPosition(BlockPos spawnPosition) {
        this.spawnPosition = spawnPosition;
    }

    public String getCurrentConstructionId() {
        return currentConstructionId;
    }

    public void setCurrentConstructionId(String currentConstructionId) {
        this.currentConstructionId = currentConstructionId;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public int getTotalCount() {
        return structures.size();
    }

    public boolean isComplete() {
        return currentIndex >= loadedConstructions.size();
    }

    public VanillaStructureLoader.VanillaStructureInfo getCurrentStructureInfo() {
        if (currentIndex < structures.size()) {
            return structures.get(currentIndex);
        }
        return null;
    }

    public Consumer<VanillaBatchPushState> getOnComplete() {
        return onComplete;
    }

    public void setOnComplete(Consumer<VanillaBatchPushState> onComplete) {
        this.onComplete = onComplete;
    }

    // New methods for the two-phase approach

    public List<LoadedConstruction> getLoadedConstructions() {
        return loadedConstructions;
    }

    public void addLoadedConstruction(LoadedConstruction loaded) {
        loadedConstructions.add(loaded);
    }

    public LoadedConstruction getCurrentLoadedConstruction() {
        if (currentIndex < loadedConstructions.size()) {
            return loadedConstructions.get(currentIndex);
        }
        return null;
    }

    public int getLoadedCount() {
        return loadedConstructions.size();
    }

    /**
     * Marks an upload error. This will cause the batch to abort.
     */
    public void setUploadError(String message) {
        uploadError.set(true);
        uploadErrorMessage = message;
    }

    /**
     * Checks if an upload error occurred.
     */
    public boolean hasUploadError() {
        return uploadError.get();
    }

    /**
     * Gets the upload error message.
     */
    public String getUploadErrorMessage() {
        return uploadErrorMessage;
    }

    /**
     * Advances to the next structure in the list.
     */
    public void nextStructure() {
        currentIndex++;
        currentConstruction = null;
        spawnPosition = null;
        currentConstructionId = null;
    }

    /**
     * Marks the batch as complete and removes it from active states.
     */
    public void complete() {
        state = State.COMPLETED;
        removeState(player.getUUID());
        if (onComplete != null) {
            onComplete.accept(this);
        }
    }

    /**
     * Marks the batch as failed and removes it from active states.
     */
    public void fail() {
        state = State.FAILED;
        removeState(player.getUUID());
        if (onComplete != null) {
            onComplete.accept(this);
        }
    }
}
