package io.canvasmc.canvas.distributed.runtime;

import io.canvasmc.canvas.distributed.config.EnigmaDistributedConfig;
import io.canvasmc.canvas.distributed.identity.InstanceIdentity;
import io.canvasmc.canvas.distributed.network.MessageEnvelope;
import io.canvasmc.canvas.distributed.network.RuntimeConnection;
import io.canvasmc.canvas.distributed.ownership.GenerationManager;
import io.canvasmc.canvas.distributed.ownership.LeaseManager;
import io.canvasmc.canvas.distributed.snapshot.RegionSnapshot;
import io.canvasmc.canvas.distributed.snapshot.RegionSnapshotUtil;
import io.canvasmc.canvas.distributed.snapshot.SnapshotSerializer;
import io.netty.buffer.ByteBuf;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs remotely owned regions on the Compute Host, and drives the migration state machine on the
 * World Host.
 *
 * <h2>How remote ticking actually works</h2>
 * The Compute Host is a complete Canvas server with its own {@code TickRegions} scheduler. The
 * scheduler is started once, globally, by {@code TickRegions.start()} during normal server startup.
 * Adding chunks to a level's regionizer is sufficient to get them scheduled: the regionizer calls
 * {@code TickRegions.onRegionActive}, which hands the region's schedule handle to the existing
 * scheduler. This class therefore does <em>not</em> create a second scheduler and does not invent
 * tick threads; it makes the assigned chunks part of the local regionizer and lets the server's own
 * scheduler pick them up.
 */
