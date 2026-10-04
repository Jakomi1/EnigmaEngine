package io.canvasmc.canvas.regionizer;

/**
 * Combines player density and region load (MSPT) into a single split decision.
 *
 * <p>A region is split when either the player density or the measured load crosses its
 * threshold. When both cross at the same time the region is marked isolated so that the
 * regionizer separates it from its neighbours first.</p>
 */
public final class RegionizerLagPriority {

    public enum Priority {
        NORMAL, HIGH, CRITICAL
    }

    public static final class Decision {

        private final long regionId;
        private final double density;
        private final double loadRatio;
        private final int splitFactor;
        private final boolean isolate;
        private final Priority priority;

        Decision(
            final long regionId,
            final double density,
            final double loadRatio,
            final int splitFactor,
            final boolean isolate,
            final Priority priority
        ) {
            this.regionId = regionId;
            this.density = density;
            this.loadRatio = loadRatio;
            this.splitFactor = splitFactor;
            this.isolate = isolate;
            this.priority = priority;
        }

        public long regionId() {
            return this.regionId;
        }

        public double density() {
            return this.density;
        }

        public double loadRatio() {
            return this.loadRatio;
        }

        public int splitFactor() {
            return this.splitFactor;
        }

        public boolean isolate() {
            return this.isolate;
        }

        public Priority priority() {
            return this.priority;
        }
    }

    private static final double DEFAULT_LOAD_SHARE_LIMIT = 0.5;

    private final double densityThreshold;
    private final double loadThreshold;
    private final int maxSplitFactor;
    private final double loadShareLimit;

    public RegionizerLagPriority(
        final double densityThreshold,
        final double loadThreshold,
        final int maxSplitFactor
    ) {
        this(densityThreshold, loadThreshold, maxSplitFactor, DEFAULT_LOAD_SHARE_LIMIT);
    }

    public RegionizerLagPriority(
        final double densityThreshold,
        final double loadThreshold,
        final int maxSplitFactor,
        final double loadShareLimit
    ) {
        this.densityThreshold = densityThreshold <= 0.0 ? 4.0 : densityThreshold;
        this.loadThreshold = loadThreshold <= 0.0 ? 1.0 : loadThreshold;
        this.maxSplitFactor = Math.clamp(maxSplitFactor, 1, 8);
        this.loadShareLimit = loadShareLimit <= 0.0 ? DEFAULT_LOAD_SHARE_LIMIT : loadShareLimit;
    }

    public static RegionizerLagPriority from(final RegionizerSettings settings) {
        return new RegionizerLagPriority(4.0, settings.splitHighLoadMspt(), settings.splitMaxSections());
    }

    /**
     * @param regionId    opaque region identifier used for bookkeeping
     * @param players     number of players currently inside the region
     * @param chunks      number of loaded chunks inside the region
     * @param regionMspt  average milliseconds of tick time spent in the region
     * @param globalMspt  average milliseconds of tick time spent server wide
     */
    public Decision decide(
        final long regionId,
        final int players,
        final int chunks,
        final double regionMspt,
        final double globalMspt
    ) {
        final double density = chunks <= 0 ? 0.0 : (double) players / (double) chunks;
        final double densityRatio = density / this.densityThreshold;
        final double densityWorst = Math.max(densityRatio, 1.0);

        // Absolute budget: a region is "hot" once its own tick cost reaches the
        // configured mspt budget, or once it hogs an unreasonable share of the tick.
        final double absoluteRatio = regionMspt / this.loadThreshold;
        final double shareRatio = globalMspt <= 0.0 ? 0.0 : (regionMspt / globalMspt) / this.loadShareLimit;
        final double loadRatio = Math.max(absoluteRatio, shareRatio);

        final boolean densityHot = densityRatio >= 1.0;
        final boolean loadHot = loadRatio >= 1.0;

        if (!densityHot && !loadHot) {
            return new Decision(regionId, density, loadRatio, 1, false, Priority.NORMAL);
        }

        final double worst = Math.max(densityWorst, loadRatio);
        final int wanted = (int) Math.min(Math.ceil(worst), (double) this.maxSplitFactor);
        final int splitFactor = Math.clamp(wanted, 1, this.maxSplitFactor);

        // Both signals hot means the region hurts its neighbours, so cut it loose first.
        final boolean isolate = loadHot && densityHot;
        final Priority priority = isolate ? Priority.CRITICAL : (loadHot ? Priority.HIGH : Priority.NORMAL);

        return new Decision(regionId, density, loadRatio, splitFactor, isolate, priority);
    }
}