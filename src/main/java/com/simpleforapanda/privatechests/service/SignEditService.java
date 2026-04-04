package com.simpleforapanda.privatechests.service;

import com.simpleforapanda.privatechests.PrivateChests;
import com.simpleforapanda.privatechests.model.LockRecord;
import com.simpleforapanda.privatechests.service.AccessControlService;
import com.simpleforapanda.privatechests.state.LockState;
import com.simpleforapanda.privatechests.util.ContainerUtils;
import com.simpleforapanda.privatechests.util.SignUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Service for handling sign editing and lock creation/updates.
 */
public class SignEditService {

    /**
     * Handle a sign edit event with new text lines.
     * Returns true if the edit is allowed, false to cancel it.
     *
     * @param player The player editing the sign
     * @param signPos The position of the sign
     * @param signEntity The sign block entity
     * @param newLines The new text for the side being edited
     * @param isFrontText True if editing front side, false if editing back side
     */
    public static boolean handleSignEdit(ServerPlayer player, BlockPos signPos, SignBlockEntity signEntity,
                                        java.util.List<net.minecraft.server.network.FilteredText> newLines,
                                        boolean isFrontText) {
        ServerLevel serverLevel = player.level();

        LockState lockState = LockState.get(serverLevel.getServer());
        BlockState signState = serverLevel.getBlockState(signPos);

        // Only process wall signs
        if (!SignUtils.isWallSign(signState)) {
            return true;
        }

        // Check what block the sign is attached to
        Optional<BlockPos> attachedPos = SignUtils.getAttachedBlock(serverLevel, signPos);
        if (attachedPos.isEmpty()) {
            return true;
        }

        // Check if attached to a lockable container
        Set<BlockPos> containerGroup = ContainerUtils.getContainerGroup(serverLevel, attachedPos.get());
        if (containerGroup.isEmpty()) {
            return true; // Not attached to a container
        }

        // Check if [private] exists on EITHER side after this edit
        // The edited side uses newLines, the other side uses existing text from signEntity
        boolean editedSideHasPrivate = SignUtils.containsPrivateMarker(newLines);
        boolean otherSideHasPrivate = SignUtils.containsPrivateMarker(signEntity, !isFrontText);
        boolean isPrivateSign = editedSideHasPrivate || otherSideHasPrivate;

        // Check if ANY part of the container group has an existing lock
        // This prevents the double chest exploit where Player B extends Player A's locked chest
        Optional<LockRecord> existingLock = Optional.empty();
        for (BlockPos pos : containerGroup) {
            existingLock = lockState.getLock(pos);
            if (existingLock.isPresent()) {
                break;
            }
        }

        if (existingLock.isPresent()) {
            return handleExistingLock(player, signPos, signEntity, newLines, isFrontText, containerGroup, existingLock.get(), lockState, isPrivateSign);
        } else {
            return handleNewLock(player, signPos, signEntity, newLines, isFrontText, containerGroup, lockState, isPrivateSign);
        }
    }

    /**
     * Handle editing a sign when there's already a lock on the container.
     */
    private static boolean handleExistingLock(
        ServerPlayer player,
        BlockPos signPos,
        SignBlockEntity signEntity,
        java.util.List<net.minecraft.server.network.FilteredText> newLines,
        boolean isFrontText,
        Set<BlockPos> containerGroup,
        LockRecord existingLock,
        LockState lockState,
        boolean isPrivateSign
    ) {
        // Check if this is the private sign for this lock
        boolean isTheLockSign = existingLock.getSignPos().equals(signPos);

        if (isTheLockSign) {
            // Editing the lock's private sign
            // Only owner or admin can edit
            if (!player.getUUID().equals(existingLock.getOwnerUuid()) && !AccessControlService.isAdmin(player)) {
                player.sendSystemMessage(Component.literal(
                    "You cannot edit someone else's [private] sign."
                ));
                return false;
            }

            // If the sign no longer has [private], remove the lock
            if (!isPrivateSign) {
                PrivateChests.LOGGER.info("Player {} removed [private] from sign at {}, removing lock",
                    player.getName().getString(), signPos);
                lockState.removeLock(containerGroup.iterator().next());
                player.sendSystemMessage(Component.literal("Lock removed from container."));
                return true;
            }

            // Extract allowed users from BOTH sides of the sign
            Set<String> allowedUsers = extractUsersFromBothSides(signEntity, newLines, isFrontText);

            // Filter out the owner's name - they already have access via ownership
            // Use case-insensitive comparison to catch variations
            allowedUsers.removeIf(name -> name.equalsIgnoreCase(existingLock.getOwnerName()));

            // Only update if the allowed users actually changed
            if (!allowedUsers.equals(existingLock.getAllowedUsers())) {
                LockRecord updatedLock = new LockRecord(
                    existingLock.getOwnerUuid(),
                    existingLock.getOwnerName(),
                    existingLock.getSignPos(),
                    existingLock.getContainerPositions(),
                    allowedUsers,
                    existingLock.getCreatedAt(),           // Preserve original creation time
                    System.currentTimeMillis()              // Update last modified time
                );

                lockState.removeLock(containerGroup.iterator().next());
                lockState.addLock(updatedLock);

                PrivateChests.LOGGER.info("Player {} updated allowed users on lock at {}",
                    player.getName().getString(), signPos);

                player.sendSystemMessage(Component.literal(
                    "Lock updated. " + allowedUsers.size() + " player(s) now have access."
                ));
            }

            return true;
        } else {
            // Trying to add another [private] sign to an already locked container
            if (isPrivateSign) {
                player.sendSystemMessage(Component.literal(
                    "This container is already protected by another [private] sign."
                ));
                return false;
            }

            // Not adding [private], allow edit
            return true;
        }
    }

