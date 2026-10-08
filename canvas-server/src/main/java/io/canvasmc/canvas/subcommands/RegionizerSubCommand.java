package io.canvasmc.canvas.subcommands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.canvasmc.canvas.GlobalConfiguration;
import io.canvasmc.canvas.commands.Style;
import io.canvasmc.canvas.commands.SubCommand;
import io.canvasmc.canvas.region.RegionTickData;
import io.canvasmc.canvas.regionizer.loadscaling.LoadScaling;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class RegionizerSubCommand implements SubCommand {

    @Override
    public String getDescription() {
        return "Controls the Enigma adaptive regionizer load scaling system";
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> construct(final LiteralArgumentBuilder<CommandSourceStack> base, final CommandBuildContext buildContext) {
        return base
            .then(literal("info")
                .requires(this.check("info"))
                .executes(RegionizerSubCommand::info))
            .then(literal("mode")
                .requires(this.check("mode"))
                .then(argument("mode", StringArgumentType.word())
                    .suggests((_, builder) -> {
                        builder.suggest("auto");
                        builder.suggest("manual");
                        return builder.buildFuture();
                    })
                    .executes(RegionizerSubCommand::mode)))
            .then(literal("aggr")
                .requires(this.check("aggr"))
                .then(argument("percent", IntegerArgumentType.integer(0, 100)).executes(RegionizerSubCommand::aggr)))
            .then(literal("reload")
                .requires(this.check("reload"))
                .executes(RegionizerSubCommand::reload))
            .then(literal("regions")
                .requires(this.check("regions"))
                .then(argument("dimension", DimensionArgument.dimension()).executes(RegionizerSubCommand::regions)));
    }

    @Override
    public String getName() {
        return "regionizer";
    }

    public static int info(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final net.minecraft.server.MinecraftServer server = source.getServer();

        final Style.Report report = Style.report("Enigma Engine Info");

        // TPS 1m/5m/15m, each number coloured like /tps colours TPS.
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

        // Load scaling.
        report.bullet("Mode", LoadScaling.getMode().name());
        report.bullet("Aggression", LoadScaling.getAggression() + "%");
        report.bullet("Config", LoadScaling.hasConfig() ? "enigma-load.yml" : "defaults");

        // Memory.
        final Runtime runtime = Runtime.getRuntime();
        final long usedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        final long maxMB = runtime.maxMemory() / (1024 * 1024);
        report.bullet("Memory", Component.text()
            .append(Style.value(usedMB))
            .append(Component.text(" MB / ", Style.SECONDARY))
            .append(Style.value(maxMB))
            .append(Component.text(" MB", Style.SECONDARY))
            .build());

        // Region stats per world.
        report.subHeader("Region Stats");

        long totalRegions = 0;
        long totalTicking = 0;
        long totalIdle = 0;
        long totalCoupling = 0;

        for (final ServerLevel level : server.getAllLevels()) {
            final int[] stateCounts = new int[2];
            final long[] sizes = new long[2];
            final long[] coupling = {0};

            level.regioniser.computeForAllChunkRegions((region) -> {
                final int sections = region.getOwnedPackedChunkPositions().length;
                if (region.getState() == io.canvasmc.canvas.region.WorldRegionizer.ChunkRegion.State.TICKING) {
                    stateCounts[0]++;
                    sizes[0] += sections;
                } else {
                    stateCounts[1]++;
                    sizes[1] += sections;
                }
            });
            coupling[0] = io.canvasmc.canvas.regionizer.RegionCoupling.size();

            final long worldTotal = stateCounts[0] + stateCounts[1];
            totalRegions += worldTotal;
            totalTicking += stateCounts[0];
            totalIdle += stateCounts[1];
            totalCoupling += coupling[0];

            final String dimName = level.dimension().identifier().getPath();
            report.bullet(dimName, Component.text()
                .append(Style.value(worldTotal))
                .append(Component.text(" regions, ", Style.SECONDARY))
                .append(Component.text("ticking ", Style.SECONDARY))
                .append(Style.value(stateCounts[0]))
                .append(Component.text(", idle ", Style.SECONDARY))
                .append(Style.value(stateCounts[1]))
                .append(Component.text(", coupling ", Style.SECONDARY))
                .append(Style.value(coupling[0]))
                .build());
        }

        report.bullet("Total", Component.text()
            .append(Style.value(totalRegions))
            .append(Component.text(" regions (ticking=", Style.SECONDARY))
            .append(Style.value(totalTicking))
            .append(Component.text(", idle=", Style.SECONDARY))
            .append(Style.value(totalIdle))
            .append(Component.text(", coupling=", Style.SECONDARY))
            .append(Style.value(totalCoupling))
            .append(Component.text(")", Style.SECONDARY))
            .build());

        if (source.getEntity() instanceof final ServerPlayer player) {
            final String desc = LoadScaling.describeCurrent((ServerLevel) player.level());
            if (desc != null) {
                report.bullet("Your level", desc);
            }
        }

        report.send(source);
        return Command.SINGLE_SUCCESS;
    }

    private static int mode(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final String value = StringArgumentType.getString(context, "mode").toUpperCase(Locale.ROOT);
        final LoadScaling.Mode mode = switch (value) {
            case "AUTO" -> LoadScaling.Mode.AUTO;
            case "MANUAL" -> LoadScaling.Mode.MANUAL;
            default -> null;
        };
        if (mode == null) {
            Style.fail(source, "Invalid mode, must be \"auto\" or \"manual\"");
            return 0;
        }
        LoadScaling.setMode(mode);
        Style.bullets().bullet("Mode", mode.name()).send(source);
        GlobalConfiguration.broadcast("Enigma regionizer mode set to " + mode.name(), GlobalConfiguration.INFO);
        return Command.SINGLE_SUCCESS;
    }

    private static int aggr(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final int percent = IntegerArgumentType.getInteger(context, "percent");
        LoadScaling.setAggression(percent);
        Style.bullets().bullet("Aggression", percent + "%").send(source);
        GlobalConfiguration.broadcast("Enigma regionizer aggression set to " + percent + "%", GlobalConfiguration.INFO);
        return Command.SINGLE_SUCCESS;
    }

    private static int reload(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        LoadScaling.reload();
        Style.bullets().bullet("Load scaling config", "reloaded", Style.good()).send(source);
        GlobalConfiguration.broadcast("Reloaded Enigma load scaling configuration", GlobalConfiguration.INFO);
        return Command.SINGLE_SUCCESS;
    }

    private static int regions(final CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        final ServerLevel level = DimensionArgument.getDimension(context, "dimension");
        final CommandSourceStack source = context.getSource();

        final int[] stateCounts = new int[2];
        final long[] sizes = new long[2];
        final List<RegionRow> rows = new ArrayList<>();
        final double[] loads = new double[2];
        final long[] coupling = new long[1];

        level.regioniser.computeForAllChunkRegions((region) -> {
            final int sections = region.getOwnedPackedChunkPositions().length;
            final double mspt = Math.max(0.0D, region.getTickData().getMSPT(RegionTickData.Frame._5_SECONDS));
            if (region.getState() == io.canvasmc.canvas.region.WorldRegionizer.ChunkRegion.State.TICKING) {
                stateCounts[0]++;
                sizes[0] += sections;
                loads[0] += mspt;
                rows.add(new RegionRow(region.getId(), true, sections, mspt));
            } else {
                stateCounts[1]++;
                sizes[1] += sections;
                rows.add(new RegionRow(region.getId(), false, sections, mspt));
            }
        });
        coupling[0] = io.canvasmc.canvas.regionizer.RegionCoupling.size();

        final long total = stateCounts[0] + stateCounts[1];
        rows.sort((a, b) -> Integer.compare(b.sections(), a.sections()));

        final Style.Report report = Style.report("Regions in " + level.dimension().identifier().getPath());
        report.bullet("Total", Component.text()
            .append(Style.value(total))
            .append(Component.text(" regions (ticking=", Style.SECONDARY))
            .append(Style.value(stateCounts[0]))
            .append(Component.text(", idle=", Style.SECONDARY))
            .append(Style.value(stateCounts[1]))
            .append(Component.text(", coupling=", Style.SECONDARY))
            .append(Style.value(coupling[0]))
            .append(Component.text(")", Style.SECONDARY))
            .build());
        report.bullet("Average sections", Component.text()
            .append(stateCounts[0] == 0 ? Component.text("-", Style.SECONDARY) : Style.value(sizes[0] / stateCounts[0]))
            .append(Component.text(" ticking, ", Style.SECONDARY))
            .append(stateCounts[1] == 0 ? Component.text("-", Style.SECONDARY) : Style.value(sizes[1] / stateCounts[1]))
            .append(Component.text(" idle", Style.SECONDARY))
            .build());
        report.bullet("Average MSPT (ticking)", stateCounts[0] == 0
            ? Component.text("-", Style.SECONDARY)
            : Style.mspt(loads[0] / stateCounts[0]));

        final int shown = Math.min(10, rows.size());
        if (shown > 0) {
            report.gap();
            report.subHeader("Top " + shown + " regions by sections");
            for (int i = 0; i < shown; i++) {
                final RegionRow row = rows.get(i);
                report.line(Component.text()
                    .append(Component.text(" - ", Style.LIST, TextDecoration.BOLD))
                    .append(Component.text("Region ", Style.PRIMARY))
                    .append(Style.value(row.id()))
                    .append(Component.text(", ", Style.SECONDARY))
                    .append(Component.text(row.ticking() ? "ticking" : "idle",
                        row.ticking() ? Style.good() : Style.SECONDARY))
                    .append(Component.text(", ", Style.SECONDARY))
                    .append(Style.value(row.sections()))
                    .append(Component.text(" sections, ", Style.SECONDARY))
                    .append(Style.mspt(row.mspt()))
                    .append(Component.text(" MSPT", Style.SECONDARY))
                    .build());
            }
        }

        report.send(source);
        return Command.SINGLE_SUCCESS;
    }

    private record RegionRow(long id, boolean ticking, int sections, double mspt) {
    }
}
