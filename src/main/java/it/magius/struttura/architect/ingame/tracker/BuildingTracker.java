package it.magius.struttura.architect.ingame.tracker;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.ingame.ChunkDataManager;
import it.magius.struttura.architect.ingame.InGameManager;
import it.magius.struttura.architect.ingame.model.SpawnedBuildingInfo;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Tick handler that tracks player proximity to spawned buildings.
 * Checks every second (20 ticks) if players have entered or exited buildings.
 */
public class BuildingTracker {

    private static final int CHECK_INTERVAL_TICKS = 20;  // Check every 1 second

    private static BuildingTracker instance;
    private int tickCounter = 0;

    private BuildingTracker() {}

    public static BuildingTracker getInstance() {
        if (instance == null) {
            instance = new BuildingTracker();
        }
        return instance;
    }

    /**
     * Registers the building tracker.
     * Call this during mod initialization.
     */
    public void register() {
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        Architect.LOGGER.info("Building tracker registered");
    }

    private void onServerTick(MinecraftServer server) {
        tickCounter++;
        if (tickCounter % CHECK_INTERVAL_TICKS != 0) {
            return;
        }

        // Check if InGame mode is active AND spawner is ready
        InGameManager manager = InGameManager.getInstance();
        if (!manager.isSpawnerReady()) {
            return;
        }

        // Check each player's proximity to buildings
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                checkPlayerProximity(player);
            } catch (Exception e) {
                Architect.LOGGER.error("Error checking player proximity", e);
            }
        }
    }

    /**
     * Checks if a player is inside a spawned building.
     */
    private void checkPlayerProximity(ServerPlayer player) {
        // Only check overworld for now
        if (player.level().dimension() != Level.OVERWORLD) {
            // Clear state if player left the dimension
            if (PlayerBuildingState.getInstance().isPlayerInBuilding(player)) {
                PlayerBuildingState.getInstance().onPlayerExitBuilding(player);
            }
            return;
        }

        ServerLevel level = (ServerLevel) player.level();
        ChunkPos playerChunk = player.chunkPosition();

        // Check current chunk and 8 adjacent chunks (3x3 grid)
        SpawnedBuildingInfo currentBuilding = null;

        for (int dx = -1; dx <= 1 && currentBuilding == null; dx++) {
            for (int dz = -1; dz <= 1 && currentBuilding == null; dz++) {
                int chunkX = playerChunk.x + dx;
                int chunkZ = playerChunk.z + dz;

                // Only check if chunk is loaded - use getChunkSource to avoid loading
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                SpawnedBuildingInfo info = ChunkDataManager.getBuildingData(chunk);

                if (info != null && isPlayerInBounds(player, info)) {
                    currentBuilding = info;
                }
            }
        }

        // Update player state
        PlayerBuildingState state = PlayerBuildingState.getInstance();
        SpawnedBuildingInfo previousBuilding = state.getCurrentBuilding(player);

        if (currentBuilding != null) {
            // Player is in a building
            if (previousBuilding == null || !previousBuilding.rdns().equals(currentBuilding.rdns())) {
                state.onPlayerEnterBuilding(player, currentBuilding);
            }
        } else {
            // Player is not in any building
            if (previousBuilding != null) {
                state.onPlayerExitBuilding(player);
            }
        }
    }

    /**
     * Checks if a player's position is within a building's bounds.
     */
    private boolean isPlayerInBounds(ServerPlayer player, SpawnedBuildingInfo info) {
        return info.contains(player.getX(), player.getY(), player.getZ());
    }
}