public final class RemoteRegionExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger("EnigmaRemoteExecutor");

    private final EnigmaDistributedConfig config;
    private final InstanceIdentity identity;
    /**
     * Compute Host: the single outbound link to the World Host. Intentionally volatile: when the
     * World Host goes away the executor is kept alive so remote regions keep ticking (and therefore
     * keep their data), and a later reconnection simply re-points this field at the new link.
     */
    private volatile @Nullable RuntimeConnection connection;
    private final GenerationManager generationManager;
    private final LeaseManager leaseManager;
    private final SnapshotSerializer snapshotSerializer;

    private final Map<Long, RemoteRegion> activeRegions = new ConcurrentHashMap<>();
    private final Map<Long, Migration> migrations = new ConcurrentHashMap<>();

    /** Regions whose snapshot has been written into the world's storage and may be ticked. */
    private final Set<Long> installedSnapshots = ConcurrentHashMap.newKeySet();
    /** Region definitions that arrived before their snapshot was installed. */
    private final Map<Long, PendingDefinition> pendingDefinitions = new ConcurrentHashMap<>();
    /**
     * World Host only: regions whose chunks this host handed over at commit time. The chunks are
     * removed from the local regionizer so the region stops ticking here; the bookkeeping is kept so
     * a failed release can be retried and so a disconnecting Compute Host can be reclaimed.
     */
    private final Map<Long, HandedOver> handedOver = new ConcurrentHashMap<>();

    /**
     * World Host only: in-flight return transfers (regionId -> future completing with {@code null}
     * on success or an error message). Guards against duplicate returns per region.
     */
    private final Map<Long, CompletableFuture<String>> pendingReturns = new ConcurrentHashMap<>();

    /**
     * World Host only: forwarded regions loaded from the persisted ledger after a restart. The next
     * connection of the recorded Compute Host triggers a {@link MessageEnvelope.MessageType#RETURN_REGION}
     * so the compute-side state comes home before anything can act on the stale local copy.
     */
    private final java.util.concurrent.CopyOnWriteArrayList<RecoveredRegion> recoveringRegions =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Whether a prepare-stop run (return everything, then stop) has been requested. Guarded so a
     * second /prepare-stop or a re-entrant halt cannot start a second run.
     */
    private final AtomicBoolean prepareStopStarted = new AtomicBoolean();

    private final ScheduledExecutorService leaseRenewer = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread t = new Thread(r, "EnigmaLeaseRenewer");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean running = new AtomicBoolean();
    private volatile @Nullable MinecraftServer server;

    public RemoteRegionExecutor(
            final EnigmaDistributedConfig config,
            final InstanceIdentity identity,
            final RuntimeConnection connection,
            final GenerationManager generationManager,
            final LeaseManager leaseManager,
            final SnapshotSerializer snapshotSerializer
    ) {
        this.config = config;
        this.identity = identity;
        this.connection = connection;
        this.generationManager = generationManager;
        this.leaseManager = leaseManager;
        this.snapshotSerializer = snapshotSerializer;
    }

    /**
     * Compute Host: swaps the executor onto a new link to the World Host after that link was lost and
     * re-established. Remote regions are deliberately not touched: they keep ticking (and therefore
     * keep their data) across the outage, so a return transfer can still bring their state home.
     */
    public void setConnection(final RuntimeConnection newConnection) {
        if (newConnection == null) {
            throw new IllegalArgumentException("connection must not be null");
        }
        this.connection = newConnection;
        LOGGER.info("RemoteRegionExecutor reattached to a new World Host connection");
    }

    public void start(final MinecraftServer server) {
        if (!this.running.compareAndSet(false, true)) {
            return;
        }
        this.server = server;

        this.leaseRenewer.scheduleAtFixedRate(this::renewLeases,
                this.config.leaseRenewalIntervalSeconds,
                this.config.leaseRenewalIntervalSeconds,
                TimeUnit.SECONDS);

        LOGGER.info("RemoteRegionExecutor started for instance {}", this.identity.getInstanceId());
    }

    public void stop() {
        if (!this.running.compareAndSet(true, false)) {
            return;
        }
        this.leaseRenewer.shutdownNow();
        // Make the forwarded-region ledger durable before it is dropped from memory: on the World
        // Host, handed-over regions must survive a crash or a normal stop that skipped prepare-stop.
        if (this.config.isWorldHost()) {
            this.persistLedger();
        }
        for (final RemoteRegion region : this.activeRegions.values()) {
            region.shutdown("runtime stopping");
        }
        this.activeRegions.clear();
        this.migrations.clear();
        this.installedSnapshots.clear();
        this.pendingDefinitions.clear();
        this.handedOver.clear();
        LOGGER.info("RemoteRegionExecutor stopped");
    }

    public void tick() {
        if (!this.running.get()) {
            return;
        }
        for (final Migration migration : this.migrations.values()) {
            migration.tick();
        }
        this.retryPendingReleases();
    }

    /**
     * Retries handed-over regions whose local chunk removal failed, at most once per second. Entries
     * whose chunks nobody owns anymore are simply marked released.
     */
    private void retryPendingReleases() {
        if (this.handedOver.isEmpty()) {
            return;
        }
        final long now = System.currentTimeMillis();
        for (final HandedOver entry : this.handedOver.values()) {
            if (entry.released || now - entry.lastAttemptMs < 1000L) {
                continue;
            }
            if (entry.chunkX.isEmpty() || !ownsChunk(entry.world, entry.chunkX.get(0), entry.chunkZ.get(0))) {
                entry.released = true;
                continue;
            }
            this.releaseChunks(entry);
        }
    }

    /**
     * Called on the World Host once a Compute Host completed the handshake.
     */
    public void onComputeHostRegistered(final RuntimeConnection computeConn) {
        final long instanceId = computeConn.getRemoteInstanceId();
        LOGGER.info("Compute Host {} is ready to receive migrations", instanceId);
        // A freshly restarted World Host uses its persisted ledger to bring the compute-side state
        // back the moment the recorded Compute Host reconnects.
        for (final RecoveredRegion recovered : this.recoveringRegions) {
            if (recovered.targetInstanceId != instanceId) {
                continue;
            }
            LOGGER.info("Recovering {} from reconnected Compute Host {} (return request sent)",
                    recovered, instanceId);
            if (this.recoveringRegions.remove(recovered)) {
                this.requestRecoveredRegionReturn(recovered, computeConn).whenComplete((failure, error) -> {
                    if (failure != null) {
                        this.recoveringRegions.addIfAbsent(recovered);
                        LOGGER.warn("Region {} could not be recovered from Compute Host {}: {}",
                                recovered.regionId, instanceId, failure);
                    }
                    this.persistLedger();
                });
            }
        }
    }

    /**
     * Called on the World Host when a Compute Host connection goes away. Everything the host
     * owned — leases, active regions, in-flight migrations to it, orphaned definitions and
     * installed snapshots — is rolled back so the region can be reclaimed or re-migrated.
     */
    public void onComputeHostDisconnected(final long hostInstanceId) {
        final int revokedLeases = this.leaseManager.revokeAllForOwner(hostInstanceId, "compute host disconnected");

        int shutdownRegions = 0;
        for (final Map.Entry<Long, RemoteRegion> entry : this.activeRegions.entrySet()) {
            final RemoteRegion region = entry.getValue();
            if (region.lease.ownerInstanceId != hostInstanceId) {
                continue;
            }
            region.shutdown("compute host " + hostInstanceId + " disconnected");
            this.activeRegions.remove(entry.getKey(), region);
            this.generationManager.removeRegion(entry.getKey());
            shutdownRegions++;
        }

        int droppedMigrations = 0;
        for (final Map.Entry<Long, Migration> entry : this.migrations.entrySet()) {
            final Migration migration = entry.getValue();
            if (migration.target.getRemoteInstanceId() != hostInstanceId) {
                continue;
            }
            if (this.migrations.remove(entry.getKey(), migration)) {
                this.leaseManager.forgetLease(migration.regionId);
                this.generationManager.removeRegion(migration.regionId);
                droppedMigrations++;
            }
        }

        // Regions this host took over had their chunks removed from the local regionizer at commit
        // time. Give them back, otherwise the area stays frozen on the World Host whenever the
        // Compute Host goes away.
        int reclaimedRegions = 0;
        int reclaimedChunks = 0;
        for (final Map.Entry<Long, HandedOver> entry : new ArrayList<>(this.handedOver.entrySet())) {
            final HandedOver handedOverEntry = entry.getValue();
            if (handedOverEntry.targetInstanceId != hostInstanceId) {
                continue;
            }
            reclaimedChunks += this.reclaim(handedOverEntry);
            reclaimedRegions++;
            this.handedOver.remove(entry.getKey(), handedOverEntry);
        }
        if (reclaimedRegions > 0) {
            this.persistLedger();
        }

        int droppedOrphans = 0;
        for (final long regionId : this.pendingDefinitions.keySet()) {
            if (!this.migrations.containsKey(regionId) && !this.activeRegions.containsKey(regionId)) {
                this.pendingDefinitions.remove(regionId);
                droppedOrphans++;
            }
        }
        for (final long regionId : this.installedSnapshots) {
            if (!this.migrations.containsKey(regionId) && !this.activeRegions.containsKey(regionId)) {
                this.installedSnapshots.remove(regionId);
                droppedOrphans++;
            }
        }

        LOGGER.warn("Compute Host {} disconnected: revoked {} lease(s), shut down {} region(s), dropped {} migration(s), "
                        + "reclaimed {} region(s) ({} chunk(s)), dropped {} orphan(s)",
                hostInstanceId, revokedLeases, shutdownRegions, droppedMigrations,
                reclaimedRegions, reclaimedChunks, droppedOrphans);
    }

    // -------------------------------------------------------------- lease maintenance

    private void renewLeases() {
        if (!this.running.get()) {
            return;
        }
        for (final RemoteRegion region : this.activeRegions.values()) {
            // Once the region ticks locally the World Host has forgotten the lease (handover is
            // complete); continuing to renew would just spam stale-lease warnings.
            if (region.tickHandle != null) {
                continue;
            }
            final LeaseManager.Lease lease = region.lease;
            if (lease == null) {
                continue;
            }
            final ByteBuf payload = this.connection.getChannel().alloc().buffer();
            payload.writeLong(lease.regionId);
            payload.writeLong(lease.leaseId);
            payload.writeLong(this.config.leaseTtlSeconds * 1000L);
            this.connection.sendControl(MessageEnvelope.MessageType.LEASE_RENEW, payload);
        }
    }

    // ------------------------------------------------------------ inbound dispatch

    /**
     * Handles one inbound message. Returns true if the message was consumed.
     */
    public boolean handleMessage(final MessageEnvelope message) {
        final ByteBuf payload = message.getPayload();
        switch (message.getMessageType()) {
            case LEASE_GRANT -> {
                handleLeaseGrant(payload);
                return true;
            }
            case REGION_DEFINITION -> {
                handleRegionDefinition(payload);
                return true;
            }
            case LEASE_REVOKE -> {
                handleLeaseRevoke(payload);
                return true;
            }
            case LEASE_RENEW -> {
                handleLeaseRenew(message, payload);
                return true;
            }
            case LEASE_RENEW_ACK -> {
                // Our own renewal was confirmed; nothing further to do.
                return true;
            }
            case FENCE_NOTICE -> {
                handleFenceNotice(payload);
                return true;
            }
            case SNAPSHOT_CHUNK, SNAPSHOT_COMPLETE -> {
                final io.netty.buffer.ByteBuf ack = this.snapshotSerializer.handleChunk(message);
                if (ack != null) {
                    this.connection.sendReply(message, message.getMessageType(), ack);
                } else {
                    // Nothing is expecting this frame; answer anyway so the sender does not stall.
                    final ByteBuf reject = io.netty.buffer.Unpooled.buffer();
                    reject.writeBoolean(false);
                    SnapshotSerializer.writeString(reject, "no snapshot transfer in progress");
                    this.connection.sendReply(message, message.getMessageType(), reject);
                }
                return true;
            }
            case MIGRATION_VERIFY -> {
                handleMigrationVerify(message, payload);
                return true;
            }
            case RETURN_REGION -> {
                handleReturnRegion(message, payload);
                return true;
            }
            case MIGRATION_VERIFY_ACK -> {
                // Only meaningful on the initiator, where it is matched against a pending request.
                return true;
            }
            case CROSS_REGION_MSG, ENTITY_TRANSFER, CHUNK_UPDATE -> {
                handleCrossRegionPayload(message);
                return true;
            }
            case PLAYER_ROUTE -> {
                handlePlayerRoute(payload);
                return true;
            }
            case HANDSHAKE_ACK, HEARTBEAT -> {
                return true;
            }
            default -> {
                LOGGER.debug("RemoteRegionExecutor ignoring {}", message.getMessageType());
                return false;
            }
        }
    }

    private void handleLeaseGrant(final ByteBuf payload) {
        final long regionId = payload.readLong();
        final long leaseId = payload.readLong();
        final long generation = payload.readLong();
        final long fenceToken = payload.readLong();
        final long ownerInstanceId = payload.readLong();
        final long ttlMs = payload.readLong();

        if (ownerInstanceId != this.identity.getInstanceId().getMostSignificantBits()) {
            LOGGER.warn("Lease grant for region {} names owner {} but we are {}; ignoring",
                    regionId, ownerInstanceId, this.identity.getInstanceId().getMostSignificantBits());
            return;
        }

        final boolean installed = this.leaseManager.acceptGrantedLease(
                regionId, leaseId, generation, fenceToken, ownerInstanceId, ttlMs, this.connection);
        if (!installed) {
            LOGGER.warn("Lease for region {} was not installed", regionId);
            return;
        }

        final LeaseManager.Lease lease = this.leaseManager.getLease(regionId).orElse(null);
        if (lease == null) {
            LOGGER.error("Lease for region {} vanished right after being installed", regionId);
            return;
        }

        LOGGER.info("Lease acquired for region {}: {}", regionId, lease);

        // The World Host ships the snapshot right after the grant and follows it with the region
        // definition. Arm the receiver now so the incoming frames land in a pending transfer.
        this.snapshotSerializer.expectSnapshot(regionId)
                .thenAccept(snapshot -> {
                    final MinecraftServer currentServer = this.server;
                    if (currentServer == null) {
                        LOGGER.error("Received snapshot for region {} but no server reference is set", regionId);
                        return;
                    }
                    final ServerLevel overworld = currentServer.getLevel(ServerLevel.OVERWORLD);
                    if (overworld == null) {
                        LOGGER.error("Received snapshot for region {} but the overworld is not loaded", regionId);
                        return;
                    }
                    if (lease.generation != this.leaseManager.getLease(regionId).map(l -> l.generation).orElse(-1L)) {
                        LOGGER.error("Snapshot for region {} arrived after the lease moved on; discarding", regionId);
                        return;
                    }

                    // The snapshot has to be in the world's storage before the region definition makes the
                    // Compute Host start ticking these chunks, otherwise the loader would generate
                    // fresh chunks over the migrated state.
                    RegionSnapshotUtil.restore(overworld, snapshot).whenComplete((ignored, error) -> {
                        if (error != null) {
                            LOGGER.error("Failed to install the snapshot for region {}", regionId, error);
                            return;
                        }
                        this.installedSnapshots.add(regionId);
                        LOGGER.info("Snapshot for region {} installed", regionId);
                        // The definition usually arrived while we were installing; if so it was parked.
                        activateDeferred(regionId);
                    });
                })
                .whenComplete((snapshot, error) -> {
                    if (error != null) {
                        LOGGER.error("Snapshot transfer for region {} failed", regionId, error);
                    }
                });
    }

    private void handleRegionDefinition(final ByteBuf payload) {
        final long regionId = payload.readLong();
        final long generation = payload.readLong();
        final int chunkCount = payload.readInt();
        if (chunkCount < 0) {
            LOGGER.error("Region definition for region {} declares {} chunks", regionId, chunkCount);
            return;
        }
        if (chunkCount == 0) {
            LOGGER.warn("Region definition for region {} contains no chunks; nothing to schedule", regionId);
            return;
        }
        if (chunkCount > MAX_CHUNKS_PER_REGION) {
            LOGGER.error("Region definition for region {} declares {} chunks which exceeds the limit of {}",
                    regionId, chunkCount, MAX_CHUNKS_PER_REGION);
            return;
        }

        final List<Integer> chunkX = new ArrayList<>(chunkCount);
        final List<Integer> chunkZ = new ArrayList<>(chunkCount);
        for (int i = 0; i < chunkCount; i++) {
            chunkX.add(payload.readInt());
            chunkZ.add(payload.readInt());
        }

        final LeaseManager.Lease lease = this.leaseManager.getLease(regionId).orElse(null);
        if (lease == null) {
            LOGGER.warn("Received region definition for region {} but no lease is installed", regionId);
            return;
        }
        if (lease.generation != generation) {
            LOGGER.warn("Region definition for region {} carries generation {} but our lease is generation {}",
                    regionId, generation, lease.generation);
            return;
        }
        if (this.activeRegions.containsKey(regionId)) {
            LOGGER.warn("Region {} is already active locally; ignoring duplicate definition", regionId);
            return;
        }

        // The chunks must not start ticking before the migrated state is in place, so a definition
        // that overtakes its snapshot is parked until the restore finishes.
        if (!this.installedSnapshots.contains(regionId)) {
            this.pendingDefinitions.put(regionId, new PendingDefinition(chunkX, chunkZ, generation));
            LOGGER.info("Region definition for region {} arrived before its snapshot; deferring activation",
                    regionId);
            return;
        }

        activateRegion(regionId, lease, generation, chunkX, chunkZ);
    }

    /**
     * Activates a definition that was parked until its snapshot was installed.
     */
    private void activateDeferred(final long regionId) {
        final PendingDefinition pending = this.pendingDefinitions.remove(regionId);
        if (pending == null) {
            return;
        }
        final LeaseManager.Lease lease = this.leaseManager.getLease(regionId).orElse(null);
        if (lease == null) {
            LOGGER.warn("Dropping deferred definition for region {}: the lease is gone", regionId);
            return;
        }
        if (lease.generation != pending.generation) {
            LOGGER.warn("Dropping deferred definition for region {}: generation moved from {} to {}",
                    regionId, pending.generation, lease.generation);
            return;
        }
        activateRegion(regionId, lease, pending.generation, pending.chunkX, pending.chunkZ);
    }

    private record PendingDefinition(List<Integer> chunkX, List<Integer> chunkZ, long generation) {
    }

    /**
     * One handed-over region on the World Host: the chunk list that was given to {@code target}
     * and whether the local regionizer gave it up yet.
     */
    private static final class HandedOver {
        private final long regionId;
        private final long targetInstanceId;
        private final ServerLevel world;
        private final List<Integer> chunkX;
        private final List<Integer> chunkZ;
        private volatile boolean released;
        private volatile long lastAttemptMs;

        private HandedOver(final long regionId, final long targetInstanceId, final ServerLevel world,
                           final List<Integer> chunkX, final List<Integer> chunkZ) {
            this.regionId = regionId;
            this.targetInstanceId = targetInstanceId;
            this.world = world;
            this.chunkX = List.copyOf(chunkX);
            this.chunkZ = List.copyOf(chunkZ);
        }
    }

    /**
     * A forwarded region recorded in the persisted ledger by a previous process. The region id is
     * only meaningful to the Compute Host that still ticks it; the world and chunk lists identify
     * what comes back on the World Host once the return transfer arrives.
     */
    private record RecoveredRegion(long regionId, long targetInstanceId, String worldPath,
                                   List<Integer> chunkX, List<Integer> chunkZ) {
        RecoveredRegion {
            chunkX = List.copyOf(chunkX);
            chunkZ = List.copyOf(chunkZ);
        }
    }

    /**
     * Registers the assigned chunks with the Compute Host's own regionizer. The server's existing
     * {@code TickRegions} scheduler picks them up through {@code onRegionActive}.
     */
    private void activateRegion(
            final long regionId,
            final LeaseManager.Lease lease,
            final long generation,
            final List<Integer> chunkX,
            final List<Integer> chunkZ
    ) {
        final MinecraftServer currentServer = this.server;
        if (currentServer == null) {
            LOGGER.error("Cannot activate region {}: server reference is not set", regionId);
            return;
        }
        final ServerLevel overworld = currentServer.getLevel(ServerLevel.OVERWORLD);
        if (overworld == null) {
            LOGGER.error("Cannot activate region {}: overworld is not loaded", regionId);
            return;
        }

        final ThreadedRegionizer<?, ?> regionizer = overworld.regioniser;
        final int sectionShift = regionizer.sectionChunkShift;

        try {
            int preOwned = 0;
            int alreadyOwned = 0;
            ThreadedRegionizer.ThreadedRegion<?, ?> activated = null;
            for (int i = 0; i < chunkX.size(); i++) {
                final int cx = chunkX.get(i);
                final int cz = chunkZ.get(i);
                // The Compute Host may already tick this chunk inside one of its own regions (e.g.
                // a spawn region or a region the snapshot restore loaded). Pre-existing owners must
                // hand the chunk over: remove it there, otherwise addChunk throws. The activated
                // region itself is excluded: once its section maps the block around one of its
                // neighbours, getRegionAtSynchronised reports it as owner even for not-yet-added
                // neighbours, and removing those is both unnecessary and crashes section.removeChunk.
                final ThreadedRegionizer.ThreadedRegion<?, ?> existing =
                        regionizer.getRegionAtSynchronised(cx, cz);
                if (existing != null && existing != activated) {
                    regionizer.removeChunk(cx, cz);
                    preOwned++;
                    regionizer.addChunk(cx, cz);
                } else if (existing != null) {
                    // The section is owned by the region we are activating. The chunk itself may
                    // already sit in it (then addChunk rightfully refuses and we keep it) or not
                    // (then we add it). Let addChunk tell us which one it is.
                    try {
                        regionizer.addChunk(cx, cz);
                    } catch (final IllegalStateException alreadyPresent) {
                        if (!alreadyPresent.getMessage().contains("already has the chunk")) {
                            throw alreadyPresent;
                        }
                        alreadyOwned++;
                    }
                } else {
                    // No owner at all: straight add.
                    regionizer.addChunk(cx, cz);
                }
                if (activated == null) {
                    activated = regionizer.getRegionAtSynchronised(cx, cz);
                }
            }
            if (preOwned > 0) {
                LOGGER.info("Took over {} chunk(s) that were owned by a local region", preOwned);
            }
            if (alreadyOwned > 0 && preOwned == 0) {
                LOGGER.info("Activated {} chunk(s) already present in the local regionizer", alreadyOwned);
            }

            final ThreadedRegionizer.ThreadedRegion<?, ?> region =
                    regionizer.getRegionAtSynchronised(chunkX.get(0), chunkZ.get(0));
            if (region == null) {
                LOGGER.error("Chunks for region {} were added but no region owns ({}, {})",
                        regionId, chunkX.get(0), chunkZ.get(0));
                rollback(regionizer, chunkX, chunkZ);
                return;
            }

            final RemoteRegion remote = new RemoteRegion(regionId, lease, generation, overworld,
                    regionizer, region, chunkX, chunkZ);
            this.activeRegions.put(regionId, remote);

            final long[] ownedChunks = region.getOwnedPackedChunkPositions();
            LOGGER.info("Region {} is now ticking locally: {} chunk(s) requested, {} owned, "
                            + "{} owned sections, grid shift {}",
                    regionId, chunkX.size(), ownedChunks.length,
                    region.getOwnedSections().size(), sectionShift);

            final TickRegions.TickRegionData data = dataOf(region);
            if (data != null) {
                remote.tickHandle = data.getRegionSchedulingHandle();
                LOGGER.info("Region {} scheduled through the server TickRegions scheduler (handle={})",
                        regionId, remote.tickHandle != null);
            } else {
                LOGGER.warn("Region {} has no TickRegionData; it will be scheduled on next activation", regionId);
            }
        } catch (final Throwable t) {
            LOGGER.error("Failed to activate region {}", regionId, t);
            rollback(regionizer, chunkX, chunkZ);
        }
    }

    private static TickRegions.TickRegionData dataOf(
            final ThreadedRegionizer.ThreadedRegion<?, ?> region
    ) {
        try {
            @SuppressWarnings("unchecked")
            final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> typed =
                    (ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>) region;
            return typed.getData();
        } catch (final ClassCastException e) {
            return null;
        }
    }

    private static void rollback(
            final ThreadedRegionizer<?, ?> regionizer,
            final List<Integer> chunkX,
            final List<Integer> chunkZ
    ) {
        for (int i = 0; i < chunkX.size(); i++) {
            try {
                regionizer.removeChunk(chunkX.get(i), chunkZ.get(i));
            } catch (final Throwable ignored) {
                // best effort
            }
        }
    }

    /**
     * Applies a renewal sent by the peer. This is what keeps the World Host from letting a lease that
     * the Compute Host is still ticking expire out from under it.
     */
    private void handleLeaseRenew(final MessageEnvelope message, final ByteBuf payload) {
        final long regionId = payload.readLong();
        final long leaseId = payload.readLong();
        final long ttlMs = payload.readLong();

        final boolean valid = this.leaseManager.applyRemoteRenewal(regionId, leaseId, ttlMs);
        final ByteBuf ack = io.netty.buffer.Unpooled.buffer(Long.BYTES + 1);
        ack.writeLong(regionId);
        ack.writeBoolean(valid);
        this.connection.sendReply(message, MessageEnvelope.MessageType.LEASE_RENEW_ACK, ack);
    }

    private void handleLeaseRevoke(final ByteBuf payload) {
        final long regionId = payload.readLong();
        payload.readLong(); // leaseId
        final long generation = payload.readLong();
        payload.readLong(); // fenceToken

        final RemoteRegion region = this.activeRegions.remove(regionId);
        if (region != null) {
            region.shutdown("lease revoked at generation " + generation);
        }
        this.installedSnapshots.remove(regionId);
        this.pendingDefinitions.remove(regionId);
        this.snapshotSerializer.abort(regionId,
                new IllegalStateException("lease revoked at generation " + generation));
        LOGGER.info("Lease revoked for region {} at generation {}", regionId, generation);
    }

    private void handleFenceNotice(final ByteBuf payload) {
        final long regionId = payload.readLong();
        final long leaseId = payload.readLong();
        final long fenceToken = payload.readLong();
        final long newGeneration = payload.readLong();

        // A newer generation exists, so move the view forward. setGeneration is monotonic, so an
        // out-of-order notice can never walk the view backwards.
        if (!this.generationManager.setGeneration(regionId, newGeneration)) {
            LOGGER.debug("Fence for region {} carries generation {} which we already passed", regionId, newGeneration);
        }
        final RemoteRegion region = this.activeRegions.remove(regionId);
        if (region != null) {
            region.shutdown("fenced (leaseId=" + leaseId + ", fence=" + fenceToken
                    + ", newGeneration=" + newGeneration + ")");
        }
        this.installedSnapshots.remove(regionId);
        this.pendingDefinitions.remove(regionId);
        this.snapshotSerializer.abort(regionId, new IllegalStateException("fenced"));
        LOGGER.warn("Fenced region {}: leaseId={} fenceToken={} newGeneration={}",
                regionId, leaseId, fenceToken, newGeneration);
    }

    private void handleCrossRegionPayload(final MessageEnvelope message) {
        final ByteBuf payload = message.getPayload();
        final long regionId = payload.readLong();
        final long generation = payload.readLong();

        final RemoteRegion region = this.activeRegions.get(regionId);
        if (region == null) {
            LOGGER.debug("Dropping {} for region {} which we do not own", message.getMessageType(), regionId);
            return;
        }
        if (region.lease.generation != generation) {
            LOGGER.warn("Dropping {} for region {} at stale generation {} (current {})",
                    message.getMessageType(), regionId, generation, region.lease.generation);
            return;
        }
        region.crossRegionMessagesReceived++;
        // The actual dispatch onto the owning tick thread requires the region's task queue; that is
        // tracked separately and is not wired yet.
        LOGGER.debug("Cross-region {} for region {} accepted ({} total)", message.getMessageType(), regionId,
                region.crossRegionMessagesReceived);
    }

    private void handlePlayerRoute(final ByteBuf payload) {
        payload.readInt(); // playerId
        LOGGER.debug("Player route message received ({} bytes)", payload.readableBytes());
    }

    /**
     * The World Host asks the Compute Host to read the state of one block on the migrated region's
     * owning tick thread and report it back. This is the visible proof that the handover actually
     * moved state: the reply is rendered into the World Host console by the initiator.
     */
    private void handleMigrationVerify(final MessageEnvelope message, final ByteBuf payload) {
        final long regionId = payload.readLong();
        final int x = payload.readInt();
        final int y = payload.readInt();
        final int z = payload.readInt();

        final String report;
        try {
            final MinecraftServer currentServer = this.server;
            if (currentServer == null) {
                report = "verify@" + x + "," + y + "," + z + ": no server reference (region " + regionId + ")";
            } else {
                final ServerLevel overworld = currentServer.getLevel(ServerLevel.OVERWORLD);
                if (overworld == null) {
                    report = "verify@" + x + "," + y + "," + z + ": overworld not loaded";
                } else {
                    report = readBlockReport(overworld, x, y, z);
                }
            }
        } catch (final Throwable t) {
            LOGGER.error("Failed to run migration verify for region {}", regionId, t);
            final ByteBuf ack = io.netty.buffer.Unpooled.buffer();
            SnapshotSerializer.writeString(ack, "verify@error: " + t);
            this.connection.sendReply(message, MessageEnvelope.MessageType.MIGRATION_VERIFY_ACK, ack);
            return;
        }

        final ByteBuf ack = io.netty.buffer.Unpooled.buffer();
        SnapshotSerializer.writeString(ack, report);
        this.connection.sendReply(message, MessageEnvelope.MessageType.MIGRATION_VERIFY_ACK, ack);
    }

    /**
     * Reads {@code (x, y, z)} on the tick thread that owns the chunk, the same way the debug
     * subcommand does. Returns a self-describing string; never throws.
     */
    private static String readBlockReport(final ServerLevel world, final int x, final int y, final int z) {
        final StringBuilder report = new StringBuilder();
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            io.papermc.paper.threadedregions.RegionizedServer.getInstance().taskQueue
                    .queueOrExecuteTickTask(world, x >> 4, z >> 4, () -> {
                        try {
                            report.append("verify@").append(x).append(",").append(y).append(",").append(z).append(" = ")
                                    .append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(
                                            world.getBlockState(new net.minecraft.core.BlockPos(x, y, z)).getBlock()))
                                    .append(" fullChunk=").append(world.getChunkIfLoaded(x >> 4, z >> 4) != null);
                            final net.minecraft.world.level.block.entity.BlockEntity blockEntity =
                                    world.getBlockEntity(new net.minecraft.core.BlockPos(x, y, z));
                            if (blockEntity instanceof net.minecraft.world.Container container) {
                                report.append(" items=[");
                                boolean first = true;
                                for (int i = 0; i < Math.min(container.getContainerSize(), 27); i++) {
                                    final net.minecraft.world.item.ItemStack stack = container.getItem(i);
                                    if (stack.isEmpty()) {
                                        continue;
                                    }
                                    if (!first) {
                                        report.append(", ");
                                    }
                                    first = false;
                                    report.append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(
                                                    stack.getItem()))
                                            .append("x").append(stack.getCount());
                                }
                                report.append("]");
                            }
                        } catch (final Throwable t) {
                            failure.set(t);
                        } finally {
                            latch.countDown();
                        }
                    });
        } catch (final Throwable t) {
            failure.set(t);
            latch.countDown();
        }
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                return "verify@" + x + "," + y + "," + z + ": timed out waiting for the region thread";
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return "verify@" + x + "," + y + "," + z + ": interrupted";
        }
        if (failure.get() != null) {
            return "verify@error: " + failure.get();
        }
        return report.toString();
    }

    // ------------------------------------------------------------ region return (both hosts)

    /**
     * World Host inbound handler for the snapshot frames a Compute Host sends while returning a
     * region. They are routed into the same {@link SnapshotSerializer} pipeline that migrations use,
     * so an {@code expectSnapshot} armed before the return request assembles and verifies them.
     */
    public boolean handleWorldHostSnapshotFrame(final RuntimeConnection connection, final MessageEnvelope message) {
        final ByteBuf ack = this.snapshotSerializer.handleChunk(message);
        if (ack != null) {
            connection.sendReply(message, message.getMessageType(), ack);
        } else {
            // Nothing is expecting this frame; answer anyway so the sender does not stall.
            final ByteBuf reject = io.netty.buffer.Unpooled.buffer();
            reject.writeBoolean(false);
            SnapshotSerializer.writeString(reject, "no return snapshot transfer in progress");
            connection.sendReply(message, message.getMessageType(), reject);
        }
        return true;
    }

    /**
     * World Host: outcome of a {@code RETURN_REGION} request. A negative ack finishes any pending
     * return immediately (the peer never held the region or could not capture it); a positive ack is
     * a courtesy after the snapshot frames, whose authoritative confirmation is the restore.
     */
    public boolean handleWorldHostReturnAck(final MessageEnvelope message) {
        final ByteBuf payload = message.getPayload();
        final long regionId = payload.readLong();
        final boolean ok = payload.readBoolean();
        final String detail = SnapshotSerializer.readString(payload);
        if (ok) {
            return true;
        }
        LOGGER.warn("Compute Host could not return region {}: {}", regionId, detail);
        this.snapshotSerializer.abort(regionId, new IllegalStateException(detail));
        this.finishReturn(regionId, detail);
        // The compute-side state is not coming back; reclaim the local (pre-migration) copy.
        this.reclaimAndForget(regionId);
        return true;
    }

    /**
     * Compute Host: the World Host asks for its region back. The region is captured on its own tick
     * threads (exactly like a migration snapshot) and shipped back; local ticking is stopped and the
     * success ack sent only after the transfer completed.
     */
    private void handleReturnRegion(final MessageEnvelope message, final ByteBuf payload) {
        final long regionId = payload.readLong();
        final RuntimeConnection conn = this.connection;
        if (conn == null) {
            return;
        }
        final RemoteRegion region = this.activeRegions.get(regionId);
        if (region == null) {
            sendReturnAck(conn, message, regionId, false,
                    "region " + regionId + " is not installed on this Compute Host");
            return;
        }
        final ThreadedRegionizer.ThreadedRegion<?, ?> threadedRegion = region.threadedRegion;
        if (threadedRegion == null) {
            sendReturnAck(conn, message, regionId, false,
                    "region " + regionId + " has no live tick backing; cannot capture its state");
            return;
        }
        final LeaseManager.Lease lease = region.lease;
        final RegionSnapshot.OwnershipMetadata ownership = new RegionSnapshot.OwnershipMetadata(
                lease.leaseId, lease.fenceToken, lease.generation, lease.ownerInstanceId);
        LOGGER.info("World Host requested the return of region {} ({} chunk(s)); capturing it back",
                regionId, region.chunkX.size());

        RegionSnapshotUtil.capture(regionId, threadedRegion, ownership).whenComplete((snapshot, error) -> {
            if (error != null) {
                LOGGER.error("Failed to capture region {} for the return transfer", regionId, error);
                sendReturnAck(conn, message, regionId, false, "capture failed: " + error);
                return;
            }
            this.snapshotSerializer.sendSnapshot(conn, snapshot, lease.generation, lease.leaseId, lease.fenceToken,
                            (sent, total) -> LOGGER.debug("Returning region {}: {}/{} frame(s)", regionId, sent, total))
                    .whenComplete((ignored, sendError) -> {
                        if (sendError != null) {
                            // The World Host never received the state; keep ticking so a later attempt can.
                            LOGGER.error("Return transfer of region {} failed; keeping the region ticking",
                                    regionId, sendError);
                            sendReturnAck(conn, message, regionId, false, "transfer failed: " + sendError);
                            return;
                        }
                        region.shutdown("returned to the world host");
                        this.activeRegions.remove(regionId, region);
                        this.installedSnapshots.remove(regionId);
                        this.pendingDefinitions.remove(regionId);
                        this.leaseManager.forgetLease(regionId);
                        this.generationManager.removeRegion(regionId);
                        sendReturnAck(conn, message, regionId, true,
                                "returned " + snapshot.getActualChunkCount() + " chunk(s)");
                    });
        });
    }

    private static void sendReturnAck(final RuntimeConnection conn, final MessageEnvelope request,
                                      final long regionId, final boolean ok, final String detail) {
        final ByteBuf ack = conn.getChannel().alloc().buffer(Long.BYTES + 1);
        ack.writeLong(regionId);
        ack.writeBoolean(ok);
        SnapshotSerializer.writeString(ack, detail);
        conn.sendReply(request, MessageEnvelope.MessageType.RETURN_REGION_ACK, ack);
    }

    // ------------------------------------------------------------ return (world host)

    /**
     * Arms the snapshot receiver and asks {@code target} to capture and ship region {@code regionId}
     * back. The returned future completes with {@code null} once the region's state was restored
     * into the World Host world, otherwise with the reason it could not be.
     */
    public CompletableFuture<String> requestRegionReturn(final long regionId, final RuntimeConnection target) {
        final HandedOver entry = this.handedOver.get(regionId);
        if (target == null || !target.isOpen()) {
            return CompletableFuture.completedFuture("target connection is not open");
        }
        if (entry == null) {
            return CompletableFuture.completedFuture("region " + regionId + " is not handed over to a Compute Host");
        }
        if (entry.targetInstanceId != target.getRemoteInstanceId()) {
            return CompletableFuture.completedFuture("region " + regionId + " is forwarded to Compute Host "
                    + entry.targetInstanceId + ", not " + target.getRemoteInstanceId());
        }
        final CompletableFuture<String> result = new CompletableFuture<>();
        final CompletableFuture<String> existing = this.pendingReturns.putIfAbsent(regionId, result);
        if (existing != null) {
            return CompletableFuture.completedFuture("a return for region " + regionId + " is already in progress");
        }

        // Arm the receiver before the request goes out so the incoming frames land in a transfer.
        this.snapshotSerializer.expectSnapshot(regionId)
                .whenComplete((snapshot, error) -> {
                    if (error != null) {
                        this.finishReturn(regionId, "snapshot transfer failed: " + error);
                        return;
                    }
                    this.installReturnedRegion(regionId, snapshot);
                });

        final ByteBuf payload = target.getChannel().alloc().buffer(Long.BYTES);
        payload.writeLong(regionId);
        target.sendControl(MessageEnvelope.MessageType.RETURN_REGION, payload);

        // Bound the whole round trip; afterwards a pending receive is aborted too.
        this.leaseRenewer.schedule(() -> {
            if (!result.isDone()) {
                this.snapshotSerializer.abort(regionId,
                        new IllegalStateException("return of region " + regionId + " timed out"));
                this.finishReturn(regionId, "return of region " + regionId + " timed out");
            }
        }, RETURN_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        return result;
    }

    private void finishReturn(final long regionId, final @Nullable String failure) {
        final CompletableFuture<String> future = this.pendingReturns.remove(regionId);
        if (future != null) {
            future.complete(failure);
        }
    }

    /**
     * Restores the returned snapshot into the world the region was handed over from, re-attaches
     * still-loaded chunks to the regionizer and drops the handed-over record.
     */
    private void installReturnedRegion(final long regionId, final RegionSnapshot snapshot) {
        final HandedOver entry = this.handedOver.get(regionId);
        if (entry == null || entry.world == null) {
            this.finishReturn(regionId, "handed-over record for region " + regionId + " is gone");
            return;
        }
        RegionSnapshotUtil.restore(entry.world, snapshot).whenComplete((ignored, error) -> {
            if (error != null) {
                // Chunks may still be loaded on this host (a player re-entered before the return).
                // The Compute Host keeps the region, so a retried /prepare-stop can complete the
                // return; the ledger is unchanged, nothing is lost.
                LOGGER.warn("Return of region {} could not be installed: {}", regionId, error.toString());
                this.finishReturn(regionId, "return restore failed: " + error);
                return;
            }
            int reattached = 0;
            try {
                reattached = this.reclaim(entry);
            } catch (final Throwable t) {
                LOGGER.warn("Failed to re-attach chunks of returned region {}: {}", regionId, t.toString());
            }
            final String worldPath = entry.world.dimension().identifier().getPath();
            if (this.handedOver.remove(regionId, entry)) {
                this.persistLedger();
            }
            LOGGER.info("Region {} returned to the World Host: {} chunk(s) restored into {}, {} re-attached",
                    regionId, snapshot.getActualChunkCount(), worldPath, reattached);
            this.finishReturn(regionId, null);
        });
    }

    /**
     * Reclaims the local copy of a handed-over region whose compute-side state cannot come back (the
     * peer rejected the return or never had the region). Same best-effort recovery as the disconnect
     * path.
     */
    private void reclaimAndForget(final long regionId) {
        final HandedOver entry = this.handedOver.get(regionId);
        if (entry == null || !this.handedOver.remove(regionId, entry)) {
            return;
        }
        try {
            this.reclaim(entry);
        } catch (final Throwable t) {
            LOGGER.warn("Failed to reclaim region {} after a failed return: {}", regionId, t.toString());
        }
        this.persistLedger();
    }

    // ---------------------------------------------------------------- stop preparation

    public boolean isPreparingStop() {
        return this.prepareStopStarted.get();
    }

    public int recoveringRegionCount() {
        return this.recoveringRegions.size();
    }

    /**
     * Runs the full prepare-stop sequence: every forwarded and every recovered region is brought
     * back to this World Host, then {@code onComplete} runs. Returns run sequentially; a region whose
     * Compute Host is not connected is reclaimed from the local copy instead. {@code onComplete} runs
     * exactly once, on the callback thread of the last operation.
     */
    public void prepareStop(final java.util.function.LongFunction<RuntimeConnection> connectionResolver,
                            final Runnable onComplete) {
        if (!this.prepareStopStarted.compareAndSet(false, true)) {
            return;
        }
        final int forwarded = this.handedOver.size();
        final int recovering = this.recoveringRegions.size();
        LOGGER.info("Prepare stop: returning {} forwarded and {} recovering region(s) to the World Host",
                forwarded, recovering);
        final List<Long> queue = new ArrayList<>(forwarded + recovering);
        queue.addAll(this.handedOver.keySet());
        for (final RecoveredRegion recovered : this.recoveringRegions) {
            queue.add(recovered.regionId);
        }
        this.drainReturnQueue(queue, 0, connectionResolver, () -> {
            this.persistLedger();
            if (this.handedOver.isEmpty() && this.recoveringRegions.isEmpty()) {
                LOGGER.info("Prepare stop complete: all regions are back on the World Host");
            } else {
                LOGGER.warn("Prepare stop finished with {} forwarded and {} recovering region(s) outstanding; "
                                + "they will be returned once their Compute Host connects again",
                        this.handedOver.size(), this.recoveringRegions.size());
            }
            onComplete.run();
        });
    }

    private void drainReturnQueue(final List<Long> queue, final int index,
                                  final java.util.function.LongFunction<RuntimeConnection> connectionResolver,
                                  final Runnable onComplete) {
        if (index >= queue.size()) {
            onComplete.run();
            return;
        }
        final long regionId = queue.get(index);
        this.advanceOneReturn(regionId, connectionResolver,
                () -> this.drainReturnQueue(queue, index + 1, connectionResolver, onComplete));
    }

    private void advanceOneReturn(final long regionId,
                                  final java.util.function.LongFunction<RuntimeConnection> connectionResolver,
                                  final Runnable next) {
        final HandedOver entry = this.handedOver.get(regionId);
        if (entry != null) {
            final RuntimeConnection target = connectionResolver.apply(entry.targetInstanceId);
            if (target == null || !target.isOpen()) {
                LOGGER.warn("Cannot return region {}: Compute Host {} is not connected; "
                                + "reclaiming from the local copy",
                        regionId, entry.targetInstanceId);
                this.reclaimAndForget(regionId);
                next.run();
                return;
            }
            this.requestRegionReturn(regionId, target).whenComplete((failure, error) -> {
                if (failure != null) {
                    LOGGER.warn("Region {} could not be returned: {}", regionId, failure);
                }
                next.run();
            });
            return;
        }
        this.returnOrReclaimRecovered(regionId, connectionResolver, next);
    }

    private void returnOrReclaimRecovered(final long regionId,
                                          final java.util.function.LongFunction<RuntimeConnection> connectionResolver,
                                          final Runnable next) {
        RecoveredRegion found = null;
        for (final RecoveredRegion candidate : this.recoveringRegions) {
            if (candidate.regionId == regionId) {
                found = candidate;
                break;
            }
        }
        if (found == null) {
            next.run();
            return;
        }
        final RecoveredRegion recovered = found;
        if (!this.recoveringRegions.remove(recovered)) {
            next.run();
            return;
        }
        final RuntimeConnection target = connectionResolver.apply(recovered.targetInstanceId);
        if (target == null || !target.isOpen()) {
            // Nothing comes back; drop the ledger entry, the local copy stays authoritative.
            LOGGER.warn("Cannot recover region {}: Compute Host {} is not connected",
                    regionId, recovered.targetInstanceId);
            this.persistLedger();
            next.run();
            return;
        }
        this.requestRecoveredRegionReturn(recovered, target).whenComplete((failure, error) -> {
            if (failure != null) {
                this.recoveringRegions.addIfAbsent(recovered);
                this.persistLedger();
                LOGGER.warn("Region {} could not be recovered: {}", regionId, failure);
            }
            next.run();
        });
    }

    private CompletableFuture<String> requestRecoveredRegionReturn(final RecoveredRegion recovered,
                                                                   final RuntimeConnection target) {
        final CompletableFuture<String> result = new CompletableFuture<>();
        final CompletableFuture<String> existing = this.pendingReturns.putIfAbsent(recovered.regionId, result);
        if (existing != null) {
            return CompletableFuture.completedFuture(
                    "a return for region " + recovered.regionId + " is already in progress");
        }
        final ServerLevel world = this.resolveWorld(recovered.worldPath);
        if (world == null) {
            this.finishReturn(recovered.regionId, null);
            return CompletableFuture.completedFuture(
                    "world '" + recovered.worldPath + "' is not loaded on this host");
        }
        this.snapshotSerializer.expectSnapshot(recovered.regionId)
                .whenComplete((snapshot, error) -> {
                    if (error != null) {
                        this.finishReturn(recovered.regionId, "snapshot transfer failed: " + error);
                        return;
                    }
                    this.installRecoveredRegion(recovered, world, snapshot);
                });
        final ByteBuf payload = target.getChannel().alloc().buffer(Long.BYTES);
        payload.writeLong(recovered.regionId);
        target.sendControl(MessageEnvelope.MessageType.RETURN_REGION, payload);
        this.leaseRenewer.schedule(() -> {
            if (!result.isDone()) {
                this.snapshotSerializer.abort(recovered.regionId,
                        new IllegalStateException("recovery of region " + recovered.regionId + " timed out"));
                this.finishReturn(recovered.regionId, "recovery of region " + recovered.regionId + " timed out");
            }
        }, RETURN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return result;
    }

    private void installRecoveredRegion(final RecoveredRegion recovered, final ServerLevel world,
                                        final RegionSnapshot snapshot) {
        RegionSnapshotUtil.restore(world, snapshot).whenComplete((ignored, error) -> {
            if (error != null) {
                LOGGER.warn("Recovery of region {} could not be installed: {}", recovered.regionId, error.toString());
                this.finishReturn(recovered.regionId, "recovery restore failed: " + error);
                return;
            }
            int reattached = 0;
            try {
                reattached = this.reclaimChunks(world, recovered.chunkX, recovered.chunkZ);
            } catch (final Throwable t) {
                LOGGER.warn("Failed to re-attach recovered chunks of region {}: {}",
                        recovered.regionId, t.toString());
            }
            LOGGER.info("Recovered region {} from Compute Host {}: {} chunk(s) restored into {}, {} re-attached",
                    recovered.regionId, recovered.targetInstanceId, snapshot.getActualChunkCount(),
                    recovered.worldPath, reattached);
            this.finishReturn(recovered.regionId, null);
        });
    }

    private int reclaimChunks(final ServerLevel world, final List<Integer> chunkX, final List<Integer> chunkZ) {
        final ThreadedRegionizer<?, ?> regionizer = world.regioniser;
        int restored = 0;
        for (int i = 0; i < chunkX.size(); i++) {
            final int cx = chunkX.get(i);
            final int cz = chunkZ.get(i);
            if (world.getChunkSource().getChunkNow(cx, cz) == null) {
                continue;
            }
            try {
                regionizer.addChunk(cx, cz);
                restored++;
            } catch (final Throwable t) {
                LOGGER.warn("Failed to reclaim chunk ({}, {}) during recovery: {}", cx, cz, t.toString());
            }
        }
        return restored;
    }

    private @Nullable ServerLevel resolveWorld(final String worldPath) {
        final MinecraftServer currentServer = this.server;
        if (currentServer == null) {
            return null;
        }
        for (final ServerLevel level : currentServer.getAllLevels()) {
            if (level.dimension().identifier().getPath().equals(worldPath)) {
                return level;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- ledger (world host)

    private static final java.nio.file.Path LEDGER_PATH = java.nio.file.Path.of("config", "enigma-forwarded.json");
    private static final long RETURN_TIMEOUT_SECONDS = 60L;

    /**
     * Persists the forwarded-region ledger. This is what makes an abrupt World Host abort
     * recoverable: the compute-side state is still ticking on the Compute Hosts, and this file is
     * the proof that tells the restarted World Host to ask for it back.
     */
    private synchronized void persistLedger() {
        if (!this.config.isWorldHost() || this.server == null) {
            return;
        }
        try {
            final com.google.gson.JsonObject root = new com.google.gson.JsonObject();
            final com.google.gson.JsonArray forwarded = new com.google.gson.JsonArray();
            for (final HandedOver entry : this.handedOver.values()) {
                forwarded.add(entryToJson(entry));
            }
            final com.google.gson.JsonArray recovering = new com.google.gson.JsonArray();
            for (final RecoveredRegion entry : this.recoveringRegions) {
                recovering.add(entryToJson(entry));
            }
            root.add("forwarded", forwarded);
            root.add("recovering", recovering);
            final String json = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root);
            java.nio.file.Files.createDirectories(java.nio.file.Path.of("config"));
            java.nio.file.Files.writeString(LEDGER_PATH, json, java.nio.charset.StandardCharsets.UTF_8);
        } catch (final Throwable t) {
            LOGGER.error("Could not persist the forwarded-region ledger", t);
        }
    }

    private static com.google.gson.JsonObject entryToJson(final HandedOver entry) {
        final com.google.gson.JsonObject object = new com.google.gson.JsonObject();
        object.addProperty("regionId", entry.regionId);
        object.addProperty("targetInstanceId", entry.targetInstanceId);
        object.addProperty("world", entry.world.dimension().identifier().getPath());
        final com.google.gson.JsonArray x = new com.google.gson.JsonArray();
        final com.google.gson.JsonArray z = new com.google.gson.JsonArray();
        for (final Integer cx : entry.chunkX) {
            x.add(cx);
        }
        for (final Integer cz : entry.chunkZ) {
            z.add(cz);
        }
        object.add("chunkX", x);
        object.add("chunkZ", z);
        return object;
    }

    private static com.google.gson.JsonObject entryToJson(final RecoveredRegion entry) {
        final com.google.gson.JsonObject object = new com.google.gson.JsonObject();
        object.addProperty("regionId", entry.regionId);
        object.addProperty("targetInstanceId", entry.targetInstanceId);
        object.addProperty("world", entry.worldPath);
        final com.google.gson.JsonArray x = new com.google.gson.JsonArray();
        final com.google.gson.JsonArray z = new com.google.gson.JsonArray();
        for (final Integer cx : entry.chunkX) {
            x.add(cx);
        }
        for (final Integer cz : entry.chunkZ) {
            z.add(cz);
        }
        object.add("chunkX", x);
        object.add("chunkZ", z);
        return object;
    }

    /**
     * World Host: restores the forwarded-region ledger after a restart and marks every recorded
     * region as pending recovery. Called once from {@code startWorldHost()} once the executor runs.
     */
    public void loadLedger() {
        if (!this.config.isWorldHost()) {
            return;
        }
        if (!java.nio.file.Files.exists(LEDGER_PATH)) {
            return;
        }
        try {
            final String json = java.nio.file.Files.readString(LEDGER_PATH, java.nio.charset.StandardCharsets.UTF_8);
            final com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            final com.google.gson.JsonArray forwarded = root.getAsJsonArray("forwarded");
            if (forwarded != null) {
                for (final com.google.gson.JsonElement element : forwarded) {
                    final RecoveredRegion recovered = jsonToRecovered(element.getAsJsonObject());
                    if (recovered != null) {
                        this.recoveringRegions.addIfAbsent(recovered);
                    }
                }
            }
            final com.google.gson.JsonArray recovering = root.getAsJsonArray("recovering");
            if (recovering != null) {
                for (final com.google.gson.JsonElement element : recovering) {
                    final RecoveredRegion recovered = jsonToRecovered(element.getAsJsonObject());
                    if (recovered != null) {
                        this.recoveringRegions.addIfAbsent(recovered);
                    }
                }
            }
            if (!this.recoveringRegions.isEmpty()) {
                LOGGER.warn("Ledger: {} forwarded region(s) are pending return from their Compute Hosts; "
                                + "they come back automatically once each host reconnects",
                        this.recoveringRegions.size());
            }
        } catch (final Throwable t) {
            LOGGER.error("Could not read the forwarded-region ledger; compute-sided state may still be "
                    + "ticking on other hosts. Inspect manually and delete {} to silence this",
                    LEDGER_PATH, t);
        }
    }

    private static @Nullable RecoveredRegion jsonToRecovered(final com.google.gson.JsonObject object) {
        try {
            final long regionId = object.get("regionId").getAsLong();
            final long targetInstanceId = object.get("targetInstanceId").getAsLong();
            final String world = object.get("world").getAsString();
            final List<Integer> chunkX = new ArrayList<>();
            final List<Integer> chunkZ = new ArrayList<>();
            for (final com.google.gson.JsonElement element : object.getAsJsonArray("chunkX")) {
                chunkX.add(element.getAsInt());
            }
            for (final com.google.gson.JsonElement element : object.getAsJsonArray("chunkZ")) {
                chunkZ.add(element.getAsInt());
            }
            return new RecoveredRegion(regionId, targetInstanceId, world, chunkX, chunkZ);
        } catch (final Throwable t) {
            LOGGER.error("Skipping a malformed ledger entry: {}", object, t);
            return null;
        }
    }

    // ------------------------------------------------------------ migration (world host)

    /**
     * Starts a migration of {@code regionId} to {@code target}.
     *
     * <p>The sequence is driven entirely from the World Host:</p>
     * <ol>
     *   <li>bump the generation and grant the lease, which fences any previous owner. The Compute Host
     *       cannot tick anything yet because it only activates chunks on a region definition, so there
     *       is no window in which both hosts tick this region,</li>
     *   <li>capture a verified snapshot of the region, reading every chunk on its own tick thread,</li>
     *   <li>ship the snapshot; the Compute Host reassembles it and verifies the CRC,</li>
     *   <li>send the region definition, which makes the Compute Host add the chunks to its own
     *       regionizer so the server's existing {@code TickRegions} scheduler starts ticking them,</li>
     *   <li>commit: stop ticking the region here (release the local chunks, see
     *       {@link #releaseHandedOver}) and drop the local lease record without sending a revoke,
     *       because the Compute Host is now the rightful owner.</li>
     * </ol>
     *
     * <p>Failures after this method returned {@code null} are asynchronous: they are logged and show
     * up in {@link #listMigrations()} as {@code FAILED}.</p>
     *
     * @return {@code null} once the migration was started, otherwise the reason it could not start
     */
    public @Nullable String requestRegionMigration(final long regionId, final RuntimeConnection target) {
        if (target == null || !target.isOpen()) {
            return "target connection is not open";
        }

        final MinecraftServer currentServer = this.server;
        if (currentServer == null) {
            return "server reference is not set";
        }
        // Region ids are generated per process, so the region may live in any level - walk them all
        // instead of assuming the overworld.
        ThreadedRegionizer.ThreadedRegion<?, ?> found = null;
        for (final ServerLevel level : currentServer.getAllLevels()) {
            found = findRegion(level, regionId);
            if (found != null) {
                break;
            }
        }
        final ThreadedRegionizer.ThreadedRegion<?, ?> region = found;
        if (region == null) {
            return "region " + regionId + " is not present in any level";
        }
        // The Compute Host restores the snapshot into, and activates the region against, its
        // overworld (see RegionSnapshotUtil.restore and activateRegion), so handing over any other
        // dimension would silently write those chunks into the wrong world.
        if (!ServerLevel.OVERWORLD.equals(region.regioniser.world.dimension())) {
            return "region " + regionId + " lives in " + region.regioniser.world.dimension().identifier()
                    + ", but only overworld regions can be migrated in V1";
        }
        if (this.migrations.containsKey(regionId)) {
            return "region " + regionId + " already has a migration in progress";
        }
        final TickRegions.TickRegionData regionData = dataOf(region);
        if (regionData != null && regionData.getRegionStats().getEntityCount() > 0) {
            return "region " + regionId + " still carries " + regionData.getRegionStats().getEntityCount()
                    + " local entit(y/ies); refusing to cut chunks out from under them until entity migration is implemented";
        }
        final net.minecraft.core.BlockPos spawnPos = region.regioniser.world.getRespawnData().globalPos().pos();
        if (region == region.regioniser.world.regioniser
                .getRegionAtUnsynchronised(spawnPos.getX() >> 4, spawnPos.getZ() >> 4)) {
            return "region " + regionId + " contains the world spawn; refusing to migrate it so joins land in a ticked region";
        }

        final Migration migration = new Migration(regionId, target, region);
        this.migrations.put(regionId, migration);
        LOGGER.info("Migration of region {} to Compute Host {} started",
                regionId, target.getRemoteInstanceId());

        // Step 1: fence and grant. Doing this first means the Compute Host can never act on an older
        // generation, and if anything below fails the grant is simply revoked.
        final LeaseManager.Lease lease = this.leaseManager.grantLease(regionId, target.getRemoteInstanceId(), target);
        if (lease == null) {
            migration.abort("lease grant failed");
            return "lease grant failed";
        }
        migration.lease = lease;

        final RegionSnapshot.OwnershipMetadata ownership = new RegionSnapshot.OwnershipMetadata(
                lease.leaseId, lease.fenceToken, lease.generation, lease.ownerInstanceId);
        final long migrationStartNanos = System.nanoTime();

        // Step 2: capture. This is asynchronous because chunk state may only be read on the tick
        // thread that owns the region.
        migration.stage(MigrationStage.CAPTURING);
        RegionSnapshotUtil.capture(region.id, region, ownership).whenComplete((snapshot, error) -> {
            if (error != null) {
                LOGGER.error("Migration of region {} aborted: snapshot capture failed", regionId, error);
                this.leaseManager.revokeLease(regionId, "snapshot capture failed");
                migration.abort("snapshot capture failed: " + error);
                return;
            }
            migration.snapshot = snapshot;
            migration.chunkCount = snapshot.getActualChunkCount();
            LOGGER.info("Snapshot for region {} captured: {} chunk(s), {} entit(y/ies), "
                            + "checksum=0x{}, worldId={}, {}ms",
                    regionId, snapshot.getActualChunkCount(), snapshot.getActualEntityCount(),
                    Long.toHexString(snapshot.checksum), snapshot.worldId,
                    (System.nanoTime() - migrationStartNanos) / 1_000_000);

            // Step 3: ship it.
            migration.stage(MigrationStage.SENDING);
            this.snapshotSerializer.sendSnapshot(target, snapshot, lease.generation, lease.leaseId, lease.fenceToken,
                            (sent, total) -> {
                                migration.progressSent = sent;
                                migration.progressTotal = total;
                            })
                    .whenComplete((ignored, sendError) -> {
                        if (sendError != null) {
                            LOGGER.error("Migration of region {} aborted: snapshot transfer failed", regionId, sendError);
                            this.leaseManager.revokeLease(regionId, "snapshot transfer failed");
                            migration.abort("snapshot transfer failed: " + sendError);
                            return;
                        }
                        LOGGER.info("Snapshot for region {} delivered to Compute Host {} ({}ms)",
                                regionId, target.getRemoteInstanceId(),
                                (System.nanoTime() - migrationStartNanos) / 1_000_000);

                        // Step 4: tell the Compute Host which chunks to take over.
                        migration.stage(MigrationStage.DEFINITION);
                        sendRegionDefinition(target, region, lease.generation);

                        // Step 5: commit. The Compute Host owns the region now, so stop ticking it here.
                        // forgetLease, not revokeLease: a revoke would tell the Compute Host to stop
                        // ticking the region it just took over.
                        this.leaseManager.forgetLease(regionId);
                        migration.commit();
                        LOGGER.info("Migration of region {} to Compute Host {} committed "
                                        + "({}ms total, {} chunks, checksum=0x{})",
                                regionId, target.getRemoteInstanceId(),
                                (System.nanoTime() - migrationStartNanos) / 1_000_000,
                                migration.snapshot.getActualChunkCount(),
                                Long.toHexString(migration.snapshot.checksum));

                        // Step 6: give up the local chunks. Without this the World Host would keep
                        // ticking the region next to the Compute Host, and an automatic pass would
                        // pick the very same region again.
                        this.releaseHandedOver(region, target.getRemoteInstanceId());

                        verifyMigrationAfterHandover(region, target);
                    });
        });
        return null;
    }

    /**
     * Gives the committed region's chunks up locally so the region stops ticking on the World Host.
     * Runs on the commit callback (a netty thread), which is the same context in which the Compute
     * Host's lease revoke already removes chunks ({@link RemoteRegion#shutdown}).
     */
    private void releaseHandedOver(final ThreadedRegionizer.ThreadedRegion<?, ?> region,
                                   final long targetInstanceId) {
        final long[] packed = region.getOwnedPackedChunkPositions();
        final ServerLevel world = region.regioniser.world;

        final TickRegions.TickRegionData data = dataOf(region);
        if (data != null && data.getRegionStats().getEntityCount() > 0) {
            LOGGER.warn("Region {} is handed over with {} local entit(y/ies); entity migration is not "
                            + "implemented yet, so they stay behind on the World Host",
                    region.id, data.getRegionStats().getEntityCount());
        }

        if (packed.length == 0) {
            LOGGER.info("Region {} owned no chunks at commit; nothing to release", region.id);
            return;
        }

        final List<Integer> chunkX = new ArrayList<>(packed.length);
        final List<Integer> chunkZ = new ArrayList<>(packed.length);
        for (final long chunk : packed) {
            chunkX.add((int) chunk);
            chunkZ.add((int) (chunk >>> 32));
        }

        final HandedOver entry =
                new HandedOver(region.id, targetInstanceId, world, chunkX, chunkZ);
        this.handedOver.put(region.id, entry);
        // The handover is now durable: if this World Host aborts, the ledger tells the restarted
        // process exactly where every region went and what to ask back for.
        this.persistLedger();
        this.releaseChunks(entry);
    }

    /**
     * Removes all of {@code entry}'s chunks from the local regionizer. The removal runs on the tick
     * thread that owns the chunks: vanilla removes chunks both with and without the regionizer lock,
     * so doing it from this netty thread could race a ticking region that touches the same bitset
     * word. If the owning region is already gone the task is dropped and
     * {@link #retryPendingReleases()} marks the entry released once nobody owns the chunks.
     */
    private void releaseChunks(final HandedOver entry) {
        entry.lastAttemptMs = System.currentTimeMillis();
        final ServerLevel world = entry.world;
        if (world == null) {
            entry.released = true;
            return;
        }
        RegionizedServer.getInstance().taskQueue.queueOrExecuteTickTask(
                world, entry.chunkX.get(0), entry.chunkZ.get(0), () -> {
            final ThreadedRegionizer<?, ?> regionizer = world.regioniser;
            for (int i = 0; i < entry.chunkX.size(); i++) {
                final int cx = entry.chunkX.get(i);
                final int cz = entry.chunkZ.get(i);
                try {
                    regionizer.removeChunk(cx, cz);
                } catch (final Throwable t) {
                    LOGGER.warn("Failed to release chunk ({}, {}) of region {}: {}",
                            cx, cz, entry.regionId, t.toString());
                }
            }
            entry.released = true;
            LOGGER.info("World Host released {} chunk(s) of region {} to Compute Host {}",
                    entry.chunkX.size(), entry.regionId, entry.targetInstanceId);
        });
    }

    /**
     * Re-attaches the still loaded chunks of a handed-over region to the local regionizer. Called
     * when the Compute Host that owned them goes away: without this the area would stay frozen on
     * the World Host. Chunks that are not in memory re-join through Moonrise's normal chunk-load
     * path the next time they are loaded.
     */
    private int reclaim(final HandedOver entry) {
        final ThreadedRegionizer<?, ?> regionizer = entry.world.regioniser;
        int restored = 0;
        int failed = 0;
        for (int i = 0; i < entry.chunkX.size(); i++) {
            final int cx = entry.chunkX.get(i);
            final int cz = entry.chunkZ.get(i);
            if (entry.world.getChunkSource().getChunkNow(cx, cz) == null) {
                continue;
            }
            try {
                regionizer.addChunk(cx, cz);
                restored++;
            } catch (final Throwable t) {
                failed++;
                LOGGER.warn("Failed to reclaim chunk ({}, {}) of region {}: {}", cx, cz, entry.regionId, t.toString());
            }
        }
        LOGGER.info("Reclaimed {} of {} chunk(s) of region {} after Compute Host {} disconnected{}",
                restored, entry.chunkX.size(), entry.regionId, entry.targetInstanceId,
                failed > 0 ? " (" + failed + " failed)" : "");
        return restored;
    }

    /**
     * Whether any local region actually owns the chunk. {@code getRegionAtSynchronised} is not
     * enough: it reports the region of the chunk's <em>section</em>, which also exists for empty
     * structural sections (see the activateRegion comment).
     */
    private static boolean ownsChunk(final ServerLevel world, final int chunkX, final int chunkZ) {
        final ThreadedRegionizer.ThreadedRegion<?, ?> region =
                world.regioniser.getRegionAtSynchronised(chunkX, chunkZ);
        if (region == null) {
            return false;
        }
        for (final long owned : region.getOwnedPackedChunkPositions()) {
            if ((int) owned == chunkX && (int) (owned >> 32) == chunkZ) {
                return true;
            }
        }
        return false;
    }

    /**
     * After a successful handover, ask the Compute Host to read the marker block on the region's
     * owning tick thread and report it. Polls until the marker (a chest holding one diamond) shows up
     * or the attempts run out, and prints the result into the console either way. This is the visible,
     * machine-checked proof that the migration really moved state.
     */
    private void verifyMigrationAfterHandover(
            final ThreadedRegionizer.ThreadedRegion<?, ?> region,
            final RuntimeConnection target
    ) {
        final long regionId = region.id;
        if (!containsChunk(region, VERIFY_X >> 4, VERIFY_Z >> 4)) {
            LOGGER.info("Migration verify skipped: marker chunk ({}, {}) is not part of region {}",
                    VERIFY_X >> 4, VERIFY_Z >> 4, regionId);
            return;
        }
        LOGGER.info("MIGRATION VERIFY: checking marker @{}, {}, {} on Compute Host {} for region {}",
                VERIFY_X, VERIFY_Y, VERIFY_Z, target.getRemoteInstanceId(), regionId);
        final Migration migration = this.migrations.get(regionId);
        if (migration != null) {
            migration.stage(MigrationStage.VERIFYING);
        }
        verifyAttempt(regionId, target, VERIFY_X, VERIFY_Y, VERIFY_Z, 0);
    }

    private static boolean containsChunk(
            final ThreadedRegionizer.ThreadedRegion<?, ?> region,
            final int chunkX,
            final int chunkZ
    ) {
        for (final long chunk : region.getOwnedPackedChunkPositions()) {
            if ((int) chunk == chunkX && (int) (chunk >> 32) == chunkZ) {
                return true;
            }
        }
        return false;
    }

    private void verifyAttempt(
            final long regionId,
            final RuntimeConnection target,
            final int x,
            final int y,
            final int z,
            final int attempt
    ) {
        if (!this.running.get() || target == null || !target.isOpen()) {
            LOGGER.warn("MIGRATION VERIFY for region {} aborted: connection is gone", regionId);
            return;
        }
        if (attempt >= VERIFY_ATTEMPTS) {
            LOGGER.warn("MIGRATION VERIFY for region {} gave up after {} attempts; "
                    + "inspect manually with the debug block subcommand", regionId, VERIFY_ATTEMPTS);
            return;
        }
        final ByteBuf payload = target.getChannel().alloc().buffer(Long.BYTES + 3 * Integer.BYTES);
        payload.writeLong(regionId);
        payload.writeInt(x);
        payload.writeInt(y);
        payload.writeInt(z);
        final MessageEnvelope request = MessageEnvelope.createControl(
                MessageEnvelope.MessageType.MIGRATION_VERIFY, payload);
        target.sendAndWait(request, 5000).whenComplete((reply, error) -> {
            // The reply payload is only readable while this synchronous completion body runs.
            if (error != null) {
                LOGGER.warn("MIGRATION VERIFY for region {} attempt {} failed: {}",
                        regionId, attempt + 1, error);
                scheduleVerifyNext(regionId, target, x, y, z, attempt);
                return;
            }
            // Not final: javac's definite-assignment analysis of a value assigned in try and reassigned in
            // catch is too conservative here (result can never be set twice), so keep the holder mutable.
            String result;
            try {
                result = SnapshotSerializer.readString(reply.getPayload());
            } catch (final Throwable t) {
                result = "verify@decode-error: " + t;
            } finally {
                io.netty.util.ReferenceCountUtil.release(reply.getPayload());
            }
            LOGGER.info("MIGRATION VERIFY for region {} attempt {}: {}", regionId, attempt + 1, result);
            if (result.contains("minecraft:diamond")) {
                LOGGER.info("MIGRATION of region {} VERIFIED: the marker state reads on the "
                        + "Compute Host -> distributed state transfer CONFIRMED", regionId);
                final Migration migration = migrations.get(regionId);
                if (migration != null && migration.failure == null) {
                    migration.stage(MigrationStage.VERIFIED);
                }
            } else {
                scheduleVerifyNext(regionId, target, x, y, z, attempt);
            }
        });
    }

    private void scheduleVerifyNext(
            final long regionId,
            final RuntimeConnection target,
            final int x,
            final int y,
            final int z,
            final int attempt
    ) {
        this.leaseRenewer.schedule(() -> verifyAttempt(regionId, target, x, y, z, attempt + 1),
                1, TimeUnit.SECONDS);
    }

    /**
     * Garbage collects empty regions on every loaded level and drops stale runtime bookkeeping for
     * regions the regionizers no longer carry. Returns how many empty regions were released.
     */
    public int garbageCollectRegions() {
        if (!this.running.get()) {
            return 0;
        }
        final MinecraftServer currentServer = this.server;
        if (currentServer == null) {
            return 0;
        }
        int released = 0;
        for (final ServerLevel level : currentServer.getAllLevels()) {
            released += level.regioniser.enigma$garbageCollectRegions();
        }
        int stale = 0;
        for (final Map.Entry<Long, RemoteRegion> entry : new ArrayList<>(this.activeRegions.entrySet())) {
            final long regionId = entry.getKey();
            if (findRegionInAnyLevel(currentServer, regionId) == null) {
                entry.getValue().shutdown("region garbage collected");
                this.activeRegions.remove(regionId);
                stale++;
            }
        }
        for (final Long regionId : new ArrayList<>(this.installedSnapshots)) {
            if (findRegionInAnyLevel(currentServer, regionId) == null) {
                this.installedSnapshots.remove(regionId);
            }
        }
        this.pendingDefinitions.keySet().removeIf(regionId ->
                findRegionInAnyLevel(currentServer, regionId) == null);
        if (released > 0 || stale > 0) {
            LOGGER.info("Region GC complete: {} empty region(s) released, {} stale runtime record(s) dropped",
                    released, stale);
        }
        return released;
    }

    private static ThreadedRegionizer.ThreadedRegion<?, ?> findRegionInAnyLevel(
            final MinecraftServer server,
            final long regionId
    ) {
        for (final ServerLevel level : server.getAllLevels()) {
            final ThreadedRegionizer.ThreadedRegion<?, ?> region = findRegion(level, regionId);
            if (region != null) {
                return region;
            }
        }
        return null;
    }

    /**
     * Returns the overworld regions sorted by owned chunk count, descending, as [id, chunkCount]
     * pairs. Empty regions, regions with a migration already in flight and regions that a player
     * could be affected by are excluded: the region around the world spawn stays World Host side so
     * joins always land in a ticked area, and regions that still contain local entities stay put
     * because entity/player migration is not implemented yet.
     */
    public List<long[]> topRegionsByChunks(final int limit) {
        final MinecraftServer currentServer = this.server;
        final List<long[]> ranked = new ArrayList<>();
        if (currentServer == null) {
            return ranked;
        }
        for (final ServerLevel level : currentServer.getAllLevels()) {
            if (!ServerLevel.OVERWORLD.equals(level.dimension())) {
                continue;
            }
            final net.minecraft.core.BlockPos spawnPos = level.getRespawnData().globalPos().pos();
            final ThreadedRegionizer.ThreadedRegion<?, ?> spawnRegion =
                    level.regioniser.getRegionAtUnsynchronised(spawnPos.getX() >> 4, spawnPos.getZ() >> 4);
            level.regioniser.computeForAllRegions(region -> {
                if (region == spawnRegion) {
                    return;
                }
                final TickRegions.TickRegionData data = dataOf(region);
                if (data != null && data.getRegionStats().getEntityCount() > 0) {
                    LOGGER.debug("Region {} kept on World Host: still carries {} local entit(y/ies)",
                            region.id, data.getRegionStats().getEntityCount());
                    return;
                }
                ranked.add(new long[]{region.id, region.getOwnedPackedChunkPositions().length});
            });
        }
        ranked.removeIf(entry -> entry[1] == 0 || this.migrations.containsKey(entry[0]));
        ranked.sort((a, b) -> Long.compare(b[1], a[1]));
        return ranked.subList(0, Math.min(limit, ranked.size()));
    }

    /**
     * Sends the exact chunk coordinates the Compute Host must add to its own regionizer.
     */
    private void sendRegionDefinition(
            final RuntimeConnection target,
            final ThreadedRegionizer.ThreadedRegion<?, ?> region,
            final long generation
    ) {
        final long[] packed = region.getOwnedPackedChunkPositions();
        // One packed long per chunk, not two.
        final int chunkCount = packed.length;
        if (chunkCount == 0) {
            LOGGER.error("Region {} owns no chunks; refusing to hand over an empty region", region.id);
            return;
        }
        if (chunkCount > MAX_CHUNKS_PER_REGION) {
            LOGGER.error("Region {} owns {} chunks which exceeds the per-region limit of {}",
                    region.id, chunkCount, MAX_CHUNKS_PER_REGION);
            return;
        }

        final ByteBuf payload = target.getChannel().alloc().buffer(Long.BYTES * 2 + Integer.BYTES + chunkCount * 8);
        payload.writeLong(region.id);
        payload.writeLong(generation);
        payload.writeInt(chunkCount);
        for (final long chunk : packed) {
            payload.writeInt((int) chunk);
            payload.writeInt((int) (chunk >> 32));
        }
        target.sendControl(MessageEnvelope.MessageType.REGION_DEFINITION, payload);
        LOGGER.info("Sent region definition for region {} (generation {}, {} chunks)",
                region.id, generation, chunkCount);
    }

    /**
     * Region ids are generated per process, so a region id is only meaningful locally. Walk the
     * level's regions instead of assuming chunk (0, 0).
     */
    private static ThreadedRegionizer.ThreadedRegion<?, ?> findRegion(
            final ServerLevel level,
            final long regionId
    ) {
        final List<ThreadedRegionizer.ThreadedRegion<?, ?>> found = new ArrayList<>(1);
        level.regioniser.computeForAllRegions(region -> {
            if (region.id == regionId) {
                found.add(region);
            }
        });
        return found.isEmpty() ? null : found.get(0);
    }

    private static final int MAX_CHUNKS_PER_REGION = 1 << 16;

    // Marker block used to prove migrations moved real state: a chest holding one diamond at
    // block 8, 64, 8 (chunk 0, 0).
    private static final int VERIFY_X = 8;
    private static final int VERIFY_Y = 64;
    private static final int VERIFY_Z = 8;
    private static final int VERIFY_ATTEMPTS = 12;

    public boolean isRunning() {
        return this.running.get();
    }

    public Map<Long, RemoteRegion> getActiveRegions() {
        return this.activeRegions;
    }

    public int activeRegionCount() {
        return this.activeRegions.size();
    }

    /** World Host: regions whose chunks were handed over and are no longer ticked locally. */
    public int handedOverCount() {
        return this.handedOver.size();
    }

    /** World Host: handed-over regions whose local chunk removal has not succeeded yet. */
    public int handedOverPendingRelease() {
        int pending = 0;
        for (final HandedOver entry : this.handedOver.values()) {
            if (!entry.released) {
                pending++;
            }
        }
        return pending;
    }

    /**
     * Immutable snapshot of a region this World Host forwarded to another instance, for the
     * commands to show which region now lives where.
     */
    public record ForwardedRegion(
            long regionId,
            long targetInstanceId,
            String worldPath,
            int chunkCount,
            boolean released
    ) {}

    /**
     * World Host: every region that was handed over to a Compute Host and is ticking there,
     * sorted by region id.
     */
    public List<ForwardedRegion> forwardedRegions() {
        if (this.handedOver.isEmpty()) {
            return List.of();
        }
        final List<ForwardedRegion> views = new ArrayList<>(this.handedOver.size());
        for (final HandedOver entry : this.handedOver.values()) {
            views.add(new ForwardedRegion(
                    entry.regionId,
                    entry.targetInstanceId,
                    entry.world.dimension().identifier().getPath(),
                    entry.chunkX.size(),
                    entry.released
            ));
        }
        views.sort(java.util.Comparator.comparingLong(ForwardedRegion::regionId));
        return views;
    }

    /**
     * Compute Host: true when the region with this id arrived from a World Host via a
     * migration snapshot instead of being created locally.
     */
    public boolean isInstalledLocally(final long regionId) {
        return this.installedSnapshots.contains(regionId);
    }

    /** Compute Host: how many regions migrated in from a World Host are installed right now. */
    public int installedRegionCount() {
        return this.installedSnapshots.size();
    }

    /**
     * Stage of a migration as reported by {@link #listMigrations()} and the
     * {@code enigma distributed migrations} command. Transitions are driven by the callback chain in
     * {@link #requestRegionMigration} and the verify poll.
     */
    public enum MigrationStage {
        STARTED,
        CAPTURING,
        SENDING,
        DEFINITION,
        COMMITTED,
        VERIFYING,
        VERIFIED,
        FAILED
    }

    /**
     * Immutable snapshot of one migration for command output.
     */
    public record MigrationView(
            long regionId,
            long targetInstanceId,
            MigrationStage stage,
            @Nullable String failure,
            long startedAtMs,
            int chunkCount,
            int progressSent,
            int progressTotal
    ) {}

    /**
     * Tracks a single in-flight migration so it can be reported and reaped. The transitions themselves
     * are driven by the callback chain in {@link #requestRegionMigration}, so this only holds the
     * outcome and gives {@link #tick()} something to clean up.
     */
    private final class Migration {
        private final long regionId;
        private final RuntimeConnection target;
        private final ThreadedRegionizer.ThreadedRegion<?, ?> region;
        private final long startedAtMs = System.currentTimeMillis();

        // Informational only; read by status reporting after the state machine has finished.
        private volatile LeaseManager.Lease lease;
        private volatile RegionSnapshot snapshot;
        private volatile String failure;
        private volatile boolean committed;
        private volatile long finishedAtMs;

        private volatile MigrationStage stage = MigrationStage.STARTED;
        private volatile long stageAtMs = this.startedAtMs;
        private volatile int chunkCount;
        private volatile int progressSent;
        private volatile int progressTotal;

        private Migration(final long regionId, final RuntimeConnection target,
                          final ThreadedRegionizer.ThreadedRegion<?, ?> region) {
            this.regionId = regionId;
            this.target = target;
            this.region = region;
            this.chunkCount = region.getOwnedPackedChunkPositions().length;
        }

        private void stage(final MigrationStage next) {
            this.stage = next;
            this.stageAtMs = System.currentTimeMillis();
        }

        private void commit() {
            this.committed = true;
            this.finishedAtMs = System.currentTimeMillis();
            this.stage(MigrationStage.COMMITTED);
        }

        private void abort(final String reason) {
            if (this.committed || this.failure != null) {
                return;
            }
            this.failure = reason;
            this.finishedAtMs = System.currentTimeMillis();
            this.stage(MigrationStage.FAILED);
            LOGGER.error("Migration of region {} failed: {}", this.regionId, reason);
        }

        private boolean isFinished() {
            return this.committed || this.failure != null;
        }

        private void tick() {
            // Nothing to advance: the state machine runs on its callback chain. A finished migration is
            // kept briefly so `migrations` can report it, then dropped.
            if (this.isFinished() && System.currentTimeMillis() - this.finishedAtMs > MIGRATION_HISTORY_MS) {
                migrations.remove(this.regionId, this);
            }
        }
    }

    private static final long MIGRATION_HISTORY_MS = 60_000L;

    /**
     * Migrations currently in flight plus finished ones from the last {@code MIGRATION_HISTORY_MS},
     * oldest first.
     */
    public List<MigrationView> listMigrations() {
        final List<MigrationView> views = new ArrayList<>(this.migrations.size());
        for (final Migration migration : this.migrations.values()) {
            views.add(new MigrationView(migration.regionId, migration.target.getRemoteInstanceId(),
                    migration.stage, migration.failure, migration.startedAtMs, migration.chunkCount,
                    migration.progressSent, migration.progressTotal));
        }
        views.sort(java.util.Comparator.comparingLong(MigrationView::startedAtMs));
        return views;
    }

    /**
     * Number of migrations that have neither committed nor failed yet; used to cap the automatic
     * passes. Finished history entries do not count against the limit.
     */
    public int activeMigrationCount() {
        int active = 0;
        for (final Migration migration : this.migrations.values()) {
            if (!migration.isFinished()) {
                active++;
            }
        }
        return active;
    }

    public static final class RemoteRegion {
        public final long regionId;
        public final LeaseManager.Lease lease;
        public final long generation;
        public final ServerLevel world;
        public final ThreadedRegionizer<?, ?> regionizer;
        /** The regionizer region the assigned chunks were added to; needed to capture a snapshot. */
        public final ThreadedRegionizer.ThreadedRegion<?, ?> threadedRegion;
        public final List<Integer> chunkX;
        public final List<Integer> chunkZ;

        public volatile @Nullable Object tickHandle;
        public volatile boolean shutdown = false;
        public volatile long crossRegionMessagesReceived = 0;

        private RemoteRegion(
                final long regionId,
                final LeaseManager.Lease lease,
                final long generation,
                final ServerLevel world,
                final ThreadedRegionizer<?, ?> regionizer,
                final ThreadedRegionizer.ThreadedRegion<?, ?> threadedRegion,
                final List<Integer> chunkX,
                final List<Integer> chunkZ
        ) {
            this.regionId = regionId;
            this.lease = lease;
            this.generation = generation;
            this.world = world;
            this.regionizer = regionizer;
            this.threadedRegion = threadedRegion;
            this.chunkX = List.copyOf(chunkX);
            this.chunkZ = List.copyOf(chunkZ);
        }

        public void shutdown(final String reason) {
            if (this.shutdown) {
                return;
            }
            this.shutdown = true;
            LOGGER.info("Stopping local ticking of region {}: {}", this.regionId, reason);
            try {
                for (int i = 0; i < this.chunkX.size(); i++) {
                    this.regionizer.removeChunk(this.chunkX.get(i), this.chunkZ.get(i));
                }
            } catch (final Throwable t) {
                LOGGER.warn("Failed to remove chunks of region {}: {}", this.regionId, t.toString());
            }
        }

        public int ownedChunkCount() {
            return this.chunkX.size();
        }
    }
}
