package io.enigmaclient.host;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Minimaler Source-RCON-Client (das von Minecraft-Servern gesprochene Protokoll). Jeder Aufruf
 * von {@link #execute} ist eine Verbindung: connect, auth, command, close.
 */
public final class RconClient {

    private static final int TYPE_AUTH = 3;
    private static final int TYPE_COMMAND = 2;
    private static final int TYPE_RESPONSE = 0;

    private static final int MAX_PACKETS = 12;

    private RconClient() {
    }

    /**
     * Fuehrt ein Kommando aus und liefert die Serverantwort; wirft {@code IOException} bei
     * Verbindungs-/Auth-Fehlern oder Timeout.
     */
    public static String execute(String host, int port, String password, String command, int timeoutMs)
            throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setTcpNoDelay(true);
            int requestId = (int) (System.nanoTime() & 0x7FFFFFFFL);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            writePacket(out, requestId, TYPE_AUTH, password);
            boolean authenticated = false;
            socket.setSoTimeout(timeoutMs);
            for (int i = 0; i < 5 && !authenticated; i++) {
                Packet packet = readPacket(in);
                if (packet.id == requestId && packet.id != -1) {
                    authenticated = true;
                }
            }
            if (!authenticated) {
                throw new IOException("RCON-Authentifizierung fehlgeschlagen (host=" + host + ", port=" + port + ")");
            }

            drainStrays(socket, in);

            socket.setSoTimeout(timeoutMs);
            writePacket(out, requestId, TYPE_COMMAND, command);
            StringBuilder response = new StringBuilder();
            boolean sawResponse = false;
            Packet last = null;
            for (int i = 0; i < MAX_PACKETS; i++) {
                long start = System.currentTimeMillis();
                Packet packet = readPacket(in);
                if (packet == null) {
                    break;
                }
                last = packet;
                if (packet.id == requestId && packet.body != null) {
                    sawResponse = true;
                    response.append(packet.body);
                } else if (packet.id == requestId && !sawResponse && packet.type == TYPE_RESPONSE) {
                    sawResponse = true;
                }
                socket.setSoTimeout(300);
                if (i == 0 && System.currentTimeMillis() - start > timeoutMs) {
                    break;
                }
            }
            if (!sawResponse || (last == null && response.length() == 0)) {
                throw new IOException("RCON: keine Antwort auf Kommando " + command);
            }
            return response.toString();
        }
    }

    /** Liest nach der Auth-Bestaetigung eventuell vorhandene Extra-Pakete (kurzes Zeitfenster) weg. */
    private static void drainStrays(Socket socket, DataInputStream in) throws IOException {
        socket.setSoTimeout(300);
        for (int i = 0; i < 3; i++) {
            if (readPacket(in) == null) {
                return;
            }
        }
    }

    /** Ein Paket oder null bei Socket-Timeout. */
    private static Packet readPacket(DataInputStream in) throws IOException {
        Packet packet = new Packet();
        int size;
        try {
            size = readLittleEndian(in);
        } catch (SocketTimeoutException e) {
            return null;
        }
        if (size < 8 || size > 16 * 1024 * 1024) {
            throw new IOException("RCON-Paketgroesse ungueltig: " + size);
        }
        packet.id = readLittleEndian(in);
        packet.type = readLittleEndian(in);
        int len = Math.max(0, size - 8);
        byte[] payload = new byte[len];
        int read = 0;
        while (read < len) {
            int n = in.read(payload, read, len - read);
            if (n < 0) {
                throw new IOException("RCON-Stream vor Paketende geschlossen");
            }
            read += n;
        }
        packet.body = new String(payload, StandardCharsets.UTF_8).replaceFirst("\u0000+$", "");
        return packet;
    }

    private static int readLittleEndian(DataInputStream in) throws IOException {
        int b0 = in.readUnsignedByte();
        int b1 = in.readUnsignedByte();
        int b2 = in.readUnsignedByte();
        int b3 = in.readUnsignedByte();
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static void writePacket(OutputStream out, int requestId, int type, String payload)
            throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        byte[] packet = new byte[12 + body.length + 2];
        writeLittleEndian(packet, 0, 8 + body.length + 2);
        writeLittleEndian(packet, 4, requestId);
        writeLittleEndian(packet, 8, type);
        System.arraycopy(body, 0, packet, 12, body.length);
        packet[packet.length - 2] = 0;
        packet[packet.length - 1] = 0;
        // Ein einziger write() verhindert, dass der Vanilla-Rcon-Thread (liest genau ein
        // komplettes Paket pro read()-Aufruf) das Paket in mehreren TCP-Segmenten antrifft.
        out.write(packet);
        out.flush();
    }

    private static void writeLittleEndian(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >> 8) & 0xFF);
        target[offset + 2] = (byte) ((value >> 16) & 0xFF);
        target[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static void writeLittleEndian(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static final class Packet {
        int id;
        int type;
        String body;
    }
}