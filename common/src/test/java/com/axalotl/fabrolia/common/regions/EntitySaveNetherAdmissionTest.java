package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.api.utils.SyncItemPickup;
import com.axalotl.fabrolia.api.fastutil.Long2IntConcurrentHashMap;
import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.mixin.server.EntitySectionSaveMixin;
import com.axalotl.fabrolia.common.mixin.world.ServerLevelEntityManagerAccessor;
import com.axalotl.fabrolia.common.parallelised.utils.TickingChunksAccess;
import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** The production save wrapper and lifecycle entry, with an already-held local save claim. */
@Isolated
class EntitySaveNetherAdmissionTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void arrivalMetadataDefersTheWholeSaveUntilOrderedSettlement() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Entity source = fixture.entity();
            NetherPortalPlayerAccess.Reservation reservation = NetherPortalPlayerAccess.reserve(source, new Object());
            assertNotNull(reservation);
            fixture.arrival(reservation);
            AtomicInteger order = new AtomicInteger();
            assertTrue(reservation.deferMetadata(() -> assertEquals(0, order.getAndIncrement())));
            try {
                assertFalse(fixture.save(args -> {
                    var scope = AsyncIslandPlayerLifecycle.begin(
                            RegionalPlayerEntityTicksV2.LifecycleMutationKind.HARD_EXIT, fixture.root);
                    try {
                        order.incrementAndGet();
                        return true;
                    } finally {
                        assertNull(scope.finish(null));
                    }
                }), "unsettled arrival must defer before serialization or removal");
                assertEquals(0, order.get());
                assertTrue(NetherPortalPlayerAccess.needsMetadataSettlement(fixture.root));
                fixture.releaseClaim();
                Method drain = NetherPortalPlayerAccess.Reservation.class.getDeclaredMethod("drainOutbox");
                drain.setAccessible(true);
                drain.invoke(reservation);
                assertTrue(NetherPortalPlayerAccess.needsMetadataSettlement(fixture.root));
                fixture.claim();
                assertFalse(fixture.save(args -> {
                    order.incrementAndGet();
                    return true;
                }), "drained callbacks still retain the previous source reservation");
                assertEquals(1, order.get());
                reservation.cancel(null);
                assertTrue(fixture.save(args -> {
                    var scope = AsyncIslandPlayerLifecycle.begin(
                            RegionalPlayerEntityTicksV2.LifecycleMutationKind.HARD_EXIT, fixture.root);
                    try {
                        assertEquals(1, order.getAndIncrement());
                        return true;
                    } finally {
                        assertNull(scope.finish(null));
                    }
                }));
                assertEquals(2, order.get());
            } finally {
                reservation.cancel(null);
            }
        }
    }

    @Test
    void capturedSourceDefersWithoutConsumingTheUnloadRetryLease() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var reservation = NetherPortalPlayerAccess.reserve(fixture.root, new Object());
            assertNotNull(reservation);
            var attempt = IslandUnitAdmission.beginEntityUnloadAttempt(fixture.manager, 0L, true);
            try {
                assertFalse(fixture.save(args -> fail("captured source reached serialization")));
                assertFalse(IslandUnitAdmission.entityUnloadOwnershipYielded(attempt),
                        "metadata exclusion is not queue starvation");
                assertTrue(NetherPortalPlayerAccess.needsMetadataSettlement(fixture.root));
            } finally {
                IslandUnitAdmission.endEntityUnloadAttempt(attempt);
                reservation.cancel(null);
            }
        }
    }

    @Test
    void saveExcludesNewCaptureUntilItsBodyReturnsButNotUnrelatedTransfers() throws Exception {
        try (Fixture fixture = new Fixture(); var remote = Executors.newSingleThreadExecutor()) {
            Entity unrelated = fixture.entity();
            assertTrue(fixture.save(args -> {
                try {
                    var overlap = remote.submit(() -> NetherPortalPlayerAccess.reserve(fixture.root, new Object()))
                            .get(5, TimeUnit.SECONDS);
                    if (overlap != null) overlap.cancel(null);
                    assertNull(overlap, "a foreign reservation crossed the active save body");
                    var independent = remote.submit(() -> NetherPortalPlayerAccess.reserve(unrelated, new Object()))
                            .get(5, TimeUnit.SECONDS);
                    assertNotNull(independent, "save admission must not fence unrelated entities");
                    independent.cancel(null);
                    return true;
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }));
            var afterSave = NetherPortalPlayerAccess.reserve(fixture.root, new Object());
            assertNotNull(afterSave, "normal return must reopen capture admission");
            afterSave.cancel(null);
        }
    }

    @Test
    void staticPoolStillDefersCapturedEntitySave() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.runtime.asyncActive = false;
            var reservation = NetherPortalPlayerAccess.reserve(fixture.root, new Object());
            assertNotNull(reservation);
            try {
                assertFalse(fixture.save(args -> fail("static compatibility capture reached serialization")));
            } finally {
                reservation.cancel(null);
            }
        }
    }

    @Test
    void fullSaveClassificationIsExactNestedAndExceptionSafe() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var otherManager = new TestManager();
            Entity other = fixture.entity();
            field(Entity.class, "uuid").set(other, new UUID(0L, 72L));
            otherManager.storage.getOrCreateSection(0L).add(other);
            var first = NetherPortalPlayerAccess.reserve(fixture.root, new Object());
            var second = NetherPortalPlayerAccess.reserve(other, new Object());
            assertNotNull(first);
            assertNotNull(second);
            try {
                assertNull(NetherPortalPlayerAccess.tryBeginEntitySave(fixture.manager, 0L));
                var failure = new IllegalArgumentException("full save failed");
                assertSame(failure, assertThrows(IllegalArgumentException.class, () ->
                        IslandUnitAdmission.runFullEntitySave(fixture.manager, () -> {
                            try (var admission = NetherPortalPlayerAccess.tryBeginEntitySave(fixture.manager, 0L)) {
                                assertNotNull(admission, "full save cannot retry optional deferral inside its blocking loop");
                            }
                            assertNull(NetherPortalPlayerAccess.tryBeginEntitySave(otherManager, 0L));
                            IslandUnitAdmission.runFullEntitySave(otherManager, () -> {
                                assertNull(NetherPortalPlayerAccess.tryBeginEntitySave(fixture.manager, 0L));
                                try (var admission = NetherPortalPlayerAccess.tryBeginEntitySave(otherManager, 0L)) {
                                    assertNotNull(admission);
                                }
                            });
                            try (var admission = NetherPortalPlayerAccess.tryBeginEntitySave(fixture.manager, 0L)) {
                                assertNotNull(admission, "nested return must restore the enclosing manager");
                            }
                            throw failure;
                        })));
                assertNull(NetherPortalPlayerAccess.tryBeginEntitySave(fixture.manager, 0L));
                assertNull(NetherPortalPlayerAccess.tryBeginEntitySave(otherManager, 0L));
            } finally {
                first.cancel(null);
                second.cancel(null);
            }
        }
    }

    private static final class GuardedItem extends ItemEntity implements SyncItemPickup.Guarded, ParallelProcessor.TickGuard {
        SyncItemPickup.Guard guard;
        EntityTickLease lease;
        byte syncCache;
        private GuardedItem() { super(null, null); }
        @Override public SyncItemPickup.Guard fabrolia$consumptionGuard() { return guard; }
        @Override public EntityTickLease fabrolia$tickLease() { return lease; }
        @Override public byte fabrolia$getSyncCache() { return syncCache; }
        @Override public void fabrolia$setSyncCache(byte value) { syncCache = value; }
    }

    private static final class Fixture implements AutoCloseable {
        final sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        final Object oldPool = field(RegionScheduler.class, "pool").get(null);
        final Object oldActive = field(RegionScheduler.class, "active").get(null);
        final Object oldFailure = field(RegionScheduler.class, "orphanFatalFailure").get(null);
        final Object oldServer = field(ParallelProcessor.class, "server").get(null);
        final TestLevel level;
        final RegionizedServer runtime;
        final IslandRegistry.PIsland owner = new IslandRegistry.PIsland(1L, 0L);
        final TestManager manager = new TestManager();
        final Entity root;
        final Map<ServerLevel, RegionizedServer> levels;

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            assertNull(oldPool);
            MinecraftServer server = (MinecraftServer) unsafe.allocateInstance(com.axalotl.fabrolia.common.regions.TestDedicatedServer.class);
            field(MinecraftServer.class, "serverThread").set(server, Thread.currentThread());
            field(ParallelProcessor.class, "server").set(null, server);
            level = (TestLevel) unsafe.allocateInstance(TestLevel.class);
            field(ServerLevel.class, "server").set(level, server);
            runtime = new RegionizedServer(level, new RegionGrid(3));
            runtime.asyncActive = true;
            owner.level = level;
            ((Long2ObjectOpenHashMap<IslandRegistry.PIsland>) field(IslandRegistry.class, "islands")
                    .get(runtime.islandRegistry)).put(owner.id, owner);
            levels = (Map<ServerLevel, RegionizedServer>) field(RegionScheduler.class, "PER_LEVEL").get(null);
            levels.put(level, runtime);
            RegionWorkerPool pool = new RegionWorkerPool(1);
            field(RegionWorkerPool.class, "running").setBoolean(pool, true);
            field(RegionWorkerPool.class, "accepting").setBoolean(pool, true);
            field(RegionScheduler.class, "pool").set(null, pool);
            field(RegionScheduler.class, "active").setBoolean(null, true);
            field(RegionScheduler.class, "orphanFatalFailure").set(null, null);
            root = entity();
            manager.storage.getOrCreateSection(0L).add(root);
            claim();
        }

        Entity entity() throws Exception {
            GuardedItem entity = (GuardedItem) unsafe.allocateInstance(GuardedItem.class);
            entity.guard = new SyncItemPickup.Guard();
            entity.lease = new EntityTickLease();
            field(Entity.class, "level").set(entity, level);
            field(Entity.class, "uuid").set(entity, new UUID(0L, 71L));
            field(Entity.class, "passengers").set(entity, ImmutableList.of());
            field(Entity.class, "blockPosition").set(entity, BlockPos.ZERO);
            return entity;
        }

        @SuppressWarnings("unchecked")
        void arrival(NetherPortalPlayerAccess.Reservation reservation) throws Exception {
            ((List<Entity>) field(NetherPortalPlayerAccess.Reservation.class, "metadataArrivals")
                    .get(reservation)).add(root);
            Object cameras = field(NetherPortalPlayerAccess.class, "CAMERAS").get(null);
            synchronized (cameras) {
                ((Map<Entity, NetherPortalPlayerAccess.Reservation>) field(
                        NetherPortalPlayerAccess.class, "ARRIVAL_METADATA").get(null)).put(root, reservation);
            }
        }

        void claim() {
            assertTrue(owner.unitClaim.compareAndSet(false, true));
            owner.noteClaim("entitySave");
        }

        void releaseClaim() {
            owner.clearClaim();
            owner.unitClaim.set(false);
        }

        boolean save(Operation<Boolean> body) throws Exception {
            AtomicBoolean finished = new AtomicBoolean();
            Thread watchdog = Thread.ofPlatform().daemon().start(() -> {
                while (!finished.get()) {
                    if (runtime.globalFenceIntentReceipt() != null) {
                        RegionScheduler.publishFatalAndSelectWinner(new AssertionError(
                                "entity save attempted a global fence while holding its own claim"));
                        return;
                    }
                    Thread.onSpinWait();
                }
            });
            try {
                Method method = EntitySectionSaveMixin.class.getDeclaredMethod(
                        "fabrolia$ownerSafeEntitySave", long.class, Consumer.class, Operation.class);
                method.setAccessible(true);
                return (Boolean) method.invoke(manager, 0L, (Consumer<Entity>) ignored -> {}, body);
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof Error error) throw error;
                if (failure.getCause() instanceof Exception exception) throw exception;
                throw failure;
            } finally {
                finished.set(true);
                watchdog.join(5_000L);
                assertFalse(watchdog.isAlive());
            }
        }

        @Override public void close() throws Exception {
            releaseClaim();
            levels.remove(level, runtime);
            field(RegionScheduler.class, "pool").set(null, oldPool);
            field(RegionScheduler.class, "active").set(null, oldActive);
            field(RegionScheduler.class, "orphanFatalFailure").set(null, oldFailure);
            field(ParallelProcessor.class, "server").set(null, oldServer);
        }
    }

    private static final class TestManager extends EntitySectionSaveMixin implements TickingChunksAccess {
        final EntitySectionStorage<EntityAccess> storage = new EntitySectionStorage<>(EntityAccess.class, ignored -> Visibility.HIDDEN);
        @Override public Long2IntConcurrentHashMap fabrolia$tickingChunks() { throw new AssertionError(); }
        @Override public EntitySectionStorage<EntityAccess> fabrolia$sectionStorage() { return storage; }
        @Override public void fabrolia$finishStatusTransitions(boolean healthy) { throw new AssertionError(); }
    }

    private static final class TestLevel extends ServerLevel implements ServerLevelEntityManagerAccessor {
        private TestLevel() { super(null, null, null, null, null, null, false, 0L, null, false); }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
        @Override public PersistentEntitySectionManager<Entity> fabrolia$entityManager() { return null; }
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
