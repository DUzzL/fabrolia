package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.vmp.PublishedLong2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Predicate;

/** Copper users in native accessible entity sections, including TRACKED sections that
 * do not tick. Keys are spatial cells, not Region instances or cached island owners. */
public final class CopperIslandClaims {
    private static final long[] NO_CELLS = {};
    private volatile long[] occupiedCells = NO_CELLS;
    private volatile PublishedLong2ObjectMap<CopperGolem[]> cells;
    private final Object admissions = new Object();

    /** BUSY owns nothing; null owns only source; a Window owns source and the recorded
     * idle targets. Serialize only preflight for golem-bearing islands, never their ticks.
     * Otherwise simultaneous A->B and B->A strict requests can both acquire their source
     * and repeatedly force the other to roll back. */
    Window tryUnit(RegionizedServer runtime, IslandRegistry.PIsland source, IslandTickUnit.GlobalSprintRequest sprint) {
        if (occupiedCells.length == 0 || !mayContainGolem(runtime, source)) {
            if (!trySource(source, sprint)) return Window.BUSY;
            boolean keep = false;
            try {
                // Tracking can change between the hint and the CAS.
                keep = source.retired || !runtime.asyncActive || occupiedCells.length == 0
                        || !mayContainGolem(runtime, source);
                if (keep) return null;
            } finally {
                if (!keep) source.unitClaim.set(false);
            }
        }
        synchronized (admissions) {
            if (!trySource(source, sprint)) return Window.BUSY;
            boolean success = false;
            try {
                Window window = source.retired || !runtime.asyncActive ? null
                        : entityWorkInFlight(source) ? Window.BUSY : prepare(runtime, source, sprint != null);
                success = window != Window.BUSY;
                return window;
            } finally {
                if (!success) source.unitClaim.set(false);
            }
        }
    }

    private boolean mayContainGolem(RegionizedServer runtime, IslandRegistry.PIsland source) {
        for (long key : occupiedCells) if (runtime.islandOwnerOfCell(key) == source) return true;
        return false;
    }

    private static boolean trySource(IslandRegistry.PIsland source, IslandTickUnit.GlobalSprintRequest sprint) {
        return sprint == null ? source.tryClaimAutonomousWork() : sprint.tryClaim();
    }

    /** Borrowed, read-only membership snapshot; null for an empty cell. Checking membership
     * does not confer the cell's claim. Read the entities only under their physical owner. */
    CopperGolem[] openers(long gridKey) {
        var index = cells;
        return index == null ? null : index.get(gridKey);
    }

    // Only section membership writes serialize. Readers take no monitor, box no grid key,
    // and never see a partially modified array.
    synchronized void add(long key, CopperGolem golem) {
        var index = cells;
        if (index == null) cells = index = new PublishedLong2ObjectMap<>();
        var old = index.get(key);
        if (old == null) {
            long[] keys = Arrays.copyOf(occupiedCells, occupiedCells.length + 1);
            keys[keys.length - 1] = key;
            index.put(key, new CopperGolem[] { golem });
            occupiedCells = keys;
        } else {
            var next = Arrays.copyOf(old, old.length + 1);
            next[old.length] = golem;
            index.put(key, next);
        }
    }

    synchronized void remove(long key, CopperGolem golem) {
        var old = openers(key);
        if (old == null) return;
        for (int i = 0; i < old.length; i++) {
            if (old[i] != golem) continue;
            if (old.length > 1) {
                CopperGolem[] next = new CopperGolem[old.length - 1];
                System.arraycopy(old, 0, next, 0, i);
                System.arraycopy(old, i + 1, next, i, next.length - i);
                cells.put(key, next);
            } else if (occupiedCells.length == 1) {
                cells.remove(key);
                occupiedCells = NO_CELLS;
            } else {
                long[] keys = Arrays.copyOf(occupiedCells, occupiedCells.length - 1);
                for (int j = 0; j < keys.length; j++) {
                    if (keys[j] == key) { keys[j] = occupiedCells[keys.length]; break; }
                }
                cells.remove(key);
                occupiedCells = keys;
            }
            return;
        }
    }

