package io.canvasmc.canvas.distributed.network;

import io.netty.buffer.ByteBuf;
import org.jspecify.annotations.Nullable;

public final class MessageEnvelope {

    public static final short MAGIC = 0xE1C;
    public static final byte VERSION = 1;

    /**
     * magic(2) + version(1) + flags(1) + msgType(1) + generation(8) + leaseId(8)
     * + fenceToken(8) + sequenceNumber(8) + requestId(8) + payloadLength(4)
     */
    public static final int HEADER_SIZE = 2 + 1 + 1 + 1 + 8 + 8 + 8 + 8 + 8 + 4;

    public enum MessageType {
        HANDSHAKE((byte) 0x01),
        HANDSHAKE_ACK((byte) 0x14),
        LEASE_GRANT((byte) 0x02),
        LEASE_RENEW((byte) 0x03),
        LEASE_RENEW_ACK((byte) 0x15),
        LEASE_REVOKE((byte) 0x04),
        MIGRATION_PREPARE((byte) 0x05),
        MIGRATION_COMMIT((byte) 0x08),
        MIGRATION_ABORT((byte) 0x16),
        SNAPSHOT_CHUNK((byte) 0x06),
        SNAPSHOT_COMPLETE((byte) 0x07),
        FENCE_NOTICE((byte) 0x09),
        PLAYER_ROUTE((byte) 0x0A),
        PLAYER_PACKET((byte) 0x0B),
        CROSS_REGION_MSG((byte) 0x10),
        ENTITY_TRANSFER((byte) 0x11),
        CHUNK_UPDATE((byte) 0x12),
        REGION_DEFINITION((byte) 0x13),
        MIGRATION_VERIFY((byte) 0x17),
        MIGRATION_VERIFY_ACK((byte) 0x18),
        RETURN_REGION((byte) 0x19),
        RETURN_REGION_ACK((byte) 0x1A),
        HEARTBEAT((byte) 0x7F);

        public final byte value;

        MessageType(final byte value) {
            this.value = value;
        }

        public static @Nullable MessageType fromByte(final byte b) {
            for (MessageType t : values()) {
                if (t.value == b) {
                    return t;
                }
            }
            return null;
        }
    }

    public enum Flags {
        NONE((byte) 0x00),
        COMPRESSED((byte) 0x01),
        ENCRYPTED((byte) 0x02),
        FRAGMENTED((byte) 0x04),
        LAST_FRAGMENT((byte) 0x08);

        public final byte value;

        Flags(final byte value) {
            this.value = value;
        }
    }

    private final long generation;
    private final long leaseId;
    private final long fenceToken;
    private final long sequenceNumber;
    private final long requestId;
    private final MessageType messageType;
    private final byte flags;
    private final ByteBuf payload;

    public MessageEnvelope(
            final long generation,
            final long leaseId,
            final long fenceToken,
            final long sequenceNumber,
            final long requestId,
            final MessageType messageType,
            final byte flags,
            final ByteBuf payload
    ) {
        this.generation = generation;
        this.leaseId = leaseId;
        this.fenceToken = fenceToken;
        this.sequenceNumber = sequenceNumber;
        this.requestId = requestId;
        this.messageType = messageType;
        this.flags = flags;
        this.payload = payload;
    }

    public static MessageEnvelope create(
            final long generation,
            final long leaseId,
            final long fenceToken,
            final long sequenceNumber,
            final long requestId,
            final MessageType messageType,
            final ByteBuf payload
    ) {
        return new MessageEnvelope(generation, leaseId, fenceToken, sequenceNumber, requestId, messageType,
                Flags.NONE.value, payload);
    }

    public static MessageEnvelope createControl(
            final MessageType messageType,
            final ByteBuf payload
    ) {
        return new MessageEnvelope(0, 0, 0, 0, 0, messageType, Flags.NONE.value, payload);
    }

    public long getGeneration() {
        return generation;
    }

