package io.canvasmc.canvas.regionizer;

import io.canvasmc.canvas.GlobalConfiguration;

public final class RegionizerSettings {

    private final boolean enabled;
    private final double mergeOverlapThreshold;
    private final int mergeMinRegionSections;
    private final double couplingWeight;
    private final double overlapWeight;
    private final long mergeCooldownMillis;
    private final long splitCooldownMillis;
    private final int splitMinSize;
    private final int splitMaxSections;
    private final double splitHighLoadMspt;
    private final int splitLowLoadPercent;
    private final int splitIdleSeconds;
    private final int splitPassIntervalTicks;
    private final boolean instantUnloadEnabled;
    private final boolean instantUnloadActiveRegions;
    private final int instantUnloadIdleRadius;
    private final int instantUnloadMaxChunksPerPass;
    private final int aggression;
    private final int targetSections;
    private final boolean partitionEnabled;
    private final int splitMaxRegions;
    private final int partitionMinSections;
    private final double partitionSlack;
    private final int partitionRefinementPasses;

    private static volatile RegionizerSettings INSTANCE = fromGlobalConfiguration();

    public RegionizerSettings(
        final boolean enabled,
        final double mergeOverlapThreshold,
        final int mergeMinRegionSections,
        final double couplingWeight,
        final double overlapWeight,
        final long mergeCooldownMillis,
        final long splitCooldownMillis,
        final int splitMinSize,
        final int splitMaxSections,
        final double splitHighLoadMspt,
        final int splitLowLoadPercent,
        final int splitIdleSeconds,
        final int splitPassIntervalTicks,
        final boolean instantUnloadEnabled,
        final boolean instantUnloadActiveRegions,
        final int instantUnloadIdleRadius,
        final int instantUnloadMaxChunksPerPass
    ) {
        this(
            enabled,
            mergeOverlapThreshold,
            mergeMinRegionSections,
            couplingWeight,
            overlapWeight,
            mergeCooldownMillis,
            splitCooldownMillis,
            splitMinSize,
            splitMaxSections,
            splitHighLoadMspt,
            splitLowLoadPercent,
            splitIdleSeconds,
            splitPassIntervalTicks,
            instantUnloadEnabled,
            instantUnloadActiveRegions,
            instantUnloadIdleRadius,
            instantUnloadMaxChunksPerPass,
            100,
            Math.max(1, mergeMinRegionSections)
        );
    }

    public RegionizerSettings(
        final boolean enabled,
        final double mergeOverlapThreshold,
        final int mergeMinRegionSections,
        final double couplingWeight,
        final double overlapWeight,
        final long mergeCooldownMillis,
        final long splitCooldownMillis,
        final int splitMinSize,
        final int splitMaxSections,
        final double splitHighLoadMspt,
        final int splitLowLoadPercent,
        final int splitIdleSeconds,
        final int splitPassIntervalTicks,
        final boolean instantUnloadEnabled,
        final boolean instantUnloadActiveRegions,
        final int instantUnloadIdleRadius,
        final int instantUnloadMaxChunksPerPass,
        final int aggression,
        final int targetSections
    ) {
        this(
            enabled,
            mergeOverlapThreshold,
            mergeMinRegionSections,
            couplingWeight,
            overlapWeight,
            mergeCooldownMillis,
            splitCooldownMillis,
            splitMinSize,
            splitMaxSections,
            splitHighLoadMspt,
            splitLowLoadPercent,
            splitIdleSeconds,
            splitPassIntervalTicks,
            instantUnloadEnabled,
            instantUnloadActiveRegions,
            instantUnloadIdleRadius,
            instantUnloadMaxChunksPerPass,
            aggression,
            targetSections,
            true,
            16,
            1,
            0.15D,
            2
        );
    }

