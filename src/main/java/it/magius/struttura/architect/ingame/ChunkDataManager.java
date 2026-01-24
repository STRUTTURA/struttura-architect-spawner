package it.magius.struttura.architect.ingame;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.ingame.model.SpawnedBuildingInfo;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

/**
 * Manages persistent data stored in chunks for the InGame spawner system.
 * Uses Fabric API's data attachment system for persistence.
 *
 * Data stored per chunk:
 * - processed (boolean) - whether this chunk has been evaluated by the spawner
 * - building info - spawned building data if present (rdns, pk, bounds, rotation)
 */
public class ChunkDataManager {

    /**
     * Checks if a chunk has been processed by the spawner system.
     * @param chunk the chunk to check
     * @return true if already processed
     */
    public static boolean isChunkProcessed(LevelChunk chunk) {
        ModAttachments.ChunkSpawnData data = chunk.getAttached(ModAttachments.CHUNK_SPAWN_DATA);
        return data != null && data.processed();
    }

    /**
     * Marks a chunk as processed by the spawner system.
     * @param chunk the chunk to mark
     */
    public static void markChunkProcessed(LevelChunk chunk) {
        chunk.setAttached(ModAttachments.CHUNK_SPAWN_DATA, ModAttachments.ChunkSpawnData.processedOnly());
        // Fabric Attachment API automatically marks chunk as needing save
    }

    /**
     * Gets the spawned building info for a chunk, if any.
     * @param chunk the chunk to query
     * @return the building info, or null if no building was spawned in this chunk
     */
    public static SpawnedBuildingInfo getBuildingData(LevelChunk chunk) {
        ModAttachments.ChunkSpawnData data = chunk.getAttached(ModAttachments.CHUNK_SPAWN_DATA);
        if (data == null || !data.hasBuilding()) {
            return null;
        }

        AABB bounds = new AABB(
            data.minX(), data.minY(), data.minZ(),
            data.maxX(), data.maxY(), data.maxZ()
        );

        return new SpawnedBuildingInfo(
            data.buildingRdns(), data.buildingPk(), bounds, data.rotation(),
            data.buildingName(), data.buildingAuthor()
        );
    }

    /**
     * Sets the building data for a chunk after spawning.
     * @param chunk the chunk where the building was spawned
     * @param rdns the building's RDNS identifier
     * @param pk the building's primary key
     * @param bounds the world-space bounding box
     * @param rotation the rotation applied (0, 90, 180, 270)
     * @param name the building's localized name at spawn time
     * @param author the building's author nickname
     */
    public static void setBuildingData(LevelChunk chunk, String rdns, long pk, AABB bounds, int rotation,
                                       String name, String author) {
        ModAttachments.ChunkSpawnData data = ModAttachments.ChunkSpawnData.withBuilding(
            rdns, pk, rotation,
            bounds.minX, bounds.minY, bounds.minZ,
            bounds.maxX, bounds.maxY, bounds.maxZ,
            name, author
        );
        chunk.setAttached(ModAttachments.CHUNK_SPAWN_DATA, data);
        // Fabric Attachment API automatically marks chunk as needing save
    }

    /**
     * Clears all spawner data from a chunk.
     * @param chunk the chunk to clear
     */
    public static void clearChunkData(LevelChunk chunk) {
        chunk.removeAttached(ModAttachments.CHUNK_SPAWN_DATA);
        // Fabric Attachment API automatically marks chunk as needing save
    }
}
