package com.simpleforapanda.privatechests.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModConfigTest {

    @TempDir
    Path configDir;

    private Path configFile() {
        return configDir.resolve("private-chests.json");
    }

    private void writeConfig(String json) throws IOException {
        Files.writeString(configFile(), json);
    }

    @Test
    void missingFileCreatesDefaults() {
        ModConfig config = ModConfig.load(configDir);

        assertEquals(3, config.getAdminPermissionLevel());
        assertEquals(0, config.getMaxLocksPerPlayer());
        assertTrue(Files.exists(configFile()));
    }

    @Test
    void validValuesAreLoaded() throws IOException {
        writeConfig("""
            {
              "floodgatePrefix": "*",
              "adminPermissionLevel": 2,
              "listMaxEntries": 10,
              "listPreviewEntries": 5,
              "disableProtectionIfOwnerBanned": false,
              "maxLocksPerPlayer": 7
            }
            """);

        ModConfig config = ModConfig.load(configDir);

        assertEquals("*", config.getFloodgatePrefix());
        assertEquals(2, config.getAdminPermissionLevel());
        assertEquals(10, config.getListMaxEntries());
        assertEquals(5, config.getListPreviewEntries());
        assertEquals(false, config.isDisableProtectionIfOwnerBanned());
        assertEquals(7, config.getMaxLocksPerPlayer());
    }

    @Test
    void malformedJsonFallsBackToDefaults() throws IOException {
        writeConfig("{ this is not json");

        ModConfig config = ModConfig.load(configDir);

        assertEquals(3, config.getAdminPermissionLevel());
    }

    @Test
    void emptyFileFallsBackToDefaults() throws IOException {
        writeConfig("");

        ModConfig config = ModConfig.load(configDir);

        assertEquals(3, config.getAdminPermissionLevel());
    }

    @Test
    void adminPermissionLevelZeroIsRejected() throws IOException {
        // Level 0 would have made every player an admin; it must clamp to the default.
        writeConfig("{ \"adminPermissionLevel\": 0 }");

        ModConfig config = ModConfig.load(configDir);

        assertEquals(3, config.getAdminPermissionLevel());
    }

    @Test
    void outOfRangeValuesAreCorrected() throws IOException {
        writeConfig("""
            {
              "adminPermissionLevel": 9,
              "listMaxEntries": -1,
              "listPreviewEntries": 0,
              "maxLocksPerPlayer": -5
            }
            """);

        ModConfig config = ModConfig.load(configDir);

        assertEquals(3, config.getAdminPermissionLevel());
        assertEquals(50, config.getListMaxEntries());
        assertEquals(20, config.getListPreviewEntries());
        assertEquals(0, config.getMaxLocksPerPlayer());
    }

    @Test
    void previewEntriesAreClampedToMaxEntries() throws IOException {
        writeConfig("{ \"listMaxEntries\": 10, \"listPreviewEntries\": 30 }");

        ModConfig config = ModConfig.load(configDir);

        assertEquals(10, config.getListMaxEntries());
        assertEquals(10, config.getListPreviewEntries());
    }

    @Test
    void correctedConfigIsSavedBackToTheSameDirectory() throws IOException {
        writeConfig("{ \"adminPermissionLevel\": 0 }");

        ModConfig.load(configDir);

        String saved = Files.readString(configFile());
        assertTrue(saved.contains("\"adminPermissionLevel\": 3"));
    }
}