    public long getLeaseId() {
        return leaseId;
    }

    public long getFenceToken() {
        return fenceToken;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    /**
     * Correlation id. A request sets this to a locally generated value, and the responder
     * echoes it back on the reply so the original caller can complete its future. This is
     * deliberately separate from {@link #sequenceNumber}, which is per-direction and would
     * never match if the responder allocated its own.
     */
    public long getRequestId() {
        return requestId;
    }

    public MessageType getMessageType() {
        return messageType;
    }

    public byte getFlags() {
        return flags;
    }

    public ByteBuf getPayload() {
        return payload;
    }

    public int getPayloadLength() {
        return payload.readableBytes();
    }

    public void encode(final ByteBuf out) {
        out.writeShort(MAGIC);
        out.writeByte(VERSION);
        out.writeByte(flags);
        out.writeByte(messageType.value);
        out.writeLong(generation);
        out.writeLong(leaseId);
        out.writeLong(fenceToken);
        out.writeLong(sequenceNumber);
        out.writeLong(requestId);
        out.writeInt(payload.readableBytes());
        out.writeBytes(payload);
    }

    /**
     * Decodes one envelope starting at the reader index. Returns null and leaves the reader
     * index untouched if the buffer does not (yet) contain a complete, valid frame.
     */
    public static @Nullable MessageEnvelope decode(final ByteBuf in) {
        if (in.readableBytes() < HEADER_SIZE) {
            return null;
        }

        in.markReaderIndex();

        final short magic = in.readShort();
        if (magic != MAGIC) {
            in.resetReaderIndex();
            return null;
        }

        final byte version = in.readByte();
        if (version != VERSION) {
            in.resetReaderIndex();
            return null;
        }

        final byte flags = in.readByte();
        final byte msgTypeByte = in.readByte();
        final MessageType messageType = MessageType.fromByte(msgTypeByte);
        if (messageType == null) {
            in.resetReaderIndex();
            return null;
        }

        final long generation = in.readLong();
        final long leaseId = in.readLong();
        final long fenceToken = in.readLong();
        final long sequenceNumber = in.readLong();
        final long requestId = in.readLong();
        final int payloadLength = in.readInt();

        if (payloadLength < 0 || in.readableBytes() < payloadLength) {
            in.resetReaderIndex();
            return null;
        }

        final ByteBuf payload = in.readRetainedSlice(payloadLength);
        return new MessageEnvelope(generation, leaseId, fenceToken, sequenceNumber, requestId, messageType,
                flags, payload);
    }

    public MessageEnvelope withSequenceNumber(final long sequenceNumber) {
        return new MessageEnvelope(generation, leaseId, fenceToken, sequenceNumber, requestId, messageType, flags,
                payload);
    }

    public MessageEnvelope withRequestId(final long requestId) {
        return new MessageEnvelope(generation, leaseId, fenceToken, sequenceNumber, requestId, messageType, flags,
                payload);
    }

    public MessageEnvelope withGeneration(final long generation) {
        return new MessageEnvelope(generation, leaseId, fenceToken, sequenceNumber, requestId, messageType, flags,
                payload);
    }

    public MessageEnvelope withLeaseId(final long leaseId) {
        return new MessageEnvelope(generation, leaseId, fenceToken, sequenceNumber, requestId, messageType, flags,
                payload);
    }

    public MessageEnvelope withFenceToken(final long fenceToken) {
        return new MessageEnvelope(generation, leaseId, fenceToken, sequenceNumber, requestId, messageType, flags,
                payload);
    }

    @Override
    public String toString() {
        return "MessageEnvelope{" +
                "type=" + messageType +
                ", gen=" + generation +
                ", lease=" + leaseId +
                ", fence=" + fenceToken +
                ", seq=" + sequenceNumber +
                ", req=" + requestId +
                ", payload=" + payload.readableBytes() + "B" +
                '}';
    }
}
