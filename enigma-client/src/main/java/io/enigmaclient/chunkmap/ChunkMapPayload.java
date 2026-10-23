package io.enigmaclient.chunkmap;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * Server to client snapshot for a single dimension.
 *
 * <p>It carries the chunks the server has loaded ({@code ticking}, server wide), the exact
 * configured merge-buffer radius in chunks, and, grouped per
 * Canvas/Folia region, the loaded chunks that region owns. The region table is described by
 * {@code regionIds} (sorted, one entry per region that owns at least one loaded chunk), a parallel
 * {@code regionChunkCounts} array and {@code regionChunks}, the flattened chunk positions in region
 * order. Every region slice of {@code regionChunks} is sorted and packed with
 * {@link net.minecraft.world.level.ChunkPos#pack(int, int)} (x in the low 32 bits, z in the high 32
 * bits).</p>
 *
 * <p>The region surface is the chunks the server has actually loaded (the same set Folia's
 * {@code /tps} command counts), not the regionizer's holder-owned chunks.</p>
 *
 * <p>The region stats arrays are aligned to the region table and describe each region for the hover
 * debug overlay: entity count, MSPT (millis*1000) and TPS (millis*1000). The chunk count of a
 * region is {@code regionChunkCounts}. Finally the player arrays describe every online player's
 * position and name.</p>
 */
public record ChunkMapPayload(
        int version,
        Identifier dimension,
        long sequence,
        boolean enabled,
        int bufferRadiusChunks,
        long[] ticking,
        long[] regionIds,
        int[] regionChunkCounts,
        long[] regionChunks,
        long[] regionEntities,
        long[] regionMsptMilli,
        long[] regionTpsMilli,
        UUID[] playerIds,
        String[] playerNames,
        int[] playerX,
        int[] playerY,
        int[] playerZ
) implements CustomPacketPayload {

    public static final int PROTOCOL_VERSION = 8;

    public static final Type<ChunkMapPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("enigmaclient", "chunkmap"));

    public static final StreamCodec<FriendlyByteBuf, ChunkMapPayload> CODEC =
            StreamCodec.of(ChunkMapPayload::write, ChunkMapPayload::read);

    public static ChunkMapPayload disabled(final Identifier dimension, final long sequence) {
        return new ChunkMapPayload(PROTOCOL_VERSION, dimension, sequence, false, 0,
                new long[0], new long[0], new int[0], new long[0],
                new long[0], new long[0], new long[0],
                new UUID[0], new String[0], new int[0], new int[0], new int[0]);
    }

    private static void write(FriendlyByteBuf buf, ChunkMapPayload payload) {
        buf.writeByte(payload.version);
        buf.writeUtf(payload.dimension.toString());
        buf.writeLong(payload.sequence);
        buf.writeBoolean(payload.enabled);
        buf.writeVarInt(payload.bufferRadiusChunks);
        writeLongs(buf, payload.ticking);
        writeLongs(buf, payload.regionIds);
        writeVarInts(buf, payload.regionChunkCounts);
        writeLongs(buf, payload.regionChunks);
        writeLongs(buf, payload.regionEntities);
        writeLongs(buf, payload.regionMsptMilli);
        writeLongs(buf, payload.regionTpsMilli);
        buf.writeVarInt(payload.playerIds.length);
        for (int i = 0; i < payload.playerIds.length; i++) {
            buf.writeUUID(payload.playerIds[i]);
            buf.writeUtf(payload.playerNames[i]);
            buf.writeVarInt(payload.playerX[i]);
            buf.writeVarInt(payload.playerY[i]);
            buf.writeVarInt(payload.playerZ[i]);
        }
    }

    private static ChunkMapPayload read(FriendlyByteBuf buf) {
        int version = buf.readByte() & 0xFF;
        if (version != PROTOCOL_VERSION) {
            buf.skipBytes(buf.readableBytes());
            return new ChunkMapPayload(version, null, 0L, false, 0,
                    new long[0], new long[0], new int[0], new long[0],
                    new long[0], new long[0], new long[0],
                    new UUID[0], new String[0], new int[0], new int[0], new int[0]);
        }
        Identifier dimension = Identifier.parse(buf.readUtf());
        long sequence = buf.readLong();
        boolean enabled = buf.readBoolean();
        int bufferRadiusChunks = buf.readVarInt();
        long[] ticking = readLongs(buf);
        long[] regionIds = readLongs(buf);
        int[] regionChunkCounts = readVarInts(buf);
        long[] regionChunks = readLongs(buf);
        long[] regionEntities = readLongs(buf);
        long[] regionMsptMilli = readLongs(buf);
        long[] regionTpsMilli = readLongs(buf);
        int playerCount = buf.readVarInt();
        UUID[] playerIds = new UUID[playerCount];
        String[] playerNames = new String[playerCount];
        int[] playerX = new int[playerCount];
        int[] playerY = new int[playerCount];
        int[] playerZ = new int[playerCount];
        for (int i = 0; i < playerCount; i++) {
            playerIds[i] = buf.readUUID();
            playerNames[i] = buf.readUtf();
            playerX[i] = buf.readVarInt();
            playerY[i] = buf.readVarInt();
            playerZ[i] = buf.readVarInt();
        }
        return new ChunkMapPayload(version, dimension, sequence, enabled, bufferRadiusChunks,
                ticking, regionIds, regionChunkCounts, regionChunks,
                regionEntities, regionMsptMilli, regionTpsMilli,
                playerIds, playerNames, playerX, playerY, playerZ);
    }

    private static void writeLongs(FriendlyByteBuf buf, long[] values) {
        buf.writeVarInt(values.length);
        for (long value : values) {
            buf.writeLong(value);
        }
    }

    private static long[] readLongs(FriendlyByteBuf buf) {
        int length = buf.readVarInt();
        long[] values = new long[length];
        for (int i = 0; i < length; i++) {
            values[i] = buf.readLong();
        }
        return values;
    }

    private static void writeVarInts(FriendlyByteBuf buf, int[] values) {
        buf.writeVarInt(values.length);
        for (int value : values) {
            buf.writeVarInt(value);
        }
    }

    private static int[] readVarInts(FriendlyByteBuf buf) {
        int length = buf.readVarInt();
        int[] values = new int[length];
        for (int i = 0; i < length; i++) {
            values[i] = buf.readVarInt();
        }
        return values;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
