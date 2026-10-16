package io.canvasmc.canvas.util;

/**
 * Gates player logins during server startup.
 *
 * <p>After a (re)start the world and the regionizer need some time to warm up. Joining during that
 * window can leave already-loaded entities (for example villagers) unsupported for a moment, which
 * lets them fall through the world. To avoid this, logins are refused until the configured startup
 * delay has elapsed, measured from the moment the server starts accepting connections.</p>
 */
public final class JoinGate {

    private static volatile long readyNanos = -1L;

    private JoinGate() {
    }

    /**
     * Records the point at which the server is able to accept player connections. Called once during
     * startup, right before the connection listener begins accepting connections.
     */
    public static void markReady() {
        readyNanos = System.nanoTime();
    }

    /**
     * @return the number of whole seconds a login should still be refused, or {@code 0} if logins are
     * allowed (the gate is disabled, has not been armed yet, or the delay has already elapsed).
     */
    public static long remainingSeconds() {
        final long ready = readyNanos;
        if (ready < 0L) {
            return 0L;
        }

        final long delaySeconds = io.canvasmc.canvas.GlobalConfiguration.getInstance().startup.joinDelaySeconds;
        if (delaySeconds <= 0L) {
            return 0L;
        }

        final long remainingNanos = delaySeconds * 1_000_000_000L - (System.nanoTime() - ready);
        return remainingNanos <= 0L ? 0L : (remainingNanos + 999_999_999L) / 1_000_000_000L;
    }
}
