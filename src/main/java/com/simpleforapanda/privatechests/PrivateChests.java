package com.simpleforapanda.privatechests;

import com.simpleforapanda.privatechests.command.PlayerLockCommand;
import com.simpleforapanda.privatechests.command.PrivateChestsCommand;
import com.simpleforapanda.privatechests.config.ModConfig;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PrivateChests implements ModInitializer {
	public static final String MOD_ID = "private-chests";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static ModConfig config;

	@Override
	public void onInitialize() {
		// Get mod version
		String version = FabricLoader.getInstance()
			.getModContainer(MOD_ID)
			.map(container -> container.getMetadata().getVersion().getFriendlyString())
			.orElse("unknown");

		LOGGER.info("Private Chests v{} initializing...", version);

		// Load configuration
		config = ModConfig.load(FabricLoader.getInstance().getConfigDir());

		// Register event handlers
		com.simpleforapanda.privatechests.event.ContainerEventHandler.register();
		com.simpleforapanda.privatechests.event.BlockBreakHandler.register();

		// Register commands
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			PrivateChestsCommand.register(dispatcher);
			PlayerLockCommand.register(dispatcher);
		});

		LOGGER.info("Private Chests v{} initialized successfully!", version);
	}

	public static ModConfig getConfig() {
		return config;
	}
}