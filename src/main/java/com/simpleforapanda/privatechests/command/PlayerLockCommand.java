package com.simpleforapanda.privatechests.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.simpleforapanda.privatechests.PrivateChests;
import com.simpleforapanda.privatechests.model.LockRecord;
import com.simpleforapanda.privatechests.service.AccessControlService;
import com.simpleforapanda.privatechests.state.LockState;
import com.simpleforapanda.privatechests.util.ContainerUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.Set;

/**
 * Player-facing {@code /lock} commands.
 *
 * <ul>
 *   <li>{@code /lock list}                    – list all containers you have locked</li>
 *   <li>{@code /lock info <pos>}               – show details about a lock (owner or admin only)</li>
 *   <li>{@code /lock transfer <player> <pos>}  – transfer ownership of a lock to another player</li>
 * </ul>
 *
 * All sub-commands are accessible to every player.  {@code info} and {@code transfer}
 * require the caller to own the lock, unless they are an admin.
 */
public class PlayerLockCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("lock")
                .then(Commands.literal("list")
                    .executes(PlayerLockCommand::executeList)
                )
                .then(Commands.literal("info")
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(PlayerLockCommand::executeInfo)
                    )
                )
                .then(Commands.literal("transfer")
                    .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                            .executes(PlayerLockCommand::executeTransfer)
                        )
                    )
                )
        );
    }

    // -------------------------------------------------------------------------
    // /lock list
    // -------------------------------------------------------------------------

    private static int executeList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        MinecraftServer server = source.getServer();
        LockState lockState = LockState.get(server);
        Set<LockRecord> myLocks = lockState.getLocksByOwner(player.getUUID());

        if (myLocks.isEmpty()) {
            source.sendSuccess(() -> Component.literal("You have no locked containers."), false);
            return 0;
        }

        int maxLocks = PrivateChests.getConfig().getMaxLocksPerPlayer();
        String limitSuffix = maxLocks > 0 ? " / " + maxLocks : "";
        source.sendSuccess(() -> Component.literal(
            "===== Your Locked Containers (" + myLocks.size() + limitSuffix + ") ====="
        ), false);

        ServerLevel level = source.getLevel();
        for (LockRecord lock : myLocks) {
            String containerType = ContainerUtils.getContainerTypeName(level, lock.getContainerPositions());
            String pos = ContainerUtils.positionToString(ContainerUtils.getPrimaryPosition(lock.getContainerPositions()));
            String users = lock.getAllowedUsers().isEmpty()
                ? "(none)"
                : String.join(", ", lock.getAllowedUsers());
            source.sendSuccess(() -> Component.literal(
                "  " + containerType + " at " + pos + " | Allowed: " + users
            ), false);
        }

        return myLocks.size();
    }

    // -------------------------------------------------------------------------
    // /lock info <pos>
    // -------------------------------------------------------------------------

    private static int executeInfo(CommandContext<CommandSourceStack> ctx) {
        try {
            BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
            CommandSourceStack source = ctx.getSource();

            if (!(source.getEntity() instanceof ServerPlayer player)) {
                source.sendFailure(Component.literal("This command must be run by a player."));
                return 0;
            }

            MinecraftServer server = source.getServer();
            ServerLevel level = source.getLevel();
            LockState lockState = LockState.get(server);

            Set<BlockPos> containerGroup = ContainerUtils.getContainerGroup(level, pos);
            if (containerGroup.isEmpty()) {
                source.sendFailure(Component.literal("No lockable container found at " + ContainerUtils.positionToString(pos)));
                return 0;
            }

            Optional<LockRecord> lockOpt = Optional.empty();
            for (BlockPos groupPos : containerGroup) {
                lockOpt = lockState.getLock(groupPos);
                if (lockOpt.isPresent()) break;
            }

            if (lockOpt.isEmpty()) {
                source.sendFailure(Component.literal("No lock found at " + ContainerUtils.positionToString(pos)));
                return 0;
            }

            LockRecord lock = lockOpt.get();

            // Only the owner or an admin may view lock details
            boolean isOwner = player.getUUID().equals(lock.getOwnerUuid());
            boolean isAdmin = AccessControlService.isAdmin(player);
            if (!isOwner && !isAdmin) {
                source.sendFailure(Component.literal("You do not own the lock at " + ContainerUtils.positionToString(pos)));
                return 0;
            }

            String containerType = ContainerUtils.getContainerTypeName(level, containerGroup);
            String location = ContainerUtils.positionToString(ContainerUtils.getPrimaryPosition(lock.getContainerPositions()));
            // Prefer the current online name if available
            String ownerName = AccessControlService.getOwnerName(server, lock);

            source.sendSuccess(() -> Component.literal("===== Lock Information ====="), false);
            source.sendSuccess(() -> Component.literal("Container: " + containerType), false);
            source.sendSuccess(() -> Component.literal("Location:  " + location), false);
            source.sendSuccess(() -> Component.literal("Owner:     " + ownerName), false);

            if (lock.getAllowedUsers().isEmpty()) {
                source.sendSuccess(() -> Component.literal("Allowed:   (none – owner only)"), false);
            } else {
                source.sendSuccess(() -> Component.literal("Allowed:   " + String.join(", ", lock.getAllowedUsers())), false);
            }

            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    // -------------------------------------------------------------------------
    // /lock transfer <player> <pos>
    // -------------------------------------------------------------------------

    private static int executeTransfer(CommandContext<CommandSourceStack> ctx) {
        try {
            CommandSourceStack source = ctx.getSource();

            if (!(source.getEntity() instanceof ServerPlayer callerPlayer)) {
                source.sendFailure(Component.literal("This command must be run by a player."));
                return 0;
            }

            ServerPlayer targetPlayer = EntityArgument.getPlayer(ctx, "player");
            BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");

            MinecraftServer server = source.getServer();
            ServerLevel level = source.getLevel();
            LockState lockState = LockState.get(server);

            Set<BlockPos> containerGroup = ContainerUtils.getContainerGroup(level, pos);
            if (containerGroup.isEmpty()) {
                source.sendFailure(Component.literal("No lockable container found at " + ContainerUtils.positionToString(pos)));
                return 0;
            }

            Optional<LockRecord> lockOpt = Optional.empty();
            for (BlockPos groupPos : containerGroup) {
                lockOpt = lockState.getLock(groupPos);
                if (lockOpt.isPresent()) break;
            }

            if (lockOpt.isEmpty()) {
                source.sendFailure(Component.literal("No lock found at " + ContainerUtils.positionToString(pos)));
                return 0;
            }

            LockRecord lock = lockOpt.get();

            // Only the owner or an admin may transfer
            boolean isOwner = callerPlayer.getUUID().equals(lock.getOwnerUuid());
            boolean isAdmin = AccessControlService.isAdmin(callerPlayer);
            if (!isOwner && !isAdmin) {
                source.sendFailure(Component.literal("You do not own the lock at " + ContainerUtils.positionToString(pos)));
                return 0;
            }

            if (targetPlayer.getUUID().equals(lock.getOwnerUuid())) {
                source.sendFailure(Component.literal(targetPlayer.getName().getString() + " already owns this lock."));
                return 0;
            }

            // Enforce per-player limit for the new owner (the target is exempt if they are an admin)
            int maxLocks = PrivateChests.getConfig().getMaxLocksPerPlayer();
            if (maxLocks > 0 && !AccessControlService.isAdmin(targetPlayer)) {
                int targetCurrentLocks = lockState.countLocksForPlayer(targetPlayer.getUUID());
                if (targetCurrentLocks >= maxLocks) {
                    source.sendFailure(Component.literal(
                        targetPlayer.getName().getString() + " has reached their lock limit of " + maxLocks + "."
                    ));
                    return 0;
                }
            }

            // Build the transferred lock record
            LockRecord transferredLock = new LockRecord(
                targetPlayer.getUUID(),
                targetPlayer.getName().getString(),
                lock.getSignPos(),
                lock.getContainerPositions(),
                lock.getAllowedUsers(),
                lock.getCreatedAt(),
                System.currentTimeMillis()
            );

            lockState.removeLock(lock.getContainerPositions().iterator().next());
            lockState.addLock(transferredLock);

            PrivateChests.LOGGER.info("Player {} transferred lock at {} to {}",
                callerPlayer.getName().getString(), pos, targetPlayer.getName().getString());

            String newOwner = targetPlayer.getName().getString();
            source.sendSuccess(() -> Component.literal(
                "Lock at " + ContainerUtils.positionToString(pos) + " transferred to " + newOwner + "."
            ), true);

            // Notify the new owner if they are online
            targetPlayer.sendSystemMessage(Component.literal(
                callerPlayer.getName().getString() + " has transferred a locked container to you at "
                + ContainerUtils.positionToString(pos) + "."
            ));

            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }
}
