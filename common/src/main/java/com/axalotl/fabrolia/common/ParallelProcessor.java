package com.axalotl.fabrolia.common;

import com.axalotl.fabrolia.api.utils.FabroliaCompatible;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.axalotl.fabrolia.common.parallelised.utils.PortalTeleportationManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.Shulker;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.Map;

import static com.axalotl.fabrolia.common.utils.TickStats.*;
import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementBuffer;
import com.axalotl.fabrolia.common.regions.RegionViz;
import com.axalotl.fabrolia.common.regions.RegionScheduler;

public final class ParallelProcessor {

    private ParallelProcessor() {}

    public interface TickGuard {
        /** task #19: entity has a portal teleport queued on main — it must
         *  stop participating in simulation until main resolves it (vanilla:
         *  the entity would already be gone the tick portal-time fired). */
        default boolean fabrolia$isPortalPending() { return false; }
        default void fabrolia$setPortalPending(boolean pending) {}

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

        boolean fabrolia$tryBeginTick();
        void fabrolia$endTick();
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

    /**
     * Region dense-fan-out ordered-lane gate: the flat-pool class blacklist applied to
     * INTRA-region parallelism. {@link #requiresMainThread} deliberately skips the class
     * blacklist in region mode because "a region task is already serial for its area" —
     * but the dense fan-out ({@code RegionizedServer.parallelTickOwned}) breaks exactly
     * that assumption by ticking one region's entities on concurrent helper batches.
     * These classes write OTHER entities' state when they tick (minecart/boat mutual
     * momentum exchange in {@code AbstractMinecart.pushOtherMinecart} writes BOTH carts'
     * deltaMovement, projectile hits mutate their target, shulkers/falling blocks write
     * world state), so two batches racing a touching pair garble vanilla motion
     * arithmetic — same failure class as the 2026-06-14 item-sorter race, observed
     * 2026-07-10 as minecarts bunching up and piling at a rail-splitter corner once
     * several carts circulate through a dense region. Players are excluded: region
     * player ticks have their own contract (see {@link #shouldTickSynchronously}).
     */
    public static boolean requiresOrderedLane(Entity entity) {
        return !(entity instanceof ServerPlayer) && isBlocked(entity.getClass());
    }

    private static final java.util.Map<Class<?>, Boolean> FABROLIA_API_SYNC_CACHE = new ConcurrentHashMap<>();

    public static boolean entitySupportsFabroliaApi(Entity entity) {
        Class<?> cls = entity.getClass();
        Boolean cached = FABROLIA_API_SYNC_CACHE.get(cls);
        if (cached != null) return cached;
        boolean result = !"minecraft".equals(EntityType.getKey(entity.getType()).getNamespace()) && !cls.isAnnotationPresent(FabroliaCompatible.class);
        FABROLIA_API_SYNC_CACHE.put(cls, result);
        return result;
    }

    private static final java.util.Map<Class<?>, Boolean> SYNC_CLASS_CACHE = new ConcurrentHashMap<>();

    public static void clearCaches() {
        SYNC_CLASS_CACHE.clear();
        FABROLIA_API_SYNC_CACHE.clear();
    }

    private static final Map<String, Set<WeakReference<Thread>>> MC_THREAD_TRACKER = new ConcurrentHashMap<>();

    public static void setServer(MinecraftServer server) {
        ParallelProcessor.server = server;
        PortalTeleportationManager.init(server);
    }

    public static void setupThreadPool(int parallelism) {
        executor = createPool(parallelism);
    }


    private static ForkJoinPool createPool(int parallelism) {
        return new ForkJoinPool(parallelism, fabroliaWorkerFactory(), (t, e) -> LOGGER.error("Uncaught exception in thread {}", t.getName(), e),
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
            t.setUncaughtExceptionHandler((thr, ex) -> LOGGER.error("Uncaught exception in thread {}", thr.getName(), ex));
            registerThread("Fabrolia-Tick", t);
            return t;
        };
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
                || RegionScheduler.isRegionWorker(t);
    }

    public static int getPoolSize() {
        return executor instanceof ForkJoinPool pool ? pool.getParallelism() : 0;
    }

