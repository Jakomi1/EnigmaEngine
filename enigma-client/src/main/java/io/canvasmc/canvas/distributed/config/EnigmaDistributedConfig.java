package io.canvasmc.canvas.distributed.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * Configuration for the EnigmaEngine distributed runtime ({@code WORLD_HOST}/{@code COMPUTE_HOST}).
 *
 * <p>Unlike {@code io.canvasmc.canvas.GlobalConfiguration} (which lives in
 * {@code config/canvas-server.yml}) this part of the configuration is intentionally standalone:
 * it is read from {@code config/enigma-engine.yml} so all Enigma state lives under {@code config/}
 * next to the identity key and {@code config/enigma-load.yml}. Values are applied in this order:
 * built-in defaults &lt; {@code config/enigma-engine.yml} &lt; {@code -Denigma.distributed.*} system
 * properties.</p>
 */
public final class EnigmaDistributedConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("EnigmaDistributedConfig");
    public static final String PROP_PREFIX = "enigma.distributed.";
    private static final Path CONFIG_PATH = Path.of("config/enigma-engine.yml").toAbsolutePath().normalize();

    private static volatile @Nullable EnigmaDistributedConfig loaded;

    public enum Role {
        WORLD_HOST,
        COMPUTE_HOST
    }

    /**
     * Master switch. When false the distributed runtime is never started, so a normal
     * single-server setup is completely unaffected by the enigma configuration.
     */
    public boolean enabled = false;

    public @Nullable String role = null;

    /**
     * Hostname of the World Host as seen by Compute Hosts. {@link #effectiveWorldHostPort()}
     * decides the port to connect to.
     */
    public String worldHostAddress = "localhost";

    /**
     * Bind address for the World Host runtime listener.
     */
    public String bindAddress = "0.0.0.0";

    /**
     * Runtime control channel port. Must not collide with the Minecraft server port. The World
     * Host binds this port; a Compute Host uses it to reach the World Host unless
     * {@link #worldHostPort} is set explicitly.
     */
    public int port = 25567;

    /**
     * Port a Compute Host connects to on the World Host. {@code 0} means "use {@link #port}".
     * The World Host ignores this value and always binds {@link #port}.
     */
    public int worldHostPort = 0;

    /**
     * Where the Ed25519 instance identity key is stored, relative to the server directory. Lives
     * under {@code config/} so no Enigma artifacts are left next to the server jar.
     */
    public String identityKeyPath = "config/enigma_identity.key";

    public int leaseTtlSeconds = 5;

    public int leaseRenewalIntervalSeconds = 2;

    public int snapshotChunkSizeKb = 64;

    public int maxSnapshotSizeMb = 512;

    public boolean debugLogging = false;

    /**
     * Automatically hand regions over to connected Compute Hosts (World Host only). Tuned to be
     * aggressive by default: a pass every {@link #autoMigrateIntervalSeconds} seconds that starts up
     * to {@link #autoMigratePerRun} new migrations while at most {@link #autoMigrateMaxConcurrent}
     * run at the same time.
     */
    public boolean autoMigrate = true;

    public int autoMigrateIntervalSeconds = 10;

    public int autoMigratePerRun = 2;

    public int autoMigrateMaxConcurrent = 3;

    /**
     * Regions owning fewer chunks than this are left alone so small spawn/slice regions never burn
     * migration bandwidth.
     */
    public int autoMigrateMinChunks = 100;

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
     * Loads {@code config/enigma-engine.yml} (writing the documented template on first boot) and
     * applies {@code -Denigma.distributed.*} overrides on top. Idempotent; the first call caches
     * the result for the lifetime of the process.
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
            config.loadFromFile();
            config.applySystemPropertyOverrides();
            loaded = config;
            final Path identity = Path.of(config.identityKeyPath).toAbsolutePath().normalize();
            LOGGER.info("Enigma distributed config loaded from {}, identity key at {}",
                    CONFIG_PATH.getFileName(), identity);
            return config;
        }
    }

    private void loadFromFile() {
        if (!Files.isRegularFile(CONFIG_PATH)) {
            writeDefaultConfig();
            return;
        }
        try (final Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
            final Object root = new Yaml().load(reader);
            if (!(root instanceof Map<?, ?> raw)) {
                LOGGER.warn("{} is empty or not a mapping, using built-in defaults", CONFIG_PATH.getFileName());
                return;
            }
            final Map<@Nullable Object, @Nullable Object> values = (Map<@Nullable Object, @Nullable Object>) raw;
            if (values.containsKey("enabled")) {
                this.enabled = boolValue(values.get("enabled"), this.enabled);
            }
            final String roleValue = stringValue(values.get("role"), null);
            if (roleValue != null) {
                this.role = roleValue;
            }
            this.worldHostAddress = stringValue(values.get("worldHost"), this.worldHostAddress);
            this.bindAddress = stringValue(values.get("bind"), this.bindAddress);
            this.identityKeyPath = stringValue(values.get("identityKey"), this.identityKeyPath);
            this.port = intValue(values.get("port"), this.port);
            this.worldHostPort = intValue(values.get("worldHostPort"), this.worldHostPort);
            this.leaseTtlSeconds = intValue(values.get("leaseTtlSeconds"), this.leaseTtlSeconds);
            this.leaseRenewalIntervalSeconds =
                    intValue(values.get("leaseRenewalIntervalSeconds"), this.leaseRenewalIntervalSeconds);
            this.snapshotChunkSizeKb = intValue(values.get("snapshotChunkSizeKb"), this.snapshotChunkSizeKb);
            this.maxSnapshotSizeMb = intValue(values.get("maxSnapshotSizeMb"), this.maxSnapshotSizeMb);
            this.debugLogging = boolValue(values.get("debugLogging"), this.debugLogging);
            this.autoMigrate = boolValue(values.get("autoMigrate"), this.autoMigrate);
            this.autoMigrateIntervalSeconds =
                    intValue(values.get("autoMigrateIntervalSeconds"), this.autoMigrateIntervalSeconds);
            this.autoMigratePerRun = intValue(values.get("autoMigratePerRun"), this.autoMigratePerRun);
            this.autoMigrateMaxConcurrent =
                    intValue(values.get("autoMigrateMaxConcurrent"), this.autoMigrateMaxConcurrent);
            this.autoMigrateMinChunks = intValue(values.get("autoMigrateMinChunks"), this.autoMigrateMinChunks);
        } catch (final IOException | RuntimeException thrown) {
            LOGGER.error("Failed to load {}, keeping built-in defaults", CONFIG_PATH.getFileName(), thrown);
        }
    }

    private void writeDefaultConfig() {
        try {
            if (CONFIG_PATH.getParent() != null) {
                Files.createDirectories(CONFIG_PATH.getParent());
            }
            // A hand-written template keeps the comments readable, which SnakeYAML cannot produce.
            Files.writeString(CONFIG_PATH, DEFAULT_TEMPLATE, StandardCharsets.UTF_8);
            LOGGER.info("Wrote default configuration template to {}", CONFIG_PATH);
        } catch (final IOException thrown) {
            LOGGER.error("Failed to write default configuration template to {}", CONFIG_PATH, thrown);
        }
    }

    /**
     * Applies {@code -Denigma.distributed.*} overrides on top of the YAML values so the runtime
     * can be exercised without hand-editing the configuration file.
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

    private static String stringValue(final @Nullable Object value, final String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean boolValue(final @Nullable Object value, final boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static int intValue(final @Nullable Object value, final int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (final NumberFormatException e) {
            LOGGER.warn("Invalid integer '{}' in {}, using {}", value, CONFIG_PATH.getFileName(), fallback);
            return fallback;
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

    private static final String DEFAULT_TEMPLATE = """
            # -----------------------------------------------
            #  EnigmaEngine distributed runtime configuration
            # -----------------------------------------------
            #  This file replaces the former "distributed" section of canvas-server.yml. Every value
            #  can also be overridden per start with -Denigma.distributed.<key>=<value> (those win).
            #
            #  Identity key:              config/enigma_identity.key
            #  Load scaling (regionizer): config/enigma-load.yml
            # -----------------------------------------------

            # Master switch. Both World Hosts and Compute Hosts need this enabled to start the
            # distributed runtime at all.
            enabled: false

            # Role of this instance: WORLD_HOST or COMPUTE_HOST. Required when enabled.
            role: ''

            # Address of the World Host's runtime control channel as seen by Compute Hosts.
            # Ignored on the World Host, which uses the bind address instead.
            worldHost: localhost

            # Address the World Host's runtime listener binds to. Ignored on the Compute Host.
            bind: 0.0.0.0

            # Port the World Host binds and a Compute Host connects to. Must not collide with the
            # Minecraft server port.
            port: 25567

            # Port a Compute Host connects to on the World Host. 0 means "use the port value".
            # Ignored on the World Host, which always binds the port value.
            worldHostPort: 0

            # Where the Ed25519 instance identity key is stored, relative to the server directory.
            identityKey: config/enigma_identity.key

            # Lease TTL in seconds. The Compute Host must renew before this expires, otherwise the
            # World Host reclaims the region.
            leaseTtlSeconds: 5

            # Interval in seconds at which the lease is renewed. Must be smaller than leaseTtlSeconds.
            leaseRenewalIntervalSeconds: 2

            # Maximum size of each snapshot chunk in KB.
            snapshotChunkSizeKb: 64

            # Maximum total snapshot size in MB.
            maxSnapshotSizeMb: 512

            # Automatic region migration, World Host only. Every interval, up to autoMigratePerRun
            # new regions are handed to connected Compute Hosts while at most autoMigrateMaxConcurrent
            # migrations run at the same time. Regions smaller than autoMigrateMinChunks are ignored.
            autoMigrate: true
            autoMigrateIntervalSeconds: 10
            autoMigratePerRun: 2
            autoMigrateMaxConcurrent: 3
            autoMigrateMinChunks: 100

            # Enable verbose distributed logging.
            debugLogging: false
            """;
}