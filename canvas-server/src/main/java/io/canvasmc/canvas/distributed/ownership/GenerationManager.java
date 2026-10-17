package io.canvasmc.canvas.distributed.ownership;

import io.canvasmc.canvas.distributed.network.MessageEnvelope;
import io.canvasmc.canvas.distributed.network.RuntimeConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class GenerationManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("EnigmaGeneration");

    private final Map<Long, AtomicLong> regionGenerations = new ConcurrentHashMap<>();

    public long nextGeneration(final long regionId) {
        final AtomicLong gen = regionGenerations.computeIfAbsent(regionId, _ -> new AtomicLong(1));
        final long newGen = gen.incrementAndGet();
        LOGGER.debug("Region {} generation advanced to {}", regionId, newGen);
        return newGen;
    }

    public long currentGeneration(final long regionId) {
        final AtomicLong gen = regionGenerations.get(regionId);
        return gen != null ? gen.get() : 0;
    }

    /**
     * Moves the region's generation view forward. Generations are monotonic per region, so a delayed
     * or duplicated control frame can never resurrect an already-fenced generation.
     *
     * @return true if the view moved forward
     */
    public boolean setGeneration(final long regionId, final long generation) {
        final AtomicLong gen = regionGenerations.computeIfAbsent(regionId, _ -> new AtomicLong(generation));
        long previous = gen.get();
        while (generation > previous && !gen.compareAndSet(previous, generation)) {
            previous = gen.get();
        }
        if (generation < previous) {
            LOGGER.debug("Ignoring generation {} for region {}: current generation is {}",
                    generation, regionId, previous);
            return false;
        }
        return generation > previous;
    }

    public boolean isCurrentGeneration(final long regionId, final long generation) {
        return currentGeneration(regionId) == generation;
    }

    /**
     * True if {@code generation} is at least as new as the highest generation seen for the region.
     * Used to reject stale work arriving from a fenced owner.
     */
    public boolean isAtLeastGeneration(final long regionId, final long generation) {
        return currentGeneration(regionId) >= generation;
    }

    public void removeRegion(final long regionId) {
        regionGenerations.remove(regionId);
    }
}