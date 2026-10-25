package com.axalotl.fabrolia.common.utils;

import com.axalotl.fabrolia.common.regions.RegionScheduler;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.concurrent.CompletableFuture;
import java.util.function.LongPredicate;
import java.util.function.Supplier;

/**
 * Per-chunk completion handshake for {@code SectionStorage.unpackChunk} (task
 * #38, review defect D4).
 *
 * <p>Vanilla's loser path is broken under concurrency: the thread that loses
 * the {@code loadedChunks.add(chunkKey)} race returns from inside the first
 * {@code loadLock} block while the winner is still parked in the disk
 * {@code future.join()} — long before any section reaches {@code storage}. The
 * loser then does {@code get(sectionPos)} → {@code null} →
 * {@code IllegalStateException} in {@code getOrLoad}, or (Lithium path)
 * {@code Objects.requireNonNull(columns.get(pos))} → NPE
 * {@code "Failed to load POI section data!"}. Holding a lock around the unpack
 * fan-out does nothing for this window — the loser has already returned.
 *
 * <p>The fix: the winner registers a per-chunk {@link CompletableFuture} BEFORE
 * running the vanilla body and completes it only AFTER the fan-out has fully
 * populated {@code storage}; losers join that future instead of returning
 * early. Nothing inside the fan-out touches chunk futures, region claims or
 * {@code pumpUntilDone} (verified in the review: the unpacker constructs fresh
 * sections; {@code setDirty}/{@code onSectionLoad} are a dirty-set add plus a
 * deferred tracker enqueue plus a storage read; the disk IO runs on
 * {@code Util.backgroundExecutor}) — so a joining loser, main thread included,
 * cannot deadlock.
 *
 * <p>All map accesses run under the CALLER's monitor — the storage's own
 * {@code loadLock} — which is what makes the in-flight check composable with
 * vanilla's {@code pendingLoads}/{@code loadedChunks} bookkeeping and with
 * C2ME's {@code c2me$unloadPoi} guard (both also under {@code loadLock}).
 */
public final class PoiChunkLoadFence {

    /** Guarded by the storage {@code loadLock}; never accessed without it. */
    private final Long2ObjectOpenHashMap<LoadAttempt> inFlight = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap readers = new Long2IntOpenHashMap();
    private final Long2ObjectOpenHashMap<Runnable> deferredUnloads = new Long2ObjectOpenHashMap<>();

    /**
     * Pin a column until its result has been acquired, not merely unpacked.
     * No monitor or storage read lock is held through the body or disk join.
     * A hot lookup must also join an existing unpack: its first section can
     * become visible before the rest of the column has been published.
     */
    public <T> T acquire(Object loadLock, long chunkKey, Supplier<T> body) {
        LoadAttempt loading;
        synchronized (loadLock) {
            readers.addTo(chunkKey, 1);
            loading = inFlight.get(chunkKey);
        }
        try {
            if (loading != null) awaitCompletion(loading);
            return body.get();
        } finally {
            synchronized (loadLock) {
                int remaining = readers.get(chunkKey) - 1;
                if (remaining == 0) readers.remove(chunkKey);
                else readers.put(chunkKey, remaining);
            }
        }
    }

    /** Main-only. Keep the request: C2ME's downgrade does not retry this call. */
    public void unload(Object loadLock, long chunkKey, LongPredicate pending, Runnable body) {
        synchronized (loadLock) {
            deferredUnloads.remove(chunkKey);
            if (blocksUnload(chunkKey, pending)) {
                deferredUnloads.put(chunkKey, body);
            } else {
                runUnload(body);
            }
        }
    }

    /** A newer C2ME disk residency supersedes the old residency's deferred unload. */
    public void cancelUnload(Object loadLock, long chunkKey) {
        synchronized (loadLock) {
            deferredUnloads.remove(chunkKey);
        }
    }

    /** Storage close terminalizes requests without touching closed storage. */
    public void discardUnloads(Object loadLock) {
        synchronized (loadLock) {
            deferredUnloads.clear();
        }
    }

    /** Main-only, after pending loads have been unpacked by the normal tick. */
    public void drainUnloads(Object loadLock, LongPredicate pending) {
        synchronized (loadLock) {
            var entries = deferredUnloads.long2ObjectEntrySet().iterator();
            while (entries.hasNext()) {
                var entry = entries.next();
                try {
                    if (blocksUnload(entry.getLongKey(), pending)) continue;
                } catch (Throwable failure) {
                    entries.remove();
                    rethrow(failure);
                }
                Runnable body = entry.getValue();
                entries.remove();
                runUnload(body);
            }
        }
    }

    private boolean blocksUnload(long chunkKey, LongPredicate pending) {
        LoadAttempt loading = inFlight.get(chunkKey);
        if (loading != null) rethrow(loading.failure);
        return readers.containsKey(chunkKey)
                || (loading != null && !loading.completion.isDone())
                || pending.test(chunkKey);
    }

    private static void runUnload(Runnable body) {
        try {
            if (RegionScheduler.isActive()) RegionScheduler.runSuccessBodyIfHealthy(body);
            else body.run();
        } catch (Throwable failure) {
            rethrow(selectFailure(failure));
        }
    }

    private static void awaitCompletion(LoadAttempt attempt) {
        try {
            attempt.completion.join();
        } catch (Throwable completionSignal) {
            Throwable failure = attempt.failure;
            rethrow(failure != null ? failure : selectFailure(completionSignal));
        }
    }

    private static final class LoadAttempt {
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private volatile Throwable failure;
        /** Set only in the scheduler's success-vs-W linearization cut. */
        private volatile boolean successCommitted;
    }

