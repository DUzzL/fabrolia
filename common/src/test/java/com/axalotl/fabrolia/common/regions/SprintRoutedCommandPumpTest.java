package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task #33 regression pin — the 2026-07-28 production `/tick sprint` wedge,
 * replayed against the REAL {@link ParallelProcessor#pumpUntilDone}:
 *
 * <pre>
 *   Server thread:  sprint lockstep → pumpUntilDone(unit barrier of island X)
 *   Worker-6 (X's coordinator):  scheduled ticks → command block → routed
 *       command → parked in IslandCommands.routeCommandBlock until MAIN
 *       executes the routed batch
 * </pre>
 *
 * The self-nudge lands on the MinecraftServer task queue that pumpUntilDone
 * deliberately never polls (Carpet-reentry hazard), and the next drive-head
 * batch point cannot come while main is parked inside this tick — so the
 * await must service the batch itself ({@code serviceRoutedCommands}). If the
 * hook is reverted, "main" here never drains the batch, the coordinator never
 * unparks, the barrier never completes, and this test fails by timeout.
 */
class SprintRoutedCommandPumpTest {

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

    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static void setStatic(Class<?> owner, String name, Object value)
            throws ReflectiveOperationException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T getStatic(Class<?> owner, String name) throws ReflectiveOperationException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(null);
    }

    private static void setField(Object target, Class<?> owner, String name, Object value)
            throws ReflectiveOperationException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private boolean savedRegionThreading;

    @AfterEach
    void tearDown() throws Exception {
        setStatic(ParallelProcessor.class, "server", null);
        setStatic(RegionScheduler.class, "server", null);
        setStatic(RegionScheduler.class, "active", false);
        SprintRoutedCommandPumpTest.<Map<ServerLevel, RegionizedServer>>getStatic(
                RegionScheduler.class, "PER_LEVEL").clear();
        FabroliaConfig.enableRegionThreading = savedRegionThreading;
        IslandCommands.releaseAllExcept();
    }

    @Test
    void sprintUnitBarrierAwaitMustServiceRoutedCommandBatch() throws Exception {
        // healColorGate stays inert (skeleton rs has no heal snapshot); the
        // routed-batch machinery is what this test exercises.
        savedRegionThreading = FabroliaConfig.enableRegionThreading;
        FabroliaConfig.enableRegionThreading = false;

        // One async level so quiesceAllExcept has a fence to take (empty
        // island registry → the fence is trivially acquired).
        ServerLevel level = (ServerLevel) UNSAFE.allocateInstance(
                PacketLaneProductionPathTest.LaneLevel.class);
        RegionizedServer rs = (RegionizedServer) UNSAFE.allocateInstance(RegionizedServer.class);
        rs.asyncActive = true;
        setField(rs, RegionizedServer.class, "islandRegistry", new IslandRegistry());
        setStatic(RegionScheduler.class, "active", true);
        SprintRoutedCommandPumpTest.<Map<ServerLevel, RegionizedServer>>getStatic(
                RegionScheduler.class, "PER_LEVEL").put(level, rs);

        // The routed command a unit's command block produced. Enqueued while
        // no server is registered so the self-nudge (sv.execute) — the lane
        // that production main never polls from inside pumpUntilDone — is
        // skipped, exactly reproducing the wedge's reachable-drain set.
        CompletableFuture<Boolean> cmdDone = new CompletableFuture<>();
        IslandCommands.enqueueFencedBody(() -> {
            cmdDone.complete(true);
            return true;
        });
        assertTrue(IslandCommands.hasRoutedPending(), "routed batch must be pending");

        // Island X's coordinator: parked exactly like routeCommandBlock's
        // wait loop, completes its unit barrier only after the command ran.
        CompletableFuture<Void> unitBarrier = new CompletableFuture<>();
        CountDownLatch coordinatorParked = new CountDownLatch(1);
        RegionWorkerThread coordinator = new RegionWorkerThread(() -> {
            coordinatorParked.countDown();
            while (!cmdDone.isDone()) {
                LockSupport.parkNanos(50_000L);
            }
            unitBarrier.complete(null);
        }, "fabrolia-test-sprint-coordinator");
        coordinator.start();
        assertTrue(coordinatorParked.await(5, TimeUnit.SECONDS));

        // "Server thread": the sprint lockstep await. serverThread is stamped
        // to THIS pump thread so pumpUntilDone takes its main branch.
        AtomicReference<Throwable> err = new AtomicReference<>();
        Thread pumpThread = new Thread(() -> {
            try {
                ParallelProcessor.pumpUntilDone(unitBarrier, true);
            } catch (Throwable t) {
                err.set(t);
            }
        }, "fabrolia-test-main");
        MinecraftServer srv = (MinecraftServer) UNSAFE.allocateInstance(
                net.minecraft.server.dedicated.DedicatedServer.class);
        setField(srv, MinecraftServer.class, "serverThread", pumpThread);
        setField(srv, MinecraftServer.class, "levels", Map.of());
        setStatic(ParallelProcessor.class, "server", srv);
        setStatic(RegionScheduler.class, "server", srv);

        pumpThread.setDaemon(true);
        pumpThread.start();
        pumpThread.join(10_000);
        boolean wedged = pumpThread.isAlive();
        if (wedged) {
            cmdDone.complete(true); // unwedge so the JVM can exit cleanly
        }
        assertFalse(wedged,
                "pumpUntilDone at the sprint unit barrier never drained the routed "
                        + "command batch — the coordinator parked in routeCommandBlock "
                        + "can never resume: this is the 2026-07-28 production "
                        + "main ⇄ coordinator sprint deadlock");
        if (err.get() != null) throw new AssertionError(err.get());

        coordinator.join(5_000);
        assertFalse(coordinator.isAlive(), "coordinator must finish its unit");
        assertTrue(cmdDone.isDone() && unitBarrier.isDone());
        assertFalse(IslandCommands.hasRoutedPending(), "batch must be fully drained");
    }
}
