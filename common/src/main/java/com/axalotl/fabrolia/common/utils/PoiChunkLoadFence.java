package com.axalotl.fabrolia.common.utils;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.concurrent.CompletableFuture;
import java.util.function.LongPredicate;

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
    private final Long2ObjectOpenHashMap<CompletableFuture<Void>> inFlight = new Long2ObjectOpenHashMap<>();

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
     *       exception — deregister under {@code loadLock} and complete the
     *       future so losers never hang.</li>
     * </ol>
     *
     * <p>On winner failure losers complete normally and re-observe a null
     * section; {@code getOrLoad}'s bounded retry (same commit) turns the
     * unload-after-complete flavour of that into a fresh disk read, and a
     * persistent failure surfaces as vanilla's own ISE — unchanged semantics.
     *
     * @return {@code true} if this thread was the winner and ran {@code original}.
     */
    public boolean runExclusively(Object loadLock, long chunkKey, LongPredicate alreadyLoaded, Runnable original) {
        CompletableFuture<Void> waitFor;
        CompletableFuture<Void> mine = null;
        synchronized (loadLock) {
            waitFor = this.inFlight.get(chunkKey);
            if (waitFor == null) {
                if (alreadyLoaded.test(chunkKey)) {
                    return false;
                }
                mine = new CompletableFuture<>();
                this.inFlight.put(chunkKey, mine);
            }
        }
        if (waitFor != null) {
            // Loser: park until the winner's fan-out has fully populated
            // storage. Deliberately join() and not a timed wait — the winner's
            // finally-block completion makes an unbounded hang structurally
            // impossible, and a timeout would just re-open the ISE window.
            waitFor.join();
            return false;
        }
        try {
            original.run();
        } finally {
            synchronized (loadLock) {
                this.inFlight.remove(chunkKey);
            }
            mine.complete(null);
        }
        return true;
    }
}
