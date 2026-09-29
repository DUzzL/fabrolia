package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Main's island-bucketed claim drain: idle islands run without waiting, busy
 * islands keep their work registered, one claim per island per flush, the
 * interest flag is restored per island as soon as that island is claimed, and
 * an abort cancels exactly the bodies that never started.
 */
@Isolated
class IslandBucketsTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void flushReadyRunsIdleIslandsAndKeepsBusyOnesRegisteredInOrder() throws Exception {
        try (Fixture f = new Fixture()) {
            List<String> ran = new ArrayList<>();
            RegionScheduler.IslandBuckets<String> buckets =
                    new RegionScheduler.IslandBuckets<>(ran::add, (item, failure) -> fail("no abort expected"));
            f.busy.unitClaim.set(true);
            buckets.add(f.idle, "idle-1");
            buckets.add(f.busy, "busy-1");
            buckets.add(f.idle, "idle-2");
            buckets.add(f.busy, "busy-2");
            buckets.add(null, "unclaimed");
            assertTrue(buckets.flushReady());
            assertEquals(List.of("unclaimed", "idle-1", "idle-2"), ran);
            assertFalse(f.idle.unitClaim.get(), "the idle island's claim was released after its bucket");
            assertFalse(f.busy.claimWanted, "a non-blocking pass announces no interest");
            assertEquals(List.of("busy-1", "busy-2"), buckets.drainRemaining());
            assertTrue(buckets.isEmpty());
        }
    }

    @Test
    void readyPassNeverRaisesInterestSoAMainStallCannotParkTheIsland() throws Exception {
        try (Fixture f = new Fixture()) {
            List<String> ran = new ArrayList<>();
            long stale = System.nanoTime() - RegionScheduler.IslandBuckets.STALE_NANOS * 2;
            RegionScheduler.IslandBuckets<long[]> buckets = new RegionScheduler.IslandBuckets<>(
                    item -> ran.add(Long.toString(item[1])), (item, failure) -> fail("no abort expected"),
                    item -> item[0], item -> item[1] == 7);
            f.busy.unitClaim.set(true);
            buckets.add(f.busy, new long[]{stale, 1});
            buckets.add(f.busy, new long[]{stale, 7});
            assertFalse(buckets.flushReady(), "the ready pass never waits for a busy island");
            assertFalse(f.busy.claimWanted,
                    "no interest is left standing: the island runs no unit while it is raised");
            assertTrue(ran.isEmpty());
            assertEquals(2, buckets.drainRemaining().size(), "the busy island's work stays queued");
        }
    }

    @Test
    void overdueFlushWaitsOnlyForIslandsWithStaleOrUrgentWork() throws Exception {
        try (Fixture f = new Fixture()) {
            List<String> ran = new ArrayList<>();
            long fresh = System.nanoTime();
            long stale = fresh - RegionScheduler.IslandBuckets.STALE_NANOS * 2;
            RegionScheduler.IslandBuckets<long[]> buckets = new RegionScheduler.IslandBuckets<>(
                    item -> ran.add(Long.toString(item[1])), (item, failure) -> fail("no abort expected"),
                    item -> item[0], item -> item[1] == 7);
            // idle plays a second busy island here, holding only fresh work.
            f.idle.unitClaim.set(true);
            f.busy.unitClaim.set(true);
            buckets.add(f.idle, new long[]{fresh, 1});
            buckets.add(f.busy, new long[]{stale, 2});
            AtomicBoolean fresherAnnounced = new AtomicBoolean();
            Thread unit = Thread.ofPlatform().daemon().start(() -> {
                long deadline = System.nanoTime() + 2_000_000_000L;
                while (!f.busy.claimWanted && System.nanoTime() < deadline) Thread.onSpinWait();
                fresherAnnounced.set(f.idle.claimWanted);
                f.busy.unitClaim.set(false);
            });
            assertTrue(buckets.flushOverdue());
            unit.join(2_000L);
            assertEquals(List.of("2"), ran, "the stale island is waited for, the fresh one is not");
            assertFalse(fresherAnnounced.get(), "no interest for an island that is not overdue");
            assertFalse(f.busy.claimWanted, "interest ends with the wait");
            assertFalse(f.idle.claimWanted);
            List<long[]> left = buckets.drainRemaining();
            assertEquals(1, left.size());
            assertEquals(1, left.get(0)[1]);

            f.busy.unitClaim.set(true);
            buckets.add(f.busy, new long[]{fresh, 7});
            Thread urgentUnit = Thread.ofPlatform().daemon().start(() -> {
                long deadline = System.nanoTime() + 2_000_000_000L;
                while (!f.busy.claimWanted && System.nanoTime() < deadline) Thread.onSpinWait();
                f.busy.unitClaim.set(false);
            });
            assertTrue(buckets.flushOverdue());
            urgentUnit.join(2_000L);
            assertEquals(List.of("2", "7"), ran, "a fresh urgent task is overdue at once");
            assertFalse(f.busy.claimWanted);
            assertTrue(buckets.isEmpty());
        }
    }

    @Test
    void flushWaitsForTheBusyIslandAndRestoresItsInterestBeforeTheBody() throws Exception {
        try (Fixture f = new Fixture()) {
            List<String> ran = new ArrayList<>();
            AtomicBoolean interestSeenByBody = new AtomicBoolean(true);
            RegionScheduler.IslandBuckets<String> buckets =
                    new RegionScheduler.IslandBuckets<>(item -> {
                        if (item.startsWith("busy")) interestSeenByBody.set(f.busy.claimWanted);
                        ran.add(item);
                    }, (item, failure) -> fail("no abort expected"));
            f.busy.unitClaim.set(true);
            buckets.add(f.busy, "busy-1");
            buckets.add(f.idle, "idle-1");
            AtomicBoolean interestSeenWhileBusy = new AtomicBoolean();
            Thread unit = Thread.ofPlatform().daemon().start(() -> {
                long deadline = System.nanoTime() + 2_000_000_000L;
                while (!f.busy.claimWanted && System.nanoTime() < deadline) Thread.onSpinWait();
                interestSeenWhileBusy.set(f.busy.claimWanted);
                f.busy.unitClaim.set(false);
            });
            buckets.flush();
            unit.join(2_000L);
            assertEquals(List.of("idle-1", "busy-1"), ran, "idle island first, busy island once its unit ended");
            assertTrue(interestSeenWhileBusy.get(), "the running unit saw Main's interest");
            assertFalse(interestSeenByBody.get(), "interest is withdrawn before the island's bodies run");
            assertFalse(f.busy.claimWanted);
            assertFalse(f.busy.unitClaim.get());
            assertTrue(buckets.isEmpty());
        }
    }

    @Test
    void abortCancelsOnlyBodiesThatNeverStartedAndReleasesTheClaim() throws Exception {
        try (Fixture f = new Fixture()) {
            List<String> ran = new ArrayList<>();
            List<String> cancelled = new ArrayList<>();
            RegionScheduler.IslandBuckets<String> buckets =
                    new RegionScheduler.IslandBuckets<>(item -> {
                        ran.add(item);
                        if (item.equals("idle-2")) throw new IllegalStateException("body failed");
                    }, (item, failure) -> { cancelled.add(item); return failure; });
            f.busy.unitClaim.set(true);
            buckets.add(f.idle, "idle-1");
            buckets.add(f.idle, "idle-2");
            buckets.add(f.idle, "idle-3");
            buckets.add(f.busy, "busy-1");
            IllegalStateException thrown = assertThrows(IllegalStateException.class, buckets::flush);
            assertEquals("body failed", thrown.getMessage());
            assertEquals(List.of("idle-1", "idle-2"), ran);
            assertEquals(List.of("idle-3", "busy-1"), cancelled);
            assertFalse(f.idle.unitClaim.get(), "the claim is released on the failing path");
            assertFalse(f.idle.claimWanted);
            assertFalse(f.busy.claimWanted);
            assertTrue(buckets.isEmpty());
        }
    }

    private static final class TestLevel extends ServerLevel {
        private TestLevel() { super(null, null, null, null, null, null, false, 0L, null, false); }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
    }

    private static final class Fixture implements AutoCloseable {
        final sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        final Object oldPool = field(RegionScheduler.class, "pool").get(null);
        final Object oldActive = field(RegionScheduler.class, "active").get(null);
        final Object oldFailure = field(RegionScheduler.class, "orphanFatalFailure").get(null);
        final Object oldServer = field(ParallelProcessor.class, "server").get(null);
        final TestLevel level;
        final RegionizedServer runtime;
        final IslandRegistry.PIsland idle = new IslandRegistry.PIsland(1L, 0L);
        final IslandRegistry.PIsland busy = new IslandRegistry.PIsland(2L, 0L);
        final Map<ServerLevel, RegionizedServer> levels;

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            assertNull(oldPool);
            MinecraftServer server = (MinecraftServer) unsafe.allocateInstance(TestDedicatedServer.class);
            field(MinecraftServer.class, "serverThread").set(server, Thread.currentThread());
            field(ParallelProcessor.class, "server").set(null, server);
            level = (TestLevel) unsafe.allocateInstance(TestLevel.class);
            field(ServerLevel.class, "server").set(level, server);
            runtime = new RegionizedServer(level, new RegionGrid(3));
            runtime.asyncActive = true;
            for (IslandRegistry.PIsland island : List.of(idle, busy)) {
                island.level = level;
                ((Long2ObjectOpenHashMap<IslandRegistry.PIsland>) field(IslandRegistry.class, "islands")
                        .get(runtime.islandRegistry)).put(island.id, island);
            }
            levels = (Map<ServerLevel, RegionizedServer>) field(RegionScheduler.class, "PER_LEVEL").get(null);
            levels.put(level, runtime);
            RegionWorkerPool pool = new RegionWorkerPool(1);
            field(RegionWorkerPool.class, "running").setBoolean(pool, true);
            field(RegionWorkerPool.class, "accepting").setBoolean(pool, true);
            field(RegionScheduler.class, "pool").set(null, pool);
            field(RegionScheduler.class, "active").setBoolean(null, true);
            field(RegionScheduler.class, "orphanFatalFailure").set(null, null);
        }

        @Override public void close() throws Exception {
            idle.unitClaim.set(false);
            busy.unitClaim.set(false);
            levels.remove(level, runtime);
            field(RegionScheduler.class, "pool").set(null, oldPool);
            field(RegionScheduler.class, "active").set(null, oldActive);
            field(RegionScheduler.class, "orphanFatalFailure").set(null, oldFailure);
            field(ParallelProcessor.class, "server").set(null, oldServer);
        }
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
