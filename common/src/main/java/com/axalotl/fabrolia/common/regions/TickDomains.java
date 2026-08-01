package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * The single scheduled-tick clock-domain engine (task #31 rework). Replaces
 * every ownership-guessing conversion path with tag-driven operations over
 * {@link FabroliaTickDomain}-tagged containers:
 *
 * <ul>
 *   <li>{@link #resolveNow} — what "now" is in a container's tagged domain.
 *       Resolution order: live island by id (registry) → retired-island final-L
 *       snapshot ({@code rs.islandLastL}, recorded at every rebuild and at
 *       retirement) → ERROR + wall + counter (structurally unreachable: every
 *       transition that could orphan a tag rebases first).</li>
 *   <li>{@link #rebaseTo} — shift every trigger by (targetNow − tagNow),
 *       preserving type, position, priority and sub-tick order bit-for-bit,
 *       then retag. Delay is clock-invariant: a tick due in N ticks stays due
 *       in N ticks of the new domain. Idempotent when already in the target
 *       domain (delta 0 short-circuits).</li>
 *   <li>{@link #collapseAllToWall} — shutdown/teardown: every island-tagged
 *       container in every level returns to WALL before the scheduler state
 *       becomes unresolvable, so the final world save packs true remaining
 *       delays. Crash safety: periodic saves during async operation resolve
 *       live tags exactly, so the last autosave is always domain-consistent —
 *       a crash loses no more than vanilla does.</li>
 * </ul>
 */
public final class TickDomains {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-TickDomains");

    private TickDomains() {}

    /** Tag resolution failures (should be zero; nonzero = report with logs). */
    public static final AtomicLong UNRESOLVED_TAGS = new AtomicLong();
    /** Containers rebased at registration (load into an island's territory). */
    public static final AtomicLong LOAD_REBASES = new AtomicLong();
    /** Containers collapsed to wall at shutdown. */
    public static final AtomicLong SHUTDOWN_COLLAPSES = new AtomicLong();

    public static long wallNow(ServerLevel level) {
        return IslandClock.wallTime(level);
    }

    /**
     * FINAL-CLOCK RETENTION (2026-07-21 shutdown-order fix): once
     * {@code RegionScheduler.stop()} flips {@code active=false}, both the
     * live-registry and {@code islandLastL} resolution paths go dark
     * ({@code forLevel} is active-gated) — but the shutdown collapse and the
     * FINAL WORLD SAVE still need every island's last logical tick. Before
     * anything is torn down, {@code stop()} snapshots every level's
     * {islandId → final L} here; {@link #resolveNow} consults it whenever the
     * scheduler paths return nothing. Cleared at the next scheduler start.
     * Keyed by ServerLevel identity — entries die with the server instance.
     */
    private static final java.util.Map<ServerLevel, it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap>
            FINAL_CLOCKS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Snapshot every island clock (live + already-retired) for {@code levels}.
     *  MUST run before {@code active=false} consequences bite — i.e. first
     *  thing in stop() after the worker pool quiesces. */
    public static void retainFinalClocks(java.util.Collection<RegionizedServer> levels) {
        for (RegionizedServer rs : levels) {
            it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap m =
                    new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
            synchronized (rs.islandLastL) {
                m.putAll(rs.islandLastL);
            }
            for (IslandRegistry.PIsland p : rs.islandRegistry().islands()) {
                m.put(p.id, p.logicalTick);
            }
            FINAL_CLOCKS.put(rs.level, m);
        }
    }

    /** New scheduler session: stale final clocks are for dead island ids. */
    public static void clearFinalClocks() {
        FINAL_CLOCKS.clear();
    }

    /** "Now" in {@code c}'s tagged domain, or {@code Long.MIN_VALUE} if the
     *  tag names an island this server has never recorded (bug — counted). */
    public static long resolveNow(ServerLevel level, LevelChunkTicks<?> c) {
        return resolveNow(level, c, Long.MIN_VALUE, null);
    }

    /** Context-rich variant: {@code chunkKey}/{@code lane} feed the diagnostic
     *  on resolution failure (pass MIN_VALUE/null when unknown). */
    public static long resolveNow(ServerLevel level, LevelChunkTicks<?> c, long chunkKey, String lane) {
        long now = probeNow(level, c);
        if (now != Long.MIN_VALUE) {
            return now;
        }
        UNRESOLVED_TAGS.incrementAndGet();
        LOGGER.error(fabrolia$unresolvedReport(level, c, (FabroliaTickDomain) (Object) c,
                chunkKey, lane, RegionScheduler.forLevel(level)));
        return Long.MIN_VALUE;
    }

    /**
     * NON-COUNTING, NON-LOGGING resolution probe (2026-07-27 review finding
     * #3a): identical resolution order to {@link #resolveNow} (WALL → live
     * registry → islandLastL → retained FINAL_CLOCKS), but a failure returns
     * {@code Long.MIN_VALUE} silently. For pure classification/diagnostic
     * passes (the bulk {@code TickRepair} scan) that perform NO recovery: the
     * counter and the "recovery=ADOPT-TAG-WITHOUT-SHIFT" ERROR are the
     * operational path's bug signal and must not be inflated by a read-only
     * dry run that adopts nothing.
     */
    public static long probeNow(ServerLevel level, LevelChunkTicks<?> c) {
        FabroliaTickDomain tag = (FabroliaTickDomain) (Object) c;
        long id = tag.fabrolia$domainIsland();
        if (id == FabroliaTickDomain.WALL) {
            return wallNow(level);
        }
        RegionizedServer rs = RegionScheduler.forLevel(level);
        if (rs != null) {
            for (IslandRegistry.PIsland p : rs.islandRegistry().islands()) {
                if (p.id == id) {
                    return p.logicalTick;
                }
            }
            synchronized (rs.islandLastL) {
                if (rs.islandLastL.containsKey(id)) {
                    return rs.islandLastL.get(id);
                }
            }
        }
        // Scheduler inactive (shutdown window) or the id predates this rs:
        // the retained final-clock snapshot is the authority of last resort.
        it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap finals = FINAL_CLOCKS.get(level);
        if (finals != null) {
            synchronized (finals) {
                if (finals.containsKey(id)) {
                    return finals.get(id);
                }
            }
        }
        return Long.MIN_VALUE;
    }

    /** Full forensic line for a resolution failure: dimension, chunk, lane,
     *  tag identity, current owner, trigger range, and the recovery taken. */
    private static String fabrolia$unresolvedReport(ServerLevel level, LevelChunkTicks<?> c,
            FabroliaTickDomain tag, long chunkKey, String lane, RegionizedServer rs) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("scheduled-tick container tagged island#").append(tag.fabrolia$domainIsland())
          .append(" gen ").append(tag.fabrolia$domainGen())
          .append(" cannot be resolved — dim=").append(level.dimension().identifier());
        if (chunkKey != Long.MIN_VALUE) {
            sb.append(" chunk=[").append(net.minecraft.world.level.ChunkPos.getX(chunkKey))
              .append(',').append(net.minecraft.world.level.ChunkPos.getZ(chunkKey)).append(']');
        }
        if (lane != null) {
            sb.append(" lane=").append(lane);
        }
        IslandRegistry.PIsland owner = (rs != null && chunkKey != Long.MIN_VALUE)
                ? rs.islandOwnerOfChunk(chunkKey) : null;
        sb.append(" currentOwner=").append(owner == null ? "n/a"
                : ("island#" + owner.id + "g" + owner.gen + " L=" + owner.logicalTick));
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        int n = 0;
        try {
            for (var it = c.getAll().iterator(); it.hasNext(); ) {
                ScheduledTick<?> t = it.next();
                min = Math.min(min, t.triggerTick());
                max = Math.max(max, t.triggerTick());
                n++;
            }
        } catch (Throwable ignored) {
        }
        sb.append(" triggers=").append(n);
        if (n > 0) {
            sb.append(" range=[").append(min).append("..").append(max).append(']')
              .append(" wallNow=").append(wallNow(level));
        }
        sb.append(" recovery=ADOPT-TAG-WITHOUT-SHIFT (triggers keep absolute values; run "
                + "/fabrolia regions ticks scan|inspect|clear-stale|reschedule to audit/repair). "
                + "This path is designed unreachable — report with logs.");
        return sb.toString();
    }

    /**
     * Rebase {@code c} into the target domain. {@code targetId} = island id or
     * {@link FabroliaTickDomain#WALL}; {@code targetNow} = "now" on the target
     * clock. Caller must hold whatever exclusion the container needs (the
     * LevelTicks lock, a rebuild safe point, or quiesced workers).
     *
     * @return number of ticks shifted (0 = already in domain or empty)
     */
    public static int rebaseTo(ServerLevel level, LevelChunkTicks<?> c,
                               long targetId, long targetGen, long targetNow) {
        return rebaseTo(level, c, targetId, targetGen, targetNow, Long.MIN_VALUE, null);
    }

    public static int rebaseTo(ServerLevel level, LevelChunkTicks<?> c,
                               long targetId, long targetGen, long targetNow,
                               long chunkKey, String lane) {
        FabroliaTickDomain tag = (FabroliaTickDomain) (Object) c;
        if (tag.fabrolia$domainIsland() == targetId) {
            // same domain (gen changes within one island id keep the same clock)
            tag.fabrolia$setDomain(targetId, targetGen);
            return 0;
        }
        long tagNow = resolveNow(level, c, chunkKey, lane);
        if (tagNow == Long.MIN_VALUE) {
            // unresolvable source tag: adopt the target tag WITHOUT shifting —
            // triggers keep their absolute values (already logged as a bug).
            tag.fabrolia$setDomain(targetId, targetGen);
            return 0;
        }
        long delta = targetNow - tagNow;
        int moved = 0;
        // LIVENESS GATE, NOT count() (task #41, Lithium audit §4.7): vanilla's
        // count() is queue.size() + pendingTicks.size(); Lithium's is
        // allTicks.size() — the (pos,type) DEDUP-SET cardinality. The two
        // diverge the moment a queue holds duplicates, which unpack() can
        // create in BOTH implementations (it schedules unchecked, bypassing the
        // dedup set), so a container can legitimately report count()==0 with a
        // live tick still queued. Gating the shift on it would silently skip
        // the rebase for exactly those containers. peek() is the only
        // implementation-agnostic liveness answer, and it is null-safe in both.
        if (delta != 0 && c.peek() != null) {
            moved = shiftAllErased(c, delta);
        }
        tag.fabrolia$setDomain(targetId, targetGen);
        return moved;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int shiftAllErased(LevelChunkTicks<?> c, long delta) {
        return shiftAll((LevelChunkTicks) c, delta);
    }

    /**
     * Task #37 (2026-07-28 22:10 production NPE) — implementation-agnostic
     * {@code removeIf}. Lithium's overwritten {@code removeIf} iterates
     * ABSOLUTE indices {@code 0..size()} over its poll-compacted
     * OrderedTickQueue ring, but live data occupies
     * {@code [firstIndex, lastIndexExclusive)} and every polled slot is
     * nulled — on any PARTIALLY-POLLED bucket the loop reads those nulls and
     * NPEs ({@code nextTick.pos()}). Island tick slicing leaves
     * partially-polled buckets routinely (vanilla only reaches that state
     * when the MAX_TICKS budget interrupts a drain — their latent bug, hit
     * via clearArea//fill). Poll-drain + re-schedule survivors uses only the
     * hot-path methods BOTH implementations keep correct (poll maintains the
     * dedup set in each), under the container monitor so the swap is atomic
     * against the synchronized container API. Survivors re-fire onTickAdded —
     * a benign LevelTicks next-trigger recompute.
     */
    public static <T> int drainRemoveIf(LevelChunkTicks<T> c,
                                        java.util.function.Predicate<ScheduledTick<T>> remove) {
        synchronized (c) {
            List<ScheduledTick<T>> keep = null;
            int removed = 0;
            // peek-before-poll (task #37b, 2026-07-29 01:35 regression):
            // Lithium's poll() does NOT tolerate an empty container
            // (this.nextTickQueue is null there — their contract is
            // peek-first, and peek IS null-safe in both impls). A PACKED
            // (deserialized, not yet unpacked) Lithium container reports
            // count()>0 while its live queues are empty — the audit15
            // invariant "packed state is unreachable through this API and
            // stays untouched" is preserved exactly by the peek guard: the
            // drain no-ops on packed/empty containers like removeIf did.
            for (ScheduledTick<T> t; c.peek() != null && (t = c.poll()) != null; ) {
                if (remove.test(t)) {
                    removed++;
                } else {
                    if (keep == null) keep = new ArrayList<>();
                    keep.add(t);
                }
            }
            if (keep != null) {
                for (ScheduledTick<T> t : keep) {
                    c.schedule(t);
                }
            }
            return removed;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int drainRemoveIfErased(LevelChunkTicks<?> c,
                                          java.util.function.Predicate<ScheduledTick<?>> remove) {
        return drainRemoveIf((LevelChunkTicks) c, (java.util.function.Predicate) remove);
    }

    /** Exact-order-preserving trigger shift (the proven assignDomain algorithm,
     *  now shared): original (triggerTick, subTickOrder) pairs kept, all
     *  shifted by one delta — never executed, never duplicated, dedup set
     *  rebuilt consistently by re-scheduling into the drained container.
     *  Task #37: drained via poll (impl-agnostic), never removeIf. */
    public static <T> int shiftAll(LevelChunkTicks<T> c, long delta) {
        synchronized (c) {
            List<ScheduledTick<T>> all = new ArrayList<>();
            // peek-before-poll: see drainRemoveIf (task #37b) — Lithium's
            // poll() NPEs on empty/packed containers.
            for (ScheduledTick<T> t; c.peek() != null && (t = c.poll()) != null; ) {
                all.add(t);
            }
            all.sort(Comparator.<ScheduledTick<T>>comparingLong(ScheduledTick::triggerTick)
                    .thenComparingLong(ScheduledTick::subTickOrder));
            for (ScheduledTick<T> t : all) {
                c.schedule(new ScheduledTick<>(t.type(), t.pos(), t.triggerTick() + delta,
                        t.priority(), t.subTickOrder()));
            }
            return all.size();
        }
    }

    // ------------------------------------------------------------------
    // Task #41 — Lithium `world.tick_scheduler` containment
    //
    // Lithium's LevelChunkTicksMixin replaces the vanilla PriorityQueue with a
    // Long2ReferenceAVLTreeMap of (time, priority) buckets plus a cached
    // `nextTickQueue` head pointer and an `allTicks` dedup set. Three of its
    // public methods behave nothing like their vanilla counterparts, and all
    // three are reached from Fabrolia worker threads. Everything below is the
    // container-API-level containment; the wrap sites are in
    // {@code mixin/world/LevelChunkTicksMixin}, which delegates here so the
    // logic can be exercised without a mixin environment
    // (TickContainerWriteShapedApiTest).
    //
    // THE ONE MONITOR. Every public method of LevelChunkTicks carries
    // ACC_SYNCHRONIZED on the target-instance monitor, because the class is
    // listed in SyncAllMixin. That monitor is what makes Lithium's cached head
    // pointer, its bucket map and its dedup set mutually consistent under
    // Fabrolia's concurrent schedule()/poll(). It is load-bearing — see the
    // comment on the SyncAllMixin entry before removing it. The helpers here
    // take the SAME monitor explicitly rather than relying on it, because
    // methods merged by a priority-2000 mixin are NOT seen by
    // SynchronisePlugin.postApply and therefore never receive the blanket bit
    // (SynchronisePlugin's own javadoc says so, naming this very class).
    // ------------------------------------------------------------------

    /** Lithium bucket-invariant violations contained by {@link #pollGuarded}.
     *  Designed unreachable while the blanket monitor stands — any nonzero
     *  value is a bug report, with a chunk's worth of scheduled ticks lost. */
    public static final AtomicLong BUCKET_INVARIANT_VIOLATIONS = new AtomicLong();

    /** Containers un-wedged by {@link #compact} on the recovery path. */
    public static final AtomicLong CONTAINER_COMPACTIONS = new AtomicLong();

    /** Lithium's own message from {@code updateNextTickQueue}. Matching on it
     *  is deliberate and deliberately narrow: any OTHER IllegalStateException
     *  out of poll() is not ours to swallow and is rethrown unchanged. */
    private static final String LITHIUM_BUCKET_INVARIANT_MESSAGE =
            "Next tick queue doesn't have the lowest key!";

    /**
     * IDENTITY-COMPARED sentinel predicate. {@code LevelChunkTicksMixin}'s
     * {@code removeIf} wrap normally replaces the whole body with
     * {@link #drainRemoveIf} (task #37 — Lithium's overwrite NPEs on
     * partially-polled buckets). This one predicate is the exception: the wrap
     * forwards it to the UNDERLYING implementation instead, because Lithium's
     * {@code removeIf} is the only public operation that evicts stale-EMPTY
     * buckets from its tree map and then re-points {@code nextTickQueue}, and
     * that is precisely the repair a wedged container needs. Removing nothing
     * (the predicate is constant false, and null-tolerant, so Lithium's
     * absolute-index loop cannot NPE on a polled slot) makes the forward safe.
     * On vanilla it is an inert scan of the PriorityQueue.
     */
    public static final java.util.function.Predicate<ScheduledTick<?>> COMPACT_ONLY = t -> false;

    /**
     * Implementation-level compaction: evict empty buckets and re-derive the
     * head pointer. See {@link #COMPACT_ONLY}. No-op semantics — not one
     * scheduled tick is added, removed or reordered on either implementation.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void compact(LevelChunkTicks<?> c) {
        synchronized (c) {
            ((LevelChunkTicks) c).removeIf((java.util.function.Predicate) COMPACT_ONLY);
        }
        CONTAINER_COMPACTIONS.incrementAndGet();
    }

    /**
     * HAZARD A (Lithium audit §4.6) — {@code getAll()} is WRITE-SHAPED, and
     * worse, it is write-shaped LAZILY.
     *
     * <p>Vanilla's {@code getAll()} is {@code tickQueue.stream()}: a pure read.
     * Lithium's is {@code tickQueuesByTimeAndPriority.values().stream()
     * .flatMap(Collection::stream)}, and {@code OrderedTickQueue.iterator()}
     * begins with {@code sort()} → {@code removeNullsAndConsumed()} →
     * {@code handleCompaction()}, which REALLOCATES the backing array, moves
     * every live element to index 0 and resets {@code firstIndex = 0}. A drain
     * in progress on that container has already advanced {@code firstIndex};
     * resetting it under the drainer makes its next {@code poll()} re-read
     * slots it already consumed (duplicate ticks) or skip live ones (lost
     * ticks).
     *
     * <p>The laziness is why the blanket monitor does NOT cover this today.
     * {@code Collection.spliterator()} defers {@code iterator()} until first
     * traversal and {@code flatMap} builds the inner streams during traversal,
     * so the compaction happens at the CALLER's terminal operation — long
     * after the synchronized {@code getAll()} frame returned. Same shape as the
     * lazy {@code PoiSection.getRecords} stream that made the blanket bit
     * useless there (see the SyncAllMixin comment).
     *
     * <p>Fix: drain the stream into a snapshot INSIDE the monitor and hand the
     * caller a stream over the snapshot. Callers see the same elements in the
     * same order; what they no longer see is a live view whose traversal
     * mutates the scheduler. {@code LevelTicks.copyAreaFrom} — the vanilla
     * caller — already consumes it immediately, so nothing regresses.
     */
    public static <T> java.util.stream.Stream<ScheduledTick<T>> snapshotAll(
            LevelChunkTicks<T> c,
            java.util.function.Supplier<java.util.stream.Stream<ScheduledTick<T>>> raw) {
        java.util.ArrayList<ScheduledTick<T>> snapshot = new java.util.ArrayList<>();
        synchronized (c) {
            raw.get().forEach(snapshot::add);
        }
        return snapshot.stream();
    }

    /**
     * HAZARD A, second half — {@code pack(long)} is write-shaped too.
     *
     * <p>Vanilla's {@code pack} copies the queue into a fresh {@code ArrayList}
     * and sorts the copy. Lithium's iterates each live bucket directly
     * ({@code for (ScheduledTick<T> t : nextTickQueue)}), so it takes the same
     * {@code iterator() → sort() → handleCompaction()} path as
     * {@link #snapshotAll}. Unlike {@code getAll} it is EAGER — the whole
     * iteration completes before {@code pack} returns — so a monitor really is
     * sufficient here. It is taken explicitly because {@code pack} is called
     * from chunk serialisation, which under c2me runs OFF the server thread,
     * against a container a region worker may be draining.
     *
     * <p>Kept as a named helper rather than a bare {@code synchronized} in the
     * mixin so that "pack is a write" is stated once, in a place a reader
     * classifying the container API will actually find.
     */
    public static <T> java.util.List<net.minecraft.world.ticks.SavedTick<T>> packUnderMonitor(
            LevelChunkTicks<T> c,
            java.util.function.Supplier<java.util.List<net.minecraft.world.ticks.SavedTick<T>>> raw) {
        synchronized (c) {
            return raw.get();
        }
    }

    /**
     * HAZARD B (Lithium audit §4.5) — {@code updateNextTickQueue(true)} removes
     * the map entry BEFORE it validates that it removed the right one:
     *
     * <pre>{@code
     * OrderedTickQueue<T> removed = this.tickQueuesByTimeAndPriority.remove(
     *         this.tickQueuesByTimeAndPriority.firstLongKey());
     * if (removed != this.nextTickQueue) {
     *     throw new IllegalStateException("Next tick queue doesn't have the lowest key!");
     * }
     * }</pre>
     *
     * By the time the guard fires the bucket is gone, and it is the bucket with
     * the LOWEST key — the one a racing {@code schedule()} just created, which
     * may hold live ticks. Their {@code allTicks} keys survive, so
     * {@code hasScheduledTick} stays true at those positions forever and the
     * blocks that gate on it (repeater, comparator, observer, fluid) never
     * reschedule. The exception then unwinds out of {@code LevelTicks.tick}.
     *
     * <p>PREVENTION is the real fix and it is structural, not a catch: the
     * violation needs {@code nextTickQueue} to stop being the minimum-key
     * bucket, which single-threaded Lithium never produces — every path that
     * creates a lower bucket ({@code queueTick}) re-points the head in the same
     * breath. It becomes reachable only when a {@code schedule()} from another
     * worker interleaves a drain's {@code poll()}, and the container monitor
     * (see the block comment above) is what forbids that interleaving. The
     * upstream fix — compare {@code firstLongKey()} against
     * {@code nextTickQueue} before removing — is not reproducible from here:
     * it needs {@code @Shadow} access to two Lithium-added private fields, and
     * a {@code @Shadow} cannot be made conditional, so the mixin would fail to
     * apply on a vanilla-without-Lithium install.
     *
     * <p>What this method adds is CONTAINMENT for the case where prevention is
     * defeated anyway (a future mod calling {@code schedule} off-monitor, or a
     * Lithium change). Three decisions, all deliberate:
     *
     * <ul>
     *   <li><b>Recover, do not propagate.</b> Letting the ISE out of
     *       {@code poll()} aborts the entire level's tick phase — under
     *       Fabrolia, a region worker's whole slice — turning one chunk's
     *       damage into every chunk's. The damage is already done when the
     *       exception is constructed; re-throwing adds harm and repairs
     *       nothing.</li>
     *   <li><b>Fail loudly anyway.</b> {@link #BUCKET_INVARIANT_VIOLATIONS}
     *       and an ERROR line with the surviving-tick count. This path is
     *       designed unreachable; a nonzero counter is a bug report.</li>
     *   <li><b>Un-wedge before returning.</b> Lithium leaves the emptied head
     *       bucket wired as both {@code nextTickQueue} and the map's first key.
     *       {@code queueTick}'s {@code updateNextTickQueue(false)} re-selects it
     *       by {@code firstLongKey()} from then on, so {@code peek()} and
     *       {@code poll()} answer null forever while {@code schedule()} keeps
     *       filling {@code allTicks} — the chunk's scheduled ticks stop,
     *       silently, until it reloads (audit §4.4). {@link #compact} evicts
     *       the stale bucket and re-derives the head, then the poll is retried
     *       once so the caller gets a real tick rather than a null vanilla's
     *       {@code LevelTicks.collectTicks} would go on to dereference.</li>
     * </ul>
     *
     * <p>NOT recoverable, and not pretended to be: the ticks in the destroyed
     * bucket are gone, and their {@code allTicks} keys are unreachable through
     * any public API (there is no enumeration of that set). Those positions
     * stop rescheduling until the chunk reloads. The ERROR line says so and
     * points at {@code /fabrolia regions ticks scan}.
     *
     * <p>CALLING SHAPE — this is the CATCH BLOCK, not the whole guard, and
     * that is deliberate. {@code poll()} runs thousands of times per tick, so
     * the wrap in {@code LevelChunkTicksMixin} keeps its {@code try} body bare
     * ({@code return original.call();}) and constructs the retry supplier only
     * inside {@code catch}. A {@code try} that never throws is free; a
     * capturing lambda evaluated on the happy path would not be, and neither
     * would a second monitor acquisition — the happy path takes the container
     * monitor exactly once, from the blanket bit, as it always has. The monitor
     * IS taken here, because the repair is several operations long.
     */
    public static <T> ScheduledTick<T> recoverFromBucketInvariantViolation(
            LevelChunkTicks<T> c, IllegalStateException e,
            java.util.function.Supplier<ScheduledTick<T>> raw) {
        if (!LITHIUM_BUCKET_INVARIANT_MESSAGE.equals(e.getMessage())) {
            throw e;
        }
        synchronized (c) {
            BUCKET_INVARIANT_VIOLATIONS.incrementAndGet();
            compact(c);
            ScheduledTick<?> head = c.peek();
            LOGGER.error("[tick-domains] Lithium bucket invariant violated in LevelChunkTicks.poll() — "
                    + "the lowest-key bucket was REMOVED before the check failed and its scheduled ticks "
                    + "are unrecoverable (their dedup keys survive, so those positions stop rescheduling "
                    + "until the chunk reloads). Container un-wedged and the tick phase continued; "
                    + "head after repair={}. This requires an off-monitor schedule() racing a drain and "
                    + "is designed unreachable — report with logs, then run /fabrolia regions ticks scan.",
                    head, e);
            if (head == null) {
                return null;
            }
            try {
                return raw.get();
            } catch (IllegalStateException again) {
                LOGGER.error("[tick-domains] repair did not restore LevelChunkTicks.poll(); "
                        + "container abandoned for this tick", again);
                return null;
            }
        }
    }

    /** Container registration hook (chunk load / promotion): if the chunk's
     *  territory is island-owned, move the container onto the island clock
     *  immediately — a chunk loading into a diverged island (post-sprint
     *  L ≫ wall, or lagging L &lt; wall) previously kept wall triggers that
     *  read as massively overdue (burst) or frozen (delay).
     *
     *  <p>NOTE (2026-07-27 lifecycle audit): for a freshly DESERIALIZED
     *  container this rebase only RETAGS — the loaded delays still sit
     *  relative in {@code pendingTicks} (not in the tick queue that
     *  {@link #shiftAll} streams), and relative delays must not be shifted.
     *  The anchoring itself happens later, at {@code startTickingChunk} →
     *  {@code LevelChunkTicks.unpack}, which LevelChunkMixin now feeds the
     *  TAGGED domain's clock via {@link #unpackBasis} — the tag set here is
     *  what makes that anchor correct. Only a container whose queue is
     *  already live (FULL-status re-registration without serialization)
     *  actually shifts triggers here. */
    public static void onContainerInstalled(ServerLevel level, long chunkKey, LevelChunkTicks<?> c) {
        RegionizedServer rs = RegionScheduler.forLevel(level);
        if (rs == null) return;
        if (!rs.asyncActive) {
            // SYNC INVARIANT (2026-07-22 6gt QC trencher, task #13): non-async
            // consumption is WALL-clocked everywhere — vanilla LevelTicks
            // compares triggers against gameTime and nothing heals a foreign
            // tag on that path. A container loaded from an async-era save
            // used to KEEP its island tag here (early return), and every
            // fresh schedule at that chunk then went through
            // rebaseScheduledTick's stale delta (frozen island L minus an
            // advancing wall) — delays collapsed toward fire-immediately in a
            // zone starting exactly at the first region the async-off toggle
            // never rebased (a static seam). Collapse on sight instead,
            // delay-preserving via the tag's own resolvable clock.
            fabrolia$collapseStaleTag(level, chunkKey, c, "install-sync");
            return;
        }
        IslandRegistry.PIsland owner = rs.islandOwnerOfChunk(chunkKey);
        if (owner == null || owner.retired) {
            // Async active but this chunk is GAP territory (no live island):
            // its consumer is main's wall lane — same invariant as sync.
            fabrolia$collapseStaleTag(level, chunkKey, c, "install-gap");
            return;
        }
        int moved = rebaseTo(level, c, owner.id, owner.gen, owner.logicalTick);
        if (moved > 0) {
            LOAD_REBASES.incrementAndGet();
        }
    }

    /** Collapse a non-WALL-tagged container to the wall clock the moment a
     *  wall-lane consumer would see it. Delay-preserving when the tag's clock
     *  resolves (live island / islandLastL / FINAL_CLOCKS); unresolvable tags
     *  take the existing adopt-with-forensics path inside rebaseTo. No-op for
     *  WALL-tagged containers — the hot path pays one field read. */
    public static void fabrolia$collapseStaleTag(ServerLevel level, long chunkKey,
                                                 LevelChunkTicks<?> c, String site) {
        if (((FabroliaTickDomain) (Object) c).fabrolia$domainIsland() == FabroliaTickDomain.WALL) {
            return;
        }
        int moved = rebaseTo(level, c, FabroliaTickDomain.WALL, -1,
                com.axalotl.fabrolia.common.regions.IslandClock.wallTime(level), chunkKey, site);
        STALE_TAG_COLLAPSES.incrementAndGet();
        if (moved > 0) {
            LOGGER.info("[tick-domains] {} chunk=[{},{}]: stale island tag collapsed to wall at {} ({} pending ticks)",
                    level.dimension().identifier(),
                    net.minecraft.world.level.ChunkPos.getX(chunkKey),
                    net.minecraft.world.level.ChunkPos.getZ(chunkKey), site, moved);
        }
    }

    public static final AtomicLong STALE_TAG_COLLAPSES = new AtomicLong();

    /** Consumption healed at scan time (tag != consuming lane). */
    public static final AtomicLong CONSUMER_HEALS = new AtomicLong();

    /**
     * CONSUMPTION-POINT ENFORCEMENT (2026-07-21 flying-machine regression):
     * the lane consuming a container is authoritative for its clock domain.
     * Install-time tagging is best-effort only — a chunk can register before
     * its region/island exists (boot, terrain freshly generated ahead of a
     * moving machine), leaving a WALL tag inside island territory. Selecting
     * such a container's triggers against the island's L compressed pulses
     * (L &gt; wall) or froze them (L &lt; wall) — TNT flying-machine dupers
     * stalled wherever a route crossed one. Called from the due-selection
     * scans; rebases under the scheduler's structural lock and returns the
     * container's refreshed head trigger (or Long.MAX_VALUE if empty).
     */
    public static long enforceConsumerDomain(ServerLevel level, LevelTicksIslandAccess<?> access,
                                             long chunkKey, long laneIslandId, long laneGen,
                                             long laneNow) {
        Object o = access.fabrolia$allContainers().get(chunkKey);
        if (!(o instanceof LevelChunkTicks<?> c)) return Long.MAX_VALUE;
        FabroliaTickDomain tag = (FabroliaTickDomain) (Object) c;
        if (tag.fabrolia$domainIsland() == laneIslandId) {
            ScheduledTick<?> head = c.peek();
            return head == null ? Long.MAX_VALUE : head.triggerTick();
        }
        // PAIR CONSISTENCY (2026-07-21 fM finding): lanes are scanned
        // independently, so healing only the scanned lane left block/fluid
        // containers of one chunk in DIFFERENT domains — and the save basis
        // is necessarily shared (vanilla packs both lists against one clock).
        // Whenever one lane is enforced, bring its sibling with it.
        LevelTicksIslandAccess<?> sibling = (LevelTicksIslandAccess<?>)
                (access == level.getBlockTicks() ? level.getFluidTicks() : level.getBlockTicks());
        // GLOBAL LOCK ORDER (2026-07-27 review): both lane locks are held for
        // the pair heal, ALWAYS acquired block-lane-first regardless of which
        // lane triggered — a fixed total order can never AB-BA against a
        // concurrent heal of the opposite lane, and holding both makes the
        // block+fluid rebase one atomic step (no scheduler interleave between
        // the scanned lane's heal and its sibling's).
        boolean accessIsBlock = access == level.getBlockTicks();
        LevelTicksIslandAccess<?> blockLane = accessIsBlock ? access : sibling;
        LevelTicksIslandAccess<?> fluidLane = accessIsBlock ? sibling : access;
        int moved;
        synchronized (blockLane.fabrolia$tickLock()) {
            synchronized (fluidLane.fabrolia$tickLock()) {
                moved = rebaseTo(level, c, laneIslandId, laneGen, laneNow, chunkKey, "consume");
                Object so = sibling.fabrolia$allContainers().get(chunkKey);
                if (so instanceof LevelChunkTicks<?> sc) {
                    rebaseTo(level, sc, laneIslandId, laneGen, laneNow);
                    ScheduledTick<?> sh = sc.peek();
                    if (sh != null) {
                        sibling.fabrolia$nextTickForContainer().put(chunkKey, sh.triggerTick());
                    }
                }
            }
        }
        ScheduledTick<?> head = c.peek();
        long newNext = head == null ? Long.MAX_VALUE : head.triggerTick();
        if (newNext != Long.MAX_VALUE) {
            access.fabrolia$nextTickForContainer().put(chunkKey, newNext);
        }
        if (moved > 0) {
            CONSUMER_HEALS.incrementAndGet();
            FlightRecorder.record("TICKDOMAIN-HEAL " + level.dimension().identifier()
                    + " chunk=[" + net.minecraft.world.level.ChunkPos.getX(chunkKey)
                    + "," + net.minecraft.world.level.ChunkPos.getZ(chunkKey) + "] -> "
                    + (laneIslandId == FabroliaTickDomain.WALL ? "WALL" : "island#" + laneIslandId)
                    + " now=" + laneNow + " ticks=" + moved);
        }
        return newNext;
    }

    /** Shutdown collapse: every island-tagged container back to WALL while
     *  tags are still resolvable. Runs after workers stop, before the final
     *  world save. Idempotent.
     *
     *  <p>SHUTDOWN-ORDER FIX (2026-07-21): the no-arg form iterated
     *  {@code RegionScheduler.allLevels()}, which is {@code active}-gated —
     *  and stop() flips {@code active=false} BEFORE collapsing, so the whole
     *  collapse was a silent no-op and every island-tagged container reached
     *  the final save unresolvable (the "cannot be resolved" flood + saved
     *  triggers shifted by the island offset). stop() now passes its own
     *  pre-captured level list; the no-arg form remains for live-session
     *  callers only. */
    public static void collapseAllToWall() {
        collapseAllToWall(RegionScheduler.allLevels());
    }

    public static void collapseAllToWall(java.util.Collection<RegionizedServer> levels) {
        for (RegionizedServer rs : levels) {
            ServerLevel level = rs.level;
            long wall = wallNow(level);
            int bad = collapseLane(level, (LevelTicksIslandAccess<?>) level.getBlockTicks(), wall, "block");
            bad += collapseLane(level, (LevelTicksIslandAccess<?>) level.getFluidTicks(), wall, "fluid");
            if (bad > 0) {
                LOGGER.error("[tick-domains] {}: {} container(s) failed clean collapse — triggers may be shifted; "
                        + "run /fabrolia regions ticks scan after restart", level.dimension().identifier(), bad);
            }
        }
    }

    /** Per-lane collapse. INFO = one compact summary line; per-container detail
     *  only under the diagnostics flag (/fabrolia regions async debug on).
     *  Failures are never quiet: any container whose tag cannot be resolved
     *  still ERRORs with full forensics via resolveNow, and one left
     *  island-tagged after the pass is reported as an ERROR here. Wording is
     *  neutral — this path also serves /fabrolia regions off, not only server
     *  shutdown. @return unresolved-tag count for the caller's summary. */
    private static int collapseLane(ServerLevel level, LevelTicksIslandAccess<?> access,
                                    long wall, String lane) {
        long t0 = System.nanoTime();
        long unresolvedBefore = UNRESOLVED_TAGS.get();
        int containers = 0, ticks = 0, leftTagged = 0;
        for (var e : access.fabrolia$allContainers().long2ObjectEntrySet()) {
            Object o = e.getValue();
            if (!(o instanceof LevelChunkTicks<?> c)) continue;
            FabroliaTickDomain tag = (FabroliaTickDomain) (Object) c;
            if (tag.fabrolia$domainIsland() == FabroliaTickDomain.WALL) continue;
            // REPORTING ONLY — never a gate. count() is queue+pending on
            // vanilla but the dedup-set cardinality on Lithium (audit §4.7);
            // the two disagree whenever a queue holds duplicates. Nothing
            // below branches on it: every container is rebased unconditionally.
            int n = c.count();
            if (n > 0 && FlightRecorder.DEBUG) {
                LOGGER.info("[tick-domains] collapse detail: {} chunk=[{},{}] {} island#{} -> wall ticks={}",
                        level.dimension().identifier(),
                        net.minecraft.world.level.ChunkPos.getX(e.getLongKey()),
                        net.minecraft.world.level.ChunkPos.getZ(e.getLongKey()),
                        lane, tag.fabrolia$domainIsland(), n);
            }
            rebaseTo(level, c, FabroliaTickDomain.WALL, -1, wall, e.getLongKey(), lane);
            if (tag.fabrolia$domainIsland() != FabroliaTickDomain.WALL) {
                leftTagged++;
                LOGGER.error("[tick-domains] container {} chunk=[{},{}] {} STILL island-tagged after collapse — report this",
                        level.dimension().identifier(),
                        net.minecraft.world.level.ChunkPos.getX(e.getLongKey()),
                        net.minecraft.world.level.ChunkPos.getZ(e.getLongKey()), lane);
            }
            SHUTDOWN_COLLAPSES.incrementAndGet();
            containers++;
            ticks += n;
        }
        long unresolved = UNRESOLVED_TAGS.get() - unresolvedBefore;
        if (containers > 0) {
            LOGGER.info("[tick-domains] {} {}: collapsed {} containers ({} pending ticks) to wall-clock in {}ms{}",
                    level.dimension().identifier(), lane, containers, ticks,
                    (System.nanoTime() - t0) / 1_000_000,
                    (unresolved == 0 && leftTagged == 0) ? ", all tags resolved"
                            : (" — " + unresolved + " UNRESOLVED, " + leftTagged + " left tagged (see errors)"));
        }
        return (int) unresolved + leftTagged;
    }

    /** Save-time basis for {@code chunk}'s tick pack: the BLOCK container's
     *  tagged domain "now" (block+fluid are always retagged in pairs; a
     *  mismatch is logged and block wins). WALL tag → wall — bit-for-bit
     *  vanilla. NEVER guessed from current ownership. */
    public static long saveBasis(ServerLevel level, net.minecraft.world.level.chunk.ChunkAccess chunk, long wall) {
        var bt = chunk.getBlockTicks();
        if (!(bt instanceof LevelChunkTicks<?> c)) {
            return wall; // proto/worldgen chunks: vanilla wall semantics
        }
        long saveChunkKey = chunk.getPos().pack();
        var ft = chunk.getFluidTicks();
        if (ft instanceof LevelChunkTicks<?> fc) {
            long b = ((FabroliaTickDomain) (Object) c).fabrolia$domainIsland();
            long f = ((FabroliaTickDomain) (Object) fc).fabrolia$domainIsland();
            if (b != f) {
                // Save basis is shared: SELF-HEAL the fluid lane into the
                // block lane's domain (delay-preserving) so both lists pack
                // against one true clock — never mistranslate one lane.
                long bNow = resolveNow(level, c, saveChunkKey, "block");
                if (bNow != Long.MIN_VALUE) {
                    LevelTicksIslandAccess<?> fa = (LevelTicksIslandAccess<?>) level.getFluidTicks();
                    synchronized (fa.fabrolia$tickLock()) {
                        rebaseTo(level, fc, b, ((FabroliaTickDomain) (Object) c).fabrolia$domainGen(), bNow);
                    }
                    LOGGER.warn("block/fluid tick containers of chunk {} were tagged differently "
                            + "(block island#{} vs fluid island#{}) — fluid lane rebased to block's domain at save",
                            chunk.getPos(), b, f);
                }
            }
        }
        long now = resolveNow(level, c, saveChunkKey, "block");
        long basis = now == Long.MIN_VALUE ? wall : now;
        // OBSERVABILITY ONLY (2026-07-27 review): vanilla persists delays as
        // int (SavedTick.delay, Codec.INT; ScheduledTick.toSavedTick casts
        // trigger − basis) — a remaining delay outside the int range wraps
        // SILENTLY on save. We change nothing about the value or the format
        // (bit-identical vanilla serialization); we only name the entry so
        // the operator can act. Throttled: one line per lane per cooldown.
        fabrolia$warnDelayOverflow(level, c, basis, "block");
        if (ft instanceof LevelChunkTicks<?> fscan) {
            fabrolia$warnDelayOverflow(level, fscan, basis, "fluid");
        }
        return basis;
    }

    /** Warn (throttled) for every pending tick whose remaining delay against
     *  {@code basis} does not fit vanilla's int saved-delay field. Read-only:
     *  never alters, skips, or clamps an entry. */
    private static void fabrolia$warnDelayOverflow(ServerLevel level, LevelChunkTicks<?> c,
                                                   long basis, String lane) {
        c.getAll().forEach(t -> {
            long remaining = t.triggerTick() - basis;
            if ((int) remaining != remaining) {
                Object type = t.type();
                String typeName;
                if (type instanceof net.minecraft.world.level.block.Block b) {
                    typeName = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).toString();
                } else if (type instanceof net.minecraft.world.level.material.Fluid f) {
                    typeName = net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(f).toString();
                } else {
                    typeName = String.valueOf(type);
                }
                ThrottledLog.warn(LOGGER, "tickdomains.save-delay-overflow." + lane,
                        level.dimension().identifier() + "|" + lane,
                        "[tick-domains] {} {} scheduled tick {} at {}: remaining delay {} does not fit the int "
                                + "delay field of vanilla's saved-tick format and will wrap on save "
                                + "(vanilla behavior — value untouched); audit with /fabrolia regions ticks inspect",
                        level.dimension().identifier(), lane, typeName,
                        t.pos().toShortString(), remaining);
            }
        });
    }

    // ------------------------------------------------------------------
    // Load-side anchoring (2026-07-27 scheduled-tick lifecycle audit)
    // ------------------------------------------------------------------

    /**
     * UNPACK-ANCHOR FIX (2026-07-27 lifecycle audit — the hours-in-the-future
     * hole): a freshly deserialized container holds RELATIVE {@code SavedTick}
     * delays in {@code pendingTicks}; nothing is anchored until
     * {@code LevelChunkTicks.unpack(now)} runs at {@code startTickingChunk}.
     * Registration ({@link #onContainerInstalled}) fires EARLIER, at the
     * chunk's FULL transition — its rebase sees an empty {@code tickQueue}
     * ({@link #shiftAll} streams the queue, pending delays are relative and
     * must not be shifted anyway) and correctly only RETAGS the container to
     * its owner island. But vanilla then anchored the pending delays against
     * {@code getGameTime()} on the promoting thread — main's WALL clock —
     * inside a container TAGGED to the island's L clock. Every trigger came
     * out offset by (W − L) from what the tag declares, and because tag ==
     * consuming lane, no consumer-domain heal can ever see the mismatch: on a
     * lagging/post-sprint-wall island (W ≫ L) the tick parks (W − L) island
     * epochs in the future — hours after a long {@code /tick sprint} — and its
     * pos+type dedup entry rejects every replacement schedule meanwhile; on an
     * island ahead of wall it burst-fires early. The save side then wrote
     * {@code delay = (W + d) − L} through {@link #saveBasis}, PERSISTING the
     * corruption. Worlds that already carry such entries from an older build
     * are repaired EXPLICITLY only — {@code /fabrolia regions ticks
     * scan|inspect|clear-stale|reschedule} ({@link TickInspector}); the
     * original offset (W − L at mistranslation time) was never persisted, so
     * exact reconstruction is impossible and no automatic rewrite runs —
     * arbitrarily long delays are legitimate for modded schedulers.
     *
     * <p>The anchor for pending delays is therefore the TAGGED domain's "now",
     * never the calling thread's clock: WALL tag → the raw wall counter (also
     * correct when a worker context pumped the promotion task and
     * {@code getGameTime()} answered some island's L); island tag → the tag's
     * resolved clock. Unresolvable tags (designed unreachable, already
     * ERROR-logged by {@link #resolveNow}) fall back to the caller's value —
     * identical to vanilla.
     */
    public static long unpackBasis(ServerLevel level, LevelChunkTicks<?> c, long callerNow) {
        if (((FabroliaTickDomain) (Object) c).fabrolia$domainIsland() == FabroliaTickDomain.WALL) {
            return IslandClock.wallTime(level);
        }
        long now = resolveNow(level, c);
        if (now == Long.MIN_VALUE) {
            return callerNow;
        }
        return now;
    }
}
