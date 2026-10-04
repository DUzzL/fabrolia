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
            assertFalse(f.busy.claimInterestAnnounced(), "a non-blocking pass announces no interest");
            assertEquals(List.of("busy-1", "busy-2"), buckets.drainRemaining());
            assertTrue(buckets.isEmpty());
        }
    }

    /**
     * A postponed task (a Main-sync function pass that may need the global
     * fence during an entity-phase pump) stops its bucket: neither it nor any
     * later task of that island runs, and the remainder returns in order.
     */
    @Test
    void aPostponedTaskStopsItsBucketAndKeepsTheRemainderInOrder() throws Exception {
        try (Fixture f = new Fixture()) {
            List<String> ran = new ArrayList<>();
            RegionScheduler.IslandBuckets<String> buckets =
                    new RegionScheduler.IslandBuckets<>(ran::add, (item, failure) -> fail("no abort expected"));
            buckets.postponeWhile(item -> item.startsWith("fence"));
            buckets.add(f.idle, "idle-1");
            buckets.add(f.idle, "fence-idle");
            buckets.add(f.idle, "idle-2");
            buckets.add(null, "unclaimed-1");
            buckets.add(null, "fence-unclaimed");
            buckets.add(null, "unclaimed-2");
            assertTrue(buckets.flushReady());
            assertEquals(List.of("unclaimed-1", "idle-1"), ran);
            assertFalse(f.idle.unitClaim.get(), "the claim is released at the postponed task");
            assertEquals(List.of("fence-idle", "idle-2", "fence-unclaimed", "unclaimed-2"),
                    buckets.drainRemaining());
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
            assertFalse(f.busy.mainInterest(),
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
                while (!f.busy.claimInterestAnnounced() && System.nanoTime() < deadline) Thread.onSpinWait();
                fresherAnnounced.set(f.idle.claimInterestAnnounced());
                f.busy.unitClaim.set(false);
            });
            assertTrue(buckets.flushOverdue());
            unit.join(2_000L);
            assertEquals(List.of("2"), ran, "the stale island is waited for, the fresh one is not");
            assertFalse(fresherAnnounced.get(), "no interest for an island that is not overdue");
            assertFalse(f.busy.claimInterestAnnounced(), "interest ends with the wait");
            assertFalse(f.idle.claimInterestAnnounced());
            List<long[]> left = buckets.drainRemaining();
            assertEquals(1, left.size());
            assertEquals(1, left.get(0)[1]);

            f.busy.unitClaim.set(true);
            buckets.add(f.busy, new long[]{fresh, 7});
            Thread urgentUnit = Thread.ofPlatform().daemon().start(() -> {
                long deadline = System.nanoTime() + 2_000_000_000L;
                while (!f.busy.claimInterestAnnounced() && System.nanoTime() < deadline) Thread.onSpinWait();
                f.busy.unitClaim.set(false);
            });
            assertTrue(buckets.flushOverdue());
            urgentUnit.join(2_000L);
            assertEquals(List.of("2", "7"), ran, "a fresh urgent task is overdue at once");
            assertFalse(f.busy.claimInterestAnnounced());
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
                        if (item.startsWith("busy")) interestSeenByBody.set(f.busy.claimInterestAnnounced());
                        ran.add(item);
                    }, (item, failure) -> fail("no abort expected"));
            f.busy.unitClaim.set(true);
            buckets.add(f.busy, "busy-1");
            buckets.add(f.idle, "idle-1");
            AtomicBoolean interestSeenWhileBusy = new AtomicBoolean();
            Thread unit = Thread.ofPlatform().daemon().start(() -> {
                long deadline = System.nanoTime() + 2_000_000_000L;
                while (!f.busy.claimInterestAnnounced() && System.nanoTime() < deadline) Thread.onSpinWait();
                interestSeenWhileBusy.set(f.busy.claimInterestAnnounced());
                f.busy.unitClaim.set(false);
            });
            buckets.flush();
            unit.join(2_000L);
            assertEquals(List.of("idle-1", "busy-1"), ran, "idle island first, busy island once its unit ended");
            assertTrue(interestSeenWhileBusy.get(), "the running unit saw Main's interest");
            assertFalse(interestSeenByBody.get(), "interest is withdrawn before the island's bodies run");
            assertFalse(f.busy.claimInterestAnnounced());
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
            assertFalse(f.idle.mainInterest());
            assertFalse(f.busy.mainInterest());
            assertTrue(buckets.isEmpty());
        }
    }

    @Test
    void tailPassNeverWaitsAndLeavesAStandingRequestOnlyWithOverdueBusyIslands() throws Exception {
        try (Fixture f = new Fixture()) {
            List<String> ran = new ArrayList<>();
            long fresh = System.nanoTime();
            long stale = fresh - RegionScheduler.IslandBuckets.STALE_NANOS * 2;
            IslandRegistry.PIsland freshBusy = f.island(3L);
            RegionScheduler.IslandBuckets<long[]> buckets = new RegionScheduler.IslandBuckets<>(
                    item -> ran.add(Long.toString(item[1])), (item, failure) -> fail("no abort expected"),
                    item -> item[0], item -> item[1] == 7, RegionScheduler.MainSyncService.INSTANCE);
            f.busy.unitClaim.set(true);
            freshBusy.unitClaim.set(true);
            buckets.add(f.busy, new long[]{stale, 1});
            buckets.add(freshBusy, new long[]{fresh, 2});
            buckets.add(f.idle, new long[]{fresh, 3});
            buckets.add(f.busy, new long[]{fresh, 4});
            // A blocking pass would spin on these claims forever; this belt
            // releases them after 3 s and records that it had to.
            AtomicBoolean rescued = new AtomicBoolean();
            Thread belt = Thread.ofPlatform().daemon().start(() -> {
                try {
                    Thread.sleep(3_000L);
                    rescued.set(true);
                    f.busy.unitClaim.set(false);
                    freshBusy.unitClaim.set(false);
                } catch (InterruptedException ignored) {
                    // the pass returned on its own
                }
            });
            assertTrue(buckets.flushReadyRequestingService(false));
            belt.interrupt();
            assertFalse(rescued.get(), "the tail pass waited for a running unit");
            assertEquals(List.of("3"), ran, "only the idle island ran");
            assertTrue(f.busy.mainSyncWanted, "the overdue busy island got a standing request");
            assertTrue(f.busy.mainInterest(), "its unit admission, chaining and pacer now yield");
            assertFalse(freshBusy.mainSyncWanted, "an island whose work is not overdue keeps running");
            assertFalse(f.busy.claimInterestAnnounced(), "no blocking claim waiter was announced");
            assertEquals(List.of(1L, 4L, 2L),
                    buckets.drainRemaining().stream().map(t -> t[1]).toList(),
                    "busy islands keep their work in queue order");
        }
    }

    @Test
    void requestedIslandIsServedOnceAtTheNextReadyPassAndStaysStoppedThroughItsBodies() throws Exception {
        try (Fixture f = new Fixture()) {
            List<String> ran = new ArrayList<>();
            AtomicBoolean requestSeenByBody = new AtomicBoolean();
            RegionScheduler.MainSyncService service = RegionScheduler.MainSyncService.INSTANCE;
            RegionScheduler.IslandBuckets<String> tail = new RegionScheduler.IslandBuckets<>(
                    ran::add, (item, failure) -> fail("no abort expected"), item -> 0L, null, service);
            f.busy.unitClaim.set(true);
            tail.add(f.busy, "busy-1");
            tail.add(f.busy, "busy-2");
            assertFalse(tail.flushReadyRequestingService(false));
            assertTrue(f.busy.mainSyncWanted);
            List<String> requeued = tail.drainRemaining();
            assertEquals(List.of("busy-1", "busy-2"), requeued);

            f.busy.unitClaim.set(false); // its running unit ended; no successor starts
            RegionScheduler.IslandBuckets<String> next = new RegionScheduler.IslandBuckets<>(item -> {
                requestSeenByBody.set(f.busy.mainSyncWanted);
                ran.add(item);
            }, (item, failure) -> fail("no abort expected"), item -> 0L, null, service);
            for (String item : requeued) next.add(f.busy, item);
            assertTrue(next.flushReady());
            assertEquals(List.of("busy-1", "busy-2"), ran, "each body exactly once, in queue order");
            assertTrue(requestSeenByBody.get(), "the island stays stopped while its bucket runs");
            assertFalse(f.busy.mainSyncWanted, "the request ends once the bucket was served");
            assertFalse(f.busy.unitClaim.get(), "Main released the claim");
            assertEquals(0, service.size());
            assertTrue(next.isEmpty());
        }
    }

    @Test
    void anIslandWhoseUnitEndsAsTheRequestIsRaisedIsServedInTheSamePass() throws Exception {
        try (Fixture f = new Fixture()) {
            List<String> ran = new ArrayList<>();
            List<IslandRegistry.PIsland> requested = new ArrayList<>();
            List<IslandRegistry.PIsland> serviced = new ArrayList<>();
            RegionScheduler.IslandServiceRequests requests = new RegionScheduler.IslandServiceRequests() {
                @Override public void request(IslandRegistry.PIsland island) {
                    requested.add(island);
                    island.unitClaim.set(false); // the unit completes right then
                }
                @Override public void serviced(IslandRegistry.PIsland island) { serviced.add(island); }
                @Override public void clearAll() { fail("no abort expected"); }
            };
            RegionScheduler.IslandBuckets<String> buckets = new RegionScheduler.IslandBuckets<>(
                    ran::add, (item, failure) -> fail("no abort expected"), item -> 0L, null, requests);
            f.busy.unitClaim.set(true);
            buckets.add(f.busy, "busy-1");
            assertTrue(buckets.flushReadyRequestingService(false));
            assertEquals(List.of("busy-1"), ran, "the second pass served the island that just stopped");
            assertEquals(List.of(f.busy), requested);
            assertEquals(List.of(f.busy), serviced);
            assertTrue(buckets.isEmpty());
        }
    }

    @Test
    void requestAllAsksEveryBusyIslandToStopEvenWithFreshWork() throws Exception {
        try (Fixture f = new Fixture()) {
            RegionScheduler.IslandBuckets<long[]> buckets = new RegionScheduler.IslandBuckets<>(
                    item -> fail("busy island ran"), (item, failure) -> fail("no abort expected"),
                    item -> item[0], item -> false, RegionScheduler.MainSyncService.INSTANCE);
            f.busy.unitClaim.set(true);
            buckets.add(f.busy, new long[]{System.nanoTime(), 1});
            assertFalse(buckets.flushReadyRequestingService(true));
            assertTrue(f.busy.mainSyncWanted,
                    "an island queued before a fenced body is asked to stop at once");
        }
    }

    @Test
    void standingRequestsNeverOutliveTheirWork() throws Exception {
        try (Fixture f = new Fixture()) {
            RegionScheduler.MainSyncService service = RegionScheduler.MainSyncService.INSTANCE;
            IslandRegistry.PIsland retired = f.island(5L);
            service.request(f.busy);
            service.request(f.idle);
            service.request(retired);
            retired.retired = true;
            service.reconcile(java.util.Set.of(f.busy, retired), System.nanoTime());
            assertTrue(f.busy.mainSyncWanted, "an island that still holds queued work keeps its request");
            assertFalse(f.idle.mainSyncWanted, "no queued work left: the request ends");
            assertFalse(retired.mainSyncWanted, "a retired identity never keeps a request");
            // An empty queue at top level ends every request: a complete or
            // re-keyed drain served the work it was raised for.
            assertFalse(RegionScheduler.drainReadyMainSyncTicks());
            assertFalse(f.busy.mainSyncWanted);
            assertEquals(0, service.size());
        }
    }

    @Test
    void abortEndsEveryStandingRequest() throws Exception {
        try (Fixture f = new Fixture()) {
            RegionScheduler.MainSyncService service = RegionScheduler.MainSyncService.INSTANCE;
            service.request(f.busy);
            RegionScheduler.IslandBuckets<String> buckets = new RegionScheduler.IslandBuckets<>(item -> {
                throw new IllegalStateException("body failed");
            }, (item, failure) -> failure, null, null, service);
            buckets.add(f.idle, "idle-1");
            assertThrows(IllegalStateException.class, buckets::flushReady);
            assertFalse(f.busy.mainSyncWanted, "a failed drain leaves no island stopped");
            assertEquals(0, service.size());
        }
    }

    @Test
    void outOfPhaseServiceRunsEachBucketOnItsIslandsLevelAndLeavesUnclaimedWork() throws Exception {
        try (Fixture f = new Fixture()) {
            ServerLevel phaseLevel = RegionScheduler.mainCurrentLevel;
            try {
                RegionScheduler.mainCurrentLevel = null;
                List<Object> seen = new ArrayList<>();
                RegionScheduler.IslandBuckets<String> buckets = new RegionScheduler.IslandBuckets<>(
                        item -> seen.add(RegionScheduler.mainCurrentLevel),
                        (item, failure) -> fail("no abort expected"), null, null,
                        RegionScheduler.MainSyncService.INSTANCE);
                buckets.add(f.idle, "island-body");
                buckets.add(null, "unclaimed-body");
                assertTrue(buckets.flushIdleIslands(true));
                assertEquals(1, seen.size());
                assertSame(f.level, seen.get(0),
                        "a Main cross-dimension stage lands in the island's own level");
                assertNull(RegionScheduler.mainCurrentLevel, "the phase level is restored");
                assertEquals(List.of("unclaimed-body"), buckets.drainRemaining(),
                        "unclaimed work waits for an entity-phase drain");
            } finally {
                RegionScheduler.mainCurrentLevel = phaseLevel;
            }
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
        final List<IslandRegistry.PIsland> extra = new ArrayList<>();
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

        /** One more live island of this level (claims released at close). */
        @SuppressWarnings("unchecked")
        IslandRegistry.PIsland island(long id) throws Exception {
            IslandRegistry.PIsland island = new IslandRegistry.PIsland(id, 0L);
            island.level = level;
            ((Long2ObjectOpenHashMap<IslandRegistry.PIsland>) field(IslandRegistry.class, "islands")
                    .get(runtime.islandRegistry)).put(island.id, island);
            extra.add(island);
            return island;
        }

        @Override public void close() throws Exception {
            RegionScheduler.MainSyncService.INSTANCE.clearAll();
            idle.unitClaim.set(false);
            busy.unitClaim.set(false);
            for (IslandRegistry.PIsland island : extra) island.unitClaim.set(false);
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
