package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.config.FabroliaConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;

/**
 * Island-owned block-entity ticks with a Main-side residual pass.
 *
 * <p>Autonomous units execute their own cells serially on the claim-holding
 * coordinator. Main's residual partitions the remaining cells per island and
 * fans the slices out to the region workers under a fail-stop gate, ticking
 * the isolated remainder itself while pumping the level's main-thread executor.
 *
 * <p>F2b (2026-07-12): the per-tick partition no longer walks
 * {@code Level.blockEntityTickers} at all. Block entities never change chunk,
 * so cell membership is a registration-time constant — the persistent
 * per-cell index on {@link RegionizedServer} ({@code beCells}) is maintained
 * at {@code addBlockEntityTicker} (LevelMixin stash → drained here) and
 * pruned by the amortized sweep. The per-tick cost drops from O(all tickers)
 * (~5.6 ms at ~20k tickers, 2026-07-11 profiles) to O(populated cells)
 * (a few hundred ordered-map reads).
 *
 * <p>Ordering: immutable per-cell snapshots carry registration sequences next
 * to their wrappers. Each slice merges these ordered lists to restore vanilla's
 * global registration order, including moving pistons on opposite cell edges.
 *
 * <p>{@code Level.blockEntityTickers} stays authoritatively maintained
 * (pending flush + order-preserving sweep) for mod compat and as the bulk
 * rebuild source whenever the index was cold or missed adds
 * ({@code beCellsValid == false}).
 */
public final class IslandBlockEntityTicks {

    private IslandBlockEntityTicks() {}

    /**
     * Vanilla's per-ticker {@code shouldTickBlocksAt} gate for ONE pass,
     * answered from ONE completed-simulation snapshot captured at pass start
     * (the immutable view {@code DistanceManager.inBlockTickingRange} reads).
     * Vanilla reads per-BE but is naturally coherent — main is the only thread
     * and no ticket mutation can land mid-loop. Here a pass runs on a worker
     * WHILE main's straggler pump executes chunk-system tasks (ticket
     * placements → level recomputes), so a live per-BE read could flip
     * mid-loop: a moving-piston BE skipped for one tick while the rest of its
     * contraption advanced = flying machines breaking at one chunk's
     * entry/exit (bomber fleet report, 2026-07-06). The pinned snapshot
     * restores vanilla's whole-phase coherence; its page cursor makes a
     * per-BE read one array index while the pass stays inside a 32×32 page.
     */
    static final class ChunkTickGate {
        private final java.util.function.LongPredicate blockTicking;

        private ChunkTickGate(java.util.function.LongPredicate blockTicking) {
            this.blockTicking = blockTicking;
        }

        static ChunkTickGate forLevel(ServerLevel level) {
            return new ChunkTickGate(((BlockTickingPassAccess) level).fabrolia$blockTickingPass());
        }

        boolean ticks(long chunkKey) {
            return blockTicking.test(chunkKey);
        }
    }

    private static Object registryLock(Level level) {
        return ((BlockEntityTickerAccess) level).fabrolia$blockEntityTickerLock();
    }

    /** Registration and its derived-index receipt are one ordered transaction. */
    public static void registerTicker(Level level, TickingBlockEntity ticker, Runnable registration) {
        synchronized (registryLock(level)) {
            registration.run();
            RegionScheduler.onBlockEntityTickerAdded(level, ticker);
        }
    }

    public static void setTickingBlockEntities(Level level, boolean ticking) {
        synchronized (registryLock(level)) {
            level.tickingBlockEntities = ticking;
        }
    }

    /** Also used by vanilla's addAll/clear replacement; no ticker body runs here. */
    public static boolean flushPendingTickers(Level level) {
        synchronized (registryLock(level)) {
            if (level.pendingBlockEntityTickers.isEmpty()) return false;
            level.blockEntityTickers.addAll(level.pendingBlockEntityTickers);
            level.pendingBlockEntityTickers.clear();
            return true;
        }
    }


