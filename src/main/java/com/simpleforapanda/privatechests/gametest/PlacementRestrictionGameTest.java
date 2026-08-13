package com.simpleforapanda.privatechests.gametest;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import static com.simpleforapanda.privatechests.gametest.GameTestFixtures.editSign;

/**
 * In-world tests covering the sign- and chest-placement restrictions around
 * locked containers, driven through the real UseBlockCallback path.
 */
public class PlacementRestrictionGameTest {

    private static final BlockPos CHEST_REL = new BlockPos(2, 1, 2);
    private static final BlockPos SIGN_REL = CHEST_REL.east();

    private static ServerPlayer setUpLockedChest(GameTestHelper helper) {
        helper.setBlock(CHEST_REL, Blocks.CHEST);
        helper.setBlock(SIGN_REL, Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.EAST));

        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        editSign(helper, SIGN_REL, owner, "[private]");
        return owner;
    }

    private static InteractionResult useHeldItemOn(
        GameTestHelper helper,
        ServerPlayer player,
        InteractionHand hand,
        Item heldItem,
        BlockPos targetRel,
        Direction face
    ) {
        player.setItemInHand(hand, new ItemStack(heldItem));
        BlockPos targetAbs = helper.absolutePos(targetRel);
        return UseBlockCallback.EVENT.invoker().interact(
            player,
            helper.getLevel(),
            hand,
            new BlockHitResult(Vec3.atCenterOf(targetAbs), face, targetAbs, false)
        );
    }

    @GameTest
    public void strangerCannotPlaceSignOnLockedChest(GameTestHelper helper) {
        setUpLockedChest(helper);

        ServerPlayer stranger = helper.makeMockServerPlayerInLevel();
        InteractionResult result = useHeldItemOn(helper, stranger, InteractionHand.MAIN_HAND, Items.OAK_SIGN, CHEST_REL, Direction.NORTH);

        if (result != InteractionResult.FAIL) {
            helper.fail("Stranger should not be able to place a sign on a locked chest");
            return;
        }

        helper.succeed();
    }

    @GameTest
    public void ownerCanPlaceSignOnOwnLockedChest(GameTestHelper helper) {
        ServerPlayer owner = setUpLockedChest(helper);

        InteractionResult result = useHeldItemOn(helper, owner, InteractionHand.MAIN_HAND, Items.OAK_SIGN, CHEST_REL, Direction.NORTH);

        if (result != InteractionResult.PASS) {
            helper.fail("Owner should be able to place additional signs on their own chest");
            return;
        }

        helper.succeed();
    }

    @GameTest
    public void strangerCannotPlaceChestNextToLockedChest(GameTestHelper helper) {
        setUpLockedChest(helper);
        BlockPos stoneRel = new BlockPos(4, 1, 2);
        helper.setBlock(stoneRel, Blocks.STONE);

        // Placing against the stone's west face would put the chest at (3,1,2),
        // directly adjacent to the locked chest.
        ServerPlayer stranger = helper.makeMockServerPlayerInLevel();
        InteractionResult result = useHeldItemOn(helper, stranger, InteractionHand.MAIN_HAND, Items.CHEST, stoneRel, Direction.WEST);

        if (result != InteractionResult.FAIL) {
            helper.fail("Stranger should not be able to place a chest next to a locked chest");
            return;
        }

        helper.succeed();
    }

    @GameTest
    public void offHandPlacementIsAlsoBlocked(GameTestHelper helper) {
        // Regression test: the handler used to skip the off-hand entirely,
        // letting sneak-placements bypass the restrictions.
        setUpLockedChest(helper);
        BlockPos stoneRel = new BlockPos(4, 1, 2);
        helper.setBlock(stoneRel, Blocks.STONE);

        ServerPlayer stranger = helper.makeMockServerPlayerInLevel();

        InteractionResult signResult = useHeldItemOn(helper, stranger, InteractionHand.OFF_HAND, Items.OAK_SIGN, CHEST_REL, Direction.NORTH);
        if (signResult != InteractionResult.FAIL) {
            helper.fail("Off-hand sign placement on a locked chest should be blocked");
            return;
        }

        InteractionResult chestResult = useHeldItemOn(helper, stranger, InteractionHand.OFF_HAND, Items.CHEST, stoneRel, Direction.WEST);
        if (chestResult != InteractionResult.FAIL) {
            helper.fail("Off-hand chest placement next to a locked chest should be blocked");
            return;
        }

        helper.succeed();
    }
}
