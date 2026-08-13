package com.simpleforapanda.privatechests.mixin;

import com.simpleforapanda.privatechests.PrivateChests;
import com.simpleforapanda.privatechests.model.DormantSignRecord;
import com.simpleforapanda.privatechests.model.LockRecord;
import com.simpleforapanda.privatechests.service.AccessControlService;
import com.simpleforapanda.privatechests.state.LockState;
import com.simpleforapanda.privatechests.util.ContainerUtils;
import com.simpleforapanda.privatechests.util.SignUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.Set;

/**
 * Intercepts sign break attempts at the packet level.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class SignBreakPacketMixin {
    @Shadow
    public abstract ServerPlayer getPlayer();

    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void onPlayerAction(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (packet.getAction() != ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
            return;
        }

        ServerPlayer player = getPlayer();
        BlockPos pos = packet.getPos();
        Level level = player.level();
        BlockState state = level.getBlockState(pos);

        if (!SignUtils.isWallSign(state) || !isProtectedSign(player, level, pos)) {
            return;
        }

        ci.cancel();
        PrivateChests.LOGGER.info("Denied break attempt on protected sign at {} by {}", pos, player.getName().getString());
        player.sendSystemMessage(Component.literal("You cannot break someone else's protected sign."));

        try {
            BlockState currentState = level.getBlockState(pos);
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(pos, currentState));
        } catch (Exception e) {
            PrivateChests.LOGGER.error("Failed to send block update for sign at {}", pos, e);
        }

        ((ServerLevel) level).getServer().execute(() -> {
            try {
                var blockEntity = level.getBlockEntity(pos);
                if (blockEntity instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) {
                    var signUpdatePacket = sign.getUpdatePacket();
                    if (signUpdatePacket != null) {
                        player.connection.send(signUpdatePacket);
                    }

                    var entityPacket = net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(sign);
                    if (entityPacket != null) {
                        player.connection.send(entityPacket);
                    }
                }
            } catch (Exception e) {
                PrivateChests.LOGGER.error("Failed to resync sign text at {}", pos, e);
            }
        });
    }

    private boolean isProtectedSign(ServerPlayer player, Level level, BlockPos signPos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }

        LockState lockState = LockState.get(serverLevel.getServer());
        Optional<DormantSignRecord> dormantSign = lockState.getDormantSign(level, signPos);
        if (dormantSign.isPresent()) {
            return !player.getUUID().equals(dormantSign.get().getOwnerUuid()) && !AccessControlService.isAdmin(player);
        }

        Optional<BlockPos> attachedPos = SignUtils.getAttachedBlock(level, signPos);
        if (attachedPos.isEmpty()) {
            return false;
        }

        Set<BlockPos> containerGroup = ContainerUtils.getContainerGroup(level, attachedPos.get());
        Optional<LockRecord> lockOpt = lockState.getLock(level, containerGroup);
        if (lockOpt.isPresent() && lockOpt.get().getSignPos().equals(signPos)) {
            boolean isOwner = player.getUUID().equals(lockOpt.get().getOwnerUuid());
            boolean isAdmin = AccessControlService.isAdmin(player);
            return !isOwner && !isAdmin;
        }

        return false;
    }
}
