package com.simpleforapanda.privatechests.event;

import com.simpleforapanda.privatechests.PrivateChests;
import com.simpleforapanda.privatechests.model.DormantSignRecord;
import com.simpleforapanda.privatechests.model.LockRecord;
import com.simpleforapanda.privatechests.service.AccessControlService;
import com.simpleforapanda.privatechests.service.SignEditService;
import com.simpleforapanda.privatechests.state.LockState;
import com.simpleforapanda.privatechests.util.ContainerUtils;
import com.simpleforapanda.privatechests.util.SignUtils;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SignItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Optional;
import java.util.Set;

/**
 * Handles container access and placement events.
 */
public class ContainerEventHandler {
    public static void register() {
        UseBlockCallback.EVENT.register(ContainerEventHandler::onUseBlock);
    }

    private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hitResult) {
        // Both hands must be checked: sneak-placing from the off-hand would
        // otherwise bypass the sign- and chest-placement restrictions below.
        if (level.isClientSide()) {
            return InteractionResult.PASS;
        }

        if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.PASS;
        }

        BlockPos clickedPos = hitResult.getBlockPos();
        BlockState clickedState = level.getBlockState(clickedPos);
        ItemStack heldItem = player.getItemInHand(hand);
        LockState lockState = LockState.get(serverLevel.getServer());

        if (SignUtils.isWallSign(clickedState)) {
            InteractionResult signResult = handleSignInteraction(serverPlayer, level, clickedPos, lockState);
            if (signResult != InteractionResult.PASS) {
                return signResult;
            }
        }

        if (heldItem.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ChestBlock) {
            InteractionResult chestResult = handleChestPlacement(serverPlayer, level, clickedPos, clickedState, hitResult, lockState);
            if (chestResult != InteractionResult.PASS) {
                return chestResult;
            }
        }

        if (!ContainerUtils.isLockableContainer(clickedState)) {
            return InteractionResult.PASS;
        }

        if (heldItem.getItem() instanceof SignItem) {
            Optional<LockRecord> lockOpt = lockState.getLock(level, ContainerUtils.getContainerGroup(level, clickedPos));
            if (lockOpt.isPresent() && !canManageLock(serverPlayer, lockOpt.get())) {
                serverPlayer.sendSystemMessage(Component.literal(
                    "You cannot place a sign on someone else's locked container."
                ));
                serverPlayer.containerMenu.sendAllDataToRemote();
                return InteractionResult.FAIL;
            }
        }

        AccessControlService.AccessResult result = AccessControlService.canAccess(serverPlayer, level, clickedPos);
        if (!result.allowed()) {
            serverPlayer.sendSystemMessage(Component.literal(result.message()));
            return InteractionResult.FAIL;
        }

        return InteractionResult.PASS;
    }

    private static InteractionResult handleSignInteraction(ServerPlayer player, Level level, BlockPos signPos, LockState lockState) {
        Optional<BlockPos> attachedPos = SignUtils.getAttachedBlock(level, signPos);
        if (attachedPos.isEmpty()) {
            return InteractionResult.PASS;
        }

        Set<BlockPos> containerGroup = ContainerUtils.getContainerGroup(level, attachedPos.get());
        Optional<LockRecord> activeLock = lockState.getLock(level, containerGroup);
        Optional<DormantSignRecord> dormantSign = lockState.getDormantSign(level, signPos);

        if (activeLock.isPresent() && activeLock.get().getSignPos().equals(signPos) && !canManageLock(player, activeLock.get())) {
            player.sendSystemMessage(Component.literal("You cannot edit someone else's protected sign."));
            return InteractionResult.FAIL;
        }

        if (dormantSign.isPresent() && activeLock.isEmpty()) {
            if (!canManageDormantSign(player, dormantSign.get())) {
                player.sendSystemMessage(Component.literal("You cannot edit someone else's protected sign."));
                return InteractionResult.FAIL;
            }

            if (SignEditService.reactivateDormantSign(player, level, signPos)) {
                return InteractionResult.SUCCESS;
            }
        }

        return InteractionResult.PASS;
    }

    private static InteractionResult handleChestPlacement(
        ServerPlayer player,
        Level level,
        BlockPos clickedPos,
        BlockState clickedState,
        BlockHitResult hitResult,
        LockState lockState
    ) {
        BlockPos placementPos = clickedState.canBeReplaced() ? clickedPos : clickedPos.relative(hitResult.getDirection());

        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos adjacentPos = placementPos.relative(dir);
            BlockState adjacentState = level.getBlockState(adjacentPos);
            if (!(adjacentState.getBlock() instanceof ChestBlock)) {
                continue;
            }

            Optional<LockRecord> lockOpt = lockState.getLock(level, ContainerUtils.getContainerGroup(level, adjacentPos));
            if (lockOpt.isEmpty()) {
                continue;
            }

            LockRecord lock = lockOpt.get();
            if (!canManageLock(player, lock)) {
                player.sendSystemMessage(Component.literal(
                    "You cannot place a chest next to someone else's locked chest."
                ));
                player.containerMenu.sendAllDataToRemote();
                return InteractionResult.FAIL;
            }

            if (level instanceof ServerLevel serverLevel) {
                serverLevel.getServer().execute(() -> updateLockForExtendedChest(serverLevel, placementPos, lock));
            }
        }

        return InteractionResult.PASS;
    }

    private static void updateLockForExtendedChest(ServerLevel level, BlockPos newChestPos, LockRecord existingLock) {
        BlockState placedState = level.getBlockState(newChestPos);
        if (!(placedState.getBlock() instanceof ChestBlock)) {
            return;
        }

        LockState lockState = LockState.get(level.getServer());
        Set<BlockPos> newContainerGroup = ContainerUtils.getContainerGroup(level, newChestPos);

        if (newContainerGroup.size() > existingLock.getContainerPositions().size()) {
            LockRecord updatedLock = new LockRecord(
                existingLock.getDimension(),
                existingLock.getOwnerUuid(),
                existingLock.getOwnerName(),
                existingLock.getSignPos(),
                newContainerGroup,
                existingLock.getAccessMode(),
                existingLock.getAllowedUsers(),
                existingLock.getCreatedAt(),
                System.currentTimeMillis()
            );

            lockState.removeLock(existingLock);
            lockState.addLock(updatedLock);

            PrivateChests.LOGGER.info(
                "Updated lock at {} - chest extended from {} to {} positions",
                existingLock.getSignPos(),
                existingLock.getContainerPositions().size(),
                newContainerGroup.size()
            );
        }
    }

    private static boolean canManageLock(ServerPlayer player, LockRecord lock) {
        return player.getUUID().equals(lock.getOwnerUuid()) || AccessControlService.isAdmin(player);
    }

    private static boolean canManageDormantSign(ServerPlayer player, DormantSignRecord dormantSign) {
        return player.getUUID().equals(dormantSign.getOwnerUuid()) || AccessControlService.isAdmin(player);
    }
}
