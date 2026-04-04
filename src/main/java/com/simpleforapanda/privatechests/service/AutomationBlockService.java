package com.simpleforapanda.privatechests.service;

import com.simpleforapanda.privatechests.PrivateChests;
import com.simpleforapanda.privatechests.model.LockRecord;
import com.simpleforapanda.privatechests.state.LockState;
import com.simpleforapanda.privatechests.util.ContainerUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

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

        // Check if this container is locked
        Optional<LockRecord> lockOpt = lockState.getLock(pos);
        if (lockOpt.isEmpty()) {
            return false; // Not locked, allow automation
        }

        LockRecord lock = lockOpt.get();

        // Check if owner is banned
        return !isOwnerBanned(server, lock); // Allow automation if owner is banned (configurable)

        // Container is locked, block automation
    }

    /**
     * Check if the owner of a lock is banned and protection should be disabled.
     */
    private static boolean isOwnerBanned(MinecraftServer server, LockRecord lock) {
        var player = server.getPlayerList().getPlayer(lock.getOwnerUuid());
        if (player != null) {
            boolean isBanned = server.getPlayerList().getBans().isBanned(player.getGameProfile());
            return isBanned && PrivateChests.getConfig().isDisableProtectionIfOwnerBanned();
        }

        return false;
    }
}
