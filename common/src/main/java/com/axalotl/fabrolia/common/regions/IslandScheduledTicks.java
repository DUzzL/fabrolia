package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.LongPredicate;

/**
 * Owner-local scheduled block/fluid ticks with vanilla collect/run ordering
 * and slice-local visibility receipts. Autonomous island units collect at their
 * own logical time; physical Main collects only the exact global-domain
 * complement, in one serial lane under a pre-body global gameplay envelope.
 *
 * <p>The vanilla budget is shared per block/fluid lane for each level tick.
 * Collected work remains registered until completion or first-failure cleanup,
 * including restoration of every detached but unentered scheduled tick.
 */
public final class IslandScheduledTicks {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-IslandTicks");
    private static final long HOLD_LOG_INTERVAL_NANOS = 10_000_000_000L;
    private static final AtomicLong HOLDS_REPORTED = new AtomicLong();
    private static final AtomicLong HOLD_LAST_LOG_NANOS = new AtomicLong();
    /**
     * Consecutive same-L fail-closed HOLD attempts after which an island is
     * reported as STUCK (and again every further multiple). A HOLD is retried
     * by the pacer after {@code TemporalControl.Pacer.READINESS_RETRY_NANOS}
     * = 50 ms, so 200 attempts ≈ 10 s of wall time at 0 TPS: far beyond any
     * genuine publication race (chunk future completion, entity status drain
     * — tens of milliseconds). Detection only: the unit keeps holding, since
     * a refused pulse-sensitive head that never heals is a pipeline bug to
     * fix at its source (2026-09-27: deferred entity status transitions),
     * never a reason to silently run the island incoherently.
     */
    static final int SCHEDULED_HOLD_STUCK_STREAK = 200;
    /** Logical LevelTicks phase of the handler currently running on this
     * thread. willTickThisTick is phase-local in vanilla; consulting foreign
     * islands would mix independent clocks and create a global barrier. */
    private static final ThreadLocal<SliceExecution<?>> CURRENT_SLICE =
            new ThreadLocal<>();

    private IslandScheduledTicks() {}

    /** Read-only block-frontier decision retained until structural commit. No
     *  queue/map mutation is allowed while these records are built. */
    private static final class PreparedChunk<T> {
        final long chunkPos;
        final LevelChunkTicks<T> container;
        final ScheduledTick<T> head;
        final boolean admitted;

        PreparedChunk(long chunkPos, LevelChunkTicks<T> container,
                      ScheduledTick<T> head, boolean admitted) {
            this.chunkPos = chunkPos;
            this.container = container;
            this.head = head;
            this.admitted = admitted;
        }
    }

    /** One island lane after domain-normalized due-key discovery and the
     *  read-only eligibility pass, but before nextTick removal or polling. */
    private static final class PreparedIslandLane<T> {
        final LevelTicksIslandAccess<T> access;
        final BiConsumer<BlockPos, T> ticker;
        final AtomicInteger budget;
        final boolean blockLane;
        final LongArrayList dueChunks;
        final ArrayList<PreparedChunk<T>> prepared;
        /** A transient exact-readiness refusal or structural drift makes this
         *  complete island unit non-admissible.  Preparation is read-only, so
         *  HOLD leaves every scheduled container byte-for-byte untouched. */
        boolean hold;
        long holdChunk = Long.MIN_VALUE;
        ScheduledTick<?> holdHead;
        String holdReason;
        long maxOverdue;
        long overdueChunk;
        long dormantRefused;
        long dormantMaxAge;

        PreparedIslandLane(LevelTicksIslandAccess<T> access,
                           BiConsumer<BlockPos, T> ticker,
                           AtomicInteger budget, boolean blockLane,
                           LongArrayList dueChunks) {
            this.access = access;
            this.ticker = ticker;
            this.budget = budget;
            this.blockLane = blockLane;
            this.dueChunks = dueChunks;
            this.prepared = dueChunks == null
                    ? new ArrayList<>(0) : new ArrayList<>(dueChunks.size());
        }
    }

    private static void markHold(PreparedIslandLane<?> lane, long chunkPos,
                                 ScheduledTick<?> head, String reason) {
        lane.hold = true;
        if (lane.holdChunk == Long.MIN_VALUE) {
            lane.holdChunk = chunkPos;
            lane.holdHead = head;
            lane.holdReason = reason;
        }
    }

    /** Counts one HOLD attempt at {@code unitTime}; a different L starts a
     *  fresh streak. Returns the streak length including this attempt.
     *  Coordinator-only fields. */
    static int recordHold(IslandRegistry.PIsland owner, long unitTime) {
        if (owner.scheduledHoldL != unitTime) {
            owner.scheduledHoldL = unitTime;
            owner.scheduledHoldStreak = 0;
        }
        return ++owner.scheduledHoldStreak;
    }

    /** The scheduled frontier was admitted at some L: the streak is over. */
    static void clearHoldStreak(IslandRegistry.PIsland owner) {
        owner.scheduledHoldL = Long.MIN_VALUE;
        owner.scheduledHoldStreak = 0;
    }

    /** True on the {@link #SCHEDULED_HOLD_STUCK_STREAK}th consecutive hold and
     *  on every further multiple, so a long stall is re-reported rather than
     *  warned about once and forgotten. */
    static boolean holdStreakStuck(int streak) {
        return streak > 0 && streak % SCHEDULED_HOLD_STUCK_STREAK == 0;
    }

