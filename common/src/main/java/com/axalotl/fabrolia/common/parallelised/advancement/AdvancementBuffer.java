package com.axalotl.fabrolia.common.parallelised.advancement;

import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.triggers.CriterionTrigger;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Advancement criteria are evaluated and awarded on the thread that owns the player,
 * at vanilla's position: inside {@code SimpleCriterionTrigger.trigger}, matcher and
 * {@code player:} condition of every listener first, then each matched award
 * synchronously (contract C1). Only work the owner may not do itself leaves it, into
 * ONE FIFO applied by physical Main (contract C2):
 * <ul>
 *   <li>a trigger fired off the player's owner (an attacker in a neighbouring cell
 *       while the player's own turn runs elsewhere, a lockstep worker, a pool
 *       thread): its matcher is resolved at fire time, the {@code player:}
 *       condition and the award at the drain;</li>
 *   <li>a trigger whose fire-time evaluation needs foreign state
 *       ({@link AdvancementCacheContext}): its condition (or, when the matcher
 *       needs it, the whole call) is evaluated at the drain;</li>
 *   <li>an award whose completion runs a reward the owner may not run
 *       ({@link AdvancementRewardRouting#completionNeedsFence}); its listener
 *       stays registered until the drain, so revoke-and-rerun counters see every
 *       fire;</li>
 *   <li>non-owner-local reward functions, loot grants and chat announcements
 *       ({@link AdvancementRewardRouting}).</li>
 * </ul>
 * The FIFO keeps each player's routed work in vanilla order: while a player has
 * routed work outstanding (a per-player backlog count), its later trigger calls
 * resolve their matcher at fire time and leave condition and award to the drain,
 * after that work.
 *
 * <p>Drain points (physical Main): the tick-head window before the
 * {@code #minecraft:tick} functions — the only point that acquires the global fence,
 * and only when a routed item needs it; each level's entity-batch end, which borrows
 * an already-held fence or runs when no island is live; {@code PlayerList.remove},
 * under the removal fence, before the player's advancements are saved; and server
 * stop. With nothing routed no scope takes a fence.
 */
public final class AdvancementBuffer {

    private AdvancementBuffer() {}

    /** Routed work for the Main lane, FIFO across all producers. */
    private static final ConcurrentLinkedQueue<Routed> ROUTED = new ConcurrentLinkedQueue<>();

    /** Outstanding routed items per PlayerAdvancements (identity keys); absent means zero. */
    private static final ConcurrentHashMap<PlayerAdvancements, Integer> BACKLOG = new ConcurrentHashMap<>();

    /** Sum of {@link #BACKLOG}: the per-trigger probe is one volatile read while nothing is routed. */
    private static final AtomicInteger BACKLOG_TOTAL = new AtomicInteger();

    // ------------------------------------------------------------------ trigger

    /** {@code SimpleCriterionTrigger.trigger} body. */
    public static void trigger(ServerPlayer player, Predicate<?> matcher, CriterionTrigger<?> trigger) {
        PlayerAdvancements advancements = player.getAdvancements();
        if (AdvancementOwnership.quiescentOutsidePass()) {
            AdvancementDiag.TRIGGERS_QUIESCENT.increment();
            // A routed item's own cascade nests synchronously, as vanilla; any other
            // quiescent caller (a fenced command) keeps behind the player's routed work.
            if (hasBacklog(advancements) && !AdvancementBatchScope.rewardFunctionActive()) {
                routeTrigger(player, advancements, matcher, trigger,
                        AdvancementDiag.Route.BACKLOG_TRIGGER);
            } else {
                triggerQuiescent(player, advancements, matcher, trigger);
            }
        } else if (AdvancementOwnership.ownsPlayer(player)) {
            AdvancementDiag.TRIGGERS_OWNER.increment();
            if (hasBacklog(advancements)) {
                // Behind routed work of this player: its player conditions and awards
                // must observe that work first, exactly as the drain applies it.
                routeTrigger(player, advancements, matcher, trigger,
                        AdvancementDiag.Route.BACKLOG_TRIGGER);
            } else {
                triggerOnOwner(player, advancements, matcher, trigger);
            }
        } else {
            routeTrigger(player, advancements, matcher, trigger,
                    AdvancementDiag.Route.OFF_OWNER_TRIGGER);
        }
    }

    /** Vanilla's trigger body: nothing is routed. */
    private static void triggerQuiescent(ServerPlayer player, PlayerAdvancements advancements,
                                         Predicate<?> matcher, CriterionTrigger<?> trigger) {
        Map<PlayerAdvancements.TriggerInstanceKey, ?> listeners = advancements.getTriggerMapForType(trigger);
        if (listeners == null || listeners.isEmpty()) return;
        LootContext context = EntityPredicate.createContext(player, player);
        @SuppressWarnings("unchecked")
        List<PlayerAdvancements.TriggerInstanceKey> matched =
                (List<PlayerAdvancements.TriggerInstanceKey>) AdvancementCacheContext.evaluateQuiescent(
                        AdvancementBuffer::collect, listeners, matcher, context);
        if (matched == null) return;
        for (int i = 0, n = matched.size(); i < n; i++) {
            PlayerAdvancements.TriggerInstanceKey key = matched.get(i);
            advancements.award(key.advancement(), key.criterion());
        }
    }

    /** Vanilla's trigger body on the owner, routing only what the owner may not run. */
    private static void triggerOnOwner(ServerPlayer player, PlayerAdvancements advancements,
                                       Predicate<?> matcher, CriterionTrigger<?> trigger) {
        Map<PlayerAdvancements.TriggerInstanceKey, ?> listeners = advancements.getTriggerMapForType(trigger);
        if (listeners == null || listeners.isEmpty()) return;
        LootContext context = EntityPredicate.createContext(player, player);
        Object evaluated = AdvancementCacheContext.evaluateAtFireTime(
                AdvancementBuffer::collect, listeners, matcher, context);
        if (evaluated == AdvancementCacheContext.ROUTE) {
            routeTrigger(player, advancements, matcher, trigger,
                    AdvancementDiag.Route.FOREIGN_READ_TRIGGER);
            return;
        }
        @SuppressWarnings("unchecked")
        List<PlayerAdvancements.TriggerInstanceKey> matched =
                (List<PlayerAdvancements.TriggerInstanceKey>) evaluated;
        if (matched == null) return;
        awardOnOwner(advancements, matched);
    }

    /**
     * Awards one trigger call's fire-time match list in vanilla order on the owner.
     * A completion whose reward the owner may not run is routed whole — its listener
     * stays registered, so a refire before the drain is seen — and every later award
     * of the list follows it through the FIFO.
     */
    static void awardOnOwner(PlayerAdvancements advancements,
                             List<PlayerAdvancements.TriggerInstanceKey> matched) {
        for (int i = 0, n = matched.size(); i < n; i++) {
            PlayerAdvancements.TriggerInstanceKey key = matched.get(i);
            if (hasBacklog(advancements)) {
                publish(new RoutedAward(advancements, key), AdvancementDiag.Route.AWARD_BACKLOG);
            } else if (AdvancementRewardRouting.completionNeedsFence(
                    advancements, key.advancement(), key.criterion())) {
                publish(new RoutedAward(advancements, key), AdvancementDiag.Route.AWARD_GLOBAL_REWARD);
            } else {
                advancements.award(key.advancement(), key.criterion());
                AdvancementDiag.AWARDS_INLINE.increment();
            }
        }
    }

    /**
     * Route one trigger call: snapshot the listeners under the advancements monitor
     * and keep those passing the per-trigger matcher (its arguments are only valid
     * now); the {@code player:} condition and the award run at the drain against the
     * fire-time loot context. A matcher that itself needs foreign state routes the
     * whole call.
     */
    private static void routeTrigger(ServerPlayer player, PlayerAdvancements advancements,
                                     Predicate<?> matcher, CriterionTrigger<?> trigger,
                                     AdvancementDiag.Route route) {
        Object evaluated = AdvancementCacheContext.evaluateAtFireTime(
                AdvancementBuffer::buildPending, player, matcher, trigger);
        if (evaluated == AdvancementCacheContext.ROUTE) {
            publish(new DeferredTrigger(advancements, player, matcher, trigger),
                    AdvancementDiag.Route.FOREIGN_READ_MATCHER);
        } else if (evaluated != null) {
            publish((PendingTrigger) evaluated, route);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object collect(Map<PlayerAdvancements.TriggerInstanceKey, ?> listeners,
                                  Predicate<?> matcher, LootContext context) {
        Predicate<Object> test = (Predicate) matcher;
        List<PlayerAdvancements.TriggerInstanceKey> matched = null;
        int inspected = 0;
        for (Map.Entry<PlayerAdvancements.TriggerInstanceKey, ?> entry : listeners.entrySet()) {
            inspected++;
            Object instance = entry.getValue();
            if (test.test(instance) && matchesPlayerPredicate(instance, context)) {
                if (matched == null) matched = new ArrayList<>(4);
                matched.add(entry.getKey());
            }
        }
        AdvancementDiag.ENTRIES_INSPECTED.add(inspected);
        if (matched != null) AdvancementDiag.MATCHES.add(matched.size());
        return matched;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object buildPending(ServerPlayer player, Predicate<?> matcher,
                                       CriterionTrigger<?> trigger) {
        Predicate<Object> test = (Predicate) matcher;
        PlayerAdvancements advancements = player.getAdvancements();
        synchronized (advancements) {
            Map<PlayerAdvancements.TriggerInstanceKey, ?> listeners =
                    advancements.getTriggerMapForType(trigger);
            if (listeners == null || listeners.isEmpty()) return null;
            int n = listeners.size();
            PlayerAdvancements.TriggerInstanceKey[] keys = new PlayerAdvancements.TriggerInstanceKey[n];
            Object[] instances = new Object[n];
            int kept = 0;
            for (Map.Entry<PlayerAdvancements.TriggerInstanceKey, ?> entry : listeners.entrySet()) {
                if (kept >= n) break;
                Object instance = entry.getValue();
                if (test.test(instance)) {
                    keys[kept] = entry.getKey();
                    instances[kept] = instance;
                    kept++;
                }
            }
            AdvancementDiag.ENTRIES_INSPECTED.add(n);
            if (kept == 0) return null;
            if (kept < n) {
                keys = Arrays.copyOf(keys, kept);
                instances = Arrays.copyOf(instances, kept);
            }
            return new PendingTrigger(advancements, player,
                    EntityPredicate.createContext(player, player), keys, instances);
        }
    }

    // Vanilla SimpleCriterionTrigger.trigger gates each listener on BOTH the matcher
    // AND the SimpleInstance.player() condition (the advancement JSON's `player:` block).
    private static boolean matchesPlayerPredicate(Object instance, LootContext context) {
        if (instance instanceof SimpleCriterionTrigger.SimpleInstance simple) {
            Optional<Holder<LootItemCondition>> condition = simple.player();
            return condition.isEmpty() || condition.get().value().test(context);
        }
        return true;
    }

    // ------------------------------------------------------------------ routed work

    sealed interface Routed permits PendingTrigger, DeferredTrigger, RoutedAward, RoutedReward,
            RoutedGrant, RoutedAnnouncement, RoutedStat {
        /** Whose per-player FIFO position this item holds; {@code null} when untracked. */
        PlayerAdvancements backlogOwner();

        boolean needsFence();

        void apply();
    }

    record PendingTrigger(PlayerAdvancements advancements, ServerPlayer player, LootContext context,
                          PlayerAdvancements.TriggerInstanceKey[] keys, Object[] instances)
            implements Routed {
        public PlayerAdvancements backlogOwner() { return advancements; }

        public boolean needsFence() { return true; }

        public void apply() {
            ServerPlayer target = currentPlayer(player);
            LootContext evaluation = target == player ? context : EntityPredicate.createContext(target, target);
            @SuppressWarnings("unchecked")
            List<PlayerAdvancements.TriggerInstanceKey> matched =
                    (List<PlayerAdvancements.TriggerInstanceKey>) AdvancementCacheContext.evaluateQuiescent(
                            PendingTrigger::matchConditions, keys, instances, evaluation);
            if (matched == null) return;
            AdvancementDiag.MATCHES.add(matched.size());
            for (int i = 0, n = matched.size(); i < n; i++) {
                PlayerAdvancements.TriggerInstanceKey key = matched.get(i);
                advancements.award(key.advancement(), key.criterion());
            }
        }

        private static Object matchConditions(PlayerAdvancements.TriggerInstanceKey[] keys, Object[] instances,
                                              LootContext evaluation) {
            List<PlayerAdvancements.TriggerInstanceKey> hits = null;
            for (int i = 0; i < keys.length; i++) {
                if (matchesPlayerPredicate(instances[i], evaluation)) {
                    if (hits == null) hits = new ArrayList<>(4);
                    hits.add(keys[i]);
                }
            }
            return hits;
        }
    }

    record DeferredTrigger(PlayerAdvancements advancements, ServerPlayer player, Predicate<?> matcher,
                           CriterionTrigger<?> trigger) implements Routed {
        public PlayerAdvancements backlogOwner() { return advancements; }

        public boolean needsFence() { return true; }

        public void apply() {
            triggerQuiescent(currentPlayer(player), advancements, matcher, trigger);
        }
    }

    record RoutedAward(PlayerAdvancements advancements, PlayerAdvancements.TriggerInstanceKey key)
            implements Routed {
        public PlayerAdvancements backlogOwner() { return advancements; }

        public boolean needsFence() { return true; }

        public void apply() {
            advancements.award(key.advancement(), key.criterion());
        }
    }

    record RoutedReward(PlayerAdvancements advancements, CommandFunction<CommandSourceStack> function,
                        CommandSourceStack source) implements Routed {
        public PlayerAdvancements backlogOwner() { return advancements; }

        public boolean needsFence() { return true; }

        public void apply() {
            CommandSourceStack effective = source;
            if (source.getEntity() instanceof ServerPlayer captured) {
                ServerPlayer current = currentPlayer(captured);
                // Vanilla builds the reward source from the player it rewards now.
                if (current != captured) effective = AdvancementRewardRouting.rewardSource(current);
            }
            effective.getServer().getFunctions().execute(function, effective);
        }
    }

    record RoutedGrant(PlayerAdvancements advancements, ServerPlayer player, AdvancementRewards rewards)
            implements Routed {
        public PlayerAdvancements backlogOwner() { return advancements; }

        public boolean needsFence() { return true; }

        public void apply() {
            rewards.grant(currentPlayer(player));
        }
    }

    /** Main owns the player list; the broadcast needs no fence and changes no player state. */
    /**
     * A stat change from a thread that does not own the player (breeding love
     * cause, kill credit, raid victory on another island). Stats are owner-local
     * state ({@link com.axalotl.fabrolia.common.regions.PlayerTurnOwnership}); the
     * change keeps its place in the player's FIFO and applies on Main under the
     * fence, at most one tick later (audit D-12).
     */
    record RoutedStat(PlayerAdvancements advancements, ServerPlayer player,
                      net.minecraft.stats.Stat<?> stat, int count, boolean reset) implements Routed {
        public PlayerAdvancements backlogOwner() { return advancements; }

        public boolean needsFence() { return true; }

        public void apply() {
            ServerPlayer target = currentPlayer(player);
            if (reset) target.resetStat(stat);
            else target.awardStat(stat, count);
        }
    }

    /** Off-owner stat change: see {@link RoutedStat}. */
    public static void routeStat(ServerPlayer player, net.minecraft.stats.Stat<?> stat, int count, boolean reset) {
        publish(new RoutedStat(player.getAdvancements(), player, stat, count, reset),
                AdvancementDiag.Route.OFF_OWNER_STAT);
    }

    record RoutedAnnouncement(PlayerList playerList, Component message, boolean overlay) implements Routed {
        public PlayerAdvancements backlogOwner() { return null; }

        public boolean needsFence() { return false; }

        public void apply() {
            playerList.broadcastSystemMessage(message, overlay);
        }
    }

    /**
     * The lifecycle object vanilla would reward now. Respawn replaces the
     * ServerPlayer but keeps its PlayerAdvancements; the player list maps the UUID
     * to the current object until {@code PlayerList.remove}, which drains first.
     */
    static ServerPlayer currentPlayer(ServerPlayer captured) {
        if (!captured.isRemoved()) return captured;
        MinecraftServer server = com.axalotl.fabrolia.common.ParallelProcessor.getServer();
        PlayerList players = server == null ? null : server.getPlayerList();
        ServerPlayer current = players == null ? null : players.getPlayer(captured.getUUID());
        return current != null ? current : captured;
    }

    static boolean hasBacklog(PlayerAdvancements advancements) {
        return advancements != null && BACKLOG_TOTAL.get() != 0 && BACKLOG.containsKey(advancements);
    }

    /** Publish one routed item; its backlog position is counted before it is visible. */
    static void publish(Routed item, AdvancementDiag.Route route) {
        PlayerAdvancements owner = item.backlogOwner();
        if (owner != null) {
            BACKLOG_TOTAL.incrementAndGet();
            BACKLOG.merge(owner, 1, Integer::sum);
        }
        RegionScheduler.runSuccessBodyIfHealthy(() -> ROUTED.add(item));
        AdvancementDiag.routed(route);
    }

    /** Main-only, inside the batch scope. Stops at a fence-requiring item it may not fence. */
    private static void drainRouted(AdvancementDiag.Site site, boolean mayAcquireFence) {
        for (;;) {
            Routed head = ROUTED.peek();
            if (head == null) return;
            if (head.needsFence() && !AdvancementOwnership.quiescentContext()) {
                if (!mayAcquireFence) return;
                AdvancementBatchScope.ensureEvaluationFence(site);
            }
            // One admission per detached item: its detach and its vanilla body
            // cannot be split by a peer fatal.
            if (!RegionScheduler.callSuccessBooleanIfHealthy(AdvancementBuffer::applyOneRouted)) return;
        }
    }

    private static boolean applyOneRouted() {
        Routed item = ROUTED.poll();
        if (item == null) return false;
        // A drain nested in a running command (a kick reaching PlayerList.remove)
        // applies the item as its own top-level execution, never queued behind
        // that command where it would run after the leaving player was saved.
        fabrolia$runRewardBody(() -> CommandContextIsolation.runTopLevel(item::apply));
        PlayerAdvancements owner = item.backlogOwner();
        if (owner != null) {
            BACKLOG.computeIfPresent(owner, (ignored, count) -> count > 1 ? count - 1 : null);
            BACKLOG_TOTAL.decrementAndGet();
        }
        AdvancementDiag.ROUTED_APPLIED.increment();
        return true;
    }

    /**
     * Narrow capability around one serial vanilla advancement mutation which
     * may synchronously dispatch a reward function. The broad batch scope is
     * also active during fence acquisition and its chunk-callback pump; keeping
     * this separate prevents unrelated re-entrant Main work from borrowing the
     * clean unbounded function fence. Cleanup never contains the failure: the
     * exact body primary is rethrown after the depth token is retired.
     */
    private static void fabrolia$runRewardBody(Runnable body) {
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

    /** Runs one drain inside a fresh exact batch scope; end() releases a fence it took. */
    private static void runInScope(Runnable drain) {
        long start = System.nanoTime();
        Throwable failure = null;
        AdvancementBatchScope.begin();
        try {
            drain.run();
            RegionScheduler.throwWorkerFailureIfAny();
        } catch (Throwable thrown) {
            // A sticky scope may own the global fence. Select W while that
            // ownership is still closed; end() is mandatory cleanup.
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        try {
            AdvancementBatchScope.end();
        } catch (Throwable cleanup) {
            failure = RegionScheduler.publishFatalAndSelectWinner(mergeFailure(failure, cleanup));
        }
        if (failure != null) {
            AdvancementBuffer.<RuntimeException>rethrow(failure);
        }
        fabrolia$commitBarrierNanos(start);
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

    // ------------------------------------------------------------------ drain points

    /**
     * MAIN, level entity-batch end. Never acquires the fence: applies routed work
     * only while a held fence can be borrowed or no island is live (lockstep and
     * pool producers then apply at the nearest point after their entity phase),
     * plus fence-free announcements at the FIFO head.
     */
    public static void applyDeferred() {
        if (ROUTED.isEmpty()) return;
        if (IslandCommands.globalFenceTransitionInProgress() || AdvancementBatchScope.active()) return;
        runInScope(() -> drainRouted(AdvancementDiag.Site.BATCH_END, false));
    }

    /**
     * MAIN, tick head, immediately before the {@code #minecraft:tick} functions. Applies
     * routed work under one sticky fence, taken only when a routed item needs it; with
     * tick functions queued the window takes that fence anyway so the functions share
     * it ({@code fenceWindowHeld} → inline) instead of quiescing once per function. A
     * {@code true} return MUST be paired with {@link #endTickHeadWindow}.
     *
     * @param tickFunctionsQueued whether {@code ServerFunctionManager} will execute at
     *                            least one tick-tag function on Main in this window
     * @return whether this call owns the window
     */
    public static boolean beginTickHeadWindow(boolean tickFunctionsQueued) {
        RegionScheduler.throwWorkerFailureIfAny();
        if (IslandCommands.globalFenceTransitionInProgress()
                || AdvancementBatchScope.active()) {
            // A nested Main pump owns this position (an acquiring fence's
            // callbacks, or a re-entered tick()). The next head applies.
            return false;
        }
        long start = System.nanoTime();
        Throwable failure = null;
        AdvancementBatchScope.begin();
        try {
            AdvancementDiag.HEAD_WINDOWS.increment();
            if (!ROUTED.isEmpty()) {
                AdvancementDiag.HEAD_WINDOWS_ROUTED.increment();
                drainRouted(AdvancementDiag.Site.HEAD_ROUTED, true);
            }
            if (tickFunctionsQueued) {
                AdvancementBatchScope.ensureEvaluationFence(AdvancementDiag.Site.HEAD_TICK_FUNCTIONS);
            }
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
     * Mandatory pair of a {@code true} {@link #beginTickHeadWindow}. Applies work the
     * tick functions routed while the window still holds its fence (never acquiring a
     * new one: islands' later publications wait for the next head), releases the sticky
     * fence, and returns {@code primary} merged with any cleanup failure.
     */
    public static Throwable endTickHeadWindow(Throwable primary) {
        long start = System.nanoTime();
        Throwable failure = primary;
        if (failure == null) {
            try {
                drainRouted(AdvancementDiag.Site.HEAD_ROUTED, false);
                RegionScheduler.throwWorkerFailureIfAny();
            } catch (Throwable thrown) {
                failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
            }
        }
        failure = fabrolia$closeTickHeadWindow(failure);
        if (failure != null) return failure;
        try {
            fabrolia$commitBarrierNanos(start);
        } catch (Throwable thrown) {
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        return failure;
    }

    private static Throwable fabrolia$closeTickHeadWindow(Throwable failure) {
        try {
            AdvancementBatchScope.end();
        } catch (Throwable cleanup) {
            failure = RegionScheduler.publishFatalAndSelectWinner(mergeFailure(failure, cleanup));
        }
        return failure;
    }

    /**
     * HEAD of vanilla {@code PlayerList.remove}, before it saves the player: apply
     * every routed item (FIFO order across players) so no award, reward or revoke of
     * the leaving player lands on an already-saved, detached PlayerAdvancements.
     * Fabrolia's removal wrapper holds the global fence whenever islands are live.
     */
    public static void drainBeforeRemoval(ServerPlayer player) {
        if (ROUTED.isEmpty()) return;
        if (!AdvancementOwnership.quiescentContext()) {
            throw new IllegalStateException("routed advancement work cannot be applied before "
                    + player.getScoreboardName() + " is saved: PlayerList.remove runs without"
                    + " quiescent Main ownership");
        }
        if (AdvancementBatchScope.active()) {
            // Removal reached from inside a drain (a routed reward kicked a player):
            // continue the same FIFO in the enclosing scope.
            drainRouted(AdvancementDiag.Site.REMOVE, false);
            return;
        }
        runInScope(() -> drainRouted(AdvancementDiag.Site.REMOVE, false));
    }

    /**
     * Physical-Main lifecycle progress. Normal drains remain the timing authority;
     * this closes work already retained when the server leaves its final tick (or
     * when region threading was disabled and has no stop-progress callback).
     *
     * <p>The emptiness probe is only an optimization. RegionWorkerPool's shutdown
     * protocol requires a second zero-ledger progress pass, so a publication which
     * becomes visible immediately after this probe is picked up by the next pass.
     */
    public static void drainShutdownPassIfPending() {
        MinecraftServer server = com.axalotl.fabrolia.common.ParallelProcessor.getServer();
        if (server != null && !RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)) {
            throw new IllegalStateException("advancement shutdown drain entered off physical Main");
        }
        if (ROUTED.isEmpty()) return;
        if (AdvancementBatchScope.active()) {
            drainRouted(AdvancementDiag.Site.SHUTDOWN, true);
            return;
        }
        runInScope(() -> drainRouted(AdvancementDiag.Site.SHUTDOWN, true));
    }

    /**
     * Healthy server-generation retirement. Nothing here discards simulation work:
     * the FIFO must already be empty after the causal fixed point and the shared
     * executor join. A surviving item is a shutdown failure.
     */
    public static void verifyEmptyAfterShutdown() {
        MinecraftServer server = com.axalotl.fabrolia.common.ParallelProcessor.getServer();
        if (server != null && !RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)) {
            throw new IllegalStateException("advancement shutdown verification entered off physical Main");
        }
        if (!ROUTED.isEmpty() || !BACKLOG.isEmpty() || BACKLOG_TOTAL.get() != 0
                || !AdvancementBatchScope.shutdownClean()) {
            throw new IllegalStateException("advancement work survived the healthy shutdown fixed point"
                    + " (routed=" + ROUTED.size() + ", backlog=" + BACKLOG.size()
                    + ", backlogTotal=" + BACKLOG_TOTAL.get() + ")");
        }
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
