package io.canvasmc.canvas.regionizer.loadscaling;

import io.canvasmc.canvas.regionizer.RegionizerSettings;
import io.papermc.paper.FeatureHooks;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.TickRegions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

public final class LoadScaling {

    public enum Mode {
        AUTO,
        MANUAL
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("EnigmaLoad");
    private static final Path CONFIG_PATH = Path.of("config/enigma-load.yml").toAbsolutePath().normalize();
    private static final String[] SPAWN_LIMIT_KEYS = {"monster", "animal", "ambient", "water_animal", "water_ambient"};
    private static final String[] TICKS_PER_SPAWN_KEYS = {"monster", "animal", "ambient", "water_animal", "water_ambient"};

    private static volatile Mode mode = Mode.AUTO;
    private static volatile int aggression = 100;

    private static volatile NavigableMap<Integer, WorldSettings> levels = new TreeMap<>();
    private static volatile Map<String, WorldSettings> worldOverrides = Map.of();

    private static final AtomicReference<Map<String, LastApplied>> lastAppliedByWorld = new AtomicReference<>(Map.of());

    private record LastApplied(int viewDistance, int simulationDistance, int regionizerSections) {
    }

    public static final class WorldSettings {

        private final int viewDistance;
        private final int simulationDistance;
        private final int regionizerSections;
        private final Map<String, Integer> spawnLimits;
        private final Map<String, Integer> ticksPerSpawn;

        public WorldSettings(
            final int viewDistance,
            final int simulationDistance,
            final int regionizerSections,
            final Map<String, Integer> spawnLimits,
            final Map<String, Integer> ticksPerSpawn
        ) {
            this.viewDistance = viewDistance;
            this.simulationDistance = simulationDistance;
            this.regionizerSections = regionizerSections;
            this.spawnLimits = Map.copyOf(spawnLimits);
            this.ticksPerSpawn = Map.copyOf(ticksPerSpawn);
        }

        public int viewDistance() {
            return this.viewDistance;
        }

        public int simulationDistance() {
            return this.simulationDistance;
        }

        public int regionizerSections() {
            return this.regionizerSections;
        }

        public Map<String, Integer> spawnLimits() {
            return this.spawnLimits;
        }

        public Map<String, Integer> ticksPerSpawn() {
            return this.ticksPerSpawn;
        }
    }

    private LoadScaling() {
    }

    public static Mode getMode() {
        return mode;
    }

    public static void setMode(final Mode newMode) {
        mode = newMode;
        applyToAllWorlds();
    }

    public static int getAggression() {
        return aggression;
    }

    public static void setAggression(final int newAggression) {
        aggression = Math.max(0, Math.min(100, newAggression));
        applyToAllWorlds();
    }

    public static boolean hasConfig() {
        return Files.isRegularFile(CONFIG_PATH);
    }

    public static int getOnlinePlayerCount() {
        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return 0;
        }
        return server.getPlayerList().getPlayerCount();
    }

    public static Map.Entry<Integer, WorldSettings> selectedLevel(final int playerCount) {
        final NavigableMap<Integer, WorldSettings> snapshot = levels;
        Map.Entry<Integer, WorldSettings> selected = snapshot.ceilingEntry(playerCount);
        if (selected == null) {
            selected = snapshot.isEmpty() ? null : snapshot.lastEntry();
        }
        return selected;
    }

    public static Map.Entry<Integer, WorldSettings> baseLevel(final int playerCount) {
        final NavigableMap<Integer, WorldSettings> snapshot = levels;
        final Map.Entry<Integer, WorldSettings> selected = selectedLevel(playerCount);
        if (selected == null) {
            return null;
        }
        Map.Entry<Integer, WorldSettings> base = snapshot.lowerEntry(selected.getKey());
        return base == null ? selected : base;
    }

