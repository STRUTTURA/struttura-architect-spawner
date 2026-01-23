package it.magius.struttura.architect.ingame.spawn;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.api.ApiClient;
import it.magius.struttura.architect.ingame.ChunkDataManager;
import it.magius.struttura.architect.ingame.cache.BuildingCache;
import it.magius.struttura.architect.ingame.model.SpawnableBuilding;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.placement.ConstructionOperations;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

/**
 * Handles the actual spawning of buildings in the world.
 * Downloads building data if not cached, places blocks, and updates chunk data.
 */
public class InGameBuildingSpawner {

    /**
     * Spawns a building at the specified position.
     * If not in cache, downloads on-demand (for world reload scenarios).
     *
     * @param level the server level
     * @param chunk the chunk where the building is being spawned
     * @param building the building to spawn
     * @param position the spawn position and rotation
     */
    public static void spawn(ServerLevel level, LevelChunk chunk, SpawnableBuilding building, SpawnPosition position) {
        String rdns = building.getRdns();

        // Get building from cache
        Construction cachedConstruction = BuildingCache.getInstance().get(rdns);

        if (cachedConstruction != null) {
            doSpawn(level, chunk, building, cachedConstruction, position);
        } else {
            // Not in cache - download on-demand (happens on world reload when cache was cleared)
            ApiClient.downloadConstruction(rdns, response -> {
                if (response.success() && response.construction() != null) {
                    BuildingCache.getInstance().put(rdns, response.construction());
                    // Execute spawn on main thread
                    level.getServer().execute(() -> {
                        // Verify chunk is still loaded
                        LevelChunk reloadedChunk = level.getChunkSource().getChunkNow(
                            chunk.getPos().x, chunk.getPos().z);
                        if (reloadedChunk != null) {
                            doSpawn(level, reloadedChunk, building, response.construction(), position);
                        }
                    });
                } else {
                    Architect.LOGGER.error("Failed to download building {} on-demand: {}",
                        rdns, response.message());
                }
            });
        }
    }

    /**
     * Performs the actual spawn operation using architectSpawn for full functionality
     * (rooms, entities, unfreeze).
     */
    private static void doSpawn(ServerLevel level, LevelChunk chunk, SpawnableBuilding building,
                                Construction construction, SpawnPosition position) {

        BlockPos entrancePos = position.blockPos();  // This is where the entrance should be placed
        int rotationDegrees = position.rotation();

        // Calculate seed for room selection based on world seed + spawn position
        long roomSeed = level.getSeed()
            ^ (long)(entrancePos.getX() * 1000)
            ^ (long)(entrancePos.getZ() * 1000);

        // Use architectSpawn with specific spawn point (InGame mode)
        var result = ConstructionOperations.architectSpawn(
            level,
            construction,
            rotationDegrees,        // yaw in degrees
            null,                   // No forced rooms
            entrancePos,            // Specific spawn point (entrance position)
            roomSeed,               // Seed for room selection
            null                    // No player - using spawn point instead
        );

        if (result.blocksPlaced() > 0) {
            // Calculate world-space bounds using centralized calculator
            AABB worldBounds = BoundsCalculator.calculate(construction, result.origin(), rotationDegrees);

            // Store building data in chunk
            ChunkDataManager.setBuildingData(
                chunk,
                building.getRdns(),
                building.getPk(),
                worldBounds,
                rotationDegrees
            );

            // Note: OccupiedChunks.markOccupied() is now called BEFORE spawning in SpawnEvaluator
            // to prevent race conditions with other chunks in the queue

            // Increment spawn count
            building.incrementSpawnCount();

            // Calculate remaining spawns
            int remaining = building.getXWorld() == 0 ? -1 : building.getXWorld() - building.getSpawnedCount();
            String remainingStr = remaining < 0 ? "unlimited" : String.valueOf(remaining);

            Architect.LOGGER.info("[InGame] Spawned {} at {} (remaining: {})",
                building.getRdns(), entrancePos.toShortString(), remainingStr);
        }
    }

}
