package io.canvasmc.canvas.subcommands;

import com.mojang.brigadier.Command;


import com.mojang.brigadier.context.CommandContext;
import io.canvasmc.canvas.commands.Style;

import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegions;
import net.kyori.adventure.text.Component;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;


/**
 * Emergency command: force-unloads a region by disconnecting every player in it and
 * killing the region state, even if the region is mid-tick and the server is at
 * a fraction of a TPS. Intended as a last resort before a full server restart.
 *
 * <p>This command deliberately avoids the region's tick thread. It scans the global
 * player list and checks each player's current region via {@code getRegionAtUnsynchronised},
 * so a lagged region thread cannot block the unload.</p>
 */
final class ForceUnloadSubCommand {

    private ForceUnloadSubCommand() {
    }

    static int execute(final CommandContext<CommandSourceStack> context, final long regionId) {
        final CommandSourceStack source = context.getSource();
        final MinecraftServer server = source.getServer();

        final AtomicReference<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>> target =
            new AtomicReference<>(null);
        final AtomicReference<ServerLevel> targetLevel = new AtomicReference<>(null);

        // Scan every level for the target region. This does not touch the region's
        // tick thread, so a severely lagged region cannot block this command.
        for (final ServerLevel level : server.getAllLevels()) {
            level.regioniser.computeForAllRegions(region -> {
                if (region.id == regionId) {
                    target.set(region);
                    targetLevel.set(level);
                }
            });
        }

        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region = target.get();
        final ServerLevel level = targetLevel.get();

        if (region == null || level == null) {
            Style.fail(source, "Region id=" + regionId + " not found");
            return 0;
        }

        // ------------------------------------------------------------------
        // 1. Disconnect every player inside the target region
        // ------------------------------------------------------------------
        int disconnected = 0;
        for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
            final ServerLevel playerLevel = (ServerLevel) player.level();
            final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> playerRegion =
                playerLevel.regioniser.getRegionAtUnsynchronised(player.getBlockX() >> 4, player.getBlockZ() >> 4);
            if (playerRegion != null && playerRegion.id == regionId) {
                final org.bukkit.entity.Player bukkitPlayer = Bukkit.getPlayer(player.getUUID());
                if (bukkitPlayer != null) {
                    bukkitPlayer.kick(Component.text("Region force-unloaded by operator"));
                    disconnected++;
                }
            }
        }

        // ------------------------------------------------------------------
        // 2. Force-kill the region state (bypasses the STATE_TICKING guard)
        // ------------------------------------------------------------------
        final boolean killed = forceKill(region.regioniser, region);

        Style.report("Force-unload region id=" + regionId)
            .bullet("World", level.dimension().identifier().getPath())
            .bullet("Players disconnected", disconnected)
            .bullet("Region killed", Boolean.toString(killed))
            .send(source);

        return Command.SINGLE_SUCCESS;
    }

    private static boolean forceKill(
            final ThreadedRegionizer<?, ?> regionizer,
            final ThreadedRegionizer.ThreadedRegion<?, ?> region
    ) {
        for (final Method method : regionizer.getClass().getMethods()) {
            if (!"enigma$forceKill".equals(method.getName()) || method.getParameterCount() != 1
                    || !method.getParameterTypes()[0].isInstance(region)) {
                continue;
            }
            try {
                return (boolean) method.invoke(regionizer, region);
            } catch (final IllegalAccessException | InvocationTargetException exception) {
                throw new IllegalStateException("Failed to force-kill region " + region.id, exception);
            }
        }
        throw new IllegalStateException("Force-kill support is not present in this ThreadedRegionizer build");
    }
}