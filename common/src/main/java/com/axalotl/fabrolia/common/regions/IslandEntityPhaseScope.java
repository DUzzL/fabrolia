package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Spatial authority of one serial island entity pass, after its region queues
 * have joined. A unit claim alone is insufficient: before that join, member
 * helpers can still run concurrently. This scope permits inline block effects
 * throughout the claimed island, including its write halo, only while that
 * exclusive phase is executing.
 *
 * <p>Keeping the complete vanilla block callback inline is essential. Deferring
 * just its setBlock while running wasExploded immediately leaves a TNT block
 * visible to a second explosion and primes it twice.
 */
public final class IslandEntityPhaseScope implements AutoCloseable {
    private final RegionWorkerThread worker;
    private final RegionizedServer runtime;
    private final IslandRegistry.PIsland island;
    private final EpochDomain domain;
    private final long epoch;
    private final IslandEntityPhaseScope previous;
    private boolean closed;

    private IslandEntityPhaseScope(RegionWorkerThread worker, RegionizedServer runtime,
                                   IslandRegistry.PIsland island,
                                   IslandEntityPhaseScope previous) {
        this.worker = worker;
        this.runtime = runtime;
        this.island = island;
        this.domain = island.domain;
        this.epoch = domain.epoch;
        this.previous = previous;
    }

    /** Enter only after the exact queue-only dispatch completed successfully. */
    public static IslandEntityPhaseScope enter(RegionizedServer runtime,
                                              IslandRegistry.PIsland island) {
        if (runtime == null || island == null) throw new NullPointerException();
        if (!(Thread.currentThread() instanceof RegionWorkerThread worker)) {
            throw new IllegalStateException("exclusive island entity phase entered off its worker");
        }
        EpochDomain domain = island.domain;
        long epoch = domain.epoch;
        if (island.retired || island.domainLevel() != runtime.level || domain.owner != island
                || !island.unitClaim.get() || island.claimHolder != worker
                || worker.currentDomain != domain
                || worker.currentRegion != null && worker.currentRegion.domain != domain) {
            throw new IllegalStateException("exclusive island entity phase without its exact live unit claim");
        }
        if (!queueJoinSucceeded(domain, epoch)) {
            throw new IllegalStateException("exclusive island entity phase before its exact queue join");
        }
        IslandEntityPhaseScope previous = worker.exclusiveEntityPhase;
        if (previous != null && (previous.runtime != runtime || previous.island != island
                || previous.epoch != epoch || previous.closed)) {
            throw new IllegalStateException("exclusive island entity phase nested across owners or epochs");
        }
        IslandEntityPhaseScope scope = new IslandEntityPhaseScope(worker, runtime, island, previous);
        worker.exclusiveEntityPhase = scope;
        return scope;
    }

    private static boolean queueJoinSucceeded(EpochDomain domain, long epoch) {
        var barrier = domain.islandEntityQueueBarrier;
        return domain.queueOnlyEntityEpoch == epoch && domain.islandEntityQueueEpoch == epoch
                && domain.entityDispatchCutEpoch == epoch && domain.dispatchedEpoch == epoch
                && domain.entityDispatchPublication.isAccepted()
                && barrier != null && barrier.isDone()
                && !barrier.isCompletedExceptionally() && !barrier.isCancelled();
    }

    private boolean liveAuthority() {
        return !closed && Thread.currentThread() == worker && worker.exclusiveEntityPhase == this
                && !island.retired && domain.owner == island && domain.epoch == epoch
                && island.unitClaim.get() && island.claimHolder == worker
                && worker.currentDomain == domain
                && (worker.currentRegion == null || worker.currentRegion.domain == domain);
    }

    /** Authenticates the completed-queue region claim used by the serial pass. */
    static boolean owns(Region region) {
        if (!(Thread.currentThread() instanceof RegionWorkerThread worker)) return false;
        IslandEntityPhaseScope scope = worker.exclusiveEntityPhase;
        return scope != null && region != null && scope.liveAuthority()
                && queueJoinSucceeded(scope.domain, scope.epoch)
                && region.level == scope.runtime.level && region.domain == scope.domain
                && scope.runtime.islandOwnerOfCell(region.gridKey) == scope.island;
    }

    /** A context stamp cannot authorize a foreign level, island, or helper body. */
    boolean ownsTarget(Level level, BlockPos position) {
        return liveAuthority() && level == runtime.level
                && runtime.islandOwnerOfChunk(ChunkPos.pack(position)) == island;
    }

    @Override
    public void close() {
        if (closed || Thread.currentThread() != worker || worker.exclusiveEntityPhase != this) {
            throw new IllegalStateException("exclusive island entity scopes must close once in stack order on their worker");
        }
        worker.exclusiveEntityPhase = previous;
        closed = true;
    }
}
