package it.magius.struttura.architect.ingame.spawn;

import net.minecraft.world.phys.AABB;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks chunks that are occupied by spawned buildings during this session.
 * This is non-persistent (memory only) to prevent multiple buildings
 * from spawning in overlapping chunks during the same session.
 */
public class OccupiedChunks {

    private static final Set<Long> occupiedChunks = ConcurrentHashMap.newKeySet();

    /**
     * Marks all chunks covered by the given world bounds as occupied.
     * @param bounds the world-space bounding box of the building
     */
    public static void markOccupied(AABB bounds) {
        int minChunkX = (int) Math.floor(bounds.minX) >> 4;
        int maxChunkX = (int) Math.floor(bounds.maxX) >> 4;
        int minChunkZ = (int) Math.floor(bounds.minZ) >> 4;
        int maxChunkZ = (int) Math.floor(bounds.maxZ) >> 4;

        int added = 0;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (occupiedChunks.add(chunkKey(cx, cz))) {
                    added++;
                }
            }
        }
    }

    /**
     * Checks if a chunk is occupied by a building.
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return true if the chunk is occupied
     */
    public static boolean isOccupied(int chunkX, int chunkZ) {
        return occupiedChunks.contains(chunkKey(chunkX, chunkZ));
    }

    /**
     * Checks if ANY chunk covered by the given bounds is already occupied.
     * This prevents buildings from overlapping even if their anchor chunks are different.
     * @param bounds the world-space bounding box to check
     * @return true if any chunk in the bounds is already occupied
     */
    public static boolean isAnyOccupied(AABB bounds) {
        int minChunkX = (int) Math.floor(bounds.minX) >> 4;
        int maxChunkX = (int) Math.floor(bounds.maxX) >> 4;
        int minChunkZ = (int) Math.floor(bounds.minZ) >> 4;
        int maxChunkZ = (int) Math.floor(bounds.maxZ) >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (occupiedChunks.contains(chunkKey(cx, cz))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Clears all occupied chunks. Called when world unloads.
     */
    public static void clear() {
        occupiedChunks.clear();
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
