package com.simpleforapanda.privatechests.service;

import com.simpleforapanda.privatechests.PrivateChests;
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

/**
 * Service for checking if a block is protected (either a locked container or its private sign).
 */
public class ProtectionService {

    /**
     * Check if a block position is protected from destruction.
     * Returns true if it's either:
     * - A locked container
     * - A private sign for a locked container
     */
    public static boolean isProtected(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }

        MinecraftServer server = serverLevel.getServer();
        LockState lockState = LockState.get(server);
        BlockState state = level.getBlockState(pos);

        // Check if this is a locked container
        if (ContainerUtils.isLockableContainer(state)) {
            return isContainerProtected(lockState, pos, server);
        }

        // Check if this is a private sign
        if (SignUtils.isWallSign(state)) {
            return isSignProtected(level, lockState, pos, server);
        }

        return false;
    }

    /**
     * Check if a container is protected.
     */
    private static boolean isContainerProtected(LockState lockState, BlockPos pos, MinecraftServer server) {
        Optional<LockRecord> lockOpt = lockState.getLock(pos);
        if (lockOpt.isEmpty()) {
            return false;
        }

        LockRecord lock = lockOpt.get();

        // Check if owner is banned
        if (AccessControlService.shouldDisableProtectionForBannedOwner(server, lock)) {
            return false; // Not protected if owner is banned
        }

        return true;
    }

    /**
     * Check if a sign is a protected private sign.
     */
    private static boolean isSignProtected(Level level, LockState lockState, BlockPos signPos, MinecraftServer server) {
        // Check what block the sign is attached to
        Optional<BlockPos> attachedPos = SignUtils.getAttachedBlock(level, signPos);
        if (attachedPos.isEmpty()) {
            return false;
        }

        // Check if there's a lock on the attached container
        Optional<LockRecord> lockOpt = lockState.getLock(attachedPos.get());
        if (lockOpt.isEmpty()) {
            return false;
        }

        LockRecord lock = lockOpt.get();

        // Check if this is the private sign for this lock
        if (!lock.getSignPos().equals(signPos)) {
            return false;
        }

        // Check if owner is banned
        if (AccessControlService.shouldDisableProtectionForBannedOwner(server, lock)) {
            return false; // Not protected if owner is banned
        }

        return true;
    }
}
