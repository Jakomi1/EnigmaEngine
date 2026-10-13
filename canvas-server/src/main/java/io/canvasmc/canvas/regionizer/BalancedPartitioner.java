package io.canvasmc.canvas.regionizer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToIntFunction;

/**
 * Balanced spatial graph partitioner for ThreadedRegion sections.
 *
 * <p>Where {@link DynamicRegionPartitioner} recursively bisects a region, this partitioner grows all
 * sub-regions at once using a capacitated multi-source breadth first search and then polishes the
 * boundaries with a min-cut refinement pass. The result is a set of sub-regions that are:</p>
 *
 * <ul>
 *     <li><b>connected</b> - every sub-region is a single spatially contiguous blob,</li>
 *     <li><b>balanced</b> - no sub-region carries more than roughly {@code 1 + slack} times the
 *     average workload weight, and</li>
 *     <li><b>compact</b> - the min-cut refinement moves boundary sections to the neighbouring
 *     sub-region they interact with the most, minimising the number of cross-region seams.</li>
 * </ul>
 *
 * <p>The implementation is completely free of Minecraft types: it only relies on primitive
 * projections of the caller supplied section objects, which keeps it trivially unit testable and
 * usable from the regionizer's hot path without pulling in the server.</p>
 *
 * @param <T> the section type being partitioned
 */
public final class BalancedPartitioner {

    private static final AtomicLong GROUP_TOKEN_GENERATOR = new AtomicLong(1L);

    private static final int[][] NEIGHBOUR_OFFSETS = {
        {-1, -1}, {0, -1}, {1, -1},
        {-1, 0}, {1, 0},
        {-1, 1}, {0, 1}, {1, 1}
    };

    private BalancedPartitioner() {
    }

    /**
     * Allocates a fresh, process-unique partition group token. Every region produced by a single
     * split shares the returned token so the merge logic can recognise them as siblings and refuse
     * to immediately glue them back together.
     *
     * @return a positive group token
     */
    public static long nextGroupToken() {
        return GROUP_TOKEN_GENERATOR.getAndIncrement();
    }

    /**
     * Combines the raw section metrics into a single workload weight using the configured
     * coefficients. Kept intentionally MC free so callers can reuse it outside the regionizer thread.
     *
     * @param players         number of players actively ticking the section
     * @param chunkFill       number of loaded chunks in the section
     * @param entities        number of entities in the section
     * @param weightPlayers   coefficient applied to the player count
     * @param weightChunks    coefficient applied to the normalised chunk fill
     * @param weightEntities  coefficient applied to the normalised entity count
     * @param entityNorm      entity count that counts as one full "entity unit"
     * @param sectionChunkSize number of chunks in a fully loaded section
     * @return a weight of at least {@code 1}
     */
    public static int combineWeight(
        final int players,
        final int chunkFill,
        final int entities,
        final double weightPlayers,
        final double weightChunks,
        final double weightEntities,
        final int entityNorm,
        final int sectionChunkSize
    ) {
        final double normalisedChunks = sectionChunkSize <= 0 ? chunkFill : (double) chunkFill / sectionChunkSize;
        final double normalisedEntities = entityNorm <= 0 ? entities : (double) entities / entityNorm;
        final double value = weightPlayers * players + weightChunks * normalisedChunks + weightEntities * normalisedEntities;
        return Math.max(1, (int) Math.round(value));
    }

