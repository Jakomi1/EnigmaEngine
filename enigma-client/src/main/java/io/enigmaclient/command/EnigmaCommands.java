package io.enigmaclient.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.enigmaclient.EnigmaClient;
import io.enigmaclient.config.EnigmaConfig;
import io.enigmaclient.host.ComputeHostService;
import io.enigmaclient.host.DistributedStatus;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

public final class EnigmaCommands {

    private EnigmaCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(tree()));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> tree() {
        return ClientCommands.literal("enigmaclient")
                .then(ClientCommands.literal("status").executes(ctx -> {
                    status(ctx.getSource());
                    return 1;
                }))
                .then(ClientCommands.literal("start").executes(ctx -> {
                    ComputeHostService svc = service(ctx.getSource());
                    if (svc == null) {
                        return 0;
                    }
                    svc.start();
                    return 1;
                }))
                .then(ClientCommands.literal("stop").executes(ctx -> {
                    ComputeHostService svc = service(ctx.getSource());
                    if (svc == null) {
                        return 0;
                    }
                    svc.stop();
                    return 1;
                }))
                .then(ClientCommands.literal("restart").executes(ctx -> {
                    ComputeHostService svc = service(ctx.getSource());
                    if (svc == null) {
                        return 0;
                    }
                    svc.restart();
                    return 1;
                }))
                .then(ClientCommands.literal("autostart").executes(ctx -> {
                    EnigmaConfig config = EnigmaClient.config;
                    config.enabled = !config.enabled;
                    config.save();
                    ctx.getSource().sendFeedback(EnigmaClient.prefix(
                            "Autostart " + (config.enabled ? "ein" : "aus")
                                    + (config.enabled ? "" : " (der laufende Compute-Host bleibt aktiv)")));
                    return 1;
                }))
                .then(ClientCommands.literal("hud").executes(ctx -> {
                    EnigmaConfig config = EnigmaClient.config;
                    config.showHud = !config.showHud;
                    config.save();
                    ctx.getSource().sendFeedback(EnigmaClient.prefix(
                            "HUD " + (config.showHud ? "ein" : "aus")));
                    return 1;
                }))
                .then(ClientCommands.literal("notifications").executes(ctx -> {
                    EnigmaConfig config = EnigmaClient.config;
                    config.notifications = !config.notifications;
                    config.save();
                    ctx.getSource().sendFeedback(EnigmaClient.prefix(
                            "Benachrichtigungen " + (config.notifications ? "ein" : "aus")));
                    return 1;
                }))
                .then(ClientCommands.literal("debug").executes(ctx -> {
                    EnigmaConfig config = EnigmaClient.config;
                    config.debugLogging = !config.debugLogging;
                    config.validate();
                    config.save();
                    ctx.getSource().sendFeedback(restartHint(
                            "Debug-Logging " + (config.debugLogging ? "ein" : "aus")));
                    return 1;
                }))
                .then(ClientCommands.literal("reload").executes(ctx -> {
                    EnigmaClient.config = EnigmaConfig.load();
                    ctx.getSource().sendFeedback(EnigmaClient.prefix(
                            "Konfiguration neu geladen - Ziel " + EnigmaClient.config.target()));
                    return 1;
                }))
                .then(ClientCommands.literal("set")
                        .then(ClientCommands.literal("host")
                                .then(ClientCommands.argument("value", StringArgumentType.string())
                                        .executes(ctx -> {
                                            EnigmaConfig config = EnigmaClient.config;
                                            config.host = StringArgumentType.getString(ctx, "value");
                                            config.validate();
                                            config.save();
                                            ctx.getSource().sendFeedback(restartHint(
                                                    "World-Host: " + config.target()));
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("port")
                                .then(ClientCommands.argument("value", IntegerArgumentType.integer(1, 65535))
                                        .executes(ctx -> {
                                            EnigmaConfig config = EnigmaClient.config;
                                            config.port = IntegerArgumentType.getInteger(ctx, "value");
                                            config.save();
                                            ctx.getSource().sendFeedback(restartHint(
                                                    "World-Host: " + config.target()));
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("jar")
                                .then(ClientCommands.argument("value", StringArgumentType.string())
                                        .executes(ctx -> {
                                            EnigmaConfig config = EnigmaClient.config;
                                            config.jarPath = StringArgumentType.getString(ctx, "value");
                                            config.save();
                                            ctx.getSource().sendFeedback(restartHint(
                                                    "Jar-Pfad: " + (config.jarPath.isBlank()
                                                            ? "(automatisch)" : config.jarPath)));
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("memory")
                                .then(ClientCommands.argument("value", IntegerArgumentType.integer(512, 65536))
                                        .executes(ctx -> {
                                            EnigmaConfig config = EnigmaClient.config;
                                            config.xmxMb = IntegerArgumentType.getInteger(ctx, "value");
                                            config.save();
                                            ctx.getSource().sendFeedback(restartHint(
                                                    "Speicher: " + config.xmxMb + " MB"));
                                            return 1;
                                        }))));
    }

    private static ComputeHostService service(FabricClientCommandSource source) {
        ComputeHostService service = EnigmaClient.computeHost;
        if (service == null) {
            source.sendFeedback(EnigmaClient.prefix("Compute-Host-Dienst nicht verf\u00fcgbar"));
        }
        return service;
    }

    private static Component restartHint(String change) {
        return EnigmaClient.prefix(change + " - wirksam nach /enigmaclient restart");
    }

    private static void status(FabricClientCommandSource source) {
        EnigmaConfig config = EnigmaClient.config;
        ComputeHostService service = EnigmaClient.computeHost;
        if (service == null) {
            source.sendFeedback(EnigmaClient.prefix("Compute-Host-Dienst nicht verf\u00fcgbar"));
            return;
        }

        source.sendFeedback(EnigmaClient.prefix("Compute-Host: " + service.stateText()));
        source.sendFeedback(Component.literal("  Ziel: " + config.target()
                + " \u00B7 Autostart: " + onOff(config.enabled)
                + " \u00B7 Restarts: " + service.restarts())
                .append(Component.literal(" \u00B7 Debug: " + onOff(config.debugLogging))));

        if (service.state() == ComputeHostService.State.ERROR && service.lastError() != null) {
            source.sendFeedback(Component.literal("  " + service.lastError()));
        }
        if (service.jarMissing()) {
            source.sendFeedback(Component.literal("  Jar fehlt: " + service.jarPath()));
        }

        if (service.state() == ComputeHostService.State.CONNECTED) {
            DistributedStatus stats = service.stats();
            if (stats == null || !stats.hasLiveData()) {
                source.sendFeedback(Component.literal("  Live-Daten: (noch nicht verf\u00fcgbar)"));
            } else {
                String live = "  Live-Daten: " + stats.activeRegions + " Regionen \u00B7 "
                        + stats.chunkSum + " Chunks \u00B7 " + Math.max(stats.activeLeases, 0) + " Leases";
                source.sendFeedback(Component.literal(live));
                if (stats.migrationsActive > 0 || stats.migrationsHistory > 0) {
                    String migrations = "  Migrationen: " + stats.migrationsActive + " aktiv"
                            + " \u00B7 " + stats.migrationsHistory + " in Historie";
                    if (!stats.migrationsDetail.isBlank()) {
                        migrations += " \u00B7 " + stats.migrationsDetail;
                    }
                    source.sendFeedback(Component.literal(migrations));
                }
            }
        }

        source.sendFeedback(Component.literal("  HUD: " + onOff(config.showHud)
                + " \u00B7 Benachrichtigungen: " + onOff(config.notifications)
                + " \u00B7 Xmx: " + config.xmxMb + " MB"
                + " \u00B7 Verzeichnis: " + ComputeHostService.DIR_NAME));
    }

    private static String onOff(boolean value) {
        return value ? "ein" : "aus";
    }
}
