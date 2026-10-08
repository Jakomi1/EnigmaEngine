package io.enigmaclient.hud;

import io.enigmaclient.EnigmaClient;
import io.enigmaclient.config.EnigmaConfig;
import io.enigmaclient.host.ComputeHostService;
import io.enigmaclient.host.DistributedStatus;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

public final class EnigmaHud {

    private static final int COLOR_CONNECTED = 0xFF55FF55;
    private static final int COLOR_PENDING = 0xFFFFFF55;
    private static final int COLOR_ERROR = 0xFFFF5555;
    private static final int COLOR_IDLE = 0xFFAAAAAA;
    private static final int MAX_TEXT_WIDTH = 360;

    private EnigmaHud() {
    }

    public static void register() {
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(EnigmaClient.MOD_ID, "status"),
                EnigmaHud::render);
    }

    private static void render(GuiGraphicsExtractor extractor, DeltaTracker deltaTracker) {
        EnigmaConfig config = EnigmaClient.config;
        ComputeHostService service = EnigmaClient.computeHost;
        Minecraft mc = Minecraft.getInstance();
        if (config == null || service == null || !config.showHud) {
            return;
        }

        Font font = mc.font;
        String title = "EnigmaClient \u00B7 " + config.target();
        String state = truncate(font, service.stateText(), MAX_TEXT_WIDTH);
        int stateColor = switch (service.state()) {
            case CONNECTED -> COLOR_CONNECTED;
            case STARTING, WAITING, RESTARTING -> COLOR_PENDING;
            case ERROR -> COLOR_ERROR;
            default -> COLOR_IDLE;
        };
        String stats = statsLine(service);

        int x = 6;
        int y = 6;
        int width = Math.max(font.width(title), font.width(state));
        if (stats != null) {
            width = Math.max(width, font.width(stats));
        }
        int lines = stats == null ? 2 : 3;
        int lineHeight = font.lineHeight;
        extractor.fill(x - 4, y - 4, x + width + 4, y + lineHeight * lines + 4, 0x900A0A12);
        extractor.text(font, title, x, y, 0xFF55FFFF, true);
        extractor.text(font, state, x, y + lineHeight, stateColor, true);
        if (stats != null) {
            extractor.text(font, stats, x, y + lineHeight * 2, 0xFF88CCCC, true);
        }
    }

    private static String statsLine(ComputeHostService service) {
        if (service.state() != ComputeHostService.State.CONNECTED) {
            return null;
        }
        DistributedStatus stats = service.stats();
        if (stats == null || !stats.hasLiveData()) {
            return null;
        }
        StringBuilder line = new StringBuilder("Regionen: ").append(stats.activeRegions)
                .append(" \u00B7 Chunks: ").append(stats.chunkSum)
                .append(" \u00B7 Leases: ").append(Math.max(stats.activeLeases, 0));
        if (stats.migrationsActive > 0) {
            line.append(" \u00B7 Migration: ").append(stats.migrationsActive);
            if (!stats.migrationsDetail.isBlank()) {
                line.append(" (").append(stats.migrationsDetail).append(')');
            }
        }
        return line.toString();
    }

    private static String truncate(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String result = text;
        while (!result.isEmpty() && font.width(result) > maxWidth) {
            result = result.substring(0, result.length() - 1);
        }
        return result + "\u2026";
    }
}
