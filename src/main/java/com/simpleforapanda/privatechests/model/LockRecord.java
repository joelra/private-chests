package com.simpleforapanda.privatechests.model;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Represents a lock on a container group (chest, double chest, or barrel).
 * Stores ownership, access mode, allowed users, and positions of the container and sign.
 */
public class LockRecord {
    private final ResourceKey<Level> dimension;
    private final UUID ownerUuid;
    private final String ownerName;
    private final BlockPos signPos;
    private final Set<BlockPos> containerPositions;
    private final AccessMode accessMode;
    private final Set<String> allowedUsers;
    private final long createdAt;
    private final long lastUpdatedAt;

    public LockRecord(
        ResourceKey<Level> dimension,
        UUID ownerUuid,
        String ownerName,
        BlockPos signPos,
        Set<BlockPos> containerPositions,
        AccessMode accessMode,
        Set<String> allowedUsers
    ) {
        this(dimension, ownerUuid, ownerName, signPos, containerPositions, accessMode, allowedUsers, System.currentTimeMillis(), System.currentTimeMillis());
    }

    public LockRecord(
        ResourceKey<Level> dimension,
        UUID ownerUuid,
        String ownerName,
        BlockPos signPos,
        Set<BlockPos> containerPositions,
        AccessMode accessMode,
        Set<String> allowedUsers,
        long createdAt,
        long lastUpdatedAt
    ) {
        this.dimension = dimension;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.signPos = signPos;
        this.containerPositions = new HashSet<>(containerPositions);
        this.accessMode = accessMode;
        this.allowedUsers = new HashSet<>(allowedUsers);
        this.createdAt = createdAt;
        this.lastUpdatedAt = lastUpdatedAt;
    }

    public ResourceKey<Level> getDimension() {
        return dimension;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public BlockPos getSignPos() {
        return signPos;
    }

    public Set<BlockPos> getContainerPositions() {
        return Collections.unmodifiableSet(containerPositions);
    }

    public AccessMode getAccessMode() {
        return accessMode;
    }

    public Set<String> getAllowedUsers() {
        return Collections.unmodifiableSet(allowedUsers);
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    /**
     * Check if a username is in the allowed list (case-insensitive).
     * Also handles Floodgate prefix stripping and whitespace normalization.
     */
    public boolean isUserAllowed(String username, String floodgatePrefix) {
        if (username == null) {
            return false;
        }

        String normalized = normalizeUsername(username, floodgatePrefix);

        for (String allowed : allowedUsers) {
            if (normalizeUsername(allowed, floodgatePrefix).equals(normalized)) {
                return true;
            }
        }

        return false;
    }

    private String normalizeUsername(String username, String floodgatePrefix) {
        String normalized = username.trim().toLowerCase();

        if (floodgatePrefix != null && !floodgatePrefix.isEmpty() && normalized.startsWith(floodgatePrefix.toLowerCase())) {
            normalized = normalized.substring(floodgatePrefix.length());
        }

        return normalized.replace('_', ' ');
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();

        tag.putString("Dimension", dimension.identifier().toString());
        tag.putLong("OwnerMost", ownerUuid.getMostSignificantBits());
        tag.putLong("OwnerLeast", ownerUuid.getLeastSignificantBits());
        tag.putString("OwnerName", ownerName);
        tag.putInt("SignPosX", signPos.getX());
        tag.putInt("SignPosY", signPos.getY());
        tag.putInt("SignPosZ", signPos.getZ());
        tag.putString("AccessMode", accessMode.name());

        ListTag containerList = new ListTag();
        for (BlockPos pos : containerPositions) {
            CompoundTag posTag = new CompoundTag();
            posTag.putInt("X", pos.getX());
            posTag.putInt("Y", pos.getY());
            posTag.putInt("Z", pos.getZ());
            containerList.add(posTag);
        }
        tag.put("Containers", containerList);

        ListTag userList = new ListTag();
        for (String user : allowedUsers) {
            CompoundTag userTag = new CompoundTag();
            userTag.putString("Name", user);
            userList.add(userTag);
        }
        tag.put("AllowedUsers", userList);

        tag.putLong("CreatedAt", createdAt);
        tag.putLong("LastUpdatedAt", lastUpdatedAt);

        return tag;
    }

    public static LockRecord fromNbt(CompoundTag tag) {
        // Records saved before dimension support were only reachable in the overworld
        ResourceKey<Level> dimension = tag.getString("Dimension")
            .map(Identifier::tryParse)
            .map(id -> ResourceKey.create(Registries.DIMENSION, id))
            .orElse(Level.OVERWORLD);
        UUID ownerUuid = new UUID(
            tag.getLong("OwnerMost").orElse(0L),
            tag.getLong("OwnerLeast").orElse(0L)
        );
        String ownerName = tag.getString("OwnerName").orElse("Unknown");
        BlockPos signPos = new BlockPos(
            tag.getInt("SignPosX").orElse(0),
            tag.getInt("SignPosY").orElse(0),
            tag.getInt("SignPosZ").orElse(0)
        );

        AccessMode accessMode = tag.getString("AccessMode")
            .map(value -> {
                try {
                    return AccessMode.valueOf(value);
                } catch (IllegalArgumentException ignored) {
                    return AccessMode.PRIVATE;
                }
            })
            .orElse(AccessMode.PRIVATE);

        Set<BlockPos> containerPositions = new HashSet<>();
        tag.getList("Containers").ifPresent(containerList -> {
            for (int i = 0; i < containerList.size(); i++) {
                containerList.getCompound(i).ifPresent(posTag -> {
                    containerPositions.add(new BlockPos(
                        posTag.getInt("X").orElse(0),
                        posTag.getInt("Y").orElse(0),
                        posTag.getInt("Z").orElse(0)
                    ));
                });
            }
        });

        Set<String> allowedUsers = new HashSet<>();
        tag.getList("AllowedUsers").ifPresent(userList -> {
            for (int i = 0; i < userList.size(); i++) {
                userList.getCompound(i).ifPresent(userTag -> userTag.getString("Name").ifPresent(allowedUsers::add));
            }
        });

        long createdAt = tag.getLong("CreatedAt").orElse(0L);
        long lastUpdatedAt = tag.getLong("LastUpdatedAt").orElse(0L);

        return new LockRecord(dimension, ownerUuid, ownerName, signPos, containerPositions, accessMode, allowedUsers, createdAt, lastUpdatedAt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LockRecord that)) return false;
        return Objects.equals(dimension, that.dimension)
            && Objects.equals(ownerUuid, that.ownerUuid)
            && Objects.equals(signPos, that.signPos)
            && Objects.equals(containerPositions, that.containerPositions)
            && accessMode == that.accessMode
            && Objects.equals(allowedUsers, that.allowedUsers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dimension, ownerUuid, signPos, containerPositions, accessMode, allowedUsers);
    }

    @Override
    public String toString() {
        return "LockRecord{"
            + "dimension=" + dimension.identifier()
            + ", ownerUuid=" + ownerUuid
            + ", signPos=" + signPos
            + ", containerPositions=" + containerPositions
            + ", accessMode=" + accessMode
            + ", allowedUsers=" + allowedUsers
            + '}';
    }
}
