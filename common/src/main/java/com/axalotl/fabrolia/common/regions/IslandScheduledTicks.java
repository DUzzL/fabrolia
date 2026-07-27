package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.config.FabroliaConfig;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.LongPredicate;

/**
 * F1 — island-partitioned scheduled block/fluid ticks (the user's Folia-batch
 * strategy). The level's due chunks are split by {@link RegionizedServer.Island}
 * and each island's slice runs vanilla's exact collect/run algorithm over
 * slice-LOCAL queues; the shared maps are the concurrent ones LevelTicksMixin
 * installs. Islands are ≥2 unpopulated regions (≥16 unloaded chunks) apart, so
 * within one phase no effect can cross between slices — within an island the
 * (priority, subTickOrder) order is vanilla bit-for-bit, across islands the
 * interleaving is unobservable by construction.
 *
 * <p>Scheduling: the HEAVIEST slice runs on main — the Oxidizium redstone
 * engine (NetworkCache/CascadeEngine) is main-confined, and the lag-machine
 * island is precisely where it pays; worker-run slices bail to vanilla wire
 * logic per the engine's own onMain() guard (its cache stays correct via the
 * off-main event buffer added 2026-06-13). Light islands fan out to region
 * workers; main helps once its own slice is done.
 *
 * <p>Vanilla semantics kept: 65536 budget is GLOBAL (shared atomic across
 * slices); a handler throwable crashes the server after the phase barrier
 * (first one wins) exactly like vanilla's propagation; chunks loaded mid-tick
 * (no island at the head rebuild) join their single adjacent island — two
 * adjacent islands are impossible under the separation invariant — or run
 * serially on main when truly isolated.
 */
public final class IslandScheduledTicks {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-IslandTicks");

    private IslandScheduledTicks() {}

    /** Vanilla LevelTicks.CONTAINER_DRAIN_ORDER replica (compares container heads). */
    private static Comparator<LevelChunkTicks<?>> containerOrder() {
        return (a, b) -> ScheduledTick.INTRA_TICK_DRAIN_ORDER.compare(a.peek(), b.peek());
    }

    /**
     * Per-thread visibility of the slice's pending ticks for
     * {@code willTickThisTick}. {@link #runSlice} bypasses vanilla
     * {@code runCollectedTicks} and therefore never populates the vanilla
     * {@code toRunThisTick}/{@code toRunThisTickSet} fields; without this
     * mirror, {@code willTickThisTick} returns {@code false} for ticks that
     * ARE pending. {@link net.minecraft.world.level.block.ComparatorBlock},
     * {@link net.minecraft.world.level.block.DiodeBlock} and
     * {@link net.minecraft.world.level.block.RedstoneTorchBlock} read that
     * answer as "no pending tick, schedule one" and queue a duplicate every
     * neighbour update.
     *
     * <p>Uses {@link ScheduledTick#UNIQUE_TICK_HASH} (matches vanilla's
     * {@code toRunThisTickSet}) so {@code ScheduledTick.probe(type, pos)}
     * lookups match.
     */
    public static final ThreadLocal<ObjectOpenCustomHashSet<ScheduledTick<?>>> SLICE_PENDING = new ThreadLocal<>();

