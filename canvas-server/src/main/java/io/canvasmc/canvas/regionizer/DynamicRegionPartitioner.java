package io.canvasmc.canvas.regionizer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Intelligent, dynamic spatial graph partitioner for ThreadedRegion sections.
 *
 * Partitions regions into balanced, spatially contiguous, compact sub-regions
 * while minimizing cross-region boundaries (cut edges) to avoid synchronization overhead
 * and thread contention.
 */
public final class DynamicRegionPartitioner {

    private DynamicRegionPartitioner() {
    }

    /**
     * Internal node representing a section in the 2D grid graph.
     */
    static final class Node<T> {
        final T data;
        final int x;
        final int z;
        final long key;
        final int weight;
        final List<Node<T>> neighbors = new ArrayList<>(8);

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

    /**
     * A cluster of connected nodes with an aggregate workload weight.
     */
    static final class Cluster<T> {
        final List<Node<T>> nodes;
        final int totalWeight;

        Cluster(final List<Node<T>> nodes) {
            this.nodes = nodes;
            int sum = 0;
            for (final Node<T> n : nodes) {
                sum += n.weight;
            }
            this.totalWeight = sum;
        }
    }

    private record ClusterPair<T>(Cluster<T> partA, Cluster<T> partB) {
    }

    public static long coordKey(final int x, final int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /**
     * Partitions a collection of sections into balanced, connected, compact sub-regions.
     *
     * @param sections the collection of sections to partition
     * @param getX function to extract section X coordinate
     * @param getZ function to extract section Z coordinate
     * @param getWeight function to extract section workload weight (e.g. chunk count)
     * @param targetSections target sections per sub-region (>= 1)
     * @param splitMaxSections hard max sections per sub-region
     * @param maxParallelism maximum sub-regions to produce (e.g. available CPU cores)
     * @param <T> section type
     * @return list of sub-regions, each being a list of sections. Returns list with 1 element if cannot split.
     */
    public static <T> List<List<T>> partition(
        final Collection<T> sections,
        final ToIntFunction<T> getX,
        final ToIntFunction<T> getZ,
        final ToIntFunction<T> getWeight,
        final int targetSections,
        final int splitMaxSections,
        final int maxParallelism
    ) {
        if (sections == null || sections.size() <= 1) {
            return sections == null ? List.of() : List.of(new ArrayList<>(sections));
        }

        final int totalCount = sections.size();
        final int effectiveTarget = Math.max(1, targetSections);
        final int effectiveMaxParallelism = Math.max(2, maxParallelism);

        // Desired number of parts K:
        // Partition into at least 2 parts, up to min(totalCount, effectiveMaxParallelism)
        int desiredParts = (int) Math.ceil((double) totalCount / effectiveTarget);
        desiredParts = Math.max(2, Math.min(totalCount, Math.min(desiredParts, effectiveMaxParallelism)));

        // Wrap sections into graph nodes
        final List<Node<T>> nodes = new ArrayList<>(totalCount);
        final Map<Long, Node<T>> nodeMap = new HashMap<>(totalCount * 2);

        for (final T section : sections) {
            final int x = getX.applyAsInt(section);
            final int z = getZ.applyAsInt(section);
            final int w = Math.max(1, getWeight.applyAsInt(section));
            final long key = coordKey(x, z);
            final Node<T> node = new Node<>(section, x, z, key, w);
            nodes.add(node);
            nodeMap.put(key, node);
        }

        // Establish adjacency (8-way: orthogonal or diagonal neighbors)
        for (final Node<T> node : nodes) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if ((dx | dz) == 0) {
                        continue;
                    }
                    final Node<T> neighbor = nodeMap.get(coordKey(node.x + dx, node.z + dz));
                    if (neighbor != null) {
                        node.neighbors.add(neighbor);
                    }
                }
            }
        }

        // Step 1: Find connected components (natural disconnected islands)
        final List<List<Node<T>>> components = findConnectedComponents(nodes);

        // Step 2: Use a priority queue of clusters to hierarchically split down to desiredParts
        // Comparator: prefer splitting clusters that have > 1 node, ordered by weight (descending)
        final PriorityQueue<Cluster<T>> clusterQueue = new PriorityQueue<>((c1, c2) -> {
            final boolean c1Splittable = c1.nodes.size() > 1;
            final boolean c2Splittable = c2.nodes.size() > 1;
            if (c1Splittable != c2Splittable) {
                return c1Splittable ? -1 : 1;
            }
            return Integer.compare(c2.totalWeight, c1.totalWeight);
        });

