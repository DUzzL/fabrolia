package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.google.common.collect.ImmutableList;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the real MainTrackingTask through reservation custody and the native scheduler queue. */
@Isolated
class ArrivalTrackingPublicationTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void bulkMembershipSurvivesMetadataDrainWithOneTokenUntilOriginalTaskPublishes() throws Exception {
        try (Fixture f = new Fixture()) {
            f.capture();
            assertTrue(IslandEntityTracking.stageBulkPairingRefreshIfOwned(f.tracker));
            long token = f.token;
            synchronized (f.tracker) {
                assertEquals(IslandEntityTracking.Route.DROP, IslandEntityTracking.route(f.tracker, f.arrival));
                assertEquals(IslandEntityTracking.Route.DROP, IslandEntityTracking.route(f.tracker, f.arrival));
            }
            assertEquals(token, f.token);
            assertEquals(1, f.acceptedTokens);
            assertTrue(f.tasks.isEmpty());
            assertTrue(f.pending);
            f.reservationCall("drainOutbox");
            synchronized (f.tracker) {
                Method publish = IslandEntityTracking.class.getDeclaredMethod("publishAndPairUnderTrackerLock", IslandTrackedEntityAccess.class);
                assertEquals(false, invoke(publish, null, f.tracker));
            }
            assertTrue(f.pending, "blocked false must not commit pending membership as a hard-clock duplicate");
            assertTrue(f.events.isEmpty());
            f.settle();
            assertTrue(f.events.isEmpty(), "arrival callback must enqueue, never claim or publish inline");
            assertEquals(1, f.tasks.size());
            f.tasks.remove().run();
            assertEquals(List.of("refresh", "pair"), f.events);
            assertFalse(f.pending);
            assertEquals(0L, f.token);
            assertEquals(1, f.acceptedTokens);
            assertEquals(1, f.releases);
        }
    }

    @Test
    void alreadyQueuedTaskTransfersItsExactEnvelopeToAnInterveningArrival() throws Exception {
        try (Fixture f = new Fixture()) {
            f.schedule();
            TickTask original = f.tasks.remove();
            f.pending = true;
            f.capture();
            original.run();
            assertTrue(f.tasks.isEmpty());
            assertTrue(f.pending);
            assertEquals(1, f.acceptedTokens);
            assertEquals(0, f.releases);
            f.settle();
            f.tasks.remove().run();
            assertEquals(List.of("refresh", "pair"), f.events);
            assertEquals(1, f.releases);
        }
    }


    @Test
    void levelChangeTerminalizesHeldTaskWithoutPublishingInTheNewGeneration() throws Exception {
        try (Fixture f = new Fixture()) {
            f.capture();
            assertTrue(IslandEntityTracking.stageBulkPairingRefreshIfOwned(f.tracker));
            f.settle();
            field(Entity.class, "level").set(f.arrival, f.unsafe.allocateInstance(ServerLevel.class));
            f.tasks.remove().run();
            assertTrue(f.events.isEmpty());
            assertTrue(f.pending);
            assertEquals(0L, f.token);
            assertEquals(1, f.releases);
        }
    }

    @Test
    void cancellationReleasesOnlyItsExactTokenAndDoesNotCommitMembership() throws Exception {
        try (Fixture f = new Fixture()) {
            f.capture();
            assertTrue(IslandEntityTracking.stageBulkPairingRefreshIfOwned(f.tracker));
            var held = (List<?>) field(NetherPortalPlayerAccess.Reservation.class,
                    "arrivalPublications").get(f.reservation);
            assertEquals(1, held.size());
            var oldPublication = (NetherPortalPlayerAccess.ArrivalPublication) held.getFirst();
            f.reservation.cancel(null);
            assertEquals(0L, f.token);
            assertEquals(1, f.releases);
            assertTrue(f.pending);
            assertTrue(f.events.isEmpty());
            f.schedule();
            long successor = f.token;
            oldPublication.cancel();
            assertEquals(successor, f.token, "late task cleanup cannot erase a successor token");
            assertEquals(1, f.releases);
            f.tasks.remove().run();
            assertEquals(List.of("refresh", "pair"), f.events);
            assertTrue(f.tasks.isEmpty());
            assertEquals(2, f.releases);
        }
    }

    @Test
    void releaseOfferThenThrowSelectsFailureBeforeQueuedTaskCanPublish() throws Exception {
        try (Fixture f = new Fixture()) {
            f.capture();
            assertTrue(IslandEntityTracking.stageBulkPairingRefreshIfOwned(f.tracker));
            RuntimeException failure = new RuntimeException("offered then threw");
            f.offerFailure = failure;
            assertSame(failure, assertThrows(RuntimeException.class, f::settle));
            assertEquals(0L, f.token);
            assertEquals(1, f.releases);
            assertSame(failure, assertThrows(RuntimeException.class, () -> f.tasks.remove().run()));
            assertTrue(f.pending);
            assertTrue(f.events.isEmpty());
            assertEquals(1, f.releases);
        }
    }

    private static final class GuardedFrame extends ItemFrame implements ParallelProcessor.TickGuard {
        EntityTickLease lease;
        private GuardedFrame() { super(null, null); }
        @Override public EntityTickLease fabrolia$tickLease() { return lease; }
        @Override public byte fabrolia$getSyncCache() { return 0; }
        @Override public void fabrolia$setSyncCache(byte value) {}
    }

    private static final class Fixture implements AutoCloseable {
        final sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        final Object priorPool = field(RegionScheduler.class, "pool").get(null);
        final Object priorActive = field(RegionScheduler.class, "active").get(null);
        final Object priorServer = field(RegionScheduler.class, "server").get(null);
        final Object priorFailure = field(RegionScheduler.class, "orphanFatalFailure").get(null);
        final List<String> events = new ArrayList<>();
        RuntimeException offerFailure;
        final ConcurrentLinkedQueue<TickTask> tasks = new ConcurrentLinkedQueue<>() {
            @Override public boolean add(TickTask task) {
                boolean added = super.add(task);
                if (offerFailure != null) throw offerFailure;
                return added;
            }
        };
        final MinecraftServer server = (MinecraftServer) unsafe.allocateInstance(com.axalotl.fabrolia.common.regions.TestDedicatedServer.class);
        final ServerLevel level = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
        final GuardedFrame arrival = (GuardedFrame) unsafe.allocateInstance(GuardedFrame.class);
        final NetherPortalPlayerAccess.Reservation reservation;
        final Object transfer;
        final IslandTrackedEntityAccess tracker;
        boolean pending;
        long token;
        int acceptedTokens;
        int releases;

        Fixture() throws Exception {
            field(MinecraftServer.class, "serverThread").set(server, Thread.currentThread());
            field(MinecraftServer.class, "levels").set(server, Map.of());
            field(BlockableEventLoop.class, "pendingRunnables").set(server, tasks);
            field(ServerLevel.class, "server").set(level, server);
            field(Entity.class, "level").set(arrival, level);
            field(Entity.class, "passengers").set(arrival, ImmutableList.of());
            arrival.lease = new EntityTickLease();
            field(RegionScheduler.class, "pool").set(null, null);
            field(RegionScheduler.class, "active").set(null, false);
            field(RegionScheduler.class, "server").set(null, server);
            field(RegionScheduler.class, "orphanFatalFailure").set(null, null);
            Class<?> transferType = Class.forName(NetherPortalTransfers.class.getName() + "$Transfer");
            var transferConstructor = transferType.getDeclaredConstructor(Entity.class, ServerLevel.class, EpochDomain.class, long.class);
            transferConstructor.setAccessible(true);
            transfer = transferConstructor.newInstance(arrival, level, null, 0L);
            var constructor = NetherPortalPlayerAccess.Reservation.class.getDeclaredConstructor(Entity.class, Object.class);
            constructor.setAccessible(true);
            reservation = constructor.newInstance(arrival, transfer);
            tracker = (IslandTrackedEntityAccess) Proxy.newProxyInstance(IslandTrackedEntityAccess.class.getClassLoader(),
                    new Class<?>[]{IslandTrackedEntityAccess.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "fabrolia$trackedEntity" -> arrival;
                        case "fabrolia$islandTrackingState" -> IslandTrackedEntityAccess.TRACKING_ACTIVE;
                        case "fabrolia$hasPendingIslandPairings" -> pending;
                        case "fabrolia$hasIslandTrackingViewers" -> false;
                        case "fabrolia$queueIslandBulkPairingRefresh" -> { pending = true; yield true; }
                        case "fabrolia$islandTrackingOwner" -> null;
                        case "fabrolia$setIslandTrackingOwner" -> null;
                        case "fabrolia$tryScheduleMainTracking" -> {
                            if (token != 0L) yield 0L;
                            token = ++acceptedTokens;
                            yield token;
                        }
                        case "fabrolia$finishMainTrackingSchedule" -> {
                            if (token == (long) args[0]) { token = 0L; releases++; }
                            yield null;
                        }
                        case "fabrolia$sendIslandChanges" -> { events.add("refresh"); yield true; }
                        case "fabrolia$commitIslandPairings" -> { events.add("pair"); pending = false; yield null; }
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "arrival tracker";
                        default -> throw new AssertionError("Unexpected tracker operation: " + method);
                    });
        }

        @SuppressWarnings("unchecked")
        void capture() throws Exception {
            ThreadLocal<Object> current = (ThreadLocal<Object>) field(NetherPortalPlayerAccess.class, "CURRENT").get(null);
            ThreadLocal<Object> active = (ThreadLocal<Object>) field(NetherPortalTransfers.class, "ACTIVE").get(null);
            Object oldCurrent = current.get(), oldActive = active.get();
            Class<?> scope = Class.forName(NetherPortalTransfers.class.getName() + "$Scope");
            var constructor = scope.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            try {
                current.set(reservation);
                active.set(constructor.newInstance(transfer, null));
                field(NetherPortalPlayerAccess.Reservation.class, "active").setBoolean(reservation, true);
                NetherPortalPlayerAccess.captureArrivalMetadata(arrival);
            } finally {
                current.set(oldCurrent);
                active.set(oldActive);
                field(NetherPortalPlayerAccess.Reservation.class, "active").setBoolean(reservation, false);
            }
        }

        void schedule() throws Exception {
            synchronized (tracker) {
                invoke(IslandEntityTracking.class.getDeclaredMethod("scheduleMainPublication", IslandTrackedEntityAccess.class), null, tracker);
            }
        }

        void reservationCall(String name) throws Exception {
            invoke(NetherPortalPlayerAccess.Reservation.class.getDeclaredMethod(name), reservation);
        }

        void settle() throws Exception {
            field(NetherPortalPlayerAccess.Reservation.class, "releaseSource").set(reservation,
                    (Runnable) () -> {
                        assertTrue(arrival.lease.isArrivalPending(),
                                "source retirement must still precede readiness");
                        assertTrue(events.isEmpty());
                    });
            reservationCall("completeMetadata");
        }

        @Override public void close() throws Exception {
            try { reservation.cancel(null); }
            finally {
                field(RegionScheduler.class, "pool").set(null, priorPool);
                field(RegionScheduler.class, "active").set(null, priorActive);
                field(RegionScheduler.class, "server").set(null, priorServer);
                field(RegionScheduler.class, "orphanFatalFailure").set(null, priorFailure);
            }
        }
    }

    private static Object invoke(Method method, Object target, Object... args) throws Exception {
        method.setAccessible(true);
        try { return method.invoke(target, args); }
        catch (InvocationTargetException wrapper) {
            if (wrapper.getCause() instanceof Exception failure) throw failure;
            if (wrapper.getCause() instanceof Error failure) throw failure;
            throw new AssertionError(wrapper.getCause());
        }
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
