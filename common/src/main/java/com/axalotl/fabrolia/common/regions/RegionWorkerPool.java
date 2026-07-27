package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Pool of region-worker threads with three work lanes, polled in priority order:
 *
 * <ol>
 *   <li><b>ready</b> — regions whose task queue has work. The worker claims the region
 *       (CAS via {@link Region#tryClaim}; single writer per region), publishes it as
 *       {@code currentRegion} (region-lock discipline for FabroliaLocks.tickLockOr),
 *       drains the queue, releases, and re-arms the region if work landed mid-drain —
 *       the lost-wakeup fix: a dropped ready entry would otherwise strand a task and
 *       wedge the entity barrier.</li>
 *   <li><b>help</b> — on a lost claim CAS the worker instead runs ONE sub-batch from
 *       the holder's {@link Region#helpQueue} (dense-region fan-out), under the same
 *       region-lock discipline.</li>
 *   <li><b>global</b> — pool-level work units that span regions: per-tick region GROUP
 *       tasks (dispatch coalescing) and the offloaded chunk-grouping jobs.</li>
 * </ol>
 *
 * <p>Workers are not bound to regions; N≈cores-2 threads multiplex hundreds of regions.
 * Submit paths call {@link RegionScheduler#wakeWorkers()}; idle workers park with a
 * 10ms fallback timeout, so a missed wake costs at most one slice.
 */
public final class RegionWorkerPool {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegionWorkerPool.class);
    private static final AtomicInteger WORKER_COUNTER = new AtomicInteger();

    private final Thread[] workers;
    private volatile boolean running;

    boolean isRunning() { return running; }

    RegionWorkerPool(int size) {
        this.workers = new Thread[Math.max(1, size)];
    }

    void start() {
        if (running) return;
        running = true;
        int priority = FabroliaConfig.getThreadPriority();
        for (int i = 0; i < workers.length; i++) {
            RegionWorkerThread t = new RegionWorkerThread(this::workerLoop,
                    "Fabrolia-Region-Worker-" + WORKER_COUNTER.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(priority);
            t.setUncaughtExceptionHandler((thr, ex) -> LOGGER.error("Uncaught in {}", thr.getName(), ex));
            ParallelProcessor.registerThread("Fabrolia-Region", t);
            workers[i] = t;
            t.start();
        }
        LOGGER.info("RegionWorkerPool: started {} workers", workers.length);
    }

    void stop() {
        if (!running) return;
        running = false;
        for (Thread t : workers) if (t != null) LockSupport.unpark(t);
        // Join hardening (2026-07-18 audit #3/#12): a worker still running
        // after the bound is about to race the FINAL SAVE (the save wrap
        // no-ops once active=false) — never proceed silently, and an
        // interrupt must not skip the remaining joins.
        boolean interrupted = false;
        long deadline = System.nanoTime() + 15_000_000_000L;
        for (Thread t : workers) {
            if (t == null) continue;
            while (t.isAlive() && System.nanoTime() < deadline) {
                try {
                    t.join(500L);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (t.isAlive()) {
                StringBuilder st = new StringBuilder();
                for (StackTraceElement el : t.getStackTrace()) st.append("\n  at ").append(el);
                LOGGER.error("RegionWorkerPool: worker {} STILL ALIVE at stop — final save may race it; stack:{}",
                        t.getName(), st);
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        LOGGER.info("RegionWorkerPool: stopped");
    }

    /** Unpark all workers. Called when work lands in a region queue. */
    void wakeAll() {
        for (Thread t : workers) if (t != null) LockSupport.unpark(t);
    }

    /**
     * R5 targeted wake: unpark up to {@code k} PARKED workers instead of the whole pool.
     * Lost-wake-free (Dekker): the submitter publishes the task (volatile/CAS enqueue)
     * BEFORE scanning flags; the worker sets {@code parked=true} BEFORE its final lane
     * re-check. In any interleaving either the re-check sees the task or the scan sees
     * the flag. A stale unpark of a worker that already found work is a benign permit;
     * the 10ms park fallback backstops any residual hole.
     */
    void wake(int k) {
        if (k <= 0) return;
        for (Thread t : workers) {
            if (t instanceof RegionWorkerThread w && w.parked) {
                w.parked = false;
                LockSupport.unpark(w);
                if (--k == 0) return;
            }
        }
    }

    public int size() { return workers.length; }

    /**
     * Global ready-queue of regions with pending work. Submit paths push the region
     * here (always — duplicates are benign); workers poll O(1) instead of the old
     * linear scan over every region snapshot. The scan was the region-owned-ticking
     * killer: ~400 region tasks/tick × 30 workers × `synchronized snapshot()`
     * (ArrayList copy of the whole region map under the regionizer monitor) per
     * claim attempt = O(R²) copies and a serialized claim path (tick median 24 → 37ms,
     * live 2026-06-09). CLQ claim protocol below closes the stranded-task race:
     * whoever holds the claim re-arms the region at release if its queue refilled.
     */
    private final ConcurrentLinkedQueue<Region> ready =
            new ConcurrentLinkedQueue<>();

    void enqueueReady(Region r) {
        ready.add(r);
    }

    /**
     * Pool-level task lane for region-GROUP work units (dispatch coalescing): one task
     * processes several regions back-to-back (spin-claim → drain queue → tick entities →
     * release per region), so a tick costs ~2×workers tasks instead of 3×regionCount.
     * Group tasks manage currentRegion themselves per region segment.
     */
    private final ConcurrentLinkedQueue<Runnable> globalTasks =
            new ConcurrentLinkedQueue<>();

    void enqueueGlobal(Runnable task) {
        globalTasks.add(task);
    }

    /**
     * Main-safe global lane: tasks with NO unbounded claim-spins inside (chunk grouping
     * jobs). The barrier pump steals from here; entity GROUP tasks stay in globalTasks
     * only — main parked in a group's claim-spin while the claim holder waits on
     * mainThreadProcessor (which only main drains) would be a hard cycle.
     */
    private final ConcurrentLinkedQueue<Runnable> mainSafeTasks =
            new ConcurrentLinkedQueue<>();

    void enqueueGlobalMainSafe(Runnable task) {
        mainSafeTasks.add(task);
    }

    /** Drain the main-safe lane to empty on the CALLING thread (main: tick
     *  boundary + pre-island-phase). Rerouted region tasks left here after a
     *  barrier closed would otherwise run a phase late — or worse, run on
     *  main mid-island-phase and race the very island ticking them. */
    void drainMainSafeAll() {
        Runnable g;
        while ((g = mainSafeTasks.poll()) != null) {
            try {
                g.run();
            } catch (Throwable t) {
                LOGGER.error("Main-safe task threw on boundary drain", t);
            }
        }
    }

    /**
     * One non-blocking unit of region work for the BARRIER PUMP (main thread): a
     * main-safe global job, a ready region's queue drain (try-claim ONCE — never spin:
     * see mainSafeTasks doc for the cycle this avoids), or one dense-region help batch.
     * Help batches run without a RegionWorkerThread currentRegion, so tickLockOr falls
     * back to the GLOBAL monitor for the thread-scoped sibling locks; ITEM-pickup
     * exclusion does NOT rely on that — SyncItemPickup keys its monitor by the ITEM's
     * region (FabroliaLocks.itemLockOr), so a main-run batch and a region worker touching
     * the same ItemEntity always contend on the same region.lock.
     *
     * @return true if any work was executed.
     */
    /** /fabrolia regions top: cumulative main-thread help-batch steals. */
    public static final java.util.concurrent.atomic.LongAdder MAIN_HELP_BATCHES =
            new java.util.concurrent.atomic.LongAdder();
    public static final java.util.concurrent.atomic.LongAdder MAIN_HELP_NANOS =
            new java.util.concurrent.atomic.LongAdder();

    boolean stealOne() {
        Runnable g = mainSafeTasks.poll();
        if (g != null) {
            try {
                g.run();
            } catch (Throwable t) {
                LOGGER.error("Main-safe global task threw on pump", t);
            }
            return true;
        }
        Region r = ready.poll();
        if (r == null) {
            return false;
        }
        if (r.state() != RegionState.ACTIVE) {
            return false;
        }
        RegionizedServer rsPeek = RegionScheduler.forLevel(r.level);
        // R4 color gate: dropping the entry is safe — the blocker's
        // done-mark re-arms dependents after its volatile write.
        if (!com.axalotl.fabrolia.common.regions.ColorGate.eligible(r, rsPeek)) {
            return false;
        }
        // R5 LIVELOCK GUARD (live stall 2026-07-02, "[event-sched] barrier stalled 13s"):
        // main must NEVER claim a region that owes an entity phase. It cannot run the
        // phase itself (mixed-monitor hazard), and the old claim→drain→re-arm epilogue
        // ping-ponged the region between main's tight pump loop and the woken worker —
        // main kept winning the ready.poll race, the phase never ran, the barrier hung,
        // and heal (idle-only back then) starved. Hand the entry straight to a worker
        // and report "no work" so main's pump goes through its heal + park path.
        if (FabroliaConfig.regionEventScheduler && rsPeek != null && rsPeek.owesEntityPhase(r)) {
            // Dense fan-out unlock (2026-07-12): while the claim holder is mid-phase it
            // publishes intra-region help batches — main may run those WITHOUT claiming
            // (see the doc above: batches are main-legal; item-pickup exclusion keys by
            // the ITEM's region, not the executing thread). The 2026-07-02 livelock
            // guard below only forbids main CLAIMING an owing region; returning before
            // this poll left main parked next to a stealable long pole (steal yielded
            // 0.17 ms/tick against a 17 ms barrier park on the 2026-07-11 profiles).
            Runnable owedHelp = r.helpQueue.poll();
            if (owedHelp != null) {
                long ht0 = System.nanoTime();
                try {
                    owedHelp.run();
                } catch (Throwable t) {
                    LOGGER.error("Region help task threw on pump", t);
                }
                MAIN_HELP_BATCHES.increment();
                MAIN_HELP_NANOS.add(System.nanoTime() - ht0);
                if (!r.helpQueue.isEmpty() || !r.queue().isEmpty()) {
                    ready.add(r);
                }
                return true;
            }
            ready.add(r);
            wake(1);
            return false;
        }
        if (!r.tryClaim(Thread.currentThread())) {
            Runnable help = r.helpQueue.poll();
            if (help != null) {
                long ht0 = System.nanoTime();
                try {
                    help.run();
                } catch (Throwable t) {
                    LOGGER.error("Region help task threw on pump", t);
                }
                MAIN_HELP_BATCHES.increment();
                MAIN_HELP_NANOS.add(System.nanoTime() - ht0);
                if (!r.helpQueue.isEmpty() || !r.queue().isEmpty()) {
                    ready.add(r);
                }
                return true;
            }
            return false;
        }
        // CLAIM-LEAK FIX (2026-07-13): same shape as workOnce — nothing runs
        // between a successful tryClaim and the try/finally that releases.
        r.stampAcquire("stealOne");
        boolean rerouted = false;
        try {
            if (rerouteIfMainSafe(r, Thread.currentThread())) {
                rerouted = true;
            } else {
                r.queue().drain(Integer.MAX_VALUE);
            }
        } finally {
            if (!rerouted) {
                stealOneEpilogue(r);
            }
        }
        return true;
    }

    /** Post-drain epilogue for {@link #stealOne} — release + re-arm + completion. */
    private void stealOneEpilogue(Region r) {
        {
            r.stampRelease("stealOne");
            r.release(Thread.currentThread());
            // R5: main NEVER runs entity phases (thread-scoped sibling monitors would mix
            // fallback vs region.lock for the local-invariant locks) — an owing region
            // main happened to claim for its queue drain is re-armed for a WORKER.
            RegionizedServer rsSteal = RegionScheduler.forLevel(r.level);
            if (!r.queue().isEmpty()
                    || (FabroliaConfig.regionEventScheduler
                        && rsSteal != null && rsSteal.owesEntityPhase(r))) {
                ready.add(r);
                wake(1);
            }
            com.axalotl.fabrolia.common.regions.ColorGate.tryMarkDone(r, rsSteal);
        }
    }

    /**
     * R4: a region outside the epoch's ordered participant set must not run
     * region work. A DONE region can have higher-color dependents ticking right
     * now; a post-dispatch orphan was never seen by the color DAG. Tasks can
     * still land in its queue through submit/dispatch TOCTOU windows, so move
     * them to the main-safe lane (global-lock fallback semantics, same as the
     * executeAt redirect). Queue-FIFO is preserved.
     *
     * <p>MUST be called with the claim held — the claim serializes reroutes
     * (two concurrent pollers would interleave the FIFO). Releases on true.
     *
     * @return true if the region's queue was rerouted.
     */
    private boolean rerouteIfMainSafe(Region r, Thread holder) {
        RegionizedServer rs = RegionScheduler.forLevel(r.level);
        if (rs == null || !RegionScheduler.shouldRunMainSafe(rs, r)) {
            return false;
        }
        try {
            Runnable t;
            while ((t = r.queue().poll()) != null) {
                mainSafeTasks.add(t);
            }
        } finally {
            r.stampRelease("reroute");
            r.release(holder);
        }
        com.axalotl.fabrolia.common.regions.ColorGate.tryMarkDone(r, rs);
        return true;
    }

    /** A2 unit lane: island tick-unit coordinator tasks. Separate from
     *  globalTasks so a coordinator's help-pump ({@link #workOnce} with
     *  {@code allowUnits=false}) can NEVER pick up another unit and nest —
     *  nesting would stack unit latencies and coordinator stacks arbitrarily
     *  deep. Only the top-level worker loop starts units. */
    private final ConcurrentLinkedQueue<Runnable> unitTasks =
            new ConcurrentLinkedQueue<>();

    void enqueueUnit(Runnable task) {
        unitTasks.add(task);
    }

    private void workerLoop() {
        RegionWorkerThread me = (RegionWorkerThread) Thread.currentThread();
        while (running) {
            boolean worked;
            try {
                worked = workOnce(me, true);
            } catch (Throwable t) {
                // WORKER-DEATH CONTAINMENT (2026-07-13): a worker must never
                // die — a terminated worker silently shrinks the pool and any
                // state it held (claims, context) wedges scheduling. Claims
                // are now leak-proof in workOnce; this is the outer belt.
                LOGGER.error("Region worker contained an uncaught failure and keeps serving", t);
                me.currentRegion = null;
                worked = true;
            }
            if (worked) {
                continue;
            }
            // Bounded park with the R5 targeted-wake handshake: flag up FIRST,
            // then one final re-check of every lane, then park. Submitters scan
            // the flags after publishing (see wake(int)) — either our re-check
            // sees their task or their scan sees our flag. The 10ms fallback
            // timeout backstops the protocol.
            me.parked = true;
            if (ready.peek() == null && globalTasks.peek() == null
                    && mainSafeTasks.peek() == null && unitTasks.peek() == null) {
                LockSupport.parkNanos(this, 10_000_000L);
            }
            me.parked = false;
        }
    }

    /**
     * One work step on a WORKER thread: a ready-region claim (queue drain +
     * owed entity phase), a help batch, or a unit/global/main-safe task — the
     * body the worker loop always had, extracted so an island unit coordinator
     * can help-pump the pool while waiting on its own barrier (it runs
     * everything EXCEPT other units — {@code allowUnits=false}).
     *
     * @return true if any work was executed or attempted (caller should
     *         re-poll immediately); false = all lanes empty.
     */
    boolean workOnce(RegionWorkerThread me, boolean allowUnits) {
            Region claimed = ready.poll();
            if (claimed == null) {
                Runnable global = allowUnits ? unitTasks.poll() : null;
                if (global == null) {
                    global = globalTasks.poll();
                }
                if (global == null) {
                    global = mainSafeTasks.poll();
                }
                if (global != null) {
                    try {
                        global.run();
                    } catch (Throwable t) {
                        com.axalotl.fabrolia.common.regions.ThrottledLog.error(
                                LOGGER, "groupTask", t, "Region group task threw");
                    }
                    return true;
                }
                return false;
            }
            if (claimed.state() != RegionState.ACTIVE) return true;
            // R4 color gate (see stealOne note on liveness).
            if (!com.axalotl.fabrolia.common.regions.ColorGate.eligible(claimed,
                    RegionScheduler.forLevel(claimed.level))) {
                return true;
            }
            // COHERENT PUBLICATION PROTOCOL (2026-07-13 production audit):
            // publish BEFORE claim, release BEFORE unpublish. With volatile
            // ordering this makes the state (holder == T && T.currentRegion
            // != region) IMPOSSIBLE to observe for any legitimately held
            // claim: a reader that sees the claim CAS must also see the
            // earlier publication, and a reader that still sees the old
            // holder during release sees the not-yet-cleared publication.
            // The heal forensic therefore never needs to guess — any
            // mismatch it observes twice for one claim GENERATION is a real
            // retention, with the acquire path and age in the ledger.
            Region prevPublished = me.currentRegion;
            if (prevPublished != null) {
                // Nested-claim invariant (user directive 2026-07-13): a thread
                // must never take a region claim while still publishing (and
                // thus potentially holding) another region. No legal path
                // does; if this ever fires, the stack IS the bug report.
                LOGGER.error("[claim-ledger] NESTED CLAIM ATTEMPT: {} entering claim of {} while publishing {}",
                        me.getName(), Long.toHexString(claimed.gridKey),
                        Long.toHexString(prevPublished.gridKey),
                        new Throwable("nested claim origin"));
            }
            me.currentRegion = claimed;
            if (!claimed.tryClaim(me)) {
                me.currentRegion = prevPublished;
                // Another worker holds the claim. If the holder published intra-region
                // sub-batches (dense fan-out), help with ONE of them under this region's
                // lock discipline, then re-arm if more work remains. Otherwise drop the
                // entry — the holder's release re-check re-arms the region if its task
                // queue refilled.
                Runnable help = claimed.helpQueue.poll();
                if (help != null) {
                    Region prev = me.currentRegion;
                    me.currentRegion = claimed;
                    try {
                        help.run();
                    } catch (Throwable t) {
                        LOGGER.error("Region help task threw", t);
                    } finally {
                        me.currentRegion = prev;
                    }
                    if (!claimed.helpQueue.isEmpty() || !claimed.queue().isEmpty()) {
                        ready.add(claimed);
                    }
                }
                return true;
            }
            claimed.stampAcquire("workOnce");
            // CLAIM-LEAK FIX (2026-07-13 "SUSPECT LEAKED CLAIM"): everything
            // after a successful tryClaim runs under try/finally — the old
            // shape left rerouteIfMainSafe + forLevel outside it, so any throw
            // there leaked the claim permanently (and the worker died, see
            // workerLoop containment). release() is a CAS(me→null), so the
            // reroute path's internal release makes the outer one a no-op.
            boolean rerouted = false;
            // audit11: this claim path is the R5 entity-phase executor — portal
            // transfers its phase stages publish ONCE, in the finally below at
            // claim release (one atomic queue element), never per entity, so
            // main's drain cannot bisect one portal activation. Mirrors the
            // legacy group task (RegionizedServer.processGroup).
            com.axalotl.fabrolia.common.parallelised.utils.PortalTeleportationManager
                    .beginRegionClaimFrame();
            try {
                if (rerouteIfMainSafe(claimed, me)) {
                    rerouted = true;
                    return true;
                }
                RegionizedServer rs = RegionScheduler.forLevel(claimed.level);
                claimed.queue().drain(Integer.MAX_VALUE);
                // R5 event scheduler: the claim path IS the entity-phase executor —
                // preserves the per-region [spawn][random][entities] FIFO exactly like
                // the legacy group task (drain first, then tick). Gated internally on
                // owing + ColorGate eligibility; contained on Throwable.
                if (FabroliaConfig.regionEventScheduler && rs != null) {
                    rs.runOwedEntityPhase(claimed);
                }
            } finally {
                // Always balances beginRegionClaimFrame — publish in the finally
                // so a throwing phase still publishes (reroute path included).
                com.axalotl.fabrolia.common.parallelised.utils.PortalTeleportationManager
                        .endRegionClaimFrame();
                if (!rerouted) {
                    claimed.stampRelease("workOnce");
                    claimed.release(me);
                    // Unpublish AFTER release (coherent protocol; see above).
                    me.currentRegion = prevPublished;
                    RegionizedServer rs = RegionScheduler.forLevel(claimed.level);
                    boolean eventSched = FabroliaConfig.regionEventScheduler;
                    // Re-arm: a task submitted after our final empty poll (its ready
                    // entry may have been consumed by a worker that lost the claim CAS
                    // to us) would otherwise strand until the next dispatch. R5: an
                    // entity phase we could not run (pre-dispatch claim that became
                    // owing mid-drain but is not yet color-eligible) also re-arms —
                    // its dropped seed entry must not strand the barrier.
                    if (!claimed.queue().isEmpty()
                            || (eventSched && rs != null && rs.owesEntityPhase(claimed))) {
                        ready.add(claimed);
                    }
                    com.axalotl.fabrolia.common.regions.ColorGate.tryMarkDone(claimed, rs);
                    if (eventSched && rs != null) {
                        rs.contributeEntityBarrier(claimed);
                    }
                } else {
                    // Reroute path: rerouteIfMainSafe released internally
                    // (stamped "reroute") — only the unpublish remains.
                    me.currentRegion = prevPublished;
                }
            }
            return true;
    }
}
