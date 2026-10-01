package com.axalotl.fabrolia.common.parallelised.advancement;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import net.minecraft.core.Holder;
import net.minecraft.advancements.triggers.CriterionTrigger;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Deferred award harness for {@link SimpleCriterionTrigger}. The per-trigger matcher is
 * resolved at the trigger call under the player's advancements monitor; the retained
 * instances, their {@code player:} predicate and the awards are evaluated at the
 * surrounding monitor-free drain against the {@link LootContext} captured at fire time.
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
 * <p>This buffer cannot transport an entered callback or acquire a global fence while
 * its caller holds arbitrary monitors and claims.
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


    /** One trigger invocation: matcher-filtered instances plus the live context evaluated at drain. */
    private record Pending(ServerPlayer player,
                           LootContext lootContext,
                           PlayerAdvancements.TriggerInstanceKey[] keys,
                           Object[] instances) {}


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
        if (com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                .inWorkerPlayerTick()) {
            // The exact player actor owns the logical server turn while physical
            // main is blocked at this EntityTickList ordinal and the region pool is
            // fenced. Deferring TICK/LEVITATION criteria until batch end would change
            // reward/callback/packet order, so keep the vanilla synchronous chain.
            return false;
        }
        Frame f = FRAME.get();
        MinecraftServer s = ParallelProcessor.getServer();
        boolean onMain = s == null || s.isSameThread();
        if (f.depth == 0) {
            if (onMain) {
                // FRAMELESS MAIN (packet-driven triggers: joins, crafting,
                // attacks). Async OFF: vanilla inline is single-threaded and
                // byte-for-byte correct — keep it. Async ON: inline ran the
                // award chain (rewards → function fence) inside the trigger
                // call. Capture complete matches now; only awards wait for
                // monitor-free drain points.
                if (!IslandCommands.requiresMainFence()) {
                    return false;
                }
                Pending p = buildPending(player, predicate, trigger,
                        EntityPredicate.createContext(player, player));
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
            Pending p = buildPending(player, predicate, trigger,
                    EntityPredicate.createContext(player, player));
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
            if (!IslandCommands.requiresMainFence()) {
                if (!FabroliaConfig.enableFabroliaAdvancements) return false;
            }
        }
        // Defensive off-main frame: buffering is MANDATORY
        // regardless of enableFabroliaAdvancements — falling through to vanilla inline would
        // run award() (grants → rewards → functions/commands) on the worker thread.
        // drainOnWorker retains only the already-decided award obligation.

        ArrayList<Pending> buf = f.buffer;
        // Each invocation has vanilla's own recursion stack and current state.
        Pending p = buildPending(player, predicate, trigger,
                EntityPredicate.createContext(player, player));
        if (p != null) buf.add(p);
        return true;
    }

    /**
     * Snapshot the listeners under the player's advancements monitor and keep those
     * passing the per-trigger matcher, in vanilla order. The {@code player:} predicate is
     * evaluated later at the drain against the retained {@code lootContext}.
     * @return the filtered Pending, or {@code null} when no instance passes the matcher —
     *         nothing to defer, the caller still reports the trigger call as handled.
     */
    private static Pending buildPending(ServerPlayer player, Predicate<?> predicate,
                                        CriterionTrigger<?> trigger, LootContext lootContext) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Predicate<Object> pred = (Predicate) predicate;
        synchronized (player.getAdvancements()) {
            Map<PlayerAdvancements.TriggerInstanceKey, ?> triggerMap =
                    player.getAdvancements().getTriggerMapForType(trigger);
            if (triggerMap == null || triggerMap.isEmpty()) return null;
            int n = triggerMap.size();
            PlayerAdvancements.TriggerInstanceKey[] keys = new PlayerAdvancements.TriggerInstanceKey[n];
            Object[] instances = new Object[n];
            int kept = 0;
            for (Map.Entry<PlayerAdvancements.TriggerInstanceKey, ?> entry : triggerMap.entrySet()) {
                if (kept >= n) break;
                Object instance = entry.getValue();
                if (pred.test(instance)) {
                    keys[kept] = entry.getKey();
                    instances[kept] = instance;
                    kept++;
                }
            }
            AdvancementDiag.ENTRIES_INSPECTED.add(n);
            AdvancementDiag.INSTANCES_KEPT.add(kept);
            if (kept == 0) return null;
            AdvancementDiag.EVENTS_PRODUCED.increment();
            if (kept < n) {
                keys = Arrays.copyOf(keys, kept);
                instances = Arrays.copyOf(instances, kept);
            }
            return new Pending(player, lootContext, keys, instances);
        }
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
        boolean main = s == null || s.isSameThread();
        if (main && IslandCommands.requiresMainFence()) {
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
        // Main ticks its players one after another, so this batch carries vanilla's
        // order ACROSS players as well: any award invalidates every later evaluation.
        evaluateAndApply(pending, n, fired, true);
    }

    private static final AdvancementPredicateEval.Shape<Pending> PENDING_SHAPE =
            new AdvancementPredicateEval.Shape<>() {
                @Override
                public Object owner(Pending pending) {
                    return pending.player;
                }

                @Override
                public int entries(Pending pending) {
                    return pending.keys.length;
                }
            };

    private static final AdvancementPredicateEval.EntryPredicate<Pending> PLAYER_PREDICATE =
            (p, entry) -> matchesPlayerPredicate(p.instances[entry], p.lootContext);

    /**
     * Caller holds the evaluation fence. Evaluates every retained {@code player:}
     * predicate — fanned over the idle tick pool for large batches — then applies the
     * awards serially on Main in vanilla order: pending publication order, listener
     * order within one pending.
     *
     * <p>Vanilla evaluates each trigger call after the awards of the previous call.
     * The fan-out sees one pre-award snapshot, so pendings that follow an award in
     * this pass are re-evaluated inline against the post-award state (a reward may
     * have changed the score, inventory or XP the next predicate reads); a fresh NBT
     * cache per re-evaluation keeps that read current. Re-evaluation walks the
     * fire-time listener snapshot: a listener re-registered by an earlier reward's
     * revoke of another advancement is not in that snapshot.
     *
     * @param crossPlayerOrder whether the batch carries vanilla order between different
     *                         players' pendings (Main-ticked players, one after another).
     *                         Island players' pendings have no such order, so there only
     *                         the awarded player's own later pendings are re-evaluated.
     */
    private static void evaluateAndApply(Pending[] pending, int n,
            IdentityHashMap<ServerPlayer, Set<PlayerAdvancements.TriggerInstanceKey>> fired,
            boolean crossPlayerOrder) {
        AdvancementCacheContext.requireSerializationOwnership();
        int[][] matched = AdvancementPredicateEval.evaluate(pending, n, PENDING_SHAPE,
                PLAYER_PREDICATE, IslandCommands.fenceWindowHeld(), AdvancementCacheContext::runScoped);
        IdentityHashMap<ServerPlayer, Set<PlayerAdvancements.TriggerInstanceKey>> activeFired =
                fired == null ? new IdentityHashMap<>() : fired;
        Set<ServerPlayer> awarded = Collections.newSetFromMap(new IdentityHashMap<>());
        int matches = 0;
        for (int i = 0; i < n; i++) {
            Pending p = pending[i];
            int[] hits = matched[i];
            if (crossPlayerOrder ? !awarded.isEmpty() : awarded.contains(p.player)) {
                int[][] fresh = new int[1][];
                AdvancementCacheContext.runScoped(() ->
                        fresh[0] = AdvancementPredicateEval.evaluateOne(p, PENDING_SHAPE, PLAYER_PREDICATE));
                hits = fresh[0];
            }
            if (hits == null) continue;
            matches += hits.length;
            for (int entry : hits) {
                PlayerAdvancements.TriggerInstanceKey key = p.keys[entry];
                RegionScheduler.runSuccessBodyIfHealthy(() ->
                        fabrolia$applyAwardInAdmittedBody(p.player, key, activeFired, awarded));
            }
        }
        AdvancementDiag.MATCHES.add(matches);
        AdvancementDiag.lastBatchMatches = matches;
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
        if (matchesPlayerPredicate(p.instances[entry], p.lootContext)) {
            fabrolia$applyAwardInAdmittedBody(p.player, p.keys[entry], fired, null);
        }
    }

    private static final ConcurrentLinkedQueue<ServerPlayer> PENDING_FLUSH = new ConcurrentLinkedQueue<>();

    public static void queueFlush(ServerPlayer player) {
        // Queue publication is a successor stage. A region/player body which
        // began before peer W may finish, but it cannot publish a later flush
        // after that W has already closed the generation.
        RegionScheduler.runSuccessBodyIfHealthy(() -> PENDING_FLUSH.add(player));
    }

    /** MAIN, entity-batch end, after grants: run the flushes workers skipped. */
    public static void drainPendingFlushes() {
        if (PENDING_FLUSH.isEmpty()) return;
        Set<ServerPlayer> done = Collections.newSetFromMap(new IdentityHashMap<>());
        while (RegionScheduler.callSuccessBooleanIfHealthy(() -> {
            ServerPlayer target = PENDING_FLUSH.poll();
            if (target == null) return false;
            if (done.add(target) && !target.hasDisconnected()) {
                target.getAdvancements().flushDirty(target, true);
                AdvancementDiag.FLUSHES.increment();
            }
            return true;
        })) {
            // fixed point over already-published flush obligations
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
                s.schedule(new TickTask(s.getTickCount(),
                        () -> fabrolia$runFailStopMainTask(AdvancementBuffer::drainMainFrameless)));
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
        try {
            RegionScheduler.publishFatalFailure(primary);
        } catch (Throwable publicationFailure) {
            mergeFailure(primary, publicationFailure);
        }
        return primary;
    }

    /**
     * MAIN-only. Applies frameless-main pendings and worker-side direct
     * award/revoke replays in FIFO order, looping until both queues are empty
     * so award cascades (reward functions raising new triggers, /advancement
     * grants inside rewards) settle within one drain. Every award here runs
     * with NO monitor held. A standalone drain owns an exact advancement scope
     * so its first reward function waits for one unbounded fence and retains
     * that sticky window through the complete accepted FIFO.
     *
     * <p>While autonomous islands are live and no window is held, a standalone
     * drain would quiesce every island for these two queues alone; the
     * once-per-tick head window applies them under the fence it shares with the
     * tick functions, so the standalone belt only runs when that fence is free.
     */
    public static void drainMainFrameless() {
        RegionScheduler.throwWorkerFailureIfAny();
        if (com.axalotl.fabrolia.common.regions.IslandCommands
                .globalFenceTransitionInProgress()) {
            return;
        }
        if (AdvancementBatchScope.active()) {
            drainMainFrameless(null);
            return;
        }
        if (IslandCommands.requiresMainFence() && !IslandCommands.fenceWindowHeld()) {
            // The scheduled marker deliberately stays set: it coalesces further
            // TickTasks until the head window drains and resets it.
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
    // predicate AND the SimpleInstance.player() condition (the advancement JSON's
    // `player:` block). For TickTrigger/LocationTrigger the per-trigger predicate
    // is trivially true, so dropping this second gate would grant every listener every
    // tick.
    private static boolean matchesPlayerPredicate(Object instance, LootContext ctx) {
        if (instance instanceof SimpleCriterionTrigger.SimpleInstance si) {
            Optional<Holder<LootItemCondition>> pp = si.player();
            return pp.isEmpty() || pp.get().value().test(ctx);
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
     * Main-thread, entity-batch end (ParallelProcessor.onEntityTickBatchEnd). Applies the
     * Main-ticked players' parked batch: vanilla awards them inline inside the entity
     * phase, so this is the nearest monitor-free point and must stay per level. The
     * worker lane (island players, whose ticks have no Main-phase anchor) rides along
     * only when this batch already holds the fence or none is required; otherwise it
     * waits for the tick-head window, which shares the tick-function fence instead of
     * quiescing every island once more per level.
     */
    public static void applyDeferred() {
        applyBatch(false);
    }

    /**
     * One fenced, serial award pass. Dedup spans the pass; cascades settle inside it.
     * @param workerLaneUnconditional drain the worker lane even when that costs a fresh
     *                                fence (shutdown: nothing may survive)
     */
    private static void applyBatch(boolean workerLaneUnconditional) {
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

            boolean laneApplied = false;
            if (workerLaneUnconditional
                    || !IslandCommands.requiresMainFence()
                    || IslandCommands.fenceWindowHeld()) {
                any |= fabrolia$applyDeferredLane(fired);
                laneApplied = true;
            }

            // Cascades (reward functions raising new triggers, /advancement in
            // rewards) settle inside the same scope + dedup.
            drainMainFrameless(fired);
            if (!laneApplied && IslandCommands.fenceWindowHeld()) {
                // The frameless drain took the sticky fence: the lane rides along.
                any |= fabrolia$applyDeferredLane(fired);
            }
            RegionScheduler.throwWorkerFailureIfAny();
            fabrolia$finishPass(any, fired);
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
        fabrolia$commitBarrierNanos(batchT0);
    }

    /** Batch/window epilogue inside the scope: metrics plus one flush per affected player. */
    private static void fabrolia$finishPass(boolean any,
            IdentityHashMap<ServerPlayer, Set<PlayerAdvancements.TriggerInstanceKey>> fired) {
        if (!any && fired.isEmpty()) return;
        // Snapshot before the first successor publication. Allocation or
        // iteration failure is caught by the caller's fail-stop envelope;
        // no queue entry has been detached or lost at that point.
        ServerPlayer[] affectedPlayers = fired.keySet().toArray(new ServerPlayer[0]);
        int affectedCount = affectedPlayers.length;
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            AdvancementDiag.BATCH_APPLIES.increment();
            AdvancementDiag.lastBatchAffectedPlayers = affectedCount;
        });
        // One flush per affected player, after the whole pass — the
        // packet carries the complete final state, same tick.
        for (ServerPlayer p : affectedPlayers) {
            queueFlush(p);
        }
    }

    private static void fabrolia$commitBarrierNanos(long startNanos) {
        Throwable failure = null;
        try {
            long barrierNanos = System.nanoTime() - startNanos;
            RegionScheduler.commitSuccessIfHealthy(
                    () -> AdvancementDiag.BARRIER_NANOS.add(barrierNanos));
        } catch (Throwable successFailure) {
            failure = RegionScheduler.publishFatalAndSelectWinner(successFailure);
        }
        if (failure != null) AdvancementBuffer.<RuntimeException>rethrow(failure);
    }

    /** Dedup scope of the open tick-head window (Main-only, between begin and end). */
    private static IdentityHashMap<ServerPlayer, Set<PlayerAdvancements.TriggerInstanceKey>>
            tickHeadFired;

    /**
     * MAIN, tick head, immediately before the {@code #minecraft:tick} functions. Opens the
     * per-tick advancement window: the worker lane, then frameless Main awards and
     * direct replays (fire order: frameless items arrive in the inter-tick pump, after
     * the lane's) apply here under one sticky fence which the tick functions then share
     * ({@code fenceWindowHeld} → inline, no second quiesce). With tick functions queued
     * the window takes that fence even when no award is pending, so K functions cost
     * one quiesce instead of K. A {@code true} return MUST be paired with
     * {@link #endTickHeadWindow} after the functions ran: cascades they raise settle in
     * the same window and the fence is released exactly once.
     *
     * <p>Order argument: vanilla runs tick functions before any level ticks; island
     * players' awards have no vanilla position inside Main's tick, and Main-ticked
     * players never publish to this lane. Awards fired during tick T become visible to
     * the tick functions of T+1 in both the batch-end and the head placement; the head
     * placement keeps "awards before functions" for the same tick's Main work.
     *
     * @param tickFunctionsQueued whether {@code ServerFunctionManager} will execute at
     *                            least one tick-tag function in this window
     * @return whether this call owns the window
     */
    public static boolean beginTickHeadWindow(boolean tickFunctionsQueued) {
        RegionScheduler.throwWorkerFailureIfAny();
        if (IslandCommands.globalFenceTransitionInProgress()
                || AdvancementBatchScope.active()) {
            // A nested Main pump owns this position (an acquiring fence's
            // callbacks, or a re-entered tick()). The ordinary belt applies.
            drainMainFrameless();
            return false;
        }
        long start = System.nanoTime();
        IdentityHashMap<ServerPlayer, Set<PlayerAdvancements.TriggerInstanceKey>> fired =
                new IdentityHashMap<>();
        Throwable failure = null;
        AdvancementBatchScope.begin();
        tickHeadFired = fired;
        try {
            if (fabrolia$applyDeferredLane(fired)) {
                RegionScheduler.commitSuccessIfHealthy(AdvancementDiag.HEAD_DRAINS::increment);
            }
            drainMainFrameless(fired);
            if (tickFunctionsQueued) AdvancementBatchScope.ensureEvaluationFence();
            RegionScheduler.throwWorkerFailureIfAny();
        } catch (Throwable thrown) {
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        if (failure == null) {
            try {
                fabrolia$commitBarrierNanos(start);
                return true;
            } catch (Throwable thrown) {
                failure = thrown;
            }
        }
        // The window never opened for its caller: close it here, primary first.
        AdvancementBuffer.<RuntimeException>rethrow(fabrolia$closeTickHeadWindow(failure));
        return false;
    }

    /**
     * Mandatory pair of a {@code true} {@link #beginTickHeadWindow}. Settles cascades the
     * tick functions raised (inside the still-held window), flushes affected players,
     * releases the sticky fence, and returns {@code primary} merged with any cleanup
     * failure (never null when {@code primary} was non-null). Barrier accounting covers
     * the drains only, not the functions' own bodies.
     */
    public static Throwable endTickHeadWindow(Throwable primary) {
        long start = System.nanoTime();
        Throwable failure = primary;
        if (failure == null) {
            try {
                drainMainFrameless(tickHeadFired);
                RegionScheduler.throwWorkerFailureIfAny();
                fabrolia$finishPass(false, tickHeadFired);
            } catch (Throwable thrown) {
                failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
            }
        }
        failure = fabrolia$closeTickHeadWindow(failure);
        if (failure != null) return failure;
        try {
            drainPendingFlushes();
            fabrolia$commitBarrierNanos(start);
        } catch (Throwable thrown) {
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        return failure;
    }

    private static Throwable fabrolia$closeTickHeadWindow(Throwable failure) {
        tickHeadFired = null;
        try {
            AdvancementBatchScope.end();
        } catch (Throwable cleanup) {
            failure = RegionScheduler.publishFatalAndSelectWinner(mergeFailure(failure, cleanup));
        }
        return failure;
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
        applyBatch(true);
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

    /** The worker lane: island players' already-matched trigger decisions.
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
        int total = 0;
        Pending[] arr;
        while ((arr = DEFERRED_PENDING.poll()) != null) {
            if (arr.length == 0) continue;
            batches.add(arr);
            total += arr.length;
        }
        if (batches.isEmpty()) return;
        // Flatten in publication order: one evaluation fan-out for the whole lane.
        Pending[] pending = new Pending[total];
        int at = 0;
        for (Pending[] batch : batches) {
            System.arraycopy(batch, 0, pending, at, batch.length);
            at += batch.length;
        }
        AdvancementDiag.lastBatchPendings = total;
        // Island players' ticks have no order between each other on Main.
        evaluateAndApply(pending, total, fired, false);
        // Batch grants can cascade frameless-main raises (reward functions);
        // settle them in the same window.
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


    /** Caller owns exactly one scheduler-admitted criterion body.
     *  @param awarded when non-null, records the player once award() is entered so the
     *                 caller re-evaluates that player's later pendings post-award */
    private static void fabrolia$applyAwardInAdmittedBody(
            ServerPlayer player,
            PlayerAdvancements.TriggerInstanceKey key,
            IdentityHashMap<ServerPlayer,
                    Set<PlayerAdvancements.TriggerInstanceKey>> fired,
            Set<ServerPlayer> awarded) {
        Set<PlayerAdvancements.TriggerInstanceKey> playerFired = fired.computeIfAbsent(
                player, k -> new HashSet<>());
        if (!playerFired.add(key) && fabrolia$stillGranted(player, key)) {
            AdvancementDiag.DEDUP_DROPPED.increment();
            return;
        }
        boolean[] granted = new boolean[1];
        fabrolia$runRewardBody(() -> granted[0] =
                player.getAdvancements().award(key.advancement(), key.criterion()));
        // Only a real grant (progress changed, rewards ran) invalidates later
        // evaluations; an already-done criterion changes nothing.
        if (granted[0] && awarded != null) awarded.add(player);
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
