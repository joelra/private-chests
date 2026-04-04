package com.simpleforapanda.privatechests.event;

import com.simpleforapanda.privatechests.PrivateChests;
import com.simpleforapanda.privatechests.model.LockRecord;
import com.simpleforapanda.privatechests.service.AccessControlService;
import com.simpleforapanda.privatechests.state.LockState;
import com.simpleforapanda.privatechests.util.ContainerUtils;
import com.simpleforapanda.privatechests.util.SignUtils;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;
import java.util.Set;

/**
 * Handles block break events for protected containers and signs.
 */
public class BlockBreakHandler {

    public static void register() {
        PlayerBlockBreakEvents.BEFORE.register(BlockBreakHandler::onBlockBreak);
    }

    /**
     * Called before a player breaks a block.
     * Return false to cancel the break.
     */
    private static boolean onBlockBreak(Level level, net.minecraft.world.entity.player.Player player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return true;
        }

        if (!(player instanceof ServerPlayer serverPlayer)) {
            return true;
        }

        LockState lockState = LockState.get(serverLevel.getServer());

        // Check if this is a lockable container
        if (ContainerUtils.isLockableContainer(state)) {
            return handleContainerBreak(serverPlayer, pos, state, serverLevel, lockState);
        }

        // Check if this is a wall sign
        if (SignUtils.isWallSign(state)) {
            return handleSignBreak(serverPlayer, level, pos, lockState);
        }

        return true;
    }

    /**
     * Handle breaking a container block.
     */
    private static boolean handleContainerBreak(ServerPlayer player, BlockPos pos, BlockState state, ServerLevel level, LockState lockState) {
        // Get the full container group (handles double chests)
        // This ensures we find locks even when breaking the unlocked half
        Set<BlockPos> containerGroup = ContainerUtils.getContainerGroup(level, pos);

        // Check if ANY part of the container group has a lock
        Optional<LockRecord> lockOpt = Optional.empty();
        for (BlockPos containerPos : containerGroup) {
            lockOpt = lockState.getLock(containerPos);
            if (lockOpt.isPresent()) {
                break;
            }
        }

        if (lockOpt.isEmpty()) {
            return true; // Not locked, allow break
        }

        LockRecord lock = lockOpt.get();

        // Check if owner is banned
        if (isOwnerBanned(player, lock)) {
            return true; // Allow break if owner is banned (configurable)
        }

        // Check if player is the owner or admin
        if (player.getUUID().equals(lock.getOwnerUuid()) || AccessControlService.isAdmin(player)) {
            // Owner/admin can break the chest - automatically remove the lock
            lockState.removeLock(pos);
            PrivateChests.LOGGER.info("Player {} broke their locked container at {}, lock removed",
                player.getName().getString(), pos);
            player.sendSystemMessage(Component.literal(
                "Locked container broken. Lock has been removed."
            ));
            return true;
        }

        // Deny breaking locked containers for non-owners
        player.sendSystemMessage(Component.literal(
            "Cannot break someone else's locked container."
        ));

        // Resync the block to the client to prevent ghost block
        BlockState containerState = player.level().getBlockState(pos);
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(pos, containerState));

        // Resync the container entity data if it exists
        BlockEntity containerEntity = player.level().getBlockEntity(pos);
        if (containerEntity != null) {
            player.connection.send(net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(containerEntity));
        }

        return false;
    }

    /**
     * Handle breaking a sign.
     */
    private static boolean handleSignBreak(ServerPlayer player, Level level, BlockPos signPos, LockState lockState) {
        // Check if this sign is a private sign attached to any locked container
        Optional<BlockPos> attachedPos = SignUtils.getAttachedBlock(level, signPos);
        if (attachedPos.isEmpty()) {
            return true; // Not attached to anything
        }

        // Check if the attached block or its group has a lock
        Set<BlockPos> containerGroup = ContainerUtils.getContainerGroup(level, attachedPos.get());

        for (BlockPos containerPos : containerGroup) {
            Optional<LockRecord> lockOpt = lockState.getLock(containerPos);
            if (lockOpt.isPresent()) {
                LockRecord lock = lockOpt.get();

                // Check if this is the private sign for this lock
                if (lock.getSignPos().equals(signPos)) {
                    return handlePrivateSignBreak(player, lock);
                }
            }
        }

        return true; // Not a private sign, allow break
    }

    /**
     * Handle breaking a private sign.
     */
    private static boolean handlePrivateSignBreak(ServerPlayer player, LockRecord lock) {
        // Check if owner is banned
        if (isOwnerBanned(player, lock)) {
            return true; // Allow break if owner is banned
        }

        // Check admin bypass
        if (AccessControlService.isAdmin(player)) {
            PrivateChests.LOGGER.info("Admin {} broke private sign at {}", player.getName().getString(), lock.getSignPos());
            return true;
        }

        // Check if player is the owner
        if (player.getUUID().equals(lock.getOwnerUuid())) {
            return true; // Owner can break their own sign
        }

        // Deny breaking for non-owners
        // Note: SignBreakPacketMixin also handles this at the packet level,
        // but this event handler provides additional protection
        player.sendSystemMessage(Component.literal(
            "You cannot break someone else's [private] sign."
        ));

        return false;
    }

    /**
     * Check if the owner of a lock is banned and protection should be disabled.
     */
    private static boolean isOwnerBanned(ServerPlayer player, LockRecord lock) {
        var server = player.level().getServer();
        var ownerPlayer = server.getPlayerList().getPlayer(lock.getOwnerUuid());
        if (ownerPlayer != null) {
            boolean isBanned = server.getPlayerList().getBans().isBanned(ownerPlayer.getGameProfile());
            if (isBanned && PrivateChests.getConfig().isDisableProtectionIfOwnerBanned()) {
                return true;
            }
        }

        return false;
    }
}
