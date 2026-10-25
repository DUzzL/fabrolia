package com.axalotl.fabrolia.common.parallelised.utils;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Per-simulation-tick natural-spawn cadence instrumentation (2026-07-21 refill-rate
 * investigation). Vanilla parity is not only the cap value — it is the number of
 * eligible-chunk visits, pack attempts and accepted spawns per tick. This ring
 * records, per WALL tick: spawn passes (arm executions), unique/duplicate chunk
 * visits (duplicates = two passes touching one chunk in the same wall tick — the
 * overlap class), per-category candidate checks and accepted spawns.
 *
 * <p>Lock-free: one row per wall tick (ring of 128), all counters atomic; a row is
 * claimed by CAS on its tick stamp and zeroed by the claimer. Readers (the
 * spawnrate command) snapshot rows non-blockingly. Duplicate detection uses a
 * per-row hash of visited chunk keys (open probing over a fixed table — collisions
 * over-report duplicates at worst, never under-report; table sized 4096 ≫ typical
 * ~2.5k spawning chunks).
 *
 * <p>Off until the first {@code spawnrate} report: the hooks are then a single
 * volatile read. Recording every spawn pass made every spawning worker share
 * one row of atomics and allocated the 4 MiB visit table at class load. While
 * armed every spawn pass of every worker shares that row again, so
 * {@code spawnrate off} ({@link #disarm()}) switches recording off once the
 * measurement is read.
 */
public final class SpawnRateDiag {

    private SpawnRateDiag() {}

    private static final int RING = 128;
    private static final int CATS = MobCategory.values().length;
    // row layout: [0]=tick stamp, [1]=passes, [2]=visits, [3]=dupVisits,
    // [4..4+CATS)=checks, [4+CATS..4+2*CATS)=accepts
    private static final int ROW = 4 + 2 * CATS;
    private static final int VISIT_TABLE = 4096;

    private static final class Recorder {
        final AtomicLongArray data = new AtomicLongArray(RING * ROW);
        final AtomicLongArray visits = new AtomicLongArray(RING * VISIT_TABLE);

        /** Claim/locate the row for {@code tick}, zeroing it on first touch. */
        int row(long tick) {
            int base = rowBase(tick);
            long cur = data.get(base);
            if (cur != tick + 1) {
                if (data.compareAndSet(base, cur, tick + 1)) {
                    for (int i = 1; i < ROW; i++) data.set(base + i, 0);
                    int vb = (int) (tick & (RING - 1)) * VISIT_TABLE;
                    for (int i = 0; i < VISIT_TABLE; i++) visits.set(vb + i, 0);
                }
            }
            return base;
        }
    }

    /** Null until the first report arms recording; {@link #disarm()} clears it. */
    private static volatile Recorder recorder;

    private static int rowBase(long tick) {
        return (int) (tick & (RING - 1)) * ROW;
    }

    public static void onPass(ServerLevel level) {
        Recorder r = recorder;
        if (r == null) return;
        r.data.incrementAndGet(r.row(wall(level)) + 1);
    }

    public static void onChunkVisit(ServerLevel level, long chunkKey) {
        Recorder r = recorder;
        if (r == null) return;
        long t = wall(level);
        int base = r.row(t);
        r.data.incrementAndGet(base + 2);
        // per-tick duplicate detection: open-probe insert of chunkKey+1
        int vb = (int) (t & (RING - 1)) * VISIT_TABLE;
        long key = chunkKey + 1; // avoid 0 sentinel
        int idx = (int) (mix(chunkKey) & (VISIT_TABLE - 1));
        for (int probe = 0; probe < 32; probe++) {
            long cur = r.visits.get(vb + idx);
            if (cur == key) {
                r.data.incrementAndGet(base + 3);
                return;
            }
            if (cur == 0 && r.visits.compareAndSet(vb + idx, 0, key)) {
                return;
            }
            idx = (idx + 1) & (VISIT_TABLE - 1);
        }
        // table pressure — count as duplicate-unknown rather than lose the signal
        r.data.incrementAndGet(base + 3);
    }

    public static void onCheck(ServerLevel level, MobCategory cat) {
        Recorder r = recorder;
        if (r == null) return;
        r.data.incrementAndGet(r.row(wall(level)) + 4 + cat.ordinal());
    }

    public static void onAccept(ServerLevel level, MobCategory cat) {
        Recorder r = recorder;
        if (r == null) return;
        r.data.incrementAndGet(r.row(wall(level)) + 4 + CATS + cat.ordinal());
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        return z ^ (z >>> 33);
    }

    private static long wall(ServerLevel level) {
        return com.axalotl.fabrolia.common.regions.IslandClock.wallTime(level);
    }

    /** Stops recording and drops the ring; returns whether recording was armed. */
    public static boolean disarm() {
        synchronized (SpawnRateDiag.class) {
            boolean was = recorder != null;
            recorder = null;
            return was;
        }
    }

    /** Last {@code n} recorded ticks (newest first), one row per line. The first
     *  call arms recording and reports that no ticks are recorded yet. */
    public static String report(ServerLevel level, int n) {
        long now = wall(level);
        StringBuilder sb = new StringBuilder(256);
        sb.append("SPAWNRATE wall=").append(now).append(" (last ").append(n).append(" ticks, newest first)");
        Recorder r = recorder;
        if (r == null) {
            synchronized (SpawnRateDiag.class) {
                if (recorder == null) recorder = new Recorder();
            }
            return sb.append("\nrecording started now; run again to read recorded ticks").toString();
        }
        MobCategory[] cats = MobCategory.values();
        for (long t = now; t > now - n && t >= 0; t--) {
            int base = rowBase(t);
            if (r.data.get(base) != t + 1) continue;
            long passes = r.data.get(base + 1);
            long visits = r.data.get(base + 2);
            long dups = r.data.get(base + 3);
            if (passes == 0 && visits == 0) continue;
            sb.append("\nT").append(t).append(" passes=").append(passes)
              .append(" visits=").append(visits).append(" dup=").append(dups);
            for (int c = 0; c < cats.length; c++) {
                long ck = r.data.get(base + 4 + c);
                long ac = r.data.get(base + 4 + CATS + c);
                if (ck != 0 || ac != 0) {
                    sb.append(' ').append(cats[c].getName()).append('=')
                      .append(ac).append('/').append(ck);
                }
            }
        }
        return sb.toString();
    }
}
