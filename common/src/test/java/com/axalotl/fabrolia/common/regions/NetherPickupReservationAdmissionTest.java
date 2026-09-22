package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.api.utils.SyncItemPickup;
import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.mixin.entity.ExperienceOrbMixin;
import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real source capture, custody publication and consumption gates with controlled mutation bodies. */
@Isolated
class NetherPickupReservationAdmissionTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void busyOrbRejectsRootCaptureBeforeConsumptionCompletes() throws Exception {
        consumerWins(true, false);
    }

    @Test
    void busyOrbRejectsCustodyCaptureBeforeConsumptionCompletes() throws Exception {
        consumerWins(true, true);
    }

    @Test
    void busyItemRejectsRootCaptureBeforeConsumptionCompletes() throws Exception {
        consumerWins(false, false);
    }

    @Test
    void busyItemRejectsCustodyCaptureBeforeConsumptionCompletes() throws Exception {
        consumerWins(false, true);
    }

    private void consumerWins(boolean experience, boolean custody) throws Exception {
        try (Fixture fixture = new Fixture()) {
            Entity source = experience ? fixture.orb() : fixture.item();
            ItemStack stack = new ItemStack(new Holder.Direct<>(Items.DIAMOND,
                    DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build()), 7);
            if (source instanceof Item item) item.stack = stack;
            if (source instanceof Orb) field(ExperienceOrb.class, "count").setInt(source, 7);
            Object transfer = fixture.transfer(custody ? fixture.item() : source);
            if (custody) {
                fixture.prepareCustody(transfer, source);
                assertTrue(NetherPortalTransfers.isSourceMutationBlocked(source));
                assertTrue(NetherPortalPlayerAccess.needsMetadataSettlement(source),
                        "ordinary lifecycle callers must retain preparatory custody protection");
            }
            field(transfer.getClass(), "capturing").set(transfer, true);
            CountDownLatch entering = new CountDownLatch(1);
            AtomicReference<Boolean> accepted = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread capturer = new Thread(() -> {
                entering.countDown();
                try {
                    accepted.set((boolean) (custody
                            ? invoke(transfer, "captureCustody", new Class<?>[0])
                            : invoke(transfer, "captureTree", new Class<?>[]{Entity.class}, source)));
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            }, "pickup-source-capture");
            int[] awarded = {0};
            Runnable consume = () -> {
                capturer.start();
                await(entering);
                awaitFinished(capturer);
                if (failure.get() != null) throw new AssertionError(failure.get());
                assertEquals(Boolean.FALSE, accepted.get(), "busy admission must reject before the consumer releases");
                assertFalse(fixture.sources.containsKey(source.getUUID()));
                assertFalse(((ParallelProcessor.TickGuard) source).fabrolia$isPortalPending());
                assertFalse(NetherPortalTransfers.isSourceMutationBlocked(source),
                        "preparatory player custody must not revoke an admitted consumer's discard");
                assertFalse(NetherPortalPlayerAccess.needsMetadataSettlement(source),
                        "native discard must not request a Main fence for its own admitted consumption");
                if (source instanceof Item item) {
                    awarded[0] += item.getItem().getCount();
                    item.getItem().setCount(0);
                } else {
                    awarded[0] += orbCount((ExperienceOrb) source);
                    setOrbCount((ExperienceOrb) source, 0);
                }
                NetherPortalPlayerAccess.withRidePublication(new Entity[]{source}, () -> {
                    remove(source);
                    return null;
                });
            };
            try {
                if (experience) orbTransaction((ExperienceOrb) source, consume);
                else SyncItemPickup.wrap(null, (ItemEntity) source, consume);
            } finally {
                capturer.join(5_000L);
            }
            assertFalse(capturer.isAlive(), "source admission did not retire after the consumer");
            if (failure.get() != null) throw new AssertionError(failure.get());
            assertEquals(Boolean.FALSE, accepted.get(), "a consumed source must not enter a portal request");
            assertEquals(7, awarded[0]);
            assertEquals(7, awarded[0] + (source instanceof Item item
                    ? item.getItem().getCount() : orbCount((ExperienceOrb) source)));
            assertTrue(source.isRemoved());
            assertFalse(((ParallelProcessor.TickGuard) source).fabrolia$isPortalPending());
            assertFalse(fixture.sources.containsKey(source.getUUID()));
            fixture.cancel(transfer);
            invoke(transfer, "finishCapture", new Class<?>[0]);
            assertTrue(fixture.sources.isEmpty(), "a rejected custody member must release the earlier root too");
        }
    }

    @Test
    void reservationFirstRejectsExistingOrbAwardAndItemPickupBeforeMutation() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Orb orb = fixture.orb();
            Item item = fixture.item();
            Object orbTransfer = fixture.transfer(orb);
            Object itemTransfer = fixture.transfer(item);
            assertTrue((boolean) invoke(orbTransfer, "captureTree", new Class<?>[]{Entity.class}, orb));
            assertTrue((boolean) invoke(itemTransfer, "captureTree", new Class<?>[]{Entity.class}, item));
            setOrbCount(orb, 4);
            int[] inventory = {0};
            existingOrbMerge(() -> {
                CallbackInfoReturnable<Boolean> decision = select(orb);
                if (!decision.isCancelled()) setOrbCount(orb, orbCount(orb) + 1);
                assertTrue(decision.isCancelled());
                assertFalse(decision.getReturnValue());
            });
            Runnable pickup = () -> {
                inventory[0] += item.getItem().getCount();
                item.getItem().setCount(0);
            };
            SyncItemPickup.wrap(null, item, pickup);
            assertEquals(4, orbCount(orb));
            assertEquals(7, item.getItem().getCount());
            assertEquals(0, inventory[0]);
            fixture.cancel(orbTransfer);
            fixture.cancel(itemTransfer);
            SyncItemPickup.wrap(null, item, pickup);
            assertEquals(7, inventory[0], "cleanup must reopen the same item's consumption admission");
            assertEquals(7, inventory[0] + item.getItem().getCount());
        }
    }

    @Test
    void ownItemTickCanCapturePassengersAndOverlappingSourceDoesNotWaitForAnotherRoot() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Item root = fixture.item();
            Item passenger = fixture.item();
            field(Entity.class, "passengers").set(root, ImmutableList.of(passenger));
            Object transfer = fixture.transfer(root);
            SyncItemPickup.wrapTick(root, () -> assertTrue((boolean) call(
                    method(transfer.getClass(), "captureTree", Entity.class), transfer, root)));
            assertTrue(root.pending);
            assertTrue(passenger.pending);
            Object overlap = fixture.transfer(passenger);
            passenger.guard.enterActivity();
            try {
                AtomicReference<Object> result = new AtomicReference<>();
                Thread contender = new Thread(() -> result.set(call(
                        method(overlap.getClass(), "captureTree", Entity.class), overlap, passenger)));
                contender.start();
                contender.join(5_000L);
                assertFalse(contender.isAlive(), "overlapping capture waited on the other source's root monitor");
                assertEquals(Boolean.FALSE, result.get());
            } finally {
                passenger.guard.exitActivity();
            }
            fixture.cancel(overlap);
            assertTrue(root.pending);
            assertTrue(passenger.pending, "losing cleanup must not clear the winning source's pending flag");
            fixture.cancel(transfer);
            assertFalse(root.pending);
            assertFalse(passenger.pending);
        }
    }

    @Test
    void cancellationAfterBusyCustodyRejectionReleasesEarlierRootWhenCaptureFinishes() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Item root = fixture.item();
            Orb source = fixture.orb();
            Object transfer = fixture.transfer(root);
            fixture.prepareCustody(transfer, source);
            field(transfer.getClass(), "capturing").set(transfer, true);
            AtomicReference<Object> accepted = new AtomicReference<>();
            Thread capturer = new Thread(() -> accepted.set(call(
                    method(transfer.getClass(), "captureCustody"), transfer)));
            try {
                orbTransaction(source, () -> {
                    capturer.start();
                    awaitFinished(capturer);
                    assertEquals(Boolean.FALSE, accepted.get());
                    fixture.cancel(transfer);
                    assertTrue(root.pending, "cancellation cannot release mutable construction custody");
                });
            } finally {
                capturer.join(5_000L);
            }
            assertFalse(capturer.isAlive());
            assertEquals(Boolean.FALSE, accepted.get());
            assertFalse(source.pending);
            invoke(transfer, "finishCapture", new Class<?>[0]);
            assertFalse(root.pending);
            assertTrue(fixture.sources.isEmpty());
        }
    }

    @Test
    void ownConsumptionCannotReserveButActivityCanAfterNestedFailure() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Item item = fixture.item();
            Object transfer = fixture.transfer(item);
            RuntimeException failure = new RuntimeException("pickup failed");
            SyncItemPickup.wrapTick(item, () -> {
                assertSame(failure, assertThrows(RuntimeException.class, () ->
                        SyncItemPickup.wrap(null, item, () -> {
                            SyncItemPickup.wrap(null, item, () -> assertFalse((boolean) invoke(
                                    transfer, "captureTree", new Class<?>[]{Entity.class}, item)));
                            assertFalse((boolean) invoke(transfer, "captureTree",
                                    new Class<?>[]{Entity.class}, item));
                            assertFalse(item.pending);
                            assertFalse(fixture.sources.containsKey(item.getUUID()));
                            throw failure;
                        })));
                assertTrue((boolean) invoke(transfer, "captureTree", new Class<?>[]{Entity.class}, item),
                        "failed nested consumption must restore the enclosing tick's reservation admission");
            });
            fixture.cancel(transfer);
            assertFalse(item.pending);
            SyncItemPickup.wrap(null, item, () -> assertTrue(SyncItemPickup.holdsUnreservedConsumption(item)));
        }
    }

    @Test
    void admittedTickCanExpireDuringPreparatoryCustodyWithoutAsyncOwners() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertFalse(AsyncIslandPlayerTicks.enabledForAsync());
            Item root = fixture.item();
            Item expiring = fixture.item();
            Object transfer = fixture.transfer(root);
            fixture.prepareCustody(transfer, expiring);
            assertTrue(NetherPortalPlayerAccess.needsMetadataSettlement(expiring));
            SyncItemPickup.wrapTick(expiring, () -> {
                var scope = AsyncIslandPlayerLifecycle.begin(
                        RegionalPlayerEntityTicksV2.LifecycleMutationKind.HARD_EXIT, expiring);
                try {
                    remove(expiring);
                } finally {
                    assertNull(scope.finish(null));
                }
            });
            assertTrue(expiring.isRemoved());
            assertFalse((boolean) invoke(transfer, "captureCustody", new Class<?>[0]));
            assertFalse(expiring.pending);
            fixture.cancel(transfer);
            assertFalse(root.pending);
            assertTrue(fixture.sources.isEmpty());
        }
    }

    @Test
    void busyPassengerActivityRejectsCaptureWithoutWaitingAndReleasesPartialRoot() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Item root = fixture.item();
            Orb passenger = fixture.orb();
            field(Entity.class, "passengers").set(root, ImmutableList.of(passenger));
            Object transfer = fixture.transfer(root);
            AtomicReference<Object> accepted = new AtomicReference<>();
            Thread capturer = new Thread(() -> accepted.set(invoke(
                    transfer, "captureTree", new Class<?>[]{Entity.class}, root)));
            SyncItemPickup.wrapTick(passenger, () -> {
                capturer.start();
                awaitFinished(capturer);
                assertEquals(Boolean.FALSE, accepted.get());
                assertFalse(passenger.pending);
                assertFalse(fixture.sources.containsKey(passenger.getUUID()));
                fixture.cancel(transfer);
                assertFalse(root.pending, "a rejected passenger must not strand the earlier root");
                assertTrue(fixture.sources.isEmpty());
            });
            capturer.join(5_000L);
        }
    }

    private static int orbCount(ExperienceOrb orb) {
        try {
            return field(ExperienceOrb.class, "count").getInt(orb);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void setOrbCount(ExperienceOrb orb, int count) {
        try {
            field(ExperienceOrb.class, "count").setInt(orb, count);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void orbTransaction(ExperienceOrb orb, Runnable body) {
        existingOrbMerge(() -> {
            CallbackInfoReturnable<Boolean> decision = select(orb);
            if (!decision.isCancelled()) body.run();
        });
    }

    private static CallbackInfoReturnable<Boolean> select(ExperienceOrb orb) {
        CallbackInfoReturnable<Boolean> decision = new CallbackInfoReturnable<>("tryMergeToExisting", true);
        call(method(ExperienceOrbMixin.class, "fabrolia$guardSelectedMerge", ServerLevel.class,
                Vec3.class, int.class, CallbackInfoReturnable.class, ExperienceOrb.class),
                null, null, Vec3.ZERO, 1, decision, orb);
        return decision;
    }

    private static void existingOrbMerge(Runnable body) {
        Operation<Boolean> operation = args -> { body.run(); return true; };
        call(method(ExperienceOrbMixin.class, "fabrolia$syncExistingMerge", ServerLevel.class,
                Vec3.class, int.class, Operation.class), null, null, Vec3.ZERO, 1, operation);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void awaitFinished(Thread thread) {
        try {
            thread.join(5_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
        assertFalse(thread.isAlive(), "capture must reject while the activity or consumer still owns its guard");
    }

    private static void remove(Entity entity) {
        try {
            field(Entity.class, "removalReason").set(entity, Entity.RemovalReason.DISCARDED);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        final Class<?> transferType = Class.forName(NetherPortalTransfers.class.getName() + "$Transfer");
        final ArrayList<Object> transfers = new ArrayList<>();
        final Map<UUID, Object> sources;

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            sources = (Map<UUID, Object>) field(NetherPortalTransfers.class, "SOURCES").get(null);
            assertTrue(sources.isEmpty());
        }

        Item item() throws Exception {
            Item item = entity(Item.class);
            item.stack = new ItemStack(new Holder.Direct<>(Items.DIAMOND,
                    DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build()), 7);
            return item;
        }
        Orb orb() throws Exception { return entity(Orb.class); }

        private <T extends Entity> T entity(Class<T> type) throws Exception {
            T entity = type.cast(unsafe.allocateInstance(type));
            field(Entity.class, "uuid").set(entity, UUID.randomUUID());
            field(Entity.class, "passengers").set(entity, ImmutableList.of());
            field(type, "guard").set(entity, new SyncItemPickup.Guard());
            return entity;
        }

        Object transfer(Entity root) throws Exception {
            Constructor<?> constructor = transferType.getDeclaredConstructor(
                    Entity.class, ServerLevel.class, EpochDomain.class, long.class);
            constructor.setAccessible(true);
            Object transfer = constructor.newInstance(root, null, null, 0L);
            transfers.add(transfer);
            return transfer;
        }

        void prepareCustody(Object transfer, Entity source) throws Exception {
            Entity root = (Entity) field(transferType, "root").get(transfer);
            assertTrue((boolean) invoke(transfer, "captureTree", new Class<?>[]{Entity.class}, root));
            // Expose a second identity only to the real player-custody capture,
            // so CAPTURED publication genuinely precedes captureCustody's pending cut.
            field(Entity.class, "passengers").set(root, ImmutableList.of(source));
            NetherPortalPlayerAccess.Reservation players = NetherPortalPlayerAccess.reserve(root, transfer);
            assertNotNull(players);
            field(transferType, "players").set(transfer, players);
            assertSame(transfer, NetherPortalPlayerAccess.requestForLifecycle(source));
        }

        void cancel(Object transfer) {
            call(method(transferType, "cancel", Throwable.class), transfer, (Object) null);
        }

        @Override public void close() {
            for (Object transfer : transfers) {
                cancel(transfer);
                call(method(transferType, "finishCapture"), transfer);
            }
            assertTrue(sources.isEmpty());
        }
    }

    private interface Source extends ParallelProcessor.TickGuard, SyncItemPickup.Guarded {
        @Override default EntityTickLease fabrolia$tickLease() { throw new AssertionError("not ticking"); }
        @Override default byte fabrolia$getSyncCache() { return 0; }
        @Override default void fabrolia$setSyncCache(byte value) {}
    }

    private static final class Item extends ItemEntity implements Source {
        volatile boolean pending;
        SyncItemPickup.Guard guard;
        ItemStack stack;
        @Override public ItemStack getItem() { return stack; }
        @Override public SyncItemPickup.Guard fabrolia$consumptionGuard() { return guard; }
        Item() { super(null, null); }
        @Override public Vec3 position() { return Vec3.ZERO; }
        @Override public BlockPos blockPosition() { return BlockPos.ZERO; }
        @Override public Vec3 getDeltaMovement() { return Vec3.ZERO; }
        @Override public Pose getPose() { return Pose.STANDING; }
        @Override public boolean fabrolia$isPortalPending() { return pending; }
        @Override public void fabrolia$setPortalPending(boolean value) { pending = value; }
    }

    private static final class Orb extends ExperienceOrb implements Source {
        volatile boolean pending;
        SyncItemPickup.Guard guard;
        @Override public SyncItemPickup.Guard fabrolia$consumptionGuard() { return guard; }
        Orb() { super(null, null); }
        @Override public Vec3 position() { return Vec3.ZERO; }
        @Override public BlockPos blockPosition() { return BlockPos.ZERO; }
        @Override public Vec3 getDeltaMovement() { return Vec3.ZERO; }
        @Override public Pose getPose() { return Pose.STANDING; }
        @Override public boolean fabrolia$isPortalPending() { return pending; }
        @Override public void fabrolia$setPortalPending(boolean value) { pending = value; }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) {
        try {
            Method method = type.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static Object invoke(Object target, String name, Class<?>[] parameters, Object... args) {
        return call(method(target.getClass(), name, parameters), target, args);
    }

    private static Object call(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