    /** Vanilla-fallback lifecycle cleanup, called after vanilla completed.
     *  A synchronous fallback has no consumer for the derived cell index, so
     *  keeping it warm only retains wrappers that vanilla has already removed.
     *  Frozen async is different: an older unit may still hold/read the map;
     *  no vanilla removals happen while frozen, so only its bounded add stash
     *  is discarded. Main thread. */
    public static void maintainIndexOnly(Level level) {
        if (!(level instanceof ServerLevel sl)) return;
        RegionizedServer rs = RegionScheduler.forLevel(sl);
        if (rs == null) return;
        boolean frozenAsync = rs.asyncActive && RegionScheduler.isActive()
                && !level.tickRateManager().runsNormally();
        synchronized (registryLock(level)) {
            if (frozenAsync) {
                if (rs.beCellsValid) {
                    rs.drainPendingBeAdds();
                } else {
                    rs.discardPendingBeAdds();
                }
                return;
            }
            rs.releaseBeIndexForVanillaFallback();
        }
    }

    /**
     * Main maintenance overlaps autonomous units. Fold the authoritative
     * pending list and publish its derived index under registration's gate;
     * readers retain immutable cell lists and never take this gate to tick.
     */
    public static void preUnitMaintenance(ServerLevel level, RegionizedServer rs) {
        synchronized (registryLock(level)) {
            flushPendingTickers(level);
            if (!rs.beCellsValid) {
                rs.rebuildBeCells(level.blockEntityTickers);
            } else {
                rs.drainPendingBeAdds();
            }
        }
    }

    /**
     * A2: one island's BE phase, run by its unit coordinator after its entity
     * barrier — single-threaded per island, member cells in ascending key
     * order (the same order the AVL-map walk gives the sync path), then the
     * BEs registered mid-unit (vanilla ticks those the same tick via its
     * direct-list add; the persistent index only adopts them at the next
     * maintenance pass). Main may publish a replacement index or cell list
     * during the unit; each pass retains its captured immutable lists. Removed
     * wrappers land in {@code rs.unitRemovedBes} for main's post-slot sweep.
     */
    public static void tickIslandCells(ServerLevel level, RegionizedServer rs,
                                       IslandRegistry.PIsland island) {
        if (!level.tickRateManager().runsNormally()) {
            return; // frozen: vanilla ticks no BEs, neither do units
        }
        // owned cells + halo cells (partition parity: a moving-piston BE
        // in a halo-domain cell froze after its first unit — nobody's BE
        // pass covered it; tickSlice's per-chunk gate keeps non-ticking
        // halo chunks no-op). Sorted ascending = deterministic AVL order.
        long[] owned = island.cells.toLongArray();
        long[] halo = island.haloCells.toLongArray();
        long[] keys = new long[owned.length + halo.length];
        System.arraycopy(owned, 0, keys, 0, owned.length);
        System.arraycopy(halo, 0, keys, owned.length, halo.length);
        java.util.Arrays.sort(keys);
        final ChunkTickGate tickable = ChunkTickGate.forLevel(level);
        // ONE TICK PER WRAPPER PER UNIT (2026-07-15 production trace, 11-then-fail):
        // a fresh BE is dual-written to pendingUnitBes (same-unit tick, vanilla
        // direct-add parity) AND pendingBeAdds, which MAIN folds into beCells every
        // main tick CONCURRENTLY with in-flight units (the deadline drive never
        // awaits). When the fold lands between registration and this pass, the
        // wrapper appears in BOTH lists: a new moving-piston BE then ticked
        // 0→0.5→1.0 in ONE unit and finalized a tick early — the whole machine
        // cycle compressed by exactly one island tick (period 7→6), the early
        // faller's forced continuation met the sweeper's still-live moving_piston,
        // and vanilla's one-shot landing gate silently destroyed the source block.
        // Drain fresh FIRST, skip its members in the cell passes (identity — the
        // fold inserts the same wrapper object), tick it LAST (vanilla appends
        // pending tickers at the list end). BEs registered DURING this BE phase
        // now land in pendingUnitBes after the drain → first tick next unit =
        // vanilla pending-list parity (the old shape ticked them same-unit).
        ArrayList<TickingBlockEntity> fresh = island.drainPendingUnitBes();
        // ISLAND-SIDE CARRY (2026-07-17): a fresh BE's beCells visibility
        // arrives via MAIN's fold at MAIN cadence — an island running faster
        // than main (per-island rate / sprint / tick-rate ramps) would only
        // tick it when the fold lands, stretching a moving piston's 3-unit
        // lifecycle to main's pace (caravan at island rate 40 paced at 20;
        // observer engines broke one stroke in). Keep every fresh wrapper in
        // carryBes and tick it EVERY unit until the cell pass first sees it
        // (fold landed); the skip-set covers both lists, so it still ticks
        // exactly once per unit regardless of which side owns it.
        ArrayList<TickingBlockEntity> pendingTail = island.carryBes;
        if (fresh != null) {
            pendingTail.addAll(fresh);
        }
        java.util.Set<TickingBlockEntity> pendingSet = null;
        java.util.Set<TickingBlockEntity> seen = null;
        if (!pendingTail.isEmpty()) {
            pendingSet = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            pendingSet.addAll(pendingTail);
            seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        }
        ArrayList<BlockEntityTickerList> unitLists = new ArrayList<>(keys.length);
        var cells = rs.beCells;
        for (long key : keys) {
            BlockEntityTickerList cell = cells.get(key);
            if (cell != null && !cell.isEmpty()) {
                unitLists.add(cell);
            }
        }
        // task #14: registration-order merge across the unit's cells — same
        // vanilla mutual-order restoration as the sync slices.
        tickMerged(rs, unitLists, rs.unitRemovedBes, tickable, pendingSet, seen);
        if (!pendingTail.isEmpty()) {
            // OWNERSHIP GATE (2026-07-17 fail-closed): a carried wrapper whose
            // cell no longer belongs to this island (rebuild reassigned it —
            // e.g. a flying machine crossing into freshly-born territory) must
            // neither tick here nor stay carried: its true owner ticks it via
            // the fold. "Seen in my cell pass" can never fire for a foreign
            // cell, so without this gate the carry held it forever, ticking
            // cross-domain against the owner's live unit.
            pendingTail.removeIf(t -> {
                if (t.isRemoved()) return true;
                BlockPos tp = t.getPos();
                if (tp == null) return false; // posless stub: harmless, keep
                return rs.islandOwnerOfChunk(ChunkPos.pack(tp)) != island;
            });
            if (!pendingTail.isEmpty()) {
                tickSlice(pendingTail, rs.unitRemovedBes, tickable, null, null);
            }
            // retire carried entries the fold has delivered (seen in a cell
            // list this unit) or that finalized/died — the rest keep ticking
            // island-side next unit.
            final java.util.Set<TickingBlockEntity> seenF = seen;
            pendingTail.removeIf(t -> t.isRemoved() || (seenF != null && seenF.contains(t)));
        }
    }

