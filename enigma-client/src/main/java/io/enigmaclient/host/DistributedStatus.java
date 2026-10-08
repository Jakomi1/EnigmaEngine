package io.enigmaclient.host;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wertet das Live-Ergebnis des Enigma-Serverbefehls {@code /enigma distributed status} aus
 * (und optional {@code /enigma distributed migrations}) - also EnigmaSeite des Zahlenwerks.
 */
public final class DistributedStatus {

    private static final String STATUS_MARKER = "Enigma Distributed Status";

    private static final Pattern STARTED = Pattern.compile("Started: (true|false)");
    private static final Pattern ACTIVE_REGIONS = Pattern.compile("Active regions: (\\d+)");
    private static final Pattern ACTIVE_LEASES = Pattern.compile("Active leases: (\\d+)");
    private static final Pattern MIGRATIONS = Pattern.compile("Migrations: (\\d+) active, (\\d+) in history");
    private static final Pattern REGION_CHUNKS = Pattern.compile("region\\s+\\S+\\s+gen=\\d+\\s+chunks=(\\d+)");
    private static final Pattern MIGRATION_LINE = Pattern.compile(
            "(\\S+)\\s*->\\s*host\\s+\\S+\\s+stage=(\\S+)\\s+age=\\d+s(?:\\s+chunks=(\\d+)/(\\d+))?");
    private static final String CONNECTED_CHANNEL = "Control channel:";

    public boolean started;
    public boolean controlConnected;
    public int activeRegions = -1;
    public long chunkSum = 0;
    public int activeLeases = -1;
    public int migrationsActive = -1;
    public int migrationsHistory = -1;
    public String migrationsDetail = "";

    public boolean hasLiveData() {
        return activeRegions >= 0;
    }

    /** null wenn die Ausgabe nicht vom Status-Befehl stammt. */
    public static DistributedStatus parseStatus(String output) {
        if (output == null || !output.contains(STATUS_MARKER)) {
            return null;
        }
        DistributedStatus status = new DistributedStatus();
        Matcher m;
        if ((m = STARTED.matcher(output)).find()) {
            status.started = Boolean.parseBoolean(m.group(1));
        }
        int channelIdx = output.indexOf(CONNECTED_CHANNEL);
        if (channelIdx >= 0) {
            String tail = output.substring(channelIdx);
            status.controlConnected = !tail.startsWith(CONNECTED_CHANNEL + " <not connected>");
        }
        if ((m = ACTIVE_REGIONS.matcher(output)).find()) {
            status.activeRegions = Integer.parseInt(m.group(1));
        }
        m = REGION_CHUNKS.matcher(output);
        while (m.find()) {
            status.chunkSum += Long.parseLong(m.group(1));
        }
        if ((m = ACTIVE_LEASES.matcher(output)).find()) {
            status.activeLeases = Integer.parseInt(m.group(1));
        }
        if ((m = MIGRATIONS.matcher(output)).find()) {
            status.migrationsActive = Integer.parseInt(m.group(1));
            status.migrationsHistory = Integer.parseInt(m.group(2));
        }
        return status;
    }

    /** Fuegt sichtbare laufende Migrationen als Kurztext hinzu (z. B. &quot;#3 24/64&quot;). */
    public static String parseMigrations(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String line : output.split("\\r?\\n|\\s{2,}")) {
            Matcher m = MIGRATION_LINE.matcher(line.trim());
            if (!m.find() || line.contains("failure=")) {
                continue;
            }
            String stage = m.group(2);
            if ("VERIFIED".equals(stage)) {
                continue;
            }
            StringBuilder text = new StringBuilder(m.group(1));
            if (m.group(3) != null) {
                text.append(' ').append(m.group(3)).append('/').append(m.group(4));
            } else {
                text.append(' ').append(stage);
            }
            parts.add(text.toString());
            if (parts.size() >= 2) {
                break;
            }
        }
        return String.join(", ", parts);
    }
}