    public static void onEntityTickBatchEnd(long batchStartNanos) {
        if (RECORDING_TICKS_LEFT.get() > 0) {
            TOTAL_BATCH_WALL_NS.add(System.nanoTime() - batchStartNanos);
            RECORDING_TICKS_LEFT.decrementAndGet();
        }
        // Grants queued by region-worker player drains apply here, on main, before the
        // portal drain — reward functions/commands keep their main-thread assumptions
        // (MFC dispatch entry, loot tables, /tellraw), and a reward that moves a player
        // still sees its portal handoff processed in the same batch epilogue.
        AdvancementBuffer.applyDeferred();
        AdvancementBuffer.drainPendingFlushes();
        PortalTeleportationManager.drainPending();
        // Seal main's cross-dimension stage for the level just ticked (one
        // cohort per source entity batch, same contract as island units).
        var srcLevel = com.axalotl.fabrolia.common.regions.RegionScheduler.mainCurrentLevel;
        if (srcLevel != null) {
            if ((srcLevel.getGameTime() & 255) == 17
                    && srcLevel instanceof com.axalotl.fabrolia.common.parallelised.utils.FabroliaNavAudit audit) {
                audit.fabrolia$sweepNavLeaks();
            }
            var srcRs = com.axalotl.fabrolia.common.regions.RegionScheduler.forLevel(srcLevel);
            if (srcRs != null) {
                srcRs.sealGlobalStage();
            }
        }
        // Rerouted region tasks (claims that found their region already DONE
        // this epoch) must land same-tick — a leftover here would slide a
        // phase or race next tick's island slices on main.
        RegionScheduler.drainMainSafeNow();
        // Region-boundary debug particles (/fabrolia regions show). One isEmpty check when off.
        if (!RegionViz.isEmpty()) {
            RegionViz.tick();
        }
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
     * where main holds no claims and no global-lane phase is in flight —
     * i.e. the sprint unit-barrier loop, which is exactly the drive-head
     * serialization point re-entered mid-tick.
     */
    public static void pumpUntilDone(CompletableFuture<?> future, boolean serviceRoutedCommands) {
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
                future.whenComplete((v, t) -> LockSupport.unpark(pumpWaiter));
                // Spin-then-park: most barriers through here are SHORT — an advancement
                // drain's eval batch or a nearly-complete entity batch, done in tens of µs.
                // The previous unconditional park(100µs) overshot by up to a full slice per
                // barrier; at 50 players × one drain each per tick that alone was ~5ms/tick
                // of pure park overshoot (drain = 10.24% of server thread in the 2026-06-09
                // 50-bot profile). Spinning ~40µs first lets short barriers complete at full
                // poll rate; long barriers pay the spin once (~0.3% of a 13ms batch) and
                // then park in 100µs slices exactly as before.
                long spinUntil = System.nanoTime() + 40_000L;
                while (!future.isDone()) {
                    // Region mode: tick entities the region tasks routed to main (modded
                    // API-contract, UUID blacklist) WHILE the regions run — same overlap
                    // the old syncDeferred loop had, just demand-driven. No-op (one CLQ
                    // poll) when the queue is empty or regions are off.
                    boolean drained = RegionScheduler.drainMainSyncTicks();
                    // Main-steal: instead of parking through the barrier, chew one unit
                    // of region work per iteration (main-safe global jobs, ready-queue
                    // drains via try-claim-once — never a claim-spin, see RegionWorkerPool
                    // .stealOne). Main was parked 17.6% of the thread at this barrier on
                    // the 50-bot load — that's a 17th worker going to waste.
                    if (RegionScheduler.stealOneRegionTask()) {
                        drained = true;
                    }
                    for (ServerLevel level : s.getAllLevels()) {
                        if (level.getChunkSource().mainThreadProcessor.pollTask()) {
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
            while (!future.isDone()) {
                if (System.nanoTime() < spinUntil) {
                    Thread.onSpinWait();
                } else {
                    LockSupport.parkNanos("Fabrolia pumpUntilDone off-main", 25_000L);
                }
                // Shutdown liveness (audit #11): once the region scheduler is
                // torn down, this future's completer may be dead — an
                // unbounded park here is what wedged the pool join and hung
                // /stop. Give in-flight completions a 5s grace, then abandon
                // LOUDLY; the join hardening reports any worker still stuck.
                if (!com.axalotl.fabrolia.common.regions.RegionScheduler.isActive()) {
                    long now = System.nanoTime();
                    if (shutdownAbandonAt == 0) {
                        shutdownAbandonAt = now + 5_000_000_000L;
                    } else if (now > shutdownAbandonAt) {
                        LOGGER.error("pumpUntilDone: abandoning incomplete future on {} — "
                                + "scheduler stopped and the completer never finished",
                                Thread.currentThread().getName());
                        return;
                    }
                }
            }
        }
        if (future.isCompletedExceptionally()) {
            try { future.join(); }
            catch (Exception e) { LOGGER.error("Fabrolia batch failed", e); }
        }
    }

    /**
     * Region-mode gate: which entities still MUST tick on the server thread even though
     * their region task reached them. Only the API contract (modded entities without
     * {@code @FabroliaCompatible} may assume main-thread ticks) and the manual UUID blacklist.
     * The class blacklist / vanilla-namespace synchronizedEntities entries are deliberately
     * NOT honored here: that machinery exists to SERIALIZE classes that race on the shared
     * pool, and a region task is already serial for its area — the protection is structural
     * in region mode (items/minecarts/projectiles tick in vanilla order within their
     * region; the cross-thread consumption paths are covered by the item-region-keyed
     * SyncItemPickup monitor (FabroliaLocks.itemLockOr) in ItemEntityMixin /
     * HopperBlockEntityMixin and the global lock in ExperienceOrbMixin).
     */
    public static boolean requiresMainThread(Entity entity) {
        if (!BLACKLISTED_ENTITIES.isEmpty() && BLACKLISTED_ENTITIES.contains(entity.getUUID())) return true;
        return entitySupportsFabroliaApi(entity);
    }

    public static boolean shouldTickSynchronously(Entity entity) {
        if (isShuttingDown || FabroliaConfig.disabled) return true;
        if (!BLACKLISTED_ENTITIES.isEmpty() && BLACKLISTED_ENTITIES.contains(entity.getUUID())) return true;

        // Players: decided per-call, never via the per-entity byte cache — the answer
        // flips with the region scheduler's runtime on/off toggle and there are at most
        // a few hundred players, so the instanceof costs nothing while a stale cached
        // route would strand players on the wrong path until relog. Ticking a player on
        // a region worker is safe because: region workers count as server execution
        // threads (vanilla same-thread checks pass), dimension changes defer through
        // PortalTeleportationManager to batch end, AdvancementBuffer frames are
        // per-thread with grants applied on main, and item/orb pickup paths take the
        // item-region-keyed SyncItemPickup monitor (ItemEntityMixin) / the global orb
        // lock (ExperienceOrbMixin). Outside region mode players stay on main —
        // BLOCKED_BASE_CLASSES below would say the same.
        if (entity instanceof ServerPlayer) {
            // A5 production hardening (2026-07-12): with async islands live,
            // players are MAIN-OWNED — packet application and the player's
            // entity tick execute on the same thread, eliminating the torn
            // player-state class (movement apply vs worker travel physics)
            // wholesale. The player's ISLAND still simulates at its own TPS;
            // the player character itself rides the W lane.
            if (com.axalotl.fabrolia.common.regions.RegionScheduler.anyAsyncActive()) {
                // EXPERIMENTAL regional player ticking (task #30): eligible real
                // players simulate on their island worker (doTick from the tick()
                // wrap tail) with simulation packets routed into the same island
                // lane — restoring the pre-A5 region path WITH the torn-state fix
                // A5 existed for. Ineligible (or feature off) = A5 main-owned.
                if (com.axalotl.fabrolia.common.regions.RegionalPlayerTicks
                        .regionEligible((ServerPlayer) entity)) {
                    return false;
                }
                return true;
            }
            return !(FabroliaConfig.enableRegionPlayerTicks
                    && FabroliaConfig.enableRegionThreading
                    && RegionScheduler.isActive());
        }

        TickGuard guard = (TickGuard) entity;
        byte cached = guard.fabrolia$getSyncCache();
        if (cached >= 0) return cached == 1;

        Class<?> cls = entity.getClass();
        boolean sync = isBlocked(cls)
                || entitySupportsFabroliaApi(entity)
                || FabroliaConfig.isEntitySynchronized(EntityType.getKey(entity.getType()));
        guard.fabrolia$setSyncCache(sync ? (byte)1 : (byte)0);
        return sync;
    }

    /** True on the current thread only while inside a REAL entity tick — lets the
     *  setOldPosAndRot hook distinguish a genuine tick from the teleport's own
     *  setOldPosAndRot call (which would otherwise pollute the cart trace ring
     *  buffer with a spurious "tick" on the teleporting thread; task #26). */
    public static final ThreadLocal<Boolean> IN_ENTITY_TICK = ThreadLocal.withInitial(() -> false);

    public static void tickEntity(ServerLevel world, Entity entity, boolean fabrolia) {
        long start = System.nanoTime();
        boolean prevInTick = IN_ENTITY_TICK.get();
        IN_ENTITY_TICK.set(Boolean.TRUE);
        try {
            world.tickNonPassenger(entity);
        } catch (Exception e) {
            com.axalotl.fabrolia.common.regions.ThrottledLog.error(LOGGER, "tickEntity", e,
                    "Error during {} tick. Entity: {}, UUID: {}",
                    fabrolia ? "fabrolia" : "sync", entity.getType(), entity.getUUID());
            if (fabrolia) {
                SYNC_CLASS_CACHE.put(entity.getClass(), Boolean.TRUE);
            }
        } finally {
            // Restore the frame and release staged transfers FIRST. These used to
            // sit after the metrics block, so any throw there (e.g. OOM inside
            // computeIfAbsent) left IN_ENTITY_TICK true on a pooled thread and
            // stranded the entity's portal transfer forever. Audit 2026-07-26.
            IN_ENTITY_TICK.set(prevInTick);
            // audit11: inside a region-claim frame, per-entity publication is
            // exactly what let main's fixed-rate drain bisect one portal
            // activation into two batches — the frame publishes the whole
            // claim's transfers at once, in the region task's finally, at
            // claim release. Outside a frame (main, flat pool, help batches)
            // the per-entity cadence is unchanged.
            if (!prevInTick && !PortalTeleportationManager.inRegionClaimFrame()) {
                PortalTeleportationManager.publishTickLocalTransfers();
            }
            if (RECORDING_TICKS_LEFT.get() > 0) {
                EntityType<?> type = entity.getType();
                long elapsed = System.nanoTime() - start;
                if (fabrolia) {
                    FABROLIA_TICK_TIME_NS.computeIfAbsent(type, _ -> new LongAdder()).add(elapsed);
                    FABROLIA_TICK_COUNT.computeIfAbsent(type, _ -> new LongAdder()).increment();
                    TOTAL_FABROLIA_TIME_NS.add(elapsed);
                    TOTAL_FABROLIA_COUNT.increment();
                } else {
                    TICK_TIME_NS.computeIfAbsent(type, _ -> new LongAdder()).add(elapsed);
                    TICK_COUNT.computeIfAbsent(type, _ -> new LongAdder()).increment();
                    TOTAL_SYNC_TIME_NS.add(elapsed);
                    TOTAL_SYNC_COUNT.increment();
                }
            }
        }
    }

    public static void stop() {
        isShuttingDown = true;

        if (executor != null) {
            LOGGER.info("Waiting for Fabrolia pool to shutdown...");
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60L, TimeUnit.SECONDS)) {
                    LOGGER.warn("Fabrolia pool did not terminate within 60 seconds, forcing shutdown");
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warn("Interrupted while waiting for thread pool shutdown", e);
                executor.shutdownNow();
            }
        }

        FabroliaConfig.clearCaches();
        BLACKLISTED_ENTITIES.clear();
        PortalTeleportationManager.shutdown();
        resetEntityTickStats();
    }
}
