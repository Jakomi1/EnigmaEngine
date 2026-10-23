package io.canvasmc.canvas.chunkmap;

import io.canvasmc.canvas.config.EnigmaConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistent store for the chunk map server settings. The live values live in
 * {@link EnigmaChunkMapService}; this class only reads and writes the {@code chunkmap} section of
 * {@code config/enigma-engine.yml}, which is seeded from the code defaults on first start and
 * rewritten by every {@code /enigma chunkmap settings} change.
 */
public final class EnigmaChunkMapSettings {

    private static final Logger LOGGER = LoggerFactory.getLogger("EnigmaChunkMap");

    private EnigmaChunkMapSettings() {
    }

    /** Reads the settings section; missing sections seed the defaults. Returns the stored values. */
    public static Snapshot load() {
        boolean enabled = true;
        try {
            if (EnigmaConfig.hasSection(EnigmaConfig.CHUNKMAP_SECTION)) {
                final Map<String, Object> root = EnigmaConfig.readSection(EnigmaConfig.CHUNKMAP_SECTION);
                final Object enabledNode = root.get("enabled");
                if (enabledNode instanceof Boolean value) {
                    enabled = value;
                }
                return new Snapshot(enabled);
            }
        } catch (final RuntimeException thrown) {
            LOGGER.error("Failed to load the \"{}\" section, keeping previous configuration", EnigmaConfig.CHUNKMAP_SECTION, thrown);
            return new Snapshot(enabled);
        }
        final Snapshot seeded = new Snapshot(enabled);
        save(seeded);
        return seeded;
    }

    /** Writes the given settings back to the {@code chunkmap} section of {@code config/enigma-engine.yml}. */
    public static void save(final Snapshot settings) {
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("enabled", settings.enabled());
        EnigmaConfig.writeSection(EnigmaConfig.CHUNKMAP_SECTION, root);
    }

    /** The persisted values. */
    public record Snapshot(boolean enabled) {
    }
}
