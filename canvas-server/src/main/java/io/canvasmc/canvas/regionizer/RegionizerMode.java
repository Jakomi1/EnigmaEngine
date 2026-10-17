package io.canvasmc.canvas.regionizer;

/**
 * Runtime switch for Enigma's additions to Folia's regionizer.
 *
 * <p>{@link Mode#AUTO} retains the adaptive Enigma split, merge and maintenance behaviour.
 * {@link Mode#FOLIA} leaves those decisions to unmodified Folia behaviour. The active value is
 * persisted by the Enigma regionizer configuration and can be changed immediately in-game.</p>
 */
public final class RegionizerMode {

    public enum Mode {
        AUTO,
        FOLIA
    }

    private static volatile Mode mode = Mode.AUTO;

    private RegionizerMode() {
    }

    public static Mode get() {
        return mode;
    }

    public static void set(final Mode newMode) {
        mode = newMode;
    }

    public static boolean isAuto() {
        return mode == Mode.AUTO;
    }
}
