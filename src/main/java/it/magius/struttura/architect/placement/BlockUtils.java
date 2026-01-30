package it.magius.struttura.architect.placement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility methods for block operations, particularly for multi-block structures.
 */
public class BlockUtils {

    /**
     * Gets all block positions that form part of a multi-block structure
     * starting from the given position. This handles:
     * - Doors (2 blocks tall)
     * - Beds (2 blocks wide)
     * - Double plants (tall grass, sunflower, etc.)
     * - Double chests (2 blocks wide)
     * - Pistons with extended heads
     *
     * @param level The level to check blocks in
     * @param pos The starting position
     * @return List of all positions that form the multi-block structure (including the original)
     */
    public static List<BlockPos> getMultiBlockPositions(Level level, BlockPos pos) {
        List<BlockPos> positions = new ArrayList<>();
        positions.add(pos);

        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();

        // Handle doors (2 blocks tall)
        if (block instanceof DoorBlock) {
            addDoorPositions(level, pos, state, positions);
        }
        // Handle beds (2 blocks wide)
        else if (block instanceof BedBlock) {
            addBedPositions(level, pos, state, positions);
        }
        // Handle double plants (tall grass, sunflower, rose bush, peony, lilac, large fern, pitcher plant)
        else if (block instanceof DoublePlantBlock) {
            addDoublePlantPositions(level, pos, state, positions);
        }
        // Handle double chests
        else if (block instanceof ChestBlock) {
            addDoubleChestPositions(level, pos, state, positions);
        }
        // Handle extended pistons
        else if (block instanceof PistonHeadBlock) {
            addPistonPositions(level, pos, state, positions);
        }

        return positions;
    }

    /**
     * Adds the other half of a door to the positions list.
     */
    private static void addDoorPositions(Level level, BlockPos pos, BlockState state, List<BlockPos> positions) {
        DoubleBlockHalf half = state.getValue(DoorBlock.HALF);
        BlockPos otherPos;

        if (half == DoubleBlockHalf.LOWER) {
            otherPos = pos.above();
        } else {
            otherPos = pos.below();
        }

        // Verify the other half is also a door
        BlockState otherState = level.getBlockState(otherPos);
        if (otherState.getBlock() instanceof DoorBlock) {
            positions.add(otherPos);
        }
    }

    /**
     * Adds the other half of a bed to the positions list.
     */
    private static void addBedPositions(Level level, BlockPos pos, BlockState state, List<BlockPos> positions) {
        BedPart part = state.getValue(BedBlock.PART);
        Direction facing = state.getValue(BedBlock.FACING);
        BlockPos otherPos;

        if (part == BedPart.FOOT) {
            // Foot is at pos, head is in the facing direction
            otherPos = pos.relative(facing);
        } else {
            // Head is at pos, foot is opposite to facing
            otherPos = pos.relative(facing.getOpposite());
        }

        // Verify the other half is also a bed
        BlockState otherState = level.getBlockState(otherPos);
        if (otherState.getBlock() instanceof BedBlock) {
            positions.add(otherPos);
        }
    }

    /**
     * Adds the other half of a double plant to the positions list.
     */
    private static void addDoublePlantPositions(Level level, BlockPos pos, BlockState state, List<BlockPos> positions) {
        DoubleBlockHalf half = state.getValue(DoublePlantBlock.HALF);
        BlockPos otherPos;

        if (half == DoubleBlockHalf.LOWER) {
            otherPos = pos.above();
        } else {
            otherPos = pos.below();
        }

        // Verify the other half is also a double plant
        BlockState otherState = level.getBlockState(otherPos);
        if (otherState.getBlock() instanceof DoublePlantBlock) {
            positions.add(otherPos);
        }
    }

    /**
     * Adds the other half of a double chest to the positions list.
     */
    private static void addDoubleChestPositions(Level level, BlockPos pos, BlockState state, List<BlockPos> positions) {
        ChestType type = state.getValue(ChestBlock.TYPE);

        // Single chests don't have another half
        if (type == ChestType.SINGLE) {
            return;
        }

        Direction facing = state.getValue(ChestBlock.FACING);
        Direction otherDirection;

        // For LEFT type, the other chest is to the right when looking at the front
        // For RIGHT type, the other chest is to the left when looking at the front
        if (type == ChestType.LEFT) {
            otherDirection = facing.getClockWise();
        } else {
            otherDirection = facing.getCounterClockWise();
        }

        BlockPos otherPos = pos.relative(otherDirection);

        // Verify the other half is also a chest
        BlockState otherState = level.getBlockState(otherPos);
        if (otherState.getBlock() instanceof ChestBlock) {
            positions.add(otherPos);
        }
    }

    /**
     * Adds the piston base for a piston head.
     */
    private static void addPistonPositions(Level level, BlockPos pos, BlockState state, List<BlockPos> positions) {
        Direction facing = state.getValue(PistonHeadBlock.FACING);

        // The base is behind the head (opposite to facing direction)
        BlockPos basePos = pos.relative(facing.getOpposite());

        // Verify it's actually a piston base
        BlockState baseState = level.getBlockState(basePos);
        if (baseState.getBlock() instanceof net.minecraft.world.level.block.piston.PistonBaseBlock) {
            positions.add(basePos);
        }
    }

    /**
     * Checks if a block is part of a multi-block structure.
     *
     * @param state The block state to check
     * @return true if the block can be part of a multi-block structure
     */
    public static boolean isMultiBlock(BlockState state) {
        Block block = state.getBlock();
        return block instanceof DoorBlock
            || block instanceof BedBlock
            || block instanceof DoublePlantBlock
            || block instanceof ChestBlock
            || block instanceof PistonHeadBlock;
    }
}