    /**
     * Handle creating a new lock (sign being edited to add [private]).
     */
    private static boolean handleNewLock(
        ServerPlayer player,
        BlockPos signPos,
        SignBlockEntity signEntity,
        java.util.List<net.minecraft.server.network.FilteredText> newLines,
        boolean isFrontText,
        Set<BlockPos> containerGroup,
        LockState lockState,
        boolean isPrivateSign
    ) {
        if (!isPrivateSign) {
            return true; // Not adding [private], allow edit
        }

        // Enforce per-player lock limit (admins are exempt)
        int maxLocks = PrivateChests.getConfig().getMaxLocksPerPlayer();
        if (maxLocks > 0 && !AccessControlService.isAdmin(player)) {
            int currentLocks = lockState.countLocksForPlayer(player.getUUID());
            if (currentLocks >= maxLocks) {
                player.sendSystemMessage(Component.literal(
                    "You have reached the maximum of " + maxLocks + " locked container(s). "
                    + "Remove an existing lock before adding a new one."
                ));
                return false;
            }
        }

        // Extract allowed users from BOTH sides of the sign
        Set<String> allowedUsers = extractUsersFromBothSides(signEntity, newLines, isFrontText);

        // Filter out the owner's name - they already have access via ownership
        String ownerName = player.getName().getString();
        allowedUsers.removeIf(name -> name.equalsIgnoreCase(ownerName));

        LockRecord newLock = new LockRecord(
            player.getUUID(),
            ownerName,
            signPos,
            containerGroup,
            allowedUsers
        );

        lockState.addLock(newLock);

        PrivateChests.LOGGER.info("Player {} created new lock at {} for container group with {} blocks",
            player.getName().getString(), signPos, containerGroup.size());

        player.sendSystemMessage(Component.literal(
            "Container is now protected. Only you and listed players can access it."
        ));

        return true;
    }

    /**
     * Extract allowed users from both sides of a sign.
     * Combines usernames from the edited side (new text) and the other side (existing text).
     *
     * @param signEntity The sign block entity
     * @param editedSideText The new text for the side being edited
     * @param isEditingFront True if editing front side, false if editing back side
     * @return Combined set of all usernames from both sides
     */
    private static Set<String> extractUsersFromBothSides(
        SignBlockEntity signEntity,
        java.util.List<net.minecraft.server.network.FilteredText> editedSideText,
        boolean isEditingFront
    ) {
        Set<String> users = new HashSet<>();

        // Extract from the edited side (new text)
        users.addAll(SignUtils.extractAllowedUsers(editedSideText));

        // Extract from the OTHER side (existing text from sign entity)
        boolean otherSideIsFront = !isEditingFront;
        boolean otherSideHasPrivate = SignUtils.containsPrivateMarker(signEntity, otherSideIsFront);
        int startLine = otherSideHasPrivate ? 1 : 0;

        for (int i = startLine; i < 4; i++) {
            String line = signEntity.getText(otherSideIsFront).getMessage(i, false).getString().trim();
            if (!line.isEmpty()) {
                // Use the same comma-separation logic
                String[] parts = line.split(",");
                for (String part : parts) {
                    String username = part.trim();
                    if (!username.isEmpty()) {
                        users.add(username);
                    }
                }
            }
        }

        return users;
    }
}
