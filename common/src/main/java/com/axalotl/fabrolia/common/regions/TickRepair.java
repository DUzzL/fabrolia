package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.ScheduledTick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ObjLongConsumer;
import java.util.stream.Collectors;

/**
 * Bulk scheduled-tick repair (task #33, 2026-07-27) —
 * {@code /fabrolia regions ticks repair-all}.
 *
 * <p>Repairs the damage class left behind by the (now fixed, 0b276ca)
 * unpack-anchor bug and by missed domain transitions, using STRUCTURAL
 * evidence only. The classification predicate, exactly:
 *
 * <ul>
 *   <li><b>DOMAIN-SYNC (routine, NOT corruption)</b> — container-level. The
 *       container's domain tag names clock T, the chunk's CURRENT consumer
 *       domain is a different clock R, and BOTH resolve right now. This is a
 *       routine pending domain transition — the SAME condition and the SAME
 *       operation as {@link TickDomains#enforceConsumerDomain}'s automatic
 *       consumption-point heal, which fires lazily from the due-selection
 *       scans; on a healthy async server every chunk whose island ownership
 *       changed since its container was last consumed matches (after every
 *       merge, split and rebuild). Applying it here merely pre-empts the lazy
 *       heal: shift every queued trigger by {@code nowR − nowT} and retag
 *       ({@link TickDomains#rebaseTo}), preserving relative delay, type,
 *       position, priority and sub-tick order bit-for-bit — exact because the
 *       delta is the difference of two currently-resolvable clocks. It is
 *       counted in its own counter and never in any corruption total.</li>
 *   <li><b>REMOVE-STALE (proven stale — the corruption-repair signal)</b> —
 *       entry-level. The scheduled
 *       block/fluid type no longer matches the world at that position.
 *       Decidable without any clock; removal reproduces exactly what vanilla's
 *       tick executor would do with the entry (skip it on the type
 *       recheck), while also freeing the pos+type dedup slot.</li>
 *   <li><b>AMBIGUOUS</b> — entry-level, untouchable by the normal pass. The
 *       container's tag names an island this server cannot resolve (not live,
 *       not in islandLastL, not in the retained final-clock snapshot): the
 *       clock its triggers are expressed in is unknown, so the intended delay
 *       is not reconstructible. Nothing is invented; only
 *       {@code repair-all clear-ambiguous confirm} may remove these.</li>
 *   <li><b>Everything else is healthy.</b> Delay magnitude is NEVER evidence:
 *       a 99_999_999-tick delay in a coherent container is legitimate and
 *       survives every pass byte-identical.</li>
 * </ul>
 *
 * <p>What is deliberately NOT auto-classified: historical corruption that was
 * saved to disk and reloaded. The on-disk format carries no domain tag, so
 * such entries come back as WALL-tagged relative delays inflated by an offset
 * O = (W − L at mistranslation time) that was never persisted — structurally
 * indistinguishable from a legitimate long delay. Those remain healthy here
 * (unless the type no longer matches) and stay in the operator's hands via
 * the per-position {@code ticks inspect / clear-stale} tools.
 *
 * <p>Packed containers (chunks loaded to FULL but not yet ticking) hold
 * RELATIVE {@link SavedTick} delays: relative delays have no anchoring clock
 * yet, so they can never be offset-corrupt or ambiguous. As of audit15
 * (2026-07-27 production NPE) packed containers are classified and reported
 * as <b>DEFERRED-PACKED</b> and NEVER mutated: vanilla's {@code removeIf}
 * iterates only the live {@code tickQueue} and cannot remove from
 * {@code pendingTicks}, so there is NO public API that mutates a packed
 * container — and reaching into the internals is unfixable across mods
 * (vanilla keeps the {@code ticksPerPosition} dedup set, Lithium's
 * tick_scheduler mixin nulls it and keeps its own {@code allTicks} hash set;
 * any internal write is an NPE on one implementation or silent dedup
 * corruption on the other). Deferred entries become repairable once the
 * chunk unpacks naturally at start-of-ticking; the repair never forces (or
 * risks doubling) an unpack.
 *
 * <p>ALL container mutation goes through the public container API only —
 * {@code getAll()} / {@code removeIf()} / {@code schedule()} /
 * {@code hasScheduledTick()} / {@code peek()} / {@code poll()} /
 * {@code count()} — which Lithium overwrites consistently, so every repair
 * behaves identically on vanilla and Lithium servers.
 *
 * <p>Safety contract: every bare command form is a dry run; only a trailing
 * {@code confirm} mutates. Before ANY mutation the full classification is
 * written to a report file (mutation aborts if the report cannot be written),
 * and the affected region files are copied into a sibling backup directory.
 * Each chunk is applied as its own transaction (pre-mutation snapshot,
 * rollback + exactness verification on any throw, run continues) and its
 * applied record is appended to the report immediately, per chunk.
 * Everything runs on main under the two lane locks (block first, fluid second
 * — the same fixed order as {@code TickDomains.enforceConsumerDomain}).
 */
public final class TickRepair {

    private TickRepair() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-TickRepair");

    /** One bulk run at a time (loaded pass + async unloaded scan). */
    static final AtomicBoolean RUNNING = new AtomicBoolean();

    /** The honest scope statement — leads every command output (dry run and
     *  confirm) and every report file. */
    public static final String SCOPE =
            "SCOPE: historical corruption that was saved and reloaded is NOT automatically repairable — "
            + "the domain tag is not persisted and the offset O = (W - L) at mistranslation time was "
            + "never recorded, so such entries are structurally indistinguishable from legitimate long "
            + "delays (audit those per position with 'ticks inspect' / 'ticks clear-stale'). This pass "
            + "repairs type mismatches (proven corruption), applies routine pending domain transitions "
            + "(DOMAIN-SYNC — identical to the automatic consumption-point heal, NOT corruption), and "
            + "classifies unresolvable-tag containers as AMBIGUOUS.";

