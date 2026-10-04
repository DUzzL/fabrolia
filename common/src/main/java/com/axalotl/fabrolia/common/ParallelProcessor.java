package com.axalotl.fabrolia.common;

import com.axalotl.fabrolia.api.utils.FabroliaCompatible;
import com.axalotl.fabrolia.api.utils.SyncItemPickup;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.axalotl.fabrolia.common.regions.NetherPortalTransfers;
import com.axalotl.fabrolia.common.regions.EntityTickLease;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.Map;

import static com.axalotl.fabrolia.common.utils.TickStats.*;
import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementBuffer;
import com.axalotl.fabrolia.common.regions.RegionViz;
import com.axalotl.fabrolia.common.regions.RegionScheduler;

public final class ParallelProcessor {

    private ParallelProcessor() {}

    public interface TickGuard extends SyncItemPickup.PortalReservation {
        /** task #19: entity has a portal teleport queued on main — it must
         *  stop participating in simulation until main resolves it (vanilla:
         *  the entity would already be gone the tick portal-time fired). */
        @Override
        default boolean fabrolia$isPortalPending() { return false; }
        default void fabrolia$setPortalPending(boolean pending) {}
        @Override
        default boolean fabrolia$isPortalResolutionOwner() { return false; }
        /**
         * Negative cache for NetherPortalPlayerAccess custody: false proves the
         * entity is not in CAPTURED, so every entity-pair push can answer
         * without the global CAMERAS monitor; true means "consult the map".
         * Guards that keep no flag must therefore answer true.
         */
        default boolean fabrolia$mayBePortalCaptured() { return true; }
        default void fabrolia$setPortalCaptured(boolean captured) {}

        /** task #26 stuck-in-portal probe: {@code System.nanoTime()} of this
         *  entity's most recent tick (setOldPosAndRot head), so `regions debug
         *  portalstuck` can tell a still-ticking-but-physically-trapped cart
         *  (ms-ago ≈ one epoch) from a stranded/unticked one (ms-ago large).
         *  Wall-clock nanos, NOT game-time — immune to the island-L-vs-wall
         *  offset that would make a healthy island-driven cart look stalled.
         *  0 = never ticked. One volatile write per entity tick. */
        default long fabrolia$lastTickNanos() { return 0L; }

        /** task #26 stuck-in-portal probe: portal cooldown + portalProcess
         *  entry/expiry, the "portal-entry state" discriminator. */
        default String fabrolia$portalStateInfo() { return "?"; }

        EntityTickLease fabrolia$tickLease();
        default boolean fabrolia$tryBeginTick() { return fabrolia$tickLease().tryBegin(); }
        default void fabrolia$endTick() { fabrolia$tickLease().end(); }
        /** Immutable class/type safety only; configured synchronization is read per dispatch. */
        byte fabrolia$getSyncCache();
        void fabrolia$setSyncCache(byte v);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ParallelProcessor.class);

    private static volatile MinecraftServer server;

    public static MinecraftServer getServer() { return server; }

    public static volatile ExecutorService executor;
    public static final ThreadGroup FABROLIA_GROUP = new ThreadGroup("Fabrolia-Tick-Pool");
    private static volatile boolean isShuttingDown = false;

    public static final MobCategory[] CATEGORIES = MobCategory.values();

    private static final Set<UUID> BLACKLISTED_ENTITIES = ConcurrentHashMap.newKeySet();
    private static final Class<?>[] BLOCKED_BASE_CLASSES = {
            AbstractBoat.class,
            FallingBlockEntity.class,
            Shulker.class,
            // Preserve synchronous Enderman ticks in regions-off flat-pool mode.
            Enderman.class,
            Boat.class,
            Projectile.class,
            ServerPlayer.class,
            AbstractMinecart.class
    };

