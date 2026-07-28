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
        if (delta != 0 && c.count() > 0) {
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
