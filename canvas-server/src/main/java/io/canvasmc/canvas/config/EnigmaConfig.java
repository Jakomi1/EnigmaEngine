package io.canvasmc.canvas.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * Central loader and writer for {@code config/enigma-engine.yml}, the single persistent store for
 * all Enigma server configuration. The file is split into three top-level sections:
 *
 * <ul>
 *   <li>{@code regionizer} - adaptive regionizer load scaling (formerly {@code enigma-regionizer.yml})</li>
 *   <li>{@code chunkmap} - chunk map server settings (formerly {@code enigma-chunkmap.yml})</li>
 *   <li>{@code distributed} - distributed runtime settings</li>
 * </ul>
 *
 * <p>On first load, legacy {@code config/enigma-regionizer.yml} and {@code config/enigma-chunkmap.yml}
 * files are migrated into their sections and the legacy files are deleted after the new file has
 * been written successfully.</p>
 */
public final class EnigmaConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("EnigmaConfig");

    public static final String REGIONIZER_SECTION = "regionizer";
    public static final String CHUNKMAP_SECTION = "chunkmap";
    public static final String DISTRIBUTED_SECTION = "distributed";

    private static volatile Path baseDir = Path.of("");

    private static final String HEADER = """
        # -----------------------------------------------
        #  EnigmaEngine configuration
        # -----------------------------------------------
        #  Single persistent store for all Enigma server state. Sections:
        #
        #  regionizer: adaptive regionizer load scaling (mode, aggression, per-player-count
        #              levels, per-dimension world overrides, regionizer limits and the
        #              player-count debounce). Adjusted via "/enigma regionizer ..." commands;
        #              the file always mirrors the live configuration.
        #
        #  chunkmap:   master switch for the chunk map pushed to EnigmaClient mods.
        #
        #  distributed: WORLD_HOST / COMPUTE_HOST runtime. Every value can also be overridden
        #               per start with -Denigma.distributed.<key>=<value> (those win).
        #
        #  Identity key: config/enigma_identity.key
        # -----------------------------------------------
        """;

    private EnigmaConfig() {
    }

    public static Path configPath() {
        return baseDir.resolve("config/enigma-engine.yml").toAbsolutePath().normalize();
    }

    /** Test hook: redirects all config IO below the given directory. */
    static void overrideBaseDir(final Path dir) {
        baseDir = dir;
    }

    private static Path legacyRegionizerPath() {
        return baseDir.resolve("config/enigma-regionizer.yml").toAbsolutePath().normalize();
    }

    private static Path legacyChunkMapPath() {
        return baseDir.resolve("config/enigma-chunkmap.yml").toAbsolutePath().normalize();
    }

    /** Reads the full root mapping of {@code config/enigma-engine.yml}; empty when missing/invalid. */
    public static Map<String, Object> readRoot() {
        final Path path = configPath();
        if (!Files.isRegularFile(path)) {
            return new LinkedHashMap<>();
        }
        try {
            final Object root = new Yaml().load(Files.readString(path, StandardCharsets.UTF_8));
            return root instanceof Map<?, ?> map ? stringifyKeys(map) : new LinkedHashMap<>();
        } catch (final IOException | RuntimeException thrown) {
            LOGGER.error("Failed to read {}, using empty configuration", path, thrown);
            return new LinkedHashMap<>();
        }
    }

    /** Reads one top-level section; empty map when the section or file is missing. */
    public static Map<String, Object> readSection(final String name) {
        final Object section = readRoot().get(name);
        return section instanceof Map<?, ?> map ? stringifyKeys(map) : new LinkedHashMap<>();
    }

    /** Whether the given top-level section exists in the config file. */
    public static boolean hasSection(final String name) {
        return readRoot().containsKey(name);
    }

    /**
     * Replaces one top-level section and writes the file back, preserving all other sections.
     * Synchronised so concurrent command mutations cannot clobber each other.
     */
    public static synchronized boolean writeSection(final String name, final Map<String, Object> data) {
        final Map<String, Object> root = readRoot();
        root.put(name, new LinkedHashMap<>(data));
        return writeRoot(root);
    }

    /** Writes the full root mapping with the documented header. Returns whether the write succeeded. */
    public static synchronized boolean writeRoot(final Map<String, Object> root) {
        final Path path = configPath();
        try {
            final Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, HEADER + new Yaml().dump(root), StandardCharsets.UTF_8);
            return true;
        } catch (final IOException thrown) {
            LOGGER.error("Failed to write {}", path, thrown);
            return false;
        }
    }

    /**
     * Migrates the legacy {@code enigma-regionizer.yml} / {@code enigma-chunkmap.yml} files and
     * flat distributed keys into the sectioned {@code enigma-engine.yml}. Legacy files are only
     * deleted after the new file has been written successfully. Idempotent: safe to call on
     * every reload.
     */
    public static synchronized void migrateLegacyFiles() {
        final Path enginePath = configPath();
        final Map<String, Object> root = readRoot();
        boolean changed = false;

        // Legacy flat distributed keys (old enigma-engine.yml template) move under "distributed".
        if (!root.containsKey(DISTRIBUTED_SECTION) && hasFlatDistributedKeys(root)) {
            final Map<String, Object> distributed = new LinkedHashMap<>();
            for (final String key : new String[]{
                "enabled", "role", "worldHost", "bind", "port", "worldHostPort", "identityKey",
                "leaseTtlSeconds", "leaseRenewalIntervalSeconds", "snapshotChunkSizeKb",
                "maxSnapshotSizeMb", "debugLogging", "autoMigrate", "autoMigrateIntervalSeconds",
                "autoMigratePerRun", "autoMigrateMaxConcurrent", "autoMigrateMinChunks"
            }) {
                if (root.containsKey(key)) {
                    distributed.put(key, root.remove(key));
                }
            }
            root.put(DISTRIBUTED_SECTION, distributed);
            changed = true;
        }

        final Path legacyRegionizer = legacyRegionizerPath();
        if (!root.containsKey(REGIONIZER_SECTION) && Files.isRegularFile(legacyRegionizer)) {
            final Map<String, Object> legacy = readLegacy(legacyRegionizer);
            if (!legacy.isEmpty()) {
                root.put(REGIONIZER_SECTION, legacy);
                changed = true;
            }
        }

        final Path legacyChunkMap = legacyChunkMapPath();
        if (!root.containsKey(CHUNKMAP_SECTION) && Files.isRegularFile(legacyChunkMap)) {
            final Map<String, Object> legacy = readLegacy(legacyChunkMap);
            if (!legacy.isEmpty()) {
                root.put(CHUNKMAP_SECTION, legacy);
                changed = true;
            }
        }

        if (changed && !writeRoot(root)) {
            // the new store could not be written; keep the legacy files so no data is lost
            return;
        }

        // Migration complete (or nothing to migrate): delete the legacy files once the engine
        // file exists, i.e. the new store is guaranteed to hold the data.
        if (!Files.isRegularFile(enginePath)) {
            return;
        }
        if (root.containsKey(REGIONIZER_SECTION)) {
            deleteLegacy(legacyRegionizer);
        }
        if (root.containsKey(CHUNKMAP_SECTION)) {
            deleteLegacy(legacyChunkMap);
        }
    }

    private static boolean hasFlatDistributedKeys(final Map<String, Object> root) {
        return root.containsKey("enabled") || root.containsKey("role") || root.containsKey("worldHost")
            || root.containsKey("port") || root.containsKey("identityKey");
    }

    private static Map<String, Object> readLegacy(final Path path) {
        try {
            final Object root = new Yaml().load(Files.readString(path, StandardCharsets.UTF_8));
            return root instanceof Map<?, ?> map ? stringifyKeys(map) : new LinkedHashMap<>();
        } catch (final IOException | RuntimeException thrown) {
            LOGGER.error("Failed to read legacy config {}, skipping migration for it", path, thrown);
            return new LinkedHashMap<>();
        }
    }

    private static void deleteLegacy(final Path path) {
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            Files.delete(path);
            LOGGER.info("Migrated and removed legacy config {}", path.getFileName());
        } catch (final IOException thrown) {
            LOGGER.warn("Failed to delete legacy config {} after migration", path, thrown);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringifyKeys(final Map<?, ?> map) {
        final Map<String, Object> ret = new LinkedHashMap<>();
        for (final Map.Entry<?, ?> entry : map.entrySet()) {
            ret.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return ret;
    }
}
