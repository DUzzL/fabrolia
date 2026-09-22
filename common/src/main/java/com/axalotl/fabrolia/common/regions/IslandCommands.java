package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * A8 — island command execution (docs/ASYNC-ISLANDS.md §12; vanilla redstone
 * and vanilla command semantics are the sole source of truth).
 *
 * <p>Model: every command execution is ONE logical invocation with
 * exactly-once semantics, with two execution shapes:
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
 * <p>The {@link Invocation} ledger admits exactly one execution per routed
 * command. Selector commands retain vanilla's complete command-block envelope.
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
    // Invocation identity and exactly-once routed execution ledger
    // ------------------------------------------------------------------

    private static final AtomicLong NEXT_INVOCATION_ID = new AtomicLong(1);

    /** Poll-only receipt shared by typed routed bodies; not a command invocation. */
    private static class RequestReceipt {
        public final long id = NEXT_INVOCATION_ID.getAndIncrement();
        public volatile byte state;
        public volatile int result;
        public volatile Throwable failure;
    }

    /** One logical command invocation. Identity is final; state is volatile.
     *  Exactly one execution is admitted per invocation. */
    public static final class Invocation extends RequestReceipt {
        public final String sourceKind;         // "command_block" | "main" | "function"
        public final BlockPos sourcePos;        // may be null (console)
        public final long sourceIslandId;       // -1 = global context
        public final long sourceLogicalTick;    // island L (or gameTime for main)
        public final String command;
        public final long orderingToken;        // island (id<<20|L) — deterministic per source

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
     *  invocation must stay zero. */
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
                + " dupExec=" + DUPLICATE_EXEC_ATTEMPTS.sum();
    }

    // ------------------------------------------------------------------
    // Classification — island-local proof
    // ------------------------------------------------------------------

    /** Roots whose effects are fully described by their coordinate arguments
     *  (no selectors, no global state). Everything else routes. */
    private static boolean isLocalRoot(String root) {
        return switch (root) {
            // clone's translated destination is not bounded by its three
            // coordinate arguments. Without a full footprint proof it routes.
            case "setblock", "fill", "summon", "data" -> true;
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
                    return tok.length >= 7 && fillBoxLocal(tok, src, rs, d);
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

    private static boolean fillBoxLocal(String[] tok, BlockPos src,
                                        RegionizedServer rs, EpochDomain d) {
        double x1 = coord(tok[1], src.getX());
        double y1 = coord(tok[2], src.getY());
        double z1 = coord(tok[3], src.getZ());
        double x2 = coord(tok[4], src.getX());
        double y2 = coord(tok[5], src.getY());
        double z2 = coord(tok[6], src.getZ());
        if (!Double.isFinite(x1) || !Double.isFinite(y1) || !Double.isFinite(z1)
                || !Double.isFinite(x2) || !Double.isFinite(y2) || !Double.isFinite(z2)) {
            return false;
        }
        return boxOwnedBy(rs, d,
                (int) Math.floor(Math.min(x1, x2)), (int) Math.floor(Math.min(z1, z2)),
                (int) Math.floor(Math.max(x1, x2)), (int) Math.floor(Math.max(z1, z2)));
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
     * chain parks until the vanilla boolean result is known,
     * preserving conditional-chain and comparator semantics exactly.
     */
    public static boolean routeCommandBlock(String command, ServerLevel level,
                                            BlockPos sourcePos,
                                            net.minecraft.commands.CommandSourceStack sourceStack,
                                            BooleanSupplier original) {
        RegionizedServer rs = RegionScheduler.publishedForLevel(level);
        if (rs == null || !requiresMainFence(rs)) {
            return RegionScheduler.callSuccessBooleanIfHealthy(original);
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
            return RegionScheduler.callSuccessBooleanIfHealthy(original);
        }
        if (isIslandLocal(command, sourcePos, rs, d)) {
            return RegionScheduler.callSuccessBooleanIfHealthy(() -> {
                LOCAL_INLINE.increment();
                return original.getAsBoolean();
            });
        }
        // Phase 3 (metadata-gated): a /function whose registered scope PROVES
        // island-locality (and needs no runtime validation) executes inline on
        // the owner — the whole compiled/interpreted body at island cadence on
        // island time. No metadata (Scope.UNKNOWN) or any dynamic-behavior
        // flag → the fenced routed path below (safe compatibility path).
        EpochDomain exactFunctionDomain = d;
        String fnId = localFunctionId(command);
        if (fnId != null && sourceStack != null) {
            net.minecraft.resources.Identifier ident =
                    net.minecraft.resources.Identifier.tryParse(fnId);
            if (ident != null) {
                // The callback holds FunctionAnalyzer's fair read lease across
                // both proof and exact vanilla execution. A reload writer
                // therefore cannot replace the function between those edges.
                Boolean inlineResult = FunctionAnalyzer.withExactCurrentAnalysis(
                        sourceStack.getServer().getFunctions(), ident,
                        (exactness, analysis) -> {
                            if (exactness != FunctionAnalyzer.Exactness.EXACT
                                    || !fnInlineEligible(
                                            analysis, sourcePos, rs, exactFunctionDomain)) {
                                return null;
                            }
                            return RegionScheduler.callSuccessBooleanIfHealthy(() -> {
                                LOCAL_FN_INLINE.increment();
                                return original.getAsBoolean();
                            });
                        });
                if (inlineResult != null) return inlineResult;
            }
        }
        // Keep the complete BaseCommandBlock.performCommand envelope on Main.
        // Sharding its command text here bypasses the enable-command-block
        // gate, success callback and lastExecution update, and substitutes a
        // command result for the boolean controlling unconditional chains.
        // Routed: one shard, exactly once, on the serialized main batch.
        Invocation inv = new Invocation("command_block", sourcePos, owner.id,
                owner.logicalTick, command);
        RoutedResult fut = new RoutedResult();
        RegionWorkerThread coordinator = sourceCoordinator();
        EpochDomain commandDomain = d;
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            RoutedRequest request;
            if (commandDomain.unitGameTime != 0) {
                if (coordinator.currentDomain != commandDomain) {
                    throw new IllegalStateException("command rendezvous lost its source domain");
                }
                var source = new SourceCommandRequest(
                        inv, original, fut, rs, owner, coordinator);
                source.validateSource();
                request = source;
            } else {
                request = new CommandRequest(
                        inv, original, fut, null, coordinator);
            }
            ROUTED.add(request);
            // Self-nudge: main drains its task queue while idling between slots,
            // so the batch runs within ~ms instead of at the next drive head.
            var sv = RegionScheduler.getServer();
            if (sv != null) {
                // The routed receipt is already visible.  Publishing its Main
                // successor is a separate success stage: a peer W selected after
                // ROUTED.add must cancel the queued receipt, not enqueue new work.
                RegionScheduler.runSuccessBodyIfHealthy(
                        () -> RegionScheduler.scheduleMainControlTask(
                                sv, IslandCommands::drainRoutedBatch));
            }
        });
        RegionScheduler.commitSuccessIfHealthy(ROUTED_GLOBAL::increment);
        // PARK-ONLY wait (no help-pumping: helping could claim this island's
        // own regions and race the fenced execution). The marker drives Main's
        // pumps; only an exact frame ACK lends the retained claim. Results
        // complete after fence release, never inside another body's window.
        owner.waitingOnRoutedCommand = true;
        long w0 = System.nanoTime();
        try {
            awaitRoutedResult(rs, owner, fut);
        } finally {
            owner.waitingOnRoutedCommand = false;
        }
        long routedWaitNanos = System.nanoTime() - w0;
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> ROUTE_WAIT_NANOS.add(routedWaitNanos));
        return exactCommandResult(fut, inv, "routed command");
    }

    /** Keep the original coordinator stack and claim until its exact Main body settles. */
    static void runPlayerLateOnMain(RegionizedServer runtime, IslandRegistry.PIsland island,
                                   net.minecraft.server.level.ServerPlayer player, Runnable body) {
        rendezvous(new PlayerLateRequest(runtime, island, player, sourceCoordinator(), body));
    }

    /** Preserve the original island BE ordinal and clock while its native body owns Main. */
    static void runBlockEntityTickOnMain(RegionizedServer runtime, IslandRegistry.PIsland island,
                                        Runnable body) {
        rendezvous(new BlockEntityRequest(runtime, island, sourceCoordinator(), body));
    }

    /** Retain the exact member claim and entity ordinal until its Main body settles. */
    static void runEntityTickOnMain(RegionizedServer runtime, Region member, Runnable body) {
        EpochDomain domain = member.domain;
        if (domain == null || domain.owner == null) {
            throw new IllegalStateException("entity rendezvous requires its dispatched island");
        }
        rendezvous(new EntityRequest(runtime, domain.owner, member, sourceCoordinator(), body));
    }

    /** Keep the native impact admission and all of its effects in one writer window. */
    public static void runPearlImpactOnMain(
            net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl source,
            Runnable producer) {
        java.util.Objects.requireNonNull(producer, "producer");
        RegionWorkerThread worker = sourceCoordinator();
        Region member = worker.currentRegion;
        if (member == null || member.domain == null || member.domain.owner == null
                || worker.currentDomain != member.domain
                || !(source.level() instanceof ServerLevel level) || member.level != level
                || !(source instanceof com.axalotl.fabrolia.common.ParallelProcessor.TickGuard guard)
                || !guard.fabrolia$tickLease().isOwnedByCurrentThread()) {
            throw new IllegalStateException("pearl impact lost its entered native entity stage");
        }
        // Published identity outlives async intent while an accepted unit finishes during stop.
        RegionizedServer runtime = RegionScheduler.publishedForLevel(level);
        if (runtime == null) {
            throw new IllegalStateException("pearl impact lost its source runtime");
        }
        runEntityTickOnMain(runtime, member, producer);
    }

    private static RegionWorkerThread sourceCoordinator() {
        if (!(Thread.currentThread() instanceof RegionWorkerThread worker)) {
            throw new IllegalStateException("unit rendezvous requires its owning coordinator");
        }
        return worker;
    }

    private static void rendezvous(SourceUnitRequest request) {
        RegionizedServer runtime = request.runtime;
        IslandRegistry.PIsland island = request.island;
        request.validateSource();
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            request.validateSource();
            ROUTED.add(request);
            RegionScheduler.scheduleMainControlTask(
                    RegionScheduler.getServer(), IslandCommands::drainRoutedBatch);
        });
        island.waitingOnRoutedCommand = true;
        try {
            awaitRoutedResult(runtime, island, request.fut);
            request.validateSource();
            RegionScheduler.throwWorkerFailureIfAny();
            request.fut.joinExact();
            RegionScheduler.throwWorkerFailureIfAny();
        } finally {
            island.waitingOnRoutedCommand = false;
        }
    }

    private static SourceRequest activeSourceUnit;

    /** A narrow typed TURN capability, never an arbitrary Main/native-service lease. */
    static boolean ownsPlayerLateOnMain(net.minecraft.server.level.ServerPlayer player,
                                       IslandRegistry.PIsland island) {
        return activeSourceUnit instanceof PlayerLateRequest request
                && request.player == player && request.island == island
                && hasSourceWriterWindow(request);
    }

    private static boolean hasSourceWriterWindow(SourceRequest request) {
        GlobalFenceFrame frame = ACTIVE_FENCE;
        return request.inv.state == 1
                && RegionalPlayerEntityTicksV2.isPhysicalServerThread(RegionScheduler.getServer())
                && !RegionScheduler.inNativeMainService()
                && frame != null && frame.owner == Thread.currentThread()
                && frame.phase == GlobalFencePhase.HELD
                && request.sourceIntact()
                && request.runtime.currentThreadHoldsAllClaims()
                && commandWaiterParkedForGlobalFence(request.runtime, request.island, frame);
    }

    /** Same-unit registration context, not an authority for arbitrary Main work. */
    static IslandRegistry.PIsland routedUnitSourceOnMain(RegionizedServer runtime) {
        SourceRequest request = activeSourceUnit;
        return request != null && request.runtime == runtime && hasSourceWriterWindow(request)
                ? request.island : null;
    }

    /** The original parked coordinator's same-drain queue, never a new capture. */
    static IslandBlockEvents.OrderedEvents
            routedUnitEventCaptureOnMain(RegionizedServer runtime) {
        return activeSourceUnit instanceof SourceCommandRequest request
                && request.runtime == runtime && hasSourceWriterWindow(request)
                ? request.eventCapture : null;
    }

    private static void awaitRoutedResult(RegionizedServer runtime,
                                          IslandRegistry.PIsland island, RoutedResult result) {
        for (;;) {
            // Once acknowledged, only the exact Main frame's completed cleanup
            // may let this stack unwind, including after terminal publication.
            if (parkCommandWaiterForGlobalFence(runtime, island, result)) continue;
            RegionScheduler.throwWorkerFailureIfAny();
            if (result.isDone()) return;
            if (!RegionScheduler.poolRunning()) {
                throw new IllegalStateException("routed body lost completion during worker stop");
            }
            LockSupport.parkNanos(50_000L);
        }
    }

    /**
     * Exact worker/Main rendezvous at a command-result wait boundary.  The
     * caller acknowledges the exact frame while parked. Once the acknowledgement
     * is visible, it runs no command/world body until Main clears the same
     * frame's intent; Main therefore has a real stable substitute for this
     * coordinator's already-held unit claim.
     */
    private static boolean parkCommandWaiterForGlobalFence(
            RegionizedServer rs, IslandRegistry.PIsland owner,
            RoutedResult result) {
        Object receipt = rs.globalFenceIntentReceipt();
        if (receipt == null) return false;
        // Observe intent before completion: Main publishes a later frame only
        // after completing the previous receipt. A completed wait must drain
        // its entered continuation, not lend the same source to another writer.
        if (result.isDone()) return false;
        if (!(receipt instanceof GlobalFenceFrame frame)) {
            throw new IllegalStateException("command waiter encountered an unknown fence receipt");
        }
        GlobalFenceParkAck ack = new GlobalFenceParkAck(frame);
        GlobalFenceParkAck previous = GLOBAL_FENCE_PARK_ACKS.putIfAbsent(owner, ack);
        if (previous != null) {
            publishAndPropagate(new IllegalStateException(
                    "command waiter already has a global-fence park acknowledgement"));
        }

        Throwable failure = null;
        try {
            while (ACTIVE_FENCE == receipt || rs.globalFenceIntentMatches(receipt)) {
                // W does not revoke the frame. Retain this exact ACK and source
                // claim until every physical claim and level intent is released.
                LockSupport.parkNanos(50_000L);
            }
            RegionScheduler.throwWorkerFailureIfAny();
        } catch (Throwable primary) {
            failure = publishFatalPreserving(primary);
        }
        try {
            if (!GLOBAL_FENCE_PARK_ACKS.remove(owner, ack)) {
                throw new IllegalStateException(
                        "command waiter lost its exact global-fence park acknowledgement");
            }
        } catch (Throwable cleanup) {
            failure = publishFatalPreserving(mergeFailure(failure, cleanup));
        }
        if (failure != null) {
            IslandCommands.<RuntimeException>rethrow(failure);
        }
        return true;
    }

    private static boolean commandWaiterParkedForGlobalFence(
            RegionizedServer rs, IslandRegistry.PIsland owner,
            GlobalFenceFrame frame) {
        GlobalFenceParkAck ack = GLOBAL_FENCE_PARK_ACKS.get(owner);
        return rs.globalFenceIntentMatches(frame)
                && ack != null && ack.frame == frame;
    }

    /** Logical ownership moves only after this selected command waiter ACKs HELD. */
    static boolean delegatesGameplayFrom(Thread worker, Thread main) {
        GlobalFenceFrame frame = ACTIVE_FENCE;
        if (frame == null || frame.owner != main || frame.phase != GlobalFencePhase.HELD
                || !(worker instanceof RegionWorkerThread regional)) return false;
        EpochDomain domain = regional.currentRegion != null
                ? regional.currentRegion.domain : regional.currentDomain;
        IslandRegistry.PIsland island = domain == null ? null : domain.owner;
        GlobalFenceParkAck ack = island == null ? null : GLOBAL_FENCE_PARK_ACKS.get(island);
        return island != null && island.claimHolder == worker
                && ack != null && ack.frame == frame;
    }

    /** Poll-only routed result. No completion callback or waiter is invoked from
     *  the scheduler lifecycle cut; the parked island already polls at 50us. */
    private static boolean exactCommandResult(RoutedResult result,
                                              Invocation invocation,
                                              String lane) {
        RegionScheduler.throwWorkerFailureIfAny();
        Throwable bodyFailure = invocation.failure;
        if (bodyFailure != null) {
            IslandCommands.<RuntimeException>rethrow(bodyFailure);
        }
        if (!result.isDone()) {
            RegionScheduler.throwWorkerFailureIfAny();
            throw new IllegalStateException(lane
                    + " lost its completion before the worker generation stopped");
        }
        boolean value = result.joinExact();
        RegionScheduler.throwWorkerFailureIfAny();
        return value;
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
        long ctxId = IslandClock.contextId();
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

    private static final class RoutedResult {
        private static final int PENDING = 0;
        private static final int SUCCEEDED = 1;
        private static final int FAILED = 2;

        private final AtomicInteger terminal = new AtomicInteger(PENDING);
        private volatile boolean value;
        private volatile Throwable failure;

        boolean isDone() {
            return terminal.get() != PENDING;
        }

        void completeSuccessInHealthyCut(boolean completedValue) {
            // Caller validated PENDING immediately before entering the global
            // cut. Failure terminalization can only start after W wins that
            // same cut, so these are fixed publication writes, not a callback.
            value = completedValue;
            terminal.set(SUCCEEDED);
        }

        boolean completeFailureAfterFatal(Throwable winner) {
            failure = winner;
            return terminal.compareAndSet(PENDING, FAILED);
        }

        boolean joinExact() {
            int observed = terminal.get();
            if (observed == FAILED) {
                IslandCommands.<RuntimeException>rethrow(failure);
            }
            if (observed != SUCCEEDED) {
                throw new IllegalStateException("routed result observed before terminal");
            }
            return value;
        }
    }

    private abstract static class RoutedRequest {
        final RequestReceipt inv;
        final RoutedResult fut;
        final Runnable trackedBody;
        // A pending synchronous source permits progress, never body authority.
        final RegionWorkerThread coordinator;

        RoutedRequest(RequestReceipt inv, RoutedResult fut,
                      Runnable trackedBody, RegionWorkerThread coordinator) {
            this.inv = inv;
            this.fut = fut;
            this.trackedBody = trackedBody;
            this.coordinator = coordinator;
        }

        abstract boolean runBody();
    }

    private static final class CommandRequest extends RoutedRequest {
        private final BooleanSupplier body;

        CommandRequest(Invocation inv, BooleanSupplier body, RoutedResult fut,
                       Runnable trackedBody, RegionWorkerThread coordinator) {
            super(inv, fut, trackedBody, coordinator);
            this.body = body;
        }

        @Override boolean runBody() {
            return body.getAsBoolean();
        }
    }

    private abstract static class SourceRequest extends RoutedRequest {
        final RegionizedServer runtime;
        final IslandRegistry.PIsland island;
        final RegionWorkerPool sourcePool;
        final EpochDomain domain;
        final long generation;
        final boolean handlingPhase;
        final IslandClock.MainUnitSource sourceClock;

        SourceRequest(RequestReceipt receipt, RoutedResult result,
                      RegionizedServer runtime, IslandRegistry.PIsland island,
                      RegionWorkerThread coordinator, boolean handlingPhase, boolean handlingTick) {
            super(receipt, result, null, coordinator);
            this.runtime = java.util.Objects.requireNonNull(runtime);
            this.island = java.util.Objects.requireNonNull(island);
            this.sourcePool = RegionWorkerPool.currentThreadCausalOwnerOrNull();
            this.domain = coordinator.currentDomain;
            this.generation = island.gen;
            this.handlingPhase = handlingPhase;
            this.sourceClock = new IslandClock.MainUnitSource(
                    runtime.level, domain == null ? 0 : domain.unitGameTime, island.id, handlingTick);
        }

        boolean sourceIntact() {
            return RegionScheduler.ownsRuntimeGeneration(runtime, sourcePool)
                    && !island.retired && island.gen == generation
                    && island.level == runtime.level && domain != null && domain == island.domain
                    && coordinator.currentDomain == domain && sourceStageIntact()
                    && island.unitClaim.get() && island.claimHolder == coordinator
                    && domain.owner == island && domain.handlingTickPhase == handlingPhase
                    && domain.unitGameTime == sourceClock.time() && sourceClock.time() != 0;
        }

        boolean sourceStageIntact() {
            return coordinator.currentRegion == null;
        }

        final boolean entityStageIntact(Region member, long epoch) {
            return coordinator.currentRegion == member && member.currentWorker() == coordinator
                    && member.domain == domain && domain.epoch == epoch
                    && member.groupedEpoch == epoch && member.entityEpoch < epoch;
        }

        final void validateSource() {
            if (!sourceIntact()) {
                throw new IllegalStateException("unit rendezvous lost its source unit identity");
            }
        }

        final void validateBodyEntry() {
            validateSource();
            if (!RegionalPlayerEntityTicksV2.isPhysicalServerThread(RegionScheduler.getServer())
                    || RegionScheduler.inNativeMainService()) {
                throw new IllegalStateException("unit source body entered outside its Main window");
            }
            if (activeSourceUnit != null) {
                throw new IllegalStateException("nested typed unit rendezvous");
            }
        }

        final void enterSourceBody() {
            validateBodyEntry();
            activeSourceUnit = this;
        }
    }

    /** Only these native continuations may extend an already selected native batch tail. */
    private abstract static class SourceUnitRequest extends SourceRequest implements Runnable {
        SourceUnitRequest(RegionizedServer runtime, IslandRegistry.PIsland island,
                          RegionWorkerThread coordinator) {
            super(new RequestReceipt(), new RoutedResult(), runtime, island, coordinator, false, false);
        }

        @Override boolean runBody() {
            validateBodyEntry();
            if (!hasSourceWriterWindow(this)) {
                throw new IllegalStateException("unit body entered without its exact Main fence/ACK");
            }
            RegionScheduler.runGlobalMainBody(RegionScheduler.getServer(), this::runClockedBody);
            validateSource();
            return true;
        }

        private void runClockedBody() {
            enterSourceBody();
            try {
                IslandClock.runMainUnitSource(sourceClock, this);
            } finally {
                activeSourceUnit = null;
            }
        }
    }

    private static final class SourceCommandRequest extends SourceRequest {
        final BooleanSupplier body;
        final Region member;
        final long epoch;
        final IslandBlockEvents.OrderedEvents eventCapture;

        SourceCommandRequest(Invocation inv, BooleanSupplier body, RoutedResult result,
                             RegionizedServer runtime, IslandRegistry.PIsland island,
                             RegionWorkerThread coordinator) {
            super(inv, result, runtime, island, coordinator,
                    coordinator.currentDomain.handlingTickPhase,
                    Boolean.TRUE.equals(IslandClock.workerHandlingTick(runtime.level)));
            this.body = body;
            this.member = coordinator.currentRegion;
            this.epoch = domain.epoch;
            if (IslandBlockEvents.CAPTURING.get()) {
                if (IslandBlockEvents.CAPTURING_ISLAND.get() != island) {
                    throw new IllegalStateException("command rendezvous crossed unit event captures");
                }
                eventCapture = IslandBlockEvents.UNIT_LOCAL.get();
            } else {
                eventCapture = null;
            }
        }

        @Override boolean sourceStageIntact() {
            return member != null ? entityStageIntact(member, epoch)
                    : coordinator.currentRegion == null && domain.epoch == epoch;
        }

        @Override boolean runBody() {
            validateBodyEntry();
            if (!hasSourceWriterWindow(this)) {
                throw new IllegalStateException("unit command entered without its exact Main fence/ACK");
            }
            boolean result = RegionScheduler.callGlobalMainBody(
                    RegionScheduler.getServer(), this::callClockedBody);
            validateSource();
            return result;
        }

        private boolean callClockedBody() {
            enterSourceBody();
            try {
                return IslandClock.callMainUnitSource(sourceClock, body);
            } finally {
                activeSourceUnit = null;
            }
        }
    }

    private static final class PlayerLateRequest extends SourceUnitRequest {
        final net.minecraft.server.level.ServerPlayer player;
        final Runnable body;

        PlayerLateRequest(RegionizedServer runtime, IslandRegistry.PIsland island,
                          net.minecraft.server.level.ServerPlayer player,
                          RegionWorkerThread coordinator, Runnable body) {
            super(runtime, island, coordinator);
            this.player = java.util.Objects.requireNonNull(player);
            this.body = java.util.Objects.requireNonNull(body);
        }

        @Override public void run() { body.run(); }
    }

    private static final class BlockEntityRequest extends SourceUnitRequest {
        final Runnable body;

        BlockEntityRequest(RegionizedServer runtime, IslandRegistry.PIsland island,
                           RegionWorkerThread coordinator, Runnable body) {
            super(runtime, island, coordinator);
            this.body = java.util.Objects.requireNonNull(body);
        }

        @Override public void run() { body.run(); }
    }

    private static final class EntityRequest extends SourceUnitRequest {
        final Region member;
        final long epoch;
        final Runnable body;

        EntityRequest(RegionizedServer runtime, IslandRegistry.PIsland island, Region member,
                      RegionWorkerThread coordinator, Runnable body) {
            super(runtime, island, coordinator);
            this.member = member;
            this.epoch = member.domain.epoch;
            this.body = java.util.Objects.requireNonNull(body);
        }

        @Override boolean sourceStageIntact() { return entityStageIntact(member, epoch); }
        @Override public void run() { body.run(); }
    }

    private static final java.util.concurrent.ConcurrentLinkedQueue<RoutedRequest> ROUTED =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    public static boolean hasRoutedPending() {
        return !ROUTED.isEmpty();
    }

    /** Main-only readiness, including the selected root's exact source restriction. */
    static boolean hasActionableRoutedProgress() {
        return routedProgressWindowOpen() && hasRoutedPending()
                && (RegionScheduler.allowsRoutedProgress(null)
                    || pendingSelectedSource(ROUTED) != null);
    }

    private static boolean routedProgressWindowOpen() {
        return !RegionScheduler.inNativeMainService() && ACTIVE_FENCE == null && !inBatch;
    }

    /**
     * Failed-generation terminal sweep. Every entry here was accepted but its
     * command body has not started. Detach it without execution and complete
     * the exact waiter exceptionally so no island remains parked during crash.
     */
    static Throwable cancelFailedGeneration(Throwable primary) {
        Throwable failure = primary;
        inBatch = false;
        RoutedRequest queued;
        while ((queued = ROUTED.poll()) != null) {
            failure = cancelQueued(queued, failure);
        }
        return failure;
    }

    static boolean failedGenerationQueuesEmpty() {
        return ROUTED.isEmpty();
    }

    private static Throwable cancelQueued(RoutedRequest queued, Throwable primary) {
        Throwable failure = publishFatalPreserving(primary);
        try {
            if (queued.inv.state != 0) {
                throw new IllegalStateException(
                        "failed-generation sweep found entered routed invocation "
                                + queued.inv.id + " state=" + queued.inv.state);
            }
        } catch (Throwable cleanup) {
            failure = mergeFailure(failure, cleanup);
        }
        return failAcceptedRouted(queued, failure);
    }

    /**
     * MAIN thread, with NO island claims held by the caller: execute every
     * queued routed body under one fence (waiters acknowledge the exact frame),
     * release the fence, THEN complete the waiter futures —
     * a resuming coordinator can never run inside a fenced window. Called at
     * the drive head each tick and by main-side claim spins stuck behind a
     * parked waiter (rebuild/save-all/player-save).
     */
    public static void drainRoutedBatch() {
        // The typed Main control task leaves fence ownership to this batch.
        // Admit its drain against W without wrapping the receipt completion
        // in a second, externally owned gameplay fence.
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            offMainBelt("drainRoutedBatch");
            // Polling native chunk callbacks or an outer fence is not this
            // queue's ownership window. Its ordinary control/progress pumps retry.
            if (!routedProgressWindowOpen()) return;
            if (!hasRoutedPending()) return;
            RoutedRequest selectedSource = null;
            if (!RegionScheduler.allowsRoutedProgress(null)) {
                selectedSource = pendingSelectedSource(ROUTED);
                if (selectedSource == null) return;
            }
            fabrolia$drainRoutedBatchEntered(selectedSource);
        });
    }

    private static RoutedRequest pendingSelectedSource(
            Iterable<? extends RoutedRequest> requests) {
        for (RoutedRequest request : requests) {
            if (request.coordinator != null && !request.fut.isDone()
                    && RegionScheduler.allowsRoutedProgress(request.coordinator)) {
                return request;
            }
        }
        return null;
    }

    /** True while the routed batch executes on Main under its shared writer
     *  fence. CommandsMixin must not reacquire or release that enclosing frame.
     *  Main-confined. */
    private static boolean inBatch;

    public static boolean inRoutedBatch() {
        return inBatch;
    }

    /** Publish an exact Main-fenced continuation; never run it inline in an outer claim. */
    static void enqueueFencedBody(Runnable body) {
        Invocation inv = new Invocation("island_task", null, -1, 0, "<retired island task>");
        RoutedResult receipt = new RoutedResult();
        try {
            RegionScheduler.executeTrackedExternal(tracked -> {
                ROUTED.add(new CommandRequest(inv, () -> {
                    tracked.run();
                    return true;
                }, receipt, tracked, null));
                var server = RegionScheduler.getServer();
                if (server != null) {
                    RegionScheduler.scheduleMainControlTask(server, () -> {
                        if (!receipt.isDone()) drainRoutedBatch();
                    });
                }
            }, body);
        } catch (Throwable failure) {
            publishAndPropagate(failure);
        }
    }


    private static void fabrolia$drainRoutedBatchEntered(RoutedRequest selectedSource) {
        // Retain every detached obligation until its exact post-release completion.
        java.util.ArrayList<RoutedRequest> done = new java.util.ArrayList<>();
        java.util.ArrayList<RoutedRequest> fenced = new java.util.ArrayList<>();
        RoutedRequest detached = null;
        Throwable batchFailure = null;
        try {
            inBatch = true;
            try {
                while ((detached = ROUTED.poll()) != null) {
                    // Publish ledger ownership before entering any body. If
                    // allocation fails, detached still names the exact receipt.
                    RoutedRequest owned = detached;
                    RegionScheduler.commitSuccessIfHealthy(
                            () -> fenced.add(owned));
                    detached = null;
                }
            } finally {
                inBatch = false;
            }
            if (!fenced.isEmpty() || selectedSource != null) {
                long q0 = System.nanoTime();
                boolean took = quiesceAllExcept(null);
                if (!took && (ACTIVE_FENCE != null
                        || requiresMainFence())) {
                    // An existing global frame is not borrowable here: completing
                    // this batch's futures would resume its parked coordinators
                    // inside that outer frame. The sole no-claims success shape is
                    // ON→OFF collapse after the last async level went inactive;
                    // the accepted waiter is still parked, but there is no
                    // autonomous island left for the exact body to race.
                    throw new IllegalStateException(
                            "unbounded routed-command fence returned without "
                                    + "ownership while async/global intent remained active");
                }
                inBatch = true;
                Throwable fencedFailure = null;
                try {
                    if (took) {
                        long quiesceNanos = System.nanoTime() - q0;
                        RegionScheduler.commitSuccessIfHealthy(
                                () -> QUIESCE_NANOS.add(quiesceNanos));
                    }
                    if (selectedSource != null) {
                        if (!took || selectedSource.fut.isDone()
                                || !delegatesGameplayFrom(
                                        selectedSource.coordinator, Thread.currentThread())) {
                            throw new IllegalStateException(
                                    "routed batch lacks its selected actor's writer ACK");
                        }
                    }
                    if (took && ROUTED.peek() instanceof SourceUnitRequest) {
                        // These coordinators published while acquisition was
                        // parking them and already ACK this writer. Preserve FIFO:
                        // extend only a native-only tail, bounded by issuance
                        // before any body executes.
                        long issuedBefore = NEXT_INVOCATION_ID.get();
                        boolean nativeOnly = true;
                        for (RoutedRequest queued : ROUTED) {
                            if (!(queued instanceof SourceUnitRequest)) {
                                nativeOnly = false;
                                break;
                            }
                            if (queued.inv.id >= issuedBefore) break;
                        }
                        if (nativeOnly) {
                            RoutedRequest head;
                            while ((head = ROUTED.peek()) instanceof SourceUnitRequest
                                    && head.inv.id < issuedBefore) {
                                detached = ROUTED.poll();
                                RoutedRequest owned = detached;
                                RegionScheduler.commitSuccessIfHealthy(
                                        () -> fenced.add(owned));
                                detached = null;
                            }
                        }
                    }
                    for (int i = 0, n = fenced.size(); i < n; i++) {
                        RoutedRequest command = fenced.get(i);
                        RegionScheduler.commitSuccessIfHealthy(
                                () -> done.add(command));
                        RegionScheduler.runSuccessBodyIfHealthy(
                                () -> execute(command));
                    }
                } catch (Throwable bodyFailure) {
                    fencedFailure = publishFatalPreserving(bodyFailure);
                }
                inBatch = false;
                if (took) {
                    try {
                        releaseAllExcept();
                    } catch (Throwable cleanupFailure) {
                        fencedFailure = publishFatalPreserving(
                                mergeFailure(fencedFailure, cleanupFailure));
                    }
                }
                if (fencedFailure != null) {
                    IslandCommands.<RuntimeException>rethrow(fencedFailure);
                }
            }
        } catch (Throwable failure) {
            batchFailure = publishFatalPreserving(failure);
        } finally {
            inBatch = false;
            // Fence release (when async remained live) is mandatory and already
            // complete. Normal DONE/future publication now shares the scheduler's
            // success-vs-W cut: if any peer selected W, none of these earlier
            // locally-normal commands may resume a waiter with success.
            if (batchFailure == null && !done.isEmpty()) {
                try {
                    for (RoutedRequest completed : done) completeHealthyRouted(completed);
                } catch (Throwable completionFailure) {
                    batchFailure = publishFatalPreserving(completionFailure);
                }
            }
            if (batchFailure != null) {
                if (detached != null && !detached.fut.isDone()) {
                    batchFailure = failAcceptedRouted(detached, batchFailure);
                }
                for (int i = 0, n = done.size(); i < n; i++) {
                    RoutedRequest completed = done.get(i);
                    if (!completed.fut.isDone()) {
                        batchFailure = failAcceptedRouted(completed, batchFailure);
                    }
                }
                for (int i = 0, n = fenced.size(); i < n; i++) {
                    RoutedRequest pending = fenced.get(i);
                    if (!pending.fut.isDone()) {
                        batchFailure = failAcceptedRouted(pending, batchFailure);
                    }
                }
                RoutedRequest pending;
                while ((pending = ROUTED.poll()) != null) {
                    batchFailure = failAcceptedRouted(pending, batchFailure);
                }
            }
        }
        if (batchFailure != null) {
            batchFailure = publishFatalPreserving(batchFailure);
            RegionScheduler.throwWorkerFailureIfAny();
            IslandCommands.<RuntimeException>rethrow(batchFailure);
        }
    }

    private static void completeHealthyRouted(RoutedRequest request) {
        if (request.inv.failure != null || request.inv.state != 1 || request.fut.isDone()) {
            throw new IllegalStateException(
                    "routed body lost its healthy completion state for invocation " + request.inv.id);
        }
        boolean result = request.inv.result != 0;
        RegionScheduler.commitSuccessIfHealthy(() -> {
            request.inv.state = 2;
            request.fut.completeSuccessInHealthyCut(result);
        });
    }

    private static void execute(RoutedRequest rc) {
        if (rc.inv.state != 0) {
            DUPLICATE_EXEC_ATTEMPTS.increment();
            throw new IllegalStateException(
                    "routed command invocation " + rc.inv.id
                            + " entered twice (state=" + rc.inv.state + ")");
        }
        rc.inv.state = 1;
        try {
            rc.inv.result = rc.runBody() ? 1 : 0;
        } catch (Throwable t) {
            Throwable failure = publishFatalPreserving(t);
            rc.inv.failure = failure;
            RegionScheduler.throwWorkerFailureIfAny();
            IslandCommands.<RuntimeException>rethrow(failure);
        }
    }

    /** W is already selected; only failure terminalization may wake this waiter. */
    private static Throwable failAcceptedRouted(RoutedRequest command, Throwable primary) {
        Throwable failure = publishFatalPreserving(primary);
        if (command.trackedBody instanceof RegionTaskFanIn.CancellableQueueEntry cancellable) {
            try {
                cancellable.cancelQueuedBeforeStartWithFailure(failure);
            } catch (Throwable cleanup) {
                failure = publishFatalPreserving(mergeFailure(failure, cleanup));
            }
        }
        try {
            command.inv.failure = failure;
            command.inv.state = 2;
            if (!command.fut.completeFailureAfterFatal(failure)) {
                throw new IllegalStateException(
                        "fatal routed waiter raced completion for invocation "
                                + command.inv.id);
            }
        } catch (Throwable notificationFailure) {
            failure = publishFatalPreserving(
                    mergeFailure(failure, notificationFailure));
        }
        return failure;
    }

    // ------------------------------------------------------------------
    // Global fences (main context) — every level's islands, minus an
    // optional survivor whose coordinator is known-parked.
    // ------------------------------------------------------------------

    private enum GlobalFencePhase {
        ACQUIRING,
        HELD,
        RELEASING
    }

    /** One exact global-fence acquisition and cleanup identity.  The frame is
     * published before acquisition intent, owns every side-effect ledger, and
     * reaches HELD only after every level has an acquired/stably-parked island
     * window.  A same-thread callback reached while ACQUIRING therefore cannot
     * mistake an intent marker for reentrant ownership. */
    private static final class GlobalFenceFrame {
        final Thread owner;
        final IslandRegistry.PIsland except;
        volatile GlobalFencePhase phase = GlobalFencePhase.ACQUIRING;
        final java.util.ArrayList<IslandRegistry.PIsland> claims =
                new java.util.ArrayList<>();
        final java.util.ArrayList<RegionizedServer> intentLevels =
                new java.util.ArrayList<>();
        final java.util.ArrayList<RegionizedServer> ownedLevels =
                new java.util.ArrayList<>();

        GlobalFenceFrame(Thread owner, IslandRegistry.PIsland except) {
            this.owner = owner;
            this.except = except;
        }
    }

    /** Volatile so a foreign callback can never borrow Main's frame.  Frame
     * lists themselves remain confined to the exact owner. */
    private static volatile GlobalFenceFrame ACTIVE_FENCE;

    /** Worker-published rendezvous acknowledgements for command waiters. A
     *  waiter writes the exact active frame while parked, then performs no
     *  simulation work until that frame clears the level intent. Main may
     *  treat an otherwise-held unit claim as stable only through this exact
     *  token, never through the broader waitingOnRoutedCommand diagnostic bit. */
    private record GlobalFenceParkAck(GlobalFenceFrame frame) {}

    private static final java.util.concurrent.ConcurrentHashMap<
            IslandRegistry.PIsland, GlobalFenceParkAck> GLOBAL_FENCE_PARK_ACKS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Single-island claims MAIN currently holds (interaction handlers,
     *  entity-section/raid/fight/player-save wraps). Main-confined. A nested
     *  command fence treats these as already-quiescent instead of spinning on
     *  main's own claim (sign-click → command dispatch deadlock class). */
    private static final java.util.ArrayList<IslandRegistry.PIsland> MAIN_HELD =
            new java.util.ArrayList<>();

    /**
     * Thread-owned capability for the two vanilla scheduled-function callback
     * types at their exact {@code TimerQueue.tick -> TimerCallback.handle}
     * callsite. The queue has already removed the due entry and holds no caller
     * monitor there, so the current function may wait for its exact global
     * fence instead of treating ordinary island work longer than the bounded
     * nested-call budget as a fatal ownership miss.
     *
     * <p>The previous value is restored in {@code finally}, so a nested call or
     * a throwing vanilla body cannot leak the classification into a later
     * callback. The body is neither caught nor replayed here. Thread ownership
     * prevents an unrelated publisher from borrowing Main's capability.</p>
     */
    private static final ThreadLocal<Boolean> MAIN_SCHEDULED_FUNCTION_CALLBACK =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static void runMainScheduledFunctionCallback(Runnable body) {
        var server = RegionScheduler.getServer();
        if (server != null
                && !RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)) {
            publishAndPropagate(new IllegalStateException(
                    "scheduled function timer callback entered off physical Main"));
            return;
        }
        boolean previous = MAIN_SCHEDULED_FUNCTION_CALLBACK.get();
        MAIN_SCHEDULED_FUNCTION_CALLBACK.set(Boolean.TRUE);
        try {
            body.run();
        } finally {
            if (previous) {
                MAIN_SCHEDULED_FUNCTION_CALLBACK.set(Boolean.TRUE);
            } else {
                MAIN_SCHEDULED_FUNCTION_CALLBACK.remove();
            }
        }
    }

    public static boolean inMainScheduledFunctionCallback() {
        return MAIN_SCHEDULED_FUNCTION_CALLBACK.get();
    }

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
        if (!RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)) return;
        for (net.minecraft.server.level.ServerLevel lvl : server.getAllLevels()) {
            // One native polling opportunity per level and claim attempt.
            // Distance-manager work may consume it before a FIFO head is run.
            // A waiting worker retains its claim and requests further passes
            // until its exact future completes. Do not drain a replenished
            // C2ME backlog to exhaustion or move, retry or drop any callback.
            RegionScheduler.runNativeMainServiceBody(() ->
                    RegionScheduler.callSuccessBooleanIfHealthy(
                            () -> lvl.getChunkSource().pollTask()));
        }
    }

    /** Rate limiter for the off-main misuse belts (task #25): loud, but a
     *  regression that re-enters per-packet must not flood the log. */
    private static volatile long lastOffMainBeltNanos;

    private static void offMainBelt(String api) {
        var srv = RegionScheduler.getServer();
        if (srv == null || RegionalPlayerEntityTicksV2.isPhysicalServerThread(srv)) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastOffMainBeltNanos > 1_000_000_000L) {
            lastOffMainBeltNanos = now;
            LOGGER.error("[async-islands] {} entered OFF-MAIN by '{}' — main-confined API "
                    + "(MAIN_HELD/global-fence/routed-batch are main-thread structures); fix the caller",
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

    private static int safeGlobalFenceCount(IslandRegistry.PIsland island) {
        GlobalFenceFrame frame = ACTIVE_FENCE;
        return frame == null ? 0 : safeCount(frame.claims, island);
    }

    /** Full retained-claim forensics block (task #25 operator spec): island
     *  identity + generation, the claim ledger (acquisition site, holder
     *  thread identity/aliveness/state, wall acquisition time, age), the
     *  reentrancy-ledger membership counts (a count above 1 is list
     *  corruption), and the holder's LIVE stack. The claim is binary by
     *  design (no depth counter): MAIN_HELD occurrence IS main's nesting
     *  record, global-frame occurrence the fence's. */
    public static String claimForensics(IslandRegistry.PIsland p) {
        StringBuilder sb = new StringBuilder(640);
        sb.append("island#").append(p.id).append("/gen").append(p.gen)
          .append(" retired=").append(p.retired)
          .append(" waitingOnRoutedCommand=").append(p.waitingOnRoutedCommand)
          .append(" mainHeldOccurrences=").append(safeCount(MAIN_HELD, p))
          .append(" fenceHeldOccurrences=").append(safeGlobalFenceCount(p))
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
        boolean priorClaimWanted = owner.claimWanted;
        owner.claimWanted = true; // rate/sprint chaining + pacer yield to us
        long spinStart = 0L;
        boolean contended = false;
        int stuck = 0;
        boolean acquired = false;
        RegionizedServer.AllIslandClaimIntents admission = null;
        Throwable failure = null;
        try {
            while (!owner.unitClaim.compareAndSet(false, true)) {
                if (!contended) {
                    spinStart = System.nanoTime();
                    contended = true;
                }
                if (owner.waitingOnRoutedCommand && hasRoutedPending()) {
                    // The target's entered unit may need several Main
                    // rendezvous. Do not reopen unrelated pacing/chaining at
                    // every released frame and replenish the work it must
                    // rendezvous with. Already queued/entered units still
                    // finish; interest is neither a claim nor body authority.
                    if (admission == null) {
                        admission = new RegionizedServer.AllIslandClaimIntents(16);
                    }
                    // Extend only BETWEEN child frames: capturing an inner
                    // frame's temporary true would retain an orphaned intent.
                    for (RegionizedServer rs : RegionScheduler.publishedLevels()) {
                        if (requiresMainFence(rs)) {
                            admission.include(rs.islandRegistry.identitySnapshot());
                        }
                    }
                    drainRoutedBatch();
                    continue;
                }
                if (!RegionScheduler.poolRunning()) {
                    // Pool liveness is not ownership. A retained flag may still
                    // be in mandatory cleanup or belong to a foreign serializer;
                    // returning void here makes every caller enter its body as if
                    // this method had acquired and registered the claim.
                    RegionScheduler.throwWorkerFailureIfAny();
                    publishAndPropagate(new IllegalStateException(
                            "mainClaim found a retained island claim after the worker "
                                    + "pool stopped: " + claimForensics(owner)));
                }
                if (System.nanoTime() - spinStart > 5_000_000_000L) {
                    spinStart = System.nanoTime();
                    LOGGER.error("[async-islands] mainClaim stuck {}x5s — retained-claim forensics: {}",
                            ++stuck, claimForensics(owner));
                    if (owner.retired) {
                        throw new IllegalStateException(
                                "mainClaim targeted retired island#" + owner.id
                                        + "/gen" + owner.gen + ": "
                                        + claimForensics(owner));
                    }
                }
                drainMainChunkServices();
                LockSupport.parkNanos(20_000L);
            }
            acquired = true;
        } catch (Throwable thrown) {
            failure = publishFatalPreserving(thrown);
        } finally {
            failure = RegionizedServer.clearAllIslandClaimIntents(admission, failure);
            // The outer snapshot captured the target's temporary true.
            // Restore it before restoring this target wait's original value.
            owner.claimWanted = priorClaimWanted;
        }
        if (failure != null) {
            if (acquired) failure = releaseMainClaim(owner, failure);
            IslandCommands.<RuntimeException>rethrow(publishFatalPreserving(failure));
            return;
        }
        // ACQUIRED. Publication must be leak-proof: every caller arms its
        // release try/finally only AFTER this method returns, so any throw
        // between the winning CAS and that arming retained the claim forever
        // (the 08:05/05:58 all-workers-idle wedge class). noteClaim is pure
        // field writes; MAIN_HELD.add can only throw once the main-confinement
        // contract is already broken — release and rethrow, never leak.
        try {
            RegionScheduler.commitSuccessIfHealthy(() -> {
                owner.noteClaim("mainClaim");
                MAIN_HELD.add(owner);
            });
        } catch (Throwable t) {
            failure = publishFatalPreserving(t);
            failure = releaseMainClaim(owner, failure);
            IslandCommands.<RuntimeException>rethrow(
                    publishFatalPreserving(failure));
        }
    }

    /**
     * Acquire one new Main-scoped claim without pumping or waiting. Callers
     * which can inherit a scope check {@link #mainHolds} first. A false result
     * owns nothing and must leave the complete callback body unentered.
     */
    public static boolean tryMainClaim(IslandRegistry.PIsland owner) {
        var server = RegionScheduler.getServer();
        if (server != null && !RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)) {
            throw new IllegalStateException("tryMainClaim entered off physical Main");
        }
        if (owner.retired || mainHolds(owner)
                || !owner.unitClaim.compareAndSet(false, true)) {
            return false;
        }
        try {
            RegionScheduler.commitSuccessIfHealthy(() -> {
                owner.noteClaim("tryMainClaim");
                MAIN_HELD.add(owner);
            });
        } catch (Throwable thrown) {
            Throwable failure = publishFatalPreserving(thrown);
            failure = releaseMainClaim(owner, failure);
            IslandCommands.<RuntimeException>rethrow(publishFatalPreserving(failure));
        }
        return true;
    }

    /** Exact Main-owned claim; a parked native-service requester lends no authority. */
    public static boolean mainHolds(IslandRegistry.PIsland owner) {
        return owner != null && owner.claimHolder == Thread.currentThread()
                && MAIN_HELD.contains(owner);
    }

    public static void mainRelease(IslandRegistry.PIsland owner) {
        Throwable failure = releaseMainClaim(owner, null);
        if (failure != null) {
            IslandCommands.<RuntimeException>rethrow(failure);
        }
    }

    /** Reentrancy-aware main-side claim for short coherent reads (chunk-packet
     *  builds): no-op when main already holds this island (interaction wrap /
     *  save hooks — the state is already quiescent for us). @return true iff
     *  THIS call took the claim and the caller must {@link #mainRelease}. */
    public static boolean mainClaimScoped(IslandRegistry.PIsland owner) {
        var server = RegionScheduler.getServer();
        if (server != null && !RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)) {
            throw new IllegalStateException("mainClaimScoped entered off physical Main");
        }
        if (mainHolds(owner)) {
            return false;
        }
        RegionizedServer runtime = owner.level == null
                ? null : RegionScheduler.publishedForLevel(owner.level);
        if (runtime != null && runtime.currentThreadHoldsLocalAllClaims()) {
            // A target-level quiesce remains the writer even while a global
            // read/acquisition frame covers the other, non-quiesced levels.
            if (owner.retired || !runtime.islandRegistry.identitySnapshot().contains(owner)) {
                throw new IllegalStateException(
                        "scoped Main claim is outside the local writer's live island generation");
            }
            return false;
        }
        GlobalFenceFrame frame = ACTIVE_FENCE;
        if (frame != null) {
            // Physical frame claims and parked writer ACKs are not MAIN_HELD
            // entries. Only the completed writer receipt can lend this scope;
            // waiting on an incomplete frame would self-deadlock.
            if (frame.owner != Thread.currentThread()
                    || frame.phase != GlobalFencePhase.HELD) {
                throw new IllegalStateException(
                        "scoped Main claim cannot inherit an incomplete or foreign global fence");
            }
            if (owner.retired || runtime == null
                    || !runtime.currentThreadHoldsAllClaims()
                    || !runtime.islandRegistry.identitySnapshot().contains(owner)) {
                throw new IllegalStateException(
                        "scoped Main claim is outside the current writer's live island generation");
            }
            return false;
        }
        mainClaim(owner);
        return true;
    }

    /** OFF intent cannot revoke ownership of a still-entered autonomous unit. */
    public static boolean requiresMainFence() {
        for (RegionizedServer runtime : RegionScheduler.publishedLevels()) {
            if (requiresMainFence(runtime)) return true;
        }
        return false;
    }

    public static boolean requiresMainFence(RegionizedServer runtime) {
        if (runtime.asyncActive) return true;
        var committedOwners = runtime.arrivalOwnerSnapshot();
        if (committedOwners != null && !committedOwners.isEmpty()) return true;
        for (IslandRegistry.PIsland island : runtime.islandRegistry.identitySnapshot()) {
            if (island.unitClaim.get()) return true;
        }
        return false;
    }
    /** Main thread only. @return true if this call took the fence (nested
     *  callers see the exact HELD owner receipt and skip). */
    public static boolean quiesceAllExcept(IslandRegistry.PIsland except) {
        return quiesceAllExcept(except, Long.MAX_VALUE);
    }

    /** Budgeted fence: if a RUNNING (non-waiting) unit outlasts the spin
     *  budget, release everything taken and report a pre-body ownership miss.
     *  Current exact command/function callers fail-stop on that receipt; they
     *  never acknowledge, defer, replay, or skip the vanilla invocation. */
    public static boolean quiesceAllExcept(IslandRegistry.PIsland except, long spinBudgetNanos) {
        return acquireGlobalFrame(except, spinBudgetNanos);
    }

    private static boolean acquireGlobalFrame(
            IslandRegistry.PIsland except, long spinBudgetNanos) {
        offMainBelt("quiesceAllExcept");
        long deadline = spinBudgetNanos == Long.MAX_VALUE
                ? Long.MAX_VALUE : System.nanoTime() + spinBudgetNanos;
        GlobalFenceFrame frame = null;
        try {
            if (except != null) {
                throw new IllegalArgumentException(
                        "global all-claims fence does not support an excluded island");
            }
            GlobalFenceFrame existing = ACTIVE_FENCE;
            if (existing != null) {
                if (existing.owner == Thread.currentThread()
                        && existing.phase == GlobalFencePhase.HELD && existing.except == except) {
                    return false;
                }
                throw new IllegalStateException(
                        "global fence reentered without an exact HELD owner receipt"
                                + " (phase=" + existing.phase + ")");
            }
            RegionScheduler.throwWorkerFailureIfAny();
            java.util.ArrayList<RegionizedServer> levels =
                    new java.util.ArrayList<>(RegionScheduler.publishedLevels());
            boolean needsClaims = false;
            for (RegionizedServer rs : levels) {
                if (!requiresMainFence(rs)) continue;
                if (rs.mainHoldsAllClaims()) {
                    if (!rs.currentThreadHoldsLocalAllClaims()) {
                        throw new IllegalStateException(
                                "global fence attempted to borrow a foreign/orphaned "
                                        + "all-claims owner for " + rs.level.dimension().identifier());
                    }
                } else {
                    needsClaims = true;
                }
            }
            if (!needsClaims) return false;
            if (RegionScheduler.inNativeMainService()) {
                throw new IllegalStateException(
                        "native Main chunk service cannot acquire a fresh global fence");
            }

            frame = new GlobalFenceFrame(Thread.currentThread(), except);
            ACTIVE_FENCE = frame;
            for (RegionizedServer rs : levels) {
                if (!requiresMainFence(rs) || rs.mainHoldsAllClaims()) continue;
                frame.intentLevels.add(rs);
                rs.installGlobalFenceIntent(frame);
            }
            if (!acquireGlobalClaims(frame, deadline)) {
                Throwable cleanup = releaseGlobalFenceFrame(frame, null);
                if (cleanup != null) IslandCommands.<RuntimeException>rethrow(cleanup);
                RegionScheduler.commitSuccessIfHealthy(() -> {});
                return false;
            }
            for (RegionizedServer rs : frame.intentLevels) {
                frame.ownedLevels.add(rs);
                rs.publishGlobalAllClaimsOwnership(frame);
            }
            GlobalFenceFrame acquiredFrame = frame;
            RegionScheduler.commitSuccessIfHealthy(
                    () -> acquiredFrame.phase = GlobalFencePhase.HELD);
            return true;
        } catch (Throwable primary) {
            Throwable failure = frame == null
                    ? publishFatalPreserving(primary) : releaseGlobalFenceFrame(frame, primary);
            IslandCommands.<RuntimeException>rethrow(publishFatalPreserving(failure));
            return false;
        }
    }

    private static boolean acquireGlobalClaims(GlobalFenceFrame frame, long deadline) {
        for (RegionizedServer rs : frame.intentLevels) {
            for (IslandRegistry.PIsland island : rs.islandRegistry.identitySnapshot()) {
                if (mainHolds(island)) continue;
                if (island.retired) {
                    throw new IllegalStateException(
                            "global fence enumerated retired island#" + island.id
                                    + "/gen" + island.gen + ": " + claimForensics(island));
                }
                // Every participating runtime already exposes this frame's global
                // admission intent. An uncontended real CAS needs no temporary
                // per-island interest and must not disturb an older one.
                boolean claimed = island.unitClaim.compareAndSet(false, true);
                if (!claimed) {
                    boolean priorClaimWanted = island.claimWanted;
                    island.claimWanted = true;
                    try {
                        long spinStarted = System.nanoTime();
                        int stuck = 0;
                        while (!(claimed = island.unitClaim.compareAndSet(false, true))) {
                            GlobalFenceParkAck ack = GLOBAL_FENCE_PARK_ACKS.get(island);
                            if (rs.globalFenceIntentMatches(frame) && ack != null && ack.frame == frame) {
                                break;
                            }
                            if (!RegionScheduler.poolRunning()) {
                                RegionScheduler.throwWorkerFailureIfAny();
                                throw new IllegalStateException(
                                        "global fence found a retained island claim after the worker pool stopped: "
                                                + claimForensics(island));
                            }
                            if (System.nanoTime() - spinStarted > 5_000_000_000L) {
                                spinStarted = System.nanoTime();
                                LOGGER.error("[async-islands] fence stuck {}x5s (L={}) — retained-claim forensics: {}",
                                        ++stuck, island.logicalTick, claimForensics(island));
                                if (island.retired) {
                                    throw new IllegalStateException(
                                            "global fence waited on retired island#" + island.id
                                                    + "/gen" + island.gen + ": " + claimForensics(island));
                                }
                            }
                            if (deadline != Long.MAX_VALUE && System.nanoTime() > deadline) return false;
                            drainMainChunkServices();
                            RegionScheduler.throwWorkerFailureIfAny();
                            if ((claimed = island.unitClaim.compareAndSet(false, true))) break;
                            LockSupport.parkNanos(20_000L);
                        }
                    } finally {
                        island.claimWanted = priorClaimWanted;
                    }
                }
                if (claimed) {
                    try {
                        island.noteClaim("fence");
                        frame.claims.add(island);
                    } catch (Throwable publicationFailure) {
                        Throwable failure = publishFatalPreserving(publicationFailure);
                        try {
                            island.clearClaim();
                        } catch (Throwable cleanup) {
                            failure = publishFatalPreserving(mergeFailure(failure, cleanup));
                        }
                        try {
                            island.unitClaim.set(false);
                        } catch (Throwable cleanup) {
                            failure = publishFatalPreserving(mergeFailure(failure, cleanup));
                        }
                        IslandCommands.<RuntimeException>rethrow(failure);
                    }
                }
            }
        }
        return true;
    }

    /**
     * True while a published global frame is only intent, not ownership.
     * Main callbacks which still own their queue entry must defer rather than
     * enter a command/function body against this incomplete capability.
     */
    public static boolean globalFenceTransitionInProgress() {
        GlobalFenceFrame frame = ACTIVE_FENCE;
        return frame != null && frame.phase != GlobalFencePhase.HELD;
    }

    /** Exact-thread reentrancy signal. Acquisition intent and a foreign HELD
     *  frame are both false. A set of outer per-level quiesces is accepted only
     *  when every async level is locally owned by this exact thread. */
    public static boolean fenceWindowHeld() {
        GlobalFenceFrame frame = ACTIVE_FENCE;
        if (frame != null) {
            return frame.owner == Thread.currentThread()
                    && frame.phase == GlobalFencePhase.HELD;
        }
        boolean any = false;
        for (RegionizedServer rs : RegionScheduler.publishedLevels()) {
            if (!requiresMainFence(rs)) continue;
            if (!rs.currentThreadHoldsLocalAllClaims()) return false;
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
        GlobalFenceFrame frame = ACTIVE_FENCE;
        sb.append("MAIN_HELD.size=").append(safeSize(MAIN_HELD));
        if (frame == null) {
            sb.append(" globalFence=IDLE");
        } else {
            sb.append(" globalFence=").append(frame.phase)
              .append(" owner=").append(frame.owner.getName())
              .append(" ownerId=").append(frame.owner.threadId())
              .append(" claims=").append(safeSize(frame.claims))
              .append(" intentLevels=").append(safeSize(frame.intentLevels))
              .append(" ownedLevels=").append(safeSize(frame.ownedLevels));
        }
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

    private static Throwable mergeFailure(Throwable primary, Throwable secondary) {
        if (primary == null) return secondary;
        if (secondary != null && secondary != primary) {
            try {
                primary.addSuppressed(secondary);
            } catch (Throwable ignored) {
                // Already fail-stop; diagnostic suppression cannot replace primary.
            }
        }
        return primary;
    }

    /** Publish immediately so no waiter can run a successor before Main sees
     * the first failure. Publication cleanup may be suppressed, never replace
     * the primary which made this generation divergent. */
    private static Throwable publishFatalPreserving(Throwable primary) {
        return RegionScheduler.publishFatalAndSelectWinner(primary);
    }

    private static Throwable releaseMainClaim(IslandRegistry.PIsland owner,
                                               Throwable primary) {
        Throwable failure = primary;
        try {
            MAIN_HELD.remove(owner);
        } catch (Throwable cleanup) {
            failure = publishFatalPreserving(mergeFailure(failure, cleanup));
        }
        try {
            owner.clearClaim();
        } catch (Throwable cleanup) {
            failure = publishFatalPreserving(mergeFailure(failure, cleanup));
        }
        try {
            owner.unitClaim.set(false);
        } catch (Throwable cleanup) {
            failure = publishFatalPreserving(mergeFailure(failure, cleanup));
        }
        return failure;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T) failure;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException rethrowAs(
            Throwable failure) throws T {
        throw (T) failure;
    }

    /** Exhaustively release one exact frame.  Skip-claim authority is revoked
     * before the first physical claim; acquisition intent remains until every
     * claim is free, preventing a new autonomous successor in the release gap. */
    private static Throwable releaseGlobalFenceFrame(GlobalFenceFrame frame,
                                                       Throwable primary) {
        Throwable failure = primary != null && RegionScheduler.isActive()
                ? RegionScheduler.publishFatalAndSelectWinner(primary)
                : primary;
        if (frame == null) return failure;
        if (frame.owner != Thread.currentThread()) {
            return publishFatalPreserving(mergeFailure(failure,
                    new IllegalStateException(
                            "global fence cleanup entered on a non-owner thread")));
        }

        GlobalFenceFrame active = ACTIVE_FENCE;
        if (active != frame) {
            // A bounded timeout may finish cleanup, then have its post-cleanup
            // success cut observe peer W and enter the outer catch. That second
            // pass has no remaining resource to release.
            if (frame.phase == GlobalFencePhase.RELEASING
                    && frame.claims.isEmpty()
                    && frame.intentLevels.isEmpty()
                    && frame.ownedLevels.isEmpty()) {
                return failure;
            }
            return publishFatalPreserving(mergeFailure(failure,
                    new IllegalStateException(
                            "global fence cleanup lost its active frame identity")));
        }

        frame.phase = GlobalFencePhase.RELEASING;

        int ownedCount = frame.ownedLevels.size();
        for (int i = 0; i < ownedCount; i++) {
            RegionizedServer runtime;
            try {
                runtime = frame.ownedLevels.get(i);
            } catch (Throwable cleanup) {
                failure = publishFatalPreserving(
                        mergeFailure(failure, cleanup));
                continue;
            }
            try {
                runtime.clearGlobalAllClaimsOwnership(frame);
            } catch (Throwable cleanup) {
                failure = publishFatalPreserving(
                        mergeFailure(failure, cleanup));
            }
        }
        try {
            frame.ownedLevels.clear();
        } catch (Throwable cleanup) {
            failure = publishFatalPreserving(mergeFailure(failure, cleanup));
        }

        int claimCount = frame.claims.size();
        for (int i = 0; i < claimCount; i++) {
            IslandRegistry.PIsland island;
            try {
                island = frame.claims.get(i);
            } catch (Throwable cleanup) {
                failure = publishFatalPreserving(
                        mergeFailure(failure, cleanup));
                continue;
            }
            try {
                island.clearClaim();
            } catch (Throwable cleanup) {
                failure = publishFatalPreserving(
                        mergeFailure(failure, cleanup));
            }
            try {
                island.unitClaim.set(false);
            } catch (Throwable cleanup) {
                failure = publishFatalPreserving(
                        mergeFailure(failure, cleanup));
            }
        }
        try {
            frame.claims.clear();
        } catch (Throwable cleanup) {
            failure = publishFatalPreserving(mergeFailure(failure, cleanup));
        }

        int intentCount = frame.intentLevels.size();
        for (int i = 0; i < intentCount; i++) {
            RegionizedServer runtime;
            try {
                runtime = frame.intentLevels.get(i);
            } catch (Throwable cleanup) {
                failure = publishFatalPreserving(
                        mergeFailure(failure, cleanup));
                continue;
            }
            try {
                runtime.clearGlobalFenceIntent(frame);
            } catch (Throwable cleanup) {
                failure = publishFatalPreserving(
                        mergeFailure(failure, cleanup));
            }
        }
        try {
            frame.intentLevels.clear();
        } catch (Throwable cleanup) {
            failure = publishFatalPreserving(mergeFailure(failure, cleanup));
        }

        if (ACTIVE_FENCE == frame) {
            ACTIVE_FENCE = null;
        } else {
            failure = publishFatalPreserving(mergeFailure(failure,
                    new IllegalStateException(
                            "global fence cleanup encountered a replaced active frame")));
        }
        return failure;
    }

    /** Release the exact currently-held global frame, if any. */
    public static Throwable releaseAllExcept(Throwable primary) {
        GlobalFenceFrame frame = ACTIVE_FENCE;
        if (frame == null) {
            return primary != null && RegionScheduler.isActive()
                    ? RegionScheduler.publishFatalAndSelectWinner(primary)
                    : primary;
        }
        return releaseGlobalFenceFrame(frame, primary);
    }

    public static void releaseAllExcept() {
        Throwable failure = releaseAllExcept(null);
        if (failure != null) IslandCommands.<RuntimeException>rethrow(failure);
    }

    /** Publish the exact primary to the generation gate and rethrow first-wins. */
    public static void publishAndPropagate(Throwable primary) {
        if (primary == null) throw new NullPointerException("primary");
        Throwable failure = publishFatalPreserving(primary);
        RegionScheduler.throwWorkerFailureIfAny();
        IslandCommands.<RuntimeException>rethrow(failure);
    }

    /**
     * Terminal fence exit for an exact body.  Passing {@code null} means the
     * body completed normally; otherwise cleanup is exhausted, attached behind
     * that exact primary, and the physical-Main fatal gate is armed before the
     * same first-wins Throwable is rethrown.
     */
    public static void releaseAllExceptAndPropagate(Throwable primary) {
        Throwable failure = releaseAllExcept(primary);
        if (failure == null) {
            try {
                // Claim release is mandatory cleanup, not a success edge. A
                // peer W which won while that cleanup ran must still prevent
                // this fenced command/advancement body from returning normally.
                RegionScheduler.commitSuccessIfHealthy(() -> {});
            } catch (Throwable peerFailure) {
                failure = publishFatalPreserving(peerFailure);
            }
        }
        if (failure != null) publishAndPropagate(failure);
    }
}
