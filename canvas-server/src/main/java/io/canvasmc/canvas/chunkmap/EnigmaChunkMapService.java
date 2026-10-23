package io.canvasmc.canvas.chunkmap;

import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import io.canvasmc.canvas.region.RegionTickData;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pushes the chunk state of this server to subscribed EnigmaClient mods.
 *
 * <p>Protocol v8, per dimension: the chunks the server has loaded (server wide), the exact
 * configured merge-buffer radius in chunks, grouped ownership, per-region debug statistics
 * (entity count, chunk count, MSPT, TPS), and the position of every online player in that
 * dimension.</p>
 *
 * <p>The region surface is exactly the chunks the region has loaded (the same set the Folia
 * {@code /tps} command counts), <em>not</em> the regionizer's holder-owned chunks. A region owns a
 * holder for every chunk it is generating, which is far more than the chunks it has actually
 * loaded; drawing those made the map show several times the real region size. Each loaded chunk is
 * resolved to its region per chunk. A chunk whose regionizer section is momentarily not registered
 * (only possible during a region split/merge handover) is still sent as a loaded chunk, but without
 * a region: the client draws it and reports it as "keine Region", so it never claims an ownership
 * that the server cannot prove.</p>
 *
 * <p>The global tick hook only collects the loaded chunk keys and decides <em>who</em> gets an
 * update. Entity counts are region thread owned, so each region's count is gathered on its own
 * region thread through a fire-and-forget tick task and served from a short lived cache
 * afterwards.</p>
 */
public final class EnigmaChunkMapService {

    private static final Logger LOGGER = LoggerFactory.getLogger("EnigmaChunkMap");

    public static final Identifier CHUNK_CHANNEL = Identifier.fromNamespaceAndPath("enigmaclient", "chunkmap");
    public static final Identifier SUBSCRIBE_CHANNEL = Identifier.fromNamespaceAndPath("enigmaclient", "chunkmap_subscribe");

    public static final int PROTOCOL_VERSION = 8;

    private static final long PRIME = 0x9E3779B97F4A7C15L;

    /** How often a snapshot is pushed, in global ticks (1 second). */
    private static final long UPDATE_INTERVAL_TICKS = 20L;
    /** How often a region's entity count is refreshed, in global ticks (1 second). */
    private static final long ENTITY_SAMPLE_INTERVAL_TICKS = 20L;
    /** Cached entity samples older than this are dropped, in global ticks (30 seconds). */
    private static final long ENTITY_SAMPLE_MAX_AGE_TICKS = 600L;

    private static final EnigmaChunkMapService INSTANCE = new EnigmaChunkMapService();

    private final Set<UUID> subscribed = ConcurrentHashMap.newKeySet();
    /** Per player content hash of the last snapshot actually sent, used to skip unchanged pushes. */
    private final Map<UUID, Long> lastSent = new ConcurrentHashMap<>();
    /** Per player wall clock time of the last snapshot actually sent, for the clients command. */
    private final Map<UUID, Long> lastPushedAt = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    /**
     * Entity count per (dimension, region id), sampled on the owning region thread and reused for
     * {@link #ENTITY_SAMPLE_INTERVAL_TICKS} global ticks. A {@code -1} means "not sampled yet".
     */
    private final Map<Identifier, Map<Long, EntitySample>> entityCounts = new ConcurrentHashMap<>();

    private volatile boolean enabled = true;
    private volatile boolean settingsLoaded;
    private long tickCounter;
    private volatile boolean forceResend;

    private EnigmaChunkMapService() {
    }

    public static EnigmaChunkMapService getInstance() {
        return INSTANCE;
    }

    /**
     * Global tick hook, invoked once per server tick from {@code RegionizedServer.globalTick}. Any
     * failure is contained here so it can never escape into the global tick loop.
     */
    public static void globalTick() {
        try {
            INSTANCE.tick();
        } catch (final Throwable thrown) {
            LOGGER.error("Uncaught failure while ticking the chunk map service", thrown);
        }
    }

