package io.canvasmc.canvas.distributed.config;

import java.nio.file.Path;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for the EnigmaEngine distributed runtime ({@code WORLD_HOST}/{@code COMPUTE_HOST}).
 *
 * <p>Everything is controlled through {@code -Denigma.distributed.*} system properties on the start
 * command, applied on top of the built-in defaults shown below. There is no configuration file; the
 * only filesystem state is the Ed25519 instance identity key under {@code config/}.</p>
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
     * Loads the configuration from {@code -Denigma.distributed.*} system properties on top of the
     * built-in defaults. Idempotent; the first call caches the result for the lifetime of the process.
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
            config.applySystemPropertyOverrides();
            loaded = config;
            final Path identity = Path.of(config.identityKeyPath).toAbsolutePath().normalize();
            LOGGER.info("Enigma distributed config loaded from -Denigma.distributed.* system "
                    + "properties, identity key at {}", identity);
            return config;
        }
    }

    /**
     * Applies {@code -Denigma.distributed.*} overrides on top of the built-in defaults so the whole
     * runtime is configured purely from the start command.
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
}