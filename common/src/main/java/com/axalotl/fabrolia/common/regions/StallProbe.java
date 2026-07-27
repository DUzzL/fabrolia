package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TEMPORARY diagnostic for task #20 (post-freeze permanent duper breakage).
 * Gated on {@code -Dfabrolia.debug.stallProbe=true}; default off — a single
 * static boolean on the heartbeat path, no thread, no allocation.
 *
 * <p>Three duties while enabled:
 * <ul>
 *   <li>a daemon watchdog samples the main-tick heartbeat every 100 ms; any
 *       gap &gt; 500 ms logs a PAUSE line with the main thread's stack, the
 *       save-phase flag, GC time delta over the gap, and queue depths;</li>
 *   <li>on resume after a PAUSE, island snapshots run every 20 wall ticks for
 *       400 wall ticks: per island {@code id, gen, L, W, L-W, deadline-now,
 *       missedDeadlines, claim, pendingArrivals, chain anchors};</li>
 *   <li>a save-phase flag exported for the PAUSE line (set by the save wrap).</li>
 * </ul>
 */
public final class StallProbe {

    public static final boolean ENABLED = Boolean.getBoolean("fabrolia.debug.stallProbe");
    private static final Logger LOG = LoggerFactory.getLogger("Fabrolia-StallProbe");

    private static volatile long lastBeatNanos;
    private static volatile long lastBeatWall;
    private static volatile ServerLevel beatLevel;
    private static volatile Thread mainThread;
    public static volatile boolean saveActive;
    private static volatile long snapshotUntilWall = -1;
    private static long lastSnapshotWall = -1;
    private static Thread watchdog;

    private StallProbe() {}

    /** Once per main tick (batch end). Starts the watchdog lazily. */
    public static void heartbeat(ServerLevel level) {
        if (!ENABLED) {
            return;
        }
        long now = System.nanoTime();
        long wall = level.getGameTime();
        beatLevel = level;
        mainThread = Thread.currentThread();
        lastBeatNanos = now;
        lastBeatWall = wall;
        if (watchdog == null) {
            startWatchdog();
        }
        long until = snapshotUntilWall;
        if (until >= 0 && wall <= until && wall - lastSnapshotWall >= 20) {
            lastSnapshotWall = wall;
            snapshotIslands("POST-RESUME");
            if (wall >= until) {
                snapshotUntilWall = -1;
            }
        }
    }

    private static synchronized void startWatchdog() {
        if (watchdog != null) {
            return;
        }
        watchdog = new Thread(() -> {
            long pauseStartNanos = 0;
            long gcAtPauseMs = -1;
            while (true) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
                long beat = lastBeatNanos;
                if (beat == 0) {
                    continue;
                }
                long gap = System.nanoTime() - beat;
                if (gap > 500_000_000L && pauseStartNanos == 0) {
                    pauseStartNanos = beat;
                    gcAtPauseMs = gcMillis();
                    dumpPause(gap);
                } else if (gap <= 500_000_000L && pauseStartNanos != 0) {
                    long pausedMs = (System.nanoTime() - pauseStartNanos) / 1_000_000L;
                    LOG.info("[stallprobe] RESUME pausedMs={} gcDeltaMs={} wall={}",
                            pausedMs, gcMillis() - gcAtPauseMs, lastBeatWall);
                    pauseStartNanos = 0;
                    snapshotIslands("AT-RESUME");
                    lastSnapshotWall = lastBeatWall;
                    snapshotUntilWall = lastBeatWall + 400;
                }
            }
        }, "Fabrolia-StallProbe");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static void dumpPause(long gapNanos) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("[stallprobe] PAUSE gapMs=").append(gapNanos / 1_000_000L)
                .append(" wall=").append(lastBeatWall)
                .append(" saveActive=").append(saveActive)
                .append(" ptmPending=").append(
                        com.axalotl.fabrolia.common.parallelised.utils.PortalTeleportationManager.pendingCount());
        Thread m = mainThread;
        if (m != null) {
            sb.append("\n  main(").append(m.getState()).append("):");
            StackTraceElement[] st = m.getStackTrace();
            for (int i = 0; i < st.length && i < 24; i++) {
                sb.append("\n    at ").append(st[i]);
            }
        }
        LOG.info("{}", sb);
        snapshotIslands("IN-PAUSE");
    }

    private static void snapshotIslands(String tag) {
        ServerLevel any = beatLevel;
        if (any == null) {
            return;
        }
        long now = System.nanoTime();
        for (ServerLevel level : any.getServer().getAllLevels()) {
            RegionizedServer rs = RegionScheduler.forLevel(level);
            if (rs == null || !rs.asyncActive) {
                continue;
            }
            long wall = level.getGameTime();
            for (IslandRegistry.PIsland p : rs.islandRegistry.islands()) {
                String chains;
                int pend;
                synchronized (p.pendingArrivals) {
                    pend = p.pendingArrivals.size();
                    StringBuilder cb = new StringBuilder(48);
                    for (var e : p.arrivalChains.entrySet()) {
                        if (cb.length() > 0) cb.append(' ');
                        cb.append(e.getKey()).append(":").append(e.getValue()[0])
                                .append("@").append(e.getValue()[1]);
                        if (cb.length() > 120) break;
                    }
                    chains = cb.toString();
                }
                LOG.info("[stallprobe] {} {} island#{} gen={} L={} W={} LmW={} deadlineMs={} missed={} claim={} pend={} chains=[{}]",
                        tag, level.dimension().identifier(), p.id, p.gen,
                        p.logicalTick, wall, p.logicalTick - wall,
                        p.nextDeadlineNanos == 0 ? 0 : (p.nextDeadlineNanos - now) / 1_000_000L,
                        p.missedDeadlines, p.unitClaim.get(), pend, chains);
            }
        }
    }

    private static long gcMillis() {
        long t = 0;
        for (java.lang.management.GarbageCollectorMXBean b
                : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            long v = b.getCollectionTime();
            if (v > 0) {
                t += v;
            }
        }
        return t;
    }
}
