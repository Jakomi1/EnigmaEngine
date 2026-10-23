package io.enigmaclient.menu;

import io.enigmaclient.EnigmaClient;
import io.enigmaclient.chunkmap.ChunkMapClient;
import io.enigmaclient.config.EnigmaConfig;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * Settings screen, rebuilt on top of the vanilla {@link OptionsSubScreen} / {@code OptionsList}
 * infrastructure. Rows are laid out and scrolled by the list itself, so nothing can overlap and
 * every control is aligned the same way the vanilla video/sound settings are.
 */
public class EnigmaConfigScreen extends OptionsSubScreen {

    private static final Component TITLE = Component.literal("EnigmaClient");

    private String host;
    private String port;
    private String jarPath;
    private String memory;
    private boolean enabled;
    private boolean debugLogging;
    private boolean showHud;
    private boolean notifications;
    private boolean showChunkMap;
    private boolean showChunkMapBuffer;
    private boolean chunkMapShowPlayers;
    private boolean chunkMapRegionDebug;
    private boolean chunkMapPlayersMapOnly;

    public EnigmaConfigScreen(Screen parent) {
        super(parent, net.minecraft.client.Minecraft.getInstance().options, TITLE);
        final EnigmaConfig config = EnigmaClient.config;
        this.host = config.host;
        this.port = String.valueOf(config.port);
        this.jarPath = config.jarPath;
        this.memory = String.valueOf(config.xmxMb);
        this.enabled = config.enabled;
        this.debugLogging = config.debugLogging;
        this.showHud = config.showHud;
        this.notifications = config.notifications;
        this.showChunkMap = config.showChunkMap;
        this.showChunkMapBuffer = config.showChunkMapBuffer;
        this.chunkMapShowPlayers = config.chunkMapShowPlayers;
        this.chunkMapRegionDebug = config.chunkMapRegionDebug;
        this.chunkMapPlayersMapOnly = config.chunkMapPlayersMapOnly;
    }

    @Override
    protected void addOptions() {
        this.list.addHeader(Component.literal("Verbindung"));

        EditBox hostEdit = new EditBox(this.font, 0, 0, 308, 20, Component.literal("World-Host-Adresse"));
        hostEdit.setMaxLength(255);
        hostEdit.setValue(this.host);
        hostEdit.setHint(Component.literal("World-Host-Adresse, z. B. 127.0.0.1").withStyle(s -> s.withColor(0x808080)));
        hostEdit.setTooltip(Tooltip.create(Component.literal("Adresse des World-Host-Prozesses")));
        hostEdit.setResponder(value -> this.host = value);
        this.list.addBig(hostEdit);
        this.setInitialFocus(hostEdit);

        this.list.addSmall(
                EditBoxHint.number(this.font, this.port, 5, "Control-Port", value -> this.port = value),
                EditBoxHint.number(this.font, this.memory, 5, "Speicher (MB)", value -> this.memory = value)
        );

        this.list.addHeader(Component.literal("Host-Prozess"));

        EditBox jarEdit = new EditBox(this.font, 0, 0, 308, 20, Component.literal("EnigmaEngine.jar"));
        jarEdit.setMaxLength(255);
        jarEdit.setValue(this.jarPath);
        jarEdit.setHint(Component.literal("EnigmaEngine.jar-Pfad (leer = automatisch)").withStyle(s -> s.withColor(0x808080)));
        jarEdit.setTooltip(Tooltip.create(Component.literal("Pfad zur Server-Jar. Leer lassen, um sie automatisch zu suchen.")));
        jarEdit.setResponder(value -> this.jarPath = value);
        this.list.addBig(jarEdit);

        this.list.addSmall(
                Checkbox.builder(Component.literal("Autostart"), this.font)
                        .selected(this.enabled)
                        .onValueChange((box, value) -> this.enabled = value)
                        .tooltip(Tooltip.create(Component.literal("Startet den Compute-Host beim Spielstart automatisch")))
                        .build(),
                Checkbox.builder(Component.literal("Debug-Logging"), this.font)
                        .selected(this.debugLogging)
                        .onValueChange((box, value) -> this.debugLogging = value)
                        .tooltip(Tooltip.create(Component.literal("Ausfuehrliche Log-Ausgaben im Client")))
                        .build()
        );

        this.list.addHeader(Component.literal("Anzeige"));

        this.list.addSmall(
                Checkbox.builder(Component.literal("HUD"), this.font)
                        .selected(this.showHud)
                        .onValueChange((box, value) -> this.showHud = value)
                        .tooltip(Tooltip.create(Component.literal("Zeigt das EnigmaClient-HUD oben links")))
                        .build(),
                Checkbox.builder(Component.literal("Benachrichtigungen"), this.font)
                        .selected(this.notifications)
                        .onValueChange((box, value) -> this.notifications = value)
                        .tooltip(Tooltip.create(Component.literal("Chat-Hinweise bei Statusaenderungen")))
                        .build()
        );

        this.list.addHeader(Component.literal("Server-Chunk-Karte"));

        this.list.addSmall(
                Checkbox.builder(Component.literal("Chunk-Karte"), this.font)
                        .selected(this.showChunkMap)
                        .onValueChange((box, value) -> this.showChunkMap = value)
                        .tooltip(Tooltip.create(Component.literal("Zeichnet vom Server gemeldete Chunks auf Xaero's Karten")))
                        .build(),
                Checkbox.builder(Component.literal("Spieler anzeigen"), this.font)
                        .selected(this.chunkMapShowPlayers)
                        .onValueChange((box, value) -> this.chunkMapShowPlayers = value)
                        .tooltip(Tooltip.create(Component.literal("Zeigt die Positionen aller Spieler mit Marker")))
                        .build()
        );

        this.list.addSmall(
                Checkbox.builder(Component.literal("Fusions-Buffer"), this.font)
                        .selected(this.showChunkMapBuffer)
                        .onValueChange((box, value) -> this.showChunkMapBuffer = value)
                        .tooltip(Tooltip.create(Component.literal("Zeigt den vom Server verwendeten Buffer-Radius blau an")))
                        .build(),
                Checkbox.builder(Component.literal("Region-Infos beim Hovern"), this.font)
                        .selected(this.chunkMapRegionDebug)
                        .onValueChange((box, value) -> this.chunkMapRegionDebug = value)
                        .tooltip(Tooltip.create(Component.literal("Beim Hovern Details der Region inkl. MSPT/TPS anzeigen")))
                        .build()
        );

        this.list.addSmall(
                Checkbox.builder(Component.literal("Spieler in Spielwelt"), this.font)
                        .selected(!this.chunkMapPlayersMapOnly)
                        .onValueChange((box, value) -> this.chunkMapPlayersMapOnly = !value)
                        .tooltip(Tooltip.create(Component.literal("Zeigt Spieler-Marker zusätzlich in der Spielwelt")))
                        .build(),
                Button.builder(Component.literal("Karte neu laden"), button -> ChunkMapClient.syncSubscription())
                        .tooltip(Tooltip.create(Component.literal("Fordert eine aktuelle Server-Chunk-Karte an")))
                        .build()
        );

    }

