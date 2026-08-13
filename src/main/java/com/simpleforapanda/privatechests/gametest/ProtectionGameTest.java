package com.simpleforapanda.privatechests.gametest;

import com.simpleforapanda.privatechests.model.AccessMode;
import com.simpleforapanda.privatechests.model.LockRecord;
import com.simpleforapanda.privatechests.service.AccessControlService;
import com.simpleforapanda.privatechests.state.LockState;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * In-world integration tests covering the protection flows that need a real level:
 * sign-driven lock creation, access control, and automation blocking.
 */
public class ProtectionGameTest {

    private static final UUID ABSENT_OWNER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @GameTest
    public void signEditCreatesLockAndControlsAccess(GameTestHelper helper) {
        BlockPos chestRel = new BlockPos(2, 1, 2);
        BlockPos signRel = chestRel.east();
        helper.setBlock(chestRel, Blocks.CHEST);
        helper.setBlock(signRel, Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.EAST));

        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        editSign(helper, signRel, owner, "[private]");

        BlockPos chestAbs = helper.absolutePos(chestRel);
        LockState lockState = LockState.get(helper.getLevel().getServer());
        LockRecord lock = lockState.getLock(helper.getLevel(), chestAbs).orElse(null);

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
    public void strangerCannotRemoveSomeoneElsesLock(GameTestHelper helper) {
        BlockPos chestRel = new BlockPos(2, 1, 2);
        BlockPos signRel = chestRel.east();
        helper.setBlock(chestRel, Blocks.CHEST);
        helper.setBlock(signRel, Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.EAST));

        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        editSign(helper, signRel, owner, "[private]");

        // A stranger rewriting the sign without a marker must not release the lock
        ServerPlayer stranger = helper.makeMockServerPlayerInLevel();
        editSign(helper, signRel, stranger, "not a marker");

        BlockPos chestAbs = helper.absolutePos(chestRel);
        LockState lockState = LockState.get(helper.getLevel().getServer());
        LockRecord lock = lockState.getLock(helper.getLevel(), chestAbs).orElse(null);

        if (lock == null) {
            helper.fail("Stranger's sign edit must not remove the lock");
            return;
        }
        if (!owner.getUUID().equals(lock.getOwnerUuid())) {
            helper.fail("Lock should still belong to the original owner");
            return;
        }

        SignBlockEntity sign = helper.getBlockEntity(signRel, SignBlockEntity.class);
        if (AccessMode.fromSign(sign, true).isEmpty()) {
            helper.fail("The blocked edit should not have overwritten the sign text");
            return;
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
        lockDirectly(helper, chestRel);

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
        lockDirectly(helper, chestRel);

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

    private static void editSign(GameTestHelper helper, BlockPos signRel, ServerPlayer editor, String firstLine) {
        SignBlockEntity sign = helper.getBlockEntity(signRel, SignBlockEntity.class);
        sign.setAllowedPlayerEditor(editor.getUUID());
        sign.updateSignText(editor, true, List.of(
            FilteredText.passThrough(firstLine),
            FilteredText.passThrough(""),
            FilteredText.passThrough(""),
            FilteredText.passThrough("")
        ));
    }

    /**
     * Registers a lock for the container without a physical sign. The automation
     * paths only consult the lock record, so no sign is needed for those tests.
     */
    private static void lockDirectly(GameTestHelper helper, BlockPos containerRel) {
        LockState lockState = LockState.get(helper.getLevel().getServer());
        lockState.addLock(new LockRecord(
            helper.getLevel().dimension(),
            ABSENT_OWNER,
            "TestOwner",
            helper.absolutePos(containerRel.north()),
            Set.of(helper.absolutePos(containerRel)),
            AccessMode.PRIVATE,
            Set.of()
        ));
    }
}
