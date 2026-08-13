package com.simpleforapanda.privatechests.service;

import com.simpleforapanda.privatechests.model.LockRecord;
import com.simpleforapanda.privatechests.state.LockState;
import com.simpleforapanda.privatechests.util.ContainerUtils;
import com.simpleforapanda.privatechests.util.SignUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;
import java.util.Set;

/**
 * Service for checking if a block is protected from destruction.
 */
public class ProtectionService {
    public static boolean isProtected(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }

        MinecraftServer server = serverLevel.getServer();
        LockState lockState = LockState.get(server);
        BlockState state = level.getBlockState(pos);

        if (ContainerUtils.isLockableContainer(state)) {
            return isContainerProtected(level, lockState, pos, server);
        }

        if (SignUtils.isWallSign(state)) {
            return isSignProtected(level, lockState, pos, server);
        }

        return false;
    }

    private static boolean isContainerProtected(Level level, LockState lockState, BlockPos pos, MinecraftServer server) {
        Set<BlockPos> containerGroup = ContainerUtils.getContainerGroup(level, pos);
        Optional<LockRecord> lockOpt = lockState.getLock(level, containerGroup);
        return lockOpt.isPresent() && !isOwnerBanned(server, lockOpt.get());
    }

    private static boolean isSignProtected(Level level, LockState lockState, BlockPos signPos, MinecraftServer server) {
        Optional<BlockPos> attachedPos = SignUtils.getAttachedBlock(level, signPos);
        if (attachedPos.isEmpty()) {
            return false;
        }

        Set<BlockPos> containerGroup = ContainerUtils.getContainerGroup(level, attachedPos.get());
        Optional<LockRecord> lockOpt = lockState.getLock(level, containerGroup);
        return lockOpt.isPresent()
            && lockOpt.get().getSignPos().equals(signPos)
            && !isOwnerBanned(server, lockOpt.get());
    }

    private static boolean isOwnerBanned(MinecraftServer server, LockRecord lock) {
        return AccessControlService.shouldDisableProtectionForBannedOwner(server, lock);
    }
}
