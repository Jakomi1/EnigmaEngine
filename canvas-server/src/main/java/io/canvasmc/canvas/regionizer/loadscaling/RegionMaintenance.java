package io.canvasmc.canvas.regionizer.loadscaling;

import io.canvasmc.canvas.regionizer.RegionCoupling;
import io.canvasmc.canvas.regionizer.RegionizerSettings;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public final class RegionMaintenance {

    private static long tickCounter;

    private RegionMaintenance() {
    }

    public static void tick() {
        final RegionizerSettings settings = RegionizerSettings.get();
        if (!settings.enabled()) {
            return;
        }
        final int interval = settings.splitPassIntervalTicks();
        if (interval <= 0) {
            return;
        }
        if (++tickCounter % interval != 0) {
            return;
        }
        runPass();
    }

    public static void runPass() {
        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }
        for (final ServerLevel level : server.getAllLevels()) {
            level.regioniser.enigma$runRegionMaintenance();
            level.regioniser.enigma$garbageCollectRegions();
        }
        RegionCoupling.decay();
    }
}