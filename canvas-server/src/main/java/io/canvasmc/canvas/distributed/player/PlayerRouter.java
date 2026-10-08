package io.canvasmc.canvas.distributed.player;

import io.canvasmc.canvas.distributed.config.EnigmaDistributedConfig;
import io.canvasmc.canvas.distributed.network.MessageEnvelope;
import io.canvasmc.canvas.distributed.network.RuntimeConnection;
import io.canvasmc.canvas.distributed.runtime.RemoteRegionExecutor;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks which players are meant to be served by a Compute Host.
 *
 * <p><strong>Not implemented.</strong> Nothing in the server calls
 * {@link #onPlayerLogin}, {@link #routePacketToComputeHost} or
 * {@link #onPlayerDisconnect}: there is no hook into the login pipeline, no packet proxy and no
 * reconnect handling yet. Players therefore stay on the World Host in every current configuration.
 * The bookkeeping below is the shape the real implementation needs, so it is kept, but it must not be
 * treated as a working player network.</p>
 */
public final class PlayerRouter {

    private static final Logger LOGGER = LoggerFactory.getLogger("EnigmaPlayerRouter");

    private final EnigmaDistributedConfig config;
    private final RuntimeConnection connection;
    private final RemoteRegionExecutor regionExecutor;

    private final Map<UUID, RoutedPlayer> routedPlayers = new ConcurrentHashMap<>();
    private final AtomicReference<RuntimeConnection> computeHostConnection = new AtomicReference<>();

    public PlayerRouter(
            final EnigmaDistributedConfig config,
            final RuntimeConnection connection,
            final RemoteRegionExecutor regionExecutor
    ) {
        this.config = config;
        this.connection = connection;
        this.regionExecutor = regionExecutor;
    }

    public void setComputeHostConnection(final RuntimeConnection computeConnection) {
        computeHostConnection.set(computeConnection);
        LOGGER.info("Compute Host connected for player routing");
    }

    public void onComputeHostDisconnected(final RuntimeConnection computeConnection) {
        if (computeHostConnection.compareAndSet(computeConnection, null)) {
            routedPlayers.clear();
            LOGGER.info("Compute Host disconnected; player routing falls back to the World Host");
        }
    }

    /**
     * Marks the current state of player termination. A caller that wants to offload a player has to
     * check this; there is no working player path in this build.
     */
    public static boolean isPlayerTerminationImplemented() {
        return false;
    }

    public void onPlayerLogin(final UUID playerUuid, final int playerId, final String playerName) {
        final RuntimeConnection computeConn = computeHostConnection.get();
        if (computeConn == null || !computeConn.isOpen()) {
            LOGGER.warn("No Compute Host available for player {}, keeping on World Host", playerName);
            return;
        }

        final RoutedPlayer routed = new RoutedPlayer(playerUuid, playerId, playerName, computeConn);
        routedPlayers.put(playerUuid, routed);

        final ByteBuf payload = computeConn.getChannel().alloc().buffer();
        try {
            payload.writeInt(playerId);
            payload.writeLong(playerUuid.getMostSignificantBits());
            payload.writeLong(playerUuid.getLeastSignificantBits());
            writeString(payload, playerName);
            // Must go to the Compute Host connection; the listener connection is the inbound side
            // of the World Host and writing to it would never reach the peer.
            computeConn.sendControl(MessageEnvelope.MessageType.PLAYER_ROUTE, payload);
            LOGGER.info("Routed player {} ({}) to Compute Host", playerName, playerUuid);
        } catch (Exception e) {
            payload.release();
            routedPlayers.remove(playerUuid);
            LOGGER.error("Failed to route player to Compute Host", e);
        }
    }

    public void onPlayerDisconnect(final UUID playerUuid) {
        final RoutedPlayer routed = routedPlayers.remove(playerUuid);
        if (routed != null) {
            final RuntimeConnection computeConn = computeHostConnection.get();
            if (computeConn != null && computeConn.isOpen()) {
                final ByteBuf payload = computeConn.getChannel().alloc().buffer();
                payload.writeInt(routed.playerId);
                computeConn.sendControl(MessageEnvelope.MessageType.PLAYER_ROUTE, payload);
            }
            LOGGER.info("Player {} disconnected from Compute Host", routed.name);
        }
    }

    public void routePacketToComputeHost(final UUID playerUuid, final byte[] packetData) {
        final RoutedPlayer routed = routedPlayers.get(playerUuid);
        if (routed == null) {
            return;
        }
        final RuntimeConnection computeConn = computeHostConnection.get();
        if (computeConn == null || !computeConn.isOpen()) {
            return;
        }

        final ByteBuf payload = computeConn.getChannel().alloc().buffer();
        try {
            payload.writeInt(routed.playerId);
            payload.writeBytes(packetData);
            computeConn.sendControl(MessageEnvelope.MessageType.PLAYER_PACKET, payload);
        } catch (Exception e) {
            payload.release();
            LOGGER.error("Failed to route packet to Compute Host", e);
        }
    }

    private void writeString(final ByteBuf buf, final String s) {
        final byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }

    private static class RoutedPlayer {
        final UUID uuid;
        final int playerId;
        final String name;
        final RuntimeConnection computeConnection;

        RoutedPlayer(final UUID uuid, final int playerId, final String name, final RuntimeConnection computeConnection) {
            this.uuid = uuid;
            this.playerId = playerId;
            this.name = name;
            this.computeConnection = computeConnection;
        }
    }
}