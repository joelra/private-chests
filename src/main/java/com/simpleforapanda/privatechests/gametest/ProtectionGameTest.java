package com.simpleforapanda.privatechests.gametest;

import com.simpleforapanda.privatechests.PrivateChests;
import com.simpleforapanda.privatechests.model.AccessMode;
import com.simpleforapanda.privatechests.model.LockRecord;
import com.simpleforapanda.privatechests.service.AccessControlService;
import com.simpleforapanda.privatechests.service.SignEditService;
import com.simpleforapanda.privatechests.state.LockState;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserBanListEntry;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;

import java.util.Set;
import java.util.UUID;

import static com.simpleforapanda.privatechests.gametest.GameTestFixtures.ABSENT_OWNER;
import static com.simpleforapanda.privatechests.gametest.GameTestFixtures.editSign;
import static com.simpleforapanda.privatechests.gametest.GameTestFixtures.lockAt;
import static com.simpleforapanda.privatechests.gametest.GameTestFixtures.lockDirectly;
import static com.simpleforapanda.privatechests.gametest.GameTestFixtures.lockState;

/**
 * In-world integration tests covering lock creation, access control,
 * lock lifecycle features, and automation blocking.
 */
public class ProtectionGameTest {

    private static final BlockPos CHEST_REL = new BlockPos(2, 1, 2);
    private static final BlockPos SIGN_REL = CHEST_REL.east();

