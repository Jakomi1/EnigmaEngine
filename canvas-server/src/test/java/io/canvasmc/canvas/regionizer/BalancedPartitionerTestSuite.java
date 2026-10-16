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

class BalancedPartitionerTestSuite {

    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    record TestSection(int x, int z, int chunks) {
    }

    private List<List<TestSection>> partition(
        final List<TestSection> sections,
        final int targetSections,
        final int maxRegions,
        final int minSections,
        final double slack,
        final int refinementPasses
    ) {
        return BalancedPartitioner.partition(
            sections, s -> s.x, s -> s.z, s -> s.chunks,
            targetSections, maxRegions, minSections, slack, refinementPasses, 16
        );
    }

    private void assertValidPartition(final List<TestSection> input, final List<List<TestSection>> result) {
        assertFalse(result.isEmpty(), "Result should not be empty");

        final Set<TestSection> seen = new HashSet<>();
        int totalResultSections = 0;

        for (final List<TestSection> part : result) {
            assertFalse(part.isEmpty(), "Sub-region part should not be empty");
            totalResultSections += part.size();
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
            keys.add(BalancedPartitioner.coordKey(s.x, s.z));
        }

        final Set<Long> visited = new HashSet<>();
        final Queue<TestSection> queue = new ArrayDeque<>();
        final TestSection start = part.get(0);
        visited.add(BalancedPartitioner.coordKey(start.x, start.z));
        queue.add(start);

        while (!queue.isEmpty()) {
            final TestSection curr = queue.poll();
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if ((dx | dz) == 0) continue;
                    final long neighbourKey = BalancedPartitioner.coordKey(curr.x + dx, curr.z + dz);
                    if (keys.contains(neighbourKey) && visited.add(neighbourKey)) {
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

    private int maxWeight(final List<List<TestSection>> result) {
        int max = 0;
        for (final List<TestSection> part : result) {
            int sum = 0;
            for (final TestSection s : part) {
                sum += s.chunks;
            }
            max = Math.max(max, sum);
        }
        return max;
    }

    @Test
    void testSingleSectionDoesNotSplit() {
        final List<TestSection> sections = List.of(new TestSection(0, 0, 10));
        final List<List<TestSection>> result = partition(sections, 1, 16, 1, 0.15, 2);

        assertEquals(1, result.size());
        assertEquals(1, result.get(0).size());
        assertValidPartition(sections, result);
    }

    @Test
    void testEmptyInputYieldsEmptyResult() {
        assertTrue(partition(List.of(), 1, 16, 1, 0.15, 2).isEmpty());
    }

    @Test
    void testTwoAdjacentSectionsSplitToSingles() {
        final List<TestSection> sections = List.of(
            new TestSection(0, 0, 10),
            new TestSection(1, 0, 10)
        );

        final List<List<TestSection>> result = partition(sections, 1, 16, 1, 0.15, 2);

        assertEquals(2, result.size());
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

        final List<List<TestSection>> result = partition(sections, 1, 16, 1, 0.15, 2);

        assertTrue(result.size() >= 2);
        assertValidPartition(sections, result);
    }

    @Test
    void testLinearStripBisection() {
        final List<TestSection> sections = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            sections.add(new TestSection(i, 0, 10));
        }

        final List<List<TestSection>> result = partition(sections, 3, 16, 1, 0.15, 2);

        assertEquals(2, result.size());
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

        final List<List<TestSection>> result = partition(sections, 2, 16, 1, 0.15, 2);

        assertTrue(result.size() >= 2);
        assertValidPartition(sections, result);
    }

    @Test
    void testGrid2x2SplitsToSingleSections() {
        final List<TestSection> sections = List.of(
            new TestSection(0, 0, 1),
            new TestSection(1, 0, 1),
            new TestSection(0, 1, 1),
            new TestSection(1, 1, 1)
        );

        final List<List<TestSection>> result = partition(sections, 1, 16, 1, 0.15, 2);

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

        final List<List<TestSection>> result = partition(sections, 4, 16, 1, 0.25, 2);

        assertTrue(result.size() >= 4, "Expected at least four sub-regions, got " + result.size());
        assertValidPartition(sections, result);

        // Uniform weights: the heaviest sub-region may not carry more than twice the lightest.
        int min = Integer.MAX_VALUE;
        int max = 0;
        for (final List<TestSection> part : result) {
            int sum = 0;
            for (final TestSection s : part) {
                sum += s.chunks;
            }
            min = Math.min(min, sum);
            max = Math.max(max, sum);
        }
        assertTrue(max <= min * 2, "Sub-regions should be reasonably balanced: min=" + min + ", max=" + max);
    }

    @Test
    void testUnevenWeightedGridStillBalancesSectionCounts() {
        final List<TestSection> sections = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                sections.add(new TestSection(x, z, x == 0 && z == 0 ? 100 : 1));
            }
        }

        final List<List<TestSection>> result = partition(sections, 3, 16, 1, 0.15, 2);
        assertEquals(3, result.size());
        assertValidPartition(sections, result);

        int smallest = Integer.MAX_VALUE;
        int largest = 0;
        for (final List<TestSection> part : result) {
            smallest = Math.min(smallest, part.size());
            largest = Math.max(largest, part.size());
        }
        assertTrue(largest - smallest <= 1,
            "Split children should carry nearly identical section counts: min=" + smallest + ", max=" + largest);
    }

