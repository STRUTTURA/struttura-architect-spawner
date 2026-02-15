package it.magius.struttura.architect.ingame.spawn;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.api.ApiClient;
import it.magius.struttura.architect.ingame.ChunkDataManager;
import it.magius.struttura.architect.ingame.cache.BuildingCache;
import it.magius.struttura.architect.ingame.model.EnsureBoundsMode;
import it.magius.struttura.architect.ingame.model.PositionType;
import it.magius.struttura.architect.ingame.model.SpawnRule;
import it.magius.struttura.architect.ingame.model.SpawnableBuilding;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.model.ConstructionSnapshot;
import it.magius.struttura.architect.placement.ConstructionOperations;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import java.util.List;

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
                    if (response.snapshot() != null) {
                        cache.putSnapshot(rdns, response.snapshot());
                    }

                    // Execute spawn on main thread
                    level.getServer().execute(() -> {
                        // Verify chunk is still loaded
                        LevelChunk reloadedChunk = level.getChunkSource().getChunkNow(
                            chunk.getPos().x, chunk.getPos().z);
                        if (reloadedChunk != null) {
                            doSpawn(level, reloadedChunk, building, response.construction(), position);
                        } else {
                            // Chunk was unloaded during download - spawn skipped, chunk remains occupied
                            Architect.LOGGER.warn("Chunk unloaded during download, spawn skipped for {}", rdns);
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

        // Pre-clear bounds based on ensureBoundsMode
        SpawnRule rule = position.rule();
        if (rule != null && rule.getEnsureBoundsMode() != EnsureBoundsMode.NONE) {
            AABB clearBounds = BoundsCalculator.calculate(building, position);
            boolean useWater = rule.getType() == PositionType.BOTTOM_WATER ||
                               rule.getType() == PositionType.ON_WATER;

            if (rule.getEnsureBoundsMode() == EnsureBoundsMode.ABOVE_ENTRANCE) {
                // Clear only from entranceY+1 upwards within the building bounds
                // entrancePos is already the world-space position of the entrance anchor
                int newMinY = entrancePos.getY() + 1;
                if (newMinY <= clearBounds.maxY) {
                    clearBounds = new AABB(clearBounds.minX, newMinY, clearBounds.minZ,
                                           clearBounds.maxX, clearBounds.maxY, clearBounds.maxZ);
                    preClearBounds(level, clearBounds, useWater);
                }
            } else {
                // ALL: clear entire spawn area
                preClearBounds(level, clearBounds, useWater);
            }
        }

        // Calculate seed for room selection based on world seed + spawn position
        long roomSeed = level.getSeed()
            ^ (long)(entrancePos.getX() * 1000)
            ^ (long)(entrancePos.getZ() * 1000);

        // Get snapshot from cache for placement data
        ConstructionSnapshot snapshot = BuildingCache.getInstance().getSnapshot(building.getRdns());
        if (snapshot == null) {
            Architect.LOGGER.error("No snapshot found in cache for building {}, cannot spawn", building.getRdns());
            return;
        }

        // Use architectSpawn with specific spawn point (InGame mode)
        var result = ConstructionOperations.architectSpawn(
            level,
            construction,
            snapshot,
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

    /**
     * Clears all blocks and entities in the specified bounds.
     * Used when ensureBounds is enabled to prepare terrain before placing a building.
     * Blocks are replaced with air or water (depending on useWater), entities are discarded without death loot.
     *
     * @param level the server level
     * @param bounds the world-space bounding box to clear
     * @param useWater if true, fill with water instead of air (for water positions)
     */
    private static void preClearBounds(ServerLevel level, AABB bounds, boolean useWater) {
        // Silent removal flags - UPDATE_SUPPRESS_DROPS prevents block drops (seeds, saplings, etc.)
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS;

        int minX = (int) Math.floor(bounds.minX);
        int minY = (int) Math.floor(bounds.minY);
        int minZ = (int) Math.floor(bounds.minZ);
        int maxX = (int) Math.floor(bounds.maxX);
        int maxY = (int) Math.floor(bounds.maxY);
        int maxZ = (int) Math.floor(bounds.maxZ);

        // Phase 1: Remove all entities in the area (except players)
        // This prevents mobs from dying and dropping loot when blocks are placed on them
        List<Entity> entitiesToRemove = level.getEntitiesOfClass(Entity.class, bounds,
            e -> !(e instanceof Player));

        int entitiesRemoved = 0;
        for (Entity entity : entitiesToRemove) {
            entity.discard();  // Remove without death effects or drops
            entitiesRemoved++;
        }

        // Phase 2: Clear all blocks without drops
        // IMPORTANT: Iterate from TOP to BOTTOM (Y descending) to remove plants/grass
        // before removing the ground blocks they sit on. This prevents plants from
        // breaking naturally and dropping items when their support block is removed.
        int clearedCount = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState fillBlock = useWater ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();

        for (int y = maxY; y >= minY; y--) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    pos.set(x, y, z);
                    BlockState currentState = level.getBlockState(pos);
                    // Replace if not already the target block type
                    if (currentState.getBlock() != fillBlock.getBlock()) {
                        level.setBlock(pos, fillBlock, flags);
                        clearedCount++;
                    }
                }
            }
        }

        if (entitiesRemoved > 0 || clearedCount > 0) {
            Architect.LOGGER.debug("Pre-clear: {} {} blocks, {} entities",
                useWater ? "flooded" : "cleared", clearedCount, entitiesRemoved);
        }
    }

}
