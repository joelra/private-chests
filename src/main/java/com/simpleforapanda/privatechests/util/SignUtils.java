package com.simpleforapanda.privatechests.util;

import com.simpleforapanda.privatechests.model.AccessMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Utility methods for working with signs and detecting protection markers.
 */
public class SignUtils {
    public static boolean isWallSign(BlockState state) {
        return state.getBlock() instanceof WallSignBlock;
    }

    public static boolean isProtectionSign(Level level, BlockPos signPos) {
        BlockState state = level.getBlockState(signPos);
        if (!isWallSign(state)) {
            return false;
        }

        BlockEntity blockEntity = level.getBlockEntity(signPos);
        if (!(blockEntity instanceof SignBlockEntity signEntity)) {
            return false;
        }

        return getAccessMode(signEntity, true).isPresent() || getAccessMode(signEntity, false).isPresent();
    }

    public static boolean isPrivateSign(Level level, BlockPos signPos) {
        return isProtectionSign(level, signPos);
    }

    public static Optional<AccessMode> getAccessMode(SignBlockEntity signEntity, boolean isFront) {
        return AccessMode.fromSign(signEntity, isFront);
    }

    public static Optional<AccessMode> getAccessMode(java.util.List<net.minecraft.server.network.FilteredText> lines) {
        return AccessMode.fromLines(lines);
    }

    public static boolean containsPrivateMarker(SignBlockEntity signEntity, boolean isFront) {
        return getAccessMode(signEntity, isFront).filter(AccessMode::isPrivate).isPresent();
    }

    public static boolean containsPrivateMarker(java.util.List<net.minecraft.server.network.FilteredText> lines) {
        return getAccessMode(lines).filter(AccessMode::isPrivate).isPresent();
    }

    public static boolean containsProtectionMarker(SignBlockEntity signEntity, boolean isFront) {
        return getAccessMode(signEntity, isFront).isPresent();
    }

    public static boolean containsProtectionMarker(java.util.List<net.minecraft.server.network.FilteredText> lines) {
        return getAccessMode(lines).isPresent();
    }

    public static Optional<BlockPos> getAttachedBlock(Level level, BlockPos signPos) {
        BlockState state = level.getBlockState(signPos);
        if (!isWallSign(state)) {
            return Optional.empty();
        }

        Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        return Optional.of(signPos.relative(facing.getOpposite()));
    }

    public static Set<String> extractAllowedUsers(SignBlockEntity signEntity) {
        Set<String> users = new HashSet<>();

        int frontStartLine = containsProtectionMarker(signEntity, true) ? 1 : 0;
        for (int i = frontStartLine; i < 4; i++) {
            extractUsernamesFromLine(signEntity.getText(true).getMessage(i, false).getString().trim(), users);
        }

        int backStartLine = containsProtectionMarker(signEntity, false) ? 1 : 0;
        for (int i = backStartLine; i < 4; i++) {
            extractUsernamesFromLine(signEntity.getText(false).getMessage(i, false).getString().trim(), users);
        }

        return users;
    }

    public static Set<String> extractAllowedUsers(java.util.List<net.minecraft.server.network.FilteredText> lines) {
        Set<String> users = new HashSet<>();
        int startLine = containsProtectionMarker(lines) ? 1 : 0;
        for (int i = startLine; i < Math.min(lines.size(), 4); i++) {
            extractUsernamesFromLine(lines.get(i).raw().trim(), users);
        }
        return users;
    }

    private static void extractUsernamesFromLine(String line, Set<String> users) {
        if (line.isEmpty()) {
            return;
        }

        String[] parts = line.split(",");
        for (String part : parts) {
            String username = part.trim();
            if (!username.isEmpty() && AccessMode.fromLine(username).isEmpty()) {
                users.add(username);
            }
        }
    }

    public static boolean isAttachedToContainer(Level level, BlockPos signPos, BlockPos containerPos) {
        Optional<BlockPos> attached = getAttachedBlock(level, signPos);
        if (attached.isEmpty()) {
            return false;
        }

        if (attached.get().equals(containerPos)) {
            return true;
        }

        Set<BlockPos> containerGroup = ContainerUtils.getContainerGroup(level, containerPos);
        return containerGroup.contains(attached.get());
    }

    public static boolean isValidProtectionSign(Level level, BlockPos signPos, Set<BlockPos> containerPositions) {
        BlockState state = level.getBlockState(signPos);
        if (!isWallSign(state) || !isProtectionSign(level, signPos)) {
            return false;
        }

        Optional<BlockPos> attached = getAttachedBlock(level, signPos);
        return attached.isPresent() && containerPositions.contains(attached.get());
    }

    public static boolean isValidPrivateSign(Level level, BlockPos signPos, Set<BlockPos> containerPositions) {
        return isValidProtectionSign(level, signPos, containerPositions);
    }

    public static Set<BlockPos> findAttachedSigns(Level level, Set<BlockPos> containerPositions) {
        Set<BlockPos> signs = new HashSet<>();

        for (BlockPos containerPos : containerPositions) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos signPos = containerPos.relative(direction);
                BlockState signState = level.getBlockState(signPos);

                if (isWallSign(signState)) {
                    Direction signFacing = signState.getValue(BlockStateProperties.HORIZONTAL_FACING);
                    if (signFacing == direction.getOpposite()) {
                        signs.add(signPos);
                    }
                }
            }
        }

        return signs;
    }
}
