package com.simpleforapanda.privatechests.model;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public class DormantSignRecord {
    private final ResourceKey<Level> dimension;
    private final UUID ownerUuid;
    private final String ownerName;
    private final BlockPos signPos;

    public DormantSignRecord(ResourceKey<Level> dimension, UUID ownerUuid, String ownerName, BlockPos signPos) {
        this.dimension = dimension;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.signPos = signPos;
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

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Dimension", dimension.identifier().toString());
        tag.putLong("OwnerMost", ownerUuid.getMostSignificantBits());
        tag.putLong("OwnerLeast", ownerUuid.getLeastSignificantBits());
        tag.putString("OwnerName", ownerName);
        tag.putInt("SignPosX", signPos.getX());
        tag.putInt("SignPosY", signPos.getY());
        tag.putInt("SignPosZ", signPos.getZ());
        return tag;
    }

    public static DormantSignRecord fromNbt(CompoundTag tag) {
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
        return new DormantSignRecord(dimension, ownerUuid, ownerName, signPos);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DormantSignRecord that)) return false;
        return Objects.equals(dimension, that.dimension)
            && Objects.equals(ownerUuid, that.ownerUuid)
            && Objects.equals(signPos, that.signPos);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dimension, ownerUuid, signPos);
    }
}
