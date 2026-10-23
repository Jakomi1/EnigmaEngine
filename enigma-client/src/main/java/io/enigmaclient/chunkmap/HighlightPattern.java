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

    /**
     * Converts standard ARGB (A<<24 | R<<16 | G<<8 | B) to Xaero's format
     * (B<<24 | G<<16 | R<<8 | A).
     */
    private static int toXaeroColor(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (b << 24) | (g << 16) | (r << 8) | a;
    }

    /** Fills the whole chunk, for chunks the server reports as actively ticking. */
    public static int[] solid(int[] target, int color) {
        Arrays.fill(target, toXaeroColor(color));
        return target;
    }

    /** Dashed outline along the chunk border, for chunks the server sent to this player. */
    public static int[] border(int[] target, int color) {
        int xaeroColor = toXaeroColor(color);
        Arrays.fill(target, 0);
        for (int i = 0; i < 16; i++) {
            // Two blocks on, two blocks off, so the dashes stay readable at low zoom levels.
            if (((i >> 1) & 1) != 0) {
                continue;
            }
            target[(0 << 4) | i] = xaeroColor;
            target[(15 << 4) | i] = xaeroColor;
            target[(i << 4) | 0] = xaeroColor;
            target[(i << 4) | 15] = xaeroColor;
        }
        return target;
    }
}