    /** Residual semantic difference of REMOVE-STALE vs vanilla, disclosed in
     *  every command output and report (2026-07-27 review finding #1b). World
     *  reads are race-free — the loaded pass runs inside the all-islands
     *  command fence (CommandsMixin quiesce) and the unloaded-chunk batcher
     *  re-takes the same fence per batch — but WHEN the check runs still
     *  differs from vanilla. */
    public static final String SEMANTICS =
            "NOTE: vanilla re-checks the scheduled type at the moment a tick comes DUE; this pass "
            + "checks it NOW (under the all-islands quiesce fence, so the read is not racing worker "
            + "mutation). A position whose block is REPLACED and later RESTORED before its trigger "
            + "would be ticked by vanilla but its entry is removed here. Only actual block/fluid-type "
            + "replacement is affected — BlockState property changes (powered, lit, fluid level) do "
            + "not change the identity this check compares.";

    /** Operator abort for the background scan / batcher ('repair-all cancel').
     *  Reset when a new run acquires {@link #RUNNING}. */
    static final AtomicBoolean CANCEL = new AtomicBoolean();

    public enum Mode {
        DRY(false, false),
        CONFIRM(true, false),
        CLEAR_DRY(false, true),
        CLEAR_CONFIRM(true, true);

        public final boolean mutating;
        public final boolean clearAmbiguous;

        Mode(boolean mutating, boolean clearAmbiguous) {
            this.mutating = mutating;
            this.clearAmbiguous = clearAmbiguous;
        }
    }

    // ------------------------------------------------------------------
    // Results
    // ------------------------------------------------------------------

    public static final class Totals {
        public int dimensions;
        public long containersScanned;
        public long entriesScanned;
        public long healthyEntries;
        /** Routine pending domain transitions applied eagerly (identical to
         *  the automatic consumption-point heal). NOT corruption — never
         *  folded into any corruption total. */
        public long domainSyncEntries;
        public long domainSyncContainers;
        /** Definitely-stale type-mismatch removals — the corruption-repair
         *  signal. */
        public long removedMismatch;
        /** Positions given a fresh schedule through the smart reschedule
         *  path after a removal orphaned live state. */
        public long rescheduled;
        public long ambiguousEntries;
        public long ambiguousContainers;
        /** clear-ambiguous mode only. */
        public long clearedAmbiguous;
        /** Entries/containers that could not be safely classified or
         *  processed (state unreadable, undecodable NBT, below-full chunks). */
        public long skipped;
        /** Entries in packed (deserialized, not-yet-unpacked) containers —
         *  reported, never mutated: no public container API reaches them, and
         *  internal field access is unfixable across mods (audit15). They
         *  become repairable once their chunk unpacks naturally. */
        public long deferredPackedEntries;
        public long deferredPackedContainers;
        /** Unloaded chunks the batcher declined to load, counted per CHUNK —
         *  deliberately separate from {@link #deferredPackedContainers}, which
         *  counts per LANE (a chunk with both lanes packed contributes 2 there).
         *  Mixing the two units over-reported deferral (2026-07-27 review). */
        public long deferredUnloadedChunks;

        public String summary() {
            return "dims=" + dimensions
                    + " containers=" + containersScanned
                    + " entries=" + entriesScanned
                    + " | corruption repaired (type-mismatch removals)=" + removedMismatch
                    + " rescheduled=" + rescheduled
                    + " ambiguous=" + ambiguousEntries
                    + " cleared-ambiguous=" + clearedAmbiguous
                    + " | routine domain transitions applied (NOT corruption)=" + domainSyncEntries
                    + " (in " + domainSyncContainers + " containers)"
                    + " | deferred-packed=" + deferredPackedEntries
                    + " (in " + deferredPackedContainers + " packed containers — never mutated; "
                    + "repairable once those chunks unpack naturally at start-of-ticking)"
                    + " | skipped=" + skipped
                    + " healthy=" + healthyEntries;
        }

        /** Merge every counter of {@code o} into this (transaction-commit
         *  helper for the per-chunk apply; {@link #dimensions} is a run-level
         *  property and deliberately not merged). */
        void add(Totals o) {
            containersScanned += o.containersScanned;
            entriesScanned += o.entriesScanned;
            healthyEntries += o.healthyEntries;
            domainSyncEntries += o.domainSyncEntries;
            domainSyncContainers += o.domainSyncContainers;
            removedMismatch += o.removedMismatch;
            rescheduled += o.rescheduled;
            ambiguousEntries += o.ambiguousEntries;
            ambiguousContainers += o.ambiguousContainers;
            clearedAmbiguous += o.clearedAmbiguous;
            skipped += o.skipped;
            deferredPackedEntries += o.deferredPackedEntries;
            deferredPackedContainers += o.deferredPackedContainers;
            deferredUnloadedChunks += o.deferredUnloadedChunks;
        }
    }

    /** One classified entry (or container-level action applied to an entry).
     *  {@code relDelay} is the remaining delay against the clock the entry is
     *  read with — EXACT and preserved by every repair; absolute triggers are
     *  as-of-scan (island clocks advance concurrently). */
    public record Finding(String dim, int cx, int cz, String lane, String type, BlockPos pos,
                          long oldTrigger, long newTrigger, long relDelay,
                          net.minecraft.world.ticks.TickPriority priority, long subTick,
                          String action, String reason) {

        public String line() {
            return dim + " chunk[" + cx + "," + cz + "] " + lane + " " + type
                    + " @" + pos.toShortString()
                    + " trigger=" + oldTrigger
                    + (newTrigger != oldTrigger ? " -> " + newTrigger : "")
                    + " rel=" + (relDelay >= 0 ? "+" : "") + relDelay
                    + " prio=" + priority + " sub=" + subTick
                    + " action=" + action + " reason=" + reason;
        }
    }

