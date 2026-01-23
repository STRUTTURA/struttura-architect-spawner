package it.magius.struttura.architect.ingame.spawn;

import it.magius.struttura.architect.ingame.model.SpawnableBuilding;
import it.magius.struttura.architect.model.Construction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/**
 * Centralized utility for calculating building bounds in world space.
 * Uses the same rotation logic as ConstructionOperations to ensure consistency.
 */
public class BoundsCalculator {

    /**
     * Calculates world-space bounds for a SpawnableBuilding at the given position.
     * Used by SpawnEvaluator before spawning.
     *
     * @param building The building metadata
     * @param position The spawn position (entrance location and rotation)
     * @return The world-space AABB
     */
    public static AABB calculate(SpawnableBuilding building, SpawnPosition position) {
        BlockPos spawnPoint = position.blockPos();  // Where entrance will be placed
        int rotationDegrees = position.rotation();

        int sizeX = building.getSizeX();
        int sizeY = building.getSizeY();
        int sizeZ = building.getSizeZ();

        // Get entrance position (normalized coordinates within building)
        BlockPos entrance = building.getEntrance();
        int entranceX = entrance != null ? entrance.getX() : sizeX / 2;
        int entranceY = entrance != null ? entrance.getY() : 0;
        int entranceZ = entrance != null ? entrance.getZ() : sizeZ / 2;

        // Pivot for rotation (same as entrance)
        int pivotX = entranceX;
        int pivotZ = entranceZ;

        // Calculate rotation steps using the same formula as ConstructionOperations.calculateRotationSteps
        float entranceYaw = building.getEntranceYaw();
        int rotationSteps = calculateRotationSteps(rotationDegrees, entranceYaw);

        // Rotate entrance around pivot to get the offset for targetPos calculation
        int[] rotatedEntrance = rotateXZ(entranceX, entranceZ, pivotX, pivotZ, rotationSteps);

        // Calculate targetPos (building origin in world space)
        // Same as calculatePositionAtEntranceRotated: spawnPoint - rotatedEntrance
        int targetX = spawnPoint.getX() - rotatedEntrance[0];
        int targetY = spawnPoint.getY() - entranceY;
        int targetZ = spawnPoint.getZ() - rotatedEntrance[1];

        return calculateFromTarget(targetX, targetY, targetZ, sizeX, sizeY, sizeZ, pivotX, pivotZ, rotationSteps);
    }

    /**
     * Calculates rotation steps from target yaw and entrance yaw.
     * Same logic as ConstructionOperations.calculateRotationSteps.
     */
    private static int calculateRotationSteps(float targetYaw, float entranceYaw) {
        float deltaYaw = targetYaw - entranceYaw;
        // Normalize to 0-360
        deltaYaw = ((deltaYaw % 360) + 360) % 360;
        // Convert to steps (0, 1, 2, 3)
        int steps = Math.round(deltaYaw / 90f) % 4;
        return steps;
    }

    /**
     * Calculates world-space bounds for a Construction after spawning.
     * Used by InGameBuildingSpawner after spawn to store in chunk data.
     *
     * @param construction The construction that was spawned
     * @param targetPos The target position (origin) returned by architectSpawn
     * @param rotationDegrees The rotation in degrees (0, 90, 180, 270)
     * @return The world-space AABB
     */
    public static AABB calculate(Construction construction, BlockPos targetPos, int rotationDegrees) {
        var bounds = construction.getBounds();

        int sizeX = bounds.getSizeX();
        int sizeY = bounds.getSizeY();
        int sizeZ = bounds.getSizeZ();

        // Get pivot and entrance yaw
        int pivotX, pivotZ;
        float entranceYaw = 0f;
        if (construction.getAnchors().hasEntrance()) {
            BlockPos entrance = construction.getAnchors().getEntrance();
            pivotX = entrance.getX();
            pivotZ = entrance.getZ();
            entranceYaw = construction.getAnchors().getEntranceYaw();
        } else {
            pivotX = sizeX / 2;
            pivotZ = sizeZ / 2;
        }

        // Calculate rotation steps using the same formula as ConstructionOperations
        int rotationSteps = calculateRotationSteps(rotationDegrees, entranceYaw);

        return calculateFromTarget(targetPos.getX(), targetPos.getY(), targetPos.getZ(),
            sizeX, sizeY, sizeZ, pivotX, pivotZ, rotationSteps);
    }

    /**
     * Core calculation: rotate corners around pivot and find min/max.
     */
    private static AABB calculateFromTarget(int targetX, int targetY, int targetZ,
                                            int sizeX, int sizeY, int sizeZ,
                                            int pivotX, int pivotZ, int rotationSteps) {
        // Building corners in normalized coords
        int[][] corners = {
            {0, 0},
            {sizeX - 1, 0},
            {0, sizeZ - 1},
            {sizeX - 1, sizeZ - 1}
        };

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        for (int[] corner : corners) {
            int[] rotated = rotateXZ(corner[0], corner[1], pivotX, pivotZ, rotationSteps);
            int worldX = targetX + rotated[0];
            int worldZ = targetZ + rotated[1];

            minX = Math.min(minX, worldX);
            maxX = Math.max(maxX, worldX);
            minZ = Math.min(minZ, worldZ);
            maxZ = Math.max(maxZ, worldZ);
        }

        return new AABB(
            minX,
            targetY,
            minZ,
            maxX,
            targetY + sizeY - 1,
            maxZ
        );
    }

    /**
     * Rotates a point (x, z) around a pivot by the given rotation steps.
     * Counter-clockwise rotation when viewed from above (Y+).
     * Same logic as ConstructionOperations.rotateXZ.
     */
    private static int[] rotateXZ(int x, int z, int pivotX, int pivotZ, int steps) {
        if (steps == 0) return new int[]{x, z};

        int relX = x - pivotX;
        int relZ = z - pivotZ;
        int newRelX, newRelZ;

        switch (steps) {
            case 1: // 90° counter-clockwise: (x,z) -> (-z,x)
                newRelX = -relZ;
                newRelZ = relX;
                break;
            case 2: // 180°: (x,z) -> (-x,-z)
                newRelX = -relX;
                newRelZ = -relZ;
                break;
            case 3: // 270° counter-clockwise (= 90° clockwise): (x,z) -> (z,-x)
                newRelX = relZ;
                newRelZ = -relX;
                break;
            default:
                return new int[]{x, z};
        }

        return new int[]{pivotX + newRelX, pivotZ + newRelZ};
    }
}
