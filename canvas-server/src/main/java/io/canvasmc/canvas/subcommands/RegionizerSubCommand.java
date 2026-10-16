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
import io.canvasmc.canvas.regionizer.RegionizerMode;
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
                        builder.suggest("folia");
                        return builder.buildFuture();
                    })
                    .executes(RegionizerSubCommand::mode)))
            .then(literal("aggr")
                .requires(this.check("aggr"))
                .then(argument("percent", IntegerArgumentType.integer(0, 100)).executes(RegionizerSubCommand::aggr)))
            .then(literal("settings")
                .requires(this.check("settings"))
                .executes(RegionizerSubCommand::settings))
            .then(literal("setting")
                .requires(this.check("setting"))
                .then(argument("key", StringArgumentType.word())
                    .suggests(RegionizerSubCommand::suggestRegionizerSettings)
                    .then(argument("value", StringArgumentType.greedyString())
                        .executes(RegionizerSubCommand::setting))))
            .then(literal("reload")
                .requires(this.check("reload"))
                .executes(RegionizerSubCommand::reload))
            .then(literal("regions")
                .requires(this.check("regions"))
                .then(argument("dimension", DimensionArgument.dimension()).executes(RegionizerSubCommand::regions)))
            .then(literal("level")
                .requires(this.check("level"))
                .then(argument("players", IntegerArgumentType.integer(0))
                    .then(literal("view-distance")
                        .then(argument("value", IntegerArgumentType.integer(1, 32))
                            .executes(RegionizerSubCommand::levelViewDistance)))
                    .then(literal("simulation-distance")
                        .then(argument("value", IntegerArgumentType.integer(1, 32))
                            .executes(RegionizerSubCommand::levelSimulationDistance)))
                    .then(literal("regionizer-sections")
                        .then(argument("value", IntegerArgumentType.integer(1, 4096))
                            .executes(RegionizerSubCommand::levelRegionizerSections)))
                    .then(literal("spawn-limit")
                        .then(argument("type", StringArgumentType.word())
                            .suggests(RegionizerSubCommand::suggestMobTypes)
                            .then(argument("value", IntegerArgumentType.integer(0))
                                .executes(RegionizerSubCommand::levelSpawnLimit))))
                    .then(literal("ticks-per-spawn")
                        .then(argument("type", StringArgumentType.word())
                            .suggests(RegionizerSubCommand::suggestMobTypes)
                            .then(argument("value", IntegerArgumentType.integer(1))
                                .executes(RegionizerSubCommand::levelTicksPerSpawn))))
                    .then(literal("remove")
                        .executes(RegionizerSubCommand::levelRemove))))
            .then(literal("world")
                .requires(this.check("world"))
                .then(argument("dimension", DimensionArgument.dimension())
                    .then(literal("view-distance")
                        .then(argument("value", IntegerArgumentType.integer(1, 32))
                            .executes(RegionizerSubCommand::worldViewDistance)))
                    .then(literal("simulation-distance")
                        .then(argument("value", IntegerArgumentType.integer(1, 32))
                            .executes(RegionizerSubCommand::worldSimulationDistance)))
                    .then(literal("regionizer-sections")
                        .then(argument("value", IntegerArgumentType.integer(1, 4096))
                            .executes(RegionizerSubCommand::worldRegionizerSections)))
                    .then(literal("spawn-limit")
                        .then(argument("type", StringArgumentType.word())
                            .suggests(RegionizerSubCommand::suggestMobTypes)
                            .then(argument("value", IntegerArgumentType.integer(0))
                                .executes(RegionizerSubCommand::worldSpawnLimit))))
                    .then(literal("ticks-per-spawn")
                        .then(argument("type", StringArgumentType.word())
                            .suggests(RegionizerSubCommand::suggestMobTypes)
                            .then(argument("value", IntegerArgumentType.integer(1))
                                .executes(RegionizerSubCommand::worldTicksPerSpawn))))
                    .then(literal("remove")
                        .executes(RegionizerSubCommand::worldRemove))));
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestMobTypes(
        final CommandContext<CommandSourceStack> context,
        final com.mojang.brigadier.suggestion.SuggestionsBuilder builder
    ) {
        for (final String type : LoadScaling.SPAWN_LIMIT_KEYS) {
            builder.suggest(type);
        }
        return builder.buildFuture();
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestRegionizerSettings(
        final CommandContext<CommandSourceStack> context,
        final com.mojang.brigadier.suggestion.SuggestionsBuilder builder
    ) {
        for (final String key : LoadScaling.REGIONIZER_SETTING_KEYS) {
            builder.suggest(key);
        }
        return builder.buildFuture();
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
        report.bullet("Regionizer mode", RegionizerMode.get().name());
        report.bullet("Aggression", LoadScaling.getAggression() + "%");
        report.bullet("Config", LoadScaling.hasConfig() ? "enigma-regionizer.yml" : "defaults");

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
        final RegionizerMode.Mode mode = switch (value) {
            case "AUTO" -> RegionizerMode.Mode.AUTO;
            case "FOLIA" -> RegionizerMode.Mode.FOLIA;
            default -> null;
        };
        if (mode == null) {
            Style.fail(source, "Invalid mode, must be \"auto\" or \"folia\"");
            return 0;
        }
        LoadScaling.setRegionizerMode(mode);
        Style.bullets().bullet("Mode", mode.name()).send(source);
        GlobalConfiguration.broadcast("Enigma regionizer mode set to " + mode.name(), GlobalConfiguration.INFO);
        return Command.SINGLE_SUCCESS;
    }

    private static int aggr(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final int percent = IntegerArgumentType.getInteger(context, "percent");
        LoadScaling.setAggression(percent);
        LoadScaling.save();
        Style.bullets().bullet("Aggression", percent + "%").send(source);
        GlobalConfiguration.broadcast("Enigma regionizer aggression set to " + percent + "%", GlobalConfiguration.INFO);
        return Command.SINGLE_SUCCESS;
    }

    private static int settings(final CommandContext<CommandSourceStack> context) {
        final Style.Report report = Style.report("Enigma Regionizer Settings");
        report.bullet("Mode", RegionizerMode.get().name());
        report.bullet("Aggression", LoadScaling.getAggression() + "%");
        for (final java.util.Map.Entry<String, Object> entry : LoadScaling.regionizerSettingsSnapshot().entrySet()) {
            report.bullet(entry.getKey(), String.valueOf(entry.getValue()));
        }
        report.bullet("Edit", "/enigma regionizer setting <key> <value>");
        report.send(context.getSource());
        return Command.SINGLE_SUCCESS;
    }

    private static int setting(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final String key = StringArgumentType.getString(context, "key");
        final String value = StringArgumentType.getString(context, "value");
        try {
            final String saved = LoadScaling.setRegionizerSetting(key, value);
            Style.bullets()
                .bullet("Regionizer setting", key)
                .bullet("Value", saved)
                .bullet("Saved", "config/enigma-regionizer.yml", Style.good())
                .send(source);
            GlobalConfiguration.broadcast("Enigma regionizer setting " + key + " set to " + saved, GlobalConfiguration.INFO);
            return Command.SINGLE_SUCCESS;
        } catch (final IllegalArgumentException thrown) {
            Style.fail(source, thrown.getMessage());
            return 0;
        }
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

    private static LoadScaling.WorldSettings currentLevel(final int players) {
        final LoadScaling.WorldSettings current = LoadScaling.levelSettings(players);
        return current == null ? LoadScaling.emptySettings() : current;
    }

    private static LoadScaling.WorldSettings currentWorld(final String dimension) {
        final LoadScaling.WorldSettings current = LoadScaling.worldSettings(dimension);
        return current == null ? LoadScaling.emptySettings() : current;
    }

    private static boolean validateMobType(final CommandSourceStack source, final String type) {
        for (final String key : LoadScaling.SPAWN_LIMIT_KEYS) {
            if (key.equals(type)) {
                return true;
            }
        }
        Style.fail(source, "Unknown mob type \"" + type + "\", must be one of: monster, animal, ambient, water_animal, water_ambient");
        return false;
    }

    private static int commitLevel(final CommandContext<CommandSourceStack> context, final int players,
                                   final LoadScaling.WorldSettings updated, final String label, final String value) {
        LoadScaling.setLevel(players, updated);
        final CommandSourceStack source = context.getSource();
        Style.bullets()
            .bullet("Level", players + " players")
            .bullet(label, value)
            .bullet("Saved", "config/enigma-regionizer.yml", Style.good())
            .send(source);
        GlobalConfiguration.broadcast("Enigma regionizer level " + players + " " + label + " set to " + value, GlobalConfiguration.INFO);
        return Command.SINGLE_SUCCESS;
    }

    private static int commitWorld(final CommandContext<CommandSourceStack> context, final String dimension,
                                   final LoadScaling.WorldSettings updated, final String label, final String value) {
        LoadScaling.setWorld(dimension, updated);
        final CommandSourceStack source = context.getSource();
        Style.bullets()
            .bullet("World", dimension)
            .bullet(label, value)
            .bullet("Saved", "config/enigma-regionizer.yml", Style.good())
            .send(source);
        GlobalConfiguration.broadcast("Enigma regionizer world " + dimension + " " + label + " set to " + value, GlobalConfiguration.INFO);
        return Command.SINGLE_SUCCESS;
    }

    private static int levelViewDistance(final CommandContext<CommandSourceStack> context) {
        final int players = IntegerArgumentType.getInteger(context, "players");
        final int value = IntegerArgumentType.getInteger(context, "value");
        return commitLevel(context, players, currentLevel(players).withViewDistance(value), "view-distance", String.valueOf(value));
    }

    private static int levelSimulationDistance(final CommandContext<CommandSourceStack> context) {
        final int players = IntegerArgumentType.getInteger(context, "players");
        final int value = IntegerArgumentType.getInteger(context, "value");
        return commitLevel(context, players, currentLevel(players).withSimulationDistance(value), "simulation-distance", String.valueOf(value));
    }

    private static int levelRegionizerSections(final CommandContext<CommandSourceStack> context) {
        final int players = IntegerArgumentType.getInteger(context, "players");
        final int value = IntegerArgumentType.getInteger(context, "value");
        return commitLevel(context, players, currentLevel(players).withRegionizerSections(value), "regionizer-sections", String.valueOf(value));
    }

    private static int levelSpawnLimit(final CommandContext<CommandSourceStack> context) {
        final int players = IntegerArgumentType.getInteger(context, "players");
        final String type = StringArgumentType.getString(context, "type");
        if (!validateMobType(context.getSource(), type)) {
            return 0;
        }
        final int value = IntegerArgumentType.getInteger(context, "value");
        return commitLevel(context, players, currentLevel(players).withSpawnLimit(type, value), "spawn-limit " + type, String.valueOf(value));
    }

    private static int levelTicksPerSpawn(final CommandContext<CommandSourceStack> context) {
        final int players = IntegerArgumentType.getInteger(context, "players");
        final String type = StringArgumentType.getString(context, "type");
        if (!validateMobType(context.getSource(), type)) {
            return 0;
        }
        final int value = IntegerArgumentType.getInteger(context, "value");
        return commitLevel(context, players, currentLevel(players).withTicksPerSpawn(type, value), "ticks-per-spawn " + type, String.valueOf(value));
    }

    private static int levelRemove(final CommandContext<CommandSourceStack> context) {
        final int players = IntegerArgumentType.getInteger(context, "players");
        final CommandSourceStack source = context.getSource();
        if (!LoadScaling.removeLevel(players)) {
            Style.fail(source, "No load scaling level configured for " + players + " players");
            return 0;
        }
        Style.bullets().bullet("Removed level", players + " players", Style.good()).send(source);
        GlobalConfiguration.broadcast("Enigma regionizer removed level " + players + " players", GlobalConfiguration.INFO);
        return Command.SINGLE_SUCCESS;
    }

    private static int worldViewDistance(final CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        final String dimension = DimensionArgument.getDimension(context, "dimension").dimension().identifier().getPath();
        final int value = IntegerArgumentType.getInteger(context, "value");
        return commitWorld(context, dimension, currentWorld(dimension).withViewDistance(value), "view-distance", String.valueOf(value));
    }

    private static int worldSimulationDistance(final CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        final String dimension = DimensionArgument.getDimension(context, "dimension").dimension().identifier().getPath();
        final int value = IntegerArgumentType.getInteger(context, "value");
        return commitWorld(context, dimension, currentWorld(dimension).withSimulationDistance(value), "simulation-distance", String.valueOf(value));
    }

    private static int worldRegionizerSections(final CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        final String dimension = DimensionArgument.getDimension(context, "dimension").dimension().identifier().getPath();
        final int value = IntegerArgumentType.getInteger(context, "value");
        return commitWorld(context, dimension, currentWorld(dimension).withRegionizerSections(value), "regionizer-sections", String.valueOf(value));
    }

    private static int worldSpawnLimit(final CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        final String dimension = DimensionArgument.getDimension(context, "dimension").dimension().identifier().getPath();
        final String type = StringArgumentType.getString(context, "type");
        if (!validateMobType(context.getSource(), type)) {
            return 0;
        }
        final int value = IntegerArgumentType.getInteger(context, "value");
        return commitWorld(context, dimension, currentWorld(dimension).withSpawnLimit(type, value), "spawn-limit " + type, String.valueOf(value));
    }

    private static int worldTicksPerSpawn(final CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        final String dimension = DimensionArgument.getDimension(context, "dimension").dimension().identifier().getPath();
        final String type = StringArgumentType.getString(context, "type");
        if (!validateMobType(context.getSource(), type)) {
            return 0;
        }
        final int value = IntegerArgumentType.getInteger(context, "value");
        return commitWorld(context, dimension, currentWorld(dimension).withTicksPerSpawn(type, value), "ticks-per-spawn " + type, String.valueOf(value));
    }

    private static int worldRemove(final CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        final String dimension = DimensionArgument.getDimension(context, "dimension").dimension().identifier().getPath();
        final CommandSourceStack source = context.getSource();
        if (!LoadScaling.removeWorld(dimension)) {
            Style.fail(source, "No load scaling override configured for dimension " + dimension);
            return 0;
        }
        Style.bullets().bullet("Removed world override", dimension, Style.good()).send(source);
        GlobalConfiguration.broadcast("Enigma regionizer removed world override " + dimension, GlobalConfiguration.INFO);
        return Command.SINGLE_SUCCESS;
    }

    private record RegionRow(long id, boolean ticking, int sections, double mspt) {
    }
}