    private static void placeLockableChestWithSign(GameTestHelper helper) {
        helper.setBlock(CHEST_REL, Blocks.CHEST);
        helper.setBlock(SIGN_REL, Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.EAST));
    }

    @GameTest
    public void signEditCreatesLockAndControlsAccess(GameTestHelper helper) {
        placeLockableChestWithSign(helper);

        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        editSign(helper, SIGN_REL, owner, "[private]");

        BlockPos chestAbs = helper.absolutePos(CHEST_REL);
        LockRecord lock = lockAt(helper, CHEST_REL).orElse(null);

        if (lock == null) {
            helper.fail("Writing [private] on an attached sign should create a lock");
            return;
        }
        if (!owner.getUUID().equals(lock.getOwnerUuid())) {
            helper.fail("Lock owner should be the sign editor");
            return;
        }

        if (!AccessControlService.canAccess(owner, helper.getLevel(), chestAbs).allowed()) {
            helper.fail("Owner should be able to open their own locked chest");
            return;
        }

        ServerPlayer stranger = helper.makeMockServerPlayerInLevel();
        if (AccessControlService.canAccess(stranger, helper.getLevel(), chestAbs).allowed()) {
            helper.fail("Stranger should be denied access to a locked chest");
            return;
        }

        helper.succeed();
    }

    @GameTest
    public void rightClickingLockedChestOnlyOpensForOwner(GameTestHelper helper) {
        placeLockableChestWithSign(helper);

        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        editSign(helper, SIGN_REL, owner, "[private]");

        // Use the full vanilla interaction pipeline, exactly as a right-click
        // packet would, and check whether the chest screen actually opened.
        BlockPos chestAbs = helper.absolutePos(CHEST_REL);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(chestAbs), Direction.NORTH, chestAbs, false);

        ServerPlayer stranger = helper.makeMockServerPlayerInLevel();
        stranger.gameMode.useItemOn(stranger, helper.getLevel(), ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit);
        if (stranger.containerMenu != stranger.inventoryMenu) {
            helper.fail("Right-clicking a locked chest must not open it for a stranger");
            return;
        }

        owner.gameMode.useItemOn(owner, helper.getLevel(), ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit);
        if (!(owner.containerMenu instanceof ChestMenu)) {
            helper.fail("Right-clicking their own locked chest should open it for the owner");
            return;
        }
        owner.closeContainer();

        helper.succeed();
    }

    @GameTest
    public void strangerCannotRemoveSomeoneElsesLock(GameTestHelper helper) {
        placeLockableChestWithSign(helper);

        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        editSign(helper, SIGN_REL, owner, "[private]");

        // A stranger rewriting the sign without a marker must not release the lock
        ServerPlayer stranger = helper.makeMockServerPlayerInLevel();
        editSign(helper, SIGN_REL, stranger, "not a marker");

        LockRecord lock = lockAt(helper, CHEST_REL).orElse(null);
        if (lock == null) {
            helper.fail("Stranger's sign edit must not remove the lock");
            return;
        }
        if (!owner.getUUID().equals(lock.getOwnerUuid())) {
            helper.fail("Lock should still belong to the original owner");
            return;
        }

        SignBlockEntity sign = helper.getBlockEntity(SIGN_REL, SignBlockEntity.class);
        if (AccessMode.fromSign(sign, true).isEmpty()) {
            helper.fail("The blocked edit should not have overwritten the sign text");
            return;
        }

        helper.succeed();
    }

    @GameTest
    public void publicSignLetsAnyoneOpenButOnlyOwnerManage(GameTestHelper helper) {
        placeLockableChestWithSign(helper);

        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        editSign(helper, SIGN_REL, owner, "[public]");

        BlockPos chestAbs = helper.absolutePos(CHEST_REL);
        LockRecord lock = lockAt(helper, CHEST_REL).orElse(null);
        if (lock == null || lock.getAccessMode() != AccessMode.PUBLIC) {
            helper.fail("Writing [public] should create a public lock");
            return;
        }

        ServerPlayer stranger = helper.makeMockServerPlayerInLevel();
        if (!AccessControlService.canAccess(stranger, helper.getLevel(), chestAbs).allowed()) {
            helper.fail("Anyone should be able to open a public chest");
            return;
        }

        // But a stranger still must not be able to release the protection
        editSign(helper, SIGN_REL, stranger, "not a marker");
        if (lockAt(helper, CHEST_REL).isEmpty()) {
            helper.fail("Stranger's edit must not remove a public lock");
            return;
        }

        helper.succeed();
    }

    @GameTest
    public void allowedUserCanOpenPrivateChest(GameTestHelper helper) {
        placeLockableChestWithSign(helper);

        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        editSign(helper, SIGN_REL, owner, "[private]");

        ServerPlayer guest = helper.makeMockServerPlayerInLevel();
        LockRecord lock = lockAt(helper, CHEST_REL).orElseThrow();

        // All mock players share one name, so re-own the lock to an absent player
        // and list the guest's name; the physical sign stays valid.
        LockState lockState = lockState(helper);
        lockState.removeLock(lock);
        lockState.addLock(new LockRecord(
            lock.getDimension(),
            ABSENT_OWNER,
            "AbsentOwner",
            lock.getSignPos(),
            lock.getContainerPositions(),
            AccessMode.PRIVATE,
            Set.of(guest.getName().getString())
        ));

        if (!AccessControlService.canAccess(guest, helper.getLevel(), helper.absolutePos(CHEST_REL)).allowed()) {
            helper.fail("A player on the allowed-users list should be able to open the chest");
            return;
        }

        helper.succeed();
    }

    @GameTest
    public void dormantSignActivatesAfterPrimarySignRemoved(GameTestHelper helper) {
        placeLockableChestWithSign(helper);
        BlockPos dormantSignRel = CHEST_REL.north();
        helper.setBlock(dormantSignRel, Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.NORTH));

        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        editSign(helper, SIGN_REL, owner, "[private]");
        editSign(helper, dormantSignRel, owner, "[private]");

        if (lockState(helper).getDormantSign(helper.getLevel(), helper.absolutePos(dormantSignRel)).isEmpty()) {
            helper.fail("A second protection sign on a locked chest should be recorded as dormant");
            return;
        }

        // Owner breaks the primary sign, releasing the lock
        BlockPos signAbs = helper.absolutePos(SIGN_REL);
        boolean allowed = PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(
            helper.getLevel(), owner, signAbs, helper.getLevel().getBlockState(signAbs), helper.getBlockEntity(SIGN_REL, SignBlockEntity.class));
        if (!allowed) {
            helper.fail("Owner should be able to break their own protection sign");
            return;
        }
        helper.setBlock(SIGN_REL, Blocks.AIR);
        if (lockAt(helper, CHEST_REL).isPresent()) {
            helper.fail("Breaking the protection sign should remove the lock");
            return;
        }

        if (!SignEditService.reactivateDormantSign(owner, helper.getLevel(), helper.absolutePos(dormantSignRel))) {
            helper.fail("The dormant sign should reactivate once the container is unprotected");
            return;
        }

        LockRecord newLock = lockAt(helper, CHEST_REL).orElse(null);
        if (newLock == null || !newLock.getSignPos().equals(helper.absolutePos(dormantSignRel))) {
            helper.fail("Reactivation should create a lock controlled by the dormant sign");
            return;
        }

        helper.succeed();
    }

    @GameTest
    public void maxLocksPerPlayerIsEnforced(GameTestHelper helper) {
        BlockPos secondChestRel = new BlockPos(4, 1, 2);
        BlockPos secondSignRel = secondChestRel.east();
        placeLockableChestWithSign(helper);
        helper.setBlock(secondChestRel, Blocks.CHEST);
        helper.setBlock(secondSignRel, Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.EAST));

        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        int previousLimit = PrivateChests.getConfig().maxLocksPerPlayer;
        try {
            PrivateChests.getConfig().maxLocksPerPlayer = 1;

            editSign(helper, SIGN_REL, owner, "[private]");
            editSign(helper, secondSignRel, owner, "[private]");
        } finally {
            PrivateChests.getConfig().maxLocksPerPlayer = previousLimit;
        }

        if (lockAt(helper, CHEST_REL).isEmpty()) {
            helper.fail("The first lock should be within the limit");
            return;
        }
        if (lockAt(helper, secondChestRel).isPresent()) {
            helper.fail("The second lock should have been rejected by maxLocksPerPlayer=1");
            return;
        }

        helper.succeed();
    }

    @GameTest
    public void bannedOwnerDisablesProtection(GameTestHelper helper) {
        helper.setBlock(CHEST_REL, Blocks.CHEST);
        UUID bannedOwner = UUID.randomUUID();
        lockDirectly(helper, CHEST_REL, bannedOwner, "BannedOwner");

        helper.getLevel().getServer().getPlayerList().getBans()
            .add(new UserBanListEntry(new NameAndId(bannedOwner, "BannedOwner")));

        ServerPlayer stranger = helper.makeMockServerPlayerInLevel();
        if (!AccessControlService.canAccess(stranger, helper.getLevel(), helper.absolutePos(CHEST_REL)).allowed()) {
            helper.fail("Protection should be suspended while the owner is banned");
            return;
        }

        helper.succeed();
    }

    @GameTest
    public void adminBypassesLockAccess(GameTestHelper helper) {
        helper.setBlock(CHEST_REL, Blocks.CHEST);
        lockDirectly(helper, CHEST_REL, ABSENT_OWNER, "AbsentOwner");

        ServerPlayer admin = helper.makeMockServerPlayerInLevel();
        NameAndId adminId = new NameAndId(admin.getUUID(), admin.getName().getString());
        // The game test server's default op permission set is level 0, so op
        // with an explicit OWNER-level permission set.
        helper.getLevel().getServer().getPlayerList()
            .op(adminId, java.util.Optional.of(LevelBasedPermissionSet.OWNER), java.util.Optional.of(false));
        try {
            if (!AccessControlService.isAdmin(admin)) {
                helper.fail("An opped player should count as admin");
                return;
            }
            if (!AccessControlService.canAccess(admin, helper.getLevel(), helper.absolutePos(CHEST_REL)).allowed()) {
                helper.fail("An admin should bypass someone else's lock");
                return;
            }
        } finally {
            helper.getLevel().getServer().getPlayerList().deop(adminId);
        }

        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void hopperCannotExtractFromLockedChest(GameTestHelper helper) {
        BlockPos hopperRel = new BlockPos(1, 1, 1);
        BlockPos chestRel = hopperRel.above();
        helper.setBlock(chestRel, Blocks.CHEST);
        helper.setBlock(hopperRel, Blocks.HOPPER);

        helper.getBlockEntity(chestRel, ChestBlockEntity.class).setItem(0, new ItemStack(Items.DIAMOND));
        lockDirectly(helper, chestRel, ABSENT_OWNER, "TestOwner");

        helper.runAfterDelay(40, () -> {
            if (!helper.getBlockEntity(hopperRel, HopperBlockEntity.class).isEmpty()) {
                helper.fail("Hopper extracted from a locked chest");
                return;
            }
            if (helper.getBlockEntity(chestRel, ChestBlockEntity.class).getItem(0).isEmpty()) {
                helper.fail("Item disappeared from the locked chest");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void dropperCannotInsertIntoLockedChest(GameTestHelper helper) {
        BlockPos dropperRel = new BlockPos(1, 1, 1);
        BlockPos chestRel = dropperRel.east();
        helper.setBlock(chestRel, Blocks.CHEST);
        helper.setBlock(dropperRel, Blocks.DROPPER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST));

        helper.getBlockEntity(dropperRel, DispenserBlockEntity.class).setItem(0, new ItemStack(Items.DIAMOND));
        lockDirectly(helper, chestRel, ABSENT_OWNER, "TestOwner");

        helper.pulseRedstone(dropperRel.above(), 4);

        helper.runAfterDelay(40, () -> {
            if (!helper.getBlockEntity(chestRel, ChestBlockEntity.class).isEmpty()) {
                helper.fail("Dropper inserted into a locked chest");
                return;
            }
            if (helper.getBlockEntity(dropperRel, DispenserBlockEntity.class).isEmpty()) {
                helper.fail("Dropper should have kept its item");
                return;
            }
            helper.succeed();
        });
    }
}