    @Test
    void testHeavySectionIsolation() {
        final List<TestSection> sections = List.of(
            new TestSection(0, 0, 100),
            new TestSection(1, 0, 5),
            new TestSection(0, 1, 5),
            new TestSection(1, 1, 5)
        );

        final List<List<TestSection>> result = partition(sections, 1, 16, 1, 0.15, 2);

        assertTrue(result.size() >= 2);
        assertValidPartition(sections, result);

        boolean foundIsolatedHeavy = false;
        for (final List<TestSection> part : result) {
            if (part.size() == 1 && part.get(0).chunks == 100) {
                foundIsolatedHeavy = true;
                break;
            }
        }
        assertTrue(foundIsolatedHeavy, "Heavy section should be isolated into its own sub-region");
    }

    @Test
    void testMaxRegionsIsRespected() {
        final List<TestSection> sections = new ArrayList<>();
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                sections.add(new TestSection(x, z, 1));
            }
        }

        final List<List<TestSection>> result = partition(sections, 1, 4, 1, 0.15, 2);

        assertTrue(result.size() <= 4, "Number of sub-regions must not exceed maxRegions: " + result.size());
        assertValidPartition(sections, result);
    }

    @Test
    void testMinSectionsIsRespected() {
        final List<TestSection> sections = new ArrayList<>();
        for (int x = 0; x < 6; x++) {
            sections.add(new TestSection(x, 0, 1));
        }

        final List<List<TestSection>> result = partition(sections, 1, 16, 3, 0.15, 2);

        for (final List<TestSection> part : result) {
            assertTrue(part.size() >= 3, "Sub-region smaller than minSections: " + part.size());
        }
        assertValidPartition(sections, result);
    }

    @Test
    void testDeterministicOutput() {
        final List<TestSection> sections = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                sections.add(new TestSection(x, z, 1 + ((x * 7 + z * 13) % 9)));
            }
        }

        final List<List<TestSection>> first = partition(sections, 2, 16, 1, 0.15, 3);
        final List<List<TestSection>> second = partition(sections, 2, 16, 1, 0.15, 3);

        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(new HashSet<>(first.get(i)), new HashSet<>(second.get(i)),
                "Partition " + i + " must be identical across runs");
        }
    }

    @Test
    void testHollowDonutShapeConnectivity() {
        final List<TestSection> sections = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                if (x == 1 && z == 1) continue;
                sections.add(new TestSection(x, z, 5));
            }
        }

        final List<List<TestSection>> result = partition(sections, 2, 16, 1, 0.15, 2);

        assertTrue(result.size() >= 2);
        assertValidPartition(sections, result);
    }

    @Test
    void testHighLoadStressLargeGrid() {
        final List<TestSection> sections = new ArrayList<>();
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                sections.add(new TestSection(x, z, 1 + ((x * 17 + z * 31) % 10)));
            }
        }

        final long start = System.nanoTime();
        final List<List<TestSection>> result = partition(sections, 4, 16, 1, 0.15, 2);
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 250, "Partitioning 400 sections must finish within 250ms, took " + elapsedMs + "ms");
        assertTrue(result.size() >= 4);
        assertValidPartition(sections, result);
    }

    @Test
    void testCombineWeight() {
        // 2 players, 256 chunk fill (a full section), 16 entities with unit normalisation.
        final int weight = BalancedPartitioner.combineWeight(2, 256, 16, 4.0D, 3.0D, 1.0D, 16, 256);
        // 4 * 2 + 3 * (256 / 256) + 1 * (16 / 16) = 8 + 3 + 1 = 12
        assertEquals(12, weight);

        assertTrue(BalancedPartitioner.combineWeight(0, 0, 0, 4.0D, 3.0D, 1.0D, 16, 256) >= 1);
    }

    @Test
    void testSplitPlannerCreatesEnoughChildrenForPlayersAndTickLoad() {
        // Seven players in a 51-section, 14 MSPT region should create four near-equal children
        // (about two players each), rather than retaining the configured static sizing alone.
        assertEquals(13, RegionSplitPlanner.targetSections(51, 24, 16, 7, 14.0D, 8.0D));
        // Tick pressure also causes additional children when no player count is available.
        assertEquals(13, RegionSplitPlanner.targetSections(51, 24, 16, 0, 14.0D, 8.0D));
    }

    @Test
    void testRegionizerSettingsPartitionKnobs() {
        final RegionizerSettings base = new RegionizerSettings(
            true, 0.25D, 24, 0.7D, 0.3D, 60_000L, 30_000L, 128, 4096, 32.0D, 15, 15, 20,
            true, true, 16, 512, 100, 2, true, 8, 1, 0.2D, 3
        );

        assertTrue(base.partitionEnabled());
        assertEquals(8, base.splitMaxRegions());
        assertEquals(1, base.partitionMinSections());
        assertEquals(0.2D, base.partitionSlack(), 0.0001D);
        assertEquals(3, base.partitionRefinementPasses());

        final RegionizerSettings world = RegionizerSettings.forWorld(base, 2, 50);
        assertTrue(world.partitionEnabled());
        assertEquals(8, world.splitMaxRegions());
        assertEquals(3, world.partitionRefinementPasses());
    }

    @Test
    void testRegionizerIsForcedEnabledInAutoSettings() {
        final RegionizerSettings disabledInYaml = new RegionizerSettings(
            false, 0.99D, 2, 0.8D, 0.2D, 120_000L, 750L, 2, 16, 4.0D, 20, 10, 1,
            false, false, 6, 1
        );

        assertTrue(disabledInYaml.enabled(), "AUTO settings must never leave the adaptive splitter disabled");
    }
}
