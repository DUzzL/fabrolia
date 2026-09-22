package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.mixin.entity.EntityLevelCallbackAccessor;
import com.axalotl.fabrolia.common.mixin.entity.EntityMixin;
import com.axalotl.fabrolia.common.mixin.server.ServerWaypointManagerMixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.google.common.collect.ImmutableList;
import net.minecraft.SharedConstants;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.waypoints.ServerWaypointManager;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.waypoints.Waypoint;
import net.minecraft.world.waypoints.WaypointTransmitter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executes EntityMixin's real lifecycle helper, not a copy or a bytecode assertion.
 * Entity construction, ride topology and startRiding's body are vanilla. addPassenger
 * enters the real nested lifecycle helper at vanilla's partially updated ride link.
 * Native coverage still verifies the complete C2ME -> structure -> mob spawn chain.
 */
@Isolated
final class GenerationEntityLifecycleTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void generationOwnedRideTreeCanMountWhileMainRetainsHealthyExclusiveCut() throws Exception {
        try (Fixture f = new Fixture()) {
            f.assertConstruction(() -> {
                EntityBody rider = f.entity();
                EntityBody existingPassenger = f.entity();
                EntityBody vehicle = f.entity();
                assertTrue(existingPassenger.startRiding(rider, true, false));

                assertEquals(Boolean.TRUE, withLifecycle(new Entity[]{rider, vehicle},
                        () -> rider.startRiding(vehicle, true, false)));
                assertSame(vehicle, rider.getVehicle());
                assertEquals(List.of(rider), vehicle.getPassengers());
                assertSame(rider, existingPassenger.getVehicle());
                assertEquals(List.of(existingPassenger), rider.getPassengers());
                return null;
            });
            assertNull(f.pool.fatalFailureOrNull(), "private construction must not poison live gameplay");
            assertTrue(f.pool.ownsExclusiveGameplay(Thread.currentThread()));
        }
    }

    @Test
    void privateConstructionDoesNotAuthorizeRemovalHooks() throws Exception {
        try (Fixture f = new Fixture()) {
            f.assertConstruction(() -> {
                EntityBody rider = f.entity();
                EntityBody vehicle = f.entity();
                assertTrue(rider.startRiding(vehicle, true, false));
                assertThrows(IllegalStateException.class,
                        () -> withLifecycle(new Entity[]{rider}, false, () -> {
                            rider.setRemoved(Entity.RemovalReason.DISCARDED);
                            return true;
                        }));
                assertFalse(rider.isRemoved());
                assertSame(vehicle, rider.getVehicle());
                assertEquals(List.of(rider), vehicle.getPassengers());
                return null;
            });
        }
    }

    @Test
    void hiddenPublishedPassengerPreventsDetachedRootBypass() throws Exception {
        try (Fixture f = new Fixture()) {
            f.assertConstruction(() -> {
                EntityBody rider = f.entity();
                EntityBody publishedPassenger = f.entity();
                EntityBody vehicle = f.entity();
                assertTrue(publishedPassenger.startRiding(rider, true, false));
                // HIDDEN residents may have NO_REGION, but still have a real level callback.
                publishedPassenger.setLevelCallback(new EntityInLevelCallback() {
                    @Override public void onMove() { fail("published callback must not run off-owner"); }
                    @Override public void onRemove(Entity.RemovalReason reason) {
                        fail("published callback must not run off-owner");
                    }
                });
                assertRefused(f, new Entity[]{rider, vehicle});
                assertNull(rider.getVehicle());
                assertSame(rider, publishedPassenger.getVehicle());
                return null;
            });
        }
    }

    @Test
    void indexedVehicleOutsideExplicitRootsPreventsDetachedBypass() throws Exception {
        try (Fixture f = new Fixture()) {
            f.assertConstruction(() -> {
                EntityBody rider = f.entity();
                EntityBody publishedVehicle = f.entity();
                assertTrue(rider.startRiding(publishedVehicle, true, false));
                // Model an indexed participant before its publication revocation hook:
                // the index guard must independently deny even a current stamp.
                field(EntityMixin.class, "fabrolia$regionKey").setLong(publishedVehicle.guard, 0L);
                assertRefused(f, new Entity[]{rider});
                assertSame(publishedVehicle, rider.getVehicle());
                return null;
            });
        }
    }

    @Test
    void playerPassengerCannotBeClassifiedAsPrivateGenerationState() throws Exception {
        try (Fixture f = new Fixture()) {
            f.assertConstruction(() -> {
                EntityBody vehicle = f.entity();
                // Supply the same construction and custody interfaces as nonplayers:
                // player identity, not absent provenance, must prevent admission.
                PlayerBody player = allocate(PlayerBody.class);
                player.guard = new LifecycleBody();
                field(Entity.class, "level").set(player, f.level);
                field(Entity.class, "levelCallback").set(player, EntityInLevelCallback.NULL);
                field(Entity.class, "passengers").set(player, ImmutableList.of());
                field(Entity.class, "vehicle").set(player, vehicle);
                field(Entity.class, "passengers").set(vehicle, ImmutableList.of(player));
                assertRefused(f, new Entity[]{vehicle});
                assertSame(vehicle, player.getVehicle());
                return null;
            });
        }
    }

    @Test
    void detachedLookingEntityWithLiveLeaseCannotBypassItsOwner() throws Exception {
        try (Fixture f = new Fixture()) {
            f.assertConstruction(() -> {
                EntityBody entity = f.entity();
                assertTrue(entity.fabrolia$tickLease().tryBegin());
                try {
                    assertRefused(f, new Entity[]{entity});
                    assertTrue(entity.fabrolia$tickLease().isOwnedByCurrentThread());
                } finally {
                    entity.fabrolia$tickLease().end();
                }
                return null;
            });
        }
    }

    @Test
    void pendingArrivalCannotRegainPrivateAuthorityWhileItsLeaseIsFree() throws Exception {
        try (Fixture f = new Fixture()) {
            f.assertConstruction(() -> {
                EntityBody entity = f.entity();
                entity.fabrolia$tickLease().setArrivalPending(true);
                assertRefused(f, new Entity[]{entity});
                assertTrue(entity.fabrolia$tickLease().isArrivalPending());
                return null;
            });
        }
    }

    @Test
    void expiredConstructionCannotEnterLifecycleOutsideItsInvocation() throws Exception {
        try (Fixture f = new Fixture()) {
            Outcome outcome = f.generation(() -> {
                EntityBody entity = (EntityBody) inScope(f.level, f::entity);
                assertRefused(f, new Entity[]{entity});
                return null;
            });
            outcome.rethrowFailure();
        }
    }

    @Test
    void laterInvocationCannotAdoptAnEarlierDetachedIdentity() throws Exception {
        try (Fixture f = new Fixture()) {
            Outcome outcome = f.generation(() -> {
                EntityBody old = (EntityBody) inScope(f.level, f::entity);
                return inScope(f.level, () -> {
                    EntityBody fresh = f.entity();
                    assertRefused(f, new Entity[]{fresh, old});
                    assertNull(fresh.getVehicle());
                    return null;
                });
            });
            outcome.rethrowFailure();
        }
    }

    @Test
    void matchingInvocationCannotAdoptEntityConstructedForAnotherLevel() throws Exception {
        try (Fixture f = new Fixture()) {
            ServerLevel other = allocate(TestLevel.class);
            f.assertConstruction(() -> {
                EntityBody local = f.entity();
                EntityBody foreign = new EntityBody(other);
                assertRefused(f, new Entity[]{local, foreign});
                assertNull(local.getVehicle());
                return null;
            });
        }
    }

    @Test
    void nestedInvocationRestoresOuterConstructionAfterExceptionalExit() throws Exception {
        try (Fixture f = new Fixture()) {
            f.assertConstruction(() -> {
                EntityBody rider = f.entity();
                EntityBody vehicle = f.entity();
                IllegalArgumentException nestedFailure = new IllegalArgumentException("nested generation");
                assertSame(nestedFailure, assertThrows(IllegalArgumentException.class,
                        () -> inScope(f.level, () -> {
                            assertFalse(WorldGenEntityConstruction.owns(rider, vehicle));
                            throw nestedFailure;
                        })));
                assertEquals(Boolean.TRUE, withLifecycle(new Entity[]{rider, vehicle},
                        () -> rider.startRiding(vehicle, true, false)));
                assertSame(vehicle, rider.getVehicle());
                assertEquals(List.of(rider), vehicle.getPassengers());
                return null;
            });
        }
    }

    @Test
    void publicationRevocationCannotBeUndoneByDetachingAgain() throws Exception {
        try (Fixture f = new Fixture()) {
            f.assertConstruction(() -> {
                EntityBody rider = f.entity();
                EntityBody vehicle = f.entity();
                vehicle.fabrolia$setRegionKey(0L);
                vehicle.setLevelCallback(EntityInLevelCallback.NULL);
                vehicle.fabrolia$setRegionKey(RegionKeyed.NO_REGION);
                assertRefused(f, new Entity[]{rider, vehicle});
                assertNull(rider.getVehicle());
                assertEquals(List.of(), vehicle.getPassengers());
                return null;
            });
        }
    }

    @Test
    void stagedCloneLosesPrivateAuthorityBeforeDestinationPublication() throws Exception {
        try (Fixture f = new Fixture()) {
            f.assertConstruction(() -> {
                EntityBody entity = f.entity();
                new CrossDimArrivals.StagedOp(f.level, 0L,
                        () -> fail("staged destination body must not run"), "entity", 0L, 0L, entity);
                assertRefused(f, new Entity[]{entity});
                assertNull(entity.getVehicle());
                return null;
            });
        }
    }

    @Test
    void waypointPublicationCannotLeaveAnUnindexedEntityPrivate() throws Exception {
        try (Fixture f = new Fixture()) {
            f.assertConstruction(() -> {
                EntityBody entity = f.entity();
                ServerWaypointManager manager = new ServerWaypointManager();
                var track = ServerWaypointManagerMixin.class.getDeclaredMethod(
                        "fabrolia$trackWaypoint", WaypointTransmitter.class, Operation.class);
                track.setAccessible(true);
                Operation<Void> publish = arguments -> {
                    manager.trackWaypoint((WaypointTransmitter) arguments[0]);
                    return null;
                };
                track.invoke(new ServerWaypointManagerMixin() {}, entity, publish);
                assertTrue(manager.transmitters().contains(entity));
                assertEquals(RegionKeyed.NO_REGION, entity.fabrolia$getRegionKey());
                assertRefused(f, new Entity[]{entity});
                return null;
            });
        }
    }

    @Test
    void detachedConstructionCannotRunPastAlreadySelectedFatalFailure() throws Exception {
        try (Fixture f = new Fixture()) {
            AssertionError selected = new AssertionError("prior world failure");
            assertSame(selected, RegionScheduler.publishFatalAndSelectWinner(selected));
            AtomicReference<EntityBody> rider = new AtomicReference<>();
            Outcome outcome = f.constructing(() -> {
                rider.set(f.entity());
                return withLifecycle(new Entity[]{rider.get(), f.entity()}, () -> {
                    fail("even detached construction must observe the selected fatal failure");
                    return false;
                });
            });
            assertSame(selected, outcome.failure());
            assertNull(rider.get().getVehicle());
        }
    }

    @Test
    void fatalSelectedDuringPrivateBodyWinsItsSuccessfulReturnWithoutHoldingGateMonitor() throws Exception {
        try (Fixture f = new Fixture()) {
            AssertionError selected = new AssertionError("peer failed during construction");
            AtomicReference<Throwable> published = new AtomicReference<>();
            AtomicReference<Thread> publisher = new AtomicReference<>();
            AtomicReference<EntityBody> rider = new AtomicReference<>();
            AtomicReference<EntityBody> vehicle = new AtomicReference<>();
            CountDownLatch publication = new CountDownLatch(1);
            Outcome outcome = f.constructing(() -> {
                rider.set(f.entity());
                vehicle.set(f.entity());
                return withLifecycle(new Entity[]{rider.get(), vehicle.get()}, () -> {
                    assertTrue(rider.get().startRiding(vehicle.get(), true, false));
                    publisher.set(Thread.ofPlatform().daemon().start(() -> {
                        published.set(RegionScheduler.publishFatalAndSelectWinner(selected));
                        publication.countDown();
                    }));
                    try {
                        assertTrue(publication.await(2, TimeUnit.SECONDS),
                                "private vanilla body must not retain the fatal-publication monitor");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    return true;
                });
            });
            if (publisher.get() != null) publisher.get().join(Duration.ofSeconds(5));
            assertSame(selected, published.get());
            assertSame(selected, outcome.failure());
            assertSame(selected, f.pool.fatalFailureOrNull());
            assertSame(vehicle.get(), rider.get().getVehicle());
            assertEquals(List.of(rider.get()), vehicle.get().getPassengers());
        }
    }

    private static void assertRefused(Fixture f, Entity[] roots) {
        Throwable failure = assertThrows(IllegalStateException.class, () -> withLifecycle(roots, () -> {
            fail("a generation thread entered a published/player/owned lifecycle body");
            return false;
        }));
        assertSame(failure, f.pool.fatalFailureOrNull(),
                "off-owner gameplay refusal must retain fail-stop publication");
    }

    private static Object inScope(ServerLevel level, GenerationBody body) throws Exception {
        WorldGenEntityConstruction.enter(level);
        try {
            return body.get();
        } finally {
            WorldGenEntityConstruction.exit();
        }
    }

    private static Object withLifecycle(Entity[] roots, Supplier<Boolean> body) {
        return withLifecycle(roots, WorldGenEntityConstruction.owns(roots), body);
    }

    private static Object withLifecycle(
            Entity[] roots, boolean generationPrivateRide, Supplier<Boolean> body) {
        try {
            var method = EntityMixin.class.getDeclaredMethod("fabrolia$withPlayerLifecycleLease",
                    RegionalPlayerEntityTicksV2.LifecycleMutationKind.class,
                    Entity[].class, boolean.class, Supplier.class);
            method.setAccessible(true);
            return method.invoke(new LifecycleBody(),
                    RegionalPlayerEntityTicksV2.LifecycleMutationKind.SOFT_HANDOFF,
                    roots, generationPrivateRide, body);
        } catch (InvocationTargetException failure) {
            return GenerationEntityLifecycleTest.<Object, RuntimeException>rethrow(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T, X extends Throwable> T rethrow(Throwable failure) throws X {
        throw (X) failure;
    }

    @FunctionalInterface
    private interface GenerationBody {
        Object get() throws Exception;
    }

    private record Outcome(Object result, Throwable failure) {
        void rethrowFailure() {
            if (failure != null) GenerationEntityLifecycleTest.<Object, RuntimeException>rethrow(failure);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final RegionWorkerPool pool;
        final ServerLevel level = allocate(TestLevel.class);
        final Object previousPool = field(RegionScheduler.class, "pool").get(null);
        final Object previousActive = field(RegionScheduler.class, "active").get(null);
        final Object previousFatal = field(RegionScheduler.class, "orphanFatalFailure").get(null);
        final Object previousEnabled = field(RegionalPlayerEntityTicksV2.class, "enabled").get(null);

        Fixture() throws Exception {
            MinecraftServer server = allocate(TestDedicatedServer.class);
            field(MinecraftServer.class, "serverThread").set(server, Thread.currentThread());
            field(MinecraftServer.class, "levels").set(server, Map.of());
            field(BlockableEventLoop.class, "pendingRunnables").set(server,
                    new ConcurrentLinkedQueue<TickTask>());
            pool = new RegionWorkerPool(1, server);
            field(RegionScheduler.class, "pool").set(null, pool);
            field(RegionScheduler.class, "active").set(null, true);
            field(RegionScheduler.class, "orphanFatalFailure").set(null, null);
            // A NOOP player lease is deliberately not permission to mutate published
            // nonplayers. Keep player tick ownership absent to exercise that distinction.
            field(RegionalPlayerEntityTicksV2.class, "enabled").set(null, false);
            pool.start();
            pool.pauseExactTurnAndAwait(() -> {});
            assertTrue(pool.ownsPausedExactTurn(Thread.currentThread()));
            assertTrue(pool.ownsExclusiveGameplay(Thread.currentThread()));
        }

        EntityBody entity() { return new EntityBody(level); }

        void assertConstruction(GenerationBody body) throws InterruptedException {
            constructing(body).rethrowFailure();
        }

        Outcome constructing(GenerationBody body) throws InterruptedException {
            return generation(() -> inScope(level, body));
        }

        Outcome generation(GenerationBody body) throws InterruptedException {
            AtomicReference<Outcome> result = new AtomicReference<>();
            Thread generation = Thread.ofPlatform().daemon().name("worldgen-lifecycle-regression").start(() -> {
                try {
                    result.set(new Outcome(body.get(), null));
                } catch (Throwable failure) {
                    result.set(new Outcome(null, failure));
                }
            });
            generation.join(Duration.ofSeconds(5));
            assertFalse(generation.isAlive(), "generation-local mutation must not wait for Main's cut");
            return result.get();
        }

        @Override public void close() throws Exception {
            try {
                if (pool.ownsPausedExactTurn(Thread.currentThread())) pool.resumeExactTurn();
                if (!pool.isTerminated()) {
                    Throwable fatal = pool.fatalFailureOrNull();
                    if (fatal == null) pool.stop(pool::runExclusiveWorkerStep);
                    else assertSame(fatal, assertThrows(Throwable.class, pool::stop));
                }
            } finally {
                field(RegionScheduler.class, "pool").set(null, previousPool);
                field(RegionScheduler.class, "active").set(null, previousActive);
                field(RegionScheduler.class, "orphanFatalFailure").set(null, previousFatal);
                field(RegionalPlayerEntityTicksV2.class, "enabled").set(null, previousEnabled);
            }
        }
    }

    private static final class TestLevel extends ServerLevel {
        private int nextEntityId;
        private TestLevel() { super(null, null, null, null, null, null, false, 0L, null, false); }
        @Override public int getNextEntityId() { return ++nextEntityId; }
    }

    /** World shells are Unsafe-allocated; all nonplayer entity state is vanilla-initialized. */
    private static final class EntityBody extends Entity
            implements GuardedEntity, WaypointTransmitter {
        private final LifecycleBody guard = new LifecycleBody();

        EntityBody(Level level) { super(EntityTypes.PIG, level); }
        @Override public LifecycleBody guard() { return guard; }
        @Override public boolean isTransmittingWaypoint() { return true; }
        @Override public Optional<WaypointTransmitter.Connection> makeWaypointConnectionWith(ServerPlayer player) {
            throw new AssertionError("this fixture has no waypoint receivers");
        }
        @Override public Waypoint.Icon waypointIcon() {
            throw new AssertionError("this fixture has no waypoint receivers");
        }
        @Override protected void addPassenger(Entity passenger) {
            withLifecycle(new Entity[]{this, passenger}, () -> {
                super.addPassenger(passenger);
                return true;
            });
        }
        @Override public void removeVehicle() {
            Entity[] roots = new Entity[]{this};
            withLifecycle(roots, WorldGenEntityConstruction.owns(roots) && getVehicle() == null, () -> {
                super.removeVehicle();
                return true;
            });
        }
        @Override protected void removePassenger(Entity passenger) {
            withLifecycle(new Entity[]{this, passenger}, () -> {
                super.removePassenger(passenger);
                return true;
            });
        }
        @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
        @Override protected void readAdditionalSaveData(ValueInput input) {}
        @Override protected void addAdditionalSaveData(ValueOutput output) {}
        @Override public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
            throw new AssertionError("damage is not part of lifecycle entry");
        }
    }

    private static final class PlayerBody extends ServerPlayer implements GuardedEntity {
        private LifecycleBody guard;

        private PlayerBody() { super(null, null, null, null); }
        @Override public LifecycleBody guard() { return guard; }
    }

    /** Delegates the unwoven unit JVM's mixin interfaces to the real mixin state. */
    private interface GuardedEntity extends ParallelProcessor.TickGuard, RegionKeyed,
            EntityLevelCallbackAccessor, WorldGenEntityConstruction.Constructed {
        LifecycleBody guard();

        @Override default long fabrolia$constructionScope() { return guard().fabrolia$constructionScope(); }
        @Override default void fabrolia$clearConstructionScope() { guard().fabrolia$clearConstructionScope(); }
        @Override default EntityTickLease fabrolia$tickLease() { return guard().fabrolia$tickLease(); }
        @Override default byte fabrolia$getSyncCache() { return guard().fabrolia$getSyncCache(); }
        @Override default void fabrolia$setSyncCache(byte value) { guard().fabrolia$setSyncCache(value); }
        @Override default long fabrolia$getRegionKey() { return guard().fabrolia$getRegionKey(); }
        @Override default void fabrolia$setRegionKey(long key) { guard().fabrolia$setRegionKey(key); }
        @Override default boolean fabrolia$tryMarkRegionTick(long token) {
            return guard().fabrolia$tryMarkRegionTick(token);
        }
        @Override default boolean fabrolia$carryRegionTickMark(long oldToken, long newToken) {
            return guard().fabrolia$carryRegionTickMark(oldToken, newToken);
        }
        @Override default EntityInLevelCallback fabrolia$getLevelCallback() {
            try {
                return (EntityInLevelCallback) field(Entity.class, "levelCallback").get(this);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static final class LifecycleBody extends EntityMixin {
        @Override public boolean isOnPortalCooldown() { throw new AssertionError(); }
        @Override public int getPortalCooldown() { throw new AssertionError(); }
        @Override public Level level() { throw new AssertionError(); }
        @Override public double getX() { throw new AssertionError(); }
        @Override public double getY() { throw new AssertionError(); }
        @Override public double getZ() { throw new AssertionError(); }
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static <T> T allocate(Class<T> type) throws ReflectiveOperationException {
        return type.cast(((sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null))
                .allocateInstance(type));
    }
}
