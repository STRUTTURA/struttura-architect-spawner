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
        BuildingCache cache = BuildingCache.getInstance();

        // Get building from cache
        Construction cachedConstruction = cache.get(rdns);

        if (cachedConstruction != null) {
            doSpawn(level, chunk, building, cachedConstruction, position);
        } else {
            // Check if already downloading - if so, re-queue this spawn for later
            if (cache.isDownloading(rdns)) {
                return;
            }

            // Mark as downloading to prevent duplicate download requests
            if (!cache.markDownloading(rdns)) {
                return;
            }

            // Not in cache - download on-demand (happens on world reload when cache was cleared)
            String hash = building.getHash();

            ApiClient.downloadConstruction(rdns, response -> {
                // Clear downloading flag regardless of success/failure
                cache.clearDownloading(rdns);

                if (response.success() && response.construction() != null) {
                    cache.put(rdns, response.construction(), hash);

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
                    // Apply 20% spawn penalty for download failure
                    level.getServer().execute(() -> {
                        building.markDownloadFailed();
                    });
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

        // Apply bounds from SpawnableBuilding metadata to the Construction
        // The NBT data doesn't include bounds, they come from the list export
        AABB buildingBounds = building.getBounds();
        construction.getBounds().set(
            (int) buildingBounds.minX, (int) buildingBounds.minY, (int) buildingBounds.minZ,
            (int) buildingBounds.maxX, (int) buildingBounds.maxY, (int) buildingBounds.maxZ
        );

        // Apply entrance anchor from SpawnableBuilding metadata
        BlockPos entrance = building.getEntrance();
        if (entrance != null) {
            construction.getAnchors().setEntrance(entrance, building.getEntranceYaw());
        }

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

            // Get localized name (prefer English for storage)
            String buildingName = building.getLocalizedName("en_us");
            String buildingAuthor = building.getAuthor();

            // Store building data in chunk (including name/author for offline display)
            ChunkDataManager.setBuildingData(
                chunk,
                building.getRdns(),
                building.getPk(),
                worldBounds,
                rotationDegrees,
                buildingName,
                buildingAuthor
            );

            // Note: OccupiedChunks.markOccupied() is now called BEFORE spawning in SpawnEvaluator
            // to prevent race conditions with other chunks in the queue

            // Increment spawn count
            building.incrementSpawnCount();
        }
    }

}
