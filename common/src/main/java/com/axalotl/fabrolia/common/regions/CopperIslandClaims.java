package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.golem.CopperGolem;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Secondary index of actual tick-list golems. Regions may change domain at a rebuild;
 * use the live region identity, never an island cached at insertion. */
public final class CopperIslandClaims {
    private final ConcurrentHashMap<Region, Set<CopperGolem>> cells = new ConcurrentHashMap<>();
    private final Object admissions = new Object();

    /** BUSY owns nothing; null owns only source; a Window owns source and the recorded
     * idle targets. Serialize only preflight for golem-bearing islands, never their ticks.
     * Otherwise simultaneous A->B and B->A strict requests can both acquire their source
     * and repeatedly force the other to roll back. */
    Window tryUnit(RegionizedServer runtime, IslandRegistry.PIsland source, IslandTickUnit.GlobalSprintRequest sprint) {
        if (cells.isEmpty() || !mayContainGolem(source)) {
            return trySource(source, sprint) ? null : Window.BUSY;
        }
        synchronized (admissions) {
            if (!trySource(source, sprint)) return Window.BUSY;
            boolean success = false;
            try {
                Window window = prepare(runtime, source, sprint != null);
                success = window != Window.BUSY;
                return window;
            } finally {
                if (!success) source.unitClaim.set(false);
            }
        }
    }

    private boolean mayContainGolem(IslandRegistry.PIsland source) {
        for (var region : cells.keySet()) if (region.domain == source.domain) return true;
        return false;
    }

    private static boolean trySource(IslandRegistry.PIsland source, IslandTickUnit.GlobalSprintRequest sprint) {
        return sprint == null ? source.tryClaimAutonomousWork() : sprint.tryClaim();
    }

    void add(Region region, CopperGolem golem) {
        cells.compute(region, (key, old) -> {
            var golems = old != null ? old : ConcurrentHashMap.<CopperGolem>newKeySet();
            golems.add(golem);
            return golems;
        });
    }

    void remove(Region region, CopperGolem golem) {
        cells.computeIfPresent(region, (key, golems) -> {
            golems.remove(golem);
            return golems.isEmpty() ? null : golems;
        });
    }

    /** Source is held, no vanilla phase has started. Never wait with a partial footprint. */
    private Window prepare(RegionizedServer runtime, IslandRegistry.PIsland source, boolean strict) {
        Window window = null;
        try {
            IslandRegistry.PIsland current = source;
            for (int next = 0;;) {
                for (var entry : cells.entrySet()) {
                    Region region = entry.getKey();
                    if (region.domain != current.domain || !runtime.ownsRegion(region)) continue;
                    for (CopperGolem golem : entry.getValue()) {
                        if (((RegionKeyed) golem).fabrolia$getRegionKey() != region.gridKey
                                || !CopperContainerFootprint.reachesOutside(runtime, current, golem)) continue;
                        if (window == null) window = new Window(source, strict);
                        CopperContainerFootprint.visit(runtime, golem, window);
                        if (window.missed) {
                            window.close();
                            return Window.BUSY;
                        }
                    }
                }
                // An opener in a claimed island can itself remember an old chest elsewhere.
                // Inspect its references only now, while that island really is quiescent.
                if (window == null || next == window.owners.size()) return window;
                current = window.owners.get(next++);
            }
        } catch (Throwable failure) {
            if (window != null) window.close();
            throw failure;
        }
    }

    /** An extra owner has no active unit of its own. Its unitClaim excludes hopper ticks,
     * address-routed jobs and Main just as it would during save. Its logical clock is never
     * written. A coordinator's *exact* command park may lend the entire window to Main. */
    static final class Window implements Consumer<IslandRegistry.PIsland>, AutoCloseable {
        static final Window BUSY = new Window(null, false);
        final IslandRegistry.PIsland source;
        final boolean strict;
        final ArrayList<IslandRegistry.PIsland> owners = new ArrayList<>(2);
        boolean missed;

        Window(IslandRegistry.PIsland source, boolean strict) {
            this.source = source;
            this.strict = strict;
        }

