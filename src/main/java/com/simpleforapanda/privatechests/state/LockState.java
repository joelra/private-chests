package com.simpleforapanda.privatechests.state;

import com.mojang.serialization.Codec;
import com.simpleforapanda.privatechests.PrivateChests;
import com.simpleforapanda.privatechests.model.DormantSignRecord;
import com.simpleforapanda.privatechests.model.LockRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Manages all lock records for the server.
 * Persists lock data across server restarts.
 */
public class LockState extends SavedData {
    private static final String FILE_NAME = "private_chests";

    private final Map<BlockPos, LockRecord> locksByPosition = new HashMap<>();
    private final Map<UUID, Set<LockRecord>> locksByOwner = new HashMap<>();
    private final Map<BlockPos, DormantSignRecord> dormantSignsByPosition = new HashMap<>();

    public LockState() {
        super();
    }

    private static final Codec<LockState> CODEC = new Codec<>() {
        @Override
        public <T> com.mojang.serialization.DataResult<com.mojang.datafixers.util.Pair<LockState, T>> decode(
            com.mojang.serialization.DynamicOps<T> ops,
            T input
        ) {
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
        public <T> com.mojang.serialization.DataResult<T> encode(
            LockState input,
            com.mojang.serialization.DynamicOps<T> ops,
            T prefix
        ) {
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
        null
    );

    public static LockState get(MinecraftServer server) {
        SavedDataStorage storage = server.overworld().getDataStorage();
        return storage.computeIfAbsent(TYPE);
    }

    public static LockState load(CompoundTag tag, HolderLookup.Provider provider) {
        LockState state = new LockState();

        tag.getList("Locks").ifPresent(lockList -> {
            for (int i = 0; i < lockList.size(); i++) {
                lockList.getCompound(i).ifPresent(lockTag -> state.indexRecord(LockRecord.fromNbt(lockTag)));
            }
        });

        tag.getList("DormantSigns").ifPresent(signList -> {
            for (int i = 0; i < signList.size(); i++) {
                signList.getCompound(i).ifPresent(signTag -> {
                    DormantSignRecord record = DormantSignRecord.fromNbt(signTag);
                    state.dormantSignsByPosition.put(record.getSignPos(), record);
                });
            }
        });

        return state;
    }

    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag lockList = new ListTag();
        for (Set<LockRecord> ownerLocks : locksByOwner.values()) {
            for (LockRecord record : ownerLocks) {
                lockList.add(record.toNbt());
            }
        }

        ListTag dormantSignList = new ListTag();
        for (DormantSignRecord record : dormantSignsByPosition.values()) {
            dormantSignList.add(record.toNbt());
        }

        tag.put("Locks", lockList);
        tag.put("DormantSigns", dormantSignList);
        return tag;
    }

    public void addLock(LockRecord record) {
        indexRecord(record);
        setDirty();
    }

    public void removeLock(BlockPos containerPos) {
        LockRecord record = locksByPosition.get(containerPos);
        if (record != null) {
            unindexRecord(record);
            setDirty();
        }
    }

    public void addDormantSign(DormantSignRecord record) {
        dormantSignsByPosition.put(record.getSignPos(), record);
        setDirty();
    }

    public void removeDormantSign(BlockPos signPos) {
        if (dormantSignsByPosition.remove(signPos) != null) {
            setDirty();
        }
    }

    public Optional<LockRecord> getLock(BlockPos containerPos) {
        return Optional.ofNullable(locksByPosition.get(containerPos));
    }

    public Optional<LockRecord> getLock(Set<BlockPos> containerGroup) {
        for (BlockPos containerPos : containerGroup) {
            Optional<LockRecord> lock = getLock(containerPos);
            if (lock.isPresent()) {
                return lock;
            }
        }
        return Optional.empty();
    }

    public boolean isLocked(BlockPos containerPos) {
        return locksByPosition.containsKey(containerPos);
    }

    public Optional<DormantSignRecord> getDormantSign(BlockPos signPos) {
        return Optional.ofNullable(dormantSignsByPosition.get(signPos));
    }

    public Collection<LockRecord> getAllLocks() {
        List<LockRecord> all = new ArrayList<>();
        for (Set<LockRecord> ownerLocks : locksByOwner.values()) {
            all.addAll(ownerLocks);
        }
        return all;
    }

    public Set<LockRecord> getLocksByOwner(UUID playerUuid) {
        Set<LockRecord> ownerLocks = locksByOwner.get(playerUuid);
        return ownerLocks != null ? Collections.unmodifiableSet(ownerLocks) : Collections.emptySet();
    }

    public int countLocksForPlayer(UUID playerUuid) {
        Set<LockRecord> ownerLocks = locksByOwner.get(playerUuid);
        return ownerLocks != null ? ownerLocks.size() : 0;
    }

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

    public void cleanupDanglingLocks(java.util.function.Predicate<LockRecord> isValid) {
        List<LockRecord> toRemove = new ArrayList<>();
        for (Set<LockRecord> ownerLocks : locksByOwner.values()) {
            for (LockRecord record : ownerLocks) {
                if (!isValid.test(record)) {
                    toRemove.add(record);
                }
            }
        }

        for (LockRecord record : toRemove) {
            removeLock(record.getContainerPositions().iterator().next());
        }
    }

    public void cleanupDanglingDormantSigns(java.util.function.Predicate<DormantSignRecord> isValid) {
        List<BlockPos> toRemove = new ArrayList<>();
        for (DormantSignRecord record : dormantSignsByPosition.values()) {
            if (!isValid.test(record)) {
                toRemove.add(record.getSignPos());
            }
        }

        for (BlockPos signPos : toRemove) {
            removeDormantSign(signPos);
        }
    }

    private void indexRecord(LockRecord record) {
        for (BlockPos pos : record.getContainerPositions()) {
            locksByPosition.put(pos, record);
        }
        locksByOwner.computeIfAbsent(record.getOwnerUuid(), ignored -> new HashSet<>()).add(record);
    }

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
