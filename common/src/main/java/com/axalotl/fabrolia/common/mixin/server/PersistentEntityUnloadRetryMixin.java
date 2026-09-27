package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.IslandUnitAdmission;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Restores vanilla's retry contract after C2ME rewrites processUnloads.
 *
 * <p>Vanilla removes an unload key only when processChunkUnload returns true.
 * C2ME 0.4.1/0.4.2 instead calls removeFirstLong before the attempt and drops
 * the boolean. Fabrolia's owner-safe entity serializer legitimately returns
 * false while an island unit owns the chunk, so the rewritten loop used to
 * strand the chunk and its pearls HIDDEN forever. Failed keys are collected
 * only inside processUnloads and re-added after the loop returns; re-adding
 * inline would make C2ME immediately remove the same key and spin forever.
 *
 * <p>Priority 900 is intentional: C2ME's default-priority @Overwrite is
 * applied first, then this MixinExtras wrapper brackets the final rewritten
 * body. Without C2ME the same wrapper is harmless—vanilla already retained
 * false keys and Set.add is idempotent.
 */
@Mixin(value = PersistentEntitySectionManager.class, priority = 900)
public abstract class PersistentEntityUnloadRetryMixin
        implements com.axalotl.fabrolia.common.parallelised.utils.TickingChunksAccess {

    @Shadow @Final @Mutable
    private LongSet chunksToUnload;

    @Shadow @Final
    private Long2ObjectMap<Visibility> chunkVisibility;

    @Unique
    private ThreadLocal<Boolean> fabrolia$insideProcessUnloads;

    @Unique
    private ThreadLocal<java.util.LinkedHashSet<Long>> fabrolia$unloadRetries;

    @Unique
    private IslandUnitAdmission.EntityUnloadRetryFairness fabrolia$unloadFairness;

    @Unique
    private FabroliaStatusTransitions fabrolia$statusTransitions;

    @Unique
    private static final java.util.concurrent.atomic.LongAdder fabrolia$unloadRetryTotal =
            new java.util.concurrent.atomic.LongAdder();

    @Unique
    private static final java.util.concurrent.atomic.AtomicLong fabrolia$unloadRetryLogNanos =
            new java.util.concurrent.atomic.AtomicLong();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void fabrolia$initializeUnloadRetryState(CallbackInfo ci) {
        // ThreadLocal.withInitial uses invokedynamic in the mixin instance
        // initializer; Mixin 0.8.7 dropped that initializer on a real transform.
        // Explicit target-constructor initialization is runtime-verifiable.
        this.fabrolia$insideProcessUnloads = new ThreadLocal<>();
        this.fabrolia$unloadRetries = new ThreadLocal<>();
        this.fabrolia$unloadFairness =
                new IslandUnitAdmission.EntityUnloadRetryFairness();
        this.fabrolia$statusTransitions = new FabroliaStatusTransitions();
        // Pass C2ME's instanceof check so its overwritten loop never replaces
        // the set object while our outer monitor is held.
        if (!(this.chunksToUnload instanceof LongLinkedOpenHashSet)) {
            this.chunksToUnload = new LongLinkedOpenHashSet(this.chunksToUnload);
        }
    }

    @Unique
    private java.util.LinkedHashSet<Long> fabrolia$retrySet() {
        java.util.LinkedHashSet<Long> retries = fabrolia$unloadRetries.get();
        if (retries == null) {
            retries = new java.util.LinkedHashSet<>();
            fabrolia$unloadRetries.set(retries);
        }
        return retries;
    }

    @WrapMethod(method = "processChunkUnload")
    private boolean fabrolia$captureFailedUnload(long chunkPos, Operation<Boolean> original) {
        boolean insidePass = Boolean.TRUE.equals(fabrolia$insideProcessUnloads.get());
        boolean forceQueuedClaim = insidePass
                && fabrolia$unloadFairness.tryGrant(chunkPos, System.nanoTime());
        IslandUnitAdmission.EntityUnloadAttempt attempt =
                IslandUnitAdmission.beginEntityUnloadAttempt(
                        this, chunkPos, forceQueuedClaim);
        try {
            boolean stored;
            try {
                // processUnloads itself owns the one success-body permit for
                // its entire vanilla pop loop.  Opening another permit here
                // for every key turns a large unload pass into a lifecycle-lock
                // convoy.  Standalone processChunkUnload callers still need
                // their own exact W-vs-body admission.
                //
                // A deferred status change of THIS chunk must settle before
                // unload observes its old HIDDEN value (C2ME's pop loop has no
                // visibility predicate); a false result re-queues the key.
                // Other chunks' deferrals are irrelevant here — a level-wide
                // gate stalled every unload while one island stayed claimed.
                if (insidePass) {
                    stored = !fabrolia$statusTransitions.hasPending(chunkPos) && original.call(chunkPos);
                } else {
                    stored = com.axalotl.fabrolia.common.regions.RegionScheduler
                            .callSuccessBooleanIfHealthy(
                                    () -> !fabrolia$statusTransitions.hasPending(chunkPos)
                                            && original.call(chunkPos));
                }
            } catch (Throwable bodyFailure) {
                Throwable winner = com.axalotl.fabrolia.common.regions.RegionScheduler
                        .publishFatalAndSelectWinner(bodyFailure);
                PersistentEntityUnloadRetryMixin.<RuntimeException>fabrolia$rethrow(winner);
                return false;
            }
            if (insidePass) {
                boolean ownershipYielded =
                        IslandUnitAdmission.entityUnloadOwnershipYielded(attempt);
                // This staging is part of the already-admitted outer body.  A
                // peer W which wins while it runs is observed by that permit's
                // terminal edge; the outer failure path then clears the staged
                // set and publishes no retry successor.
                fabrolia$unloadFairness.recordResult(
                        chunkPos, ownershipYielded, System.nanoTime());
                if (!stored) {
                    fabrolia$retrySet().add(chunkPos);
                }
            }
            return stored;
        } finally {
            IslandUnitAdmission.endEntityUnloadAttempt(attempt);
        }
    }

    /**
     * Visibility and its tracking callbacks are one owner transaction. In
     * particular, a section must not become HIDDEN while an entered mover can
     * still cross into another, not-yet-demoted section of the same chunk.
     * Status notifications may arrive inside ChunkMap's monitor, so they must
     * never wait for that mover's claim. Retain the complete, unentered body.
     */
    @WrapMethod(method = "updateChunkStatus(Lnet/minecraft/world/level/ChunkPos;"
            + "Lnet/minecraft/world/level/entity/Visibility;)V")
    private void fabrolia$serializeUnloadStatusMutation(
            ChunkPos pos, Visibility visibility, Operation<Void> original) {
        com.axalotl.fabrolia.common.regions.RegionScheduler.runSuccessBodyIfHealthy(() ->
                fabrolia$statusTransitions.offer(pos.pack(), () -> {
                    synchronized (this.chunksToUnload) {
                        original.call(pos, visibility);
                        if (visibility != Visibility.HIDDEN) {
                            fabrolia$unloadFairness.forget(pos.pack());
                        }
                    }
                }));
        fabrolia$drainStatusTransitions(false);
    }

    @Inject(method = "tick", at = @At("HEAD"), require = 1)
    private void fabrolia$applyPendingStatusTransitions(CallbackInfo ci) {
        fabrolia$drainStatusTransitions(false);
    }

    /** close delegates to saveAll, so accepted status work also precedes the
     * final entity snapshot and storage close. No unload iterator exists here. */
    @Inject(method = "saveAll", at = @At("HEAD"), require = 1)
    private void fabrolia$flushPendingStatusTransitions(CallbackInfo ci) {
        fabrolia$drainStatusTransitions(true);
    }

    @Override
    public void fabrolia$finishStatusTransitions(boolean healthy) {
        if (healthy) fabrolia$drainStatusTransitions(true);
        else fabrolia$statusTransitions.cancel();
    }

    @Unique
    private void fabrolia$drainStatusTransitions(boolean flush) {
        if (fabrolia$statusTransitions.isEmpty()) return;
        var runtime = com.axalotl.fabrolia.common.regions.RegionScheduler.forEntityManager(this);
        if (runtime != null && !com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                .isPhysicalServerThread(runtime.level.getServer())) {
            if (flush) throw new IllegalStateException("entity status flush entered off physical Main");
            return;
        }
        var currentRuntime = runtime;
        fabrolia$statusTransitions.drain((chunkPos, body) -> {
            if (currentRuntime == null || currentRuntime.currentThreadHoldsAllClaims()) {
                com.axalotl.fabrolia.common.regions.RegionScheduler.runSuccessBodyIfHealthy(body);
                return true;
            }
            var owner = currentRuntime.islandOwnerOfChunk(chunkPos);
            boolean[] entered = {false};
            boolean claimed = FabroliaStatusTransitions.runWithOwner(owner, flush, () -> {
                // A blocking full-save claim can pump topology publication.
                // Never run a body using the retired predecessor's capability.
                if (owner != null && (owner.retired
                        || currentRuntime.islandOwnerOfChunk(chunkPos) != owner)) {
                    return;
                }
                entered[0] = true;
                body.run();
            });
            return claimed && entered[0];
        }, flush);
    }

    /** The queue monitor protects only admission, never callbacks or a claim
     * wait. Reentrant chunk services append behind the exact active body.
     *
     * <p>Order is a per-chunk contract, not a global one: two islands are
     * independent timelines, so an entry whose owner claim is unavailable is
     * deferred (kept, in place) together with every later entry of the same
     * chunk, while other chunks keep draining. Stopping at the queue head
     * instead let one persistently claimed island block every status change
     * of every other island — including the FRESH→queued-for-load promotion
     * that vanilla's areEntitiesLoaded/tickCheck needs — and gated all entity
     * unloads level-wide (2026-09-27 island#55 3.4-minute hold). */
    @Unique
    static final class FabroliaStatusTransitions {
        private record Entry(long chunkPos, Runnable body) {}
        @FunctionalInterface
        interface Runner {
            boolean run(long chunkPos, Runnable body);
        }

        synchronized void cancel() {
            if (draining) throw new IllegalStateException("status cancellation preceded owner quiescence");
            pending.clear();
            pendingByChunk.clear();
        }
        private final java.util.ArrayDeque<Entry> pending = new java.util.ArrayDeque<>();
        /** Entries deferred by the active pass, in queue order; re-queued ahead
         * of everything offered during that pass when it ends. */
        private final java.util.ArrayList<Entry> retained = new java.util.ArrayList<>();
        /** Unapplied entries per chunk: queued, deferred, or the active body. */
        private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap pendingByChunk =
                new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        private boolean draining;

        synchronized void offer(long chunkPos, Runnable body) {
            pending.addLast(new Entry(chunkPos, body));
            pendingByChunk.addTo(chunkPos, 1);
        }

        synchronized boolean isEmpty() {
            return pendingByChunk.isEmpty();
        }

        /** True while any status change of this chunk is still unapplied,
         * including the body running right now. */
        synchronized boolean hasPending(long chunkPos) {
            return pendingByChunk.containsKey(chunkPos);
        }

        private synchronized void applied(Entry entry) {
            if (pendingByChunk.addTo(entry.chunkPos(), -1) == 1) {
                pendingByChunk.remove(entry.chunkPos());
            }
        }

        void drain(Runner runner, boolean flush) {
            synchronized (this) {
                if (draining) {
                    if (flush && !pendingByChunk.isEmpty()) {
                        throw new IllegalStateException("entity save reentered an active status transition");
                    }
                    return;
                }
                draining = true;
            }
            it.unimi.dsi.fastutil.longs.LongOpenHashSet deferredChunks = null;
            try {
                while (true) {
                    Entry next;
                    synchronized (this) {
                        next = pending.pollFirst();
                    }
                    if (next == null) return;
                    if (deferredChunks != null && deferredChunks.contains(next.chunkPos())) {
                        synchronized (this) {
                            retained.add(next); // same chunk: stays behind its deferred predecessor
                        }
                        continue;
                    }
                    if (runner.run(next.chunkPos(), next.body())) {
                        applied(next);
                        continue;
                    }
                    synchronized (this) {
                        if (flush) {
                            pending.addFirst(next); // unentered topology change: resolve the same head afresh
                        } else {
                            retained.add(next);
                        }
                    }
                    if (!flush) {
                        if (deferredChunks == null) {
                            deferredChunks = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
                        }
                        deferredChunks.add(next.chunkPos());
                    }
                }
            } catch (Throwable failure) {
                Throwable winner = com.axalotl.fabrolia.common.regions.RegionScheduler
                        .publishFatalAndSelectWinner(failure);
                synchronized (this) {
                    // A partially entered body is never replayed after W.
                    pending.clear();
                    retained.clear();
                    pendingByChunk.clear();
                }
                com.axalotl.fabrolia.common.regions.IslandCommands.publishAndPropagate(winner);
            } finally {
                synchronized (this) {
                    // Deferred entries precede everything offered during this pass.
                    for (int i = retained.size() - 1; i >= 0; i--) {
                        pending.addFirst(retained.get(i));
                    }
                    retained.clear();
                    draining = false;
                }
            }
        }

        static boolean runWithOwner(
                com.axalotl.fabrolia.common.regions.IslandRegistry.PIsland owner,
                boolean flush, Runnable body) {
            if (owner == null || com.axalotl.fabrolia.common.regions.IslandCommands.mainHolds(owner)) {
                com.axalotl.fabrolia.common.regions.RegionScheduler.runSuccessBodyIfHealthy(body);
                return true;
            }
            boolean scoped = flush
                    ? com.axalotl.fabrolia.common.regions.IslandCommands.mainClaimScoped(owner)
                    : com.axalotl.fabrolia.common.regions.IslandCommands.tryMainClaim(owner);
            if (!flush && !scoped) return false;
            Throwable failure = null;
            try {
                com.axalotl.fabrolia.common.regions.RegionScheduler.runSuccessBodyIfHealthy(body);
            } catch (Throwable thrown) {
                failure = com.axalotl.fabrolia.common.regions.RegionScheduler.publishFatalAndSelectWinner(thrown);
            } finally {
                try {
                    if (scoped) {
                        com.axalotl.fabrolia.common.regions.IslandCommands.mainRelease(owner);
                    }
                } catch (Throwable cleanup) {
                    failure = com.axalotl.fabrolia.common.regions.RegionScheduler
                            .publishFatalAndSelectWinner(fabrolia$mergeFailure(failure, cleanup));
                }
            }
            if (failure != null) com.axalotl.fabrolia.common.regions.IslandCommands.publishAndPropagate(failure);
            com.axalotl.fabrolia.common.regions.RegionScheduler.commitSuccessIfHealthy(() -> {});
            return true;
        }
    }

    @WrapMethod(method = "processUnloads")
    private void fabrolia$restoreFailedUnloadRequests(Operation<Void> original) {
        // Per-chunk deferred status changes are gated inside processChunkUnload.
        if (Boolean.TRUE.equals(fabrolia$insideProcessUnloads.get())) {
            // Re-entrant execution inherits the top-level pass admission.
            original.call();
            return;
        }
        synchronized (this.chunksToUnload) {
            fabrolia$unloadFairness.beginPass();
            java.util.LinkedHashSet<Long> retries = fabrolia$retrySet();
            retries.clear();
            fabrolia$insideProcessUnloads.set(true);
            Throwable failure = null;
            int[] restored = {0};
            try {
                com.axalotl.fabrolia.common.regions.RegionScheduler
                        .runSuccessBodyIfHealthy(original::call);
                // One normally-returned pop loop publishes one retry successor
                // transaction.  The monitor excludes promotion for the entire
                // HIDDEN recheck/re-add set, while this single success permit
                // supplies both the entry cut and peer-W terminal cut.
                com.axalotl.fabrolia.common.regions.RegionScheduler
                        .runSuccessBodyIfHealthy(() -> {
                    for (long chunkPos : retries) {
                        // Vanilla re-checks this predicate on every false-result
                        // retry. Without it a promotion racing the failed save
                        // could be undone by a stale re-add, and C2ME's next loop
                        // has no predicate.
                        if (this.chunkVisibility.get(chunkPos) != Visibility.HIDDEN) {
                            continue;
                        }
                        this.chunksToUnload.add(chunkPos);
                        restored[0]++;
                    }
                });
            } catch (Throwable bodyFailure) {
                // Either processUnloads or the normal-result restoration may
                // have partially mutated PESM/C2ME state. Do not publish any
                // further retry successors from that partial attempt; only
                // mandatory ThreadLocal cleanup is allowed before the exact
                // same Throwable escapes.
                failure = com.axalotl.fabrolia.common.regions.RegionScheduler
                        .publishFatalAndSelectWinner(bodyFailure);
            }
            try {
                fabrolia$insideProcessUnloads.remove();
            } catch (Throwable cleanupFailure) {
                failure = fabrolia$publishMergedFailure(failure, cleanupFailure);
            }
            try {
                retries.clear();
            } catch (Throwable cleanupFailure) {
                failure = fabrolia$publishMergedFailure(failure, cleanupFailure);
            }
            try {
                fabrolia$unloadRetries.remove();
            } catch (Throwable cleanupFailure) {
                failure = fabrolia$publishMergedFailure(failure, cleanupFailure);
            }
            if (failure == null) {
                try {
                    com.axalotl.fabrolia.common.regions.RegionScheduler
                            .commitSuccessIfHealthy(() -> {});
                } catch (Throwable peerFailure) {
                    failure = com.axalotl.fabrolia.common.regions.RegionScheduler
                            .publishFatalAndSelectWinner(peerFailure);
                }
            }
            if (failure != null) {
                PersistentEntityUnloadRetryMixin.<RuntimeException>fabrolia$rethrow(failure);
            }
            if (restored[0] > 0) {
                int restoredCount = restored[0];
                // Diagnostics are also a successor stage: they must not run after
                // a peer W, and their own failure must not be swallowed as a reason
                // to continue this server tick.
                com.axalotl.fabrolia.common.regions.RegionScheduler
                        .runSuccessBodyIfHealthy(() -> {
                    fabrolia$unloadRetryTotal.add(restoredCount);
                    long now = System.nanoTime();
                    long last = fabrolia$unloadRetryLogNanos.get();
                    if (now - last > 10_000_000_000L
                            && fabrolia$unloadRetryLogNanos.compareAndSet(last, now)) {
                        org.slf4j.LoggerFactory.getLogger("Fabrolia-PearlLoad").warn(
                                "[entity-unload] restored {} deferred chunk unload request(s) "
                                        + "whose store returned false; total={} (C2ME retry repair)",
                                restoredCount, fabrolia$unloadRetryTotal.sum());
                    }
                });
            }
        }
    }

    @Unique
    private static Throwable fabrolia$mergeFailure(Throwable primary, Throwable cleanup) {
        if (primary == null) return cleanup;
        if (cleanup != null && cleanup != primary) {
            try {
                primary.addSuppressed(cleanup);
            } catch (Throwable ignored) {
                // Mandatory ThreadLocal cleanup cannot replace the unload primary.
            }
        }
        return primary;
    }

    @Unique
    private static Throwable fabrolia$publishMergedFailure(
            Throwable primary, Throwable cleanup) {
        return com.axalotl.fabrolia.common.regions.RegionScheduler
                .publishFatalAndSelectWinner(fabrolia$mergeFailure(primary, cleanup));
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void fabrolia$rethrow(Throwable failure) throws T {
        throw (T) failure;
    }
}