    /**
     * Handles a {@code enigmaclient:chunkmap_subscribe} payload received from a client, running on
     * the receiving player's region thread.
     */
    public static void handleSubscribe(final ServerCommonPacketListenerImpl listener, final byte[] data) {
        if (!(listener instanceof final ServerGamePacketListenerImpl gameListener)) {
            return;
        }
        final ServerPlayer player = gameListener.player;
        if (player == null) {
            return;
        }

        final UUID uuid = player.getUUID();
        if (data.length > 0 && data[0] != 0) {
            final boolean fresh = INSTANCE.subscribed.add(uuid);
            if (fresh) {
                INSTANCE.lastSent.remove(uuid);
                INSTANCE.forceResend = true;
            }
        } else {
            INSTANCE.subscribed.remove(uuid);
            INSTANCE.lastSent.remove(uuid);
            INSTANCE.lastPushedAt.remove(uuid);
        }
    }

    public boolean enabled() {
        return this.enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
        saveSettings();
    }

    /** Re-reads {@code config/enigma-chunkmap.yml} and applies it to the live service. */
    public void reload() {
        final EnigmaChunkMapSettings.Snapshot settings = EnigmaChunkMapSettings.load();
        this.enabled = settings.enabled();
        this.settingsLoaded = true;
    }

    private void saveSettings() {
        if (!this.settingsLoaded) {
            return;
        }
        EnigmaChunkMapSettings.save(new EnigmaChunkMapSettings.Snapshot(this.enabled));
    }

    public int subscriberCount() {
        return this.subscribed.size();
    }

    /** The uuid of every currently subscribed client. */
    public Set<UUID> subscribers() {
        return Collections.unmodifiableSet(this.subscribed);
    }

    /** Wall clock time of the last snapshot pushed to the given client, or {@code 0} when none. */
    public long lastPushedAt(final UUID uuid) {
        return this.lastPushedAt.getOrDefault(uuid, 0L);
    }

    private void tick() {
        if (!this.settingsLoaded) {
            reload();
        }

        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }

