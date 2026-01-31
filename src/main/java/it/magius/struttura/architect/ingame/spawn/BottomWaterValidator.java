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
 * Validator for BOTTOM_WATER position type.
 * Building is placed on the sea/lake floor with water above.
 * Similar to OnGroundValidator but the "ground" is the underwater floor
 * and the space above must be water (not air).
 *
 * The floor is considered solid terrain (dirt, stone, sand, gravel, clay, etc.)
 * Coral, kelp, and other underwater plants are ignored (can be destroyed).
 *
 * Supports both surface water bodies and underground water lakes (caves).
 */
public class BottomWaterValidator extends AbstractPositionValidator {

    public static final BottomWaterValidator INSTANCE = new BottomWaterValidator();

    private BottomWaterValidator() {}

    @Override
    public PositionType getPositionType() {
        return PositionType.BOTTOM_WATER;
    }

    @Override
    protected int getYAtPosition(ServerLevel level, int x, int z, SpawnRule rule, Random random) {
        // Find the sea floor height at this position within the rule's Y range
        return getSeaFloorHeight(level, x, z, rule.getY1(), rule.getY2());
    }

    @Override
    protected PlacementResult validatePlacement(PlacementContext ctx) {
        int anchorY = ctx.entrancePos().getY();
        int buildingHeight = ctx.building().getSizeY();
        int topY = anchorY + buildingHeight; // Y level just above the building top

        // Check that the anchor is on solid floor (one block below anchor)
        BlockPos floorPos = new BlockPos(ctx.entrancePos().getX(), anchorY - 1, ctx.entrancePos().getZ());
        BlockState floorState = ctx.level().getBlockState(floorPos);
        if (!isSolidFloorBlock(floorState)) {
            return PlacementResult.fail(String.format(
                "No solid floor below anchor at %s (found: %s)",
                floorPos.toShortString(), floorState.getBlock().getName().getString()));
        }

        // Check corners of the building footprint
        int[] cornerXOffsets = {0, ctx.effectiveWidth() - 1, 0, ctx.effectiveWidth() - 1};
        int[] cornerZOffsets = {0, 0, ctx.effectiveDepth() - 1, ctx.effectiveDepth() - 1};

        for (int i = 0; i < 4; i++) {
            int cornerX = ctx.buildingOrigin().getX() + cornerXOffsets[i];
            int cornerZ = ctx.buildingOrigin().getZ() + cornerZOffsets[i];

            // Check floor at corner (one block below anchor Y)
            BlockPos cornerFloorPos = new BlockPos(cornerX, anchorY - 1, cornerZ);
            BlockState cornerFloorState = ctx.level().getBlockState(cornerFloorPos);

            if (!isSolidFloorBlock(cornerFloorState)) {
                return PlacementResult.fail(String.format(
                    "Corner %d has no solid floor at %s (found: %s)",
                    i, cornerFloorPos.toShortString(), cornerFloorState.getBlock().getName().getString()));
            }

            // Check water ABOVE the building top at this corner
            // The entire building must be submerged - water must be at topY
            BlockPos cornerTopPos = new BlockPos(cornerX, topY, cornerZ);
            BlockState cornerTopState = ctx.level().getBlockState(cornerTopPos);

            if (!isWater(cornerTopState) && !isIgnorableUnderwaterBlock(cornerTopState)) {
                return PlacementResult.fail(String.format(
                    "Corner %d has no water above building top at %s (found: %s)",
                    i, cornerTopPos.toShortString(), cornerTopState.getBlock().getName().getString()));
            }
        }

        return PlacementResult.ok();
    }

    /**
     * Finds the sea floor height at the given position within the specified Y range.
     * Scans down from maxY to find solid floor with water above it.
     *
     * @param minY minimum Y to search (from spawn rule)
     * @param maxY maximum Y to search (from spawn rule)
     * @return Y coordinate on top of the sea floor (where building sits), or -1 if not found
     */
    private int getSeaFloorHeight(ServerLevel level, int x, int z, int minY, int maxY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, maxY, z);

        boolean foundWater = false;

        // Scan down from maxY looking for the pattern: water -> solid floor
        while (pos.getY() >= minY) {
            BlockState state = level.getBlockState(pos);

            if (isWater(state) || isIgnorableUnderwaterBlock(state)) {
                // We're in water or underwater vegetation
                foundWater = true;
            } else if (foundWater && isSolidFloorBlock(state)) {
                // Found solid floor with water above - return one block above (on the floor)
                return pos.getY() + 1;
            } else if (!state.isAir()) {
                // Hit something solid without finding water first - reset
                foundWater = false;
            }

            pos.setY(pos.getY() - 1);
        }

        return -1; // No suitable sea floor found
    }

    /**
     * Checks if a block is water.
     */
    private boolean isWater(BlockState state) {
        return state.is(Blocks.WATER);
    }

    /**
     * Checks if a block is solid floor material (sea/lake bed).
     * This includes dirt, stone, sand, gravel, clay, etc.
     */
    private boolean isSolidFloorBlock(BlockState state) {
        // Base materials commonly found on sea/lake floors
        if (state.is(BlockTags.BASE_STONE_OVERWORLD) ||
            state.is(BlockTags.DIRT) ||
            state.is(BlockTags.SAND) ||
            state.is(Blocks.GRAVEL) ||
            state.is(Blocks.CLAY) ||
            state.is(Blocks.MUD)) {
            return true;
        }

        // Deepslate (deep ocean floors)
        if (state.is(Blocks.DEEPSLATE) ||
            state.is(Blocks.COBBLED_DEEPSLATE)) {
            return true;
        }

        // Ocean monument materials (if building near monuments)
        if (state.is(Blocks.PRISMARINE) ||
            state.is(Blocks.DARK_PRISMARINE) ||
            state.is(Blocks.PRISMARINE_BRICKS)) {
            return true;
        }

        // Bedrock
        if (state.is(Blocks.BEDROCK)) {
            return true;
        }

        return false;
    }

    /**
     * Checks if a block is ignorable underwater vegetation/decoration.
     * These blocks can be destroyed when placing the building.
     */
    private boolean isIgnorableUnderwaterBlock(BlockState state) {
        // Coral blocks and coral fans (both dead and alive)
        if (state.is(BlockTags.CORAL_BLOCKS) ||
            state.is(BlockTags.CORALS) ||
            state.is(BlockTags.WALL_CORALS)) {
            return true;
        }

        // Underwater plants
        if (state.is(Blocks.KELP) ||
            state.is(Blocks.KELP_PLANT) ||
            state.is(Blocks.SEAGRASS) ||
            state.is(Blocks.TALL_SEAGRASS) ||
            state.is(Blocks.SEA_PICKLE)) {
            return true;
        }

        // Bubble columns
        if (state.is(Blocks.BUBBLE_COLUMN)) {
            return true;
        }

        return false;
    }
}
