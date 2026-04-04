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
import java.util.Set;

/**
 * Service for checking if a block is protected from destruction.
 * Returns true for locked containers and their private signs.
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
            return isContainerProtected(level, lockState, pos, server);
        }

        // Check if this is a private sign
        if (SignUtils.isWallSign(state)) {
            return isSignProtected(level, lockState, pos, server);
        }

        return false;
    }

    /**
     * Check if a container is protected.
     * Iterates the full container group so that both halves of a double chest
     * are protected even if only one half is registered in the lock record.
     */
    private static boolean isContainerProtected(Level level, LockState lockState, BlockPos pos, MinecraftServer server) {
        Set<BlockPos> containerGroup = ContainerUtils.getContainerGroup(level, pos);

        Optional<LockRecord> lockOpt = Optional.empty();
        for (BlockPos groupPos : containerGroup) {
            lockOpt = lockState.getLock(groupPos);
            if (lockOpt.isPresent()) {
                break;
            }
        }

        if (lockOpt.isEmpty()) {
            return false;
        }

        return !isOwnerBanned(server, lockOpt.get());
    }

    /**
     * Check if a sign is a protected private sign.
     * Iterates the full container group of the attached block.
     */
    private static boolean isSignProtected(Level level, LockState lockState, BlockPos signPos, MinecraftServer server) {
        Optional<BlockPos> attachedPos = SignUtils.getAttachedBlock(level, signPos);
        if (attachedPos.isEmpty()) {
            return false;
        }

        Set<BlockPos> containerGroup = ContainerUtils.getContainerGroup(level, attachedPos.get());

        for (BlockPos groupPos : containerGroup) {
            Optional<LockRecord> lockOpt = lockState.getLock(groupPos);
            if (lockOpt.isPresent()) {
                LockRecord lock = lockOpt.get();

                if (!lock.getSignPos().equals(signPos)) {
                    continue;
                }

                return !isOwnerBanned(server, lock);
            }
        }

        return false;
    }

    /**
     * Returns true when the owner is banned AND the config says protection should
     * be disabled for banned owners.  Works for offline players.
     */
    private static boolean isOwnerBanned(MinecraftServer server, LockRecord lock) {
        return AccessControlService.shouldDisableProtectionForBannedOwner(server, lock);
    }
}