    public RegionizerSettings(
        final boolean enabled,
        final double mergeOverlapThreshold,
        final int mergeMinRegionSections,
        final double couplingWeight,
        final double overlapWeight,
        final long mergeCooldownMillis,
        final long splitCooldownMillis,
        final int splitMinSize,
        final int splitMaxSections,
        final double splitHighLoadMspt,
        final int splitLowLoadPercent,
        final int splitIdleSeconds,
        final int splitPassIntervalTicks,
        final boolean instantUnloadEnabled,
        final boolean instantUnloadActiveRegions,
        final int instantUnloadIdleRadius,
        final int instantUnloadMaxChunksPerPass,
        final int aggression,
        final int targetSections,
        final boolean partitionEnabled,
        final int splitMaxRegions,
        final int partitionMinSections,
        final double partitionSlack,
        final int partitionRefinementPasses
    ) {
        this.enabled = enabled;
        this.mergeOverlapThreshold = mergeOverlapThreshold;
        this.mergeMinRegionSections = mergeMinRegionSections;
        this.couplingWeight = couplingWeight;
        this.overlapWeight = overlapWeight;
        this.mergeCooldownMillis = mergeCooldownMillis;
        this.splitCooldownMillis = splitCooldownMillis;
        this.splitMinSize = splitMinSize;
        this.splitMaxSections = splitMaxSections;
        this.splitHighLoadMspt = splitHighLoadMspt;
        this.splitLowLoadPercent = splitLowLoadPercent;
        this.splitIdleSeconds = splitIdleSeconds;
        this.splitPassIntervalTicks = splitPassIntervalTicks;
        this.instantUnloadEnabled = instantUnloadEnabled;
        this.instantUnloadActiveRegions = instantUnloadActiveRegions;
        this.instantUnloadIdleRadius = instantUnloadIdleRadius;
        this.instantUnloadMaxChunksPerPass = instantUnloadMaxChunksPerPass;
        this.aggression = Math.max(0, Math.min(100, aggression));
        this.targetSections = Math.max(1, targetSections);
        this.partitionEnabled = partitionEnabled;
        this.splitMaxRegions = Math.max(2, splitMaxRegions);
        this.partitionMinSections = Math.max(1, partitionMinSections);
        this.partitionSlack = Math.max(0.0D, Math.min(1.0D, partitionSlack));
        this.partitionRefinementPasses = Math.max(0, partitionRefinementPasses);
    }

    public static RegionizerSettings fromGlobalConfiguration() {
        final GlobalConfiguration.Regionizer c = GlobalConfiguration.getInstance().regionizer;
        return new RegionizerSettings(
            c.enabled,
            c.mergeOverlapThreshold,
            c.mergeMinRegionSections,
            c.couplingWeight,
            c.overlapWeight,
            c.mergeCooldownMillis,
            c.splitCooldownMillis,
            c.splitMinSize,
            c.splitMaxSections,
            c.splitHighLoadMspt,
            c.splitLowLoadPercent,
            c.splitIdleSeconds,
            c.splitPassIntervalTicks,
            c.instantUnloadEnabled,
            c.instantUnloadActiveRegions,
            c.instantUnloadIdleRadius,
            c.instantUnloadMaxChunksPerPass,
            100,
            2,
            c.partitionEnabled,
            c.splitMaxRegions,
            c.partitionMinSections,
            c.partitionSlack,
            c.partitionRefinementPasses
        );
    }

    public static RegionizerSettings get() {
        return INSTANCE;
    }

    public static void set(final RegionizerSettings settings) {
        INSTANCE = settings;
    }

    public static RegionizerSettings forWorld(
        final RegionizerSettings base,
        final int worldMergeMinRegionSections,
        final double worldMergeThreshold
    ) {
        return forWorld(base, worldMergeMinRegionSections, 100);
    }

