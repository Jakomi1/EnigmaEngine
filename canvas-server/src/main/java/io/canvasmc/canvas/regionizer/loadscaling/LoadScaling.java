package io.canvasmc.canvas.regionizer.loadscaling;

import io.canvasmc.canvas.regionizer.RegionizerSettings;
import io.canvasmc.canvas.regionizer.RegionizerMode;
import io.papermc.paper.FeatureHooks;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.TickRegions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    private static final Logger LOGGER = LoggerFactory.getLogger("EnigmaLoad");
    private static final Path CONFIG_PATH = Path.of("config/enigma-regionizer.yml").toAbsolutePath().normalize();
    public static final String[] SPAWN_LIMIT_KEYS = {"monster", "animal", "ambient", "water_animal", "water_ambient"};
    public static final String[] TICKS_PER_SPAWN_KEYS = {"monster", "animal", "ambient", "water_animal", "water_ambient"};

    // How long the online player count must remain stable before the selected level is applied.
    // This debounces the dynamic distance changer so a single player spamming join/quit cannot
    // cause the view/simulation distance to oscillate and repeatedly reload chunks.
    public static final int DEFAULT_PLAYER_COUNT_BUFFER_TICKS = 100;

    private static final String HEADER = """
        # Enigma regionizer adaptive load scaling.
        #
        # This file is the persistent store for the /enigma regionizer commands. It is read at
        # startup and on "/enigma reload", and every value changed through a command is written
        # back here automatically, so the file always mirrors the live configuration. It may be
        # edited by hand as well; the values are picked up on the next reload.
        #
        #   regionizer-mode: auto | folia. AUTO is Enigma's adaptive regionizer; FOLIA
        #                    leaves region decisions to Folia without restarting.
        #   aggression: 0-100
        #   regionizer:  all global Enigma regionizer limits. These were migrated from
        #                canvas-server.yml so this is the only persistent file for
        #                Enigma's adaptive regionizer. Use
        #                "/enigma regionizer setting <key> <value>" at runtime.
        #   levels:     thresholds keyed by online player count. The entry with the smallest key
        #               that is >= the current player count is applied. A view-distance,
        #               simulation-distance or regionizer-sections of 0 (or less) means "leave
        #               that value unchanged". This is the table adjusted via
        #               "/enigma regionizer level <players> ...".
        #   worlds:     per-dimension overrides keyed by the dimension path (e.g. world,
        #               world_nether, world_the_end). Adjusted via
        #               "/enigma regionizer world <dimension> ...".
        #
        #   player-count-buffer-ticks:
        #               The number of ticks the online player count must remain stable before
        #               the selected level is (re)applied to the worlds. This buffers the
        #               dynamic view/simulation distance and spawn scaling so that a player
        #               rapidly joining and leaving cannot thrash chunk loading (which would
        #               otherwise make the server unplayable). Set to 0 to apply immediately
        #               and disable the buffer.
        #
        # spawn-limit / ticks-per-spawn keys:
        #   monster, animal, ambient, water_animal, water_ambient
        """;


    private static volatile int aggression = 100;
    private static volatile int playerCountBufferTicks = DEFAULT_PLAYER_COUNT_BUFFER_TICKS;

    // Debounce state for the player-count-driven load scaling. A value of -1 means no
    // re-evaluation is pending.
    private static volatile int pendingPlayerCount = -1;
    private static volatile long pendingStableTicks = 0;

    private static volatile NavigableMap<Integer, WorldSettings> levels = new TreeMap<>();
    private static volatile Map<String, WorldSettings> worldOverrides = Map.of();

    private static final AtomicReference<Map<String, LastApplied>> lastAppliedByWorld = new AtomicReference<>(Map.of());

    private record LastApplied(int viewDistance, int simulationDistance, int regionizerSections, int aggression) {
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

        public WorldSettings withViewDistance(final int value) {
            return new WorldSettings(value, this.simulationDistance, this.regionizerSections, this.spawnLimits, this.ticksPerSpawn);
        }

        public WorldSettings withSimulationDistance(final int value) {
            return new WorldSettings(this.viewDistance, value, this.regionizerSections, this.spawnLimits, this.ticksPerSpawn);
        }

        public WorldSettings withRegionizerSections(final int value) {
            return new WorldSettings(this.viewDistance, this.simulationDistance, value, this.spawnLimits, this.ticksPerSpawn);
        }

        public WorldSettings withSpawnLimit(final String key, final int value) {
            final Map<String, Integer> next = new LinkedHashMap<>(this.spawnLimits);
            if (value <= 0) {
                next.remove(key);
            } else {
                next.put(key, value);
            }
            return new WorldSettings(this.viewDistance, this.simulationDistance, this.regionizerSections, next, this.ticksPerSpawn);
        }

        public WorldSettings withTicksPerSpawn(final String key, final int value) {
            final Map<String, Integer> next = new LinkedHashMap<>(this.ticksPerSpawn);
            if (value <= 0) {
                next.remove(key);
            } else {
                next.put(key, value);
            }
            return new WorldSettings(this.viewDistance, this.simulationDistance, this.regionizerSections, this.spawnLimits, next);
        }
    }

    private static final WorldSettings EMPTY = new WorldSettings(-1, -1, -1, Map.of(), Map.of());

    private LoadScaling() {
    }

    public static int getAggression() {
        return aggression;
    }

    /** Changes and persists the explicit AUTO/FOLIA operational mode. */
    public static void setRegionizerMode(final RegionizerMode.Mode mode) {
        RegionizerMode.set(mode);
        save();
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
            playerCountBufferTicks = DEFAULT_PLAYER_COUNT_BUFFER_TICKS;
            // RegionizerSettings already contains the canvas-server.yml compatibility defaults.
            // Persist them here immediately so future restarts no longer depend on that file.
            // Seed the file so the command-managed store exists from the start.
            save();
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

            final boolean migrateRegionizer = !rootMap.containsKey("regionizer");
            final RegionizerMode.Mode parsedMode = parseRegionizerMode(rootMap.get("regionizer-mode"));
            if (parsedMode != null) {
                RegionizerMode.set(parsedMode);
            }
            final Map<String, Object> regionizerNode = asMap(rootMap.get("regionizer"));
            RegionizerSettings.set(parseRegionizerSettings(regionizerNode, RegionizerSettings.get()));
            final Object aggressionNode = rootMap.get("aggression");
            if (aggressionNode instanceof Number number) {
                aggression = Math.max(0, Math.min(100, number.intValue()));
            }
            final Object bufferNode = rootMap.get("player-count-buffer-ticks");
            if (bufferNode instanceof Number number) {
                playerCountBufferTicks = Math.max(0, number.intValue());
            }

            levels = Collections.unmodifiableNavigableMap(parsedLevels);
            worldOverrides = Map.copyOf(parsedOverrides);
            LOGGER.info("Loaded {} load scaling level(s) from {}", parsedLevels.size(), CONFIG_PATH.getFileName());
            applyToAllWorlds();
            if (migrateRegionizer) {
                LOGGER.info("Migrated Enigma regionizer settings into {}", CONFIG_PATH.getFileName());
                save();
            }
        } catch (final IOException | RuntimeException thrown) {
            LOGGER.error("Failed to load {}, keeping previous configuration", CONFIG_PATH.getFileName(), thrown);
        }
    }

    private static RegionizerMode.@Nullable Mode parseRegionizerMode(final Object value) {
        if (value == null) {
            return null;
        }
        try {
            return RegionizerMode.Mode.valueOf(String.valueOf(value).trim().toUpperCase(java.util.Locale.ROOT));
        } catch (final IllegalArgumentException ignored) {
            LOGGER.warn("Ignoring invalid mode \"{}\" in {}", value, CONFIG_PATH.getFileName());
            return null;
        }
    }

    /**
     * Writes the current configuration (regionizer settings, aggression, levels and world overrides) back to
     * {@code config/enigma-regionizer.yml}. Called by every command that mutates the state so the
     * file always reflects the live configuration.
     */
    public static void save() {
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("regionizer-mode", RegionizerMode.get().name().toLowerCase(java.util.Locale.ROOT));
        root.put("aggression", aggression);
        root.put("player-count-buffer-ticks", playerCountBufferTicks);
        root.put("regionizer", serialiseRegionizer(RegionizerSettings.get()));

        final Map<String, Object> levelsNode = new LinkedHashMap<>();
        for (final Map.Entry<Integer, WorldSettings> entry : levels.entrySet()) {
            levelsNode.put(String.valueOf(entry.getKey()), serialise(entry.getValue()));
        }
        root.put("levels", levelsNode);

        final Map<String, Object> worldsNode = new LinkedHashMap<>();
        for (final Map.Entry<String, WorldSettings> entry : worldOverrides.entrySet()) {
            worldsNode.put(entry.getKey(), serialise(entry.getValue()));
        }
        root.put("worlds", worldsNode);

        try {
            final Path parent = CONFIG_PATH.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(CONFIG_PATH, HEADER + new Yaml().dump(root), StandardCharsets.UTF_8);
        } catch (final IOException thrown) {
            LOGGER.error("Failed to save {}", CONFIG_PATH.getFileName(), thrown);
        }
    }

    private static Map<String, Object> serialise(final WorldSettings settings) {
        final Map<String, Object> node = new LinkedHashMap<>();
        node.put("view-distance", settings.viewDistance);
        node.put("simulation-distance", settings.simulationDistance);
        node.put("regionizer-sections", settings.regionizerSections);
        if (!settings.spawnLimits.isEmpty()) {
            node.put("spawn-limits", new LinkedHashMap<>(settings.spawnLimits));
        }
        if (!settings.ticksPerSpawn.isEmpty()) {
            node.put("ticks-per-spawn", new LinkedHashMap<>(settings.ticksPerSpawn));
        }
        return node;
    }

    /** All global regionizer keys accepted by {@code /enigma regionizer setting}. */
    public static final String[] REGIONIZER_SETTING_KEYS = {
        "enabled", "merge-overlap-threshold", "merge-min-region-sections", "coupling-weight", "overlap-weight",
        "merge-cooldown-millis", "split-cooldown-millis", "split-min-size", "split-max-sections",
        "split-high-load-mspt", "split-low-load-percent", "split-idle-seconds", "split-pass-interval-ticks",
        "instant-unload-enabled", "instant-unload-active-regions", "instant-unload-idle-radius",
        "instant-unload-max-chunks-per-pass", "partition-enabled", "split-max-regions", "partition-min-sections",
        "partition-slack", "partition-refinement-passes", "player-count-buffer-ticks"
    };

    /**
     * Replaces one global regionizer limit, reapplies it to every loaded world and persists the
     * complete state. {@code enabled} intentionally only accepts {@code true}: AUTO is forced on
     * by design and FOLIA mode is the explicit runtime opt-out.
     *
     * @return the normalised stored value
     * @throws IllegalArgumentException if the key or value is invalid
     */
    public static String setRegionizerSetting(final String key, final String rawValue) {
        final String normalisedKey = key.toLowerCase(java.util.Locale.ROOT);

        // The player-count debounce lives beside the regionizer limits in the same file but is not
        // part of RegionizerSettings, so it is handled before the regionizer snapshot is rebuilt.
        if (normalisedKey.equals("player-count-buffer-ticks")) {
            final int ticks;
            try {
                ticks = Integer.parseInt(rawValue.trim());
            } catch (final NumberFormatException thrown) {
                throw new IllegalArgumentException("\"" + rawValue + "\" is not a valid value for player-count-buffer-ticks");
            }
            if (ticks < 0) {
                throw new IllegalArgumentException("player-count-buffer-ticks cannot be negative");
            }
            setPlayerCountBufferTicks(ticks);
            return String.valueOf(ticks);
        }

        final RegionizerSettings current = RegionizerSettings.get();

        final double mergeOverlapThreshold;
        final int mergeMinRegionSections;
        final double couplingWeight;
        final double overlapWeight;
        final long mergeCooldownMillis;
        final long splitCooldownMillis;
        final int splitMinSize;
        final int splitMaxSections;
        final double splitHighLoadMspt;
        final int splitLowLoadPercent;
        final int splitIdleSeconds;
        final int splitPassIntervalTicks;
        final boolean instantUnloadEnabled;
        final boolean instantUnloadActiveRegions;
        final int instantUnloadIdleRadius;
        final int instantUnloadMaxChunksPerPass;
        final boolean partitionEnabled;
        final int splitMaxRegions;
        final int partitionMinSections;
        final double partitionSlack;
        final int partitionRefinementPasses;

        try {
            mergeOverlapThreshold = normalisedKey.equals("merge-overlap-threshold") ? parseDouble(rawValue, normalisedKey) : current.mergeOverlapThreshold();
            mergeMinRegionSections = normalisedKey.equals("merge-min-region-sections") ? parseInt(rawValue, normalisedKey) : current.mergeMinRegionSections();
            couplingWeight = normalisedKey.equals("coupling-weight") ? parseDouble(rawValue, normalisedKey) : current.couplingWeight();
            overlapWeight = normalisedKey.equals("overlap-weight") ? parseDouble(rawValue, normalisedKey) : current.overlapWeight();
            mergeCooldownMillis = normalisedKey.equals("merge-cooldown-millis") ? parseLong(rawValue, normalisedKey) : current.mergeCooldownMillis();
            splitCooldownMillis = normalisedKey.equals("split-cooldown-millis") ? parseLong(rawValue, normalisedKey) : current.splitCooldownMillis();
            splitMinSize = normalisedKey.equals("split-min-size") ? parseInt(rawValue, normalisedKey) : current.splitMinSize();
            splitMaxSections = normalisedKey.equals("split-max-sections") ? parseInt(rawValue, normalisedKey) : current.splitMaxSections();
            splitHighLoadMspt = normalisedKey.equals("split-high-load-mspt") ? parseDouble(rawValue, normalisedKey) : current.splitHighLoadMspt();
            splitLowLoadPercent = normalisedKey.equals("split-low-load-percent") ? parseInt(rawValue, normalisedKey) : current.splitLowLoadPercent();
            splitIdleSeconds = normalisedKey.equals("split-idle-seconds") ? parseInt(rawValue, normalisedKey) : current.splitIdleSeconds();
            splitPassIntervalTicks = normalisedKey.equals("split-pass-interval-ticks") ? parseInt(rawValue, normalisedKey) : current.splitPassIntervalTicks();
            instantUnloadEnabled = normalisedKey.equals("instant-unload-enabled") ? parseBoolean(rawValue, normalisedKey) : current.instantUnloadEnabled();
            instantUnloadActiveRegions = normalisedKey.equals("instant-unload-active-regions") ? parseBoolean(rawValue, normalisedKey) : current.instantUnloadActiveRegions();
            instantUnloadIdleRadius = normalisedKey.equals("instant-unload-idle-radius") ? parseInt(rawValue, normalisedKey) : current.instantUnloadIdleRadius();
            instantUnloadMaxChunksPerPass = normalisedKey.equals("instant-unload-max-chunks-per-pass") ? parseInt(rawValue, normalisedKey) : current.instantUnloadMaxChunksPerPass();
            partitionEnabled = normalisedKey.equals("partition-enabled") ? parseBoolean(rawValue, normalisedKey) : current.partitionEnabled();
            splitMaxRegions = normalisedKey.equals("split-max-regions") ? parseInt(rawValue, normalisedKey) : current.splitMaxRegions();
            partitionMinSections = normalisedKey.equals("partition-min-sections") ? parseInt(rawValue, normalisedKey) : current.partitionMinSections();
            partitionSlack = normalisedKey.equals("partition-slack") ? parseDouble(rawValue, normalisedKey) : current.partitionSlack();
            partitionRefinementPasses = normalisedKey.equals("partition-refinement-passes") ? parseInt(rawValue, normalisedKey) : current.partitionRefinementPasses();
        } catch (final NumberFormatException thrown) {
            throw new IllegalArgumentException("\"" + rawValue + "\" is not a valid value for " + normalisedKey);
        }

        if (normalisedKey.equals("enabled") && !parseBoolean(rawValue, normalisedKey)) {
            throw new IllegalArgumentException("enabled is always true; use /enigma regionizer mode folia to suspend Enigma decisions");
        }
        boolean known = false;
        for (final String candidate : REGIONIZER_SETTING_KEYS) {
            if (candidate.equals(normalisedKey)) {
                known = true;
                break;
            }
        }
        if (!known) {
            throw new IllegalArgumentException("unknown setting \"" + key + "\"");
        }
        if (mergeMinRegionSections < 1 || splitMinSize < 2 || splitMaxSections < splitMinSize || splitMaxRegions < 2
            || partitionMinSections < 1 || mergeCooldownMillis < 0 || splitCooldownMillis < 0 || splitPassIntervalTicks < 1
            || splitHighLoadMspt <= 0.0D || mergeOverlapThreshold < 0.0D || mergeOverlapThreshold > 1.0D
            || couplingWeight < 0.0D || overlapWeight < 0.0D || partitionSlack < 0.0D || partitionSlack > 1.0D) {
            throw new IllegalArgumentException("value is outside the safe range for " + normalisedKey);
        }

        final RegionizerSettings updated = new RegionizerSettings(
            true, mergeOverlapThreshold, mergeMinRegionSections, couplingWeight, overlapWeight,
            mergeCooldownMillis, splitCooldownMillis, splitMinSize, splitMaxSections, splitHighLoadMspt,
            splitLowLoadPercent, splitIdleSeconds, splitPassIntervalTicks, instantUnloadEnabled,
            instantUnloadActiveRegions, instantUnloadIdleRadius, instantUnloadMaxChunksPerPass, 100,
            splitMaxSections, partitionEnabled, splitMaxRegions, partitionMinSections, partitionSlack,
            partitionRefinementPasses
        );
        RegionizerSettings.set(updated);
        lastAppliedByWorld.set(Map.of());
        applyToAllWorlds();
        save();
        return String.valueOf(serialiseRegionizer(updated).get(normalisedKey));
    }

    /** Immutable view used by the command's {@code settings} status page. */
    public static Map<String, Object> regionizerSettingsSnapshot() {
        final Map<String, Object> snapshot = new LinkedHashMap<>(serialiseRegionizer(RegionizerSettings.get()));
        snapshot.put("player-count-buffer-ticks", playerCountBufferTicks);
        return Map.copyOf(snapshot);
    }

    private static Map<String, Object> serialiseRegionizer(final RegionizerSettings settings) {
        final Map<String, Object> node = new LinkedHashMap<>();
        node.put("enabled", true);
        node.put("merge-overlap-threshold", settings.mergeOverlapThreshold());
        node.put("merge-min-region-sections", settings.mergeMinRegionSections());
        node.put("coupling-weight", settings.couplingWeight());
        node.put("overlap-weight", settings.overlapWeight());
        node.put("merge-cooldown-millis", settings.mergeCooldownMillis());
        node.put("split-cooldown-millis", settings.splitCooldownMillis());
        node.put("split-min-size", settings.splitMinSize());
        node.put("split-max-sections", settings.splitMaxSections());
        node.put("split-high-load-mspt", settings.splitHighLoadMspt());
        node.put("split-low-load-percent", settings.splitLowLoadPercent());
        node.put("split-idle-seconds", settings.splitIdleSeconds());
        node.put("split-pass-interval-ticks", settings.splitPassIntervalTicks());
        node.put("instant-unload-enabled", settings.instantUnloadEnabled());
        node.put("instant-unload-active-regions", settings.instantUnloadActiveRegions());
        node.put("instant-unload-idle-radius", settings.instantUnloadIdleRadius());
        node.put("instant-unload-max-chunks-per-pass", settings.instantUnloadMaxChunksPerPass());
        node.put("partition-enabled", settings.partitionEnabled());
        node.put("split-max-regions", settings.splitMaxRegions());
        node.put("partition-min-sections", settings.partitionMinSections());
        node.put("partition-slack", settings.partitionSlack());
        node.put("partition-refinement-passes", settings.partitionRefinementPasses());
        return node;
    }

    private static RegionizerSettings parseRegionizerSettings(final Map<String, Object> node, final RegionizerSettings fallback) {
        return new RegionizerSettings(
            true,
            doubleValue(node.get("merge-overlap-threshold"), fallback.mergeOverlapThreshold()),
            intValue(node.get("merge-min-region-sections"), fallback.mergeMinRegionSections()),
            doubleValue(node.get("coupling-weight"), fallback.couplingWeight()),
            doubleValue(node.get("overlap-weight"), fallback.overlapWeight()),
            longValue(node.get("merge-cooldown-millis"), fallback.mergeCooldownMillis()),
            longValue(node.get("split-cooldown-millis"), fallback.splitCooldownMillis()),
            intValue(node.get("split-min-size"), fallback.splitMinSize()),
            intValue(node.get("split-max-sections"), fallback.splitMaxSections()),
            doubleValue(node.get("split-high-load-mspt"), fallback.splitHighLoadMspt()),
            intValue(node.get("split-low-load-percent"), fallback.splitLowLoadPercent()),
            intValue(node.get("split-idle-seconds"), fallback.splitIdleSeconds()),
            intValue(node.get("split-pass-interval-ticks"), fallback.splitPassIntervalTicks()),
            booleanValue(node.get("instant-unload-enabled"), fallback.instantUnloadEnabled()),
            booleanValue(node.get("instant-unload-active-regions"), fallback.instantUnloadActiveRegions()),
            intValue(node.get("instant-unload-idle-radius"), fallback.instantUnloadIdleRadius()),
            intValue(node.get("instant-unload-max-chunks-per-pass"), fallback.instantUnloadMaxChunksPerPass()),
            100,
            intValue(node.get("split-max-sections"), fallback.splitMaxSections()),
            booleanValue(node.get("partition-enabled"), fallback.partitionEnabled()),
            intValue(node.get("split-max-regions"), fallback.splitMaxRegions()),
            intValue(node.get("partition-min-sections"), fallback.partitionMinSections()),
            doubleValue(node.get("partition-slack"), fallback.partitionSlack()),
            intValue(node.get("partition-refinement-passes"), fallback.partitionRefinementPasses())
        );
    }

    private static int parseInt(final String value, final String key) {
        return Integer.parseInt(value);
    }

    private static long parseLong(final String value, final String key) {
        return Long.parseLong(value);
    }

    private static double parseDouble(final String value, final String key) {
        return Double.parseDouble(value);
    }

    private static boolean parseBoolean(final String value, final String key) {
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }
        throw new IllegalArgumentException("\"" + value + "\" is not a boolean for " + key);
    }

    public static @Nullable WorldSettings levelSettings(final int players) {
        return levels.get(players);
    }

    public static @Nullable WorldSettings worldSettings(final String dimension) {
        return worldOverrides.get(dimension);
    }

    public static java.util.Set<Integer> levelKeys() {
        return levels.keySet();
    }

    public static java.util.Set<String> worldKeys() {
        return worldOverrides.keySet();
    }

    /**
     * Replaces (or creates) the settings of the level keyed by {@code players} and persists it.
     */
    public static void setLevel(final int players, final WorldSettings settings) {
        final NavigableMap<Integer, WorldSettings> next = new TreeMap<>(levels);
        next.put(players, settings);
        levels = Collections.unmodifiableNavigableMap(next);
        applyToAllWorlds();
        save();
    }

    /**
     * Replaces (or creates) the per-dimension override and persists it.
     */
    public static void setWorld(final String dimension, final WorldSettings settings) {
        final Map<String, WorldSettings> next = new LinkedHashMap<>(worldOverrides);
        next.put(dimension, settings);
        worldOverrides = Collections.unmodifiableMap(next);
        applyToAllWorlds();
        save();
    }

    public static boolean removeLevel(final int players) {
        if (!levels.containsKey(players)) {
            return false;
        }
        final NavigableMap<Integer, WorldSettings> next = new TreeMap<>(levels);
        next.remove(players);
        levels = Collections.unmodifiableNavigableMap(next);
        applyToAllWorlds();
        save();
        return true;
    }

    public static boolean removeWorld(final String dimension) {
        if (!worldOverrides.containsKey(dimension)) {
            return false;
        }
        final Map<String, WorldSettings> next = new LinkedHashMap<>(worldOverrides);
        next.remove(dimension);
        worldOverrides = Collections.unmodifiableMap(next);
        applyToAllWorlds();
        save();
        return true;
    }

    public static WorldSettings emptySettings() {
        return EMPTY;
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

    private static long longValue(final Object value, final long fallback) {
        if (value == null) {
            return fallback;
        }
        return ((Number) value).longValue();
    }

    private static double doubleValue(final Object value, final double fallback) {
        if (value == null) {
            return fallback;
        }
        return ((Number) value).doubleValue();
    }

    private static boolean booleanValue(final Object value, final boolean fallback) {
        return value instanceof Boolean bool ? bool : fallback;
    }

    private static NavigableMap<Integer, WorldSettings> defaultLevels() {
        final NavigableMap<Integer, WorldSettings> defaults = new TreeMap<>();
        defaults.put(0, new WorldSettings(8, 6, 24, Map.of("monster", 88, "animal", 13, "water_animal", 6), Map.of("monster", 1, "animal", 1, "water_animal", 1)));
        defaults.put(5, new WorldSettings(8, 5, 23, Map.of("monster", 70, "animal", 10, "water_animal", 6), Map.of("monster", 1, "animal", 1, "water_animal", 1)));
        defaults.put(10, new WorldSettings(8, 5, 22, Map.of("monster", 58, "animal", 9, "water_animal", 5), Map.of("monster", 2, "animal", 2, "water_animal", 2)));
        defaults.put(15, new WorldSettings(7, 4, 20, Map.of("monster", 48, "animal", 8, "water_animal", 4), Map.of("monster", 2, "animal", 2, "water_animal", 2)));
        defaults.put(20, new WorldSettings(7, 4, 19, Map.of("monster", 39, "animal", 6, "water_animal", 4), Map.of("monster", 2, "animal", 2, "water_animal", 2)));
        defaults.put(25, new WorldSettings(6, 4, 17, Map.of("monster", 31, "animal", 5, "water_animal", 3), Map.of("monster", 2, "animal", 2, "water_animal", 2)));
        defaults.put(30, new WorldSettings(6, 3, 16, Map.of("monster", 24, "animal", 4, "water_animal", 3), Map.of("monster", 3, "animal", 3, "water_animal", 3)));
        defaults.put(35, new WorldSettings(6, 3, 15, Map.of("monster", 19, "animal", 4, "water_animal", 3), Map.of("monster", 3, "animal", 3, "water_animal", 3)));
        defaults.put(40, new WorldSettings(5, 3, 14, Map.of("monster", 15, "animal", 3, "water_animal", 1), Map.of("monster", 4, "animal", 4, "water_animal", 4)));
        defaults.put(45, new WorldSettings(5, 3, 13, Map.of("monster", 11, "animal", 3, "water_animal", 1), Map.of("monster", 4, "animal", 4, "water_animal", 4)));
        defaults.put(50, new WorldSettings(5, 2, 12, Map.of("monster", 9, "animal", 1, "water_animal", 1), Map.of("monster", 5, "animal", 5, "water_animal", 5)));
        defaults.put(60, new WorldSettings(4, 2, 11, Map.of("monster", 5, "animal", 1, "water_animal", 1), Map.of("monster", 7, "animal", 7, "water_animal", 7)));
        defaults.put(75, new WorldSettings(4, 2, 10, Map.of("monster", 4, "animal", 1, "water_animal", 1), Map.of("monster", 8, "animal", 8, "water_animal", 8)));
        defaults.put(100, new WorldSettings(4, 2, 8, Map.of("monster", 3, "animal", 1, "water_animal", 1), Map.of("monster", 9, "animal", 9, "water_animal", 9)));
        defaults.put(150, new WorldSettings(4, 2, 6, Map.of("monster", 2, "animal", 1, "water_animal", 1), Map.of("monster", 10, "animal", 10, "water_animal", 10)));
        return Collections.unmodifiableNavigableMap(defaults);
    }

    public static int getPlayerCountBufferTicks() {
        return playerCountBufferTicks;
    }

    public static void setPlayerCountBufferTicks(final int ticks) {
        playerCountBufferTicks = Math.max(0, ticks);
        // drain any pending re-evaluation immediately, the caller changed the buffer policy
        pendingPlayerCount = -1;
        pendingStableTicks = 0;
        applyToAllWorlds();
        save();
    }

    /**
     * Buffered replacement for applying load scaling directly from a join. Instead of applying
     * immediately on every join/quit (which lets a single player spamming join/leave oscillate the
     * view/simulation distance and thrash chunk loading), we remember the new player count and wait
     * for it to be stable for {@code player-count-buffer-ticks} before applying. See {@link #tick()}.
     */
    public static void onPlayerJoin(final ServerLevel level) {
        schedulePlayerCountReevaluation();
    }

    public static void onPlayerQuit(final ServerLevel level) {
        schedulePlayerCountReevaluation();
    }

    private static void schedulePlayerCountReevaluation() {
        if (playerCountBufferTicks <= 0) {
            // buffering disabled, preserve the legacy immediate behavior
            applyToAllWorlds();
            return;
        }
        // Record the new count and restart the stability window. A player that keeps joining and
        // leaving keeps resetting this, so nothing is ever applied until the count settles.
        pendingPlayerCount = getOnlinePlayerCount();
        pendingStableTicks = 0;
    }

    /**
     * Advances the player-count debounce. Must be called once per global tick. Once the online
     * player count has been stable for {@code player-count-buffer-ticks}, the selected level is
     * applied to every world.
     */
    public static void tick() {
        if (playerCountBufferTicks <= 0) {
            return; // immediate mode is handled in schedulePlayerCountReevaluation
        }
        final int pending = pendingPlayerCount;
        if (pending < 0) {
            return; // nothing pending
        }
        final int current = getOnlinePlayerCount();
        if (current != pending) {
            // the count changed again while we were waiting, restart the stability window
            pendingPlayerCount = current;
            pendingStableTicks = 0;
            return;
        }
        if (++pendingStableTicks < playerCountBufferTicks) {
            return;
        }
        pendingPlayerCount = -1;
        pendingStableTicks = 0;
        applyToAllWorlds();
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

        final LastApplied last = lastAppliedByWorld.get().get(dimensionName);

        // View and simulation distance are strictly governed by player-count load scaling and overrides,
        // and NEVER altered by the regionizer aggression slider.
        final int rawView = worldOverride != null && worldOverride.viewDistance > 0 ? worldOverride.viewDistance : selected.getValue().viewDistance;
        final int rawSim = worldOverride != null && worldOverride.simulationDistance > 0 ? worldOverride.simulationDistance : selected.getValue().simulationDistance;
        final int viewDistance = clampDistance(rawView);
        final int simulationDistance = clampDistance(rawSim);

        final int configuredSections = worldOverride != null && worldOverride.regionizerSections > 0 ? worldOverride.regionizerSections : selected.getValue().regionizerSections;
        final int currentAggression = aggression;

        if (last == null || last.viewDistance != viewDistance || last.simulationDistance != simulationDistance
            || last.regionizerSections != configuredSections || last.aggression != currentAggression) {
            if (viewDistance > 0) {
                FeatureHooks.setViewDistance(level, viewDistance);
            }
            if (simulationDistance > 0) {
                FeatureHooks.setSimulationDistance(level, simulationDistance);
            }

            final RegionizerSettings worldSettings = RegionizerSettings.forWorld(RegionizerSettings.get(), configuredSections, currentAggression);
            level.enigma$regionizerSettings = worldSettings;

            updateLastApplied(dimensionName, new LastApplied(viewDistance, simulationDistance, configuredSections, currentAggression));
        }

        final double factor = 1.0D;
        // Canvas start - the nether always runs with doubled spawn caps and doubled spawn rates on top of the selected level
        final double spawnMultiplier = isNetherDimension(dimensionName) ? 2.0D : 1.0D;
        // Canvas end - the nether always runs with doubled spawn caps and doubled spawn rates on top of the selected level
        applySpawnLimits(io.papermc.paper.threadedregions.RegionizedServer.getInstance(), level, selected, base, factor, worldOverride, spawnMultiplier);
        applyTicksPerSpawn(level, selected, base, factor, worldOverride, spawnMultiplier);
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

    // Canvas start - detect the nether so its spawn caps and rates can be doubled per level
    private static boolean isNetherDimension(final String dimensionPath) {
        return "the_nether".equals(dimensionPath);
    }
    // Canvas end - detect the nether so its spawn caps and rates can be doubled per level

    private static void applySpawnLimits(
        final io.papermc.paper.threadedregions.RegionizedServer regionizedServer, final ServerLevel level,
        final Map.Entry<Integer, WorldSettings> selected,
        final Map.Entry<Integer, WorldSettings> base,
        final double factor,
        final WorldSettings worldOverride,
        final double spawnMultiplier
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
                // Canvas start - apply the per-dimension spawn cap multiplier (the nether doubles its caps)
                final int applied = spawnMultiplier == 1.0D ? value : (int) Math.round(value * spawnMultiplier);
                // Canvas end - apply the per-dimension spawn cap multiplier (the nether doubles its caps)
                switch (key) {
                    case "monster" -> world.setMonsterSpawnLimit(applied);
                    case "animal" -> world.setAnimalSpawnLimit(applied);
                    case "ambient" -> world.setAmbientSpawnLimit(applied);
                    case "water_animal" -> world.setWaterAnimalSpawnLimit(applied);
                    case "water_ambient" -> world.setWaterAmbientSpawnLimit(applied);
                }
            }
        }
    }

    private static void applyTicksPerSpawn(
        final ServerLevel level,
        final Map.Entry<Integer, WorldSettings> selected,
        final Map.Entry<Integer, WorldSettings> base,
        final double factor,
        final WorldSettings worldOverride,
        final double spawnMultiplier
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
                // Canvas start - apply the per-dimension spawn rate multiplier (the nether doubles its rate by halving the ticks)
                final int applied = spawnMultiplier == 1.0D ? value : Math.max(1, (int) Math.round(value / spawnMultiplier));
                // Canvas end - apply the per-dimension spawn rate multiplier (the nether doubles its rate by halving the ticks)
                switch (key) {
                    case "monster" -> world.setTicksPerMonsterSpawns(applied);
                    case "animal" -> world.setTicksPerAnimalSpawns(applied);
                    case "ambient" -> world.setTicksPerAmbientSpawns(applied);
                    case "water_animal" -> world.setTicksPerWaterSpawns(applied);
                    case "water_ambient" -> world.setTicksPerWaterAmbientSpawns(applied);
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
        final String dimensionName = level.dimension().identifier().getPath();
        final WorldSettings override = worldOverrides.get(dimensionName);
        final int rawView = override != null && override.viewDistance > 0 ? override.viewDistance : selected.getValue().viewDistance;
        final int rawSim = override != null && override.simulationDistance > 0 ? override.simulationDistance : selected.getValue().simulationDistance;
        final int view = clampDistance(rawView);
        final int sim = clampDistance(rawSim);
        final int sections = override != null && override.regionizerSections > 0 ? override.regionizerSections : selected.getValue().regionizerSections;
        return "Level " + selected.getKey() + " players, view=" + view + ", sim=" + sim + ", regionizerSections=" + sections + ", aggr=" + aggression + "%";
    }
}