        this.tickCounter++;
        this.subscribed.removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);

        final boolean forced = this.forceResend;
        this.forceResend = false;

        if (!this.enabled) {
            for (final UUID uuid : this.subscribed) {
                if (this.lastSent.remove(uuid) != null) {
                    pushDisabled(server.getPlayerList().getPlayer(uuid));
                }
            }
            return;
        }

        if (this.tickCounter % UPDATE_INTERVAL_TICKS != 0L) {
            return;
        }

        if (this.subscribed.isEmpty()) {
            return;
        }

        final Map<ServerLevel, LevelSnapshot> perLevel = new ConcurrentHashMap<>();
        for (final UUID uuid : this.subscribed) {
            final ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) {
                continue;
            }
            final ServerLevel level = player.level();
            final LevelSnapshot snapshot = perLevel.computeIfAbsent(level, EnigmaChunkMapService::collectLevel);
            pushToPlayer(server, player, level, snapshot, forced);
        }
    }

    /**
     * Collects, on the global tick thread, the loaded chunks of {@code level} grouped by the region
     * that owns them, together with the debug statistics (including the entity count sampled on
     * each region's own thread).
     *
     * <p>The chunk set is the same one the Folia {@code /tps} command counts (the chunks the server
     * has loaded), not the regionizer's holder-owned surface. Each loaded chunk is mapped to its
     * region through {@code getRegionAtUnsynchronised}. That lookup returns {@code null} only while
     * the chunk's section is not registered with the regionizer (a split/merge handover); such a
     * chunk is still reported as loaded, just without a region, which the client shows as
     * "keine Region" instead of guessing an owner.</p>
     */
    private static LevelSnapshot collectLevel(final ServerLevel level) {
        final ChunkTaskScheduler scheduler = ((ChunkSystemServerLevel) level).moonrise$getChunkTaskScheduler();
        final List<NewChunkHolder> allHolders = scheduler.chunkHolderManager.getChunkHolders();
        long[] chunks = new long[allHolders.size()];
        int count = 0;
        for (final NewChunkHolder holder : allHolders) {
            if (!holder.isFullChunkReady()) {
                continue;
            }
            chunks[count++] = CoordinateUtils.getChunkKey(holder.chunkX, holder.chunkZ);
        }
        chunks = Arrays.copyOf(chunks, count);
        Arrays.sort(chunks);

        final Long2ObjectOpenHashMap<LongArrayList> chunksByRegion = new Long2ObjectOpenHashMap<>();
        final Long2ObjectOpenHashMap<long[]> regionStats = new Long2ObjectOpenHashMap<>();
        final Long2LongOpenHashMap anchorByRegion = new Long2LongOpenHashMap();
        for (final long packed : chunks) {
            final int chunkX = CoordinateUtils.getChunkX(packed);
            final int chunkZ = CoordinateUtils.getChunkZ(packed);
            final var region = level.regioniser.getRegionAtUnsynchronised(chunkX, chunkZ);
            if (region == null) {
                continue;
            }
            final long regionId = region.getId();
            LongArrayList owned = chunksByRegion.get(regionId);
            if (owned == null) {
                owned = new LongArrayList();
                chunksByRegion.put(regionId, owned);
                final RegionTickData data = region.getData();
                final double mspt = Math.max(0.0D, data.getMSPT(RegionTickData.Frame._5_SECONDS));
                final double tps = data.getTPS(RegionTickData.Frame._5_SECONDS);
                regionStats.put(regionId, new long[]{Math.round(mspt * 1000.0D), Math.round(tps * 1000.0D)});
                // The anchor is a loaded chunk, so it already has a holder: scheduling the entity
                // refresh on it cannot force-load anything.
                anchorByRegion.put(regionId, packed);
            }
            owned.add(packed);
        }

        final long[] regionIds = chunksByRegion.keySet().toLongArray();
        Arrays.sort(regionIds);

        final Long2ObjectOpenHashMap<long[]> regionChunks = new Long2ObjectOpenHashMap<>();
        for (final long regionId : regionIds) {
            final long[] owned = chunksByRegion.get(regionId).toLongArray();
            Arrays.sort(owned);
            regionChunks.put(regionId, owned);
        }

        // Schedules the fire-and-forget entity count refreshes. This path can create chunk holders
        // and takes the regionizer write lock, so it must never run while the regionizer read lock
        // is held (the StampedLock is not reentrant and the global tick thread would deadlock).
        final Long2LongOpenHashMap entityMap = new Long2LongOpenHashMap();
        final long sampleNow = INSTANCE.tickCounter;
        for (final Long2LongMap.Entry entry : anchorByRegion.long2LongEntrySet()) {
            entityMap.put(entry.getLongKey(),
                    entityCountFor(level, entry.getLongKey(), entry.getLongValue(), sampleNow));
        }
        entityMap.defaultReturnValue(-1L);
        pruneStaleEntitySamples(level);

        return new LevelSnapshot(chunks, regionIds, regionChunks, regionStats, entityMap);
    }

    /**
     * Returns the cached entity count of {@code regionId}, scheduling a refresh on the region that
     * owns {@code packedAnchorChunk} when the sample is stale. The anchor must be a chunk the
     * region already owns (it has a holder), so scheduling never force-loads. The refresh is
     * fire-and-forget; this method never blocks on the region thread. {@code -1} means "no sample
     * yet".
     */
    private static long entityCountFor(final ServerLevel level,
                                       final long regionId,
                                       final long packedAnchorChunk,
                                       final long now) {
        final Map<Long, EntitySample> cache = INSTANCE.entityCounts
                .computeIfAbsent(level.dimension().identifier(), key -> new ConcurrentHashMap<>());
        final EntitySample sample = cache.get(regionId);
        if (sample != null && now - sample.tick() < ENTITY_SAMPLE_INTERVAL_TICKS) {
            return sample.count();
        }

        final int chunkX = CoordinateUtils.getChunkX(packedAnchorChunk);
        final int chunkZ = CoordinateUtils.getChunkZ(packedAnchorChunk);
        try {
            RegionizedServer.getInstance().taskQueue.queueOrExecuteTickTask(
                    level, chunkX, chunkZ, () -> {
                        final RegionizedWorldData worldData = level.getCurrentWorldData();
                        if (worldData != null) {
                            cache.put(regionId, new EntitySample(worldData.getEntityCount(), now));
                        }
                    },
                    Priority.NORMAL);
        } catch (final RuntimeException thrown) {
            // The region thread is gone (merge/split/unload); the stale sample simply stays.
            return sample == null ? -1L : sample.count();
        }
        return sample == null ? -1L : sample.count();
    }

    private static void pruneStaleEntitySamples(final ServerLevel level) {
        final Map<Long, EntitySample> cache = INSTANCE.entityCounts.get(level.dimension().identifier());
        if (cache == null) {
            return;
        }
        final long now = INSTANCE.tickCounter;
        cache.entrySet().removeIf(entry -> now - entry.getValue().tick() > ENTITY_SAMPLE_MAX_AGE_TICKS);
    }

    /**
     * Decides, on the player's own region thread, whether the player's snapshot changed and sends
     * it when it did.
     */
    private void pushToPlayer(final MinecraftServer server, final ServerPlayer player, final ServerLevel level,
                              final LevelSnapshot snapshot, final boolean forced) {
        final UUID uuid = player.getUUID();
        player.getBukkitEntity().taskScheduler.scheduleOrExecute((final ServerPlayer online) -> {
            if (online.hasDisconnected() || online.level() != level) {
                return;
            }

            final long contentHash = contentHash(server, level, snapshot);
            if (!forced && contentHash == this.lastSent.getOrDefault(uuid, Long.MIN_VALUE)) {
                return;
            }

            final FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {
                writeSnapshot(server, buf, level, online, snapshot, true);
                send(online, buf);
            } finally {
                buf.release();
            }
            this.lastSent.put(uuid, contentHash);
            this.lastPushedAt.put(uuid, System.currentTimeMillis());
        });
    }

    private void pushDisabled(final ServerPlayer player) {
        if (player == null) {
            return;
        }
        final MinecraftServer server = MinecraftServer.getServer();
        player.getBukkitEntity().taskScheduler.scheduleOrExecute((final ServerPlayer online) -> {
            if (online.hasDisconnected()) {
                return;
            }
            final FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {
                writeSnapshot(server, buf, online.level(), online, LevelSnapshot.empty(), false);
                send(online, buf);
            } finally {
                buf.release();
            }
        });
    }

    private static void send(final ServerPlayer player, final FriendlyByteBuf buf) {
        final byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        player.connection.send(new ClientboundCustomPayloadPacket(new DiscardedPayload(CHUNK_CHANNEL, bytes)));
    }

    private void writeSnapshot(final MinecraftServer server, final FriendlyByteBuf buf, final ServerLevel level,
                               final ServerPlayer online, final LevelSnapshot snapshot, final boolean enabled) {
        buf.writeByte(PROTOCOL_VERSION);
        buf.writeUtf(level.dimension().identifier().toString());
        buf.writeLong(this.sequence.incrementAndGet());
        buf.writeBoolean(enabled);
        // This must be the same configured radius used by the merge gate, rather than the
        // currently materialised shell width (which can temporarily be only one section).
        buf.writeVarInt(enabled ? Math.max(1,
                io.papermc.paper.configuration.GlobalConfiguration.get().threadedRegions.bufferRadius) : 0);

        final long[] ticking = enabled ? snapshot.ticking() : new long[0];
        final long[] regionIds = enabled ? snapshot.regionIds() : new long[0];
        writeLongs(buf, ticking);
        writeLongs(buf, regionIds);

        final int[] regionChunkCounts = new int[regionIds.length];
        final LongArrayList flattenedChunks = new LongArrayList();
        if (enabled) {
            for (int index = 0; index < regionIds.length; index++) {
                final long[] owned = snapshot.regionChunks().get(regionIds[index]);
                if (owned == null) {
                    continue;
                }
                regionChunkCounts[index] = owned.length;
                flattenedChunks.addAll(LongArrayList.wrap(owned));
            }
        }
        writeInts(buf, regionChunkCounts);
        writeLongs(buf, flattenedChunks.toLongArray());

        final long[] entities = new long[regionIds.length];
        final long[] mspt = new long[regionIds.length];
        final long[] tps = new long[regionIds.length];
        if (enabled) {
            for (int index = 0; index < regionIds.length; index++) {
                entities[index] = snapshot.regionEntities().getOrDefault(regionIds[index], -1L);
                final long[] stats = snapshot.regionStats().get(regionIds[index]);
                if (stats != null) {
                    mspt[index] = stats[0];
                    tps[index] = stats[1];
                }
            }
        }
        writeLongs(buf, entities);
        writeLongs(buf, mspt);
        writeLongs(buf, tps);

        writePlayers(server, buf, level, online, enabled);
    }

    private static void writePlayers(final MinecraftServer server, final FriendlyByteBuf buf, final ServerLevel level,
                                     final ServerPlayer online, final boolean enabled) {
        final List<ServerPlayer> all = server.getPlayerList().getPlayers();
        int count = 0;
        if (enabled) {
            for (final ServerPlayer other : all) {
                if (other != null && other.level() == level) {
                    count++;
                }
            }
        }
        buf.writeVarInt(count);
        if (!enabled) {
            return;
        }
        for (final ServerPlayer other : all) {
            if (other == null || other.level() != level) {
                continue;
            }
            buf.writeUUID(other.getUUID());
            buf.writeUtf(other.getGameProfile().name());
            buf.writeVarInt(other.getBlockX());
            buf.writeVarInt(other.getBlockY());
            buf.writeVarInt(other.getBlockZ());
        }
    }

    /**
     * Stable fingerprint of everything that is drawn for a player. A snapshot is only skipped when
     * this is byte for byte identical to the last one, so unloaded chunks, vanished regions, region
     * merge/split and moved players all force a fresh push.
     */
    private static long contentHash(final MinecraftServer server, final ServerLevel level,
                                    final LevelSnapshot snapshot) {
        long hash = level.dimension().identifier().hashCode();
        hash = hash * PRIME + Arrays.hashCode(snapshot.ticking());
        hash = hash * PRIME + Arrays.hashCode(snapshot.regionIds());
        hash = hash * PRIME + hashRegions(snapshot.regionChunks());
        hash = hash * PRIME + hashRegions(snapshot.regionStats());
        hash = hash * PRIME + snapshot.regionEntities().hashCode();
        hash = hash * PRIME + playersHash(server, level);
        return hash == Long.MIN_VALUE ? 1L : hash;
    }

    /**
     * Content hash of a region table. {@code Long2ObjectOpenHashMap.hashCode} hashes the
     * {@code long[]} values by identity, which would change on every snapshot, so the values are
     * hashed by content here.
     */
    private static long hashRegions(final Long2ObjectOpenHashMap<long[]> values) {
        long hash = values.size();
        for (final Long2ObjectMap.Entry<long[]> entry : values.long2ObjectEntrySet()) {
            hash = hash * PRIME + entry.getLongKey();
            hash = hash * PRIME + Arrays.hashCode(entry.getValue());
        }
        return hash;
    }

    private static long playersHash(final MinecraftServer server, final ServerLevel level) {
        long hash = PRIME;
        if (server == null) {
            return hash;
        }
        for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player == null || player.level() != level) {
                continue;
            }
            hash = hash * PRIME + player.getUUID().hashCode();
            hash = hash * PRIME + player.getBlockX();
            hash = hash * PRIME + player.getBlockY();
            hash = hash * PRIME + player.getBlockZ();
        }
        return hash;
    }

    private static void writeLongs(final FriendlyByteBuf buf, final long[] values) {
        buf.writeVarInt(values.length);
        for (final long value : values) {
            buf.writeLong(value);
        }
    }

    private static void writeInts(final FriendlyByteBuf buf, final int[] values) {
        buf.writeVarInt(values.length);
        for (final int value : values) {
            buf.writeVarInt(value);
        }
    }

    /** An entity count sampled on the owning region thread, together with its sampling tick. */
    private record EntitySample(long count, long tick) {
    }

    private record LevelSnapshot(long[] ticking, long[] regionIds,
                                 Long2ObjectOpenHashMap<long[]> regionChunks,
                                 Long2ObjectOpenHashMap<long[]> regionStats,
                                 Long2LongOpenHashMap regionEntities) {

        static LevelSnapshot empty() {
            return new LevelSnapshot(new long[0], new long[0],
                    new Long2ObjectOpenHashMap<>(), new Long2ObjectOpenHashMap<>(), new Long2LongOpenHashMap());
        }
    }
}
