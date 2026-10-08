package io.canvasmc.canvas.distributed;

import io.canvasmc.canvas.distributed.config.EnigmaDistributedConfig;
import io.canvasmc.canvas.distributed.identity.InstanceIdentity;
import io.canvasmc.canvas.distributed.network.MessageEnvelope;
import io.canvasmc.canvas.distributed.network.RuntimeConnection;
import io.canvasmc.canvas.distributed.ownership.GenerationManager;
import io.canvasmc.canvas.distributed.ownership.LeaseManager;
import io.canvasmc.canvas.distributed.player.NetworkGateway;
import io.canvasmc.canvas.distributed.player.PlayerRouter;
import io.canvasmc.canvas.distributed.runtime.RemoteRegionExecutor;
import io.canvasmc.canvas.distributed.snapshot.SnapshotSerializer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wires up the distributed runtime for one process.
 *
 * <p>World Host and Compute Host share a single message handler each. The World Host additionally
 * keeps a registry of accepted Compute Host connections keyed by the remote instance id reported in
 * the handshake, which is the only correct way to address a specific Compute Host: the listening
 * socket itself can never send.</p>
 */
public final class DistributedBootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger("EnigmaDistributed");

    private final EnigmaDistributedConfig config;
    /**
     * Created on the first {@link #start(MinecraftServer)} instead of in the constructor so that the
     * singleton holder can never fail to initialise: a throwing {@code <clinit>} would poison
     * {@link #getInstance()} for every subsequent tick.
     */
    private volatile @org.jspecify.annotations.Nullable InstanceIdentity identity;
    private final GenerationManager generationManager;
    private final LeaseManager leaseManager;
    private final SnapshotSerializer snapshotSerializer;

    /** On the World Host: listener socket. On the Compute Host: the single outbound connection. */
    private volatile @org.jspecify.annotations.Nullable RuntimeConnection runtimeConnection;

    /** World Host only: remote instance id -> accepted connection. */
    private final Map<Long, RuntimeConnection> computeHostConnections = new ConcurrentHashMap<>();

    private volatile @org.jspecify.annotations.Nullable RemoteRegionExecutor regionExecutor;
    private volatile @org.jspecify.annotations.Nullable NetworkGateway networkGateway;
    private volatile @org.jspecify.annotations.Nullable PlayerRouter playerRouter;

    private volatile boolean started = false;
    /** Set as soon as a start attempt is in flight, so a retry cannot bind a second listener. */
    private volatile boolean startRequested = false;
    private volatile long nextStartAttemptMs = 0L;
    /**
     * Compute Host only: the link to the World Host died. Remote regions are kept ticking (with
     * their data) until a reconnect, after which the World Host's ledger recovery asks for them back.
     */
    private volatile boolean worldHostLost = false;
    /**
     * Set when the prepare-stop drain has finished (successfully or not). It distinguishes the halt
     * fired by the drain's own completion from a halt that arrives mid-drain, which must still be
     * deferred to the pending one.
     */
    private volatile boolean prepareStopCompleted = false;
    /** Round robin cursor over the connected Compute Hosts for automatic migrations. */
    private int autoMigrateTargetCursor = 0;
    private long lastAutoMigrateMs = 0L;
    private volatile @org.jspecify.annotations.Nullable MinecraftServer server;

    public DistributedBootstrap() {
        this.config = EnigmaDistributedConfig.current();
        this.generationManager = new GenerationManager();
        this.leaseManager = new LeaseManager(this.config, this.generationManager);
        this.snapshotSerializer = new SnapshotSerializer(this.config);
    }

    private InstanceIdentity identity() {
        final InstanceIdentity identity = this.identity;
        if (identity == null) {
            throw new IllegalStateException("Enigma instance identity has not been created yet");
        }
        return identity;
    }

    public CompletableFuture<Void> start(final MinecraftServer server) {
        this.server = server;
        if (this.started || this.startRequested) {
            return CompletableFuture.completedFuture(null);
        }
        if (!this.config.enabled) {
            LOGGER.info("Distributed runtime is disabled (enigma.distributed.enabled=false)");
            return CompletableFuture.completedFuture(null);
        }

        this.config.validateCrossField();

        final EnigmaDistributedConfig.Role role = this.config.getRole();
        if (role == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("enigma.distributed.role must be WORLD_HOST or COMPUTE_HOST"));
        }

        if (this.identity == null) {
            try {
                this.identity = InstanceIdentity.loadOrCreate(this.config);
            } catch (final Throwable t) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Failed to create the Enigma instance identity", t));
            }
        }

        // Mark before the asynchronous start so a retry cannot bind a second listener.
        this.startRequested = true;
        return switch (role) {
            case WORLD_HOST -> this.startWorldHost();
            case COMPUTE_HOST -> this.startComputeHost();
        };
    }

    // ------------------------------------------------------------------------ world host

    private CompletableFuture<Void> startWorldHost() {
        final InetSocketAddress bindAddr = parseAddress(this.config.bindAddress, this.config.port);
        LOGGER.info("Starting EnigmaEngine as WORLD_HOST (instanceId={}, bind={})",
                this.identity().getInstanceId(), bindAddr);

        return RuntimeConnection
                .bind("world-host", bindAddr, this.identity().getInstanceId().getMostSignificantBits(),
                        this::onComputeHostConnected)
                .thenAccept(listener -> {
                    this.runtimeConnection = listener;
                    // Propagated to every accepted Compute Host connection.
                    listener.setMessageHandler(this::handleWorldHostMessage);

                    final RemoteRegionExecutor executor =
                            new RemoteRegionExecutor(this.config, this.identity(), listener, this.generationManager,
                                    this.leaseManager, this.snapshotSerializer);
                    executor.start(server);
                    this.regionExecutor = executor;

                    final PlayerRouter router = new PlayerRouter(this.config, listener, executor);
                    this.playerRouter = router;

                    this.started = true;
                    LOGGER.info("World Host listening on {}", bindAddr);
                    // After a crash/restart this is where the forwarded-region ledger is re-read, so
                    // compute-held state can come back before anything acts on the stale local copy.
                    executor.loadLedger();
                });
    }

    private void onComputeHostConnected(final RuntimeConnection computeConn) {
        LOGGER.info("Compute Host connected from {} (awaiting handshake)", computeConn.getChannel().remoteAddress());
        computeConn.setCloseListener(this::onComputeHostClosed);
        if (this.playerRouter != null) {
            this.playerRouter.setComputeHostConnection(computeConn);
        }
    }

    private void onComputeHostClosed(final RuntimeConnection closed) {
        final long instanceId = closed.getRemoteInstanceId();
        if (instanceId != -1L) {
            this.computeHostConnections.remove(instanceId, closed);
            final RemoteRegionExecutor executor = this.regionExecutor;
            if (executor != null) {
                executor.onComputeHostDisconnected(instanceId);
            }
        }
        if (this.playerRouter != null) {
            this.playerRouter.onComputeHostDisconnected(closed);
        }
        LOGGER.info("Compute Host {} is no longer connected (removed from the registry)",
                closed.getChannel().remoteAddress());
    }

    private void handleWorldHostMessage(final RuntimeConnection connection, final MessageEnvelope message) {
        switch (message.getMessageType()) {
            case HANDSHAKE -> handleHandshake(connection, message);
            case LEASE_RENEW -> handleLeaseRenew(connection, message);
            case HEARTBEAT -> {
                // Liveness only.
            }
            case PLAYER_PACKET -> {
                // Compute Host forwarding a player packet to the World Host's connection.
            }
            case SNAPSHOT_CHUNK, SNAPSHOT_COMPLETE -> {
                // Compute Host returning a region: route the frames into the snapshot receiver.
                final RemoteRegionExecutor executor = this.regionExecutor;
                if (executor != null && executor.handleWorldHostSnapshotFrame(connection, message)) {
                    return;
                }
                LOGGER.debug("World Host received an orphan snapshot frame {}", message.getMessageType());
            }
            case RETURN_REGION_ACK -> {
                final RemoteRegionExecutor executor = this.regionExecutor;
                if (executor != null) {
                    executor.handleWorldHostReturnAck(message);
                }
            }
            default -> LOGGER.debug("World Host received {}", message.getMessageType());
        }
    }

    private void handleHandshake(final RuntimeConnection connection, final MessageEnvelope message) {
        final ByteBuf payload = message.getPayload();
        final long remoteInstanceId = payload.readLong();
        final int protocolVersion = payload.readInt();

        if (protocolVersion != MessageEnvelope.VERSION) {
            LOGGER.error("Rejecting Compute Host {}: protocol version {} != {}",
                    remoteInstanceId, protocolVersion, MessageEnvelope.VERSION);
            sendHandshakeAck(connection, message, false, "protocol version mismatch");
            connection.close();
            return;
        }
        if (remoteInstanceId == this.identity().getInstanceId().getMostSignificantBits()) {
            LOGGER.error("Rejecting peer claiming our own instance id {}", remoteInstanceId);
            sendHandshakeAck(connection, message, false, "duplicate instance id");
            connection.close();
            return;
        }

        connection.setRemoteInstanceId(remoteInstanceId);
        final RuntimeConnection replaced = this.computeHostConnections.put(remoteInstanceId, connection);
        if (replaced != null && replaced != connection) {
            LOGGER.warn("Compute Host {} reconnected; closing its previous connection", remoteInstanceId);
            replaced.close();
        }
        LOGGER.info("Compute Host {} registered from {}", remoteInstanceId, connection.getChannel().remoteAddress());

        sendHandshakeAck(connection, message, true, "welcome");

        final RemoteRegionExecutor executor = this.regionExecutor;
        if (executor != null) {
            executor.onComputeHostRegistered(connection);
        }
    }

    /**
     * Must echo {@code request}'s correlation id: the sender is blocked in
     * {@link RuntimeConnection#sendAndWait} and only unblocks when a frame carrying that id arrives.
     */
    private void sendHandshakeAck(final RuntimeConnection connection, final MessageEnvelope request,
                                  final boolean accepted, final String detail) {
        final ByteBuf payload = connection.getChannel().alloc().buffer();
        payload.writeBoolean(accepted);
        SnapshotSerializer.writeString(payload, detail);
        connection.sendReply(request, MessageEnvelope.MessageType.HANDSHAKE_ACK, payload);
    }

    private void handleLeaseRenew(final RuntimeConnection connection, final MessageEnvelope message) {
        final ByteBuf payload = message.getPayload();
        final long regionId = payload.readLong();
        final long leaseId = payload.readLong();
        final long ttlMs = payload.readLong();

        final boolean known = this.leaseManager.applyRemoteRenewal(regionId, leaseId,
                ttlMs > 0 ? ttlMs : this.config.leaseTtlSeconds * 1000L);
        if (!known) {
            LOGGER.warn("Lease renewal for region {} referenced an unknown or stale lease", regionId);
        }

        final ByteBuf ack = connection.getChannel().alloc().buffer(Long.BYTES + 1);
        ack.writeLong(regionId);
        ack.writeBoolean(known);
        connection.sendReply(message, MessageEnvelope.MessageType.LEASE_RENEW_ACK, ack);
    }

    // ----------------------------------------------------------------------- compute host

    private CompletableFuture<Void> startComputeHost() {
        final InetSocketAddress worldHostAddr =
                parseAddress(this.config.worldHostAddress, this.config.effectiveWorldHostPort());
        LOGGER.info("Starting EnigmaEngine as COMPUTE_HOST (instanceId={}, worldHost={})",
                this.identity().getInstanceId(), worldHostAddr);

        return RuntimeConnection
                .connect("compute-host", worldHostAddr, this.identity().getInstanceId().getMostSignificantBits())
                .thenCompose(connection -> {
                    this.runtimeConnection = connection;
                    connection.setMessageHandler(this::handleComputeHostMessage);
                    // If the link to the World Host dies (or the World Host is stopped), drop back
                    // into the lazy-start loop so the next tick reconnects. This is what makes the
                    // Compute Host independent of the World Host's lifecycle.
                    connection.setCloseListener(ignored -> this.onComputeHostConnectionLost());

                    final RemoteRegionExecutor existing = this.regionExecutor;
                    final boolean reused = existing != null && existing.isRunning();
                    final boolean[] freshlyCreated = {false};
                    final RemoteRegionExecutor executor;
                    if (reused) {
                        // The link came back after a World Host outage. Keep the remote regions
                        // exactly as they are and simply point them at the new link, otherwise the
                        // compute-held state (which is the whole point of the outage handling) would
                        // be torn down.
                        executor = existing;
                        executor.setConnection(connection);
                    } else {
                        executor = new RemoteRegionExecutor(this.config, this.identity(), connection,
                                this.generationManager, this.leaseManager, this.snapshotSerializer);
                        freshlyCreated[0] = true;
                        executor.start(server);
                        this.regionExecutor = executor;
                    }

                    this.networkGateway = new NetworkGateway(this.config, this.identity(), connection, executor);

                    // Announce ourselves and only report success once the World Host accepted us.
                    final ByteBuf payload = connection.getChannel().alloc().buffer();
                    payload.writeLong(this.identity().getInstanceId().getMostSignificantBits());
                    payload.writeInt(MessageEnvelope.VERSION);

                    return connection.sendAndWait(
                            MessageEnvelope.createControl(MessageEnvelope.MessageType.HANDSHAKE, payload),
                            10000L
                    ).thenAccept(response -> {
                        try {
                            final ByteBuf ack = response.getPayload();
                            final boolean accepted = ack.readBoolean();
                            final String detail = SnapshotSerializer.readString(ack);
                            if (!accepted) {
                                throw new IllegalStateException("World Host rejected handshake: " + detail);
                            }
                            this.worldHostLost = false;
                            this.started = true;
                            LOGGER.info(reused
                                            ? "Re-registered with World Host at {}; {} remote region(s) still ticking"
                                            : "Registered with World Host at {}",
                                    worldHostAddr, reused && this.regionExecutor != null
                                            ? this.regionExecutor.activeRegionCount() : 0);
                        } finally {
                            ReferenceCountUtil.release(response.getPayload());
                        }
                    }).whenComplete((ignored, error) -> {
                        if (error != null) {
                            // Roll the attempt back, otherwise every retry leaks a socket plus a
                            // lease-renewer task on a newly created executor.
                            this.rollbackComputeHostConnect(connection, executor, freshlyCreated[0]);
                        }
                    });
                });
    }

    private void rollbackComputeHostConnect(final RuntimeConnection connection,
                                            final RemoteRegionExecutor executor,
                                            final boolean createdThisAttempt) {
        if (createdThisAttempt) {
            executor.stop();
            if (this.regionExecutor == executor) {
                this.regionExecutor = null;
            }
        }
        if (this.runtimeConnection == connection) {
            this.runtimeConnection = null;
        }
        this.networkGateway = null;
        connection.close();
    }

    /**
     * Called (via the connection's close listener) when the World Host went away. The executor - and
     * with it every remote region and the compute-held state - is deliberately kept alive; the
     * lazy-start loop picks the connection back up after {@link #nextStartAttemptMs} and the
     * restarted World Host's ledger recovery brings the regions home.
     */
    private void onComputeHostConnectionLost() {
        if (!this.started) {
            return;
        }
        LOGGER.warn("Connection to the World Host was lost; remote regions keep ticking and the "
                + "Compute Host will reconnect in 5s");
        this.worldHostLost = true;
        this.started = false;
        this.startRequested = false;
        this.nextStartAttemptMs = System.currentTimeMillis() + 5000L;
        this.runtimeConnection = null;
    }

    private void handleComputeHostMessage(final RuntimeConnection connection, final MessageEnvelope message) {
        switch (message.getMessageType()) {
            case HANDSHAKE_ACK -> {
                // Already consumed by sendAndWait; reaching here means an unsolicited ack.
            }
            case HEARTBEAT -> {
                // Liveness only.
            }
            default -> {
                final RemoteRegionExecutor executor = this.regionExecutor;
                if (executor != null && executor.handleMessage(message)) {
                    return;
                }
                LOGGER.debug("Compute Host received unhandled {}", message.getMessageType());
            }
        }
    }

    // ------------------------------------------------------------------------- plumbing

    /**
     * Migrates {@code regionId} to the registered Compute Host {@code targetInstanceId}.
     * Only valid on the World Host, which is the side that owns the region data.
     *
     * @return {@code null} once the migration was started, otherwise the reason it could not start
     */
    public @org.jspecify.annotations.Nullable String startMigration(final long regionId, final long targetInstanceId) {
        if (!this.started) {
            return "distributed runtime is not started";
        }
        if (!this.config.isWorldHost()) {
            return "only the World Host can migrate regions; it owns the region data";
        }
        final RemoteRegionExecutor executor = this.regionExecutor;
        if (executor == null) {
            return "region executor is not available";
        }
        if (executor.isPreparingStop()) {
            return "the server is preparing to stop; no new migrations can start";
        }
        final RuntimeConnection target = this.getComputeHostConnection(targetInstanceId);
        if (target == null) {
            return "Compute Host " + targetInstanceId + " is not connected";
        }
        return executor.requestRegionMigration(regionId, target);
    }

    /**
     * Whether the World Host is currently running a prepare-stop return.
     */
    public boolean isPreparingStop() {
        final RemoteRegionExecutor executor = this.regionExecutor;
        return executor != null && executor.isPreparingStop();
    }

    /**
     * Whether the prepare-stop return drain has already finished. The halt hook consults this so the
     * halt that the drain itself fires (see {@link #haltAfterPrepareStop}) is not swallowed again.
     */
    public boolean isPrepareStopCompleted() {
        return this.prepareStopCompleted;
    }

    /**
     * Starts the prepare-stop sequence on the World Host: every forwarded (and every recovered) region
     * is returned from its Compute Host, then the server halts normally. Calling it again while a run
     * is in progress does nothing; the ordinary {@code stop} command funnels here through
     * {@code MinecraftServer.halt}.
     */
    public void prepareStop() {
        if (!this.config.isWorldHost()) {
            LOGGER.warn("prepare-stop requested but this instance is not the World Host; nothing to do");
            return;
        }
        if (!this.started || this.regionExecutor == null) {
            LOGGER.warn("prepare-stop requested but the distributed runtime is not started; stopping anyway");
            final MinecraftServer currentServer = this.server;
            if (currentServer != null) {
                currentServer.halt(false);
            }
            return;
        }
        this.regionExecutor.prepareStop(
                targetInstanceId -> this.computeHostConnections.get(targetInstanceId),
                this::haltAfterPrepareStop);
    }

    private void haltAfterPrepareStop() {
        // The drain is over; a halt fired from here is the pending one and must not be deferred again.
        this.prepareStopCompleted = true;
        final RemoteRegionExecutor executor = this.regionExecutor;
        final int forwarded = executor == null ? 0 : executor.handedOverCount();
        final int recovering = executor == null ? 0 : executor.recoveringRegionCount();
        if (forwarded > 0 || recovering > 0) {
            LOGGER.warn("Prepare stop finished with {} forwarded and {} recovering region(s) still outstanding; "
                            + "their state is preserved in the ledger and a later run (or the next start) can "
                            + "return them. Stopping now.",
                    forwarded, recovering);
        } else {
            LOGGER.info("All forwarded regions are back on the World Host; stopping the server");
        }
        final MinecraftServer currentServer = this.server;
        if (currentServer != null) {
            currentServer.halt(false);
        } else {
            MinecraftServer.getServer().halt(false);
        }
    }

    public void stop() {
        if (!this.started) {
            return;
        }
        this.started = false;
        // Allow a later start to bind again instead of being short circuited by the in-flight flag.
        this.startRequested = false;
        this.nextStartAttemptMs = 0L;

        final RemoteRegionExecutor executor = this.regionExecutor;
        if (executor != null) {
            executor.stop();
        }
        for (final RuntimeConnection connection : this.computeHostConnections.values()) {
            connection.close();
        }
        this.computeHostConnections.clear();

        final RuntimeConnection connection = this.runtimeConnection;
        if (connection != null) {
            connection.close();
        }
        LOGGER.info("Distributed runtime stopped");
    }

    public void tick() {
        // Called from the global tick handle every tick. Anything that escapes this method kills the
        // server's global tick scheduler, so every failure is contained here.
        try {
            this.tick0();
        } catch (final Throwable t) {
            if (this.started) {
                LOGGER.error("Uncaught failure while ticking the distributed runtime", t);
            } else {
                LOGGER.error("Uncaught failure while starting the distributed runtime; retrying in 5s", t);
                this.startRequested = false;
                this.nextStartAttemptMs = System.currentTimeMillis() + 5000L;
            }
        }
    }

    /**
     * Stops and detaches compute-runtime state. Runs on the World Host only while stopped and on
     * the Compute Host whenever its connection to the World Host is (re)establishing.
     */
    private void teardownComputeRuntime() {
        final RemoteRegionExecutor executor = this.regionExecutor;
        if (executor != null) {
            executor.stop();
            this.regionExecutor = null;
        }
        if (this.networkGateway != null) {
            this.networkGateway = null;
        }
        this.playerRouter = null;
    }

    private void tick0() {
        // Lazy start. The config is read during Bootstrap.bootStrap(), long before a MinecraftServer
        // exists, so the runtime cannot be started from the config load. The first tick happens once
        // the server and all of its worlds are up, which is the earliest moment the runtime can
        // safely be handed a server reference.
        if (!this.started && !this.startRequested) {
            if (!this.worldHostLost) {
                // Leftover compute-runtime state from a failed first start; torn down here (on the
                // global tick thread) instead of inside a netty close callback.
                this.teardownComputeRuntime();
            }
            // When the World Host simply went away, the executor - and with it every remote region
            // and the compute-held state - is deliberately kept alive across the outage.
            if (System.currentTimeMillis() < this.nextStartAttemptMs) {
                return;
            }
            final MinecraftServer currentServer = MinecraftServer.getServer();
            if (currentServer == null) {
                return;
            }
            start(currentServer).whenComplete((ignored, error) -> {
                if (error != null) {
                    LOGGER.error("Failed to start the distributed runtime; retrying in 5s", error);
                    // Lets the next tick try again, which makes the Compute Host independent of the
                    // exact order in which the two processes come up.
                    this.startRequested = false;
                    this.nextStartAttemptMs = System.currentTimeMillis() + 5000L;
                }
            });
            return;
        }
        if (!this.started) {
            return;
        }
        this.leaseManager.tick();

        final RemoteRegionExecutor executor = this.regionExecutor;
        if (executor != null) {
            executor.tick();
            this.maybeAutoMigrate(executor, System.currentTimeMillis());
        }
    }

    /**
     * Hands regions over to connected Compute Hosts on a fixed interval, World Host only and gated
     * by {@code autoMigrate*} config. Runs on the global tick thread inside the try/catch of
     * {@link #tick()}, so a failure here can never escape into the server's tick loop.
     */
    private void maybeAutoMigrate(final RemoteRegionExecutor executor, final long now) {
        final EnigmaDistributedConfig config = this.config;
        if (!config.autoMigrate || !config.isWorldHost()) {
            return;
        }
        if (executor.isPreparingStop()) {
            // Drain mode: during a prepare-stop nothing new may move to a Compute Host.
            return;
        }
        if (now - this.lastAutoMigrateMs < config.autoMigrateIntervalSeconds * 1000L) {
            return;
        }
        this.lastAutoMigrateMs = now;

        final List<RuntimeConnection> targets = new ArrayList<>(this.computeHostConnections.values());
        if (targets.isEmpty()) {
            LOGGER.debug("Auto migration skipped: no Compute Host connected");
            return;
        }

        final int active = executor.activeMigrationCount();
        int budget = Math.min(config.autoMigratePerRun, config.autoMigrateMaxConcurrent - active);
        if (budget <= 0) {
            LOGGER.debug("Auto migration skipped: {} migration(s) already active (limit {})",
                    active, config.autoMigrateMaxConcurrent);
            return;
        }

        // Drop empty husks first so the candidates below are real, tickable regions.
        executor.garbageCollectRegions();

        int started = 0;
        for (final long[] candidate : executor.topRegionsByChunks(budget)) {
            final long regionId = candidate[0];
            final long chunks = candidate[1];
            if (chunks < config.autoMigrateMinChunks) {
                // The list is sorted descending: nothing below qualifies either.
                break;
            }
            final RuntimeConnection target = targets.get(this.autoMigrateTargetCursor % targets.size());
            this.autoMigrateTargetCursor++;
            final String failure = this.startMigration(regionId, target.getRemoteInstanceId());
            if (failure == null) {
                started++;
                budget--;
                LOGGER.info("Auto migration: region {} ({} chunks) -> Compute Host {}",
                        regionId, chunks, target.getRemoteInstanceId());
            } else {
                LOGGER.warn("Auto migration: cannot start region {} ({} chunks): {}", regionId, chunks, failure);
            }
            if (budget <= 0) {
                break;
            }
        }
        if (started == 0) {
            LOGGER.debug("Auto migration pass found no region to migrate");
        }
    }

    public boolean isStarted() {
        return this.started;
    }

    public EnigmaDistributedConfig getConfig() {
        return this.config;
    }

    public InstanceIdentity getIdentity() {
        return this.identity();
    }

    public GenerationManager getGenerationManager() {
        return this.generationManager;
    }

    public LeaseManager getLeaseManager() {
        return this.leaseManager;
    }

    public SnapshotSerializer getSnapshotSerializer() {
        return this.snapshotSerializer;
    }

    public @org.jspecify.annotations.Nullable RemoteRegionExecutor getRegionExecutor() {
        return this.regionExecutor;
    }

    public @org.jspecify.annotations.Nullable NetworkGateway getNetworkGateway() {
        return this.networkGateway;
    }

    public @org.jspecify.annotations.Nullable PlayerRouter getPlayerRouter() {
        return this.playerRouter;
    }

    public @org.jspecify.annotations.Nullable RuntimeConnection getRuntimeConnection() {
        return this.runtimeConnection;
    }

    public MinecraftServer getServer() {
        return this.server;
    }

    public Collection<RuntimeConnection> getComputeHostConnections() {
        return new ArrayList<>(this.computeHostConnections.values());
    }

    public @org.jspecify.annotations.Nullable RuntimeConnection getComputeHostConnection(final long instanceId) {
        return this.computeHostConnections.get(instanceId);
    }

    private static InetSocketAddress parseAddress(final String address, final int defaultPort) {
        final int lastColon = address.lastIndexOf(':');
        if (lastColon > 0 && lastColon < address.length() - 1) {
            final String host = address.substring(0, lastColon);
            final String portPart = address.substring(lastColon + 1);
            try {
                return new InetSocketAddress(host, Integer.parseInt(portPart));
            } catch (NumberFormatException ignored) {
                // fall through and use the default port
            }
        }
        return new InetSocketAddress(address, defaultPort);
    }

    public static DistributedBootstrap getInstance() {
        return DistributedBootstrapHolder.INSTANCE;
    }

    private static class DistributedBootstrapHolder {
        private static final DistributedBootstrap INSTANCE = new DistributedBootstrap();
    }
}
