package io.canvasmc.canvas.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnigmaConfigMigrationTestSuite {

    @TempDir
    Path tempDir;

    private Path enginePath() {
        return tempDir.resolve("config/enigma-engine.yml");
    }

    @BeforeEach
    void redirectConfig() throws IOException {
        EnigmaConfig.overrideBaseDir(tempDir);
        Files.createDirectories(tempDir.resolve("config"));
    }

    @Test
    void migratesLegacyRegionizerFileIntoSection() throws IOException {
        Files.writeString(tempDir.resolve("config/enigma-regionizer.yml"), """
            regionizer-mode: auto
            aggression: 70
            player-count-buffer-ticks: 50
            levels:
              0:
                view-distance: 10
                simulation-distance: 8
                regionizer-sections: 12
            worlds: {}
            """, StandardCharsets.UTF_8);

        EnigmaConfig.migrateLegacyFiles();

        final Map<String, Object> section = EnigmaConfig.readSection(EnigmaConfig.REGIONIZER_SECTION);
        assertEquals("auto", section.get("regionizer-mode"));
        assertEquals(70, ((Number) section.get("aggression")).intValue());
        assertEquals(50, ((Number) section.get("player-count-buffer-ticks")).intValue());
        assertTrue(section.containsKey("levels"), "levels table must survive the migration");
        assertFalse(Files.exists(tempDir.resolve("config/enigma-regionizer.yml")), "legacy file must be deleted");
        assertTrue(Files.isRegularFile(enginePath()), "engine file must exist");
    }

    @Test
    void migratesLegacyChunkMapFileIntoSection() throws IOException {
        Files.writeString(tempDir.resolve("config/enigma-chunkmap.yml"), "enabled: false\n", StandardCharsets.UTF_8);

        EnigmaConfig.migrateLegacyFiles();

        final Map<String, Object> section = EnigmaConfig.readSection(EnigmaConfig.CHUNKMAP_SECTION);
        assertEquals(Boolean.FALSE, section.get("enabled"));
        assertFalse(Files.exists(tempDir.resolve("config/enigma-chunkmap.yml")));
    }

    @Test
    void migratesFlatDistributedKeysIntoSection() throws IOException {
        Files.writeString(enginePath(), """
            enabled: true
            role: WORLD_HOST
            port: 30000
            leaseTtlSeconds: 9
            """, StandardCharsets.UTF_8);

        EnigmaConfig.migrateLegacyFiles();

        final Map<String, Object> section = EnigmaConfig.readSection(EnigmaConfig.DISTRIBUTED_SECTION);
        assertEquals(Boolean.TRUE, section.get("enabled"));
        assertEquals("WORLD_HOST", section.get("role"));
        assertEquals(30000, ((Number) section.get("port")).intValue());
        assertEquals(9, ((Number) section.get("leaseTtlSeconds")).intValue());

        // the flat keys must be gone from the root so the file is sectioned from now on
        final Map<String, Object> root = EnigmaConfig.readRoot();
        assertFalse(root.containsKey("enabled"), "flat keys must move under the distributed section");
        assertFalse(root.containsKey("port"));
    }

    @Test
    void migrationIsIdempotent() throws IOException {
        Files.writeString(tempDir.resolve("config/enigma-regionizer.yml"), "aggression: 42\n", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("config/enigma-chunkmap.yml"), "enabled: true\n", StandardCharsets.UTF_8);

        EnigmaConfig.migrateLegacyFiles();
        final Map<String, Object> first = EnigmaConfig.readRoot();

        // second run must not change anything (legacy files are already gone)
        EnigmaConfig.migrateLegacyFiles();
        final Map<String, Object> second = EnigmaConfig.readRoot();

        assertEquals(first, second);
        assertEquals(42, ((Number) EnigmaConfig.readSection(EnigmaConfig.REGIONIZER_SECTION).get("aggression")).intValue());
    }

    @Test
    void writeSectionPreservesOtherSections() throws IOException {
        EnigmaConfig.writeSection(EnigmaConfig.CHUNKMAP_SECTION, Map.of("enabled", true));
        EnigmaConfig.writeSection(EnigmaConfig.DISTRIBUTED_SECTION, Map.of("port", 25570));

        // LoadScaling-style mutation of the regionizer section must not clobber the others
        EnigmaConfig.writeSection(EnigmaConfig.REGIONIZER_SECTION, Map.of("aggression", 80));

        assertEquals(Boolean.TRUE, EnigmaConfig.readSection(EnigmaConfig.CHUNKMAP_SECTION).get("enabled"));
        assertEquals(25570, ((Number) EnigmaConfig.readSection(EnigmaConfig.DISTRIBUTED_SECTION).get("port")).intValue());
        assertEquals(80, ((Number) EnigmaConfig.readSection(EnigmaConfig.REGIONIZER_SECTION).get("aggression")).intValue());
    }

    @Test
    void doesNotDeleteLegacyFilesWhenEngineFileCannotBeWritten() throws IOException {
        Files.writeString(tempDir.resolve("config/enigma-regionizer.yml"), "aggression: 33\n", StandardCharsets.UTF_8);
        // make the config directory read-only so writeRoot fails
        final Path configDir = tempDir.resolve("config");
        configDir.toFile().setWritable(false);

        try {
            EnigmaConfig.migrateLegacyFiles();
            // on platforms where the write actually failed the legacy file must still exist
            if (!Files.isRegularFile(enginePath())) {
                assertTrue(Files.exists(tempDir.resolve("config/enigma-regionizer.yml")),
                    "legacy file must be kept when the engine file was not written");
            }
        } finally {
            configDir.toFile().setWritable(true);
        }
    }
}
