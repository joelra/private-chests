package com.simpleforapanda.privatechests.gametest;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;

import static com.simpleforapanda.privatechests.gametest.GameTestFixtures.editSign;
import static com.simpleforapanda.privatechests.gametest.GameTestFixtures.lockAt;

/**
 * In-world tests covering destruction protection: block breaking and explosions.
 */
public class BreakProtectionGameTest {

    private static final BlockPos CHEST_REL = new BlockPos(2, 1, 2);
    private static final BlockPos SIGN_REL = CHEST_REL.east();

    private static ServerPlayer setUpLockedChest(GameTestHelper helper) {
        helper.setBlock(CHEST_REL, Blocks.CHEST);
        helper.setBlock(SIGN_REL, Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.EAST));

        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        editSign(helper, SIGN_REL, owner, "[private]");
        return owner;
    }

    private static boolean tryBreak(GameTestHelper helper, ServerPlayer player, BlockPos rel) {
        BlockPos abs = helper.absolutePos(rel);
        return PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(
            helper.getLevel(),
            player,
            abs,
            helper.getLevel().getBlockState(abs),
            helper.getLevel().getBlockEntity(abs)
        );
    }

    @GameTest
    public void strangerCannotBreakLockedChest(GameTestHelper helper) {
        setUpLockedChest(helper);

        ServerPlayer stranger = helper.makeMockServerPlayerInLevel();
        if (tryBreak(helper, stranger, CHEST_REL)) {
            helper.fail("Stranger should not be able to break a locked chest");
            return;
        }
        if (lockAt(helper, CHEST_REL).isEmpty()) {
            helper.fail("The denied break must not remove the lock");
            return;
        }

        helper.succeed();
    }

    @GameTest
    public void ownerBreakingChestRemovesLock(GameTestHelper helper) {
        ServerPlayer owner = setUpLockedChest(helper);

        if (!tryBreak(helper, owner, CHEST_REL)) {
            helper.fail("Owner should be able to break their own locked chest");
            return;
        }
        if (lockAt(helper, CHEST_REL).isPresent()) {
            helper.fail("Breaking the chest should remove the lock");
            return;
        }

        helper.succeed();
    }

    @GameTest
    public void strangerCannotBreakProtectionSign(GameTestHelper helper) {
        setUpLockedChest(helper);

        ServerPlayer stranger = helper.makeMockServerPlayerInLevel();
        if (tryBreak(helper, stranger, SIGN_REL)) {
            helper.fail("Stranger should not be able to break a protection sign");
            return;
        }
        if (lockAt(helper, CHEST_REL).isEmpty()) {
            helper.fail("The denied sign break must not remove the lock");
            return;
        }

        helper.succeed();
    }

    @GameTest
    public void ownerBreakingSignRemovesLock(GameTestHelper helper) {
        ServerPlayer owner = setUpLockedChest(helper);

        if (!tryBreak(helper, owner, SIGN_REL)) {
            helper.fail("Owner should be able to break their own protection sign");
            return;
        }
        if (lockAt(helper, CHEST_REL).isPresent()) {
            helper.fail("Breaking the protection sign should remove the lock");
            return;
        }

        helper.succeed();
    }

    @GameTest(maxTicks = 200, padding = 4)
    public void explosionSparesLockedChestAndSign(GameTestHelper helper) {
        BlockPos lockedChestRel = new BlockPos(1, 1, 3);
        BlockPos signRel = lockedChestRel.west();
        BlockPos tntRel = new BlockPos(3, 1, 3);
        BlockPos controlChestRel = new BlockPos(5, 1, 3);

        helper.setBlock(lockedChestRel, Blocks.CHEST);
        helper.setBlock(signRel, Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.WEST));
        helper.setBlock(controlChestRel, Blocks.CHEST);
        helper.setBlock(tntRel, Blocks.TNT);

        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        editSign(helper, signRel, owner, "[private]");

        helper.pulseRedstone(tntRel.above(), 4);

        helper.runAfterDelay(140, () -> {
            // The control chest proves the explosion actually happened and reached
            // as far as the protected blocks.
            helper.assertBlockNotPresent(Blocks.CHEST, controlChestRel);
            helper.assertBlockPresent(Blocks.CHEST, lockedChestRel);
            helper.assertBlockPresent(Blocks.OAK_WALL_SIGN, signRel);
            if (lockAt(helper, lockedChestRel).isEmpty()) {
                helper.fail("The lock should survive the explosion");
                return;
            }
            helper.succeed();
        });
    }
}