    public static final class Result {
        public final Totals totals = new Totals();
        /** Classification snapshot (what the scan saw / dry-run output). */
        public final List<Finding> findings = new ArrayList<>();
        /** What was actually mutated (mutating modes only). */
        public final List<Finding> applied = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();
        public Path reportFile;
    }

    // ------------------------------------------------------------------
    // Consumer-domain resolution (the "correct" clock R for a chunk)
    // ------------------------------------------------------------------

    record ConsumerDomain(long id, long gen, long now) {
        String name() {
            return id == FabroliaTickDomain.WALL ? "WALL" : "island#" + id;
        }
    }

    /** The domain whose lane will CONSUME this chunk's containers right now:
     *  the owning live island when async is active, otherwise WALL. Both are
     *  always resolvable. */
    static ConsumerDomain consumerDomain(ServerLevel level, long chunkKey) {
        RegionizedServer rs = RegionScheduler.forLevel(level);
        if (rs != null && rs.asyncActive) {
            IslandRegistry.PIsland owner = rs.islandOwnerOfChunk(chunkKey);
            if (owner != null && !owner.retired) {
                return new ConsumerDomain(owner.id, owner.gen, owner.logicalTick);
            }
        }
        return new ConsumerDomain(FabroliaTickDomain.WALL, -1, TickDomains.wallNow(level));
    }

    // ------------------------------------------------------------------
    // Loaded-chunk pass
    // ------------------------------------------------------------------

    /**
     * Classify (and, in mutating modes, repair) every loaded scheduled-tick
     * container of {@code levels}. Two-phase: full classification first, then
     * the report file is written, then — only in mutating modes — each
     * actionable chunk is re-classified and repaired atomically under the
     * lane locks (clocks advance concurrently, so the applied delta is
     * re-derived at apply time; relative delays are preserved either way).
     *
     * @param preMutationHook invoked once per chunk immediately before its
     *        first mutation (production: region-file backup); may be null.
     */
    public static Result runLoadedPass(List<ServerLevel> levels, Mode mode, Path reportDir,
                                       ObjLongConsumer<ServerLevel> preMutationHook) {
        Result r = new Result();
        r.totals.dimensions = levels.size();
        List<long[]> actionable = new ArrayList<>();   // {levelIndex, chunkKey}
        for (int li = 0; li < levels.size(); li++) {
            ServerLevel level = levels.get(li);
            for (long chunkKey : chunkKeysWithTicks(level)) {
                int before = r.findings.size();
                processChunk(level, chunkKey, mode, false, r.totals, r.findings, null, null);
                if (hasActionable(r.findings, before, mode)) {
                    actionable.add(new long[]{li, chunkKey});
                }
            }
        }
        // Report file BEFORE any mutation. Mutating modes abort if it cannot
        // be written — an unrecorded repair is worse than no repair.
        try {
            r.reportFile = writeReport(reportDir, mode, r);
        } catch (IOException e) {
            r.notes.add("report file could not be written (" + e + ")"
                    + (mode.mutating ? " — MUTATION ABORTED, nothing was changed" : ""));
            if (mode.mutating) {
                return r;
            }
        }
        if (!mode.mutating) {
            return r;
        }
        Totals appliedTotals = new Totals();
        appliedTotals.dimensions = r.totals.dimensions;
        // Crash-safe applied record (audit15): the section header goes in
        // BEFORE the first mutation and every chunk's applied lines are
        // appended immediately after that chunk commits — a crash mid-run can
        // never lose the record of what was already committed.
        try {
            appendLines(r.reportFile, List.of(
                    "== applied (appended incrementally, per chunk — crash-safe record) =="));
        } catch (IOException e) {
            r.notes.add("could not append applied-section header to report: " + e);
        }
        for (long[] a : actionable) {
            ServerLevel level = levels.get((int) a[0]);
            if (preMutationHook != null) {
                try {
                    preMutationHook.accept(level, a[1]);
                } catch (Throwable t) {
                    r.notes.add("backup hook failed for " + level.dimension().identifier()
                            + " chunk [" + ChunkPos.getX(a[1]) + "," + ChunkPos.getZ(a[1]) + "]: "
                            + t + " (chunk still repaired; "
                            + "the report file is the authoritative record)");
                }
            }
            int fromApplied = r.applied.size();
            int fromNotes = r.notes.size();
            try {
                // applySingleChunk is itself a transaction: on an internal
                // throw it rolls the chunk back to its pre-mutation snapshot,
                // records FAILED-ROLLED-BACK in the notes and returns — the
                // run then CONTINUES with the next chunk.
                applySingleChunk(level, a[1], mode, appliedTotals, r.applied, r.notes);
            } catch (Throwable t) {
                // belt-and-braces: nothing past the transaction should throw,
                // but one chunk's escape must not abort the remaining chunks
                // or lose the incremental record.
                String msg = dim(level) + " chunk[" + ChunkPos.getX(a[1]) + ","
                        + ChunkPos.getZ(a[1]) + "] apply failed OUTSIDE the per-chunk "
                        + "transaction: " + t + " — earlier chunks' applied records above "
                        + "are committed; continuing with the next chunk";
                r.notes.add(msg);
                LOGGER.error("[tick-repair] {}", msg, t);
            }
            List<String> chunkRecord = new ArrayList<>();
            for (int i = fromApplied; i < r.applied.size(); i++) {
                chunkRecord.add(r.applied.get(i).line());
            }
            for (int i = fromNotes; i < r.notes.size(); i++) {
                chunkRecord.add("note: " + r.notes.get(i));
            }
            if (!chunkRecord.isEmpty()) {
                try {
                    appendLines(r.reportFile, chunkRecord);
                } catch (IOException e) {
                    LOGGER.warn("[tick-repair] could not append per-chunk applied record", e);
                }
            }
        }
        // Scan counters (containers/entries/healthy/ambiguous/skipped) stay
        // from the full classification snapshot; mutation counters come from
        // what the apply pass actually did.
        r.totals.domainSyncEntries = appliedTotals.domainSyncEntries;
        r.totals.domainSyncContainers = appliedTotals.domainSyncContainers;
        r.totals.removedMismatch = appliedTotals.removedMismatch;
        r.totals.rescheduled = appliedTotals.rescheduled;
        r.totals.clearedAmbiguous = appliedTotals.clearedAmbiguous;
        try {
            appendApplied(r.reportFile, r);
        } catch (IOException e) {
            r.notes.add("could not append applied section to report: " + e);
        }
        return r;
    }

