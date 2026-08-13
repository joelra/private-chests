package com.simpleforapanda.privatechests;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * Boots the vanilla registries once so tests can use Minecraft classes.
 */
public final class TestBootstrap {
    private static boolean initialized;

    private TestBootstrap() {
    }

    public static synchronized void init() {
        if (!initialized) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            initialized = true;
        }
    }
}
