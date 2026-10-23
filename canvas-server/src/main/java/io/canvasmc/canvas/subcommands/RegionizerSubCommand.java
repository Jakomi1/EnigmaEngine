package io.canvasmc.canvas.subcommands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.canvasmc.canvas.GlobalConfiguration;
import io.canvasmc.canvas.commands.Style;
import io.canvasmc.canvas.commands.SubCommand;
import io.canvasmc.canvas.regionizer.RegionizerMode;
import io.canvasmc.canvas.regionizer.loadscaling.LoadScaling;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.server.level.ServerLevel;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Configuration of the adaptive regionizer (load scaling, mode, aggression, per-player-count and
 * per-world overrides). Read-only region information lives in {@code /enigma regions} and
 * {@code /enigma info}; this command only changes state.
 *
 * <p>The old {@code info}, {@code settings}, {@code regions} and {@code reload} children are
 * still accepted as aliases, but the canonical commands are {@code /enigma info},
 * {@code /enigma regions ...} and {@code /enigma reload}.</p>
 */
public class RegionizerSubCommand implements SubCommand {

    @Override
    public String getDescription() {
        return "Changes regionizer load scaling, mode, aggression and per-level/per-world overrides";
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> construct(final LiteralArgumentBuilder<CommandSourceStack> base, final CommandBuildContext buildContext) {
        return base
            .then(literal("status")
                .requires(this.check("status"))
                .executes(InfoSubCommand::info))
            .then(literal("info")
                .requires(this.check("info"))
                .executes(InfoSubCommand::info))
            .then(literal("settings")
                .requires(this.check("settings"))
                .executes(InfoSubCommand::info))
            .then(literal("mode")
                .requires(this.check("mode"))
                .then(argument("mode", StringArgumentType.word())
                    .suggests((_, builder) -> {
                        builder.suggest("auto");
                        builder.suggest("folia");
                        return builder.buildFuture();
                    })
                    .executes(RegionizerSubCommand::mode)))
            .then(aggression(literal("aggression").requires(this.check("aggression"))))
            .then(aggression(literal("aggr").requires(this.check("aggr"))))
            .then(setting(literal("set").requires(this.check("set"))))
            .then(setting(literal("setting").requires(this.check("setting"))))
            .then(literal("regions")
                .requires(this.check("regions"))
                .then(argument("dimension", DimensionArgument.dimension())
                    .executes(context -> RegionsSubCommand.listFor(
                        context, DimensionArgument.getDimension(context, "dimension")))))
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

    @Override
    public String getName() {
        return "regionizer";
    }

    private static LiteralArgumentBuilder<CommandSourceStack> aggression(final LiteralArgumentBuilder<CommandSourceStack> node) {
        return node.then(argument("percent", IntegerArgumentType.integer(0, 100)).executes(RegionizerSubCommand::aggression));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> setting(final LiteralArgumentBuilder<CommandSourceStack> node) {
        return node.then(argument("key", StringArgumentType.word())
            .suggests(RegionizerSubCommand::suggestRegionizerSettings)
            .then(argument("value", StringArgumentType.greedyString())
                .executes(RegionizerSubCommand::setting)));
    }

    private static CompletableFuture<Suggestions> suggestMobTypes(
        final CommandContext<CommandSourceStack> context,
        final SuggestionsBuilder builder
    ) {
        for (final String type : LoadScaling.SPAWN_LIMIT_KEYS) {
            builder.suggest(type);
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestRegionizerSettings(
        final CommandContext<CommandSourceStack> context,
        final SuggestionsBuilder builder
    ) {
        for (final String key : LoadScaling.REGIONIZER_SETTING_KEYS) {
            builder.suggest(key);
        }
        return builder.buildFuture();
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

    private static int aggression(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final int percent = IntegerArgumentType.getInteger(context, "percent");
        LoadScaling.setAggression(percent);
        LoadScaling.save();
        Style.bullets().bullet("Aggression", percent + "%").send(source);
        GlobalConfiguration.broadcast("Enigma regionizer aggression set to " + percent + "%", GlobalConfiguration.INFO);
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
                .bullet("Saved", "config/enigma-engine.yml (regionizer)", Style.good())
                .send(source);
            GlobalConfiguration.broadcast("Enigma regionizer setting " + key + " set to " + saved, GlobalConfiguration.INFO);
            return Command.SINGLE_SUCCESS;
        } catch (final IllegalArgumentException thrown) {
            Style.fail(source, thrown.getMessage());
            return 0;
        }
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
            .bullet("Saved", "config/enigma-engine.yml (regionizer)", Style.good())
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
            .bullet("Saved", "config/enigma-engine.yml (regionizer)", Style.good())
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

    private static int worldViewDistance(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        final String dimension = DimensionArgument.getDimension(context, "dimension").dimension().identifier().getPath();
        final int value = IntegerArgumentType.getInteger(context, "value");
        return commitWorld(context, dimension, currentWorld(dimension).withViewDistance(value), "view-distance", String.valueOf(value));
    }

    private static int worldSimulationDistance(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        final String dimension = DimensionArgument.getDimension(context, "dimension").dimension().identifier().getPath();
        final int value = IntegerArgumentType.getInteger(context, "value");
        return commitWorld(context, dimension, currentWorld(dimension).withSimulationDistance(value), "simulation-distance", String.valueOf(value));
    }

    private static int worldRegionizerSections(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        final String dimension = DimensionArgument.getDimension(context, "dimension").dimension().identifier().getPath();
        final int value = IntegerArgumentType.getInteger(context, "value");
        return commitWorld(context, dimension, currentWorld(dimension).withRegionizerSections(value), "regionizer-sections", String.valueOf(value));
    }

    private static int worldSpawnLimit(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        final String dimension = DimensionArgument.getDimension(context, "dimension").dimension().identifier().getPath();
        final String type = StringArgumentType.getString(context, "type");
        if (!validateMobType(context.getSource(), type)) {
            return 0;
        }
        final int value = IntegerArgumentType.getInteger(context, "value");
        return commitWorld(context, dimension, currentWorld(dimension).withSpawnLimit(type, value), "spawn-limit " + type, String.valueOf(value));
    }

    private static int worldTicksPerSpawn(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        final String dimension = DimensionArgument.getDimension(context, "dimension").dimension().identifier().getPath();
        final String type = StringArgumentType.getString(context, "type");
        if (!validateMobType(context.getSource(), type)) {
            return 0;
        }
        final int value = IntegerArgumentType.getInteger(context, "value");
        return commitWorld(context, dimension, currentWorld(dimension).withTicksPerSpawn(type, value), "ticks-per-spawn " + type, String.valueOf(value));
    }

    private static int worldRemove(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
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
}
