package io.canvasmc.canvas.world;

import com.mojang.datafixers.util.Pair;
import io.canvasmc.canvas.WorldConfig;
import io.papermc.paper.threadedregions.commands.CommandUtil;
import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.management.ManagementFactory;
import java.text.DecimalFormat;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Regionized per-core CPU bar. Reads {@code /proc/stat} for per-core utilisation on Linux and falls
 * back to the JVM {@link com.sun.management.OperatingSystemMXBean} for whole-process and system load.
 * <p>
 * This was folded into the server core from the former standalone {@code EnigmaPerformanceBar} plugin
 * so no extra plugin jar has to be deployed alongside EnigmaEngine.
 * </p>
 */
public class RegionizedCpuBar extends RegionResourceBar {
    private static final ThreadLocal<DecimalFormat> PERCENT_FORMAT = ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.0"));

    private static final int MAX_TRACKED_CORES = 64;
    private static final String[] KEYS = {"cpu", "sys", "cores"};

    // we have this as an instance field because formats can be per-world
    private final AtomicReference<@Nullable FormatEntry> cachedFormat = new AtomicReference<>(null);

    // /proc/stat sampling state (Linux only). Each region/world keeps its own sampler so this never
    // crosses region threads.
    private final long[] prevTotal = new long[MAX_TRACKED_CORES];
    private final long[] prevIdle = new long[MAX_TRACKED_CORES];
    private final double[] coreUsage = new double[MAX_TRACKED_CORES];
    private volatile int coreCount = 0;

    private final com.sun.management.OperatingSystemMXBean osBean;

    private volatile double processLoad = 0.0D;
    private volatile double systemLoad = 0.0D;

    public RegionizedCpuBar() {
        super((worldData) -> worldData.world.canvasConfig().regionBars.enableCpuBar);
        com.sun.management.OperatingSystemMXBean bean = null;
        try {
            final java.lang.management.OperatingSystemMXBean platform = ManagementFactory.getOperatingSystemMXBean();
            if (platform instanceof com.sun.management.OperatingSystemMXBean extended) {
                bean = extended;
            }
        } catch (final Throwable ignored) {
            // not available on this JVM, /proc/stat sampling still works on Linux
        }
        this.osBean = bean;
    }

    @Override
    void updatePlayerDisplaysAndTick(final Pair<Component, Float> componentAndProgress) {
        for (final ServerPlayer localPlayer : getWorldData().getLocalPlayers()) {
            localPlayer.canvas$cpuBarDisplay.setDisplay(componentAndProgress.getFirst());
            localPlayer.canvas$cpuBarDisplay.setProgress(componentAndProgress.getSecond());
            localPlayer.canvas$cpuBarDisplay.tick();
        }
    }

    @Override
    Pair<Component, Float> buildComponent() {
        this.sample();

        final String raw = getWorldData().world.canvasConfig().regionBars.cpuBarFormat;
        final String effectiveRaw = (raw == null || raw.isBlank()) ? "" : raw;

        FormatEntry entry = this.cachedFormat.get();
        if (entry == null || !effectiveRaw.equals(entry.raw())) {
            entry = FormatEntry.compile(effectiveRaw, this);
            this.cachedFormat.set(entry);
        }

        final TextColor cpuColor = CommandUtil.getUtilisationColourRegion(this.processLoad / 100.0D);
        final Component cpuComponent = Component.text(PERCENT_FORMAT.get().format(this.processLoad) + "%", cpuColor);
        final Component sysComponent = Component.text(PERCENT_FORMAT.get().format(this.systemLoad) + "%", cpuColor);
        final Component coresComponent = this.buildCoresComponent();

        final TextComponent.Builder builder = Component.text();
        for (final FormatEntry.Segment segment : entry.segments()) {
            if (segment instanceof FormatEntry.Segment.Static(Component component)) {
                builder.append(component);
            }
            else if (segment instanceof FormatEntry.Segment.Dynamic(String key)) {
                builder.append(switch (key) {
                    case "cpu" -> cpuComponent;
                    case "sys" -> sysComponent;
                    case "cores" -> coresComponent;
                    default -> Component.empty();
                });
            }
        }

        return new Pair<>(builder.build(), (float) Math.clamp(this.processLoad / 100.0D, 0.0D, 1.0D));
    }

    private Component buildCoresComponent() {
        final int cores = this.coreCount;
        if (cores <= 0) {
            return Component.text("n/a");
        }

        final TextColor color = CommandUtil.getUtilisationColourRegion(this.processLoad / 100.0D);
        final TextComponent.Builder builder = Component.text();
        for (int i = 0; i < cores; i++) {
            if (i > 0) {
                builder.append(Component.text(" "));
            }
            builder.append(Component.text(
                "C" + i + ":" + PERCENT_FORMAT.get().format(this.coreUsage[i]) + "%",
                color
            ));
        }
        return builder.build();
    }

    private void sample() {
        if (this.osBean != null) {
            try {
                this.processLoad = Math.max(0.0D, this.osBean.getProcessCpuLoad() * 100.0D);
                this.systemLoad = Math.max(0.0D, this.osBean.getCpuLoad() * 100.0D);
            } catch (final Throwable ignored) {
                // keep the previous sample
            }
        }
        this.readProcStat();
    }

    private void readProcStat() {
        try (final BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"))) {
            int seen = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("cpu")) {
                       continue;
                }
                final String[] parts = line.trim().split("\\s+");
                // parts[0] is "cpu" (aggregate) or "cpuN"
                if (parts.length < 5 || parts[0].length() <= 3) {
                    continue;
                }
                final int coreIndex;
                try {
                    coreIndex = Integer.parseInt(parts[0].substring(3));
                } catch (final NumberFormatException ex) {
                    continue;
                }
                if (coreIndex < 0 || coreIndex >= MAX_TRACKED_CORES) {
                    continue;
                }

                final long user = Long.parseLong(parts[1]);
                final long nice = Long.parseLong(parts[2]);
                final long sys = Long.parseLong(parts[3]);
                final long idle = Long.parseLong(parts[4]);
                final long total = user + nice + sys + idle;

                final long deltaTotal = total - this.prevTotal[coreIndex];
                final long deltaIdle = idle - this.prevIdle[coreIndex];
                if (deltaTotal > 0L) {
                    this.coreUsage[coreIndex] = Math.max(0.0D, Math.min(100.0D, (1.0D - (double) deltaIdle / (double) deltaTotal) * 100.0D));
                }
                this.prevTotal[coreIndex] = total;
                this.prevIdle[coreIndex] = idle;
                seen++;
            }
            this.coreCount = seen;
        } catch (final Throwable ignored) {
            // /proc/stat is not readable (e.g. Windows). Leave coreCount as-is; JMX load is still shown.
        }
    }

    @Override
    String normalize(final String input) {
        return input
            .replace("%cpu%", "<cpu>")
            .replace("%sys%", "<sys>")
            .replace("%cores%", "<cores>");
    }

    @Override
    String getDefaultFormat() {
        return WorldConfig.DEFAULT_CPUBAR_FORMAT;
    }

    @Override
    String[] getKeys() {
        return KEYS;
    }
}