    private static boolean hasActionable(List<Finding> findings, int from, Mode mode) {
        for (int i = from; i < findings.size(); i++) {
            String a = findings.get(i).action();
            if (mode.clearAmbiguous) {
                if (a.equals(ACTION_CLEAR)) return true;
            } else {
                if (a.equals(ACTION_DOMAIN_SYNC) || a.equals(ACTION_REMOVE)) return true;
            }
        }
        return false;
    }

    /** Union of chunk keys with any pending scheduled work in either lane. */
    static it.unimi.dsi.fastutil.longs.LongOpenHashSet chunkKeysWithTicks(ServerLevel level) {
        it.unimi.dsi.fastutil.longs.LongOpenHashSet keys = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        collectKeys(level.getBlockTicks(), keys);
        collectKeys(level.getFluidTicks(), keys);
        return keys;
    }

    private static void collectKeys(Object lane, it.unimi.dsi.fastutil.longs.LongOpenHashSet keys) {
        if (!(lane instanceof LevelTicksIslandAccess<?> access)) return;
        synchronized (access.fabrolia$tickLock()) {
            for (var e : access.fabrolia$allContainers().long2ObjectEntrySet()) {
                // count() OR peek() (task #41, Lithium audit §4.7): vanilla's
                // count() is queue.size() + pendingTicks.size(); Lithium's is
                // allTicks.size(), the (pos,type) dedup-set cardinality. Those
                // disagree in BOTH directions once a queue holds duplicates —
                // which unpack() can create on either implementation, since it
                // schedules unchecked past the dedup set. count()==0 with a
                // live queued tick would silently exclude that chunk from every
                // scan/repair pass, so the liveness half of the gate is peek(),
                // which is null-safe and exact on both. count() is kept for the
                // packed (deserialized, not-yet-unpacked) case, where the live
                // queue is empty by construction and peek() answers null.
                if (e.getValue() instanceof LevelChunkTicks<?> c
                        && (c.count() > 0 || c.peek() != null)) {
                    keys.add(e.getLongKey());
                }
            }
        }
    }

    static final String ACTION_DOMAIN_SYNC = "DOMAIN-SYNC";
    static final String ACTION_REMOVE = "REMOVE-STALE";
    static final String ACTION_AMBIGUOUS = "AMBIGUOUS";
    static final String ACTION_CLEAR = "CLEAR-AMBIGUOUS";
    static final String ACTION_RESCHEDULE = "RESCHEDULE";
    /** Packed (deserialized, not-yet-unpacked) container: reported, never
     *  mutated — repairable once the chunk unpacks naturally (audit15). */
    static final String ACTION_DEFERRED = "DEFERRED-PACKED";

    // ------------------------------------------------------------------
    // Per-chunk transaction (audit15): snapshot -> mutate -> commit; any
    // throw restores the snapshot exactly and the run continues. Snapshot
    // and restore use ONLY the public container API (getAll / removeIf /
    // schedule), which vanilla and Lithium both implement consistently.
    // ------------------------------------------------------------------

    /** Pre-mutation state of one lane's container: the exact queue entries
     *  (type/pos/triggerTick/priority/subTickOrder — the ScheduledTick record
     *  carries all five), the domain tag, and the lane's next-tick index. */
    private record LaneSnapshot(LevelChunkTicks<?> container, List<ScheduledTick<?>> queue,
                                long tagId, long tagGen, boolean hadNext, long nextVal) {}

    private static LaneSnapshot snapshotLane(LevelTicksIslandAccess<?> access, long chunkKey) {
        Object o = access.fabrolia$allContainers().get(chunkKey);
        if (!(o instanceof LevelChunkTicks<?> c)) {
            return null;
        }
        FabroliaTickDomain tag = (FabroliaTickDomain) (Object) c;
        boolean hadNext = access.fabrolia$nextTickForContainer().containsKey(chunkKey);
        long nextVal = hadNext ? access.fabrolia$nextTickForContainer().get(chunkKey) : 0L;
        return new LaneSnapshot(c, c.getAll().collect(Collectors.toList()),
                tag.fabrolia$domainIsland(), tag.fabrolia$domainGen(), hadNext, nextVal);
    }