    /**
     * @return true — phase handled; false — caller must run the vanilla
     *         serial {@code tickBlockEntities} body.
     */
    public static boolean tickPartitioned(Level level) {
        if (!RegionScheduler.isActive()) {
            return false;
        }
        if (!(level instanceof ServerLevel sl)) return false;
        RegionizedServer rs = RegionScheduler.forLevel(sl);
        if (rs == null) return false;
        // A2: with async islands live, this path MUST handle the phase no
        // matter the island count or the F2 flag — island cells were already
        // ticked inside their units this tick, and the vanilla fallback would
        // tick them a second time from the flat list. (Frozen tick rate falls
        // through: units skip their BE pass then too, and vanilla's own body
        // gates itself — nothing double-ticks.)
        if (!rs.asyncActive) {
            if (!FabroliaConfig.fabroliaIslandBlockEntityTicks) return false;
            if (rs.islands().size() < 2) return false;
        } else if (IslandCommands.hasRoutedPending()) {
            // A8 third batch point: by the BE slot every fast unit that hit a
            // routed command this tick has parked — completing them HERE lets
            // the coordinator release its claim long before the next drive's
            // submit phase (the resume-vs-submit race halved cadence).
            IslandCommands.drainRoutedBatch();
        }
        if (!level.tickRateManager().runsNormally()) return false;

        setTickingBlockEntities(level, true);
        try {
            preUnitMaintenance(sl, rs);

            int islandCount = rs.islands().size();
            @SuppressWarnings("unchecked")
            ArrayList<BlockEntityTickerList>[] perIsland = new ArrayList[islandCount];
            int[] perIslandSize = new int[islandCount];
            ArrayList<BlockEntityTickerList> isolatedCells = null;
            int isolatedSize = 0;
            final ConcurrentLinkedQueue<TickingBlockEntity> removed = new ConcurrentLinkedQueue<>();
            BlockEntityTickerList posless = null;
            var cells = rs.beCells;
            long[] cellKeys = cells.keySet().toLongArray();
            java.util.Arrays.sort(cellKeys);
            for (long cellKey : cellKeys) {
                BlockEntityTickerList cell = cells.get(cellKey);
                if (cell == null || cell.isEmpty()) continue;
                if (cellKey == Long.MIN_VALUE) {
                    posless = cell;
                    continue;
                }
                if (rs.asyncActive) {
                    // Exact complement of tickIslandCells: units own both their
                    // member cells and halo, never the global/positionless lane.
                    Region cellRegion = rs.regionizer().peekByGridKey(cellKey);
                    if (cellRegion != null && cellRegion.domain != null
                            && cellRegion.domain != rs.globalDomain) {
                        continue;
                    }
                }
                int island = rs.islandForCell(cellKey);
                if (island >= 0 && island < islandCount) {
                    ArrayList<BlockEntityTickerList> lists = perIsland[island];
                    if (lists == null) {
                        perIsland[island] = lists = new ArrayList<>(4);
                    }
                    lists.add(cell);
                    perIslandSize[island] += cell.size();
                } else {
                    if (isolatedCells == null) {
                        isolatedCells = new ArrayList<>(4);
                    }
                    isolatedCells.add(cell);
                    isolatedSize += cell.size();
                }
            }
            ArrayList<Slice> slices = new ArrayList<>(islandCount);
            for (int i = 0; i < islandCount; i++) {
                if (perIsland[i] != null) {
                    slices.add(new Slice(perIsland[i], perIslandSize[i]));
                }
            }
            if (slices.isEmpty()) {
                if (isolatedCells != null) {
                    tickCells(sl, rs, isolatedCells, removed);
                }
                if (posless != null) {
                    tickSlice(posless, removed, ChunkTickGate.forLevel(sl));
                }
                sweepRemoved(level, rs, removed);
                return true;
            }
            slices.sort((a, b) -> Integer.compare(b.size(), a.size()));
            final int SMALL_SLICE_THRESHOLD = 3;
            if (slices.size() > 2) {
                ArrayList<BlockEntityTickerList> tinyMerged = null;
                int tinySize = 0;
                int writeIdx = 0;
                for (int readIdx = 0; readIdx < slices.size(); readIdx++) {
                    Slice s = slices.get(readIdx);
                    if (s.size() < SMALL_SLICE_THRESHOLD && readIdx > 0) {
                        if (tinyMerged == null) {
                            tinyMerged = new ArrayList<>();
                        }
                        tinyMerged.addAll(s.cells());
                        tinySize += s.size();
                    } else {
                        slices.set(writeIdx++, s);
                    }
                }
                while (slices.size() > writeIdx) {
                    slices.remove(slices.size() - 1);
                }
                if (tinyMerged != null && !tinyMerged.isEmpty()) {
                    if (isolatedCells == null) {
                        isolatedCells = tinyMerged;
                    } else {
                        isolatedCells.addAll(tinyMerged);
                    }
                    isolatedSize += tinySize;
                }
            }

            RegionScheduler.drainMainSafeNow();
            final ArrayList<BlockEntityTickerList> isolatedFinal = isolatedCells;
            final FailStopGate gate = new FailStopGate(slices.size());
            Runnable runner = () -> {
                int idx;
                while ((idx = gate.claimSlice()) >= 0) {
                    try {
                        tickCells(sl, rs, slices.get(idx).cells(), removed);
                        RegionScheduler.throwWorkerFailureIfAny();
                    } catch (Throwable th) {
                        Throwable winner = gate.failAndSelect(th);
                        rethrow(winner);
                    } finally {
                        gate.finish();
                    }
                }
            };
            int workerRunners = Math.min(slices.size(), Math.max(1, RegionScheduler.workerCount()));
            for (int i = 0; i < workerRunners && !gate.failed(); i++) {
                try {
                    RegionScheduler.enqueueGlobal(runner);
                } catch (Throwable submissionFailure) {
                    gate.failAndSelect(submissionFailure);
                    break;
                }
            }
            if (workerRunners > 0 && !gate.failed()) {
                try {
                    RegionScheduler.wakeWorkers();
                } catch (Throwable wakeFailure) {
                    gate.failAndSelect(wakeFailure);
                }
            }
            if (isolatedFinal != null && gate.claimMainLane()) {
                try {
                    tickCells(sl, rs, isolatedFinal, removed);
                    RegionScheduler.throwWorkerFailureIfAny();
                } catch (Throwable th) {
                    gate.failAndSelect(th);
                } finally {
                    gate.finish();
                }
            }
            runner.run();
            while (gate.hasActive()) {
                if (gate.failed()) {
                    LockSupport.parkNanos(20_000L);
                    continue;
                }
                boolean drained;
                try {
                    drained = RegionScheduler.callSuccessBooleanIfHealthy(
                            () -> sl.getChunkSource().mainThreadProcessor.pollTask());
                } catch (Throwable taskFailure) {
                    gate.failAndSelect(taskFailure);
                    drained = false;
                }
                if (!drained) {
                    LockSupport.parkNanos(20_000L);
                }
            }
            Throwable failure = gate.failure();
            if (failure != null) {
                rethrow(failure);
            }
            if (posless != null) {
                tickSlice(posless, removed, ChunkTickGate.forLevel(sl));
            }
            sweepRemoved(level, rs, removed);
            return true;
        } finally {
            setTickingBlockEntities(level, false);
        }
    }

