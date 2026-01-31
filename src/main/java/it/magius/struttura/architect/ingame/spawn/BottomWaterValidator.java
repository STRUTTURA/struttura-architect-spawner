package it.magius.struttura.architect.ingame.spawn;

import it.magius.struttura.architect.ingame.model.PositionType;
import it.magius.struttura.architect.ingame.model.SpawnRule;
import it.magius.struttura.architect.ingame.model.SpawnableBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
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
 * Uses multi-Y attempt logic, starting from random Y and searching upward
 * to find underwater floors in caves.
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
        // Not used - we override findPositionWithDetails to handle multiple Y attempts
        return rule.getY1();
    }

    @Override
    protected PlacementResult validatePlacement(PlacementContext ctx) {
        // Not used directly - we call validateAtY instead
        return validateAtY(ctx, ctx.entrancePos().getY());
    }

    /**
     * Validates placement at a specific Y coordinate (anchor Y in world coordinates).
     * The building base must be on solid floor with water above the building top.
     *
     * Key positions:
     * - anchorY (y parameter) = world Y of the anchor/entrance
     * - entranceY = normalized Y of anchor within building (0 = bottom)
     * - floorY = anchorY - 1 (where solid floor must be, directly below anchor)
     * - topY = anchorY + heightAboveAnchor (where water must be)
     *
     * The part of the building below the anchor (entranceY blocks) can be in the terrain.
     * The anchor itself must be on solid floor with water above.
     */
    private PlacementResult validateAtY(PlacementContext ctx, int anchorY) {
        // entranceY = how far the anchor is from the building base (normalized coords)
        int entranceY = ctx.building().getEntrance().getY();
        int buildingHeight = ctx.building().getSizeY();
        int heightAboveAnchor = buildingHeight - entranceY;

        // Floor must be one block below the anchor (not building base!)
        int floorY = anchorY - 1;
        // Top of building in world coordinates (where water must be)
        int topY = anchorY + heightAboveAnchor;

        // Check that the anchor is on solid floor
        BlockPos floorPos = new BlockPos(ctx.entrancePos().getX(), floorY, ctx.entrancePos().getZ());
        BlockState floorState = ctx.level().getBlockState(floorPos);
        if (!isSolidFloorBlock(floorState)) {
            return PlacementResult.fail(String.format(
                "No solid floor below anchor at %s (found: %s)",
                floorPos.toShortString(), floorState.getBlock().getName().getString()));
        }

        // Check that the anchor position is in water (not buried in terrain)
        BlockPos anchorPos = new BlockPos(ctx.entrancePos().getX(), anchorY, ctx.entrancePos().getZ());
        BlockState anchorState = ctx.level().getBlockState(anchorPos);
        if (!isWater(anchorState) && !isIgnorableUnderwaterBlock(anchorState)) {
            return PlacementResult.fail(String.format(
                "Anchor at %s is not in water (found: %s)",
                anchorPos.toShortString(), anchorState.getBlock().getName().getString()));
        }

        // Check corners of the building footprint
        int[] cornerXOffsets = {0, ctx.effectiveWidth() - 1, 0, ctx.effectiveWidth() - 1};
        int[] cornerZOffsets = {0, 0, ctx.effectiveDepth() - 1, ctx.effectiveDepth() - 1};

        for (int i = 0; i < 4; i++) {
            int cornerX = ctx.buildingOrigin().getX() + cornerXOffsets[i];
            int cornerZ = ctx.buildingOrigin().getZ() + cornerZOffsets[i];

            // Check floor at corner (one block below anchor Y)
            BlockPos cornerFloorPos = new BlockPos(cornerX, floorY, cornerZ);
            BlockState cornerFloorState = ctx.level().getBlockState(cornerFloorPos);

            if (!isSolidFloorBlock(cornerFloorState)) {
                return PlacementResult.fail(String.format(
                    "Corner %d has no solid floor at %s (found: %s)",
                    i, cornerFloorPos.toShortString(), cornerFloorState.getBlock().getName().getString()));
            }

            // Check anchor level at corner is in water (not buried)
            BlockPos cornerAnchorPos = new BlockPos(cornerX, anchorY, cornerZ);
            BlockState cornerAnchorState = ctx.level().getBlockState(cornerAnchorPos);

            if (!isWater(cornerAnchorState) && !isIgnorableUnderwaterBlock(cornerAnchorState)) {
                return PlacementResult.fail(String.format(
                    "Corner %d anchor level at %s is not in water (found: %s)",
                    i, cornerAnchorPos.toShortString(), cornerAnchorState.getBlock().getName().getString()));
            }

            // Check water ABOVE the building top at this corner
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

    @Override
    public FindResult findPositionWithDetails(ServerLevel level, ChunkPos chunkPos,
                                               SpawnableBuilding building, SpawnRule rule, Random random) {

        int buildingWidth = building.getSizeX();
        int buildingDepth = building.getSizeZ();
        int buildingHeight = building.getSizeY();
        int margin = rule.getMargin();

        // The entrance Y is in normalized coordinates (relative to building origin)
        // entranceY = how far anchor is from building base
        // heightAboveAnchor = how much of building is above the anchor
        int entranceY = building.getEntrance().getY();
        int heightAboveAnchor = buildingHeight - entranceY;

        List<String> failureReasons = new ArrayList<>();
        failureReasons.add(String.format("Building: %s (size %dx%dx%d, entranceY=%d, heightAbove=%d)",
            building.getRdns(), buildingWidth, buildingHeight, buildingDepth, entranceY, heightAboveAnchor));
        failureReasons.add(String.format("Rule: Y range [%d-%d], margin=%d, type=%s",
            rule.getY1(), rule.getY2(), margin, rule.getType()));

        // Divide chunk into 4 quadrants and try each
        int[][] quadrants = {
            {0, 0}, {8, 0}, {0, 8}, {8, 8}
        };

        // Shuffle quadrants for variety
        for (int i = quadrants.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int[] temp = quadrants[i];
            quadrants[i] = quadrants[j];
            quadrants[j] = temp;
        }

        for (int[] quadrant : quadrants) {
            // Pick random position within this quadrant
            int localX = quadrant[0] + random.nextInt(8);
            int localZ = quadrant[1] + random.nextInt(8);

            int worldX = chunkPos.getBlockX(localX);
            int worldZ = chunkPos.getBlockZ(localZ);

            // Calculate valid Y range for the anchor position
            // Floor must be at: anchorY - 1 (directly below anchor)
            // Water must be at: anchorY + heightAboveAnchor (above building top)
            //
            // Constraints:
            // - Floor (anchorY - 1) >= y1, so anchorY >= y1 + 1
            // - Water (anchorY + heightAboveAnchor) <= y2, so anchorY <= y2 - heightAboveAnchor
            int effectiveMinY = rule.getY1() + 1;
            int effectiveMaxY = rule.getY2() - heightAboveAnchor;

            // Check if there's any valid range at all
            if (effectiveMaxY < effectiveMinY) {
                failureReasons.add(String.format(
                    "Quadrant [%d,%d] at (%d,%d): Y range too small (effective [%d-%d] invalid for height %d)",
                    quadrant[0], quadrant[1], worldX, worldZ, effectiveMinY, effectiveMaxY, buildingHeight));
                continue;
            }

            // Start searching from the bottom going up (to find cave floors first)
            int currentMinY = effectiveMinY;

            while (true) {
                // Check if we've exhausted the valid range
                if (currentMinY > effectiveMaxY) {
                    failureReasons.add(String.format(
                        "Quadrant [%d,%d] at (%d,%d): Exhausted Y range [%d-%d]",
                        quadrant[0], quadrant[1], worldX, worldZ, effectiveMinY, effectiveMaxY));
                    break;
                }

                // Pick a random Y between currentMinY and effectiveMaxY
                int range = effectiveMaxY - currentMinY;
                int y = (range <= 0) ? currentMinY : currentMinY + random.nextInt(range + 1);

                // First check if this Y could be a valid underwater floor position
                // (solid block below building base, water above building top)
                if (!isValidUnderwaterFloorCandidate(level, worldX, y, worldZ, entranceY, heightAboveAnchor)) {
                    // Move minY up past this attempt for next iteration
                    currentMinY = y + 1;
                    continue;
                }

                // Try all 4 rotations at this Y
                int[] rotations = {0, 90, 180, 270};

                for (int rotation : rotations) {
                    BlockPos entrancePos = new BlockPos(worldX, y, worldZ);

                    PlacementContext ctx = createPlacementContext(level, entrancePos, building, rotation, margin);

                    // Check that all chunks the building will occupy are loaded
                    String chunkCheck = PositionValidator.checkChunksLoaded(
                        level, ctx.buildingOrigin(), ctx.effectiveWidth(), ctx.effectiveDepth(), margin);
                    if (chunkCheck != null) {
                        failureReasons.add(String.format("Pos %s rot %d°: %s",
                            entrancePos.toShortString(), rotation, chunkCheck));
                        continue;
                    }

                    // Validate the position
                    PlacementResult result = validateAtY(ctx, y);
                    if (result.valid()) {
                        return FindResult.success(SpawnPosition.at(entrancePos, rotation, rule));
                    } else {
                        failureReasons.add(String.format("Pos %s rot %d°: %s",
                            entrancePos.toShortString(), rotation, result.reason()));
                    }
                }

                // Move minY up past this attempt for next iteration
                currentMinY = y + 1;
            }
        }

        return FindResult.failure(failureReasons);
    }

    /**
     * Quick check if a position could be a valid underwater floor.
     * Checks:
     * 1. Solid floor below the anchor (anchorY - 1)
     * 2. Water/ignorable at the anchor level (not solid - we must be ON the floor, not IN it)
     * 3. Water above the building top
     *
     * The part of the building below the anchor can be in the terrain.
     *
     * @param anchorY the anchor Y in world coordinates
     * @param entranceY how far the anchor is from the building base (normalized) - NOT USED anymore
     * @param heightAboveAnchor how much of the building is above the anchor
     */
    private boolean isValidUnderwaterFloorCandidate(ServerLevel level, int x, int anchorY, int z,
                                                     int entranceY, int heightAboveAnchor) {
        // Floor is one block below the anchor (not building base!)
        int floorY = anchorY - 1;

        // Check 1: Floor must be solid (directly below anchor)
        BlockState floorState = level.getBlockState(new BlockPos(x, floorY, z));
        if (!isSolidFloorBlock(floorState)) {
            return false;
        }

        // Check 2: Anchor level must be in water (not solid terrain!)
        // This ensures we're ON the floor, not buried inside it
        BlockState anchorState = level.getBlockState(new BlockPos(x, anchorY, z));
        if (!isWater(anchorState) && !isIgnorableUnderwaterBlock(anchorState)) {
            return false;
        }

        // Check 3: Water must be above the building top
        int topY = anchorY + heightAboveAnchor;
        BlockState topState = level.getBlockState(new BlockPos(x, topY, z));
        if (!isWater(topState) && !isIgnorableUnderwaterBlock(topState)) {
            return false;
        }

        return true;
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
