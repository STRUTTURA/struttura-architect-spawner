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
 * Validator for ON_WATER position type.
 * Building is completely submerged in water - all blocks within bounds + margin must be water.
 * Coral, kelp, seagrass and other non-solid underwater blocks are ignored (building can spawn there).
 * Fails if the building would intersect solid ground blocks (dirt, stone, sand, gravel, etc.).
 * Ice on surface is allowed - searches below ice for water.
 *
 * Uses similar multi-Y attempt logic as OnAirValidator, but starts from water surface
 * and searches downward toward the sea floor.
 */
public class OnWaterValidator extends AbstractPositionValidator {

    public static final OnWaterValidator INSTANCE = new OnWaterValidator();

    private OnWaterValidator() {}

    @Override
    public PositionType getPositionType() {
        return PositionType.ON_WATER;
    }

    @Override
    protected int getYAtPosition(ServerLevel level, int x, int z, SpawnRule rule, Random random) {
        // Not used - we override findPositionWithDetails to handle multiple Y attempts
        return getWaterSurfaceHeight(level, x, z, rule.getY1(), rule.getY2());
    }

    @Override
    protected PlacementResult validatePlacement(PlacementContext ctx) {
        // Not used directly - we call validateAtY instead
        return validateAtY(ctx, ctx.entrancePos().getY());
    }

    /**
     * Validates placement at a specific Y coordinate.
     * All blocks within bounds + margin must be water or ignorable underwater blocks.
     * Fails if any solid ground block (dirt, stone, sand, etc.) is found.
     */
    private PlacementResult validateAtY(PlacementContext ctx, int y) {
        int margin = ctx.margin();
        int sizeY = ctx.building().getSizeY();

        // Calculate expanded bounds with margin
        int minX = ctx.buildingOrigin().getX() - margin;
        int maxX = ctx.buildingOrigin().getX() + ctx.effectiveWidth() + margin - 1;
        int minY = y - margin;
        int maxY = y + sizeY + margin - 1;
        int minZ = ctx.buildingOrigin().getZ() - margin;
        int maxZ = ctx.buildingOrigin().getZ() + ctx.effectiveDepth() + margin - 1;

        // Check all blocks within expanded bounds
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    pos.set(bx, by, bz);
                    BlockState state = ctx.level().getBlockState(pos);

                    // Check if this block is acceptable for underwater placement
                    if (!isAcceptableUnderwaterBlock(state)) {
                        return PlacementResult.fail(String.format(
                            "Unacceptable block at %s (block: %s)",
                            pos.toShortString(), state.getBlock().getName().getString()));
                    }
                }
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

        // Height needed for the building including margins
        int requiredHeight = buildingHeight + 2 * margin;

        List<String> failureReasons = new ArrayList<>();
        failureReasons.add(String.format("Building: %s (size %dx%dx%d)",
            building.getRdns(), buildingWidth, buildingHeight, buildingDepth));
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

            // Find water surface at this position within rule's Y range
            int waterSurfaceY = getWaterSurfaceHeight(level, worldX, worldZ, rule.getY1(), rule.getY2());
            if (waterSurfaceY < 0) {
                failureReasons.add(String.format(
                    "Quadrant [%d,%d] at (%d,%d): No water found",
                    quadrant[0], quadrant[1], worldX, worldZ));
                continue;
            }

            // Find water depth (distance from surface to sea floor)
            int seaFloorY = findSeaFloorY(level, worldX, worldZ, waterSurfaceY);

            // Calculate valid Y range for submerged placement
            // Building must be completely underwater:
            // - Top of building + margin must be at or below water surface
            // - Bottom of building - margin must be above sea floor
            // effectiveMaxY = waterSurfaceY - buildingHeight - margin (top of building at surface)
            // effectiveMinY = seaFloorY + margin + 1 (bottom of building above floor)
            int effectiveMaxY = waterSurfaceY - buildingHeight - margin;
            int effectiveMinY = seaFloorY + margin + 1;

            // Also constrain by rule's Y range
            effectiveMaxY = Math.min(effectiveMaxY, rule.getY2() - buildingHeight - margin + 1);
            effectiveMinY = Math.max(effectiveMinY, rule.getY1() + margin);

            // Check if there's any valid range at all
            if (effectiveMaxY < effectiveMinY) {
                failureReasons.add(String.format(
                    "Quadrant [%d,%d] at (%d,%d): Water depth too shallow (surface=%d, floor=%d, effective [%d-%d] invalid for height %d + margin %d)",
                    quadrant[0], quadrant[1], worldX, worldZ, waterSurfaceY, seaFloorY,
                    effectiveMinY, effectiveMaxY, buildingHeight, margin));
                continue;
            }

            // Start searching from the top (near surface) going down
            int currentMaxY = effectiveMaxY;