    /** One slice: its cells' wrappers merged by REGISTRATION SEQUENCE (task #14):
     *  vanilla's flat list runs in registration order, and for same-tick
     *  moving-piston pairs straddling a cell seam that mutual order is
     *  load-bearing. Each cell list is already seq-ascending (appends), so a
     *  k-way heap merge restores the exact vanilla order at O(n log cells);
     *  one pinned block-ticking snapshot for the slice. Generic lists retain
     *  the missing-sequence MIN_VALUE rule and original heap tie behavior. */
    private static void tickCells(ServerLevel level, RegionizedServer rs,
                                  ArrayList<BlockEntityTickerList> cells,
                                  java.util.Queue<TickingBlockEntity> removed) {
        tickMerged(rs, cells, removed, ChunkTickGate.forLevel(level), null, null);
    }

    static void tickMerged(RegionizedServer rs,
                           java.util.List<? extends java.util.List<TickingBlockEntity>> lists,
                           java.util.Queue<TickingBlockEntity> removed,
                           ChunkTickGate tickable,
                           java.util.Set<TickingBlockEntity> skip,
                           java.util.Set<TickingBlockEntity> seen) {
        int live = 0;
        java.util.List<TickingBlockEntity> only = null;
        for (int i = 0, n = lists.size(); i < n; i++) {
            java.util.List<TickingBlockEntity> l = lists.get(i);
            if (l != null && !l.isEmpty()) {
                live++;
                only = l;
            }
        }
        if (live == 0) return;
        if (live == 1) {
            tickSlice(only, removed, tickable, skip, seen);
            return;
        }
        BlockEntityTickerList.Merge merge = new BlockEntityTickerList.Merge(lists, rs.beSeqOf);
        while (merge.advance()) {
            tickOne(merge.ticker(), removed, tickable, skip, seen);
        }
    }

