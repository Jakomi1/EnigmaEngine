package io.enigmaclient;

import io.enigmaclient.command.EnigmaCommands;
import io.enigmaclient.config.EnigmaConfig;
import io.enigmaclient.host.ComputeHostService;
import io.enigmaclient.hud.EnigmaHud;
import io.enigmaclient.keybind.EnigmaKeybinds;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EnigmaClient implements ClientModInitializer {

    public static final String MOD_ID = "enigmaclient";
    public static final Logger LOGGER = LoggerFactory.getLogger("EnigmaClient");

    public static EnigmaConfig config;
    public static ComputeHostService computeHost;

    @Override
    public void onInitializeClient() {
        config = EnigmaConfig.load();

        ComputeHostService.init();
        computeHost = ComputeHostService.get();

        EnigmaHud.register();
        EnigmaCommands.register();
        EnigmaKeybinds.register();

        Runtime.getRuntime().addShutdownHook(new Thread(
                ComputeHostService::shutdown,
                "EnigmaClient-Shutdown"));

        LOGGER.info("EnigmaClient geladen - World-Host {} (Autostart {})",
                config.target(), config.enabled ? "ein" : "aus");
    }

    public static Component prefix(String text) {
        return Component.literal("[Enigma] ")
                .withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA))
                .append(Component.literal(text)
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)));
    }

    /** Loggt und - falls aktiv - zeigt eine Nachricht in Chat/Actionbar (thread-sicher). */
    public static void notifyUser(String text, boolean chat) {
        LOGGER.info("[EnigmaClient] {}", text);
        EnigmaConfig current = config;
        if (current == null || !current.notifications) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        mc.execute(() -> {
            if (mc.gui == null) {
                return;
            }
            Component message = prefix(text);
            if (chat) {
                mc.gui.hud.getChat().addClientSystemMessage(message);
            }
            mc.gui.hud.setOverlayMessage(message, false);
        });
    }

    public static String formatDuration(long millis) {
        long totalSeconds = millis / 1000L;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%d:%02d", minutes, seconds);
    }
}
