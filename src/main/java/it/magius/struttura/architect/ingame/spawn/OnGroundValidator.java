package it.magius.struttura.architect.ingame.spawn;

import it.magius.struttura.architect.ingame.model.PositionType;
import it.magius.struttura.architect.ingame.model.SpawnRule;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Random;

/**
 * Validator for ON_GROUND position type.
 * Building is placed with its entrance anchor on solid ground.
 * The spawn position represents where the entrance will be placed.
 */
public class OnGroundValidator extends AbstractPositionValidator {

    public static final OnGroundValidator INSTANCE = new OnGroundValidator();

    private OnGroundValidator() {}

    @Override
    public PositionType getPositionType() {
        return PositionType.ON_GROUND;
    }

    @Override
    protected int getYAtPosition(ServerLevel level, int x, int z, SpawnRule rule, Random random) {
        // getGroundHeight returns the Y of the first air block above ground.
        // Subtract 1 so the entrance anchor is placed ON the ground block itself.
        return getGroundHeight(level, x, z) - 1;
    }

    @Override
    protected PlacementResult validatePlacement(PlacementContext ctx) {
        // Check corners are on solid ground
        int[] cornerXOffsets = {0, ctx.effectiveWidth() - 1, 0, ctx.effectiveWidth() - 1};
        int[] cornerZOffsets = {0, 0, ctx.effectiveDepth() - 1, ctx.effectiveDepth() - 1};
        String[] cornerNames = {"NW", "NE", "SW", "SE"};

        for (int i = 0; i < 4; i++) {
            int cx = ctx.buildingOrigin().getX() + cornerXOffsets[i];
            int cz = ctx.buildingOrigin().getZ() + cornerZOffsets[i];

            int cornerY = getGroundHeight(ctx.level(), cx, cz);

            // Check ground block is solid (and not a tree log)
            BlockPos groundPos = new BlockPos(cx, cornerY - 1, cz);
            BlockState groundState = ctx.level().getBlockState(groundPos);
            if (!groundState.isSolid()) {
                return PlacementResult.fail(String.format("Corner %s ground not solid at %s (block: %s)",
                    cornerNames[i], groundPos.toShortString(), groundState.getBlock().getName().getString()));
            }
            if (isTreeBlock(groundState)) {
                return PlacementResult.fail(String.format("Corner %s ground is tree at %s (block: %s)",
                    cornerNames[i], groundPos.toShortString(), groundState.getBlock().getName().getString()));
            }
        }

        return PlacementResult.ok();
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