    /** Source is held, no vanilla phase has started. Never wait with a partial footprint. */
    private Window prepare(RegionizedServer runtime, IslandRegistry.PIsland source, boolean strict) {
        Window window = null;
        try {
            for (int pass = 0; pass < 2; pass++) {
                var keys = (pass == 0 ? source.cells : source.haloCells).iterator();
                while (keys.hasNext()) {
                    long key = keys.nextLong();
                    var golems = openers(key);
                    if (golems == null || runtime.islandOwnerOfCell(key) != source) continue;
                    for (CopperGolem golem : golems) {
                        // Only a ticking golem can start transport. A merely tracked golem
                        // matters as an opener, and is checked at its actual queried cell.
                        if (((RegionKeyed) golem).fabrolia$getRegionKey() != key || golem.isRemoved()
                                || golem.level() != runtime.level
                                || runtime.regionizer().grid.keyFor(golem.chunkPosition().pack()) != key
                                || !CopperContainerFootprint.reachesOutside(runtime, source, golem)) continue;
                        if (window == null) window = new Window(source, strict);
                        CopperContainerFootprint.visit(runtime, golem, window);
                        if (window.missed) {
                            window.close();
                            return Window.BUSY;
                        }
                        // Unclaimable global cells are not BUSY. Recheck the live golem and
                        // its actual opener dependencies at its ordinal; route only that body.
                    }
                }
            }
            return window;
        } catch (Throwable failure) {
            if (window != null) window.close();
            throw failure;
        }
    }

    private static boolean entityWorkInFlight(IslandRegistry.PIsland owner) {
        var barrier = owner.domain.entityBarrier;
        return owner.domain.entityPending.get() != 0 || barrier != null
                && (!barrier.isDone() || barrier.isCompletedExceptionally());
    }

    /** An extra owner has no active unit of its own. Its unitClaim excludes hopper ticks,
     * address-routed jobs and Main just as it would during save. Its logical clock is never
     * written. A coordinator's *exact* command park may lend the entire window to Main. */
    static final class Window implements Predicate<IslandRegistry.PIsland>, AutoCloseable {
        static final Window BUSY = new Window(null, false);
        final IslandRegistry.PIsland source;
        final boolean strict;
        final ArrayList<IslandRegistry.PIsland> owners = new ArrayList<>(2);
        boolean missed;

        Window(IslandRegistry.PIsland source, boolean strict) {
            this.source = source;
            this.strict = strict;
        }

