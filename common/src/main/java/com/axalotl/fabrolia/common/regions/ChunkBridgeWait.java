package com.axalotl.fabrolia.common.regions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * Task #34 (2026-07-28 06:56 production watchdog, async OFF / classic pool) —
 * the off-main chunk-bridge wait loop, extracted from
 * {@code ServerChunkCacheMixin.fabrolia$awaitMainThreadResult} so the liveness
 * contract is testable against the real loop.
 *
 * <p>The crash: an entity-batch worker ticking a zombie hit
 * {@code Entity.collide → Level.getChunkForCollisions} — a vanilla
 * <b>load=false</b>, {@code @Nullable} lookup ({@code BlockCollisions}
 * treats a null chunk as "no colliders here") — and the Tier-3 bridge parked
 * it UNBOUNDEDLY on the main-thread chunk future. Under c2me's rewritten
 * chunk system with heavy ticket churn (stasis pearls), a holder can pass the
 * {@code chunkAbsent} check and then lose its ticket: the scheduled
 * generation future is abandoned without completing (every c2me worker sat
 * idle on its semaphore in the dump), main pumped {@code mainThreadProcessor}
 * forever, the entity barrier never finished, watchdog fired.
 *
 * <p>Contract restored here, per vanilla semantics:
 * <ul>
 *   <li><b>load=false</b> may legally answer {@code null} ("not currently
 *       available" — {@code Level.getChunkForCollisions} is {@code @Nullable}
 *       and every vanilla caller handles it) — so the wait is BOUNDED
 *       ({@link #SOFT_NULL_BUDGET_NS}, generous for any legitimate visibility
 *       promotion). A stranded future costs one bounded stall, never a
 *       watchdog.</li>
 *   <li><b>load=true</b> must produce a chunk — the wait remains on the exact
 *       original future. A slow or wedged upgrade is reported periodically,
 *       but never answered by entering a second ticket/schedule body while the
 *       first may still be live.</li>
 * </ul>
 */
public final class ChunkBridgeWait {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-ChunkBridge");

    /** load=false: total wait budget before answering the vanilla-legal null.
     *  4 game ticks — a legitimately promoting holder becomes visible well
     *  within this; only a stranded future ever exhausts it. */
    static final long SOFT_NULL_BUDGET_NS = 200_000_000L;

    /** load=true forensic cadence; observation only, never a retry timer. */
    static final long STALL_REPORT_INTERVAL_NS = 5_000_000_000L;

    private static final long INITIAL_PARK_NS = 50_000L;
    private static final long MAX_PARK_NS = 1_000_000L;

    // ------------------------------------------------------------------
    // Log aggregation (2026-07-28 mass-join field test): forty fake players
    // dropped into ungenerated wilderness produce dozens of legitimate
    // >200ms generations per second — one WARN per give-up floods the log
    // and the "strand?" wording misdiagnoses plain slow worldgen. Give-ups
    // aggregate into one summary line per window; the load=true stall keeps
    // its per-event one-liner (rare, meaningful) but prints the call-site
    // stack at most once per minute globally.
    // ------------------------------------------------------------------

    private static final long GIVEUP_SUMMARY_WINDOW_NS = 5_000_000_000L;
    private static final java.util.concurrent.atomic.AtomicLong GIVEUPS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong GIVEUP_FLUSH_AT =
            new java.util.concurrent.atomic.AtomicLong();
    private static volatile String LAST_GIVEUP_POS = "";

    private static final long STALL_STACK_INTERVAL_NS = 60_000_000_000L;
    private static final java.util.concurrent.atomic.AtomicLong LAST_STALL_STACK_AT =
            new java.util.concurrent.atomic.AtomicLong();

    private static void logGiveUp(long waitedNs, String pos) {
        long n = GIVEUPS.incrementAndGet();
        LAST_GIVEUP_POS = pos;
        long now = System.nanoTime();
        long flushAt = GIVEUP_FLUSH_AT.get();
        if (now - flushAt < GIVEUP_SUMMARY_WINDOW_NS
                || !GIVEUP_FLUSH_AT.compareAndSet(flushAt, now)) {
            LOGGER.debug("[fabrolia] load=false chunk bridge gave up after {}ms at {} — vanilla null",
                    waitedNs / 1_000_000, pos);
            return;
        }
        long total = GIVEUPS.getAndSet(0);
        LOGGER.warn("[fabrolia] load=false chunk bridge answered vanilla null for {} lookups "
                        + "in the last {}s (latest {}) — worldgen slower than the {}ms budget "
                        + "under load, or a chunk-system strand; per-event detail at DEBUG",
                total, GIVEUP_SUMMARY_WINDOW_NS / 1_000_000_000, LAST_GIVEUP_POS,
                SOFT_NULL_BUDGET_NS / 1_000_000);
    }

    private ChunkBridgeWait() {}

    /** Outcome of one bridge wait. */
    public sealed interface Result<T, C> {
        /** The future completed (possibly exceptionally) — extract from it. */
        record Done<T, C>(CompletableFuture<T> future) implements Result<T, C> {}
        /** The lock-free probe found the chunk first — use it directly. */
        record Ready<T, C>(C chunk) implements Result<T, C> {}
        /** load=false budget exhausted — answer the vanilla-legal null. */
        record GiveUpNull<T, C>() implements Result<T, C> {}
    }

    /**
     * The production wait loop.
     *
     * @param initial the future produced by the caller's one submission
     * @param probe   lock-free ready-chunk probe, re-run every iteration;
     *                a non-null result wins immediately
     * @param loadStallInspector run at every REPEATED load=true stall report:
     *                the request's load ticket is held until it resolves, so a
     *                stranded (e.g. C2ME FLAG_BROKEN) holder can never downgrade
     *                and fail its futures; the inspector names it and fail-stops
     */
    public static <T, C> Result<T, C> await(CompletableFuture<T> initial,
                                            Supplier<C> probe,
                                            boolean loadOrGenerate,
                                            Supplier<String> posDescription,
                                            Runnable loadStallInspector) {
        long sleepNs = INITIAL_PARK_NS;
        final long start = System.nanoTime();
        long lastReport = start;
        int reports = 0;
        while (!initial.isDone()) {
            // A peer W closes the already-entered caller body. In particular,
            // a trial-LOS ray parked on its causal chunk request must unwind so
            // Main can quiesce RUNNING rays without executing the level-wide
            // mainThreadProcessor after failure.
            RegionScheduler.throwWorkerFailureIfAny();
            C ready = probe.get();
            if (ready != null) {
                return new Result.Ready<>(ready);
            }
            long now = System.nanoTime();
            if (!loadOrGenerate && now - start > SOFT_NULL_BUDGET_NS) {
                // Vanilla-legal: load=false means "what is there RIGHT NOW";
                // the budget already exceeded any legitimate promotion. This
                // exact wait shape was a silent 60s watchdog kill before #34.
                logGiveUp(now - start, posDescription.get());
                return new Result.GiveUpNull<>();
            }
            if (loadOrGenerate && now - lastReport > STALL_REPORT_INTERVAL_NS) {
                lastReport = now;
                reports++;
                long stackAt = LAST_STALL_STACK_AT.get();
                boolean withStack = reports > 1
                        || (now - stackAt > STALL_STACK_INTERVAL_NS
                                && LAST_STALL_STACK_AT.compareAndSet(stackAt, now));
                String msg = "[fabrolia] load=true chunk bridge stalled {}s at {} — "
                        + "still waiting for the original request (report {}); "
                        + "no successor ticket/schedule submitted";
                Object[] args = {(now - start) / 1_000_000_000, posDescription.get(), reports,
                        withStack ? new Throwable("chunk bridge wait site") : null};
                if (reports > 1) {
                    LOGGER.error(msg + " — REPEATED stall: likely a chunk-system strand", args);
                    loadStallInspector.run();
                } else {
                    LOGGER.warn(msg, args);
                }
            }
            LockSupport.parkNanos(sleepNs);
            sleepNs = Math.min(sleepNs << 1, MAX_PARK_NS);
        }
        return new Result.Done<>(initial);
    }
}
