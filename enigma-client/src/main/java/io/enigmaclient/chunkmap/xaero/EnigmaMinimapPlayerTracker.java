package io.enigmaclient.chunkmap.xaero;

import io.enigmaclient.chunkmap.player.EnigmaPlayerMarker;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.hud.minimap.player.tracker.system.IRenderedPlayerTracker;
import xaero.hud.minimap.player.tracker.system.ITrackedPlayerReader;
import xaero.hud.minimap.player.tracker.system.RenderedPlayerTrackerManager;

import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Feeds the server reported player positions into Xaero's Minimap player tracker. Registered once
 * per tracker manager from a collector mixin; Xaero then collects and renders the markers like any
 * other tracked player. The icon substitution (generic marker instead of a skin head) happens in
 * the renderer mixin.
 */
public final class EnigmaMinimapPlayerTracker implements IRenderedPlayerTracker<EnigmaPlayerMarker> {

    public static final String KEY = "enigma_chunk_map";

    private static final EnigmaMinimapPlayerTracker INSTANCE = new EnigmaMinimapPlayerTracker();
    private static final Object LOCK = new Object();
    private static final Set<RenderedPlayerTrackerManager> REGISTERED =
            Collections.newSetFromMap(new WeakHashMap<>());

    private final ITrackedPlayerReader<EnigmaPlayerMarker> reader = new Reader();

    private EnigmaMinimapPlayerTracker() {
    }

    /** Registers this tracker exactly once per tracker manager instance. */
    public static void ensureRegistered(final RenderedPlayerTrackerManager manager) {
        synchronized (LOCK) {
            if (REGISTERED.add(manager)) {
                manager.register(KEY, INSTANCE);
            }
        }
    }

    @Override
    public ITrackedPlayerReader<EnigmaPlayerMarker> getReader() {
        return this.reader;
    }

    @Override
    public Iterator<EnigmaPlayerMarker> getTrackedPlayerIterator() {
        return EnigmaPlayerTrackerSupport.markers().iterator();
    }

    private static final class Reader implements ITrackedPlayerReader<EnigmaPlayerMarker> {

        @Override
        public UUID getId(final EnigmaPlayerMarker marker) {
            return marker.id();
        }

        @Override
        public double getX(final EnigmaPlayerMarker marker) {
            return marker.x();
        }

        @Override
        public double getY(final EnigmaPlayerMarker marker) {
            return marker.y();
        }

        @Override
        public double getZ(final EnigmaPlayerMarker marker) {
            return marker.z();
        }

        @Override
        public ResourceKey<Level> getDimension(final EnigmaPlayerMarker marker) {
            return marker.dimension();
        }
    }
}
