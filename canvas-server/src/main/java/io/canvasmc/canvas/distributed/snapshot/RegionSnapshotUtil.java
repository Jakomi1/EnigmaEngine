package io.canvasmc.canvas.distributed.snapshot;

import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.RegionizedTaskQueue;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Captures and restores the real state of a ticked region.
 *
 * <h2>Threading</h2>
 * Chunk and entity state may only be read on the tick thread that owns the region. Every capture
 * step is therefore dispatched through {@link RegionizedTaskQueue#queueOrExecuteTickTask}, which
 * runs inline when the caller already owns the region and queues otherwise. This is why the capture
 * API is asynchronous: a migration cannot synchronously read a region that is being ticked.
 *
 * <h2>Format</h2>
 * Chunks are captured with the vanilla chunk serializer, i.e. exactly the representation the server
 * writes to disk. A migrated chunk is therefore byte compatible with one generated locally, which is
 * what allows the Compute Host to treat it as an ordinary chunk.
 */
public final class RegionSnapshotUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger("EnigmaSnapshotUtil");

    private RegionSnapshotUtil() {
    }

    /**
     * Captures a full snapshot of {@code region}. The returned future completes once every chunk in
     * the region has been serialized.
     *
     * @param snapshotRegionId the id the snapshot is tagged with; the {@code region}'s own id is
     *     host local, so callers pass the distributed region id (the one both hosts agree on) here
     */
    public static CompletableFuture<RegionSnapshot> capture(
            final long snapshotRegionId,
            final ThreadedRegionizer.ThreadedRegion<?, ?> region,
            final RegionSnapshot.OwnershipMetadata ownership
    ) {
        final ServerLevel world = region.regioniser.world;
        final long[] packedChunks = region.getOwnedPackedChunkPositions();

        // One packed long per chunk. The packing convention is the server wide one
        // (CoordinateUtils.getChunkKey): X in the low 32 bits, Z in the high 32 bits.
        final int chunkTotal = packedChunks.length;
        final int[] chunkX = new int[chunkTotal];
        final int[] chunkZ = new int[chunkTotal];
        for (int i = 0; i < chunkTotal; i++) {
            final long packed = packedChunks[i];
            chunkX[i] = (int) packed;
            chunkZ[i] = (int) (packed >>> 32);
        }

        if (chunkTotal == 0) {
            LOGGER.warn("Region {} owns no chunks; capturing metadata only", region.id);
            return CompletableFuture.completedFuture(new RegionSnapshot(
                    worldId(world), snapshotRegionId, ownership.generation, world.getGameTime(),
                    ownership, RegionSnapshot.SchedulerSnapshot.empty(), Map.of()));
        }

        final CompletableFuture<RegionSnapshot> result = new CompletableFuture<>();
        final List<RegionSnapshot.ChunkSnapshot> captured = new ArrayList<>(chunkTotal);
        final AtomicInteger remaining = new AtomicInteger(chunkTotal);

        for (int i = 0; i < chunkTotal; i++) {
            final int cx = chunkX[i];
            final int cz = chunkZ[i];

            RegionizedServer.getInstance().taskQueue.queueOrExecuteTickTask(world, cx, cz, () -> {
                try {
                    final LevelChunk chunk = world.getChunkIfLoaded(cx, cz);
                    if (chunk != null) {
                        synchronized (captured) {
                            captured.add(captureChunk(world, chunk));
                        }
                    } else {
                        LOGGER.debug("Skipping chunk ({}, {}) in region {}: not a loaded chunk", cx, cz, region.id);
                    }
                } catch (final Throwable t) {
                    LOGGER.error("Failed to capture chunk ({}, {}) of region {}", cx, cz, region.id, t);
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        finish(world, snapshotRegionId, region, ownership, captured, result);
                    }
                }
            });
        }

        return result;
    }

    private static void finish(
            final ServerLevel world,
            final long snapshotRegionId,
            final ThreadedRegionizer.ThreadedRegion<?, ?> region,
            final RegionSnapshot.OwnershipMetadata ownership,
            final List<RegionSnapshot.ChunkSnapshot> captured,
            final CompletableFuture<RegionSnapshot> result
    ) {
        try {
            final RegionSnapshot snapshot = new RegionSnapshot(
                    worldId(world),
                    snapshotRegionId,
                    ownership.generation,
                    world.getGameTime(),
                    ownership,
                    RegionSnapshot.SchedulerSnapshot.empty(),
                    Map.of()
            );
            final List<RegionSnapshot.ChunkSnapshot> chunks;
            synchronized (captured) {
                chunks = new ArrayList<>(captured);
            }
            for (final RegionSnapshot.ChunkSnapshot chunk : chunks) {
                snapshot.addChunk(chunk);
            }
            // Fails fast if the encode/decode round trip is not self consistent.
            snapshot.selfCheck();
            LOGGER.info("Captured snapshot for region {}: {} chunk(s)", region.id, snapshot.getActualChunkCount());
            result.complete(snapshot);
        } catch (final Throwable t) {
            result.completeExceptionally(t);
        }
    }

    /**
     * Serializes one chunk with the vanilla chunk serializer.
     */
    public static RegionSnapshot.ChunkSnapshot captureChunk(final ServerLevel world, final LevelChunk chunk) {
        final CompoundTag tag = SerializableChunkData.copyOf(world, chunk).write();

        final List<RegionSnapshot.BlockEntitySnapshot> blockEntities = new ArrayList<>();
        for (final var blockEntity : chunk.getBlockEntities().values()) {
            blockEntities.add(new RegionSnapshot.BlockEntitySnapshot(
                    blockEntity.getBlockPos().getX(),
                    blockEntity.getBlockPos().getY(),
                    blockEntity.getBlockPos().getZ(),
                    blockEntity.getType().builtInRegistryHolder().key().identifier().toString(),
                    toBytes(blockEntity.saveWithFullMetadata(world.registryAccess()))
            ));
        }

        return new RegionSnapshot.ChunkSnapshot(
                chunk.getPos().x(),
                chunk.getPos().z(),
                toBytes(tag),
                blockEntities,
                new RegionSnapshot.ScheduledTicksSnapshot(null, null)
        );
    }

    /**
     * Installs the snapshot into {@code world} so the chunks become real world chunks.
     *
     * <p>The snapshot payload is a vanilla serialized chunk, which is byte for byte the format the
     * server already persists. Rather than hand-building chunks, the payload is therefore written
     * through the level's own chunk storage ({@code SimpleRegionStorage.write}) before the chunks are
     * handed to the regionizer. Once the chunks are registered, the ordinary chunk pipeline loads
     * exactly the state the World Host captured, including block data, block entities, light and
     * scheduled ticks.</p>
     *
     * <p>The returned future completes when every chunk has been written. Callers must only
     * register the chunks with the regionizer after it completes, otherwise the loader could race a
     * partially written chunk.</p>
     */
    public static CompletableFuture<Void> restore(
            final ServerLevel world,
            final RegionSnapshot snapshot
    ) {
        final List<RegionSnapshot.ChunkSnapshot> chunks = snapshot.getChunks();
        if (chunks.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        for (final RegionSnapshot.ChunkSnapshot chunkSnapshot : chunks) {
            final CompoundTag tag = readTag(chunkSnapshot.blockData);
            if (tag == null) {
                LOGGER.error("Chunk ({}, {}) of region {} has an unreadable payload; aborting restore",
                        chunkSnapshot.chunkX, chunkSnapshot.chunkZ, snapshot.regionId);
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Unreadable chunk payload for region " + snapshot.regionId));
            }

            final ChunkPos pos = new ChunkPos(chunkSnapshot.chunkX, chunkSnapshot.chunkZ);

            // A cached chunk would keep the pre-migration state in memory even after the storage
            // write, so refuse rather than silently ticking stale data.
            if (world.getChunkSource().getChunkNow(chunkSnapshot.chunkX, chunkSnapshot.chunkZ) != null) {
                LOGGER.error("Chunk ({}, {}) is already loaded on this host; cannot install the migrated state",
                        chunkSnapshot.chunkX, chunkSnapshot.chunkZ);
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Chunk " + chunkSnapshot.chunkX + "," + chunkSnapshot.chunkZ
                                + " is already loaded; refusing to install migrated state"));
            }

            // Moonrise's chunk map schedules the save asynchronously and intentionally returns null,
            // so the migration must not await it; the flush below makes the writes durable.
            world.getChunkSource().chunkMap.write(pos, tag);
        }

        // Wait until the scheduled saves reached the region files: the region definition that the
        // World Host sent right behind the snapshot must find the migrated state on disk when the
        // regionizer loads these chunks during activation.
        return world.getChunkSource().chunkMap.synchronize(true)
                .thenRun(() -> LOGGER.info("Installed {} migrated chunk(s) of region {} into {}",
                        chunks.size(), snapshot.regionId, world.dimension().identifier()));
    }

    private static long worldId(final ServerLevel world) {
        return world.dimension().identifier().hashCode();
    }

    /**
     * Compressed NBT bytes. {@link NbtIo} already applies its own compression, so no extra stream
     * layer is wrapped around it.
     */
    public static byte[] toBytes(final CompoundTag tag) {
        try {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                NbtIo.writeCompressed(tag, out);
            }
            return bytes.toByteArray();
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to encode NBT", e);
        }
    }

    public static CompoundTag readTag(final byte[] data) {
        try {
            return NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.unlimitedHeap());
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to decode NBT", e);
        }
    }
}
