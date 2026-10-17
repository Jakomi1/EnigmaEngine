package io.canvasmc.canvas.distributed.snapshot;

import io.canvasmc.canvas.distributed.config.EnigmaDistributedConfig;
import io.canvasmc.canvas.distributed.network.MessageEnvelope;
import io.canvasmc.canvas.distributed.network.RuntimeConnection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Streams a {@link RegionSnapshot} across the control channel as a sequence of fixed size
 * {@code SNAPSHOT_CHUNK} frames followed by a {@code SNAPSHOT_COMPLETE} frame carrying the
 * advertised CRC32. The receiver reassembles, verifies the CRC and only then reports success.
 *
 * <p>Flow control is explicit: the sender waits for a window of chunk ACKs before pushing more,
 * so a slow or stalled Compute Host applies backpressure to the World Host instead of buffering
 * an unbounded snapshot in the socket.</p>
 */
public final class SnapshotSerializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("EnigmaSnapshot");

    private static final int CHUNK_META_BYTES = Integer.BYTES * 2;
    private static final int WINDOW = 32;

    private final EnigmaDistributedConfig config;
    private final int maxChunkSize;

    private final Map<Long, CompletableFuture<RegionSnapshot>> pendingReceives = new ConcurrentHashMap<>();
    private final Map<Long, ReceiveState> receiveStates = new ConcurrentHashMap<>();

    public SnapshotSerializer(final EnigmaDistributedConfig config) {
        this.config = config;
        this.maxChunkSize = Math.max(1024, config.snapshotChunkSizeKb * 1024);
    }

    // ---------------------------------------------------------------- sending

    public CompletableFuture<Void> sendSnapshot(
            final RuntimeConnection connection,
            final RegionSnapshot snapshot,
            final long generation,
            final long leaseId,
            final long fenceToken
    ) {
        return this.sendSnapshot(connection, snapshot, generation, leaseId, fenceToken, null);
    }

    /**
     * @param progress invoked after every acknowledged chunk as {@code (sentChunks, totalChunks)};
     *     may be {@code null}
     */
    public CompletableFuture<Void> sendSnapshot(
            final RuntimeConnection connection,
            final RegionSnapshot snapshot,
            final long generation,
            final long leaseId,
            final long fenceToken,
            final @Nullable BiConsumer<Integer, Integer> progress
    ) {
        final ByteBuf encoded;
        final long checksum;
        try {
            encoded = Unpooled.buffer();
            // The checksum is only known after the body has been written, so it has to come from the
            // return value rather than from the snapshot's field, which still holds the constructor
            // value at this point.
            checksum = snapshot.encodeTo(encoded);
        } catch (final Throwable t) {
            return CompletableFuture.failedFuture(t);
        }

        final int totalBytes = encoded.readableBytes();
        final int maxTotal = config.maxSnapshotSizeMb * 1024 * 1024;
        if (totalBytes > maxTotal) {
            encoded.release();
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Snapshot for region " + snapshot.regionId + " is " + totalBytes
                            + " bytes which exceeds maxSnapshotSizeMb=" + config.maxSnapshotSizeMb));
        }

        LOGGER.info("Sending snapshot for region {} (gen={}, {} bytes, {} chunk(s))",
                snapshot.regionId, generation, totalBytes,
                (totalBytes + maxChunkSize - 1) / maxChunkSize);

        final int chunkCount = Math.max(1, (totalBytes + maxChunkSize - 1) / maxChunkSize);

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int index = 0; index < chunkCount; index++) {
            final int chunkIndex = index;
            chain = chain.thenCompose(ignored -> {
                if (!connection.isOpen()) {
                    return CompletableFuture.failedFuture(
                            new java.io.IOException("Connection closed mid-snapshot for region " + snapshot.regionId));
                }
                final int offset = chunkIndex * maxChunkSize;
                final int length = Math.min(maxChunkSize, totalBytes - offset);

                final ByteBuf payload = connection.getChannel().alloc().buffer(CHUNK_META_BYTES + Long.BYTES + length);
                payload.writeLong(snapshot.regionId);
                payload.writeInt(chunkIndex);
                payload.writeInt(chunkCount);
                payload.writeBytes(encoded, offset, length);

                return connection.sendAndWait(
                        MessageEnvelope.create(generation, leaseId, fenceToken, 0, 0,
                                MessageEnvelope.MessageType.SNAPSHOT_CHUNK, payload),
                        30000L
                ).thenAccept(response -> {
                    try {
                        if (response.getMessageType() != MessageEnvelope.MessageType.SNAPSHOT_CHUNK) {
                            throw new IllegalStateException("Unexpected reply to snapshot chunk "
                                    + chunkIndex + ": " + response.getMessageType());
                        }
                        // The ACK carries the highest contiguous chunk index the receiver has.
                        final ByteBuf ack = response.getPayload();
                        final int ackIndex = ack.readInt();
                        if (ackIndex < chunkIndex) {
                            throw new IllegalStateException("Receiver acknowledged chunk " + ackIndex
                                    + " but chunk " + chunkIndex + " was in flight");
                        }
                    } finally {
                        ReferenceCountUtil.release(response.getPayload());
                    }
                    if (progress != null) {
                        progress.accept(chunkIndex + 1, chunkCount);
                    }
                });
            });
        }

        return chain.thenCompose(ignored -> {
            final ByteBuf done = connection.getChannel().alloc().buffer(Long.BYTES + 2 * Integer.BYTES + Long.BYTES);
            done.writeLong(snapshot.regionId);
            done.writeInt(chunkCount);
            done.writeInt(totalBytes);
            done.writeLong(checksum);
            return connection.sendAndWait(
                    MessageEnvelope.create(generation, leaseId, fenceToken, 0, 0,
                            MessageEnvelope.MessageType.SNAPSHOT_COMPLETE, done),
                    30000L
            );
        }).thenAccept(response -> {
            try {
                final ByteBuf ack = response.getPayload();
                final boolean ok = ack.readBoolean();
                final String detail = readString(ack);
                if (!ok) {
                    throw new IllegalStateException("Receiver rejected snapshot: " + detail);
                }
                LOGGER.info("Snapshot for region {} acknowledged (checksum=0x{})",
                        snapshot.regionId, Long.toHexString(checksum));
            } finally {
                ReferenceCountUtil.release(response.getPayload());
            }
        }).whenComplete((ignored, error) -> {
            ReferenceCountUtil.release(encoded);
            if (error != null) {
                LOGGER.error("Snapshot transfer for region {} failed", snapshot.regionId, error);
            }
        });
    }

    // --------------------------------------------------------------- receiving

    /**
     * Registers interest in a snapshot for {@code regionId}. The returned future completes once a
     * {@code SNAPSHOT_COMPLETE} frame has been reassembled and its CRC verified.
     */
    public CompletableFuture<RegionSnapshot> expectSnapshot(final long regionId) {
        final CompletableFuture<RegionSnapshot> future = new CompletableFuture<>();
        final ReceiveState state = new ReceiveState(future);
        final CompletableFuture<RegionSnapshot> existing = pendingReceives.putIfAbsent(regionId, future);
        if (existing != null) {
            return existing;
        }
        receiveStates.put(regionId, state);
        return future;
    }

    /**
     * Handles one inbound snapshot frame. The region id is read from the payload, so the receiver
     * never has to guess which transfer a frame belongs to.
     *
     * <p>The sender waits for a reply per frame, so this returns the ACK payload that must be sent
     * back, or {@code null} if the frame was not recognised.</p>
     */
    public @Nullable ByteBuf handleChunk(final MessageEnvelope message) {
        final ByteBuf payload = message.getPayload();
        if (payload.readableBytes() < Long.BYTES) {
            return null;
        }
        final long regionId = payload.readLong();
        final ReceiveState state = receiveStates.get(regionId);
        if (state == null) {
            return null;
        }
        try {
            switch (message.getMessageType()) {
                case SNAPSHOT_CHUNK -> {
                    state.acceptChunk(payload);
                    // Highest contiguous index received so far.
                    final ByteBuf ack = Unpooled.buffer(Integer.BYTES);
                    ack.writeInt(state.received - 1);
                    return ack;
                }
                case SNAPSHOT_COMPLETE -> {
                    state.readTrailer(payload);
                    state.acceptComplete(regionId);
                    receiveStates.remove(regionId, state);
                    // The caller may hold on to this future, so drop our reference to it once it is done.
                    pendingReceives.remove(regionId, state.future);
                    state.close();

                    final ByteBuf ack = Unpooled.buffer();
                    ack.writeBoolean(true);
                    writeString(ack, "verified");
                    return ack;
                }
                default -> {
                    return null;
                }
            }
        } catch (final Throwable t) {
            receiveStates.remove(regionId, state);
            state.close();
            final ByteBuf ack = Unpooled.buffer();
            ack.writeBoolean(false);
            writeString(ack, String.valueOf(t.getMessage()));
            pendingReceives.remove(regionId);
            final CompletableFuture<RegionSnapshot> future = state.future;
            future.completeExceptionally(t);
            return ack;
        }
    }

    public void abort(final long regionId, final Throwable cause) {
        final ReceiveState state = receiveStates.remove(regionId);
        if (state != null) {
            state.close();
        }
        fail(regionId, cause);
    }

    private void fail(final long regionId, final Throwable cause) {
        final CompletableFuture<RegionSnapshot> future = pendingReceives.remove(regionId);
        if (future != null) {
            future.completeExceptionally(cause);
        }
    }

    private static final class ReceiveState {
        private final CompletableFuture<RegionSnapshot> future;
        private ByteBuf buffer;
        private int chunkCount = -1;
        private int received = 0;

        private ReceiveState(final CompletableFuture<RegionSnapshot> future) {
            this.future = future;
        }

        private void acceptChunk(final ByteBuf payload) {
            final int index = payload.readInt();
            final int count = payload.readInt();
            if (count <= 0 || index < 0 || index >= count) {
                throw new IllegalStateException("Invalid snapshot chunk header index=" + index + " count=" + count);
            }
            if (this.chunkCount == -1) {
                this.chunkCount = count;
            } else if (this.chunkCount != count) {
                throw new IllegalStateException("Snapshot chunk count changed mid-stream: "
                        + this.chunkCount + " -> " + count);
            }
            if (this.buffer == null) {
                this.buffer = Unpooled.buffer();
            }
            if (payload.readableBytes() > 0) {
                this.buffer.writeBytes(payload);
            }
            this.received++;
        }

        private void acceptComplete(final long regionId) {
            final int actualBytes = this.buffer == null ? 0 : this.buffer.readableBytes();
            if (this.buffer == null) {
                throw new IllegalStateException("Snapshot completed without any data");
            }
            if (actualBytes != this.expectedBytes) {
                throw new IllegalStateException("Snapshot length mismatch: expected " + this.expectedBytes
                        + " got " + actualBytes);
            }
            if (this.declaredChunks != this.received) {
                throw new IllegalStateException("Expected " + this.declaredChunks
                        + " snapshot chunks but received " + this.received);
            }

            // decode() advances the reader index, so verification needs its own view of the buffer.
            final RegionSnapshot snapshot = RegionSnapshot.decode(this.buffer.duplicate());
            if (snapshot == null) {
                throw new IllegalStateException("Reassembled snapshot failed to decode");
            }
            if (snapshot.checksum != this.advertisedChecksum) {
                throw new IllegalStateException("Snapshot header checksum " + Long.toHexString(snapshot.checksum)
                        + " != trailer checksum " + Long.toHexString(this.advertisedChecksum));
            }
            if (!snapshot.verifyChecksum(this.buffer.duplicate())) {
                throw new IllegalStateException("Snapshot CRC32 verification failed");
            }

            LOGGER.info("Snapshot for region {} received and verified: {}", regionId, snapshot);
            this.future.complete(snapshot);
        }

        private int declaredChunks = -1;
        private int expectedBytes = -1;
        private long advertisedChecksum;

        private void readTrailer(final ByteBuf payload) {
            this.declaredChunks = payload.readInt();
            this.expectedBytes = payload.readInt();
            this.advertisedChecksum = payload.readLong();
        }

        private void close() {
            if (this.buffer != null) {
                this.buffer.release();
                this.buffer = null;
            }
        }
    }

    public static void writeString(final ByteBuf out, final String value) {
        final byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.writeBytes(bytes);
    }

    public static String readString(final ByteBuf in) {
        final int length = in.readInt();
        if (length < 0 || in.readableBytes() < length) {
            throw new IllegalStateException("Invalid string length " + length);
        }
        final byte[] bytes = new byte[length];
        in.readBytes(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    public int getMaxChunkSize() {
        return maxChunkSize;
    }

    public static @Nullable RegionSnapshot decodeHeaderForDiagnostics(final ByteBuf buf) {
        return RegionSnapshot.decode(buf.duplicate());
    }
}