    public static void reload() {
        if (!hasConfig()) {
            LOGGER.warn("No {} present, using built-in defaults", CONFIG_PATH.getFileName());
            levels = defaultLevels();
            worldOverrides = Map.of();
            applyToAllWorlds();
            return;
        }
        try (final java.io.Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            final Yaml yaml = new Yaml();
            final Object root = yaml.load(reader);
            final Map<String, Object> rootMap = asMap(root);
            final NavigableMap<Integer, WorldSettings> parsedLevels = new TreeMap<>();
            final Map<String, WorldSettings> parsedOverrides = new LinkedHashMap<>();

            final Object levelsNode = rootMap.get("levels");
            if (levelsNode instanceof Map<?, ?> levelMap) {
                for (final Map.Entry<?, ?> entry : levelMap.entrySet()) {
                    final int players = Integer.parseInt(String.valueOf(entry.getKey()));
                    parsedLevels.put(players, parseWorldSettings(asMap(entry.getValue()), null));
                }
            }
            final Object worldsNode = rootMap.get("worlds");
            if (worldsNode instanceof Map<?, ?> overrideMap) {
                for (final Map.Entry<?, ?> entry : overrideMap.entrySet()) {
                    parsedOverrides.put(String.valueOf(entry.getKey()), parseWorldSettings(asMap(entry.getValue()), null));
                }
            }

            levels = Collections.unmodifiableNavigableMap(parsedLevels);
            worldOverrides = Map.copyOf(parsedOverrides);
            LOGGER.info("Loaded {} load scaling level(s) from {}", parsedLevels.size(), CONFIG_PATH.getFileName());
            applyToAllWorlds();
        } catch (final IOException | RuntimeException thrown) {
            LOGGER.error("Failed to load {}, keeping previous configuration", CONFIG_PATH.getFileName(), thrown);
        }
    }

    private static WorldSettings parseWorldSettings(final Map<String, Object> values, final WorldSettings inherit) {
        final int viewDistance = inherit == null ? -1 : inherit.viewDistance;
        final int simulationDistance = inherit == null ? -1 : inherit.simulationDistance;
        final int regionizerSections = inherit == null ? -1 : inherit.regionizerSections;

        final int view = intValue(values.get("view-distance"), inherit == null ? -1 : inherit.viewDistance);
        final int sim = intValue(values.get("simulation-distance"), inherit == null ? -1 : inherit.simulationDistance);
        final int sections = intValue(values.get("regionizer-sections"), inherit == null ? -1 : inherit.regionizerSections);

        final Map<String, Integer> spawnLimits = new LinkedHashMap<>();
        final Object spawnLimitsNode = values.get("spawn-limits");
        if (spawnLimitsNode instanceof Map<?, ?> spawnLimitMap) {
            for (final String key : SPAWN_LIMIT_KEYS) {
                final Object value = spawnLimitMap.get(key);
                if (value != null) {
                    spawnLimits.put(key, ((Number) value).intValue());
                }
            }
        }
        final Map<String, Integer> ticksPerSpawn = new LinkedHashMap<>();
        final Object ticksNode = values.get("ticks-per-spawn");
        if (ticksNode instanceof Map<?, ?> ticksMap) {
            for (final String key : TICKS_PER_SPAWN_KEYS) {
                final Object value = ticksMap.get(key);
                if (value != null) {
                    ticksPerSpawn.put(key, ((Number) value).intValue());
                }
            }
        }
        return new WorldSettings(view, sim, sections, spawnLimits, ticksPerSpawn);
    }

    private static int intValue(final Object value, final int fallback) {
        if (value == null) {
            return fallback;
        }
        return ((Number) value).intValue();
    }

    private static NavigableMap<Integer, WorldSettings> defaultLevels() {
        final NavigableMap<Integer, WorldSettings> defaults = new TreeMap<>();
        defaults.put(0, new WorldSettings(12, 10, 24, Map.of(), Map.of()));
        defaults.put(10, new WorldSettings(8, 6, 14, Map.of("monster", 32), Map.of("monster", 1)));
        defaults.put(25, new WorldSettings(6, 4, 8, Map.of("monster", 16), Map.of("monster", 1)));
        defaults.put(50, new WorldSettings(5, 3, 6, Map.of("monster", 10), Map.of("monster", 1)));
        return Collections.unmodifiableNavigableMap(defaults);
    }

    public static void onPlayerJoin(final ServerLevel level) {
        applyForWorld(level);
    }

    public static void onPlayerQuit(final ServerLevel level) {
        applyForWorld(level);
    }

