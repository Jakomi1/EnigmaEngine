package io.canvasmc.canvas.distributed.player;

import io.canvasmc.canvas.distributed.config.EnigmaDistributedConfig;
import io.canvasmc.canvas.distributed.identity.InstanceIdentity;
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

/**
 * Outbound half of the player network: would forward player packets from the host that terminates a
 * connection towards the host that owns the player's region.
 *
 * <p><strong>Not implemented.</strong> No server code calls any method here, so it never sends
 * anything. It is kept because the buffer framing it uses matches what the Compute Host side already
 * expects, but it must not be treated as working player forwarding.</p>
 */
public final class NetworkGateway {

    private static final Logger LOGGER = LoggerFactory.getLogger("EnigmaNetworkGateway");

    private final EnigmaDistributedConfig config;
    private final InstanceIdentity identity;
    private final RuntimeConnection connection;
    private final RemoteRegionExecutor regionExecutor;

    private final Map<Integer, PlayerSession> playerSessions = new ConcurrentHashMap<>();

    public NetworkGateway(
            final EnigmaDistributedConfig config,
            final InstanceIdentity identity,
            final RuntimeConnection connection,
            final RemoteRegionExecutor regionExecutor
    ) {
        this.config = config;
        this.identity = identity;
        this.connection = connection;
        this.regionExecutor = regionExecutor;
    }

    public void onPlayerJoin(final int playerId, final UUID playerUuid, final String playerName) {
        final PlayerSession session = new PlayerSession(playerId, playerUuid, playerName);
        playerSessions.put(playerId, session);
        LOGGER.info("NetworkGateway: Player joined {} ({})", playerName, playerUuid);
    }

    public void onPlayerLeave(final int playerId) {
        playerSessions.remove(playerId);
        LOGGER.info("NetworkGateway: Player left (id={})", playerId);
    }

    public void routePacketToWorldHost(final int playerId, final byte[] packetData) {
        final PlayerSession session = playerSessions.get(playerId);
        if (session == null) {
            return;
        }
        final ByteBuf buf = connection.getChannel().alloc().buffer();
        try {
            buf.writeInt(playerId);
            buf.writeBytes(packetData);
            connection.sendControl(MessageEnvelope.MessageType.PLAYER_PACKET, buf);
        } catch (Exception e) {
            buf.release();
            LOGGER.error("Failed to route packet to World Host", e);
        }
    }

    public Optional<PlayerSession> getSession(final int playerId) {
        return Optional.ofNullable(playerSessions.get(playerId));
    }

    public static final class PlayerSession {
        public final int playerId;
        public final UUID playerUuid;
        public final String playerName;

        public PlayerSession(final int playerId, final UUID playerUuid, final String playerName) {
            this.playerId = playerId;
            this.playerUuid = playerUuid;
            this.playerName = playerName;
        }
    }
}