package io.enigmaclient.chunkmap;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;

import java.util.Arrays;
import java.util.UUID;

/**
 * Client side snapshot of the chunk map the server pushes.
 *
 * <p>The payload receiver runs on the game thread, but Xaero queries this store from its own map
 * thread. Everything read from another thread therefore goes through a single immutable
 * {@link Snapshot} behind a volatile field: a reader either sees the whole old or the whole new
 * state and never a partially built one.</p>
 *
 * <p>Region ownership is driven by the <em>loaded</em> chunks the server sends per region (the same
 * set Folia's {@code /tps} command counts): a flat chunk to region id table maps every loaded chunk
 * to its region, so the hover tooltip, the region debug stats and the reported chunk count all agree
 * and always cover the real region surface.</p>
 *
 * <p>Per chunk states, in the order they are decided:</p>
 * <ul>
 *   <li>{@link #TICKING} - the server has this chunk loaded. Drawn solid green.</li>
 *   <li>{@link #BUFFER} - inside the server's optional merge-buffer overlay.</li>
 *   <li>{@link #NONE} - nothing is drawn.</li>
 * </ul>
 */
public final class ChunkMapStore {

    private static final long[] NO_LONGS = new long[0];
    private static final PlayerMarker[] NO_MARKERS = new PlayerMarker[0];

    /**
     * Region id used when the server did not report a region for a chunk. Region ids start at zero,
     * so zero is a valid region and cannot be used as the "unknown" marker.
     */
    public static final long NO_REGION = -1L;

    private static final Snapshot EMPTY = new Snapshot(null, 0,
            NO_LONGS, NO_LONGS, noRegions(), new Long2LongOpenHashMap(),
            new Long2ObjectOpenHashMap<>(), NO_MARKERS);

    public static final ChunkMapStore INSTANCE = new ChunkMapStore();

    /** Nothing is drawn for this chunk. */
    public static final byte NONE = 0;
    /** The server has this chunk loaded. */
    public static final byte TICKING = 1;
    /** Inside the server's configured merge buffer, but not loaded itself. */
    public static final byte BUFFER = 2;

    /** Per region debug data sent by the server. */
    public record RegionStats(int entities, int chunks, int msptMilli, int tpsMilli) {
    }

    /** Snapshot of everything known about one chunk, see {@link #chunkInfo}. */
    public record ChunkInfo(byte state, long region, RegionStats stats) {

        /** Whether the server reported a region that owns this chunk. */
        public boolean hasRegion() {
            return this.region != NO_REGION;
        }
    }

    /** An online player's position, used to draw a marker on the map. */
    public record PlayerMarker(UUID id, String name, int x, int y, int z, Identifier dimension) {
    }

    private volatile Snapshot snapshot = EMPTY;
    private volatile boolean active;
    private volatile long revision;
    private long lastSequence;

    private ChunkMapStore() {
    }

    /** Whether the server currently reports chunk data to this client. */
    public boolean active() {
        return this.active;
    }

    /** Bumped on every change of the drawn content. */
    public long revision() {
        return this.revision;
    }

    /** The dimension the current snapshot describes, or {@code null} when there is none. */
    public Identifier dimension() {
        return this.snapshot.dimension;
    }

    /** Exact server-provided radius used for the optional buffer overlay. */
    public int bufferRadiusChunks() {
        return this.snapshot.bufferRadiusChunks;
    }

    public void clear() {
        this.snapshot = EMPTY;
        this.active = false;
        this.lastSequence = 0L;
        this.revision++;
    }

    /** Applies a server snapshot. Must be called on the game thread. */
    public void apply(final ChunkMapPayload payload) {
        if (payload.version() != ChunkMapPayload.PROTOCOL_VERSION || payload.dimension() == null) {
            return;
        }
        if (payload.sequence() <= this.lastSequence) {
            return;
        }
        this.lastSequence = payload.sequence();
        if (!payload.enabled()) {
            clear();
            return;
        }
        this.snapshot = build(payload);
        this.active = true;
        this.revision++;
    }

