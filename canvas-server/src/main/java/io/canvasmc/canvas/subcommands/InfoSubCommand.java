package io.canvasmc.canvas.subcommands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.canvasmc.canvas.chunkmap.EnigmaChunkMapService;
import io.canvasmc.canvas.commands.Style;
import io.canvasmc.canvas.commands.SubCommand;
import io.canvasmc.canvas.region.WorldRegionizer;
import io.canvasmc.canvas.regionizer.RegionizerMode;
import io.canvasmc.canvas.regionizer.loadscaling.LoadScaling;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server overview with runtime metrics, region counts and chunk-map state.
 */
public class InfoSubCommand implements SubCommand {

    @Override
    public String getDescription() {
        return "Shows a server overview: TPS, MSPT, memory, load scaling, regions and chunk map";
    }

    @Override
    public String getName() {
        return "info";
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> construct(final LiteralArgumentBuilder<CommandSourceStack> base, final CommandBuildContext buildContext) {
        return base
            .requires(this.check("info"))
            .executes(InfoSubCommand::info);
    }

    public static int info(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final MinecraftServer server = source.getServer();

        final Style.Report report = Style.report("Enigma Engine Info");

        final double[] tps = server.getTPS();
        report.bullet("TPS 1m, 5m, 15m", Component.text()
            .append(Style.tps(Math.min(tps[0], 20.0)))
            .append(Component.text(", ", Style.LIST))
            .append(Style.tps(Math.min(tps[1], 20.0)))
            .append(Component.text(", ", Style.LIST))
            .append(Style.tps(Math.min(tps[2], 20.0)))
            .build());

        final double msptMs = server.getAverageTickTimeNanos() / 1_000_000.0;
        report.bullet("MSPT", Style.mspt(msptMs));

        final Runtime runtime = Runtime.getRuntime();
        final long usedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        final long maxMB = runtime.maxMemory() / (1024 * 1024);
        report.bullet("Memory", Component.text()
            .append(Style.value(usedMB))
            .append(Component.text(" MB / ", Style.SECONDARY))
            .append(Style.value(maxMB))
            .append(Component.text(" MB", Style.SECONDARY))
            .build());

        report.gap();
        report.subHeader("Regionizer");
        report.bullet("Mode", RegionizerMode.get().name());
        report.bullet("Aggression", LoadScaling.getAggression() + "%");
        report.bullet("Config", LoadScaling.hasConfig() ? "config/enigma-engine.yml (regionizer)" : "defaults");
        for (final Map.Entry<String, Object> entry : LoadScaling.regionizerSettingsSnapshot().entrySet()) {
            report.bullet(entry.getKey(), String.valueOf(entry.getValue()));
        }

        report.gap();
        report.subHeader("Region Stats");

        long totalRegions = 0;
        long totalSections = 0;
        long totalTicking = 0;
        long totalIdle = 0;

        for (final ServerLevel level : server.getAllLevels()) {
            final int[] stateCounts = new int[2];
            final long[] sections = {0};

            level.regioniser.computeForAllRegions(region -> {
                sections[0] += region.getOwnedSections().size();
                if (region.getState() == WorldRegionizer.ChunkRegion.State.TICKING) {
                    stateCounts[0]++;
                } else {
                    stateCounts[1]++;
                }
            });

            final long worldTotal = stateCounts[0] + stateCounts[1];
            totalRegions += worldTotal;
            totalSections += sections[0];
            totalTicking += stateCounts[0];
            totalIdle += stateCounts[1];

            final String dimName = level.dimension().identifier().getPath();
            report.bullet(dimName, Component.text()
                .append(Style.value(worldTotal))
                .append(Component.text(" regions, ", Style.SECONDARY))
                .append(Style.value(sections[0]))
                .append(Component.text(" sections, ticking ", Style.SECONDARY))
                .append(Style.value(stateCounts[0]))
                .append(Component.text(", idle ", Style.SECONDARY))
                .append(Style.value(stateCounts[1]))
                .build());
        }

        report.bullet("Total", Component.text()
            .append(Style.value(totalRegions))
            .append(Component.text(" regions (", Style.SECONDARY))
            .append(Style.value(totalSections))
            .append(Component.text(" sections, ticking=", Style.SECONDARY))
            .append(Style.value(totalTicking))
            .append(Component.text(", idle=", Style.SECONDARY))
            .append(Style.value(totalIdle))
            .append(Component.text(")", Style.SECONDARY))
            .build());

        final EnigmaChunkMapService chunkMap = EnigmaChunkMapService.getInstance();
        report.gap();
        report.subHeader("Chunk Map");
        report.bullet("Enabled", Style.onOff(chunkMap.enabled()));
        report.bullet("Subscribed clients", chunkMap.subscriberCount());
        report.bullet("Protocol", "v" + EnigmaChunkMapService.PROTOCOL_VERSION);

        if (source.getEntity() instanceof final ServerPlayer player) {
            final String desc = LoadScaling.describeCurrent((ServerLevel) player.level());
            if (desc != null) {
                report.bullet("Your level", desc);
            }
        }

        report.line(Style.value("Details: /enigma regions list, /enigma regions show <#N|id|here>"));
        report.send(source);
        return Command.SINGLE_SUCCESS;
    }
}