            while (true) {
                // Check if we've exhausted the valid range
                if (currentMaxY < effectiveMinY) {
                    failureReasons.add(String.format(
                        "Quadrant [%d,%d] at (%d,%d): Exhausted Y range [%d-%d]",
                        quadrant[0], quadrant[1], worldX, worldZ, effectiveMinY, effectiveMaxY));
                    break;
                }

                // Pick a random Y between effectiveMinY and currentMaxY
                int range = currentMaxY - effectiveMinY;
                int y = (range <= 0) ? currentMaxY : effectiveMinY + random.nextInt(range + 1);

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

                    // Validate all blocks are water/acceptable
                    PlacementResult result = validateAtY(ctx, y);
                    if (result.valid()) {
                        return FindResult.success(SpawnPosition.at(entrancePos, rotation, rule));
                    } else {
                        failureReasons.add(String.format("Pos %s rot %d°: %s",
                            entrancePos.toShortString(), rotation, result.reason()));
                    }
                }

                // Move maxY down past this attempt for next iteration
                currentMaxY = y - 1;
            }
        }

        return FindResult.failure(failureReasons);
    }

    /**
     * Finds the water surface height at the given position within the specified Y range.
     * Scans down from maxY to find the topmost water block.
     * Handles ice on surface - looks for water below ice.
     *
     * This handles both surface water (oceans) and underground water (caves).
     *
     * @param minY minimum Y to search (from spawn rule)
     * @param maxY maximum Y to search (from spawn rule)
     * @return Y coordinate of the topmost water block, or -1 if no water found
     */
    private int getWaterSurfaceHeight(ServerLevel level, int x, int z, int minY, int maxY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, maxY, z);

        // Scan down from maxY looking for water
        // This pattern finds both surface and underground water lakes
        while (pos.getY() >= minY) {
            BlockState state = level.getBlockState(pos);

            if (isWater(state)) {
                // Found water - return this Y as the surface
                return pos.getY();
            }

            // Ice counts as "above water" - keep scanning
            if (isIce(state)) {
                pos.setY(pos.getY() - 1);
                continue;
            }

            pos.setY(pos.getY() - 1);
        }

        return -1; // No water found
    }

    /**
     * Finds the sea floor Y at the given position (topmost solid ground block below water).
     *
     * @return Y coordinate of the sea floor, or minY if no floor found
     */
    private int findSeaFloorY(ServerLevel level, int x, int z, int startY) {
        int minY = level.getMinY();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, startY, z);

        while (pos.getY() > minY) {
            BlockState state = level.getBlockState(pos);
            if (isSolidGroundBlock(state)) {
                return pos.getY();
            }
            pos.setY(pos.getY() - 1);
        }

        return minY;
    }

    /**
     * Checks if a block is water.
     */
    private boolean isWater(BlockState state) {
        return state.is(Blocks.WATER);
    }

    /**
     * Checks if a block is ice (any type).
     */
    private boolean isIce(BlockState state) {
        return state.is(BlockTags.ICE);
    }

    /**
     * Checks if a block is acceptable for underwater building placement.
     * Water, coral, kelp, seagrass, and other non-solid underwater blocks are acceptable.
     * Air is NOT acceptable (building must be fully submerged).
     */
    private boolean isAcceptableUnderwaterBlock(BlockState state) {
        // Water is always acceptable
        if (isWater(state)) {
            return true;
        }

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

        // Not an acceptable underwater block
        return false;
    }

    /**
     * Checks if a block is solid ground that should block placement.
     * This includes dirt, stone, sand, gravel, and similar terrain blocks.
     */
    private boolean isSolidGroundBlock(BlockState state) {
        // Base materials
        if (state.is(BlockTags.BASE_STONE_OVERWORLD) ||
            state.is(BlockTags.DIRT) ||
            state.is(BlockTags.SAND) ||
            state.is(Blocks.GRAVEL) ||
            state.is(Blocks.CLAY) ||
            state.is(Blocks.MUD) ||
            state.is(Blocks.SOUL_SAND) ||
            state.is(Blocks.SOUL_SOIL)) {
            return true;
        }

        // Deepslate
        if (state.is(BlockTags.BASE_STONE_NETHER) ||
            state.is(Blocks.DEEPSLATE) ||
            state.is(Blocks.COBBLED_DEEPSLATE)) {
            return true;
        }

        // Ocean floor blocks
        if (state.is(Blocks.PRISMARINE) ||
            state.is(Blocks.DARK_PRISMARINE) ||
            state.is(Blocks.PRISMARINE_BRICKS) ||
            state.is(Blocks.SEA_LANTERN)) {
            return true;
        }

        // Bedrock
        if (state.is(Blocks.BEDROCK)) {
            return true;
        }

        // General solid block check as fallback
        // If it's not water, air, or known acceptable block, and it's solid, treat as ground
        return !state.isAir() && !isWater(state) && !isAcceptableUnderwaterBlock(state) &&
               state.isSolid();
    }
}
