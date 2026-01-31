package it.magius.struttura.architect.ingame.spawn;

import it.magius.struttura.architect.ingame.model.SpawnRule;
import it.magius.struttura.architect.ingame.model.SpawnableBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Abstract base class for position validators.
 * Centralizes common logic: quadrant selection, Y range check, rotation handling,
 * and chunk loading verification.
 *
 * Subclasses implement:
 * - getYAtPosition(): how to find the Y coordinate for this position type
 * - validatePlacement(): specific checks for this position type
 */
public abstract class AbstractPositionValidator implements PositionValidator {

    /**
     * Result of placement validation.
     * @param valid true if placement is valid
     * @param reason if invalid, describes why
     */
    protected record PlacementResult(boolean valid, String reason) {
        public static PlacementResult ok() {
            return new PlacementResult(true, null);
        }

        public static PlacementResult fail(String reason) {
            return new PlacementResult(false, reason);
        }
    }

    /**
     * Context passed to validatePlacement with pre-calculated values.
     */
    protected record PlacementContext(
        ServerLevel level,
        BlockPos entrancePos,
        SpawnableBuilding building,
        int rotation,
        int margin,
        // Pre-calculated rotated values
        int effectiveWidth,
        int effectiveDepth,
        int rotatedEntranceX,
        int rotatedEntranceZ,
        BlockPos buildingOrigin
    ) {}

    @Override
    public Optional<SpawnPosition> findPosition(ServerLevel level, ChunkPos chunkPos,
                                                 SpawnableBuilding building, SpawnRule rule, Random random) {
        FindResult result = findPositionWithDetails(level, chunkPos, building, rule, random);
        return result.position() != null ? Optional.of(result.position()) : Optional.empty();
    }

    @Override
    public FindResult findPositionWithDetails(ServerLevel level, ChunkPos chunkPos,
                                               SpawnableBuilding building, SpawnRule rule, Random random) {

        int buildingWidth = building.getSizeX();
        int buildingDepth = building.getSizeZ();
        int margin = rule.getMargin();

        List<String> failureReasons = new ArrayList<>();
        failureReasons.add(String.format("Building: %s (size %dx%dx%d)",
            building.getRdns(), buildingWidth, building.getSizeY(), buildingDepth));
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

            // Get Y coordinate for this position type
            int y = getYAtPosition(level, worldX, worldZ, rule, random);

            // Check Y range
            if (y < rule.getY1() || y > rule.getY2()) {
                failureReasons.add(String.format("Quadrant [%d,%d] at (%d,%d): Y=%d outside range [%d-%d]",
                    quadrant[0], quadrant[1], worldX, worldZ, y, rule.getY1(), rule.getY2()));
                continue;
            }

            // Try all 4 rotations
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

                // Type-specific validation
                PlacementResult result = validatePlacement(ctx);
                if (result.valid()) {
                    return FindResult.success(SpawnPosition.at(entrancePos, rotation, rule));
                } else {
                    failureReasons.add(String.format("Pos %s rot %d°: %s",
                        entrancePos.toShortString(), rotation, result.reason()));
                }
            }
        }

        return FindResult.failure(failureReasons);
    }

    /**
     * Creates placement context with pre-calculated rotated values.
     */
    protected PlacementContext createPlacementContext(ServerLevel level, BlockPos entrancePos,
                                                       SpawnableBuilding building, int rotation, int margin) {
        int sizeX = building.getSizeX();
        int sizeZ = building.getSizeZ();

        // Get entrance offset within building bounds (normalized coordinates)
        BlockPos entrance = building.getEntrance();
        int entranceOffsetX = entrance != null ? entrance.getX() : sizeX / 2;
        int entranceOffsetZ = entrance != null ? entrance.getZ() : sizeZ / 2;

        // Apply rotation to get effective dimensions and entrance offset
        int effectiveWidth, effectiveDepth;
        int rotatedEntranceX, rotatedEntranceZ;

        switch (rotation) {
            case 90 -> {
                effectiveWidth = sizeZ;
                effectiveDepth = sizeX;
                rotatedEntranceX = sizeZ - 1 - entranceOffsetZ;
                rotatedEntranceZ = entranceOffsetX;
            }
            case 180 -> {
                effectiveWidth = sizeX;
                effectiveDepth = sizeZ;
                rotatedEntranceX = sizeX - 1 - entranceOffsetX;
                rotatedEntranceZ = sizeZ - 1 - entranceOffsetZ;
            }
            case 270 -> {
                effectiveWidth = sizeZ;
                effectiveDepth = sizeX;
                rotatedEntranceX = entranceOffsetZ;
                rotatedEntranceZ = sizeX - 1 - entranceOffsetX;
            }
            default -> { // 0
                effectiveWidth = sizeX;
                effectiveDepth = sizeZ;
                rotatedEntranceX = entranceOffsetX;
                rotatedEntranceZ = entranceOffsetZ;
            }
        }

        // Calculate building origin (corner) from entrance position
        int buildingOriginX = entrancePos.getX() - rotatedEntranceX;
        int buildingOriginZ = entrancePos.getZ() - rotatedEntranceZ;
        BlockPos buildingOrigin = new BlockPos(buildingOriginX, entrancePos.getY(), buildingOriginZ);

        return new PlacementContext(
            level, entrancePos, building, rotation, margin,
            effectiveWidth, effectiveDepth, rotatedEntranceX, rotatedEntranceZ, buildingOrigin
        );
    }

    /**
     * Gets the Y coordinate for this position type at the given XZ position.
     * Each validator implements this differently (surface height, random Y, water level, etc.)
     * @param random seeded random for deterministic selection when needed
     */
    protected abstract int getYAtPosition(ServerLevel level, int x, int z, SpawnRule rule, Random random);

    /**
     * Validates that the building can be placed at this position.
     * Called after chunk loading is verified.
     * @param ctx the placement context with pre-calculated values
     * @return PlacementResult indicating if valid or why not
     */
    protected abstract PlacementResult validatePlacement(PlacementContext ctx);
}
