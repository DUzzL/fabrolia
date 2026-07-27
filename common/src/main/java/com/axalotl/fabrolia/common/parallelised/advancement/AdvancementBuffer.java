package com.axalotl.fabrolia.common.parallelised.advancement;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import net.minecraft.advancements.predicates.ContextAwarePredicate;
import net.minecraft.advancements.triggers.CriterionTrigger;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.loot.LootContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Predicate;

/**
 * Deferred-mutation harness for {@link SimpleCriterionTrigger}. Mirrors the read/apply split
 * the user landed on for MFC datapack execution: while buffering is active for the current
 * thread, calls to {@code SimpleCriterionTrigger.trigger(ServerPlayer, Predicate)} stash
 * the (player, predicate, key-snapshot) tuple instead of executing inline; the surrounding
 * caller drains the buffer once it's done.
 *
 * <p>26.2 model change: vanilla moved the listener-set off the trigger (no more
 * {@code SimpleCriterionTrigger.players: Map<PlayerAdvancements, Set<Listener>>}) onto each
 * {@link PlayerAdvancements} ({@link PlayerAdvancements#getTriggerMapForType(net.minecraft.advancements.triggers.CriterionTrigger)
 * getTriggerMapForType} → {@code Map<TriggerInstanceKey, SimpleInstance>}). Grants go through
 * {@link PlayerAdvancements#award(net.minecraft.advancements.AdvancementHolder, String) award}
 * keyed on {@code (advancement, criterion)} instead of {@code listener.run(playerAdvancements)}.
 * The buffer now snapshots key+instance pairs from the per-player map and awards by key at
 * drain time. Semantics unchanged: per-drain dedup by {@code (player, TriggerInstanceKey)}
 * matches the old {@code (player, Listener)} dedup (TriggerInstanceKey identity is stable
 * within {@code activeTriggers} for the lifetime of the registration).
 *
 * <p>Threading model (frame-based — supports concurrently ticking players):
 * <ul>
 *   <li>Each thread owns a private {@link Frame} via a single ThreadLocal (one TL holding a
 *       mutable frame, fields toggled in place, never {@code remove()}d — TL set/remove on
 *       the hot path is the expensive part, plain get is ~10ns). {@link #enter}/{@link #exit}
 *       flip the frame's active flag; {@link #tryBuffer} appends to the calling thread's own
 *       buffer. Main thread and every region worker buffer disjointly, so region-mode player
 *       ticks (enableRegionPlayerTicks) need no shared-buffer coordination at all.</li>
 *   <li>If trigger() fires on a thread whose frame is inactive (a mod calling from a worker
 *       outside a player tick, cascaded triggers inside grant handlers, etc.) it falls
 *       straight through to the original implementation.</li>
 *   <li>{@link #drain} deactivates the frame BEFORE running/queueing grants, so cascaded
 *       triggers from inside grant handlers execute inline (no re-entrant buffering).</li>
 * </ul>
 *
 * <p>Two drain paths:
 * <ul>
 *   <li><b>Main thread</b> (vanilla fabrolia-advancements mode, players sync): eval phase fans
 *       out to the fabrolia pool, main pumps the barrier, grants apply serially right here —
 *       byte-for-byte the original behavior.</li>
 *   <li><b>Region worker</b> (players ticking on regions): the worker does no
 *       player-predicate work — it hands its player's pendings to {@link #applyDeferred}, which runs
 *       once on main at entity-batch end and drains ALL players' pendings as one merged
 *       batch (one parallel-eval fan-out + one pump per tick, instead of one per player).
 *       Grants must not run on workers: rewards execute functions/commands (MFC
 *       parallel-dispatch entry, loot, /tellraw) with main-thread assumptions. Timing
 *       shift vs vanilla: evals+grants land at the end of the same tick's entity phase
 *       instead of mid-player-tick — within-tick reordering of the same class the rest of
 *       the mod's fabrolia ticking already accepts, and the per-batch dedup below makes the
 *       result MORE deterministic than vanilla's mid-iteration grant bursts.</li>
 * </ul>
 *
 * <p>Snapshot semantics: the (key, instance) pairs are snapshotted into parallel arrays at
 * buffer time, so concurrent trigger-map mutations during the eval phase don't affect this
 * tick's grants. The per-trigger predicate is ALSO evaluated at buffer time, on the firing
 * thread (see {@link #buildPending}) — it closes over the trigger call's raw arguments,
 * which the caller may mutate or destroy the moment {@code trigger()} returns. Only the
 * {@code player:} ContextAwarePredicate (which reads live player state — same implicit
 * concurrent-read guarantees as the rest of the mod's fabrolia ticking) and the award are
 * deferred.
 */
