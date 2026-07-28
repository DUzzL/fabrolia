package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * A8 — island command execution (docs/ASYNC-ISLANDS.md §12; vanilla redstone
 * and vanilla command semantics are the sole source of truth).
 *
 * <p>Model: every command execution is ONE logical invocation with
 * exactly-once semantics. Phase 1 (this class) implements the two degenerate
 * shard shapes that cover all commands correctly:
 *
 * <ul>
 *   <li><b>island-local</b> — the command is PROVEN to read and write only
 *       state owned by the source island (allowlisted root, no selectors,
 *       every coordinate resolves into the island's cells). It executes
 *       INLINE on the island thread, at the island's cadence, reading the
 *       island's logical clock. One shard, on the owner.</li>
 *   <li><b>routed</b> — everything else (selectors, cross-island or
 *       unresolvable coordinates, unknown roots, functions, modded commands).
 *       It executes exactly once on the SERVER thread under a quiesce of
 *       every OTHER island (the source island is stable because its
 *       coordinator parks while it waits) — the safe compatibility path.
 *       One shard, on the serialized global domain; the source island's
 *       chain blocks until the result is known, so conditional chains,
 *       success counts and comparator outputs keep exact vanilla values.
 *       The synchronization is localized in time (one command) and never
 *       an ongoing server-wide barrier.</li>
 * </ul>
 *
 * <p>Phase 2 (invocation shards) refines "routed" into per-island partitioned
 * execution with a transaction ledger; the {@link Invocation} identity carried
 * here is that ledger's key. Nothing in Phase 1 executes a command more than
 * once — there is always exactly one executing shard per invocation.
 *
 * <p>Main-context commands (console, players, datapack functions, RCON,
 * command-block minecarts on the main-sync lane) are fenced by the same
 * quiesce (CommandsMixin / ServerFunctionManagerMixin): with async islands
 * live they can no longer write into an in-flight island.
 */
public final class IslandCommands {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-A8");

    private IslandCommands() {}

    // ------------------------------------------------------------------
    // Invocation identity (the ledger schema — Phase 1 fills and retires
    // entries synchronously; Phase 2's shard ledger keys off the same ids)
    // ------------------------------------------------------------------

    private static final AtomicLong NEXT_INVOCATION_ID = new AtomicLong(1);

    /** One logical command invocation. Identity is final; state is volatile.
     *  Exactly one executing shard exists per invocation in Phase 1. */
    public static final class Invocation {
        public final long id = NEXT_INVOCATION_ID.getAndIncrement();
        public final String sourceKind;         // "command_block" | "main" | "function"
        public final BlockPos sourcePos;        // may be null (console)
        public final long sourceIslandId;       // -1 = global context
        public final long sourceLogicalTick;    // island L (or gameTime for main)
        public final String command;
        public final long orderingToken;        // island (id<<20|L) — deterministic per source
        public volatile byte state;             // 0 pending, 1 running, 2 committed
        public volatile int result;             // success count / boolean result

        Invocation(String sourceKind, BlockPos sourcePos, long islandId, long logicalTick, String command) {
            this.sourceKind = sourceKind;
            this.sourcePos = sourcePos;
            this.sourceIslandId = islandId;
            this.sourceLogicalTick = logicalTick;
            this.command = command;
            this.orderingToken = (islandId << 20) ^ logicalTick;
        }
    }

    // ---- diagnostics (read by /fabrolia regions cmdstats) ----
    public static final LongAdder LOCAL_INLINE = new LongAdder();
    public static final LongAdder ROUTED_GLOBAL = new LongAdder();
    public static final LongAdder MAIN_FENCED = new LongAdder();
    public static final LongAdder FUNCTION_FENCED = new LongAdder();
    static final LongAdder ROUTE_WAIT_NANOS = new LongAdder();
    static final LongAdder QUIESCE_NANOS = new LongAdder();
    public static final LongAdder LOCAL_FN_INLINE = new LongAdder();
    public static final LongAdder TICK_FN_ISLAND_DISPATCH = new LongAdder();
    /** Exactly-once proof counter: a second execution attempt for the same
     *  invocation (must stay 0 forever; Phase 2 merge/split coalescing
     *  asserts through it too). */
    static final LongAdder DUPLICATE_EXEC_ATTEMPTS = new LongAdder();

    public static String stats() {
        return "cmd inline=" + LOCAL_INLINE.sum()
                + " fnInline=" + LOCAL_FN_INLINE.sum()
                + " tickFnIsland=" + TICK_FN_ISLAND_DISPATCH.sum()
                + " routed=" + ROUTED_GLOBAL.sum()
                + " mainFenced=" + MAIN_FENCED.sum()
                + " fnFenced=" + FUNCTION_FENCED.sum()
                + " waitMs=" + ROUTE_WAIT_NANOS.sum() / 1_000_000
                + " quiesceMs=" + QUIESCE_NANOS.sum() / 1_000_000
                + " dupExec=" + DUPLICATE_EXEC_ATTEMPTS.sum()
                + " | " + CommandShards.stats();
    }

    // ------------------------------------------------------------------
    // Classification — island-local proof
    // ------------------------------------------------------------------

    /** Roots whose effects are fully described by their coordinate arguments
     *  (no selectors, no global state). Everything else routes. */
    private static boolean isLocalRoot(String root) {
        return switch (root) {
            case "setblock", "fill", "clone", "summon", "data" -> true;
            default -> false;
        };
    }

    /**
     * True when {@code cmd} is PROVEN island-local for an island execution
     * context: allowlisted root, no selector anywhere, and every coordinate
     * triple the root's grammar defines resolves (absolutes and ~relatives
     * against the source position) into a region owned by {@code d}.
     * Any parse doubt returns false — the routed path is always correct.
     */
    static boolean isIslandLocal(String cmd, BlockPos src, RegionizedServer rs, EpochDomain d) {
        if (cmd.indexOf('@') >= 0) {
            return false;   // selectors reach arbitrary owners
        }
        String s = cmd.startsWith("/") ? cmd.substring(1) : cmd;
        String[] tok = s.trim().split("\\s+");
        if (tok.length == 0 || !isLocalRoot(tok[0])) {
            return false;
        }
        try {
            switch (tok[0]) {
                case "setblock":
                    return tok.length >= 4 && tripleLocal(tok, 1, src, rs, d);
                case "fill":
                    return tok.length >= 7 && tripleLocal(tok, 1, src, rs, d)
                            && tripleLocal(tok, 4, src, rs, d);
                case "clone":
                    return tok.length >= 10 && tripleLocal(tok, 1, src, rs, d)
                            && tripleLocal(tok, 4, src, rs, d) && tripleLocal(tok, 7, src, rs, d);
                case "summon":
                    // "summon <type>" = at source (local); with coords, prove them.
                    if (tok.length <= 2) return true;
                    return tok.length >= 5 && tripleLocal(tok, 2, src, rs, d);
                case "data":
                    // only block targets are provable: data <get|merge|modify|remove> block x y z ...
                    if (tok.length >= 6 && "block".equals(tok[2])) {
                        return tripleLocal(tok, 3, src, rs, d);
                    }
                    return false;
                default:
                    return false;
            }
        } catch (RuntimeException e) {
            return false;   // any grammar surprise → routed path
        }
    }

    /** Runtime validation of a load-time locality proof against THIS call's
     *  source island: REL bodies need source±radius inside the island's
     *  cells; ABS bodies need their box owned by this island. */
    static boolean fnInlineEligible(FunctionAnalyzer.Analysis a, BlockPos src,
                                    RegionizedServer rs, EpochDomain d) {
        switch (a.kind()) {
            case PURE_LOCAL_REL -> {
                int r = a.maxRelRadius();
                return boxOwnedBy(rs, d, src.getX() - r, src.getZ() - r,
                        src.getX() + r, src.getZ() + r);
            }
            case PURE_LOCAL_ABS -> {
                return boxOwnedBy(rs, d, a.minX(), a.minZ(), a.maxX(), a.maxZ());
            }
            default -> {
                return false;
            }
        }
    }

    static boolean boxOwnedBy(RegionizedServer rs, EpochDomain d,
                              int minX, int minZ, int maxX, int maxZ) {
        ThreadedRegionizer reg = rs.regionizer();
        int shift = reg.grid.shift + 4; // blocks per region cell
        for (int cx = minX >> shift; cx <= maxX >> shift; cx++) {
            for (int cz = minZ >> shift; cz <= maxZ >> shift; cz++) {
                Region r = reg.peekByGridKey(
                        reg.grid.keyFor((cx << shift) >> 4, (cz << shift) >> 4));
                if (r == null || r.domain != d) return false;
            }
        }
        return true;
    }

    /** A8 Phase 3: the single island owning a tick-tag function's absolute
     *  box, or null (ROUTED analysis / multi-island / unloaded / async off).
     *  Main thread, at dispatch time. */
    public static IslandRegistry.PIsland tickFunctionIslandOwner(net.minecraft.resources.Identifier id) {
        var a = FunctionAnalyzer.analysisOf(id);
        if (a == null || a.kind() != FunctionAnalyzer.Kind.PURE_LOCAL_ABS) {
            return null;
        }
        for (RegionizedServer rs : RegionScheduler.allLevels()) {
            if (!rs.asyncActive) continue;
            IslandRegistry.PIsland owner = ownerOfBox(rs, a.minX(), a.minZ(), a.maxX(), a.maxZ());
            if (owner != null) return owner;
        }
        return null;
    }

    static IslandRegistry.PIsland ownerOfBox(RegionizedServer rs,
                                             int minX, int minZ, int maxX, int maxZ) {
        ThreadedRegionizer reg = rs.regionizer();
        int shift = reg.grid.shift + 4;
        IslandRegistry.PIsland owner = null;
        for (int cx = minX >> shift; cx <= maxX >> shift; cx++) {
            for (int cz = minZ >> shift; cz <= maxZ >> shift; cz++) {
                Region r = reg.peekByGridKey(
                        reg.grid.keyFor((cx << shift) >> 4, (cz << shift) >> 4));
                EpochDomain d = r != null ? r.domain : null;
                IslandRegistry.PIsland p = d != null ? d.owner : null;
                if (p == null || (owner != null && p != owner)) return null;
                owner = p;
            }
        }
        return owner;
    }

    /** "function ns:path" (no macro args, no selectors) → the id token; else null. */
    private static String localFunctionId(String cmd) {
        String t = cmd.startsWith("/") ? cmd.substring(1) : cmd;
        String[] tok = t.trim().split("\\s+");
        if (tok.length != 2 || !"function".equals(tok[0])) return null;
        return tok[1].indexOf('#') < 0 ? tok[1] : null; // tags route (multi-fn)
    }

    private static boolean tripleLocal(String[] tok, int i, BlockPos src,
                                       RegionizedServer rs, EpochDomain d) {
        double x = coord(tok[i], src.getX());
        double y = coord(tok[i + 1], src.getY());
        double z = coord(tok[i + 2], src.getZ());
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) {
            return false;
        }
        ThreadedRegionizer reg = rs.regionizer();
        Region r = reg.peekByGridKey(reg.grid.keyFor(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4));
        return r != null && r.domain == d;
    }

    /** ~[offset] and plain absolutes; '^' (rotation-local) is not resolved
     *  here — NaN routes it. */
    private static double coord(String t, double base) {
        if (t.isEmpty()) return Double.NaN;
        if (t.charAt(0) == '^') return Double.NaN;
        if (t.charAt(0) == '~') {
            if (t.length() == 1) return base;
            try { return base + Double.parseDouble(t.substring(1)); }
            catch (NumberFormatException e) { return Double.NaN; }
        }
        try { return Double.parseDouble(t); }
        catch (NumberFormatException e) { return Double.NaN; }
    }

    // ------------------------------------------------------------------
    // Execution paths
    // ------------------------------------------------------------------

    /**
     * A command block executing from ITS island coordinator. Island-local →
     * inline (the caller is the owner thread; the vanilla body runs here, at
     * island cadence, on island time). Otherwise → exactly one execution on
     * the server thread under an all-other-islands quiesce; this island's
     * chain blocks (help-pumping) until the vanilla boolean result is known,
     * preserving conditional-chain and comparator semantics exactly.
     */
    public static boolean routeCommandBlock(String command, ServerLevel level,
                                            BlockPos sourcePos,
                                            net.minecraft.commands.CommandSourceStack sourceStack,
                                            BooleanSupplier original) {
        RegionizedServer rs = RegionScheduler.forLevel(level);
        if (rs == null || !rs.asyncActive) {
            return original.getAsBoolean();
        }
        EpochDomain d = null;
        if (Thread.currentThread() instanceof RegionWorkerThread w) {
            Region cur = w.currentRegion;
            d = cur != null ? cur.domain : w.currentDomain;
        }
        IslandRegistry.PIsland owner = d != null ? d.owner : null;
        if (owner == null || command == null || command.isEmpty()) {
            // Main context (sync-lane minecarts, console triggers) or a
            // non-island worker: vanilla body here; the nested dispatch is
            // fenced by CommandsMixin.
            return original.getAsBoolean();
        }
        if (isIslandLocal(command, sourcePos, rs, d)) {
            LOCAL_INLINE.increment();
            return original.getAsBoolean();
        }
        // Phase 3 (metadata-gated): a /function whose registered scope PROVES
        // island-locality (and needs no runtime validation) executes inline on
        // the owner — the whole compiled/interpreted body at island cadence on
        // island time. No metadata (Scope.UNKNOWN) or any dynamic-behavior
        // flag → the fenced routed path below (safe compatibility path).
        String fnId = localFunctionId(command);
        if (fnId != null) {
            net.minecraft.resources.Identifier ident =
                    net.minecraft.resources.Identifier.tryParse(fnId);
            var analysis = ident == null ? null : FunctionAnalyzer.analysisOf(ident);
            if (analysis != null && fnInlineEligible(analysis, sourcePos, rs, d)) {
                LOCAL_FN_INLINE.increment();
                return original.getAsBoolean();
            }
        }
        // Phase 2: decomposable selector command → per-owner shards (no
        // fence at all — ownership IS the mutual exclusion). Falls through
        // to the fenced batch when no adapter/selector applies.
        if (sourceStack != null) {
            Invocation shardLedger = new Invocation("command_block", sourcePos, owner.id,
                    owner.logicalTick, command);
            CompletableFuture<Boolean> sf =
                    CommandShards.tryExecuteSharded(command, level, rs, d, sourceStack, shardLedger);
            if (sf != null) {
                owner.waitingOnRoutedCommand = true;
                long sw0 = System.nanoTime();
                try {
                    while (!sf.isDone() && RegionScheduler.poolRunning()) {
                        CommandShards.serviceShards(rs, owner);
                        // Service TARGET islands too (claim-protected try):
                        // on small pools no idle worker exists to do it, and
                        // the target's own coordinator may be a whole unit
                        // away — this closes cross-island command latency to
                        // ~µs regardless of pool size.
                        if (!sf.isDone() && !RegionScheduler.serviceOneIslandLane()) {
                            LockSupport.parkNanos(50_000L);
                        }
                    }
                } finally {
                    owner.waitingOnRoutedCommand = false;
                }
                ROUTE_WAIT_NANOS.add(System.nanoTime() - sw0);
                return sf.getNow(false);
            }
        }
        // Routed: one shard, exactly once, on the serialized main batch.
        Invocation inv = new Invocation("command_block", sourcePos, owner.id,
                owner.logicalTick, command);
        ROUTED_GLOBAL.increment();
        CompletableFuture<Boolean> fut = new CompletableFuture<>();
        ROUTED.add(new RoutedCmd(inv, original, fut, isGlobalNoFence(command)));
        // Self-nudge: main drains its task queue while idling between slots,
        // so the batch runs within ~ms instead of at the next drive head —
        // a fenceless repeating command costs its island ~2ms, not a tick.
        var sv = RegionScheduler.getServer();
        if (sv != null) {
            sv.execute(() -> drainRoutedBatch(2_000_000L));
        }
        // PARK-ONLY wait (no help-pumping: helping could claim this island's
        // own regions and race the fenced execution). The waiting flag makes
        // this island fence-safe without its claim; futures complete only
        // AFTER the batch's fence is released, so no waiter ever resumes
        // into another command's fenced window.
        owner.waitingOnRoutedCommand = true;
        long w0 = System.nanoTime();
        try {
            while (!fut.isDone() && RegionScheduler.poolRunning()) {
                CommandShards.serviceShards(rs, owner);
                if (!fut.isDone() && !RegionScheduler.serviceOneIslandLane()) {
                    LockSupport.parkNanos(50_000L);
                }
            }
        } finally {
            owner.waitingOnRoutedCommand = false;
        }
        ROUTE_WAIT_NANOS.add(System.nanoTime() - w0);
        return fut.getNow(false);
    }

    // ------------------------------------------------------------------
    // Clock-domain rebase for scheduled ticks (LevelTicksMixin.schedule)
    // ------------------------------------------------------------------

    /** Re-express a schedule request's trigger time in the TARGET CONTAINER's
     *  tagged clock domain (delay is clock-invariant: newTrigger = tagNow +
     *  (trigger − ctxNow)). Same-domain calls (the common case) return the
     *  tick untouched. */
    public static <T> net.minecraft.world.ticks.ScheduledTick<T> rebaseScheduledTick(
            net.minecraft.world.ticks.LevelTicks<T> scheduler,
            net.minecraft.world.ticks.ScheduledTick<T> tick) {
        RegionizedServer rs = RegionScheduler.forTicksInstance(scheduler);
        if (rs == null) {
            return tick;
        }
        // Task #31 rework: the CONTAINER's tagged domain is the authority for
        // what clock its triggers live in — never the current region owner.
        // A missing container means vanilla's own not-loaded warn/drop path
        // handles the call; we do not invent a domain for it.
        @SuppressWarnings("unchecked")
        LevelTicksIslandAccess<T> access = (LevelTicksIslandAccess<T>) scheduler;
        long chunkKey = net.minecraft.world.level.ChunkPos.pack(
                tick.pos().getX() >> 4, tick.pos().getZ() >> 4);
        Object cObj = access.fabrolia$allContainers().get(chunkKey);
        if (!(cObj instanceof net.minecraft.world.ticks.LevelChunkTicks<?> c)) {
            return tick;
        }
        // HOT-PATH short-circuit: scheduling from inside the container's own
        // domain (island runner into its own chunk, main into a wall chunk) —
        // the overwhelmingly common case — needs no resolution at all.
        long tagId = ((FabroliaTickDomain) (Object) c).fabrolia$domainIsland();
        // ctxId MUST mirror IslandClock.contextTime() exactly: the caller's
        // trigger was computed through the redirected getGameTime(), so the
        // identity feeding the same-domain short-circuit has to name the SAME
        // clock that computed it. The pre-audit resolution diverged twice —
        // it preferred currentDomain over the claimed region's domain
        // (contextTime prefers the region and never falls through past it),
        // and it ignored unitGameTime == 0 (where contextTime answers WALL) —
        // either divergence let the short-circuit skip a rebase the trigger
        // actually needed, or apply one it must not, off by the full
        // wall↔island clock offset.
        long ctxId = FabroliaTickDomain.WALL;
        if (Thread.currentThread() instanceof RegionWorkerThread w) {
            EpochDomain cd = w.currentRegion != null ? w.currentRegion.domain : w.currentDomain;
            if (cd != null && cd.owner != null && cd.unitGameTime != 0) ctxId = cd.owner.id;
        }
        if (tagId == ctxId) {
            return tick;
        }
        if (!rs.asyncActive) {
            // SYNC INVARIANT (task #13): with async off the wall lane is the
            // only consumer — a non-WALL tag here is stale by definition
            // (async-era save loaded into a sync session). Shifting FRESH
            // wall-computed triggers by the dead island's frozen clock was
            // the 6gt QC trencher stall: delta = frozenL - advancingWall grew
            // without bound and collapsed every scheduled delay at exactly
            // the chunks the async-off toggle never rebased. Collapse the
            // container once, schedule unshifted — vanilla semantics.
            synchronized (((LevelTicksIslandAccess<?>) scheduler).fabrolia$tickLock()) {
                TickDomains.fabrolia$collapseStaleTag(rs.level, chunkKey, c, "schedule-sync");
            }
            return tick;
        }
        long tagNow = TickDomains.resolveNow(rs.level, c);
        if (tagNow == Long.MIN_VALUE) {
            return tick; // unresolvable tag already logged; do not shift blind
        }
        long ctxNow = rs.level.getGameTime();  // redirected = caller clock
        long delta = tagNow - ctxNow;
        if (delta == 0) {
            return tick;
        }
        return new net.minecraft.world.ticks.ScheduledTick<>(
                tick.type(), tick.pos(), tick.triggerTick() + delta,
                tick.priority(), tick.subTickOrder());
    }

    // ------------------------------------------------------------------
    // The routed batch — the ONE main-side execution point.
    // ------------------------------------------------------------------

    /** Server-global-state-only commands (no island state, no entities): the
     *  batch's main-thread serialization is sufficient — no island fence
     *  needed. Conservative allowlist: scoreboard ops on plain names (any
     *  selector routes to the fenced class). */
    static boolean isGlobalNoFence(String cmd) {
        if (cmd.indexOf('@') >= 0) return false;
        String t = cmd.startsWith("/") ? cmd.substring(1) : cmd;
        int sp = t.indexOf(' ');
        String root = sp < 0 ? t.trim() : t.substring(0, sp);
        return "scoreboard".equals(root);
    }

    private record RoutedCmd(Invocation inv, BooleanSupplier body,
                             CompletableFuture<Boolean> fut, boolean fenceless) {}

    private static final java.util.concurrent.ConcurrentLinkedQueue<RoutedCmd> ROUTED =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    public static boolean hasRoutedPending() {
        // DEFERRED_FENCED is main-confined; both batch points run on main.
        return !ROUTED.isEmpty() || !DEFERRED_FENCED.isEmpty();
    }

    /**
     * MAIN thread, with NO island claims held by the caller: execute every
     * queued routed command under one fence (parked waiters are fence-safe
     * via their flag), release the fence, THEN complete the waiter futures —
     * a resuming coordinator can never run inside a fenced window. Called at
     * the drive head each tick and by main-side claim spins stuck behind a
     * parked waiter (rebuild/save-all/player-save).
     */
    /** Consecutive bounded-fence timeouts — after 3, the next head batch goes
     *  unbounded so a perpetually-overloaded island can't starve commands. */
    private static int fenceStarve;

    public static void drainRoutedBatch() {
        drainRoutedBatch(fenceStarve >= 3 ? Long.MAX_VALUE : 2_000_000L);
    }

    /** True while the routed batch executes on main: the batch IS the
     *  serialization point (fenced entries hold the island fence, fenceless
     *  entries need none) — the per-dispatch CommandsMixin fence must not
     *  re-fence each command from inside it (that re-coupled every island to
     *  the slowest unit: mainFenced=2007 in the 2026-07-12 a8cmd rerun).
     *  Main-confined. */
    private static boolean inBatch;

    public static boolean inRoutedBatch() {
        return inBatch;
    }

    /** A8 Phase 2: enqueue an arbitrary fenced body (shard global-lane /
     *  redirect fallback) into the routed batch — same exactly-once and
     *  post-release completion discipline as command bodies. */
    static void enqueueFencedBody(BooleanSupplier body) {
        Invocation inv = new Invocation("shard_fallback", null, -1, 0, "<shard>");
        ROUTED.add(new RoutedCmd(inv, body, new CompletableFuture<>(), false));
        var sv = RegionScheduler.getServer();
        if (sv != null) {
            sv.execute(() -> drainRoutedBatch(2_000_000L));
        }
    }

    /** Fenced commands a bounded attempt could not run yet (main-only). */
    private static final java.util.ArrayDeque<RoutedCmd> DEFERRED_FENCED =
            new java.util.ArrayDeque<>();

    public static void drainRoutedBatch(long spinBudgetNanos) {
        offMainBelt("drainRoutedBatch");
        if (ROUTED.isEmpty() && DEFERRED_FENCED.isEmpty()) {
            return;
        }
        java.util.ArrayList<RoutedCmd> done = new java.util.ArrayList<>();
        java.util.ArrayList<RoutedCmd> fenced = new java.util.ArrayList<>(DEFERRED_FENCED);
        DEFERRED_FENCED.clear();
        // Partition the incoming queue; fenceless (server-global-state-only)
        // commands run right here — the batch's main-thread serialization is
        // their entire mutual-exclusion requirement, so a slow island can
        // never delay them.
        inBatch = true;
        try {
            RoutedCmd rc;
            while ((rc = ROUTED.poll()) != null) {
                if (rc.fenceless) {
                    execute(rc, done);
                } else {
                    fenced.add(rc);
                }
            }
        } finally {
            inBatch = false;
        }
        if (!fenced.isEmpty()) {
            long q0 = System.nanoTime();
            boolean took = quiesceAllExcept(null, spinBudgetNanos);
            QUIESCE_NANOS.add(System.nanoTime() - q0);
            if (took) {
                fenceStarve = 0;
                inBatch = true;
                try {
                    for (int i = 0, n = fenced.size(); i < n; i++) {
                        execute(fenced.get(i), done);
                    }
                } finally {
                    inBatch = false;
                    releaseAllExcept();
                }
            } else {
                // Bounded attempt lost to a long-running unit: keep order,
                // retry at the next batch point; repeated timeouts escalate
                // to an unbounded head fence so nothing starves.
                fenceStarve++;
                DEFERRED_FENCED.addAll(fenced);
            }
        }
        // Futures complete strictly AFTER any fence released — a resuming
        // coordinator can never observe a fenced window.
        for (int i = 0, n = done.size(); i < n; i++) {
            RoutedCmd d = done.get(i);
            d.fut.complete(d.inv.result != 0);
        }
    }

    private static void execute(RoutedCmd rc, java.util.List<RoutedCmd> done) {
        if (rc.inv.state != 0) {
            DUPLICATE_EXEC_ATTEMPTS.increment();
            done.add(rc);
            return;
        }
        rc.inv.state = 1;
        try {
            rc.inv.result = rc.body.getAsBoolean() ? 1 : 0;
        } catch (Throwable t) {
            LOGGER.error("[a8] routed command failed: {}", rc.inv.command, t);
            rc.inv.result = 0;
        }
        rc.inv.state = 2;
        done.add(rc);
    }

    // ------------------------------------------------------------------
    // Global fences (main context) — every level's islands, minus an
    // optional survivor whose coordinator is known-parked.
    // ------------------------------------------------------------------

    private static final java.util.ArrayList<IslandRegistry.PIsland> HELD =
            new java.util.ArrayList<>();

    /** Single-island claims MAIN currently holds (interaction handlers,
     *  entity-section/raid/fight/player-save wraps). Main-confined. A nested
     *  command fence treats these as already-quiescent instead of spinning on
     *  main's own claim (sign-click → command dispatch deadlock class). */
    private static final java.util.ArrayList<IslandRegistry.PIsland> MAIN_HELD =
            new java.util.ArrayList<>();

    /**
     * DEADLOCK FIX (carpet-bots dump 2026-07-13): a unit coordinator may hold
     * its island claim while PARKED on a MAIN-SERVICED chunk load (vault
     * comparator update / border hopper reaching into an unloaded neighbor —
     * any worker getChunk enqueues onto the level's mainThreadProcessor and
     * waits). Every MAIN-side claim spin must therefore keep servicing those
     * executors or fence-vs-chunk-load is an ABBA deadlock (main spun in the
     * tick-tag fence while worker-18 parked in awaitMainThreadResult holding
     * the claim main wanted). Main thread only.
     */
    public static void drainMainChunkServices() {
        var server = RegionScheduler.getServer();
        // TICKET-SAFETY (2026-07-13): pollTask runs runDistanceManagerUpdates
        // first — ticket ticking is server-thread-only (vanilla invariant;
        // VMP enforces it with a CME that kicks the triggering player). Any
        // future off-main caller of a claim spin gets a no-op, never a drain.
        if (server == null || !server.isSameThread()) return;
        for (net.minecraft.server.level.ServerLevel lvl : server.getAllLevels()) {
            while (lvl.getChunkSource().mainThreadProcessor.pollTask()) {
                // drain fully — exactly what main's own pump would do
            }
        }
    }

    /** Rate limiter for the off-main misuse belts (task #25): loud, but a
     *  regression that re-enters per-packet must not flood the log. */
    private static volatile long lastOffMainBeltNanos;

    private static void offMainBelt(String api) {
        var srv = RegionScheduler.getServer();
        if (srv == null || srv.isSameThread()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastOffMainBeltNanos > 1_000_000_000L) {
            lastOffMainBeltNanos = now;
            LOGGER.error("[async-islands] {} entered OFF-MAIN by '{}' — main-confined API "
                    + "(MAIN_HELD/HELD/routed-batch are main-thread structures); fix the caller",
                    api, Thread.currentThread().getName(),
                    new Throwable("off-main " + api + " call site"));
        }
    }

    /** Guarded reads for forensics of possibly-corrupt lists — a report must
     *  never throw. @return occurrence count, or -1 on a torn read. */
    private static int safeCount(java.util.ArrayList<IslandRegistry.PIsland> list,
                                 IslandRegistry.PIsland p) {
        try {
            int c = 0;
            for (int i = 0, n = list.size(); i < n; i++) {
                if (list.get(i) == p) c++;
            }
            return c;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Full retained-claim forensics block (task #25 operator spec): island
     *  identity + generation, the claim ledger (acquisition site, holder
     *  thread identity/aliveness/state, wall acquisition time, age), the
     *  reentrancy-ledger membership counts (a count above 1 is list
     *  corruption), and the holder's LIVE stack. The claim is binary by
     *  design (no depth counter): MAIN_HELD occurrence IS main's nesting
     *  record, HELD occurrence the fence's. */
    public static String claimForensics(IslandRegistry.PIsland p) {
        StringBuilder sb = new StringBuilder(640);
        sb.append("island#").append(p.id).append("/gen").append(p.gen)
          .append(" retired=").append(p.retired)
          .append(" waitingOnRoutedCommand=").append(p.waitingOnRoutedCommand)
          .append(" mainHeldOccurrences=").append(safeCount(MAIN_HELD, p))
          .append(" fenceHeldOccurrences=").append(safeCount(HELD, p))
          .append("\n    ledger: ").append(p.claimReport())
          .append("\n    holder live stack:").append(p.holderLiveStack(14));
        return sb.toString();
    }

    /** Blocking, batch-aware single-island claim from MAIN. Pair with
     *  {@link #mainRelease}.
     *
     *  <p>MAIN-CONFINED (task #25, the 2026-07-23 05:58/08:05 production
     *  wedges): MAIN_HELD and the routed-batch machinery reachable from this
     *  spin are main-thread structures. The historical off-main caller — the
     *  interaction wraps entered on the Netty event loop BEFORE vanilla's
     *  ensureRunningOnSameThread requeue — now passes through in the mixin;
     *  any new off-main entry is a bug and trips the belt log. */
    public static void mainClaim(IslandRegistry.PIsland owner) {
        offMainBelt("mainClaim");
        owner.claimWanted = true; // rate/sprint chaining + pacer yield to us
        long spinStart = System.nanoTime();
        int stuck = 0;
        try {
            while (!owner.unitClaim.compareAndSet(false, true)) {
                if (owner.waitingOnRoutedCommand) {
                    drainRoutedBatch();
                    continue;
                }
                if (!RegionScheduler.poolRunning()) {
                    // Task #36b: pool down — nothing can hold or release this
                    // claim anymore; proceeding claimless is safe (no
                    // concurrency exists) and spinning wedges shutdown.
                    LOGGER.warn("[async-islands] mainClaim met a claim with the worker pool "
                            + "stopped (island#{}) — proceeding claimless (shutdown leak)", owner.id);
                    return;
                }
                if (System.nanoTime() - spinStart > 5_000_000_000L) {
                    spinStart = System.nanoTime();
                    LOGGER.error("[async-islands] mainClaim stuck {}x5s — retained-claim forensics: {}",
                            ++stuck, claimForensics(owner));
                    if (owner.retired) {
                        // a retired identity can never be claimed by a unit
                        // again — nothing will ever release it for us; the
                        // CURRENT owner of these cells is a different object.
                        LOGGER.error("[async-islands] mainClaim target island#{} is RETIRED — abandoning "
                                + "stale-object claim (caller resolves the current owner)", owner.id);
                        return;
                    }
                }
                drainMainChunkServices();
                LockSupport.parkNanos(20_000L);
            }
        } finally {
            owner.claimWanted = false;
        }
        // ACQUIRED. Publication must be leak-proof: every caller arms its
        // release try/finally only AFTER this method returns, so any throw
        // between the winning CAS and that arming retained the claim forever
        // (the 08:05/05:58 all-workers-idle wedge class). noteClaim is pure
        // field writes; MAIN_HELD.add can only throw once the main-confinement
        // contract is already broken — release and rethrow, never leak.
        try {
            owner.noteClaim("mainClaim");
            MAIN_HELD.add(owner);
        } catch (Throwable t) {
            owner.clearClaim();
            owner.unitClaim.set(false);
            throw t;
        }
    }

    /** Does main already hold this island's claim (scoped/fence)? */
    public static boolean mainHolds(IslandRegistry.PIsland owner) {
        return MAIN_HELD.contains(owner);
    }

    public static void mainRelease(IslandRegistry.PIsland owner) {
        MAIN_HELD.remove(owner);
        owner.clearClaim();
        owner.unitClaim.set(false);
    }

    /** Reentrancy-aware main-side claim for short coherent reads (chunk-packet
     *  builds): no-op when main already holds this island (interaction wrap /
     *  save hooks — the state is already quiescent for us). @return true iff
     *  THIS call took the claim and the caller must {@link #mainRelease}. */
    public static boolean mainClaimScoped(IslandRegistry.PIsland owner) {
        if (MAIN_HELD.contains(owner)) {
            return false;
        }
        mainClaim(owner);
        return true;
    }
    private static IslandRegistry.PIsland heldExcept;

    /** Main thread only. @return true if this call took the fence (nested
     *  callers see mainHoldsAllClaims and skip). */
    public static boolean quiesceAllExcept(IslandRegistry.PIsland except) {
        return quiesceAllExcept(except, Long.MAX_VALUE);
    }

    /** Budgeted fence: if a RUNNING (non-waiting) unit outlasts the spin
     *  budget, release everything taken and report false — the caller retries
     *  at its next batch point instead of stalling main behind the slowest
     *  island (Phase 2's scoped fences quiesce only affected islands). */
    public static boolean quiesceAllExcept(IslandRegistry.PIsland except, long spinBudgetNanos) {
        offMainBelt("quiesceAllExcept");
        long deadline = spinBudgetNanos == Long.MAX_VALUE
                ? Long.MAX_VALUE : System.nanoTime() + spinBudgetNanos;
        boolean marked = false;
        for (RegionizedServer rs : RegionScheduler.allLevels()) {
            if (!rs.asyncActive || rs.mainHoldsAllClaims()) continue;
            rs.markMainHoldsAllClaims(true);
            marked = true;
            for (IslandRegistry.PIsland p : rs.islandRegistry.islands()) {
                if (p == except || MAIN_HELD.contains(p)) continue; // main-held = quiescent
                boolean claimed;
                p.claimWanted = true;
                long fSpin = System.nanoTime();
                int fStuck = 0;
                while (!(claimed = p.unitClaim.compareAndSet(false, true))) {
                    if (p.waitingOnRoutedCommand) {
                        // Parked waiter: exactly as quiescent as claimed, and
                        // it cannot resume before this fence releases (its
                        // future completes post-release by construction).
                        break;
                    }
                    if (!RegionScheduler.poolRunning()) {
                        // Task #36b: pool is DOWN — no unit can be running,
                        // a set claim is a shutdown leak; spinning on it
                        // wedges stopServer's task drain. Treat as quiescent.
                        LOGGER.warn("[async-islands] fence met a claim with the worker pool "
                                + "stopped (island#{}) — treating as quiescent (shutdown leak)", p.id);
                        break;
                    }
                    if (System.nanoTime() - fSpin > 5_000_000_000L) {
                        fSpin = System.nanoTime();
                        LOGGER.error("[async-islands] fence stuck {}x5s (shards={} L={}) — retained-claim forensics: {}",
                                ++fStuck, p.pendingCommandShards.size(), p.logicalTick,
                                claimForensics(p));
                        if (p.retired) {
                            // stale identity in the CURRENT-islands walk can
                            // only mean the registry changed under us —
                            // skip the dead object (its cells' new owner is
                            // claimed by this same pass).
                            LOGGER.error("[async-islands] fence saw RETIRED island#{} — skipping stale object", p.id);
                            break;
                        }
                    }
                    if (deadline != Long.MAX_VALUE && System.nanoTime() > deadline) {
                        releaseAllExcept();
                        return false;
                    }
                    drainMainChunkServices();
                    LockSupport.parkNanos(20_000L);
                }
                p.claimWanted = false;
                if (claimed) {
                    // Leak-proof publication (task #25): a throw between the
                    // winning CAS and HELD registration would strand the flag
                    // outside releaseAllExcept's walk forever.
                    try {
                        p.noteClaim("fence");
                        HELD.add(p);
                    } catch (Throwable t) {
                        p.clearClaim();
                        p.unitClaim.set(false);
                        throw t;
                    }
                }
            }
        }
        if (marked) heldExcept = except;
        return marked;
    }

    /** True when a main-context global fence window is already held (every
     *  async level marked mainHoldsAllClaims) — the reentrancy signal for the
     *  fence wraps: nested function/command dispatches inside a held window
     *  run inline, already quiescent. Distinguishes "already fenced" from
     *  quiesceAllExcept's false-on-contention (2026-07-17 fence redesign). */
    public static boolean fenceWindowHeld() {
        boolean any = false;
        for (RegionizedServer rs : RegionScheduler.allLevels()) {
            if (!rs.asyncActive) continue;
            if (!rs.mainHoldsAllClaims()) return false;
            any = true;
        }
        return any;
    }

    /** Operator dump for {@code regions debug claims} (task #25): global fence
     *  marks and reentrancy-ledger sizes, then full forensics for every island
     *  whose claim flag is currently SET. A healthy idle instant prints only
     *  transient unit/lane holds; a wedged server prints the leaked claim with
     *  its holder identity and liveness. */
    public static String claimsDump() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("MAIN_HELD.size=").append(safeSize(MAIN_HELD))
          .append(" fenceHELD.size=").append(safeSize(HELD));
        for (RegionizedServer rs : RegionScheduler.allLevels()) {
            sb.append('\n').append(rs.level.dimension().identifier())
              .append(" asyncActive=").append(rs.asyncActive)
              .append(" mainHoldsAllClaims=").append(rs.mainHoldsAllClaims());
            int held = 0;
            for (IslandRegistry.PIsland p : rs.islandRegistry.islands()) {
                if (p.unitClaim.get()) {
                    held++;
                    sb.append("\n  CLAIMED ").append(claimForensics(p));
                }
            }
            if (held == 0) {
                sb.append(" — no island claims held");
            }
        }
        return sb.toString();
    }

    private static int safeSize(java.util.ArrayList<?> l) {
        try {
            return l.size();
        } catch (Throwable t) {
            return -1;
        }
    }

    public static void releaseAllExcept() {
        for (int i = 0, n = HELD.size(); i < n; i++) {
            IslandRegistry.PIsland p = HELD.get(i);
            p.clearClaim();
            p.unitClaim.set(false);
        }
        HELD.clear();
        heldExcept = null;
        for (RegionizedServer rs : RegionScheduler.allLevels()) {
            if (rs.asyncActive) rs.markMainHoldsAllClaims(false);
        }
    }
}