    /** Drawn state of a chunk: {@link #TICKING}, {@link #BUFFER} or {@link #NONE}. */
    public byte state(final Identifier dimension, final int chunkX, final int chunkZ) {
        final Snapshot current = this.snapshot;
        if (!dimension.equals(current.dimension)) {
            return NONE;
        }
        final long packed = ChunkPos.pack(chunkX, chunkZ);
        if (Arrays.binarySearch(current.ticking, packed) >= 0) {
            return TICKING;
        }
        return Arrays.binarySearch(current.buffer, packed) >= 0 ? BUFFER : NONE;
    }

    /**
     * Everything the map overlay knows about one chunk: its drawn state, the id of the region that
     * owns it (or {@link #NO_REGION} when the server did not report one) and that region's debug
     * stats. All values come from a single snapshot, so a tooltip can never mix two server pushes.
     * Reads only immutable state, so it is safe from Xaero's map thread.
     */
    public ChunkInfo chunkInfo(final Identifier dimension, final int chunkX, final int chunkZ) {
        final Snapshot current = this.snapshot;
        if (!dimension.equals(current.dimension)) {
            return new ChunkInfo(NONE, NO_REGION, null);
        }
        final long packed = ChunkPos.pack(chunkX, chunkZ);
        final long region = current.chunkToRegion.get(packed);
        final RegionStats stats = region == NO_REGION ? null : current.stats.get(region);
        final byte state = Arrays.binarySearch(current.ticking, packed) >= 0 ? TICKING
                : Arrays.binarySearch(current.buffer, packed) >= 0 ? BUFFER : NONE;
        return new ChunkInfo(state, region, stats);
    }

    /** Markers of every player the server reported for the current dimension. */
    public PlayerMarker[] markers() {
        return this.snapshot.markers;
    }

    /**
     * Stable content hash of everything this store draws inside the given Xaero region (512 blocks,
     * 32 chunks per axis).
     */
    public long regionContent(final Identifier dimension, final int regionX, final int regionZ) {
        final Snapshot current = this.snapshot;
        if (!dimension.equals(current.dimension)) {
            return 0L;
        }
        return current.regionContent.get(regionKey(regionX, regionZ));
    }

    /** Whether anything at all is drawn inside the given Xaero region. */
    public boolean regionHasHighlights(final Identifier dimension, final int regionX, final int regionZ) {
        return regionContent(dimension, regionX, regionZ) != 0L;
    }

    private static Snapshot build(final ChunkMapPayload payload) {
        final long[] ticking = payload.ticking();
        final long[] buffer = bufferChunks(ticking, payload.bufferRadiusChunks());
        final long[] regionIds = payload.regionIds();
        final int[] regionChunkCounts = payload.regionChunkCounts();
        final long[] regionChunks = payload.regionChunks();

        final Long2LongOpenHashMap chunkToRegion = noRegions();
        int cursor = 0;
        for (int index = 0; index < regionIds.length && cursor < regionChunks.length; index++) {
            final long regionId = regionIds[index];
            final int count = index < regionChunkCounts.length ? regionChunkCounts[index] : 0;
            for (int offset = 0; offset < count && cursor < regionChunks.length; offset++) {
                chunkToRegion.put(regionChunks[cursor++], regionId);
            }
        }

        final Long2LongOpenHashMap regionContent = new Long2LongOpenHashMap();
        for (final long packed : ticking) {
            regionContent.addTo(regionKeyOf(packed), mixChunk(packed, TICKING));
        }
        for (final long packed : buffer) {
            regionContent.addTo(regionKeyOf(packed), mixChunk(packed, BUFFER));
        }

        final Long2ObjectOpenHashMap<RegionStats> stats = new Long2ObjectOpenHashMap<>();
        final long[] entities = payload.regionEntities();
        final long[] mspt = payload.regionMsptMilli();
        final long[] tps = payload.regionTpsMilli();
        for (int i = 0; i < regionIds.length; i++) {
            stats.put(regionIds[i], new RegionStats(
                    (int) valueAt(entities, i), i < regionChunkCounts.length ? regionChunkCounts[i] : 0,
                    (int) valueAt(mspt, i), (int) valueAt(tps, i)));
        }

        final UUID[] ids = payload.playerIds();
        final String[] names = payload.playerNames();
        final int[] px = payload.playerX();
        final int[] py = payload.playerY();
        final int[] pz = payload.playerZ();
        final PlayerMarker[] markers = new PlayerMarker[ids.length];
        for (int i = 0; i < ids.length; i++) {
            markers[i] = new PlayerMarker(ids[i], names[i], px[i], py[i], pz[i], payload.dimension());
        }

        return new Snapshot(payload.dimension(), payload.bufferRadiusChunks(), ticking, buffer,
                chunkToRegion, regionContent, stats, markers);
    }