    /**
     * Partitions a collection of sections into balanced, connected sub-regions.
     *
     * @param sections         the sections to partition
     * @param getX             extracts the section X coordinate
     * @param getZ             extracts the section Z coordinate
     * @param getWeight        extracts the section workload weight (values below 1 are clamped to 1)
     * @param targetSections   desired section count per sub-region ({@code 1} means "one section per region")
     * @param maxRegions       hard upper bound on the number of sub-regions produced
     * @param minSections      soft lower bound on sections per sub-region
     * @param slack            tolerated relative overload, e.g. {@code 0.15} for 15 percent
     * @param refinementPasses number of min-cut refinement sweeps ({@code 0} disables refinement)
     * @param maxParallelism   absolute upper bound on sub-regions (e.g. the tick thread count)
     * @param <T>              the section type
     * @return a list of sub-regions, each a non-empty, connected, disjoint list of sections
     */
    public static <T> List<List<T>> partition(
        final Collection<T> sections,
        final ToIntFunction<T> getX,
        final ToIntFunction<T> getZ,
        final ToIntFunction<T> getWeight,
        final int targetSections,
        final int maxRegions,
        final int minSections,
        final double slack,
        final int refinementPasses,
        final int maxParallelism
    ) {
        if (sections == null || sections.isEmpty()) {
            return List.of();
        }
        if (sections.size() == 1) {
            return List.of(new ArrayList<>(sections));
        }

        final List<Node<T>> nodes = new ArrayList<>(sections.size());
        final Map<Long, Node<T>> nodeMap = new HashMap<>(sections.size() * 2);
        for (final T section : sections) {
            final int x = getX.applyAsInt(section);
            final int z = getZ.applyAsInt(section);
            final int weight = Math.max(1, getWeight.applyAsInt(section));
            final long key = coordKey(x, z);
            final Node<T> node = new Node<>(section, x, z, key, weight);
            nodes.add(node);
            nodeMap.put(key, node);
        }

        for (final Node<T> node : nodes) {
            for (final int[] offset : NEIGHBOUR_OFFSETS) {
                final Node<T> neighbour = nodeMap.get(coordKey(node.x + offset[0], node.z + offset[1]));
                if (neighbour != null) {
                    node.neighbours.add(neighbour);
                }
            }
            node.neighbours.sort(Comparator.comparingLong(n -> n.key));
        }

        final int effectiveTarget = Math.max(1, targetSections);
        final int effectiveMinSections = Math.max(1, minSections);
        final double effectiveSlack = Math.max(0.0D, Math.min(1.0D, slack));
        final int effectiveMaxParts = Math.max(1, Math.min(Math.max(2, maxRegions), Math.max(1, maxParallelism)));

        final List<List<Node<T>>> components = findConnectedComponents(nodes);
        final int[] desiredParts = new int[components.size()];
        int desiredTotal = 0;
        for (int i = 0; i < components.size(); i++) {
            final int count = components.get(i).size();
            int parts = (int) Math.ceil((double) count / effectiveTarget);
            parts = Math.max(1, Math.min(count, parts));
            if (effectiveMinSections > 1) {
                parts = Math.min(parts, Math.max(1, count / effectiveMinSections));
            }
            desiredParts[i] = parts;
            desiredTotal += parts;
        }

        // Shrink the plan down to the hard cap, trimming the currently largest components first.
        while (desiredTotal > effectiveMaxParts) {
            int bestIndex = -1;
            int bestParts = 1;
            for (int i = 0; i < desiredParts.length; i++) {
                if (desiredParts[i] > bestParts) {
                    bestParts = desiredParts[i];
                    bestIndex = i;
                }
            }
            if (bestIndex < 0) {
                break;
            }
            desiredParts[bestIndex]--;
            desiredTotal--;
        }

        final List<List<T>> result = new ArrayList<>(desiredTotal);
        for (int i = 0; i < components.size(); i++) {
            final List<List<Node<T>>> parts = partitionComponent(
                components.get(i), desiredParts[i], effectiveSlack, refinementPasses
            );
            for (final List<Node<T>> part : parts) {
                final List<T> mapped = new ArrayList<>(part.size());
                for (final Node<T> node : part) {
                    mapped.add(node.data);
                }
                result.add(mapped);
            }
        }
        return result;
    }