        @Override public void accept(IslandRegistry.PIsland owner) {
            // The global remainder has no island claim. It must be handled by the existing
            // Main compatibility path at entity admission; it is never forged as an owner.
            if (missed || owner == null || owner == source || owners.contains(owner)) return;
            owners.ensureCapacity(owners.size() + 1);
            synchronized (owner.unitAdmissionLock) {
                if (owner.retired || owner.globalSprintFenced != strict
                        || !strict && (owner.mainInterest() || NetherPortalTransfers.blocksAutonomousUnit(owner)
                            || owners.isEmpty() && FlatOwnerTasks.newContainerWindowMustYield())
                        || !owner.unitClaim.compareAndSet(false, true)) {
                    missed = true;
                    return;
                }
                owners.add(owner);
                owner.noteClaim("copper-container");
                owner.containerClaimSource = source;
            }
            var barrier = owner.domain.entityBarrier;
            if (owner.domain.entityPending.get() != 0 || barrier != null
                    && (!barrier.isDone() || barrier.isCompletedExceptionally())) missed = true;
        }

        @Override public void close() {
            for (int i = owners.size() - 1; i >= 0; i--) {
                var owner = owners.get(i);
                owner.containerClaimSource = null;
                owner.clearClaim();
                owner.unitClaim.set(false);
            }
            owners.clear();
        }
    }

    static boolean ownsExtra(IslandRegistry.PIsland owner, IslandRegistry.PIsland source) {
        return owner != null && source != null && owner.containerClaimSource == source
                && !owner.retired && owner.claimHolder == Thread.currentThread()
                && source.claimHolder == Thread.currentThread() && owner.unitClaim.get();
    }

    static boolean treeNeedsMain(RegionizedServer runtime, IslandRegistry.PIsland source, Entity root) {
        if (CopperContainerFootprint.needsMain(runtime, source, root)) return true;
        var riders = root.getPassengers();
        for (int i = 0; i < riders.size(); i++) {
            if (treeNeedsMain(runtime, source, riders.get(i))) return true;
        }
        return false;
    }

    /** On physical Main, protect only the spatial owners of a Main-owned copper AI call.
     * Main is already the sole executor of the joined global entity pass. An unknown
     * global cell additionally needs its global preparation work joined by the fence. */
    public static void onMain(ServerLevel level, CopperGolem golem, Runnable nativeCall) {
        var runtime = RegionScheduler.publishedForLevel(level);
        var needed = new ArrayList<IslandRegistry.PIsland>(2);
        Consumer<IslandRegistry.PIsland> collect = owner -> { if (!needed.contains(owner)) needed.add(owner); };
        var acquired = new ArrayList<IslandRegistry.PIsland>(2);
        try {
            for (;;) {
                collect.accept(runtime.islandOwnerOfChunk(golem.chunkPosition().pack()));
                // Acquisition can pump a routed command. Re-read the actual references after
                // every wait; never inspect another golem before its owner is quiescent.
                boolean changed = false;
                for (var owner : needed) {
                    if (owner == null || owner.retired || golem.level() != level) {
                        AsyncIslandPlayerTicks.runFencedMainMutation("global copper container", nativeCall);
                        return;
                    }
                    if (!IslandCommands.mainHolds(owner) && !IslandCommands.mainFenceOwns(owner)) {
                        acquired.ensureCapacity(acquired.size() + 1);
                        if (IslandCommands.mainClaimScoped(owner)) acquired.add(owner);
                        changed = true;
                    }
                }
                int n = needed.size();
                CopperContainerFootprint.visit(runtime, golem, collect);
                for (var entry : runtime.copperContainers.cells.entrySet()) {
                    var region = entry.getKey();
                    var domain = region.domain;
                    if (domain == null || !needed.contains(domain.owner) || !runtime.ownsRegion(region)
                            || !(IslandCommands.mainHolds(domain.owner) || IslandCommands.mainFenceOwns(domain.owner))) continue;
                    for (var other : entry.getValue()) {
                        if (other != golem && ((RegionKeyed) other).fabrolia$getRegionKey() == region.gridKey)
                            CopperContainerFootprint.visit(runtime, other, collect);
                    }
                }
                if (!changed && n == needed.size()) break;
            }
            RegionScheduler.runSuccessBodyIfHealthy(nativeCall);
        } finally {
            for (int i = acquired.size() - 1; i >= 0; i--) IslandCommands.mainRelease(acquired.get(i));
        }
    }

    public static boolean mainNeedsClaims(ServerLevel level) {
        if (Thread.currentThread() instanceof RegionWorkerThread) return false;
        var rs = RegionScheduler.publishedForLevel(level);
        return rs != null && rs.asyncActive && !rs.currentThreadHoldsAllClaims();
    }
}