    /**
     * A2 increment 4 — the async-islands slot handler. Global-domain due
     * chunks run here (main, in-slot, vanilla semantics); island-owned chunks
     * are left in the shared nextTick map for their unit coordinators, which
     * collect at their OWN island time ({@link #runIslandScheduled}) — a
     * lagging island's redstone keeps ITS cadence instead of skipping. Also
     * registers scheduler/ticker/budget on the RegionizedServer for those
     * units (budget stays vanilla-shared per lane per level tick).
     *
     * @return true — slot handled; false — async off, fall through.
     */
    public static <T> boolean slotPartitionedAsync(LevelTicks<T> scheduler, ServerLevel level,
                                                   long gameTime, int maxTicks,
                                                   BiConsumer<BlockPos, T> ticker) {
        if (!RegionScheduler.isActive()) return false;
        RegionizedServer rs = RegionScheduler.forLevel(level);
        if (rs == null || !rs.asyncActive) return false;
        @SuppressWarnings("unchecked")
        LevelTicksIslandAccess<T> access = (LevelTicksIslandAccess<T>) scheduler;
        boolean isBlock = scheduler == level.getBlockTicks();
        AtomicInteger budget = isBlock ? rs.blockTickBudget : rs.fluidTickBudget;
        budget.set(maxTicks);
        if (isBlock) {
            rs.blockTicksAccess = access;
            rs.blockTicker = ticker;
        } else {
            rs.fluidTicksAccess = access;
            rs.fluidTicker = ticker;
        }
        Long2LongMap nextTick = access.fabrolia$nextTickForContainer();
        if (nextTick.isEmpty()) return true;
        LongArrayList globals = null;
        ThreadedRegionizer reg = rs.regionizer();
        for (Long2LongMap.Entry e : nextTick.long2LongEntrySet()) {
            long chunkPos = e.getLongKey();
            Region r = reg.peekByGridKey(reg.grid.keyFor(chunkPos));
            if (r != null && r.domain != null && r.domain.owner != null) {
                continue;   // island-owned: its unit collects at its own L
            }
            long next = e.getLongValue();
            if (next > gameTime) {
                // possibly island-tagged leftovers in now-global territory
                next = com.axalotl.fabrolia.common.regions.TickDomains.enforceConsumerDomain(
                        level, access, chunkPos, -1, -1, gameTime);
                if (next > gameTime) continue;
            } else {
                next = com.axalotl.fabrolia.common.regions.TickDomains.enforceConsumerDomain(
                        level, access, chunkPos, -1, -1, gameTime);
                if (next > gameTime) continue;
            }
            if (globals == null) globals = new LongArrayList(8);
            globals.add(chunkPos);
        }
        if (globals != null) {
            runSlice(access, globals, gameTime, budget, ticker);
        }
        return true;
    }

