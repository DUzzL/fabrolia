package com.axalotl.fabrolia.common.regions;

/**
 * A monotonic, cached counter-rate window.
 *
 * <p>Reads inside the minimum window are deliberately idempotent: they return
 * the last completed sample instead of dividing a handful of counter changes
 * by a caller-dependent, sub-second interval.  This matters for TPS because
 * the HUD, list command and top command can all read the same island in one
 * server tick.</p>
 */
final class TpsWindow {

    static final long ONE_SECOND_NANOS = 1_000_000_000L;

    private final long minimumWindowNanos;
    private final double initialValue;
    private long baselineCount;
    private long baselineNanos;
    private double value;
    private boolean initialized;

    TpsWindow(long minimumWindowNanos, double initialValue) {
        if (minimumWindowNanos <= 0) {
            throw new IllegalArgumentException("minimumWindowNanos must be positive");
        }
        this.minimumWindowNanos = minimumWindowNanos;
        this.initialValue = initialValue;
        this.value = initialValue;
    }

    /**
     * Observe {@code count} at {@code nowNanos}.
     *
     * @return true only when a complete new rate window was committed
     */
    boolean update(long count, long nowNanos) {
        if (!initialized || nowNanos < baselineNanos || count < baselineCount) {
            baselineCount = count;
            baselineNanos = nowNanos;
            value = initialValue;
            initialized = true;
            return false;
        }

        long elapsed = nowNanos - baselineNanos;
        if (elapsed < minimumWindowNanos) {
            return false;
        }

        value = (count - baselineCount) * 1_000_000_000.0 / elapsed;
        baselineCount = count;
        baselineNanos = nowNanos;
        return true;
    }

    double value() {
        return value;
    }
}
