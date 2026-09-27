package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.axalotl.fabrolia.common.mixin.server.ReentrantBlockableEventLoopAccessor;
import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementBuffer;
import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementBatchScope;
import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementCaptureOwnership;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import com.axalotl.fabrolia.common.ParallelProcessor;

/**
 * Top-level region scheduler. Lifecycle tied to {@link MinecraftServer}: started
 * in SERVER_STARTING after {@link ParallelProcessor#setServer},
 * stopped in SERVER_STOPPING.
 *
 * <p>Gated by {@link FabroliaConfig#enableRegionThreading} — when off, the scheduler stays
 * dormant, the entity index is not maintained, and ServerLevelMixin takes the chunked
 * fabrolia-pool path. Runtime toggle re-bootstraps the index from EntityTickList.
 *
 * <p>Region-owned surface: {@link #dispatchOwnedEntityTicks(ServerLevel)} submits one
 * task per non-empty region over the region's own entity set; the caller barriers on the
 * returned future. Index maintenance flows through {@link #onEntityAdd}/{@link #onEntityRemove}
 * (EntityTickListMixin) and lazy cross-cell moves in RegionizedServer.tickOwned.
 */
public final class RegionScheduler {

    /** The level main is currently ticking (source-domain resolution for
     *  main-thread cross-dimension teleports). Main thread writes only. */
    public static volatile net.minecraft.server.level.ServerLevel mainCurrentLevel;

    private static final Logger LOGGER = LoggerFactory.getLogger(RegionScheduler.class);

    private RegionScheduler() {}

    private static volatile MinecraftServer server;
    private static volatile RegionWorkerPool pool;
    /** Startup failure/admission authority before the runtime is scheduler-visible. */
    private static volatile RegionWorkerPool transitionPool;

    private static volatile RegionGrid grid;
    private static final Map<ServerLevel, RegionizedServer> PER_LEVEL = new ConcurrentHashMap<>();
    /**
     * Stable BE-wrapper positions (2026-08-21 universal-crafter incident).
     * Lithium's fully sleeping delegate reports {@code getPos()==null}; this
     * registration stamp survives regions-off and lets the next bulk rebuild
     * distinguish that sleeper from a genuinely positionless ticker.
     */
    private static final BlockEntityTickerPositionIndex<TickingBlockEntity, BlockPos>
            BLOCK_ENTITY_TICKER_POSITIONS = new BlockEntityTickerPositionIndex<>();
    private static volatile boolean active;
    private static volatile boolean accepting;
    /** Short scheduler lifecycle lock.  It is never held while joining a worker. */
    private static final Object LIFECYCLE_LOCK = new Object();
    private enum LifecycleState { STOPPED, STARTING, RUNNING, STOPPING, FAILED }
    private static LifecycleState lifecycleState = LifecycleState.STOPPED;
    private static Thread lifecycleOwner;
    private static long lifecycleGeneration;

    /** Failure published while no pool reference is available (startup/teardown edge). */
    private static volatile Throwable orphanFatalFailure;
    /**
     * Lifecycle commands entered from an accepted pool body cannot stop that
     * pool inline: their enclosing ExternalPermit is itself part of stop's fixed
     * point.  Keep those commands in a scheduler-owned FIFO until the exact
     * physical-Main post-tickServer boundary has returned from every such body.
     * The lock protects publication/inspection only and is never held while a
     * transition quiesces or joins workers.
     */
    private static final Object DEFERRED_LIFECYCLE_LOCK = new Object();
    private static final ArrayDeque<DeferredLifecycleTransition>
            DEFERRED_LIFECYCLE_TRANSITIONS = new ArrayDeque<>();

    private static RegionWorkerPool failureGateLocked() {
        return pool != null ? pool : transitionPool;
    }

    private static final class StartDraft {
        RegionGrid grid;
        java.util.Map<ServerLevel, RegionizedServer> levels;
        int bootstrappedEntities;
    }

    /**
     * A lifecycle command can itself be nested in a pool-owned Main success body
     * (for example the tickConnection console drain). Stopping that pool inline
     * would wait for the command's own ExternalPermit. Publish one exact FIFO
     * baton instead; the post-tickServer boundary enters it only after the
     * complete command/tick stack has returned and retired that permit.
     *
     * @return true when the transition was accepted for detached execution.
     */
    public static boolean deferLifecycleTransitionFromCurrentBody(
            MinecraftServer mcServer, Runnable transition) {
        if (mcServer == null || transition == null) throw new NullPointerException();
        if (RegionWorkerPool.currentThreadCausalOwnerOrNull() == null) return false;
        if (!RegionalPlayerEntityTicksV2.isPhysicalServerThread(mcServer)
                || !RegionalPlayerEntityTicksV2
                .schedulerLifecycleTransitionAllowed(mcServer)) {
            throw new IllegalStateException(
                    "region lifecycle deferral lost its between-phase physical Main");
        }
        DeferredLifecycleTransition task =
                new DeferredLifecycleTransition(mcServer, transition);
        // This command already owns a live causal permit, so the commit adds no
        // second ExternalPermit.  Queue publication and first-W selection retain
        // one cut; a W which won first leaves no accepted orphan baton.
        commitSuccessIfHealthy(() -> {
            synchronized (DEFERRED_LIFECYCLE_LOCK) {
                DEFERRED_LIFECYCLE_TRANSITIONS.addLast(task);
            }
        });
        return true;
    }

    /**
     * Exact consumer for deferred lifecycle commands. Called only after the
     * top-level {@code processPacketsAndTick -> tickServer} invocation returns.
     * Consequently the command fence, tickChildren wrapper, and the publishing
     * ExternalPermit have all closed before stop is allowed to await its pool.
     */
    public static void drainDeferredLifecycleTransitionsAtPostTickBoundary(
            MinecraftServer mcServer) {
        if (mcServer == null) throw new NullPointerException("mcServer");
        requireDeferredLifecycleBoundary(mcServer);
        NetherPortalTransfers.serviceMain(mcServer);
        while (true) {
            DeferredLifecycleTransition task;
            synchronized (DEFERRED_LIFECYCLE_LOCK) {
                task = DEFERRED_LIFECYCLE_TRANSITIONS.peekFirst();
            }
            if (task == null) return;
            if (task.server != mcServer) {
                throw new IllegalStateException(
                        "deferred region lifecycle FIFO crossed its server identity");
            }

            task.runAtPostTickBoundary(mcServer);

            // Retain the completed head until its terminal W cut has returned.
            // No later FIFO request can overtake a failing or still-running head.
            synchronized (DEFERRED_LIFECYCLE_LOCK) {
                if (DEFERRED_LIFECYCLE_TRANSITIONS.peekFirst() != task
                        || task.state() != DeferredLifecycleTransition.COMPLETE) {
                    throw new IllegalStateException(
                            "deferred region lifecycle FIFO lost its completed head");
                }
                DEFERRED_LIFECYCLE_TRANSITIONS.removeFirst();
            }
            requireDeferredLifecycleBoundary(mcServer);
        }
    }

    private static void requireDeferredLifecycleBoundary(MinecraftServer mcServer) {
        if (!RegionalPlayerEntityTicksV2.isPhysicalServerThread(mcServer)
                || !RegionalPlayerEntityTicksV2
                .schedulerLifecycleTransitionAllowed(mcServer)
                || RegionWorkerPool.currentThreadCausalOwnerOrNull() != null
                || IslandCommands.fenceWindowHeld()) {
            throw new IllegalStateException(
                    "deferred region lifecycle drain lost its post-tick physical-Main boundary");
        }
    }

    /** Exact-once body admission with no ExternalPermit for stop() to self-await. */
    static final class DeferredLifecycleTransition {
        static final int QUEUED = 0;
        static final int RUNNING = 1;
        static final int COMPLETE = 2;

        private final MinecraftServer server;
        private final Runnable transition;
        private final AtomicInteger state = new AtomicInteger(QUEUED);

        DeferredLifecycleTransition(MinecraftServer server, Runnable transition) {
            if (server == null) throw new NullPointerException("server");
            if (transition == null) throw new NullPointerException("transition");
            this.server = server;
            this.transition = transition;
        }

        void runAtPostTickBoundary(MinecraftServer boundaryServer) {
            // The fixed CAS is the W-vs-entry linearization edge. In particular,
            // do not use runSuccessBodyIfHealthy here: its ExternalPermit would
            // recreate the exact self-deadlock this baton exists to remove.
            commitSuccessIfHealthy(() -> {
                if (server != boundaryServer) {
                    throw new IllegalStateException(
                            "deferred region lifecycle baton changed server identity");
                }
                if (!RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)
                        || !RegionalPlayerEntityTicksV2
                        .schedulerLifecycleTransitionAllowed(server)
                        || RegionWorkerPool.currentThreadCausalOwnerOrNull() != null
                        || IslandCommands.fenceWindowHeld()) {
                    throw new IllegalStateException(
                            "deferred region lifecycle baton entered outside its post-tick boundary");
                }
                if (!state.compareAndSet(QUEUED, RUNNING)) {
                    throw new IllegalStateException(
                            "deferred region lifecycle task executed more than once");
                }
            });
            transition.run();
            // A clean transition must observe a peer W selected while start/stop
            // was running before it can expose successful command completion.
            commitSuccessIfHealthy(() -> {
                if (!state.compareAndSet(RUNNING, COMPLETE)) {
                    throw new IllegalStateException(
                            "deferred region lifecycle task lost its running baton");
                }
            });
        }