    /** Restore a lane to its snapshot: drain the queue via the public API and
     *  re-schedule the snapshot's own ScheduledTick objects (every field
     *  preserved bit-for-bit — vanilla re-adds the object as-is, Lithium
     *  re-derives its hash from the same pos+type). Returns null when the
     *  restored queue verifies EXACTLY equal to the snapshot (multiset of
     *  full records), else a discrepancy description. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String restoreLane(LevelTicksIslandAccess<?> access, long chunkKey,
                                      LaneSnapshot snap) {
        if (snap == null) {
            return null;
        }
        LevelChunkTicks erased = (LevelChunkTicks) snap.container();
        TickDomains.drainRemoveIfErased(erased, t -> true); // task #37: impl-agnostic drain
        for (ScheduledTick<?> st : snap.queue()) {
            erased.schedule(st);
        }
        ((FabroliaTickDomain) (Object) snap.container())
                .fabrolia$setDomain(snap.tagId(), snap.tagGen());
        if (snap.hadNext()) {
            access.fabrolia$nextTickForContainer().put(chunkKey, snap.nextVal());
        } else {
            access.fabrolia$nextTickForContainer().remove(chunkKey);
        }
        List<ScheduledTick<?>> now = ((LevelChunkTicks<?>) snap.container())
                .getAll().collect(Collectors.toList());
        return compareExact(snap.queue(), now);
    }

    /** Exact multiset comparison of full ScheduledTick records (record equals:
     *  type identity for registry objects, pos, triggerTick, priority,
     *  subTickOrder). Null = exact match. */
    private static String compareExact(List<ScheduledTick<?>> want, List<ScheduledTick<?>> got) {
        if (want.size() != got.size()) {
            return "queue size " + got.size() + " != expected " + want.size();
        }
        java.util.Map<ScheduledTick<?>, Integer> counts = new java.util.HashMap<>();
        for (ScheduledTick<?> t : want) {
            counts.merge(t, 1, Integer::sum);
        }
        for (ScheduledTick<?> t : got) {
            Integer left = counts.computeIfPresent(t, (k, v) -> v - 1);
            if (left == null || left < 0) {
                return "unexpected entry after restore: " + t;
            }
        }
        return null;
    }

    /** Re-classify and repair one chunk atomically as a TRANSACTION, then run
     *  the recovery reschedules and mark the chunk dirty. Entry point for both
     *  the loaded pass and the unloaded batcher. On any throw from the
     *  classification/mutation step both lanes are restored to their
     *  pre-mutation snapshots (verified exact), a FAILED-ROLLED-BACK note is
     *  recorded, and the method returns false so the run continues with the
     *  next chunk — nothing from this chunk is committed. */
    static boolean applySingleChunk(ServerLevel level, long chunkKey, Mode mode,
                                    Totals totals, List<Finding> applied, List<String> notes) {
        List<BlockPos> rescheduleFluid = new ArrayList<>();
        List<BlockPos> rescheduleFull = new ArrayList<>();
        int cx = ChunkPos.getX(chunkKey), cz = ChunkPos.getZ(chunkKey);
        LevelTicksIslandAccess<?> blockA = (LevelTicksIslandAccess<?>) level.getBlockTicks();
        LevelTicksIslandAccess<?> fluidA = (LevelTicksIslandAccess<?>) level.getFluidTicks();
        boolean mutated;
        Totals tx = new Totals();
        List<Finding> txApplied = new ArrayList<>();
        // GLOBAL LOCK ORDER: block lane first, fluid second (processChunk
        // re-enters both reentrantly) — snapshot and mutation are one atomic
        // step under the same lock hold.
        synchronized (blockA.fabrolia$tickLock()) {
            synchronized (fluidA.fabrolia$tickLock()) {
                LaneSnapshot blockSnap = snapshotLane(blockA, chunkKey);
                LaneSnapshot fluidSnap = snapshotLane(fluidA, chunkKey);
                try {
                    mutated = processChunk(level, chunkKey, mode, true, tx, txApplied,
                            rescheduleFluid, rescheduleFull);
                } catch (Throwable t) {
                    String blockDiff = null, fluidDiff = null, restoreThrow = null;
                    try {
                        blockDiff = restoreLane(blockA, chunkKey, blockSnap);
                        fluidDiff = restoreLane(fluidA, chunkKey, fluidSnap);
                    } catch (Throwable rt) {
                        restoreThrow = rt.toString();
                    }
                    boolean exact = blockDiff == null && fluidDiff == null && restoreThrow == null;
                    String msg = dim(level) + " chunk[" + cx + "," + cz + "] FAILED-ROLLED-BACK: "
                            + t + " — no mutation from this chunk was committed; "
                            + (exact
                               ? "pre-mutation snapshot restored and verified exact"
                               : "SNAPSHOT RESTORE NOT VERIFIED EXACT ("
                                 + (restoreThrow != null ? "restore threw: " + restoreThrow : "")
                                 + (blockDiff != null ? " block: " + blockDiff : "")
                                 + (fluidDiff != null ? " fluid: " + fluidDiff : "")
                                 + ") — use the region-file backup for this chunk")
                            + "; continuing with the next chunk";
                    notes.add(msg);
                    LOGGER.error("[tick-repair] {}", msg, t);
                    return false;
                }
            }
        }
        // COMMIT: merge the transaction's counters and record only now.
        totals.add(tx);
        applied.addAll(txApplied);
        // Smart-reschedule recovery, outside the lane locks (scheduleTick
        // re-enters them itself). Normal pass: a removed stale FLUID entry can
        // orphan a real fluid mid-flow (fluids self-sustain via their pending
        // tick) — give the CURRENT fluid a fresh 1-tick schedule if it has
        // none. Never a generic immediate tick; the fluid decides.
        for (BlockPos pos : dedupe(rescheduleFluid)) {
            try {
                var fluid = level.getFluidState(pos);
                if (!fluid.isEmpty() && !level.getFluidTicks().hasScheduledTick(pos, fluid.getType())) {
                    level.scheduleTick(pos, fluid.getType(), 1);
                    totals.rescheduled++;
                    applied.add(new Finding(dim(level), cx, cz, "fluid",
                            BuiltInRegistries.FLUID.getKey(fluid.getType()).toString(), pos,
                            0, 0, 1, net.minecraft.world.ticks.TickPriority.NORMAL, 0,
                            ACTION_RESCHEDULE, "current fluid re-scheduled after stale-entry removal"));
                }
            } catch (Throwable t) {
                LOGGER.warn("[tick-repair] fluid reschedule failed at {}: {}", pos.toShortString(), t.toString());
            }
        }
        // clear-ambiguous recovery: a cleared entry whose type still matches
        // the world may have been a legitimate pending tick — resume the
        // block/fluid through the existing operator reschedule path.
        for (BlockPos pos : dedupe(rescheduleFull)) {
            try {
                String out = TickInspector.reschedule(level, pos, 1);
                totals.rescheduled++;
                applied.add(new Finding(dim(level), cx, cz, "both", "-", pos,
                        0, 0, 1, net.minecraft.world.ticks.TickPriority.NORMAL, 0,
                        ACTION_RESCHEDULE, "post-clear recovery: " + out));
            } catch (Throwable t) {
                LOGGER.warn("[tick-repair] reschedule failed at {}: {}", pos.toShortString(), t.toString());
            }
        }
        if (mutated) {
            markDirty(level, chunkKey);
        }
        return mutated;
    }

