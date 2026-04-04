package com.simpleforapanda.privatechests.state;

import com.simpleforapanda.privatechests.PrivateChests;
import com.simpleforapanda.privatechests.model.LockRecord;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages all lock records for the server.
 * Persists lock data across server restarts.
 */
public class LockState extends SavedData {
    private static final String FILE_NAME = "private_chests";

    // Map container positions to their lock records
    private final Map<BlockPos, LockRecord> locksByPosition = new ConcurrentHashMap<>();

    // Map container group IDs to their lock records for efficient lookup
    private final Map<String, LockRecord> locksByGroupId = new ConcurrentHashMap<>();

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
                // Save to NBT using our existing method
                CompoundTag nbt = input.save(new CompoundTag(), null);
                // Return the NBT tag directly (works with NbtOps and JsonOps via conversion)
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

                    // Add to position map for all container positions
                    for (BlockPos pos : record.getContainerPositions()) {
                        state.locksByPosition.put(pos, record);
                    }

                    // Add to group ID map
                    String groupId = state.computeGroupId(record.getContainerPositions());
                    state.locksByGroupId.put(groupId, record);
                });
            }
        });

        return state;
    }

    /**
     * Save lock state to NBT.
     */
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag lockList = new ListTag();

        // Save each unique lock record once (avoid duplicates from position map)
        Set<LockRecord> savedRecords = new HashSet<>();
        for (LockRecord record : locksByPosition.values()) {
            if (savedRecords.add(record)) {
                lockList.add(record.toNbt());
            }
        }

        tag.put("Locks", lockList);
        return tag;
    }

    /**
     * Add a new lock record.
     */
    public void addLock(LockRecord record) {
        // Add to position map for all container positions
        for (BlockPos pos : record.getContainerPositions()) {
            locksByPosition.put(pos, record);
        }

        // Add to group ID map
        String groupId = computeGroupId(record.getContainerPositions());
        locksByGroupId.put(groupId, record);

        setDirty();
    }

    /**
     * Remove a lock record by container position.
     */
    public void removeLock(BlockPos containerPos) {
        LockRecord record = locksByPosition.get(containerPos);
        if (record != null) {
            // Remove from position map for all container positions
            for (BlockPos pos : record.getContainerPositions()) {
                locksByPosition.remove(pos);
            }

            // Remove from group ID map
            String groupId = computeGroupId(record.getContainerPositions());
            locksByGroupId.remove(groupId);

            setDirty();
        }
    }

    /**
     * Get the lock record for a container position.
     */
    public Optional<LockRecord> getLock(BlockPos containerPos) {
        return Optional.ofNullable(locksByPosition.get(containerPos));
    }

    /**
     * Get the lock record for a container group.
     */
    public Optional<LockRecord> getLockByGroupId(String groupId) {
        return Optional.ofNullable(locksByGroupId.get(groupId));
    }

    /**
     * Check if a position has a lock.
     */
    public boolean isLocked(BlockPos containerPos) {
        return locksByPosition.containsKey(containerPos);
    }

    /**
     * Get all lock records.
     */
    public Collection<LockRecord> getAllLocks() {
        // Return unique records only
        return new HashSet<>(locksByPosition.values());
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

        for (LockRecord record : locksByPosition.values()) {
            for (BlockPos pos : record.getContainerPositions()) {
                int chunkX = pos.getX() >> 4;
                int chunkZ = pos.getZ() >> 4;

                if (chunkX >= minX && chunkX <= maxX && chunkZ >= minZ && chunkZ <= maxZ) {
                    uniqueLocks.add(record);
                    break;
                }
            }
        }

        return new ArrayList<>(uniqueLocks);
    }

    /**
     * Compute a unique group ID for a set of container positions.
     * For single containers, this is just the position string.
     * For double chests, this combines both positions in a sorted order.
     */
    public String computeGroupId(Set<BlockPos> positions) {
        if (positions.isEmpty()) {
            return "";
        }

        if (positions.size() == 1) {
            BlockPos pos = positions.iterator().next();
            return pos.getX() + "," + pos.getY() + "," + pos.getZ();
        }

        // Sort positions for consistent group ID
        List<BlockPos> sorted = new ArrayList<>(positions);
        sorted.sort(Comparator.comparingLong(BlockPos::asLong));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) sb.append(";");
            BlockPos pos = sorted.get(i);
            sb.append(pos.getX()).append(",").append(pos.getY()).append(",").append(pos.getZ());
        }

        return sb.toString();
    }

    /**
     * Clean up dangling lock records (where the sign no longer exists).
     * This should be called periodically or during access checks.
     */
    public void cleanupDanglingLocks(java.util.function.Predicate<LockRecord> isValid) {
        List<BlockPos> toRemove = new ArrayList<>();

        for (Map.Entry<BlockPos, LockRecord> entry : locksByPosition.entrySet()) {
            if (!isValid.test(entry.getValue())) {
                toRemove.add(entry.getKey());
            }
        }

        for (BlockPos pos : toRemove) {
            removeLock(pos);
        }
    }
}
