package com.axalotl.fabrolia.common.parallelised.advancement;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import net.minecraft.advancements.predicates.ContextAwarePredicate;
import net.minecraft.advancements.triggers.CriterionTrigger;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.loot.LootContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Deferred award harness for {@link SimpleCriterionTrigger}. Both criterion conditions
 * are resolved in the source operation's exclusive execution venue, before its live
 * arguments or player state can change. Only successful criterion keys survive the
 * trigger call; awards remain at the surrounding monitor-free drain.
 *
 * <p>26.2 model change: vanilla moved the listener-set off the trigger (no more
 * {@code SimpleCriterionTrigger.players: Map<PlayerAdvancements, Set<Listener>>}) onto each
 * {@link PlayerAdvancements} ({@link PlayerAdvancements#getTriggerMapForType(net.minecraft.advancements.triggers.CriterionTrigger)
 * getTriggerMapForType} → {@code Map<TriggerInstanceKey, SimpleInstance>}). Grants go through
 * {@link PlayerAdvancements#award(net.minecraft.advancements.AdvancementHolder, String) award}
 * keyed on {@code (advancement, criterion)} instead of {@code listener.run(playerAdvancements)}.
 * The buffer snapshots registered key/instance pairs, evaluates them in vanilla order,
 * then retains only matched keys. Per-drain dedup remains scoped to each player/key.
 *
 * <p>Frame-based retention:
 * <ul>
 *   <li>Each thread owns a private {@link Frame} via a single ThreadLocal (one TL holding a
 *       mutable frame, fields toggled in place, never {@code remove()}d — TL set/remove on
 *       the hot path is the expensive part, plain get is ~10ns). {@link #enter}/{@link #exit}
 *       flip the frame's active flag; {@link #tryBuffer} appends to the calling thread's own
 *       buffer. Buffers stay disjoint across Main and worker owner turns;
 *       independent island producers may capture concurrently.</li>
 *   <li>Worker triggers outside a frame publish directly to the retained Main
 *       lane. Main triggers outside a frame retain their own drain queue.</li>
 * </ul>
 *
 * <p>Exact target/dependency ownership must already exist before the source operation
 * enters. This buffer asserts that contract; it cannot transport an entered callback
 * or acquire a global fence while its caller holds arbitrary monitors and claims.
 * Neither live loot contexts nor player NBT caches cross trigger boundaries.
 */
public final class AdvancementBuffer {

    private static final class Frame {
        /**
         * Reentrancy depth instead of a boolean: separate buffering windows open on
         * ServerPlayer.tick() in the entity phase and ServerPlayer.doTick() later in
         * the connection phase. The counter protects modded/reentrant call chains;
         * ordinary vanilla calls drain each phase separately. Carpet fake players
         * (EntityPlayerMPFake) OVERRIDE tick() and call doTick() directly, bypassing
         * the tick() wrap entirely — without the
         * doTick window their every-tick triggers (TICK/LOCATION × BACAP listener
         * sets) fell through to the vanilla inline path under the SyncAll monitor,
         * convoying all region workers on one PlayerTrigger lock (live 2026-06-12,
         * 50 fake players: workers BLOCKED on PlayerTrigger while main evaluated
         * LocationPredicate inline under the lock).
         */
        int depth;
        final ArrayList<Pending> buffer = new ArrayList<>(128);
    }

    private static final ThreadLocal<Frame> FRAME = ThreadLocal.withInitial(Frame::new);

    /** Fully matched criteria retained until their monitor-free Main award drain. */
    private static final ConcurrentLinkedQueue<Pending[]> DEFERRED_PENDING = new ConcurrentLinkedQueue<>();

    /** Main-confined frameless triggers retain matched keys for the next safe drain. */
    private static final ArrayDeque<Pending> MAIN_FRAMELESS = new ArrayDeque<>();

    /** Explicit asynchronous Main mutations. Synchronous off-owner award/revoke
     * calls are rejected, never silently published here with a fabricated result. */
    private static final ConcurrentLinkedQueue<Runnable> DIRECT_MAIN = new ConcurrentLinkedQueue<>();

    /** Main-frame awards parked for the per-tick drain. */
    private static final ArrayList<Pending> MAIN_TICK_BATCH = new ArrayList<>(256);

    private static final AtomicBoolean MAIN_DRAIN_SCHEDULED = new AtomicBoolean();


    /** One successful trigger invocation; no deferred predicates or live context. */
    private record Pending(ServerPlayer player,
                           PlayerAdvancements.TriggerInstanceKey[] keys) {}

    private AdvancementBuffer() {}

    public static void enter() {
        FRAME.get().depth++;
    }

    /** Emergency hard reset — discards pendings without processing. */
    public static void exit() {
        Frame f = FRAME.get();
        f.depth = 0;
        f.buffer.clear();
    }

    /**
     * Called from the {@code SimpleCriterionTrigger.trigger} wrap. Returns {@code true} if
     * this call has been buffered for deferred processing — the caller must then NOT invoke
     * the original implementation. Returns {@code false} when buffering is inactive for this
     * thread (or feature flag off / executor unavailable) — caller proceeds with vanilla.
     *
     * <p>{@code triggerMap} is the per-player map vanilla itself iterates in {@code trigger()}
     * ({@link PlayerAdvancements#getTriggerMapForType}). May be {@code null} when no listener
     * for this trigger is registered for this player, in which case vanilla would early-return
     * — we accept and return {@code true} so the caller skips the original call too.
     */
    public static boolean tryBuffer(ServerPlayer player,
                                    Predicate<?> predicate,
                                    CriterionTrigger<?> trigger) {
        if (FabroliaConfig.disabled) return false;
        AdvancementCaptureOwnership.requireTriggerEvaluationOwnership(player, trigger);
        if (com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                .inWorkerPlayerTick() && AdvancementCaptureOwnership.ownsGlobalProducerEnvelope()) {
            // The exact player actor owns the logical server turn while physical
            // main is blocked at this EntityTickList ordinal and the region pool is
            // fenced. Deferring TICK/LEVITATION criteria until batch end would change
            // reward/callback/packet order, so keep the vanilla synchronous chain.
            // The broad scoped check is deliberate: a reward from player A may
            // synchronously trigger player B and vanilla keeps that nested chain
            // inline in the same call stack.
            return false;
        }
        Frame f = FRAME.get();
        MinecraftServer s = ParallelProcessor.getServer();
        boolean onMain = s == null || com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                .isPhysicalServerThread(s);
        if (f.depth == 0) {
            if (onMain) {
                // FRAMELESS MAIN (packet-driven triggers: joins, crafting,
                // attacks). Async OFF: vanilla inline is single-threaded and
                // byte-for-byte correct — keep it. Async ON: inline ran the
                // award chain (rewards → function fence) inside the trigger
                // call. Capture complete matches now; only awards wait for
                // monitor-free drain points.
                if (!IslandCommands.requiresMainFence() && AdvancementCaptureOwnership.ownsGlobalProducerEnvelope()) {
                    return false;
                }
                Pending p = buildPending(player, predicate, trigger);
                if (p != null) {
                    RegionScheduler.runSuccessBodyIfHealthy(() -> {
                        MAIN_FRAMELESS.addLast(p);
                        scheduleMainDrain(s);
                    });
                }
                return true;
            }
            // FRAMELESS WORKER: the trigger fired outside any player-tick frame — an
            // attacker mob/projectile tick damaging this player's armor (durability
            // trigger fires on the ATTACKER's thread), a beacon/conduit BE tick applying
            // effects from an island worker, etc. Falling through to vanilla inline here
            // ran award() → rewards → functions/commands ON THE WORKER — the exact thing
            // the worker frame exists to prevent — and mutated PlayerAdvancements
            // (progress + listener re-registration) unsynchronized against the main
            // batch-end drain. Live symptom: armor-break criteria never registering
            // ("Where are all your clothes?", 2026-07-07). Only complete matches
            // may reach this lane from an authenticated exclusive producer.
            Pending p = buildPending(player, predicate, trigger);
            if (p != null) {
                Pending[] publication = new Pending[]{p};
                RegionScheduler.runSuccessBodyIfHealthy(
                        () -> DEFERRED_PENDING.add(publication));
            }
            return true;
        }
        if (onMain) {
            // Main-thread frame (players sync). Async OFF: buffering is a pure
            // optimization — honor the feature flag,
            // vanilla inline otherwise (single-threaded, safe). Async ON:
            // buffering is MANDATORY here too (2026-07-17): inline would run
            // awards inside the trigger call while region workers exist; the
            // tick-exit drain applies them on main outside every monitor.
            if (!IslandCommands.requiresMainFence() && AdvancementCaptureOwnership.ownsGlobalProducerEnvelope()) {
                if (!FabroliaConfig.enableFabroliaAdvancements) return false;
            }
        }
        // Defensive off-main frame: buffering is MANDATORY
        // regardless of enableFabroliaAdvancements — falling through to vanilla inline would
        // run award() (grants → rewards → functions/commands) on the worker thread.
        // drainOnWorker retains only the already-decided award obligation.

        ArrayList<Pending> buf = f.buffer;
        // Each invocation has vanilla's own recursion stack and current state.
        Pending p = buildPending(player, predicate, trigger);
        if (p != null) buf.add(p);
        return true;
    }

    /**
     * Snapshot listeners under their map monitor, then evaluate matcher and player
     * condition together, in vanilla order, outside that monitor. Callbacks may mutate
     * state, consume randomness or raise nested triggers; neither conditions nor NBT
     * serialization results may be cached across these observations or replayed.
     * Exclusive source ownership must already be established before this call.
     * @return the filtered Pending, or {@code null} when no instance passes — nothing to
     *         defer, the caller still reports the trigger call as handled.
     */
    private static Pending buildPending(ServerPlayer player, Predicate<?> predicate,
                                        CriterionTrigger<?> trigger) {
        AdvancementCaptureOwnership.requireTriggerEvaluationOwnership(player, trigger);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Predicate<Object> pred = (Predicate) predicate;
        PlayerAdvancements advancements = player.getAdvancements();
        PlayerAdvancements.TriggerInstanceKey[] keys;
        Object[] instances;
        synchronized (advancements) {
            Map<PlayerAdvancements.TriggerInstanceKey, ?> triggerMap =
                    advancements.getTriggerMapForType(trigger);
            if (triggerMap == null || triggerMap.isEmpty()) return null;
            int n = triggerMap.size();
            keys = new PlayerAdvancements.TriggerInstanceKey[n];
            instances = new Object[n];
            int index = 0;
            for (Map.Entry<PlayerAdvancements.TriggerInstanceKey, ?> entry : triggerMap.entrySet()) {
                keys[index] = entry.getKey();
                instances[index++] = entry.getValue();
            }
        }
        LootContext lootContext = EntityPredicate.createContext(player, player);
        int matched = 0;
        int itemMatches = 0;
        for (int index = 0; index < instances.length; index++) {
            Object instance = instances[index];
            if (!pred.test(instance)) continue;
            itemMatches++;
            if (!matchesPlayerPredicate(instance, lootContext)) continue;
            keys[matched++] = keys[index];
            AdvancementDiag.MATCHES.increment();
        }
        AdvancementDiag.ENTRIES_INSPECTED.add(instances.length);
        AdvancementDiag.INSTANCES_KEPT.add(itemMatches);
        if (matched == 0) return null;
        AdvancementDiag.EVENTS_PRODUCED.increment();
        if (matched < keys.length) keys = Arrays.copyOf(keys, matched);
        return new Pending(player, keys);
    }

    /**
     * Drains the calling thread's frame. Main applies awards or parks them for the
     * tick batch; authenticated worker producers publish their already-matched keys.
     * Frame ownership ends before any award or cascade.
     */
    public static void drain() {
        Frame f = FRAME.get();
        if (f.depth == 0) return;       // unbalanced — nothing to do
        if (--f.depth > 0) return;      // reentrant inner window; outer caller drains
        final ArrayList<Pending> buf = f.buffer;
        final int n = buf.size();
        if (n == 0) return;
        MinecraftServer s = ParallelProcessor.getServer();
        boolean main = s == null || com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                .isPhysicalServerThread(s);
        if (main && (IslandCommands.requiresMainFence() || !AdvancementCaptureOwnership.ownsGlobalProducerEnvelope())) {
            // Keep frame order without allocating a temporary Pending[] only
            // to copy it into the retained Main batch.
            MAIN_TICK_BATCH.ensureCapacity(MAIN_TICK_BATCH.size() + n);
            for (int i = 0; i < n; i++) MAIN_TICK_BATCH.add(buf.get(i));
            buf.clear();
            AdvancementDiag.DRAIN_PARKS.increment();
            return;
        }
        Pending[] pending = buf.toArray(new Pending[0]);
        buf.clear();
        if (main) {
            AdvancementDiag.DRAIN_LEGACY.increment();
            drainOnMain(pending, n);
        } else {
            drainOnWorker(pending);
        }
    }

    /** Publish only complete trigger decisions; no producer state survives here. */
    private static void drainOnWorker(Pending[] pending) {
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> DEFERRED_PENDING.add(pending));
    }

    private static void drainOnMain(Pending[] pending, int n) {
        drainOnMain(pending, n, null);
    }

    private static void drainOnMain(Pending[] pending, int n,
            IdentityHashMap<ServerPlayer, Set<PlayerAdvancements.TriggerInstanceKey>> fired) {
        AdvancementBatchScope.ensureEvaluationFence();
        IdentityHashMap<ServerPlayer, Set<PlayerAdvancements.TriggerInstanceKey>> activeFired =
                fired == null ? new IdentityHashMap<>() : fired;
        for (int index = 0; index < n; index++) {
            Pending p = pending[index];
            for (int entry = 0; entry < p.keys.length; entry++) {
                int admittedEntry = entry;
                RegionScheduler.runSuccessBodyIfHealthy(() ->
                        applyPendingEntryInAdmittedBody(p, admittedEntry, activeFired));
            }
        }
    }


    /**
     * Apply a complete frameless trigger invocation after its FIFO detach has
     * already won one scheduler admission.  This path deliberately has no
     * nested admission: W may linearize after the trigger body entered, but it
     * must not turn that already-detached invocation into an unowned refusal.
     */
    private static void applyPendingInAdmittedBody(
            Pending p,
            IdentityHashMap<ServerPlayer, Set<PlayerAdvancements.TriggerInstanceKey>> fired) {
        for (int entry = 0, count = p.keys.length; entry < count; entry++) {
            applyPendingEntryInAdmittedBody(p, entry, fired);
        }
    }

    /** Caller owns the criterion or containing trigger's scheduler admission. */
    private static void applyPendingEntryInAdmittedBody(
            Pending p, int entry,
            IdentityHashMap<ServerPlayer, Set<PlayerAdvancements.TriggerInstanceKey>> fired) {
        // Both conditions passed at fire time; never inspect the player's later state.
        fabrolia$applyAwardInAdmittedBody(p.player, p.keys[entry], fired);
    }

    /** Native flush obligations retain their original argument and FIFO ownership. */
    private record Flush(ServerPlayer player, boolean force) {}
    private static final ConcurrentLinkedQueue<Flush> PENDING_FLUSH = new ConcurrentLinkedQueue<>();

    public static void queueFlush(ServerPlayer player, boolean force) {
        // Queue publication is a successor stage. A region/player body which
        // began before peer W may finish, but it cannot publish a later flush
        // after that W has already closed the generation.
        RegionScheduler.runSuccessBodyIfHealthy(() -> PENDING_FLUSH.add(new Flush(player, force)));
    }

    /** MAIN, entity-batch end, after grants: run the flushes workers skipped. */
    public static void drainPendingFlushes() {
        if (PENDING_FLUSH.isEmpty() || IslandCommands.globalFenceTransitionInProgress()) return;
        if (AdvancementBatchScope.active()) {
            drainPendingFlushesHeld();
            return;
        }
        Throwable failure = null;
        AdvancementBatchScope.begin();
        try {
            drainPendingFlushesHeld();
        } catch (Throwable thrown) {
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        try {
            AdvancementBatchScope.end();
        } catch (Throwable cleanup) {
            failure = RegionScheduler.publishFatalAndSelectWinner(mergeFailure(failure, cleanup));
        }
        if (failure != null) AdvancementBuffer.<RuntimeException>rethrow(failure);
    }

    private static void drainPendingFlushesHeld() {
        if (PENDING_FLUSH.isEmpty()) return;
        // Acquire before detach, and retain exclusion through the last flush.
        // A nested acquisition pump leaves the original queue untouched.
        AdvancementBatchScope.ensureEvaluationFence();
        while (RegionScheduler.callSuccessBooleanIfHealthy(() -> {
            Flush flush = PENDING_FLUSH.poll();
            if (flush == null) return false;
            ServerPlayer target = flush.player;
            if (!target.hasDisconnected()) {
                target.getAdvancements().flushDirty(target, flush.force);
                AdvancementDiag.FLUSHES.increment();
            }
            return true;
        })) {
            // fixed point over already-published native flush obligations
        }
    }

    /** Explicit asynchronous obligation; never used to emulate a synchronous return value. */
    public static void deferDirect(Runnable replay) {
        if (replay == null) throw new NullPointerException("replay");
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            DIRECT_MAIN.add(replay);
            scheduleMainDrain(ParallelProcessor.getServer());
        });
        // Publication may have raced a peer W after it entered. Its terminal
        // permit observes that W, so this metric is reached only after a clean
        // accepted hand-off.
        RegionScheduler.runSuccessBodyIfHealthy(
                AdvancementDiag.DIRECT_REPLAYS::increment);
    }

    private static void scheduleMainDrain(MinecraftServer s) {
        if (s != null && MAIN_DRAIN_SCHEDULED.compareAndSet(false, true)) {
            // execute() runs inline on physical Main, including inside an
            // inventory/menu callback that still owns a claim or monitor.
            // Queue instead: the producer unwinds before the ordinary Main
            // pump, and one scheduled drain coalesces its complete slot burst.
            try {
                RegionScheduler.scheduleMainControlTask(s,
                        () -> fabrolia$runFailStopMainTask(AdvancementBuffer::drainMainFrameless));
            } catch (Throwable publicationFailure) {
                // schedule() may enqueue before throwing. Publish fatal now so an
                // ambiguously accepted task cannot enter after its producer failed.
                AdvancementBuffer.<RuntimeException>rethrow(
                        fabrolia$publishFatalPreserving(publicationFailure));
            }
        }
    }

    /**
     * MinecraftServer's task executor logs ordinary task exceptions and keeps
     * polling. Arm the shared fatal gate before rethrowing the exact task
     * primary so no later drain can consume a successor mutation.
     */
    private static void fabrolia$runFailStopMainTask(Runnable task) {
        try {
            com.axalotl.fabrolia.common.regions.RegionScheduler
                    .runSuccessBodyIfHealthy(task);
        } catch (Throwable failure) {
            AdvancementBuffer.<RuntimeException>rethrow(
                    fabrolia$publishFatalPreserving(failure));
        }
    }

    private static Throwable fabrolia$publishFatalPreserving(Throwable primary) {
        return RegionScheduler.publishFatalAndSelectWinner(primary);
    }

    /**
     * MAIN-only. Applies frameless-main pendings and worker-side direct
     * award/revoke replays in FIFO order, looping until both queues are empty
     * so award cascades (reward functions raising new triggers, /advancement
     * grants inside rewards) settle within one drain. Every award here runs
     * with NO monitor held. A standalone drain owns an exact advancement scope
     * so its first reward function waits for one unbounded fence and retains
     * that sticky window through the complete accepted FIFO.
     */
    public static void drainMainFrameless() {
        MinecraftServer server = ParallelProcessor.getServer();
        if (server != null && !com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                .isPhysicalServerThread(server)) {
            throw new IllegalStateException("advancement Main carrier accessed off physical Main");
        }
        RegionScheduler.throwWorkerFailureIfAny();
        if (com.axalotl.fabrolia.common.regions.IslandCommands
                .globalFenceTransitionInProgress()) {
            return;
        }
        if (AdvancementBatchScope.active()) {
            drainMainFrameless(null);
            return;
        }
        Throwable failure = null;
        AdvancementBatchScope.begin();
        try {
            drainMainFrameless(null);
        } catch (Throwable thrown) {
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        try {
            AdvancementBatchScope.end();
        } catch (Throwable cleanup) {
            failure = RegionScheduler.publishFatalAndSelectWinner(
                    mergeFailure(failure, cleanup));
        }
        if (failure != null) {
            AdvancementBuffer.<RuntimeException>rethrow(failure);
        }
    }

    private static void drainMainFrameless(
            IdentityHashMap<ServerPlayer, Set<PlayerAdvancements.TriggerInstanceKey>> fired) {
        RegionScheduler.throwWorkerFailureIfAny();
        // A nested Main pump may run a queued task while an unrelated global
        // fence is still acquiring. Keep both queues and the scheduled marker
        // intact: the unconditional tick-head/entity-batch belts retry after
        // the transition, without a self-rescheduling spin.
        if (com.axalotl.fabrolia.common.regions.IslandCommands
                .globalFenceTransitionInProgress()) {
            return;
        }
        MAIN_DRAIN_SCHEDULED.set(false);
        if (MAIN_FRAMELESS.isEmpty() && DIRECT_MAIN.isEmpty()) return;
        AdvancementBatchScope.ensureEvaluationFence();
        if (fired == null) {
            fired = new IdentityHashMap<>();
        }
        final IdentityHashMap<ServerPlayer,
                Set<PlayerAdvancements.TriggerInstanceKey>> activeFired = fired;
        while (!MAIN_FRAMELESS.isEmpty() || !DIRECT_MAIN.isEmpty()) {
            // Each FIFO detach and its actual replay body are one admission.
            // There is intentionally no post-detach nested permit which could
            // refuse the item after it no longer has a queue owner.
            drainOneMainFrameless(activeFired);
            drainOneDirectMain();
        }
    }

    private static boolean drainOneMainFrameless(
            IdentityHashMap<ServerPlayer, Set<PlayerAdvancements.TriggerInstanceKey>> fired) {
        return RegionScheduler.callSuccessBooleanIfHealthy(() -> {
            Pending pending = MAIN_FRAMELESS.pollFirst();
            if (pending == null) return false;
            applyPendingInAdmittedBody(pending, fired);
            return true;
        });
    }

    private static boolean drainOneDirectMain() {
        return RegionScheduler.callSuccessBooleanIfHealthy(() -> {
            Runnable replay = DIRECT_MAIN.poll();
            if (replay == null) return false;
            fabrolia$runRewardBody(replay);
            return true;
        });
    }

    /**
     * Narrow capability around one serial vanilla advancement mutation which
     * may synchronously dispatch a reward function.  The broad batch scope is
     * also active during fence acquisition and its chunk-callback pump; keeping
     * this separate prevents unrelated re-entrant Main work from borrowing the
     * clean unbounded function fence.  Cleanup never contains the failure: the
     * exact body primary is rethrown after the depth token is retired.
     */
    private static void fabrolia$runRewardBody(Runnable body) {
        if (body == null) throw new NullPointerException("body");
        // Legacy synchronous advancement mode has no outer batch scope and no
        // asynchronous island fence to lend. Preserve its direct vanilla body.
        if (!AdvancementBatchScope.active()) {
            body.run();
            return;
        }
        Throwable failure = null;
        AdvancementBatchScope.enterRewardBody();
        try {
            body.run();
        } catch (Throwable thrown) {
            failure = thrown;
        }
        try {
            AdvancementBatchScope.exitRewardBody();
        } catch (Throwable cleanup) {
            failure = mergeFailure(failure, cleanup);
        }
        if (failure != null) {
            AdvancementBuffer.<RuntimeException>rethrow(failure);
        }
    }

    // Vanilla SimpleCriterionTrigger.trigger gates each listener on BOTH the per-trigger
    // predicate AND the SimpleInstance.player() ContextAwarePredicate (the advancement
    // JSON's `player:` block). For TickTrigger/LocationTrigger the per-trigger predicate
    // is trivially true, so dropping this second gate would grant every listener every
    // tick.
    private static boolean matchesPlayerPredicate(Object instance, LootContext ctx) {
        if (instance instanceof SimpleCriterionTrigger.SimpleInstance si) {
            Optional<ContextAwarePredicate> pp = si.player();
            return pp.isEmpty() || pp.get().matches(ctx);
        }
        return true;
    }

    // Per-drain dedup of (player, TriggerInstanceKey) — each pair fires award at most once even
    // if N buffered Pendings produced N matches for it. Datapacks whose reward function uses
    // a revoke-and-rerun pattern (e.g. BACAP's blazeandcave:technical/inventory_changed →
    // inv_check_immortal: the reward revokes its own advancement so the criterion is fresh
    // for the next trigger call) re-fire the reward N times in the burst because each
    // award sees a freshly-revoked criterion and re-grants. In vanilla those N fires
    // are spread across the tick (one reward per trigger call, inline); our batched apply
    // fires them in a tight burst at end-of-tick. With a downstream parallel-function
    // dispatcher in play (e.g. MFC's parallel datapacks), the same player's reward function
    // can then execute on multiple worker threads concurrently — scoreboard `players
    // operation +=` ops interleave across threads and accumulators climb past their real
    // ceiling. The Immortal advancement (37-totem inventory) was being falsely granted to
    // players carrying 1-2 totems under lag, because the dispatched inv_check_immortal
    // executions overlapped enough to push bac_inv_immortal past 37.
    //
    // This retained policy collapses reward calls, not trigger predicate evaluation.
    // State-check rewards can treat repeated reads of final state idempotently;
    // counter-style rewards ("+1 per fire") cannot. Per-drain dedup does not
    // preserve vanilla per-fire reward timing, and async modes require buffering.
    //
    // Dedup uses TriggerInstanceKey value-equality (record equals): keys with identical
    // (advancement, criterion) collapse even if some path produced fresh key instances.

    /**
     * Main-thread, entity-batch end (ParallelProcessor.onEntityTickBatchEnd). Merges
     * already-matched criteria into one fenced, serial award pass. Dedup spans the
     * batch; accepted publications also drain at the healthy shutdown fixed point.
     */
    public static void applyDeferred() {
        long batchT0 = System.nanoTime();
        // Prepare every allocation/local before publishing the exact scope
        // owner. Once begin() succeeds, the cleanup envelope starts
        // immediately and end() is therefore unavoidable.
        IdentityHashMap<ServerPlayer, Set<PlayerAdvancements.TriggerInstanceKey>> fired =
                new IdentityHashMap<>();
        boolean any = false;
        Throwable failure = null;
        // Keep the first award/reward fence through the complete batch,
        // including its nested cascades. Empty batches need no acquisition.
        AdvancementBatchScope.begin();
        try {

            // Main-ticked players share one fenced award pass.
            if (!MAIN_TICK_BATCH.isEmpty()) {
                AdvancementBatchScope.ensureEvaluationFence();
                any = true;
                Pending[] parked = MAIN_TICK_BATCH.toArray(new Pending[0]);
                MAIN_TICK_BATCH.clear();
                AdvancementDiag.lastBatchPendings = parked.length;
                drainOnMain(parked, parked.length, fired);
            }

            any |= fabrolia$applyDeferredLane(fired);

            // Cascades (reward functions raising new triggers, /advancement in
            // rewards) settle inside the same scope + dedup.
            drainMainFrameless(fired);
            RegionScheduler.throwWorkerFailureIfAny();

            if (any || !fired.isEmpty()) {
                // Snapshot before the first successor publication. Allocation or
                // iteration failure is caught by this method's fail-stop envelope;
                // no queue entry has been detached or lost at that point.
                ServerPlayer[] affectedPlayers = fired.keySet().toArray(new ServerPlayer[0]);
                int affectedCount = affectedPlayers.length;
                RegionScheduler.runSuccessBodyIfHealthy(() -> {
                    AdvancementDiag.BATCH_APPLIES.increment();
                    AdvancementDiag.lastBatchAffectedPlayers = affectedCount;
                });
                // One flush per affected player, after the whole batch — the
                // packet carries the tick's complete final state, same tick.
                for (ServerPlayer p : affectedPlayers) {
                    queueFlush(p, true);
                }
            }
            drainPendingFlushes();
        } catch (Throwable thrown) {
            // A sticky batch scope may own the global fence. Select W while
            // that ownership is still closed; end() is mandatory cleanup, not
            // the place where an entered advancement failure first becomes
            // visible to the rest of the generation.
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        try {
            AdvancementBatchScope.end();
        } catch (Throwable cleanup) {
            Throwable candidate = mergeFailure(failure, cleanup);
            failure = RegionScheduler.publishFatalAndSelectWinner(candidate);
        }
        if (failure != null) {
            AdvancementBuffer.<RuntimeException>rethrow(failure);
        }
        try {
            long barrierNanos = System.nanoTime() - batchT0;
            RegionScheduler.commitSuccessIfHealthy(
                    () -> AdvancementDiag.BARRIER_NANOS.add(barrierNanos));
        } catch (Throwable successFailure) {
            failure = RegionScheduler.publishFatalAndSelectWinner(successFailure);
        }
        if (failure != null) AdvancementBuffer.<RuntimeException>rethrow(failure);
    }

    /**
     * Physical-Main lifecycle progress.  Normal entity-batch drains remain the
     * timing authority; this method only closes work already retained when the
     * server leaves its final tick (or when region threading was disabled and
     * therefore has no RegionScheduler stop-progress callback).
     *
     * <p>The emptiness probe is only an optimization.  RegionWorkerPool's
     * shutdown protocol requires a second zero-ledger progress pass, so a
     * publication which becomes visible immediately after this probe is picked
     * up by the next pass rather than acknowledged and moved to another tick.
     */
    public static void drainShutdownPassIfPending() {
        MinecraftServer server = ParallelProcessor.getServer();
        if (server != null
                && !com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                        .isPhysicalServerThread(server)) {
            throw new IllegalStateException(
                    "advancement shutdown drain entered off physical Main");
        }
        if (DEFERRED_PENDING.isEmpty()
                && MAIN_FRAMELESS.isEmpty()
                && DIRECT_MAIN.isEmpty()
                && MAIN_TICK_BATCH.isEmpty()
                && PENDING_FLUSH.isEmpty()
                && !MAIN_DRAIN_SCHEDULED.get()) {
            return;
        }
        applyDeferred();
        drainPendingFlushes();
    }

    /**
     * Healthy server-generation retirement.  Nothing here discards simulation
     * work: every carrier must already be empty after the causal fixed point and
     * the shared executor join.  A surviving item is a shutdown failure, not a
     * reason to clear a queue and report success.
     */
    public static void verifyAndResetAfterShutdown() {
        MinecraftServer server = ParallelProcessor.getServer();
        if (server != null
                && !com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                        .isPhysicalServerThread(server)) {
            throw new IllegalStateException(
                    "advancement shutdown verification entered off physical Main");
        }
        Frame frame = FRAME.get();
        boolean queuesClean = DEFERRED_PENDING.isEmpty()
                && MAIN_FRAMELESS.isEmpty()
                && DIRECT_MAIN.isEmpty()
                && MAIN_TICK_BATCH.isEmpty()
                && PENDING_FLUSH.isEmpty();
        if (!queuesClean || MAIN_DRAIN_SCHEDULED.get()
                || frame.depth != 0 || !frame.buffer.isEmpty()
                || !AdvancementBatchScope.shutdownClean()) {
            throw new IllegalStateException(
                    "advancement work survived the healthy shutdown fixed point"
                            + " (deferred=" + DEFERRED_PENDING.size()
                            + ", frameless=" + MAIN_FRAMELESS.size()
                            + ", direct=" + DIRECT_MAIN.size()
                            + ", mainBatch=" + MAIN_TICK_BATCH.size()
                            + ", flush=" + PENDING_FLUSH.size()
                            + ", scheduled=" + MAIN_DRAIN_SCHEDULED.get()
                            + ", frameDepth=" + frame.depth
                            + ", frameSize=" + frame.buffer.size() + ")");
        }
        // Scheduling metadata and the physical-Main ThreadLocal carry no vanilla
        // body. Reset only after the proof above; no queue is cleared here.
        MAIN_DRAIN_SCHEDULED.set(false);
        FRAME.remove();
    }

    /** The deferred award lane.
     *  @return true when anything was processed. */
    private static boolean fabrolia$applyDeferredLane(
            IdentityHashMap<ServerPlayer, Set<PlayerAdvancements.TriggerInstanceKey>> fired) {
        if (DEFERRED_PENDING.isEmpty()) return false;
        AdvancementBatchScope.ensureEvaluationFence();
        drainDeferredHeld(fired);
        return true;
    }

    /** Caller holds the award fence (or concurrent island execution is inactive). */
    private static void drainDeferredHeld(
            IdentityHashMap<ServerPlayer, Set<PlayerAdvancements.TriggerInstanceKey>> fired) {
        ArrayList<Pending[]> batches = new ArrayList<>();
        Pending[] arr;
        while ((arr = DEFERRED_PENDING.poll()) != null) {
            if (arr.length != 0) batches.add(arr);
        }
        if (batches.isEmpty()) return;
        AdvancementBatchScope.ensureEvaluationFence();
        for (Pending[] batch : batches) drainOnMain(batch, batch.length, fired);
        // Batch grants can cascade frameless-main raises (reward functions);
        // settle them in the same batch-end window.
        drainMainFrameless(fired);
    }

    /** Duplicate-fire gate refinement (2026-07-19, BACAP oh_baby_a_triple):
     *  a same-drain duplicate is a TRUE duplicate only while the criterion is
     *  still granted. Revoke-and-refire COUNTERS (reward increments a score,
     *  grants at N, revokes its own criterion so the next fire counts again)
     *  need every fire — the old unconditional dedup collapsed a multishot
     *  triple-kill's same-tick fires (+1..2 instead of +3; rigs t5/t6:
     *  vanilla ctr=3 GRANTED vs fabrolia ctr<=2 never). Re-applying is safe
     *  now: awards + reward functions run SERIALLY on main in the drain (the
     *  parallel reward dispatch that motivated the blanket dedup — the
     *  Immortal scoreboard race — no longer exists on this path). State-check
     *  rewards that do NOT revoke stay deduped exactly as before. */
    private static boolean fabrolia$stillGranted(ServerPlayer player,
                                                 PlayerAdvancements.TriggerInstanceKey key) {
        var prog = player.getAdvancements().getOrStartProgress(key.advancement());
        var cp = prog.getCriterion(key.criterion());
        return cp == null || cp.isDone();
    }


    /** Caller owns exactly one scheduler-admitted criterion body. */
    private static void fabrolia$applyAwardInAdmittedBody(
            ServerPlayer player,
            PlayerAdvancements.TriggerInstanceKey key,
            IdentityHashMap<ServerPlayer,
                    Set<PlayerAdvancements.TriggerInstanceKey>> fired) {
        Set<PlayerAdvancements.TriggerInstanceKey> playerFired = fired.computeIfAbsent(
                player, k -> new HashSet<>());
        if (!playerFired.add(key) && fabrolia$stillGranted(player, key)) {
            AdvancementDiag.DEDUP_DROPPED.increment();
            return;
        }
        fabrolia$runRewardBody(() ->
                player.getAdvancements().award(key.advancement(), key.criterion()));
        AdvancementDiag.AWARDS.increment();
    }

    private static Throwable mergeFailure(Throwable primary, Throwable cleanup) {
        if (primary == null) return cleanup;
        if (cleanup != null && cleanup != primary) {
            try {
                primary.addSuppressed(cleanup);
            } catch (Throwable ignored) {
                // Cleanup diagnostics must never replace the exact tick failure.
            }
        }
        return primary;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T) failure;
    }
}
