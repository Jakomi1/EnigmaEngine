package io.canvasmc.canvas.world;

import com.mojang.datafixers.util.Pair;
import io.canvasmc.canvas.WorldConfig;
import io.papermc.paper.threadedregions.commands.CommandUtil;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.management.ManagementFactory;
import java.text.DecimalFormat;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Regionized per-core CPU bar. Reads {@code /proc/stat} for per-core utilisation on Linux and falls
 * back to {@code NtQuerySystemInformation(SystemProcessorPerformanceInformation)} on Windows, so every
 * core is shown as {@code C0:xx% C1:xx% ...} on both platforms. Whole-process and system load come
 * from the JVM {@link com.sun.management.OperatingSystemMXBean}.
 * <p>
 * This was folded into the server core from the former standalone {@code EnigmaPerformanceBar} plugin
 * so no extra plugin jar has to be deployed alongside EnigmaEngine.
 * </p>
 */
public class RegionizedCpuBar extends RegionResourceBar {
    private static final ThreadLocal<DecimalFormat> PERCENT_FORMAT = ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.0"));

    private static final TextColor CORE_LABEL_COLOR = NamedTextColor.LIGHT_PURPLE;
    private static final TextColor CORE_ORANGE = TextColor.color(0xFFA500);

    private static final int MAX_TRACKED_CORES = 64;
    private static final String[] KEYS = {"cpu", "sys", "cores"};

    private static final boolean HAS_PROC_STAT = new File("/proc/stat").canRead();
    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    // we have this as an instance field because formats can be per-world
    private final AtomicReference<@Nullable FormatEntry> cachedFormat = new AtomicReference<>(null);

    // Per-core sampling state (/proc/stat on Linux, NtQuerySystemInformation on Windows). Each
    // region/world keeps its own sampler so this never crosses region threads.
    private final long[] prevTotal = new long[MAX_TRACKED_CORES];
    private final long[] prevIdle = new long[MAX_TRACKED_CORES];
    private final double[] coreUsage = new double[MAX_TRACKED_CORES];
    private volatile int coreCount = 0;

    private final com.sun.management.OperatingSystemMXBean osBean;

    // The JVM process/system load is global, not per region or per world, and on Windows
    // OperatingSystemMXBean#getProcessCpuLoad() performs an expensive native performance-counter
    // query (measured at ~100ms per call here). Sampling it once per region per second therefore
    // pins whole tick threads on a single core. Refresh the shared value on a coarse interval and
    // let every region read the cached snapshot instead.
    private static final long PROCESS_LOAD_REFRESH_NANOS = 2_000_000_000L;
    private static volatile double sharedProcessLoad = 0.0D;
    private static volatile double sharedSystemLoad = 0.0D;
    private static volatile long sharedLoadSampledAt = 0L;

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
            // not available on this JVM, per-core sampling still works on Linux/Windows
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

