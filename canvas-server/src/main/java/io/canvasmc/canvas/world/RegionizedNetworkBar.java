package io.canvasmc.canvas.world;

import com.mojang.datafixers.util.Pair;
import io.canvasmc.canvas.WorldConfig;
import java.text.DecimalFormat;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jspecify.annotations.Nullable;

public class RegionizedNetworkBar extends RegionResourceBar {
    private static final ThreadLocal<DecimalFormat> RATE_FORMAT = ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.0"));
    private static final ThreadLocal<DecimalFormat> PING_FORMAT = ThreadLocal.withInitial(() -> new DecimalFormat("#,##0"));
    private static final ThreadLocal<DecimalFormat> INT_FORMAT = ThreadLocal.withInitial(() -> new DecimalFormat("#,##0"));

    private static final TextColor ORANGE = TextColor.color(0xFFA500);

    private static final String[] KEYS = {"rx", "tx", "ping", "players"};

    private static final long SNAPSHOT_INTERVAL_NANOS = 1_000_000_000L;

    private static volatile long snapshotPingTotal;
    private static volatile int snapshotPlayerCount;
    private static volatile float snapshotReceivedPackets;
    private static volatile float snapshotSentPackets;
    private static volatile long snapshotLastUpdate;

    private final AtomicReference<@Nullable FormatEntry> cachedFormat = new AtomicReference<>(null);

    public RegionizedNetworkBar() {
        super((worldData) -> worldData.world.canvasConfig().regionBars.enableNetworkBar);
    }

    public static void globalTick() {
        final long now = System.nanoTime();
        if (now - snapshotLastUpdate < SNAPSHOT_INTERVAL_NANOS) {
            return;
        }
        snapshotLastUpdate = now;

        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }

        long pingTotal = 0L;
        int players = 0;
        float receivedPackets = 0.0F;
        float sentPackets = 0.0F;

        for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
            final ServerGamePacketListenerImpl listener = player.connection;
            if (listener == null || listener.connection == null) {
                continue;
            }
            players++;
            pingTotal += listener.latency();
            receivedPackets += listener.connection.getAverageReceivedPackets();
            sentPackets += listener.connection.getAverageSentPackets();
        }

        snapshotPingTotal = pingTotal;
        snapshotPlayerCount = players;
        snapshotReceivedPackets = receivedPackets;
        snapshotSentPackets = sentPackets;
    }

    @Override
    void updatePlayerDisplaysAndTick(final Pair<Component, Float> componentAndProgress) {
        for (final ServerPlayer localPlayer : getWorldData().getLocalPlayers()) {
            localPlayer.canvas$networkBarDisplay.setDisplay(componentAndProgress.getFirst());
            localPlayer.canvas$networkBarDisplay.setProgress(componentAndProgress.getSecond());
            localPlayer.canvas$networkBarDisplay.tick();
        }
    }

    @Override
    Pair<Component, Float> buildComponent() {
        final int players = snapshotPlayerCount;
        final float receivedPackets = snapshotReceivedPackets;
        final float sentPackets = snapshotSentPackets;
        final double pingAverage = players == 0 ? 0.0D : (double) snapshotPingTotal / players;

        final String raw = getWorldData().world.canvasConfig().regionBars.networkBarFormat;
        final String effectiveRaw = (raw == null || raw.isBlank()) ? "" : raw;

        FormatEntry entry = cachedFormat.get();
        if (entry == null || !effectiveRaw.equals(entry.raw())) {
            entry = FormatEntry.compile(effectiveRaw, this);
            cachedFormat.set(entry);
        }

        final TextColor rxColor = packetColour(receivedPackets);
        final TextColor txColor = packetColour(sentPackets);
        final TextColor pingColor = pingColour(pingAverage);

        final Component rxComponent = Component.text(RATE_FORMAT.get().format(receivedPackets) + " pkt/s", rxColor);
        final Component txComponent = Component.text(RATE_FORMAT.get().format(sentPackets) + " pkt/s", txColor);
        final Component pingComponent = Component.text(PING_FORMAT.get().format(pingAverage) + " ms", pingColor);
        final Component playersComponent = number(players, INT_FORMAT, NamedTextColor.AQUA);

        final TextComponent.Builder builder = Component.text();
        for (final FormatEntry.Segment segment : entry.segments()) {
            if (segment instanceof FormatEntry.Segment.Static(Component component)) {
                builder.append(component);
            }
            else if (segment instanceof FormatEntry.Segment.Dynamic(String key)) {
                builder.append(switch (key) {
                    case "rx" -> rxComponent;
                    case "tx" -> txComponent;
                    case "ping" -> pingComponent;
                    case "players" -> playersComponent;
                    default -> Component.empty();
                });
            }
        }

        final float quality = (float) Math.clamp(1.0D - pingAverage / 500.0D, 0.0D, 1.0D);
        return new Pair<>(builder.build(), quality);
    }

    static TextColor packetColour(final double packetsPerSecond) {
        if (packetsPerSecond < 50.0D) {
            return NamedTextColor.GREEN;
        }
        if (packetsPerSecond < 200.0D) {
            return NamedTextColor.YELLOW;
        }
        if (packetsPerSecond < 1000.0D) {
            return ORANGE;
        }
        return NamedTextColor.RED;
    }

    static TextColor pingColour(final double pingMs) {
        if (pingMs < 80.0D) {
            return NamedTextColor.GREEN;
        }
        if (pingMs < 150.0D) {
            return NamedTextColor.YELLOW;
        }
        if (pingMs < 250.0D) {
            return ORANGE;
        }
        return NamedTextColor.RED;
    }

    @Override
    String normalize(final String input) {
        return input
            .replace("%rx%", "<rx>")
            .replace("%tx%", "<tx>")
            .replace("%ping%", "<ping>")
            .replace("%players%", "<players>");
    }

    @Override
    String getDefaultFormat() {
        return WorldConfig.DEFAULT_NETWORKBAR_FORMAT;
    }

    @Override
    String[] getKeys() {
        return KEYS;
    }
}
