package it.magius.struttura.architect.ingame.spawn;

import it.magius.struttura.architect.ingame.model.PositionType;
import it.magius.struttura.architect.ingame.model.SpawnRule;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;

/**
 * Validator for OVER_WATER position type.
 * Building is placed with its anchor exactly one block above water surface.
 * The entire footprint (bounds X and Z) must have water directly below at the anchor Y level.
 *
 * Supports both surface water and underground water lakes (caves).
 * For underground lakes, validates that the building has enough air space above
 * (building height + margin must fit in the cave).
 */
public class OverWaterValidator extends AbstractPositionValidator {

    public static final OverWaterValidator INSTANCE = new OverWaterValidator();

    private OverWaterValidator() {}

    @Override
    public PositionType getPositionType() {
        return PositionType.OVER_WATER;
    }

    @Override
    protected int getYAtPosition(ServerLevel level, int x, int z, SpawnRule rule, Random random) {
        // Find water surface height at this position (one block above water)
        // Search within the rule's Y range to handle both surface and underground water
        return getWaterSurfaceHeight(level, x, z, rule.getY1(), rule.getY2());
    }

    @Override
    protected PlacementResult validatePlacement(PlacementContext ctx) {
        int anchorY = ctx.entrancePos().getY(); // Entrance Y = one block above water
        int waterY = anchorY - 1; // Water should be directly below the entrance
        int margin = ctx.margin();
        int buildingHeight = ctx.building().getSizeY();

        // Entrance offset: blocks below the entrance are allowed to be in water
        int entranceOffsetY = ctx.building().getEntrance() != null ? ctx.building().getEntrance().getY() : 0;
        int heightAboveEntrance = buildingHeight - entranceOffsetY;

        // Check entire footprint has water at waterY (below entrance)
        for (int x = ctx.buildingOrigin().getX(); x < ctx.buildingOrigin().getX() + ctx.effectiveWidth(); x++) {
            for (int z = ctx.buildingOrigin().getZ(); z < ctx.buildingOrigin().getZ() + ctx.effectiveDepth(); z++) {
                BlockPos waterPos = new BlockPos(x, waterY, z);
                BlockState state = ctx.level().getBlockState(waterPos);

                if (!isWater(state)) {
                    return PlacementResult.fail(String.format(
                        "No water at %s (found: %s)",
                        waterPos.toShortString(), state.getBlock().getName().getString()));
                }

                // Check that the block at entrance level (above water) is air or replaceable
                BlockPos abovePos = new BlockPos(x, anchorY, z);
                BlockState aboveState = ctx.level().getBlockState(abovePos);
                if (!isReplaceableAboveWater(aboveState)) {
                    return PlacementResult.fail(String.format(
                        "Non-air block above water at %s (found: %s)",
                        abovePos.toShortString(), aboveState.getBlock().getName().getString()));
                }
            }
        }

        // Check that the part of the building above the entrance + margin fits in air space
        // Blocks below the entrance are allowed to be submerged in water
        int minX = ctx.buildingOrigin().getX() - margin;
        int maxX = ctx.buildingOrigin().getX() + ctx.effectiveWidth() + margin - 1;
        int minZ = ctx.buildingOrigin().getZ() - margin;
        int maxZ = ctx.buildingOrigin().getZ() + ctx.effectiveDepth() + margin - 1;
        int maxY = anchorY + heightAboveEntrance + margin - 1;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                for (int by = anchorY; by <= maxY; by++) {
                    pos.set(bx, by, bz);
                    BlockState state = ctx.level().getBlockState(pos);
                    if (!isReplaceableAboveWater(state)) {
                        return PlacementResult.fail(String.format(
                            "Non-air block in building space at %s (found: %s)",
                            pos.toShortString(), state.getBlock().getName().getString()));
                    }
                }
            }
        }

        return PlacementResult.ok();
    }

    /**
     * Finds the water surface height at the given position within the specified Y range.
     * Scans down from maxY to find the topmost water block with air/replaceable above it,
     * then returns Y + 1 (one block above water).
     *
     * This handles both surface water and underground water (caves).
     *
     * @param minY minimum Y to search (from spawn rule)
     * @param maxY maximum Y to search (from spawn rule)
     * @return Y coordinate one block above water surface, or -1 if no suitable water found
     */
    private int getWaterSurfaceHeight(ServerLevel level, int x, int z, int minY, int maxY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, maxY, z);

        // Scan down from maxY looking for water with air/replaceable above it
        while (pos.getY() >= minY) {
            BlockState state = level.getBlockState(pos);

            if (isWater(state)) {
                // Found water - check if there's air or replaceable block above it (a valid water surface)
                BlockPos abovePos = pos.above();
                BlockState aboveState = level.getBlockState(abovePos);

                if (isReplaceableAboveWater(aboveState)) {
                    return pos.getY() + 1;
                }
                // Water but solid block above - keep searching down
            }

            pos.setY(pos.getY() - 1);
        }

        return -1; // No suitable water surface found
    }

    /**
     * Checks if a block is water.
     */
    private boolean isWater(BlockState state) {
        return state.is(Blocks.WATER);
    }

    /**
     * Checks if a block is replaceable above water.
     * Includes air, lily pads, snow layers, and other small decorative blocks
     * that naturally generate on or near water surfaces and will be replaced by the building.
     */
    private boolean isReplaceableAboveWater(BlockState state) {
        if (state.isAir()) {
            return true;
        }
        // Lily pads on water surface
        if (state.is(Blocks.LILY_PAD)) {
            return true;
        }
        // Snow layers (can form on frozen water)
        if (state.is(Blocks.SNOW)) {
            return true;
        }
        // Small flowers and grass that could be on shoreline blocks within footprint
        if (state.is(BlockTags.REPLACEABLE)) {
            return true;
        }
        return false;
    }
}
