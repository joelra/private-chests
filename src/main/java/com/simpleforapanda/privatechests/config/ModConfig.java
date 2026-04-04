package com.simpleforapanda.privatechests.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.simpleforapanda.privatechests.PrivateChests;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuration for the Private Chests mod.
 */
public class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ModConfig instance;

    // Floodgate prefix for Bedrock players (commonly ".")
    public String floodgatePrefix = ".";

    // Admin permission level (2 = operator can break blocks, 3 = operator can use commands)
    public int adminPermissionLevel = 3;

    // Maximum number of entries to show in /private_chests list before abbreviating
    public int listMaxEntries = 50;

    // Number of entries to show in preview when list is abbreviated
    public int listPreviewEntries = 20;

    // Disable protection if the owner is banned
    public boolean disableProtectionIfOwnerBanned = true;

    // Maximum number of containers a single player may lock (0 = unlimited)
    public int maxLocksPerPlayer = 0;

    /**
     * Load the configuration from file, or create default if it doesn't exist.
     */
    public static ModConfig load(Path configPath) {
        Path configFile = configPath.resolve("private-chests.json");

        if (Files.exists(configFile)) {
            try {
                String json = Files.readString(configFile);
                instance = GSON.fromJson(json, ModConfig.class);
                PrivateChests.LOGGER.info("Loaded configuration from {}", configFile);
            } catch (IOException e) {
                PrivateChests.LOGGER.error("Failed to load configuration, using defaults", e);
                instance = new ModConfig();
            }
        } else {
            PrivateChests.LOGGER.info("Configuration file not found, creating default at {}", configFile);
            instance = new ModConfig();
            instance.save(configPath);
        }

        // Validate and fix invalid values
        instance.validate();

        return instance;
    }

    /**
     * Validate configuration values and fix any invalid settings.
     */
    private void validate() {
        boolean needsSave = false;

        if (adminPermissionLevel < 0 || adminPermissionLevel > 4) {
            PrivateChests.LOGGER.warn("Invalid adminPermissionLevel ({}), must be 0-4. Using default: 3", adminPermissionLevel);
            adminPermissionLevel = 3;
            needsSave = true;
        }

        if (listMaxEntries < 1) {
            PrivateChests.LOGGER.warn("Invalid listMaxEntries ({}), must be >= 1. Using default: 50", listMaxEntries);
            listMaxEntries = 50;
            needsSave = true;
        }

        if (listPreviewEntries < 1) {
            PrivateChests.LOGGER.warn("Invalid listPreviewEntries ({}), must be >= 1. Using default: 20", listPreviewEntries);
            listPreviewEntries = 20;
            needsSave = true;
        }

        if (listPreviewEntries > listMaxEntries) {
            PrivateChests.LOGGER.warn("listPreviewEntries ({}) cannot exceed listMaxEntries ({}). Adjusting to match.",
                listPreviewEntries, listMaxEntries);
            listPreviewEntries = listMaxEntries;
            needsSave = true;
        }

        if (maxLocksPerPlayer < 0) {
            PrivateChests.LOGGER.warn("Invalid maxLocksPerPlayer ({}), must be >= 0. Using default: 0 (unlimited)", maxLocksPerPlayer);
            maxLocksPerPlayer = 0;
            needsSave = true;
        }

        if (needsSave) {
            PrivateChests.LOGGER.info("Configuration had invalid values, saving corrected version");
            save(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir());
        }
    }

    /**
     * Save the configuration to file.
     */
    public void save(Path configPath) {
        Path configFile = configPath.resolve("private-chests.json");

        try {
            String json = GSON.toJson(this);
            Files.createDirectories(configPath);
            Files.writeString(configFile, json);
            PrivateChests.LOGGER.info("Saved configuration to {}", configFile);
        } catch (IOException e) {
            PrivateChests.LOGGER.error("Failed to save configuration", e);
        }
    }

    /**
     * Get the current configuration instance.
     */
    public static ModConfig getInstance() {
        if (instance == null) {
            throw new IllegalStateException("Configuration not loaded yet!");
        }
        return instance;
    }

    public String getFloodgatePrefix() {
        return floodgatePrefix;
    }

    public int getAdminPermissionLevel() {
        return adminPermissionLevel;
    }

    public int getListMaxEntries() {
        return listMaxEntries;
    }

    public int getListPreviewEntries() {
        return listPreviewEntries;
    }

    public boolean isDisableProtectionIfOwnerBanned() {
        return disableProtectionIfOwnerBanned;
    }

    public int getMaxLocksPerPlayer() {
        return maxLocksPerPlayer;
    }
}
