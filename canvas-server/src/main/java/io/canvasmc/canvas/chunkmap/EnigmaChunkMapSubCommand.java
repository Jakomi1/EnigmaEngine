package io.canvasmc.canvas.chunkmap;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.canvasmc.canvas.commands.Style;
import io.canvasmc.canvas.commands.SubCommand;
import java.util.UUID;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import static net.minecraft.commands.Commands.literal;

/**
 * Controls the server side of the EnigmaClient chunk map: clients that opt in through the
 * {@code enigmaclient:chunkmap_subscribe} payload receive periodic snapshots of the chunks this
 * server considers ticking and the exact chunks owned by every Canvas/Folia region.
 *
 * <p>Only the lowercase {@code /enigma chunkmap <argument>} form exists; the bare command is not
 * executable, so an operator always states what they want to do. All settings changed here are
 * persisted to the {@code chunkmap} section of {@code config/enigma-engine.yml}.</p>
 */
public final class EnigmaChunkMapSubCommand implements SubCommand {

    @Override
    public String getDescription() {
        return "Controls the chunk map pushed to EnigmaClient mods";
    }

    @Override
    public String getName() {
        return "chunkmap";
    }

    @Override
    public boolean hasExtraArgs() {
        // The bare "/enigma chunkmap" is deliberately not executable.
        return false;
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> construct(final LiteralArgumentBuilder<CommandSourceStack> base, final CommandBuildContext buildContext) {
        return base
            .then(literal("status")
                .requires(this.check("status"))
                .executes(EnigmaChunkMapSubCommand::status))
            .then(literal("enable")
                .requires(this.check("enable"))
                .executes(EnigmaChunkMapSubCommand::enable))
            .then(literal("disable")
                .requires(this.check("disable"))
                .executes(EnigmaChunkMapSubCommand::disable))
            .then(literal("clients")
                .requires(this.check("clients"))
                .executes(EnigmaChunkMapSubCommand::clients))
            .then(literal("settings")
                .requires(this.check("settings"))
                .executes(EnigmaChunkMapSubCommand::settings)
                .then(literal("enabled")
                    .then(literal("on").executes(ctx -> setSetting(ctx, true)))
                    .then(literal("off").executes(ctx -> setSetting(ctx, false)))));
    }

    private static int status(final CommandContext<CommandSourceStack> context) {
        final EnigmaChunkMapService service = EnigmaChunkMapService.getInstance();
        Style.bullets()
            .bullet("Chunk map", Style.onOff(service.enabled()))
            .bullet("Update interval", "1s")
            .bullet("Protocol", "v" + EnigmaChunkMapService.PROTOCOL_VERSION)
            .bullet("Subscribed clients", service.subscriberCount())
            .bullet("Config", "config/enigma-engine.yml (chunkmap)")
            .line(Style.value("Controls the chunk overlay pushed to EnigmaClient mods"))
            .send(context.getSource());
        return Command.SINGLE_SUCCESS;
    }

    private static int enable(final CommandContext<CommandSourceStack> context) {
        EnigmaChunkMapService.getInstance().setEnabled(true);
        Style.bullets()
            .bullet("Chunk map", "enabled", Style.good())
            .send(context.getSource());
        return Command.SINGLE_SUCCESS;
    }

    private static int disable(final CommandContext<CommandSourceStack> context) {
        EnigmaChunkMapService.getInstance().setEnabled(false);
        Style.bullets()
            .bullet("Chunk map", "disabled", Style.bad())
            .send(context.getSource());
        return Command.SINGLE_SUCCESS;
    }

    private static int settings(final CommandContext<CommandSourceStack> context) {
        final EnigmaChunkMapService service = EnigmaChunkMapService.getInstance();
        Style.bullets()
            .bullet("enabled", Style.onOff(service.enabled()))
            .bullet("Config", "config/enigma-engine.yml (chunkmap)")
            .line(Style.value("Change a value with /enigma chunkmap settings <key> <on|off>"))
            .send(context.getSource());
        return Command.SINGLE_SUCCESS;
    }

    private static int setSetting(final CommandContext<CommandSourceStack> context, final boolean value) {
        final EnigmaChunkMapService service = EnigmaChunkMapService.getInstance();
        service.setEnabled(value);
        Style.bullets()
            .bullet("enabled", value ? "on" : "off", value ? Style.good() : Style.bad())
            .bullet("Saved", "config/enigma-engine.yml (chunkmap)", Style.good())
            .send(context.getSource());
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Lists every subscribed client with its name and the age of the last snapshot it received.
     * A client only appears here after its EnigmaClient mod sent the subscribe payload, so the
     * list doubles as proof that the mod is present and talking to this server.
     */
    private static int clients(final CommandContext<CommandSourceStack> context) {
        final EnigmaChunkMapService service = EnigmaChunkMapService.getInstance();
        final MinecraftServer server = MinecraftServer.getServer();
        final Style.Report report = Style.report("Subscribed chunk map clients");
        if (service.subscriberCount() == 0) {
            report.bullet("Clients", "none");
        } else {
            final long now = System.currentTimeMillis();
            for (final UUID uuid : service.subscribers()) {
                String name = uuid.toString();
                int ping = -1;
                if (server != null) {
                    final ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                    if (player != null) {
                        name = player.getGameProfile().name();
                        ping = player.connection.latency();
                    }
                }
                final long lastPushedAt = service.lastPushedAt(uuid);
                final String lastPush = lastPushedAt == 0L
                        ? "not yet"
                        : ((now - lastPushedAt) / 1000L) + "s ago";
                report.bullet(name, "last push " + lastPush + (ping >= 0 ? ", ping " + ping + "ms" : ""));
            }
            report.bullet("Protocol", "v" + EnigmaChunkMapService.PROTOCOL_VERSION);
        }
        report.send(context.getSource());
        return Command.SINGLE_SUCCESS;
    }
}
