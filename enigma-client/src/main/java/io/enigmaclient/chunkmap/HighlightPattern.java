package io.enigmaclient.chunkmap;

import java.util.Arrays;

/**
 * Builds the 16x16 per-block colour array Xaero expects from a chunk highlighter.
 *
 * <p>The array is indexed as {@code (blockZ << 4) | blockX} to match
 * {@code AbstractHighlighter#setResult}.</p>
 */
public final class HighlightPattern {

    private HighlightPattern() {
    }

    public static int[] solid(int[] target, int color) {
        Arrays.fill(target, color);
        return target;
    }

    public static int[] dashed(int[] target, int color) {
        Arrays.fill(target, 0);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                if (((x + z) & 3) < 2) {
                    target[(z << 4) | x] = color;
                }
            }
        }
        return target;
    }
}
