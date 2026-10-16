package io.canvasmc.canvas.regionizer;

/**
 * Converts a region's observed load into the section target used by a split.
 *
 * <p>Section topology is still partitioned by {@link BalancedPartitioner}; this class only
 * determines how many connected children are needed. Keeping that decision separate makes the
 * policy testable and prevents an overloaded region from being limited to its static chunk target.</p>
 */
public final class RegionSplitPlanner {

    /** Two active players per child preserves useful locality without allowing player hotspots. */
    private static final int TARGET_PLAYERS_PER_REGION = 2;

    private RegionSplitPlanner() {
    }

    /**
     * Chooses a target section count which produces enough children for the current region load.
     * A child is planned for roughly every two active players and a region above the split
     * threshold is reduced toward half that threshold, leaving headroom for normal tick variance.
     */
    public static int targetSections(
        final int sectionCount,
        final int configuredTargetSections,
        final int maxRegions,
        final int players,
        final double mspt,
        final double splitHighLoadMspt
    ) {
        if (sectionCount <= 1) {
            return 1;
        }

        final int configuredTarget = Math.max(1, configuredTargetSections);
        final int cappedRegions = Math.max(2, Math.min(sectionCount, maxRegions));
        final int sectionParts = ceilDiv(sectionCount, configuredTarget);
        final int playerParts = ceilDiv(Math.max(1, players), TARGET_PLAYERS_PER_REGION);

        int tickParts = 1;
        if (splitHighLoadMspt > 0.0D && mspt >= splitHighLoadMspt) {
            final double desiredMspt = Math.max(0.5D, splitHighLoadMspt / 2.0D);
            tickParts = Math.max(2, (int)Math.ceil(mspt / desiredMspt));
        }

        final int desiredParts = Math.min(cappedRegions, Math.max(sectionParts, Math.max(playerParts, tickParts)));
        return Math.max(1, ceilDiv(sectionCount, desiredParts));
    }

    private static int ceilDiv(final int numerator, final int denominator) {
        return (numerator + denominator - 1) / denominator;
    }
}