    /**
     * Is a winner currently between "claimed the chunk" and "fan-out complete"?
     * MUST be called while holding {@code loadLock}.
     */
    public boolean isInFlight(long chunkKey) {
        return this.inFlight.containsKey(chunkKey);
    }

    /**
     * The whole handshake:
     * <ol>
     *   <li>under {@code loadLock}: if a winner is in flight, become a loser
     *       and (outside the lock) join its completion future;</li>
     *   <li>else if the chunk is already fully unpacked, return — nothing to
     *       do;</li>
     *   <li>else become the winner: register the completion future, run
     *       {@code original} (the vanilla unpack: loadLock-gated bookkeeping,
     *       disk join OUTSIDE any lock we hold, fan-out), then — even on
     *       exception — publish one authoritative failure and complete the
     *       future exceptionally so losers never hang.</li>
     * </ol>
     *
     * <p>A failed attempt remains as a poisoned terminal entry. Every waiter and
     * later caller rethrows the exact same first-wins failure; nobody retries a
     * vanilla body which has already been entered and failed.
     *
     * @return {@code true} if this thread was the winner and ran {@code original}.
     */
    /** Neither physical Main nor a Fabrolia simulation thread (region worker, pool, actor). */
    static boolean foreignLoaderThread() {
        if (com.axalotl.fabrolia.common.ParallelProcessor.isServerExecutionThread()) return false;
        net.minecraft.server.MinecraftServer server = RegionScheduler.getServer();
        return server == null
                || !com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2.isPhysicalServerThread(server);
    }

    public boolean runExclusively(Object loadLock, long chunkKey, LongPredicate alreadyLoaded, Runnable original) {
        LoadAttempt waitFor;
        LoadAttempt mine = null;
        synchronized (loadLock) {
            waitFor = this.inFlight.get(chunkKey);
            if (waitFor == null) {
                if (alreadyLoaded.test(chunkKey)) {
                    return false;
                }
                mine = new LoadAttempt();
                this.inFlight.put(chunkKey, mine);
            }
        }
        if (waitFor != null) {
            // Loser: park until the winner's fan-out has fully populated
            // storage. Deliberately join() and not a timed wait — the winner's
            // finally-block completion makes an unbounded hang structurally
            // impossible, and a timeout would just re-open the ISE window.
            awaitCompletion(waitFor);
            return false;
        }
        Throwable failure = null;
        try {
            if (RegionScheduler.isActive() && foreignLoaderThread()) {
                // A chunk loader outside the pool (C2ME disk read) unpacks storage,
                // not simulation. The success-body permit refuses such a thread in
                // closed pool windows (exact-turn pause, stop drain) and turned the
                // refusal into a fatal (audit D-11, the class of the worldgen fix).
                // It only must not start after a selected W.
                RegionScheduler.throwWorkerFailureIfAny();
                original.run();
            } else if (RegionScheduler.isActive()) {
                // The check and the first bytecode of the vanilla unpack must be
                // one generation cut.  A plain throwIfFailed() here leaves a
                // W-vs-original.run() window and can enter a fresh POI load after
                // another island has already failed.
                RegionScheduler.runSuccessBodyIfHealthy(original);
            } else {
                original.run();
            }
        } catch (Throwable thrown) {
            failure = selectFailure(thrown);
        }

        if (failure == null) {
            try {
                LoadAttempt owner = mine;
                // Keep the established local-gate -> generation-cut order.  In
                // particular, never hold the scheduler lifecycle monitor while
                // waiting for loadLock: unpackPendingLoads/C2ME legitimately keep
                // loadLock across their local POI bookkeeping.
                synchronized (loadLock) {
                    RegionScheduler.commitSuccessIfHealthy(() -> {
                        if (owner.successCommitted || owner.completion.isDone()) {
                            throw new IllegalStateException(
                                    "POI load attempt was completed before its owner");
                        }
                        if (this.inFlight.remove(chunkKey) != owner) {
                            throw new IllegalStateException(
                                    "POI load attempt was replaced before successful completion");
                        }
                        owner.successCommitted = true;
                        if (!owner.completion.complete(null)) {
                            throw new IllegalStateException(
                                    "POI load attempt was completed before its owner");
                        }
                    });
                }
                // Removing the poison token and notifying its waiters are one
                // success receipt: either they linearize before global W, or W
                // leaves the token installed and every waiter fails with W.
            } catch (Throwable cleanup) {
                // The monitor has been released before exact-W selection and
                // failure terminalization.
                failure = selectFailure(cleanup);
            }
        }

        if (failure == null && !mine.successCommitted) {
            failure = selectFailure(new IllegalStateException(
                    "POI load success was not committed before notification"));
        }
        if (failure != null) {
            // Do not remove the failed token. A removal window would permit a
            // second vanilla unpack to start before physical Main observes the
            // generation fatal. The server is fail-stop; retaining the poison is
            // the terminal state, not a retry cache.
            mine.failure = failure;
            try {
                if (!mine.completion.completeExceptionally(failure)) {
                    mergeFailure(failure, new IllegalStateException(
                            "POI load failure reached an already-terminal attempt"));
                }
            } catch (Throwable notificationFailure) {
                mergeFailure(failure, notificationFailure);
            }
        }
        rethrow(failure);
        return true;
    }

    private static Throwable selectFailure(Throwable candidate) {
        return RegionScheduler.isActive()
                ? RegionScheduler.publishFatalAndSelectWinner(candidate)
                : candidate;
    }

    private static void mergeFailure(Throwable primary, Throwable cleanup) {
        if (cleanup == null || cleanup == primary) return;
        try {
            primary.addSuppressed(cleanup);
        } catch (Throwable ignored) {
            // Cleanup diagnostics cannot replace the exact vanilla/global W.
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        if (failure != null) throw (T) failure;
    }
}
