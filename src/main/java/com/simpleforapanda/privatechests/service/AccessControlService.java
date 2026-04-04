package com.simpleforapanda.privatechests.service;

import com.simpleforapanda.privatechests.PrivateChests;
import com.simpleforapanda.privatechests.config.ModConfig;
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
 * Service for checking access control on locked containers.
 */
public class AccessControlService {

    /**
     * Check if a player can access a container at the given position.
     * Returns AccessResult with the decision and optional message.
     */
    public static AccessResult canAccess(ServerPlayer player, Level level, BlockPos containerPos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return AccessResult.allow();
        }

        MinecraftServer server = serverLevel.getServer();
        LockState lockState = LockState.get(server);

        // Get the full container group (handles double chests)
        // This ensures we find locks even when a single chest is extended to a double chest
        Set<BlockPos> containerGroup = ContainerUtils.getContainerGroup(level, containerPos);

        // Check if ANY part of the container group has a lock
        Optional<LockRecord> lockOpt = Optional.empty();
        for (BlockPos pos : containerGroup) {
            lockOpt = lockState.getLock(pos);
            if (lockOpt.isPresent()) {
                break;
            }
        }

        if (lockOpt.isEmpty()) {
            return AccessResult.allow();
        }

        LockRecord lock = lockOpt.get();

        // Validate the lock is still valid (sign still exists and qualifies)
        if (!SignUtils.isValidPrivateSign(level, lock.getSignPos(), lock.getContainerPositions())) {
            // Lock is dangling, remove it
            PrivateChests.LOGGER.info("Removing dangling lock at {} - sign no longer valid", containerPos);
            lockState.removeLock(containerPos);
            return AccessResult.allow();
        }

        if (shouldDisableProtectionForBannedOwner(server, lock)) {
            return AccessResult.allow();
        }

        // Check admin bypass
        if (isAdmin(player)) {
            return AccessResult.allow();
        }

        // Check if player is the owner
        if (player.getUUID().equals(lock.getOwnerUuid())) {
            return AccessResult.allow();
        }

        // Check if player is in allowed list
        ModConfig config = PrivateChests.getConfig();
        if (lock.isUserAllowed(player.getName().getString(), config.getFloodgatePrefix())) {
            return AccessResult.allow();
        }

        // Deny access — use the actual container type in the message
        String ownerName = getOwnerName(server, lock);
        String containerType = ContainerUtils.getContainerTypeName(level, lock.getContainerPositions()).toLowerCase();
        return AccessResult.deny("Cannot open " + ownerName + "'s private " + containerType + ". Permission denied.");
    }

    /**
     * Check if a player is an admin (has bypass permission).
     * Uses the configured {@code adminPermissionLevel}.
     */
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

    /**
     * Check if the owner of a lock is banned and protection should be disabled.
     * Works correctly for offline players.
     */
    public static boolean isOwnerBanned(MinecraftServer server, LockRecord lock) {
        return server.getPlayerList()
            .getBans()
            .isBanned(new NameAndId(lock.getOwnerUuid(), lock.getOwnerName()));
    }

    public static boolean shouldDisableProtectionForBannedOwner(MinecraftServer server, LockRecord lock) {
        return PrivateChests.getConfig().isDisableProtectionIfOwnerBanned() && isOwnerBanned(server, lock);
    }

    /**
     * Get the display name of the lock owner.
     * Returns the current in-game name when the player is online; otherwise
     * falls back to the cached name stored in the lock record.
     */
    public static String getOwnerName(MinecraftServer server, LockRecord lock) {
        var onlinePlayer = server.getPlayerList().getPlayer(lock.getOwnerUuid());
        return onlinePlayer != null ? onlinePlayer.getName().getString() : lock.getOwnerName();
    }

    /**
     * Result of an access check.
     */
    public record AccessResult(boolean allowed, String message) {
        public static AccessResult allow() {
            return new AccessResult(true, null);
        }

        public static AccessResult deny(String message) {
            return new AccessResult(false, message);
        }
    }
}
