package com.simpleforapanda.privatechests.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.HashSet;
import java.util.Set;

/**
 * Utility methods for working with containers (chests and barrels).
 */
public class ContainerUtils {

    /**
     * Check if a block is a lockable container.
     */
    public static boolean isLockableContainer(Block block) {
        return block instanceof ChestBlock ||
               block instanceof BarrelBlock;
    }

    /**
     * Check if a block state is a lockable container.
     */
    public static boolean isLockableContainer(BlockState state) {
        return isLockableContainer(state.getBlock());
    }

    /**
     * Get all blocks in a container group (handles double chests).
     * Returns a set containing:
     * - Single chest/barrel: just that position
     * - Double chest: both chest positions
     */
    public static Set<BlockPos> getContainerGroup(Level level, BlockPos pos) {
        Set<BlockPos> group = new HashSet<>();
        BlockState state = level.getBlockState(pos);

        if (!isLockableContainer(state)) {
            return group;
        }

        group.add(pos);

        // Check if this is a double chest
        if (state.getBlock() instanceof ChestBlock) {
            ChestType chestType = state.getValue(BlockStateProperties.CHEST_TYPE);

            if (chestType != ChestType.SINGLE) {
                // Find the other half of the double chest
                Direction facing = state.getValue(ChestBlock.FACING);
                Direction offsetDir = chestType == ChestType.LEFT ? facing.getClockWise() : facing.getCounterClockWise();
                BlockPos otherPos = pos.relative(offsetDir);

                BlockState otherState = level.getBlockState(otherPos);
                if (otherState.getBlock() instanceof ChestBlock) {
                    ChestType otherType = otherState.getValue(BlockStateProperties.CHEST_TYPE);
                    if (otherType != ChestType.SINGLE) {
                        group.add(otherPos);
                    }
                }
            }
        }

        return group;
    }

    /**
     * Get the container type name for display.
     */
    public static String getContainerTypeName(Level level, Set<BlockPos> positions) {
        if (positions.isEmpty()) {
            return "Unknown";
        }

        BlockPos firstPos = positions.iterator().next();
        BlockState state = level.getBlockState(firstPos);

        if (state.getBlock() instanceof BarrelBlock) {
            return "Barrel";
        } else if (state.getBlock() instanceof ChestBlock) {
            if (positions.size() > 1) {
                return "Double Chest";
            } else {
                return "Chest";
            }
        }

        return "Container";
    }

    /**
     * Get a human-readable position string for display.
     */
    public static String positionToString(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    /**
     * Get the primary position for a container group (for consistent display).
     * For double chests, returns the "lower" position based on coordinate comparison.
     */
    public static BlockPos getPrimaryPosition(Set<BlockPos> positions) {
        return positions.stream()
            .min((p1, p2) -> Long.compare(p1.asLong(), p2.asLong()))
            .orElseThrow();
    }
}
