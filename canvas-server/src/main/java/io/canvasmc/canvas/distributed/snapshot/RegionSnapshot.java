package io.canvasmc.canvas.distributed.snapshot;

import io.netty.buffer.ByteBuf;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * A full, self-contained image of one ticked region.
 *
 * <p>The encoded form is a single contiguous byte stream: a fixed header, the regionized data
 * map, then every chunk and entity. {@link #checksum()} is a CRC32 over exactly that stream, so
 * a receiver can prove that what it reassembled is bit-identical to what the World Host captured
 * before it is allowed to take over ticking.</p>
 */
public final class RegionSnapshot {

    public static final int CURRENT_VERSION = 1;

    public final long worldId;
    public final long regionId;
    public final long generation;
    public final long regionTick;
    public final int chunkCount;
    public final int entityCount;
    public final long checksum;
    public final OwnershipMetadata ownership;
    public final SchedulerSnapshot schedulerSnapshot;
    public final Map<String, byte[]> regionizedData;

    private final List<ChunkSnapshot> chunks;
    private final List<EntitySnapshot> entities;

    public RegionSnapshot(
            final long worldId,
            final long regionId,
            final long generation,
            final long regionTick,
            final OwnershipMetadata ownership,
            final SchedulerSnapshot schedulerSnapshot,
            final Map<String, byte[]> regionizedData
    ) {
        this(worldId, regionId, generation, regionTick, 0, 0, 0L, ownership, schedulerSnapshot,
                regionizedData, new ArrayList<>(), new ArrayList<>());
    }

    private RegionSnapshot(
            final long worldId,
            final long regionId,
            final long generation,
            final long regionTick,
            final int chunkCount,
            final int entityCount,
            final long checksum,
            final OwnershipMetadata ownership,
            final SchedulerSnapshot schedulerSnapshot,
            final Map<String, byte[]> regionizedData,
            final List<ChunkSnapshot> chunks,
            final List<EntitySnapshot> entities
    ) {
        this.worldId = worldId;
        this.regionId = regionId;
        this.generation = generation;
        this.regionTick = regionTick;
        this.chunkCount = chunkCount;
        this.entityCount = entityCount;
        this.checksum = checksum;
        this.ownership = ownership;
        this.schedulerSnapshot = schedulerSnapshot;
        this.regionizedData = regionizedData;
        this.chunks = chunks;
        this.entities = entities;
    }

    public void addChunk(final ChunkSnapshot chunk) {
        chunks.add(chunk);
    }

    public void addEntity(final EntitySnapshot entity) {
        entities.add(entity);
    }

    public List<ChunkSnapshot> getChunks() {
        return chunks;
    }

    public List<EntitySnapshot> getEntities() {
        return entities;
    }

    public int getActualChunkCount() {
        return chunks.size();
    }

    public int getActualEntityCount() {
        return entities.size();
    }

    /**
     * Encodes the complete snapshot, header included, into {@code out}.
     * The returned value is the CRC32 that the header advertises.
     */
    public long encodeTo(final ByteBuf out) {
        final CRC32 crc = new CRC32();
        final long checksum = encodeBody(out, crc);
        // The checksum is only known once the rest is written, so writeHeader() emits a placeholder
        // that encodeBody() back-patches.
        return checksum;
    }

    private long encodeBody(final ByteBuf out, final CRC32 crc) {
        final int headerStart = out.writerIndex();
        writeHeader(out, 0L);

        out.writeInt(chunks.size());
        for (final ChunkSnapshot chunk : chunks) {
            chunk.encode(out);
        }
        out.writeInt(entities.size());
        for (final EntitySnapshot entity : entities) {
            entity.encode(out);
        }

        // CRC over everything except the checksum field itself. This range has to match
        // verifyChecksum() exactly - it used to stop at bodyStart, which silently excluded the
        // ownership/scheduler/regionizedData tail of the header from the checksum.
        crc.reset();
        updateCrc(crc, out, headerStart, checksumFieldOffset(headerStart));
        updateCrc(crc, out, checksumFieldOffset(headerStart) + Long.BYTES, out.writerIndex());
        final long checksum = crc.getValue();

        // Back-patch the advertised checksum.
        out.setLong(checksumFieldOffset(headerStart), checksum);
        return checksum;
    }

    private static int checksumFieldOffset(final int headerStart) {
        // 4 version + 8 worldId + 8 regionId + 8 generation + 8 regionTick + 4 chunkCount + 4 entityCount
        return headerStart + 4 + 8 + 8 + 8 + 8 + 4 + 4;
    }

    private static void updateCrc(final CRC32 crc, final ByteBuf buf, final int from, final int to) {
        for (int i = from; i < to; i++) {
            crc.update(buf.getByte(i));
        }
    }

    private void writeHeader(final ByteBuf out, final long checksum) {
        out.writeInt(CURRENT_VERSION);
        out.writeLong(worldId);
        out.writeLong(regionId);
        out.writeLong(generation);
        out.writeLong(regionTick);
        out.writeInt(chunks.size());
        out.writeInt(entities.size());
        out.writeLong(checksum);
        ownership.encode(out);
        schedulerSnapshot.encode(out);
        out.writeInt(regionizedData.size());
        for (final Map.Entry<String, byte[]> entry : regionizedData.entrySet()) {
            writeString(out, entry.getKey());
            out.writeInt(entry.getValue().length);
            out.writeBytes(entry.getValue());
        }
    }

    public static @Nullable RegionSnapshot decode(final ByteBuf in) {
        if (in.readableBytes() < 4) {
            return null;
        }
        in.markReaderIndex();

        final int version = in.readInt();
        if (version != CURRENT_VERSION) {
            in.resetReaderIndex();
            return null;
        }

        final long worldId = in.readLong();
        final long regionId = in.readLong();
        final long generation = in.readLong();
        final long regionTick = in.readLong();
        final int chunkCount = in.readInt();
        final int entityCount = in.readInt();
        final long checksum = in.readLong();
        final OwnershipMetadata ownership = OwnershipMetadata.decode(in);
        final SchedulerSnapshot schedulerSnapshot = SchedulerSnapshot.decode(in);

        final int regionizedDataCount = in.readInt();
        if (regionizedDataCount < 0) {
            in.resetReaderIndex();
            return null;
        }
        final Map<String, byte[]> regionizedData = new java.util.LinkedHashMap<>();
        for (int i = 0; i < regionizedDataCount; i++) {
            final String key = readString(in);
            final int length = readLength(in);
            if (length < 0 || in.readableBytes() < length) {
                in.resetReaderIndex();
                return null;
            }
            final byte[] data = new byte[length];
            in.readBytes(data);
            regionizedData.put(key, data);
        }

        final int actualChunkCount = in.readInt();
        if (actualChunkCount < 0) {
            in.resetReaderIndex();
            return null;
        }
        final List<ChunkSnapshot> chunks = new ArrayList<>(actualChunkCount);
        for (int i = 0; i < actualChunkCount; i++) {
            chunks.add(ChunkSnapshot.decode(in));
        }

        final int actualEntityCount = in.readInt();
        if (actualEntityCount < 0) {
            in.resetReaderIndex();
            return null;
        }
        final List<EntitySnapshot> entities = new ArrayList<>(actualEntityCount);
        for (int i = 0; i < actualEntityCount; i++) {
            entities.add(EntitySnapshot.decode(in));
        }

        return new RegionSnapshot(worldId, regionId, generation, regionTick, chunkCount, entityCount, checksum,
                ownership, schedulerSnapshot, regionizedData, chunks, entities);
    }

    /**
     * Recomputes the CRC over the encoded form of this snapshot and compares it against the
     * advertised value. Used by tests and by the receiver after reassembly.
     */
    public boolean verifyChecksum(final ByteBuf encoded) {
        if (encoded.readableBytes() < 8) {
            return false;
        }
        final int start = encoded.readerIndex();
        final long advertised = encoded.getLong(start + checksumFieldOffset(0));
        final CRC32 crc = new CRC32();
        updateCrc(crc, encoded, start, start + checksumFieldOffset(0));
        updateCrc(crc, encoded, start + checksumFieldOffset(0) + 8, encoded.writerIndex());
        return advertised == crc.getValue();
    }

    private static int readLength(final ByteBuf in) {
        final int length = in.readInt();
        if (length < 0) {
            throw new IllegalStateException("Negative length in snapshot stream");
        }
        return length;
    }

    private static void writeString(final ByteBuf out, final String s) {
        final byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.writeBytes(bytes);
    }

    private static String readString(final ByteBuf in) {
        final int length = readLength(in);
        final byte[] bytes = new byte[length];
        in.readBytes(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static final class ChunkSnapshot {
        public final int chunkX;
        public final int chunkZ;
        public final byte[] blockData;
        public final List<BlockEntitySnapshot> blockEntities;
        public final ScheduledTicksSnapshot ticks;

        public ChunkSnapshot(
                final int chunkX,
                final int chunkZ,
                final byte[] blockData,
                final List<BlockEntitySnapshot> blockEntities,
                final ScheduledTicksSnapshot ticks
        ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.blockData = blockData;
            this.blockEntities = blockEntities != null ? blockEntities : new ArrayList<>();
            this.ticks = ticks != null ? ticks : new ScheduledTicksSnapshot(null, null);
        }

        public void encode(final ByteBuf out) {
            out.writeInt(chunkX);
            out.writeInt(chunkZ);
            out.writeInt(blockData.length);
            out.writeBytes(blockData);
            out.writeInt(blockEntities.size());
            for (final BlockEntitySnapshot be : blockEntities) {
                be.encode(out);
            }
            ticks.encode(out);
        }

        public static ChunkSnapshot decode(final ByteBuf in) {
            final int chunkX = in.readInt();
            final int chunkZ = in.readInt();
            final int blockDataLen = readLength(in);
            final byte[] blockData = new byte[blockDataLen];
            in.readBytes(blockData);
            final int beCount = readLength(in);
            final List<BlockEntitySnapshot> blockEntities = new ArrayList<>(beCount);
            for (int i = 0; i < beCount; i++) {
                blockEntities.add(BlockEntitySnapshot.decode(in));
            }
            final ScheduledTicksSnapshot ticks = ScheduledTicksSnapshot.decode(in);
            return new ChunkSnapshot(chunkX, chunkZ, blockData, blockEntities, ticks);
        }
    }

    public static final class BlockEntitySnapshot {
        public final int blockX;
        public final int blockY;
        public final int blockZ;
        public final String type;
        public final byte[] nbtData;

        public BlockEntitySnapshot(final int blockX, final int blockY, final int blockZ, final String type, final byte[] nbtData) {
            this.blockX = blockX;
            this.blockY = blockY;
            this.blockZ = blockZ;
            this.type = type;
            this.nbtData = nbtData;
        }

        public void encode(final ByteBuf out) {
            out.writeInt(blockX);
            out.writeInt(blockY);
            out.writeInt(blockZ);
            writeString(out, type);
            out.writeInt(nbtData.length);
            out.writeBytes(nbtData);
        }

        public static BlockEntitySnapshot decode(final ByteBuf in) {
            final int x = in.readInt();
            final int y = in.readInt();
            final int z = in.readInt();
            final String type = readString(in);
            final int len = readLength(in);
            final byte[] nbt = new byte[len];
            in.readBytes(nbt);
            return new BlockEntitySnapshot(x, y, z, type, nbt);
        }
    }

    public static final class EntitySnapshot {
        public final int entityId;
        public final UUID uuid;
        public final String type;
        public final double posX, posY, posZ;
        public final double motionX, motionY, motionZ;
        public final float yaw, pitch;
        public final byte[] nbtData;
        public final @Nullable EntityRegionTransferState transferState;

        public EntitySnapshot(
                final int entityId,
                final UUID uuid,
                final String type,
                final double posX, final double posY, final double posZ,
                final double motionX, final double motionY, final double motionZ,
                final float yaw, final float pitch,
                final byte[] nbtData,
                final @Nullable EntityRegionTransferState transferState
        ) {
            this.entityId = entityId;
            this.uuid = uuid;
            this.type = type;
            this.posX = posX;
            this.posY = posY;
            this.posZ = posZ;
            this.motionX = motionX;
            this.motionY = motionY;
            this.motionZ = motionZ;
            this.yaw = yaw;
            this.pitch = pitch;
            this.nbtData = nbtData;
            this.transferState = transferState;
        }

        public void encode(final ByteBuf out) {
            out.writeInt(entityId);
            writeUuid(out, uuid);
            writeString(out, type);
            out.writeDouble(posX);
            out.writeDouble(posY);
            out.writeDouble(posZ);
            out.writeDouble(motionX);
            out.writeDouble(motionY);
            out.writeDouble(motionZ);
            out.writeFloat(yaw);
            out.writeFloat(pitch);
            out.writeInt(nbtData.length);
            out.writeBytes(nbtData);
            out.writeBoolean(transferState != null);
            if (transferState != null) {
                transferState.encode(out);
            }
        }

        public static EntitySnapshot decode(final ByteBuf in) {
            final int entityId = in.readInt();
            final UUID uuid = readUuid(in);
            final String type = readString(in);
            final double posX = in.readDouble();
            final double posY = in.readDouble();
            final double posZ = in.readDouble();
            final double motionX = in.readDouble();
            final double motionY = in.readDouble();
            final double motionZ = in.readDouble();
            final float yaw = in.readFloat();
            final float pitch = in.readFloat();
            final int nbtLen = readLength(in);
            final byte[] nbt = new byte[nbtLen];
            in.readBytes(nbt);
            final boolean hasTransferState = in.readBoolean();
            final EntityRegionTransferState transferState = hasTransferState ? EntityRegionTransferState.decode(in) : null;
            return new EntitySnapshot(entityId, uuid, type, posX, posY, posZ, motionX, motionY, motionZ, yaw, pitch, nbt, transferState);
        }

        private static void writeUuid(final ByteBuf out, final UUID uuid) {
            out.writeLong(uuid.getMostSignificantBits());
            out.writeLong(uuid.getLeastSignificantBits());
        }

        private static UUID readUuid(final ByteBuf in) {
            return new UUID(in.readLong(), in.readLong());
        }
    }

    public static final class EntityRegionTransferState {
        public enum Type {
            GENERIC_TELEPORT,
            PLUGIN_TELEPORT_ASYNC,
            PORTAL_START,
            PORTAL_FINISH
        }

        public final String reason;
        public final Type type;
        public final double fromX, fromY, fromZ;
        public final double toX, toY, toZ;
        public final List<String> callTrace;
        public final String extra;

        public EntityRegionTransferState(
                final String reason,
                final Type type,
                final double fromX, final double fromY, final double fromZ,
                final double toX, final double toY, final double toZ,
                final List<String> callTrace,
                final String extra
        ) {
            this.reason = reason;
            this.type = type;
            this.fromX = fromX;
            this.fromY = fromY;
            this.fromZ = fromZ;
            this.toX = toX;
            this.toY = toY;
            this.toZ = toZ;
            this.callTrace = callTrace != null ? callTrace : new ArrayList<>();
            this.extra = extra != null ? extra : "";
        }

        public void encode(final ByteBuf out) {
            writeString(out, reason);
            out.writeByte(type.ordinal());
            out.writeDouble(fromX);
            out.writeDouble(fromY);
            out.writeDouble(fromZ);
            out.writeDouble(toX);
            out.writeDouble(toY);
            out.writeDouble(toZ);
            out.writeInt(callTrace.size());
            for (final String ste : callTrace) {
                writeString(out, ste);
            }
            writeString(out, extra);
        }

        public static EntityRegionTransferState decode(final ByteBuf in) {
            final String reason = readString(in);
            final byte typeOrdinal = in.readByte();
            final Type[] types = Type.values();
            if (typeOrdinal < 0 || typeOrdinal >= types.length) {
                throw new IllegalStateException("Invalid entity transfer type " + typeOrdinal);
            }
            final double fromX = in.readDouble();
            final double fromY = in.readDouble();
            final double fromZ = in.readDouble();
            final double toX = in.readDouble();
            final double toY = in.readDouble();
            final double toZ = in.readDouble();
            final int traceCount = readLength(in);
            final List<String> callTrace = new ArrayList<>(traceCount);
            for (int i = 0; i < traceCount; i++) {
                callTrace.add(readString(in));
            }
            final String extra = readString(in);
            return new EntityRegionTransferState(reason, types[typeOrdinal], fromX, fromY, fromZ, toX, toY, toZ,
                    callTrace, extra);
        }
    }

    public static final class ScheduledTicksSnapshot {
        public final List<BlockTickSnapshot> blockTicks;
        public final List<FluidTickSnapshot> fluidTicks;

        public ScheduledTicksSnapshot(final List<BlockTickSnapshot> blockTicks, final List<FluidTickSnapshot> fluidTicks) {
            this.blockTicks = blockTicks != null ? blockTicks : new ArrayList<>();
            this.fluidTicks = fluidTicks != null ? fluidTicks : new ArrayList<>();
        }

        public void encode(final ByteBuf out) {
            out.writeInt(blockTicks.size());
            for (final BlockTickSnapshot bt : blockTicks) {
                bt.encode(out);
            }
            out.writeInt(fluidTicks.size());
            for (final FluidTickSnapshot ft : fluidTicks) {
                ft.encode(out);
            }
        }

        public static ScheduledTicksSnapshot decode(final ByteBuf in) {
            final int blockCount = readLength(in);
            final List<BlockTickSnapshot> blockTicks = new ArrayList<>(blockCount);
            for (int i = 0; i < blockCount; i++) {
                blockTicks.add(BlockTickSnapshot.decode(in));
            }
            final int fluidCount = readLength(in);
            final List<FluidTickSnapshot> fluidTicks = new ArrayList<>(fluidCount);
            for (int i = 0; i < fluidCount; i++) {
                fluidTicks.add(FluidTickSnapshot.decode(in));
            }
            return new ScheduledTicksSnapshot(blockTicks, fluidTicks);
        }
    }

    public static final class BlockTickSnapshot {
        public final int x, y, z;
        public final String blockType;
        public final int delay;
        public final int priority;

        public BlockTickSnapshot(final int x, final int y, final int z, final String blockType, final int delay, final int priority) {
            this.x = x; this.y = y; this.z = z;
            this.blockType = blockType;
            this.delay = delay;
            this.priority = priority;
        }

        public void encode(final ByteBuf out) {
            out.writeInt(x); out.writeInt(y); out.writeInt(z);
            writeString(out, blockType);
            out.writeInt(delay);
            out.writeInt(priority);
        }

        public static BlockTickSnapshot decode(final ByteBuf in) {
            return new BlockTickSnapshot(in.readInt(), in.readInt(), in.readInt(), readString(in), in.readInt(), in.readInt());
        }
    }

    public static final class FluidTickSnapshot {
        public final int x, y, z;
        public final String fluidType;
        public final int delay;

        public FluidTickSnapshot(final int x, final int y, final int z, final String fluidType, final int delay) {
            this.x = x; this.y = y; this.z = z;
            this.fluidType = fluidType;
            this.delay = delay;
        }

        public void encode(final ByteBuf out) {
            out.writeInt(x); out.writeInt(y); out.writeInt(z);
            writeString(out, fluidType);
            out.writeInt(delay);
        }

        public static FluidTickSnapshot decode(final ByteBuf in) {
            return new FluidTickSnapshot(in.readInt(), in.readInt(), in.readInt(), readString(in), in.readInt());
        }
    }

    public static final class SchedulerSnapshot {
        public final byte[] priorityQueueData;

        public SchedulerSnapshot(final byte[] priorityQueueData) {
            this.priorityQueueData = priorityQueueData != null ? priorityQueueData : new byte[0];
        }

        public static SchedulerSnapshot empty() {
            return new SchedulerSnapshot(new byte[0]);
        }

        public void encode(final ByteBuf out) {
            out.writeInt(priorityQueueData.length);
            out.writeBytes(priorityQueueData);
        }

        public static SchedulerSnapshot decode(final ByteBuf in) {
            final int len = readLength(in);
            final byte[] data = new byte[len];
            in.readBytes(data);
            return new SchedulerSnapshot(data);
        }
    }

    public static final class OwnershipMetadata {
        public final long leaseId;
        public final long fenceToken;
        public final long generation;
        public final long ownerInstanceId;

        public OwnershipMetadata(final long leaseId, final long fenceToken, final long generation, final long ownerInstanceId) {
            this.leaseId = leaseId;
            this.fenceToken = fenceToken;
            this.generation = generation;
            this.ownerInstanceId = ownerInstanceId;
        }

        public void encode(final ByteBuf out) {
            out.writeLong(leaseId);
            out.writeLong(fenceToken);
            out.writeLong(generation);
            out.writeLong(ownerInstanceId);
        }

        public static OwnershipMetadata decode(final ByteBuf in) {
            return new OwnershipMetadata(in.readLong(), in.readLong(), in.readLong(), in.readLong());
        }
    }

    /**
     * Round-trips this snapshot through the wire format and asserts the CRC survives.
     * Throws if the encoding is not self-consistent.
     */
    public RegionSnapshot selfCheck() {
        final io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
        try {
            encodeTo(buf);
            if (!verifyChecksum(buf)) {
                throw new IllegalStateException("Snapshot checksum mismatch after encode");
            }
            final RegionSnapshot decoded = decode(buf.duplicate());
            if (decoded == null) {
                throw new IllegalStateException("Snapshot failed to decode");
            }
            if (decoded.checksum != checksumOf(buf)) {
                throw new IllegalStateException("Decoded checksum does not match advertised");
            }
            if (decoded.getActualChunkCount() != getActualChunkCount()) {
                throw new IllegalStateException("Chunk count mismatch: " + decoded.getActualChunkCount()
                        + " != " + getActualChunkCount());
            }
            if (decoded.getActualEntityCount() != getActualEntityCount()) {
                throw new IllegalStateException("Entity count mismatch: " + decoded.getActualEntityCount()
                        + " != " + getActualEntityCount());
            }
            return decoded;
        } finally {
            buf.release();
        }
    }

    private static long checksumOf(final ByteBuf buf) {
        return buf.getLong(buf.readerIndex() + checksumFieldOffset(0));
    }

    @Override
    public String toString() {
        return "RegionSnapshot{region=" + regionId + ", gen=" + generation + ", chunks=" + chunks.size()
                + ", entities=" + entities.size() + ", checksum=" + Long.toHexString(checksum) + "}";
    }

    /**
     * Convenience for tests: encodes to a plain byte array.
     */
    public byte[] toByteArray() {
        final io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
        try {
            encodeTo(buf);
            final byte[] out = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), out);
            return out;
        } finally {
            buf.release();
        }
    }

    public static @Nullable RegionSnapshot fromByteArray(final byte[] data) {
        return decode(io.netty.buffer.Unpooled.wrappedBuffer(Arrays.copyOf(data, data.length)));
    }
}