    @Override
    protected void addFooter() {
        LinearLayout row = LinearLayout.horizontal().spacing(8);
        row.addChild(Button.builder(Component.literal("Zuruecksetzen"), button -> reset())
                .width(150).build());
        row.addChild(Button.builder(Component.literal("Fertig"), button -> this.onClose())
                .width(150).build());
        this.layout.addToFooter(row);
    }

    private void reset() {
        final EnigmaConfig defaults = new EnigmaConfig();
        this.host = defaults.host;
        this.port = String.valueOf(defaults.port);
        this.jarPath = defaults.jarPath;
        this.memory = String.valueOf(defaults.xmxMb);
        this.enabled = defaults.enabled;
        this.debugLogging = defaults.debugLogging;
        this.showHud = defaults.showHud;
        this.notifications = defaults.notifications;
        this.showChunkMap = defaults.showChunkMap;
        this.showChunkMapBuffer = defaults.showChunkMapBuffer;
        this.chunkMapShowPlayers = defaults.chunkMapShowPlayers;
        this.chunkMapRegionDebug = defaults.chunkMapRegionDebug;
        this.chunkMapPlayersMapOnly = defaults.chunkMapPlayersMapOnly;
        this.rebuildWidgets();
    }

    @Override
    public void onClose() {
        final EnigmaConfig config = EnigmaClient.config;

        final String trimmedHost = this.host == null ? "" : this.host.trim();
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
        config.showChunkMap = this.showChunkMap;
        config.showChunkMapBuffer = this.showChunkMapBuffer;
        config.chunkMapShowPlayers = this.chunkMapShowPlayers;
        config.chunkMapRegionDebug = this.chunkMapRegionDebug;
        config.chunkMapPlayersMapOnly = this.chunkMapPlayersMapOnly;
        config.validate();
        config.save();

        ChunkMapClient.syncSubscription();

        EnigmaClient.LOGGER.info("Einstellungen gespeichert \u2013 World-Host {}", config.target());
        this.minecraft.gui.setScreen(this.lastScreen);
    }

    private static int parseBounded(String text, int fallback, int min, int max) {
        try {
            final int value = Integer.parseInt(text.trim());
            return Math.max(min, Math.min(max, value));
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Small helper so the numeric fields share one construction path and keep the hint styling
     * consistent with the address fields.
     */
    private static final class EditBoxHint {

        private EditBoxHint() {
        }

        static EditBox number(net.minecraft.client.gui.Font font, String value, int maxLength,
                              String hint, java.util.function.Consumer<String> responder) {
            final EditBox box = new EditBox(font, 0, 0, 150, 20, Component.literal(hint));
            box.setMaxLength(maxLength);
            box.setValue(value);
            box.setHint(Component.literal(hint).withStyle(s -> s.withColor(0x808080)));
            box.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(hint)));
            box.setResponder(responder);
            return box;
        }
    }
}
