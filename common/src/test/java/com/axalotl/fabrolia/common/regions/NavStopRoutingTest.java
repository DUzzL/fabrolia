package com.axalotl.fabrolia.common.regions;

import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task #40 (audit finding #2) regression pins for the owner-routed
 * {@code PathNavigation.stop()} — the main-side mutator door the task-#32 fix
 * left open ({@code TeleportCommand.performTeleport → getNavigation().stop()},
 * {@code PiglinAi.stopWalking} from the interact packet,
 * {@code LivingEntity.randomTeleport}). Proven against the PRODUCTION routing
 * core {@link NavRecompute#stopDeferred} the {@code stop} HEAD injector calls:
 *
 * <pre>
 *   Fabrolia-Region-Worker-N:  synchronized nav.tick() → getGroundY →
 *       getChunk → ChunkBridgeWait (parked, HOLDING the nav monitor)
 *   Server thread:  /tp → performTeleport → mob.getNavigation().stop()
 *       — pre-fix: BLOCKED on the monitor (cannot pump mainThreadProcessor,
 *         the worker's chunk future never completes → watchdog), or, with the
 *         blanket bit merely removed, an unguarded cross-thread path=null
 *         racing the owner's synchronized tick.
 * </pre>
 */
class NavStopRoutingTest {

    private static final sun.misc.Unsafe UNSAFE;

    static {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (sun.misc.Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static PathNavigation nav() throws InstantiationException {
        // Real vanilla type, Unsafe-allocated (no ctor, no field init): the
        // routing core only uses it as the coalesce-key identity.
        return (PathNavigation) UNSAFE.allocateInstance(GroundPathNavigation.class);
    }

    private static EpochDomain islandDomain(IslandRegistry.PIsland owner) {
        EpochDomain d = new EpochDomain();
        d.owner = owner;
        d.unitGameTime = 1;
        return d;
    }

    private static void serviceOnOwnerWorker(IslandRegistry.PIsland island, EpochDomain d)
            throws Exception {
        AtomicReference<Throwable> err = new AtomicReference<>();
        RegionWorkerThread t = new RegionWorkerThread(() -> {
            try {
                CommandShards.serviceIslandTasks(island);
            } catch (Throwable e) {
                err.set(e);
            }
        }, "fabrolia-test-stop-owner-coordinator");
        t.currentDomain = d;
        t.start();
        t.join(10_000);
        assertFalse(t.isAlive(), "owner coordinator wedged");
        if (err.get() != null) throw new AssertionError(err.get());
    }

    @AfterEach
    void resetServer() throws ReflectiveOperationException {
        Field f = RegionScheduler.class.getDeclaredField("server");
        f.setAccessible(true);
        f.set(null, null);
        IslandCommands.releaseAllExcept();
    }

    /**
     * The deadlock graph, replayed: a worker holds the nav monitor parked on
     * a condition only main's forward progress can satisfy; main-side stop
     * must complete WITHOUT entering the monitor, deferring to the owner's
     * lane, where the stop runs once the monitor is free.
     */
    @Test
    void mainSideStopMustNotEnterNavMonitorHeldByBridgeParkedWorker() throws Exception {
        IslandRegistry.PIsland islandB = new IslandRegistry.PIsland(31, 0);
        EpochDomain domainB = islandDomain(islandB);
        PathNavigation nav = nav();

        Object navMonitor = new Object();
        CountDownLatch workerHoldsMonitor = new CountDownLatch(1);
        CountDownLatch mainSideDone = new CountDownLatch(1); // the "chunk future"
        AtomicBoolean stopped = new AtomicBoolean();

        RegionWorkerThread worker = new RegionWorkerThread(() -> {
            synchronized (navMonitor) {
                workerHoldsMonitor.countDown();
                try {
                    if (!mainSideDone.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("main never progressed — deadlock");
                    }
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }
        }, "fabrolia-test-stop-worker");
        worker.currentDomain = domainB;
        worker.start();
        assertTrue(workerHoldsMonitor.await(5, TimeUnit.SECONDS));

        // What the pre-fix (synchronized) stop body amounted to cross-thread.
        Runnable deferredStop = () -> {
            synchronized (navMonitor) {
                stopped.set(true);
            }
        };

        AtomicBoolean deferred = new AtomicBoolean();
        Thread mainSide = new Thread(
                () -> deferred.set(NavRecompute.stopDeferred(null, islandB, nav, deferredStop)),
                "test-main-side-stop");
        mainSide.start();
        mainSide.join(5_000);
        assertFalse(mainSide.isAlive(),
                "stop routing blocked on the nav monitor held by a bridge-parked "
                        + "worker — the task-#32 deadlock edge through the stop door");
        assertTrue(deferred.get(), "cross-context stop must report DEFERRED (injector cancels)");
        assertFalse(stopped.get(), "the stop must not run cross-thread");
        synchronized (islandB.pendingIslandTasks) {
            assertEquals(1, islandB.pendingIslandTasks.size(),
                    "deferred stop must sit in the owner island's lane");
        }

        mainSideDone.countDown();
        worker.join(5_000);
        assertFalse(worker.isAlive(), "worker never released the nav monitor");

        serviceOnOwnerWorker(islandB, domainB);
        assertTrue(stopped.get(), "owner never executed the deferred stop");
    }

    /** Owner context (the mob's own worker) keeps vanilla's synchronous stop:
     *  the core reports NOT-deferred (injector falls through to path = null)
     *  and queues nothing. */
    @Test
    void ownerContextFallsThroughToVanillaStop() throws Exception {
        IslandRegistry.PIsland island = new IslandRegistry.PIsland(33, 0);
        EpochDomain d = islandDomain(island);
        Region r = (Region) UNSAFE.allocateInstance(Region.class);
        r.domain = d;
        PathNavigation nav = nav();

        AtomicBoolean deferred = new AtomicBoolean(true);
        AtomicReference<Throwable> err = new AtomicReference<>();
        RegionWorkerThread t = new RegionWorkerThread(() -> {
            try {
                deferred.set(NavRecompute.stopDeferred(null, island, nav,
                        () -> { throw new AssertionError("owner context must not defer"); }));
            } catch (Throwable e) {
                err.set(e);
            }
        }, "fabrolia-test-stop-owner");
        t.currentRegion = r;
        t.start();
        t.join(5_000);
        if (err.get() != null) throw new AssertionError(err.get());
        assertFalse(deferred.get(),
                "owner context must fall through to the vanilla body (synchronous stop)");
        synchronized (island.pendingIslandTasks) {
            assertEquals(0, island.pendingIslandTasks.size());
        }
    }

    /** One pending stop per navigation (missed-cycle semantics, StopKey record):
     *  a command spamming /tp on the same mob must not flood the owner lane;
     *  a second navigation still gets its own slot. */
    @Test
    void deferredStopsCoalescePerNavigation() throws Exception {
        IslandRegistry.PIsland island = new IslandRegistry.PIsland(35, 0);
        PathNavigation navA = nav();
        PathNavigation navB = nav();

        assertTrue(NavRecompute.stopDeferred(null, island, navA, () -> {}));
        assertTrue(NavRecompute.stopDeferred(null, island, navA, () -> {}));
        assertTrue(NavRecompute.stopDeferred(null, island, navB, () -> {}));
        synchronized (island.pendingIslandTasks) {
            assertEquals(2, island.pendingIslandTasks.size(),
                    "same nav coalesces to one pending stop; distinct navs get their own");
        }
    }
}
