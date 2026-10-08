package io.enigmaclient.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.HexFormat;

public class EnigmaConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("enigmaclient.json");

    public boolean enabled = false;
    public String host = "127.0.0.1";
    public int port = 25567;
    public String jarPath = "";
    public int xmxMb = 2048;
    public boolean debugLogging = false;
    public boolean showHud = true;
    public boolean notifications = true;
    public String rconPassword = "";

    public static EnigmaConfig load() {
        EnigmaConfig config = null;
        boolean legacy = false;
        try {
            if (Files.exists(PATH)) {
                String raw = Files.readString(PATH);
                legacy = raw.contains("reconnectDelaySeconds");
                config = GSON.fromJson(raw, EnigmaConfig.class);
            }
        } catch (Exception e) {
            try {
                Files.copy(PATH, PATH.resolveSibling("enigmaclient.json.bak"), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {
            }
            config = null;
        }
        if (config == null) {
            config = new EnigmaConfig();
        }
        if (legacy) {
            config.port = 25567;
        }
        if (config.rconPassword == null || config.rconPassword.isBlank()) {
            byte[] bytes = new byte[16];
            new SecureRandom().nextBytes(bytes);
            config.rconPassword = HexFormat.of().formatHex(bytes);
        }
        config.validate();
        config.save();
        return config;
    }

    public void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(this));
        } catch (Exception e) {
            io.enigmaclient.EnigmaClient.LOGGER.error("Konfiguration konnte nicht gespeichert werden: {}", e.toString());
        }
    }

    public String target() {
        return host + ":" + port;
    }

    public void validate() {
        if (host == null || host.isBlank()) {
            host = "127.0.0.1";
        }
        port = Math.max(1, Math.min(65535, port));
        if (jarPath == null) {
            jarPath = "";
        }
        if (rconPassword == null || rconPassword.isBlank()) {
            byte[] bytes = new byte[16];
            new SecureRandom().nextBytes(bytes);
            rconPassword = HexFormat.of().formatHex(bytes);
        }
        xmxMb = Math.max(512, Math.min(65536, xmxMb));
    }
}
