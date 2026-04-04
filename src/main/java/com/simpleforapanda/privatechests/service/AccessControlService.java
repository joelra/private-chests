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
import net.minecraft.server.permissions.Permissions;
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

        // Check if owner is banned
        if (isOwnerBanned(server, lock)) {
            ModConfig config = PrivateChests.getConfig();
            if (config.isDisableProtectionIfOwnerBanned()) {
                return AccessResult.allow();
            }
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

        // Deny access
        String ownerName = getOwnerName(server, lock);
        return AccessResult.deny("Cannot open " + ownerName + "'s private chest. Permission denied.");
    }

    /**
     * Check if a player is an admin (has bypass permission).
     */
    public static boolean isAdmin(ServerPlayer player) {
        ModConfig config = PrivateChests.getConfig();
        // Check if player is an operator with sufficient permission level
        return player.permissions().hasPermission(Permissions.COMMANDS_MODERATOR);
        //return player.server.getPlayerList().isOp(player.getGameProfile());
    }

    /**
     * Check if the owner of a lock is banned.
     */
    private static boolean isOwnerBanned(MinecraftServer server, LockRecord lock) {
        // Check if the owner UUID is banned
        var player = server.getPlayerList().getPlayer(lock.getOwnerUuid());
        if (player != null) {
            return server.getPlayerList().getBans().isBanned(player.getGameProfile());
        }
        // If player is not online, we can't easily check ban status, so assume not banned
        return false;
    }

    /**
     * Get the display name of the lock owner.
     */
    private static String getOwnerName(MinecraftServer server, LockRecord lock) {
        // Use the cached owner name from the lock record
        return lock.getOwnerName();
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
