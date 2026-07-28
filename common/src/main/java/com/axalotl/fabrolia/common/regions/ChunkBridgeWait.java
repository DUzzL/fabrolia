package com.axalotl.fabrolia.common.regions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
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
 *   <li><b>load=true</b> must produce a chunk — the wait stays unbounded but
 *       SELF-HEALS: every {@link #RESUBMIT_INTERVAL_NS} the request is
 *       re-submitted to the main thread ({@code getChunkFutureMainThread}
 *       re-adds the ticket and re-schedules the generation task), so a load
 *       the chunk system dropped is re-armed instead of waited on forever.
 *       Forensics log each resubmission.</li>
 * </ul>
 */
public final class ChunkBridgeWait {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-ChunkBridge");

    /** load=false: total wait budget before answering the vanilla-legal null.
     *  4 game ticks — a legitimately promoting holder becomes visible well
     *  within this; only a stranded future ever exhausts it. */
    static final long SOFT_NULL_BUDGET_NS = 200_000_000L;

    /** load=true: re-submit the main-thread request after this long without a
     *  completion — re-arms loads the chunk system dropped (a fresh ticket +
     *  fresh schedule is harmless when the original was healthy). */
    static final long RESUBMIT_INTERVAL_NS = 5_000_000_000L;

    private static final long INITIAL_PARK_NS = 50_000L;
    private static final long MAX_PARK_NS = 1_000_000L;

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
     * @param initial the future produced by the caller's first submission
     * @param submit  re-submits {@code getChunkFutureMainThread} onto the
     *                main-thread processor (load=true self-heal only)
     * @param probe   lock-free ready-chunk probe, re-run every iteration;
     *                a non-null result wins immediately
     */
    public static <T, C> Result<T, C> await(CompletableFuture<T> initial,
                                            Supplier<CompletableFuture<T>> submit,
                                            Supplier<C> probe,
                                            boolean loadOrGenerate,
                                            Supplier<String> posDescription) {
        CompletableFuture<T> future = initial;
        long sleepNs = INITIAL_PARK_NS;
        final long start = System.nanoTime();
        long lastSubmit = start;
        int resubmits = 0;
        while (!future.isDone()) {
            C ready = probe.get();
            if (ready != null) {
                return new Result.Ready<>(ready);
            }
            long now = System.nanoTime();
            if (!loadOrGenerate && now - start > SOFT_NULL_BUDGET_NS) {
                // Vanilla-legal: load=false means "what is there RIGHT NOW";
                // the budget already exceeded any legitimate promotion. The
                // stranded future is abandoned loudly — this exact shape was
                // a silent 60s watchdog kill before task #34.
                LOGGER.warn("[fabrolia] load=false chunk bridge gave up after {}ms at {} "
                                + "(future never completed — chunk-system strand?); "
                                + "answering vanilla null",
                        (now - start) / 1_000_000, posDescription.get());
                return new Result.GiveUpNull<>();
            }
            if (loadOrGenerate && now - lastSubmit > RESUBMIT_INTERVAL_NS) {
                lastSubmit = now;
                resubmits++;
                // Call-site stack on the FIRST stall only (task #32 forensic
                // parity: the wait site identifies which game path — nav,
                // collision, BE — is bridging, without flooding on repeats).
                LOGGER.error("[fabrolia] load=true chunk bridge stalled {}s at {} — "
                                + "re-submitting main-thread request (attempt {}); a lost "
                                + "load is re-armed by the fresh ticket+schedule",
                        (now - start) / 1_000_000_000, posDescription.get(), resubmits,
                        resubmits == 1 ? new Throwable("chunk bridge wait site") : null);
                // Race the fresh request against the old future: whichever
                // completes first wins; a permanently stranded original can
                // no longer pin this thread.
                future = future.applyToEither(submit.get(), Function.identity());
            }
            LockSupport.parkNanos(sleepNs);
            sleepNs = Math.min(sleepNs << 1, MAX_PARK_NS);
        }
        return new Result.Done<>(future);
    }
}
