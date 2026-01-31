package it.magius.struttura.architect.ingame.spawn;

import it.magius.struttura.architect.ingame.model.SpawnRule;
import net.minecraft.core.BlockPos;

/**
 * Represents a valid spawn position for a building.
 * Contains the block position, rotation to apply, and the spawn rule that was used.
 */
public record SpawnPosition(
    BlockPos blockPos,      // World position where the building origin will be placed
    int rotation,           // Rotation in degrees (0, 90, 180, 270)
    SpawnRule rule          // The spawn rule used for this position (nullable for manual spawns)
) {
    /**
     * Creates a spawn position with no rotation and no rule.
     */
    public static SpawnPosition at(BlockPos pos) {
        return new SpawnPosition(pos, 0, null);
    }

    /**
     * Creates a spawn position with the specified rotation and no rule.
     */
    public static SpawnPosition at(BlockPos pos, int rotation) {
        // Normalize rotation to 0, 90, 180, or 270
        int normalizedRotation = ((rotation % 360) + 360) % 360;
        normalizedRotation = (normalizedRotation / 90) * 90;  // Round to nearest 90
        return new SpawnPosition(pos, normalizedRotation, null);
    }

    /**
     * Creates a spawn position with the specified rotation and rule.
     */
    public static SpawnPosition at(BlockPos pos, int rotation, SpawnRule rule) {
        // Normalize rotation to 0, 90, 180, or 270
        int normalizedRotation = ((rotation % 360) + 360) % 360;
        normalizedRotation = (normalizedRotation / 90) * 90;  // Round to nearest 90
        return new SpawnPosition(pos, normalizedRotation, rule);
    }

    @Override
    public String toString() {
        return "SpawnPosition{pos=" + blockPos.toShortString() + ", rotation=" + rotation + "°}";
    }
}
