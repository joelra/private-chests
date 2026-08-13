package com.simpleforapanda.privatechests.state;

import com.simpleforapanda.privatechests.TestBootstrap;
import com.simpleforapanda.privatechests.model.AccessMode;
import com.simpleforapanda.privatechests.model.DormantSignRecord;
import com.simpleforapanda.privatechests.model.LockRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockStateTest {

    private static final UUID OWNER_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID OWNER_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final BlockPos CHEST_POS = new BlockPos(100, 64, 200);
    private static final BlockPos SIGN_POS = new BlockPos(100, 64, 199);

    private LockState state;

    @BeforeAll
    static void bootstrap() {
        TestBootstrap.init();
    }

    @BeforeEach
    void setUp() {
        state = new LockState();
    }

    private static LockRecord lockAt(ResourceKey<Level> dimension, UUID owner, BlockPos... containerPositions) {
        return new LockRecord(
            dimension,
            owner,
            "Owner",
            SIGN_POS,
            Set.of(containerPositions),
            AccessMode.PRIVATE,
            Set.of()
        );
    }

    @Test
    void lockIsFoundInItsOwnDimension() {
        state.addLock(lockAt(Level.OVERWORLD, OWNER_A, CHEST_POS));

        assertTrue(state.getLock(Level.OVERWORLD, CHEST_POS).isPresent());
    }

    @Test
    void lockIsNotVisibleFromOtherDimensions() {
        // Regression test: an overworld lock must not resolve for the same
        // coordinates in the Nether, which previously allowed remote unlocking.
        state.addLock(lockAt(Level.OVERWORLD, OWNER_A, CHEST_POS));

        assertTrue(state.getLock(Level.NETHER, CHEST_POS).isEmpty());
        assertTrue(state.getLock(Level.END, CHEST_POS).isEmpty());
    }

    @Test
    void samePositionInDifferentDimensionsHoldsIndependentLocks() {
        LockRecord overworldLock = lockAt(Level.OVERWORLD, OWNER_A, CHEST_POS);
        LockRecord netherLock = lockAt(Level.NETHER, OWNER_B, CHEST_POS);
        state.addLock(overworldLock);
        state.addLock(netherLock);

        assertEquals(OWNER_A, state.getLock(Level.OVERWORLD, CHEST_POS).orElseThrow().getOwnerUuid());
        assertEquals(OWNER_B, state.getLock(Level.NETHER, CHEST_POS).orElseThrow().getOwnerUuid());

        state.removeLock(overworldLock);

        assertTrue(state.getLock(Level.OVERWORLD, CHEST_POS).isEmpty());
        assertEquals(OWNER_B, state.getLock(Level.NETHER, CHEST_POS).orElseThrow().getOwnerUuid());
    }

    @Test
    void groupLookupFindsLockThroughAnyPosition() {
        BlockPos otherHalf = CHEST_POS.east();
        state.addLock(lockAt(Level.OVERWORLD, OWNER_A, CHEST_POS, otherHalf));

        assertTrue(state.getLock(Level.OVERWORLD, Set.of(otherHalf)).isPresent());
        assertTrue(state.getLock(Level.OVERWORLD, Set.of(new BlockPos(0, 0, 0), CHEST_POS)).isPresent());
        assertTrue(state.getLock(Level.OVERWORLD, Set.of(new BlockPos(0, 0, 0))).isEmpty());
    }

    @Test
    void removingLockClearsAllIndexedPositions() {
        BlockPos otherHalf = CHEST_POS.east();
        LockRecord lock = lockAt(Level.OVERWORLD, OWNER_A, CHEST_POS, otherHalf);
        state.addLock(lock);

        state.removeLock(lock);

        assertTrue(state.getLock(Level.OVERWORLD, CHEST_POS).isEmpty());
        assertTrue(state.getLock(Level.OVERWORLD, otherHalf).isEmpty());
        assertEquals(0, state.countLocksForPlayer(OWNER_A));
    }

    @Test
    void countsAndOwnerLookupsTrackLocksPerPlayer() {
        state.addLock(lockAt(Level.OVERWORLD, OWNER_A, CHEST_POS));
        state.addLock(lockAt(Level.NETHER, OWNER_A, CHEST_POS));
        state.addLock(lockAt(Level.OVERWORLD, OWNER_B, new BlockPos(5, 64, 5)));

        assertEquals(2, state.countLocksForPlayer(OWNER_A));
        assertEquals(1, state.countLocksForPlayer(OWNER_B));
        assertEquals(2, state.getLocksByOwner(OWNER_A).size());
        assertEquals(3, state.getAllLocks().size());
    }

    @Test
    void areaQueryOnlyReturnsLocksInTheQueriedDimension() {
        state.addLock(lockAt(Level.OVERWORLD, OWNER_A, CHEST_POS));
        state.addLock(lockAt(Level.NETHER, OWNER_B, CHEST_POS));

        List<LockRecord> found = state.getLocksInArea(Level.OVERWORLD, CHEST_POS, 1);

        assertEquals(1, found.size());
        assertEquals(OWNER_A, found.get(0).getOwnerUuid());
    }

    @Test
    void areaQueryRespectsChunkRadius() {
        state.addLock(lockAt(Level.OVERWORLD, OWNER_A, CHEST_POS));

        assertEquals(1, state.getLocksInArea(Level.OVERWORLD, CHEST_POS, 0).size());
        BlockPos farAway = CHEST_POS.offset(1000, 0, 0);
        assertTrue(state.getLocksInArea(Level.OVERWORLD, farAway, 1).isEmpty());
    }

    @Test
    void dormantSignsAreTrackedPerDimension() {
        DormantSignRecord record = new DormantSignRecord(Level.OVERWORLD, OWNER_A, "Owner", SIGN_POS);
        state.addDormantSign(record);

        assertTrue(state.getDormantSign(Level.OVERWORLD, SIGN_POS).isPresent());
        assertTrue(state.getDormantSign(Level.NETHER, SIGN_POS).isEmpty());

        state.removeDormantSign(Level.NETHER, SIGN_POS);
        assertTrue(state.getDormantSign(Level.OVERWORLD, SIGN_POS).isPresent());

        state.removeDormantSign(Level.OVERWORLD, SIGN_POS);
        assertTrue(state.getDormantSign(Level.OVERWORLD, SIGN_POS).isEmpty());
    }

    @Test
    void saveAndLoadRoundTripPreservesState() {
        state.addLock(lockAt(Level.OVERWORLD, OWNER_A, CHEST_POS));
        state.addLock(lockAt(Level.NETHER, OWNER_B, CHEST_POS));
        state.addDormantSign(new DormantSignRecord(Level.END, OWNER_A, "Owner", SIGN_POS));

        CompoundTag saved = state.save(new CompoundTag(), null);
        LockState restored = LockState.load(saved, null);

        assertEquals(OWNER_A, restored.getLock(Level.OVERWORLD, CHEST_POS).orElseThrow().getOwnerUuid());
        assertEquals(OWNER_B, restored.getLock(Level.NETHER, CHEST_POS).orElseThrow().getOwnerUuid());
        assertTrue(restored.getDormantSign(Level.END, SIGN_POS).isPresent());
        assertEquals(2, restored.getAllLocks().size());
    }

    @Test
    void cleanupRemovesOnlyInvalidRecords() {
        LockRecord keep = lockAt(Level.OVERWORLD, OWNER_A, CHEST_POS);
        LockRecord drop = lockAt(Level.NETHER, OWNER_B, CHEST_POS);
        state.addLock(keep);
        state.addLock(drop);

        state.cleanupDanglingLocks(record -> record.getDimension().equals(Level.OVERWORLD));

        assertTrue(state.getLock(Level.OVERWORLD, CHEST_POS).isPresent());
        assertTrue(state.getLock(Level.NETHER, CHEST_POS).isEmpty());
    }
}