    /** Unit coordinator, FIRST unit segment (before block events): the
     *  island's due scheduled ticks at its unit time — block lane then fluid
     *  lane, vanilla slot order. No-op until the slot has registered the
     *  schedulers (first tick after async enable). */
    static void runIslandScheduled(RegionizedServer rs, EpochDomain d, long unitTime) {
        // BUDGET SEMANTICS FIX (2026-07-13 production overdue-burst trace):
        // the island lanes used the SHARED per-level budget, which main's slot
        // resets once per MAIN tick — while islands consume at THEIR cadence.
        // Under a main-thread stall (terrain generation!) several island units
        // shared one 65536 and exhaustion RE-QUEUED due containers: pending
        // machine ticks aged unit after unit exactly like the recorded bursts
        // (due + tickable + never draining). Vanilla's rule is 65536 per LEVEL
        // TICK; the faithful island mapping is 65536 per ISLAND TICK — the
        // island IS the level from its own clock's perspective (same mapping
        // as random ticks and spawn cycles). Bounded per unit, never coupled
        // to main's load, no cross-island starvation.
        runIslandLane(rs, d, unitTime, rs.blockTicksAccess, rs.blockTicker,
                new AtomicInteger(65536), true);
        runIslandLane(rs, d, unitTime, rs.fluidTicksAccess, rs.fluidTicker,
                new AtomicInteger(65536), false);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void runIslandLane(RegionizedServer rs, EpochDomain d, long unitTime,
                                      Object accessObj, java.util.function.BiConsumer tickerRaw,
                                      AtomicInteger budget, boolean blockLane) {
        if (accessObj == null || tickerRaw == null) return;
        LevelTicksIslandAccess access = (LevelTicksIslandAccess) accessObj;
        Long2LongMap nextTick = access.fabrolia$nextTickForContainer();
        if (nextTick.isEmpty()) return;
        LongArrayList due = null;
        long maxOverdue = 0;
        long overdueChunk = 0;
        ThreadedRegionizer reg = rs.regionizer();
        long laneIslandId = d.owner != null ? d.owner.id : -1;
        long laneGen = d.owner != null ? d.owner.gen : -1;
        for (Long2LongMap.Entry e : nextTick.long2LongEntrySet()) {
            long chunkPos = e.getLongKey();
            long next = e.getLongValue();
            if (next > unitTime) {
                // Possibly a FROZEN mismatch (container tagged to a different
                // clock than this consuming lane — e.g. WALL-tagged inside
                // island territory with L behind wall). Rare branch: one
                // region peek + one tag read; healthy future entries fall
                // straight through. Healing here is what un-stalls a machine
                // whose triggers were parked on the wrong clock.
                Region rf = reg.peekByGridKey(reg.grid.keyFor(chunkPos));
                if (rf == null || rf.domain != d) continue;
                next = com.axalotl.fabrolia.common.regions.TickDomains.enforceConsumerDomain(
                        rs.level, access, chunkPos, laneIslandId, laneGen, unitTime);
                if (next > unitTime) continue;
                if (due == null) due = new LongArrayList(16);
                due.add(chunkPos);
                continue;
            }
            Region r = reg.peekByGridKey(reg.grid.keyFor(chunkPos));
            if (r == null || r.domain != d) continue;   // not ours (disjoint keys per unit)
            // Due-looking entry may be a COMPRESSED mismatch (wall triggers
            // read as overdue vs L ahead of wall): enforce first, re-evaluate.
            next = com.axalotl.fabrolia.common.regions.TickDomains.enforceConsumerDomain(
                    rs.level, access, chunkPos, laneIslandId, laneGen, unitTime);
            if (next > unitTime) continue;
            if (due == null) due = new LongArrayList(16);
            due.add(chunkPos);
            long over = unitTime - next;
            // PRODUCTION-BOUNDED detector (2026-07-14 flood incident: 8000+
            // per-entry records + minutely 4096-line ERROR dumps for KNOWN
            // dormant fringe fluids/leaves). Expected dormant aging is now a
            // pair of cheap aggregate counters — no strings, no ring writes,
            // no logging. Only candidates >32 behind even do the verify reads.
            if (over > 32 && over > maxOverdue) {
                Object cObj = access.fabrolia$allContainers().get(chunkPos);
                net.minecraft.world.ticks.ScheduledTick<?> head =
                        cObj instanceof net.minecraft.world.ticks.LevelChunkTicks<?> c
                                ? c.peek() : null;
                long realOver = head == null ? 0 : unitTime - head.triggerTick();
                if (!access.fabrolia$tickCheck().test(chunkPos)) {
                    // Fringe chunk outside block-ticking range: vanilla leaves
                    // its pending ticks aging indefinitely BY DESIGN. Counter
                    // only; identities available via /fabrolia regions
                    // flightrec dump when explicitly requested.
                    if (realOver > 32) {
                        FlightRecorder.DORMANT_REFUSED.increment();
                        FlightRecorder.noteDormantAge(realOver);
                    }
                    continue;
                }
                if (realOver > maxOverdue) {
                    maxOverdue = realOver;
                    overdueChunk = chunkPos;
                }
            }
        }
        // MACHINE INVARIANT (2026-07-13 flight recorder): a healthy island's
        // pending triggers are at most a few ticks behind its own L (vanilla
        // budget carry-over under load). A REAL head trigger 32+ ticks overdue
        // means pending triggers crossed a clock jump or a migration lost
        // translation — the compressed-pulse signature that breaks loaded
        // machines. Record + dump the ring for the field trace.
        if (maxOverdue > 32) {
            String dim = rs.level.dimension().identifier().toString();
            // Strong-signal gate: a tickable container that stays overdue
            // across >=3 CONSECUTIVE units of the same lane is genuinely stuck
            // (budget starvation or a scheduler bug) — that dumps. A single
            // sighting is an aged fringe chunk ENTERING ticking range
            // delivering its backlog (vanilla-legal, drains this unit) — that
            // only records to the in-memory ring.
            boolean stuck;
            if (blockLane) {
                stuck = d.overdueStreakChunkB == overdueChunk && ++d.overdueStreakB >= 3;
                if (d.overdueStreakChunkB != overdueChunk) { d.overdueStreakChunkB = overdueChunk; d.overdueStreakB = 1; }
            } else {
                stuck = d.overdueStreakChunkF == overdueChunk && ++d.overdueStreakF >= 3;
                if (d.overdueStreakChunkF != overdueChunk) { d.overdueStreakChunkF = overdueChunk; d.overdueStreakF = 1; }
            }
            if (FlightRecorder.DEBUG) {
                FlightRecorder.record("OVERDUE-BURST " + dim + " island#"
                        + (d.owner != null ? d.owner.id : -1) + " L=" + unitTime
                        + " maxOverdue=" + maxOverdue + " dueContainers=" + (due == null ? 0 : due.size())
                        + " lane=" + (blockLane ? "block" : "fluid"));
            }
            // Top overdue containers, full identity (user directive: prove the
            // exact dimension/chunk/type — never classify blind again).
            if (FlightRecorder.DEBUG && stuck && due != null) {
                int logged = 0;
                for (int i = 0; i < due.size() && logged < 5; i++) {
                    long cp = due.getLong(i);
                    Object cObj = access.fabrolia$allContainers().get(cp);
                    if (!(cObj instanceof net.minecraft.world.ticks.LevelChunkTicks<?> c)) continue;
                    net.minecraft.world.ticks.ScheduledTick<?> h = c.peek();
                    if (h == null || unitTime - h.triggerTick() <= 32) continue;
                    logged++;
                    Region reg2 = reg.peekByGridKey(reg.grid.keyFor(cp));
                    FlightRecorder.record("OVERDUE-CONTAINER " + dim
                            + " chunk=[" + net.minecraft.world.level.ChunkPos.getX(cp)
                            + "," + net.minecraft.world.level.ChunkPos.getZ(cp) + "]"
                            + " region=" + (reg2 == null ? "?" : Long.toHexString(reg2.gridKey))
                            + " island#" + (d.owner != null ? d.owner.id : -1)
                            + " blockTicking=" + access.fabrolia$tickCheck().test(cp)
                            + " entityTicking=" + rs.level.getChunkSource().chunkMap
                                    .getDistanceManager().inEntityTickingRange(cp)
                            + " count=" + c.count()
                            + " L=" + unitTime
                            + " head=" + describeHead(h)
                            + " overdue=" + (unitTime - h.triggerTick())
                            + " lane=" + (blockLane ? "block" : "fluid"));
                }
            }
            if (stuck) {
                FlightRecorder.dump("TICKABLE container stuck overdue " + maxOverdue
                        + " ticks across 3+ consecutive units on island#"
                        + (d.owner != null ? d.owner.id : -1)
                        + " (" + dim + ") — OVERDUE-CONTAINER lines above carry"
                        + " chunk/type/trigger identity; TOPO/REBASE lines nearby ="
                        + " migration bug; BUDGET-EXHAUSTED lines = throughput starvation");
            }
        } else {
            if (blockLane) { d.overdueStreakB = 0; d.overdueStreakChunkB = 0; }
            else { d.overdueStreakF = 0; d.overdueStreakChunkF = 0; }
        }
        if (due != null) {
            runSlice(access, due, unitTime, budget, tickerRaw);
            if (budget.get() <= 0) {
                FlightRecorder.record("BUDGET-EXHAUSTED " + rs.level.dimension().identifier()
                        + " island#" + (d.owner != null ? d.owner.id : -1)
                        + " L=" + unitTime + " lane=" + (blockLane ? "block" : "fluid")
                        + " dueContainers=" + due.size());
                if (++d.budgetExhaustStreak >= 5) {
                    d.budgetExhaustStreak = 0;
                    FlightRecorder.dump("budget exhausted 5+ consecutive units on island#"
                            + (d.owner != null ? d.owner.id : -1)
                            + " (" + rs.level.dimension().identifier()
                            + ") — fresh machine ticks are being starved behind a backlog");
                }
            } else {
                d.budgetExhaustStreak = 0;
            }
        }
    }

    /** Bounded head description: pos, type, trigger, subTickOrder. */
    private static String describeHead(net.minecraft.world.ticks.ScheduledTick<?> h) {
        if (h == null) return "-";
        return h.pos().toShortString() + " type=" + h.type()
                + " trigger=" + h.triggerTick() + " sub=" + h.subTickOrder();
    }

    /**
     * @return true — phase handled (partitioned or nothing due); false — caller
     *         must run the vanilla serial path.
     */
    public static <T> boolean tickPartitioned(LevelTicks<T> scheduler, ServerLevel level,
                                              long gameTime, int maxTicks,
                                              BiConsumer<BlockPos, T> ticker) {
        if (!FabroliaConfig.fabroliaIslandScheduledTicks || !RegionScheduler.isActive()) {
            return false;
        }
        RegionizedServer rs = RegionScheduler.forLevel(level);
        if (rs == null || rs.islands().size() < 2) {
            return false;   // one island = vanilla serial is already optimal
        }
        @SuppressWarnings("unchecked")
        LevelTicksIslandAccess<T> access = (LevelTicksIslandAccess<T>) scheduler;
        Long2LongMap nextTick = access.fabrolia$nextTickForContainer();
        if (nextTick.isEmpty()) {
            return true;
        }

        // ---- partition due chunks per island (main, pre-fan-out) ----
        int islandCount = rs.islands().size();
        LongArrayList[] perIsland = new LongArrayList[islandCount];
        LongArrayList isolated = null;
        for (Long2LongMap.Entry e : nextTick.long2LongEntrySet()) {
            if (e.getLongValue() > gameTime) {
                continue;
            }
            long chunkPos = e.getLongKey();
            // Plain-table lookup (rebuilt with the islands at tick HEAD) — same result
            // as islandOfChunk's live concurrent-map probe, ~10x cheaper on this hot path.
            int island = rs.islandForChunk(chunkPos);
            if (island < 0 || island >= islandCount) {
                if (isolated == null) isolated = new LongArrayList(4);
                isolated.add(chunkPos);
            } else {
                LongArrayList list = perIsland[island];
                if (list == null) perIsland[island] = list = new LongArrayList(16);
                list.add(chunkPos);
            }
        }

        ArrayList<LongArrayList> slices = new ArrayList<>(islandCount);
        for (LongArrayList list : perIsland) {
            if (list != null) slices.add(list);
        }
        if (slices.isEmpty()) {
            if (isolated != null) {
                runSlice(access, isolated, gameTime, new AtomicInteger(maxTicks), ticker);
            }
            return true;
        }
        // Heaviest first — main takes slice 0 (redstone engine is main-confined).
        slices.sort((a, b) -> Integer.compare(b.size(), a.size()));

        // Leftover rerouted region tasks must not run on main mid-phase (their
        // writes would race the very islands now ticking on workers).
        RegionScheduler.drainMainSafeNow();

        AtomicInteger budget = new AtomicInteger(maxTicks);
        AtomicInteger nextSlice = new AtomicInteger(1);   // 0 is main's
        AtomicInteger remaining = new AtomicInteger(slices.size());
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Runnable runner = () -> {
            int idx;
            while ((idx = nextSlice.getAndIncrement()) < slices.size()) {
                try {
                    runSlice(access, slices.get(idx), gameTime, budget, ticker);
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    remaining.decrementAndGet();
                }
            }
        };
        int workerRunners = Math.min(slices.size() - 1, Math.max(1, RegionScheduler.workerCount()));
        for (int i = 0; i < workerRunners; i++) {
            RegionScheduler.enqueueGlobal(runner);
        }
        if (workerRunners > 0) {
            RegionScheduler.wakeWorkers();
        }

        // Main: its own heavy slice + the isolated lane, then help, then barrier.
        try {
            runSlice(access, slices.get(0), gameTime, budget, ticker);
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        } finally {
            remaining.decrementAndGet();
        }
        if (isolated != null) {
            try {
                runSlice(access, isolated, gameTime, budget, ticker);
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }
        runner.run();
        // Straggler barrier: park-only pump — region steals here could write
        // into islands mid-slice; chunk-system callbacks stay drained so a
        // worker blocked on a chunk wait can't wedge against a parked main.
        while (remaining.get() > 0) {
            boolean drained = level.getChunkSource().mainThreadProcessor.pollTask();
            if (!drained) {
                LockSupport.parkNanos(20_000L);
            }
        }
        Throwable t = failure.get();
        if (t != null) {
            // Vanilla propagates handler exceptions out of the tick — match it.
            if (t instanceof RuntimeException re) throw re;
            if (t instanceof Error err) throw err;
            throw new RuntimeException("island scheduled-tick slice failed", t);
        }
        return true;
    }

    /** Vanilla collect (collectTicks) + run + cleanup over slice-local state,
     *  scoped to {@code dueChunks}. Mirrors LevelTicks line-for-line; the only
     *  structural change is locals instead of instance fields. */
    private static <T> void runSlice(LevelTicksIslandAccess<T> access, LongArrayList dueChunks,
                                     long gameTime, AtomicInteger budget,
                                     BiConsumer<BlockPos, T> ticker) {
        Long2ObjectMap<LevelChunkTicks<T>> containers = access.fabrolia$allContainers();
        Long2LongMap nextTick = access.fabrolia$nextTickForContainer();
        LongPredicate tickCheck = access.fabrolia$tickCheck();

        // collectContainersToTick, scoped to this slice's due keys
        PriorityQueue<LevelChunkTicks<T>> containersToTick = new PriorityQueue<>(containerOrder());
        for (int i = 0, n = dueChunks.size(); i < n; i++) {
            long chunkPos = dueChunks.getLong(i);
            LevelChunkTicks<T> container = containers.get(chunkPos);
            if (container == null) {
                nextTick.remove(chunkPos);
                continue;
            }
            ScheduledTick<T> peek = container.peek();
            if (peek == null) {
                nextTick.remove(chunkPos);
            } else if (peek.triggerTick() > gameTime) {
                nextTick.put(chunkPos, peek.triggerTick());
            } else if (tickCheck.test(chunkPos)) {
                nextTick.remove(chunkPos);
                containersToTick.add(container);
            }
        }

        // collectTicksToRun (incl. the drain-while-cheaper-than-next-container
        // inner loop) — vanilla structure, shared global budget
        ArrayDeque<ScheduledTick<T>> toRun = new ArrayDeque<>();
        LevelChunkTicks<T> top;
        while (budget.get() > 0 && (top = containersToTick.poll()) != null) {
            ScheduledTick<T> first = top.poll();
            toRun.add(first);
            budget.decrementAndGet();

            LevelChunkTicks<T> nextBest = containersToTick.peek();
            ScheduledTick<T> bar = nextBest != null ? nextBest.peek() : null;
            while (budget.get() > 0) {
                ScheduledTick<T> cand = top.peek();
                if (cand == null || cand.triggerTick() > gameTime
                        || (bar != null && ScheduledTick.INTRA_TICK_DRAIN_ORDER.compare(cand, bar) > 0)) {
                    break;
                }
                top.poll();
                toRun.add(cand);
                budget.decrementAndGet();
            }

            ScheduledTick<T> next = top.peek();
            if (next != null) {
                if (next.triggerTick() <= gameTime && budget.get() > 0) {
                    containersToTick.add(top);
                } else {
                    nextTick.put(ChunkPos.pack(next.pos()), next.triggerTick());
                }
            }
        }
        // rescheduleLeftoverContainers (budget exhausted)
        for (LevelChunkTicks<T> leftover : containersToTick) {
            ScheduledTick<T> peek = leftover.peek();
            if (peek != null) {
                nextTick.put(ChunkPos.pack(peek.pos()), peek.triggerTick());
            }
        }

        // runCollectedTicks — publish slice's pending set to this thread so that
        // willTickThisTick (consulted by Comparator/Diode/RedstoneTorch
        // neighbour handlers via LevelTicksMixin) sees the same answer vanilla
        // does. We mirror vanilla's runCollectedTicks remove-then-execute order:
        // the tick about to run is no longer "pending" while its handler runs.
        ObjectOpenCustomHashSet<ScheduledTick<?>> pending =
                new ObjectOpenCustomHashSet<>(ScheduledTick.UNIQUE_TICK_HASH);
        for (ScheduledTick<T> t : toRun) {
            pending.add(t);
        }
        ThreadLocal<ObjectOpenCustomHashSet<ScheduledTick<?>>> tl = SLICE_PENDING;
        ObjectOpenCustomHashSet<ScheduledTick<?>> prev = tl.get();
        tl.set(pending);
        try {
            ScheduledTick<T> tick;
            while ((tick = toRun.poll()) != null) {
                pending.remove(tick);
                // Deliberately NO shouldTickBlocksAt re-check here. Ticking eligibility
                // is decided ONCE per chunk at collection (tickCheck above) — exactly
                // vanilla, which never re-evaluates after collecting. The old dispatch-
                // time re-read + rescheduleFromSlice could split ONE chunk's due ticks
                // across two game ticks whenever the answer flipped mid-phase (main's
                // straggler pump runs ticket-mutating chunk tasks), desyncing the two
                // halves of a contraption straddling that chunk's border — the bomber
                // fleet breaking at one chunk's entry/exit, 2026-07-06. A chunk whose
                // level drops mid-phase is still loaded; running its already-collected
                // ticks is what vanilla does when the same change lands between ticks.
                ticker.accept(tick.pos(), tick.type());
            }
        } finally {
            if (prev == null) tl.remove();
            else tl.set(prev);
        }
    }

    /** Island of a chunk via the LIVE region map; freshly-populated (post-rebuild)
     *  chunks join their single adjacent island (two are impossible — separation
     *  invariant). The hot partition paths now use the equivalent
     *  {@link RegionizedServer#islandForChunk} table (rebuilt at tick HEAD together
     *  with the islands — this method is its reference semantics). Kept public for
     *  external callers and for contexts where the table may be stale. */
    public static int islandOfChunk(RegionizedServer rs, long chunkPos) {
        ThreadedRegionizer regionizer = rs.regionizer();
        long gridKey = regionizer.grid.keyFor(chunkPos);
        Region r = regionizer.peekByGridKey(gridKey);
        if (r != null && r.islandId >= 0) {
            return r.islandId;
        }
        int rx = (int) gridKey;
        int rz = (int) (gridKey >>> 32);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                long key = ((rx + dx) & 0xFFFFFFFFL) | (((long) (rz + dz) & 0xFFFFFFFFL) << 32);
                Region nb = regionizer.peekByGridKey(key);
                if (nb != null && nb.islandId >= 0) {
                    return nb.islandId;
                }
            }
        }
        return -1;
    }
}
