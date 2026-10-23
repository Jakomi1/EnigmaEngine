package io.canvasmc.canvas.subcommands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.canvasmc.canvas.commands.Style;
import io.canvasmc.canvas.commands.SubCommand;
import io.canvasmc.canvas.world.RegionResourceBar;
import java.util.Collection;
import net.kyori.adventure.text.Component;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Per-player region resource bars. The old {@code /enigma regionbar <type> [players] [placement]}
 * mixed an optional collection argument in front of another optional argument, which made the
 * trailing word ambiguous. The new grammar always names the operation first:
 *
 * <pre>
 * /enigma display &lt;tps|ram|cpu|network&gt; toggle [&lt;players&gt;]
 * /enigma display &lt;tps|ram|cpu|network&gt; status [&lt;players&gt;]
 * /enigma display &lt;tps|ram|cpu|network&gt; placement &lt;action_bar|boss_bar&gt; [&lt;players&gt;]
 * </pre>
 */
public class DisplaySubCommand implements SubCommand {

    private static final SimpleCommandExceptionType INVALID_BAR_TYPE = new SimpleCommandExceptionType(
        net.minecraft.network.chat.Component.literal("Invalid bar type: must be \"tps\", \"ram\", \"cpu\" or \"network\".")
    );
    private static final SimpleCommandExceptionType INVALID_PLACEMENT = new SimpleCommandExceptionType(
        net.minecraft.network.chat.Component.literal("Invalid placement: must be \"action_bar\" or \"boss_bar\".")
    );
    private static final SimpleCommandExceptionType MUST_BE_PLAYER = new SimpleCommandExceptionType(
        net.minecraft.network.chat.Component.literal("You must be a player to change your own display.")
    );

    @Override
    public String getDescription() {
        return "Toggles, queries or moves the region resource bars of one or more players";
    }

    @Override
    public String getName() {
        return "display";
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> construct(final LiteralArgumentBuilder<CommandSourceStack> base, final CommandBuildContext buildContext) {
        return base
            .then(argument("bar", StringArgumentType.word())
                .suggests((_, builder) -> {
                    for (final BarType type : BarType.values()) {
                        builder.suggest(type.lowerName);
                    }
                    return builder.buildFuture();
                })
                .then(literal("toggle")
                    .executes(context -> toggle(context, null))
                    .then(argument("players", EntityArgument.players())
                        .executes(context -> toggle(context, EntityArgument.getPlayers(context, "players")))))
                .then(literal("status")
                    .executes(context -> status(context, null))
                    .then(argument("players", EntityArgument.players())
                        .executes(context -> status(context, EntityArgument.getPlayers(context, "players")))))
                .then(literal("placement")
                    .then(argument("placement", StringArgumentType.word())
                        .suggests((_, builder) -> {
                            builder.suggest("action_bar");
                            builder.suggest("boss_bar");
                            return builder.buildFuture();
                        })
                        .executes(context -> placement(context, null,
                            StringArgumentType.getString(context, "placement")))
                        .then(argument("players", EntityArgument.players())
                            .executes(context -> placement(context, EntityArgument.getPlayers(context, "players"),
                                StringArgumentType.getString(context, "placement")))))));
    }

    private static int toggle(final CommandContext<CommandSourceStack> context, final Collection<ServerPlayer> targets)
        throws CommandSyntaxException {
        final CommandSourceStack source = context.getSource();
        final BarType type = parseBarType(context);
        for (final ServerPlayer target : targets(source, targets)) {
            target.getBukkitEntity().taskScheduler.scheduleOrExecute((ServerPlayer player) -> {
                final RegionResourceBar.DisplayManager display = type.display(player);
                final RegionResourceBar.Entry current = display.serializeDisplay();
                final RegionResourceBar.Entry updated = new RegionResourceBar.Entry(!current.enabled(), current.placement());
                display.updateFromEntry(updated);

                Style.bullets()
                    .bullet(type.lowerName.toUpperCase() + " for " + player.getName().getString(),
                        updated.enabled() ? "enabled" : "disabled",
                        updated.enabled() ? Style.good() : Style.SECONDARY)
                    .send(source);
            });
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int status(final CommandContext<CommandSourceStack> context, final Collection<ServerPlayer> targets)
        throws CommandSyntaxException {
        final CommandSourceStack source = context.getSource();
        final BarType type = parseBarType(context);
        for (final ServerPlayer target : targets(source, targets)) {
            target.getBukkitEntity().taskScheduler.scheduleOrExecute((ServerPlayer player) -> {
                final RegionResourceBar.Entry current = type.display(player).serializeDisplay();
                Style.bullets()
                    .bullet(type.lowerName.toUpperCase() + " for " + player.getName().getString(),
                        current.enabled() ? "on" : "off",
                        current.enabled() ? Style.good() : Style.SECONDARY)
                    .bullet("Placement", current.placement().name().toLowerCase())
                    .send(source);
            });
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int placement(
        final CommandContext<CommandSourceStack> context,
        final Collection<ServerPlayer> targets,
        final String rawPlacement
    ) throws CommandSyntaxException {
        final CommandSourceStack source = context.getSource();
        final BarType type = parseBarType(context);
        final RegionResourceBar.Placement placement = switch (rawPlacement.toLowerCase()) {
            case "action_bar" -> RegionResourceBar.Placement.ACTION_BAR;
            case "boss_bar" -> RegionResourceBar.Placement.BOSS_BAR;
            default -> throw INVALID_PLACEMENT.create();
        };

        for (final ServerPlayer target : targets(source, targets)) {
            target.getBukkitEntity().taskScheduler.scheduleOrExecute((ServerPlayer player) -> {
                final RegionResourceBar.DisplayManager display = type.display(player);
                final RegionResourceBar.Entry current = display.serializeDisplay();
                display.updateFromEntry(new RegionResourceBar.Entry(current.enabled(), placement));

                Style.bullets()
                    .bullet(type.lowerName.toUpperCase() + " placement for " + player.getName().getString(),
                        rawPlacement.toLowerCase())
                    .send(source);
            });
        }
        return Command.SINGLE_SUCCESS;
    }

    private static Collection<ServerPlayer> targets(
        final CommandSourceStack source,
        final Collection<ServerPlayer> targets
    ) throws CommandSyntaxException {
        if (targets != null) {
            return targets;
        }
        final ServerPlayer player = source.getPlayer();
        if (player == null) {
            throw MUST_BE_PLAYER.create();
        }
        return java.util.List.of(player);
    }

    private static BarType parseBarType(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        final String raw = StringArgumentType.getString(context, "bar").toLowerCase();
        for (final BarType type : BarType.values()) {
            if (type.lowerName.equals(raw)) {
                return type;
            }
        }
        throw INVALID_BAR_TYPE.create();
    }

    private enum BarType {
        TPS("tps"),
        RAM("ram"),
        CPU("cpu"),
        NETWORK("network");

        private final String lowerName;

        BarType(final String lowerName) {
            this.lowerName = lowerName;
        }

        RegionResourceBar.DisplayManager display(final ServerPlayer player) {
            return switch (this) {
                case RAM -> player.canvas$ramBarDisplay;
                case TPS -> player.canvas$tpsBarDisplay;
                case CPU -> player.canvas$cpuBarDisplay;
                case NETWORK -> player.canvas$networkBarDisplay;
            };
        }
    }
}


