package io.enigma.perf;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public class EnigmaPerformanceBar extends JavaPlugin implements Listener, CommandExecutor {

    private BossBar tpsBossBar;
    private BossBar cpuBossBar;
    private final ConcurrentHashMap<Player, Boolean> enabledPlayers = new ConcurrentHashMap<>();
    private OperatingSystemMXBean osBean;

    // Previous CPU counters for per-core calculations from /proc/stat
    private long[] prevTotal = new long[7];
    private long[] prevIdle = new long[7];
    private double[] perCoreUsage = new double[7];

    @Override
    public void onEnable() {
        try {
            osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        } catch (Throwable t) {
            getLogger().warning("Failed to initialize OperatingSystemMXBean: " + t.getMessage());
        }

        // Initialize BossBars
        tpsBossBar = BossBar.bossBar(
                Component.text("Initializing EnigmaEngine Performance Bar...", NamedTextColor.AQUA),
                1.0f,
                BossBar.Color.BLUE,
                BossBar.Overlay.PROGRESS
        );

        cpuBossBar = BossBar.bossBar(
                Component.text("Initializing CPU Multicore Bar...", NamedTextColor.GREEN),
                0.0f,
                BossBar.Color.GREEN,
                BossBar.Overlay.PROGRESS
        );

        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("perfbar") != null) {
            getCommand("perfbar").setExecutor(this);
        }

        // Use Folia Global Region Scheduler to tick the performance bar every second (20 ticks)
        try {
            getServer().getGlobalRegionScheduler().runAtFixedRate(this, task -> updateBars(), 20L, 20L);
        } catch (Throwable t) {
            // Fallback for non-Folia environments
            Bukkit.getScheduler().runTaskTimer(this, this::updateBars, 20L, 20L);
        }

        getLogger().info("EnigmaPerformanceBar enabled: Dual TPS & 7-Core CPU BossBars active!");
    }

    @Override
    public void onDisable() {
        if (tpsBossBar != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                tpsBossBar.removeViewer(p);
                cpuBossBar.removeViewer(p);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        enabledPlayers.put(player, true);
        tpsBossBar.addViewer(player);
        cpuBossBar.addViewer(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        enabledPlayers.remove(player);
        tpsBossBar.removeViewer(player);
        cpuBossBar.removeViewer(player);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player) {
            boolean current = enabledPlayers.getOrDefault(player, true);
            boolean newState = !current;
            enabledPlayers.put(player, newState);
            if (newState) {
                tpsBossBar.addViewer(player);
                cpuBossBar.addViewer(player);
                player.sendMessage(Component.text("EnigmaEngine Performance Bars: ENABLED", NamedTextColor.GREEN));
            } else {
                tpsBossBar.removeViewer(player);
                cpuBossBar.removeViewer(player);
                player.sendMessage(Component.text("EnigmaEngine Performance Bars: DISABLED", NamedTextColor.RED));
            }
            return true;
        }
        sender.sendMessage("This command can only be used by players.");
        return true;
    }

    private void updateBars() {
        // Read CPU per-core stats
        readProcStat();

        // 1. Gather Metrics
        double tps = 20.0;
        try {
            double[] tpsArr = Bukkit.getTPS();
            if (tpsArr != null && tpsArr.length > 0) {
                tps = Math.min(20.0, Math.max(0.0, tpsArr[0]));
            }
        } catch (Throwable ignored) {}

        double mspt = 0.5;
        try {
            mspt = Bukkit.getAverageTickTime();
        } catch (Throwable ignored) {}

        double processCpu = 0.0;
        double systemCpu = 0.0;
        if (osBean != null) {
            processCpu = Math.max(0.0, osBean.getProcessCpuLoad() * 100.0);
            systemCpu = Math.max(0.0, osBean.getCpuLoad() * 100.0);
        }

        Runtime runtime = Runtime.getRuntime();
        long usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMemMb = runtime.maxMemory() / (1024 * 1024);

        int playerCount = Bukkit.getOnlinePlayers().size();

        // 2. Build TPS BossBar Component
        NamedTextColor tpsColor = tps >= 19.5 ? NamedTextColor.GREEN : (tps >= 15.0 ? NamedTextColor.YELLOW : NamedTextColor.RED);
        BossBar.Color barTpsColor = tps >= 19.5 ? BossBar.Color.GREEN : (tps >= 15.0 ? BossBar.Color.YELLOW : BossBar.Color.RED);

        Component tpsText = Component.text()
                .append(Component.text("⚡ TPS: ", NamedTextColor.AQUA, TextDecoration.BOLD))
                .append(Component.text(String.format(Locale.US, "%.2f", tps), tpsColor, TextDecoration.BOLD))
                .append(Component.text("  |  MSPT: ", NamedTextColor.GRAY))
                .append(Component.text(String.format(Locale.US, "%.1f ms", mspt), NamedTextColor.WHITE))
                .append(Component.text("  |  CPU: ", NamedTextColor.GRAY))
                .append(Component.text(String.format(Locale.US, "%.1f%%", processCpu), NamedTextColor.GOLD))
                .append(Component.text("  |  Players: ", NamedTextColor.GRAY))
                .append(Component.text(playerCount + "/150", NamedTextColor.YELLOW))
                .build();

        float tpsProgress = (float) Math.min(1.0, Math.max(0.0, tps / 20.0));
        tpsBossBar.name(tpsText);
        tpsBossBar.progress(tpsProgress);
        tpsBossBar.color(barTpsColor);

        // 3. Build CPU 7-Core BossBar Component
        StringBuilder coresStr = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            coresStr.append(String.format(Locale.US, "C%d:%.0f%% ", i, perCoreUsage[i]));
        }

        NamedTextColor cpuColor = processCpu <= 60.0 ? NamedTextColor.GREEN : (processCpu <= 85.0 ? NamedTextColor.YELLOW : NamedTextColor.RED);
        BossBar.Color barCpuColor = processCpu <= 60.0 ? BossBar.Color.GREEN : (processCpu <= 85.0 ? BossBar.Color.YELLOW : BossBar.Color.RED);

        Component cpuText = Component.text()
                .append(Component.text("⚙ Cores [0-6]: ", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD))
                .append(Component.text(coresStr.toString().trim(), NamedTextColor.WHITE))
                .append(Component.text("  |  RAM: ", NamedTextColor.GRAY))
                .append(Component.text(String.format(Locale.US, "%.1fG/%.1fG", usedMemMb / 1024.0, maxMemMb / 1024.0), NamedTextColor.AQUA))
                .build();

        float cpuProgress = (float) Math.min(1.0, Math.max(0.0, processCpu / 700.0));
        cpuBossBar.name(cpuText);
        cpuBossBar.progress(cpuProgress);
        cpuBossBar.color(barCpuColor);
    }

    private void readProcStat() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("cpu")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 5 && parts[0].matches("cpu[0-6]")) {
                        int coreIdx = Integer.parseInt(parts[0].substring(3));
                        if (coreIdx < 7) {
                            long user = Long.parseLong(parts[1]);
                            long nice = Long.parseLong(parts[2]);
                            long sys = Long.parseLong(parts[3]);
                            long idle = Long.parseLong(parts[4]);
                            long total = user + nice + sys + idle;

                            long deltaTotal = total - prevTotal[coreIdx];
                            long deltaIdle = idle - prevIdle[coreIdx];

                            if (deltaTotal > 0) {
                                perCoreUsage[coreIdx] = Math.max(0.0, Math.min(100.0, (1.0 - (double) deltaIdle / deltaTotal) * 100.0));
                            }
                            prevTotal[coreIdx] = total;
                            prevIdle[coreIdx] = idle;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }
}