        int state() {
            return state.get();
        }
    }

    public static boolean isActive() { return active; }
    public static MinecraftServer getServer() { return server; }
    public static int workerCount() { return pool == null ? 0 : pool.size(); }
    public static RegionGrid grid() { return grid; }

    /**
     * Exact scheduler-generation admission predicate for an island unit.
     *
     * <p>{@code active} alone is deliberately insufficient: normal stop keeps the
     * old runtime visible while it drains already-accepted bodies.  A delayed pacer
     * ticket from that runtime must be refused before body entry, and must never
     * publish its lifecycle refusal into a later scheduler generation.
     */
    static boolean acceptsUnitFor(RegionizedServer expected) {
        synchronized (LIFECYCLE_LOCK) {
            return acceptsUnitForLocked(expected);
        }
    }

    static boolean ownsRuntimeGeneration(RegionizedServer expected) {
        synchronized (LIFECYCLE_LOCK) {
            return expected != null && pool != null
                    && PER_LEVEL.get(expected.level) == expected;
        }
    }

    /** Accepted synchronous player L retains this exact pool through normal stop. */
    static boolean ownsRuntimeGeneration(RegionizedServer expected, RegionWorkerPool expectedPool) {
        synchronized (LIFECYCLE_LOCK) {
            return expectedPool != null && pool == expectedPool
                    && expected != null && PER_LEVEL.get(expected.level) == expected;
        }
    }

    private static boolean acceptsUnitForLocked(RegionizedServer expected) {
        return expected != null
                && lifecycleState == LifecycleState.RUNNING
                && active && accepting && pool != null
                && expected.asyncActive
                && PER_LEVEL.get(expected.level) == expected;
    }

    /**
     * Commit pacer bookkeeping/successors only against the pool generation that
     * owns the ticket.  False means that generation retired before the commit;
     * the caller must cancel its OFFERING receipt locally and publish no W.
     */
    static boolean commitUnitSuccessIfCurrent(RegionizedServer expected,
                                              Runnable commit) {
        if (commit == null) throw new NullPointerException("commit");
        synchronized (LIFECYCLE_LOCK) {
            if (!acceptsUnitForLocked(expected)) return false;
            pool.commitSuccessIfHealthy(commit);
            return true;
        }
    }

    /**
     * Run one autonomous-pacer stage against the pool generation which owns its
     * {@code Entry.rs}.  A stale entry is bookkeeping only and returns false; it
     * must never fall through to the static current/replacement pool.
     *
     * <p>The scheduler lock is released before the potentially unbounded exact-turn
     * wait.  Normal stop first publishes {@code accepting=false}, then interrupts
     * and joins this exact pacer daemon before retiring the captured pool, so a
     * lifecycle race is reclassified by the body's own generation checks or exits
     * through the normal-stop interrupt path.
     */
    static boolean runPacerBodyIfCurrent(RegionizedServer expected, Runnable body)
            throws InterruptedException {
        if (body == null) throw new NullPointerException("body");
        RegionWorkerPool expectedPool;
        synchronized (LIFECYCLE_LOCK) {
            if (!acceptsUnitForLocked(expected)) return false;
            expectedPool = pool;
        }
        return expectedPool.runPacerBodyIfHealthy(body);
    }

    public static void start(MinecraftServer mcServer) {
        if (!RegionalPlayerEntityTicksV2.schedulerLifecycleTransitionAllowed(mcServer)) {
            throw new IllegalStateException(
                    "RegionScheduler.start requires physical main between server-tick phases");
        }
        boolean regionThreadingEnabled = callSuccessBooleanIfHealthy(() -> {
            FabroliaConfig.enforceSafetyInvariants();
            if (!FabroliaConfig.enableRegionThreading) {
                LOGGER.debug("RegionScheduler: enableRegionThreading=false, scheduler dormant");
                return false;
            }
            return true;
        });
        if (!regionThreadingEnabled) return;
        final long generation;
        synchronized (LIFECYCLE_LOCK) {
            Throwable failure = orphanFatalFailure;
            if (failure != null) RegionScheduler.<RuntimeException>rethrow(failure);
            if (lifecycleState == LifecycleState.RUNNING) return;
            if (lifecycleState != LifecycleState.STOPPED) {
                throw new IllegalStateException(
                        "RegionScheduler cannot start from lifecycle state " + lifecycleState);
            }
            lifecycleState = LifecycleState.STARTING;
            lifecycleOwner = Thread.currentThread();
            generation = ++lifecycleGeneration;
        }
        RegionWorkerPool startedPool = null;
        StartDraft draft = null;
        Throwable failure = null;
        try {
            com.axalotl.fabrolia.common.utils.SensorCandidateBridge.sealBootstrap();
            NativeEntityKnownModEnvelope.initialize();
            int n = FabroliaConfig.getRegionWorkerCount();
            StartDraft buildDraft = new StartDraft();
            draft = buildDraft;
            RegionWorkerPool transitionGate = new RegionWorkerPool(n, mcServer);
            startedPool = transitionGate;
            synchronized (LIFECYCLE_LOCK) {
                Throwable failureBeforeTransition = orphanFatalFailure;
                if (failureBeforeTransition != null) {
                    RegionScheduler.<RuntimeException>rethrow(failureBeforeTransition);
                }
                if (lifecycleState != LifecycleState.STARTING
                        || lifecycleOwner != Thread.currentThread()
                        || lifecycleGeneration != generation
                        || pool != null || transitionPool != null) {
                    throw new IllegalStateException(
                            "RegionScheduler start lost its transition-gate generation");
                }
                transitionPool = transitionGate;
                // Keep the scheduler cut only through the bounded Thread.start
                // publication. The pool commit owns its first-W cut and keeps every
                // new worker behind the pool monitor until accepting=true is visible.
                transitionGate.commitSuccessIfHealthy(transitionGate::start);
            }

            RegionWorkerPool bootstrapGate = transitionGate;
            bootstrapGate.runSuccessBodyIfHealthy(() -> {
                TickDomains.clearFinalClocks();
                RegionGrid startedGrid = new RegionGrid(RegionGrid.DEFAULT_SHIFT);
                java.util.Map<ServerLevel, RegionizedServer> startedLevels =
                        new java.util.IdentityHashMap<>();
                buildDraft.grid = startedGrid;
                buildDraft.levels = startedLevels;

                // Build the entire generation off to the side. Nothing publishes
                // active=true, a grid, or a partially-filled PER_LEVEL map yet.
                int bootstrapped = 0;
                for (ServerLevel lvl : mcServer.getAllLevels()) {
                    RegionizedServer rs = new RegionizedServer(lvl, startedGrid);
                    startedLevels.put(lvl, rs);
                    final int[] count = {0};
                    lvl.entityTickList.forEach(e -> { rs.indexAdd(e); count[0]++; });
                    bootstrapped += count[0];
                    var rta = (RegionTickingAccess) lvl.getChunkSource().chunkMap;
                    for (net.minecraft.server.level.ChunkHolder h : rta.fabrolia$visibleHolders()) {
                        if (net.minecraft.server.level.ChunkLevel.fullStatus(h.getTicketLevel())
                                .isOrAfter(net.minecraft.server.level.FullChunkStatus.FULL)) {
                            rs.onChunkFullTransition(h.getPos().pack(), true, h.getTickingChunk());
                        }
                    }
                }
                buildDraft.bootstrappedEntities = bootstrapped;
            });
            if (buildDraft.bootstrappedEntities > 0) {
                runSuccessBodyIfHealthy(() -> LOGGER.info(
                        "RegionScheduler: bootstrapped {} entities into region index",
                        buildDraft.bootstrappedEntities));
            }
            RegionWorkerPool poolToPublish = transitionGate;
            synchronized (LIFECYCLE_LOCK) {
                if (transitionPool != poolToPublish) {
                    throw new IllegalStateException(
                            "RegionScheduler start commit lost its transition pool");
                }
                poolToPublish.commitSuccessIfHealthy(() -> {
                    if (lifecycleState != LifecycleState.STARTING
                            || lifecycleOwner != Thread.currentThread()
                            || lifecycleGeneration != generation
                            || pool != null || transitionPool != poolToPublish) {
                        throw new IllegalStateException(
                                "RegionScheduler start commit lost its generation");
                    }
                    PER_LEVEL.clear();
                    PER_LEVEL.putAll(buildDraft.levels);
                    server = mcServer;
                    grid = buildDraft.grid;
                    pool = poolToPublish;
                    transitionPool = null;
                    active = true;
                    accepting = true;
                    lifecycleState = LifecycleState.RUNNING;
                    lifecycleOwner = null;
                    LIFECYCLE_LOCK.notifyAll();
                });
            }
            runSuccessBodyIfHealthy(() -> LOGGER.warn(
                    "RegionScheduler: ACTIVE with {} workers, grid shift={} ({}x{} chunks/region) — "
                            + "experimental Fabrolia region threading. Report issues at "
                            + "https://github.com/AxalotLDev/Fabrolia/issues with [region] tag",
                    n, buildDraft.grid.shift,
                    1 << buildDraft.grid.shift, 1 << buildDraft.grid.shift));
            boolean playerTickPreferenceApplied = callSuccessBooleanIfHealthy(
                    () -> RegionalPlayerEntityTicksV2.applyConfiguredPreference(mcServer));
            runSuccessBodyIfHealthy(() -> {
                if (!FabroliaConfig.enablePlayerTickActor) return;
                if (playerTickPreferenceApplied) {
                    LOGGER.info("RegionScheduler: restored saved player-tick ownership preference ON");
                    return;
                }
                LOGGER.warn("RegionScheduler: saved player-tick ownership preference is ON, "
                                + "but runtime activation was refused: {}",
                        RegionalPlayerEntityTicksV2.refusalReason(mcServer));
            });
            // Runtime publication and the optional preference hook are distinct
            // entered stages.  Do not report a healthy start over a pool W which
            // won before this final success edge.
            commitSuccessIfHealthy(() -> {});
            return;
        } catch (Throwable startFailure) {
            // Thread.start/bootstrap and the post-ACTIVE preference hook are
            // runtime-transition bodies. Close the new generation immediately;
            // pool stop and index rollback below are mandatory cleanup only.
            failure = startedPool == null
                    ? startFailure : startedPool.publishAndSelectFatal(startFailure);
            if (startedPool == null || startedPool != pool) {
                failure = publishFatalAndSelectWinner(failure);
            }
        }

        accepting = false;
        if (startedPool != null) {
            try {
                startedPool.stop();
            } catch (Throwable cleanup) {
                failure = mergeFailure(failure, cleanup);
            }
        }
        if (startedPool != null && !startedPool.isTerminated()) {
            synchronized (LIFECYCLE_LOCK) {
                lifecycleState = LifecycleState.FAILED;
                lifecycleOwner = null;
                LIFECYCLE_LOCK.notifyAll();
            }
            RegionScheduler.<RuntimeException>rethrow(failure != null ? failure
                    : new IllegalStateException("partially-started region pool stayed live"));
        }
        active = false;
        if (draft != null && draft.levels != null) {
            for (RegionizedServer rs : draft.levels.values()) {
                try { rs.regionizer().clear(); } catch (Throwable cleanup) {
                    failure = mergeFailure(failure, cleanup);
                }
            }
        }
        PER_LEVEL.clear();
        synchronized (LIFECYCLE_LOCK) {
            Throwable retiredWinner = startedPool == null
                    ? null : startedPool.fatalFailureOrNull();
            if (retiredWinner != null) {
                failure = mergeFailure(retiredWinner, failure);
            }
            if (failure != null) {
                Throwable orphan = orphanFatalFailure;
                if (orphan == null) {
                    orphanFatalFailure = failure;
                } else {
                    failure = mergeFailure(orphan, failure);
                }
            }
            if (pool == startedPool) pool = null;
            if (transitionPool == startedPool) transitionPool = null;
            grid = null;
            server = null;
            mainCurrentLevel = null;
            boolean stopped = startedPool == null || startedPool.isTerminated();
            lifecycleState = stopped && pool == null && transitionPool == null
                    && orphanFatalFailure == null
                    ? LifecycleState.STOPPED : LifecycleState.FAILED;
            lifecycleOwner = null;
            LIFECYCLE_LOCK.notifyAll();
        }
        RegionScheduler.<RuntimeException>rethrow(failure);
    }

    public static void stop() {
        if (Thread.currentThread() instanceof RegionWorkerThread) {
            throw new IllegalStateException(
                    "RegionScheduler.stop cannot self-join a region worker; invoke it on main");
        }
        MinecraftServer currentServer = server;
        if (currentServer != null
                && !RegionalPlayerEntityTicksV2
                .schedulerLifecycleTransitionAllowed(currentServer)) {
            throw new IllegalStateException(
                    "RegionScheduler.stop requires physical main between server-tick phases");
        }
        boolean interrupted = false;
        synchronized (LIFECYCLE_LOCK) {
            while (lifecycleState == LifecycleState.STARTING
                    || lifecycleState == LifecycleState.STOPPING) {
                if (lifecycleOwner == Thread.currentThread()) {
                    throw new IllegalStateException(
                            "RegionScheduler.stop re-entered from its own quiescence callback");
                }
                try {
                    LIFECYCLE_LOCK.wait();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            MinecraftServer authoritativeServer = server;
            if (authoritativeServer != null
                    && !RegionalPlayerEntityTicksV2
                    .schedulerLifecycleTransitionAllowed(authoritativeServer)) {
                if (interrupted) Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "RegionScheduler.stop lost its between-phase physical-main authority");
            }
            if (lifecycleState == LifecycleState.STOPPED
                    && !active && pool == null && transitionPool == null
                    && server == null) {
                if (interrupted) Thread.currentThread().interrupt();
                Throwable failure = orphanFatalFailure;
                if (failure != null) RegionScheduler.<RuntimeException>rethrow(failure);
                return;
            }
            if (lifecycleState != LifecycleState.RUNNING
                    && lifecycleState != LifecycleState.FAILED) {
                throw new IllegalStateException(
                        "RegionScheduler cannot stop from lifecycle state " + lifecycleState);
            }
            RegionWorkerPool drainingPool = failureGateLocked();
            if (active && drainingPool == null) {
                throw new IllegalStateException("active region scheduler had no worker pool");
            }
            // Publish scheduler retirement before stopping the autonomous pacer.
            // Pool root admission deliberately remains open until that exact daemon
            // has joined: it may still be terminalizing a poll-only AttemptReceipt,
            // and that bookkeeping is an accepted child of the unit already owned by
            // this generation rather than a fresh simulation root.
            lifecycleState = LifecycleState.STOPPING;
            lifecycleOwner = Thread.currentThread();
            accepting = false;
        }
        Throwable failure = null;
        try {
            stopOutsideLifecycleLock();
        } catch (Throwable thrown) {
            failure = thrown;
        } finally {
            synchronized (LIFECYCLE_LOCK) {
                Throwable terminalFailure = orphanFatalFailure;
                if (terminalFailure != null) {
                    failure = failure == null
                            ? terminalFailure : mergeFailure(terminalFailure, failure);
                }
                boolean stopped = !active && pool == null && transitionPool == null
                        && server == null
                        && orphanFatalFailure == null;
                lifecycleState = stopped ? LifecycleState.STOPPED : LifecycleState.FAILED;
                lifecycleOwner = null;
                LIFECYCLE_LOCK.notifyAll();
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (failure != null) RegionScheduler.<RuntimeException>rethrow(failure);
    }

    /** Long quiescence/drain path.  Never call while holding LIFECYCLE_LOCK. */
    private static void stopOutsideLifecycleLock() {
        RegionWorkerPool stoppingPool = pool != null ? pool : transitionPool;
        Throwable stopFailure = stoppingPool == null
                ? orphanFatalFailure : stoppingPool.fatalFailureOrNull();
        boolean failedGeneration = stopFailure != null;

        // STOPPING + scheduler accepting=false is already visible, so no pacer
        // ticket or completing unit can publish a successor. Retire and join the
        // exact daemon before closing generic pool roots: a healthy receipt which
        // became terminal at this boundary may otherwise try to return its local
        // cadence authority through runSuccessBodyIfHealthy after beginDraining,
        // where the pacer is intentionally neither Main nor a pool worker.
        try {
            TemporalControl.drainPacer();
        } catch (Throwable pacerStopFailure) {
            stopFailure = mergeFailure(stopFailure, pacerStopFailure);
            if (stoppingPool != null) {
                stoppingPool.publishFatalFailure(pacerStopFailure);
                stopFailure = mergeFailure(
                        stoppingPool.fatalFailureOrNull(), stopFailure);
            }
            failedGeneration = true;
        }

        // The pacer join above is bounded by that daemon alone; it does not wait
        // for pool work. Any unrelated root which entered in this short interval
        // is already tracked by the pool and is covered by the fixed-point drain.
        // Close root admission even when pacer retirement selected W so failed-
        // generation cancellation cannot race a late producer.
        if (stoppingPool != null && !stoppingPool.isTerminated()) {
            try {
                stoppingPool.beginDraining(Thread.currentThread());
            } catch (Throwable drainBoundaryFailure) {
                stopFailure = mergeFailure(stopFailure, drainBoundaryFailure);
                stoppingPool.publishFatalFailure(drainBoundaryFailure);
                stopFailure = mergeFailure(
                        stoppingPool.fatalFailureOrNull(), stopFailure);
                failedGeneration = true;
            }
        }

        java.util.List<RegionizedServer> stopLevels;
        try {
            stopLevels = new java.util.ArrayList<>(PER_LEVEL.values());
        } catch (Throwable snapshotFailure) {
            stopFailure = mergeFailure(stopFailure, snapshotFailure);
            if (stoppingPool != null) {
                stoppingPool.publishFatalFailure(snapshotFailure);
                stopFailure = mergeFailure(stoppingPool.fatalFailureOrNull(), stopFailure);
                failedGeneration = true;
            }
            stopLevels = java.util.List.of();
        }
        if (!failedGeneration) {
            try {
                RegionalPlayerEntityTicksV2.setEnabled(server, false);
            } catch (Throwable transitionFailure) {
                stopFailure = transitionFailure;
                if (stoppingPool != null) {
                    stoppingPool.publishFatalFailure(transitionFailure);
                    stopFailure = mergeFailure(stoppingPool.fatalFailureOrNull(), stopFailure);
                    failedGeneration = true;
                }
            }
        }
        // A region queue owned by an async island may deliberately wait for that
        // island's next logical unit. Re-arming such a queue during generic pool
        // drain either spins forever at ColorGate or executes a future tick early.
        // First use the normal ASYNC->SYNC quiesce/carry/collapse protocol while
        // the pool is still available; only wall-domain obligations enter stop().
        if (!failedGeneration) {
            for (RegionizedServer rs : stopLevels) {
                if (stoppingPool != null && stoppingPool.hasFatalFailure()) {
                    stopFailure = mergeFailure(
                            stoppingPool.fatalFailureOrNull(), stopFailure);
                    failedGeneration = true;
                    break;
                }
                try {
                    rs.prepareForSchedulerStop();
                } catch (Throwable collapseFailure) {
                    stopFailure = mergeFailure(stopFailure, collapseFailure);
                    if (stoppingPool != null) {
                        stoppingPool.publishFatalFailure(collapseFailure);
                        stopFailure = mergeFailure(
                                stoppingPool.fatalFailureOrNull(), stopFailure);
                        failedGeneration = true;
                    }
                    break;
                }
            }
        }
        final java.util.List<RegionizedServer> drainLevels = stopLevels;
        if (stoppingPool != null && !stoppingPool.isTerminated()) {
            try {
                Runnable drainProgress = () -> {
                    MinecraftServer physicalOwner = server;
                    if (!RegionalPlayerEntityTicksV2
                            .isPhysicalServerThread(physicalOwner)) {
                        throw new IllegalStateException(
                                "region scheduler drain lost the physical server thread");
                    }
                    // Ticket/raid/portal writes are admitted pool roots, but only the
                    // real server thread may enter them. Workers deliberately cannot
                    // consume this lane, so Main services it on every fixed-point pass.
                    stoppingPool.drainMainSafeAll(true);
                    drainTrackedMainControlTasks(stoppingPool);
                    for (RegionizedServer rs : drainLevels) {
                        // An accepted worker can enqueue more than one cold-chunk bridge
                        // before its active-step publication reaches the pool cut. Drain the
                        // exact processor to empty; a one-item poll could leave callback #2
                        // until after running=false, where its causal child would be refused.
                        while (callSuccessBooleanIfHealthy(() -> rs.level
                                .getChunkSource().mainThreadProcessor.pollTask())) {
                            // fixed point for this level's main-thread bridge
                        }
                    }
                    // Chunk processors above may be required to let a step
                    // reach its finally chain.  MAIN_SYNC bodies below are
                    // new simulation and are forbidden once any such task has
                    // published the generation fatal.
                    throwWorkerFailureIfAny();
                    while (drainMainSyncTicks()) {
                        // One root can publish another main-owned root.
                    }
                    healColorGate();
                    rearmPendingRegionWork(stoppingPool, drainLevels);
                    IslandCommands.drainRoutedBatch();
                    // This must remain the last progress stage. Chunk callbacks,
                    // MAIN_SYNC, heal, and re-arm can all expose more advancement
                    // evaluation work. Tracked producers advance the pool's shutdown
                    // activity epoch at terminal, so a completion after this drain
                    // forces another full progress pass before generation close.
                    AdvancementBuffer.drainShutdownPassIfPending();
                };
                stoppingPool.stop(drainProgress);
            } catch (Throwable workerStopFailure) {
                boolean workerFailedGeneration = stoppingPool.hasFatalFailure();
                failedGeneration |= workerFailedGeneration;
                stopFailure = workerFailedGeneration
                        ? mergeFailure(workerStopFailure, stopFailure)
                        : mergeFailure(stopFailure, workerStopFailure);
            }
            if (!stoppingPool.isTerminated()) {
                // Pre-quiescence failure keeps the live runtime published for diagnosis/retry.
                rethrow(stopFailure != null ? stopFailure : new IllegalStateException(
                        "region worker pool stop returned before quiescence"));
            }
        }
        if (stoppingPool != null && stoppingPool.hasFatalFailure()) {
            failedGeneration = true;
            stopFailure = mergeFailure(stoppingPool.fatalFailureOrNull(), stopFailure);
        }

        // Thread.join supplied the publication edge. Only now make the runtime dark and
        // perform final-clock/portal/arrival persistence work. Any failure retains every
        // registry/reference and leaves lifecycle FAILED; a later explicit stop can retry.
        // A failed simulation generation is different: its queued obligations were
        // explicitly terminalized, so success-only persistence/drain stages are forbidden
        // and all runtime references are detached before the exact primary is rethrown.
        active = false;
        if (!MAIN_SYNC.isEmpty()) {
            stopFailure = mergeFailure(stopFailure, new IllegalStateException(
                    "MAIN_SYNC was non-empty after the worker-pool fixed point"));
        }
        for (RegionizedServer rs : stopLevels) {
            try {
                for (Region region : rs.snapshot()) {
                    if (!region.queue().isEmpty() || !region.helpQueue.isEmpty()) {
                        stopFailure = mergeFailure(stopFailure, new IllegalStateException(
                                "region work survived the worker-pool fixed point: "
                                        + rs.level.dimension().identifier() + "/"
                                        + Long.toHexString(region.gridKey)));
                    }
                }
            } catch (Throwable inspectionFailure) {
                stopFailure = mergeFailure(stopFailure, inspectionFailure);
            }
        }
        for (RegionizedServer rs : stopLevels) {
            try {
                ((com.axalotl.fabrolia.common.parallelised.utils.TickingChunksAccess)
                        rs.entityManagerHandle()).fabrolia$finishStatusTransitions(!failedGeneration);
            } catch (Throwable statusFailure) {
                stopFailure = mergeFailure(publishFatalAndSelectWinner(statusFailure), stopFailure);
                failedGeneration = true;
            }
        }
        if (!failedGeneration) {
            stopFailure = finishQuiescedRuntime(stopLevels, stopFailure);
            if (stopFailure != null) rethrow(stopFailure);
        }

        Throwable clearFailure = failedGeneration ? stopFailure : null;
        for (RegionizedServer rs : stopLevels) {
            try {
                rs.regionizer().clear();
            } catch (Throwable cleanup) {
                clearFailure = mergeFailure(clearFailure, cleanup);
            }
        }
        if (!failedGeneration && clearFailure != null) rethrow(clearFailure);
        try {
            PER_LEVEL.clear();
        } catch (Throwable cleanup) {
            clearFailure = mergeFailure(clearFailure, cleanup);
        }
        try {
            LOGGER.info("RegionScheduler: stopped");
        } catch (Throwable diagnosticFailure) {
            clearFailure = mergeFailure(clearFailure, diagnosticFailure);
        }
        synchronized (LIFECYCLE_LOCK) {
            Throwable retiredWinner = stoppingPool == null
                    ? null : stoppingPool.fatalFailureOrNull();
            if (retiredWinner != null) {
                clearFailure = mergeFailure(retiredWinner, clearFailure);
            }
            if (clearFailure != null) {
                Throwable orphan = orphanFatalFailure;
                if (orphan == null) {
                    orphanFatalFailure = clearFailure;
                } else {
                    clearFailure = mergeFailure(orphan, clearFailure);
                }
            }
            if (pool == stoppingPool) pool = null;
            if (transitionPool == stoppingPool) transitionPool = null;
            grid = null;
            server = null;
            mainCurrentLevel = null;
            asyncLive = false;
        }
        if (clearFailure != null) rethrow(clearFailure);
    }

    private static Throwable finishQuiescedRuntime(
            java.util.List<RegionizedServer> levels, Throwable failure) {
        try {
            TickDomains.retainFinalClocks(levels);
        } catch (Throwable cleanup) {
            failure = mergeFailure(failure, cleanup);
        }
        try {
            TickDomains.collapseAllToWall(levels);
            RedstoneTorchHistory.collapseAllToWall(levels);
        } catch (Throwable cleanup) {
            failure = mergeFailure(failure, cleanup);
        }
        try {
            NetherPortalTransfers.flushQuiescent();
        } catch (Throwable cleanup) {
            failure = mergeFailure(failure, cleanup);
        }
        try {
            CrossDimArrivals.flushAll(levels);
        } catch (Throwable cleanup) {
            failure = mergeFailure(failure, cleanup);
        }
        try {
            CrossDimLinks.reset();
        } catch (Throwable cleanup) {
            failure = mergeFailure(failure, cleanup);
        }
        return failure;
    }

    private static Throwable mergeFailure(Throwable primary, Throwable secondary) {
        if (primary == null) return secondary;
        if (primary != secondary) {
            try {
                if (primary.getSuppressed().length >= 8) return primary;
                primary.addSuppressed(secondary);
            } catch (Throwable ignored) {
                // Cleanup diagnostics cannot replace the exact primary failure.
            }
        }
        return primary;
    }

    /**
     * Failed-generation queue cut, called only after the pool has closed admission and
     * every worker step/root publisher is quiescent. Entries are detached, never run.
     * Explicit cancellation hooks terminalize nested fan-ins/futures and every queued
     * MAIN_SYNC permit; cleanup failures remain bounded diagnostics on the exact primary.
     * Only the registered generation owns these global lanes. Its lifecycle cannot
     * detach or replace it until pool stop returns; never hold LIFECYCLE_LOCK across
     * cancellation callbacks, which may complete outstanding pool obligations.
     */
    static Throwable cancelFailedGenerationQueues(
            RegionWorkerPool expectedPool, Throwable primary) {
        synchronized (LIFECYCLE_LOCK) {
            if (expectedPool == null
                    || (pool != expectedPool && transitionPool != expectedPool)) return primary;
        }
        Throwable failure = primary;
        try {
            failure = cancelTrackedMainControlTasks(expectedPool, failure);
        } catch (Throwable cleanup) {
            failure = expectedPool.mergeCancellationFailure(failure, cleanup);
        }
        try {
            failure = NetherPortalTransfers.cancelFailedGeneration(failure);
        } catch (Throwable cleanup) {
            failure = expectedPool.mergeCancellationFailure(failure, cleanup);
        }
        try {
            failure = IslandCommands.cancelFailedGeneration(failure);
        } catch (Throwable cleanup) {
            failure = expectedPool.mergeCancellationFailure(failure, cleanup);
        }
        MainSyncTask mainTask;
        try {
            while ((mainTask = MAIN_SYNC.poll()) != null) {
                failure = expectedPool.cancelQueuedPermit(mainTask.permit(), failure);
            }
        } catch (Throwable cleanup) {
            failure = expectedPool.mergeCancellationFailure(failure, cleanup);
        }
        java.util.ArrayList<RegionizedServer> levels;
        try {
            levels = new java.util.ArrayList<>(PER_LEVEL.values());
        } catch (Throwable cleanup) {
            return expectedPool.mergeCancellationFailure(failure, cleanup);
        }
        for (int li = 0, ln = levels.size(); li < ln; li++) {
            RegionizedServer rs = levels.get(li);
            java.util.List<Region> regions;
            try {
                regions = rs.snapshot();
            } catch (Throwable cleanup) {
                failure = expectedPool.mergeCancellationFailure(failure, cleanup);
                continue;
            }
            for (int ri = 0, rn = regions.size(); ri < rn; ri++) {
                Region region = regions.get(ri);
                try {
                    failure = cancelFailedGenerationRegionQueues(
                            expectedPool, region, failure);
                } catch (Throwable cleanup) {
                    failure = expectedPool.mergeCancellationFailure(failure, cleanup);
                }
            }
        }
        return failure;
    }

    static boolean failedGenerationQueuesEmpty(RegionWorkerPool expectedPool) {
        synchronized (LIFECYCLE_LOCK) {
            if (expectedPool == null
                    || (pool != expectedPool && transitionPool != expectedPool)) return true;
        }
        try {
            if (pendingMainControl(expectedPool) != null) return false;
            if (!IslandCommands.failedGenerationQueuesEmpty()) return false;
            if (!MAIN_SYNC.isEmpty()) return false;
            if (NetherPortalTransfers.hasPending()) return false;
            for (RegionizedServer rs : PER_LEVEL.values()) {
                for (Region region : rs.snapshot()) {
                    if (!failedGenerationRegionQueuesEmpty(region)) return false;
                }
            }
            return true;
        } catch (Throwable cleanup) {
            // A failed inspection cannot replace the generation primary or authorize
            // teardown over an unknown queue. Retain it as bounded diagnostics and let
            // the stop thread retry the cancellation/inspection fixed point.
            expectedPool.recordCancellationFailure(cleanup);
            return false;
        }
    }

    /**
     * Detach every body obligation owned by one region generation without ever
     * entering its simulation body.  The ordinary, helper and post-rebase lanes
     * share admissionLock for this terminal sweep so a concurrent rebase flush
     * cannot move the final accepted ExternalTask behind the emptiness check.
     */
    static Throwable cancelFailedGenerationRegionQueues(
            RegionWorkerPool expectedPool, Region region, Throwable primary) {
        Throwable failure = primary;
        for (;;) {
            Runnable queued;
            synchronized (region.admissionLock) {
                queued = region.queue().poll();
                if (queued == null) queued = region.helpQueue.poll();
                if (queued == null) {
                    queued = region.pollDomainRebaseDeferredTaskForCancellation();
                }
                if (queued == null) {
                    region.clearDomainRebaseDeferredReadyForCancellation();
                    return failure;
                }
            }
            failure = expectedPool.cancelQueuedEntry(queued, failure);
        }
    }

    /** Fixed-point view paired with cancelFailedGenerationRegionQueues. */
    static boolean failedGenerationRegionQueuesEmpty(Region region) {
        synchronized (region.admissionLock) {
            return region.queue().isEmpty()
                    && region.helpQueue.isEmpty()
                    && region.domainRebaseDeferredAdmissionsEmpty();
        }
    }

    private static void rearmPendingRegionWork(RegionWorkerPool drainingPool,
                                               java.util.List<RegionizedServer> levels) {
        boolean armed = false;
        for (RegionizedServer rs : levels) {
            for (Region region : rs.snapshot()) {
                if (region.currentWorker() == null
                        && (!region.queue().isEmpty() || !region.helpQueue.isEmpty())) {
                    if (!drainingPool.rerouteClosedRegionForShutdown(region)) {
                        armed |= drainingPool.enqueueReady(region);
                    }
                }
            }
        }
        if (armed) drainingPool.wakeAll();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T) failure;
    }

    public static RegionizedServer forLevel(ServerLevel level) {
        if (!active) return null;
        RegionizedServer existing = PER_LEVEL.get(level);
        if (existing != null || !accepting) return existing;
        // Creation is cold (once per dimension), so linearize it with the lifecycle cut.
        // A stop snapshot can no longer miss a computeIfAbsent that observed stale true.
        synchronized (LIFECYCLE_LOCK) {
            existing = PER_LEVEL.get(level);
            if (existing != null || !active || !accepting) return existing;
            RegionWorkerPool current = pool;
            RegionGrid currentGrid = grid;
            if (current == null) {
                Throwable failure = publishFatalAndSelectWinner(
                        new IllegalStateException(
                                "active RegionScheduler has no pool for level creation"));
                RegionScheduler.<RuntimeException>rethrow(failure);
                return null;
            }
            if (lifecycleState != LifecycleState.RUNNING || currentGrid == null) {
                Throwable failure = current.publishAndSelectFatal(
                        new IllegalStateException(
                                "active RegionScheduler has no running generation for level creation"));
                RegionScheduler.<RuntimeException>rethrow(failure);
                return null;
            }

            final RegionizedServer[] prepared;
            try {
                prepared = new RegionizedServer[1];
            } catch (Throwable preparationFailure) {
                Throwable failure = current.publishAndSelectFatal(preparationFailure);
                RegionScheduler.<RuntimeException>rethrow(failure);
                return null;
            }
            try {
                // Construction reads fallible ServerLevel state. It is therefore an
                // admitted body of this exact generation, not cold work which may run
                // after a peer W or escape without selecting its Throwable globally.
                current.runSuccessBodyIfHealthy(
                        () -> prepared[0] = new RegionizedServer(level, currentGrid));
            } catch (Throwable constructionFailure) {
                Throwable failure = current.publishAndSelectFatal(constructionFailure);
                RegionScheduler.<RuntimeException>rethrow(failure);
                return null;
            }
            RegionizedServer created = prepared[0];
            try {
                // LIFECYCLE_LOCK fixes the owning generation; the pool cut fixes
                // map visibility before W. If ConcurrentHashMap publication itself
                // throws, that exact Throwable becomes W and the partial key is
                // removed by the failure envelope below.
                current.commitSuccessIfHealthy(() -> PER_LEVEL.put(level, created));
                return created;
            } catch (Throwable publicationFailure) {
                Throwable failure = current.publishAndSelectFatal(publicationFailure);
                try {
                    PER_LEVEL.remove(level, created);
                } catch (Throwable cleanupFailure) {
                    failure = current.publishAndSelectFatal(
                            mergeFailure(failure, cleanupFailure));
                }
                RegionScheduler.<RuntimeException>rethrow(failure);
                return null;
            }
        }
    }

    /**
     * Resolve a manager against published runtime identity, including the
     * stop/OFF interval after async intent is cleared but before entered
     * claims have quiesced. Absence from allAsync is not exclusion authority.
     */
    public static RegionizedServer forEntityManager(Object manager) {
        for (RegionizedServer runtime : PER_LEVEL.values()) {
            if (runtime.entityManagerHandle() == manager) return runtime;
        }
        return null;
    }

    static Collection<RegionizedServer> allLevels() {
        return active ? PER_LEVEL.values() : Collections.emptyList();
    }

    /** Published identities remain authoritative while stop retires entered work. */
    static Collection<RegionizedServer> publishedLevels() {
        return PER_LEVEL.values();
    }

    static RegionizedServer publishedForLevel(ServerLevel level) {
        return PER_LEVEL.get(level);
    }

    /**
     * Region-owned tick entry point. Each non-empty region of {@code level} gets one task
     * that runs the full vanilla per-entity sequence over the region's OWN entity set —
     * main does no list walk, no classify, no grouping (see RegionizedServer.tickOwned).
     *
     * @return future the caller MUST barrier on with
     *         {@link ParallelProcessor#pumpUntilDone}.
     */
    public static CompletableFuture<Void> dispatchOwnedEntityTicks(ServerLevel level) {
        if (!active) return CompletableFuture.completedFuture(null);
        return forLevel(level).dispatchOwnedEntityTicks();
    }

    /** Queue-only barrier used by exact-ordinal player mode. */
    public static CompletableFuture<Void> dispatchPreEntityWork(ServerLevel level) {
        if (!active) return CompletableFuture.completedFuture(null);
        RegionizedServer owner = forLevel(level);
        if (owner == null) {
            throw new IllegalStateException(
                    "exact player pre-entity dispatch has no active level owner");
        }
        return owner.dispatchPreEntityWork();
    }

    /**
     * Stop every region worker at one lifecycle-lock edge before handing a
     * canonical EntityTickList slot to the logical-server actor. Publications
     * remain queued until {@link #resumeExactPlayerTurn()}.
     */
    public static void pauseExactPlayerTurn() {
        pauseExactPlayerTurn(false);
    }

    /** Retain next-unit FIFO; false defers preparation until routed Main work can finish. */
    static boolean pauseMainPreparationTurn() {
        return pauseExactPlayerTurn(true);
    }

    private static boolean pauseExactPlayerTurn(boolean retainQueuedRegions) {
        RegionWorkerPool current = pool;
        if (!active || current == null) {
            throw new IllegalStateException("exact player turn requires a live region pool");
        }
        MinecraftServer owner = server;
        if (owner == null
                || !RegionalPlayerEntityTicksV2.isPhysicalServerThread(owner)) {
            throw new IllegalStateException(
                    "exact player turn pause lost the physical server thread");
        }
        Runnable progress = () -> {
            // Workers are now fenced or completing their last admitted step. Drain
            // both worker-safe and physical-Main-only roots before validating the
            // exact-turn fixed point; no worker is authorized to consume ticket roots.
            current.drainMainSafeAll(true);
            for (ServerLevel level : owner.getAllLevels()) {
                while (callSuccessBooleanIfHealthy(() -> level.getChunkSource()
                        .mainThreadProcessor.pollTask())) {
                    // Drain causal chunk-main work to a fixed point. The pool
                    // revalidates its queues and permits after the final fenced pass.
                }
            }
            throwWorkerFailureIfAny();
            // A causal worker may have published a main-owned entity obligation
            // immediately before the exact-turn request. External permits keep the
            // fixed point open until physical main finishes those accepted entries.
            drainMainSyncTicks();
            if (!retainQueuedRegions) {
                IslandCommands.drainRoutedBatch();
                // Mandatory V2 turns run outside autonomous mode and enter before
                // the exact actor acquires its level/connection slot. Finish each
                // accepted native control through the real FIFO while REQUESTED,
                // never by pumping predecessors inside the final PAUSED turn.
                if (!current.ownsPausedExactTurn(Thread.currentThread())) {
                    drainTrackedMainControlTasks(current);
                }
            }
        };
        if (retainQueuedRegions) return current.pauseMainPreparationAndAwait(progress);
        current.pauseExactTurnAndAwait(progress);
        return true;
    }

    public static void resumeExactPlayerTurn() {
        RegionWorkerPool current = pool;
        if (current == null) {
            throw new IllegalStateException("exact player turn lost its region pool");
        }
        current.resumeExactTurn();
    }

    /** The pool fence, not a mirrored phase flag, is the physical-owner authority. */
    static boolean ownsExactExecutionTurn() {
        RegionWorkerPool current = pool;
        RegionWorkerPool failureGate = current != null ? current : transitionPool;
        if (failureGate != null) {
            // Fast-fail the authorization probe.  This is not by itself a body
            // admission: every true branch must still use runSuccessBodyIfHealthy
            // so a W racing the probe cannot be followed by an inline root.
            failureGate.throwIfFailed();
        } else {
            Throwable failure = orphanFatalFailure;
            if (failure != null) RegionScheduler.<RuntimeException>rethrow(failure);
        }
        return RegionalPlayerEntityTicksV2.inExactPlayerActor()
                || current != null && current.ownsPausedExactTurn(Thread.currentThread());
    }

    static boolean isExactPlayerTurnPausedBy(Thread thread) {
        RegionWorkerPool current = pool;
        return current != null && current.ownsPausedExactTurn(thread);
    }

    /**
     * Affirmative spatial execution authority for the public island API. This
     * observes existing claims/turn fences; it neither acquires one nor admits
     * a success body past the fatal cut.
     */
    public static boolean ownsCurrentContext(ServerLevel level, BlockPos pos) {
        if (NetherPortalTransfers.owns(level, pos)) return true;
        Thread caller = Thread.currentThread();
        MinecraftServer physicalServer = level.getServer();
        if (physicalServer != null && physicalServer == server && ownsExclusiveGameplay()) return true;
        boolean physicalMain = RegionalPlayerEntityTicksV2
                .isPhysicalServerThread(physicalServer);
        RegionizedServer runtime = PER_LEVEL.get(level);
        if (ownsRuntimeGeneration(runtime)) {
            if (runtime.currentThreadHoldsAllClaims()) return true;
            Region target = runtime.regionizer().peekRegionForChunk(
                    ChunkPos.pack(pos));
            if (target != null && runtime.ownsRegion(target)) {
                Thread targetWorker = target.currentWorker();
                if (targetWorker == caller) return true;
                EpochDomain domain = target.domain;
                IslandRegistry.PIsland owner = domain != null ? domain.owner : null;
                if (owner != null && owner.domain == domain
                        && owner.domainLevel() == level && !owner.retired) {
                    if (physicalMain && IslandCommands.mainHolds(owner)) return true;
                    if (caller instanceof RegionWorkerThread worker
                            && worker.currentDomain == domain
                            && owner.unitClaim.get() && owner.claimHolder == caller
                            && targetWorker == null
                            && (worker.currentRegion == null
                                || runtime.ownsRegion(worker.currentRegion)
                                && worker.currentRegion.domain == domain
                                && worker.currentRegion.currentWorker() == caller)) {
                        // Serial member work retains its coordinator's whole-unit
                        // footprint, but a conflicting claim or foreign active
                        // region cannot borrow an inherited domain stamp.
                        return true;
                    }
                }
            }
        }
        // Logical server-thread identity alone does not fence region workers.
        if (physicalServer != null && physicalServer == server
                && (physicalMain || RegionalPlayerEntityTicksV2
                        .logicalServerThread(physicalServer) == caller)) {
            return ownsExactExecutionTurn();
        }
        // Disabled guards or apparent idleness are not quiescence receipts.
        return false;
    }

    /**
     * Explicit causal-context propagation for a region worker which hands work to
     * another executor before joining it.  Callers submit the returned wrapper to
     * that executor; late execution after the parent step closes is safely treated
     * as a post-turn root.
     * This retains causal lifetime only; a foreign executor does not inherit
     * gameplay ownership and must route owner mutations through executeAt.
     */
    public static Runnable wrapCausalRegionHandoff(Runnable body) {
        RegionWorkerPool current = pool;
        if (current == null) {
            throw new IllegalStateException("causal handoff requires a live region pool");
        }
        return current.wrapCausalHandoff(body);
    }

    /**
     * Publish a success-only task to a foreign executor with a generation permit
     * reserved before executor visibility. Region shutdown therefore waits for the
     * exact accepted body; when no region generation exists, the task retains its
     * ordinary flat-runtime ownership.
     */
    public static void executeTrackedExternal(Executor executor, Runnable body) {
        executeTrackedExternal(executor, body, null);
    }

    /**
     * Variant which publishes a retained-result marker immediately before executor
     * visibility under the same generation admission as {@code body}.
     */
    public static void executeTrackedExternal(Executor executor, Runnable body,
                                              Runnable beforeExecutorVisibility) {
        if (executor == null) throw new NullPointerException("executor");
        if (body == null) throw new NullPointerException("body");
        RegionWorkerPool current;
        synchronized (LIFECYCLE_LOCK) {
            current = failureGateLocked();
            Throwable failure = orphanFatalFailure;
            if (current == null && failure != null) {
                RegionScheduler.<RuntimeException>rethrow(failure);
            }
        }
        if (current != null) {
            current.executeTrackedExternal(executor, body, beforeExecutorVisibility);
        } else {
            if (beforeExecutorVisibility != null) beforeExecutorVisibility.run();
            executor.execute(body);
        }
    }

    // ---------------------------------------------------------------------
    // Region-owned entity index maintenance (EntityTickListMixin forwards here)
    // ---------------------------------------------------------------------

    public static void onEntityAdd(Entity e) {
        if (!active) return;
        if (!(e.level() instanceof ServerLevel sl)) return;
        RegionizedServer rs = forLevel(sl);
        if (rs != null) rs.indexAdd(e);
    }

    public static void onEntityRemove(Entity e) {
        if (!active) return;
        if (!(e.level() instanceof ServerLevel sl)) return;
        RegionizedServer rs = PER_LEVEL.get(sl); // never create a level container on remove
        if (rs != null) rs.indexRemove(e);
    }

    /** Exact-ordinal mode's post-list auxiliary-index reconciliation. */
    public static void reconcileCanonicalEntityIndex(ServerLevel level) {
        if (!active) return;
        RegionizedServer owner = PER_LEVEL.get(level);
        if (owner == null) {
            throw new IllegalStateException(
                    "canonical entity-index reconciliation lost its level owner");
        }
        owner.reconcileCanonicalEntityIndex();
    }

    /**
     * Per-region FULL-chunk population, driven by the loader's reliable chunk load/unload
     * event (Fabric {@code ServerChunkEvents}, NeoForge {@code ChunkEvent}). A LevelChunk
     * is exactly a FULL chunk, so its load/unload IS the FULL gain/loss — symmetric and
     * fired on every transition, unlike the c2me-bypassed vanilla updateChunkScheduling
     * seam (c2me's setTicketLevel throws; the decrement was silently lost, regions never
     * went empty, eviction reclaimed nothing — live 2026-06-27). Never creates a level
     * container (get, not computeIfAbsent).
     */
    public static void onChunkFullChange(ServerLevel level, long packedChunkPos, boolean nowFull,
                                         net.minecraft.world.level.chunk.LevelChunk chunk) {
        if (!active) return;
        RegionizedServer rs = PER_LEVEL.get(level);
        if (rs != null) rs.onChunkFullTransition(packedChunkPos, nowFull, chunk);
    }

    // ---------------------------------------------------------------------
    // Main-thread sync queue: entities a region task may not tick (modded API
    // contract, UUID blacklist, and every direct ServerPlayer entry).
    // Main drains this inside the barrier pump, overlapping the region work.
    // ---------------------------------------------------------------------

    private static final ConcurrentLinkedQueue<MainSyncTask> MAIN_SYNC =
            new ConcurrentLinkedQueue<>();

    private record MainSyncTask(Entity entity, RegionWorkerPool.ExternalPermit permit,
                                BooleanSupplier admission, Runnable completion,
                                boolean vanillaGuarded, boolean allIslandsFenced) implements Runnable {
        @Override
        public void run() {
            // runWithMainEntityClaim has established this entity's current level
            // and owner before entering this already-allocated queue body.
            if (!(entity.level() instanceof ServerLevel level)) {
                throw new IllegalStateException("MAIN_SYNC entity left its claimed server level");
            }
            if (((ParallelProcessor.TickGuard) entity).fabrolia$tickLease().isArrivalPending()) return;
            entity.checkDespawn();
            if (!entity.isRemoved()) {
                if (vanillaGuarded) ParallelProcessor.tickEntityVanillaGuarded(level, entity, false);
                else ParallelProcessor.tickEntity(level, entity, false);
            }
        }
    }

    static void queueMainSyncTick(Entity e) {
        queueMainSyncTick(e, () -> true, null, false, false);
    }

    /**
     * Publishes a Main-owned entity body with a completion edge which runs on
     * physical Main after the body but before its pool obligation is released.  A5 uses
     * this edge to keep island admission closed until an ordered fallback body has
     * actually finished; merely enqueueing the body is not a completed handoff.
     */
    static void queueMainSyncTick(Entity e, Runnable completion) {
        queueMainSyncTick(e, () -> true, completion, false, false);
    }

    /**
     * A cancelable Main body.  A closed admission is a successful no-op and still
     * runs completion; a failed body is fail-stop and must not publish a success
     * transition from that completion edge.
     */
    static void queueMainSyncTick(Entity e, BooleanSupplier admission,
                                  Runnable completion) {
        queueMainSyncTick(e, admission, completion, false, false);
    }

    /**
     * A5 transition body with the exact vanilla crash boundary.  Compatibility-lane
     * entities and A5 players both propagate their vanilla crash unchanged.  The
     * guarded variant additionally authenticates the exact A5 body; neither lane may
     * consume an exception and reopen ownership as if the entity tick completed.
     */
    static void queueMainSyncGuardedTick(Entity e, BooleanSupplier admission,
                                         Runnable completion) {
        queueMainSyncTick(e, admission, completion, true, false);
    }

    /** Ordered A5 fallback retaining both its exact body guard and global scope. */
    static void queueMainSyncFencedGuardedTick(Entity e, BooleanSupplier admission,
                                               Runnable completion) {
        queueMainSyncTick(e, admission, completion, true, true);
    }

    /**
     * A rare cross-owner entity body. Admission is evaluated only after physical
     * Main has quiesced every autonomous island, so it may read live ticket and
     * ownership state that the region task's coherent per-chunk memo predates.
     */
    static void queueMainSyncFencedTick(Entity e, BooleanSupplier admission,
                                        Runnable completion) {
        queueMainSyncTick(e, admission, completion, false, true);
    }

    private static void queueMainSyncTick(Entity e, BooleanSupplier admission,
                                          Runnable completion, boolean vanillaGuarded,
                                          boolean allIslandsFenced) {
        if (admission == null) throw new NullPointerException("admission");
        RegionWorkerPool owner = pool;
        if (owner == null) {
            throw new IllegalStateException("MAIN_SYNC publication without a live region pool");
        }
        if (!owner.publishExternalRoot(permit ->
                MAIN_SYNC.add(new MainSyncTask(e, permit, admission, completion,
                        vanillaGuarded, allIslandsFenced)))) {
            throw new IllegalStateException("MAIN_SYNC publication rejected by worker-pool lifecycle");
        }
    }

    /**
     * Runs one physical-Main entity operation while its exact current island is
     * quiescent. MAIN_SYNC entities still share world/interaction state with
     * autonomous neighbours; Main ownership alone is not mutual exclusion.
     */
    public static void runWithMainEntityClaim(Entity entity, Runnable body) {
        runWithMainEntityClaim(entity, body, null);
    }

    /**
     * A specific queued obligation may become terminal while Main waits for a
     * claim. Its owner may close that exact obligation before any world body;
     * ordinary callers retain the strict identity contract above.
     */
    public static void runWithMainEntityClaim(Entity entity, Runnable body,
                                              java.util.function.BooleanSupplier finishWithoutBody) {
        if (entity == null || body == null) throw new NullPointerException();
        if (!(entity.level() instanceof ServerLevel level)) {
            Throwable failure = null;
            try {
                runSuccessBodyIfHealthy(body);
            } catch (Throwable primary) {
                failure = publishFatalAndSelectWinner(primary);
            }
            if (failure != null) {
                RegionScheduler.<RuntimeException>rethrow(
                        publishFatalAndSelectWinner(failure));
            }
            return;
        }
        MinecraftServer minecraftServer = level.getServer();
        if (minecraftServer != null
                && !RegionalPlayerEntityTicksV2
                        .isPhysicalServerThread(minecraftServer)) {
            throw new IllegalStateException(
                    "MAIN_SYNC entity claim entered off physical Main");
        }
        RegionizedServer runtime = publishedForLevel(level);
        if (runtime == null || !IslandCommands.requiresMainFence(runtime)
                || runtime.currentThreadHoldsAllClaims()) {
            if (entity.level() != level) {
                if (finishWithoutBody != null && callSuccessBooleanIfHealthy(finishWithoutBody)) return;
                throw new IllegalStateException(
                        "MAIN_SYNC entity changed level before unclaimed body");
            }
            Throwable failure = null;
            try {
                runSuccessBodyIfHealthy(body);
            } catch (Throwable primary) {
                failure = publishFatalAndSelectWinner(primary);
            }
            if (failure != null) {
                RegionScheduler.<RuntimeException>rethrow(
                        publishFatalAndSelectWinner(failure));
            }
            return;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            if (finishWithoutBody != null && callSuccessBooleanIfHealthy(finishWithoutBody)) return;
            if (entity.level() != level || publishedForLevel(level) != runtime) {
                if (finishWithoutBody != null && callSuccessBooleanIfHealthy(finishWithoutBody)) return;
                throw new IllegalStateException(
                        "MAIN_SYNC entity changed level/runtime before claim");
            }
            IslandRegistry.PIsland owner = runtime.islandOwnerOfChunk(
                    entity.chunkPosition().pack());
            if (owner == null) {
                if (entity.level() != level || publishedForLevel(level) != runtime) {
                    if (finishWithoutBody != null && callSuccessBooleanIfHealthy(finishWithoutBody)) return;
                    throw new IllegalStateException(
                            "MAIN_SYNC entity changed level/runtime at owner gap");
                }
                Throwable failure = null;
                try {
                    runSuccessBodyIfHealthy(body);
                } catch (Throwable primary) {
                    failure = publishFatalAndSelectWinner(primary);
                }
                if (failure != null) {
                    RegionScheduler.<RuntimeException>rethrow(
                            publishFatalAndSelectWinner(failure));
                }
                return;
            }
            boolean release;
            try {
                release = IslandCommands.mainClaimScoped(owner);
            } catch (Throwable claimFailure) {
                RegionScheduler.<RuntimeException>rethrow(
                        publishFatalAndSelectWinner(claimFailure));
                return;
            }
            Throwable failure = null;
            boolean bodyCompleted = false;
            try {
                if (finishWithoutBody != null && callSuccessBooleanIfHealthy(finishWithoutBody)) {
                    bodyCompleted = true;
                } else if (entity.level() == level && publishedForLevel(level) == runtime
                        && runtime.islandOwnerOfChunk(entity.chunkPosition().pack()) == owner
                        && !owner.retired) {
                    // The permit admitted this Main operation, but another entered body can
                    // still win the shared fatal slot while Main waits for the exact claim.
                    // Give the actual vanilla body its own atomic admission after the
                    // wait/revalidation; a plain health read here has a W-vs-entry race.
                    runSuccessBodyIfHealthy(body);
                    bodyCompleted = true;
                }
            } catch (Throwable primary) {
                failure = publishFatalAndSelectWinner(primary);
            } finally {
                if (release) {
                    try {
                        IslandCommands.mainRelease(owner);
                    } catch (Throwable releaseFailure) {
                        failure = publishFatalAndSelectWinner(
                                mergeFailure(failure, releaseFailure));
                    }
                }
            }
            if (failure == null) {
                try {
                    // Release is mandatory cleanup, but a peer W selected
                    // during it still forbids a normal body return.  The empty
                    // fixed commit is the post-cleanup success linearization edge.
                    commitSuccessIfHealthy(() -> {});
                } catch (Throwable peerFailure) {
                    failure = publishFatalAndSelectWinner(peerFailure);
                }
            }
            if (failure != null) {
                RegionScheduler.<RuntimeException>rethrow(
                        publishFatalAndSelectWinner(failure));
            }
            if (bodyCompleted) return;
        }
        if (finishWithoutBody != null && callSuccessBooleanIfHealthy(finishWithoutBody)) return;
        throw new IllegalStateException(
                "MAIN_SYNC entity changed island during claim acquisition");
    }

    /**
     * Publishes a failed Main operation into the generation's first-wins fatal slot
     * and returns that exact authoritative object. A concurrent winner remains
     * primary; this operation (including cleanup diagnostics already attached to it)
     * is then context only.
     */
    public static Throwable publishFatalAndSelectWinner(Throwable candidate) {
        synchronized (LIFECYCLE_LOCK) {
            RegionWorkerPool observedPool = failureGateLocked();
            Throwable publicationFailure = null;
            try {
                publishFatalFailure(candidate);
            } catch (Throwable cleanup) {
                publicationFailure = cleanup;
            }

            return selectFatalWinner(observedPool, candidate, publicationFailure);
        }
    }

    /**
     * Select W only in the pool generation which owns {@code expected}.
     * A late callback from a retired runtime must never publish into the pool
     * currently stored in the scheduler's static slot.
     */
    static Throwable publishFatalAndSelectWinner(RegionizedServer expected,
                                                 Throwable candidate) {
        if (candidate == null) candidate = new NullPointerException("fatal failure");
        RegionWorkerPool expectedPool;
        synchronized (LIFECYCLE_LOCK) {
            expectedPool = expected != null
                    && PER_LEVEL.get(expected.level) == expected ? pool : null;
        }
        return expectedPool == null
                ? candidate : expectedPool.publishAndSelectFatal(candidate);
    }

    private static Throwable selectFatalWinner(RegionWorkerPool observedPool,
                                               Throwable candidate,
                                               Throwable publicationFailure) {
        Throwable winner = observedPool == null
                ? null : observedPool.fatalFailureOrNull();
        RegionWorkerPool currentPool = failureGateLocked();
        if (winner == null && currentPool != null) {
            winner = currentPool.fatalFailureOrNull();
        }
        if (winner == null) winner = orphanFatalFailure;
        if (winner == null) winner = candidate;
        if (winner != candidate) winner = mergeFailure(winner, candidate);
        if (publicationFailure != null) {
            winner = mergeFailure(winner, publicationFailure);
        }
        return winner;
    }

    private static void runMainSyncEntityTask(MainSyncTask queued) {
        Entity e = queued.entity();
        if (!queued.admission().getAsBoolean()
                || e.isRemoved() || !(e.level() instanceof ServerLevel)) {
            return;
        }
        runWithMainEntityClaim(e, queued);
    }

    /** Main thread only. @return true if at least one entity was ticked. */
    public static boolean drainMainSyncTicks() {
        MainSyncTask queued = MAIN_SYNC.poll();
        if (queued == null) return false;
        do {
            MainSyncTask task = queued;
            Throwable failure = null;
            boolean permitStarted = false;
            try {
                if (!queued.permit().beginMainSync()) {
                    throw new IllegalStateException("MAIN_SYNC entry executed more than once");
                }
                permitStarted = true;
                if (task.allIslandsFenced()) {
                    // Admission and the complete native producer need global Main
                    // authority even when no autonomous island requires a fence.
                    runGlobalMainBody(server, () -> runSuccessBodyIfHealthy(
                            () -> runMainSyncEntityTask(task)));
                } else {
                    runMainSyncEntityTask(task);
                }
            } catch (Throwable thrown) {
                failure = publishFatalAndSelectWinner(thrown);
            } finally {
                // This is a success continuation, not resource cleanup.  In
                // particular A5's callback reopens/schedules the listener lane;
                // running it after a partial entity mutation would consume the
                // failed turn as if vanilla had completed it.  The pool permit is
                // the actual resource and is released below for both outcomes.
                if (permitStarted) {
                    if (failure == null && queued.completion() != null) {
                        try {
                            // Completion reopens/schedules a successor lane. A worker
                            // fatal which won while the claimed body was running must
                            // stop it atomically at the last possible point before entry;
                            // a health read followed by Runnable.run has a W race.
                            runSuccessBodyIfHealthy(queued.completion());
                        } catch (Throwable completionFailure) {
                            failure = publishFatalAndSelectWinner(
                                    mergeFailure(failure, completionFailure));
                        }
                    }
                    failure = queued.permit().finish(failure);
                    if (failure != null) {
                        failure = publishFatalAndSelectWinner(failure);
                    }
                }
            }
            if (failure != null) {
                RegionScheduler.<RuntimeException>rethrow(
                        publishFatalAndSelectWinner(failure));
            }
        } while ((queued = MAIN_SYNC.poll()) != null);
        return true;
    }

    /** Generic submit at (level, chunk). Inline under authenticated existing ownership. */
    public static void executeAt(ServerLevel level, ChunkPos pos, Runnable task) {
        boolean exactRoot = ownsExactRootExecution(task);
        if (!active) {
            MinecraftServer current = server;
            if (current != null && !current.isSameThread()) {
                throw new IllegalStateException(
                        "region task submitted from a worker after scheduler admission closed");
            }
            runRootInlineIfHealthy(task);
            return;
        }
        RegionizedServer rs = forLevel(level);
        if (rs == null) {
            throw new IllegalStateException(
                    "region task targeted a level outside the active scheduler generation");
        }
        // An idle eviction can retire the Region after lookup but before the pool's
        // in-lock ownsRuntime check. A fresh replacement starts with idleStreak=0, so one
        // retry is sufficient and cannot duplicate: enqueueRegionTask returns true only
        // after the task has escaped into the old queue; partial publication throws.
        for (int attempt = 0; attempt < 2; attempt++) {
            Region r = rs.regionFor(pos);
            if (exactRoot) {
                runRegionRootInlineIfHealthy(r, task);
                return;
            }
            // An already-held region/unit/Main claim is also execution authority.
            // Enqueueing behind that same claim would deadlock callers awaiting
            // their synchronous owner callback. The claim remains held through
            // the ordinary generation/failure body admission and callback.
            if (ownsCurrentContext(level, pos.getWorldPosition())) {
                runRootInlineIfHealthy(task);
                return;
            }
            // The pool chooses region-claim versus claim-free main-safe routing
            // while holding this exact region's admissionLock.  A direct check
            // here would let a domain-rebase cut publish between the check and
            // the body receipt/publication.
            if (enqueueRegionTask(r, task)) {
                wakeWorkers();
                return;
            }
            if (!active || PER_LEVEL.get(level) != rs) break;
            if (r.state() == RegionState.ACTIVE && rs.ownsRegion(r)) break;
        }
        throw new IllegalStateException(
                "region task rejected at the scheduler drain/retirement boundary");
    }

    static boolean shouldRunMainSafe(RegionizedServer rs, Region r) {
        if (!FabroliaConfig.useRegionColorGate()) {
            return false;
        }
        // A2: evaluated in the REGION's timeline — an island region compared
        // against the global epoch would read as a permanent orphan and route
        // its queued work main-safe WHILE its unit phase runs (racing the
        // claim holder). "Done for its domain's current epoch" means no worker
        // will claim it again before the next unit dispatch, which cannot
        // start until the current drive completes — main-inline is safe.
        EpochDomain d = r.domain;
        // A2 ownership fix (2026-07-13 production log): an island-owned
        // region's queue must NEVER run on the claim-free main-safe lane while
        // async is live. The old "done for epoch + no unit in flight" reroute
        // was check-then-act: the island's next unit dispatches while the
        // rerouted task sits in the shared lane, and the task then executes
        // claim-free against the unit's phases — and on the EXECUTING thread's
        // timeline (a pumping coordinator of another island → detector
        // cross-domain trip on ordinary vanilla random ticks: SpreadingSnowy/
        // GrowingPlantHead/BuddingAmethyst storm at 05:01:08; main's stealOne →
        // the same race, silent). gate==1 arms are due at the island's next
        // unit: dispatchEntityPhase drains member queues under region claims,
        // in order, on the island's clock. Let them wait there.
        IslandRegistry.PIsland unitOwner = d.owner;
        if (unitOwner != null) {
            if (rs.asyncActive) {
                return false;
            }
            // Async collapsing/off with a unit still in flight: keep the old
            // conservative guard for the transition window.
            if (unitOwner.unitClaim.get()) {
                return false;
            }
        }
        long epoch = d.epoch;
        if (r.doneEpoch >= epoch) {
            return true;
        }
        // Dispatch has already stamped every region allowed to claim this epoch.
        // A region whose groupedEpoch is still old is an orphan: usually born by
        // indexMove or executeAt into empty territory after the snapshot. It may
        // be completed by healColorGate when empty, but queued work must not
        // claim it because lower-color orphans can appear after higher neighbors
        // have already passed eligible().
        return d.dispatchedEpoch == epoch && r.groupedEpoch != epoch;
    }

    public static void executeForEntity(Entity entity, Runnable task) {
        if (!active) { runRootInlineIfHealthy(task); return; }
        if (!(entity.level() instanceof ServerLevel sl)) {
            runRootInlineIfHealthy(task);
            return;
        }
        executeAt(sl, entity.chunkPosition(), task);
    }

    public static void executeGlobal(Runnable task) {
        runRootInlineIfHealthy(task);
    }

    public static int regionCountFor(ServerLevel level) {
        RegionizedServer rs = active ? PER_LEVEL.get(level) : null;
        return rs == null ? 0 : rs.regionCount();
    }

    public static void wakeWorkers() {
        if (pool != null) pool.wakeAll();
    }

    /** R5 targeted wake: unpark up to {@code k} parked workers (see RegionWorkerPool.wake).
     *  Use at high-frequency submit sites; the no-arg wakeAll stays for bulk fan-outs. */
    public static void wakeWorkers(int k) {
        RegionWorkerPool p = pool;
        if (p != null) p.wake(k);
    }

    /** Push a region onto the worker pool's ready-queue (O(1) claim path). */
    public static void enqueueReady(Region r) {
        RegionizedServer owner = PER_LEVEL.get(r.level);
        if (owner == null || !owner.ownsRegion(r)) {
            throw new IllegalStateException("stale region ready task crossed scheduler generation");
        }
        RegionWorkerPool p = pool;
        if (p == null || !p.enqueueReady(r)) {
            throw new IllegalStateException("region ready task rejected by worker-pool lifecycle");
        }
    }

    /**
     * Publish a ready entry carrying the exact current entity-dispatch receipt.
     * The receipt opens before CLQ visibility and follows the detached entry
     * through its complete workOnce outcome/contribution tail.
     */
    static boolean enqueueEntityReady(Region r) {
        RegionizedServer owner = PER_LEVEL.get(r.level);
        if (owner == null || !owner.ownsRegion(r)) {
            throw new IllegalStateException(
                    "stale entity-ready task crossed scheduler generation");
        }
        EpochDomain domain = r.domain;
        if (domain == null) {
            throw new IllegalStateException(
                    "entity-ready task has no epoch domain");
        }
        long epoch = domain.epoch;
        EpochDomain.EntityDispatchPublication publication =
                domain.entityDispatchPublication;
        RegionWorkerPool p = pool;
        if (p == null || !p.enqueueEntityReady(r, publication, epoch)) {
            throw new IllegalStateException(
                    "entity-ready task rejected by worker-pool lifecycle");
        }
        return true;
    }

    /** Atomically publish a region task and its ready-lane signal. */
    public static boolean enqueueRegionTask(Region region, Runnable task) {
        if (ownsExactRootExecution(task)) {
            runRegionRootInlineIfHealthy(region, task);
            return true;
        }
        RegionizedServer owner = PER_LEVEL.get(region.level);
        if (owner == null || !owner.ownsRegion(region)) return false;
        RegionWorkerPool p = pool;
        if (p == null || !p.enqueueRegionTask(region, task)) return false;
        p.wake(1);
        return true;
    }

    /** Final generation/identity check executed inside Region admission. */
    static boolean ownsRuntime(RegionWorkerPool expectedPool, Region region) {
        if (!active || pool != expectedPool || region == null
                || region.state() != RegionState.ACTIVE) return false;
        RegionizedServer owner = PER_LEVEL.get(region.level);
        return owner != null && owner.ownsRegion(region);
    }

    /** Atomically publish a dense-region helper and its ready-lane signal. */
    public static boolean enqueueHelpTask(Region region, Runnable task) {
        if (ownsExactRootExecution(task)) {
            runRegionRootInlineIfHealthy(region, task);
            return true;
        }
        RegionizedServer owner = PER_LEVEL.get(region.level);
        if (owner == null || !owner.ownsRegion(region)) return false;
        RegionWorkerPool p = pool;
        if (p == null || !p.enqueueHelpTask(region, task)) return false;
        p.wake(1);
        return true;
    }

    /**
     * Attach an explicit failure terminal to a root without putting recovery logic in its
     * simulation body. Failed-generation sweep calls only the cancellation hook; normal
     * execution calls only {@code body.run()}. The local state is defensive -- pool queue
     * wrappers provide the generation-wide linearization edge, while this one guarantees
     * that an enqueue rejection and a detached queue entry cannot notify twice.
     */
    public static Runnable cancellableRoot(Runnable body, Consumer<Throwable> cancellation) {
        if (body == null || cancellation == null) throw new NullPointerException();
        return new FailureCancellableRoot(body, cancellation);
    }

    private static final class FailureCancellableRoot
            implements Runnable, RegionTaskFanIn.CancellableQueueEntry {
        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int TERMINAL = 2;
        private static final int CANCELLED = 3;

        private final Runnable body;
        private final Consumer<Throwable> cancellation;
        private final AtomicInteger state = new AtomicInteger(QUEUED);

        private FailureCancellableRoot(Runnable body, Consumer<Throwable> cancellation) {
            this.body = body;
            this.cancellation = cancellation;
        }

        @Override
        public void run() {
            if (!state.compareAndSet(QUEUED, RUNNING)) {
                if (state.get() == CANCELLED) return;
                throw new IllegalStateException("cancellable root executed more than once");
            }
            Throwable failure = null;
            try {
                body.run();
                // A peer W selected while this body was running must become the
                // observed terminal result before this wrapper exposes TERMINAL.
                throwWorkerFailureIfAny();
            } catch (Throwable thrown) {
                // This wrapper owns a terminal marker which failed-generation sweep
                // can observe independently of the outer queue wrapper.  Select the
                // exact W before publishing that marker; otherwise TERMINAL could be
                // visible during the unwind while the generation still looked healthy.
                failure = publishFatalAndSelectWinner(thrown);
            } finally {
                state.set(TERMINAL);
            }
            if (failure != null) RegionScheduler.<RuntimeException>rethrow(failure);
        }

        @Override
        public void cancelQueuedBeforeStart() {
            cancelQueuedBeforeStartWithFailure(new CancellationException(
                    "queued region root cancelled before execution"));
        }

        @Override
        public void cancelQueuedBeforeStartWithFailure(Throwable primary) {
            if (!state.compareAndSet(QUEUED, CANCELLED)) return;
            cancellation.accept(primary != null ? primary : new CancellationException(
                    "queued region root cancelled before execution"));
        }
    }

    private static Throwable cancelRejectedRoot(RegionWorkerPool owner, Runnable task,
                                                Throwable primary) {
        if (owner != null) return owner.cancelQueuedEntry(task, primary);
        Throwable failure = primary;
        if (task instanceof RegionTaskFanIn.CancellableQueueEntry cancellable) {
            try {
                cancellable.cancelQueuedBeforeStartWithFailure(primary);
            } catch (Throwable cleanup) {
                failure = mergeFailure(failure, cleanup);
            }
        }
        return failure;
    }

    /** Inline roots still need the same atomic W-vs-entry cut as queued roots. */
    private static void runRootInlineIfHealthy(Runnable task) {
        try {
            runSuccessBodyIfHealthy(task);
        } catch (Throwable failure) {
            RegionScheduler.<RuntimeException>rethrow(
                    cancelRejectedRoot(pool, task, failure));
        }
    }

    /**
     * The exact player turn globally fences workers, but region topology still
     * needs an explicit lifetime witness: future topology callers must not be
     * able to mistake a claim-free inline body for a quiescent rebase point.
     * Open under region admission and close only after the inline root's W/body
     * terminal, exactly like an ExternalTask moved to mainSafeTasks.
     */
    private static void runRegionRootInlineIfHealthy(Region region, Runnable task) {
        boolean receiptOpen = false;
        boolean bodyAdmissionAttempted = false;
        Throwable failure = null;
        try {
            synchronized (region.admissionLock) {
                RegionizedServer owner = PER_LEVEL.get(region.level);
                if (!active || owner == null || !owner.ownsRegion(region)) {
                    throw new IllegalStateException(
                            "exact region root lost its scheduler runtime");
                }
                EpochDomain domain = region.domain;
                long epoch = domain == null ? Long.MIN_VALUE : domain.epoch;
                if (!region.tryOpenMainSafeBodyTransaction(domain, epoch)) {
                    throw new IllegalStateException(
                            "exact region root crossed a domain-rebase cut");
                }
                receiptOpen = true;
            }
            bodyAdmissionAttempted = true;
            runRootInlineIfHealthy(task);
        } catch (Throwable primary) {
            failure = publishFatalAndSelectWinner(primary);
        }
        if (receiptOpen) {
            try {
                region.finishClaimTransaction();
            } catch (Throwable cleanup) {
                failure = publishFatalAndSelectWinner(
                        mergeFailure(failure, cleanup));
            }
        }
        if (failure != null) {
            if (!bodyAdmissionAttempted) {
                failure = cancelRejectedRoot(pool, task, failure);
            }
            RegionScheduler.<RuntimeException>rethrow(
                    publishFatalAndSelectWinner(failure));
        }
    }

    private static boolean ownsExactRootExecution(Runnable task) {
        try {
            return ownsExactExecutionTurn();
        } catch (Throwable failure) {
            RegionScheduler.<RuntimeException>rethrow(
                    cancelRejectedRoot(pool, task, failure));
            return false;
        }
    }

    /** Submit a pool-level region-group work unit (dispatch coalescing). */
    public static void enqueueGlobal(Runnable task) {
        if (ownsExactRootExecution(task)) {
            runRootInlineIfHealthy(task);
            return;
        }
        RegionWorkerPool p = pool;
        if (p != null && p.enqueueGlobal(task)) return;
        Throwable failure = p == null ? workerFailureOrNull() : p.fatalFailureOrNull();
        if (failure == null) {
            failure = new IllegalStateException(
                    "global region task rejected by worker-pool lifecycle");
        }
        RegionScheduler.<RuntimeException>rethrow(cancelRejectedRoot(p, task, failure));
    }

    /** Submit a pool-level task that the barrier pump may also steal (no claim-spins). */
    public static void enqueueGlobalMainSafe(Runnable task) {
        if (ownsExactRootExecution(task)) {
            runRootInlineIfHealthy(task);
            return;
        }
        RegionWorkerPool p = pool;
        if (p != null && p.enqueueGlobalMainSafe(task)) return;
        Throwable fatal = p == null ? workerFailureOrNull() : p.fatalFailureOrNull();
        if (fatal != null) {
            RegionScheduler.<RuntimeException>rethrow(
                    cancelRejectedRoot(p, task, fatal));
        }
        MinecraftServer current = server;
        if (!active || p == null || p.isTerminated()) {
            if (current != null && !current.isSameThread()) {
                Throwable rejection = new IllegalStateException(
                        "main-safe task submitted off-main after scheduler termination");
                RegionScheduler.<RuntimeException>rethrow(
                        cancelRejectedRoot(p, task, rejection));
            }
            runRootInlineIfHealthy(task);
            return;
        }
        Throwable rejection = new IllegalStateException(
                "main-safe task rejected at worker-pool drain boundary");
        RegionScheduler.<RuntimeException>rethrow(
                cancelRejectedRoot(p, task, rejection));
    }

    /**
     * Submit a vanilla-single-writer body which may execute only on physical Main.
     *
     * <p>This queue deliberately does not use {@link #ownsExactRootExecution(Runnable)}:
     * only physical Main may drain it. The separately authenticated exact-player
     * actor ticket path executes synchronously under its exact-turn fence; it does
     * not enqueue here or authorize workers to consume this lane. Re-entry on the
     * real server thread runs inline through the same atomic success/failure gate
     * as every other root; all other live callers publish to the dedicated lane.
     */
    public static void enqueueGlobalMainOnly(Runnable task) {
        MinecraftServer current = server;
        if (RegionalPlayerEntityTicksV2.isPhysicalServerThread(current)) {
            runRootInlineIfHealthy(task);
            return;
        }
        RegionWorkerPool p = pool;
        if (p != null && p.enqueueGlobalMainOnly(task)) return;
        Throwable fatal = p == null ? workerFailureOrNull() : p.fatalFailureOrNull();
        if (fatal != null) {
            RegionScheduler.<RuntimeException>rethrow(
                    cancelRejectedRoot(p, task, fatal));
        }
        Throwable rejection = new IllegalStateException(
                !active || p == null || p.isTerminated()
                        ? "main-only task submitted off-main after scheduler termination"
                        : "main-only task rejected at worker-pool drain boundary");
        RegionScheduler.<RuntimeException>rethrow(
                cancelRejectedRoot(p, task, rejection));
    }

    /** A2: submit an island tick-unit coordinator (dedicated lane — only the
     *  top-level worker loop starts units; coordinators never nest). */
    public static boolean enqueueUnit(Runnable task) {
        if (ownsExactRootExecution(task)) {
            runRootInlineIfHealthy(task);
            return true;
        }
        RegionWorkerPool p = pool;
        if (p != null && p.enqueueUnit(task)) {
            p.wake(1);
            return true;
        }
        Throwable fatal = p == null ? workerFailureOrNull() : p.fatalFailureOrNull();
        if (fatal != null) {
            RegionScheduler.<RuntimeException>rethrow(
                    cancelRejectedRoot(p, task, fatal));
        }
        return false;
    }

    /**
     * Generation-bound island-unit publication.
     *
     * <p>A {@code false} result is possible only before the task becomes visible,
     * when {@code expected} has already left unit admission.  A rejection by the
     * still-current running pool is an invariant failure and therefore selects W;
     * it is never converted into a skipped tick or a later retry.
     */
    static boolean enqueueUnit(RegionizedServer expected, Runnable task) {
        if (task == null) throw new NullPointerException("task");

        if (ownsExactExecutionTurn()) {
            synchronized (LIFECYCLE_LOCK) {
                if (!acceptsUnitForLocked(expected)) return false;
            }
            // Exact-turn ownership excludes a concurrent legal lifecycle
            // transition; do not hold LIFECYCLE_LOCK across a simulation body.
            runRootInlineIfHealthy(task);
            return true;
        }

        synchronized (LIFECYCLE_LOCK) {
            if (!acceptsUnitForLocked(expected)) return false;
            RegionWorkerPool expectedPool = pool;
            if (expectedPool.enqueueUnit(task)) {
                expectedPool.wake(1);
                return true;
            }
            Throwable failure = expectedPool.fatalFailureOrNull();
            if (failure == null) {
                failure = new IllegalStateException(
                        "current island runtime rejected a unit publication");
            }
            failure = expectedPool.publishAndSelectFatal(failure);
            RegionScheduler.<RuntimeException>rethrow(
                    cancelRejectedRoot(expectedPool, task, failure));
            return false;
        }
    }

    /** A8 clock rebase: which async level owns this LevelTicks instance?
     *  (LevelTicks carries no level reference; the async slot registers both
     *  lanes on the RegionizedServer each tick.) Null = not an async lane. */
    public static RegionizedServer forTicksInstance(Object scheduler) {
        if (!active) return null;
        for (RegionizedServer rs : PER_LEVEL.values()) {
            if (rs.asyncActive
                    && (rs.blockTicksAccess == scheduler || rs.fluidTicksAccess == scheduler)) {
                return rs;
            }
        }
        return null;
    }

    /**
     * Pure global desire consumed by both the wall-phase snapshot and every
     * per-level mode latch. Keeping one predicate prevents a configured ON from
     * opening the synchronous player venue and autonomous island venues in the
     * same wall tick. Live-runtime and wall-phase state are separate cuts.
     */
    static boolean asyncModeWantedAtLevelHead() {
        return FabroliaConfig.asyncIslands
                && !FabroliaConfig.asyncFailClosedTripped
                && FabroliaConfig.regionEventScheduler
                && FabroliaConfig.useRegionColorGate()
                && FabroliaConfig.enableRegionThreading
                && !FabroliaConfig.disabled;
    }

    /** Hot-path async liveness flag (detector gate): maintained by the
     *  per-level mode latch; one static volatile read per setBlock. */
    private static volatile boolean asyncLive;

    public static boolean asyncLive() {
        return asyncLive;
    }

    static void updateAsyncLive() {
        asyncLive = anyAsyncActive();
    }

    /** Audit: async levels only (entity-save owner routing etc.). */
    public static java.util.List<RegionizedServer> allAsync() {
        if (!active) return java.util.Collections.emptyList();
        java.util.ArrayList<RegionizedServer> out = new java.util.ArrayList<>(2);
        for (RegionizedServer rs : PER_LEVEL.values()) {
            if (rs.asyncActive) out.add(rs);
        }
        return out;
    }

    /** A8: does ANY level currently run async islands? The main-context
     *  command fence keys on this (cheap: volatile reads over a tiny map). */
    public static boolean anyAsyncActive() {
        if (!active) return false;
        for (RegionizedServer rs : PER_LEVEL.values()) {
            if (rs.asyncActive) return true;
        }
        return false;
    }


    /** A2: is the worker pool alive? Coordinators bound their barrier pump on
     *  this so a unit stranded across shutdown exits instead of spinning. */
    public static boolean poolRunning() {
        RegionWorkerPool p = pool;
        return p != null && p.isRunning();
    }

    /** True when the calling thread is the coordinator currently running
     *  {@code owner}'s unit (sender redesign 2026-07-14: the unit's own
     *  packet-snapshot builds need no claim — they ARE the claim). */
    public static boolean isOwningCoordinator(IslandRegistry.PIsland owner) {
        return Thread.currentThread() instanceof RegionWorkerThread w
                && w.currentDomain != null && w.currentDomain.owner == owner;
    }

    /**
     * Cold-path ownership proof for a direct worker-to-Main blocking-wait guard.
     * A non-worker helper can legitimately hold an autonomous island claim, so
     * worker fields and the pool's causal token are not a complete witness.
     */
    static boolean currentThreadHoldsLiveIslandClaim() {
        Thread current = Thread.currentThread();
        for (RegionizedServer runtime : PER_LEVEL.values()) {
            for (IslandRegistry.PIsland island : runtime.islandRegistry.identitySnapshot()) {
                if (island.unitClaim.get() && island.claimHolder == current) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Pump global/Main-safe dependencies without taking unrelated ready work
     * while the coordinator retains its own island claim. */
    static boolean workerHelpBarrierDependenciesOnce() {
        RegionWorkerPool p = pool;
        return p != null
                && Thread.currentThread() instanceof RegionWorkerThread me
                && p.workBarrierDependenciesOnce(me);
    }

    /**
     * Owner-first entity-barrier step.  This does not publish a new simulation
     * root: dispatch already published the exact region obligation and this
     * coordinator is executing inside its existing causal step.  The pool's
     * ordinary ready-entry transaction supplies claim exclusion, fatal-cut
     * admission, entity completion and the one barrier contribution.
     */
    static boolean workerHelpOwnEntityOnce(
            Region region,
            EpochDomain.EntityDispatchPublication publication,
            long epoch) {
        RegionWorkerPool p = pool;
        if (p == null || !(Thread.currentThread() instanceof RegionWorkerThread me)) {
            return false;
        }
        return p.helpAcceptedEntityRegion(me, region, publication, epoch);
    }

    /** Barrier-pump step. Physical Main consumes only explicit Main lanes;
     *  non-Main callers may additionally help the autonomous ready lane. */
    public static boolean stealOneRegionTask() {
        RegionWorkerPool p = pool;
        MinecraftServer current = server;
        boolean physicalMain = RegionalPlayerEntityTicksV2
                .isPhysicalServerThread(current);
        return p != null && p.stealOne(physicalMain);
    }

    /** Surface an off-thread tick failure unchanged on the current caller. */
    public static void throwWorkerFailureIfAny() {
        Throwable failure = workerFailureOrNull();
        if (failure != null) RegionScheduler.<RuntimeException>rethrow(failure);
    }

    /** Non-throwing read used to suppress success-only tails after a peer selected W. */
    static Throwable workerFailureOrNull() {
        RegionWorkerPool p = pool;
        if (p == null) p = transitionPool;
        Throwable failure = p == null ? null : p.fatalFailureOrNull();
        return failure != null ? failure : orphanFatalFailure;
    }

    /**
     * Accept a prepared success publication only if no generation-wide fatal
     * winner has linearized first. Unowned pool-backed commits use the exact
     * monitor used by {@link RegionWorkerPool#publishFatalFailure(Throwable)};
     * already-pinned causal commits use the generation's volatile W slot. The
     * orphan path uses the scheduler lifecycle monitor which owns orphanFatalFailure.
     */
    public static void commitSuccessIfHealthy(Runnable commit) {
        if (commit == null) throw new NullPointerException("commit");
        RegionWorkerPool causalOwner =
                RegionWorkerPool.currentThreadCausalOwnerOrNull();
        if (causalOwner != null) {
            // Its step/permit pins the generation, so both admission and terminal
            // W observations can stay lock-free. The callback consequently holds
            // neither lifecycle monitor and cannot create their former convoy.
            causalOwner.commitSuccessIfHealthy(commit);
            return;
        }
        // Unowned callers retain the canonical scheduler -> pool monitor order and
        // the cold atomic commit cut: no external lifetime pins this generation.
        synchronized (LIFECYCLE_LOCK) {
            RegionWorkerPool p = failureGateLocked();
            if (p != null) {
                p.commitSuccessIfHealthy(commit);
                return;
            }
            Throwable failure = orphanFatalFailure;
            if (failure != null) RegionScheduler.<RuntimeException>rethrow(failure);
            try {
                commit.run();
                return;
            } catch (Throwable thrown) {
                if (orphanFatalFailure == null) orphanFatalFailure = thrown;
                RegionScheduler.<RuntimeException>rethrow(orphanFatalFailure);
            }
        }
    }

    /**
     * Run the exact actor's captured vanilla consumer after an atomic W-vs-entry
     * cut.  The body runs outside both lifecycle monitors and deliberately owns
     * no ExternalPermit: physical Main retains the paused turn and waits for this
     * actor synchronously, so such a permit would be both cyclic and unauthorized.
     */
    private static void commitPausedExactActorEntryIfHealthy(Thread physicalOwner) {
        if (physicalOwner == null) throw new NullPointerException("physicalOwner");
        synchronized (LIFECYCLE_LOCK) {
            RegionWorkerPool current = pool;
            if (current == null) {
                RegionWorkerPool failureGate = failureGateLocked();
                if (failureGate != null) failureGate.throwIfFailed();
                if (orphanFatalFailure != null) {
                    RegionScheduler.<RuntimeException>rethrow(orphanFatalFailure);
                }
                IllegalStateException refusal = new IllegalStateException(
                        "exact player actor lost its active pool generation");
                if (failureGate != null) {
                    RegionScheduler.<RuntimeException>rethrow(
                            failureGate.publishAndSelectFatal(refusal));
                }
                orphanFatalFailure = refusal;
                RegionScheduler.<RuntimeException>rethrow(orphanFatalFailure);
            }
            current.commitPausedExactActorEntryIfHealthy(
                    physicalOwner, Thread.currentThread());
        }
    }

    static void runPausedExactActorBodyIfHealthy(Thread physicalOwner, Runnable body) {
        if (body == null) throw new NullPointerException("body");
        commitPausedExactActorEntryIfHealthy(physicalOwner);
        try {
            body.run();
        } catch (Throwable thrown) {
            // Publish before returning control to mod/vanilla callers.  Even a
            // caller which catches this rethrow cannot reopen the next body.
            RegionScheduler.<RuntimeException>rethrow(
                    publishFatalAndSelectWinner(thrown));
        }
        // A peer W may win while the already-admitted vanilla body is running.
        // Refuse a locally clean return before the actor begins its completion
        // bookkeeping; the caller then publishes/retains W and exhausts cleanup.
        try {
            commitSuccessIfHealthy(() -> {});
        } catch (Throwable terminalFailure) {
            RegionScheduler.<RuntimeException>rethrow(
                    publishFatalAndSelectWinner(terminalFailure));
        }
    }

    private static boolean callPausedExactActorBooleanIfHealthy(
            Thread physicalOwner, java.util.function.BooleanSupplier body) {
        commitPausedExactActorEntryIfHealthy(physicalOwner);
        boolean result;
        try {
            result = body.getAsBoolean();
        } catch (Throwable thrown) {
            RegionScheduler.<RuntimeException>rethrow(
                    publishFatalAndSelectWinner(thrown));
            return false;
        }
        try {
            commitSuccessIfHealthy(() -> {});
        } catch (Throwable terminalFailure) {
            RegionScheduler.<RuntimeException>rethrow(
                    publishFatalAndSelectWinner(terminalFailure));
        }
        return result;
    }

    /**
     * Select the body gate and, when no live pool admission is required, linearize
     * logical entry directly. A null return is an admitted direct body, not an
     * unchecked pool snapshot. It normally means no pool exists. The one other
     * case is the exact lifecycle owner finishing post-join persistence while a
     * healthy, terminated generation remains published as the failure authority.
     * The caller must finish either case through
     * {@link #finishNoPoolSuccessBody(Throwable)}.
     */
    private static RegionWorkerPool beginSuccessBodyIfHealthy() {
        synchronized (LIFECYCLE_LOCK) {
            RegionWorkerPool current = failureGateLocked();
            if (current != null) {
                if (lifecycleState == LifecycleState.STOPPING
                        && lifecycleOwner == Thread.currentThread()
                        && current.isTerminated()) {
                    // Worker join is the exclusive execution edge for the final
                    // clock/portal/arrival drain. The generation must stay published
                    // so a failure in one of those vanilla bodies still becomes its
                    // exact W. This empty commit is the atomic W-vs-entry cut; the
                    // matching terminal cut remains in finishNoPoolSuccessBody.
                    current.commitSuccessIfHealthy(() -> {});
                    return null;
                }
                return current;
            }
            Throwable failure = orphanFatalFailure;
            if (failure != null) RegionScheduler.<RuntimeException>rethrow(failure);
            return null;
        }
    }

    /**
     * Terminal edge for a directly admitted body. Runtime activation may have
     * installed a pool from inside a no-pool body (the regions-on command is the
     * intentional case), while post-join teardown deliberately retains its
     * terminated pool as the failure authority. A clean return must therefore
     * commit against the gate which is current now, not merely reread the orphan
     * slot.
     */
    private static Throwable finishNoPoolSuccessBody(Throwable primary) {
        synchronized (LIFECYCLE_LOCK) {
            if (primary != null) return publishFatalAndSelectWinner(primary);
            RegionWorkerPool current = failureGateLocked();
            if (current != null) {
                try {
                    current.commitSuccessIfHealthy(() -> {});
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            }
            return orphanFatalFailure;
        }
    }

    /**
     * Run one fallible success-only stage only if it atomically wins body entry
     * before the current pool generation's first fatal Throwable.  Unlike
     * {@link #commitSuccessIfHealthy(Runnable)}, this method never invokes the
     * supplied body while holding the pool lifecycle monitor.
     */
    public static void runSuccessBodyIfHealthy(Runnable body) {
        if (body == null) throw new NullPointerException("body");
        Thread exactPhysicalOwner =
                RegionalPlayerEntityTicksV2.exactActorPhysicalOwnerOrNull();
        if (exactPhysicalOwner != null) {
            runPausedExactActorBodyIfHealthy(exactPhysicalOwner, body);
            return;
        }
        RegionWorkerPool causalOwner =
                RegionWorkerPool.currentThreadCausalOwnerOrNull();
        if (causalOwner != null) {
            causalOwner.runSuccessBodyIfHealthy(body);
            return;
        }
        RegionWorkerPool p = beginSuccessBodyIfHealthy();
        if (p != null) {
            p.runSuccessBodyIfHealthy(body);
            return;
        }
        Throwable failure = null;
        try {
            body.run();
        } catch (Throwable thrown) {
            failure = thrown;
        }
        failure = finishNoPoolSuccessBody(failure);
        if (failure != null) RegionScheduler.<RuntimeException>rethrow(failure);
    }

    /** Allocation-free boolean form of {@link #runSuccessBodyIfHealthy(Runnable)}. */
    public static boolean callSuccessBooleanIfHealthy(
            java.util.function.BooleanSupplier body) {
        if (body == null) throw new NullPointerException("body");
        Thread exactPhysicalOwner =
                RegionalPlayerEntityTicksV2.exactActorPhysicalOwnerOrNull();
        if (exactPhysicalOwner != null) {
            return callPausedExactActorBooleanIfHealthy(exactPhysicalOwner, body);
        }
        RegionWorkerPool causalOwner =
                RegionWorkerPool.currentThreadCausalOwnerOrNull();
        if (causalOwner != null) {
            return causalOwner.callSuccessBooleanIfHealthy(body);
        }
        RegionWorkerPool p = beginSuccessBodyIfHealthy();
        if (p != null) return p.callSuccessBooleanIfHealthy(body);
        Throwable failure = null;
        boolean result;
        try {
            result = body.getAsBoolean();
        } catch (Throwable thrown) {
            failure = thrown;
            result = false;
        }
        failure = finishNoPoolSuccessBody(failure);
        if (failure != null) RegionScheduler.<RuntimeException>rethrow(failure);
        return result;
    }

    /**
     * Publish a failure caught by a Fabrolia physical-Main task. Vanilla's
     * BlockableEventLoop may log ordinary task exceptions and continue, so the
     * shared first-wins slot is the crash authority checked by tick boundaries.
     */
    public static void publishFatalFailure(Throwable failure) {
        if (failure == null) throw new NullPointerException("failure");
        synchronized (LIFECYCLE_LOCK) {
            RegionWorkerPool p = failureGateLocked();
            if (p != null) {
                p.publishFatalFailure(failure);
            } else if (orphanFatalFailure == null) {
                orphanFatalFailure = failure;
            }
        }
    }

    /** F2b: registration-time cell stamp for the persistent BE index. Deliberately
     *  NOT gated on {@code active} — the index must stay fresh across scheduler
     *  toggles; a level that never built a RegionizedServer has nothing to maintain
     *  (its first partitioned pass bulk-builds). Called under LevelMixin's
     *  addBlockEntityTicker lock, so stash order matches the vanilla list order. */
    public static void onBlockEntityTickerAdded(net.minecraft.world.level.Level level,
                                                TickingBlockEntity ticker) {
        if (!(level instanceof ServerLevel sl)) return;
        // Deliberately BEFORE the PER_LEVEL lookup and independent of `active`:
        // LevelChunk registers the wrapper while it still delegates to the real
        // BoundTickingBlockEntity. Lithium may replace that delegate with its
        // posless SLEEPING_BLOCK_ENTITY_TICKER before regions are enabled or
        // before this level has built a RegionizedServer.
        BlockPos pos = ticker.getPos();
        if (pos != null) {
            BLOCK_ENTITY_TICKER_POSITIONS.remember(ticker, pos.immutable());
        }
        RegionizedServer rs = PER_LEVEL.get(sl);
        if (rs != null) rs.stashPendingBeAdd(ticker);
    }

    /**
     * Resolve a wrapper's current position, falling back to the position seen
     * at registration. Routing only: ticking still consults the live
     * {@code getPos()} so a sleeping delegate remains a no-op.
     */
    static BlockPos resolveBlockEntityTickerPosition(TickingBlockEntity ticker) {
        return BLOCK_ENTITY_TICKER_POSITIONS.resolve(ticker, ticker.getPos());
    }

    /** Drain worker-safe roots; physical Main additionally drains its exclusive lane. */
    public static void drainMainSafeNow() {
        RegionWorkerPool p = pool;
        MinecraftServer current = server;
        boolean physicalMain = RegionalPlayerEntityTicksV2
                .isPhysicalServerThread(current);
        if (p != null) p.drainMainSafeAll(physicalMain);
    }

    /**
     * R4 liveness backstop, called by the barrier pump while main idles.
     * Regions born MID-tick (an entity crossing into a fresh grid cell via
     * indexMove, an executeAt into empty territory) have no drain epilogue
     * and sit in no group — nobody else ever completes them, and ONE stale
     * lower-color orphan wedges every dependent transitively (live wedge
     * 2026-06-12: Server thread 203s at the entity barrier, one worker
     * spinning continuations, 29 parked). tryMarkDone carries every refusal
     * guard, so a blind pass over the snapshot is safe from any state; the
     * orphan's entities tick in their OLD region this tick (the grouped-where-
     * it-was rule), so completing it publishes no concurrent claim.
     */
    private static long lastHealNanos;
    /** Adaptive heal cadence: 2 ms while passes have effect (fresh dispatch, real
     *  orphans), doubling to a 16 ms ceiling while passes change nothing. The
     *  fixed 2 ms cadence cost ~1.8 ms/tick of snapshot copies + full region
     *  scans on the 2026-07-11 profiles — a liveness backstop for RARE orphans
     *  doing routine O(regions) work ~30×/tick. Worst-case orphan heal latency
     *  becomes 16 ms + snapshot staleness (≤50 ms) — bounded, and three orders
     *  of magnitude inside the 5 s stall watchdog. Reset on every dispatch. */
    private static long healIntervalNanos = 2_000_000L;

    /** Called at entity dispatch: a new epoch is exactly when new orphans can
     *  appear, so heal at full cadence again. */
    static void resetHealBackoff() {
        healIntervalNanos = 2_000_000L;
    }

    public static void healColorGate() {
        if (!active || !FabroliaConfig.useRegionColorGate()) return;
        runSuccessBodyIfHealthy(RegionScheduler::healColorGateEntered);
    }

    /** One admitted liveness-maintenance pass; nested executable roots keep their cuts. */
    private static void healColorGateEntered() {
        if (!active || !FabroliaConfig.useRegionColorGate()) return;
        throwWorkerFailureIfAny();
        // Main-thread caller only (the barrier pump) — plain fields suffice.
        long now = System.nanoTime();
        if (now - lastHealNanos < healIntervalNanos) return;
        lastHealNanos = now;
        boolean effect = false;
        for (RegionizedServer rs : PER_LEVEL.values()) {
            throwWorkerFailureIfAny();
            var regions = rs.healSnapshot(now);
            for (int i = 0, n = regions.size(); i < n; i++) {
                throwWorkerFailureIfAny();
                Region r = regions.get(i);
                if (r.state() != RegionState.ACTIVE) continue;
                // A2: every check runs in the REGION's own timeline — with async
                // islands live, one heal pass covers the global lane and every
                // in-flight island barrier alike (a wedged island region gets
                // re-armed even after the global barrier finished, and never
                // against the wrong epoch).
                EpochDomain d = r.domain;
                long epoch = d.epoch;
                if (d.dispatchedEpoch != epoch) continue; // pre-dispatch: undecidable
                boolean pending = d.isEventEntityEpoch(epoch) && d.entityPending.get() > 0;
                boolean wasSealedDone = r.hasSealedDone(epoch);
                ColorGate.tryMarkDone(r, rs);
                if (!wasSealedDone && r.hasSealedDone(epoch)) effect = true;
                if (pending && rs.owesEntityPhase(r)) {
                    if (ColorGate.eligible(r, rs) && r.currentWorker() == null) {
                        enqueueReady(r);
                        wakeWorkers(1);
                        effect = true;
                    }
                } else if (pending) {
                    int before = d.entityPending.get();
                    rs.contributeEntityBarrier(r);
                    if (d.entityPending.get() != before) effect = true;
                }
                // PRODUCTION WEDGE FIX (xaprosmp thread dump 2026-07-13): a
                // region with QUEUE-ONLY pending work (spawn/block-tick tasks
                // whose future main is pumping) has no re-arm path when it is
                // not owed an entity phase — a ready entry consumed-and-
                // dropped in the claim race stranded the pump with all 30
                // workers idle-parked. Re-arm only eligible, unwitnessed work:
                // blocked colors and next-epoch queues cannot be consumed yet.
                if (r.currentWorker() == null
                        && (!r.queue().isEmpty() || !r.helpQueue.isEmpty())
                        && ColorGate.inUnitWindow(r, rs)) {
                    // Two consecutive sightings with no retained notification.
                    if (r.strandedQueueSightings++ >= 1) {
                        r.strandedQueueSightings = 0;
                        enqueueReady(r);
                        wakeWorkers(1);
                        effect = true;
                    }
                } else {
                    r.strandedQueueSightings = 0;
                }
                // Leaked-claim forensic: a claim whose holder has moved on
                // (currentRegion no longer this region) blocks tryMarkDone and
                // neighbor eligibility forever — the other wedge shape this
                // dump could not distinguish. Two consecutive sightings avoid
                // the benign clear-then-release window in the claim epilogue.
                // COHERENT SNAPSHOT (2026-07-13, user 07:21 report): with the
                // publication protocol (publish-before-claim, release-before-
                // unpublish), a reader that observes the claim CAS is
                // guaranteed to observe the matching publication — so a
                // mismatch here can no longer be a transition artifact. The
                // snapshot is validated by re-reading the holder AFTER the
                // publication read (holder changed = transition in flight =
                // skip), and a report additionally requires the SAME claim
                // GENERATION across two sightings: one acquisition that
                // persisted, with its acquire path and age from the ledger —
                // never two different claims blurred together.
                Thread holder = r.currentWorker();
                long gen = r.claimGen;
                Region published = holder instanceof RegionWorkerThread w0 ? w0.currentRegion : r;
                if (holder != r.currentWorker()) {
                    r.staleClaimSightings = 0;
                    r.staleClaimGenSeen = -1L;
                } else if (holder instanceof RegionWorkerThread hw && published != r) {
                    // DEAD-HOLDER RECOVERY (2026-07-13): a claim held by a thread
                    // that no longer exists can NEVER be released by its owner and
                    // provably cannot be used by it either — force-release is the
                    // one case where recovery cannot double-run the region. Live
                    // holders are only ever REPORTED: releasing under a live
                    // holder could put two threads in the same region.
                    if (!holder.isAlive()) {
                        LOGGER.error("[event-sched] DEAD WORKER CLAIM on region {} ({}): holder {} terminated "
                                + "without releasing (queue={} help={} owed={}) — force-releasing and re-arming",
                                Long.toHexString(r.gridKey), r.level.dimension().identifier(),
                                holder.getName(), r.queue().size(), r.helpQueue.size(),
                                rs.owesEntityPhase(r));
                        r.staleClaimSightings = 0;
                        r.staleClaimGenSeen = -1L;
                        ((RegionWorkerThread) holder).currentRegion = null;
                        r.stampRelease("heal-dead");
                        r.release(holder);
                        try {
                            if (!r.queue().isEmpty() || !r.helpQueue.isEmpty()
                                    || (pending && rs.owesEntityPhase(r))) {
                                enqueueReady(r);
                                wakeWorkers(1);
                            }
                            ColorGate.tryMarkDone(r, rs);
                            // The dead worker may have committed entityEpoch and
                            // then died before release/done/contribution.  The
                            // earlier heal attempt could not contribute while
                            // its claim still blocked the done seal, so recovery
                            // owns that exact remaining success tail as well.
                            // Keep the claim transaction open through it: domain
                            // rebase must not overtake an old-domain barrier
                            // decrement merely because currentWorker is null.
                            if (pending && !rs.owesEntityPhase(r)) {
                                rs.contributeEntityBarrier(r);
                            }
                        } finally {
                            // The dead holder opened this receipt at tryClaim.
                            // Recovery owns its exact synthetic completion tail.
                            r.finishClaimTransaction();
                        }
                        effect = true;
                    } else if (r.staleClaimGenSeen != gen) {
                        // First mismatch sighting for THIS acquisition.
                        r.staleClaimGenSeen = gen;
                        r.staleClaimSightings = 1;
                    } else if (++r.staleClaimSightings >= 2 && now - lastStaleClaimLogNanos > 10_000_000_000L) {
                        lastStaleClaimLogNanos = now;
                        StackTraceElement[] hs = holder.getStackTrace();
                        StringBuilder sb = new StringBuilder();
                        for (int si = 0; si < Math.min(hs.length, 14); si++) {
                            sb.append("\n      at ").append(hs[si]);
                        }
                        LOGGER.error("[event-sched] RETAINED CLAIM on region {} ({}): holder {} gen={} acquired via '{}' {}ms ago "
                                + "(lastRelease: gen={} via '{}') and is not working it "
                                + "(queue={} help={} owed={} holderRegion={} holderDomain={}) — same-generation across "
                                + "sightings under the coherent publication protocol: this is a REAL retention, "
                                + "not observer skew. Holder stack:{}",
                                Long.toHexString(r.gridKey), r.level.dimension().identifier(),
                                holder.getName(), gen, r.claimPath,
                                (now - r.claimAcquiredNanos) / 1_000_000L,
                                r.lastReleaseGen, r.lastReleasePath,
                                r.queue().size(), r.helpQueue.size(),
                                rs.owesEntityPhase(r),
                                published == null ? "-" : Long.toHexString(published.gridKey),
                                hw.currentDomain == null || hw.currentDomain.owner == null
                                        ? "-" : ("island#" + hw.currentDomain.owner.id),
                                sb);
                    }
                } else {
                    r.staleClaimSightings = 0;
                    r.staleClaimGenSeen = -1L;
                }
            }
            int owingTotal = rs.entityPendingCount();
            boolean barrierPending = owingTotal > 0;
            long oldestArmedNanos = barrierPending ? now - rs.entityBarrierAgeNanos(now) : Long.MAX_VALUE;
            // A2: a pending ISLAND barrier wedging would hang the combined pump
            // just as silently — fold the oldest pending island domain into the
            // same age check (owesEntityPhase/diagnose below are region-relative
            // already, so the report is correct whichever domain is stuck).
            if (rs.asyncActive) {
                for (IslandRegistry.PIsland p : rs.islandRegistry.islands()) {
                    EpochDomain d = p.domain;
                    int ip = d.isEventEntityEpoch(d.epoch) ? d.entityPending.get() : 0;
                    if (ip > 0) {
                        barrierPending = true;
                        owingTotal += ip;
                        oldestArmedNanos = Math.min(oldestArmedNanos, d.entityBarrierArmedNanos);
                    }
                }
            }
            // Stall forensics: the legacy group path logged after 50k zero-progress
            // passes; the event path has no polling loop to count, so log by age.
            // A CLAIMED region anywhere in the level means a phase is genuinely
            // executing (e.g. 18k TNT in one region legitimately runs for seconds —
            // 2026-07-02 mega-polygon) — that is WARN "long phase", not a wedge;
            // ERROR is reserved for nothing-running-and-nothing-progressing.
            if (barrierPending && now - oldestArmedNanos > 5_000_000_000L
                    && now - lastStallLogNanos > 5_000_000_000L) {
                lastStallLogNanos = now;
                boolean working = false;
                for (int i = 0, n = regions.size(); i < n; i++) {
                    Region r = regions.get(i);
                    if (r.state() == RegionState.ACTIVE && r.currentWorker() != null) {
                        working = true;
                        break;
                    }
                }
                for (int i = 0, n = regions.size(); i < n; i++) {
                    Region r = regions.get(i);
                    if (r.state() == RegionState.ACTIVE && rs.owesEntityPhase(r)) {
                        if (working) {
                            LOGGER.warn("[event-sched] entity barrier waiting {}s on a LONG RUNNING phase ({} owing — not a wedge); {}",
                                    (now - oldestArmedNanos) / 1_000_000_000L,
                                    owingTotal, ColorGate.diagnose(r, rs));
                        } else {
                            LOGGER.error("[event-sched] entity barrier STALLED {}s, {} owing, nothing running; {}",
                                    (now - oldestArmedNanos) / 1_000_000_000L,
                                    owingTotal, ColorGate.diagnose(r, rs));
                        }
                        break;
                    }
                }
            }
        }
        throwWorkerFailureIfAny();
        healIntervalNanos = effect ? 2_000_000L : Math.min(healIntervalNanos << 1, 16_000_000L);
    }

    private static long lastStallLogNanos;
    private static long lastStaleClaimLogNanos;

    /** Used by ParallelProcessor.isServerExecutionThread — region workers count as MC threads. */
    public static boolean isRegionWorker(Thread t) {
        return t instanceof RegionWorkerThread;
    }
}