    private static List<BlockPos> dedupe(List<BlockPos> in) {
        return in.stream().distinct().collect(Collectors.toList());
    }

    private static void markDirty(ServerLevel level, long chunkKey) {
        try {
            ChunkAccess chunk = level.getChunk(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey),
                    ChunkStatus.FULL, false);
            if (chunk != null) {
                chunk.markUnsaved();
            }
        } catch (Throwable t) {
            LOGGER.warn("[tick-repair] could not mark chunk [{},{}] unsaved: {}",
                    ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey), t.toString());
        }
    }

    static String dim(ServerLevel level) {
        return level.dimension().identifier().toString();
    }

    // ------------------------------------------------------------------
    // Per-chunk classification + repair (one atomic step under both locks)
    // ------------------------------------------------------------------

    /**
     * @param apply false = classify only (never mutates anything);
     *              true = classify and repair in the same lock hold.
     * @return whether anything was mutated.
     */
    static boolean processChunk(ServerLevel level, long chunkKey, Mode mode, boolean apply,
                                Totals totals, List<Finding> out,
                                List<BlockPos> rescheduleFluid, List<BlockPos> rescheduleFull) {
        LevelTicksIslandAccess<?> blockA = (LevelTicksIslandAccess<?>) level.getBlockTicks();
        LevelTicksIslandAccess<?> fluidA = (LevelTicksIslandAccess<?>) level.getFluidTicks();
        boolean mutated = false;
        // GLOBAL LOCK ORDER: block lane first, fluid second — identical to
        // enforceConsumerDomain; never AB-BA against a concurrent heal.
        synchronized (blockA.fabrolia$tickLock()) {
            synchronized (fluidA.fabrolia$tickLock()) {
                mutated |= processLane(level, chunkKey, true, blockA, mode, apply,
                        totals, out, rescheduleFluid, rescheduleFull);
                mutated |= processLane(level, chunkKey, false, fluidA, mode, apply,
                        totals, out, rescheduleFluid, rescheduleFull);
            }
        }
        return mutated;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean processLane(ServerLevel level, long chunkKey, boolean blockLane,
                                       LevelTicksIslandAccess<?> access, Mode mode, boolean apply,
                                       Totals totals, List<Finding> out,
                                       List<BlockPos> rescheduleFluid, List<BlockPos> rescheduleFull) {
        Object o = access.fabrolia$allContainers().get(chunkKey);
        if (!(o instanceof LevelChunkTicks<?> c)) {
            return false;
        }
        List<ScheduledTick<?>> queue = c.getAll().collect(Collectors.toList());
        // PACKED-STATE PROBE, public API only (audit15): getAll() streams only
        // the live tick queue on both vanilla and Lithium, while count()
        // includes the deserialized pending entries too (vanilla: queue size +
        // pendingTicks size; Lithium: its allTicks hash set, seeded from
        // pendingTicks at construction and disjoint from the queue by dedup).
        // count() > queue size therefore means packed relative-delay entries
        // exist that NO public API can reach.
        //
        // KNOWN IMPRECISION, accepted (task #41, audit §4.7): on Lithium the
        // dedup set collapses duplicates, so a queue holding N copies of one
        // (pos,type) makes count() UNDER-report by N-1 and the difference can
        // come out zero or negative for a genuinely packed container. The
        // consequence is a packed container classified as live and then found
        // empty by getAll() — under-reporting, never a bad mutation, because
        // every mutation below still runs through the public API on entries
        // getAll() actually returned. There is no public API that separates the
        // two states exactly; reaching into pendingTicks is the audit15 NPE.
        int packedCount = c.count() - queue.size();
        if (queue.isEmpty() && packedCount <= 0) {
            return false;
        }
        totals.containersScanned++;
        String dim = dim(level);
        int cx = ChunkPos.getX(chunkKey), cz = ChunkPos.getZ(chunkKey);
        String lane = blockLane ? "block" : "fluid";
        if (packedCount > 0) {
            // LIFECYCLE PREFLIGHT (audit15): a packed (deserialized,
            // not-yet-unpacked) container is NOT in a mutable state — vanilla's
            // removeIf iterates only tickQueue and cannot remove from
            // pendingTicks, so there is no public API that mutates packed
            // state, and reaching into the internals is unfixable across mods
            // (vanilla keeps the ticksPerPosition dedup set; Lithium nulls it
            // and keeps its own allTicks — the internal-write path was the
            // production NPE on Lithium and silent dedup corruption besides).
            // Defer the WHOLE container — classification of its entries would
            // be repeated after unpack anyway — and never force an unpack.
            // This check runs under the lane locks immediately before any
            // mutation would happen, in both the classification and the apply
            // pass, so a mutable verdict here is the apply-time truth.
            long entries = (long) queue.size() + packedCount;
            totals.entriesScanned += entries;
            totals.deferredPackedEntries += entries;
            totals.deferredPackedContainers++;
            out.add(new Finding(dim, cx, cz, lane + "(packed)", "-",
                    new BlockPos(cx << 4, 0, cz << 4), 0, 0, 0,
                    net.minecraft.world.ticks.TickPriority.NORMAL, 0, ACTION_DEFERRED,
                    packedCount + " packed relative-delay entries (+" + queue.size()
                            + " queued) in a deserialized container — DEFERRED, not mutated: "
                            + "no public container API reaches packed state and internal "
                            + "access is unfixable across mods; these become repairable "
                            + "once the chunk unpacks naturally at start-of-ticking "
                            + "(re-run repair-all then); the unpack is never forced"));
            return false;
        }
        FabroliaTickDomain tag = (FabroliaTickDomain) (Object) c;
        long tagId = tag.fabrolia$domainIsland();
        ConsumerDomain consumer = consumerDomain(level, chunkKey);
        // NON-COUNTING probe: a classification pass performs no recovery, so
        // it must not inflate UNRESOLVED_TAGS or emit the operational-path
        // "adopt-without-shift" ERROR (review finding #3a).
        long tagNow = TickDomains.probeNow(level, c);
        boolean tagResolvable = tagNow != Long.MIN_VALUE;
        boolean domainMismatch = tagResolvable && tagId != consumer.id();
        long delta = domainMismatch ? consumer.now() - tagNow : 0;

        Set<ScheduledTick<?>> removeSet = new HashSet<>();
        Set<ScheduledTick<?>> clearSet = new HashSet<>();
        int rebaseCount = 0;
        boolean anyAmbiguous = false;

        for (ScheduledTick<?> st : queue) {
            totals.entriesScanned++;
            if ((st.pos().getX() >> 4) != cx || (st.pos().getZ() >> 4) != cz) {
                // out-of-chunk entry (foreign data): unverifiable here, and a
                // state read could force a synchronous chunk load — skip
                totals.skipped++;
                continue;
            }
            String typeName = typeName(blockLane, st.type());
            String mismatch = typeMismatch(level, blockLane, st.type(), st.pos());
            if (mismatch != null) {
                if (mode.clearAmbiguous) {
                    // informational only — the clear-ambiguous mode touches
                    // nothing but AMBIGUOUS entries; repair-all confirm applies these
                    totals.skipped++;
                    out.add(new Finding(dim, cx, cz, lane, typeName, st.pos(),
                            st.triggerTick(), st.triggerTick(),
                            tagResolvable ? st.triggerTick() - tagNow : 0,
                            st.priority(), st.subTickOrder(), ACTION_REMOVE,
                            mismatch + " (NOT applied in clear-ambiguous mode — run repair-all confirm)"));
                } else {
                    totals.removedMismatch++;
                    removeSet.add(st);
                    out.add(new Finding(dim, cx, cz, lane, typeName, st.pos(),
                            st.triggerTick(), st.triggerTick(),
                            tagResolvable ? st.triggerTick() - tagNow : 0,
                            st.priority(), st.subTickOrder(), ACTION_REMOVE, mismatch));
                }
            } else if (!tagResolvable) {
                totals.ambiguousEntries++;
                anyAmbiguous = true;
                boolean clearing = mode.clearAmbiguous;
                if (clearing) {
                    clearSet.add(st);
                    totals.clearedAmbiguous += apply ? 1 : 0;
                }
                out.add(new Finding(dim, cx, cz, lane, typeName, st.pos(),
                        st.triggerTick(), st.triggerTick(), 0,
                        st.priority(), st.subTickOrder(),
                        clearing ? ACTION_CLEAR : ACTION_AMBIGUOUS,
                        "container tagged island#" + tagId + " g" + tag.fabrolia$domainGen()
                                + " whose clock is unresolvable — intended delay not reconstructible"));
            } else if (domainMismatch && !mode.clearAmbiguous) {
                totals.domainSyncEntries++;
                rebaseCount++;
                out.add(new Finding(dim, cx, cz, lane, typeName, st.pos(),
                        st.triggerTick(), st.triggerTick() + delta, st.triggerTick() - tagNow,
                        st.priority(), st.subTickOrder(), ACTION_DOMAIN_SYNC,
                        "routine pending domain transition ("
                                + (tagId == FabroliaTickDomain.WALL ? "WALL" : "island#" + tagId)
                                + " now=" + tagNow + " -> " + consumer.name() + " now=" + consumer.now()
                                + ", delta " + delta + "), applied eagerly; identical to the automatic "
                                + "consumption-point heal; NOT evidence of corruption; "
                                + "relative delay preserved exactly"));
            } else {
                totals.healthyEntries++;
            }
        }

        if (rebaseCount > 0) {
            // counted at classification time so dry runs report container
            // counts correctly (review finding #4); the apply pass reuses
            // this same classification section
            totals.domainSyncContainers++;
        }
        if (anyAmbiguous) {
            totals.ambiguousContainers++;
        }

        if (!apply) {
            return false;
        }

        boolean mutated = false;
        LevelChunkTicks erased = (LevelChunkTicks) c;
        if (!mode.clearAmbiguous) {
            if (!removeSet.isEmpty()) {
                TickDomains.drainRemoveIfErased(erased, removeSet::contains); // task #37
                mutated = true;
                if (!blockLane && rescheduleFluid != null) {
                    for (ScheduledTick<?> st : removeSet) {
                        rescheduleFluid.add(st.pos());
                    }
                }
                for (ScheduledTick<?> st : removeSet) {
                    LOGGER.info("[tick-repair] {} chunk[{},{}] removed stale {} {} @{} trigger={}",
                            dim, cx, cz, lane, typeName(blockLane, st.type()),
                            st.pos().toShortString(), st.triggerTick());
                }
            }
            // NOTE (audit15): packed-entry mutation deliberately does NOT
            // exist — packed containers never reach this point (deferred by
            // the preflight above). removeIf/schedule above are the public
            // API on both vanilla and Lithium, so removals free the pos+type
            // dedup slot consistently on both.
            if (domainMismatch) {
                int moved = TickDomains.rebaseTo(level, c, consumer.id(), consumer.gen(),
                        consumer.now(), chunkKey, lane + "-repair");
                mutated = true;
                LOGGER.info("[tick-repair] {} chunk[{},{}] {} routine domain transition applied {} -> {} "
                                + "({} triggers shifted; identical to the automatic consumption-point heal, "
                                + "NOT corruption; relative delays preserved exactly)",
                        dim, cx, cz, lane,
                        tagId == FabroliaTickDomain.WALL ? "WALL" : "island#" + tagId,
                        consumer.name(), moved);
            }
        } else {
            if (!clearSet.isEmpty()) {
                TickDomains.drainRemoveIfErased(erased, clearSet::contains); // task #37
                mutated = true;
                for (ScheduledTick<?> st : clearSet) {
                    LOGGER.warn("[tick-repair] {} chunk[{},{}] CLEARED ambiguous {} {} @{} trigger={} (operator confirm)",
                            dim, cx, cz, lane, typeName(blockLane, st.type()),
                            st.pos().toShortString(), st.triggerTick());
                    String cleared = typeMismatch(level, blockLane, st.type(), st.pos());
                    if (cleared == null && rescheduleFull != null) {
                        // type still matches the world: the cleared entry may
                        // have been a legitimate pending tick — recover.
                        rescheduleFull.add(st.pos());
                    }
                }
                // The clock this container was expressed in is gone: heal the
                // tag to the current consumer UNCONDITIONALLY once the
                // ambiguous entries are cleared (review finding #5). The only
                // queue entries that can remain are proven-stale type
                // mismatches (every non-mismatch entry of an unresolvable
                // container is ambiguous and was just cleared); reinterpreting
                // their triggers on the consumer clock is harmless because
                // vanilla's due-time type recheck skips them anyway — whereas
                // KEEPING the dead tag would send later schedules through
                // rebaseTo's adopt-without-shift ERROR path and anchor packed
                // delays via unpackBasis's caller fallback (the exact bug
                // class this facility exists to prevent). Packed relative
                // delays now anchor correctly against the healed tag.
                tag.fabrolia$setDomain(consumer.id(), consumer.gen());
            }
        }
        if (mutated) {
            ScheduledTick<?> head = c.peek();
            if (head != null) {
                access.fabrolia$nextTickForContainer().put(chunkKey, head.triggerTick());
            } else {
                access.fabrolia$nextTickForContainer().remove(chunkKey);
            }
        }
        return mutated;
    }

    static String typeName(boolean blockLane, Object type) {
        try {
            return blockLane
                    ? BuiltInRegistries.BLOCK.getKey((Block) type).toString()
                    : BuiltInRegistries.FLUID.getKey((Fluid) type).toString();
        } catch (Throwable t) {
            return String.valueOf(type);
        }
    }

    /** Null when the scheduled type still matches the world (or the state
     *  cannot be read — no proof, no action); otherwise the removal reason. */
    static String typeMismatch(ServerLevel level, boolean blockLane, Object type, BlockPos pos) {
        try {
            if (blockLane) {
                Block cur = level.getBlockState(pos).getBlock();
                if (cur != type) {
                    return "scheduled block no longer matches world (position holds "
                            + BuiltInRegistries.BLOCK.getKey(cur) + ")";
                }
            } else {
                Fluid cur = level.getFluidState(pos).getType();
                if (cur != type) {
                    return "scheduled fluid no longer matches world (position holds "
                            + BuiltInRegistries.FLUID.getKey(cur) + ")";
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Report file
    // ------------------------------------------------------------------

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    static Path writeReport(Path reportDir, Mode mode, Result r) throws IOException {
        Files.createDirectories(reportDir);
        Path file = reportDir.resolve("tick-repair-" + LocalDateTime.now().format(STAMP)
                + "-" + mode.name().toLowerCase(java.util.Locale.ROOT) + ".txt");
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write("Fabrolia bulk scheduled-tick repair — mode=" + mode + "\n");
            w.write(SCOPE + "\n");
            w.write(SEMANTICS + "\n");
            w.write("classification: " + r.totals.summary() + "\n");
            w.write("NOTE: relative delays (rel=) are exact and preserved by every repair; absolute\n");
            w.write("triggers are as-of-scan (island clocks advance concurrently with this command).\n");
            w.write("== classification (" + r.findings.size() + " actionable/flagged entries) ==\n");
            for (Finding f : r.findings) {
                w.write(f.line());
                w.write("\n");
            }
            for (String n : r.notes) {
                w.write("note: " + n + "\n");
            }
        }
        return file;
    }

    /** Trailer only (audit15): the per-record applied lines are appended
     *  incrementally per chunk by {@link #runLoadedPass} so a crash cannot
     *  lose the record of committed mutations. */
    static void appendApplied(Path file, Result r) throws IOException {
        if (file == null) return;
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write("== applied trailer (" + r.applied.size()
                    + " records, appended incrementally above) ==\n");
            w.write("applied totals: " + r.totals.summary() + "\n");
        }
    }

    static synchronized void appendLines(Path file, List<String> lines) throws IOException {
        if (file == null) return;
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (String l : lines) {
                w.write(l);
                w.write("\n");
            }
        }
    }
}
