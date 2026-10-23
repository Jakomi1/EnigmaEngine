package io.enigmaclient.chunkmap.xaero;

import io.enigmaclient.EnigmaClient;
import io.enigmaclient.chunkmap.ChunkMapStore;
import io.enigmaclient.chunkmap.player.EnigmaPlayerMarker;
import io.enigmaclient.config.EnigmaConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds the marker list handed to Xaero's player tracker systems from the latest server snapshot.
 * Called from Xaero's own threads, so only the immutable store snapshot is read.
 */
public final class EnigmaPlayerTrackerSupport {

    static final int BORDER_COLOR = 0xFF000000;
    static final int SELF_COLOR = 0xFF55FFFF;
    static final int OTHER_COLOR = 0xFFFFAA00;
    static final int NAME_COLOR = 0xFFFFFFFF;

    private EnigmaPlayerTrackerSupport() {
    }

    /**
     * Whether player markers must stay on the maps (Xaero Minimap/World Map). When {@code true}
     * the world rendering of tracked players is suppressed, so markers never appear in the actual
     * game world. Defaults to {@code true} when no configuration is loaded.
     */
    public static boolean mapOnly() {
        final EnigmaConfig config = EnigmaClient.config;
        return config == null || config.chunkMapPlayersMapOnly;
    }

    /** Every player of the snapshot's dimension, or an empty list when markers are turned off. */
    static List<EnigmaPlayerMarker> markers() {
        final EnigmaConfig config = EnigmaClient.config;
        if (config == null || !config.showChunkMap || !config.chunkMapShowPlayers) {
            return List.of();
        }
        final ChunkMapStore store = ChunkMapStore.INSTANCE;
        final ChunkMapStore.PlayerMarker[] raw = store.markers();
        if (raw.length == 0) {
            return List.of();
        }
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return List.of();
        }
        final Identifier dimension = store.dimension();
        if (dimension == null || !dimension.equals(minecraft.level.dimension().identifier())) {
            return List.of();
        }
        final List<EnigmaPlayerMarker> markers = new ArrayList<>(raw.length);
        for (final ChunkMapStore.PlayerMarker marker : raw) {
            markers.add(new EnigmaPlayerMarker(marker.id(), marker.name(),
                    marker.x() + 0.5D, marker.y(), marker.z() + 0.5D,
                    ResourceKey.create(Registries.DIMENSION, marker.dimension())));
        }
        return markers;
    }

    /** Fill color of the generic marker icon: cyan for the local player, orange for everyone else. */
    static int colorOf(final UUID id) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.player != null && id.equals(minecraft.player.getUUID())) {
            return SELF_COLOR;
        }
        return OTHER_COLOR;
    }
}
