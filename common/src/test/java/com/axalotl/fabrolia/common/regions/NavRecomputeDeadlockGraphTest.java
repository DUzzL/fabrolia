package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic regression pin for the 2026-07-28 production watchdog deadlock
 * (task #32), proven against the PRODUCTION routing core
 * {@link NavRecompute#dispatch}:
 *
 * <pre>
 *   Fabrolia-Region-Worker-10:  synchronized nav.tick() → getGroundY →
 *       getChunk → awaitMainThreadResult (parked, HOLDING the nav monitor)
 *   Server thread:  handleUseItemOn → setBlock → sendBlockUpdated →
 *       nav monitor entry (BLOCKED, cannot pump mainThreadProcessor)
 * </pre>
 *
 * <p>The graph is reproduced literally: a real {@link RegionWorkerThread}
 * holds the nav monitor and waits on a condition only the "main" side can
 * satisfy AFTER its block-update completes. The fixed dispatch must complete
 * the main side WITHOUT entering the monitor (deferring the recompute to the
 * owner island's lane); the reverted behavior (inline cross-thread
 * {@code recomputePath()}) blocks the main side on the monitor forever and
 * this test fails by timeout.
 */
class NavRecomputeDeadlockGraphTest {

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

    private static Region regionWithDomain(EpochDomain d) throws InstantiationException {
        Region r = (Region) UNSAFE.allocateInstance(Region.class);
        r.domain = d;
        return r;
    }

    private static EpochDomain islandDomain(IslandRegistry.PIsland owner) {
        EpochDomain d = new EpochDomain();
        d.owner = owner;
        d.unitGameTime = 1;
        return d;
    }

    /** Drain the island's pending lane on a real worker that owns it —
     *  exactly the unit-start venue that runs deferred recomputes. */
    private static void serviceOnOwnerWorker(IslandRegistry.PIsland island, EpochDomain d)
            throws Exception {
        AtomicReference<Throwable> err = new AtomicReference<>();
        RegionWorkerThread t = new RegionWorkerThread(() -> {
            try {
                CommandShards.serviceIslandTasks(island);
            } catch (Throwable e) {
                err.set(e);
            }
        }, "fabrolia-test-owner-coordinator");
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
     * The production deadlock graph, replayed against the fixed dispatch.
     * Old behavior = the main side calls the synchronized recompute inline →
     * blocks on the worker-held monitor before it can unblock the worker →
     * cycle (this test then fails its 5s completion assertion).
     */
    @Test
    void mainSideMustNotEnterNavMonitorHeldByBridgeParkedWorker() throws Exception {
        IslandRegistry.PIsland islandB = new IslandRegistry.PIsland(7, 0);
        EpochDomain domainB = islandDomain(islandB);

        Object navMonitor = new Object();
        CountDownLatch workerHoldsMonitor = new CountDownLatch(1);
        CountDownLatch mainSideDone = new CountDownLatch(1); // the "chunk future"
        AtomicBoolean recomputed = new AtomicBoolean();

        // Worker-10 analogue: nav.tick() under the nav monitor, parked on a
        // result only main's forward progress can produce.
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
        }, "fabrolia-test-worker-10");
        worker.currentDomain = domainB;
        worker.start();
        assertTrue(workerHoldsMonitor.await(5, TimeUnit.SECONDS));

        // Server-thread analogue: sendBlockUpdated reaches a nav owned by
        // island B. inline = the synchronized recomputePath (enters monitor).
        Runnable inline = () -> {
            synchronized (navMonitor) {
                recomputed.set(true);
            }
        };
        Runnable[] redispatch = new Runnable[1];
        redispatch[0] = () -> NavRecompute.dispatch(null, islandB, "nav-key", inline, redispatch[0]);

        Thread mainSide = new Thread(() -> redispatch[0].run(), "test-main-side");
        mainSide.start();
        mainSide.join(5_000);
        assertFalse(mainSide.isAlive(),
                "dispatch blocked on the nav monitor held by a bridge-parked worker "
                        + "— the production deadlock edge is back (cross-thread inline "
                        + "recomputePath)");

        assertFalse(recomputed.get(), "recompute must be DEFERRED, not run cross-thread");
        synchronized (islandB.pendingIslandTasks) {
            assertEquals(1, islandB.pendingIslandTasks.size(),
                    "deferred recompute must sit in the owner island's lane");
        }

        // Main completed its block update → the worker's "chunk future"
        // resolves → the monitor is released. No cycle.
        mainSideDone.countDown();
        worker.join(5_000);
        assertFalse(worker.isAlive(), "worker never released the nav monitor");

        // Unit start on the owner drains the lane; the monitor is free there.
        serviceOnOwnerWorker(islandB, domainB);
        assertTrue(recomputed.get(), "owner never executed the deferred recompute");
    }

    /** Same-island context (the mob's own coordinator/worker) keeps vanilla's
     *  synchronous recompute — no deferral, nothing queued. */
    @Test
    void ownerContextRunsInline() throws Exception {
        IslandRegistry.PIsland island = new IslandRegistry.PIsland(11, 0);
        EpochDomain d = islandDomain(island);
        Region r = regionWithDomain(d);

        AtomicBoolean ran = new AtomicBoolean();
        AtomicReference<Throwable> err = new AtomicReference<>();
        RegionWorkerThread t = new RegionWorkerThread(() -> {
            try {
                NavRecompute.dispatch(null, island, "k", () -> ran.set(true), () -> {});
            } catch (Throwable e) {
                err.set(e);
            }
        }, "fabrolia-test-owner");
        t.currentRegion = r;
        t.start();
        t.join(5_000);
        if (err.get() != null) throw new AssertionError(err.get());
        assertTrue(ran.get(), "owner context must recompute inline (vanilla semantics)");
        synchronized (island.pendingIslandTasks) {
            assertEquals(0, island.pendingIslandTasks.size());
        }
    }

    /**
     * Main holding the island's interaction claim = the island is quiescent
     * for main (no unit mid-flight can hold a nav monitor) — inline is both
     * safe and vanilla-synchronous. This preserves the block-place→instant-
     * repath behavior for mobs of the island the player is interacting with.
     */
    @Test
    void mainWithClaimRunsInlineMainWithoutClaimDefers() throws Exception {
        // Fake "this thread is the server thread".
        net.minecraft.server.MinecraftServer srv = (net.minecraft.server.MinecraftServer)
                UNSAFE.allocateInstance(com.axalotl.fabrolia.common.regions.TestDedicatedServer.class);
        Field st = net.minecraft.server.MinecraftServer.class.getDeclaredField("serverThread");
        st.setAccessible(true);
        st.set(srv, Thread.currentThread());
        Field sf = RegionScheduler.class.getDeclaredField("server");
        sf.setAccessible(true);
        sf.set(null, srv);

        RegionizedServer rs = (RegionizedServer) UNSAFE.allocateInstance(RegionizedServer.class);
        IslandRegistry.PIsland claimed = new IslandRegistry.PIsland(21, 0);
        IslandRegistry.PIsland other = new IslandRegistry.PIsland(22, 0);

        List<String> ran = new ArrayList<>();
        IslandCommands.mainClaim(claimed);
        try {
            NavRecompute.dispatch(rs, claimed, "a", () -> ran.add("claimed-inline"), () -> {});
            NavRecompute.dispatch(rs, other, "b", () -> ran.add("other-inline"), () -> ran.add("other-deferred"));
        } finally {
            IslandCommands.mainRelease(claimed);
        }

        assertEquals(List.of("claimed-inline"), ran,
                "claimed island → inline; unclaimed island → deferred to its lane "
                        + "(the production stack: main under island-X claim must not "
                        + "enter island-B nav monitors)");
        synchronized (other.pendingIslandTasks) {
            assertEquals(1, other.pendingIslandTasks.size());
        }
    }
}
