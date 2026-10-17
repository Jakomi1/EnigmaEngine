package io.enigmaclient.chunkmap;

import io.enigmaclient.EnigmaClient;
import io.enigmaclient.config.EnigmaConfig;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;

/**
 * Wires the chunk map payloads into the client. Works with any vanilla-protocol server (the payload
 * is delivered through the standard custom payload channel), so no Fabric handshake is required.
 */
public final class ChunkMapClient {

    private static boolean registered;

    private ChunkMapClient() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        PayloadTypeRegistry.clientboundPlay().register(ChunkMapPayload.TYPE, ChunkMapPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ChunkMapSubscribePayload.TYPE, ChunkMapSubscribePayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(ChunkMapPayload.TYPE, (payload, context) -> {
            EnigmaConfig config = EnigmaClient.config;
            if (config != null && config.showChunkMap) {
                ChunkMapStore.INSTANCE.apply(payload);
            }
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> syncSubscription());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ChunkMapStore.INSTANCE.clear());
    }

    /**
     * Tells the server whether this client wants chunk map snapshots. Safe to call at any time; does
     * nothing when no world is loaded.
     */
    public static void syncSubscription() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getConnection() == null) {
            return;
        }
        EnigmaConfig config = EnigmaClient.config;
        boolean subscribed = config != null && config.showChunkMap;
        if (!subscribed) {
            ChunkMapStore.INSTANCE.clear();
        }
        ClientPlayNetworking.send(new ChunkMapSubscribePayload(subscribed));
    }
}