    /** Counts the attempt, emits the flood-safe periodic SCHEDULED-HOLD line
     *  and, at every stuck-streak multiple, the unthrottled SCHEDULED-HOLD-STUCK
     *  line. The unit keeps holding either way. */
    private static void noteHold(RegionizedServer rs, IslandRegistry.PIsland owner,
                                 long unitTime, PreparedIslandLane<?> lane) {
        long islandId = owner != null ? owner.id : -1;
        int streak = owner != null ? recordHold(owner, unitTime) : 0;
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            reportHold(rs.level, islandId, unitTime, lane);
            if (holdStreakStuck(streak)) {
                reportHoldStuck(rs.level, owner, unitTime, streak, lane);
            }
        });
    }

    private static void reportHoldStuck(ServerLevel level, IslandRegistry.PIsland owner,
                                        long unitTime, int streak,
                                        PreparedIslandLane<?> lane) {
        String message = "SCHEDULED-HOLD-STUCK streak=" + streak
                + " dim=" + level.dimension().identifier()
                + " island#" + owner.id + "/g" + owner.gen
                + " L=" + unitTime
                + " lane=" + (lane.blockLane ? "block" : "fluid")
                + " chunk=[" + ChunkPos.getX(lane.holdChunk)
                + "," + ChunkPos.getZ(lane.holdChunk) + "]"
                + " reason=" + lane.holdReason
                + " head=" + describeHead(lane.holdHead)
                + describeChunkReadiness(level, lane.holdChunk)
                + " -> still holding; the refused leg above is the bug to fix";
        FlightRecorder.record(message);
        LOGGER.warn("[scheduled-ticks] {}", message);
    }

    /** Every input of vanilla's exact check plus the simulation level, so a log
     *  line says WHICH leg refused (same probe shape as PortalStuckProbe).
     *
     *  <p>Each leg is read on its own: one unreadable leg must not hide the
     *  others. The loading level is taken from the holder's ticket level, not
     *  from {@code DistanceManager.getChunkLevel(pos, false)}: C2ME replaces
     *  vanilla's loading tracker and leaves {@code loadingChunkTracker} null,
     *  which turned every production SCHEDULED-HOLD line into
     *  {@code readiness=probe-failed:NullPointerException} (2026-10-10 log). */
    private static String describeChunkReadiness(ServerLevel level, long chunkPos) {
        StringBuilder sb = new StringBuilder(160);
        net.minecraft.server.level.ServerChunkCache cs;
        net.minecraft.server.level.ChunkHolder holder;
        try {
            cs = level.getChunkSource();
            holder = cs.getVisibleChunkIfPresent(chunkPos);
        } catch (Throwable t) {
            return " readiness=probe-failed:" + t;
        }
        appendProbe(sb, "simLevel", () -> cs.chunkMap.getDistanceManager().getChunkLevel(chunkPos, true));
        appendProbe(sb, "entityTickingRange", () -> cs.chunkMap.getDistanceManager().inEntityTickingRange(chunkPos));
        appendProbe(sb, "ticketLevel", () -> holder == null ? "unloaded" : holder.getTicketLevel());
        appendProbe(sb, "tickingChunk", () -> holder != null && holder.getTickingChunk() != null);
        appendProbe(sb, "entitiesLoaded", () -> level.areEntitiesLoaded(chunkPos));
        appendProbe(sb, "shouldTickBlocks", () -> level.shouldTickBlocksAt(chunkPos));
        return sb.toString();
    }

    private static void appendProbe(StringBuilder sb, String name,
                                    java.util.function.Supplier<Object> leg) {
        sb.append(' ').append(name).append('=');
        try {
            sb.append(leg.get());
        } catch (Throwable t) {
            sb.append("probe-failed:").append(t.getClass().getSimpleName());
        }
    }

    /** Always-on but flood-safe evidence that a whole unit stayed at the same L. */
    private static void reportHold(ServerLevel level, long islandId, long unitTime,
                                   PreparedIslandLane<?> lane) {
        FlightRecorder.SCHEDULED_HOLDS.increment();
        long now = System.nanoTime();
        while (true) {
            long last = HOLD_LAST_LOG_NANOS.get();
            if (last != 0L && now - last < HOLD_LOG_INTERVAL_NANOS) return;
            if (HOLD_LAST_LOG_NANOS.compareAndSet(last, now)) break;
        }
        long total = FlightRecorder.SCHEDULED_HOLDS.sum();
        long sinceLast = Math.max(0L, total - HOLDS_REPORTED.getAndSet(total));
        String head = describeHead(lane.holdHead);
        String message = "SCHEDULED-HOLD total=" + total + " sinceLast=" + sinceLast
                + " dim=" + level.dimension().identifier()
                + " island#" + islandId
                + " L=" + unitTime
                + " lane=" + (lane.blockLane ? "block" : "fluid")
                + " chunk=[" + ChunkPos.getX(lane.holdChunk)
                + "," + ChunkPos.getZ(lane.holdChunk) + "]"
                + " reason=" + lane.holdReason + " head=" + head
                + describeChunkReadiness(level, lane.holdChunk);
        FlightRecorder.record(message);
        LOGGER.warn("[scheduled-ticks] {}", message);
    }

    /**
     * Vanilla LevelTicks.CONTAINER_DRAIN_ORDER replica (compares container heads).
     *
     * <p>Null-tolerant, unlike vanilla's. Vanilla holds the invariant "a container
     * only ever sits in containersToTick with a non-null peek()" because it is
     * single-threaded; under island slicing a container can be emptied after
     * collection by another slice or an off-slice mutator (TickDomains drains,
     * shiftAll, c2me's off-thread pack()), and PriorityQueue re-compares on every
     * sift — so a null head is reachable here and would NPE inside
     * INTRA_TICK_DRAIN_ORDER. Empty containers sort LAST; the drain loop's
     * peek-before-poll guard then skips them.
     */
    private static Comparator<LevelChunkTicks<?>> containerOrder() {
        return (a, b) -> {
            ScheduledTick<?> ha = a.peek();
            ScheduledTick<?> hb = b.peek();
            if (ha == null) return hb == null ? 0 : 1;
            if (hb == null) return -1;
            return ScheduledTick.INTRA_TICK_DRAIN_ORDER.compare(ha, hb);
        };
    }

    /** Scheduler-owned visibility domain for every concurrent custom slice of
     * one LevelTicks instance. Collection/poll/retirement take the read side;
     * clear/copy take the write side. Mutable queues and willTick's lazy set
     * stay per-slice, so unrelated islands never convoy on one monitor. */
    public static final class SliceRegistry {
        final ReentrantReadWriteLock visibility = new ReentrantReadWriteLock();
        final Object membership = new Object();
        final ArrayList<SliceExecution<?>> active = new ArrayList<>();
    }

    /** One published slice. The deque/list are accessed only under state. */
    static final class SliceExecution<T> {
        final LevelTicksIslandAccess<T> access;
        final SliceRegistry registry;
        final long clockIsland;
        final long clockGen;
        final Object state = new Object();
        final ArrayDeque<ScheduledTick<T>> toRun = new ArrayDeque<>();
        final ArrayList<ScheduledTick<T>> alreadyRun = new ArrayList<>();
        final ObjectOpenCustomHashSet<ScheduledTick<?>> pending =
                new ObjectOpenCustomHashSet<>(ScheduledTick.UNIQUE_TICK_HASH);

        SliceExecution(LevelTicksIslandAccess<T> access, SliceRegistry registry,
                       long clockIsland, long clockGen) {
            this.access = access;
            this.registry = registry;
            this.clockIsland = clockIsland;
            this.clockGen = clockGen;
        }
    }

    private record ClockStamp(long island, long gen) {}

    private static ClockStamp currentClockStamp() {
        Thread thread = Thread.currentThread();
        if (thread instanceof RegionWorkerThread worker) {
            EpochDomain domain = worker.currentRegion != null
                    ? worker.currentRegion.domain : worker.currentDomain;
            if (domain != null && domain.owner != null && domain.unitGameTime != 0L) {
                return new ClockStamp(domain.owner.id, domain.owner.gen);
            }
        }
        return new ClockStamp(FabroliaTickDomain.WALL, -1L);
    }

    private static <T> ScheduledTick<T> copyToCallerClock(
            LevelTicksIslandAccess<T> source, LevelTicksIslandAccess<T> target,
            ClockStamp caller, long island, ScheduledTick<T> tick) {
        // Generations of one island id share a timeline.
        if (source == target && caller.island == island) return tick;
        long sourceNow = source.fabrolia$clockNow(island);
        long callerNow = caller.island == FabroliaTickDomain.WALL
                ? target.fabrolia$clockNow(FabroliaTickDomain.WALL)
                : IslandClock.contextTime();
        long delta = callerNow - sourceNow;
        return delta == 0L ? tick : new ScheduledTick<>(tick.type(), tick.pos(),
                tick.triggerTick() + delta, tick.priority(), tick.subTickOrder());
    }

    private static <T> SliceExecution<T> registerSlice(LevelTicksIslandAccess<T> access) {
        SliceRegistry registry = access.fabrolia$sliceRegistry();
        ClockStamp clock = currentClockStamp();
        SliceExecution<T> execution = new SliceExecution<>(
                access, registry, clock.island, clock.gen);
        synchronized (registry.membership) {
            registry.active.add(execution);
        }
        return execution;
    }

    private static void retireSlice(SliceExecution<?> execution) {
        SliceRegistry registry = execution.registry;
        synchronized (registry.membership) {
            if (!registry.active.remove(execution)) {
                throw new IllegalStateException("scheduled-tick slice retired twice");
            }
        }
    }

    /** Vanilla calculateTickSetIfNeeded for the caller's current logical phase.
     * Null means this thread is outside a custom slice (or querying another
     * scheduler), so the mixin falls through to LevelTicks' private state. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T> Boolean willTickCollected(
            LevelTicks<T> scheduler, BlockPos pos, T type) {
        LevelTicksIslandAccess<T> access = (LevelTicksIslandAccess<T>) scheduler;
        SliceExecution<?> raw = CURRENT_SLICE.get();
        if (raw == null || raw.access != access) return null;
        SliceExecution<T> execution = (SliceExecution<T>) raw;
        synchronized (execution.state) {
            // Deliberately no initialized bit: vanilla rebuilds whenever its
            // set is empty, including after the final materialized tick polls.
            if (execution.pending.isEmpty()) {
                execution.pending.addAll((ArrayDeque) execution.toRun);
            }
            return execution.pending.contains(ScheduledTick.probe(type, pos));
        }
    }

    /** One write-side cut over detached state and vanilla durable/private state.
     * Vanilla deliberately leaves an already-materialized toRunThisTickSet
     * stale here, so this method does not remove entries from pending either. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void clearCollectedArea(
            LevelTicks<?> scheduler, BoundingBox box, Runnable vanillaClear) {
        LevelTicksIslandAccess<?> access = (LevelTicksIslandAccess<?>) scheduler;
        SliceRegistry registry = access.fabrolia$sliceRegistry();
        Lock write = registry.visibility.writeLock();
        write.lock();
        try {
            synchronized (access.fabrolia$tickLock()) {
                for (SliceExecution<?> raw : registry.active) {
                    SliceExecution lane = raw;
                    synchronized (lane.state) {
                        lane.alreadyRun.removeIf(
                                t -> box.isInside(((ScheduledTick<?>) t).pos()));
                        lane.toRun.removeIf(
                                t -> box.isInside(((ScheduledTick<?>) t).pos()));
                    }
                }
                vanillaClear.run();
            }
        } finally {
            write.unlock();
        }
    }

    /** Full vanilla copyAreaFrom replica plus every custom source slice. The
     * source snapshot is complete before its locks are released; target
     * scheduling happens afterwards to avoid reciprocal source/target lock
     * cycles. Normalize each source trigger into the caller clock first;
     * ordinary target.schedule then translates into its destination tag. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> void copyCollectedAreaFrom(
            LevelTicks<T> target, LevelTicks<T> source,
            BoundingBox box, Vec3i offset) {
        LevelTicksIslandAccess<T> sourceAccess = (LevelTicksIslandAccess<T>) source;
        LevelTicksIslandAccess<T> targetAccess = (LevelTicksIslandAccess<T>) target;
        SliceRegistry registry = sourceAccess.fabrolia$sliceRegistry();
        ArrayList<ScheduledTick<T>> selected = new ArrayList<>();
        ClockStamp caller = currentClockStamp();
        Lock write = registry.visibility.writeLock();
        write.lock();
        try {
            synchronized (sourceAccess.fabrolia$tickLock()) {
                // Vanilla category order: already-run, pending, durable. The
                // custom queues are replicas of the first two categories.
                for (ScheduledTick<T> tick
                        : sourceAccess.fabrolia$vanillaAlreadyRunThisTick()) {
                    if (!box.isInside(tick.pos())) continue;
                    selected.add(copyToCallerClock(sourceAccess, targetAccess,
                            caller, FabroliaTickDomain.WALL, tick));
                }
                for (SliceExecution<?> raw : registry.active) {
                    SliceExecution<T> lane = (SliceExecution<T>) raw;
                    synchronized (lane.state) {
                        for (ScheduledTick<T> tick : lane.alreadyRun) {
                            if (!box.isInside(tick.pos())) continue;
                            selected.add(copyToCallerClock(sourceAccess, targetAccess,
                                    caller, lane.clockIsland, tick));
                        }
                    }
                }
                for (ScheduledTick<T> tick
                        : sourceAccess.fabrolia$vanillaToRunThisTick()) {
                    if (!box.isInside(tick.pos())) continue;
                    selected.add(copyToCallerClock(sourceAccess, targetAccess,
                            caller, FabroliaTickDomain.WALL, tick));
                }
                for (SliceExecution<?> raw : registry.active) {
                    SliceExecution<T> lane = (SliceExecution<T>) raw;
                    synchronized (lane.state) {
                        for (ScheduledTick<T> tick : lane.toRun) {
                            if (!box.isInside(tick.pos())) continue;
                            selected.add(copyToCallerClock(sourceAccess, targetAccess,
                                    caller, lane.clockIsland, tick));
                        }
                    }
                }

                Long2ObjectMap<LevelChunkTicks<T>> containers =
                        sourceAccess.fabrolia$allContainers();
                int minChunkX = box.minX() >> 4;
                int maxChunkX = box.maxX() >> 4;
                int minChunkZ = box.minZ() >> 4;
                int maxChunkZ = box.maxZ() >> 4;
                for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                    for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                        LevelChunkTicks<T> container =
                                containers.get(ChunkPos.pack(chunkX, chunkZ));
                        if (container == null) continue;
                        synchronized (container) {
                            long island = container instanceof FabroliaTickDomain domain
                                    ? domain.fabrolia$domainIsland() : FabroliaTickDomain.WALL;
                            for (ScheduledTick<T> tick : container.getAll()
                                    .filter(t -> box.isInside(t.pos())).toList()) {
                                selected.add(copyToCallerClock(sourceAccess, targetAccess,
                                        caller, island, tick));
                            }
                        }
                    }
                }
            }
        } finally {
            write.unlock();
        }

        if (selected.isEmpty()) return;
        long minSubTick = Long.MAX_VALUE;
        long maxSubTick = Long.MIN_VALUE;
        for (ScheduledTick<T> tick : selected) {
            minSubTick = Math.min(minSubTick, tick.subTickOrder());
            maxSubTick = Math.max(maxSubTick, tick.subTickOrder());
        }
        for (ScheduledTick<T> tick : selected) {
            target.schedule(new ScheduledTick<>(tick.type(), tick.pos().offset(offset),
                    tick.triggerTick(), tick.priority(),
                    tick.subTickOrder() - minSubTick + maxSubTick + 1L));
        }
    }

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
        LongArrayList globals = discoverGlobalDueChunks(
                rs, gameTime, access, nextTick);
        if (globals != null) {
            // The ownerless/global lane stays vanilla-local to physical Main.
            // A whole-island same-L HOLD has no meaning here, and a synthetic
            // cell frontier would let later vanilla phases overtake only part
            // of the scheduled lane.  Use the ordinary exact tickCheck skip.
            runSlice(access, globals, gameTime, budget, ticker);
        }
        return true;
    }

    private record CausalPreflightHold(long chunkPos, ScheduledTick<?> head,
                                       String reason) {}

    /**
     * Causal-only admission probe before command shards, island tasks and
     * cross-dimension arrivals can mutate this candidate logical tick.
     *
     * <p>The terrain hot path does one nextTick lookup and one monotonic hint
     * read per due container. It never calls the expensive holder predicate for
     * leaves/falling blocks. Only a container which has ever contained a
     * pulse-sensitive block tick is normalized to the owner clock and scanned.
     * No tick is detached, polled or executed here; the normal post-head prepare
     * remains authoritative and also discovers delay-zero work produced by the
     * admitted unit head.
     *
     * @return false when the unit must retry the same L before entering its
     *         producer phases.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static boolean preflightIslandScheduled(RegionizedServer rs, EpochDomain d,
                                            long unitTime) {
        Object accessObj = rs.blockTicksAccess;
        if (accessObj == null || d.owner == null) return true;
        LevelTicksIslandAccess access = (LevelTicksIslandAccess) accessObj;
        Long2LongMap nextTick = access.fabrolia$nextTickForContainer();
        if (nextTick.isEmpty()) return true;

        IslandRegistry.PIsland owner = d.owner;
        CausalPreflightHold hold = null;
        var cells = owner.cells.iterator();
        while (cells.hasNext() && hold == null) {
            long cellKey = cells.nextLong();
            Region core = rs.regionizer().peekByGridKey(cellKey);
            if (core == null || core.domain != d) {
                throw scheduledDiscoveryInvariant("causal preflight owner core",
                        cellKey, core, d);
            }
            hold = probeCausalPreflightCell(rs, d, access, nextTick,
                    cellKey, unitTime);
        }

        var haloCells = owner.haloCells.iterator();
        while (haloCells.hasNext() && hold == null) {
            long cellKey = haloCells.nextLong();
            Region halo = rs.regionizer().peekByGridKey(cellKey);
            int populatedChunks = halo == null ? 0 : halo.fullChunkCount.get();
            if (populatedChunks == 0) continue;
            if (populatedChunks < 0 || halo.domain != d) {
                throw scheduledDiscoveryInvariant("causal preflight populated owner halo",
                        cellKey, halo, d);
            }
            hold = probeCausalPreflightCell(rs, d, access, nextTick,
                    cellKey, unitTime);
        }
        if (hold == null) return true;

        PreparedIslandLane diagnostic = new PreparedIslandLane(
                access, null, new AtomicInteger(0), true, null);
        markHold(diagnostic, hold.chunkPos(), hold.head(), hold.reason());
        noteHold(rs, owner, unitTime, diagnostic);
        return false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static CausalPreflightHold probeCausalPreflightCell(
            RegionizedServer rs, EpochDomain d, LevelTicksIslandAccess access,
            Long2LongMap nextTick, long cellKey, long unitTime) {
        Long2ObjectMap<LevelChunkTicks<Object>> containers =
                access.fabrolia$allContainers();
        LongArrayList scheduled = scheduledChunksInCell(rs, nextTick, cellKey);
        if (scheduled.isEmpty()) return null;
        boolean frontierClosed = hasClosedScheduledFrontier(rs, d, cellKey);
        for (int i = 0, n = scheduled.size(); i < n; i++) {
            long chunkPos = scheduled.getLong(i);
            if (nextTick.get(chunkPos) == Long.MAX_VALUE) continue;
            LevelChunkTicks<Object> container = containers.get(chunkPos);
            if (container == null) continue;
            ScheduledTick<Object> head = container.peek();
            if (head == null) continue;
            CausalScheduledTickIndex hint =
                    (CausalScheduledTickIndex) (Object) container;
            if (!ScheduledTickIslandAdmission.requiresPulseCoherence(head.type())
                    && !hint.fabrolia$mayContainPulseSensitiveTick()) {
                continue;
            }

            // Normalize only the cold causal candidate. This is the same
            // idempotent owner-clock heal used by normal discovery; it may
            // refresh the compressed nextTick key but consumes no work.
            long next = TickDomains.enforceConsumerDomain(
                    rs.level, access, chunkPos,
                    d.owner.id, d.owner.gen, unitTime);
            if (next > unitTime) continue;
            container = containers.get(chunkPos);
            head = container != null ? container.peek() : null;
            if (container == null || head == null || head.triggerTick() > unitTime
                    || !hasDuePulseSensitiveTick(container, head, unitTime)) {
                continue;
            }
            if (!frontierClosed) {
                return new CausalPreflightHold(chunkPos, head,
                        "pulse-sensitive-head-preflight/open-write-frontier");
            }

            boolean entityDmBefore = inEntityTickingRange(rs.level, chunkPos);
            boolean exact = access.fabrolia$tickCheck().test(chunkPos);
            if (exact) continue;
            boolean entityDmAfter = inEntityTickingRange(rs.level, chunkPos);
            if (entityDmBefore || entityDmAfter) {
                return new CausalPreflightHold(chunkPos, head,
                        "pulse-sensitive-head-preflight/exact-refusal/dm-true");
            }
        }
        return null;
    }

    /**
     * Narrowing heuristic for a refused pulse-sensitive container, NOT a
     * substitute for vanilla's exact check. Vanilla's tickCheck
     * ({@code isPositionTickingWithEntitiesLoaded}) also needs the holder's
     * BLOCK_TICKING future to be ready and the chunk's entities loaded; the
     * distance manager alone (simulation level {@code <= 31}) answers neither.
     * A chunk outside DM entity-ticking range is refused by vanilla for the
     * distance reason itself, so holding for it can only be a freeze: it takes
     * vanilla's per-container refusal. A chunk inside that range is a readiness
     * hole the island holds for until it heals; a hole that does not heal is
     * reported as stuck ({@link #SCHEDULED_HOLD_STUCK_STREAK}) with every leg
     * printed, never papered over.
     */
    private static boolean inEntityTickingRange(ServerLevel level, long chunkPos) {
        return level.getChunkSource().chunkMap.getDistanceManager().inEntityTickingRange(chunkPos);
    }

    /** Unit coordinator, FIRST unit segment (before block events): the
     *  island's due scheduled ticks at its unit time — block lane then fluid
     *  lane, vanilla slot order. No-op until the slot has registered the
     *  schedulers (first tick after async enable).
     *
     * <p>A transient exact-readiness refusal containing a due pulse-sensitive
     * tick admits no part of the unit. Every scheduled container and every
     * later vanilla phase stays behind the same logical frontier, and the pacer
     * retries this candidate L after one normal tick period. Ordinary terrain
     * ticks use vanilla's per-container refusal and never hold the island.</p>
     *
     * @return true when the scheduled frontier was admitted; false when the
     *         complete island unit must remain at the same logical tick.
     */
    static boolean runIslandScheduled(RegionizedServer rs, EpochDomain d, long unitTime) {
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
        // FAIL-CLOSED ADMISSION (BYUC prod seam), deliberately BLOCK-LANE ONLY.
        // Observer -> Dropper -> Crafter is block scheduled work, but its next
        // producer may live in block-event/entity/BE phases. Therefore a
        // refused pulse target cannot be made coherent by withholding only that
        // container: the complete unit must stay at the same L. Leaves/sand/
        // gravel retain vanilla's local refusal and do not enter this path.
        PreparedIslandLane<?> block = prepareIslandLane(rs, d, unitTime,
                rs.blockTicksAccess, rs.blockTicker, new AtomicInteger(65536), true);
        if (block.hold) {
            noteHold(rs, d.owner, unitTime, block);
            return false;
        }

        // PRECOMMIT IDENTITY BELT: the slow exact holder/future gate was read
        // outside this monitor (avoids holder<->tickLock inversion). Under the
        // structural lock revalidate every observed canonical container/head,
        // detach the admitted keys, and fully collect immutable ScheduledTick
        // records. No block handler runs until that frontier is self-consistent.
        // Scope is intentionally the due-key snapshot vanilla collection saw:
        // a brand-new key scheduled after that snapshot waits for the next L,
        // as it would when scheduled after vanilla's collect phase. We do not
        // rescan the slow exact holder gate under tickLock; the proven target
        // is the already-pending Observer whose retained head caused dedup.
        SliceExecution<?> blockExecution = commitPreparedBlockLane(block, unitTime);
        if (block.hold) {
            noteHold(rs, d.owner, unitTime, block);
            return false;
        }
        // The scheduled frontier is admitted at this L: any same-L HOLD
        // streak (head preflight or prepare) ends here.
        if (d.owner != null) clearHoldStreak(d.owner);

        runCollectedIslandLane(block, blockExecution);
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> applyLaneDiagnosticsSafely(rs, d, unitTime, block));

        // Vanilla lane timing: collect fluid only AFTER every block handler.
        // It uses the ordinary exact predicate/refusal skip and can therefore
        // see delay-0 fluid ticks scheduled by the block lane. Fluid never
        // defers an already-partially-executed island unit.
        runVanillaIslandLane(rs, d, unitTime, rs.fluidTicksAccess, rs.fluidTicker,
                new AtomicInteger(65536), false);
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static PreparedIslandLane<?> prepareIslandLane(
            RegionizedServer rs, EpochDomain d, long unitTime,
            Object accessObj, java.util.function.BiConsumer tickerRaw,
            AtomicInteger budget, boolean blockLane) {
        if (accessObj == null || tickerRaw == null) {
            return new PreparedIslandLane<>(null, null, budget, blockLane, null);
        }
        LevelTicksIslandAccess access = (LevelTicksIslandAccess) accessObj;
        Long2LongMap nextTick = access.fabrolia$nextTickForContainer();
        if (nextTick.isEmpty()) {
            return new PreparedIslandLane<>(access, tickerRaw, budget, blockLane, null);
        }
        LongArrayList due = discoverIslandDueChunks(rs, d, unitTime, access, nextTick, false);
        PreparedIslandLane lane = new PreparedIslandLane(
                access, tickerRaw, budget, blockLane, due);
        if (due != null) {
            prepareBlockSlice(rs, d, lane, unitTime);
        }
        return lane;
    }

    /** Ordinary post-block lane: discover and collect at this exact slot, then
     *  use vanilla's exact refusal skip. There is intentionally no fail-closed
     *  preflight here; block handlers may schedule delay-0 fluid work that must
     *  be visible to this collection. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void runVanillaIslandLane(
            RegionizedServer rs, EpochDomain d, long unitTime,
            Object accessObj, java.util.function.BiConsumer tickerRaw,
            AtomicInteger budget, boolean blockLane) {
        if (accessObj == null || tickerRaw == null) return;
        LevelTicksIslandAccess access = (LevelTicksIslandAccess) accessObj;
        Long2LongMap nextTick = access.fabrolia$nextTickForContainer();
        if (nextTick.isEmpty()) return;
        LongArrayList due = discoverIslandDueChunks(rs, d, unitTime, access, nextTick, true);
        if (due == null) return;
        PreparedIslandLane lane = new PreparedIslandLane(
                access, tickerRaw, budget, blockLane, due);
        runSlice(access, due, unitTime, budget, tickerRaw, lane);
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> applyLaneDiagnosticsSafely(rs, d, unitTime, lane));
    }

    @SuppressWarnings({"rawtypes"})
    private static LongArrayList discoverIslandDueChunks(
            RegionizedServer rs, EpochDomain d, long unitTime,
            LevelTicksIslandAccess access, Long2LongMap nextTick, boolean closedFrontierOnly) {
        LongArrayList due = null;
        IslandRegistry.PIsland owner = d.owner;
        if (owner == null) return null;

        // OWNER-LOCAL DISCOVERY (2026-08-29): the old implementation copied and
        // scanned the complete level-wide nextTickForContainer table once for
        // every autonomous island and for both lanes. With 100 separated players
        // that was O(I * (Nblock + Nfluid)); worse, copyPrimitiveEntries scans
        // segment capacity, so terrain generation left a permanent high-water
        // cost even after most scheduled keys disappeared.
        //
        // A static cell owns exactly (2^shift)^2 chunk positions. The unit claim
        // keeps the frontier stable. A newly FULL halo may supply scheduled work
        // only when its own one-cell write halo is also owned. Otherwise fluid
        // spread or block updates can escape the current domain before Main's
        // next topology promotion. Keep unsafe block candidates for pulse HOLD
        // arbitration; fluid candidates remain indexed and unconsumed here.
        long laneIslandId = owner.id;
        long laneGen = owner.gen;
        var cells = owner.cells.iterator();
        while (cells.hasNext()) {
            long cellKey = cells.nextLong();
            Region core = rs.regionizer().peekByGridKey(cellKey);
            // A core can depopulate on Main after this wall tick's rebuild while
            // its current unit is already running. It remains in this stable
            // owner frontier until the next rebuild, so scan it but require the
            // domain identity to stay exact.
            if (core == null || core.domain != d) {
                throw scheduledDiscoveryInvariant("owner core", cellKey, core, d);
            }
            due = probeCellDueChunks(rs, access, nextTick, cellKey,
                    laneIslandId, laneGen, unitTime, due);
        }

        var haloCells = owner.haloCells.iterator();
        while (haloCells.hasNext()) {
            long cellKey = haloCells.nextLong();
            Region halo = rs.regionizer().peekByGridKey(cellKey);
            int populatedChunks = halo == null ? 0 : halo.fullChunkCount.get();
            if (populatedChunks == 0) continue;
            if (populatedChunks < 0 || halo.domain != d) {
                throw scheduledDiscoveryInvariant("populated owner halo", cellKey, halo, d);
            }
            if (closedFrontierOnly && !hasClosedScheduledFrontier(rs, d, cellKey)) continue;
            due = probeCellDueChunks(rs, access, nextTick, cellKey,
                    laneIslandId, laneGen, unitTime, due);
        }
        return due;
    }
    /**
     * True when a populated halo cell of {@code d}'s island lacks its one-cell
     * write margin: Main made its chunks FULL after the last rebuild and the next
     * rebuild has not absorbed it yet. The scheduled-tick rule above applies to the
     * whole unit then (audit X-03): random ticks, spawning, block entities and block
     * events of that halo could write into a neighbour another island or Main owns.
     */
    static boolean hasOpenPopulatedFrontier(RegionizedServer rs, EpochDomain d) {
        IslandRegistry.PIsland owner = d.owner;
        if (owner == null) return false;
        var haloCells = owner.haloCells.iterator();
        while (haloCells.hasNext()) {
            long cellKey = haloCells.nextLong();
            Region halo = rs.regionizer().peekByGridKey(cellKey);
            if (halo == null || halo.fullChunkCount.get() <= 0) continue;
            if (!hasClosedScheduledFrontier(rs, d, cellKey)) return true;
        }
        return false;
    }

    /** Core closure is a topology invariant. A newly populated halo has no such
     *  guarantee: require its complete write margin before admitting handlers.
     *  Existing Regions retain authority over the cold-creation snapshot. */
    private static boolean hasClosedScheduledFrontier(
            RegionizedServer rs, EpochDomain d, long cellKey) {
        if (d.owner.cells.contains(cellKey)) return true;
        int cellX = (int) cellKey;
        int cellZ = (int) (cellKey >> 32);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                long neighborKey = ((cellX + dx) & 0xFFFFFFFFL)
                        | (((cellZ + dz) & 0xFFFFFFFFL) << 32);
                Region neighbor = rs.regionizer().peekByGridKey(neighborKey);
                EpochDomain target = neighbor == null
                        ? rs.domainForCreation(neighborKey) : neighbor.domain;
                if (target != d) return false;
            }
        }
        return true;
    }


    /**
     * Discover physical-Main scheduled work without scanning the level-wide
     * nextTick map. The FULL transition seam maintains the exact set of cells
     * whose stored keys can currently pass vanilla tickCheck. Adopted cells are
     * removed only after their owner core/halo registry is verified.
     */
    private static <T> LongArrayList discoverGlobalDueChunks(
            RegionizedServer rs, long gameTime, LevelTicksIslandAccess<T> access,
            Long2LongMap nextTick) {
        LongArrayList due = null;
        ThreadedRegionizer regionizer = rs.regionizer();
        var cells = rs.scheduledTickDiscoveryCellsMainThread("global discovery").iterator();
        while (cells.hasNext()) {
            long cellKey = cells.nextLong();
            Region region = regionizer.peekByGridKey(cellKey);
            if (region == null || region.fullChunkCount.get() <= 0 || region.domain == null) {
                throw scheduledDiscoveryInvariant(
                        "physical-Main candidate", cellKey, region, rs.globalDomain);
            }

            EpochDomain domain = region.domain;
            IslandRegistry.PIsland owner = domain.owner;
            if (owner != null) {
                if (owner.retired
                        || (!owner.cells.contains(cellKey) && !owner.haloCells.contains(cellKey))) {
                    throw scheduledDiscoveryInvariant(
                            "adopted candidate outside owner frontier", cellKey, region, domain);
                }
                cells.remove();
                continue;
            }
            if (domain != rs.globalDomain) {
                throw scheduledDiscoveryInvariant(
                        "ownerless non-global candidate", cellKey, region, rs.globalDomain);
            }
            due = probeCellDueChunks(rs, access, nextTick, cellKey,
                    -1L, -1L, gameTime, due);
        }
        return due;
    }

    /** Group function of every {@code nextTickForContainer} map: the region-grid
     *  cell of a packed chunk position ({@link RegionGrid#DEFAULT_SHIFT}). */
    public static long scheduledTickCell(long chunkPos) {
        return TICK_CELLS.keyFor(chunkPos);
    }

    private static final RegionGrid TICK_CELLS = new RegionGrid(RegionGrid.DEFAULT_SHIFT);

    /** The chunk positions of {@code cellKey} that hold scheduled ticks. The
     *  index keeps discovery proportional to scheduled containers instead of
     *  probing all (2^shift)^2 positions of every owned cell three times per
     *  unit (the 2026-10-25 profile: 9 % of worker CPU in those probes). */
    private static LongArrayList scheduledChunksInCell(RegionizedServer rs, Long2LongMap nextTick,
                                                       long cellKey) {
        if (rs.regionizer().grid.shift != TICK_CELLS.shift
                || !(nextTick instanceof com.axalotl.fabrolia.api.fastutil.Long2LongConcurrentHashMap indexed)) {
            throw new IllegalStateException("scheduled-tick cell index does not match the region grid");
        }
        LongArrayList chunks = new LongArrayList();
        indexed.collectKeysInGroup(cellKey, chunks);
        // Preserve the former x-then-z cell walk. Loaded containers can have
        // equal priority/subTickOrder (unpack numbers each chunk separately),
        // so their PriorityQueue drain order depends on insertion order.
        chunks.sort((a, b) -> {
            int x = Integer.compare((int) a, (int) b);
            return x != 0 ? x : Integer.compare((int) (a >> 32), (int) (b >> 32));
        });
        return chunks;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static LongArrayList probeCellDueChunks(
            RegionizedServer rs, LevelTicksIslandAccess access, Long2LongMap nextTick,
            long cellKey, long laneIslandId, long laneGen, long laneTime,
            LongArrayList due) {
        LongArrayList scheduled = scheduledChunksInCell(rs, nextTick, cellKey);
        for (int i = 0, n = scheduled.size(); i < n; i++) {
            long chunkPos = scheduled.getLong(i);
            if (nextTick.get(chunkPos) == Long.MAX_VALUE) continue;

            // The container tag, not the map's compressed wall/logical
            // value, is authoritative across topology/domain transitions.
            long next = com.axalotl.fabrolia.common.regions.TickDomains
                    .enforceConsumerDomain(
                            rs.level, access, chunkPos,
                            laneIslandId, laneGen, laneTime);
            if (next > laneTime) continue;
            if (due == null) due = new LongArrayList(16);
            due.add(chunkPos);
        }
        return due;
    }

    private static IllegalStateException scheduledDiscoveryInvariant(
            String scope, long cellKey, Region region, EpochDomain expected) {
        String actual = region == null ? "<missing-region>"
                : region.domain == null ? "<null-domain>"
                : region.domain == expected ? "expected"
                : region.domain.owner == null ? "global/ownerless"
                : "island#" + region.domain.owner.id + "/gen" + region.domain.owner.gen;
        return new IllegalStateException("scheduled-tick discovery invariant failed: scope="
                + scope + " cell=" + (int) cellKey + "," + (int) (cellKey >> 32)
                + " full=" + (region == null ? -1 : region.fullChunkCount.get())
                + " actualDomain=" + actual);
    }

    /**
     * Exact cold-path check behind the monotonic per-container hint. The head
     * shortcut covers the normal machine case; a snapshot scan is needed when
     * one of several due Observer/Dropper ticks is not the container head.
     * Pure worldgen containers never set the hint and therefore never scan.
     */
    private static <T> boolean hasDuePulseSensitiveTick(LevelChunkTicks<T> container,
                                                        ScheduledTick<T> head,
                                                        long gameTime) {
        if (head != null && head.triggerTick() <= gameTime
                && ScheduledTickIslandAdmission.requiresPulseCoherence(head.type())) {
            return true;
        }
        CausalScheduledTickIndex index = (CausalScheduledTickIndex) (Object) container;
        if (!index.fabrolia$mayContainPulseSensitiveTick()) return false;
        synchronized (container) {
            return container.getAll().anyMatch(tick -> tick.triggerTick() <= gameTime
                    && ScheduledTickIslandAdmission.requiresPulseCoherence(tick.type()));
        }
    }

    /** Exact vanilla collection decision without shared queue/map mutation.
     *  Both autonomous and wall-clock block lanes use this same read-only
     *  pass; only the static cell frontier differs from vanilla collection. */
    private static <T> void prepareBlockSlice(RegionizedServer rs, EpochDomain d,
                                               PreparedIslandLane<T> lane,
                                               long gameTime) {
        ServerLevel level = rs.level;
        LevelTicksIslandAccess<T> access = lane.access;
        Long2ObjectMap<LevelChunkTicks<T>> containers = access.fabrolia$allContainers();
        LongPredicate tickCheck = access.fabrolia$tickCheck();
        long checkedCell = 0;
        boolean checkedFrontier = false;
        boolean frontierClosed = false;
        for (int i = 0, n = lane.dueChunks.size(); i < n; i++) {
            long chunkPos = lane.dueChunks.getLong(i);
            LevelChunkTicks<T> container = containers.get(chunkPos);
            if (container == null) {
                lane.prepared.add(new PreparedChunk<>(chunkPos, null, null, false));
                continue;
            }
            ScheduledTick<T> peek = container.peek();
            if (peek == null || peek.triggerTick() > gameTime) {
                lane.prepared.add(new PreparedChunk<>(chunkPos, container, peek, false));
                continue;
            }
            long cellKey = rs.regionizer().grid.keyFor(chunkPos);
            if (!checkedFrontier || checkedCell != cellKey) {
                frontierClosed = hasClosedScheduledFrontier(rs, d, cellKey);
                checkedCell = cellKey;
                checkedFrontier = true;
            }
            if (!frontierClosed) {
                lane.prepared.add(new PreparedChunk<>(chunkPos, container, peek, false));
                if (hasDuePulseSensitiveTick(container, peek, gameTime)) {
                    markHold(lane, chunkPos, peek, "pulse-sensitive-open-write-frontier");
                }
                continue;
            }

            // Within the closed write frontier, exact tickCheck remains the
            // readiness authority. The DM entity-ticking snapshot is sampled
            // before it and, on refusal, once after it only as a narrowing
            // heuristic: outside that range vanilla refuses for distance alone
            // (DORMANT); inside it the refusal is a readiness hole and the
            // unit holds until it heals (a stall is reported, not degraded).
            boolean entityDmBefore = inEntityTickingRange(level, chunkPos);
            boolean exact = tickCheck.test(chunkPos);
            if (exact) {
                lane.prepared.add(new PreparedChunk<>(chunkPos, container, peek, true));
                long overdue = gameTime - peek.triggerTick();
                if (overdue > 32 && overdue > lane.maxOverdue) {
                    lane.maxOverdue = overdue;
                    lane.overdueChunk = chunkPos;
                }
                continue;
            }
            lane.prepared.add(new PreparedChunk<>(chunkPos, container, peek, false));
            boolean entityDmAfter = inEntityTickingRange(level, chunkPos);
            boolean entityDmReady = entityDmBefore || entityDmAfter;
            boolean pulseCoherenceRequired = entityDmReady
                    && hasDuePulseSensitiveTick(container, peek, gameTime);
            ScheduledTickIslandAdmission.Decision decision =
                    ScheduledTickIslandAdmission.classify(
                            false, entityDmReady, pulseCoherenceRequired);
            if (decision == ScheduledTickIslandAdmission.Decision.HOLD) {
                markHold(lane, chunkPos, peek,
                        "pulse-sensitive-exact-refusal/dm-true");
            } else {
                long overdue = gameTime - peek.triggerTick();
                if (overdue > 32) {
                    lane.dormantRefused++;
                    lane.dormantMaxAge = Math.max(lane.dormantMaxAge, overdue);
                }
            }
            // DORMANT retains only this refused container as vanilla does.
            // HOLD is reserved for a due pulse-sensitive tick: the caller runs
            // no handler from this lane and no later island phase at this L.
        }
    }

    /** Revalidate every canonical block container/head before any mutation.
     *  Pulse-sensitive drift holds the complete unit. Ordinary world work is
     *  converted to the same per-container refusal vanilla would make instead
     *  of freezing the island behind a leaf/gravel publication race. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean validatePreparedIslandLane(PreparedIslandLane<?> rawLane,
                                                       long unitTime) {
        PreparedIslandLane lane = rawLane;
        if (lane.access == null || lane.dueChunks == null) return true;
        Long2ObjectMap<LevelChunkTicks<Object>> containers =
                lane.access.fabrolia$allContainers();
        for (int i = 0, n = lane.prepared.size(); i < n; i++) {
            PreparedChunk<Object> prepared = (PreparedChunk<Object>) lane.prepared.get(i);
            LevelChunkTicks<Object> current = containers.get(prepared.chunkPos);
            ScheduledTick<Object> currentHead = current != null ? current.peek() : null;
            boolean stable = ScheduledTickIslandAdmission.preparedShapeStable(
                    prepared.container, current, prepared.head, currentHead);
            if (stable && prepared.admitted) {
                stable = prepared.head != null && prepared.head.triggerTick() <= unitTime;
            }
            if (!stable) {
                boolean preparedPulse = prepared.head != null
                        && prepared.head.triggerTick() <= unitTime
                        && ScheduledTickIslandAdmission.requiresPulseCoherence(
                                prepared.head.type());
                boolean currentPulse = current != null
                        && hasDuePulseSensitiveTick(current, currentHead, unitTime);
                if (preparedPulse || currentPulse) {
                    markHold(lane, prepared.chunkPos,
                            currentPulse ? currentHead : prepared.head,
                            current == prepared.container
                                    ? "pulse-sensitive-head-drift"
                                    : "pulse-sensitive-container-drift");
                    return false;
                }
                lane.prepared.set(i, new PreparedChunk<>(
                        prepared.chunkPos, current, currentHead, false));
            }
        }
        return true;
    }

    /** Validate and detach one coherent block frontier. Exact readiness is
     *  never re-read after preparation. Under the scheduler lock a racing
     *  pulse-sensitive change converts the read-only plan to HOLD before a
     *  nextTick key or scheduled container is touched; ordinary drift is kept
     *  durable for the next vanilla-style collection. */
    private static SliceExecution<?> commitPreparedBlockLane(
            PreparedIslandLane<?> lane, long gameTime) {
        if (lane.access == null || lane.dueChunks == null) return null;
        SliceRegistry registry = lane.access.fabrolia$sliceRegistry();
        Lock read = registry.visibility.readLock();
        read.lock();
        try {
        // Global order is visibility -> scheduler structural lock -> slice
        // state -> container. clear/copy use the same prefix with the write
        // side, so a queued writer cannot form read<->tickLock AB-BA here.
        synchronized (lane.access.fabrolia$tickLock()) {
            try {
                if (!validatePreparedIslandLane(lane, gameTime)) {
                    return null;
                }
                PriorityQueue<?> ready =
                        commitPreparedIslandSliceUnchecked(lane, gameTime);
                return collectCommittedIslandLane(gameTime, lane, ready);
            } catch (Throwable thrown) {
                // commitPreparedIslandSlice removes due-key indexes before
                // collectSlice allocates its deque and enters its own cleanup
                // envelope. A peer W or allocation failure in that gap must not
                // leave durable containers invisible. Rebuild every index while
                // this exact structural lock is still held, then propagate W.
                Throwable failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
                failure = reindexPreparedLaneAfterFailure(lane, failure);
                IslandScheduledTicks.<RuntimeException>rethrow(failure);
                return null;
            }
        }
        } finally {
            read.unlock();
        }
    }

    /** Mandatory failure cleanup for the prepare -> detach -> collect
     *  transaction. The caller still owns fabrolia$tickLock. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Throwable reindexPreparedLaneAfterFailure(
            PreparedIslandLane<?> rawLane, Throwable primary) {
        Throwable failure = primary;
        try {
            PreparedIslandLane lane = rawLane;
            if (lane.access == null || lane.dueChunks == null) return failure;
            Long2ObjectMap<LevelChunkTicks<Object>> containers =
                    lane.access.fabrolia$allContainers();
            Long2LongMap nextTick = lane.access.fabrolia$nextTickForContainer();
            // prepareBlockSlice de-duplicates due keys before appending records,
            // so this pass needs no allocation-backed seen set (important on an
            // OOME cleanup path).
            for (int i = 0, n = lane.prepared.size(); i < n; i++) {
                PreparedChunk<Object> prepared =
                        (PreparedChunk<Object>) lane.prepared.get(i);
                LevelChunkTicks<Object> current = containers.get(prepared.chunkPos);
                ScheduledTick<Object> head = current != null ? current.peek() : null;
                if (head == null) nextTick.remove(prepared.chunkPos);
                else nextTick.put(prepared.chunkPos, head.triggerTick());
            }
        } catch (Throwable cleanup) {
            failure = mergeFailure(failure, cleanup);
        }
        return failure;
    }

    /** Apply overdue/dormant diagnostic state only after the lane's scheduler
     *  state is safe. Same-L rejected attempts must not look completed. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void applyPreparedDiagnostics(RegionizedServer rs, EpochDomain d,
                                                 long unitTime,
                                                 PreparedIslandLane<?> rawLane) {
        PreparedIslandLane lane = rawLane;
        if (lane.access == null || lane.dueChunks == null) return;
        if (lane.dormantRefused > 0) {
            FlightRecorder.DORMANT_REFUSED.add(lane.dormantRefused);
            FlightRecorder.noteDormantAge(lane.dormantMaxAge);
        }
        if (lane.maxOverdue <= 32) {
            if (lane.blockLane) { d.overdueStreakB = 0; d.overdueStreakChunkB = 0; }
            else { d.overdueStreakF = 0; d.overdueStreakChunkF = 0; }
            return;
        }

        boolean stuck;
        if (lane.blockLane) {
            stuck = d.overdueStreakChunkB == lane.overdueChunk && ++d.overdueStreakB >= 3;
            if (d.overdueStreakChunkB != lane.overdueChunk) {
                d.overdueStreakChunkB = lane.overdueChunk;
                d.overdueStreakB = 1;
            }
        } else {
            stuck = d.overdueStreakChunkF == lane.overdueChunk && ++d.overdueStreakF >= 3;
            if (d.overdueStreakChunkF != lane.overdueChunk) {
                d.overdueStreakChunkF = lane.overdueChunk;
                d.overdueStreakF = 1;
            }
        }
        String dim = rs.level.dimension().identifier().toString();
        if (FlightRecorder.DEBUG) {
            FlightRecorder.record("OVERDUE-BURST " + dim + " island#"
                    + (d.owner != null ? d.owner.id : -1) + " L=" + unitTime
                    + " maxOverdue=" + lane.maxOverdue
                    + " dueContainers=" + (lane.dueChunks == null ? 0 : lane.dueChunks.size())
                    + " lane=" + (lane.blockLane ? "block" : "fluid"));
            Object cObj = lane.access.fabrolia$allContainers().get(lane.overdueChunk);
            if (stuck && cObj instanceof LevelChunkTicks<?> c) {
                ScheduledTick<?> h = c.peek();
                FlightRecorder.record("OVERDUE-CONTAINER " + dim
                        + " chunk=[" + ChunkPos.getX(lane.overdueChunk)
                        + "," + ChunkPos.getZ(lane.overdueChunk) + "]"
                        + " island#" + (d.owner != null ? d.owner.id : -1)
                        + " count=" + c.count() + " L=" + unitTime
                        + " head=" + describeHead(h)
                        + " overdue=" + lane.maxOverdue
                        + " lane=" + (lane.blockLane ? "block" : "fluid"));
            }
        }
        if (stuck) {
            FlightRecorder.dump("TICKABLE container stuck overdue " + lane.maxOverdue
                    + " ticks across 3+ consecutive completed units on island#"
                    + (d.owner != null ? d.owner.id : -1) + " (" + dim + ")");
        }
    }

    /** Drain the committed block frontier into a local handler queue. This
     *  also restores every leftover nextTick index. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static SliceExecution<?> collectCommittedIslandLane(
            long unitTime, PreparedIslandLane<?> rawLane, PriorityQueue<?> rawReady) {
        PreparedIslandLane lane = rawLane;
        if (lane.access == null || lane.dueChunks == null) return null;
        return collectSlice(lane.access, (PriorityQueue) rawReady, unitTime, lane.budget);
    }

    /** Execute one already-collected lane without scheduler map/PQ work. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void runCollectedIslandLane(PreparedIslandLane<?> rawLane,
                                               SliceExecution<?> rawExecution) {
        PreparedIslandLane lane = rawLane;
        if (lane.access == null || lane.dueChunks == null) return;
        runCollectedSlice((SliceExecution) rawExecution, lane.ticker);
    }

    /** Budget diagnostics run only after this lane's scheduler state is safe. */
    @SuppressWarnings({"rawtypes"})
    private static void applyBudgetDiagnostics(RegionizedServer rs, EpochDomain d,
                                               long unitTime,
                                               PreparedIslandLane<?> lane) {
        if (lane.access == null || lane.dueChunks == null) return;
        if (lane.budget.get() <= 0) {
            FlightRecorder.record("BUDGET-EXHAUSTED " + rs.level.dimension().identifier()
                    + " island#" + (d.owner != null ? d.owner.id : -1)
                    + " L=" + unitTime + " lane=" + (lane.blockLane ? "block" : "fluid")
                    + " dueContainers=" + lane.dueChunks.size());
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

    /** Diagnostics are observational: they cannot strand a collected queue or
     *  mask a scheduled handler failure. */
    private static void applyLaneDiagnosticsSafely(
            RegionizedServer rs, EpochDomain d, long unitTime,
            PreparedIslandLane<?> lane) {
        try {
            applyPreparedDiagnostics(rs, d, unitTime, lane);
            applyBudgetDiagnostics(rs, d, unitTime, lane);
        } catch (Throwable diagnostic) {
            try {
                LOGGER.error("[scheduled-ticks] post-lane diagnostic failed (contained)",
                        diagnostic);
            } catch (Throwable ignored) {
                // Simulation state is already safe; diagnostics stay optional.
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static PriorityQueue<?> commitPreparedIslandSliceUnchecked(
            PreparedIslandLane<?> lane, long gameTime) {
        if (lane.access == null || lane.dueChunks == null) {
            return new PriorityQueue<>();
        }
        return commitPreparedIslandSlice((PreparedIslandLane) lane, gameTime);
    }

    /** Apply the block plan revalidated under its structural lane lock. Exact
     *  eligibility is deliberately not re-read under the lock. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static PriorityQueue<LevelChunkTicks<Object>> commitPreparedIslandSlice(
            PreparedIslandLane lane, long gameTime) {
        LevelTicksIslandAccess<Object> access = lane.access;
        Long2ObjectMap<LevelChunkTicks<Object>> containers = access.fabrolia$allContainers();
        Long2LongMap nextTick = access.fabrolia$nextTickForContainer();
        PriorityQueue<LevelChunkTicks<Object>> containersToTick =
                new PriorityQueue<>((Comparator) containerOrder());
        for (int i = 0, n = lane.prepared.size(); i < n; i++) {
            PreparedChunk<Object> prepared = (PreparedChunk<Object>) lane.prepared.get(i);
            LevelChunkTicks<Object> current = containers.get(prepared.chunkPos);
            if (prepared.container == null) {
                if (current == null) nextTick.remove(prepared.chunkPos);
                continue;
            }
            if (prepared.admitted) {
                // The block frontier already passed identity+head validation
                // while its structural lock is held. Use the prepared canonical
                // container directly: never drop only the target and let its
                // source run.
                nextTick.remove(prepared.chunkPos);
                containersToTick.add(prepared.container);
                continue;
            }
            if (current != prepared.container) {
                continue; // cleanup observation raced a reinstall; new key owns its entry
            }
            ScheduledTick<Object> peek = current.peek();
            if (peek == null) {
                nextTick.remove(prepared.chunkPos);
            } else {
                // A future/empty-at-prepare container that became due was not
                // exact-gated in this collection; leave it for the next
                // collection instead of silently admitting it after the
                // barrier decision.
                nextTick.put(prepared.chunkPos, peek.triggerTick());
            }
        }
        return containersToTick;
    }

    /** Bounded head description: pos, type, trigger, subTickOrder. */
    private static String describeHead(net.minecraft.world.ticks.ScheduledTick<?> h) {
        if (h == null) return "-";
        return h.pos().toShortString() + " type=" + h.type()
                + " trigger=" + h.triggerTick() + " sub=" + h.subTickOrder();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T) failure;
    }

    /** Vanilla collect (collectTicks) + run + cleanup over slice-local state,
     * scoped to {@code dueChunks}. Eligibility is prepared read-only, then its
     * exact container/head identity is committed before the vanilla drain. */
    private static <T> void runSlice(LevelTicksIslandAccess<T> access, LongArrayList dueChunks,
                                     long gameTime, AtomicInteger budget,
                                     BiConsumer<BlockPos, T> ticker) {
        runSlice(access, dueChunks, gameTime, budget, ticker, null);
    }

    /** Ordinary slice with optional post-safe-state island diagnostics. */
    private static <T> void runSlice(LevelTicksIslandAccess<T> access, LongArrayList dueChunks,
                                     long gameTime, AtomicInteger budget,
                                     BiConsumer<BlockPos, T> ticker,
                                     PreparedIslandLane<T> diagnostics) {
        RegionScheduler.throwWorkerFailureIfAny();
        SliceRegistry registry = access.fabrolia$sliceRegistry();
        Long2ObjectMap<LevelChunkTicks<T>> containers = access.fabrolia$allContainers();
        LongPredicate tickCheck = access.fabrolia$tickCheck();
        ArrayList<PreparedChunk<T>> prepared = new ArrayList<>(dueChunks.size());

        // Phase 1 is read-only and deliberately outside tickLock. tickCheck may
        // enter chunk-holder readiness paths whose publication side later
        // takes tickLock; putting the predicate under that lock recreates the
        // documented holder<->tickLock inversion.
        for (int i = 0, n = dueChunks.size(); i < n; i++) {
            long chunkPos = dueChunks.getLong(i);
            LevelChunkTicks<T> container = containers.get(chunkPos);
            if (container == null) {
                prepared.add(new PreparedChunk<>(chunkPos, null, null, false));
                continue;
            }
            ScheduledTick<T> peek = container.peek();
            if (peek == null || peek.triggerTick() > gameTime) {
                prepared.add(new PreparedChunk<>(chunkPos, container, peek, false));
            } else {
                boolean exact = tickCheck.test(chunkPos);
                if (exact) {
                    prepared.add(new PreparedChunk<>(chunkPos, container, peek, true));
                    if (diagnostics != null) {
                        long overdue = gameTime - peek.triggerTick();
                        if (overdue > 32 && overdue > diagnostics.maxOverdue) {
                            diagnostics.maxOverdue = overdue;
                            diagnostics.overdueChunk = chunkPos;
                        }
                    }
                } else {
                    prepared.add(new PreparedChunk<>(chunkPos, container, peek, false));
                    if (diagnostics != null) {
                        long overdue = gameTime - peek.triggerTick();
                        if (overdue > 32) {
                            diagnostics.dormantRefused++;
                            diagnostics.dormantMaxAge = Math.max(
                                    diagnostics.dormantMaxAge, overdue);
                        }
                    }
                }
            }
        }

        // Phase 2 revalidates the exact container/head identities under the
        // canonical lock prefix. A clear/repair between phases may expose a
        // future head; identity drift is re-indexed for a later logical tick,
        // never admitted from the stale phase-1 decision.
        SliceExecution<T> execution;
        Lock read = registry.visibility.readLock();
        read.lock();
        try {
        synchronized (access.fabrolia$tickLock()) {
        Long2LongMap nextTick = access.fabrolia$nextTickForContainer();
        PriorityQueue<LevelChunkTicks<T>> containersToTick =
                new PriorityQueue<>(containerOrder());
        try {
        for (PreparedChunk<T> candidate : prepared) {
            LevelChunkTicks<T> current = containers.get(candidate.chunkPos);
            ScheduledTick<T> currentHead = current != null ? current.peek() : null;
            boolean stable = ScheduledTickIslandAdmission.preparedShapeStable(
                    candidate.container, current, candidate.head, currentHead);
            if (stable && candidate.admitted && currentHead != null
                    && currentHead.triggerTick() <= gameTime) {
                nextTick.remove(candidate.chunkPos);
                containersToTick.add(current);
            } else if (currentHead == null) {
                nextTick.remove(candidate.chunkPos);
            } else {
                nextTick.put(candidate.chunkPos, currentHead.triggerTick());
            }
        }

        // collectSlice re-enters visibility READ safely; tickLock is reentrant.
        // Both remain held until every selected stable head is published in the
        // SliceExecution or failure cleanup has restored/re-indexed it.
        execution = collectSlice(access, containersToTick, gameTime, budget);
        } catch (Throwable thrown) {
            // collectSlice performs its own cleanup after entering its try, but
            // its initial peer-W check precedes that envelope. Commit may also
            // fail after removing only a prefix of due indexes. Rebuild every
            // prepared key under this still-held structural lock, select the
            // exact fatal winner, and propagate it without retrying handlers.
            Throwable failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
            failure = reindexOrdinaryPreparedAfterFailure(
                    access, prepared, failure);
            IslandScheduledTicks.<RuntimeException>rethrow(failure);
            return;
        }
        }
        } finally {
            read.unlock();
        }

        // No visibility/tickLock is held across user/vanilla handler bodies.
        runCollectedSlice(execution, ticker);
        RegionScheduler.throwWorkerFailureIfAny();
    }

    /** Mandatory index repair for every failure between ordinary commit and
     * published collection. Caller holds fabrolia$tickLock. */
    private static <T> Throwable reindexOrdinaryPreparedAfterFailure(
            LevelTicksIslandAccess<T> access,
            List<PreparedChunk<T>> prepared,
            Throwable primary) {
        Throwable failure = primary;
        try {
            Long2ObjectMap<LevelChunkTicks<T>> containers =
                    access.fabrolia$allContainers();
            Long2LongMap nextTick = access.fabrolia$nextTickForContainer();
            for (PreparedChunk<T> candidate : prepared) {
                LevelChunkTicks<T> current = containers.get(candidate.chunkPos);
                ScheduledTick<T> head = current != null ? current.peek() : null;
                if (head == null) nextTick.remove(candidate.chunkPos);
                else nextTick.put(candidate.chunkPos, head.triggerTick());
            }
        } catch (Throwable cleanup) {
            failure = mergeFailure(failure, cleanup);
        }
        return failure;
    }

    /** Vanilla collectTicksToRun + leftover re-indexing, with no handlers. */
    private static <T> SliceExecution<T> collectSlice(
            LevelTicksIslandAccess<T> access,
            PriorityQueue<LevelChunkTicks<T>> containersToTick,
            long gameTime, AtomicInteger budget) {
        RegionScheduler.throwWorkerFailureIfAny();
        Long2LongMap nextTick = access.fabrolia$nextTickForContainer();
        SliceRegistry registry = access.fabrolia$sliceRegistry();
        Lock read = registry.visibility.readLock();
        read.lock();

        // collectTicksToRun (incl. the drain-while-cheaper-than-next-container
        // inner loop) — vanilla structure, shared global budget
        SliceExecution<T> execution = null;
        LevelChunkTicks<T> top = null;
        Throwable failure = null;
        try {
        // Registration precedes the first durable poll under the same read-side
        // visibility cut. A clear/copy writer therefore sees either the old
        // container state or this published replica, never neither.
        execution = registerSlice(access);
        while (budget.get() > 0 && (top = containersToTick.poll()) != null) {
            // The comparison bar is read BEFORE taking top's monitor on purpose:
            // nextBest.peek() enters a DIFFERENT container's monitor, and two
            // slices draining crossing container pairs would give an A->B / B->A
            // cycle (the POI DistanceTracker/PoiSection shape, task #38). Every
            // container monitor here is therefore held alone, never nested.
            LevelChunkTicks<T> nextBest = containersToTick.peek();
            ScheduledTick<T> bar = nextBest != null ? nextBest.peek() : null;

            ScheduledTick<T> next;
            // Whole per-container drain under the container monitor: peek/poll
            // pairs must be ATOMIC. Collection (above) peeked in an earlier
            // phase, and containers are reachable off-slice — TickDomains
            // drains (clearArea/fill), shiftAll, and c2me's off-thread pack().
            synchronized (execution.state) {
            synchronized (top) {
                // peek-before-poll, same contract as TickDomains.drainRemoveIf:
                // Lithium's poll() NPEs when nextTickQueue is null (empty or
                // packed container), and — worse — on an empty-BUT-PRESENT head
                // bucket it returns null while leaving that bucket wired as both
                // nextTickQueue and the map's first key. queueTick's
                // updateNextTickQueue(false) then re-selects it by firstLongKey()
                // forever: peek/poll return null permanently, LevelTicks drops
                // the chunk from nextTickForContainer, yet schedule() keeps
                // filling allTicks so hasScheduledTick stays true and
                // repeaters/observers/fluids in that chunk never reschedule.
                // Silent, persistent, chunk-local death of all scheduled ticks
                // until the chunk reloads. peek() is null-safe in BOTH impls.
                ScheduledTick<T> first = top.peek() != null ? top.poll() : null;
                if (first == null) {
                    // Drained between collection and here by another slice or an
                    // off-slice mutator. Its nextTick entry was already removed
                    // at collection time; nothing left owed for this container.
                    continue;
                }
                execution.toRun.add(first);
                if (!execution.pending.isEmpty()) execution.pending.add(first);
                budget.decrementAndGet();

                while (budget.get() > 0) {
                    ScheduledTick<T> cand = top.peek();
                    if (cand == null || cand.triggerTick() > gameTime
                            || (bar != null && ScheduledTick.INTRA_TICK_DRAIN_ORDER.compare(cand, bar) > 0)) {
                        break;
                    }
                    top.poll();
                    execution.toRun.add(cand);
                    if (!execution.pending.isEmpty()) execution.pending.add(cand);
                    budget.decrementAndGet();
                }

                next = top.peek();
            }
            }
            if (next != null) {
                if (next.triggerTick() <= gameTime && budget.get() > 0) {
                    containersToTick.add(top);
                } else {
                    nextTick.put(ChunkPos.pack(next.pos()), next.triggerTick());
                }
            }
            top = null;
        }
        // rescheduleLeftoverContainers (budget exhausted)
        for (LevelChunkTicks<T> leftover : containersToTick) {
            ScheduledTick<T> peek = leftover.peek();
            if (peek != null) {
                nextTick.put(ChunkPos.pack(peek.pos()), peek.triggerTick());
            }
        }

        RegionScheduler.throwWorkerFailureIfAny();
        synchronized (execution.state) {
            // Same allocation placement as vanilla's constructor-owned list:
            // no growth may fail between removing the current tick and entering
            // its handler.
            execution.alreadyRun.ensureCapacity(execution.toRun.size());
        }
        return execution;
        } catch (Throwable thrown) {
            // Collection is pre-handler state.  Select W first, then restore
            // every detached but unentered ScheduledTick before the exact W
            // escapes; otherwise a shutdown save could persist the skipped
            // queue even though no vanilla ticker body entered.
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
            if (execution != null) {
                synchronized (access.fabrolia$tickLock()) {
                    synchronized (execution.state) {
                        failure = restoreUnenteredCollected(access, execution, failure);
                    }
                }
            }
            failure = reindexUnfinishedContainers(
                    access, top, containersToTick, failure);
            if (execution != null) {
                try {
                    retireSlice(execution);
                } catch (Throwable cleanup) {
                    failure = mergeFailure(failure, cleanup);
                }
            }
            IslandScheduledTicks.<RuntimeException>rethrow(failure);
            return execution;
        } finally {
            read.unlock();
        }
    }

    private static final class RunOneState {
        boolean ran;
    }

    /** Vanilla runCollectedTicks over one already-published local queue. */
    private static <T> void runCollectedSlice(
            SliceExecution<T> execution, BiConsumer<BlockPos, T> ticker) {
        LevelTicksIslandAccess<T> access = execution.access;
        SliceRegistry registry = execution.registry;
        Throwable failure = null;
        SliceExecution<?> previous = CURRENT_SLICE.get();
        boolean currentInstalled = false;
        try {
            CURRENT_SLICE.set(execution);
            currentInstalled = true;
            // Allocate the reusable result carrier before the first handler.
            RunOneState state = new RunOneState();
            while (runOneCollectedTick(execution, ticker, state)) {
            }
        } catch (Throwable thrown) {
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        } finally {
            if (currentInstalled) {
                CURRENT_SLICE.set(previous);
            }
        }

        // Retirement and failure restoration share one read-side cut. A
        // clear/copy writer can observe the published suffix or its durable
        // restoration, never an unregister->restore gap.
        Lock read = registry.visibility.readLock();
        read.lock();
        try {
            if (failure != null) {
                synchronized (access.fabrolia$tickLock()) {
                    synchronized (execution.state) {
                        failure = restoreUnenteredCollected(access, execution, failure);
                    }
                }
            }
            try {
                retireSlice(execution);
            } catch (Throwable cleanup) {
                failure = RegionScheduler.publishFatalAndSelectWinner(
                        mergeFailure(failure, cleanup));
            }
        } finally {
            read.unlock();
        }

        if (failure == null) {
            try {
                RegionScheduler.commitSuccessIfHealthy(() -> {});
            } catch (Throwable terminalFailure) {
                failure = RegionScheduler.publishFatalAndSelectWinner(terminalFailure);
            }
        }
        rethrowIfPresent(failure);
    }

    /**
     * Detach and execute exactly one vanilla scheduled-tick obligation under a
     * single generation admission. Queue removal, pending-set removal and the
     * ticker call share one admitted body: after a peer W, state cannot advance
     * for an obligation whose vanilla body never entered.
     */
    private static <T> boolean runOneCollectedTick(
            SliceExecution<T> execution,
            BiConsumer<BlockPos, T> ticker,
            RunOneState state) {
        state.ran = false;
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            ScheduledTick<T> tick = pollCollectedTick(execution);
            if (tick == null) return;
            state.ran = true;
            // Deliberately NO shouldTickBlocksAt re-check here. Ticking eligibility
            // is decided ONCE per chunk at collection (tickCheck above) — exactly
            // vanilla, which never re-evaluates after collecting. The old dispatch-
            // time re-read + rescheduleFromSlice could split one chunk's due ticks
            // across two game ticks whenever the answer flipped mid-phase.
            ticker.accept(tick.pos(), tick.type());
        });
        return state.ran;
    }

    /** Linearized queue -> alreadyRun transition for exactly one slice. The
     * read barrier is intentionally short and ends before handler execution,
     * so a handler may call clearArea/copyAreaFrom without a read-to-write
     * upgrade deadlock. Pending state is per-slice, so different islands share
     * neither this monitor nor their lazy willTick set. */
    static <T> ScheduledTick<T> pollCollectedTick(SliceExecution<T> execution) {
        SliceRegistry registry = execution.registry;
        Lock read = registry.visibility.readLock();
        read.lock();
        try {
            synchronized (execution.state) {
                ScheduledTick<T> tick = execution.toRun.poll();
                if (tick == null) return null;
                if (!execution.pending.isEmpty()) execution.pending.remove(tick);
                execution.alreadyRun.add(tick);
                return tick;
            }
        } finally {
            read.unlock();
        }
    }

    /** Failure cleanup only: restore the exact never-entered suffix. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> Throwable restoreUnenteredCollected(
            LevelTicksIslandAccess<T> access,
            SliceExecution<T> execution,
            Throwable primary) {
        Throwable failure = primary;
        try {
            LongOpenHashSet affected = new LongOpenHashSet();
            ScheduledTick<T> tick;
            while ((tick = execution.toRun.poll()) != null) {
                if (!execution.pending.isEmpty()) execution.pending.remove(tick);
                long chunkKey = ChunkPos.pack(tick.pos());
                Object raw = access.fabrolia$allContainers().get(chunkKey);
                if (!(raw instanceof LevelChunkTicks<?>)) {
                    failure = mergeFailure(failure, new IllegalStateException(
                            "scheduled-tick failure cleanup lost container "
                                    + ChunkPos.getX(chunkKey) + ","
                                    + ChunkPos.getZ(chunkKey)));
                    continue;
                }
                LevelChunkTicks<T> container = (LevelChunkTicks<T>) raw;
                synchronized (container) {
                    TickDomains.restoreAccepted(container, tick);
                }
                affected.add(chunkKey);
            }
            Long2LongMap nextTick = access.fabrolia$nextTickForContainer();
            var it = affected.iterator();
            while (it.hasNext()) {
                long chunkKey = it.nextLong();
                LevelChunkTicks<T> container =
                        (LevelChunkTicks<T>) access.fabrolia$allContainers().get(chunkKey);
                if (container == null) continue;
                ScheduledTick<T> head;
                synchronized (container) {
                    head = container.peek();
                }
                if (head == null) nextTick.remove(chunkKey);
                else nextTick.put(chunkKey, head.triggerTick());
            }
        } catch (Throwable cleanup) {
            failure = mergeFailure(failure, cleanup);
        }
        return failure;
    }

    /** Rebuild due-key visibility if collection itself failed mid-container. */
    private static <T> Throwable reindexUnfinishedContainers(
            LevelTicksIslandAccess<T> access,
            LevelChunkTicks<T> active,
            PriorityQueue<LevelChunkTicks<T>> remaining,
            Throwable primary) {
        Throwable failure = primary;
        try {
            java.util.IdentityHashMap<LevelChunkTicks<T>, Boolean> seen =
                    new java.util.IdentityHashMap<>();
            synchronized (access.fabrolia$tickLock()) {
                if (active != null) seen.put(active, Boolean.TRUE);
                for (LevelChunkTicks<T> container : remaining) {
                    seen.put(container, Boolean.TRUE);
                }
                Long2LongMap nextTick = access.fabrolia$nextTickForContainer();
                for (LevelChunkTicks<T> container : seen.keySet()) {
                    ScheduledTick<T> head;
                    synchronized (container) {
                        head = container.peek();
                    }
                    if (head != null) {
                        nextTick.put(ChunkPos.pack(head.pos()), head.triggerTick());
                    }
                }
            }
        } catch (Throwable cleanup) {
            failure = mergeFailure(failure, cleanup);
        }
        return failure;
    }

    private static Throwable mergeFailure(Throwable primary, Throwable secondary) {
        if (primary == null) return secondary;
        if (secondary != null && secondary != primary) {
            try {
                primary.addSuppressed(secondary);
            } catch (Throwable ignored) {
                // Suppression metadata cannot replace the authoritative failure.
            }
        }
        return primary;
    }

    private static void rethrowIfPresent(Throwable failure) {
        if (failure != null) IslandScheduledTicks.<RuntimeException>rethrow(failure);
    }

    /** Island of a chunk via the LIVE region map; freshly-populated (post-rebuild)
     *  chunks join their single adjacent island (two are impossible — separation
     *  invariant). The hot partition paths now use the equivalent
     *  {@link RegionizedServer#islandForCell} table (rebuilt at tick HEAD together
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
