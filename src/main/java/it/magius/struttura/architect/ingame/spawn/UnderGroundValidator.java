package it.magius.struttura.architect.ingame.spawn;

import it.magius.struttura.architect.ingame.model.PositionType;
import it.magius.struttura.architect.ingame.model.SpawnRule;
import it.magius.struttura.architect.ingame.model.SpawnableBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Validator for UNDER_GROUND position type.
 * Building is placed underground - the anchor must be below the terrain surface
 * and there must be no direct open path to the sky above the anchor.
 *
 * The building bounds can contain air (caves), water, or lava - this is allowed.
 * The key requirement is that the anchor position is underground (has solid blocks
 * between it and the sky).
 *
 * Uses multi-Y attempt logic similar to OnAirValidator, searching from the bottom
 * of the Y range upward.
 */
public class UnderGroundValidator extends AbstractPositionValidator {

    public static final UnderGroundValidator INSTANCE = new UnderGroundValidator();

    private UnderGroundValidator() {}

    @Override
    public PositionType getPositionType() {
        return PositionType.UNDER_GROUND;
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
     * Validates placement at a specific Y coordinate.
     * The anchor must be underground - there must be solid terrain above it
     * blocking direct access to the sky.
     */
    private PlacementResult validateAtY(PlacementContext ctx, int y) {
        int anchorX = ctx.entrancePos().getX();
        int anchorZ = ctx.entrancePos().getZ();

        // Check that the anchor position is underground (not exposed to sky)
        if (hasDirectSkyAccess(ctx.level(), anchorX, y, anchorZ)) {
            return PlacementResult.fail(String.format(
                "Anchor at (%d, %d, %d) has direct sky access",
                anchorX, y, anchorZ));
        }

        // Get surface height at anchor position
        int surfaceY = ctx.level().getHeight(Heightmap.Types.WORLD_SURFACE, anchorX, anchorZ);

        // Anchor must be below the surface
        if (y >= surfaceY) {
            return PlacementResult.fail(String.format(
                "Anchor Y=%d is at or above surface Y=%d",
                y, surfaceY));
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

            // Get surface height at this position
            int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ);

            // Calculate valid Y range for underground placement
            // Building must be completely below surface:
            // - Top of building must be below surface (y + buildingHeight < surfaceY)
            // - Building must fit within rule's Y range
            int effectiveMaxY = Math.min(surfaceY - buildingHeight - 1, rule.getY2());
            int effectiveMinY = rule.getY1();

            // Check if there's any valid range at all
            if (effectiveMaxY < effectiveMinY) {
                failureReasons.add(String.format(
                    "Quadrant [%d,%d] at (%d,%d): No underground space (surface=%d, effective [%d-%d] invalid for height %d)",
                    quadrant[0], quadrant[1], worldX, worldZ, surfaceY,
                    effectiveMinY, effectiveMaxY, buildingHeight));
                continue;
            }

            // Start searching from the bottom going up (prefer deeper placements)
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

                    // Validate the position is underground
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
     * Checks if a position has direct access to the sky.
     * Scans upward from the position to see if there's only air/water/lava
     * between the position and the sky (no solid blocks blocking).
     *
     * @return true if the position can "see" the sky (is exposed), false if underground
     */
    private boolean hasDirectSkyAccess(ServerLevel level, int x, int y, int z) {
        int maxY = level.getMaxY();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, y, z);

        // Scan upward from the position
        for (int checkY = y; checkY <= maxY; checkY++) {
            pos.setY(checkY);
            BlockState state = level.getBlockState(pos);

            // If we find a solid block, we're underground (no direct sky access)
            if (isSolidTerrainBlock(state)) {
                return false;
            }
        }

        // Reached the sky without hitting solid terrain - exposed to sky
        return true;
    }

    /**
     * Checks if a block is solid terrain that blocks sky access.
     * This includes stone, dirt, sand, etc. but NOT leaves or other passable blocks.
     */
    private boolean isSolidTerrainBlock(BlockState state) {
        // Use the standard solid check - solid blocks that would block light/access
        return state.isSolid() && !state.isAir();
    }
}