    private static <T> List<List<Node<T>>> partitionComponent(
        final List<Node<T>> component,
        final int requestedParts,
        final double slack,
        final int refinementPasses
    ) {
        final int k = Math.max(1, Math.min(requestedParts, component.size()));
        if (k <= 1) {
            return List.of(new ArrayList<>(component));
        }

        final int componentWeight = sumWeight(component);
        final double targetWeight = (double) componentWeight / k;
        final int capacity = Math.max(1, (int) Math.ceil(targetWeight * (1.0D + slack)));

        final Map<Node<T>, Integer> index = new HashMap<>(component.size() * 2);
        for (int i = 0; i < component.size(); i++) {
            index.put(component.get(i), i);
        }

        final List<Node<T>> seeds = selectSeeds(component, k);
        final int regionCount = seeds.size();
        final int[] owner = new int[component.size()];
        Arrays.fill(owner, -1);
        final int[] load = new int[regionCount];
        final int[] sizes = new int[regionCount];

        @SuppressWarnings("unchecked")
        final ArrayDeque<Node<T>>[] frontiers = new ArrayDeque[regionCount];
        for (int region = 0; region < regionCount; region++) {
            final int seedIndex = index.get(seeds.get(region));
            owner[seedIndex] = region;
            load[region] = seeds.get(region).weight;
            sizes[region] = 1;
            frontiers[region] = new ArrayDeque<>();
            for (final Node<T> neighbour : seeds.get(region).neighbours) {
                if (owner[index.get(neighbour)] < 0) {
                    frontiers[region].add(neighbour);
                }
            }
        }

        // Balanced region growing: every region claims at most one section per round so no single
        // seed can race ahead and swallow the whole component before the others start.
        boolean progress = true;
        while (progress) {
            progress = false;
            for (int region = 0; region < regionCount; region++) {
                if (load[region] >= capacity) {
                    continue;
                }
                final Node<T> claimed = pollUnassigned(frontiers[region], owner, index);
                if (claimed == null) {
                    continue;
                }
                owner[index.get(claimed)] = region;
                load[region] += claimed.weight;
                sizes[region]++;
                progress = true;
                for (final Node<T> neighbour : claimed.neighbours) {
                    if (owner[index.get(neighbour)] < 0) {
                        frontiers[region].add(neighbour);
                    }
                }
            }
        }

        assignOrphans(component, owner, load, sizes, index);

        repairConnectivity(component, owner, load, sizes, index);

        for (int pass = 0; pass < refinementPasses; pass++) {
            if (!refineCut(component, owner, load, sizes, index, capacity)) {
                break;
            }
            repairConnectivity(component, owner, load, sizes, index);
        }

        return collectParts(component, owner, index, regionCount);
    }

    private static <T> Node<T> pollUnassigned(
        final ArrayDeque<Node<T>> frontier,
        final int[] owner,
        final Map<Node<T>, Integer> index
    ) {
        while (!frontier.isEmpty()) {
            final Node<T> candidate = frontier.pollFirst();
            if (owner[index.get(candidate)] < 0) {
                return candidate;
            }
        }
        return null;
    }

    private static <T> List<Node<T>> selectSeeds(final List<Node<T>> component, final int k) {
        final List<Node<T>> seeds = new ArrayList<>(k);

        Node<T> first = component.get(0);
        for (final Node<T> node : component) {
            if (node.weight > first.weight || (node.weight == first.weight && node.key < first.key)) {
                first = node;
            }
        }
        seeds.add(first);

        final Map<Node<T>, Integer> hopDistance = new HashMap<>(component.size() * 2);
        breadthFirstDistances(first, hopDistance);

        boolean[] isSeed = new boolean[component.size()];
        final Map<Node<T>, Integer> index = new HashMap<>(component.size() * 2);
        for (int i = 0; i < component.size(); i++) {
            index.put(component.get(i), i);
        }
        isSeed[index.get(first)] = true;

        while (seeds.size() < k) {
            Node<T> best = null;
            int bestDistance = -1;
            for (final Node<T> node : component) {
                if (isSeed[index.get(node)]) {
                    continue;
                }
                final int distance = hopDistance.getOrDefault(node, Integer.MAX_VALUE);
                if (best == null
                    || distance > bestDistance
                    || (distance == bestDistance && node.weight > best.weight)
                    || (distance == bestDistance && node.weight == best.weight && node.key < best.key)) {
                    best = node;
                    bestDistance = distance;
                }
            }
            if (best == null) {
                break;
            }
            seeds.add(best);
            isSeed[index.get(best)] = true;
            breadthFirstDistances(best, hopDistance);
        }
        return seeds;
    }

    private static <T> void breadthFirstDistances(final Node<T> source, final Map<Node<T>, Integer> distance) {
        final Queue<Node<T>> queue = new ArrayDeque<>();
        distance.put(source, 0);
        queue.add(source);
        while (!queue.isEmpty()) {
            final Node<T> current = queue.poll();
            final int next = distance.get(current) + 1;
            for (final Node<T> neighbour : current.neighbours) {
                final Integer known = distance.get(neighbour);
                if (known == null || next < known) {
                    distance.put(neighbour, next);
                    queue.add(neighbour);
                }
            }
        }
    }

