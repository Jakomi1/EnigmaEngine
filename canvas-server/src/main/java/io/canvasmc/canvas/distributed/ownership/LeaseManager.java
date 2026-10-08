package io.canvasmc.canvas.distributed.ownership;

import io.canvasmc.canvas.distributed.config.EnigmaDistributedConfig;
import io.canvasmc.canvas.distributed.network.MessageEnvelope;
import io.canvasmc.canvas.distributed.network.RuntimeConnection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single authority for region ownership.
 *
 * <p>Only the World Host ever calls {@link #grantLease}. The Compute Host calls
 * {@link #acceptGrantedLease} to install the lease it was handed. Both sides enforce the same
 * monotonic generation rule, so a Compute Host can never keep ticking a region that the World Host
 * has already reclaimed.</p>
 */
public final class LeaseManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("EnigmaLease");

    private final EnigmaDistributedConfig config;
    private final GenerationManager generationManager;

    private final Map<Long, Lease> activeLeases = new ConcurrentHashMap<>();
    private final AtomicLong leaseIdGenerator = new AtomicLong(1);
    private final AtomicLong fenceTokenGenerator = new AtomicLong(1);

    public LeaseManager(final EnigmaDistributedConfig config, final GenerationManager generationManager) {
        this.config = config;
        this.generationManager = generationManager;
    }

    // ------------------------------------------------------------------- world host

    /**
     * Grants ownership of {@code regionId} to {@code ownerInstanceId}, fencing any previous owner.
     * Returns the new lease, or null if the connection is no longer usable.
     */
    public @org.jspecify.annotations.Nullable Lease grantLease(
            final long regionId,
            final long ownerInstanceId,
            final RuntimeConnection connection
    ) {
        if (connection == null || !connection.isOpen()) {
            LOGGER.warn("Cannot grant lease for region {}: connection is not open", regionId);
            return null;
        }

        final long generation = generationManager.nextGeneration(regionId);
        final long leaseId = leaseIdGenerator.getAndIncrement();
        final long fenceToken = fenceTokenGenerator.getAndIncrement();

        final Lease lease = new Lease(
                regionId,
                ownerInstanceId,
                leaseId,
                generation,
                fenceToken,
                connection,
                System.currentTimeMillis() + config.leaseTtlSeconds * 1000L
        );

        final Lease previous = activeLeases.put(regionId, lease);
        if (previous != null && previous.ownerInstanceId != ownerInstanceId) {
            LOGGER.warn("Fencing previous owner {} of region {} (previousLease={}, previousFence={})",
                    previous.ownerInstanceId, regionId, previous.leaseId, previous.fenceToken);
            // The new generation travels with the notice so the old owner can advance its view and
            // refuse any late message that still carries the older generation.
            send(previous.connection, MessageEnvelope.MessageType.FENCE_NOTICE,
                    encodeFenceNotice(previous.regionId, previous.leaseId, previous.fenceToken, generation));
        }

        LOGGER.info("Granted lease for region {}: leaseId={} generation={} fenceToken={} owner={}",
                regionId, leaseId, generation, fenceToken, ownerInstanceId);

        final ByteBuf payload = connection.getChannel().alloc().buffer();
        payload.writeLong(lease.regionId);
        payload.writeLong(lease.leaseId);
        payload.writeLong(lease.generation);
        payload.writeLong(lease.fenceToken);
        payload.writeLong(lease.ownerInstanceId);
        payload.writeLong(config.leaseTtlSeconds * 1000L);
        send(connection, MessageEnvelope.MessageType.LEASE_GRANT, payload);

        return lease;
    }

    // ----------------------------------------------------------------- compute host

    /**
     * Installs a lease that the World Host granted to us. Rejects the grant if a lease for the
     * same region is already installed with an equal or higher generation, which is what stops a
     * late duplicate grant from resurrecting a region we already gave up.
     *
     * @return true if the lease is now installed and owned by us
     */
    public boolean acceptGrantedLease(
            final long regionId,
            final long leaseId,
            final long generation,
            final long fenceToken,
            final long ownerInstanceId,
            final long ttlMs,
            final RuntimeConnection connection
    ) {
        final long local = generationManager.currentGeneration(regionId);
        if (local > generation) {
            LOGGER.warn("Rejecting lease for region {}: generation {} is older than the generation {}"
                    + " we already processed", regionId, generation, local);
            return false;
        }
        if (ownerInstanceId != connection.getLocalInstanceId()) {
            LOGGER.warn("Rejecting lease for region {}: granted to instance {} but we are {}",
                    regionId, ownerInstanceId, connection.getLocalInstanceId());
            return false;
        }

        generationManager.setGeneration(regionId, generation);

        final Lease lease = new Lease(
                regionId,
                ownerInstanceId,
                leaseId,
                generation,
                fenceToken,
                connection,
                System.currentTimeMillis() + ttlMs
        );

        final Lease previous = activeLeases.put(regionId, lease);
        if (previous != null && previous.generation > generation) {
            LOGGER.warn("Rejecting lease for region {}: newer generation {} already installed",
                    regionId, previous.generation);
            generationManager.setGeneration(regionId, previous.generation);
            activeLeases.put(regionId, previous);
            return false;
        }

        LOGGER.info("Accepted lease for region {}: leaseId={} generation={} fenceToken={} ttl={}ms",
                regionId, leaseId, generation, fenceToken, ttlMs);
        return true;
    }

    // ---------------------------------------------------------------------- common

    public Optional<Lease> getLease(final long regionId) {
        final Lease lease = activeLeases.get(regionId);
        if (lease == null) {
            return Optional.empty();
        }
        if (lease.isExpired()) {
            return Optional.empty();
        }
        return Optional.of(lease);
    }

    /**
     * Extends the local view of a lease and tells the peer. Used by the World Host to keep a
     * granted lease alive and by the Compute Host to confirm it is still alive.
     */
    public void renewLease(final long regionId) {
        final Lease lease = activeLeases.get(regionId);
        if (lease == null) {
            return;
        }
        if (lease.isExpired()) {
            expire(regionId, lease);
            return;
        }
        lease.expirationMs = System.currentTimeMillis() + config.leaseTtlSeconds * 1000L;
    }

    /**
     * Applies a renewal that arrived from the peer.
     *
     * @return true if the lease is known and still valid
     */
    public boolean applyRemoteRenewal(final long regionId, final long leaseId, final long ttlMs) {
        final Lease lease = activeLeases.get(regionId);
        if (lease == null) {
            LOGGER.debug("Ignoring renewal for unknown region {}", regionId);
            return false;
        }
        if (lease.leaseId != leaseId) {
            LOGGER.warn("Ignoring renewal for region {}: leaseId {} does not match active lease {}",
                    regionId, leaseId, lease.leaseId);
            return false;
        }
        lease.expirationMs = System.currentTimeMillis() + ttlMs;
        return true;
    }

    public void revokeLease(final long regionId, final String reason) {
        final Lease lease = activeLeases.remove(regionId);
        if (lease == null) {
            return;
        }
        LOGGER.info("Revoked lease for region {}: leaseId={} generation={} reason={}",
                regionId, lease.leaseId, lease.generation, reason);
        send(lease.connection, MessageEnvelope.MessageType.LEASE_REVOKE,
                encodeLeaseRevoke(lease.regionId, lease.leaseId, lease.generation, lease.fenceToken));
    }

    /**
     * Handles a lease that expired on its own. Sends an explicit revoke so the peer stops ticking.
     */
    private void expire(final long regionId, final Lease lease) {
        if (activeLeases.remove(regionId, lease)) {
            LOGGER.warn("Lease for region {} expired: leaseId={} generation={}",
                    regionId, lease.leaseId, lease.generation);
            send(lease.connection, MessageEnvelope.MessageType.LEASE_REVOKE,
                    encodeLeaseRevoke(lease.regionId, lease.leaseId, lease.generation, lease.fenceToken));
            send(lease.connection, MessageEnvelope.MessageType.FENCE_NOTICE,
                    encodeFenceNotice(lease.regionId, lease.leaseId, lease.fenceToken, lease.generation));
        }
    }

    /**
     * Drops the local record of a lease without telling the peer.
     *
     * <p>This is what a successful handover uses: the region now belongs to the Compute Host, so the
     * World Host must neither keep expiring the lease (which would revoke the region the Compute Host
     * is about to tick) nor send a revoke.</p>
     */
    public void forgetLease(final long regionId) {
        final Lease lease = activeLeases.remove(regionId);
        if (lease != null) {
            LOGGER.info("Forgot lease for region {} (leaseId={} generation={} handed over to {})",
                    regionId, lease.leaseId, lease.generation, lease.ownerInstanceId);
        }
    }

    /**
     * Revokes every lease whose holder is {@code ownerInstanceId}. Called by the World Host when a
     * Compute Host's connection goes away so stale region ownership cannot outlive its holder.
     *
     * @return how many leases were removed
     */
    public int revokeAllForOwner(final long ownerInstanceId, final String reason) {
        int removed = 0;
        for (final Map.Entry<Long, Lease> entry : activeLeases.entrySet()) {
            final Lease lease = entry.getValue();
            if (lease.ownerInstanceId == ownerInstanceId) {
                activeLeases.remove(entry.getKey(), lease);
                removed++;
            }
        }
        if (removed > 0) {
            LOGGER.warn("Revoked {} lease(s) held by Compute Host {}: {}", removed, ownerInstanceId, reason);
        }
        return removed;
    }

    public void tick() {
        final long now = System.currentTimeMillis();
        final long renewWindowMs = config.leaseRenewalIntervalSeconds * 1000L;

        for (final Map.Entry<Long, Lease> entry : activeLeases.entrySet()) {
            final Lease lease = entry.getValue();
            if (lease.isExpired()) {
                expire(entry.getKey(), lease);
                continue;
            }
            // Renew ahead of expiry so a single lost heartbeat does not drop the region.
            if (now > lease.expirationMs - renewWindowMs) {
                renewLease(entry.getKey());
            }
        }
    }

    public Collection<Lease> activeLeases() {
        return new ArrayList<>(activeLeases.values());
    }

    public int activeLeaseCount() {
        return activeLeases.size();
    }

    private static void send(final RuntimeConnection connection, final MessageEnvelope.MessageType type,
                             final ByteBuf payload) {
        if (connection != null && connection.isOpen()) {
            connection.sendControl(type, payload);
        } else {
            io.netty.util.ReferenceCountUtil.release(payload);
        }
    }

    private static ByteBuf encodeLeaseRevoke(final long regionId, final long leaseId, final long generation,
                                             final long fenceToken) {
        final ByteBuf buf = Unpooled.buffer();
        buf.writeLong(regionId);
        buf.writeLong(leaseId);
        buf.writeLong(generation);
        buf.writeLong(fenceToken);
        return buf;
    }

    private static ByteBuf encodeFenceNotice(final long regionId, final long leaseId, final long fenceToken,
                                             final long newGeneration) {
        final ByteBuf buf = Unpooled.buffer();
        buf.writeLong(regionId);
        buf.writeLong(leaseId);
        buf.writeLong(fenceToken);
        buf.writeLong(newGeneration);
        return buf;
    }

    public static final class Lease {
        public final long regionId;
        public final long ownerInstanceId;
        public final long leaseId;
        public final long generation;
        public final long fenceToken;
        public final RuntimeConnection connection;
        public volatile long expirationMs;

        public Lease(
                final long regionId,
                final long ownerInstanceId,
                final long leaseId,
                final long generation,
                final long fenceToken,
                final RuntimeConnection connection,
                final long expirationMs
        ) {
            this.regionId = regionId;
            this.ownerInstanceId = ownerInstanceId;
            this.leaseId = leaseId;
            this.generation = generation;
            this.fenceToken = fenceToken;
            this.connection = connection;
            this.expirationMs = expirationMs;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() >= expirationMs;
        }

        public long getRemainingMs() {
            return Math.max(0, expirationMs - System.currentTimeMillis());
        }

        @Override
        public String toString() {
            return "Lease{region=" + regionId + ", leaseId=" + leaseId + ", gen=" + generation
                    + ", fence=" + fenceToken + ", owner=" + ownerInstanceId
                    + ", remainingMs=" + getRemainingMs() + "}";
        }
    }
}
