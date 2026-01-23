package it.magius.struttura.architect.ingame.spawn;

import it.magius.struttura.architect.ingame.model.PositionType;
import it.magius.struttura.architect.ingame.model.SpawnRule;
import it.magius.struttura.architect.ingame.model.SpawnableBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Interface for position validators that determine valid spawn locations.
 * Each implementation handles a specific PositionType.
 */
public interface PositionValidator {

    /**
     * Result of a position search with detailed failure reasons.
     */
    record FindResult(SpawnPosition position, List<String> failureReasons) {
        public static FindResult success(SpawnPosition position) {
            return new FindResult(position, List.of());
        }

        public static FindResult failure(List<String> reasons) {
            return new FindResult(null, reasons);
        }
    }

    /**
     * Attempts to find a valid spawn position within the chunk.
     *
     * @param level the server level
     * @param chunkPos the chunk to search in
     * @param building the building to spawn
     * @param rule the spawn rule with Y constraints
     * @param random seeded random for deterministic selection
     * @return a valid spawn position, or empty if none found
     */
    Optional<SpawnPosition> findPosition(
        ServerLevel level,
        ChunkPos chunkPos,
        SpawnableBuilding building,
        SpawnRule rule,
        Random random
    );

    /**
     * Attempts to find a valid spawn position with detailed failure information.
     * Default implementation wraps findPosition() with no details.
     */
    default FindResult findPositionWithDetails(
        ServerLevel level,
        ChunkPos chunkPos,
        SpawnableBuilding building,
        SpawnRule rule,
        Random random
    ) {
        Optional<SpawnPosition> result = findPosition(level, chunkPos, building, rule, random);
        if (result.isPresent()) {
            return FindResult.success(result.get());
        } else {
            return FindResult.failure(List.of("No valid position found (no details available)"));
        }
    }

    /**
     * Gets the position type this validator handles.
     */
    PositionType getPositionType();

    /**
     * Gets the appropriate validator for a position type.
     */
    static PositionValidator forType(PositionType type) {
        return switch (type) {
            case ON_GROUND -> OnGroundValidator.INSTANCE;
            case ON_AIR -> OnAirValidator.INSTANCE;
            case OVER_WATER -> OverWaterValidator.INSTANCE;
            case OVER_LAVA -> OverLavaValidator.INSTANCE;
            case ON_WATER -> OnWaterValidator.INSTANCE;
            case UNDER_GROUND -> UnderGroundValidator.INSTANCE;
            case BOTTOM_WATER -> BottomWaterValidator.INSTANCE;
        };
    }

    /**
     * Checks if all chunks that a building would occupy are loaded.
     * Buildings can span multiple chunks as long as all required chunks are loaded.
     *
     * @param level the server level
     * @param origin the building origin position
     * @param effectiveWidth the building width after rotation
     * @param effectiveDepth the building depth after rotation
     * @param margin the margin around the building
     * @return null if all chunks are loaded, or a string describing which chunk is not loaded
     */
    static String checkChunksLoaded(ServerLevel level, BlockPos origin,
                                    int effectiveWidth, int effectiveDepth, int margin) {
        int minChunkX = (origin.getX() - margin) >> 4;
        int maxChunkX = (origin.getX() + effectiveWidth + margin - 1) >> 4;
        int minChunkZ = (origin.getZ() - margin) >> 4;
        int maxChunkZ = (origin.getZ() + effectiveDepth + margin - 1) >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) {
                    return String.format("Chunk [%d,%d] not loaded (building spans %d chunks wide, %d chunks deep)",
                        cx, cz, maxChunkX - minChunkX + 1, maxChunkZ - minChunkZ + 1);
                }
            }
        }
        return null; // All chunks loaded
    }
}
