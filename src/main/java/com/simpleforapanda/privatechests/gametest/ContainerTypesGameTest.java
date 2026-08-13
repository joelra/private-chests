package com.simpleforapanda.privatechests.gametest;

import com.simpleforapanda.privatechests.model.LockRecord;
import com.simpleforapanda.privatechests.service.AccessControlService;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import static com.simpleforapanda.privatechests.gametest.GameTestFixtures.editSign;
import static com.simpleforapanda.privatechests.gametest.GameTestFixtures.lockAt;

/**
 * In-world tests covering every lockable container type and double-chest handling.
 */
public class ContainerTypesGameTest {

    @GameTest
    public void barrelCanBeLocked(GameTestHelper helper) {
        assertSingleContainerLockable(helper, Blocks.BARREL);
    }

    @GameTest
    public void trappedChestCanBeLocked(GameTestHelper helper) {
        assertSingleContainerLockable(helper, Blocks.TRAPPED_CHEST);
    }

    private static void assertSingleContainerLockable(GameTestHelper helper, Block containerBlock) {
        BlockPos containerRel = new BlockPos(2, 1, 2);
        BlockPos signRel = containerRel.east();
        helper.setBlock(containerRel, containerBlock);
        helper.setBlock(signRel, Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.EAST));

        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        editSign(helper, signRel, owner, "[private]");

        if (lockAt(helper, containerRel).isEmpty()) {
            helper.fail("A [private] sign should lock this container type");
            return;
        }

        ServerPlayer stranger = helper.makeMockServerPlayerInLevel();
        if (AccessControlService.canAccess(stranger, helper.getLevel(), helper.absolutePos(containerRel)).allowed()) {
            helper.fail("Stranger should be denied access to the locked container");
            return;
        }
        if (!AccessControlService.canAccess(owner, helper.getLevel(), helper.absolutePos(containerRel)).allowed()) {
            helper.fail("Owner should be able to open their locked container");
            return;
        }

        helper.succeed();
    }

    @GameTest
    public void doubleChestLockCoversBothHalves(GameTestHelper helper) {
        BlockPos leftRel = new BlockPos(2, 1, 2);
        BlockPos rightRel = leftRel.east();
        placeDoubleChest(helper, leftRel, rightRel);

        BlockPos signRel = leftRel.west();
        helper.setBlock(signRel, Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.WEST));

        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        editSign(helper, signRel, owner, "[private]");

        LockRecord lock = lockAt(helper, leftRel).orElse(null);
        if (lock == null) {
            helper.fail("The sign on one half should lock the double chest");
            return;
        }
        if (lock.getContainerPositions().size() != 2) {
            helper.fail("The lock should cover both halves of the double chest");
            return;
        }

        // The half without the sign must be protected too
        ServerPlayer stranger = helper.makeMockServerPlayerInLevel();
        if (AccessControlService.canAccess(stranger, helper.getLevel(), helper.absolutePos(rightRel)).allowed()) {
            helper.fail("The signless half of a locked double chest should still be protected");
            return;
        }

        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void ownerExtendingChestGrowsTheLock(GameTestHelper helper) {
        BlockPos chestRel = new BlockPos(2, 1, 2);
        BlockPos signRel = chestRel.west();
        BlockPos newHalfRel = chestRel.east();
        BlockPos stoneRel = newHalfRel.east();

        helper.setBlock(chestRel, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH));
        helper.setBlock(signRel, Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.WEST));
        helper.setBlock(stoneRel, Blocks.STONE);

        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        editSign(helper, signRel, owner, "[private]");

        // Owner "places" a chest against the stone's west face, extending their chest
        owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CHEST));
        BlockPos stoneAbs = helper.absolutePos(stoneRel);
        InteractionResult result = UseBlockCallback.EVENT.invoker().interact(
            owner,
            helper.getLevel(),
            InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(stoneAbs), Direction.WEST, stoneAbs, false)
        );
        if (result != InteractionResult.PASS) {
            helper.fail("Owner should be allowed to place a chest next to their own locked chest");
            return;
        }
        placeDoubleChest(helper, chestRel, newHalfRel);

        helper.runAfterDelay(10, () -> {
            LockRecord lock = lockAt(helper, chestRel).orElse(null);
            if (lock == null) {
                helper.fail("The lock should survive the chest extension");
                return;
            }
            if (lock.getContainerPositions().size() != 2
                || !lock.getContainerPositions().contains(helper.absolutePos(newHalfRel))) {
                helper.fail("The lock should extend to cover the new double-chest half");
                return;
            }
            helper.succeed();
        });
    }

    private static void placeDoubleChest(GameTestHelper helper, BlockPos leftRel, BlockPos rightRel) {
        // Facing north, the LEFT half's partner sits to its east
        helper.setBlock(leftRel, Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.FACING, Direction.NORTH)
            .setValue(BlockStateProperties.CHEST_TYPE, ChestType.LEFT));
        helper.setBlock(rightRel, Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.FACING, Direction.NORTH)
            .setValue(BlockStateProperties.CHEST_TYPE, ChestType.RIGHT));
    }
}
