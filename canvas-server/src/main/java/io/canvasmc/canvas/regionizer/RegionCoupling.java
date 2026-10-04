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