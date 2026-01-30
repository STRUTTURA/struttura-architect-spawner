package it.magius.struttura.architect.ingame.spawn;

import it.magius.struttura.architect.ingame.model.PositionType;
import it.magius.struttura.architect.ingame.model.SpawnRule;
import it.magius.struttura.architect.ingame.model.SpawnableBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Validator for ON_GROUND position type.
 * Building is placed with its entrance anchor on solid ground.
 * The spawn position represents where the entrance will be placed.
 */
public class OnGroundValidator implements PositionValidator {

    public static final OnGroundValidator INSTANCE = new OnGroundValidator();

    private OnGroundValidator() {}

    @Override
    public PositionType getPositionType() {
        return PositionType.ON_GROUND;
    }

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

            // Get surface height, ignoring trees (logs and leaves)
            int surfaceY = getGroundHeight(level, worldX, worldZ);

            // Check Y range
            if (surfaceY < rule.getY1() || surfaceY > rule.getY2()) {
                failureReasons.add(String.format("Quadrant [%d,%d] at (%d,%d): Y=%d outside range [%d-%d]",
                    quadrant[0], quadrant[1], worldX, worldZ, surfaceY, rule.getY1(), rule.getY2()));
                continue;
            }

            // Try all 4 rotations
            int[] rotations = {0, 90, 180, 270};
            for (int rotation : rotations) {
                BlockPos spawnPos = new BlockPos(worldX, surfaceY, worldZ);

                String placementResult = checkPlacementWithReason(level, spawnPos, building, rotation, margin);
                if (placementResult == null) {
                    return FindResult.success(SpawnPosition.at(spawnPos, rotation));
                } else {
                    failureReasons.add(String.format("Pos %s rot %d°: %s",
                        spawnPos.toShortString(), rotation, placementResult));
                }
            }
        }

        return FindResult.failure(failureReasons);
    }

    /**
     * Checks if a building can be placed at the given position with the given rotation.
     * The entrancePos is where the entrance anchor will be placed.
     * Buildings can span multiple chunks as long as all required chunks are loaded.
     * @return null if valid, or a string describing why it's invalid
     */
    private String checkPlacementWithReason(ServerLevel level, BlockPos entrancePos,
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

        // Check that all chunks the building will occupy are loaded
        String chunkCheck = PositionValidator.checkChunksLoaded(level, buildingOrigin, effectiveWidth, effectiveDepth, margin);
        if (chunkCheck != null) {
            return chunkCheck;
        }

        // Check corners are on solid ground
        int[] cornerXOffsets = {0, effectiveWidth - 1, 0, effectiveWidth - 1};
        int[] cornerZOffsets = {0, 0, effectiveDepth - 1, effectiveDepth - 1};
        String[] cornerNames = {"NW", "NE", "SW", "SE"};

        for (int i = 0; i < 4; i++) {
            int cx = buildingOriginX + cornerXOffsets[i];
            int cz = buildingOriginZ + cornerZOffsets[i];

            int cornerY = getGroundHeight(level, cx, cz);

            // Check ground block is solid (and not a tree log)
            BlockPos groundPos = new BlockPos(cx, cornerY - 1, cz);
            BlockState groundState = level.getBlockState(groundPos);
            if (!groundState.isSolid()) {
                return String.format("Corner %s ground not solid at %s (block: %s)",
                    cornerNames[i], groundPos.toShortString(), groundState.getBlock().getName().getString());
            }
            if (isTreeBlock(groundState)) {
                return String.format("Corner %s ground is tree at %s (block: %s)",
                    cornerNames[i], groundPos.toShortString(), groundState.getBlock().getName().getString());
            }
        }

        return null; // Valid placement
    }

    /**
     * Gets the ground height at the given position, ignoring tree blocks (logs and leaves).
     * Starts from the heightmap and descends until finding actual ground.
     */
    private int getGroundHeight(ServerLevel level, int x, int z) {
        // Start from heightmap (ignores leaves)
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);

        // Descend through tree logs until we hit actual ground
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, y - 1, z);
        while (pos.getY() > level.getMinY()) {
            BlockState state = level.getBlockState(pos);

            if (state.isAir()) {
                // Air block, continue descending
                pos.setY(pos.getY() - 1);
                continue;
            }

            if (isTreeBlock(state)) {
                // Tree block (log, wood, leaves), continue descending
                pos.setY(pos.getY() - 1);
                continue;
            }

            // Found actual ground - return Y above this block
            return pos.getY() + 1;
        }

        // Reached world bottom, return original height
        return y;
    }

    /**
     * Checks if a block is part of a tree (logs, wood, or leaves).
     */
    private boolean isTreeBlock(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES);
    }
}
