package com.simpleforapanda.privatechests.service;

import com.simpleforapanda.privatechests.PrivateChests;
import com.simpleforapanda.privatechests.model.AccessMode;
import com.simpleforapanda.privatechests.model.DormantSignRecord;
import com.simpleforapanda.privatechests.model.LockRecord;
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

import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Service for handling sign editing and protection creation/updates.
 */
public class SignEditService {
    public static boolean handleSignEdit(
        ServerPlayer player,
        BlockPos signPos,
        SignBlockEntity signEntity,
        java.util.List<net.minecraft.server.network.FilteredText> newLines,
        boolean isFrontText
    ) {
        ServerLevel serverLevel = player.level();
        LockState lockState = LockState.get(serverLevel.getServer());
        BlockState signState = serverLevel.getBlockState(signPos);

        if (!SignUtils.isWallSign(signState)) {
            return true;
        }

        Optional<BlockPos> attachedPos = SignUtils.getAttachedBlock(serverLevel, signPos);
        if (attachedPos.isEmpty()) {
            return true;
        }

        Set<BlockPos> containerGroup = ContainerUtils.getContainerGroup(serverLevel, attachedPos.get());
        if (containerGroup.isEmpty()) {
            return true;
        }

        Optional<AccessMode> editedMode = SignUtils.getAccessMode(newLines);
        Optional<AccessMode> otherMode = SignUtils.getAccessMode(signEntity, !isFrontText);
        if (editedMode.isPresent() && otherMode.isPresent() && editedMode.get() != otherMode.get()) {
            player.sendSystemMessage(Component.literal(
                "A sign cannot mix [private] and [public] markers across its two sides."
            ));
            return false;
        }

        Optional<AccessMode> resultingMode = editedMode.isPresent() ? editedMode : otherMode;
        Optional<LockRecord> existingLock = lockState.getLock(serverLevel, containerGroup);
        Optional<DormantSignRecord> dormantSign = lockState.getDormantSign(serverLevel, signPos);

        if (existingLock.isPresent()) {
            return handleExistingProtection(player, signPos, signEntity, newLines, isFrontText, containerGroup, existingLock.get(), dormantSign, lockState, resultingMode);
        }

        return handleUnlockedContainer(player, signPos, signEntity, newLines, isFrontText, containerGroup, dormantSign, lockState, resultingMode);
    }

    public static boolean reactivateDormantSign(ServerPlayer player, Level level, BlockPos signPos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }

        LockState lockState = LockState.get(serverLevel.getServer());
        Optional<DormantSignRecord> dormantSign = lockState.getDormantSign(level, signPos);
        if (dormantSign.isEmpty() || !canManageDormantSign(player, dormantSign.get())) {
            return false;
        }

        Optional<BlockPos> attachedPos = SignUtils.getAttachedBlock(level, signPos);
        if (attachedPos.isEmpty()) {
            lockState.removeDormantSign(level, signPos);
            return false;
        }

        Set<BlockPos> containerGroup = ContainerUtils.getContainerGroup(level, attachedPos.get());
        if (containerGroup.isEmpty() || lockState.getLock(level, containerGroup).isPresent()) {
            return false;
        }

        if (!(level.getBlockEntity(signPos) instanceof SignBlockEntity signEntity)) {
            lockState.removeDormantSign(level, signPos);
            return false;
        }

        Optional<AccessMode> frontMode = SignUtils.getAccessMode(signEntity, true);
        Optional<AccessMode> backMode = SignUtils.getAccessMode(signEntity, false);
        if (frontMode.isPresent() && backMode.isPresent() && frontMode.get() != backMode.get()) {
            return false;
        }

        Optional<AccessMode> mode = frontMode.isPresent() ? frontMode : backMode;
        if (mode.isEmpty()) {
            lockState.removeDormantSign(level, signPos);
            return false;
        }

