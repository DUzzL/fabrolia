package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.config.FabroliaConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;

import java.util.ArrayList;

/**
 * Island-owned block-entity ticks with a globally owned Main complement.
 *
 * <p>Autonomous units execute their own cells serially on the claim-holding
 * coordinator. Classified nonlocal native producers rendezvous with globally
 * owned Main at that same unit ordinal and clock; Main's residual never ticks
 * them again. Synchronous mode and the async residual enter a global envelope
 * before observing any live ticker or invoking its body.
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
                tickSlice(level, rs, pendingTail, rs.unitRemovedBes, tickable, null, null);
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

            ArrayList<BlockEntityTickerList> residual = new ArrayList<>();
            var cells = rs.beCells;
            long[] cellKeys = cells.keySet().toLongArray();
            java.util.Arrays.sort(cellKeys);
            for (long cellKey : cellKeys) {
                BlockEntityTickerList cell = cells.get(cellKey);
                if (cell == null || cell.isEmpty()) continue;
                if (rs.asyncActive && cellKey != Long.MIN_VALUE) {
                    // Exact complement of tickIslandCells: units own both their
                    // member cells and halo, never the global/positionless lane.
                    Region cellRegion = rs.regionizer().peekByGridKey(cellKey);
                    if (cellRegion != null && cellRegion.domain != null
                            && cellRegion.domain != rs.globalDomain) {
                        continue;
                    }
                }
                residual.add(cell);
            }
            final java.util.Queue<TickingBlockEntity> removed = new java.util.ArrayDeque<>();
            if (!residual.isEmpty()) {
                // No registry monitor survives into acquisition. One merge restores
                // native registration order across every Main-owned cell, including
                // explicit global tickers. Empty complements need no global barrier.
                RegionScheduler.runGlobalMainBody(sl.getServer(),
                        () -> tickCells(level, rs, residual, removed));
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
     *  one shared per-chunk gate memo, exactly as before. Generic lists retain
     *  the missing-sequence MIN_VALUE rule and original heap tie behavior. */
    private static void tickCells(Level level, RegionizedServer rs,
                                  ArrayList<BlockEntityTickerList> cells,
                                  java.util.Queue<TickingBlockEntity> removed) {
        final it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap tickable =
                new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap(64);
        tickMerged(level, rs, cells, removed, tickable, null, null);
    }

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
            tickSlice(level, rs, only, removed, tickable, skip, seen);
            return;
        }
        BlockEntityTickerList.Merge merge = new BlockEntityTickerList.Merge(lists, rs.beSeqOf);
        if (merge.advance()) {
            tickCursor(level, rs, merge, removed, tickable, skip, seen);
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

    private static void tickSlice(Level level, RegionizedServer rs, java.util.List<TickingBlockEntity> slice,
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
        RegionWorkerThread worker = Thread.currentThread() instanceof RegionWorkerThread current
                ? current : null;
        for (int i = 0, m = slice.size(); i < m; i++) {
            TickingBlockEntity ticker = slice.get(i);
            if (skipTicker(ticker, skip, seen)) continue;
            if (worker != null && rs.beMainBodyTickers.contains(ticker)) {
                tickCursor(level, rs, new SliceCursor(slice, i, m, ticker),
                        removed, tickable, skip, seen);
                return;
            }
            tickOne(level, ticker, removed, tickable);
        }
    }

    /** Borrow the source cursor for a contiguous, already-declared global run.
     *  No successor metadata is read before its predecessor's original body.
     *  The first local/skipped wrapper remains current for the worker; per-source
     *  order is preserved, not a total order between independent islands. */
    private static void tickCursor(Level level, RegionizedServer rs, BlockEntityTickerList.Cursor cursor,
                                   java.util.Queue<TickingBlockEntity> removed,
                                   it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap tickable,
                                   java.util.Set<TickingBlockEntity> skip,
                                   java.util.Set<TickingBlockEntity> seen) {
        RegionWorkerThread worker = Thread.currentThread() instanceof RegionWorkerThread current
                ? current : null;
        while (cursor.hasCurrent()) {
            TickingBlockEntity ticker = cursor.ticker();
            if (skipTicker(ticker, skip, seen)) {
                cursor.advance();
                continue;
            }
            if (worker == null || !rs.beMainBodyTickers.contains(ticker)) {
                tickOne(level, ticker, removed, tickable);
                cursor.advance();
                continue;
            }
            EpochDomain domain = worker.currentDomain;
            if (domain == null || domain.owner == null) {
                throw new IllegalStateException("global block ticker lost its source island unit");
            }
            IslandRegistry.PIsland source = domain.owner;
            // Queue publication and terminal rendezvous transfer exclusive use
            // of this cursor; the coordinator never advances it concurrently.
            IslandCommands.runBlockEntityTickOnMain(rs, source, () -> {
                do {
                    tickOne(level, cursor.ticker(), removed, tickable);
                    // Preserve the former per-ticker post-body source check.
                    // A normal callback may invalidate topology or the generation;
                    // it must not lend its old source identity to the suffix.
                    if (IslandCommands.routedUnitSourceOnMain(rs) != source) {
                        throw new IllegalStateException("block ticker lost its exact source unit");
                    }
                } while (cursor.advance()
                        && (skip == null || !skip.contains(cursor.ticker()))
                        && rs.beMainBodyTickers.contains(cursor.ticker()));
            });
        }
    }

    private static boolean skipTicker(TickingBlockEntity ticker,
                                      java.util.Set<TickingBlockEntity> skip,
                                      java.util.Set<TickingBlockEntity> seen) {
        if (skip == null || !skip.contains(ticker)) return false;
        if (seen != null) seen.add(ticker); // fold delivered it: carry retires it
        return true; // pending/carried: ticked once in the tail pass
    }

    /** Allocated only when a single-list pass first needs a Main rendezvous. */
    private static final class SliceCursor implements BlockEntityTickerList.Cursor {
        private final java.util.List<TickingBlockEntity> slice;
        private final int end;
        private int index;
        private TickingBlockEntity ticker;

        SliceCursor(java.util.List<TickingBlockEntity> slice, int index, int end,
                    TickingBlockEntity ticker) {
            this.slice = slice;
            this.index = index;
            this.end = end;
            this.ticker = ticker;
        }

        @Override public boolean advance() {
            if (++index < end) {
                ticker = slice.get(index);
                return true;
            }
            ticker = null;
            return false;
        }

        @Override public boolean hasCurrent() {
            return index < end;
        }

        @Override public TickingBlockEntity ticker() {
            return ticker;
        }
    }

    private static void tickOne(Level level, TickingBlockEntity t,
                                java.util.Queue<TickingBlockEntity> removed,
                                it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap tickable) {
        // Predicate observations and the body share one first-W entry cut.
        // Spatial/global ownership was established by the producer, never here.
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            if (t.isRemoved()) { removed.add(t); return; }
            BlockPos pos = t.getPos();
            if (pos == null) return; // Lithium sleeping stub — keep it, it wakes later
            long chunkKey = ChunkPos.pack(pos);
            byte gate = tickable.get(chunkKey); // 0 = unknown, 1 = tick, 2 = skip
            if (gate == 0) {
                gate = level.shouldTickBlocksAt(pos) ? (byte) 1 : (byte) 2;
                tickable.put(chunkKey, gate);
            }
            if (gate == 1) t.tick();
        });
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
            rs.beMainBodyTickers.remove(dead);
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
}
