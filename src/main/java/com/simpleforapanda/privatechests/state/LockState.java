package com.simpleforapanda.privatechests.state;

import com.simpleforapanda.privatechests.PrivateChests;
import com.simpleforapanda.privatechests.model.LockRecord;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;

import java.util.*;

/**
 * Manages all lock records for the server.
 * Persists lock data across server restarts.
 *
 * <p>Two indices are maintained:</p>
 * <ul>
 *   <li>{@code locksByPosition} – O(1) lookup of a lock by any container block position.</li>
 *   <li>{@code locksByOwner} – O(1) lookup / count of all locks owned by a player UUID.
 *       Also used as the canonical deduplicated set for serialisation.</li>
 * </ul>
 */
public class LockState extends SavedData {
    private static final String FILE_NAME = "private_chests";

    // Map container positions to their lock records (multiple positions per double-chest)
    private final Map<BlockPos, LockRecord> locksByPosition = new HashMap<>();

    // Map owner UUID to the set of locks they own (one record per unique lock)
    private final Map<UUID, Set<LockRecord>> locksByOwner = new HashMap<>();

    public LockState() {
        super();
    }

    /**
     * Codec for serializing/deserializing LockState using NBT.
     * Delegates to our existing save/load methods.
     */
    private static final Codec<LockState> CODEC = new Codec<LockState>() {
        @Override
        public <T> com.mojang.serialization.DataResult<com.mojang.datafixers.util.Pair<LockState, T>> decode(
                com.mojang.serialization.DynamicOps<T> ops, T input) {
            if (input instanceof CompoundTag nbt) {
                try {
                    LockState state = load(nbt, null);
                    return com.mojang.serialization.DataResult.success(
                        com.mojang.datafixers.util.Pair.of(state, ops.empty())
                    );
                } catch (Exception e) {
                    return com.mojang.serialization.DataResult.error(() -> "Failed to load LockState: " + e.getMessage());
                }
            }
            return com.mojang.serialization.DataResult.error(() -> "Expected CompoundTag for LockState");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> com.mojang.serialization.DataResult<T> encode(LockState input,
                com.mojang.serialization.DynamicOps<T> ops, T prefix) {
            try {
                CompoundTag nbt = input.save(new CompoundTag(), null);
                return com.mojang.serialization.DataResult.success((T) nbt);
            } catch (Exception e) {
                return com.mojang.serialization.DataResult.error(() -> "Failed to save LockState: " + e.getMessage());
            }
        }
    };

    private static final SavedDataType<LockState> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath(PrivateChests.MOD_ID, FILE_NAME),
        LockState::new,
        CODEC,
        null   // DataFixTypes
    );

    /**
     * Get the LockState instance for the server.
     */
    public static LockState get(MinecraftServer server) {
        SavedDataStorage storage = server.overworld().getDataStorage();
        return storage.computeIfAbsent(TYPE);
    }

    /**
     * Load lock state from NBT.
     */
    public static LockState load(CompoundTag tag, HolderLookup.Provider provider) {
        LockState state = new LockState();

        tag.getList("Locks").ifPresent(lockList -> {
            for (int i = 0; i < lockList.size(); i++) {
                lockList.getCompound(i).ifPresent(lockTag -> {
                    LockRecord record = LockRecord.fromNbt(lockTag);
                    state.indexRecord(record);
                });
            }
        });

        return state;
    }

    /**
     * Save lock state to NBT.
     * Uses the owner index as the canonical deduplicated record set – no temporary
     * collection needed.
     */
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag lockList = new ListTag();

        for (Set<LockRecord> ownerLocks : locksByOwner.values()) {
            for (LockRecord record : ownerLocks) {
                lockList.add(record.toNbt());
            }
        }

