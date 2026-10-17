package io.enigmaclient.chunkmap;

import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Client side snapshot of the chunks a server reports as loaded and ticking, keyed by dimension.
 *
 * <p>All access happens on the client game thread (payload handlers and rendering share it), so no
 * synchronisation is required.</p>
 */
public final class ChunkMapStore {

    public static final ChunkMapStore INSTANCE = new ChunkMapStore();

    /** No data for the chunk. */
    public static final byte NONE = -1;
    /** Chunk is loaded but not ticking. */
    public static final byte LOADED = 0;
    /** Chunk is ticking. */
    public static final byte TICKING = 1;

    private final Map<Identifier, DimensionData> dimensions = new HashMap<>();
    private boolean active;
    private long revision;

    private ChunkMapStore() {
    }

    private static final class DimensionData {
        final Map<Long, Byte> chunks = new HashMap<>();
        final Map<Long, Integer> regionRevisions = new HashMap<>();
        int hash;
    }

    /** Whether the server currently reports chunk data to this client. */
    public boolean active() {
        return active;
    }

    /** Monotonic counter bumped on every change; used to trigger overlay refreshes. */
    public long revision() {
        return revision;
    }

    public void clear() {
        dimensions.clear();
        active = false;
        revision++;
    }

    public void apply(ChunkMapPayload payload) {
        if (!payload.enabled()) {
            clear();
            return;
        }
        active = true;
        revision++;

        DimensionData dimension = new DimensionData();
        fill(dimension, payload.loaded(), LOADED);
        fill(dimension, payload.ticking(), TICKING);

        int hash = dimension.chunks.hashCode();
        DimensionData previous = dimensions.get(payload.dimension());
        if (previous != null && previous.hash == hash) {
            return;
        }

        dimension.hash = hash;
        int revisionValue = (int) (revision & 0x7FFFFFFFL);
        for (long packed : dimension.chunks.keySet()) {
            dimension.regionRevisions.put(regionKey(packed), revisionValue);
        }
        dimensions.put(payload.dimension(), dimension);
    }

    private static void fill(DimensionData dimension, long[] values, byte state) {
        for (long packed : values) {
            dimension.chunks.put(packed, state);
        }
    }

    public byte state(Identifier dimension, int chunkX, int chunkZ) {
        DimensionData data = dimensions.get(dimension);
        if (data == null) {
            return NONE;
        }
        Byte value = data.chunks.get(packed(chunkX, chunkZ));
        return value == null ? NONE : value;
    }

    public boolean regionHasHighlights(Identifier dimension, int regionX, int regionZ) {
        DimensionData data = dimensions.get(dimension);
        if (data == null) {
            return false;
        }
        return data.regionRevisions.containsKey(regionKey(regionX, regionZ));
    }

    public int regionRevision(Identifier dimension, int regionX, int regionZ) {
        DimensionData data = dimensions.get(dimension);
        if (data == null) {
            return 0;
        }
        return data.regionRevisions.getOrDefault(regionKey(regionX, regionZ), 0);
    }

    private static long packed(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static long regionKey(int regionX, int regionZ) {
        return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
    }

    private static long regionKey(long packed) {
        int chunkX = (int) (packed >> 32);
        int chunkZ = (int) packed;
        return regionKey(chunkX >> 5, chunkZ >> 5);
    }
}
