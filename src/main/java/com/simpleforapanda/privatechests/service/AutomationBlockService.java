package com.simpleforapanda.privatechests.service;

import com.simpleforapanda.privatechests.model.LockRecord;
import com.simpleforapanda.privatechests.state.LockState;
import com.simpleforapanda.privatechests.util.ContainerUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;
import java.util.Set;

/**
 * Service for checking if automation should be blocked for a container.
 */
public class AutomationBlockService {

    /**
     * Check if automation (hoppers, etc.) should be blocked for a position.
     */
    public static boolean isAutomationBlocked(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }

        BlockState state = level.getBlockState(pos);

        // Check if this is a lockable container
        if (!ContainerUtils.isLockableContainer(state)) {
            return false;
        }

        MinecraftServer server = serverLevel.getServer();
        LockState lockState = LockState.get(server);

        Set<BlockPos> containerGroup = ContainerUtils.getContainerGroup(level, pos);
        Optional<LockRecord> lockOpt = lockState.getLock(level, containerGroup);
        if (lockOpt.isEmpty()) {
            return false; // Not locked, allow automation
        }

        LockRecord lock = lockOpt.get();

        // Check if owner is banned
        return !AccessControlService.shouldDisableProtectionForBannedOwner(server, lock);

        // Container is locked, block automation
    }
}