    private static final ClassValue<Boolean> BLOCKED_CACHE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> cls) {
            for (Class<?> base : BLOCKED_BASE_CLASSES) {
                if (base.isAssignableFrom(cls)) return Boolean.TRUE;
            }
            return Boolean.FALSE;
        }
    };

    private static boolean isBlocked(Class<?> cls) {
        return BLOCKED_CACHE.get(cls);
    }


    private static final ClassValue<Boolean> FABROLIA_API_COMPATIBLE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> cls) {
            return cls.isAnnotationPresent(FabroliaCompatible.class);
        }
    };

    public static boolean entitySupportsFabroliaApi(Entity entity) {
        return !"minecraft".equals(EntityType.getKey(entity.getType()).getNamespace())
                && !FABROLIA_API_COMPATIBLE.get(entity.getClass());
    }

    private static final Map<String, Set<WeakReference<Thread>>> MC_THREAD_TRACKER = new ConcurrentHashMap<>();

    public static void setServer(MinecraftServer server) {
        ParallelProcessor.server = server;
        NetherPortalTransfers.init(server);
    }

    public static void setupThreadPool(int parallelism) {
        ExecutorService created = createPool(parallelism);
        executor = created;
        isShuttingDown = false;
    }


    private static ForkJoinPool createPool(int parallelism) {
        return new ForkJoinPool(parallelism, fabroliaWorkerFactory(),
                ParallelProcessor::fabrolia$failStopUncaught,
                /* asyncMode       */ true,
                /* corePoolSize    */ parallelism,
                /* maximumPoolSize */ parallelism,
                /* minimumRunnable */ 1,
                /* saturate        */ pool -> true,
                /* keepAliveTime   */ 60L, TimeUnit.SECONDS);
    }

    private static final AtomicInteger WORKER_COUNTER = new AtomicInteger();

    private static ForkJoinPool.ForkJoinWorkerThreadFactory fabroliaWorkerFactory() {
        int priority = FabroliaConfig.getThreadPriority();
        return pool -> {
            FabroliaForkJoinWorkerThread t = new FabroliaForkJoinWorkerThread(pool);
            t.setDaemon(true);
            t.setPriority(priority);
            t.setUncaughtExceptionHandler(ParallelProcessor::fabrolia$failStopUncaught);
            registerThread("Fabrolia-Tick", t);
            return t;
        };
    }

    /** A pool envelope escape is a generation failure, never a log-and-continue event. */
    private static void fabrolia$failStopUncaught(Thread thread, Throwable thrown) {
        Throwable winner = RegionScheduler.publishFatalAndSelectWinner(thrown);
        try {
            LOGGER.error("Uncaught exception in thread {}", thread.getName(), thrown);
        } catch (Throwable diagnosticFailure) {
            fabrolia$mergeFailure(winner, diagnosticFailure);
        }
    }

    private static final class FabroliaForkJoinWorkerThread extends ForkJoinWorkerThread {
        FabroliaForkJoinWorkerThread(ForkJoinPool pool) {
            super(FABROLIA_GROUP, pool, false);
        }

        @Override
        protected void onStart() {
            super.onStart();
            setName("Fabrolia-Tick-Pool-Thread-" + WORKER_COUNTER.getAndIncrement());
        }
    }

    public static void registerThread(String poolName, Thread thread) {
        MC_THREAD_TRACKER.computeIfAbsent(poolName, _ -> ConcurrentHashMap.newKeySet()).add(new WeakReference<>(thread));
    }

    public static boolean isServerExecutionThread() {
        Thread t = Thread.currentThread();
        // Region workers drive vanilla entity ticks while holding a region claim,
        // same semantic as Fabrolia-Tick-Pool workers — both count as "on a server
        // execution thread" for code that branches on this check.
        return t instanceof FabroliaForkJoinWorkerThread
                || RegionScheduler.isRegionWorker(t)
                || com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                .inExactPlayerActor();
    }

    public static int getPoolSize() {
        return executor instanceof ForkJoinPool pool ? pool.getParallelism() : 0;
    }

    public static void onEntityTickBatchEnd(long batchStartNanos) {
        // Each fallible stage is admitted atomically against the generation fatal
        // cut.  Once admitted it may exhaust its own finally chain, but a W which
        // wins before the next stage prevents that successor from entering.
        // Routed advancement work applies here only when it can borrow a held fence
        // or no island is live (AdvancementBuffer.applyDeferred never acquires one).
        // Fire-time awards already ran on each player's owner; the exact player
        // actor keeps its synchronous vanilla trigger -> reward -> flush order.
        RegionScheduler.runSuccessBodyIfHealthy(AdvancementBuffer::applyDeferred);
        // Seal main's cross-dimension stage for the level just ticked (one
        // cohort per source entity batch, same contract as island units).
        var srcLevel = com.axalotl.fabrolia.common.regions.RegionScheduler.mainCurrentLevel;
        if (srcLevel != null) {
            if ((srcLevel.getGameTime() & 255) == 17
                    && srcLevel instanceof com.axalotl.fabrolia.common.parallelised.utils.FabroliaNavAudit audit) {
                RegionScheduler.runSuccessBodyIfHealthy(audit::fabrolia$sweepNavLeaks);
            }
            var srcRs = com.axalotl.fabrolia.common.regions.RegionScheduler.forLevel(srcLevel);
            if (srcRs != null) {
                RegionScheduler.runSuccessBodyIfHealthy(srcRs::sealGlobalStage);
            }
        }
        // Rerouted region tasks (claims that found their region already DONE
        // this epoch) must land same-tick — a leftover here would slide a
        // phase or race next tick's island slices on main.
        RegionScheduler.runSuccessBodyIfHealthy(RegionScheduler::drainMainSafeNow);
        // Region-boundary debug particles (/fabrolia regions show). One isEmpty check when off.
        if (!RegionViz.isEmpty()) {
            RegionScheduler.runSuccessBodyIfHealthy(RegionViz::tick);
        }
        // Success-only publication: an advancement/portal/arrival/main-safe
        // tail failure is part of this batch and may not consume a recording
        // slot or report the partial turn as completed.
        long elapsed = System.nanoTime() - batchStartNanos;
        RegionScheduler.commitSuccessIfHealthy(() -> {
            if (RECORDING_TICKS_LEFT.get() > 0) {
                TOTAL_BATCH_WALL_NS.addAndGet(elapsed);
                RECORDING_TICKS_LEFT.decrementAndGet();
            }
        });
    }

    public static void pumpUntilDone(CompletableFuture<?> future) {
        pumpUntilDone(future, false);
    }

    /**
     * Task #33 (2026-07-28 production wedge, `/tick sprint`): {@code
     * serviceRoutedCommands} makes MAIN also drain the A8 routed-command batch
     * while it waits. Required at any barrier whose completion depends on an
     * ISLAND UNIT finishing: a unit's scheduled-tick phase can hit a
     * non-island-local command block, whose coordinator then parks in
     * {@code IslandCommands.routeCommandBlock} until MAIN executes the batch —
     * but the self-nudge lands on the MinecraftServer task queue this pump
     * deliberately never polls (Carpet reentry, see below), and the next
     * drive-head batch point can't come while main is parked HERE. Cycle:
     * main ⇄ coordinator (the 2026-07-28 sprint-lockstep dump). Only legal
     * where main holds no claims and no global-lane body is executing. The
     * ordinary entity-dispatch barrier also needs this progress when parked
     * coordinators occupy every worker. Its selected global step returns only
     * after terminal cleanup, before the outer routed drain runs.
     */
    public static void pumpUntilDone(CompletableFuture<?> future, boolean serviceRoutedCommands) {
        pumpUntilDone0(future, null, serviceRoutedCommands);
    }

    /** Poll-only island-unit receipt: unlike CompletableFuture, it cannot run
     *  synchronous user callbacks in the W-to-notification interval. */
    public static void pumpUntilDone(
            com.axalotl.fabrolia.common.regions.IslandTickUnit.AttemptReceipt receipt,
            boolean serviceRoutedCommands) {
        pumpUntilDone0(null, receipt, serviceRoutedCommands);
    }

    private static void pumpUntilDone0(
            CompletableFuture<?> future,
            com.axalotl.fabrolia.common.regions.IslandTickUnit.AttemptReceipt receipt,
            boolean serviceRoutedCommands) {
        MinecraftServer s = server;
        if (s != null && s.isSameThread()) {
            // DO NOT call s.pollTask() (MinecraftServer task queue) here — reentrancy-
            // deadlock hazard. PID 73971 (2026-06-06): Carpet's EntityPlayerMPFake.kill
            // TickTask sat on the MS task queue. Server thread, mid entity-tick batch,
            // hit pumpUntilDone and polled that kill task → Player.remove → Entity.setRemoved
            // → PESM.Callback.onRemove → ChunkMap.removeEntity (Fabrolia15's `synchronized`
            // mixin) → acquired the ChunkMap monitor. It then dropped into VMP.parallelTick0
            // which submits to this very pool and parked on the resulting future. Workers
            // in this batch were running Mob.checkDespawn → Entity.discard → Entity.remove
            // → ChunkMap.removeEntity, all blocked waiting on the ChunkMap monitor Server
            // now held. Hard cycle, all 17 workers stuck. The MS task queue holds arbitrary
            // mod/Carpet tasks that walk Entity.setRemoved — NEVER drain it from a barrier.
            //
            // DO drain per-level ServerChunkCache$MainThreadExecutor.pollTask() — that's
            // a separate queue holding only chunk-system completion callbacks (chunk-gen
            // future tails, ticket placements, holder lifecycle). Vanilla's own pattern
            // for off-thread getChunk is supplyAsync(mainThreadProcessor).join() with
            // mainThreadProcessor.managedBlock(future::isDone) on the requesting side —
            // i.e. the exact loop below. Without this drain, an fabrolia worker that hits
            // ServerChunkCacheMixin.fabrolia$getChunk Tier 4 (cold path → ticket schedule)
            // parks forever because the ticket can't run while Server is here pure-parked
            // (PID 176032, 2026-06-07: 30+ workers stuck 149s on the Villager AI →
            // validateBedPoi → Level.getBlockState path).
            //
            // Safety: mainThreadProcessor tasks DO transitively run chunk-load callbacks
            // that addEntity via PESM (LevelCallback.onTrackingStart → ChunkMap.addEntity),
            // which contends Fabrolia15's `synchronized` mixin on ChunkMap. Workers also
            // contend that monitor (spawnForChunk → addFreshEntity → ChunkMap.addEntity).
            // This is plain monitor contention with bounded hold time, not a cycle —
            // every holder releases on method return. No worker parks while holding it,
            // unlike the Carpet-bot-kill chain above which parked a VMP future from
            // INSIDE the synchronized region.
            s.blockingCount++;
            try {
                // R5: cut the FINAL wake latency — the completing worker unparks main
                // directly instead of main discovering completion at its next 100µs park
                // slice boundary (avg ~50µs shaved off every barrier). The slices below
                // still stand: they drive the mainSync/steal/mainThreadProcessor lanes,
                // which have no completion event to hook.
                final Thread pumpWaiter = Thread.currentThread();
                if (future != null) {
                    future.whenComplete((v, t) -> LockSupport.unpark(pumpWaiter));
                }
                // Spin-then-park: most barriers through here are SHORT — an advancement
                // drain's eval batch or a nearly-complete entity batch, done in tens of µs.
                // The previous unconditional park(100µs) overshot by up to a full slice per
                // barrier; at 50 players × one drain each per tick that alone was ~5ms/tick
                // of pure park overshoot (drain = 10.24% of server thread in the 2026-06-09
                // 50-bot profile). Spinning ~40µs first lets short barriers complete at full
                // poll rate; long barriers pay the spin once (~0.3% of a 13ms batch) and
                // then park in 100µs slices exactly as before.
                long spinUntil = System.nanoTime() + 40_000L;
                while (!(future != null ? future.isDone() : receipt.isDone())) {
                    // A parallel vanilla body may have failed since the previous
                    // iteration. Stop before polling any Main/region/chunk successor.
                    RegionScheduler.throwWorkerFailureIfAny();
                    // Region mode: tick entities the region tasks routed to main (modded
                    // API-contract, UUID blacklist) WHILE the regions run — same overlap
                    // the old syncDeferred loop had, just demand-driven. No-op (one CLQ
                    // poll) when the queue is empty or regions are off.
                    boolean drained = RegionScheduler.drainReadyMainSyncTicks();
                    if (RegionScheduler.drainReadyMainTracking()) drained = true;
                    // Main pump: service one explicit Main-only/main-safe job instead of
                    // parking through it. Autonomous ready/help entries stay worker-owned;
                    // letting physical Main detach those long poles made its own tick time
                    // scale with the slowest remote island under 100-player load.
                    if (RegionScheduler.stealOneRegionTask()) {
                        drained = true;
                    }
                    for (ServerLevel level : s.getAllLevels()) {
                        // Each queue body has its own execution-time cut.  A
                        // check before the loop is insufficient: W can win
                        // between two levels or between check and poll.
                        if (RegionScheduler.callSuccessBooleanIfHealthy(
                                () -> level.getChunkSource()
                                        .mainThreadProcessor.pollTask())) {
                            drained = true;
                        }
                    }
                    // Liveness sweep EVERY iteration, not only when idle (self-throttled
                    // to 2ms internally): a busy main — constantly draining mainSync /
                    // steals / chunk tasks — STARVED the heal for 13+ seconds during the
                    // 2026-07-02 barrier stall, so orphan completions and owing-region
                    // re-pushes never ran exactly when they were needed most.
                    RegionScheduler.healColorGate();
                    // Task #33: unit-barrier waits must service the routed-command
                    // batch — the awaited unit may be parked INSIDE it waiting for
                    // exactly this drain (bounded fence attempt; parked waiters are
                    // fence-quiescent, live units defer it to the next iteration).
                    if (serviceRoutedCommands
                            && com.axalotl.fabrolia.common.regions.IslandCommands.hasRoutedPending()) {
                        com.axalotl.fabrolia.common.regions.IslandCommands.drainRoutedBatch();
                        drained = true;
                    }
                    if (!drained) {
                        if (System.nanoTime() < spinUntil) {
                            Thread.onSpinWait();
                        } else {
                            LockSupport.parkNanos("Fabrolia pumpUntilDone", 100_000L);
                        }
                    }
                }
            } finally {
                s.blockingCount--;
            }
        } else {
            // Off-main waiter (e.g. a region worker draining its player's advancement
            // evals). Spin briefly for the common short wait, then self-waking 25µs park
            // slices — the old unbounded onSpinWait loop burned a full core for the whole
            // wait, which was tolerable when only rare paths landed here but wasteful once
            // every region-ticked player drains through this branch each tick.
            long spinUntil = System.nanoTime() + 30_000L;
            long shutdownAbandonAt = 0;
            while (!(future != null ? future.isDone() : receipt.isDone())) {
                RegionScheduler.throwWorkerFailureIfAny();
                if (System.nanoTime() < spinUntil) {
                    Thread.onSpinWait();
                } else {
                    LockSupport.parkNanos("Fabrolia pumpUntilDone off-main", 25_000L);
                }
                // Shutdown liveness (audit #11): once the region scheduler is
                // torn down, the awaited completion owner may be dead — an
                // unbounded park here is what wedged the pool join and hung
                // /stop. Give in-flight completions a 5s grace, then abandon
                // LOUDLY; the join hardening reports any worker still stuck.
                if (!com.axalotl.fabrolia.common.regions.RegionScheduler.isActive()) {
                    long now = System.nanoTime();
                    if (shutdownAbandonAt == 0) {
                        shutdownAbandonAt = now + 5_000_000_000L;
                    } else if (now > shutdownAbandonAt) {
                        throw new IllegalStateException(
                                "pumpUntilDone lost its completer after scheduler stop on "
                                        + Thread.currentThread().getName());
                    }
                }
            }
        }
        RegionScheduler.throwWorkerFailureIfAny();
        if (receipt != null) {
            receipt.throwIfFailed();
            return;
        }
        try {
            future.join();
        } catch (CompletionException wrapped) {
            Throwable original = wrapped.getCause();
            ParallelProcessor.<RuntimeException>fabrolia$throwUnchecked(
                    original == null ? wrapped : original);
        }
    }

    /**
     * Region-mode gate: which entities still MUST tick on the server thread even though
     * their region task reached them. This includes the complete root/passenger tree
     * whenever it can invoke a ServerPlayer tick, the API contract (modded entities
     * without {@code @FabroliaCompatible} may assume main-thread ticks), and the manual
     * UUID blacklist.
     * The class blacklist / vanilla-namespace synchronizedEntities entries are deliberately
     * NOT honored here: that machinery exists to SERIALIZE classes that race on the shared
     * pool, and a region task is already serial for its area — the protection is structural
     * in region mode (items/minecarts/projectiles tick in vanilla order within their
     * region; cross-thread item/XP consumption shares each entity's stable
     * SyncItemPickup guard with its tick and merge paths. Portal capture
     * declines busy guards instead of waiting while retaining an island claim).
     */
    public static boolean requiresMainThread(Entity entity) {
        // Wither tracking membership and visibility teardown mutate its boss
        // event; keep the whole root/passenger tree on Main so those callbacks
        // cannot overlap the wither's AI tick. Item frames tick on their island:
        // the frame's own tick touches only its attachment block, and the shared
        // map data a framed map updates is guarded per map (MapItemSavedDataMixin).
        if (containsMainOwnedTrackingEntity(entity)) return true;
        if (containsForcedMainEntity(entity)) return true;
        if (containsServerPlayerTick(entity)) return true;
        return containsUnsupportedEntity(entity);
    }

    /**
     * True when ticking {@code entity} can recursively invoke a {@link ServerPlayer} tick.
     * Vanilla ticks passengers from their root vehicle, so checking only the root's class
     * would still let mounted players inherit a worker venue. This must remain an uncached
     * per-dispatch check because the passenger tree is mutable.
     */
    public static boolean containsServerPlayerTick(Entity entity) {
        if (entity instanceof ServerPlayer) return true;
        // getPassengers() is final in exact 26.2. Avoid overridable convenience
        // predicates whose modded false result could hide a real passenger subtree.
        for (Entity passenger : entity.getPassengers()) {
            if (containsServerPlayerTick(passenger)) return true;
        }
        return false;
    }

    /**
     * True when {@code entity} or any passenger is asleep in a bed whose chunk is not
     * owned by {@code owner} (the island whose unit is ticking the tree). Vanilla
     * {@code LivingEntity.tick} reads the remembered bed ({@code checkBedExists}) and
     * {@code LivingEntity.stopSleeping} — reached from Brain {@code SleepInBed.stop},
     * {@code hurtServer} and {@code die} — writes {@code BedBlock.OCCUPIED=false}
     * there, filtered only by {@code getSleepingPos().filter(level::hasChunkAt)}. A
     * villager teleported away from its bed therefore mutates a foreign island's chunk
     * from its own tick; that tick must run under the global fence
     * ({@link RegionScheduler#queueMainSyncFencedTick}) exactly like any other
     * cross-owner entity body. Only a present sleeping position pays the owner lookup;
     * {@code getSleepingPos()} returns the stored Optional without allocating.
     */
    public static boolean sleepsOutsideIsland(Entity entity,
                                              com.axalotl.fabrolia.common.regions.RegionizedServer runtime,
                                              com.axalotl.fabrolia.common.regions.IslandRegistry.PIsland owner) {
        if (runtime == null) return false;
        if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
            java.util.Optional<net.minecraft.core.BlockPos> bed = living.getSleepingPos();
            if (bed.isPresent()
                    && runtime.islandOwnerOfChunk(net.minecraft.world.level.ChunkPos.pack(bed.get())) != owner) {
                return true;
            }
        }
        for (Entity passenger : entity.getPassengers()) {
            if (sleepsOutsideIsland(passenger, runtime, owner)) return true;
        }
        return false;
    }

    public static boolean shouldTickSynchronously(Entity entity) {
        if (isShuttingDown || FabroliaConfig.disabled) return true;
        if (containsMainOwnedTrackingEntity(entity)) return true;
        if (containsForcedMainEntity(entity)) return true;

        // The rejected player-tick experiment was removed after the exact-26.2 audit. Include
        // root vehicles because vanilla recursively invokes passenger ticks there.
        if (containsServerPlayerTick(entity)) {
            return true;
        }

        return containsPoolSynchronizedEntity(entity);
    }

    private static boolean containsPoolSynchronizedEntity(Entity entity) {
        TickGuard guard = (TickGuard) entity;
        byte cached = guard.fabrolia$getSyncCache();
        if (cached < 0) {
            boolean sync = isBlocked(entity.getClass()) || entitySupportsFabroliaApi(entity);
            cached = sync ? (byte) 1 : (byte) 0;
            guard.fabrolia$setSyncCache(cached);
        }
        // Policy changes must affect already-live entities; never fold mutable
        // configuration (or passenger membership) into the lifetime cache.
        if (cached == 1 || FabroliaConfig.isEntitySynchronized(EntityType.getKey(entity.getType()))) {
            return true;
        }
        for (Entity passenger : entity.getPassengers()) {
            if (containsPoolSynchronizedEntity(passenger)) return true;
        }
        return false;
    }

    private static boolean containsUnsupportedEntity(Entity entity) {
        if (entitySupportsFabroliaApi(entity)) return true;
        for (Entity passenger : entity.getPassengers()) {
            if (containsUnsupportedEntity(passenger)) return true;
        }
        return false;
    }

    /** Hard-Main simulation state may also be reached through passenger recursion. */
    private static boolean containsMainOwnedTrackingEntity(Entity entity) {
        if (entity instanceof WitherBoss || entity instanceof EnderDragon) return true;
        for (Entity passenger : entity.getPassengers()) {
            if (containsMainOwnedTrackingEntity(passenger)) return true;
        }
        return false;
    }

    private static boolean containsForcedMainEntity(Entity entity) {
        if (!BLACKLISTED_ENTITIES.isEmpty()
                && BLACKLISTED_ENTITIES.contains(entity.getUUID())) return true;
        for (Entity passenger : entity.getPassengers()) {
            if (containsForcedMainEntity(passenger)) return true;
        }
        return false;
    }

    /** Permanent safety fallback for a live entity whose island publication failed. */
    public static void forceMainThread(Entity entity) {
        if (entity != null) BLACKLISTED_ENTITIES.add(entity.getUUID());
    }

    /** Exact direct-entity predicate used by the A5 player admission gate. */
    public static boolean isForcedMainThread(Entity entity) {
        return entity != null && !BLACKLISTED_ENTITIES.isEmpty()
                && BLACKLISTED_ENTITIES.contains(entity.getUUID());
    }

    /** True on the current thread only while inside a REAL entity tick — lets the
     *  setOldPosAndRot hook distinguish a genuine tick from the teleport's own
     *  setOldPosAndRot call (which would otherwise pollute the cart trace ring
     *  buffer with a spurious "tick" on the teleporting thread; task #26). */
    public static final ThreadLocal<Boolean> IN_ENTITY_TICK = ThreadLocal.withInitial(() -> false);

    public static void tickEntity(ServerLevel world, Entity entity, boolean fabrolia) {
        tickEntityVanillaGuarded(world, entity, fabrolia);
    }

    /**
     * Exact vanilla crash boundary for every regional entity/root tick.
     * {@link net.minecraft.world.level.Level#guardEntityTick}
     * catches every Throwable, creates the vanilla "Ticking entity" report and throws it.
     * Mandatory tick-frame restoration remains in a finally block. Portal publication
     * and success metrics occur only if the complete guarded body succeeded.
     */
    public static void tickEntityVanillaGuarded(ServerLevel world, Entity entity, boolean fabrolia) {
        if (((TickGuard) entity).fabrolia$tickLease().isArrivalPending()) return;
        long start = System.nanoTime();
        boolean prevInTick = IN_ENTITY_TICK.get();
        IN_ENTITY_TICK.set(Boolean.TRUE);
        Throwable failure = null;
        try {
            RegionScheduler.throwWorkerFailureIfAny();
            world.guardEntityTick(world::tickNonPassenger, entity);
            // The guarded body can return normally after a peer has already
            // selected the generation-wide W.  Metrics and portal publication
            // are success-only tails, so observe W before either becomes visible.
            RegionScheduler.throwWorkerFailureIfAny();
        } catch (Throwable thrown) {
            // The guarded vanilla body has entered. Close every Fabrolia start
            // gate now, before frame restoration or portal cleanup can expose a
            // window in which another simulation root starts after this failure.
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        } finally {
            try {
                IN_ENTITY_TICK.set(prevInTick);
            } catch (Throwable cleanup) {
                failure = fabrolia$publishMergedFailure(failure, cleanup);
            }
            if (failure == null) {
                try {
                    AtomicLong elapsedMetric = null;
                    AtomicLong countMetric = null;
                    AtomicLong totalElapsedMetric = null;
                    AtomicLong totalCountMetric = null;
                    long elapsed = 0L;
                    if (RECORDING_TICKS_LEFT.get() > 0) {
                        EntityType<?> type = entity.getType();
                        elapsed = System.nanoTime() - start;
                        if (fabrolia) {
                            elapsedMetric = FABROLIA_TICK_TIME_NS.computeIfAbsent(
                                    type, _ -> new AtomicLong());
                            countMetric = FABROLIA_TICK_COUNT.computeIfAbsent(
                                    type, _ -> new AtomicLong());
                            totalElapsedMetric = TOTAL_FABROLIA_TIME_NS;
                            totalCountMetric = TOTAL_FABROLIA_COUNT;
                        } else {
                            elapsedMetric = TICK_TIME_NS.computeIfAbsent(
                                    type, _ -> new AtomicLong());
                            countMetric = TICK_COUNT.computeIfAbsent(
                                    type, _ -> new AtomicLong());
                            totalElapsedMetric = TOTAL_SYNC_TIME_NS;
                            totalCountMetric = TOTAL_SYNC_COUNT;
                        }
                    }
                    final AtomicLong elapsedCommit = elapsedMetric;
                    final AtomicLong countCommit = countMetric;
                    final AtomicLong totalElapsedCommit = totalElapsedMetric;
                    final AtomicLong totalCountCommit = totalCountMetric;
                    final long elapsedNanos = elapsed;
                    RegionScheduler.commitSuccessIfHealthy(() -> {
                        if (elapsedCommit != null) {
                            elapsedCommit.addAndGet(elapsedNanos);
                            countCommit.incrementAndGet();
                            totalElapsedCommit.addAndGet(elapsedNanos);
                            totalCountCommit.incrementAndGet();
                        }
                    });
                    if (!prevInTick) {
                        // Players finish their own tick visibility here; non-player
                        // departures remain visible through the enclosing claim.
                        NetherPortalTransfers.completeTickLocalDeparture(entity);
                    }
                } catch (Throwable cleanup) {
                    failure = fabrolia$publishMergedFailure(failure, cleanup);
                }
            }
            if (!prevInTick) {
                if (failure != null) {
                    try {
                        NetherPortalTransfers.abortTickLocalTransfers();
                    } catch (Throwable cleanup) {
                        failure = fabrolia$publishMergedFailure(failure, cleanup);
                    }
                }
            }
        }
        if (failure != null) ParallelProcessor.<RuntimeException>fabrolia$throwUnchecked(failure);
    }

    /** Publish the first failure immediately; later cleanup is suppressed context only. */
    private static Throwable fabrolia$publishMergedFailure(Throwable primary,
                                                            Throwable secondary) {
        return RegionScheduler.publishFatalAndSelectWinner(
                fabrolia$mergeFailure(primary, secondary));
    }

    private static Throwable fabrolia$mergeFailure(Throwable primary, Throwable cleanup) {
        if (primary == null) return cleanup;
        if (cleanup != null && primary != cleanup) {
            try {
                primary.addSuppressed(cleanup);
            } catch (Throwable ignored) {
                // Cleanup diagnostics cannot replace the exact vanilla primary.
            }
        }
        return primary;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void fabrolia$throwUnchecked(Throwable thrown) throws T {
        throw (T) thrown;
    }

    /**
     * Loader lifecycle boundary.  Both runtimes own accepted vanilla work, so a
     * failure while stopping the region runtime may not skip the flat/player
     * runtime cleanup.  The first failure remains the exact object rethrown to
     * the loader; later cleanup failures are diagnostics on that object only.
     */
    public static void stopServerRuntime() {
        Throwable failure = null;
        // SERVER_STOPPING runs before vanilla disconnect/save.  Close any flat
        // advancement tail even when region threading was never enabled; a live
        // RegionScheduler performs further fixed-point passes while it drains.
        try {
            AdvancementBuffer.drainShutdownPassIfPending();
        } catch (Throwable primary) {
            failure = primary;
        }
        try {
            RegionScheduler.stop();
        } catch (Throwable primary) {
            failure = fabrolia$mergeFailure(failure, primary);
        }
        try {
            stop();
        } catch (Throwable cleanup) {
            failure = fabrolia$mergeFailure(failure, cleanup);
        }
        if (failure != null) {
            ParallelProcessor.<RuntimeException>fabrolia$throwUnchecked(failure);
        }
    }

    public static void stop() {
        isShuttingDown = true;
        Throwable failure = null;

        // The exact-ordinal player actor is a serial extension of the server turn.
        // Never tear down shared pools underneath an accepted player body.
        try {
            com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2.shutdownActor();
        } catch (Throwable cleanup) {
            failure = fabrolia$mergeFailure(failure, cleanup);
        }

        ExecutorService stoppingExecutor = executor;
        if (stoppingExecutor != null) {
            LOGGER.info("Waiting for Fabrolia pool to shutdown...");
            boolean shutdownStarted = false;
            try {
                stoppingExecutor.shutdown();
                shutdownStarted = true;
            } catch (Throwable cleanup) {
                failure = fabrolia$mergeFailure(failure, cleanup);
            }
            boolean interrupted = Thread.interrupted();
            long nextDiagnostic = System.nanoTime() + 15_000_000_000L;
            if (shutdownStarted || stoppingExecutor.isShutdown()) {
                while (!stoppingExecutor.isTerminated()) {
                    try {
                        stoppingExecutor.awaitTermination(1L, TimeUnit.SECONDS);
                    } catch (InterruptedException cleanup) {
                        interrupted = true;
                        failure = fabrolia$mergeFailure(failure, cleanup);
                    }
                    long now = System.nanoTime();
                    if (!stoppingExecutor.isTerminated() && now >= nextDiagnostic) {
                        LOGGER.error("Fabrolia pool is still draining accepted work during shutdown; "
                                + "no task will be cancelled");
                        nextDiagnostic = now + 15_000_000_000L;
                    }
                }
            } else {
                failure = fabrolia$mergeFailure(failure, new IllegalStateException(
                        "Fabrolia pool rejected shutdown and still accepts work"));
            }
            if (interrupted) Thread.currentThread().interrupt();
        }

        // The flat executor join is the final producer edge in regions-off mode.
        // Apply its retained advancement results on physical Main, then prove that
        // no ServerPlayer/ServerLevel carrier survives into an in-JVM restart.
        if (failure == null) {
            try {
                RegionScheduler.throwWorkerFailureIfAny();
                NetherPortalTransfers.flushQuiescent();
                AdvancementBuffer.drainShutdownPassIfPending();
                AdvancementBuffer.verifyEmptyAfterShutdown();
            } catch (Throwable cleanup) {
                failure = fabrolia$mergeFailure(failure, cleanup);
            }
        }
        if (failure != null && (stoppingExecutor == null || stoppingExecutor.isTerminated())) {
            try {
                failure = NetherPortalTransfers.cancelFailedGeneration(failure);
            } catch (Throwable cleanup) {
                failure = fabrolia$mergeFailure(failure, cleanup);
            }
        }

        try {
            FabroliaConfig.clearCaches();
        } catch (Throwable cleanup) {
            failure = fabrolia$mergeFailure(failure, cleanup);
        }
        try {
            BLACKLISTED_ENTITIES.clear();
        } catch (Throwable cleanup) {
            failure = fabrolia$mergeFailure(failure, cleanup);
        }
        try {
            NetherPortalTransfers.shutdown();
        } catch (Throwable cleanup) {
            failure = fabrolia$mergeFailure(failure, cleanup);
        }
        try {
            resetEntityTickStats();
        } catch (Throwable cleanup) {
            failure = fabrolia$mergeFailure(failure, cleanup);
        }
        if (failure != null) {
            ParallelProcessor.<RuntimeException>fabrolia$throwUnchecked(failure);
        }
    }
}