    /**
     * Tick all BEs in one cell list.
     *
     * <p>{@code getPos() == null} means Lithium's sleeping stub
     * ({@code SLEEPING_BLOCK_ENTITY_TICKER}: isRemoved=false, getPos=null) —
     * skip the tick but NEVER treat it as removed; the wrapper must survive
     * (in the vanilla list AND in its cell) for {@code wakeUpNow()} to rebind
     * it. Vanilla's removed shim ({@code NULL_TICKER}) reports isRemoved=true
     * with pos=BlockPos.ZERO, so genuine removals always take the isRemoved
     * branch.
     */

    private static void tickSlice(java.util.List<TickingBlockEntity> slice,
                                  java.util.Queue<TickingBlockEntity> removed,
                                  ChunkTickGate tickable) {
        tickSlice(slice, removed, tickable, null, null);
    }

    private static void tickSlice(java.util.List<TickingBlockEntity> slice,
                                  java.util.Queue<TickingBlockEntity> removed,
                                  ChunkTickGate tickable,
                                  java.util.Set<TickingBlockEntity> skip,
                                  java.util.Set<TickingBlockEntity> seen) {
        // One gate per pass: see ChunkTickGate for the coherence argument.
        for (int i = 0, m = slice.size(); i < m; i++) {
            tickOne(slice.get(i), removed, tickable, skip, seen);
        }
    }

