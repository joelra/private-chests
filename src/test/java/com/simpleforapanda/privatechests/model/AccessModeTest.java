package com.simpleforapanda.privatechests.model;

import net.minecraft.server.network.FilteredText;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessModeTest {

    @Test
    void parsesPrivateMarker() {
        assertEquals(Optional.of(AccessMode.PRIVATE), AccessMode.fromLine("[private]"));
    }

    @Test
    void parsesPublicMarker() {
        assertEquals(Optional.of(AccessMode.PUBLIC), AccessMode.fromLine("[public]"));
    }

    @Test
    void parsingIsCaseInsensitive() {
        assertEquals(Optional.of(AccessMode.PRIVATE), AccessMode.fromLine("[Private]"));
        assertEquals(Optional.of(AccessMode.PUBLIC), AccessMode.fromLine("[PUBLIC]"));
    }

    @Test
    void parsingTrimsWhitespace() {
        assertEquals(Optional.of(AccessMode.PRIVATE), AccessMode.fromLine("  [private]  "));
    }

    @Test
    void rejectsNonMarkerLines() {
        assertTrue(AccessMode.fromLine("private").isEmpty());
        assertTrue(AccessMode.fromLine("[private] extra").isEmpty());
        assertTrue(AccessMode.fromLine("").isEmpty());
        assertTrue(AccessMode.fromLine(null).isEmpty());
    }

    @Test
    void fromLinesUsesOnlyFirstLine() {
        List<FilteredText> lines = List.of(
            FilteredText.passThrough("SomePlayer"),
            FilteredText.passThrough("[private]")
        );
        assertTrue(AccessMode.fromLines(lines).isEmpty());

        List<FilteredText> markerFirst = List.of(FilteredText.passThrough("[public]"));
        assertEquals(Optional.of(AccessMode.PUBLIC), AccessMode.fromLines(markerFirst));
    }

    @Test
    void fromLinesHandlesEmptyList() {
        assertTrue(AccessMode.fromLines(List.of()).isEmpty());
    }
}