        for (final List<Node<T>> component : components) {
            clusterQueue.add(new Cluster<>(component));
        }

        // Hierarchically bisect the heaviest splittable cluster until desiredParts is reached
        while (clusterQueue.size() < desiredParts) {
            final Cluster<T> heaviest = clusterQueue.peek();
            if (heaviest == null || heaviest.nodes.size() <= 1) {
                // No more clusters can be split
                break;
            }
            clusterQueue.poll();

            final ClusterPair<T> split = bisectCluster(heaviest);
            if (split == null) {
                // Could not bisect, re-add as unsplittable
                clusterQueue.add(heaviest);
                break;
            }

            clusterQueue.add(split.partA());
            clusterQueue.add(split.partB());
        }

        // Convert clusters to result list
        final List<List<T>> result = new ArrayList<>(clusterQueue.size());
        for (final Cluster<T> cluster : clusterQueue) {
            final List<T> part = new ArrayList<>(cluster.nodes.size());
            for (final Node<T> node : cluster.nodes) {
                part.add(node.data);
            }
            result.add(part);
        }

        return result;
    }

    /**
     * Bisects a connected cluster into two non-empty, connected, compact sub-clusters.
     */
    private static <T> ClusterPair<T> bisectCluster(final Cluster<T> cluster) {
        final List<Node<T>> nodes = cluster.nodes;
        if (nodes.size() <= 1) {
            return null;
        }

        // Compute bounding box and principal axis
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        long sumX = 0, sumZ = 0;

        for (final Node<T> n : nodes) {
            minX = Math.min(minX, n.x);
            maxX = Math.max(maxX, n.x);
            minZ = Math.min(minZ, n.z);
            maxZ = Math.max(maxZ, n.z);
            sumX += n.x;
            sumZ += n.z;
        }

        final int spanX = maxX - minX;
        final int spanZ = maxZ - minZ;

        final boolean splitOnX;
        if (spanX != spanZ) {
            splitOnX = spanX > spanZ;
        } else {
            // Compare coordinate variances
            final double meanX = (double) sumX / nodes.size();
            final double meanZ = (double) sumZ / nodes.size();
            double varX = 0, varZ = 0;
            for (final Node<T> n : nodes) {
                varX += (n.x - meanX) * (n.x - meanX);
                varZ += (n.z - meanZ) * (n.z - meanZ);
            }
            splitOnX = varX >= varZ;
        }

        // Find extreme seeds: seedA is min on principal axis, seedB is max
        Node<T> seedA = nodes.get(0);
        Node<T> seedB = nodes.get(0);
        int minPrimary = Integer.MAX_VALUE, maxPrimary = Integer.MIN_VALUE;
        int minSecondary = Integer.MAX_VALUE, maxSecondary = Integer.MIN_VALUE;

        for (final Node<T> n : nodes) {
            final int primary = splitOnX ? n.x : n.z;
            final int secondary = splitOnX ? n.z : n.x;

            if (primary < minPrimary || (primary == minPrimary && secondary < minSecondary)) {
                minPrimary = primary;
                minSecondary = secondary;
                seedA = n;
            }
            if (primary > maxPrimary || (primary == maxPrimary && secondary > maxSecondary)) {
                maxPrimary = primary;
                maxSecondary = secondary;
                seedB = n;
            }
        }

        if (seedA == seedB) {
            seedB = nodes.get(nodes.size() - 1);
        }

        // Target weight for part A is half of total cluster weight
        final int targetWeight = Math.max(1, cluster.totalWeight / 2);

        // Grow set A from seedA using Best-First Search for compactness and load balance
        final Set<Node<T>> setA = new HashSet<>();
        final Set<Node<T>> candidatePool = new HashSet<>(nodes);

        setA.add(seedA);
        candidatePool.remove(seedA);
        int currentWeightA = seedA.weight;

        // Frontier priority: candidate neighbors of setA
        // Rank candidates by: (internal connections to setA) * 10 - distance_to_seedA
        final Set<Node<T>> frontier = new HashSet<>();
        for (final Node<T> neighbor : seedA.neighbors) {
            if (candidatePool.contains(neighbor) && neighbor != seedB) {
                frontier.add(neighbor);
            }
        }

        while (!frontier.isEmpty() && currentWeightA < targetWeight && candidatePool.size() > 1) {
            Node<T> bestCandidate = null;
            int bestScore = Integer.MIN_VALUE;

            for (final Node<T> candidate : frontier) {
                int connectionsToA = 0;
                for (final Node<T> nb : candidate.neighbors) {
                    if (setA.contains(nb)) {
                        connectionsToA++;
                    }
                }
                final int dist = Math.abs(candidate.x - seedA.x) + Math.abs(candidate.z - seedA.z);
                final int score = connectionsToA * 10 - dist;

                if (score > bestScore) {
                    bestScore = score;
                    bestCandidate = candidate;
                }
            }

            if (bestCandidate == null) {
                break;
            }

            frontier.remove(bestCandidate);
            setA.add(bestCandidate);
            candidatePool.remove(bestCandidate);
            currentWeightA += bestCandidate.weight;

            // Expand frontier
            for (final Node<T> neighbor : bestCandidate.neighbors) {
                if (candidatePool.contains(neighbor) && neighbor != seedB) {
                    frontier.add(neighbor);
                }
            }
        }

        // Remaining nodes form set B
        final Set<Node<T>> setB = new HashSet<>(candidatePool);

        // Connectivity verification & repair:
        // setA is connected by construction (grown neighbor-by-neighbor from seedA).
        // Check if setB is connected:
        final List<List<Node<T>>> componentsB = findConnectedComponentsInSubSet(setB);

        if (componentsB.size() > 1) {
            // Find component of B that contains seedB
            List<Node<T>> mainB = null;
            for (final List<Node<T>> comp : componentsB) {
                if (comp.contains(seedB)) {
                    mainB = comp;
                    break;
                }
            }
            if (mainB == null) {
                mainB = componentsB.get(0);
            }

            // Reassign orphaned satellite components of B to setA (they touch setA)
            for (final List<Node<T>> comp : componentsB) {
                if (comp != mainB) {
                    setA.addAll(comp);
                    setB.removeAll(comp);
                }
            }
        }

        // Final safeguard: ensure neither set is empty
        if (setA.isEmpty() || setB.isEmpty()) {
            return null;
        }

        return new ClusterPair<>(new Cluster<>(new ArrayList<>(setA)), new Cluster<>(new ArrayList<>(setB)));
    }

    /**
     * Finds connected components across all nodes.
     */
    private static <T> List<List<Node<T>>> findConnectedComponents(final List<Node<T>> nodes) {
        final List<List<Node<T>>> components = new ArrayList<>();
        final Set<Node<T>> visited = new HashSet<>();

        for (final Node<T> node : nodes) {
            if (visited.add(node)) {
                final List<Node<T>> component = new ArrayList<>();
                final Queue<Node<T>> queue = new ArrayDeque<>();
                queue.add(node);

                while (!queue.isEmpty()) {
                    final Node<T> curr = queue.poll();
                    component.add(curr);

                    for (final Node<T> neighbor : curr.neighbors) {
                        if (visited.add(neighbor)) {
                            queue.add(neighbor);
                        }
                    }
                }
                components.add(component);
            }
        }

        return components;
    }

    /**
     * Finds connected components within a subset of nodes.
     */
    private static <T> List<List<Node<T>>> findConnectedComponentsInSubSet(final Set<Node<T>> subset) {
        final List<List<Node<T>>> components = new ArrayList<>();
        final Set<Node<T>> visited = new HashSet<>();

        for (final Node<T> node : subset) {
            if (visited.add(node)) {
                final List<Node<T>> component = new ArrayList<>();
                final Queue<Node<T>> queue = new ArrayDeque<>();
                queue.add(node);

                while (!queue.isEmpty()) {
                    final Node<T> curr = queue.poll();
                    component.add(curr);

                    for (final Node<T> neighbor : curr.neighbors) {
                        if (subset.contains(neighbor) && visited.add(neighbor)) {
                            queue.add(neighbor);
                        }
                    }
                }
                components.add(component);
            }
        }

        return components;
    }
}
