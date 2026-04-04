package com.simpleforapanda.privatechests.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.simpleforapanda.privatechests.PrivateChests;
import com.simpleforapanda.privatechests.config.ModConfig;
import com.simpleforapanda.privatechests.model.LockRecord;
import com.simpleforapanda.privatechests.service.AccessControlService;
import com.simpleforapanda.privatechests.state.LockState;
import com.simpleforapanda.privatechests.util.ContainerUtils;
import com.simpleforapanda.privatechests.util.SignUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Admin commands for managing private chests.
 * Requires the configured {@code adminPermissionLevel} (default 3).
 */
public class PrivateChestsCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Register main command: /private_chests
        dispatcher.register(
            Commands.literal("private_chests")
                .requires(source -> source.hasPermission(PrivateChests.getConfig().getAdminPermissionLevel()))
                .then(Commands.literal("unlock")
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(PrivateChestsCommand::executeUnlockByPos)
                    )
                )
                .then(Commands.literal("unlock_player")
                    .then(Commands.argument("player", StringArgumentType.word())
                        .executes(PrivateChestsCommand::executeUnlockByPlayer)
                    )
                )
                .then(Commands.literal("list")
                    .executes(PrivateChestsCommand::executeList)
                )
                .then(Commands.literal("list_in_area")
                    .executes(ctx -> executeListInArea(ctx, 1)) // Default 2x2 chunks (radius 1)
                    .then(Commands.argument("radius", IntegerArgumentType.integer(0, 10))
                        .executes(ctx -> executeListInArea(ctx, IntegerArgumentType.getInteger(ctx, "radius")))
                    )
                )
                .then(Commands.literal("info")
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(PrivateChestsCommand::executeInfo)
                    )
                )
                .then(Commands.literal("limits")
                    .executes(PrivateChestsCommand::executeLimits)
                )
                .then(Commands.literal("cleanup")
                    .executes(PrivateChestsCommand::executeCleanup)
                )
        );

        // Register shorter alias: /pchests
        dispatcher.register(
            Commands.literal("pchests")
                .requires(source -> source.hasPermission(PrivateChests.getConfig().getAdminPermissionLevel()))
                .redirect(dispatcher.getRoot().getChild("private_chests"))
        );
    }

    /**
     * Execute /private_chests unlock <pos>
     */
    private static int executeUnlockByPos(CommandContext<CommandSourceStack> ctx) {
        try {
            BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
            CommandSourceStack source = ctx.getSource();
            MinecraftServer server = source.getServer();
            ServerLevel level = source.getLevel();
            LockState lockState = LockState.get(server);

            // Get container group at position
            Set<BlockPos> containerGroup = ContainerUtils.getContainerGroup(level, pos);

            if (containerGroup.isEmpty()) {
                source.sendFailure(Component.literal("No lockable container found at " + ContainerUtils.positionToString(pos)));
                return 0;
            }

            // Check if locked
            Optional<LockRecord> lockOpt = lockState.getLock(pos);
            if (lockOpt.isEmpty()) {
                source.sendFailure(Component.literal("No lock found at " + ContainerUtils.positionToString(pos)));
                return 0;
            }

            // Remove lock
            lockState.removeLock(pos);

            String containerType = ContainerUtils.getContainerTypeName(level, containerGroup);
            source.sendSuccess(() -> Component.literal(
                "Unlocked " + containerType + " at " + ContainerUtils.positionToString(pos)
            ), true);

            PrivateChests.LOGGER.info("Admin {} unlocked container at {}", source.getTextName(), pos);

            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    /**
     * Execute /private_chests unlock_player <playerName>
     * Removes all locks owned by the named player.
     */
    private static int executeUnlockByPlayer(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        LockState lockState = LockState.get(server);
        String playerName = StringArgumentType.getString(ctx, "player");

        // Find all locks whose owner name matches (case-insensitive)
        List<LockRecord> toRemove = lockState.getAllLocks().stream()
            .filter(r -> r.getOwnerName().equalsIgnoreCase(playerName))
            .toList();

        if (toRemove.isEmpty()) {
            source.sendFailure(Component.literal("No locks found for player '" + playerName + "'."));
            return 0;
        }

        for (LockRecord lock : toRemove) {
            lockState.removeLock(lock.getContainerPositions().iterator().next());
        }

        int removed = toRemove.size();
        source.sendSuccess(() -> Component.literal(
            "Removed " + removed + " lock(s) owned by '" + playerName + "'."
        ), true);
        PrivateChests.LOGGER.info("Admin {} removed {} lock(s) for player '{}'",
            source.getTextName(), removed, playerName);

        return removed;
    }

    /**
     * Execute /private_chests limits
     * Lists all players with locks, showing their count vs the configured limit.
     */
    private static int executeLimits(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        LockState lockState = LockState.get(server);
        int maxLocks = PrivateChests.getConfig().getMaxLocksPerPlayer();

        Collection<LockRecord> allLocks = lockState.getAllLocks();
        if (allLocks.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No active locks."), false);
            return 0;
        }

        // Group counts by owner
        java.util.Map<java.util.UUID, long[]> countByOwner = new java.util.LinkedHashMap<>();
        java.util.Map<java.util.UUID, String> nameByOwner = new java.util.LinkedHashMap<>();
        for (LockRecord lock : allLocks) {
            countByOwner.computeIfAbsent(lock.getOwnerUuid(), k -> new long[]{0})[0]++;
            nameByOwner.put(lock.getOwnerUuid(), AccessControlService.getOwnerName(server, lock));
        }

        String limitLabel = maxLocks > 0 ? " (limit: " + maxLocks + ")" : " (no limit)";
        source.sendSuccess(() -> Component.literal("===== Lock Counts Per Player" + limitLabel + " ====="), false);

        countByOwner.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
            .forEach(entry -> {
                String name = nameByOwner.get(entry.getKey());
                long count = entry.getValue()[0];
                String line = "  " + name + ": " + count
                    + (maxLocks > 0 ? " / " + maxLocks : "");
                source.sendSuccess(() -> Component.literal(line), false);
            });

        return countByOwner.size();
    }

    /**
     * Execute /private_chests cleanup
     * Removes lock records whose private sign no longer exists in the overworld.
     * Only locks in currently-loaded chunks are validated.
     */
    private static int executeCleanup(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        LockState lockState = LockState.get(server);
        ServerLevel overworld = server.overworld();

        int[] removedCount = {0};
        lockState.cleanupDanglingLocks(record -> {
            BlockPos signPos = record.getSignPos();
            // Only validate if the chunk is currently loaded
            if (!overworld.isLoaded(signPos)) {
                return true; // assume valid if not loaded
            }
            boolean valid = SignUtils.isValidPrivateSign(overworld, signPos, record.getContainerPositions());
            if (!valid) removedCount[0]++;
            return valid;
        });

        int removed = removedCount[0];
        source.sendSuccess(() -> Component.literal(
            "Cleanup complete. Removed " + removed + " dangling lock record(s)."
        ), true);
        PrivateChests.LOGGER.info("Admin {} ran cleanup, removed {} dangling lock(s)", source.getTextName(), removed);

        return removed;
    }

    /**
     * Execute /private_chests list
     */
    private static int executeList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        LockState lockState = LockState.get(server);

        Collection<LockRecord> locks = lockState.getAllLocks();
        int totalCount = locks.size();

        if (totalCount == 0) {
            source.sendSuccess(() -> Component.literal("No private chests found."), false);
            return 0;
        }

        ModConfig config = PrivateChests.getConfig();
        int maxEntries = config.getListMaxEntries();
        int previewEntries = config.getListPreviewEntries();

        source.sendSuccess(() -> Component.literal("===== Private Chests (" + totalCount + " total) ====="), false);

        if (totalCount > maxEntries) {
            // Show abbreviated list
            List<LockRecord> lockList = locks.stream().limit(previewEntries).toList();

            for (LockRecord lock : lockList) {
                sendLockInfo(source, lock, server);
            }

            source.sendSuccess(() -> Component.literal(
                "... and " + (totalCount - previewEntries) + " more. Use /private_chests list_in_area to filter by location."
            ), false);
        } else {
            // Show full list
            for (LockRecord lock : locks) {
                sendLockInfo(source, lock, server);
            }
        }

        return totalCount;
    }

    /**
     * Execute /private_chests info <pos>
     */
    private static int executeInfo(CommandContext<CommandSourceStack> ctx) {
        try {
            BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
            CommandSourceStack source = ctx.getSource();
            MinecraftServer server = source.getServer();
            ServerLevel level = source.getLevel();
            LockState lockState = LockState.get(server);

            // Get container group at position
            Set<BlockPos> containerGroup = ContainerUtils.getContainerGroup(level, pos);

            if (containerGroup.isEmpty()) {
                source.sendFailure(Component.literal("No lockable container found at " + ContainerUtils.positionToString(pos)));
                return 0;
            }

            // Check if locked
            Optional<LockRecord> lockOpt = lockState.getLock(pos);
            if (lockOpt.isEmpty()) {
                source.sendFailure(Component.literal("No lock found at " + ContainerUtils.positionToString(pos)));
                return 0;
            }

            LockRecord lock = lockOpt.get();

            // Build info message – prefer live name when owner is online
            String containerType = ContainerUtils.getContainerTypeName(level, containerGroup);
            String position = ContainerUtils.positionToString(ContainerUtils.getPrimaryPosition(lock.getContainerPositions()));
            String ownerName = AccessControlService.getOwnerName(server, lock);
            Set<String> allowedUsers = lock.getAllowedUsers();

            source.sendSuccess(() -> Component.literal("===== Lock Information ====="), false);
            source.sendSuccess(() -> Component.literal("Container: " + containerType), false);
            source.sendSuccess(() -> Component.literal("Location: " + position), false);
            source.sendSuccess(() -> Component.literal("Owner: " + ownerName), false);

            if (allowedUsers.isEmpty()) {
                source.sendSuccess(() -> Component.literal("Allowed Users: (none - owner only)"), false);
            } else {
                source.sendSuccess(() -> Component.literal("Allowed Users: " + String.join(", ", allowedUsers)), false);
            }

            // Display timestamps
            String createdDate = formatTimestamp(lock.getCreatedAt());
            String updatedDate = formatTimestamp(lock.getLastUpdatedAt());
            source.sendSuccess(() -> Component.literal("Created: " + createdDate), false);
            source.sendSuccess(() -> Component.literal("Last Updated: " + updatedDate), false);

            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    /**
     * Execute /private_chests list_in_area [radius]
     */
    private static int executeListInArea(CommandContext<CommandSourceStack> ctx, int chunkRadius) {
        CommandSourceStack source = ctx.getSource();

        // Get source position
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("This command must be run by a player or from a specific location."));
            return 0;
        }

        MinecraftServer server = source.getServer();
        LockState lockState = LockState.get(server);
        BlockPos centerPos = player.blockPosition();

        List<LockRecord> locks = lockState.getLocksInArea(centerPos, chunkRadius);

        if (locks.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                "No private chests found in " + (chunkRadius * 2) + "x" + (chunkRadius * 2) + " chunks around you."
            ), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
            "===== Private Chests in Area (" + locks.size() + " found) ====="
        ), false);

        for (LockRecord lock : locks) {
            sendLockInfo(source, lock, server);
        }

        return locks.size();
    }

    /**
     * Send lock information to the command source.
     * Prefers the live player name when the owner is currently online.
     */
    private static void sendLockInfo(CommandSourceStack source, LockRecord lock, MinecraftServer server) {
        String ownerName = AccessControlService.getOwnerName(server, lock);

        ServerLevel level = source.getLevel();
        String containerType = ContainerUtils.getContainerTypeName(level, lock.getContainerPositions());
        String position = ContainerUtils.positionToString(ContainerUtils.getPrimaryPosition(lock.getContainerPositions()));

        source.sendSuccess(() -> Component.literal(
            "- " + containerType + " at " + position + " | Owner: " + ownerName
        ), false);
    }

    /**
     * Format a timestamp (milliseconds) to a human-readable date/time string.
     * Includes timezone to help players in different timezones.
     */
    private static String formatTimestamp(long timestamp) {
        if (timestamp == 0) {
            return "Unknown (legacy lock)";
        }
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");
        return formatter.format(new Date(timestamp));
    }
}