    /**
     * Creates world settings adapting all thresholds based strictly on aggression.
     *
     * @param base base settings from configuration
     * @param worldRegionizerSections configured sections per region target (from load config)
     * @param aggression aggression percentage (0 - 100)
     * @return dynamic world regionizer settings
     */
    public static RegionizerSettings forWorld(
        final RegionizerSettings base,
        final int worldRegionizerSections,
        final int aggression
    ) {
        final int aggr = Math.max(0, Math.min(100, aggression));
        final double a = aggr / 100.0D;

        final int baseTarget = worldRegionizerSections > 0 ? worldRegionizerSections : 2;

        // At 100% aggression: target is 1 section per region (maximum parallelization).
        // At 0% aggression: conservative sizing.
        final int targetSections;
        if (aggr >= 90) {
            targetSections = 1;
        } else {
            targetSections = Math.max(1, (int) Math.round(baseTarget + (1.0D - a) * Math.max(2, baseTarget * 2)));
        }

        // splitMinSize: minimum sections a region must have before it is considered for splitting.
        // At 100% aggression: 2 sections (1 section cannot be split).
        // At 0% aggression: 32 sections.
        final int splitMinSize = Math.max(2, (int) Math.round(32 - 30 * a));

        // splitMaxSections: hard split limit.
        // At 100% aggression: 2 sections (any region with > 2 sections is hard-split).
        // At 0% aggression: 128 sections.
        final int splitMaxSections = Math.max(splitMinSize, (int) Math.round(128 - (128 - Math.max(2, targetSections * 2)) * a));

        // splitHighLoadMspt:
        // At 100% aggression: 0.5 MSPT (any loaded region can split to spread load across threads).
        // At 0% aggression: 15.0 MSPT.
        final double splitHighLoadMspt = 15.0D * (1.0D - a) + 0.5D * a;

        // mergeMinRegionSections:
        // At 100% aggression: 1 section (regions never auto-merge just for being small).
        // At 0% aggression: 16 sections.
        final int mergeMinRegionSections = Math.max(1, (int) Math.round(16 - 15 * a));

        // mergeOverlapThreshold:
        // At 100% aggression: 0.90 (refuses almost all merges unless coupling is extreme).
        // At 0% aggression: 0.30.
        final double mergeOverlapThreshold = 0.30D + 0.60D * a;

        final long mergeCooldownMillis = base.mergeCooldownMillis();
        final long splitCooldownMillis = base.splitCooldownMillis();

        return new RegionizerSettings(
            base.enabled(),
            mergeOverlapThreshold,
            mergeMinRegionSections,
            base.couplingWeight(),
            base.overlapWeight(),
            mergeCooldownMillis,
            splitCooldownMillis,
            splitMinSize,
            splitMaxSections,
            splitHighLoadMspt,
            base.splitLowLoadPercent(),
            base.splitIdleSeconds(),
            base.splitPassIntervalTicks(),
            base.instantUnloadEnabled(),
            base.instantUnloadActiveRegions(),
            base.instantUnloadIdleRadius(),
            base.instantUnloadMaxChunksPerPass(),
            aggr,
            targetSections,
            base.partitionEnabled(),
            base.splitMaxRegions(),
            base.partitionMinSections(),
            base.partitionSlack(),
            base.partitionRefinementPasses()
        );
    }

    public boolean enabled() {
        return this.enabled;
    }

    public double mergeOverlapThreshold() {
        return this.mergeOverlapThreshold;
    }

    public int mergeMinRegionSections() {
        return this.mergeMinRegionSections;
    }

    public double couplingWeight() {
        return this.couplingWeight;
    }

    public double overlapWeight() {
        return this.overlapWeight;
    }

    public long mergeCooldownMillis() {
        return this.mergeCooldownMillis;
    }

    public long splitCooldownMillis() {
        return this.splitCooldownMillis;
    }

    public int splitMinSize() {
        return this.splitMinSize;
    }

    public int splitMaxSections() {
        return this.splitMaxSections;
    }

    public double splitHighLoadMspt() {
        return this.splitHighLoadMspt;
    }

    public int splitLowLoadPercent() {
        return this.splitLowLoadPercent;
    }

    public int splitIdleSeconds() {
        return this.splitIdleSeconds;
    }

    public int splitPassIntervalTicks() {
        return this.splitPassIntervalTicks;
    }

    public boolean instantUnloadEnabled() {
        return this.instantUnloadEnabled;
    }

    public boolean instantUnloadActiveRegions() {
        return this.instantUnloadActiveRegions;
    }

    public int instantUnloadIdleRadius() {
        return this.instantUnloadIdleRadius;
    }

    public int instantUnloadMaxChunksPerPass() {
        return this.instantUnloadMaxChunksPerPass;
    }

    public int aggression() {
        return this.aggression;
    }

    public int targetSections() {
        return this.targetSections;
    }

    public boolean partitionEnabled() {
        return this.partitionEnabled;
    }

    public int splitMaxRegions() {
        return this.splitMaxRegions;
    }

    public int partitionMinSections() {
        return this.partitionMinSections;
    }

    public double partitionSlack() {
        return this.partitionSlack;
    }

    public int partitionRefinementPasses() {
        return this.partitionRefinementPasses;
    }
}