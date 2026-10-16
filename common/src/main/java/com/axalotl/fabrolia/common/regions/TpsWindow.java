package com.axalotl.fabrolia.common.regions;

/**
 * A monotonic, cached counter-rate window.
 *
 * <p>Reads inside the minimum window are deliberately idempotent: they return
 * the last completed sample instead of dividing a handful of counter changes
 * by a caller-dependent, sub-second interval.  This matters for TPS because
 * the HUD, list command and top command can all read the same island in one
 * server tick.</p>
 *
 * <p>A window commits at most once per minimum window but may average over
 * the last {@code spanWindows} committed windows. Ticks complete on a 50 ms
 * grid, so a one-second window holds 20 or 21 of them and reads 19–21 TPS
 * for a steady 20; over five windows the same step is ±0.2 TPS, while the
 * value still refreshes every second.</p>
 */
final class TpsWindow {

    static final long ONE_SECOND_NANOS = 1_000_000_000L;

    private final long minimumWindowNanos;
    private final double initialValue;
    /** Committed points, oldest first: the rate spans first → last. */
    private final long[] pointCount;
    private final long[] pointNanos;
    private int points;
    private double value;
    private boolean initialized;

    TpsWindow(long minimumWindowNanos, double initialValue) {
        this(minimumWindowNanos, initialValue, 1);
    }

    TpsWindow(long minimumWindowNanos, double initialValue, int spanWindows) {
        if (minimumWindowNanos <= 0) {
            throw new IllegalArgumentException("minimumWindowNanos must be positive");
        }
        if (spanWindows < 1) {
            throw new IllegalArgumentException("spanWindows must be at least 1");
        }
        this.minimumWindowNanos = minimumWindowNanos;
        this.initialValue = initialValue;
        this.value = initialValue;
        this.pointCount = new long[spanWindows + 1];
        this.pointNanos = new long[spanWindows + 1];
    }

    /**
     * Observe {@code count} at {@code nowNanos}.
     *
     * @return true only when a complete new rate window was committed
     */
    boolean update(long count, long nowNanos) {
        long baselineCount = pointCount[points - 1 < 0 ? 0 : points - 1];
        long baselineNanos = pointNanos[points - 1 < 0 ? 0 : points - 1];
        if (!initialized || nowNanos < baselineNanos || count < baselineCount) {
            points = 0;
            push(count, nowNanos);
            value = initialValue;
            initialized = true;
            return false;
        }

        long elapsed = nowNanos - baselineNanos;
        if (elapsed < minimumWindowNanos) {
            return false;
        }

        push(count, nowNanos);
        value = (count - pointCount[0]) * 1_000_000_000.0 / (nowNanos - pointNanos[0]);
        return true;
    }

    private void push(long count, long nowNanos) {
        if (points == pointCount.length) {
            System.arraycopy(pointCount, 1, pointCount, 0, points - 1);
            System.arraycopy(pointNanos, 1, pointNanos, 0, points - 1);
            points--;
        }
        pointCount[points] = count;
        pointNanos[points] = nowNanos;
        points++;
    }

    double value() {
        return value;
    }
}
