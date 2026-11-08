package io.canvasmc.canvas.distributed.config;

import io.canvasmc.canvas.config.EnigmaConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for the EnigmaEngine distributed runtime ({@code WORLD_HOST}/{@code COMPUTE_HOST}).
 *
 * <p>Values are read from the {@code distributed} section of {@code config/enigma-engine.yml} and
 * can be overridden per start with {@code -Denigma.distributed.<key>=<value>} system properties
 * (those win). The only other filesystem state is the Ed25519 instance identity key under
 * {@code config/}.</p>
 */
public final class EnigmaDistributedConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("EnigmaDistributedConfig");
    public static final String PROP_PREFIX = "enigma.distributed.";

    private static volatile @Nullable EnigmaDistributedConfig loaded;

    public enum Role {
        WORLD_HOST,
        COMPUTE_HOST
    }

    /**
     * Master switch. When false the distributed runtime is never started, so a normal
     * single-server setup is completely unaffected by the enigma configuration.
     */
    public volatile boolean enabled = false;

    public volatile @Nullable String role = null;

    /**
     * Hostname of the World Host as seen by Compute Hosts. {@link #effectiveWorldHostPort()}
     * decides the port to connect to.
     */
    public volatile String worldHostAddress = "localhost";

    /**
     * Bind address for the World Host runtime listener.
     */
    public volatile String bindAddress = "0.0.0.0";

    /**
     * Runtime control channel port. Must not collide with the Minecraft server port. The World
     * Host binds this port; a Compute Host uses it to reach the World Host unless
     * {@link #worldHostPort} is set explicitly.
     */
    public volatile int port = 25567;

    /**
     * Port a Compute Host connects to on the World Host. {@code 0} means "use {@link #port}".
     * The World Host ignores this value and always binds {@link #port}.
     */
    public volatile int worldHostPort = 0;

    /**
     * Where the Ed25519 instance identity key is stored, relative to the server directory. Lives
     * under {@code config/} so no Enigma artifacts are left next to the server jar.
     */
    public volatile String identityKeyPath = "config/enigma_identity.key";

    public volatile int leaseTtlSeconds = 5;

    public volatile int leaseRenewalIntervalSeconds = 2;

    public volatile int snapshotChunkSizeKb = 64;

    public volatile int maxSnapshotSizeMb = 512;

    public volatile boolean debugLogging = false;

    /**
     * Automatically hand regions over to connected Compute Hosts (World Host only). Tuned to be
     * aggressive by default: a pass every {@link #autoMigrateIntervalSeconds} seconds that starts up
     * to {@link #autoMigratePerRun} new migrations while at most {@link #autoMigrateMaxConcurrent}
     * run at the same time.
     */
    public volatile boolean autoMigrate = true;

    public volatile int autoMigrateIntervalSeconds = 10;

    public volatile int autoMigratePerRun = 2;

    public volatile int autoMigrateMaxConcurrent = 3;

    /**
     * Regions owning fewer chunks than this are left alone so small spawn/slice regions never burn
     * migration bandwidth.
     */
    public volatile int autoMigrateMinChunks = 100;

    /**
     * Whether {@code -Denigma.distributed.enabled=true} was passed.
     */
    public static boolean isEnabledViaSystemProperty() {
        return readBoolean("enabled", false);
    }

    /**
     * The shared instance. Site of the configuration is guaranteed.
     */
    public static EnigmaDistributedConfig current() {
        final EnigmaDistributedConfig instance = loaded;
        if (instance != null) {
            return instance;
        }
        return load();
    }

    /**
     * Loads the configuration from the {@code distributed} section of {@code config/enigma-engine.yml}
     * on top of the built-in defaults, then applies {@code -Denigma.distributed.*} system property
     * overrides (those win). Idempotent; the first call caches the result for the lifetime of the
     * process.
     */
    public static EnigmaDistributedConfig load() {
        final EnigmaDistributedConfig instance = loaded;
        if (instance != null) {
            return instance;
        }
        synchronized (EnigmaDistributedConfig.class) {
            EnigmaDistributedConfig existing = loaded;
            if (existing != null) {
                return existing;
            }
            final EnigmaDistributedConfig config = new EnigmaDistributedConfig();
            config.applySectionOverrides(EnigmaConfig.readSection(EnigmaConfig.DISTRIBUTED_SECTION));
            config.applySystemPropertyOverrides();
            loaded = config;
            final Path identity = Path.of(config.identityKeyPath).toAbsolutePath().normalize();
            LOGGER.info("Enigma distributed config loaded from config/enigma-engine.yml"
                    + " (distributed section) + -Denigma.distributed.* overrides, identity key at {}", identity);
            return config;
        }
    }

    /**
     * Applies the {@code distributed} config section on top of the current values. Malformed values
     * are logged and ignored so a hand-edited typo cannot prevent startup.
     */
    private void applySectionOverrides(final Map<String, Object> section) {
        if (section.isEmpty()) {
            return;
        }
        this.enabled = sectionBoolean(section, "enabled", this.enabled);
        final Object roleValue = section.get("role");
        if (roleValue != null) {
            this.role = String.valueOf(roleValue);
        }
        this.worldHostAddress = sectionString(section, "worldHost", this.worldHostAddress);
        this.bindAddress = sectionString(section, "bind", this.bindAddress);
        this.identityKeyPath = sectionString(section, "identityKey", this.identityKeyPath);
        this.port = sectionInt(section, "port", this.port);
        this.worldHostPort = sectionInt(section, "worldHostPort", this.worldHostPort);
        this.leaseTtlSeconds = sectionInt(section, "leaseTtlSeconds", this.leaseTtlSeconds);
        this.leaseRenewalIntervalSeconds = sectionInt(section, "leaseRenewalIntervalSeconds", this.leaseRenewalIntervalSeconds);
        this.snapshotChunkSizeKb = sectionInt(section, "snapshotChunkSizeKb", this.snapshotChunkSizeKb);
        this.maxSnapshotSizeMb = sectionInt(section, "maxSnapshotSizeMb", this.maxSnapshotSizeMb);
        this.debugLogging = sectionBoolean(section, "debugLogging", this.debugLogging);
        this.autoMigrate = sectionBoolean(section, "autoMigrate", this.autoMigrate);
        this.autoMigrateIntervalSeconds = sectionInt(section, "autoMigrateIntervalSeconds", this.autoMigrateIntervalSeconds);
        this.autoMigratePerRun = sectionInt(section, "autoMigratePerRun", this.autoMigratePerRun);
        this.autoMigrateMaxConcurrent = sectionInt(section, "autoMigrateMaxConcurrent", this.autoMigrateMaxConcurrent);
        this.autoMigrateMinChunks = sectionInt(section, "autoMigrateMinChunks", this.autoMigrateMinChunks);
    }

    private static String sectionString(final Map<String, Object> section, final String key, final String fallback) {
        final Object value = section.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean sectionBoolean(final Map<String, Object> section, final String key, final boolean fallback) {
        final Object value = section.get(key);
        return value instanceof Boolean bool ? bool : fallback;
    }

    private static int sectionInt(final Map<String, Object> section, final String key, final int fallback) {
        final Object value = section.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (final NumberFormatException ignored) {
                LOGGER.warn("Ignoring non-integer value \"{}\" for {} in the distributed config section", text, key);
            }
        }
        return fallback;
    }

    /**
     * Applies {@code -Denigma.distributed.*} overrides on top of the section values so the whole
     * runtime can still be configured purely from the start command.
     */
    private void applySystemPropertyOverrides() {
        this.enabled = readBoolean("enabled", this.enabled);
        final String roleOverride = readString("role", null);
        if (roleOverride != null) {
            this.role = roleOverride;
        }
        this.worldHostAddress = readString("worldHost", this.worldHostAddress);
        this.bindAddress = readString("bind", this.bindAddress);
        this.identityKeyPath = readString("identityKey", this.identityKeyPath);
        this.port = readInt("port", this.port);
        this.worldHostPort = readInt("worldHostPort", this.worldHostPort);
        this.leaseTtlSeconds = readInt("leaseTtlSeconds", this.leaseTtlSeconds);
        this.leaseRenewalIntervalSeconds = readInt("leaseRenewalIntervalSeconds", this.leaseRenewalIntervalSeconds);
        this.snapshotChunkSizeKb = readInt("snapshotChunkSizeKb", this.snapshotChunkSizeKb);
        this.maxSnapshotSizeMb = readInt("maxSnapshotSizeMb", this.maxSnapshotSizeMb);
        this.debugLogging = readBoolean("debugLogging", this.debugLogging);
        this.autoMigrate = readBoolean("autoMigrate", this.autoMigrate);
        this.autoMigrateIntervalSeconds = readInt("autoMigrateIntervalSeconds", this.autoMigrateIntervalSeconds);
        this.autoMigratePerRun = readInt("autoMigratePerRun", this.autoMigratePerRun);
        this.autoMigrateMaxConcurrent = readInt("autoMigrateMaxConcurrent", this.autoMigrateMaxConcurrent);
        this.autoMigrateMinChunks = readInt("autoMigrateMinChunks", this.autoMigrateMinChunks);
    }

    private static @Nullable String readString(final String key, final @Nullable String fallback) {
        final String value = System.getProperty(PROP_PREFIX + key);
        return value == null ? fallback : value;
    }

    private static boolean readBoolean(final String key, final boolean fallback) {
        final String value = System.getProperty(PROP_PREFIX + key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static int readInt(final String key, final int fallback) {
        final String value = System.getProperty(PROP_PREFIX + key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (final NumberFormatException e) {
            throw new IllegalStateException("Invalid integer for -D" + PROP_PREFIX + key + ": " + value);
        }
    }

    public @Nullable Role getRole() {
        if (this.role == null) {
            return null;
        }
        final Role parsed;
        try {
            parsed = Role.valueOf(this.role.trim().toUpperCase(Locale.ENGLISH));
        } catch (final IllegalArgumentException e) {
            return null;
        }
        return parsed;
    }

    public boolean isWorldHost() {
        return this.getRole() == Role.WORLD_HOST;
    }

    public boolean isComputeHost() {
        return this.getRole() == Role.COMPUTE_HOST;
    }

    public void validateCrossField() {
        if (!this.enabled) {
            return;
        }
        if (this.role == null || this.role.isBlank()) {
            throw new IllegalStateException("enigma.distributed.role must be set to WORLD_HOST or COMPUTE_HOST"
                    + " when enigma.distributed.enabled is true");
        }
        final Role parsed = this.getRole();
        if (parsed == null) {
            throw new IllegalStateException("enigma.distributed.role must be WORLD_HOST or COMPUTE_HOST,"
                    + " got: " + this.role);
        }
        this.validateSettings();
    }

    private void validateSettings() {
        if (this.port <= 0 || this.port > 65535) {
            throw new IllegalStateException("enigma.distributed.port must be 1-65535");
        }
        if (this.worldHostPort < 0 || this.worldHostPort > 65535) {
            throw new IllegalStateException("enigma.distributed.worldHostPort must be 0-65535");
        }
        if (this.leaseRenewalIntervalSeconds >= this.leaseTtlSeconds) {
            throw new IllegalStateException("leaseRenewalIntervalSeconds must be < leaseTtlSeconds");
        }
        if (this.leaseTtlSeconds <= 0) {
            throw new IllegalStateException("enigma.distributed.leaseTtlSeconds must be > 0");
        }
        if (this.snapshotChunkSizeKb <= 0) {
            throw new IllegalStateException("enigma.distributed.snapshotChunkSizeKb must be > 0");
        }
        if (this.maxSnapshotSizeMb <= 0) {
            throw new IllegalStateException("enigma.distributed.maxSnapshotSizeMb must be > 0");
        }
        if (this.autoMigrateIntervalSeconds <= 0) {
            throw new IllegalStateException("enigma.distributed.autoMigrateIntervalSeconds must be > 0");
        }
        if (this.autoMigratePerRun < 0) {
            throw new IllegalStateException("enigma.distributed.autoMigratePerRun must be >= 0");
        }
        if (this.autoMigrateMaxConcurrent < 1) {
            throw new IllegalStateException("enigma.distributed.autoMigrateMaxConcurrent must be >= 1");
        }
        if (this.autoMigrateMinChunks < 1) {
            throw new IllegalStateException("enigma.distributed.autoMigrateMinChunks must be >= 1");
        }
    }

    /**
     * Port a Compute Host uses to reach the World Host: {@link #worldHostPort} when configured,
     * otherwise the shared {@link #port}.
     */
    public int effectiveWorldHostPort() {
        return this.worldHostPort > 0 ? this.worldHostPort : this.port;
    }

    /**
     * Validates, persists and applies a setting changed through the distributed command.
     * Returns {@code null} on success, otherwise a user-facing error.
     */
    public static List<String> settingNames() {
        return List.of("enabled", "role", "worldHost", "bind", "port", "worldHostPort", "identityKey",
                "leaseTtlSeconds", "leaseRenewalIntervalSeconds", "snapshotChunkSizeKb", "maxSnapshotSizeMb",
                "debugLogging", "autoMigrate", "autoMigrateIntervalSeconds", "autoMigratePerRun",
                "autoMigrateMaxConcurrent", "autoMigrateMinChunks");
    }

    public @Nullable Object settingValue(final String key) {
        return this.toMap().get(key);
    }

    public synchronized @Nullable String updateSetting(final String key, final String value) {
        final EnigmaDistributedConfig candidate = new EnigmaDistributedConfig();
        candidate.applySectionOverrides(this.toMap());
        try {
            switch (key) {
                case "enabled" -> candidate.enabled = parseBoolean(key, value);
                case "role" -> candidate.role = value.trim().toUpperCase(Locale.ROOT);
                case "worldHost" -> candidate.worldHostAddress = value;
                case "bind" -> candidate.bindAddress = value;
                case "port" -> candidate.port = Integer.parseInt(value);
                case "worldHostPort" -> candidate.worldHostPort = Integer.parseInt(value);
                case "identityKey" -> candidate.identityKeyPath = value;
                case "leaseTtlSeconds" -> candidate.leaseTtlSeconds = Integer.parseInt(value);
                case "leaseRenewalIntervalSeconds" -> candidate.leaseRenewalIntervalSeconds = Integer.parseInt(value);
                case "snapshotChunkSizeKb" -> candidate.snapshotChunkSizeKb = Integer.parseInt(value);
                case "maxSnapshotSizeMb" -> candidate.maxSnapshotSizeMb = Integer.parseInt(value);
                case "debugLogging" -> candidate.debugLogging = parseBoolean(key, value);
                case "autoMigrate" -> candidate.autoMigrate = parseBoolean(key, value);
                case "autoMigrateIntervalSeconds" -> candidate.autoMigrateIntervalSeconds = Integer.parseInt(value);
                case "autoMigratePerRun" -> candidate.autoMigratePerRun = Integer.parseInt(value);
                case "autoMigrateMaxConcurrent" -> candidate.autoMigrateMaxConcurrent = Integer.parseInt(value);
                case "autoMigrateMinChunks" -> candidate.autoMigrateMinChunks = Integer.parseInt(value);
                default -> {
                    return "Unknown setting '" + key + "'. Use /enigma distributed settings to list valid names.";
                }
            }
            if (candidate.enabled) {
                candidate.validateCrossField();
            } else {
                candidate.validateSettings();
            }
        } catch (final IllegalArgumentException | IllegalStateException exception) {
            return "Invalid distributed setting: " + exception.getMessage();
        }

        final Map<String, Object> values = candidate.toMap();
        if (!EnigmaConfig.writeSection(EnigmaConfig.DISTRIBUTED_SECTION, values)) {
            return "Could not save config/enigma-engine.yml; the setting was not changed.";
        }
        this.copyFrom(candidate);
        return null;
    }

    private static boolean parseBoolean(final String key, final String value) {
        if ("true".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "off".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException(key + " must be true/false or on/off");
    }

    private Map<String, Object> toMap() {
        final Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("enabled", this.enabled);
        values.put("role", this.role);
        values.put("worldHost", this.worldHostAddress);
        values.put("bind", this.bindAddress);
        values.put("port", this.port);
        values.put("worldHostPort", this.worldHostPort);
        values.put("identityKey", this.identityKeyPath);
        values.put("leaseTtlSeconds", this.leaseTtlSeconds);
        values.put("leaseRenewalIntervalSeconds", this.leaseRenewalIntervalSeconds);
        values.put("snapshotChunkSizeKb", this.snapshotChunkSizeKb);
        values.put("maxSnapshotSizeMb", this.maxSnapshotSizeMb);
        values.put("debugLogging", this.debugLogging);
        values.put("autoMigrate", this.autoMigrate);
        values.put("autoMigrateIntervalSeconds", this.autoMigrateIntervalSeconds);
        values.put("autoMigratePerRun", this.autoMigratePerRun);
        values.put("autoMigrateMaxConcurrent", this.autoMigrateMaxConcurrent);
        values.put("autoMigrateMinChunks", this.autoMigrateMinChunks);
        return values;
    }

    private void copyFrom(final EnigmaDistributedConfig source) {
        this.enabled = source.enabled;
        this.role = source.role;
        this.worldHostAddress = source.worldHostAddress;
        this.bindAddress = source.bindAddress;
        this.port = source.port;
        this.worldHostPort = source.worldHostPort;
        this.identityKeyPath = source.identityKeyPath;
        this.leaseTtlSeconds = source.leaseTtlSeconds;
        this.leaseRenewalIntervalSeconds = source.leaseRenewalIntervalSeconds;
        this.snapshotChunkSizeKb = source.snapshotChunkSizeKb;
        this.maxSnapshotSizeMb = source.maxSnapshotSizeMb;
        this.debugLogging = source.debugLogging;
        this.autoMigrate = source.autoMigrate;
        this.autoMigrateIntervalSeconds = source.autoMigrateIntervalSeconds;
        this.autoMigratePerRun = source.autoMigratePerRun;
        this.autoMigrateMaxConcurrent = source.autoMigrateMaxConcurrent;
        this.autoMigrateMinChunks = source.autoMigrateMinChunks;
    }
}