package io.enigmaclient.menu;

import io.enigmaclient.EnigmaClient;
import io.enigmaclient.config.EnigmaConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class EnigmaConfigScreen extends Screen {

    private static final String TITLE = "EnigmaClient \u2013 Einstellungen";

    private final Screen parent;

    private String host;
    private String port;
    private String jarPath;
    private String memory;
    private boolean enabled;
    private boolean debugLogging;
    private boolean showHud;
    private boolean notifications;

    public EnigmaConfigScreen(Screen parent) {
        super(Component.literal(TITLE));
        this.parent = parent;
        EnigmaConfig config = EnigmaClient.config;
        this.host = config.host;
        this.port = String.valueOf(config.port);
        this.jarPath = config.jarPath;
        this.memory = String.valueOf(config.xmxMb);
        this.enabled = config.enabled;
        this.debugLogging = config.debugLogging;
        this.showHud = config.showHud;
        this.notifications = config.notifications;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        addRenderableOnly(new StringWidget(
                cx - this.font.width(TITLE) / 2, 14, Component.literal(TITLE), this.font));

        addLabel(cx - 100, 36, "World-Host-Adresse:");
        EditBox hostEdit = addRenderableWidget(new EditBox(this.font, cx - 100, 46, 200, 20,
                Component.literal("World-Host-Adresse")));
        hostEdit.setMaxLength(255);
        hostEdit.setValue(this.host);
        hostEdit.setResponder(v -> this.host = v);
        this.setInitialFocus(hostEdit);

        addLabel(cx - 100, 74, "Control-Port:");
        addLabel(cx + 40, 74, "Speicher (MB):");
        EditBox portEdit = addRenderableWidget(new EditBox(this.font, cx - 100, 84, 60, 20,
                Component.literal("Control-Port")));
        portEdit.setMaxLength(5);
        portEdit.setValue(this.port);
        portEdit.setResponder(v -> this.port = v);

        EditBox memoryEdit = addRenderableWidget(new EditBox(this.font, cx + 40, 84, 60, 20,
                Component.literal("Speicher")));
        memoryEdit.setMaxLength(5);
        memoryEdit.setValue(this.memory);
        memoryEdit.setResponder(v -> this.memory = v);

        addLabel(cx - 100, 112, "EnigmaEngine.jar (leer = automatisch):");
        EditBox jarEdit = addRenderableWidget(new EditBox(this.font, cx - 100, 122, 200, 20,
                Component.literal("EnigmaEngine.jar")));
        jarEdit.setMaxLength(255);
        jarEdit.setValue(this.jarPath);
        jarEdit.setResponder(v -> this.jarPath = v);

        int buttonWidth = 98;
        int toggleX = cx - 150;
        int toggleY = 152;
        addToggle(toggleX, toggleY, "Autostart", () -> this.enabled, v -> this.enabled = v);
        addToggle(toggleX + buttonWidth + 3, toggleY, "HUD", () -> this.showHud, v -> this.showHud = v);
        addToggle(toggleX + 2 * (buttonWidth + 3), toggleY, "Benachricht.", () -> this.notifications,
                v -> this.notifications = v);
        addToggle(toggleX, toggleY + 24, "Debug-Logging", () -> this.debugLogging,
                v -> this.debugLogging = v);

        int actionY = 200;
        addRenderableWidget(Button.builder(Component.literal("Fertig"), b -> this.onClose())
                .bounds(cx - 100, actionY, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Zur\u00FCcksetzen"), b -> reset())
                .bounds(cx + 2, actionY, buttonWidth, 20).build());
    }

    private void addLabel(int x, int y, String text) {
        addRenderableOnly(new StringWidget(x, y, Component.literal(text)
                .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)), this.font));
    }

    private void addToggle(int x, int y, String label, BooleanSupplier get, Consumer<Boolean> set) {
        addRenderableWidget(Button.builder(toggleText(label, get.getAsBoolean()), b -> {
            boolean next = !get.getAsBoolean();
            set.accept(next);
            b.setMessage(toggleText(label, next));
        }).bounds(x, y, 98, 20).build());
    }

    private static Component toggleText(String label, boolean on) {
        return Component.literal(label + ": " + (on ? "ein" : "aus"))
                .withStyle(Style.EMPTY.withColor(on ? ChatFormatting.GREEN : ChatFormatting.GRAY));
    }

    private void reset() {
        EnigmaConfig defaults = new EnigmaConfig();
        this.host = defaults.host;
        this.port = String.valueOf(defaults.port);
        this.jarPath = defaults.jarPath;
        this.memory = String.valueOf(defaults.xmxMb);
        this.enabled = defaults.enabled;
        this.debugLogging = defaults.debugLogging;
        this.showHud = defaults.showHud;
        this.notifications = defaults.notifications;
        this.rebuildWidgets();
    }

    @Override
    public void onClose() {
        EnigmaConfig config = EnigmaClient.config;

        String trimmedHost = this.host == null ? "" : this.host.trim();
        if (!trimmedHost.isEmpty()) {
            config.host = trimmedHost;
        }
        config.port = parseBounded(this.port, config.port, 1, 65535);
        config.xmxMb = parseBounded(this.memory, config.xmxMb, 512, 65536);
        config.jarPath = this.jarPath == null ? "" : this.jarPath.trim();

        config.enabled = this.enabled;
        config.debugLogging = this.debugLogging;
        config.showHud = this.showHud;
        config.notifications = this.notifications;
        config.validate();
        config.save();

        EnigmaClient.LOGGER.info("Einstellungen gespeichert \u2013 World-Host {}", config.target());
        this.minecraft.gui.setScreen(this.parent);
    }

    private static int parseBounded(String text, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(text.trim());
            return Math.max(min, Math.min(max, value));
        } catch (Exception e) {
            return fallback;
        }
    }
}
