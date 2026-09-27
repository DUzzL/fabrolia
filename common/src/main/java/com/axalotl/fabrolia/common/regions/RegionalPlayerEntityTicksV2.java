package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.axalotl.fabrolia.common.mixin.server.ServerPlayerGameModeAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.level.block.NetherPortalBlock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared player-tick gate plus the synchronous exact-ordinal fallback.
 *
 * <p>The single runtime gate moves only the exact vanilla entity-phase consumer
 * ({@code guardEntityTick -> tickNonPassenger -> ServerPlayer.tick}) for a narrow,
 * revalidated real-player envelope. The physical main thread remains parked at that
 * exact EntityTickList ordinal, the region pool is fenced, and one serial actor owns
 * the logical server turn until the consumer returns. {@code tickPlayer/doTick} and
 * serverbound packet handling remain on physical main. The mutable runtime gate remains
 * here, while {@link FabroliaConfig#enablePlayerTickActor} stores the operator preference
 * restored after scheduler startup.  With autonomous islands active the same gate is
 * consumed by {@link AsyncIslandPlayerTicks}; this actor remains the wall/sync venue.
 */
public final class RegionalPlayerEntityTicksV2 {

    private RegionalPlayerEntityTicksV2() {}

    private static volatile boolean enabled;
    private static final AtomicLong NEXT_PHASE = new AtomicLong(1L);
    private static final AtomicReference<GlobalPhase> CURRENT = new AtomicReference<>();
    private static final GlobalPhase DISABLED_PHASE = new GlobalPhase(0L, false, null);
    private static final ThreadLocal<TickContext> TICK_CONTEXT = new ThreadLocal<>();
    private static final ThreadLocal<LevelBatch> EXACT_LEVEL = new ThreadLocal<>();
    private static final PlayerTickActor EXACT_ACTOR =
            new PlayerTickActor("Fabrolia-Player-Region-Actor");
    /** One atomic publication prevents torn server/thread ownership and turn ABA. */
    private static final AtomicReference<LogicalTurn> LOGICAL_TURN = new AtomicReference<>();
    private static final ThreadLocal<ArrayDeque<ServerPlayer>> PLAYER_CONSUMER_SCOPE =
            new ThreadLocal<>();

    public static boolean enabled() {
        return enabled;
    }

    /**
     * MinecraftServer#getRunningThread override while Main is parked for an exact
     * selected gameplay root or V2 entity ordinal. This logical identity is separate
     * from V2's narrower advancement bypass and exact-player accounting.
     */
    public static Thread logicalServerThread(MinecraftServer server) {
        LogicalTurn turn = LOGICAL_TURN.get();
        return turn != null && turn.server == server
                && RegionScheduler.isExactPlayerTurnPausedBy(turn.physicalMain)
                ? turn.actor : null;
    }

    public static boolean inExactPlayerActor() {
        LogicalTurn turn = LOGICAL_TURN.get();
        return turn != null && Thread.currentThread() == turn.actor
                && RegionScheduler.isExactPlayerTurnPausedBy(turn.physicalMain);
    }

    /** Pure handoff identity check; safe inside the pool's fatal/admission monitor. */
    static boolean exactActorHandoffMatches(Thread physicalMain, Thread actor) {
        LogicalTurn turn = LOGICAL_TURN.get();
        return turn != null && turn.physicalMain == physicalMain && turn.actor == actor
                && Thread.currentThread() == actor;
    }

    /** Pure lookup; the pool cut performs the authoritative pause/fatal validation. */
    static Thread exactActorPhysicalOwnerOrNull() {
        LogicalTurn turn = LOGICAL_TURN.get();
        return turn != null && Thread.currentThread() == turn.actor
                ? turn.physicalMain : null;
    }

    /** Physical-main authority is intentionally distinct from logical isSameThread(). */
    public static boolean isPhysicalServerThread(MinecraftServer server) {
        if (server == null) return false;
        GlobalPhase phase = CURRENT.get();
        if (phase != null && phase.enabledSnapshot && phase.server == server) {
            return Thread.currentThread() == phase.mainThread;
        }
        return server.isSameThread();
    }

    /** Scheduler generations may change only between complete tickChildren phases. */
    public static boolean schedulerLifecycleTransitionAllowed(MinecraftServer server) {
        return isPhysicalServerThread(server) && CURRENT.get() == null;
    }

    /**
     * A level may publish autonomous ownership only when this wall tick did not
     * already snapshot the synchronous exact-ordinal venue. This is a pure
     * observation: the existing phase remains the owner until finish/abort.
     */
    static boolean asyncActivationAllowedAtLevelHead() {
        GlobalPhase phase = CURRENT.get();
        return phase == null || !phase.enabledSnapshot;
    }

    /** Server shutdown hook; accepted bodies are never abandoned. */
    public static void shutdownActor() {
        if (inExactPlayerActor()) {
            throw new IllegalStateException("exact player actor cannot shut itself down");
        }
        EXACT_ACTOR.shutdownAndAwait();
    }

    /** One authoritative runtime gate. Safety predicates are not feature gates. */
    public static boolean setEnabled(MinecraftServer server, boolean value) {
        // Advancement reward functions and mod callbacks can execute commands from
        // inside the exact actor body. A gate transition is a server-phase
        // operation: never let such a callback split the current phase or stop the
        // worker pool from one of its own threads.
        if (server != null && !isPhysicalServerThread(server)) return false;
        GlobalPhase active = CURRENT.get();
        if (active != null && active.enabledSnapshot) active.throwIfFailed();
        if (value && refusalReason(server) != null) return false;

        // A live A5 transition is an explicit operator boundary.  Quiesce every
        // autonomous island once so no early/late transaction straddles the volatile
        // gate publication. Ordinary ticks never take this global fence.
        ArrayList<RegionizedServer> quiesced = new ArrayList<>();
        Throwable failure = null;
        try {
            if (server != null && RegionScheduler.anyAsyncActive()) {
                for (RegionizedServer rs : RegionScheduler.allAsync()) {
                    if (rs.quiesceAllIslands()) quiesced.add(rs);
                }
            }
            // Claim ownership and global-failure admission are separate cuts. A
            // peer can select W after the last island claim is acquired, so the
            // actual A5 lifecycle mutation must atomically win body entry too.
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                if (!value) {
                    // Keep the old gate published until every exact A5 session has
                    // entered MAIN_HANDOFF. Netty then continues appending to that old
                    // FIFO until physical Main closes it; publishing OFF first allowed a
                    // vanilla Main task to overtake already-intercepted packets.
                    for (RegionizedServer rs : RegionScheduler.allAsync()) {
                        for (IslandRegistry.PIsland island : rs.islandRegistry.islands()) {
                            AsyncIslandPlayerTicks.clearIsland(island);
                        }
                    }
                }
                enabled = value;
            });
        } catch (Throwable thrown) {
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        for (int i = quiesced.size() - 1; i >= 0; i--) {
            try {
                // Pass the selected body W through every release so cleanup is
                // exhaustive without ever reopening admission between levels.
                failure = quiesced.get(i).releaseAllIslands(failure);
            } catch (Throwable cleanup) {
                failure = RegionScheduler.publishFatalAndSelectWinner(
                        mergeFailure(failure, cleanup));
            }
        }
        if (failure == null) {
            try {
                // Claim release is mandatory cleanup, not permission to report a
                // successful gate transition. Refuse normal return if W won there.
                RegionScheduler.commitSuccessIfHealthy(() -> {});
            } catch (Throwable peerFailure) {
                failure = RegionScheduler.publishFatalAndSelectWinner(peerFailure);
            }
        }
        rethrow(failure);
        return true;
    }

    /**
     * Applies the persisted operator preference through the same fail-closed runtime
     * transition used by the command. This must run only after the region scheduler has
     * published an active generation. A saved ON which is currently unsafe remains saved,
     * but the runtime gate is normalized to OFF until a later safe startup/reload.
     */
    public static boolean applyConfiguredPreference(MinecraftServer server) {
        if (!isPhysicalServerThread(server)) return false;
        if (!FabroliaConfig.enablePlayerTickActor) {
            return setEnabled(server, false);
        }
        if (setEnabled(server, true)) return true;
        setEnabled(server, false);
        return false;
    }

    public static String refusalReason(MinecraftServer server) {
        if (server == null) return "server is unavailable";
        if (!isPhysicalServerThread(server)) {
            return "player-tick mode may only change on the physical server thread";
        }
        if (FabroliaConfig.disabled || !FabroliaConfig.enableRegionThreading
                || !RegionScheduler.isActive()) {
            return "region threading is not active";
        }
        // Autonomous async levels use A5 and do not depend on the singleton exact actor.
        if (!FabroliaConfig.asyncIslands && !RegionScheduler.anyAsyncActive()
                && !EXACT_ACTOR.available()) {
            return "exact player actor is unavailable; restart the server before re-enabling";
        }
        return null;
    }

    public static String status(MinecraftServer server) {
        String refusal = refusalReason(server);
        String effective = enabled && refusal == null
                ? "effective"
                : enabled ? "requested but currently ineligible" : "inactive";
        boolean asyncVenue = RegionScheduler.anyAsyncActive();
        return (enabled ? "ON" : "off") + " (saved="
                + (FabroliaConfig.enablePlayerTickActor ? "on" : "off")
                + "; single runtime gate, " + effective
                + (asyncVenue
                    ? "; venue=autonomous island owner (real early+listener late; exact Carpet combined)"
                    : "; venue=awaited serial exact-ordinal compatibility actor"
                      + "; serverbound handlers/tickPlayer/doTick=physical main"
                      + "; outbound sends=actor at the same vanilla ordinal")
                + (refusal == null ? "" : "; reason=" + refusal)
                + (asyncVenue
                    ? "; " + AsyncIslandPlayerTicks.status()
                    : "")
                + onlineEligibilityStatus(server) + ")";
    }

    /**
     * Operational explanation for an enabled mode which is deliberately leaving
     * one or more online players on the canonical physical-main path. This runs
     * only for the status command; the entity hot path remains allocation-free.
     */
    private static String onlineEligibilityStatus(MinecraftServer server) {
        if (server == null) return "; eligibleOnline=0/0";
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        int eligible = 0;
        String firstRejected = null;
        for (ServerPlayer player : players) {
            String reason = onlinePlayerRefusal(server, player);
            if (reason == null) {
                eligible++;
            } else if (firstRejected == null) {
                firstRejected = player.getGameProfile().name() + ": " + reason;
            }
        }
        return "; eligibleOnline=" + eligible + "/" + players.size()
                + (firstRejected == null ? "" : "; firstRejected=" + firstRejected);
    }

    private static String onlinePlayerRefusal(MinecraftServer server, ServerPlayer player) {
        if (RegionScheduler.anyAsyncActive()) {
            if (player.level().getServer() != server) return "belongs to another server";
            RegionizedServer owner = RegionScheduler.forLevel(player.level());
            if (owner == null || !owner.asyncActive) return "level is not autonomous";
            IslandRegistry.PIsland island = owner.islandOwnerOfChunk(player.chunkPosition().pack());
            if (island == null) return "player chunk has no island owner";
            return AsyncIslandPlayerTicks.playerRefusal(player);
        }
        if (player.getClass() != ServerPlayer.class) return "not an exact ServerPlayer";
        if (player.level().getServer() != server) return "belongs to another server";
        RegionizedServer owner = RegionScheduler.forLevel(player.level());
        if (owner == null) return "level has no active region runtime";
        if (owner.asyncActive) return "level async islands are active";
        PortalProcessor portalProcess = player.portalProcess;
        if (player.isRemoved()) return "removed";
        if (!player.isAlive()) return "not alive";
        if (player.isSpectator()) return "spectator";
        if (player.isChangingDimension()) return "changing dimension";
        if (player.getVehicle() != null) return "mounted";
        if (!player.getPassengers().isEmpty()) return "has passengers";
        if (player.getCamera() != player) return "camera is not self";
        if (player.containerMenu != player.inventoryMenu) return "non-inventory menu is open";
        if (player.isUsingItem()) return "using an item";
        if (portalProcess != null
                && !(portalProcess.portal instanceof NetherPortalBlock)) {
            return "non-Nether portal transition is active";
        }
        if (server.getPlayerList().getPlayer(player.getUUID()) != player) {
            return "not the authoritative roster object";
        }
        if (player.connection == null) return "connection is absent";
        if (player.connection.player != player) return "listener points at another player";
        if (!player.connection.hasClientLoaded()) return "client has not completed loading";
        if (player.hasDisconnected()) return "connection is disconnected";
        String knownModReason = PlayerTickKnownModEnvelope.refusalReason(player);
        if (knownModReason != null) return knownModReason;
        if (player instanceof ParallelProcessor.TickGuard guard
                && guard.fabrolia$isPortalPending()) {
            return "Fabrolia portal transition is pending";
        }
        ServerPlayerGameModeAccessor mode =
                (ServerPlayerGameModeAccessor) (Object) player.gameMode;
        if (mode.fabrolia$isDestroyingBlock() || mode.fabrolia$hasDelayedDestroy()) {
            return "block breaking is active";
        }
        return null;
    }

    /** Called by the tickChildren wrapper before any level ticks. */
    public static void beginServerTick(MinecraftServer server) {
        if (!isPhysicalServerThread(server)) {
            rethrow(RegionScheduler.publishFatalAndSelectWinner(
                    new IllegalStateException(
                            "regional player phase began off the server thread")));
        }
        // A private actor loop/body failure is terminal, never an implicit
        // request to fall back on later server ticks. Surface the exact first
        // Throwable before any new vanilla phase or ordinal can start.
        rethrow(EXACT_ACTOR.terminalFailure());
        // Always publish a phase marker, including an allocation-free disabled sentinel:
        // a command/mod-thread toggle during tickChildren can therefore affect only the
        // next server tick and can never split the current level sequence.
        boolean snapshot = enabled
                && !RegionScheduler.asyncModeWantedAtLevelHead()
                && !RegionScheduler.anyAsyncActive()
                && refusalReason(server) == null;
        GlobalPhase phase = snapshot
                ? new GlobalPhase(NEXT_PHASE.getAndIncrement(), true, server)
                : DISABLED_PHASE;
        if (!CURRENT.compareAndSet(null, phase)) {
            GlobalPhase active = CURRENT.get();
            IllegalStateException failure = new IllegalStateException(
                    "regional player tick phase already open");
            rethrow(active != null && active.enabledSnapshot
                    ? active.fail(failure)
                    : RegionScheduler.publishFatalAndSelectWinner(failure));
        }
    }

    /** Final HB/assertion edge immediately before vanilla tickConnection. */
    public static void beforeConnections(MinecraftServer server) {
        if (!isPhysicalServerThread(server)) {
            IllegalStateException failure = new IllegalStateException(
                    "regional player connection barrier ran off main");
            GlobalPhase active = CURRENT.get();
            if (active != null && active.enabledSnapshot) {
                rethrow(active.fail(failure));
            }
            throw failure;
        }
        GlobalPhase phase = CURRENT.get();
        if (phase == null) return;
        if (!phase.enabledSnapshot) {
            if (!CURRENT.compareAndSet(phase, null)) {
                rethrow(RegionScheduler.publishFatalAndSelectWinner(
                        new IllegalStateException(
                                "disabled player phase changed before connections")));
            }
            return;
        }
        phase.throwIfFailed();
        Throwable externalFailure = phase.awaitExternalConsumers(() -> {
            for (ServerLevel level : server.getAllLevels()) {
                phase.throwIfFailed();
                RegionScheduler.callSuccessBooleanIfHealthy(() -> level
                        .getChunkSource().mainThreadProcessor.pollTask());
                phase.throwIfFailed();
            }
        });
        if (externalFailure != null) {
            rethrow(phase.fail(externalFailure));
        }
        phase.pauseForConnections();
        Throwable failure = phase.sealAndCheck();
        phase.connectionsBarrierPassed = failure == null;
        rethrow(failure);
    }

    /** Successful tickChildren epilogue; the connection/player-list tail ran fenced. */
    public static Throwable finishServerTick() {
        GlobalPhase phase = CURRENT.get();
        if (phase == null) return null;
        if (!phase.enabledSnapshot) {
            return RegionScheduler.publishFatalAndSelectWinner(
                    new IllegalStateException(
                            "disabled player phase survived tickChildren"));
        }
        if (!isPhysicalServerThread(phase.server)) {
            IllegalStateException failure = new IllegalStateException(
                    "regional player phase finished off physical main");
            return phase.fail(failure);
        }
        Throwable failure = phase.firstFailure();
        if (!phase.connectionsBarrierPassed) {
            IllegalStateException missingBarrier = new IllegalStateException(
                    "tickChildren completed without the pre-connection player barrier");
            failure = phase.fail(mergeFailure(failure, missingBarrier));
        }
        if (failure != null) {
            // Reopening the pool can admit successor simulation. A phase which
            // failed before this nominal-success epilogue stays fenced and lets
            // the exact failure terminate physical Main.
            return failure;
        }
        failure = phase.fail(mergeFailure(failure, phase.releasePause()));
        if (phase.pauseStillHeld()) {
            return phase.fail(mergeFailure(failure, new IllegalStateException(
                    "regional player phase could not release its exact pool fence")));
        }
        if (!CURRENT.compareAndSet(phase, null)) {
            failure = phase.fail(mergeFailure(failure, new IllegalStateException(
                    "regional player phase changed at tickChildren completion")));
        }
        return failure;
    }

    /**
     * Cleanup when tickChildren exits exceptionally before its connection phase.
     * A started player body is never abandoned: this method seals admission and
     * waits without a force-open timeout. A failed phase is never reopened:
     * resuming the pool here would admit successor simulation before physical
     * Main has propagated the original Throwable.
     *
     * @return a secondary v2 failure to suppress onto the original world failure
     */
    public static Throwable abortServerTick() {
        GlobalPhase phase = CURRENT.get();
        if (phase == null) return null;
        // The allocation-free disabled sentinel intentionally has no server.
        // It can still be installed when the requested gate remains true while
        // its effective regions/async prerequisite changes between ticks.
        if (!phase.enabledSnapshot) {
            if (!CURRENT.compareAndSet(phase, null)) {
                return RegionScheduler.publishFatalAndSelectWinner(
                        new IllegalStateException(
                                "disabled regional player phase changed during exceptional cleanup"));
            }
            return null;
        }
        if (!isPhysicalServerThread(phase.server)) {
            return phase.fail(new IllegalStateException(
                    "regional player exceptional barrier ran off main"));
        }
        Throwable failure;
        try {
            // Returning from abortAndAwait is the structural quiescence proof.
            failure = phase.abortAndAwait();
        } catch (Throwable barrierFailure) {
            // Do not clear/release a phase if the no-timeout barrier itself escaped
            // before proving quiescence. The enclosing server crash is the fail-stop,
            // and an already-published actor/body failure remains the exact primary.
            return phase.fail(mergeFailure(phase.firstFailure(), barrierFailure));
        }
        if (phase.pauseStillHeld()) {
            return failure;
        }
        if (!CURRENT.compareAndSet(phase, null)) {
            failure = phase.fail(mergeFailure(failure, new IllegalStateException(
                    "regional player phase changed during exceptional cleanup")));
        }
        return failure;
    }

    static LevelBatch beginLevel(RegionizedServer owner) {
        if (owner.asyncActive) return null;
        GlobalPhase global = CURRENT.get();
        // OFF at tickChildren HEAD is the allocation-free snapshot. If a command
        // enables the gate later inside commandFunctions, this wall tick remains
        // vanilla-main and the next tick opens the first v2 phase.
        if (global == null) return null;
        if (!global.enabledSnapshot) return null;
        global.throwIfFailed();
        return global.beginLevel(owner);
    }

    /** Snapshot decision used before choosing region entity dispatch vs canonical order. */
    public static boolean exactOrdinalActive(RegionizedServer owner) {
        GlobalPhase phase = CURRENT.get();
        return owner != null && phase != null && phase.enabledSnapshot
                && phase.server == owner.level.getServer() && !owner.asyncActive;
    }

    /**
     * Enter the canonical EntityTickList pass after queue-only regional prework.
     * The pool fence is retained through the remainder of ServerLevel.tick and is
     * released by {@link #finishLevelTurn(ServerLevel, Throwable)}.
     */
    public static void beginExactLevelIteration(RegionizedServer owner) {
        LevelBatch batch = owner == null ? null : owner.playerTickV2Batch;
        if (batch == null || !batch.global.enabledSnapshot || batch.owner != owner) {
            IllegalStateException failure = new IllegalStateException(
                    "exact player iteration has no level batch");
            GlobalPhase phase = CURRENT.get();
            rethrow(phase != null && phase.enabledSnapshot
                    ? phase.fail(failure)
                    : RegionScheduler.publishFatalAndSelectWinner(failure));
        }
        if (!isPhysicalServerThread(batch.global.server)) {
            IllegalStateException failure = new IllegalStateException(
                    "exact player iteration began off physical main");
            rethrow(batch.fail(failure));
        }
        batch.throwIfFailed();
        if (EXACT_LEVEL.get() != null) {
            IllegalStateException failure = new IllegalStateException(
                    "nested exact EntityTickList iteration");
            rethrow(batch.fail(failure));
        }
        batch.exactOrdinal = true;
        batch.global.pauseForLevel(batch);
        EXACT_LEVEL.set(batch);
    }

    public static void endExactLevelIteration(RegionizedServer owner) {
        LevelBatch batch = EXACT_LEVEL.get();
        if (batch == null || batch.owner != owner) {
            IllegalStateException failure = new IllegalStateException(
                    "exact EntityTickList iteration closed out of order");
            GlobalPhase phase = CURRENT.get();
            if (phase != null && phase.enabledSnapshot) {
                rethrow(phase.fail(failure));
            }
            throw failure;
        }
        EXACT_LEVEL.remove();
    }

    /** Whole-ServerLevel.tick epilogue; failure retains the fence for global abort. */
    public static Throwable finishLevelTurn(ServerLevel level, Throwable primary) {
        GlobalPhase phase = CURRENT.get();
        if (phase == null || !phase.enabledSnapshot) return primary;
        if (primary != null) return phase.fail(primary);
        if (!phase.isLevelPauseHeld(level)) return primary;
        Throwable priorFailure = phase.firstFailure();
        if (priorFailure != null) return priorFailure;
        try {
            // Block entities, deferred replays and every ServerLevel tail callback have
            // now completed. Rebuild the auxiliary region mirror at the final fenced
            // seam so resumed workers cannot observe a position/membership snapshot
            // taken too early in the level tick.
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> RegionScheduler.reconcileCanonicalEntityIndex(level));
        } catch (Throwable reconciliationFailure) {
            return phase.fail(mergeFailure(primary, reconciliationFailure));
        }
        Throwable releaseFailure = phase.releaseLevelPause(level);
        return releaseFailure == null
                ? phase.firstFailure()
                : phase.fail(mergeFailure(primary, releaseFailure));
    }

    /**
     * Move exactly one already-admitted vanilla guardEntityTick invocation to the
     * serial actor. All lambda gates and the EntityTickList ordinal remain physical-main.
     * Ineligible/transformed subclasses execute the passed operation on main unchanged.
     */
    public static boolean executeExactOrdinal(ServerLevel level, Entity entity,
                                              Runnable guardedConsumer) {
        LevelBatch batch = EXACT_LEVEL.get();
        if (batch == null || batch.owner.level != level) return false;
        if (!isPhysicalServerThread(batch.global.server)) {
            IllegalStateException failure = new IllegalStateException(
                    "exact player ordinal dispatch ran off physical main");
            rethrow(batch.fail(failure));
        }
        // This seam wraps every EntityTickList ordinal, including non-players.
        // Therefore an asynchronously published v2 failure cannot merely sit in
        // a ledger while the next vanilla entity body starts.
        batch.throwIfFailed();
        if (entity.getClass() != ServerPlayer.class) return false;
        ServerPlayer player = (ServerPlayer) entity;
        if (!playerEnvelope(batch, player)) return false;
        Object listener = player.connection;
        if (player.level() != level || player.isRemoved() || listener == null
                || !batch.hasSession(player, listener)
                || player.connection != listener || player.connection.player != player
                || player.hasDisconnected()
                || batch.global.server.getPlayerList().getPlayer(player.getUUID()) != player) {
            return false;
        }

        SessionStamp session = SessionStamp.capture(player);
        if (session.players.size() != 1 || session.tree.size() != 1
                || !session.stillCurrent(batch, player, true)) {
            return false;
        }
        PlayerTickPhase.Claim<Entity, SessionStamp> claim =
                batch.reserveCandidate(player, session);
        if (claim == null || batch.ledger.stateOf(player) != PlayerTickPhase.State.RESERVED) {
            Throwable failure = batch.firstFailure();
            if (failure == null) {
                failure = batch.fail(new IllegalStateException(
                        "exact player reservation was rejected"));
            }
            rethrow(failure);
        }
        TickContext context;
        TickContext fallback;
        ProfilerFiller profiler;
        try {
            // Nothing which can allocate is allowed after RUNNING publication and
            // before an owner capable of terminalising the claim exists.
            context = new TickContext(batch, player, null, claim, session,
                    true, true);
            fallback = new TickContext(batch, player, null, claim, session,
                    false, true);
            profiler = Profiler.get();
        } catch (Throwable allocationFailure) {
            Throwable failure = batch.failBeforeBody(claim, allocationFailure);
            batch.cancelReservation(claim);
            rethrow(batch.global.fail(failure));
            return true;
        }
        PlayerTickPhase.Admission admission;
        try {
            admission = batch.ledger.startWorker(
                    claim, player, session, session.stillCurrent(batch, player, true));
        } catch (Throwable admissionFailure) {
            Throwable failure = batch.failBeforeBody(claim, admissionFailure);
            batch.cancelReservation(claim);
            rethrow(batch.global.fail(failure));
            return true;
        }
        if (admission != PlayerTickPhase.Admission.WORKER) {
            Throwable failure = batch.publishLedgerFailure();
            if (failure == null) {
                failure = batch.fail(new IllegalStateException(
                        "exact player actor admission was rejected"));
            }
            batch.cancelReservation(claim);
            rethrow(batch.global.fail(failure));
        }

        try {
            EXACT_ACTOR.executeExactAndAwait(batch.global.mainThread,
                    () -> runExactActorTurn(context, guardedConsumer, profiler));
            // A failure published while the accepted actor obligation was being
            // retired must reach this exact physical-main ordinal before it can
            // return to the vanilla entity loop.
            batch.throwIfFailed();
        } catch (PlayerTickActor.Rejected rejected) {
            // Admission failed before the actor body could bind the claim. Preserve
            // the exact list ordinal by executing the already-captured vanilla
            // operation immediately on physical main. Once a body entered, the
            // actor terminalized the claim and this branch must not retry it.
            boolean fallbackAllowed;
            try {
                fallbackAllowed = batch.ledger.stateOf(player) == PlayerTickPhase.State.RUNNING
                        && !batch.ledger.vanillaConsumerEntered(claim);
            } catch (Throwable inspectionFailure) {
                Throwable failure = batch.fail(inspectionFailure);
                batch.cancelReservation(claim);
                rethrow(batch.global.fail(failure));
                return true;
            }
            if (!fallbackAllowed) {
                Throwable failure = batch.fail(rejected);
                batch.cancelReservation(claim);
                rethrow(batch.global.fail(failure));
            }
            runExactMainFallback(fallback, guardedConsumer);
            return true;
        } catch (Throwable thrown) {
            Throwable failure = batch.global.fail(thrown);
            try {
                if (batch.ledger.stateOf(player) == PlayerTickPhase.State.RUNNING) {
                    failure = batch.failBeforeBody(claim, failure);
                    batch.cancelReservation(claim);
                }
            } catch (Throwable cleanupFailure) {
                failure = batch.global.fail(mergeFailure(failure, cleanupFailure));
                batch.cancelReservation(claim);
            }
            rethrow(batch.global.fail(failure));
        }
        return true;
    }

    private static void runExactMainFallback(TickContext context, Runnable guardedConsumer) {
        Throwable failure = null;
        boolean installed = false;
        try {
            // Bind the RUNNING claim before any ThreadLocal publication can fail.
            // complete() can then always authenticate this physical-main fallback.
            context.beginLeases();
            installContext(context);
            installed = true;
            context.batch.throwIfFailed();
            RegionScheduler.runSuccessBodyIfHealthy(guardedConsumer);
            context.batch.throwIfFailed();
        } catch (Throwable thrown) {
            failure = context.batch.global.fail(thrown);
            // Publish the exact body/setup Throwable before any release cleanup
            // can itself fail or another thread can claim the phase's first slot.
        } finally {
            failure = finishContext(context, installed, failure);
            try {
                failure = completeAndRetire(context, failure);
            } catch (Throwable completionFailure) {
                failure = context.batch.global.fail(
                        mergeFailure(failure, completionFailure));
            }
        }
        rethrow(failure);
    }

    private static void runExactActorTurn(TickContext context, Runnable guardedConsumer,
                                          ProfilerFiller profiler) {
        LogicalTurn turn = new LogicalTurn(context.batch.global.server,
                context.batch.global.id, context.batch.owner.level,
                context.batch.global.mainThread, Thread.currentThread());
        Throwable failure = null;
        boolean logicalAcquired = false;
        boolean installed = false;
        ThreadLocal<ProfilerFiller> activeProfiler = null;
        try {
            if (Thread.currentThread() != EXACT_ACTOR.thread()
                    || !LOGICAL_TURN.compareAndSet(null, turn)) {
                throw new IllegalStateException(
                        "exact player actor could not acquire the logical server turn");
            }
            logicalAcquired = true;
            // Bind the RUNNING claim before accessor/TL work. If any later setup
            // fails, the actor remains the authenticated completion owner.
            context.beginLeases();
            installContext(context);
            installed = true;
            activeProfiler = com.axalotl.fabrolia.common.mixin.utils.ProfilerAccessor
                    .fabrolia$activeProfiler();
            if (activeProfiler.get() != null) {
                throw new IllegalStateException(
                        "exact player actor inherited a stale profiler context");
            }
            activeProfiler.set(profiler);
            context.batch.throwIfFailed();
            RegionScheduler.runPausedExactActorBodyIfHealthy(
                    turn.physicalMain, guardedConsumer);
            context.batch.throwIfFailed();
        } catch (Throwable thrown) {
            failure = context.batch.global.fail(thrown);
            // The actor may spend time releasing a whole ride tree. Publish first
            // so no concurrent cleanup/worker failure can replace this ordinal's
            // exact Throwable in the global fail-stop slot.
        } finally {
            boolean logicalPublished = logicalAcquired;
            if (!logicalPublished) {
                try {
                    // CAS may have published before an asynchronous VM failure
                    // reaches the following logicalAcquired store.
                    logicalPublished = LOGICAL_TURN.get() == turn;
                } catch (Throwable logicalInspectionFailure) {
                    failure = context.batch.global.fail(
                            mergeFailure(failure, logicalInspectionFailure));
                }
            }
            if (logicalPublished) {
                failure = finishContext(context, installed, failure);
                if (activeProfiler != null) {
                    try {
                        ProfilerFiller observedProfiler = activeProfiler.get();
                        if (observedProfiler != null && observedProfiler != profiler) {
                            failure = context.batch.global.fail(mergeFailure(failure,
                                    new IllegalStateException(
                                            "exact player actor profiler context was replaced")));
                        }
                        // The actor thread is private to this coordinator. Remove even a
                        // partially-published value from ThreadLocal.set before returning it
                        // to the next accepted exact ordinal.
                        activeProfiler.remove();
                    } catch (Throwable profilerCleanupFailure) {
                        failure = context.batch.global.fail(
                                mergeFailure(failure, profilerCleanupFailure));
                    }
                }
                try {
                    if (!LOGICAL_TURN.compareAndSet(turn, null)) {
                        failure = context.batch.global.fail(mergeFailure(failure,
                                new IllegalStateException(
                                        "logical server turn was replaced before actor completion")));
                    }
                } catch (Throwable logicalCleanupFailure) {
                    failure = context.batch.global.fail(mergeFailure(failure,
                            logicalCleanupFailure));
                }
                try {
                    failure = completeAndRetire(context, failure);
                } catch (Throwable completionFailure) {
                    failure = context.batch.global.fail(
                            mergeFailure(failure, completionFailure));
                }
            }
        }
        rethrow(failure);
    }

    private static Throwable finishContext(TickContext context, boolean installed,
                                           Throwable primary) {
        try {
            // ThreadLocal.set may publish before an asynchronous VM failure reaches
            // the following boolean store. Identity is the authoritative cleanup test.
            return installed || TICK_CONTEXT.get() == context
                    ? finishAndClearContext(context, primary)
                    : context.finish(primary);
        } catch (Throwable cleanupFailure) {
            return context.batch.global.fail(
                    mergeFailure(primary, cleanupFailure));
        }
    }

    private static Throwable completeAndRetire(TickContext context, Throwable primary) {
        Throwable result = mergeFailure(context.batch.global.firstFailure(), primary);
        if (result != null) {
            // W must exist before any failure terminal or retirement cleanup can
            // release the accepted obligation to a waiter.
            result = context.batch.global.fail(result);
        }

        PlayerTickPhase.CompletionReceipt<Entity, SessionStamp> receipt = null;
        try {
            // Offering allocates and consumes the unique completion authority
            // outside the scheduler lifecycle monitor, under LevelBatch -> ledger.
            receipt = context.batch.offerCompletion(context.claim);
        } catch (Throwable offeringFailure) {
            result = context.batch.global.fail(
                    mergeFailure(result, offeringFailure));
        }

        if (result == null && receipt != null) {
            PlayerTickPhase.CompletionReceipt<Entity, SessionStamp> acceptedReceipt = receipt;
            try {
                // Fixed receipt write only: no LevelBatch/ledger monitor, validation,
                // retirement, notification, or user callback may run in this cut.
                RegionScheduler.commitSuccessIfHealthy(acceptedReceipt::acceptSuccess);
                Throwable localFailure =
                        context.batch.consumeAcceptedCompletionAndRetire(acceptedReceipt);
                if (localFailure != null) {
                    result = context.batch.global.fail(localFailure);
                }
            } catch (Throwable completionFailure) {
                result = context.batch.global.fail(
                        mergeFailure(result, completionFailure));
            }
        }

        if (result != null && receipt != null) {
            // A refused cut, body W, or locally-detected completion invariant all
            // poison the still-unpublished ledger terminal with the exact global W.
            result = context.batch.global.fail(result);
            try {
                result = context.batch.poisonCompletionAndRetire(receipt, result);
            } catch (Throwable completionFailure) {
                result = context.batch.global.fail(
                        mergeFailure(result, completionFailure));
            }
        }

        if (receipt == null) {
            // Allocation/offer failure is already the selected W.  Retain the old
            // no-allocation terminal backstop so a RUNNING claim cannot strand the
            // level barrier merely because its receipt could not be created.
            if (result == null) {
                result = context.batch.global.fail(new IllegalStateException(
                        "player completion receipt was unavailable"));
            }
            try {
                result = context.batch.completeClaimFailureAndRetire(
                        context.claim, result);
            } catch (Throwable completionFailure) {
                result = context.batch.global.fail(
                        mergeFailure(result, completionFailure));
            }
        }

        if (receipt != null) {
            try {
                // This is the sole running--/notifyAll edge.  It is deliberately
                // after both the scheduler cut and candidate retirement.
                context.batch.signalCompletion(receipt);
            } catch (Throwable signalFailure) {
                result = context.batch.global.fail(
                        mergeFailure(result, signalFailure));
                try {
                    result = context.batch.poisonCompletion(receipt, result);
                } catch (Throwable completionFailure) {
                    result = context.batch.global.fail(
                            mergeFailure(result, completionFailure));
                }
                try {
                    context.batch.signalCompletion(receipt);
                } catch (Throwable secondSignalFailure) {
                    result = context.batch.global.fail(
                            mergeFailure(result, secondSignalFailure));
                }
            }
        }

        Throwable winner = context.batch.global.firstFailure();
        return winner == null ? result : context.batch.global.fail(
                mergeFailure(winner, result));
    }

    public static void finishLevel(RegionizedServer owner) {
        LevelBatch batch = owner.playerTickV2Batch;
        if (batch == null) return;
        if (!isPhysicalServerThread(batch.global.server)) {
            rethrow(batch.fail(new IllegalStateException(
                    "regional player level barrier ran off main")));
        }
        owner.playerTickV2Batch = null;
        Throwable externalFailure = batch.awaitExternalConsumers(() -> {
            for (ServerLevel level : batch.global.server.getAllLevels()) {
                batch.throwIfFailed();
                RegionScheduler.callSuccessBooleanIfHealthy(() -> level
                        .getChunkSource().mainThreadProcessor.pollTask());
                batch.throwIfFailed();
            }
        });
        if (externalFailure != null) batch.fail(externalFailure);
        if (!batch.exactOrdinal) {
            batch.fail(new IllegalStateException(
                    "regional player batch never entered the canonical EntityTickList pass"));
            batch.abortUnstartedAndRelease();
        }
        Throwable awaitFailure = batch.firstFailure();
        try {
            // All progress venues are already closed here. Wait only for the
            // accepted ledger obligation; never pump a new task after failure.
            batch.ledger.sealAndAwait();
            // A locally-accepted receipt may have raced a later generation W.
            // Consult the global winner immediately after the waiter opens and
            // before terminal verification or any successor bookkeeping.
            awaitFailure = mergeFailure(awaitFailure, batch.firstFailure());
        } catch (Throwable waitFailure) {
            awaitFailure = batch.fail(mergeFailure(awaitFailure, waitFailure));
        }
        if (awaitFailure != null) awaitFailure = batch.fail(awaitFailure);
        Throwable terminalFailure;
        try {
            terminalFailure = batch.ledger.verifyTerminal();
        } catch (Throwable verificationFailure) {
            terminalFailure = batch.global.fail(
                    mergeFailure(batch.firstFailure(), verificationFailure));
        }
        if (terminalFailure != null) terminalFailure = batch.global.fail(terminalFailure);
        try {
            batch.global.finishLevel(batch);
        } catch (Throwable bookkeepingFailure) {
            batch.global.fail(mergeFailure(terminalFailure, bookkeepingFailure));
        }
        rethrow(batch.firstFailure());
    }

    private static Throwable mergeFailure(Throwable primary, Throwable secondary) {
        if (secondary == null) return primary;
        if (primary == null) return secondary;
        if (primary != secondary) {
            try {
                if (primary.getSuppressed().length < 8) primary.addSuppressed(secondary);
            } catch (Throwable ignored) {
                // Suppression is diagnostic only. A secondary allocation failure must
                // never prevent the remaining leases/TLs/claims from being released.
            }
        }
        return primary;
    }

    /** Player-local pre-body envelope, revalidated at the exact actor body seam. */
    private static boolean playerEnvelope(LevelBatch batch, ServerPlayer player) {
        PortalProcessor portalProcess = player.portalProcess;
        if (player.getClass() != ServerPlayer.class
                || player.level() != batch.owner.level
                || player.isRemoved()
                || !player.isAlive()
                || player.isSpectator()
                || player.isChangingDimension()
                || player.getVehicle() != null
                || !player.getPassengers().isEmpty()
                || player.getCamera() != player
                || player.containerMenu != player.inventoryMenu
                || player.isUsingItem()
                || (portalProcess != null
                && !(portalProcess.portal instanceof NetherPortalBlock))
                || !batch.hasSession(player, player.connection)
                || player.connection == null
                || player.connection.player != player
                || !player.connection.hasClientLoaded()
                || player.hasDisconnected()
                || !PlayerTickKnownModEnvelope.idle(player)) {
            return false;
        }
        if (player instanceof ParallelProcessor.TickGuard guard && guard.fabrolia$isPortalPending()) {
            return false;
        }
        ServerPlayerGameModeAccessor mode = (ServerPlayerGameModeAccessor) (Object) player.gameMode;
        return !mode.fabrolia$isDestroyingBlock() && !mode.fabrolia$hasDelayedDestroy();
    }

    public static boolean inWorkerPlayerTick() {
        TickContext context = TICK_CONTEXT.get();
        return context != null && context.worker;
    }

    public static boolean isWorkerTickOf(Entity entity) {
        TickContext context = TICK_CONTEXT.get();
        return context != null && context.worker && context.root == entity;
    }

    /**
     * Opens an exact-object claim for a ServerPlayer.tick reached through the
     * established main-owned root path while the exact-ordinal phase is open.
     * The caller must close the returned token after the whole wrapped method.
     */
    public static boolean beginServerPlayerTick(ServerPlayer player) {
        TickContext context = TICK_CONTEXT.get();
        if (context != null) {
            context.batch.throwIfFailed();
            EntityTickLease lease = ((ParallelProcessor.TickGuard) player).fabrolia$tickLease();
            if (!lease.isReservedBy(context.claim)
                    || !context.batch.ledger.isExecutor(context.claim)
                    || !context.entityConsumerEntered
                    || !isCurrentPlayerConsumer(player)
                    || !context.session.containsEntity(player)) {
                IllegalStateException failure = new IllegalStateException(
                        "ServerPlayer.tick did not inherit its authenticated entity lease");
                rethrow(context.batch.fail(failure));
            }
            context.markPlayerTick(player);
            return false;
        }
        GlobalPhase phase = CURRENT.get();
        if (phase == null || !phase.enabledSnapshot) return false;
        phase.throwIfFailed();
        EntityTickLease lease = ((ParallelProcessor.TickGuard) player).fabrolia$tickLease();
        if (phase.hasCandidate(player)) {
            // The generic method wrapper cannot authenticate whether this is a
            // transformed EntityTickList consumer or an additional mod call. Once
            // v2 reserved the object, accepting either outside TickContext/the exact
            // external-consumer scope can produce two bodies. Fail before original.
            IllegalStateException failure = new IllegalStateException(
                    "reserved ServerPlayer.tick entered outside its exact entity consumer");
            rethrow(phase.fail(failure));
        }
        if (!phase.server.isSameThread()) {
            IllegalStateException failure = new IllegalStateException(
                    "unclaimed ServerPlayer.tick reached a non-main thread during v2 phase");
            rethrow(phase.fail(failure));
        }
        // Normal tickNonPassenger/tickPassenger already owns this exact shared slot.
        if (lease.isOwnedByCurrentThread()) {
            if (!isCurrentPlayerConsumer(player)) {
                IllegalStateException failure = new IllegalStateException(
                        "nested/direct ServerPlayer.tick lacked an exact entity-consumer scope");
                rethrow(phase.fail(failure));
            }
            return false;
        }
        if (!lease.tryBegin()) {
            IllegalStateException failure = new IllegalStateException(
                    "direct ServerPlayer.tick collided with an entity owner/reservation");
            rethrow(phase.fail(failure));
        }
        return true;
    }

    public static void endServerPlayerTick(ServerPlayer player, boolean directLease) {
        if (!directLease) return;
        ((ParallelProcessor.TickGuard) player).fabrolia$tickLease().end();
    }

    /** True only for the authenticated context that transferred this reservation to itself. */
    public static boolean ownsReservedEntityTick(Entity entity) {
        TickContext context = TICK_CONTEXT.get();
        return context != null && context.session.containsEntity(entity)
                && context.batch.ledger.isExecutor(context.claim)
                && ((ParallelProcessor.TickGuard) entity).fabrolia$tickLease()
                .isReservedBy(context.claim);
    }

    /** Rejects any second entity consumer before its body can mutate old-pos/tickCount. */
    public static void rejectReservedExternalEntityConsumer(Entity root) {
        if (TICK_CONTEXT.get() != null) return;
        GlobalPhase phase = CURRENT.get();
        if (phase == null || !phase.enabledSnapshot) return;
        phase.throwIfFailed();
        if (!phase.hasTreeClaim(root) && !phase.hasCandidateInTree(root)) return;
        IllegalStateException failure = new IllegalStateException(
                "second entity consumer reached a reserved regional-player identity");
        rethrow(phase.fail(failure));
    }

    /** Exact entry marker after TickGuard ownership and before tickNonPassenger's body. */
    public static void markVanillaEntityConsumerEntered(Entity root) {
        TickContext context = TICK_CONTEXT.get();
        if (context == null) {
            openPlayerEntityConsumerScope(root);
            return;
        }
        context.batch.throwIfFailed();
        if (context.root != root) {
            IllegalStateException failure = new IllegalStateException(
                    "v2 entity consumer retargeted to another root object");
            rethrow(context.batch.fail(failure));
        }
        boolean current = context.session.stillCurrent(
                context.batch, root, context.exactOrdinal || !context.worker);
        if (current && root instanceof ServerPlayer player) {
            current = playerEnvelope(context.batch, player);
        }
        if (!current) {
            IllegalStateException failure = new IllegalStateException(
                    "player eligibility/session changed at the exact entity-consumer seam");
            rethrow(context.batch.fail(failure));
        }
        try {
            context.batch.ledger.markVanillaConsumerEntered(context.claim);
        } catch (Throwable failure) {
            rethrow(context.batch.global.fail(failure));
        }
        context.entityConsumerEntered = true;
        openPlayerEntityConsumerScope(root);
    }

    /** Open the scope which authenticates the nested virtual ServerPlayer.tick call. */
    public static void markPlayerEntityBodyPhase(Entity entity) {
        openPlayerEntityConsumerScope(entity);
    }

    /** Close the exact ServerLevel consumer scope opened at the body seam. */
    public static void finishPlayerEntityBody(Entity entity) {
        if (!(entity instanceof ServerPlayer player)) return;
        ArrayDeque<ServerPlayer> stack = PLAYER_CONSUMER_SCOPE.get();
        if (stack == null || stack.isEmpty()) return;
        if (stack.peek() != player) {
            GlobalPhase phase = CURRENT.get();
            IllegalStateException failure = new IllegalStateException(
                    "player entity-consumer scope closed out of identity order");
            if (phase != null && phase.enabledSnapshot) {
                rethrow(phase.fail(failure));
            }
            throw failure;
        }
        stack.pop();
        if (stack.isEmpty()) PLAYER_CONSUMER_SCOPE.remove();
    }

    private static void openPlayerEntityConsumerScope(Entity entity) {
        if (!(entity instanceof ServerPlayer player)) return;
        GlobalPhase phase = CURRENT.get();
        if (phase == null || !phase.enabledSnapshot) return;
        phase.throwIfFailed();
        EntityTickLease lease = ((ParallelProcessor.TickGuard) player).fabrolia$tickLease();
        boolean reservedContext = ownsReservedEntityTick(player);
        if (!lease.isOwnedByCurrentThread() && !reservedContext) {
            IllegalStateException failure = new IllegalStateException(
                    "player entity phase stamp attempted without TickGuard ownership");
            rethrow(phase.fail(failure));
        }
        if (!reservedContext && !phase.server.isSameThread()) {
            IllegalStateException failure = new IllegalStateException(
                    "unclaimed ServerPlayer entity body reached a non-main thread in v2 phase");
            rethrow(phase.fail(failure));
        }
        ArrayDeque<ServerPlayer> stack = PLAYER_CONSUMER_SCOPE.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            PLAYER_CONSUMER_SCOPE.set(stack);
        }
        stack.push(player);
    }

    private static boolean isCurrentPlayerConsumer(ServerPlayer player) {
        ArrayDeque<ServerPlayer> stack = PLAYER_CONSUMER_SCOPE.get();
        return stack != null && !stack.isEmpty() && stack.peek() == player;
    }

    /**
     * Fail-stops a TickGuard collision involving a v2-owned root/passenger object.
     *
     * <p>This deliberately queries the global identity ledger, not the caller's
     * ThreadLocal. The conflicting invocation is normally on a different thread,
     * where a local-only check would return false and silently turn the collision
     * into a missing tick.
     */
    public static void failIfClaimedTickGuardContention(Entity entity, String seam) {
        GlobalPhase phase = CURRENT.get();
        if (phase == null || !phase.enabledSnapshot) return;
        phase.throwIfFailed();
        EntityTickLease lease = ((ParallelProcessor.TickGuard) entity).fabrolia$tickLease();
        boolean playerTree = entity instanceof ServerPlayer
                || ParallelProcessor.containsServerPlayerTick(entity);
        if (!lease.hasReservation() && !phase.hasTreeClaim(entity) && !playerTree) return;
        if (lease.hasThreadOwner()) phase.registerExternalConsumer(lease);
        IllegalStateException failure = new IllegalStateException(
                "regional player v2 TickGuard contention at " + seam
                        + " for entity " + entity.getId());
        rethrow(phase.fail(failure));
    }

    /**
     * Acquire the same entity-local leases used by TickGuard before a player lifecycle
     * mutation.  Acquisition is non-blocking and all-or-nothing: waiting with a partial
     * mount tree would introduce a cross-entity lock cycle.  The exact-ordinal actor owns
     * the exclusive logical server turn, so its own authenticated Claim is reentrant;
     * legacy parallel-worker claims remain fail-stop.
     */
    public enum LifecycleMutationKind {
        /** The player stays live, so its completed physics still needs publication. */
        SOFT_HANDOFF,
        /** Exact ServerPlayer.die origin; its ordinary listener stage remains owed. */
        DEATH,
        /** The mutation removes or replaces the player and makes old state unpublishable. */
        HARD_EXIT
    }

    public static LifecycleLeaseScope beginLifecycleMutation(
            LifecycleMutationKind kind, Entity... roots) {
        if (kind == null) {
            throw new IllegalArgumentException("player lifecycle mutation kind is required");
        }
        GlobalPhase phase = CURRENT.get();
        if (phase == null || !phase.enabledSnapshot) {
            if (!AsyncIslandPlayerTicks.enabledForAsync()) return LifecycleLeaseScope.NOOP;
            AsyncIslandPlayerLifecycle.Scope asyncScope =
                    AsyncIslandPlayerLifecycle.begin(kind, roots);
            try {
                return LifecycleLeaseScope.async(asyncScope);
            } catch (Throwable wrapperFailure) {
                // AsyncIslandPlayerLifecycle.begin may already have armed an exact
                // body depth, retained a handoff closer and taken the global
                // island fence.  Losing that scope because this tiny facade could
                // not be allocated would strand all three authorities forever.
                Throwable failure = RegionScheduler.publishFatalAndSelectWinner(wrapperFailure);
                try {
                    failure = asyncScope.finish(failure);
                } catch (Throwable cleanup) {
                    failure = RegionScheduler.publishFatalAndSelectWinner(
                            mergeFailure(failure, cleanup));
                }
                rethrow(RegionScheduler.publishFatalAndSelectWinner(failure));
                return LifecycleLeaseScope.NOOP;
            }
        }
        phase.throwIfFailed();

        IdentityHashMap<Entity, Boolean> before = new IdentityHashMap<>();
        for (Entity root : roots) collectRideComponent(root, before);
        boolean containsPlayer = false;
        for (Entity entity : before.keySet()) {
            if (entity instanceof ServerPlayer) {
                containsPlayer = true;
                break;
            }
        }
        if (!containsPlayer) return LifecycleLeaseScope.NOOP;

        ArrayList<EntityTickLease> acquired = new ArrayList<>(before.size());
        TickContext context = TICK_CONTEXT.get();
        Throwable failure = null;
        try {
            for (Entity entity : before.keySet()) {
                phase.throwIfFailed();
                EntityTickLease lease = ((ParallelProcessor.TickGuard) entity).fabrolia$tickLease();
                Object owner = lease.get();
                if (context != null && context.exactOrdinal
                        && owner == context.claim
                        && context.batch.ledger.isExecutor(context.claim)
                        && context.session.containsEntity(entity)) {
                    continue;
                }
                if (owner == Thread.currentThread()) {
                    continue;
                }
                if (owner != null || !lease.tryBegin()) {
                    throw new IllegalStateException(
                            "player lifecycle mutation collided with an entity tick/reservation");
                }
                acquired.add(lease);
            }

            // Every cooperative mount writer now holds the same lease.  Recapture after
            // acquire-all so a topology change in the acquisition window is poison, not
            // permission to mutate a different tree.
            IdentityHashMap<Entity, Boolean> after = new IdentityHashMap<>();
            for (Entity root : roots) collectRideComponent(root, after);
            phase.throwIfFailed();
            if (!sameIdentitySet(before, after)) {
                throw new IllegalStateException(
                        "player ride topology changed while acquiring lifecycle leases");
            }
            for (Entity entity : before.keySet()) {
                if (!(entity instanceof ServerPlayer player)) continue;
                PlayerTickPhaseStamp stamp = (PlayerTickPhaseStamp) player;
                stamp.fabrolia$bumpPlayerLifecycleEpoch();
            }
            phase.throwIfFailed();
            return new LifecycleLeaseScope(phase, acquired);
        } catch (Throwable thrown) {
            failure = phase.fail(thrown);
            // Publish before releasing a partially-acquired tree so cleanup
            // diagnostics cannot win the phase's first-failure slot.
        }
        for (int i = acquired.size() - 1; i >= 0; i--) {
            try {
                acquired.get(i).end();
            } catch (Throwable cleanup) {
                failure = phase.fail(mergeFailure(failure, cleanup));
            }
        }
        failure = phase.fail(failure);
        rethrow(failure);
        return LifecycleLeaseScope.NOOP;
    }

    private static void collectRideComponent(Entity entity,
                                             IdentityHashMap<Entity, Boolean> found) {
        if (entity == null || found.put(entity, Boolean.TRUE) != null) return;
        collectRideComponent(entity.getVehicle(), found);
        for (Entity passenger : entity.getPassengers()) {
            collectRideComponent(passenger, found);
        }
    }

    private static boolean sameIdentitySet(IdentityHashMap<Entity, Boolean> first,
                                           IdentityHashMap<Entity, Boolean> second) {
        if (first.size() != second.size()) return false;
        for (Entity entity : first.keySet()) if (!second.containsKey(entity)) return false;
        return true;
    }

    public static final class LifecycleLeaseScope {
        private static final LifecycleLeaseScope NOOP =
                new LifecycleLeaseScope(null, new ArrayList<>(), null);

        private final GlobalPhase phase;
        private final ArrayList<EntityTickLease> acquired;
        private final AsyncIslandPlayerLifecycle.Scope asyncScope;
        private boolean closed;

        private LifecycleLeaseScope(GlobalPhase phase, ArrayList<EntityTickLease> acquired) {
            this(phase, acquired, null);
        }

        private LifecycleLeaseScope(GlobalPhase phase, ArrayList<EntityTickLease> acquired,
                                    AsyncIslandPlayerLifecycle.Scope asyncScope) {
            this.phase = phase;
            this.acquired = acquired;
            this.asyncScope = asyncScope;
        }

        private static LifecycleLeaseScope async(AsyncIslandPlayerLifecycle.Scope scope) {
            return new LifecycleLeaseScope(null, new ArrayList<>(), scope);
        }

        /** Return the shared first-wins body failure with cleanup only as context. */
        public Throwable finish(Throwable primary) {
            if (asyncScope != null) {
                Throwable failure = primary == null
                        ? null
                        : RegionScheduler.publishFatalAndSelectWinner(primary);
                try {
                    return asyncScope.finish(failure);
                } catch (Throwable cleanup) {
                    Throwable candidate = mergeFailure(failure, cleanup);
                    return RegionScheduler.publishFatalAndSelectWinner(candidate);
                }
            }
            if (phase == null) return primary;
            // A lifecycle body failure is authoritative. Publish it before the
            // first lease release so cleanup failure on another participant can
            // only ever become diagnostic secondary state.
            Throwable result = primary == null ? phase.firstFailure() : phase.fail(primary);
            if (closed) {
                IllegalStateException repeated = new IllegalStateException(
                        "player lifecycle lease scope closed twice");
                return phase.fail(mergeFailure(result, repeated));
            }
            closed = true;
            for (int i = acquired.size() - 1; i >= 0; i--) {
                try {
                    acquired.get(i).end();
                } catch (Throwable cleanup) {
                    result = phase.fail(mergeFailure(result, cleanup));
                }
            }
            return result == null ? null : phase.fail(result);
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure != null) RegionalPlayerEntityTicksV2.<RuntimeException>sneakyThrow(failure);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable failure) throws T {
        throw (T) failure;
    }

    static final class LevelBatch {
        final GlobalPhase global;
        final RegionizedServer owner;
        final PlayerTickPhase<Entity, SessionStamp> ledger = new PlayerTickPhase<>(true);
        private final IdentityHashMap<ServerPlayer, Object> sessions = new IdentityHashMap<>();
        private final IdentityHashMap<Entity,
                PlayerTickPhase.Claim<Entity, SessionStamp>> claimedTree = new IdentityHashMap<>();
        private final IdentityHashMap<ServerPlayer,
                PlayerTickPhase.Claim<Entity, SessionStamp>> candidates = new IdentityHashMap<>();
        private final IdentityHashMap<EntityTickLease, Boolean> externalConsumers =
                new IdentityHashMap<>();
        volatile boolean exactOrdinal;

        LevelBatch(GlobalPhase global, RegionizedServer owner) {
            this.global = global;
            this.owner = owner;
            // Captured on main immediately before this level's entity dispatch,
            // after tickChildren's commandFunctions phase. A respawn/shadow from
            // those functions therefore publishes the replacement object here,
            // never the stale object seen at tickChildren HEAD.
            for (ServerPlayer player : owner.level.getServer().getPlayerList().getPlayers()) {
                if (player.level() == owner.level) sessions.put(player, player.connection);
            }
        }

        boolean hasSession(ServerPlayer player, Object listener) {
            return listener != null && sessions.get(player) == listener;
        }

        synchronized boolean hasCandidate(ServerPlayer player) {
            return candidates.containsKey(player);
        }

        synchronized boolean hasTreeClaim(Entity entity) {
            // Tombstones intentionally stay visible until beforeConnections/abort.
            // A DONE claim can still own TickGuard for a few cleanup instructions,
            // and a later second consumer in the same server phase is not safe to
            // accept merely because the first body returned.
            return claimedTree.containsKey(entity);
        }

        synchronized PlayerTickPhase.Claim<Entity, SessionStamp> reserveCandidate(
                ServerPlayer player, SessionStamp session) {
            if (candidates.containsKey(player)) {
                fail(new IllegalStateException(
                        "same player registered twice as a v2 candidate"));
                return null;
            }
            for (EntityLink link : session.tree) {
                if (claimedTree.containsKey(link.entity)) {
                    fail(new IllegalStateException(
                            "same player/root/passenger object offered by two v2 consumers in one level"));
                    return null;
                }
            }
            PlayerTickPhase.Claim<Entity, SessionStamp> claim =
                    new PlayerTickPhase.Claim<>(player, session);
            ArrayList<EntityTickLease> acquired = new ArrayList<>(session.tree.size());
            EntityTickLease busyLease = null;
            Entity busyEntity = null;
            Object busyOwner = null;
            try {
                for (EntityLink link : session.tree) {
                    EntityTickLease lease = ((ParallelProcessor.TickGuard) link.entity)
                            .fabrolia$tickLease();
                    Object witnessed = lease.tryReserveWitness(claim);
                    if (witnessed != null) {
                        busyLease = lease;
                        busyEntity = link.entity;
                        busyOwner = witnessed;
                        break;
                    }
                    acquired.add(lease);
                }

                if (!ledger.register(claim)) {
                    Throwable failure = publishLedgerFailure();
                    if (failure == null) {
                        failure = global.fail(new IllegalStateException(
                                "exact player ledger rejected a new reservation"));
                    }
                    failure = releaseReservationList(claim, acquired, failure);
                    fail(failure);
                    return null;
                }
                for (EntityLink link : session.tree) claimedTree.put(link.entity, claim);
                candidates.put(player, claim);

                if (busyLease != null) {
                    IllegalStateException busyFailure = new IllegalStateException(
                            "player entity lease was already active before v2 reservation: entity="
                                    + busyEntity.getId() + ", owner="
                                    + busyOwner.getClass().getName());
                    Throwable failure = global.fail(busyFailure);
                    failure = releaseReservationList(claim, acquired, failure);
                    // Await every witnessed owner, not only Thread.  A second exact Claim
                    // may be completing in another level batch; an unknown token is a
                    // fail-stop condition and must not be cleared underneath the phase.
                    try {
                        externalConsumers.put(busyLease, Boolean.TRUE);
                    } catch (Throwable cleanupFailure) {
                        failure = global.fail(mergeFailure(failure, cleanupFailure));
                    }
                    failBeforeBody(claim, failure);
                    return claim;
                }

                return claim;
            } catch (Throwable thrown) {
                Throwable failure = global.fail(thrown);
                failure = releaseReservationList(claim, acquired, failure);
                failBeforeBody(claim, failure);
                return claim;
            }
        }

        synchronized void cancelReservation(PlayerTickPhase.Claim<Entity, SessionStamp> claim) {
            if (claim == null || claim.session == null) return;
            Throwable failure = firstFailure();
            boolean cleanupFailed = false;
            for (EntityLink link : claim.session.tree) {
                try {
                    EntityTickLease lease = ((ParallelProcessor.TickGuard) link.entity)
                            .fabrolia$tickLease();
                    EntityTickLease.CancelResult cancelled = lease.cancelReservedExact(claim);
                    if (cancelled == EntityTickLease.CancelResult.FOREIGN) {
                        cleanupFailed = true;
                        failure = global.fail(mergeFailure(failure,
                                new IllegalStateException(
                                        "foreign owner replaced exact player entity reservation")));
                    }
                } catch (Throwable cleanupFailure) {
                    cleanupFailed = true;
                    failure = global.fail(mergeFailure(failure, cleanupFailure));
                }
            }
            if (cleanupFailed) fail(failure);
        }

        synchronized void retireCandidate(
                PlayerTickPhase.Claim<Entity, SessionStamp> claim) {
            if (claim == null || claim.session == null) return;
            if (candidates.get(claim.object) != claim) {
                throw new IllegalStateException(
                        "exact player candidate changed before retirement");
            }
            for (EntityLink link : claim.session.tree) {
                EntityTickLease lease = ((ParallelProcessor.TickGuard) link.entity)
                        .fabrolia$tickLease();
                if (lease.isReservedBy(claim)) {
                    throw new IllegalStateException(
                            "exact player candidate retired while its entity lease was live");
                }
                if (claimedTree.get(link.entity) != claim) {
                    throw new IllegalStateException(
                            "exact player tree tombstone changed before retirement");
                }
            }
            candidates.remove(claim.object);
            for (EntityLink link : claim.session.tree) claimedTree.remove(link.entity);
        }

        synchronized PlayerTickPhase.CompletionReceipt<Entity, SessionStamp> offerCompletion(
                PlayerTickPhase.Claim<Entity, SessionStamp> claim) {
            return ledger.offerCompletion(claim);
        }

        synchronized Throwable consumeAcceptedCompletionAndRetire(
                PlayerTickPhase.CompletionReceipt<Entity, SessionStamp> receipt) {
            Throwable failure = ledger.consumeAcceptedCompletion(receipt);
            if (failure == null) retireCandidate(receipt.claim);
            return failure;
        }

        synchronized Throwable poisonCompletionAndRetire(
                PlayerTickPhase.CompletionReceipt<Entity, SessionStamp> receipt,
                Throwable selectedFailure) {
            Throwable result = selectedFailure;
            try {
                result = ledger.poisonCompletion(receipt, result);
            } catch (Throwable completionFailure) {
                result = mergeFailure(result, completionFailure);
            }
            try {
                retireCandidate(receipt.claim);
            } catch (Throwable retirementFailure) {
                result = mergeFailure(result, retirementFailure);
            }
            return result;
        }

        synchronized Throwable poisonCompletion(
                PlayerTickPhase.CompletionReceipt<Entity, SessionStamp> receipt,
                Throwable selectedFailure) {
            return ledger.poisonCompletion(receipt, selectedFailure);
        }

        synchronized Throwable completeClaimFailureAndRetire(
                PlayerTickPhase.Claim<Entity, SessionStamp> claim,
                Throwable selectedFailure) {
            Throwable result = selectedFailure;
            try {
                ledger.complete(claim, selectedFailure);
            } catch (Throwable completionFailure) {
                result = mergeFailure(result, completionFailure);
            }
            try {
                retireCandidate(claim);
            } catch (Throwable retirementFailure) {
                result = mergeFailure(result, retirementFailure);
            }
            return result;
        }

        void signalCompletion(
                PlayerTickPhase.CompletionReceipt<Entity, SessionStamp> receipt) {
            ledger.signalCompletion(receipt);
        }

        synchronized void abortUnstartedAndRelease() {
            Throwable failure = firstFailure();
            try {
                ledger.abortUnstarted();
            } catch (Throwable abortFailure) {
                failure = global.fail(mergeFailure(failure, abortFailure));
            }
            for (PlayerTickPhase.Claim<Entity, SessionStamp> claim : candidates.values()) {
                try {
                    PlayerTickPhase.State state = ledger.stateOf(claim.object);
                    if (state == PlayerTickPhase.State.FAILED
                            || state == PlayerTickPhase.State.RESERVED
                            || state == PlayerTickPhase.State.MAIN_FALLBACK) {
                        cancelReservation(claim);
                    }
                } catch (Throwable cleanupFailure) {
                    failure = global.fail(mergeFailure(failure, cleanupFailure));
                }
            }
            if (failure != null) fail(failure);
        }

        Throwable awaitExternalConsumers(Runnable progress) {
            Throwable primary = firstFailure();
            ArrayList<EntityTickLease> leases;
            try {
                synchronized (this) {
                    leases = new ArrayList<>(externalConsumers.keySet());
                }
            } catch (Throwable snapshotFailure) {
                return fail(snapshotFailure);
            }
            Throwable result = awaitLeases(leases, () -> {
                rethrow(firstFailure());
                try {
                    progress.run();
                } catch (Throwable progressFailure) {
                    rethrow(fail(progressFailure));
                }
            }, primary);
            Throwable published = firstFailure();
            return published == null ? result : mergeFailure(published, result);
        }

        private Throwable releaseReservationList(
                PlayerTickPhase.Claim<Entity, SessionStamp> claim,
                List<EntityTickLease> leases, Throwable primary) {
            Throwable failure = primary == null ? null : global.fail(primary);
            for (int i = leases.size() - 1; i >= 0; i--) {
                try {
                    EntityTickLease lease = leases.get(i);
                    EntityTickLease.CancelResult cancelled = lease.cancelReservedExact(claim);
                    if (cancelled != EntityTickLease.CancelResult.RELEASED) {
                        failure = global.fail(mergeFailure(failure,
                                new IllegalStateException(
                                        "failed to roll back exact player entity reservation: "
                                                + cancelled)));
                    }
                } catch (Throwable cleanupFailure) {
                    failure = global.fail(mergeFailure(failure, cleanupFailure));
                }
            }
            return failure;
        }

        Throwable fail(Throwable failure) {
            Throwable winner = global.fail(failure);
            if (winner == null) return null;
            try {
                ledger.fail(winner);
            } catch (Throwable ledgerFailure) {
                winner = global.fail(mergeFailure(winner, ledgerFailure));
            }
            return winner;
        }

        Throwable failBeforeBody(PlayerTickPhase.Claim<Entity, SessionStamp> claim,
                                 Throwable failure) {
            Throwable winner = global.fail(failure);
            if (winner == null) return null;
            try {
                ledger.failBeforeBody(claim, winner);
            } catch (Throwable ledgerFailure) {
                winner = global.fail(mergeFailure(winner, ledgerFailure));
            }
            return winner;
        }

        Throwable publishLedgerFailure() {
            Throwable failure;
            try {
                failure = ledger.firstFailure();
            } catch (Throwable ledgerFailure) {
                failure = ledgerFailure;
            }
            return failure == null ? global.firstFailure() : global.fail(failure);
        }

        Throwable firstFailure() {
            Throwable globalFailure = global.firstFailure();
            if (globalFailure != null) return globalFailure;
            Throwable ledgerFailure;
            try {
                ledgerFailure = ledger.firstFailure();
            } catch (Throwable observationFailure) {
                return global.fail(observationFailure);
            }
            return ledgerFailure == null ? global.firstFailure() : global.fail(ledgerFailure);
        }

        void throwIfFailed() {
            rethrow(firstFailure());
        }
    }

    private static void installContext(TickContext context) {
        if (TICK_CONTEXT.get() != null) {
            throw new IllegalStateException("nested regional player entity-tick context");
        }
        TICK_CONTEXT.set(context);
    }

    private static Throwable finishAndClearContext(TickContext context, Throwable primary) {
        Throwable result = context.finish(primary);
        if (TICK_CONTEXT.get() != context) {
            IllegalStateException failure = new IllegalStateException(
                    "regional player entity-tick context was replaced");
            Throwable winner = context.batch.fail(mergeFailure(result, failure));
            // Do not remove a foreign replacement: clobbering another owner's TL
            // would hide the corruption. The propagated sticky failure terminates
            // this phase, while suppression itself must never interrupt cleanup.
            return winner;
        }
        TICK_CONTEXT.remove();
        return result;
    }

    private static final class TickContext {
        final LevelBatch batch;
        final Entity root;
        final Region region;
        final PlayerTickPhase.Claim<Entity, SessionStamp> claim;
        final SessionStamp session;
        final boolean worker;
        final boolean exactOrdinal;
        private final IdentityHashMap<ServerPlayer, Boolean> enteredPlayers = new IdentityHashMap<>();
        private int begunLeases;
        private boolean leasesClosed;
        private boolean entityConsumerEntered;

        TickContext(LevelBatch batch, Entity root, Region region,
                    PlayerTickPhase.Claim<Entity, SessionStamp> claim,
                    SessionStamp session, boolean worker) {
            this(batch, root, region, claim, session, worker, false);
        }

        TickContext(LevelBatch batch, Entity root, Region region,
                    PlayerTickPhase.Claim<Entity, SessionStamp> claim,
                    SessionStamp session, boolean worker, boolean exactOrdinal) {
            this.batch = batch;
            this.root = root;
            this.region = region;
            this.claim = claim;
            this.session = session;
            this.worker = worker;
            this.exactOrdinal = exactOrdinal;
        }

        void beginLeases() {
            Throwable failure = null;
            try {
                if (!batch.ledger.bindExecutor(claim)) {
                    Throwable rejection = batch.ledger.firstFailure();
                    if (rejection == null) {
                        rejection = new IllegalStateException(
                                "v2 player claim executor bind was rejected");
                    }
                    rethrow(rejection);
                }
                for (EntityLink link : session.tree) {
                    EntityTickLease lease = ((ParallelProcessor.TickGuard) link.entity)
                            .fabrolia$tickLease();
                    if (!lease.beginReserved(claim)) {
                        throw new IllegalStateException(
                                "v2 entity reservation was not owned at body admission");
                    }
                    begunLeases++;
                }
                return;
            } catch (Throwable thrown) {
                failure = batch.global.fail(thrown);
            }
            failure = batch.global.fail(failure);
            failure = closeLeases(failure);
            rethrow(failure);
        }

        void markPlayerTick(ServerPlayer player) {
            if (!PlayerTickKnownModEnvelope.idle(player)) {
                IllegalStateException failure = new IllegalStateException(
                        "known mod state changed immediately before ServerPlayer.tick entry");
                rethrow(batch.fail(failure));
            }
            if (!session.contains(player)) {
                IllegalStateException failure = new IllegalStateException(
                        "ServerPlayer.tick reached an object outside the claimed session tree");
                rethrow(batch.fail(failure));
            }
            if (enteredPlayers.put(player, Boolean.TRUE) != null) {
                IllegalStateException failure = new IllegalStateException(
                        "ServerPlayer.tick entered twice inside one claimed entity consumer");
                rethrow(batch.fail(failure));
            }
        }

        Throwable finish(Throwable primary) {
            Throwable result = mergeFailure(batch.firstFailure(), primary);
            if (result != null) result = batch.global.fail(result);
            try {
                if (result == null) {
                    if (root.getClass() == ServerPlayer.class
                            && !enteredPlayers.containsKey((ServerPlayer) root)) {
                        result = new IllegalStateException(
                                "exact ServerPlayer entity consumer returned without ServerPlayer.tick");
                    } else {
                        for (ServerPlayer player : enteredPlayers.keySet()) {
                            if (!PlayerTickKnownModEnvelope.idle(player)) {
                                result = new IllegalStateException(
                                        "known mod player-tick state changed inside the exact actor body");
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable validationFailure) {
                result = batch.global.fail(
                        mergeFailure(result, validationFailure));
            }
            if (result != null) result = batch.global.fail(result);
            return closeLeases(result);
        }

        private Throwable closeLeases(Throwable primary) {
            if (leasesClosed) return primary;
            leasesClosed = true;
            Throwable result = primary;
            for (int i = session.tree.size() - 1; i >= 0; i--) {
                try {
                    EntityTickLease lease = ((ParallelProcessor.TickGuard) session.tree.get(i).entity)
                            .fabrolia$tickLease();
                    if (i < begunLeases || lease.isReservedBy(claim)) {
                        EntityTickLease.CancelResult cancelled =
                                lease.cancelReservedExact(claim);
                        if (cancelled == EntityTickLease.CancelResult.RELEASED) continue;
                        throw new IllegalStateException(
                                "failed to release exact v2 entity reservation: " + cancelled);
                    }
                } catch (Throwable cleanup) {
                    result = batch.global.fail(mergeFailure(result, cleanup));
                }
            }
            begunLeases = 0;
            return result;
        }
    }

    /** Immutable object/session snapshot for every player reachable from one root tick. */
    private static final class SessionStamp {
        private final List<PlayerSession> players;
        private final List<EntityLink> tree;
        private final Entity rootVehicle;

        private SessionStamp(List<PlayerSession> players, List<EntityLink> tree,
                             Entity rootVehicle) {
            this.players = List.copyOf(players);
            this.tree = List.copyOf(tree);
            this.rootVehicle = rootVehicle;
        }

        static SessionStamp capture(Entity root) {
            ArrayList<PlayerSession> sessions = new ArrayList<>();
            ArrayList<EntityLink> tree = new ArrayList<>();
            collect(root, null, sessions, tree);
            return new SessionStamp(sessions, tree, root.getVehicle());
        }

        private static void collect(Entity entity, Entity parent,
                                    ArrayList<PlayerSession> sessions,
                                    ArrayList<EntityLink> tree) {
            tree.add(new EntityLink(entity, parent));
            if (entity instanceof ServerPlayer player) {
                sessions.add(new PlayerSession(player, player.connection,
                        ((PlayerTickPhaseStamp) player).fabrolia$playerLifecycleEpoch()));
            }
            // getPassengers() is final in exact 26.2. Never trust an overridable
            // convenience predicate when a hidden player would change the venue.
            for (Entity passenger : entity.getPassengers()) {
                collect(passenger, entity, sessions, tree);
            }
        }

        boolean isEmpty() {
            return players.isEmpty();
        }

        boolean contains(ServerPlayer player) {
            for (PlayerSession session : players) if (session.player == player) return true;
            return false;
        }

        boolean containsEntity(Entity entity) {
            for (EntityLink link : tree) if (link.entity == entity) return true;
            return false;
        }

        boolean stillCurrent(LevelBatch batch, Entity root, boolean authoritativeMain) {
            if (root.getVehicle() != rootVehicle) return false;
            SessionStamp now = capture(root);
            if (now.tree.size() != tree.size() || now.players.size() != players.size()) return false;
            for (EntityLink expected : tree) {
                boolean found = false;
                for (EntityLink observed : now.tree) {
                    if (observed.entity == expected.entity && observed.parent == expected.parent) {
                        found = true;
                        break;
                    }
                }
                if (!found) return false;
            }
            for (PlayerSession expected : players) {
                boolean found = false;
                for (PlayerSession observed : now.players) {
                    if (observed.player == expected.player
                            && observed.listener == expected.listener
                            && observed.lifecycleEpoch == expected.lifecycleEpoch) {
                        found = true;
                        break;
                    }
                }
                ServerPlayer player = expected.player;
                var listener = player.connection;
                if (!found || expected.listener == null
                        || ((PlayerTickPhaseStamp) player).fabrolia$playerLifecycleEpoch()
                                != expected.lifecycleEpoch
                        || !batch.hasSession(player, expected.listener)
                        || listener != expected.listener
                        || listener == null
                        || listener.player != player
                        || player.hasDisconnected()
                        || player.connection != listener) {
                    return false;
                }
                if (authoritativeMain
                        && batch.global.server.getPlayerList().getPlayer(player.getUUID()) != player) {
                    return false;
                }
            }
            return true;
        }
    }

    private record PlayerSession(ServerPlayer player, Object listener, long lifecycleEpoch) {}
    private record EntityLink(Entity entity, Entity parent) {}
    private record LogicalTurn(MinecraftServer server, long phaseId, ServerLevel level,
                               Thread physicalMain, Thread actor) {}

    private static Throwable awaitLeases(List<EntityTickLease> leases, Runnable progress,
                                         Throwable primary) {
        Throwable failure = primary;
        boolean progressAllowed = failure == null;
        boolean interrupted = Thread.interrupted();
        for (EntityTickLease lease : leases) {
            boolean free = false;
            while (!free) {
                try {
                    free = lease.awaitFreeNanos(100_000L);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
                if (!free && progressAllowed) {
                    try {
                        progress.run();
                    } catch (Throwable pumpFailure) {
                        failure = mergeFailure(failure, pumpFailure);
                        // A failing progress body is itself the phase failure.
                        // Keep waiting for already-started leases, but never run
                        // this or another progress body again.
                        progressAllowed = false;
                    }
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        return failure;
    }

    private static final class GlobalPhase {
        final long id;
        final boolean enabledSnapshot;
        final MinecraftServer server;
        final Thread mainThread;
        private final ArrayList<LevelBatch> allLevels = new ArrayList<>();
        private final ArrayList<LevelBatch> openLevels = new ArrayList<>();
        private final IdentityHashMap<EntityTickLease, Boolean> externalConsumers =
                new IdentityHashMap<>();
        private boolean sealed;
        private Throwable failure;
        private boolean pauseHeld;
        private ServerLevel pausedLevel;
        private boolean connectionPause;
        volatile boolean connectionsBarrierPassed;

        GlobalPhase(long id, boolean enabledSnapshot, MinecraftServer server) {
            this.id = id;
            this.enabledSnapshot = enabledSnapshot;
            this.server = server;
            this.mainThread = server == null ? null : Thread.currentThread();
        }

        void pauseForLevel(LevelBatch batch) {
            throwIfFailed();
            Throwable conflict = null;
            synchronized (this) {
                if (pauseHeld) {
                    conflict = new IllegalStateException(
                            "region pool turn already paused");
                } else {
                    pauseHeld = true;
                    pausedLevel = batch.owner.level;
                    connectionPause = false;
                }
            }
            if (conflict != null) rethrow(fail(conflict));
            try {
                RegionScheduler.pauseExactPlayerTurn();
            } catch (Throwable thrown) {
                rethrow(fail(thrown));
            }
        }

        void pauseForConnections() {
            throwIfFailed();
            Throwable conflict = null;
            synchronized (this) {
                if (pauseHeld) {
                    conflict = new IllegalStateException(
                            "level player turn remained paused before connections");
                } else {
                    pauseHeld = true;
                    pausedLevel = null;
                    connectionPause = true;
                }
            }
            if (conflict != null) rethrow(fail(conflict));
            try {
                RegionScheduler.pauseExactPlayerTurn();
            } catch (Throwable thrown) {
                rethrow(fail(thrown));
            }
        }

        Throwable releaseLevelPause(ServerLevel level) {
            Throwable conflict = null;
            synchronized (this) {
                if (!pauseHeld) return null;
                if (connectionPause || pausedLevel != level) {
                    conflict = new IllegalStateException(
                            "ServerLevel tried to release another exact player turn");
                }
            }
            if (conflict != null) return fail(conflict);
            return releasePause();
        }

        synchronized boolean isLevelPauseHeld(ServerLevel level) {
            return pauseHeld && !connectionPause && pausedLevel == level;
        }

        synchronized boolean pauseStillHeld() {
            return pauseHeld;
        }

        Throwable releasePause() {
            synchronized (this) {
                if (!pauseHeld) return null;
            }
            Throwable failure = null;
            try {
                RegionScheduler.resumeExactPlayerTurn();
            } catch (Throwable thrown) {
                failure = fail(thrown);
            }
            // Replay failures are reported after the pool has already reopened;
            // identity/ownership failures leave the real fence held for fail-stop
            // cleanup instead of trusting a stale mirrored phase flag.
            boolean stillPaused;
            try {
                stillPaused = RegionScheduler.isExactPlayerTurnPausedBy(mainThread);
            } catch (Throwable observationFailure) {
                return fail(mergeFailure(failure, observationFailure));
            }
            if (!stillPaused) {
                synchronized (this) {
                    pauseHeld = false;
                    pausedLevel = null;
                    connectionPause = false;
                }
            }
            Throwable published = firstFailure();
            return published == null ? failure : published;
        }

        LevelBatch beginLevel(RegionizedServer owner) {
            try {
                synchronized (this) {
                    rethrow(failure);
                    if (sealed) {
                        throw new IllegalStateException(
                                "level began after global player phase seal");
                    }
                    LevelBatch batch = new LevelBatch(this, owner);
                    allLevels.add(batch);
                    openLevels.add(batch);
                    return batch;
                }
            } catch (Throwable thrown) {
                rethrow(fail(thrown));
                return null;
            }
        }

        void finishLevel(LevelBatch batch) {
            Throwable bookkeepingFailure = null;
            try {
                synchronized (this) {
                    if (!openLevels.remove(batch)) {
                        bookkeepingFailure = new IllegalStateException(
                                "player level batch finished twice");
                    }
                }
            } catch (Throwable thrown) {
                bookkeepingFailure = thrown;
            }
            if (bookkeepingFailure != null) fail(bookkeepingFailure);
            try {
                Throwable levelFailure = batch.ledger.firstFailure();
                if (levelFailure != null) fail(levelFailure);
            } catch (Throwable ledgerFailure) {
                fail(ledgerFailure);
            }
        }

        synchronized boolean hasOpenLevels() {
            return !openLevels.isEmpty();
        }

        Throwable sealAndCheck() {
            List<LevelBatch> levels;
            Throwable openFailure = null;
            synchronized (this) {
                sealed = true;
                if (!openLevels.isEmpty()) {
                    openFailure = new IllegalStateException(
                            "connection phase reached with open player level batch");
                }
            }
            if (openFailure != null) openFailure = fail(openFailure);
            try {
                synchronized (this) {
                    levels = List.copyOf(allLevels);
                }
            } catch (Throwable snapshotFailure) {
                return fail(mergeFailure(openFailure, snapshotFailure));
            }
            for (LevelBatch batch : levels) {
                try {
                    Throwable terminal = batch.ledger.verifyTerminal();
                    if (terminal != null) fail(terminal);
                } catch (Throwable verificationFailure) {
                    fail(verificationFailure);
                }
            }
            return firstFailure();
        }

        boolean hasCandidate(ServerPlayer player) {
            try {
                List<LevelBatch> levels;
                synchronized (this) {
                    levels = List.copyOf(allLevels);
                }
                for (LevelBatch batch : levels) {
                    if (batch.hasCandidate(player)) return true;
                }
                return false;
            } catch (Throwable thrown) {
                rethrow(fail(thrown));
                return false;
            }
        }

        boolean hasTreeClaim(Entity entity) {
            try {
                List<LevelBatch> levels;
                synchronized (this) {
                    levels = List.copyOf(allLevels);
                }
                for (LevelBatch batch : levels) {
                    if (batch.hasTreeClaim(entity)) return true;
                }
                return false;
            } catch (Throwable thrown) {
                rethrow(fail(thrown));
                return false;
            }
        }

        boolean hasCandidateInTree(Entity root) {
            try {
                SessionStamp observed = SessionStamp.capture(root);
                for (PlayerSession session : observed.players) {
                    if (hasCandidate(session.player)) return true;
                }
                return false;
            } catch (Throwable thrown) {
                rethrow(fail(thrown));
                return false;
            }
        }

        void registerExternalConsumer(EntityTickLease lease) {
            try {
                synchronized (this) {
                    externalConsumers.put(lease, Boolean.TRUE);
                }
            } catch (Throwable thrown) {
                rethrow(fail(thrown));
            }
        }

        Throwable awaitExternalConsumers(Runnable progress) {
            Throwable primary = firstFailure();
            ArrayList<EntityTickLease> leases;
            try {
                synchronized (this) {
                    leases = new ArrayList<>(externalConsumers.keySet());
                }
            } catch (Throwable snapshotFailure) {
                return fail(snapshotFailure);
            }
            Throwable result = awaitLeases(leases, () -> {
                rethrow(firstFailure());
                try {
                    progress.run();
                } catch (Throwable progressFailure) {
                    rethrow(fail(progressFailure));
                }
            }, primary);
            Throwable published = firstFailure();
            return published == null ? result : mergeFailure(published, result);
        }

        Throwable abortAndAwait() {
            List<LevelBatch> batches;
            try {
                synchronized (this) {
                    sealed = true;
                    batches = List.copyOf(allLevels);
                }
            } catch (Throwable snapshotFailure) {
                rethrow(fail(snapshotFailure));
                return null;
            }
            Throwable cleanupFailure = firstFailure();
            for (LevelBatch batch : batches) {
                try {
                    batch.abortUnstartedAndRelease();
                } catch (Throwable releaseFailure) {
                    cleanupFailure = fail(
                            mergeFailure(cleanupFailure, releaseFailure));
                }
            }
            // The enclosing vanilla body has already failed. Exceptional cleanup
            // may wait for or release accepted obligations, but it may not execute
            // another chunk/progress body in an attempt to keep this tick alive.
            Runnable progress = () -> {};
            for (LevelBatch batch : batches) {
                try {
                    batch.ledger.sealAndAwait();
                    cleanupFailure = mergeFailure(cleanupFailure,
                            batch.ledger.firstFailure());
                    if (cleanupFailure != null) cleanupFailure = fail(cleanupFailure);
                } catch (Throwable waitFailure) {
                    cleanupFailure = fail(mergeFailure(cleanupFailure, waitFailure));
                }
            }
            try {
                cleanupFailure = mergeFailure(cleanupFailure,
                        awaitExternalConsumers(progress));
                if (cleanupFailure != null) cleanupFailure = fail(cleanupFailure);
            } catch (Throwable waitFailure) {
                cleanupFailure = fail(mergeFailure(cleanupFailure, waitFailure));
            }
            for (LevelBatch batch : batches) {
                try {
                    cleanupFailure = mergeFailure(cleanupFailure,
                            batch.awaitExternalConsumers(progress));
                    if (cleanupFailure != null) cleanupFailure = fail(cleanupFailure);
                } catch (Throwable waitFailure) {
                    cleanupFailure = fail(mergeFailure(cleanupFailure, waitFailure));
                }
                // A pool cancellation may have published MAIN_FALLBACK while the
                // running counter was draining. Exceptional world abort never replays it.
                try {
                    batch.abortUnstartedAndRelease();
                    cleanupFailure = mergeFailure(cleanupFailure,
                            batch.ledger.verifyTerminal());
                    if (cleanupFailure != null) cleanupFailure = fail(cleanupFailure);
                } catch (Throwable releaseFailure) {
                    cleanupFailure = fail(
                            mergeFailure(cleanupFailure, releaseFailure));
                }
            }
            if (cleanupFailure != null) cleanupFailure = fail(cleanupFailure);
            for (LevelBatch batch : batches) {
                try {
                    if (batch.owner.playerTickV2Batch == batch) {
                        batch.owner.playerTickV2Batch = null;
                    }
                } catch (Throwable bookkeepingFailure) {
                    cleanupFailure = fail(
                            mergeFailure(cleanupFailure, bookkeepingFailure));
                }
            }
            try {
                synchronized (this) {
                    openLevels.clear();
                    allLevels.clear();
                }
            } catch (Throwable bookkeepingFailure) {
                fail(mergeFailure(cleanupFailure, bookkeepingFailure));
            }
            return firstFailure();
        }

        Throwable fail(Throwable thrown) {
            if (thrown == null) return firstFailure();
            Throwable winner = RegionScheduler.isActive()
                    ? RegionScheduler.publishFatalAndSelectWinner(thrown)
                    : thrown;
            synchronized (this) {
                recordFailureLocked(winner);
                return failure;
            }
        }

        synchronized Throwable firstFailure() {
            return failure;
        }

        void throwIfFailed() {
            rethrow(firstFailure());
        }

        private void recordFailureLocked(Throwable thrown) {
            if (thrown == null) return;
            failure = mergeFailure(failure, thrown);
        }
    }
}
