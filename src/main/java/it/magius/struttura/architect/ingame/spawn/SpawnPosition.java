package it.magius.struttura.architect.ingame.spawn;

import net.minecraft.core.BlockPos;

/**
 * Represents a valid spawn position for a building.
 * Contains the block position and rotation to apply.
 */
public record SpawnPosition(
    BlockPos blockPos,      // World position where the building origin will be placed
    int rotation            // Rotation in degrees (0, 90, 180, 270)
) {
    /**
     * Creates a spawn position with no rotation.
     */
    public static SpawnPosition at(BlockPos pos) {
        return new SpawnPosition(pos, 0);
    }

    /**
     * Creates a spawn position with the specified rotation.
     */
    public static SpawnPosition at(BlockPos pos, int rotation) {
        // Normalize rotation to 0, 90, 180, or 270
        int normalizedRotation = ((rotation % 360) + 360) % 360;
        normalizedRotation = (normalizedRotation / 90) * 90;  // Round to nearest 90
        return new SpawnPosition(pos, normalizedRotation);
    }

    @Override
    public String toString() {
        return "SpawnPosition{pos=" + blockPos.toShortString() + ", rotation=" + rotation + "°}";
    }
}