    private static <T> void assignOrphans(
        final List<Node<T>> component,
        final int[] owner,
        final int[] load,
        final int[] sizes,
        final Map<Node<T>, Integer> index
    ) {
        boolean progress = true;
        while (progress) {
            progress = false;
            for (final Node<T> node : component) {
                final int nodeIndex = index.get(node);
                if (owner[nodeIndex] >= 0) {
                    continue;
                }
                int bestRegion = -1;
                int bestLoad = Integer.MAX_VALUE;
                for (final Node<T> neighbour : node.neighbours) {
                    final int neighbourOwner = owner[index.get(neighbour)];
                    if (neighbourOwner >= 0 && load[neighbourOwner] < bestLoad) {
                        bestLoad = load[neighbourOwner];
                        bestRegion = neighbourOwner;
                    }
                }
                if (bestRegion >= 0) {
                    owner[nodeIndex] = bestRegion;
                    load[bestRegion] += node.weight;
                    sizes[bestRegion]++;
                    progress = true;
                }
            }
        }
    }

    private static <T> void repairConnectivity(
        final List<Node<T>> component,
        final int[] owner,
        final int[] load,
        final int[] sizes,
        final Map<Node<T>, Integer> index
    ) {
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 8) {
            changed = false;
            for (int region = 0; region < sizes.length; region++) {
                if (sizes[region] <= 1) {
                    continue;
                }
                final List<List<Node<T>>> pieces = regionComponents(component, owner, index, region);
                if (pieces.size() <= 1) {
                    continue;
                }
                List<Node<T>> keep = pieces.get(0);
                for (final List<Node<T>> piece : pieces) {
                    if (piece.size() > keep.size()) {
                        keep = piece;
                    }
                }
                for (final List<Node<T>> piece : pieces) {
                    if (piece == keep) {
                        continue;
                    }
                    for (final Node<T> node : piece) {
                        final int adoption = adjacentMinRegion(node, owner, load, index, region);
                        if (adoption >= 0) {
                            owner[index.get(node)] = adoption;
                            load[region] -= node.weight;
                            load[adoption] += node.weight;
                            sizes[region]--;
                            sizes[adoption]++;
                        }
                    }
                    changed = true;
                }
            }
        }
    }

    private static <T> boolean refineCut(
        final List<Node<T>> component,
        final int[] owner,
        final int[] load,
        final int[] sizes,
        final Map<Node<T>, Integer> index,
        final int capacity
    ) {
        final List<Node<T>> ordered = new ArrayList<>(component);
        ordered.sort(Comparator.comparingLong(n -> n.key));

        boolean moved = false;
        for (final Node<T> node : ordered) {
            final int nodeIndex = index.get(node);
            final int ownRegion = owner[nodeIndex];
            if (sizes[ownRegion] <= 1) {
                continue;
            }

            int ownEdges = 0;
            for (final Node<T> neighbour : node.neighbours) {
                if (owner[index.get(neighbour)] == ownRegion) {
                    ownEdges++;
                }
            }

            int bestGain = Integer.MIN_VALUE;
            int chosenRegion = -1;
            for (final Node<T> neighbour : node.neighbours) {
                final int candidateRegion = owner[index.get(neighbour)];
                if (candidateRegion < 0 || candidateRegion == ownRegion) {
                    continue;
                }
                int candidateEdges = 0;
                for (final Node<T> other : node.neighbours) {
                    if (owner[index.get(other)] == candidateRegion) {
                        candidateEdges++;
                    }
                }
                final int gain = candidateEdges - ownEdges;
                if (gain > bestGain
                    || (gain == bestGain && chosenRegion != -1 && candidateRegion < chosenRegion)) {
                    bestGain = gain;
                    chosenRegion = candidateRegion;
                }
            }

            if (chosenRegion < 0 || bestGain <= 0) {
                continue;
            }
            if (load[chosenRegion] + node.weight > capacity) {
                continue;
            }
            owner[nodeIndex] = chosenRegion;
            load[ownRegion] -= node.weight;
            load[chosenRegion] += node.weight;
            sizes[ownRegion]--;
            sizes[chosenRegion]++;
            moved = true;
        }
        return moved;
    }

    private static <T> List<List<Node<T>>> collectParts(
        final List<Node<T>> component,
        final int[] owner,
        final Map<Node<T>, Integer> index,
        final int regionCount
    ) {
        @SuppressWarnings("unchecked")
        final List<Node<T>>[] parts = new List[regionCount];
        for (int i = 0; i < regionCount; i++) {
            parts[i] = new ArrayList<>();
        }
        for (final Node<T> node : component) {
            final int region = owner[index.get(node)];
            if (region >= 0) {
                parts[region].add(node);
            }
        }
        final List<List<Node<T>>> result = new ArrayList<>(regionCount);
        for (final List<Node<T>> part : parts) {
            if (!part.isEmpty()) {
                result.add(part);
            }
        }
        return result;
    }

    private static <T> int adjacentMinRegion(
        final Node<T> node,
        final int[] owner,
        final int[] load,
        final Map<Node<T>, Integer> index,
        final int excludedRegion
    ) {
        int bestRegion = -1;
        int bestLoad = Integer.MAX_VALUE;
        for (final Node<T> neighbour : node.neighbours) {
            final int region = owner[index.get(neighbour)];
            if (region < 0 || region == excludedRegion) {
                continue;
            }
            if (load[region] < bestLoad) {
                bestLoad = load[region];
                bestRegion = region;
            }
        }
        return bestRegion;
    }

    private static <T> List<List<Node<T>>> regionComponents(
        final List<Node<T>> component,
        final int[] owner,
        final Map<Node<T>, Integer> index,
        final int region
    ) {
        final List<List<Node<T>>> pieces = new ArrayList<>();
        final boolean[] visited = new boolean[component.size()];
        for (int i = 0; i < component.size(); i++) {
            if (visited[i] || owner[i] != region) {
                continue;
            }
            final List<Node<T>> piece = new ArrayList<>();
            final Queue<Integer> queue = new ArrayDeque<>();
            queue.add(i);
            visited[i] = true;
            while (!queue.isEmpty()) {
                final int current = queue.poll();
                final Node<T> node = component.get(current);
                piece.add(node);
                for (final Node<T> neighbour : node.neighbours) {
                    final int neighbourIndex = index.get(neighbour);
                    if (!visited[neighbourIndex] && owner[neighbourIndex] == region) {
                        visited[neighbourIndex] = true;
                        queue.add(neighbourIndex);
                    }
                }
            }
            pieces.add(piece);
        }
        return pieces;
    }

    private static <T> int sumWeight(final List<Node<T>> nodes) {
        int sum = 0;
        for (final Node<T> node : nodes) {
            sum += node.weight;
        }
        return sum;
    }

    private static <T> List<List<Node<T>>> findConnectedComponents(final List<Node<T>> nodes) {
        final List<List<Node<T>>> components = new ArrayList<>();
        final Map<Node<T>, Integer> index = new HashMap<>(nodes.size() * 2);
        for (int i = 0; i < nodes.size(); i++) {
            index.put(nodes.get(i), i);
        }
        final boolean[] visited = new boolean[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            if (visited[i]) {
                continue;
            }
            final List<Node<T>> component = new ArrayList<>();
            final Queue<Integer> queue = new ArrayDeque<>();
            queue.add(i);
            visited[i] = true;
            while (!queue.isEmpty()) {
                final int current = queue.poll();
                final Node<T> node = nodes.get(current);
                component.add(node);
                for (final Node<T> neighbour : node.neighbours) {
                    final int neighbourIndex = index.get(neighbour);
                    if (!visited[neighbourIndex]) {
                        visited[neighbourIndex] = true;
                        queue.add(neighbourIndex);
                    }
                }
            }
            components.add(component);
        }
        return components;
    }

    public static long coordKey(final int x, final int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    static final class Node<T> {
        final T data;
        final int x;
        final int z;
        final long key;
        final int weight;
        final List<Node<T>> neighbours = new ArrayList<>(8);

        Node(final T data, final int x, final int z, final long key, final int weight) {
            this.data = data;
            this.x = x;
            this.z = z;
            this.key = key;
            this.weight = Math.max(1, weight);
        }

        @Override
        public String toString() {
            return "(" + this.x + "," + this.z + ",w=" + this.weight + ")";
        }
    }
}
