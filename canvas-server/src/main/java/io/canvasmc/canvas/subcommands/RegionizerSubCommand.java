package io.canvasmc.canvas.subcommands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.canvasmc.canvas.GlobalConfiguration;
import io.canvasmc.canvas.commands.SubCommand;
import io.canvasmc.canvas.region.RegionTickData;
import io.canvasmc.canvas.regionizer.loadscaling.LoadScaling;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.network.chat.Component;
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

        source.sendSuccess(() -> Component.literal(""), false);
        source.sendSuccess(() -> Component.literal("=== Enigma Engine Info ==="), false);
        source.sendSuccess(() -> Component.literal(""), false);

        // TPS / MSPT
        final double[] tps = server.getTPS();
        final String tps1 = String.format(java.util.Locale.ROOT, "%.2f", Math.min(tps[0], 20.0));
        final String tps5 = String.format(java.util.Locale.ROOT, "%.2f", Math.min(tps[1], 20.0));
        final String tps15 = String.format(java.util.Locale.ROOT, "%.2f", Math.min(tps[2], 20.0));
        source.sendSuccess(() -> Component.literal("TPS: 1m=" + tps1 + "  5m=" + tps5 + "  15m=" + tps15), false);

        final long[] mspt = server.getTickTimesNanos();
        final double msptMs = mspt.length > 0 ? mspt[0] / 1_000_000.0 : 0.0;
        source.sendSuccess(() -> Component.literal("MSPT: " + String.format(java.util.Locale.ROOT, "%.2f", msptMs) + " ms"), false);
        source.sendSuccess(() -> Component.literal(""), false);

        // Load Scaling
        source.sendSuccess(() -> Component.literal("LoadScaling: mode=" + LoadScaling.getMode().name()
            + "  aggression=" + LoadScaling.getAggression() + "%"
            + "  config=" + (LoadScaling.hasConfig() ? "enigma-load.yml" : "defaults")), false);
        source.sendSuccess(() -> Component.literal(""), false);

        // Region stats per world
        final Runtime runtime = Runtime.getRuntime();
        final long usedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        final long maxMB = runtime.maxMemory() / (1024 * 1024);

        source.sendSuccess(() -> Component.literal("Memory: " + usedMB + "MB / " + maxMB + "MB"), false);
        source.sendSuccess(() -> Component.literal(""), false);

        source.sendSuccess(() -> Component.literal("--- Region Stats ---"), false);

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
            source.sendSuccess(() -> Component.literal("  [" + dimName + "] total=" + worldTotal
                + "  ticking=" + stateCounts[0]
                + "  idle=" + stateCounts[1]
                + "  coupling=" + coupling[0]), false);
        }

        final long fTotalRegions = totalRegions;
        final long fTotalTicking = totalTicking;
        final long fTotalIdle = totalIdle;
        final long fTotalCoupling = totalCoupling;

        source.sendSuccess(() -> Component.literal(""), false);
        source.sendSuccess(() -> Component.literal("Total: " + fTotalRegions + " regions"
            + "  (ticking=" + fTotalTicking
            + ", idle=" + fTotalIdle
            + ", saved=" + fTotalIdle
            + ", coupling=" + fTotalCoupling + ")"), false);

        if (source.getEntity() instanceof final ServerPlayer player) {
            final String desc = LoadScaling.describeCurrent((ServerLevel) player.level());
            if (desc != null) {
                source.sendSuccess(() -> Component.literal(""), false);
                source.sendSuccess(() -> Component.literal("Your level: " + desc), false);
            }
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int mode(final CommandContext<CommandSourceStack> context) {
        final String value = StringArgumentType.getString(context, "mode").toUpperCase(Locale.ROOT);
        final LoadScaling.Mode mode = switch (value) {
            case "AUTO" -> LoadScaling.Mode.AUTO;
            case "MANUAL" -> LoadScaling.Mode.MANUAL;
            default -> null;
        };
        if (mode == null) {
            context.getSource().sendFailure(Component.literal("Invalid mode, must be \"auto\" or \"manual\""));
            return 0;
        }
        LoadScaling.setMode(mode);
        GlobalConfiguration.broadcast("Enigma regionizer mode set to " + mode.name(), GlobalConfiguration.INFO);
        return Command.SINGLE_SUCCESS;
    }

    private static int aggr(final CommandContext<CommandSourceStack> context) {
        final int percent = IntegerArgumentType.getInteger(context, "percent");
        LoadScaling.setAggression(percent);
        GlobalConfiguration.broadcast("Enigma regionizer aggression set to " + percent + "%", GlobalConfiguration.INFO);
        return Command.SINGLE_SUCCESS;
    }

    private static int reload(final CommandContext<CommandSourceStack> context) {
        LoadScaling.reload();
        GlobalConfiguration.broadcast("Reloaded Enigma load scaling configuration", GlobalConfiguration.INFO);
        return Command.SINGLE_SUCCESS;
    }

    private static int regions(final CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        final ServerLevel level = DimensionArgument.getDimension(context, "dimension");
        final CommandSourceStack source = context.getSource();

        final int[] stateCounts = new int[2];
        final long[] sizes = new long[2];
        final List<String> largest = new ArrayList<>();
        final double[] loads = new double[2];
        final long[] coupling = new long[1];

        level.regioniser.computeForAllChunkRegions((region) -> {
            final int sections = region.getOwnedPackedChunkPositions().length;
            if (region.getState() == io.canvasmc.canvas.region.WorldRegionizer.ChunkRegion.State.TICKING) {
                stateCounts[0]++;
                sizes[0] += sections;
                loads[0] += Math.max(0.0D, region.getTickData().getMSPT(RegionTickData.Frame._5_SECONDS));
            } else {
                stateCounts[1]++;
                sizes[1] += sections;
            }
            largest.add("id=" + region.getId() + " state=" + region.getState() + " sections=" + sections
                + " mspt=" + String.format(java.util.Locale.ROOT, "%.2f", Math.max(0.0D, region.getTickData().getMSPT(RegionTickData.Frame._5_SECONDS))));
        });
        coupling[0] = io.canvasmc.canvas.regionizer.RegionCoupling.size();

        final long total = stateCounts[0] + stateCounts[1];
        largest.sort((a, b) -> Integer.compare(extractSections(b), extractSections(a)));

        source.sendSuccess(() -> Component.literal("Regions in " + level.dimension().identifier() + ": " + total
            + " (ticking=" + stateCounts[0] + ", idle=" + stateCounts[1]
            + ", coupling=" + coupling[0] + ")"), false);
        source.sendSuccess(() -> Component.literal("Avg sections ticking="
            + (stateCounts[0] == 0 ? "-" : (sizes[0] / stateCounts[0]))
            + ", avg mspt=" + (stateCounts[0] == 0 ? "-" : String.format(java.util.Locale.ROOT, "%.2f", loads[0] / stateCounts[0]))
            + ", avg sections idle=" + (stateCounts[1] == 0 ? "-" : (sizes[1] / stateCounts[1]))), false);

        for (int i = 0, len = Math.min(10, largest.size()); i < len; i++) {
            final String line = largest.get(i);
            source.sendSystemMessage(Component.literal("  " + line));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int extractSections(final String line) {
        final int idx = line.indexOf("sections=");
        if (idx < 0) {
            return 0;
        }
        int end = line.indexOf(' ', idx);
        if (end < 0) {
            end = line.length();
        }
        try {
            return Integer.parseInt(line.substring(idx + 9, end));
        } catch (final NumberFormatException ignored) {
            return 0;
        }
    }
}