public final class AdvancementBuffer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-Advancement");

    private static final class Frame {
        /**
         * Reentrancy depth instead of a boolean: the buffering window opens on BOTH
         * ServerPlayer.tick() and ServerPlayer.doTick(). Real players enter through
         * tick()→doTick() (nested, drains once at the outer exit — identical to the
         * old behavior); Carpet fake players (EntityPlayerMPFake) OVERRIDE tick() and
         * call doTick() directly, bypassing the tick() wrap entirely — without the
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

    /**
     * Pendings captured by region-worker drains; evaluated AND applied on main in
     * {@link #applyDeferred} as one merged batch. Workers do only the cheap fire-time
     * per-trigger test (buildPending), never the heavy player-predicate sweep —
     * inline eval on the region worker was tried first and serialized each player's
     * full listener sweep (BACAP TickTrigger ≈ thousands of listeners) onto a single
     * worker thread inside the entity phase, blowing the region-task tail to tens of
     * ms (tick median 23.7 → 52.5ms, live 2026-06-09). Deferring the raw pendings
     * instead gives ONE fan-out + ONE pump for ALL players per tick — strictly less
     * barrier traffic than even the original per-player main-thread drains.
     */
    private static final ConcurrentLinkedQueue<Pending[]> DEFERRED_PENDING = new ConcurrentLinkedQueue<>();

    /**
     * Frameless-MAIN pendings (2026-07-17 trigger-path redesign). Packet-driven
     * triggers on main outside any tick frame (player join initMenu, crafting,
     * attacks, /trigger) previously fell through to VANILLA INLINE — running
     * award → rewards → function fence inside the trigger call (and, before
     * 2026-07-17, inside the SyncAll trigger monitor: the production three-way
     * deadlock). Now they take the same read/apply split as every other
     * context: per-trigger predicate + player predicate evaluated AT FIRE TIME
     * here (exact vanilla read moment), award applied at the next main drain
     * point — outside every monitor, microseconds-to-one-pump later, FIFO.
     * Main-confined (enqueue and drain both main-only, no lock).
     */
    private static final ArrayDeque<Pending> MAIN_FRAMELESS = new ArrayDeque<>();

    /** Direct PlayerAdvancements.award/revoke calls arriving ON REGION WORKERS
     *  (mod paths bypassing triggers) — belt from PlayerAdvancementsMixin.
     *  Replayed on main in FIFO order at the same drain points. */
    private static final ConcurrentLinkedQueue<Runnable> DIRECT_MAIN = new ConcurrentLinkedQueue<>();

    /** Main-frame pendings PARKED for the per-tick batch apply (task #22,
     *  async mode): one eval fan-out + one pump + one dedup scope + one
     *  cache bind for ALL main-ticked players at the entity-batch end,
     *  replacing one drainOnMain barrier PER PLAYER PER TICK (~50 pumps/tick
     *  at 50 main players — the profiled 3ms ServerPlayer.doTick drain).
     *  Main-only. */
    private static final ArrayList<Pending> MAIN_TICK_BATCH = new ArrayList<>(256);

    private static final AtomicBoolean MAIN_DRAIN_SCHEDULED = new AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicLong fabrolia$lastRunawayWarn =
            new java.util.concurrent.atomic.AtomicLong();

    /** Eager streaming evals (2026-06-12): a region worker finishing its player's tick
     *  submits the predicate eval IMMEDIATELY to the pool instead of parking the raw
     *  pendings until batch end — eval wall-time hides inside the entity barrier while
     *  other regions still run. Grants still apply serially on main at batch end with
     *  the same cross-player dedup scope. */
    private record EagerEval(CompletableFuture<?> future,
                             ArrayList<Match> matches) {}

    private static final ConcurrentLinkedQueue<EagerEval> EAGER_EVALS = new ConcurrentLinkedQueue<>();

    /**
     * One per buffered {@code trigger()} call. {@code keys} and {@code instances} are parallel
     * arrays — {@code instances[i]} is the {@link SimpleCriterionTrigger.SimpleInstance}
     * registered under {@code keys[i]} in the player's trigger map at buffer time, filtered
     * to the instances that already PASSED the per-trigger predicate (evaluated at buffer
     * time — see {@link #buildPending}). Erased to {@code Object[]} for instances because
     * vanilla's typed predicate is also erased through a raw {@code Predicate.test} cast.
     * Deferred work per instance: the {@code player:} ContextAwarePredicate + the award.
     */
    private record Pending(ServerPlayer player,
                           LootContext lootContext,
                           PlayerAdvancements.TriggerInstanceKey[] keys,
                           Object[] instances) {}

    private record Match(ServerPlayer player, PlayerAdvancements.TriggerInstanceKey key) {}

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
        Frame f = FRAME.get();
        MinecraftServer s = ParallelProcessor.getServer();
        boolean onMain = s == null || s.isSameThread();
        if (f.depth == 0) {
            if (onMain) {
                // FRAMELESS MAIN (packet-driven triggers: joins, crafting,
                // attacks). Async OFF: vanilla inline is single-threaded and
                // byte-for-byte correct — keep it. Async ON: inline ran the
                // award chain (rewards → function fence) inside the trigger
                // call; awards must apply only at monitor-free drain points
                // (2026-07-17 deadlock redesign) — evaluate now, defer award.
                if (!com.axalotl.fabrolia.common.regions.RegionScheduler.anyAsyncActive()) {
                    return false;
                }
                Pending p = buildPending(player, predicate, trigger,
                        EntityPredicate.createContext(player, player));
                if (p != null) {
                    MAIN_FRAMELESS.addLast(p);
                    scheduleMainDrain(s);
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
            // ("Where are all your clothes?", 2026-07-07). Defer the raw pending to the
            // batch-end main drain instead: same dedup scope, evaluated + granted on
            // main. Triggers fired in phases after the entity batch (island BE ticks)
            // land next tick's drain — one tick late, never lost, never off-thread.
            Pending p = buildPending(player, predicate, trigger,
                    EntityPredicate.createContext(player, player));
            if (p != null) DEFERRED_PENDING.add(new Pending[]{p});
            return true;
        }
        if (onMain) {
            // Main-thread frame (players sync). Async OFF: buffering is a pure
            // optimization — honor the feature flag and pool availability,
            // vanilla inline otherwise (single-threaded, safe). Async ON:
            // buffering is MANDATORY here too (2026-07-17): inline would run
            // awards inside the trigger call while region workers exist; the
            // tick-exit drain applies them on main outside every monitor.
            if (!com.axalotl.fabrolia.common.regions.RegionScheduler.anyAsyncActive()) {
                if (!FabroliaConfig.enableFabroliaAdvancements) return false;
                ExecutorService exec = ParallelProcessor.executor;
                if (exec == null || exec.isShutdown()) return false;
            }
        }
        // Worker frame (player ticking on a region worker): buffering is MANDATORY
        // regardless of enableFabroliaAdvancements — falling through to vanilla inline would
        // run award() (grants → rewards → functions/commands) on the worker thread.
        // drainOnWorker evals inline and defers grants to the main-thread batch end.

        ArrayList<Pending> buf = f.buffer;
        // LootContext caching: a single ServerPlayer.tick fires multiple triggers (TICK,
        // LOCATION, advancement-progress timers, …), all with identical (player, player)
        // params. Vanilla rebuilds the LootContext per trigger() call. We reuse the same
        // instance across consecutive Pendings for the same player — the frame buffer is
        // thread-private, and each Pending is evaluated by one thread, so sharing is safe.
        //
        // Safety: the LootContext is read-only after createContext (params map immutable,
        // RandomSource is not touched by ContextAwarePredicate.matches for advancement
        // player-predicates in practice — they're entity/location/inventory checks, not
        // random_chance).
        LootContext lootContext;
        int last = buf.size() - 1;
        if (last >= 0 && buf.get(last).player == player) {
            lootContext = buf.get(last).lootContext;
        } else {
            lootContext = EntityPredicate.createContext(player, player);
        }
        Pending p = buildPending(player, predicate, trigger, lootContext);
        if (p != null) buf.add(p);
        return true;
    }

    /**
     * Snapshot the (key, instance) pairs of the player's trigger map that pass the
     * per-trigger predicate, which is evaluated HERE — at fire time, on the firing thread.
     *
     * <p>This must not be deferred: the per-trigger predicate closes over the trigger
     * call's raw arguments, and callers may mutate or destroy those the moment
     * {@code trigger()} returns. Canonical case: {@code ItemStack.applyDamage} fires
     * ITEM_DURABILITY_CHANGED with the live stack, then setDamageValue()s it and
     * shrink(1)s it to EMPTY when the item breaks — a deferred eval saw an empty (air)
     * stack, so every {@code durability: {max: 0}} armor-break criterion failed its item
     * check (live 2026-07-07: "Where are all your clothes?" never progressing, regardless
     * of enableFabroliaAdvancements — worker-side buffering is mandatory). Same staleness
     * class: consume/inventory triggers capturing live stacks and slot state, effects
     * triggers reading the live effect map. Fire-time eval is byte-for-byte vanilla's
     * read at vanilla's moment on the thread that owns the just-mutated state, and it is
     * cheap — the expensive part (the advancement JSON {@code player:} block's
     * LootItemCondition walk) stays deferred in {@link #evalRange}.
     *
     * @return the filtered Pending, or {@code null} when no instance passes — nothing to
     *         defer, the caller still reports the trigger call as handled.
     */
    private static Pending buildPending(ServerPlayer player, Predicate<?> predicate,
                                        CriterionTrigger<?> trigger,
                                        LootContext lootContext) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Predicate<Object> pred = (Predicate) predicate;
        // Trigger-map monitor (2026-07-13 production CME): this iteration runs
        // on the FIRING thread — including region workers (a bot shoved by a
        // region task fired ENTER_BLOCK mid-phase) — while MAIN (un)registers
        // criteria in the same plain HashMap as advancements progress. Pairs
        // with PlayerAdvancementsMixin, which takes this exact per-player
        // monitor around every trigger-map mutator. The section is a short
        // filtered copy; the predicate evals inside are the same cheap
        // arg-matches vanilla runs at this exact moment on this thread.
        synchronized (player.getAdvancements()) {
            // Map fetch moved INSIDE the monitor (2026-07-17 audit):
            // getTriggerMapForType is a bare HashMap.get on activeTriggers,
            // which MAIN mutates under registerListeners — a lock-free fetch
            // from a worker raced the outer-map resize (torn read), not just
            // the inner-map iteration the monitor already covered.
            Map<PlayerAdvancements.TriggerInstanceKey, ?> triggerMap =
                    player.getAdvancements().getTriggerMapForType(trigger);
            if (triggerMap == null || triggerMap.isEmpty()) return null;
            int n = triggerMap.size();
            PlayerAdvancements.TriggerInstanceKey[] keys = new PlayerAdvancements.TriggerInstanceKey[n];
            Object[] instances = new Object[n];
            int kept = 0;
            for (Map.Entry<PlayerAdvancements.TriggerInstanceKey, ?> e : triggerMap.entrySet()) {
                if (kept >= n) break;
                Object instance = e.getValue();
                try {
                    if (!pred.test(instance)) continue;
                } catch (Throwable t) {
                    LOGGER.error("Advancement predicate threw on player {}", player.getName().getString(), t);
                    continue;
                }
                keys[kept] = e.getKey();
                instances[kept] = instance;
                kept++;
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
     * Drains the calling thread's frame. On main: parallel eval + pump + serial apply
     * (original behavior). On a region worker: inline eval + queue grants for
     * {@link #applyDeferred}. Frame ownership is released before grants so cascaded
     * triggers execute inline.
     */
    public static void drain() {
        Frame f = FRAME.get();
        if (f.depth == 0) return;       // unbalanced — nothing to do
        if (--f.depth > 0) return;      // inner window (tick→doTick) — outer drains
        final ArrayList<Pending> buf = f.buffer;
        final int n = buf.size();
        if (n == 0) return;
        Pending[] pending = buf.toArray(new Pending[0]);
        buf.clear();

        MinecraftServer s = ParallelProcessor.getServer();
        if (s == null || s.isSameThread()) {
            if (com.axalotl.fabrolia.common.regions.RegionScheduler.anyAsyncActive()) {
                // task #22: no per-player barrier — park for the single
                // batch apply at entity-batch end (same tick, one pump).
                Collections.addAll(MAIN_TICK_BATCH, pending);
                AdvancementDiag.DRAIN_PARKS.increment();
            } else {
                AdvancementDiag.DRAIN_LEGACY.increment();
                drainOnMain(pending, n);
            }
        } else {
            drainOnWorker(pending);
        }
    }

    /** Region-worker path: submit the eval for THIS player's pendings to the pool right
     *  now (streams into the barrier window); fall back to the deferred merge when the
     *  pool is unavailable. No grants here — those stay on main at batch end. */
    private static void drainOnWorker(Pending[] pending) {
        ExecutorService exec = ParallelProcessor.executor;
        if (exec == null || exec.isShutdown()) {
            DEFERRED_PENDING.add(pending);
            return;
        }
        ArrayList<Match> out = new ArrayList<>(8);
        try {
            // runScopedConcurrent: this eval overlaps the region entity phase, so any pending
            // needing a full player/entity NBT save is re-routed to DEFERRED_PENDING (evalRange)
            // and evaluated at the quiesced batch-end — never racing live player state here.
            CompletableFuture<?> f = CompletableFuture.runAsync(
                    () -> AdvancementCacheContext.runScopedConcurrent(
                            () -> evalRange(pending, 0, pending.length, out)),
                    exec);
            EAGER_EVALS.add(new EagerEval(f, out));
        } catch (RejectedExecutionException rex) {
            DEFERRED_PENDING.add(pending);
        }
    }

    /** Control-flow signal (no stack trace): thrown by NbtPredicateMixin when a full NBT save
     *  would be needed during the concurrent drainOnWorker eval — caught in {@link #evalRange}
     *  to defer the whole pending to the safe batch-end drain. */
    public static final class DeferToMainSignal extends RuntimeException {
        public static final DeferToMainSignal INSTANCE = new DeferToMainSignal();
        private DeferToMainSignal() { super(null, null, false, false); }
    }

    private static void drainOnMain(Pending[] pending, int n) {
        drainOnMain(pending, n, null);
    }

    private static void drainOnMain(Pending[] pending, int n,
            IdentityHashMap<ServerPlayer, Set<PlayerAdvancements.TriggerInstanceKey>> fired) {
        // Bind the drain-scoped player-NBT cache (ScopedValue auto-unwinds on every path).
        AdvancementCacheContext.runScoped(() -> drainOnMainScoped(pending, n, fired));
    }

    private static void drainOnMainScoped(Pending[] pending, int n,
            IdentityHashMap<ServerPlayer, Set<PlayerAdvancements.TriggerInstanceKey>> fired) {
        {
            ExecutorService exec = ParallelProcessor.executor;
            if (exec == null || exec.isShutdown() || n < 2) {
                applyInline(pending, fired);
                return;
            }

            int pool = Math.max(1, ParallelProcessor.getPoolSize());
            // poolSize*4 batches with min 4 per batch — predicate.test() per listener is heavy
            // (ContextAwarePredicate.matches walks LootItemCondition list), so a single Pending
            // can take 100us+; smaller batches give better work-stealing across uneven listener
            // counts. Min 4 avoids over-scheduling when N is small.
            int batchSize = Math.max(4, (n + (pool << 2) - 1) / (pool << 2));
            int batchCount = (n + batchSize - 1) / batchSize;
            CompletableFuture<?>[] futures = new CompletableFuture<?>[batchCount];

            // Per-batch result lists (no contention on a shared queue): each worker fills its
            // own ArrayList, main thread concatenates after barrier. ConcurrentLinkedQueue's
            // CAS-per-add was ~10ns per match × thousands of matches × 31 workers contending =
            // measurable. The per-batch list is private to its worker, zero sync cost.
            @SuppressWarnings({"unchecked","rawtypes"})
            ArrayList<Match>[] perBatch = new ArrayList[batchCount];

            int dispatched = 0;
            for (int s = 0; s < n; s += batchSize) {
                final int from = s;
                final int to = Math.min(s + batchSize, n);
                final int idx = dispatched;
                ArrayList<Match> local = new ArrayList<>(Math.max(4, (to - from) >> 2));
                perBatch[idx] = local;
                try {
                    futures[dispatched++] = CompletableFuture.runAsync(() -> evalRange(pending, from, to, local), exec);
                } catch (RejectedExecutionException rex) {
                    // Pool refused; drop the parallel attempt and finish the rest inline.
                    evalRange(pending, from, n, local);
                    break;
                }
            }

            if (dispatched > 0) {
                CompletableFuture<?>[] active = dispatched == futures.length ? futures : Arrays.copyOf(futures, dispatched);
                // Route through ParallelProcessor.pumpUntilDone so we DRAIN the per-level
                // ServerChunkCache.mainThreadProcessor (chunk-system queue ONLY — never the
                // MinecraftServer task queue, which holds Carpet/mod tasks that would CME
                // the entity-tick loop). Without this, advancement-eval workers that hit
                // getChunk(loadOrGenerate=true) via Entity.getInBlockState (e.g. Sepals's
                // sepalsCramming → isPushable → onClimbable path inside Villager.tick)
                // queue a Tier 4 chunk-system ticket to mainThreadProcessor and park
                // forever — Server thread was here in plain CF.join, never draining the
                // queue. Identical signature to [[project_async15_getchunk_deadlock]].
                try {
                    ParallelProcessor.pumpUntilDone(CompletableFuture.allOf(active));
                } catch (Throwable t) {
                    LOGGER.error("Advancement eval batch failed", t);
                }
            }

            applyMatchesFromBatches(perBatch, dispatched, fired);
        }
    }

    private static void evalRange(Pending[] pending, int from, int to, ArrayList<Match> out) {
        for (int i = from; i < to; i++) {
            Pending p = pending[i];
            PlayerAdvancements.TriggerInstanceKey[] keys = p.keys;
            Object[] instances = p.instances;
            try {
                for (int li = 0, lm = keys.length; li < lm; li++) {
                    try {
                        // per-trigger predicate already passed at buffer time (buildPending);
                        // only the player: ContextAwarePredicate is evaluated here.
                        if (!matchesPlayerPredicate(instances[li], p.lootContext)) continue;
                        AdvancementDiag.MATCHES.increment();
                        out.add(new Match(p.player, keys[li]));
                    } catch (DeferToMainSignal defer) {
                        throw defer;   // pending-level: re-eval the whole pending safely
                    } catch (Throwable t) {
                        LOGGER.error("Advancement predicate threw on player {}", p.player.getName().getString(), t);
                    }
                }
            } catch (DeferToMainSignal defer) {
                // Concurrent phase can't safely full-save this player's NBT — re-evaluate the
                // whole pending at the quiesced batch-end (dedup makes any partial out[] entries
                // already added for this pending harmless: a re-grant of the same key is dropped).
                DEFERRED_PENDING.add(new Pending[]{p});
            }
        }
    }

    private static void applyInline(Pending[] pending,
            IdentityHashMap<ServerPlayer, Set<PlayerAdvancements.TriggerInstanceKey>> fired) {
        if (fired == null) {
            fired = new IdentityHashMap<>();
        }
        for (Pending p : pending) {
            applyPending(p, fired);
        }
    }

    private static void applyPending(Pending p,
                                     IdentityHashMap<ServerPlayer, Set<PlayerAdvancements.TriggerInstanceKey>> fired) {
        Set<PlayerAdvancements.TriggerInstanceKey> playerFired = fired.computeIfAbsent(
                p.player, k -> new HashSet<>());
        PlayerAdvancements.TriggerInstanceKey[] keys = p.keys;
        Object[] instances = p.instances;
        for (int li = 0, lm = keys.length; li < lm; li++) {
            try {
                // per-trigger predicate already passed at buffer time (buildPending)
                if (!matchesPlayerPredicate(instances[li], p.lootContext)) continue;
                PlayerAdvancements.TriggerInstanceKey key = keys[li];
                if (!playerFired.add(key) && fabrolia$stillGranted(p.player, key)) {
                    AdvancementDiag.DEDUP_DROPPED.increment();
                    continue;
                }
                AdvancementDiag.AWARDS.increment();
                p.player.getAdvancements().award(key.advancement(), key.criterion());
            } catch (Throwable t) {
                LOGGER.error("Advancement inline path failed on player {}", p.player.getName().getString(), t);
            }
        }
    }

    /** Players whose in-tick advancement flush was skipped because their tick
     *  ran on a region worker (task #15 production CME: vanilla flushDirty
     *  iterates progressChanged/rootsToUpdate/visible HashSets inside
     *  ServerPlayer.tick, while MAIN legally mutates the same sets via
     *  award()/revoke() from the buffer's drain points, packet triggers and
     *  commands — reader on worker, writer on main). Flushed ON MAIN at the
     *  entity-batch end AFTER {@link #applyDeferred}'s grants — one flush per
     *  player per tick, same-tick client visibility now INCLUDING the
     *  batch-end grants (vanilla's mid-tick flush missed those until the next
     *  tick), packet content = the tick's complete final state. */
    private static final ConcurrentLinkedQueue<ServerPlayer> PENDING_FLUSH = new ConcurrentLinkedQueue<>();

    public static void queueFlush(ServerPlayer player) {
        PENDING_FLUSH.add(player);
    }

    /** MAIN, entity-batch end, after grants: run the flushes workers skipped. */
    public static void drainPendingFlushes() {
        if (PENDING_FLUSH.isEmpty()) return;
        Set<ServerPlayer> done = Collections.newSetFromMap(new IdentityHashMap<>());
        ServerPlayer p;
        while ((p = PENDING_FLUSH.poll()) != null) {
            if (!done.add(p)) continue;
            try {
                if (!p.hasDisconnected()) {
                    AdvancementDiag.FLUSHES.increment();
                    p.getAdvancements().flushDirty(p, true);
                }
            } catch (Throwable t) {
                LOGGER.error("Deferred advancement flush failed for {}", p.getName().getString(), t);
            }
        }
    }

    /** Belt entry for PlayerAdvancementsMixin: a direct award/revoke arrived on
     *  a region worker — replay it on main at the next drain, FIFO. */
    public static void deferDirect(Runnable replay) {
        AdvancementDiag.DIRECT_REPLAYS.increment();
        DIRECT_MAIN.add(replay);
        scheduleMainDrain(ParallelProcessor.getServer());
    }

    private static void scheduleMainDrain(MinecraftServer s) {
        if (s != null && MAIN_DRAIN_SCHEDULED.compareAndSet(false, true)) {
            // MinecraftServer.execute is thread-safe; the task runs on main
            // during the current pump (packet-time raises apply within the
            // same processing burst — near-vanilla timing) or at the next
            // tick boundary. Belt drains at tick head + entity-batch end
            // cover any pump the scheduler misses.
            s.execute(AdvancementBuffer::drainMainFrameless);
        }
    }

    /**
     * MAIN-only. Applies frameless-main pendings and worker-side direct
     * award/revoke replays in FIFO order, looping until both queues are empty
     * so award cascades (reward functions raising new triggers, /advancement
     * grants inside rewards) settle within one drain. Every award here runs
     * with NO monitor held — reward functions hit the bounded command fence
     * from a clean stack.
     */
    public static void drainMainFrameless() {
        drainMainFrameless(null);
    }

    public static void drainMainFrameless(
            IdentityHashMap<ServerPlayer, Set<PlayerAdvancements.TriggerInstanceKey>> fired) {
        MAIN_DRAIN_SCHEDULED.set(false);
        if (MAIN_FRAMELESS.isEmpty() && DIRECT_MAIN.isEmpty()) return;
        if (fired == null) {
            fired = new IdentityHashMap<>();
        }
        int guard = 0;
        while (!MAIN_FRAMELESS.isEmpty() || !DIRECT_MAIN.isEmpty()) {
            if (++guard > 100_000) {
                // Backpressure, not a fault: an off-main bulk granter (mod
                // thread / script task spamming /advancement) can queue
                // millions of deferred awards; drain 100k per point and let
                // the rest carry — FIFO preserved, nothing lost. Rate-limited
                // WARN so a genuine cascade loop still surfaces.
                long now = System.nanoTime();
                long last = fabrolia$lastRunawayWarn.get();
                if (now - last > 5_000_000_000L && fabrolia$lastRunawayWarn.compareAndSet(last, now)) {
                    LOGGER.warn("advancement main drain budget hit ({} pendings, {} directs remain) — "
                            + "remainder drains at the next point (off-main bulk granting?)",
                            MAIN_FRAMELESS.size(), DIRECT_MAIN.size());
                }
                scheduleMainDrain(ParallelProcessor.getServer());
                return;
            }
            Pending p = MAIN_FRAMELESS.pollFirst();
            if (p != null) {
                applyPending(p, fired);
            }
            Runnable r = DIRECT_MAIN.poll();
            if (r != null) {
                try {
                    r.run();
                } catch (Throwable t) {
                    LOGGER.error("Deferred direct advancement mutation failed", t);
                }
            }
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
    // The N-fires-with-final-state pattern that Fabrolia15 buffering already produces is
    // semantically equivalent to a single fire for state-check reward functions (which
    // BACAP's inv_check_* and most reward chains are: they read current world state and
    // act on it idempotently). Dedup makes that explicit — one fire per (player, key)
    // per drain — and sidesteps the race regardless of downstream dispatch behavior.
    // Counter-style rewards (rare; "+1 counter per fire") would under-count under this
    // dedup; users with such packs should leave enableFabroliaAdvancements off.
    //
    // Dedup uses TriggerInstanceKey value-equality (record equals): keys with identical
    // (advancement, criterion) collapse even if some path produced fresh key instances.
    private static void applyMatchesFromBatches(ArrayList<Match>[] perBatch, int n,
            IdentityHashMap<ServerPlayer, Set<PlayerAdvancements.TriggerInstanceKey>> fired) {
        if (fired == null) {
            fired = new IdentityHashMap<>();
        }
        for (int b = 0; b < n; b++) {
            applyMatchList(perBatch[b], fired);
        }
    }

    /**
     * Main-thread, entity-batch end (ParallelProcessor.onEntityTickBatchEnd). Merges every
     * region-ticked player's pendings into one batch, runs the standard main drain on it
     * (parallel eval fan-out + pump + serial apply) — one barrier per tick for all players
     * instead of one per player. Dedup scope widens from per-drain to per-batch, which
     * only strengthens the revoke-rerun normalisation described above.
     */
    public static void applyDeferred() {
        long batchT0 = System.nanoTime();
        // ONE re-entrant safe scope around the complete batch (task #22): the
        // first reward FUNCTION takes the global fence sticky; everything
        // after it — nested functions, cascaded grants, the deferred lane —
        // runs inside the held window; end() releases exactly once. Function-
        // free batches take no quiesce at all.
        AdvancementBatchScope.begin();
        // Shared dedup scope across ALL lanes — one deterministic batch.
        IdentityHashMap<ServerPlayer, Set<PlayerAdvancements.TriggerInstanceKey>> fired =
                new IdentityHashMap<>();
        boolean any = false;
        try {
            if (!EAGER_EVALS.isEmpty()) {
                ArrayList<EagerEval> evals = new ArrayList<>(64);
                EagerEval ev;
                while ((ev = EAGER_EVALS.poll()) != null) {
                    evals.add(ev);
                }
                if (!evals.isEmpty()) {
                    any = true;
                    CompletableFuture<?>[] fs = new CompletableFuture<?>[evals.size()];
                    for (int i = 0; i < evals.size(); i++) {
                        fs[i] = evals.get(i).future();
                    }
                    try {
                        ParallelProcessor.pumpUntilDone(CompletableFuture.allOf(fs));
                    } catch (Throwable t) {
                        LOGGER.error("Eager advancement eval batch failed", t);
                    }
                    for (EagerEval e : evals) {
                        applyMatchList(e.matches(), fired);
                    }
                }
            }

            // Main-ticked players' parked pendings: ONE fan-out + pump for all.
            if (!MAIN_TICK_BATCH.isEmpty()) {
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

            if (any || !fired.isEmpty()) {
                AdvancementDiag.BATCH_APPLIES.increment();
                AdvancementDiag.lastBatchAffectedPlayers = fired.size();
                // One flush per affected player, after the whole batch — the
                // packet carries the tick's complete final state, same tick.
                for (ServerPlayer p : fired.keySet()) {
                    queueFlush(p);
                }
            }
        } finally {
            AdvancementBatchScope.end();
            AdvancementDiag.BARRIER_NANOS.add(System.nanoTime() - batchT0);
        }
    }

    /** The deferred lane (pendings whose eval needs a full player-NBT save).
     *  @return true when anything was processed. */
    private static boolean fabrolia$applyDeferredLane(
            IdentityHashMap<ServerPlayer, Set<PlayerAdvancements.TriggerInstanceKey>> fired) {
        if (DEFERRED_PENDING.isEmpty()) return false;
        // The entity-batch end is NOT a quiescent point under the deadline
        // drive — a full player save can race the player's next unit. Bounded
        // full quiesce (same discipline as the command/function fences); on
        // failure LEAVE the queue intact and retry next batch end — delayed a
        // tick, never lost, never evaluated against torn state. A sticky
        // window already held by this batch's reward functions is reused.
        if (com.axalotl.fabrolia.common.regions.RegionScheduler.anyAsyncActive()
                && !com.axalotl.fabrolia.common.regions.IslandCommands.fenceWindowHeld()) {
            boolean took = com.axalotl.fabrolia.common.regions.IslandCommands
                    .quiesceAllExcept(null, DEFERRED_FENCE_TRY_NANOS);
            if (!took) {
                int miss = ++deferredFenceMisses;
                if (miss == 100 || miss % 400 == 0) {
                    LOGGER.warn("[async-islands] deferred advancement drain still waiting for a "
                            + "quiesce window (attempt {}) — {} pending group(s) held over",
                            miss, DEFERRED_PENDING.size());
                }
                return false;
            }
            deferredFenceMisses = 0;
            AdvancementDiag.QUIESCE_TAKES.increment();
            try {
                drainDeferredHeld(fired);
            } finally {
                com.axalotl.fabrolia.common.regions.IslandCommands.releaseAllExcept();
            }
            return true;
        }
        drainDeferredHeld(fired);
        return true;
    }

    /** 50ms — matches the command/function fence bound. */
    private static final long DEFERRED_FENCE_TRY_NANOS = 50_000_000L;
    private static int deferredFenceMisses;

    /** Caller holds the fence (or async is inactive): full saves are legal here. */
    private static void drainDeferredHeld(
            IdentityHashMap<ServerPlayer, Set<PlayerAdvancements.TriggerInstanceKey>> fired) {
        ArrayList<Pending> all = new ArrayList<>(256);
        Pending[] arr;
        while ((arr = DEFERRED_PENDING.poll()) != null) {
            Collections.addAll(all, arr);
        }
        if (all.isEmpty()) return;
        Pending[] merged = all.toArray(new Pending[0]);
        drainOnMain(merged, merged.length, fired);
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
        try {
            var prog = player.getAdvancements().getOrStartProgress(key.advancement());
            var cp = prog.getCriterion(key.criterion());
            return cp == null || cp.isDone();
        } catch (Throwable t) {
            return true; // conservative: treat as duplicate on any API surprise
        }
    }

    private static void applyMatchList(ArrayList<Match> matches,
                                       IdentityHashMap<ServerPlayer, Set<PlayerAdvancements.TriggerInstanceKey>> fired) {
        if (matches == null || matches.isEmpty()) return;
        for (int i = 0, m = matches.size(); i < m; i++) {
            Match match = matches.get(i);
            Set<PlayerAdvancements.TriggerInstanceKey> playerFired = fired.computeIfAbsent(
                    match.player, k -> new HashSet<>());
            if (!playerFired.add(match.key) && fabrolia$stillGranted(match.player, match.key)) {
                AdvancementDiag.DEDUP_DROPPED.increment();
                continue;
            }
            try {
                AdvancementDiag.AWARDS.increment();
                match.player.getAdvancements().award(match.key.advancement(), match.key.criterion());
            } catch (Throwable t) {
                LOGGER.error("Advancement grant failed", t);
            }
        }
    }
}
