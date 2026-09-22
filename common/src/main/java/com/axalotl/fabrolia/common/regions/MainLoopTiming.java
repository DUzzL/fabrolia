package com.axalotl.fabrolia.common.regions;

/** Physical-Main-only observer, owned by one server instance. Never grants work. */
public final class MainLoopTiming {
    public interface Access {
        MainLoopTiming fabrolia$mainLoopTiming();
    }

    private final long[] completedLoops = new long[100];
    private final TpsWindow tps = new TpsWindow(TpsWindow.ONE_SECOND_NANOS);
    private long totalNanos;
    private int samples;
    private int cursor;
    private long startedNanos;
    private long idleNanos;
    private boolean running;
    private int deadlineDepth;
    private boolean topLevelWait;
    private int pollDepth;

    public void beginLoop(long nowNanos) {
        startedNanos = nowNanos;
        idleNanos = 0;
        running = true;
    }

    /** Only called after the exact outer wait returns normally. */
    public void completeLoop(long nowNanos) {
        if (!running) return;
        running = false;
        long work = Math.max(0, nowNanos - startedNanos - idleNanos);
        totalNanos += work - completedLoops[cursor];
        completedLoops[cursor] = work;
        cursor = (cursor + 1) % completedLoops.length;
        if (samples < completedLoops.length) samples++;
    }

    public void enterDeadlineWait() { deadlineDepth++; }
    public void exitDeadlineWait() { deadlineDepth--; }
    public void enterPoll() { pollDepth++; }
    public void enterTopLevelWait() { topLevelWait = true; }
    public void exitTopLevelWait() { topLevelWait = false; }
    public void exitPoll() { pollDepth--; }

    /** Nested task/dependency parks are required work, not deadline idle. */
    public boolean isDeadlineIdlePark() {
        return running && topLevelWait && deadlineDepth == 1 && pollDepth == 0;
    }

    public void recordDeadlineIdlePark(long elapsedNanos) {
        idleNanos += Math.max(0, elapsedNanos);
    }

    public boolean hasCompletedLoop() { return samples != 0; }

    public long meanWorkNanos() {
        return samples == 0 ? -1 : totalNanos / samples;
    }

    /** Required work so far, separate from the mean of completed loops. */
    public long activeWorkNanos(long nowNanos) {
        return running ? Math.max(0, nowNanos - startedNanos - idleNanos) : -1;
    }

    boolean updateTps(long count, long nowNanos) { return tps.update(count, nowNanos); }
    double tps() { return tps.value(); }
}
