package com.simpleforapanda.privatechests.event;

import com.simpleforapanda.privatechests.PrivateChests;
import com.simpleforapanda.privatechests.model.LockRecord;
import com.simpleforapanda.privatechests.service.AccessControlService;
import com.simpleforapanda.privatechests.state.LockState;
import com.simpleforapanda.privatechests.util.ContainerUtils;
import com.simpleforapanda.privatechests.util.SignUtils;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.Direction;
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
 * Handles container access events.
 */
public class ContainerEventHandler {

    public static void register() {
        UseBlockCallback.EVENT.register(ContainerEventHandler::onUseBlock);
    }

    /**
     * Called when a player tries to use (right-click) a block.
     */
    private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hitResult) {
        // Only process on server side
        if (level.isClientSide()) {
            return InteractionResult.PASS;
        }

        // Only process main hand to avoid double-processing
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }

        // Only process for server players
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.PASS;
        }

        BlockPos clickedPos = hitResult.getBlockPos();
        BlockState clickedState = level.getBlockState(clickedPos);
        ItemStack heldItem = player.getItemInHand(hand);

        // Check if player is trying to interact with a private sign
        if (SignUtils.isWallSign(clickedState)) {
            LockState lockState = LockState.get(serverLevel.getServer());

            // Check if this is a private sign for a locked container
            Optional<BlockPos> attachedPos = SignUtils.getAttachedBlock(level, clickedPos);
            if (attachedPos.isPresent()) {
                Set<BlockPos> containerGroup = ContainerUtils.getContainerGroup(level, attachedPos.get());

                // Check if any part of the container group is locked
                for (BlockPos pos : containerGroup) {
                    Optional<LockRecord> lockOpt = lockState.getLock(pos);
                    if (lockOpt.isPresent()) {
                        LockRecord lock = lockOpt.get();

                        // Check if this is the private sign for this lock
                        if (lock.getSignPos().equals(clickedPos)) {
                            // Check if player can edit this sign
                            boolean isOwner = player.getUUID().equals(lock.getOwnerUuid());
                            boolean isAdmin = AccessControlService.isAdmin(serverPlayer);

                            // Only owner can edit the sign (not allowed users)
                            if (!isOwner && !isAdmin) {
                                serverPlayer.sendSystemMessage(Component.literal(
                                    "You cannot edit someone else's [private] sign."
                                ));
                                return InteractionResult.FAIL;
                            }
                        }
                    }
                }
            }
        }

        // Check if player is trying to place a chest next to a locked chest
        if (heldItem.getItem() instanceof BlockItem blockItem) {
            Block block = blockItem.getBlock();
            if (block instanceof ChestBlock) {
                // Calculate where the chest would be placed
                BlockPos placementPos;
                if (clickedState.canBeReplaced()) {
                    // Placing in the clicked position (e.g., tall grass)
                    placementPos = clickedPos;
                } else {
                    // Placing on the face of the clicked block
                    placementPos = clickedPos.relative(hitResult.getDirection());
                }

                // Check all adjacent positions for locked chests
                LockState lockState = LockState.get(serverLevel.getServer());
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos adjacentPos = placementPos.relative(dir);
                    BlockState adjacentState = level.getBlockState(adjacentPos);

                    // If there's a chest adjacent to where we're placing
                    if (adjacentState.getBlock() instanceof ChestBlock) {
                        // Check if that chest is locked
                        Optional<LockRecord> lockOpt = lockState.getLock(adjacentPos);
                        if (lockOpt.isPresent()) {
                            LockRecord lock = lockOpt.get();

                            // Only owner and admins can extend locked chests
                            if (!player.getUUID().equals(lock.getOwnerUuid()) && !AccessControlService.isAdmin(serverPlayer)) {
                                serverPlayer.sendSystemMessage(Component.literal(
                                    "You cannot place a chest next to someone else's locked chest."
                                ));
                                // Resync inventory
                                serverPlayer.containerMenu.sendAllDataToRemote();
                                return InteractionResult.FAIL;
                            } else {
                                // Owner/admin is extending their locked chest
                                // Schedule task to update lock record after placement
                                final BlockPos finalPlacementPos = placementPos;
                                final LockRecord finalLock = lock;
                                serverLevel.getServer().execute(() -> {
                                    // Check if chest was actually placed and update lock
                                    updateLockForExtendedChest(serverLevel, finalPlacementPos, finalLock);
                                });
                            }
                        }
                    }
                }
            }
        }

        // Check if the block is a lockable container
        if (!ContainerUtils.isLockableContainer(clickedState)) {
            return InteractionResult.PASS;
        }

        // Check if player is trying to place a sign on a locked container they don't own
        if (heldItem.getItem() instanceof SignItem) {
            LockState lockState = LockState.get(serverLevel.getServer());
            Optional<LockRecord> lockOpt = lockState.getLock(clickedPos);

            if (lockOpt.isPresent()) {
                LockRecord lock = lockOpt.get();

                // Allow owner and admins to place signs
                if (!player.getUUID().equals(lock.getOwnerUuid()) && !AccessControlService.isAdmin(serverPlayer)) {
                    serverPlayer.sendSystemMessage(Component.literal(
                        "You cannot place a sign on someone else's locked container."
                    ));
                    // Resync the player's inventory to fix ghost item
                    serverPlayer.containerMenu.sendAllDataToRemote();
                    return InteractionResult.FAIL;
                }
            }
        }

        // Check access control for opening the container
        AccessControlService.AccessResult result = AccessControlService.canAccess(
            serverPlayer,
            level,
            clickedPos
        );

        if (!result.allowed()) {
            // Deny access and send message to player
            serverPlayer.sendSystemMessage(Component.literal(result.message()));
            return InteractionResult.FAIL;
        }

        return InteractionResult.PASS;
    }

    /**
     * Update a lock record when a chest is extended to a double chest.
     * Called on next tick after chest placement.
     */
    private static void updateLockForExtendedChest(ServerLevel level, BlockPos newChestPos, LockRecord existingLock) {
        // Verify the chest was actually placed
        BlockState placedState = level.getBlockState(newChestPos);
        if (!(placedState.getBlock() instanceof ChestBlock)) {
            return; // Chest wasn't placed, nothing to update
        }

        LockState lockState = LockState.get(level.getServer());

        // Get the new container group including the newly placed chest
        Set<BlockPos> newContainerGroup = ContainerUtils.getContainerGroup(level, newChestPos);

        // Check if this actually expanded the container
        if (newContainerGroup.size() > existingLock.getContainerPositions().size()) {
            // Create updated lock record with new positions
            LockRecord updatedLock = new LockRecord(
                existingLock.getOwnerUuid(),
                existingLock.getOwnerName(),
                existingLock.getSignPos(),
                newContainerGroup,
                existingLock.getAllowedUsers()
            );

            // Remove old lock and add new one
            lockState.removeLock(existingLock.getContainerPositions().iterator().next());
            lockState.addLock(updatedLock);

            PrivateChests.LOGGER.info("Updated lock at {} - chest extended from {} to {} positions",
                existingLock.getSignPos(), existingLock.getContainerPositions().size(), newContainerGroup.size());
        }
    }
}