        @Override public boolean test(IslandRegistry.PIsland owner) {
            if (missed || owner == null) return false;
            if (owner == source || owners.contains(owner)) return true;
            owners.ensureCapacity(owners.size() + 1);
            synchronized (owner.unitAdmissionLock) {
                if (owner.retired || owner.globalSprintFenced != strict
                        || !strict && (owner.mainInterest() || NetherPortalTransfers.blocksAutonomousUnit(owner)
                            || owners.isEmpty() && FlatOwnerTasks.newContainerWindowMustYield())
                        || !owner.unitClaim.compareAndSet(false, true)) {
                    missed = true;
                    return false;
                }
                owners.add(owner);
                owner.noteClaim("copper-container");
                owner.containerClaimSource = source;
            }
            missed = entityWorkInFlight(owner);
            return !missed;
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

    /** A scheduled chest recheck is not necessarily inside a golem's transport scope.
     * In particular, owning every island does not join outstanding ownerless work. */
    public static boolean unownedOpenerState(ServerLevel level, BlockPos open) {
        var runtime = RegionScheduler.publishedForLevel(level);
        if (runtime == null || !runtime.asyncActive) return false;
        if (runtime.islandOwnerOfChunk(ChunkPos.pack(open)) == null
                && RegionalPlayerEntityTicksV2.isPhysicalServerThread(level.getServer())) {
            return !RegionScheduler.globalWorkJoined(level) && !RegionScheduler.ownsExactExecutionTurn();
        }
        return !RegionScheduler.ownsCurrentContext(level, open);
    }

    static boolean treeNeedsMain(RegionizedServer runtime, IslandRegistry.PIsland source, Entity root) {
        if (CopperContainerFootprint.needsMain(runtime, source, root)) return true;
        var riders = root.getPassengers();
        for (int i = 0; i < riders.size(); i++) {
            if (treeNeedsMain(runtime, source, riders.get(i))) return true;
        }
        return false;
    }

    /** Never pump from a footprint visitor: a routed command may replace the body or
     * change its owner, invalidating even the next read of its brain. */
    public static void onMain(ServerLevel level, CopperGolem golem, Runnable nativeCall) {
        if (!RegionalPlayerEntityTicksV2.isPhysicalServerThread(level.getServer())) {
            throw new IllegalStateException("Main copper turn entered off physical Main");
        }
        var runtime = RegionScheduler.publishedForLevel(level);
        if (runtime == null) return;
        if (runtime.currentThreadHoldsAllClaims()) {
            runUnderMainFence(level, golem, runtime, nativeCall);
            return;
        }
        if (!liveBody(level, golem, runtime)) return;
        requireJoinedGlobalWork(level);
        var cover = new MainCover();
        var acquired = new ArrayList<IslandRegistry.PIsland>(2);
        try {
            for (;;) {
                var owner = runtime.islandOwnerOfChunk(golem.chunkPosition().pack());
                cover.missing = null;
                if (cover.test(owner) && CopperContainerFootprint.visit(runtime, golem, cover)) {
                    RegionScheduler.runSuccessBodyIfHealthy(nativeCall);
                    return;
                }
                var missing = cover.missing;
                if (missing == null || missing.retired) {
                    // An opener moved out of its published cell. The ordinary joined
                    // ownerless case does not require this all-island fence.
                    AsyncIslandPlayerTicks.runFencedMainMutation("unstable copper opener",
                            () -> runUnderMainFence(level, golem, runtime, nativeCall));
                    return;
                }
                acquired.ensureCapacity(acquired.size() + 1);
                if (IslandCommands.mainClaimScoped(missing)) acquired.add(missing);
                if (!liveBody(level, golem, runtime)) return;
                requireJoinedGlobalWork(level);
                // Start again with the live position and owner, not the interrupted traversal.
            }
        } finally {
            for (int i = acquired.size() - 1; i >= 0; i--) IslandCommands.mainRelease(acquired.get(i));
        }
    }

    private static void runUnderMainFence(ServerLevel level, CopperGolem golem,
                                          RegionizedServer runtime, Runnable nativeCall) {
        if (!liveBody(level, golem, runtime)) return;
        requireJoinedGlobalWork(level);
        var owner = runtime.islandOwnerOfChunk(golem.chunkPosition().pack());
        if (!runtime.currentThreadHoldsAllClaims() || (owner != null && owner.retired)) {
            throw new IllegalStateException("Main copper turn missing a live owner under its level fence");
        }
        RegionScheduler.runSuccessBodyIfHealthy(nativeCall);
    }

    private static boolean liveBody(ServerLevel level, CopperGolem golem, RegionizedServer runtime) {
        return golem.level() == level && !golem.isRemoved()
                && RegionScheduler.publishedForLevel(level) == runtime
                && level.getEntity(golem.getId()) == golem;
    }

    private static void requireJoinedGlobalWork(ServerLevel level) {
        if (!RegionScheduler.globalWorkJoined(level)) {
            throw new IllegalStateException("Main copper turn entered before global work joined");
        }
    }

    private static final class MainCover implements Predicate<IslandRegistry.PIsland> {
        IslandRegistry.PIsland missing;

        @Override public boolean test(IslandRegistry.PIsland owner) {
            if (owner == null || (!owner.retired
                    && (IslandCommands.mainHolds(owner) || IslandCommands.mainFenceOwns(owner)))) return true;
            missing = owner;
            return false;
        }
    }

    public static boolean mainNeedsClaims(ServerLevel level) {
        if (Thread.currentThread() instanceof RegionWorkerThread) return false;
        var rs = RegionScheduler.publishedForLevel(level);
        return rs != null && IslandCommands.requiresMainFence(rs);
    }
}