    private static void tickOne(TickingBlockEntity t,
                                java.util.Queue<TickingBlockEntity> removed,
                                ChunkTickGate tickable,
                                java.util.Set<TickingBlockEntity> skip,
                                java.util.Set<TickingBlockEntity> seen) {
        if (skip != null && skip.contains(t)) {
            if (seen != null) seen.add(t); // fold delivered it: carry retires it
            return; // pending/carried: ticked once in the tail pass
        }
        if (t.isRemoved()) { removed.add(t); return; }
        BlockPos pos = t.getPos();
        if (pos == null) return; // Lithium sleeping stub — keep it, it wakes later
        if (tickable.ticks(ChunkPos.pack(pos))) RegionScheduler.runSuccessBodyIfHealthy(t::tick);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T) failure;
    }


    /** Post-barrier (main, single-threaded): drop dead tickers from the vanilla list
     *  AND the per-cell index. Amortized exactly as before (<256 wrappers / ≤64 ticks
     *  of lag bound; removed wrappers are behaviorally invisible meanwhile — skipped
     *  by isRemoved exactly like vanilla's inline iterator.remove would skip them).
     *  The vanilla removeIf preserves list order (hopper order stays vanilla); the
     *  cell prune touches only affected cells, keyed by the registration-time cell
     *  (getPos() is useless post-removal — NULL_TICKER reports ZERO). */
    private static void sweepRemoved(Level level, RegionizedServer rs,
                                     java.util.Queue<TickingBlockEntity> removed) {
        java.util.HashSet<TickingBlockEntity> pending = rs.pendingBeSweep;
        TickingBlockEntity t;
        while ((t = removed.poll()) != null) {
            pending.add(t);
        }
        // A2: dead tickers the island units collected during their BE phases.
        while ((t = rs.unitRemovedBes.poll()) != null) {
            pending.add(t);
        }
        if (pending.isEmpty()) return;
        if (pending.size() < 256 && (level.getGameTime() & 63) != 0) return;
        synchronized (registryLock(level)) {
        level.blockEntityTickers.removeIf(pending::contains);
        it.unimi.dsi.fastutil.longs.LongOpenHashSet touched =
                new it.unimi.dsi.fastutil.longs.LongOpenHashSet(Math.min(64, pending.size()));
        for (TickingBlockEntity dead : pending) {
            rs.beSeqOf.remove(dead);
            long cell = rs.beCellOf.removeLong(dead);
            if (cell != Long.MAX_VALUE) {
                touched.add(cell);
            }
        }
        for (it.unimi.dsi.fastutil.longs.LongIterator it = touched.iterator(); it.hasNext(); ) {
            long cell = it.nextLong();
            BlockEntityTickerList list = rs.beCells.get(cell);
            if (list != null) {
                // Copy-on-write: a unit mid-pass may still iterate the old
                // list; it skips these wrappers via isRemoved exactly like
                // vanilla's inline removal would have.
                BlockEntityTickerList next = list.without(pending);
                if (next.isEmpty()) {
                    rs.beCells.remove(cell);
                } else {
                    rs.beCells.put(cell, next);
                }
            }
        }
        }
        pending.clear();
    }

    /** Fail-stop fan-out gate: workers claim slice ordinals until one fails;
     *  the first failure wins and every later claim returns -1. */
    static final class FailStopGate {
        private final int sliceCount;
        private int nextSlice;
        private int active;
        private Throwable failure;

        FailStopGate(int sliceCount) {
            this.sliceCount = sliceCount;
        }

        synchronized int claimSlice() {
            if (failure != null || nextSlice >= sliceCount) return -1;
            active++;
            return nextSlice++;
        }

        synchronized boolean claimMainLane() {
            if (failure != null) return false;
            active++;
            return true;
        }

        synchronized boolean fail(Throwable thrown) {
            if (failure != null) return false;
            failure = thrown;
            notifyAll();
            return true;
        }

        Throwable failAndSelect(Throwable candidate) {
            fail(candidate);
            Throwable winner = RegionScheduler.publishFatalAndSelectWinner(candidate);
            synchronized (this) {
                failure = winner;
                notifyAll();
                return winner;
            }
        }

        synchronized void finish() {
            active--;
            notifyAll();
        }

        synchronized boolean hasActive() {
            return active != 0;
        }

        synchronized boolean failed() {
            return failure != null;
        }

        synchronized Throwable failure() {
            return failure;
        }
    }

    private record Slice(ArrayList<BlockEntityTickerList> cells, int size) {
    }
}
