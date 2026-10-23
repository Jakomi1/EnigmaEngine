package io.enigmaclient.chunkmap.player;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * One server reported online player position, fed into Xaero's player tracker pipelines so the
 * markers show up on the minimap and the world map. Drawn with a generic icon (never a skin head),
 * see the Xaero renderer mixins.
 */
public record EnigmaPlayerMarker(UUID id, String name, double x, double y, double z, ResourceKey<Level> dimension) {
}
