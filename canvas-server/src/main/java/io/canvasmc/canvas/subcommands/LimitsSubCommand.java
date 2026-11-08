package io.canvasmc.canvas.subcommands;

import com.google.common.collect.ImmutableMap;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.canvasmc.canvas.GlobalConfiguration;
import io.canvasmc.canvas.commands.Style;
import io.canvasmc.canvas.commands.SubCommand;
import io.canvasmc.canvas.threadedregions.commands.AbstractCommandExecution;
import io.canvasmc.canvas.util.StringSuggestionProvider;
import io.papermc.paper.threadedregions.RegionizedServer;
import java.util.Arrays;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.util.CraftSpawnCategory;
import org.bukkit.entity.SpawnCategory;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Server-wide limits: the transient mob caps and the maximum player count. Both used to be
 * separate top-level commands ({@code /enigma mobcaps} and {@code /enigma setmaxplayers});
 * grouping them under {@code limits} keeps the root command tree about one topic per node.
 *
 * <pre>
 * /enigma limits players [set &lt;count&gt; [&lt;persist&gt;]]
 * /enigma limits mobcaps list
 * /enigma limits mobcaps get &lt;category&gt;
 * /enigma limits mobcaps set &lt;category&gt; &lt;value&gt;
 * /enigma limits mobcaps reset &lt;category&gt;
 * </pre>
 */
public class LimitsSubCommand implements SubCommand {
    private static final DynamicCommandExceptionType INVALID_CATEGORY = new DynamicCommandExceptionType(
        (obj) -> net.minecraft.network.chat.Component.literal("Unknown category by name of \"" + obj + "\"")
    );
    private static final SimpleCommandExceptionType INVALID_FOR_LIMITS = new SimpleCommandExceptionType(
        net.minecraft.network.chat.Component.literal("This category is invalid for limits")
    );

    private static final String[] ARGS = Arrays.stream(SpawnCategory.values())
        .filter((sc) -> !sc.equals(SpawnCategory.MISC))
        .map(Enum::name)
        .map(String::toLowerCase)
        .collect(Collectors.toSet())
        .toArray(new String[0]);
    private static final ImmutableMap<String, SpawnCategory> NAME2CATEGORY;

    static {
        final ImmutableMap.Builder<String, SpawnCategory> builder = new ImmutableMap.Builder<>();

        for (final String arg : ARGS) {
            final String upper = arg.toUpperCase();

            builder.put(upper, SpawnCategory.valueOf(upper));
        }

        NAME2CATEGORY = builder.build();
    }

    @Override
    public String getDescription() {
        return "Reads or changes server-wide limits: maximum players and transient mob caps";
    }

