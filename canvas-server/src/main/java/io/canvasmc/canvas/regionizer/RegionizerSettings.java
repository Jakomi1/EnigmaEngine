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
            c.instantUnloadMaxChunksPerPass
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
        final int sections = worldMergeMinRegionSections <= 0 ? base.mergeMinRegionSections : worldMergeMinRegionSections;
        final double threshold = worldMergeThreshold < 0.0D ? base.mergeOverlapThreshold : worldMergeThreshold;
        if (sections == base.mergeMinRegionSections && threshold == base.mergeOverlapThreshold) {
            return base;
        }
        return new RegionizerSettings(
            base.enabled,
            threshold,
            sections,
            base.couplingWeight,
            base.overlapWeight,
            base.mergeCooldownMillis,
            base.splitCooldownMillis,
            base.splitMinSize,
            base.splitMaxSections,
            base.splitHighLoadMspt,
            base.splitLowLoadPercent,
            base.splitIdleSeconds,
            base.splitPassIntervalTicks,
            base.instantUnloadEnabled,
            base.instantUnloadActiveRegions,
            base.instantUnloadIdleRadius,
            base.instantUnloadMaxChunksPerPass
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
}