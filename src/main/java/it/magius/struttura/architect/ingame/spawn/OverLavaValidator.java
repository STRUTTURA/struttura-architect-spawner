package it.magius.struttura.architect.ingame.spawn;

import it.magius.struttura.architect.ingame.model.PositionType;
import it.magius.struttura.architect.ingame.model.SpawnRule;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;

/**
 * Validator for OVER_LAVA position type.
 * Building is placed with its anchor exactly one block above lava surface.
 * The entire footprint (bounds X and Z) must have lava directly below at the anchor Y level.
 */
public class OverLavaValidator extends AbstractPositionValidator {

    public static final OverLavaValidator INSTANCE = new OverLavaValidator();

    private OverLavaValidator() {}

    @Override
    public PositionType getPositionType() {
        return PositionType.OVER_LAVA;
    }

    @Override
    protected int getYAtPosition(ServerLevel level, int x, int z, SpawnRule rule, Random random) {
        // Find lava surface height at this position (one block above lava)
        // Search within the rule's Y range to handle both surface and underground lava
        return getLavaSurfaceHeight(level, x, z, rule.getY1(), rule.getY2());
    }

    @Override
    protected PlacementResult validatePlacement(PlacementContext ctx) {
        int anchorY = ctx.entrancePos().getY();
        int lavaY = anchorY - 1; // Lava should be directly below the anchor

        // Check entire footprint has lava at lavaY
        for (int x = ctx.buildingOrigin().getX(); x < ctx.buildingOrigin().getX() + ctx.effectiveWidth(); x++) {
            for (int z = ctx.buildingOrigin().getZ(); z < ctx.buildingOrigin().getZ() + ctx.effectiveDepth(); z++) {
                BlockPos lavaPos = new BlockPos(x, lavaY, z);
                BlockState state = ctx.level().getBlockState(lavaPos);

                if (!isLava(state)) {
                    return PlacementResult.fail(String.format(
                        "No lava at %s (found: %s)",
                        lavaPos.toShortString(), state.getBlock().getName().getString()));
                }

                // Also check that the block above lava (where building sits) is air
                BlockPos abovePos = new BlockPos(x, anchorY, z);
                BlockState aboveState = ctx.level().getBlockState(abovePos);
                if (!aboveState.isAir()) {
                    return PlacementResult.fail(String.format(
                        "Non-air block above lava at %s (found: %s)",
                        abovePos.toShortString(), aboveState.getBlock().getName().getString()));
                }
            }
        }

        return PlacementResult.ok();
    }

    /**
     * Finds the lava surface height at the given position within the specified Y range.
     * Scans down from maxY to find the topmost lava block with air above it,
     * then returns Y + 1 (one block above lava).
     *
     * This handles both surface lava lakes and underground lava (caves, Nether).
     *
     * @param minY minimum Y to search (from spawn rule)
     * @param maxY maximum Y to search (from spawn rule)
     * @return Y coordinate one block above lava surface, or -1 if no suitable lava found
     */
    private int getLavaSurfaceHeight(ServerLevel level, int x, int z, int minY, int maxY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, maxY, z);

        // Scan down from maxY looking for lava with air above it
        // This pattern finds underground lava lakes in caves
        while (pos.getY() >= minY) {
            BlockState state = level.getBlockState(pos);

            if (isLava(state)) {
                // Found lava - check if there's air above it (a valid lava surface)
                BlockPos abovePos = pos.above();
                BlockState aboveState = level.getBlockState(abovePos);

                if (aboveState.isAir()) {
                    // This is a valid lava surface with air above
                    return pos.getY() + 1;
                }
                // Lava but no air above - keep searching down
            }

            pos.setY(pos.getY() - 1);
        }

        return -1; // No suitable lava surface found
    }

    /**
     * Checks if a block is lava.
     */
    private boolean isLava(BlockState state) {
        return state.is(Blocks.LAVA);
    }
}
