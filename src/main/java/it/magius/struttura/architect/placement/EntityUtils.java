package it.magius.struttura.architect.placement;

import it.magius.struttura.architect.Architect;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Utility methods for entity operations.
 */
public class EntityUtils {

    /**
     * Nudges an entity out of blocks if it's colliding with them.
     * Tries to find a free direction and moves the entity slightly.
     *
     * @param level The server level
     * @param entity The entity to nudge
     * @return true if the entity was nudged, false if not stuck or couldn't be freed
     */
    public static boolean nudgeEntityOutOfBlocks(ServerLevel level, Entity entity) {
        AABB entityBounds = entity.getBoundingBox();

        // Check if entity is colliding with any blocks
        if (!isCollidingWithBlocks(level, entityBounds)) {
            return false; // Not stuck, no need to nudge
        }

        // Try nudging in each horizontal direction (prioritize horizontal movement)
        double nudgeAmount = 0.5;
        double[][] directions = {
            {nudgeAmount, 0, 0},   // +X
            {-nudgeAmount, 0, 0},  // -X
            {0, 0, nudgeAmount},   // +Z
            {0, 0, -nudgeAmount},  // -Z
            {0, nudgeAmount, 0},   // +Y (up)
        };

        for (double[] dir : directions) {
            AABB testBounds = entityBounds.move(dir[0], dir[1], dir[2]);
            if (!isCollidingWithBlocks(level, testBounds)) {
                // Found a free direction, move entity there
                entity.setPos(
                    entity.getX() + dir[0],
                    entity.getY() + dir[1],
                    entity.getZ() + dir[2]
                );
                Architect.LOGGER.debug("Nudged entity {} by ({}, {}, {})",
                    EntityType.getKey(entity.getType()), dir[0], dir[1], dir[2]);
                return true;
            }
        }

        // If still stuck, try a larger nudge
        nudgeAmount = 1.0;
        for (double[] dir : new double[][]{{nudgeAmount, 0, 0}, {-nudgeAmount, 0, 0},
                                           {0, 0, nudgeAmount}, {0, 0, -nudgeAmount}}) {
            AABB testBounds = entityBounds.move(dir[0], dir[1], dir[2]);
            if (!isCollidingWithBlocks(level, testBounds)) {
                entity.setPos(
                    entity.getX() + dir[0],
                    entity.getY() + dir[1],
                    entity.getZ() + dir[2]
                );
                Architect.LOGGER.debug("Nudged entity {} by larger amount ({}, {}, {})",
                    EntityType.getKey(entity.getType()), dir[0], dir[1], dir[2]);
                return true;
            }
        }

        Architect.LOGGER.warn("Could not nudge entity {} out of blocks at ({}, {}, {})",
            EntityType.getKey(entity.getType()), entity.getX(), entity.getY(), entity.getZ());
        return false;
    }

    /**
     * Checks if an AABB collides with any solid blocks.
     *
     * @param level The server level
     * @param bounds The AABB to check
     * @return true if colliding with solid blocks
     */
    public static boolean isCollidingWithBlocks(ServerLevel level, AABB bounds) {
        int minX = (int) Math.floor(bounds.minX);
        int minY = (int) Math.floor(bounds.minY);
        int minZ = (int) Math.floor(bounds.minZ);
        int maxX = (int) Math.ceil(bounds.maxX);
        int maxY = (int) Math.ceil(bounds.maxY);
        int maxZ = (int) Math.ceil(bounds.maxZ);

        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    mutablePos.set(x, y, z);
                    BlockState state = level.getBlockState(mutablePos);
                    if (!state.isAir() && state.isSolid()) {
                        // Check if block's collision shape actually intersects with entity bounds
                        VoxelShape shape = state.getCollisionShape(level, mutablePos);
                        if (!shape.isEmpty()) {
                            AABB blockBounds = shape.bounds().move(x, y, z);
                            if (bounds.intersects(blockBounds)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }
}
