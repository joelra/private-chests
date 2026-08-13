package com.simpleforapanda.privatechests.event;

import com.simpleforapanda.privatechests.PrivateChests;
import com.simpleforapanda.privatechests.model.DormantSignRecord;
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

    private static boolean onBlockBreak(
        Level level,
        net.minecraft.world.entity.player.Player player,
        BlockPos pos,
        BlockState state,
        BlockEntity blockEntity
    ) {
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return true;
        }

        LockState lockState = LockState.get(serverLevel.getServer());

        if (ContainerUtils.isLockableContainer(state)) {
            return handleContainerBreak(serverPlayer, pos, serverLevel, lockState);
        }

        if (SignUtils.isWallSign(state)) {
            return handleSignBreak(serverPlayer, level, pos, lockState);
        }

        return true;
    }

    private static boolean handleContainerBreak(ServerPlayer player, BlockPos pos, ServerLevel level, LockState lockState) {
        Set<BlockPos> containerGroup = ContainerUtils.getContainerGroup(level, pos);
        Optional<LockRecord> lockOpt = lockState.getLock(level, containerGroup);
        if (lockOpt.isEmpty()) {
            return true;
        }

        LockRecord lock = lockOpt.get();
        if (isOwnerBanned(player, lock)) {
            return true;
        }

        if (player.getUUID().equals(lock.getOwnerUuid()) || AccessControlService.isAdmin(player)) {
            lockState.removeLock(lock);
            PrivateChests.LOGGER.info("Player {} broke their locked container at {}, lock removed", player.getName().getString(), pos);
            player.sendSystemMessage(Component.literal("Locked container broken. Lock has been removed."));
            return true;
        }

        player.sendSystemMessage(Component.literal("Cannot break someone else's locked container."));
        BlockState containerState = player.level().getBlockState(pos);
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(pos, containerState));

        BlockEntity containerEntity = player.level().getBlockEntity(pos);
        if (containerEntity != null) {
            player.connection.send(net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(containerEntity));
        }

        return false;
    }

    private static boolean handleSignBreak(ServerPlayer player, Level level, BlockPos signPos, LockState lockState) {
        Optional<DormantSignRecord> dormantSign = lockState.getDormantSign(level, signPos);
        if (dormantSign.isPresent()) {
            return handleDormantSignBreak(player, dormantSign.get());
        }

        Optional<BlockPos> attachedPos = SignUtils.getAttachedBlock(level, signPos);
        if (attachedPos.isEmpty()) {
            return true;
        }

        Set<BlockPos> containerGroup = ContainerUtils.getContainerGroup(level, attachedPos.get());
        Optional<LockRecord> lockOpt = lockState.getLock(level, containerGroup);
        if (lockOpt.isPresent() && lockOpt.get().getSignPos().equals(signPos)) {
            return handleProtectedSignBreak(player, lockOpt.get());
        }

        return true;
    }

    private static boolean handleProtectedSignBreak(ServerPlayer player, LockRecord lock) {
        if (isOwnerBanned(player, lock)) {
            return true;
        }

        if (AccessControlService.isAdmin(player)) {
            PrivateChests.LOGGER.info("Admin {} broke protected sign at {}", player.getName().getString(), lock.getSignPos());
            return true;
        }

        if (player.getUUID().equals(lock.getOwnerUuid())) {
            return true;
        }

        player.sendSystemMessage(Component.literal("You cannot break someone else's protected sign."));
        return false;
    }

    private static boolean handleDormantSignBreak(ServerPlayer player, DormantSignRecord dormantSign) {
        if (AccessControlService.isAdmin(player) || player.getUUID().equals(dormantSign.getOwnerUuid())) {
            return true;
        }

        player.sendSystemMessage(Component.literal("You cannot break someone else's protected sign."));
        return false;
    }

    private static boolean isOwnerBanned(ServerPlayer player, LockRecord lock) {
        var server = player.level().getServer();
        return AccessControlService.shouldDisableProtectionForBannedOwner(server, lock);
    }
}