    /** Expands loaded chunks by the exact radius the server uses for its merge buffer. */
    private static long[] bufferChunks(final long[] ticking, final int radius) {
        if (radius <= 0 || ticking.length == 0) {
            return NO_LONGS;
        }
        final LongOpenHashSet loaded = new LongOpenHashSet(ticking);
        final LongOpenHashSet buffer = new LongOpenHashSet(ticking.length * (radius * 2 + 1));
        for (final long packed : ticking) {
            final int x = (int) packed;
            final int z = (int) (packed >>> 32);
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    final long candidate = ChunkPos.pack(x + dx, z + dz);
                    if (!loaded.contains(candidate)) {
                        buffer.add(candidate);
                    }
                }
            }
        }
        final long[] result = buffer.toLongArray();
        Arrays.sort(result);
        return result;
    }

    private static long valueAt(final long[] values, final int index) {
        return index < values.length ? values[index] : 0L;
    }

    /** Chunk to region table whose missing-key value is {@link #NO_REGION} instead of zero. */
    private static Long2LongOpenHashMap noRegions() {
        final Long2LongOpenHashMap map = new Long2LongOpenHashMap();
        map.defaultReturnValue(NO_REGION);
        return map;
    }

    private static long regionKeyOf(final long packedChunk) {
        final int chunkX = (int) packedChunk;
        final int chunkZ = (int) (packedChunk >>> 32);
        return regionKey(chunkX >> 5, chunkZ >> 5);
    }

    private static long regionKey(final int regionX, final int regionZ) {
        return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
    }

    private static long mixChunk(final long packedChunk, final long tag) {
        long hash = packedChunk * 0x9E3779B97F4A7C15L + tag;
        hash = (hash ^ (hash >>> 30)) * 0xBF58476D1CE4E5B9L;
        hash = (hash ^ (hash >>> 27)) * 0x94D049BB133111EBL;
        return hash ^ (hash >>> 31);
    }

    /**
     * Immutable snapshot of the drawn content. Handed to Xaero's map thread through a volatile field,
     * so every field is read-only after construction.
     */
    private static final class Snapshot {

        final Identifier dimension;
        final int bufferRadiusChunks;
        final long[] ticking;
        final long[] buffer;
        /** Packed chunk position to the id of the region that owns it, {@link #NO_REGION} when none. */
        final Long2LongOpenHashMap chunkToRegion;
        /** Xaero region key (32 chunk blocks) to the content hash of its loaded chunks. */
        final Long2LongOpenHashMap regionContent;
        final Long2ObjectOpenHashMap<RegionStats> stats;
        final PlayerMarker[] markers;

        private Snapshot(final Identifier dimension, final int bufferRadiusChunks,
                         final long[] ticking, final long[] buffer,
                         final Long2LongOpenHashMap chunkToRegion,
                         final Long2LongOpenHashMap regionContent,
                         final Long2ObjectOpenHashMap<RegionStats> stats,
                         final PlayerMarker[] markers) {
            this.dimension = dimension;
            this.bufferRadiusChunks = bufferRadiusChunks;
            this.ticking = ticking;
            this.buffer = buffer;
            this.chunkToRegion = chunkToRegion;
            this.regionContent = regionContent;
            this.stats = stats;
            this.markers = markers;
        }
    }
}
