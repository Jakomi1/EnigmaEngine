package io.canvasmc.canvas.regionizer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class RegionCoupling {

    private static final double NORMALIZATION = 40.0D;
    private static final ConcurrentHashMap<Long, AtomicLong> COUNTERS = new ConcurrentHashMap<>();

    private RegionCoupling() {
    }

    public static void onCrossRegion(final long regionA, final long regionB) {
        if (regionA == regionB) {
            return;
        }
        final long key = pairKey(regionA, regionB);
        COUNTERS.computeIfAbsent(key, (_) -> new AtomicLong()).incrementAndGet();
    }

    public static double getNormalized(final long regionA, final long regionB) {
        if (regionA == regionB) {
            return 0.0D;
        }
        final AtomicLong counter = COUNTERS.get(pairKey(regionA, regionB));
        return counter == null ? 0.0D : Math.min(1.0D, counter.get() / NORMALIZATION);
    }

    public static int size() {
        return COUNTERS.size();
    }

    /**
     * Drops every pair counter that references the given region id. Called when a region dies so a
     * recycled id never observes stale pair statistics. The pair key packs both ids into 32 bits
     * each, which stays exact because the id free list keeps region ids small.
     */
    public static void clearRegion(final long regionId) {
        final int id = (int) regionId;
        COUNTERS.keySet().removeIf(key -> {
            final int a = (int) (long) key;
            final int b = (int) (key >>> 32);
            return a == id || b == id;
        });
    }

    public static void decay() {
        COUNTERS.forEach((key, value) -> {
            final long before = value.get();
            if (before <= 1) {
                COUNTERS.remove(key, value);
            } else {
                value.set(before >> 1);
            }
        });
    }

    private static long pairKey(final long a, final long b) {
        // ensure ordering to make the pair key commutative
        if (a < b) {
            return a | (b << 32);
        }
        return b | (a << 32);
    }
}