        return createNewLock(
            player,
            signPos,
            signEntity,
            Collections.emptyList(),
            true,
            containerGroup,
            lockState,
            dormantSign.get().getOwnerUuid(),
            dormantSign.get().getOwnerName(),
            mode.get(),
            AccessControlService.isAdmin(player)
        );
    }

    private static boolean handleExistingProtection(
        ServerPlayer player,
        BlockPos signPos,
        SignBlockEntity signEntity,
        java.util.List<net.minecraft.server.network.FilteredText> newLines,
        boolean isFrontText,
        Set<BlockPos> containerGroup,
        LockRecord existingLock,
        Optional<DormantSignRecord> dormantSign,
        LockState lockState,
        Optional<AccessMode> resultingMode
    ) {
        if (existingLock.getSignPos().equals(signPos)) {
            return handleActiveSignEdit(player, signPos, signEntity, newLines, isFrontText, containerGroup, existingLock, lockState, resultingMode);
        }

        if (!canManageLock(player, existingLock) && (resultingMode.isPresent() || dormantSign.isPresent())) {
            player.sendSystemMessage(Component.literal("You cannot edit someone else's protected sign."));
            return false;
        }

        if (resultingMode.isPresent()) {
            DormantSignRecord record = dormantSign.orElseGet(() -> new DormantSignRecord(
                player.level().dimension(),
                player.getUUID(),
                player.getName().getString(),
                signPos
            ));
            lockState.addDormantSign(record);
            if (dormantSign.isEmpty()) {
                player.sendSystemMessage(Component.literal(
                    "Alternate protection sign saved. It stays inactive until the container is unprotected and its owner reactivates it."
                ));
            }
        } else if (dormantSign.isPresent()) {
            lockState.removeDormantSign(player.level(), signPos);
        }

        return true;
    }

    private static boolean handleActiveSignEdit(
        ServerPlayer player,
        BlockPos signPos,
        SignBlockEntity signEntity,
        java.util.List<net.minecraft.server.network.FilteredText> newLines,
        boolean isFrontText,
        Set<BlockPos> containerGroup,
        LockRecord existingLock,
        LockState lockState,
        Optional<AccessMode> resultingMode
    ) {
        if (!canManageLock(player, existingLock)) {
            player.sendSystemMessage(Component.literal("You cannot edit someone else's protected sign."));
            return false;
        }

        if (resultingMode.isEmpty()) {
            PrivateChests.LOGGER.info(
                "Player {} removed protection marker from sign at {}, removing lock",
                player.getName().getString(),
                signPos
            );
            lockState.removeLock(existingLock);
            player.sendSystemMessage(Component.literal("Protection removed from container."));
            return true;
        }

        Set<String> allowedUsers = extractAllowedUsers(resultingMode.get(), existingLock.getOwnerName(), signEntity, newLines, isFrontText);
        if (existingLock.getAccessMode() == resultingMode.get() && allowedUsers.equals(existingLock.getAllowedUsers())) {
            return true;
        }

        LockRecord updatedLock = new LockRecord(
            existingLock.getDimension(),
            existingLock.getOwnerUuid(),
            existingLock.getOwnerName(),
            existingLock.getSignPos(),
            existingLock.getContainerPositions(),
            resultingMode.get(),
            allowedUsers,
            existingLock.getCreatedAt(),
            System.currentTimeMillis()
        );

        lockState.removeLock(existingLock);
        lockState.addLock(updatedLock);

        PrivateChests.LOGGER.info("Player {} updated protection at {}", player.getName().getString(), signPos);
        player.sendSystemMessage(Component.literal(messageForUpdatedLock(updatedLock)));
        return true;
    }

    private static boolean handleUnlockedContainer(
        ServerPlayer player,
        BlockPos signPos,
        SignBlockEntity signEntity,
        java.util.List<net.minecraft.server.network.FilteredText> newLines,
        boolean isFrontText,
        Set<BlockPos> containerGroup,
        Optional<DormantSignRecord> dormantSign,
        LockState lockState,
        Optional<AccessMode> resultingMode
    ) {
        if (dormantSign.isPresent()) {
            DormantSignRecord record = dormantSign.get();
            if (!canManageDormantSign(player, record)) {
                player.sendSystemMessage(Component.literal("You cannot edit someone else's protected sign."));
                return false;
            }

            if (resultingMode.isEmpty()) {
                lockState.removeDormantSign(player.level(), signPos);
                return true;
            }

            return createNewLock(
                player,
                signPos,
                signEntity,
                newLines,
                isFrontText,
                containerGroup,
                lockState,
                record.getOwnerUuid(),
                record.getOwnerName(),
                resultingMode.get(),
                AccessControlService.isAdmin(player)
            );
        }

        if (resultingMode.isEmpty()) {
            return true;
        }

        return createNewLock(
            player,
            signPos,
            signEntity,
            newLines,
            isFrontText,
            containerGroup,
            lockState,
            player.getUUID(),
            player.getName().getString(),
            resultingMode.get(),
            AccessControlService.isAdmin(player)
        );
    }

    private static boolean createNewLock(
        ServerPlayer actor,
        BlockPos signPos,
        SignBlockEntity signEntity,
        java.util.List<net.minecraft.server.network.FilteredText> newLines,
        boolean isFrontText,
        Set<BlockPos> containerGroup,
        LockState lockState,
        UUID ownerUuid,
        String ownerName,
        AccessMode accessMode,
        boolean bypassLimit
    ) {
        int maxLocks = PrivateChests.getConfig().getMaxLocksPerPlayer();
        if (maxLocks > 0 && !bypassLimit) {
            int currentLocks = lockState.countLocksForPlayer(ownerUuid);
            if (currentLocks >= maxLocks) {
                actor.sendSystemMessage(Component.literal(
                    "You have reached the maximum of " + maxLocks + " locked container(s). Remove an existing lock before adding a new one."
                ));
                return false;
            }
        }

        Set<String> allowedUsers = extractAllowedUsers(accessMode, ownerName, signEntity, newLines, isFrontText);
        LockRecord newLock = new LockRecord(actor.level().dimension(), ownerUuid, ownerName, signPos, containerGroup, accessMode, allowedUsers);

        lockState.addLock(newLock);
        lockState.removeDormantSign(actor.level(), signPos);

        PrivateChests.LOGGER.info("Player {} created new protection at {}", actor.getName().getString(), signPos);
        actor.sendSystemMessage(Component.literal(messageForCreatedLock(newLock)));
        return true;
    }

    private static Set<String> extractAllowedUsers(
        AccessMode accessMode,
        String ownerName,
        SignBlockEntity signEntity,
        java.util.List<net.minecraft.server.network.FilteredText> editedSideText,
        boolean isEditingFront
    ) {
        if (accessMode.isPublic()) {
            return Collections.emptySet();
        }

        Set<String> users = new HashSet<>();
        users.addAll(SignUtils.extractAllowedUsers(editedSideText));
        users.addAll(SignUtils.extractAllowedUsers(signEntity, !isEditingFront));

        users.removeIf(name -> name.equalsIgnoreCase(ownerName));
        return users;
    }

    private static boolean canManageLock(ServerPlayer player, LockRecord lock) {
        return player.getUUID().equals(lock.getOwnerUuid()) || AccessControlService.isAdmin(player);
    }

    private static boolean canManageDormantSign(ServerPlayer player, DormantSignRecord dormantSign) {
        return player.getUUID().equals(dormantSign.getOwnerUuid()) || AccessControlService.isAdmin(player);
    }

    private static String messageForCreatedLock(LockRecord lock) {
        if (lock.getAccessMode().isPublic()) {
            return "Container is now public. Anyone can open it, but only the owner or an admin can manage its protection.";
        }
        return "Container is now protected. Only you and listed players can access it.";
    }

    private static String messageForUpdatedLock(LockRecord lock) {
        if (lock.getAccessMode().isPublic()) {
            return "Protection updated. This container is public.";
        }
        return "Lock updated. " + lock.getAllowedUsers().size() + " player(s) now have access.";
    }
}