        final DecimalFormat format = PERCENT_FORMAT.get();
        final TextComponent.Builder builder = Component.text();
        for (int i = 0; i < cores; i++) {
            if (i > 0) {
                builder.append(Component.text(" "));
            }
            final double usage = this.coreUsage[i];
            builder.append(Component.text("C" + i + ":", CORE_LABEL_COLOR));
            builder.append(Component.text(format.format(usage) + "%", coreLoadColour(usage)));
        }
        return builder.build();
    }

    static TextColor coreLoadColour(final double usage) {
        if (usage < 40.0D) {
            return NamedTextColor.GREEN;
        }
        if (usage < 60.0D) {
            return NamedTextColor.YELLOW;
        }
        if (usage < 80.0D) {
            return CORE_ORANGE;
        }
        return NamedTextColor.RED;
    }

    private void sample() {
        if (this.osBean != null) {
            final long now = System.nanoTime();
            if (now - sharedLoadSampledAt >= PROCESS_LOAD_REFRESH_NANOS || sharedLoadSampledAt == 0L) {
                try {
                    sharedProcessLoad = Math.max(0.0D, this.osBean.getProcessCpuLoad() * 100.0D);
                    sharedSystemLoad = Math.max(0.0D, this.osBean.getCpuLoad() * 100.0D);
                    sharedLoadSampledAt = now;
                } catch (final Throwable ignored) {
                    // keep the previous sample
                }
            }
            this.processLoad = sharedProcessLoad;
            this.systemLoad = sharedSystemLoad;
        }
        this.readPerCoreUsage();
    }

    private void readPerCoreUsage() {
        if (HAS_PROC_STAT) {
            this.readProcStat();
        } else if (IS_WINDOWS) {
            this.readWindowsPerCore();
        }
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
            // /proc/stat is not readable. Leave coreCount as-is; JMX load is still shown.
        }
    }

    // Windows per-core sampling. The nested holder is only ever touched on Windows, so non-Windows
    // JVMs never link ntdll and never trigger restricted native access.
    private static final class WindowsCpuTimes {
        private static final int SYSTEM_PROCESSOR_PERFORMANCE_INFORMATION = 8;
        // SYSTEM_PROCESSOR_PERFORMANCE_INFORMATION: IdleTime, KernelTime (includes idle), UserTime,
        // DpcTime, InterruptTime (five LARGE_INTEGER) plus ULONG InterruptCount, padded to 48 bytes.
        private static final long STRUCT_BYTES = 48L;
        private static final @Nullable MethodHandle NT_QUERY_SYSTEM_INFORMATION = openNtQuery();
        private static volatile boolean broken = false;

        private static @Nullable MethodHandle openNtQuery() {
            try {
                final SymbolLookup ntdll = SymbolLookup.libraryLookup("ntdll.dll", Arena.global());
                return Linker.nativeLinker().downcallHandle(
                    ntdll.find("NtQuerySystemInformation").orElseThrow(),
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS
                    )
                );
            } catch (final Throwable ignored) {
                return null;
            }
        }
    }

    private void readWindowsPerCore() {
        final MethodHandle ntQuery = WindowsCpuTimes.NT_QUERY_SYSTEM_INFORMATION;
        if (ntQuery == null || WindowsCpuTimes.broken) {
            return;
        }
        try (final Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(WindowsCpuTimes.STRUCT_BYTES * MAX_TRACKED_CORES, 8L);
            final MemorySegment returnLength = arena.allocate(ValueLayout.JAVA_INT);
            int status = (int) ntQuery.invokeExact(
                WindowsCpuTimes.SYSTEM_PROCESSOR_PERFORMANCE_INFORMATION,
                buffer,
                (int) buffer.byteSize(),
                returnLength
            );
            if (status < 0) {
                // STATUS_INFO_LENGTH_MISMATCH: the machine has more processors than we sized for
                final int needed = returnLength.get(ValueLayout.JAVA_INT, 0);
                if (needed <= WindowsCpuTimes.STRUCT_BYTES) {
                    WindowsCpuTimes.broken = true;
                    return;
                }
                buffer = arena.allocate(needed, 8L);
                status = (int) ntQuery.invokeExact(
                    WindowsCpuTimes.SYSTEM_PROCESSOR_PERFORMANCE_INFORMATION,
                    buffer,
                    needed,
                    returnLength
                );
                if (status < 0) {
                    WindowsCpuTimes.broken = true;
                    return;
                }
            }

            final int cores = (int) Math.min(
                returnLength.get(ValueLayout.JAVA_INT, 0) / WindowsCpuTimes.STRUCT_BYTES,
                (long) MAX_TRACKED_CORES
            );
            for (int i = 0; i < cores; i++) {
                final long offset = i * WindowsCpuTimes.STRUCT_BYTES;
                final long idle = buffer.get(ValueLayout.JAVA_LONG, offset);
                final long kernel = buffer.get(ValueLayout.JAVA_LONG, offset + 8L);
                final long user = buffer.get(ValueLayout.JAVA_LONG, offset + 16L);
                final long total = kernel + user;

                final long deltaTotal = total - this.prevTotal[i];
                final long deltaIdle = idle - this.prevIdle[i];
                if (deltaTotal > 0L) {
                    this.coreUsage[i] = Math.max(0.0D, Math.min(100.0D, (1.0D - (double) deltaIdle / (double) deltaTotal) * 100.0D));
                }
                this.prevTotal[i] = total;
                this.prevIdle[i] = idle;
            }
            this.coreCount = cores;
        } catch (final Throwable ignored) {
            // native access denied or query failed; keep whatever we had, JMX load is still shown
            WindowsCpuTimes.broken = true;
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
