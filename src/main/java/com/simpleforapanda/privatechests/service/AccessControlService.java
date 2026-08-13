package com.simpleforapanda.privatechests.service;

import com.simpleforapanda.privatechests.PrivateChests;
import com.simpleforapanda.privatechests.config.ModConfig;
import com.simpleforapanda.privatechests.model.AccessMode;
import com.simpleforapanda.privatechests.model.LockRecord;
import com.simpleforapanda.privatechests.state.LockState;
import com.simpleforapanda.privatechests.util.ContainerUtils;
import com.simpleforapanda.privatechests.util.SignUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.Level;

import java.util.Optional;
import java.util.Set;

/**
 * Service for checking access control on protected containers.
 */
public class AccessControlService {
    public static AccessResult canAccess(ServerPlayer player, Level level, BlockPos containerPos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return AccessResult.allow();
        }

        MinecraftServer server = serverLevel.getServer();
        LockState lockState = LockState.get(server);
        Set<BlockPos> containerGroup = ContainerUtils.getContainerGroup(level, containerPos);
        Optional<LockRecord> lockOpt = lockState.getLock(level, containerGroup);

        if (lockOpt.isEmpty()) {
            return AccessResult.allow();
        }

        LockRecord lock = lockOpt.get();
        if (!SignUtils.isValidProtectionSign(level, lock.getSignPos(), lock.getContainerPositions())) {
            PrivateChests.LOGGER.info("Removing dangling lock at {} - sign no longer valid", containerPos);
            lockState.removeLock(lock);
            return AccessResult.allow();
        }

        if (shouldDisableProtectionForBannedOwner(server, lock)) {
            return AccessResult.allow();
        }

        if (lock.getAccessMode() == AccessMode.PUBLIC || isAdmin(player) || player.getUUID().equals(lock.getOwnerUuid())) {
            return AccessResult.allow();
        }

        ModConfig config = PrivateChests.getConfig();
        if (lock.isUserAllowed(player.getName().getString(), config.getFloodgatePrefix())) {
            return AccessResult.allow();
        }

        String ownerName = getOwnerName(server, lock);
        String containerType = ContainerUtils.getContainerTypeName(level, lock.getContainerPositions()).toLowerCase();
        return AccessResult.deny("Cannot open " + ownerName + "'s protected " + containerType + ". Permission denied.");
    }

    public static boolean isAdmin(ServerPlayer player) {
        return hasAdminPermission(player.permissions());
    }

    public static boolean hasAdminPermission(PermissionSet permissions) {
        int level = PrivateChests.getConfig().getAdminPermissionLevel();
        if (level <= 0) {
            return true;
        }

        Permission requiredPermission = switch (level) {
            case 1 -> Permissions.COMMANDS_MODERATOR;
            case 2 -> Permissions.COMMANDS_GAMEMASTER;
            case 3 -> Permissions.COMMANDS_ADMIN;
            default -> Permissions.COMMANDS_OWNER;
        };

        return permissions.hasPermission(requiredPermission);
    }

    public static boolean isOwnerBanned(MinecraftServer server, LockRecord lock) {
        return server.getPlayerList().getBans().isBanned(new NameAndId(lock.getOwnerUuid(), lock.getOwnerName()));
    }

    public static boolean shouldDisableProtectionForBannedOwner(MinecraftServer server, LockRecord lock) {
        return PrivateChests.getConfig().isDisableProtectionIfOwnerBanned() && isOwnerBanned(server, lock);
    }

    public static String getOwnerName(MinecraftServer server, LockRecord lock) {
        var onlinePlayer = server.getPlayerList().getPlayer(lock.getOwnerUuid());
        return onlinePlayer != null ? onlinePlayer.getName().getString() : lock.getOwnerName();
    }

    public record AccessResult(boolean allowed, String message) {
        public static AccessResult allow() {
            return new AccessResult(true, null);
        }

        public static AccessResult deny(String message) {
            return new AccessResult(false, message);
        }
    }
}
