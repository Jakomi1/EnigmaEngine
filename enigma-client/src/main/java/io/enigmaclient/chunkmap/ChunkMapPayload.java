package io.enigmaclient.chunkmap;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server to client snapshot of the chunks the server considers loaded ({@code loaded}) and actively
 * ticking ({@code ticking}) for a single dimension. Positions are packed as {@code (chunkX << 32) | chunkZ}.
 */
public record ChunkMapPayload(Identifier dimension, long sequence, boolean enabled, long[] loaded, long[] ticking)
        implements CustomPacketPayload {

    public static final Type<ChunkMapPayload> TYPE = CustomPacketPayload.createType("enigmaclient:chunkmap");

    public static final StreamCodec<FriendlyByteBuf, ChunkMapPayload> CODEC =
            StreamCodec.of(ChunkMapPayload::write, ChunkMapPayload::read);

    private static void write(FriendlyByteBuf buf, ChunkMapPayload payload) {
        buf.writeUtf(payload.dimension.toString());
        buf.writeLong(payload.sequence);
        buf.writeBoolean(payload.enabled);
        writeLongs(buf, payload.loaded);
        writeLongs(buf, payload.ticking);
    }

    private static ChunkMapPayload read(FriendlyByteBuf buf) {
        Identifier dimension = Identifier.parse(buf.readUtf());
        long sequence = buf.readLong();
        boolean enabled = buf.readBoolean();
        long[] loaded = readLongs(buf);
        long[] ticking = readLongs(buf);
        return new ChunkMapPayload(dimension, sequence, enabled, loaded, ticking);
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

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
