package io.canvasmc.canvas.chunkmap;

import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnigmaBufferOverlapTestSuite {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static long key(final int x, final int z) {
        return CoordinateUtils.getChunkKey(x, z);
    }

    @Test
    void serverAndClientUseTheSamePackedChunkCoordinates() {
        assertTrue(key(-17, 29) == ChunkPos.pack(-17, 29));
    }

    @Test
    void overlapRequiresAnActuallySharedUnloadedBufferChunk() {
        final long[] loadedWithOneChunkGap = {key(0, 0), key(3, 0)};
        assertFalse(EnigmaChunkMapService.bufferZonesOverlap(
                loadedWithOneChunkGap, new long[]{key(0, 0)}, new long[]{key(3, 0)}, 1));

        final long[] loadedWithSharedBufferCell = {key(0, 0), key(2, 0)};
        assertTrue(EnigmaChunkMapService.bufferZonesOverlap(
                loadedWithSharedBufferCell, new long[]{key(0, 0)}, new long[]{key(2, 0)}, 1));
    }

    @Test
    void loadedChunksAreRemovedFromBothBufferZones() {
        final long[] loaded = {
                key(0, 0), key(2, 0),
                key(1, -1), key(1, 0), key(1, 1)
        };
        assertFalse(EnigmaChunkMapService.bufferZonesOverlap(
                loaded, new long[]{key(0, 0)}, new long[]{key(2, 0)}, 1));
    }

    @Test
    void tenChunkContentSeparationCanOverlapOnlyWhenTheConfiguredZonesReachIt() {
        final long[] loaded = {key(0, 0), key(10, 0)};
        assertTrue(EnigmaChunkMapService.bufferZonesOverlap(
                loaded, new long[]{key(0, 0)}, new long[]{key(10, 0)}, 5));
        assertFalse(EnigmaChunkMapService.bufferZonesOverlap(
                loaded, new long[]{key(0, 0)}, new long[]{key(10, 0)}, 4));
    }
}
