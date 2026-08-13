package com.simpleforapanda.privatechests.model;

import com.simpleforapanda.privatechests.TestBootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockRecordTest {

    private static final UUID OWNER_UUID = UUID.fromString("d1b57f4a-9799-4a6a-8dc0-59f481bfff40");

    @BeforeAll
    static void bootstrap() {
        TestBootstrap.init();
    }

    private static LockRecord sampleRecord() {
        return new LockRecord(
            Level.NETHER,
            OWNER_UUID,
            "Owner",
            new BlockPos(10, 64, -20),
            Set.of(new BlockPos(10, 64, -21), new BlockPos(11, 64, -21)),
            AccessMode.PUBLIC,
            Set.of("Alice", "Bob"),
            1000L,
            2000L
        );
    }

    @Test
    void nbtRoundTripPreservesAllFields() {
        LockRecord original = sampleRecord();
        LockRecord restored = LockRecord.fromNbt(original.toNbt());

        assertEquals(original, restored);
        assertEquals(original.getDimension(), restored.getDimension());
        assertEquals(original.getOwnerName(), restored.getOwnerName());
        assertEquals(original.getCreatedAt(), restored.getCreatedAt());
        assertEquals(original.getLastUpdatedAt(), restored.getLastUpdatedAt());
    }

    @Test
    void recordsWithoutDimensionTagMigrateToOverworld() {
        CompoundTag tag = sampleRecord().toNbt();
        tag.remove("Dimension");

        LockRecord restored = LockRecord.fromNbt(tag);

        assertEquals(Level.OVERWORLD, restored.getDimension());
    }

    @Test
    void isUserAllowedMatchesCaseInsensitively() {
        LockRecord record = recordWithAllowedUsers("Steve_Jobs");

        assertTrue(record.isUserAllowed("STEVE_JOBS", "."));
        assertTrue(record.isUserAllowed("steve_jobs", "."));
    }

    @Test
    void isUserAllowedTreatsUnderscoresAsSpaces() {
        LockRecord record = recordWithAllowedUsers("Steve_Jobs");

        assertTrue(record.isUserAllowed("Steve Jobs", "."));
    }

    @Test
    void isUserAllowedStripsFloodgatePrefix() {
        LockRecord record = recordWithAllowedUsers("BedrockPlayer");

        assertTrue(record.isUserAllowed(".BedrockPlayer", "."));
        assertFalse(record.isUserAllowed(".BedrockPlayer", ""));
    }

    @Test
    void isUserAllowedRejectsUnlistedAndNullNames() {
        LockRecord record = recordWithAllowedUsers("Alice");

        assertFalse(record.isUserAllowed("Mallory", "."));
        assertFalse(record.isUserAllowed(null, "."));
    }

    @Test
    void isUserAllowedHandlesNullPrefix() {
        LockRecord record = recordWithAllowedUsers("Alice");

        assertTrue(record.isUserAllowed("alice", null));
    }

    private static LockRecord recordWithAllowedUsers(String... users) {
        return new LockRecord(
            Level.OVERWORLD,
            OWNER_UUID,
            "Owner",
            BlockPos.ZERO,
            Set.of(new BlockPos(0, 64, 0)),
            AccessMode.PRIVATE,
            Set.of(users)
        );
    }
}