    @Override
    public String getName() {
        return "limits";
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> construct(
        final LiteralArgumentBuilder<CommandSourceStack> base,
        final CommandBuildContext buildContext
    ) {
        return base
            .then(literal("players")
                .requires(this.check("players"))
                .executes(LimitsSubCommand::getMaxPlayers)
                .then(literal("set")
                    .then(argument("count", IntegerArgumentType.integer(0))
                        .executes(context -> setMaxPlayers(
                            IntegerArgumentType.getInteger(context, "count"), false))
                        .then(argument("persist", BoolArgumentType.bool())
                            .executes(context -> setMaxPlayers(
                                IntegerArgumentType.getInteger(context, "count"),
                                BoolArgumentType.getBool(context, "persist")))))))
            .then(literal("mobcaps")
                .requires(this.check("mobcaps"))
                .then(literal("list").executes(LimitsSubCommand::listCaps))
                .then(literal("get")
                    .then(argument("category", StringArgumentType.word())
                        .suggests(new StringSuggestionProvider(ARGS))
                        .executes(LimitsSubCommand::getOperation)))
                .then(literal("set")
                    .then(argument("category", StringArgumentType.word())
                        .suggests(new StringSuggestionProvider(ARGS))
                        .then(argument("value", IntegerArgumentType.integer(0))
                            .executes(LimitsSubCommand::setOperation))))
                .then(literal("reset")
                    .then(argument("category", StringArgumentType.word())
                        .suggests(new StringSuggestionProvider(ARGS))
                        .executes(LimitsSubCommand::resetOperation))));
    }

    private static int getMaxPlayers(final CommandContext<CommandSourceStack> context) {
        final int maxPlayers = MinecraftServer.getServer().server.getMaxPlayers();
        Style.bullets().bullet("Maximum players", maxPlayers).send(context.getSource());
        return Command.SINGLE_SUCCESS;
    }

    private static int setMaxPlayers(final int newSize, final boolean persist) {
        RegionizedServer.getInstance().scheduleToOrExecute(() -> {
            final MinecraftServer server = MinecraftServer.getServer();
            server.server.setMaxPlayers(newSize);

            if (persist && server instanceof DedicatedServer dedicatedServer) {
                final DedicatedServerProperties properties = dedicatedServer.getProperties();
                properties.properties.setProperty("max-players", String.valueOf(newSize));
                dedicatedServer.settings.forceSave();
            }

            GlobalConfiguration.broadcast("Set max player count to " + newSize, GlobalConfiguration.INFO);
        });
        return Command.SINGLE_SUCCESS;
    }

    private static int listCaps(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        return AbstractCommandExecution.executeOnGlobal(
            () -> {
                final CraftServer server = MinecraftServer.getServer().server;
                final Style.Report report = Style.report("Mob caps");
                for (final SpawnCategory category : SpawnCategory.values()) {
                    if (category == SpawnCategory.MISC) {
                        continue;
                    }
                    report.bullet(category.name().toLowerCase(), server.getSpawnLimitUnsafe(category));
                }
                report.send(source);
                return Command.SINGLE_SUCCESS;
            }, source
        );
    }

    private static int resetOperation(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();

        return AbstractCommandExecution.executeOnGlobal(
            () -> {
                final SpawnCategory category = getCategory(context);
                final CraftServer craftServer = MinecraftServer.getServer().server;
                //noinspection removal - holy hell ew
                final int resetValue = craftServer.spigot()
                    .getBukkitConfig()
                    .getInt(CraftSpawnCategory.getConfigNameSpawnLimit(category));

                craftServer.spawnCategoryLimit.put(category, resetValue);
                Style.bullets()
                    .bullet("Limit (" + category + ")", "reset to " + resetValue)
                    .send(source);

                return Command.SINGLE_SUCCESS;
            }, source
        );
    }

    private static int setOperation(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();

        return AbstractCommandExecution.executeOnGlobal(
            () -> {
                final SpawnCategory category = getCategory(context);
                final int value = IntegerArgumentType.getInteger(context, "value");

                if (!CraftSpawnCategory.isValidForLimits(category)) {
                    throw INVALID_FOR_LIMITS.create();
                }

                MinecraftServer.getServer().server.spawnCategoryLimit.put(category, value);
                Style.bullets()
                    .bullet("Limit (" + category + ")", String.valueOf(value))
                    .send(source);

                return Command.SINGLE_SUCCESS;
            }, source
        );
    }

    private static int getOperation(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();

        return AbstractCommandExecution.executeOnGlobal(
            () -> {
                final SpawnCategory category = getCategory(context);
                final int value = MinecraftServer.getServer().server.getSpawnLimitUnsafe(category);

                Style.bullets()
                    .bullet("Limit (" + category + ")", String.valueOf(value))
                    .send(source);

                return Command.SINGLE_SUCCESS;
            }, source
        );
    }

    private static SpawnCategory getCategory(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        final String raw = StringArgumentType.getString(context, "category");
        final String upper = raw.toUpperCase();
        final SpawnCategory parsed = NAME2CATEGORY.get(upper);

        if (parsed == null) {
            throw INVALID_CATEGORY.create(raw);
        }

        return parsed;
    }
}


