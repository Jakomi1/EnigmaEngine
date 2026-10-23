package io.enigmaclient.chunkmap;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client to server request to start ({@code true}) or stop ({@code false}) receiving chunk map
 * snapshots.
 */
public record ChunkMapSubscribePayload(boolean subscribed) implements CustomPacketPayload {

    public static final Type<ChunkMapSubscribePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("enigmaclient", "chunkmap_subscribe"));

    public static final StreamCodec<FriendlyByteBuf, ChunkMapSubscribePayload> CODEC =
            StreamCodec.of(ChunkMapSubscribePayload::write, ChunkMapSubscribePayload::read);

    private static void write(FriendlyByteBuf buf, ChunkMapSubscribePayload payload) {
        buf.writeBoolean(payload.subscribed);
    }

    private static ChunkMapSubscribePayload read(FriendlyByteBuf buf) {
        return new ChunkMapSubscribePayload(buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
