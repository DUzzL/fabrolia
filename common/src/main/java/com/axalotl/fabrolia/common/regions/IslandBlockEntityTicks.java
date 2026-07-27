package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.config.FabroliaConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * F2 (Stage C) — island-partitioned block-entity ticks.
 *
 * <p>Replaces {@link Level#tickBlockEntities()}'s flat-list iteration with a
 * per-island parallel pass. Within an island all cells belong to the same
 * slice — there is no intra-island parallelism (a piston in chunk A pushing
 * into chunk B, both in the same island, is serial under that island's
 * worker). Across islands the separation invariant (≥16-chunk unloaded gap)
 * rules out any side effect from reaching a chunk owned by a different slice.
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
 * <p>Ordering: per-cell lists append in registration order under the same
 * lock as the vanilla list, so within a cell (8×8 chunks) the order is
 * exactly vanilla's. Cells are iterated ascending by grid key, so the
 * cross-cell order within an island is deterministic and stable across
 * island rebuilds and restarts — vanilla itself only guarantees chunk-load
 * order here, which varies per session; this is the same variation class.
 *
 * <p>{@code Level.blockEntityTickers} stays authoritatively maintained
 * (pending flush + order-preserving sweep) for mod compat and as the bulk
 * rebuild source whenever the index was cold or missed adds
 * ({@code beCellsValid == false}).
 */
public final class IslandBlockEntityTicks {

    private IslandBlockEntityTicks() {}

    /** A slice = one island's cells (or the isolated lane), executed serially. */
    private record Slice(ArrayList<ArrayList<TickingBlockEntity>> cells, int size) {}

    /** Vanilla-fallback bookkeeping: fold stashed registrations into the cell
     *  index (cells aren't consumed by the fallback, but keeping them fresh
     *  bounds the stash and confines the bulk rebuild to true cold starts —
     *  the only moment no ticker can be Lithium-sleeping). Main thread. */
    public static void maintainIndexOnly(Level level) {
        if (!(level instanceof ServerLevel sl)) return;
        RegionizedServer rs = RegionScheduler.forLevel(sl);
        if (rs == null || !rs.beCellsValid) return; // cold: next partitioned pass bulk-builds
        rs.drainPendingBeAdds();
    }

    /**
     * A2 pre-unit maintenance (main, entity slot, BEFORE units dispatch):
     * vanilla pending flush + persistent-index fold, so this tick's island
     * units see every BE registered up to the entity slot — the same
     * visibility vanilla's BE-phase flush would have given them. Main is
     * quiescent here (no units in flight yet), the only legal window for
     * beCells mutations outside the BE slot.
     */
    public static void preUnitMaintenance(ServerLevel level, RegionizedServer rs) {
        if (!level.pendingBlockEntityTickers.isEmpty()) {
            level.blockEntityTickers.addAll(level.pendingBlockEntityTickers);
            level.pendingBlockEntityTickers.clear();
        }
        if (!rs.beCellsValid) {
            rs.rebuildBeCells(level.blockEntityTickers);
        } else {
            rs.drainPendingBeAdds();
        }
    }

    /**
     * A2: one island's BE phase, run by its unit coordinator after its entity
     * barrier — single-threaded per island, member cells in ascending key
     * order (the same order the AVL-map walk gives the sync path), then the
     * BEs registered mid-unit (vanilla ticks those the same tick via its
     * direct-list add; the persistent index only adopts them at the next
     * maintenance pass). beCells is read-only during the entity slot
     * (mutations happen on main pre-dispatch and in the BE slot, after every
     * unit future completed), so concurrent unit reads are safe. Removed
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
        final it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap tickable =
                new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap(64);
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
        ArrayList<ArrayList<TickingBlockEntity>> unitLists = new ArrayList<>(keys.length);
        for (long key : keys) {
            ArrayList<TickingBlockEntity> cell = rs.beCells.get(key);
            if (cell != null && !cell.isEmpty()) {
                unitLists.add(cell);
            }
        }
        // task #14: registration-order merge across the unit's cells — same
        // vanilla mutual-order restoration as the sync slices.
        tickMerged(level, rs, unitLists, rs.unitRemovedBes, tickable, pendingSet, seen);
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
                tickSlice(level, pendingTail, rs.unitRemovedBes, tickable, null, null);
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
            IslandCommands.drainRoutedBatch(2_000_000L);
        }
        if (!level.tickRateManager().runsNormally()) return false;

        level.tickingBlockEntities = true;
        try {
            // Vanilla pending-flush: adds queued during the previous tick join the main list.
            if (!level.pendingBlockEntityTickers.isEmpty()) {
                level.blockEntityTickers.addAll(level.pendingBlockEntityTickers);
                level.pendingBlockEntityTickers.clear();
            }

            // F2b index maintenance: bulk (re)build after cold start / missed adds,
            // else fold in the registrations stashed since the last pass. Runs after
            // the pending flush so a bulk rebuild sees the complete vanilla list.
            if (!rs.beCellsValid) {
                rs.rebuildBeCells(level.blockEntityTickers);
            } else {
                rs.drainPendingBeAdds();
            }

            final int islandCount = rs.islands().size();
            @SuppressWarnings("unchecked")
            final ArrayList<ArrayList<TickingBlockEntity>>[] perIsland =
                    (ArrayList<ArrayList<TickingBlockEntity>>[]) new ArrayList[islandCount];
            final int[] perIslandSize = new int[islandCount];
            ArrayList<ArrayList<TickingBlockEntity>> isolatedCells = null;
            int isolatedSize = 0;
            final java.util.concurrent.ConcurrentLinkedQueue<TickingBlockEntity> removed =
                    new java.util.concurrent.ConcurrentLinkedQueue<>();

            // Route CELLS (not tickers) to island slices. Ascending-key iteration of
            // the AVL map keeps per-island cell order deterministic. Removed wrappers
            // still sit in their cell until the amortized sweep — tickSlice's
            // isRemoved re-check skips them, exactly like vanilla's inline removal
            // would have (they are behaviorally invisible meanwhile).
            ArrayList<TickingBlockEntity> posless = null;
            // Concurrent map: no key order — sort explicitly (same ascending
            // order the old AVL walk gave, deterministic across sessions).
            long[] cellKeys = rs.beCells.keySet().toLongArray();
            java.util.Arrays.sort(cellKeys);
            for (long cellKey : cellKeys) {
                ArrayList<TickingBlockEntity> cell = rs.beCells.get(cellKey);
                if (cell == null || cell.isEmpty()) continue;
                if (cellKey == Long.MIN_VALUE) {
                    // No position, no locality: cannot be assigned to any island's
                    // isolation domain. Ticked on main AFTER the slice barrier, when
                    // every island is quiescent — a woken sleeper or a posless modded
                    // ticker can then touch anything without racing a slice.
                    posless = cell;
                    continue;
                }
                if (rs.asyncActive) {
                    // A2: unit-owned cells already ticked inside their island's
                    // unit this tick (exact complement: units cover regions on
                    // their domain; everything else — gap cells, regionless
                    // leftovers — stays on this path).
                    Region cellRegion = rs.regionizer().peekByGridKey(cellKey);
                    if (cellRegion != null && cellRegion.domain != null
                            && cellRegion.domain != rs.globalDomain) {
                        continue;
                    }
                }
                int island = rs.islandForCell(cellKey);
                if (island < 0 || island >= islandCount) {
                    if (isolatedCells == null) isolatedCells = new ArrayList<>(4);
                    isolatedCells.add(cell);
                    isolatedSize += cell.size();
                } else {
                    ArrayList<ArrayList<TickingBlockEntity>> lists = perIsland[island];
                    if (lists == null) perIsland[island] = lists = new ArrayList<>(4);
                    lists.add(cell);
                    perIslandSize[island] += cell.size();
                }
            }

            ArrayList<Slice> slices = new ArrayList<>(islandCount);
            for (int i = 0; i < islandCount; i++) {
                if (perIsland[i] != null) slices.add(new Slice(perIsland[i], perIslandSize[i]));
            }

            if (slices.isEmpty()) {
                if (isolatedCells != null) {
                    tickCells(level, rs, isolatedCells, removed);
                }
                if (posless != null) {
                    tickSlice(level, posless, removed,
                            new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap(4));
                }
                sweepRemoved(level, rs, removed);
                return true;
            }

            // Heaviest island first for the work-stealing pool (free worker grabs the long pole).
            slices.sort((a, b) -> Integer.compare(b.size(), a.size()));

            // Singleton-merge: fold tiny tail slices into the main (isolated) lane to save
            // dispatch+barrier overhead.
            final int SMALL_SLICE_THRESHOLD = 3;
            if (slices.size() > 2) {
                ArrayList<ArrayList<TickingBlockEntity>> tinyMerged = null;
                int tinySize = 0;
                int writeIdx = 0;
                for (int readIdx = 0; readIdx < slices.size(); readIdx++) {
                    Slice s = slices.get(readIdx);
                    if (s.size() < SMALL_SLICE_THRESHOLD && readIdx > 0) {
                        if (tinyMerged == null) tinyMerged = new ArrayList<>();
                        tinyMerged.addAll(s.cells());
                        tinySize += s.size();
                    } else {
                        slices.set(writeIdx++, s);
                    }
                }
                while (slices.size() > writeIdx) slices.remove(slices.size() - 1);
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

            final ArrayList<ArrayList<TickingBlockEntity>> isolatedFinal = isolatedCells;
            final AtomicInteger nextSlice = new AtomicInteger(0);
            final AtomicInteger remaining = new AtomicInteger(slices.size());
            final AtomicReference<Throwable> failure = new AtomicReference<>();

            Runnable runner = () -> {
                int idx;
                while ((idx = nextSlice.getAndIncrement()) < slices.size()) {
                    try {
                        tickCells(level, rs, slices.get(idx).cells(), removed);
                    } catch (Throwable th) {
                        failure.compareAndSet(null, th);
                    } finally {
                        remaining.decrementAndGet();
                    }
                }
            };
            int workerRunners = Math.min(slices.size(), Math.max(1, RegionScheduler.workerCount()));
            for (int i = 0; i < workerRunners; i++) {
                RegionScheduler.enqueueGlobal(runner);
            }
            if (workerRunners > 0) RegionScheduler.wakeWorkers();

            // Main: the light isolated lane, then opportunistic steal, then the barrier-pump.
            if (isolatedFinal != null) {
                try {
                    tickCells(level, rs, isolatedFinal, removed);
                } catch (Throwable th) {
                    failure.compareAndSet(null, th);
                }
            }
            runner.run();
            while (remaining.get() > 0) {
                boolean drained = sl.getChunkSource().mainThreadProcessor.pollTask();
                if (!drained) LockSupport.parkNanos(20_000L);
            }

            // Post-barrier: all islands quiescent — safe for position-less tickers.
            if (posless != null) {
                try {
                    tickSlice(level, posless, removed,
                            new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap(4));
                } catch (Throwable th) {
                    failure.compareAndSet(null, th);
                }
            }

            sweepRemoved(level, rs, removed);

            Throwable th = failure.get();
            if (th != null) {
                if (th instanceof RuntimeException re) throw re;
                if (th instanceof Error err) throw err;
                throw new RuntimeException("island BE-tick slice failed", th);
            }
            return true;
        } finally {
            level.tickingBlockEntities = false;
        }
    }

    /** One slice: its cells' wrappers merged by REGISTRATION SEQUENCE (task #14):
     *  vanilla's flat list runs in registration order, and for same-tick
     *  moving-piston pairs straddling a cell seam that mutual order is
     *  load-bearing. Each cell list is already seq-ascending (appends), so a
     *  k-way heap merge restores the exact vanilla order at O(n log cells);
     *  one shared per-chunk gate memo, exactly as before. Wrappers missing a
     *  seq (registered before this build / foreign adds) sort first among
     *  themselves in cell order — transient, one session boundary at most. */
    private static void tickCells(Level level, RegionizedServer rs,
                                  ArrayList<ArrayList<TickingBlockEntity>> cells,
                                  java.util.Queue<TickingBlockEntity> removed) {
        final it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap tickable =
                new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap(64);
        tickMerged(level, rs, cells, removed, tickable, null, null);
    }

    private record MergeHead(java.util.List<TickingBlockEntity> list, int idx, long seq) {}

    static void tickMerged(Level level, RegionizedServer rs,
                           java.util.List<? extends java.util.List<TickingBlockEntity>> lists,
                           java.util.Queue<TickingBlockEntity> removed,
                           it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap tickable,
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
            tickSlice(level, only, removed, tickable, skip, seen);
            return;
        }
        java.util.PriorityQueue<MergeHead> heap = new java.util.PriorityQueue<>(
                live, java.util.Comparator.comparingLong(MergeHead::seq));
        for (int i = 0, n = lists.size(); i < n; i++) {
            java.util.List<TickingBlockEntity> l = lists.get(i);
            if (l != null && !l.isEmpty()) {
                heap.add(new MergeHead(l, 0, seqOf(rs, l.get(0))));
            }
        }
        ArrayList<TickingBlockEntity> one = new ArrayList<>(1);
        one.add(null);
        while (!heap.isEmpty()) {
            MergeHead h = heap.poll();
            one.set(0, h.list().get(h.idx()));
            tickSlice(level, one, removed, tickable, skip, seen);
            int next = h.idx() + 1;
            if (next < h.list().size()) {
                heap.add(new MergeHead(h.list(), next, seqOf(rs, h.list().get(next))));
            }
        }
    }

    private static long seqOf(RegionizedServer rs, TickingBlockEntity t) {
        Long s = rs.beSeqOf.get(t);
        return s == null ? Long.MIN_VALUE : s;
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
    private static void tickSlice(Level level, java.util.List<TickingBlockEntity> slice,
                                  java.util.Queue<TickingBlockEntity> removed,
                                  it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap tickable) {
        tickSlice(level, slice, removed, tickable, null, null);
    }

    private static void tickSlice(Level level, java.util.List<TickingBlockEntity> slice,
                                  java.util.Queue<TickingBlockEntity> removed,
                                  it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap tickable,
                                  java.util.Set<TickingBlockEntity> skip,
                                  java.util.Set<TickingBlockEntity> seen) {
        // Vanilla's per-ticker shouldTickBlocksAt gate, memoized to ONE read per CHUNK
        // per slice pass. Vanilla reads per-BE but is naturally coherent — main is the
        // only thread and no ticket mutation can land mid-loop. Here the slice runs on
        // a worker WHILE main's straggler pump executes chunk-system tasks (ticket
        // placements → level recomputes), so a live per-BE read could flip mid-loop:
        // a moving-piston BE skipped for one tick while the rest of its contraption
        // advanced = flying machines breaking at one chunk's entry/exit (bomber fleet
        // report, 2026-07-06). The memo restores vanilla's whole-phase coherence per
        // chunk — and drops N map lookups per chunk to one as a bonus.
        for (int i = 0, m = slice.size(); i < m; i++) {
            TickingBlockEntity t = slice.get(i);
            if (skip != null && skip.contains(t)) {
                if (seen != null) {
                    seen.add(t); // fold delivered this wrapper: carry retires it
                }
                continue; // pending/carried: ticked once in the tail pass
            }
            if (t.isRemoved()) { removed.add(t); continue; }
            BlockPos pos = t.getPos();
            if (pos == null) continue; // Lithium sleeping stub — keep it, it wakes later
            long chunkKey = ChunkPos.pack(pos);
            byte gate = tickable.get(chunkKey);   // 0 = unknown, 1 = tick, 2 = skip
            if (gate == 0) {
                gate = level.shouldTickBlocksAt(pos) ? (byte) 1 : (byte) 2;
                tickable.put(chunkKey, gate);
            }
            if (gate == 1) {
                try {
                    t.tick();
                } catch (Throwable err) {
                    throw new RuntimeException("BE tick failed @ " + pos + " type=" + t.getType(), err);
                }
            }
        }
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
            ArrayList<TickingBlockEntity> list = rs.beCells.get(cell);
            if (list != null) {
                // Copy-on-write: a unit mid-pass may still iterate the old
                // list; it skips these wrappers via isRemoved exactly like
                // vanilla's inline removal would have.
                ArrayList<TickingBlockEntity> next = new ArrayList<>(list);
                next.removeIf(pending::contains);
                if (next.isEmpty()) {
                    rs.beCells.remove(cell);
                } else {
                    rs.beCells.put(cell, next);
                }
            }
        }
        pending.clear();
    }
}
