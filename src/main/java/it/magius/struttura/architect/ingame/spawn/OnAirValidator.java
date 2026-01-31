package it.magius.struttura.architect.ingame.spawn;

import it.magius.struttura.architect.ingame.model.PositionType;
import it.magius.struttura.architect.ingame.model.SpawnRule;
import it.magius.struttura.architect.ingame.model.SpawnableBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Validator for ON_AIR position type.
 * Building is floating in air - all blocks within bounds + margin must be air.
 * Tries multiple Y positions within the range until it finds a valid spot
 * or the remaining space is too small for the building.
 */
public class OnAirValidator extends AbstractPositionValidator {

    public static final OnAirValidator INSTANCE = new OnAirValidator();

    private OnAirValidator() {}

    @Override
    public PositionType getPositionType() {
        return PositionType.ON_AIR;
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

        // Check all blocks within expanded bounds are air
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    pos.set(bx, by, bz);
                    BlockState state = ctx.level().getBlockState(pos);
                    if (!state.isAir()) {
                        return PlacementResult.fail(String.format(
                            "Non-air block at %s (block: %s)",
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

            // Calculate valid Y range considering margins and building height
            // The building origin Y must be at least margin blocks above y1
            // and the top of the building + margin must be at most y2
            // So: effectiveMinY = y1 + margin
            //     effectiveMaxY = y2 - buildingHeight - margin + 1
            int effectiveMinY = rule.getY1() + margin;
            int effectiveMaxY = rule.getY2() - buildingHeight - margin + 1;

            // Check if there's any valid range at all
            if (effectiveMaxY < effectiveMinY) {
                failureReasons.add(String.format(
                    "Quadrant [%d,%d] at (%d,%d): Y range too small (effective [%d-%d] invalid for height %d + margin %d)",
                    quadrant[0], quadrant[1], worldX, worldZ, effectiveMinY, effectiveMaxY, buildingHeight, margin));
                continue;
            }

            // Start searching from the effective minimum
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
                boolean foundAtThisY = false;

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

                    // Validate all blocks are air
                    PlacementResult result = validateAtY(ctx, y);
                    if (result.valid()) {
                        return FindResult.success(SpawnPosition.at(entrancePos, rotation));
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
}