        tag.put("Locks", lockList);
        return tag;
    }

    // -------------------------------------------------------------------------
    // Mutation
    // -------------------------------------------------------------------------

    /**
     * Add a new lock record.
     */
    public void addLock(LockRecord record) {
        indexRecord(record);
        setDirty();
    }

    /**
     * Remove the lock record associated with the given container position.
     */
    public void removeLock(BlockPos containerPos) {
        LockRecord record = locksByPosition.get(containerPos);
        if (record != null) {
            unindexRecord(record);
            setDirty();
        }
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    /**
     * Get the lock record for a container position.
     */
    public Optional<LockRecord> getLock(BlockPos containerPos) {
        return Optional.ofNullable(locksByPosition.get(containerPos));
    }

    /**
     * Check if a position has a lock.
     */
    public boolean isLocked(BlockPos containerPos) {
        return locksByPosition.containsKey(containerPos);
    }

    /**
     * Get all unique lock records across all owners.
     */
    public Collection<LockRecord> getAllLocks() {
        List<LockRecord> all = new ArrayList<>();
        for (Set<LockRecord> ownerLocks : locksByOwner.values()) {
            all.addAll(ownerLocks);
        }
        return all;
    }

    /**
     * Get all locks owned by a specific player.
     */
    public Set<LockRecord> getLocksByOwner(UUID playerUuid) {
        Set<LockRecord> ownerLocks = locksByOwner.get(playerUuid);
        return ownerLocks != null ? Collections.unmodifiableSet(ownerLocks) : Collections.emptySet();
    }

    /**
     * Return the number of unique containers locked by the given player.
     */
    public int countLocksForPlayer(UUID playerUuid) {
        Set<LockRecord> ownerLocks = locksByOwner.get(playerUuid);
        return ownerLocks != null ? ownerLocks.size() : 0;
    }

    /**
     * Get locks in a specific area (for list_in_area command).
     */
    public List<LockRecord> getLocksInArea(BlockPos center, int chunkRadius) {
        int minX = (center.getX() >> 4) - chunkRadius;
        int maxX = (center.getX() >> 4) + chunkRadius;
        int minZ = (center.getZ() >> 4) - chunkRadius;
        int maxZ = (center.getZ() >> 4) + chunkRadius;

        Set<LockRecord> uniqueLocks = new HashSet<>();

        for (Set<LockRecord> ownerLocks : locksByOwner.values()) {
            for (LockRecord record : ownerLocks) {
                for (BlockPos pos : record.getContainerPositions()) {
                    int chunkX = pos.getX() >> 4;
                    int chunkZ = pos.getZ() >> 4;

                    if (chunkX >= minX && chunkX <= maxX && chunkZ >= minZ && chunkZ <= maxZ) {
                        uniqueLocks.add(record);
                        break;
                    }
                }
            }
        }

        return new ArrayList<>(uniqueLocks);
    }

    /**
     * Clean up dangling lock records (where the sign no longer exists).
     * The supplied predicate returns {@code true} if the record is still valid.
     */
    public void cleanupDanglingLocks(java.util.function.Predicate<LockRecord> isValid) {
        // Collect unique invalid records via the owner index to avoid duplicates
        List<LockRecord> toRemove = new ArrayList<>();

        for (Set<LockRecord> ownerLocks : locksByOwner.values()) {
            for (LockRecord record : ownerLocks) {
                if (!isValid.test(record)) {
                    toRemove.add(record);
                }
            }
        }

        for (LockRecord record : toRemove) {
            // removeLock accepts any position in the container group
            removeLock(record.getContainerPositions().iterator().next());
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Add {@code record} to both internal indices. */
    private void indexRecord(LockRecord record) {
        for (BlockPos pos : record.getContainerPositions()) {
            locksByPosition.put(pos, record);
        }
        locksByOwner.computeIfAbsent(record.getOwnerUuid(), k -> new HashSet<>()).add(record);
    }

    /** Remove {@code record} from both internal indices. */
    private void unindexRecord(LockRecord record) {
        for (BlockPos pos : record.getContainerPositions()) {
            locksByPosition.remove(pos);
        }
        Set<LockRecord> ownerLocks = locksByOwner.get(record.getOwnerUuid());
        if (ownerLocks != null) {
            ownerLocks.remove(record);
            if (ownerLocks.isEmpty()) {
                locksByOwner.remove(record.getOwnerUuid());
            }
        }
    }
}
