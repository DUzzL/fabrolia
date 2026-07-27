package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Region-aware temporal control (2026-07-14): island tick-rate overrides and
 * sprint sessions with MERGE-SAFE LINEAGE semantics. Sessions attach to CELL
 * SETS, not to transient island objects — island ids are reused and identities
 * permute at every rebuild, so {@link #remap} re-resolves each session against
 * the fresh topology after every rebuild: a session covering any cell of an
 * island EXPANDS to the whole island (merged areas are one causal domain and
 * cannot run at two rates), and a later split leaves the session covering all
 * descendants that inherited its cells.
 *
 * <p>Deterministic conflict policy (documented per the task):
 * <ul>
 *   <li>RATE, two explicit overrides meeting in one island → the LOWER rate
 *       (conservative) wins as the effective rate; both lineage sessions are
 *       kept so a later split restores each side's desired rate.</li>
 *   <li>SPRINT, two sessions meeting in one island → shared ticks satisfy
 *       both obligations concurrently: the island sprints until the LARGEST
 *       remaining obligation completes (remaining = max), and every covered
 *       session's counter decrements per completed unit. Neither request is
 *       discarded; the resolution is visible in status output.</li>
 * </ul>
 *
 * <p>Rates never touch clocks or deadlines: logical delays stay measured in
 * ticks; only wall-clock pacing (the unit deadline step) changes. During a
 * GLOBAL sprint (vanilla /tick sprint), the deadline drive switches to
 * lockstep — one complete unit per island per main tick, awaited at the tick
 * barrier — and local sprint chaining pauses so the exact-tick-count contract
 * holds.
 */
public final class TemporalControl {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-Temporal");
    private static final AtomicLong SESSION_IDS = new AtomicLong();

    /** Global island target (nanos per logical tick). Vanilla /tick rate sets it. */
    public static volatile long globalIslandTargetNanos = 50_000_000L;

    public static final class Session {
        public final long id = SESSION_IDS.incrementAndGet();
        /** Membership snapshot — treat as IMMUTABLE after the session is
         *  published; remap() swaps a NEW set in (audit #6: in-place
         *  clear+addAll raced the pacer thread's intersects read). */
        public volatile LongOpenHashSet cells = new LongOpenHashSet();
        /** 0 = no rate override; else nanos per logical tick. */
        public volatile long rateNanos;
        /** Remaining sprint ticks; 0 = not sprinting. */
        public final AtomicLong sprintRemaining = new AtomicLong();
        /** Coherence pin: the covered island's L must track the wall clock
         *  1:1 (never ahead, catch-up chaining when behind) and rate/sprint
         *  overrides are ignored for it — the machine-phase half of the
         *  wireless-redstone compatibility model (detector reference is
         *  wall-coherent unconditionally; see DaylightDetectorBlockMixin). */
        public volatile boolean coherent;
        public volatile String label;

        Session(String label) {
            this.label = label;
        }
    }

    private TemporalControl() {}

    /** Scheduler-stop hook: drop queued pacer fires from the closing session
     *  (the fire-time forLevel identity check is the belt; this is the
     *  suspenders — audit #5). The pacer thread itself is a daemon that
     *  blocks on the queue and is reused across sessions. */
    public static void drainPacer() {
        Pacer p = PACER;
        if (p != null) p.queue.clear();
    }

    /**
     * Single dedicated pacing thread for above-20 rate overrides (2026-07-14
     * freeze fix): precise per-island deadline submission without occupying
     * region workers with pacing parks and without a standing unit queue that
     * out-races main-side claim fences. One pacer serves every accelerated
     * island; entries re-check fence interest and override state at fire time.
     */
    public static final Pacer PACER = new Pacer();

    public static final class Pacer implements Runnable {
        private final java.util.concurrent.DelayQueue<Entry> queue = new java.util.concurrent.DelayQueue<>();
        private volatile Thread thread;

        private static final class Entry implements java.util.concurrent.Delayed {
            final RegionizedServer rs;
            final IslandRegistry.PIsland island;
            final long atNanos;
            /** Pacing generation at schedule time: an entry from an older
             *  generation is INERT at fire time (a rate change re-paces with
             *  a fresh entry from the next complete()). Exactly one live
             *  pacing authority per island per generation. */
            final long gen;

            Entry(RegionizedServer rs, IslandRegistry.PIsland island, long atNanos) {
                this.rs = rs;
                this.island = island;
                this.atNanos = atNanos;
                this.gen = island.pacingGen;
            }

            @Override
            public long getDelay(java.util.concurrent.TimeUnit unit) {
                return unit.convert(atNanos - System.nanoTime(), java.util.concurrent.TimeUnit.NANOSECONDS);
            }

            @Override
            public int compareTo(java.util.concurrent.Delayed o) {
                return Long.compare(atNanos, ((Entry) o).atNanos);
            }
        }

        public void schedule(RegionizedServer rs, IslandRegistry.PIsland island, long atNanos) {
            ensureStarted();
            queue.add(new Entry(rs, island, atNanos));
        }

        private synchronized void ensureStarted() {
            if (thread == null) {
                Thread t = new Thread(this, "Fabrolia-Rate-Pacer");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY + 1);
                thread = t;
                t.start();
            }
        }

        @Override
        public void run() {
            while (true) {
                try {
                    Entry e = queue.take();
                    // Fire-time validation: still overridden, not fence-wanted,
                    // scheduler alive. A yielded island is simply left to the
                    // deadline drive (its deadline stays due).
                    if (!RegionScheduler.isActive()
                            || com.axalotl.fabrolia.common.config.FabroliaConfig.asyncFailClosedTripped) {
                        continue; // fail-closed containment / shutdown: no new async units
                    }
                    if (e.island.retired || e.gen != e.island.pacingGen) {
                        continue; // stale identity or superseded pacing generation — inert
                    }
                    if (RegionScheduler.forLevel(e.island.level) != e.rs) {
                        continue; // off->on cycle: entry belongs to a torn-down scheduler (audit #5)
                    }
                    if (e.island.claimWanted || e.rs.mainHoldsAllClaims()) {
                        // brief backoff, retry after the fence
                        queue.add(new Entry(e.rs, e.island, System.nanoTime() + 2_000_000L));
                        continue;
                    }
                    if (effectiveTargetNanos(e.rs, e.island) >= 50_000_000L) {
                        continue; // override removed — the drive owns pacing again
                    }
                    e.island.chainedUnits++;
                    IslandTickUnit.submitUnit(e.rs, e.island);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable t) {
                    // pacing must never die silently
                    org.slf4j.LoggerFactory.getLogger("Fabrolia-Temporal")
                            .error("pacer iteration failed", t);
                }
            }
        }
    }

    /** Per-level session registry lives on RegionizedServer.temporalSessions. */

    /** REPLACE SEMANTICS (2026-07-14 "rate stops applying" fix): an explicit
     *  rate command on an island REPLACES every rate session touching it. The
     *  old append-only behavior + the lower-tps-wins conflict scan meant the
     *  lowest rate ever set stayed effective forever (a 20→40→60 ladder
     *  wedged at the first low value — the user's "ignored after ~3 uses").
     *  Lower-wins now applies only to sessions that MEET through a topology
     *  merge; one control source re-issuing on the same island replaces.
     *  @return replaced session ids for command feedback. */
    public static long[] replaceRateSessions(RegionizedServer rs, IslandRegistry.PIsland island) {
        java.util.ArrayList<Long> removed = new java.util.ArrayList<>(2);
        rs.temporalSessions.removeIf(s -> {
            if (s.rateNanos > 0 && s.sprintRemaining.get() == 0 && intersects(s.cells, island.cells)) {
                removed.add(s.id);
                return true;
            }
            return false;
        });
        long[] out = new long[removed.size()];
        for (int i = 0; i < out.length; i++) out[i] = removed.get(i);
        return out;
    }

    public static Session addCoherentSession(RegionizedServer rs, IslandRegistry.PIsland island,
                                             String label) {
        Session s = new Session(label);
        s.coherent = true;
        s.cells.addAll(island.cells);
        rs.temporalSessions.add(s);
        island.pacingGen++;
        return s;
    }

    public static boolean intersectsPublic(it.unimi.dsi.fastutil.longs.LongOpenHashSet a,
                                           it.unimi.dsi.fastutil.longs.LongOpenHashSet b) {
        return intersects(a, b);
    }

    public static boolean isCoherent(RegionizedServer rs, IslandRegistry.PIsland island) {
        List<Session> sessions = rs.temporalSessions;
        if (sessions.isEmpty()) return false;
        for (Session s : sessions) {
            if (s.coherent && intersects(s.cells, island.cells)) return true;
        }
        return false;
    }

    public static Session addRateSession(RegionizedServer rs, IslandRegistry.PIsland island,
                                         double tps, String label) {
        Session s = new Session(label);
        s.rateNanos = (long) (1_000_000_000.0 / Math.max(0.01, tps));
        s.cells.addAll(island.cells);
        rs.temporalSessions.add(s);
        island.pacingGen++;
        return s;
    }

    public static Session addSprintSession(RegionizedServer rs, IslandRegistry.PIsland island,
                                           long ticks, String label) {
        // Later sprint replaces earlier sprint on the same island (same
        // control-source invariant as rates; merge-shared decrements still
        // satisfy distinct-lineage sessions concurrently).
        rs.temporalSessions.removeIf(s2 -> s2.sprintRemaining.get() > 0 && s2.rateNanos == 0
                && intersects(s2.cells, island.cells));
        Session s = new Session(label);
        s.sprintRemaining.set(Math.max(0, ticks));
        s.cells.addAll(island.cells);
        rs.temporalSessions.add(s);
        island.pacingGen++;
        return s;
    }

    /** Pending unit-boundary commit acknowledgements for rate/sprint commands:
     *  feedback prints only after the island's completing unit observed the
     *  new pacing generation (appliedPacingGen). Bounded wait = liveness
     *  diagnostic, not silence. Main thread only. */
    private static final java.util.ArrayList<Object[]> PENDING_ACKS = new java.util.ArrayList<>();

    public static void awaitCommit(RegionizedServer rs, IslandRegistry.PIsland island,
                                   net.minecraft.commands.CommandSourceStack src, String what) {
        synchronized (PENDING_ACKS) {
            PENDING_ACKS.add(new Object[]{rs, island, island.pacingGen, 0L, src, what});
        }
    }

    /** Called once per main tick (overworld drive). */
    public static void tickAcks() {
        synchronized (PENDING_ACKS) {
            if (PENDING_ACKS.isEmpty()) return;
            var it = PENDING_ACKS.iterator();
            while (it.hasNext()) {
                Object[] a = it.next();
                var island = (IslandRegistry.PIsland) a[1];
                long gen = (Long) a[2];
                long age = (Long) a[3] + 1;
                a[3] = age;
                var src = (net.minecraft.commands.CommandSourceStack) a[4];
                if (island.retired) {
                    // topology replaced the object; the session is cell-keyed
                    // and remap re-attached it — confirm against any current
                    // island covering the old cells at the next rebuild tick.
                    src.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            a[5] + ": island identity changed (merge/split) — session remapped by lineage"));
                    it.remove();
                } else if (island.appliedPacingGen >= gen) {
                    src.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            a[5] + " committed at unit boundary (pacingGen " + gen
                            + ", L=" + island.logicalTick + ")"));
                    it.remove();
                } else if (age > 100) {
                    src.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "WARN " + a[5] + " NOT committed after 100 main ticks — island #"
                            + island.id + " units are not completing (pacingGen " + island.pacingGen
                            + " applied " + island.appliedPacingGen + "); check /fabrolia regions island list"));
                    it.remove();
                }
            }
        }
    }

    /** Vanilla /tick rate: every domain gets the same target; explicit island
     *  rate overrides are cleared (the command means "set every domain"). */
    public static void setGlobalRate(float tps) {
        globalIslandTargetNanos = (long) (1_000_000_000.0 / Math.max(0.01, tps));
        for (RegionizedServer rs : RegionScheduler.allLevels()) {
            rs.temporalSessions.removeIf(s -> {
                if (s.sprintRemaining.get() > 0) {
                    return false; // sprints are tick-count obligations, keep
                }
                return true;
            });
            for (IslandRegistry.PIsland p : rs.islandRegistry.islands()) {
                p.pacingGen++;
            }
        }
        LOGGER.info("[temporal] global island target rate set to {} tps", tps);
    }

    /** Effective pacing for one island: min explicit rate override, else global. */
    public static long effectiveTargetNanos(RegionizedServer rs, IslandRegistry.PIsland island) {
        long best = 0;
        List<Session> sessions = rs.temporalSessions;
        if (!sessions.isEmpty()) {
            for (Session s : sessions) {
                // Coherence outranks every rate override: pinned islands run
                // at exactly the wall cadence (drive holds L <= W).
                if (s.coherent && intersects(s.cells, island.cells)) {
                    return 50_000_000L;
                }
                if (s.rateNanos > 0 && intersects(s.cells, island.cells)) {
                    if (best == 0 || s.rateNanos > best) {
                        best = s.rateNanos; // larger nanos = LOWER tps = conservative
                    }
                }
            }
        }
        return best != 0 ? best : globalIslandTargetNanos;
    }

    /** Is any sprint obligation active for this island? */
    public static boolean isSprinting(RegionizedServer rs, IslandRegistry.PIsland island) {
        if (isCoherent(rs, island)) return false; // pinned: sprint sessions inert

        List<Session> sessions = rs.temporalSessions;
        if (sessions.isEmpty()) {
            return false;
        }
        for (Session s : sessions) {
            if (s.sprintRemaining.get() > 0 && intersects(s.cells, island.cells)) {
                return true;
            }
        }
        return false;
    }

    /** Unit completion: decrement every covering sprint obligation; @return
     *  true if the island should immediately chain another unit. */
    public static boolean onUnitComplete(RegionizedServer rs, IslandRegistry.PIsland island) {
        List<Session> sessions = rs.temporalSessions;
        if (sessions.isEmpty()) {
            return false;
        }
        boolean chain = false;
        for (Session s : sessions) {
            if (s.coherent && intersects(s.cells, island.cells)) {
                // Catch-up chaining: a pinned island that fell behind the
                // wall clock re-submits until L == W, then holds (the drive
                // releases exactly one unit per wall tick thereafter).
                if (island.logicalTick < IslandClock.wallTime(island.level)) {
                    chain = true;
                }
                continue;
            }
            long rem = s.sprintRemaining.get();
            if (rem > 0 && intersects(s.cells, island.cells)) {
                long left = s.sprintRemaining.decrementAndGet();
                if (left > 0) {
                    chain = true;
                } else {
                    LOGGER.info("[temporal] local sprint session#{} ({}) completed", s.id, s.label);
                    rs.temporalSessions.remove(s);
                }
            }
        }
        // Global sprint lockstep owns tick production — never self-chain then.
        if (chain && RegionScheduler.getServer() != null
                && RegionScheduler.getServer().tickRateManager().isSprinting()) {
            return false;
        }
        return chain;
    }

    /** Rebuild hook: re-resolve every session against fresh topology. A session
     *  intersecting an island expands to cover the WHOLE island; sessions that
     *  now share an island merge deterministically (rate: lower wins via the
     *  effective-rate scan; sprint: max obligation via shared decrements). */
    public static void remap(RegionizedServer rs) {
        List<Session> sessions = rs.temporalSessions;
        if (sessions.isEmpty()) {
            return;
        }
        for (Session s : sessions) {
            LongOpenHashSet expanded = null;
            for (IslandRegistry.PIsland p : rs.islandRegistry.islands()) {
                if (intersects(s.cells, p.cells)) {
                    if (expanded == null) {
                        expanded = new LongOpenHashSet(s.cells);
                    }
                    boolean grew = expanded.addAll(p.cells);
                    if (grew) {
                        FlightRecorder.record("TEMPORAL session#" + s.id + " (" + s.label
                                + ") expanded onto island#" + p.id + " (merge lineage)");
                    }
                }
            }
            if (expanded != null) {
                s.cells = expanded; // copy-and-swap: readers see old OR new, never mid-rehash
            }
        }
    }

    public static String status(RegionizedServer rs) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(java.util.Locale.ROOT, "main=%.1ftps/%.2fms ",
                TpsHud.mainTpsValue(), TpsHud.mainMsptNanos() / 1_000_000.0));
        sb.append("globalTarget=").append(String.format(java.util.Locale.ROOT, "%.1f",
                1_000_000_000.0 / globalIslandTargetNanos)).append("tps");
        var srv = RegionScheduler.getServer();
        if (srv != null && srv.tickRateManager().isSprinting()) {
            sb.append(" GLOBAL-SPRINT(lockstep)");
        }
        for (Session s : rs.temporalSessions) {
            sb.append(" | session#").append(s.id).append(" '").append(s.label).append("'");
            if (s.rateNanos > 0) {
                sb.append(" rate=").append(String.format(java.util.Locale.ROOT, "%.1f",
                        1_000_000_000.0 / s.rateNanos)).append("tps");
            }
            long rem = s.sprintRemaining.get();
            if (rem > 0) {
                sb.append(" sprintRemaining=").append(rem);
            }
            sb.append(" cells=").append(s.cells.size());
        }
        return sb.toString();
    }

    private static boolean intersects(LongOpenHashSet a, LongOpenHashSet b) {
        LongOpenHashSet small = a.size() <= b.size() ? a : b;
        LongOpenHashSet big = small == a ? b : a;
        var it = small.iterator();
        while (it.hasNext()) {
            if (big.contains(it.nextLong())) {
                return true;
            }
        }
        return false;
    }
}
