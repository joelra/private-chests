package com.simpleforapanda.privatechests.model;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.minecraft.server.network.FilteredText;
import net.minecraft.world.level.block.entity.SignBlockEntity;

public enum AccessMode {
    PRIVATE("[private]"),
    PUBLIC("[public]");

    private final String marker;

    AccessMode(String marker) {
        this.marker = marker;
    }

    public String getMarker() {
        return marker;
    }

    public boolean isPublic() {
        return this == PUBLIC;
    }

    public boolean isPrivate() {
        return this == PRIVATE;
    }

    public static Optional<AccessMode> fromLine(String line) {
        String normalized = line == null ? "" : line.trim().toLowerCase(Locale.ROOT);
        for (AccessMode mode : values()) {
            if (mode.marker.equals(normalized)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }

    public static Optional<AccessMode> fromLines(List<FilteredText> lines) {
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        return fromLine(lines.get(0).raw());
    }

    public static Optional<AccessMode> fromSign(SignBlockEntity signEntity, boolean isFront) {
        return fromLine(signEntity.getText(isFront).getMessage(0, false).getString());
    }
}
