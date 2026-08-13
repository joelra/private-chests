package com.simpleforapanda.privatechests.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.simpleforapanda.privatechests.PrivateChests;
import com.simpleforapanda.privatechests.config.ModConfig;
import com.simpleforapanda.privatechests.model.AccessMode;
import com.simpleforapanda.privatechests.model.DormantSignRecord;
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
                .requires(source -> AccessControlService.hasAdminPermission(source.permissions()))
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
                    .executes(ctx -> executeList(ctx, null))
                    .then(Commands.literal("public")
                        .executes(ctx -> executeList(ctx, AccessMode.PUBLIC))
                    )
                    .then(Commands.literal("private")
                        .executes(ctx -> executeList(ctx, AccessMode.PRIVATE))
                    )
                )
                .then(Commands.literal("list_in_area")
                    .executes(ctx -> executeListInArea(ctx, 1, null)) // Default 2x2 chunks (radius 1)
                    .then(Commands.literal("public")
                        .executes(ctx -> executeListInArea(ctx, 1, AccessMode.PUBLIC))
                    )
                    .then(Commands.literal("private")
                        .executes(ctx -> executeListInArea(ctx, 1, AccessMode.PRIVATE))
                    )
                    .then(Commands.argument("radius", IntegerArgumentType.integer(0, 10))
                        .executes(ctx -> executeListInArea(ctx, IntegerArgumentType.getInteger(ctx, "radius"), null))
                        .then(Commands.literal("public")
                            .executes(ctx -> executeListInArea(ctx, IntegerArgumentType.getInteger(ctx, "radius"), AccessMode.PUBLIC))
                        )
                        .then(Commands.literal("private")
                            .executes(ctx -> executeListInArea(ctx, IntegerArgumentType.getInteger(ctx, "radius"), AccessMode.PRIVATE))
                        )
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
                .requires(source -> AccessControlService.hasAdminPermission(source.permissions()))
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
            Optional<LockRecord> lockOpt = lockState.getLock(level, containerGroup);
            if (lockOpt.isEmpty()) {
                source.sendFailure(Component.literal("No lock found at " + ContainerUtils.positionToString(pos)));
                return 0;
            }

            // Remove lock
            lockState.removeLock(lockOpt.get());

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
            lockState.removeLock(lock);
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
     * Removes active lock records and dormant sign records whose protection sign
     * no longer exists in the overworld. Only currently-loaded chunks are validated.
     */
    private static int executeCleanup(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        LockState lockState = LockState.get(server);

        int[] removedLockCount = {0};
        lockState.cleanupDanglingLocks(record -> {
            BlockPos signPos = record.getSignPos();
            ServerLevel level = server.getLevel(record.getDimension());
            // Only validate if the record's dimension exists and the chunk is currently loaded
            if (level == null || !level.isLoaded(signPos)) {
                return true; // assume valid if not loaded
            }
            boolean valid = SignUtils.isValidProtectionSign(level, signPos, record.getContainerPositions());
            if (!valid) {
                removedLockCount[0]++;
            }
            return valid;
        });

        int[] removedDormantCount = {0};
        lockState.cleanupDanglingDormantSigns(record -> isValidDormantSign(server, record, removedDormantCount));

        int removed = removedLockCount[0] + removedDormantCount[0];
        source.sendSuccess(() -> Component.literal(
            "Cleanup complete. Removed " + removed + " dangling record(s): "
                + removedLockCount[0] + " active lock(s), "
                + removedDormantCount[0] + " dormant sign record(s)."
        ), true);
        PrivateChests.LOGGER.info(
            "Admin {} ran cleanup, removed {} dangling lock(s) and {} dormant sign record(s)",
            source.getTextName(),
            removedLockCount[0],
            removedDormantCount[0]
        );

        return removed;
    }

    /**
     * Execute /private_chests list
     */
    private static int executeList(CommandContext<CommandSourceStack> ctx, AccessMode modeFilter) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        LockState lockState = LockState.get(server);

        Collection<LockRecord> locks = modeFilter == null
            ? lockState.getAllLocks()
            : lockState.getAllLocks().stream().filter(lock -> lock.getAccessMode() == modeFilter).toList();
        int totalCount = locks.size();

        if (totalCount == 0) {
            source.sendSuccess(() -> Component.literal("No " + describeFilter(modeFilter) + " protected containers found."), false);
            return 0;
        }

        ModConfig config = PrivateChests.getConfig();
        int maxEntries = config.getListMaxEntries();
        int previewEntries = config.getListPreviewEntries();

        source.sendSuccess(() -> Component.literal(
            "===== " + headerPrefix(modeFilter) + "Protected Containers (" + totalCount + " total) ====="
        ), false);

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
            Optional<LockRecord> lockOpt = lockState.getLock(level, containerGroup);
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
            source.sendSuccess(() -> Component.literal("Mode: " + lock.getAccessMode().name().toLowerCase()), false);

            if (lock.getAccessMode() == AccessMode.PUBLIC) {
                source.sendSuccess(() -> Component.literal("Allowed Users: everyone"), false);
            } else if (allowedUsers.isEmpty()) {
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
    private static int executeListInArea(CommandContext<CommandSourceStack> ctx, int chunkRadius, AccessMode modeFilter) {
        CommandSourceStack source = ctx.getSource();

        // Get source position
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("This command must be run by a player or from a specific location."));
            return 0;
        }

        MinecraftServer server = source.getServer();
        LockState lockState = LockState.get(server);
        BlockPos centerPos = player.blockPosition();

        List<LockRecord> locks = lockState.getLocksInArea(player.level().dimension(), centerPos, chunkRadius);
        if (modeFilter != null) {
            locks = locks.stream().filter(lock -> lock.getAccessMode() == modeFilter).toList();
        }

        if (locks.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                "No " + describeFilter(modeFilter) + " protected containers found in "
                    + (chunkRadius * 2) + "x" + (chunkRadius * 2) + " chunks around you."
            ), false);
            return 0;
        }

        int filteredCount = locks.size();
        source.sendSuccess(() -> Component.literal(
            "===== " + headerPrefix(modeFilter) + "Protected Containers in Area (" + filteredCount + " found) ====="
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

        ServerLevel lockLevel = server.getLevel(lock.getDimension());
        String containerType = lockLevel != null
            ? ContainerUtils.getContainerTypeName(lockLevel, lock.getContainerPositions())
            : "Container";
        String position = ContainerUtils.positionToString(ContainerUtils.getPrimaryPosition(lock.getContainerPositions()));
        String dimensionSuffix = lock.getDimension().equals(net.minecraft.world.level.Level.OVERWORLD)
            ? ""
            : " (" + lock.getDimension().identifier() + ")";

        source.sendSuccess(() -> Component.literal(
            "- " + containerType + " at " + position + dimensionSuffix + " | Owner: " + ownerName + " | Mode: " + lock.getAccessMode().name().toLowerCase()
        ), false);
    }

    private static boolean isValidDormantSign(MinecraftServer server, DormantSignRecord record, int[] removedDormantCount) {
        BlockPos signPos = record.getSignPos();
        ServerLevel level = server.getLevel(record.getDimension());
        if (level == null || !level.isLoaded(signPos)) {
            return true;
        }

        boolean valid = SignUtils.isProtectionSign(level, signPos)
            && SignUtils.getAttachedBlock(level, signPos)
                .map(attachedPos -> !ContainerUtils.getContainerGroup(level, attachedPos).isEmpty())
                .orElse(false);

        if (!valid) {
            removedDormantCount[0]++;
        }

        return valid;
    }

    private static String describeFilter(AccessMode modeFilter) {
        return modeFilter == null ? "" : modeFilter.name().toLowerCase() + " ";
    }

    private static String headerPrefix(AccessMode modeFilter) {
        if (modeFilter == null) {
            return "";
        }
        String value = modeFilter.name().toLowerCase();
        return Character.toUpperCase(value.charAt(0)) + value.substring(1) + " ";
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