    public static void applyToAllWorlds() {
        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }
        for (final ServerLevel level : server.getAllLevels()) {
            applyForWorld(level);
        }
    }

    public static void applyForWorld(final ServerLevel level) {
        // Canvas start - region threading - load scaling must run on the global region thread. The spawn
        // limit / tick-per-spawn setters require the global tick threadched (they assert on
        // io.papermc.paper.threadedregions.RegionizedServer#isGlobalTickThread), and load scaling is triggered
        // from a player join/quit callback which runs on a region tick thread, so dispatch to the global region.
        if (io.papermc.paper.threadedregions.TickRegions.hasStarted()) {
            io.papermc.paper.threadedregions.RegionizedServer.getInstance().scheduleToOrExecute(() -> applyForWorldUnchecked(level));
            return;
        }
        // Canvas end - region threading
        applyForWorldUnchecked(level);
    }

    private static void applyForWorldUnchecked(final ServerLevel level) {
        final int playerCount = getOnlinePlayerCount();
        final Map.Entry<Integer, WorldSettings> selected = selectedLevel(playerCount);
        if (selected == null) {
            return;
        }
        final Map.Entry<Integer, WorldSettings> base = baseLevel(playerCount);
        final String dimensionName = level.dimension().identifier().getPath();
        final WorldSettings worldOverride = worldOverrides.get(dimensionName);
        final double factor = mode == Mode.AUTO ? 1.0D : aggression / 100.0D;

        final LastApplied last = lastAppliedByWorld.get().get(dimensionName);

        final int viewDistance = clampDistance(scaledInt(
            base.getValue().viewDistance, selected.getValue().viewDistance, factor, applyOverride(worldOverride == null ? -1 : worldOverride.viewDistance)
        ));
        final int simulationDistance = clampDistance(scaledInt(
            base.getValue().simulationDistance, selected.getValue().simulationDistance, factor, applyOverride(worldOverride == null ? -1 : worldOverride.simulationDistance)
        ));
        final int regionizerSections = scaledInt(
            base.getValue().regionizerSections, selected.getValue().regionizerSections, factor, applyOverride(worldOverride == null ? -1 : worldOverride.regionizerSections)
        );

        if (last == null || last.viewDistance != viewDistance || last.simulationDistance != simulationDistance || last.regionizerSections != regionizerSections) {
            if (viewDistance > 0) {
                FeatureHooks.setViewDistance(level, viewDistance);
            }
            if (simulationDistance > 0) {
                FeatureHooks.setSimulationDistance(level, simulationDistance);
            }

            final RegionizerSettings worldSettings = RegionizerSettings.forWorld(RegionizerSettings.get(), regionizerSections, -1.0D);
            level.enigma$regionizerSettings = worldSettings;

            applySpawnLimits(io.papermc.paper.threadedregions.RegionizedServer.getInstance(), level, selected, base, factor, worldOverride);
            applyTicksPerSpawn(level, selected, base, factor, worldOverride);

            updateLastApplied(dimensionName, new LastApplied(viewDistance, simulationDistance, regionizerSections));
        }
    }

    private static int applyOverride(final int override) {
        return override <= 0 ? -1 : override;
    }

    private static int scaledInt(final int base, final int target, final double factor, final int override) {
        if (override > 0) {
            return override;
        }
        if (target <= 0) {
            return base;
        }
        if (base <= 0) {
            return target;
        }
        return (int) Math.round(base + (factor * (target - base)));
    }

    private static int clampDistance(final int value) {
        if (value <= 0) {
            return -1;
        }
        return Math.max(2, Math.min(32, value));
    }

    private static void applySpawnLimits(
        final io.papermc.paper.threadedregions.RegionizedServer regionizedServer, final ServerLevel level,
        final Map.Entry<Integer, WorldSettings> selected,
        final Map.Entry<Integer, WorldSettings> base,
        final double factor,
        final WorldSettings worldOverride
    ) {
        final org.bukkit.World world = level.getWorld();
        if (world == null) {
            return;
        }
        for (final String key : SPAWN_LIMIT_KEYS) {
            final Integer override = worldOverride == null ? null : worldOverride.spawnLimits.get(key);
            final int target = resolveSpawnInt(selected.getValue(), key);
            final int baseValue = resolveSpawnInt(base.getValue(), key);
            final int value;
            if (override != null) {
                value = override;
            } else if (target <= 0) {
                value = baseValue;
            } else if (baseValue > 0) {
                value = (int) Math.round(baseValue + (factor * (target - baseValue)));
            } else {
                value = target;
            }
            if (value > 0) {
                switch (key) {
                    case "monster" -> world.setMonsterSpawnLimit(value);
                    case "animal" -> world.setAnimalSpawnLimit(value);
                    case "ambient" -> world.setAmbientSpawnLimit(value);
                    case "water_animal" -> world.setWaterAnimalSpawnLimit(value);
                    case "water_ambient" -> world.setWaterAmbientSpawnLimit(value);
                }
            }
        }
    }

    private static void applyTicksPerSpawn(
        final ServerLevel level,
        final Map.Entry<Integer, WorldSettings> selected,
        final Map.Entry<Integer, WorldSettings> base,
        final double factor,
        final WorldSettings worldOverride
    ) {
        final org.bukkit.World world = level.getWorld();
        if (world == null) {
            return;
        }
        for (final String key : TICKS_PER_SPAWN_KEYS) {
            final Integer override = worldOverride == null ? null : worldOverride.ticksPerSpawn.get(key);
            final int target = resolveTicksInt(selected.getValue(), key);
            final int baseValue = resolveTicksInt(base.getValue(), key);
            final int value;
            if (override != null) {
                value = override;
            } else if (target <= 0) {
                value = baseValue;
            } else if (baseValue > 0) {
                value = (int) Math.round(baseValue + (factor * (target - baseValue)));
            } else {
                value = target;
            }
            if (value > 0) {
                switch (key) {
                    case "monster" -> world.setTicksPerMonsterSpawns(value);
                    case "animal" -> world.setTicksPerAnimalSpawns(value);
                    case "ambient" -> world.setTicksPerAmbientSpawns(value);
                    case "water_animal" -> world.setTicksPerWaterSpawns(value);
                    case "water_ambient" -> world.setTicksPerWaterAmbientSpawns(value);
                }
            }
        }
    }

    private static int resolveSpawnInt(final WorldSettings settings, final String key) {
        final Integer value = settings.spawnLimits.get(key);
        return value == null ? -1 : value;
    }

    private static int resolveTicksInt(final WorldSettings settings, final String key) {
        final Integer value = settings.ticksPerSpawn.get(key);
        return value == null ? -1 : value;
    }

    private static void updateLastApplied(final String dimension, final LastApplied applied) {
        final Map<String, LastApplied> current = lastAppliedByWorld.get();
        final Map<String, LastApplied> next = new LinkedHashMap<>(current);
        next.put(dimension, applied);
        lastAppliedByWorld.set(Collections.unmodifiableMap(next));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(final Object value) {
        if (value instanceof Map<?, ?> map) {
            final Map<String, Object> ret = new LinkedHashMap<>();
            for (final Map.Entry<?, ?> entry : map.entrySet()) {
                ret.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return ret;
        }
        return Map.of();
    }

    public static @Nullable String describeCurrent(final ServerLevel level) {
        final int playerCount = getOnlinePlayerCount();
        final Map.Entry<Integer, WorldSettings> selected = selectedLevel(playerCount);
        if (selected == null) {
            return null;
        }
        final Map.Entry<Integer, WorldSettings> base = baseLevel(playerCount);
        final String dimensionName = level.dimension().identifier().getPath();
        final WorldSettings override = worldOverrides.get(dimensionName);
        final double factor = mode == Mode.AUTO ? 1.0D : aggression / 100.0D;
        final int view = clampDistance(scaledInt(
            base.getValue().viewDistance, selected.getValue().viewDistance, factor, applyOverride(override == null ? -1 : override.viewDistance)
        ));
        final int sim = clampDistance(scaledInt(
            base.getValue().simulationDistance, selected.getValue().simulationDistance, factor, applyOverride(override == null ? -1 : override.simulationDistance)
        ));
        final int sections = scaledInt(
            base.getValue().regionizerSections, selected.getValue().regionizerSections, factor, applyOverride(override == null ? -1 : override.regionizerSections)
        );
        return "Level " + selected.getKey() + " players, view=" + view + ", sim=" + sim + ", regionizerSections=" + sections;
    }
}