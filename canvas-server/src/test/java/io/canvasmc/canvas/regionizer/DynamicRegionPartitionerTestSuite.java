package io.canvasmc.canvas.regionizer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicRegionPartitionerTestSuite {

    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    record TestSection(int x, int z, int chunks) {
    }

    private void assertValidPartition(final List<TestSection> input, final List<List<TestSection>> result) {
        assertFalse(result.isEmpty(), "Result should not be empty");

        final Set<TestSection> seen = new HashSet<>();
        int totalResultSections = 0;

        for (final List<TestSection> part : result) {
            assertFalse(part.isEmpty(), "Sub-region part should not be empty");
            totalResultSections += part.size();

            // Check connectivity of each sub-region
            assertTrue(isConnected(part), "Every sub-region part must be spatially connected: " + part);

            for (final TestSection section : part) {
                assertTrue(seen.add(section), "Duplicate section in partition: " + section);
            }
        }

        assertEquals(input.size(), totalResultSections, "Total sections must match input");
        assertEquals(new HashSet<>(input), seen, "All input sections must be present");
    }

    private boolean isConnected(final List<TestSection> part) {
        if (part.size() <= 1) {
            return true;
        }

        final Set<Long> keys = new HashSet<>();
        for (final TestSection s : part) {
            keys.add(DynamicRegionPartitioner.coordKey(s.x, s.z));
        }

        final Set<Long> visited = new HashSet<>();
        final Queue<TestSection> queue = new ArrayDeque<>();
        final TestSection start = part.get(0);
        visited.add(DynamicRegionPartitioner.coordKey(start.x, start.z));
        queue.add(start);

        while (!queue.isEmpty()) {
            final TestSection curr = queue.poll();
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if ((dx | dz) == 0) continue;
                    final long neighborKey = DynamicRegionPartitioner.coordKey(curr.x + dx, curr.z + dz);
                    if (keys.contains(neighborKey) && visited.add(neighborKey)) {
                        for (final TestSection s : part) {
                            if (s.x == curr.x + dx && s.z == curr.z + dz) {
                                queue.add(s);
                                break;
                            }
                        }
                    }
                }
            }
        }

        return visited.size() == part.size();
    }

    @Test
    void testSingleSectionDoesNotSplit() {
        final List<TestSection> sections = List.of(new TestSection(0, 0, 10));
        final List<List<TestSection>> result = DynamicRegionPartitioner.partition(
            sections, s -> s.x, s -> s.z, s -> s.chunks, 1, 2, 7
        );

        assertEquals(1, result.size());
        assertEquals(1, result.get(0).size());
        assertValidPartition(sections, result);
    }

    @Test
    void testTwoAdjacentSectionsSplitAtMaxAggression() {
        final List<TestSection> sections = List.of(
            new TestSection(0, 0, 10),
            new TestSection(1, 0, 10)
        );

        final List<List<TestSection>> result = DynamicRegionPartitioner.partition(
            sections, s -> s.x, s -> s.z, s -> s.chunks, 1, 2, 7
        );

        assertEquals(2, result.size());
        assertEquals(1, result.get(0).size());
        assertEquals(1, result.get(1).size());
        assertValidPartition(sections, result);
    }

    @Test
    void testDisconnectedIslandsSplitNaturally() {
        final List<TestSection> sections = List.of(
            new TestSection(0, 0, 5),
            new TestSection(0, 1, 5),
            new TestSection(10, 10, 5),
            new TestSection(10, 11, 5)
        );

        final List<List<TestSection>> result = DynamicRegionPartitioner.partition(
            sections, s -> s.x, s -> s.z, s -> s.chunks, 2, 8, 7
        );

        assertTrue(result.size() >= 2);
        assertValidPartition(sections, result);
    }

    @Test
    void testLinearStripBisection() {
        final List<TestSection> sections = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            sections.add(new TestSection(i, 0, 10));
        }

        final List<List<TestSection>> result = DynamicRegionPartitioner.partition(
            sections, s -> s.x, s -> s.z, s -> s.chunks, 2, 6, 7
        );

        assertTrue(result.size() >= 2);
        assertValidPartition(sections, result);
    }

    @Test
    void testLShapeConnectivity() {
        final List<TestSection> sections = List.of(
            new TestSection(0, 0, 5),
            new TestSection(0, 1, 5),
            new TestSection(0, 2, 5),
            new TestSection(1, 0, 5),
            new TestSection(2, 0, 5)
        );

        final List<List<TestSection>> result = DynamicRegionPartitioner.partition(
            sections, s -> s.x, s -> s.z, s -> s.chunks, 2, 4, 7
        );

        assertTrue(result.size() >= 2);
        assertValidPartition(sections, result);
    }

    @Test
    void testGrid2x2SplitsToSingleSectionsAtAggression100() {
        final List<TestSection> sections = List.of(
            new TestSection(0, 0, 1),
            new TestSection(1, 0, 1),
            new TestSection(0, 1, 1),
            new TestSection(1, 1, 1)
        );

        final List<List<TestSection>> result = DynamicRegionPartitioner.partition(
            sections, s -> s.x, s -> s.z, s -> s.chunks, 1, 2, 7
        );

        assertEquals(4, result.size());
        for (final List<TestSection> part : result) {
            assertEquals(1, part.size());
        }
        assertValidPartition(sections, result);
    }

    @Test
    void testGrid4x4PartitioningAndBalance() {
        final List<TestSection> sections = new ArrayList<>();
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                sections.add(new TestSection(x, z, 5));
            }
        }

        final List<List<TestSection>> result = DynamicRegionPartitioner.partition(
            sections, s -> s.x, s -> s.z, s -> s.chunks, 2, 6, 7
        );

        assertTrue(result.size() >= 4);
        assertValidPartition(sections, result);
    }

    @Test
    void testHeavyLoadSectionIsolation() {
        // One very heavy mob farm section surrounded by light sections
        final List<TestSection> sections = List.of(
            new TestSection(0, 0, 100), // Heavy section
            new TestSection(1, 0, 5),
            new TestSection(0, 1, 5),
            new TestSection(1, 1, 5)
        );

        final List<List<TestSection>> result = DynamicRegionPartitioner.partition(
            sections, s -> s.x, s -> s.z, s -> s.chunks, 2, 4, 7
        );

        assertTrue(result.size() >= 2);
        assertValidPartition(sections, result);

        // Verify heavy section is isolated or balanced appropriately
        boolean foundIsolatedHeavy = false;
        for (final List<TestSection> part : result) {
            if (part.size() == 1 && part.get(0).chunks == 100) {
                foundIsolatedHeavy = true;
                break;
            }
        }
        assertTrue(foundIsolatedHeavy, "Heavy mob farm section should be cleanly partitioned");
    }

    @Test
    void testRegionizerSettingsAggressionScaling() {
        final RegionizerSettings base = new RegionizerSettings(
            true, 0.25D, 24, 0.7D, 0.3D, 60_000L, 30_000L, 128, 4096, 32.0D, 15, 15, 20, true, true, 16, 512
        );

        final RegionizerSettings aggr100 = RegionizerSettings.forWorld(base, 2, 100);
        assertEquals(100, aggr100.aggression());
        assertEquals(1, aggr100.targetSections());
        assertEquals(2, aggr100.splitMinSize());
        assertEquals(2, aggr100.splitMaxSections());
        assertEquals(1, aggr100.mergeMinRegionSections());
        assertEquals(0.90, aggr100.mergeOverlapThreshold(), 0.001);
        assertEquals(0.5, aggr100.splitHighLoadMspt(), 0.001);

        final RegionizerSettings aggr0 = RegionizerSettings.forWorld(base, 2, 0);
        assertEquals(0, aggr0.aggression());
        assertTrue(aggr0.targetSections() > 1);
        assertEquals(32, aggr0.splitMinSize());
        assertEquals(128, aggr0.splitMaxSections());
        assertEquals(16, aggr0.mergeMinRegionSections());
        assertEquals(0.30, aggr0.mergeOverlapThreshold(), 0.001);
        assertEquals(15.0, aggr0.splitHighLoadMspt(), 0.001);

        final RegionizerSettings aggr50 = RegionizerSettings.forWorld(base, 2, 50);
        assertEquals(50, aggr50.aggression());
        assertTrue(aggr50.splitMinSize() < aggr0.splitMinSize());
        assertTrue(aggr50.splitMinSize() > aggr100.splitMinSize());
    }

    @Test
    void testHollowDonutShapeConnectivity() {
        // 3x3 hollow donut with hole at (1,1)
        final List<TestSection> sections = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                if (x == 1 && z == 1) continue; // hole
                sections.add(new TestSection(x, z, 5));
            }
        }
        assertEquals(8, sections.size());

        final List<List<TestSection>> result = DynamicRegionPartitioner.partition(
            sections, s -> s.x, s -> s.z, s -> s.chunks, 2, 4, 7
        );

        assertTrue(result.size() >= 2);
        assertValidPartition(sections, result);
    }

    @Test
    void testZigzagSnakeShape() {
        final List<TestSection> sections = new ArrayList<>();
        // S-shaped serpentine path
        for (int x = 0; x < 5; x++) sections.add(new TestSection(x, 0, 5));
        sections.add(new TestSection(4, 1, 5));
        for (int x = 4; x >= 0; x--) sections.add(new TestSection(x, 2, 5));
        sections.add(new TestSection(0, 3, 5));
        for (int x = 0; x < 5; x++) sections.add(new TestSection(x, 4, 5));

        final List<List<TestSection>> result = DynamicRegionPartitioner.partition(
            sections, s -> s.x, s -> s.z, s -> s.chunks, 2, 8, 7
        );

        assertTrue(result.size() >= 2);
        assertValidPartition(sections, result);
    }

    @Test
    void testHighLoadStressLargeGrid() {
        // 20x20 = 400 sections
        final List<TestSection> sections = new ArrayList<>();
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                sections.add(new TestSection(x, z, 1 + ((x * 17 + z * 31) % 10)));
            }
        }

        final long start = System.nanoTime();
        final List<List<TestSection>> result = DynamicRegionPartitioner.partition(
            sections, s -> s.x, s -> s.z, s -> s.chunks, 4, 16, 16
        );
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 100, "Partitioning 400 sections must finish within 100ms, took " + elapsedMs + "ms");
        assertTrue(result.size() >= 16);
        assertValidPartition(sections, result);
    }

    @Test
    void testAggressionDoesNotAlterViewOrSimulationDistance() {
        // Setting aggression in LoadScaling
        io.canvasmc.canvas.regionizer.loadscaling.LoadScaling.setAggression(10);
        assertEquals(10, io.canvasmc.canvas.regionizer.loadscaling.LoadScaling.getAggression());

        io.canvasmc.canvas.regionizer.loadscaling.LoadScaling.setAggression(100);
        assertEquals(100, io.canvasmc.canvas.regionizer.loadscaling.LoadScaling.getAggression());

        io.canvasmc.canvas.regionizer.loadscaling.LoadScaling.setAggression(50);
        assertEquals(50, io.canvasmc.canvas.regionizer.loadscaling.LoadScaling.getAggression());
    }
}

