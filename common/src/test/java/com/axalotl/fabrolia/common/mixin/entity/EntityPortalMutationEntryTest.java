package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.api.utils.SyncItemPickup;
import com.axalotl.fabrolia.api.utils.SyncItemPickup.PortalReservation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Real entry guards with stateful mutation bodies; not a transformed server/drop-engine test. */
class EntityPortalMutationEntryTest {
    private static final ThreadLocal<Object> resolver = new ThreadLocal<>();

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void reservedOrbCannotBeCopiedOrReceiveMergedExperienceBeforeConsumption() throws Exception {
        Orb receiver = allocate(Orb.class);
        receiver.count = 3;
        ReservedOrb source = allocate(ReservedOrb.class);
        source.count = 7;
        source.pending = true;
        Method merge = method(ExperienceOrbMixin.class, "fabrolia$guardReservedMerge",
                ExperienceOrb.class, Operation.class);
        Operation<Void> absorb = args -> {
            receiver.count += source.count;
            // The old removal-only guard rejects this consumption after count was copied.
            if (!source.pending) setRemovalReason(source, Entity.RemovalReason.DISCARDED);
            return null;
        };
        merge.invoke(receiver, source, absorb);
        merge.invoke(receiver, source, absorb);
        assertEquals(10, receiver.count + source.count);
        assertEquals(7, source.count, "the portal's accepted source must retain its collectible XP");

        source.pending = false;
        receiver.pending = true;
        merge.invoke(receiver, source, absorb);
        assertEquals(3, receiver.count, "a pending survivor cannot absorb a live source either");
        assertEquals(7, source.count);
        receiver.pending = false;
        setRemovalReason(receiver, Entity.RemovalReason.DISCARDED);
        merge.invoke(receiver, source, absorb);
        assertEquals(3, receiver.count, "a removed receiver cannot consume a still-live source");
        assertFalse(source.isRemoved());
        setRemovalReason(receiver, null);
        merge.invoke(receiver, source, absorb);
        assertEquals(10, receiver.count);
        assertTrue(source.isRemoved(), "normal merging must consume its source exactly once");
        // Vanilla discard leaves count intact. A stale scan list must not copy it twice.
        merge.invoke(receiver, source, absorb);
        assertEquals(10, receiver.count);
    }

    @Test
    void reservationAfterSearchRejectsSelectedOrbBeforeCountOrAgeWrites() throws Exception {
        ReservedOrb selected = allocate(ReservedOrb.class);
        selected.count = 4;
        selected.age = 200;
        selected.pending = true;
        assertFalse(existingMerge(args -> {
            CallbackInfoReturnable<Boolean> decision = select(selected);
            if (!decision.isCancelled()) {
                selected.count++;
                selected.age = 0;
            }
            return decision.getReturnValue();
        }));
        assertTrue(selected.guard.tryEnterReservation(), "native false return must release selected consumption");
        selected.guard.exitReservation();
        assertEquals(4, selected.count);
        assertEquals(200, selected.age);
        selected.pending = false;
        existingMerge(() -> {
            assertFalse(select(selected).isCancelled());
            selected.count++;
            selected.age = 0;
        });
        assertEquals(5, selected.count);
        assertEquals(0, selected.age);
    }

    @Test
    void containerDamageCannotSpillAcceptedContentsOrReduceHealthWhilePending() throws Exception {
        ItemSource source = new ItemSource();
        source.pending = true;
        ItemStack box = stack(Items.SHULKER_BOX, 1);
        ItemContainerContents contents = ItemContainerContents.fromItems(List.of(stack(Items.DIAMOND, 12)));
        box.set(DataComponents.CONTAINER, contents);
        List<ItemStack> drops = new ArrayList<>();
        int[] health = {5};
        Operation<Boolean> destroy = args -> {
            health[0] -= 100;
            ItemContainerContents spilled = box.set(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
            spilled.nonEmptyItemCopyStream().forEach(drops::add);
            return true;
        };
        Method damage = method(ItemEntityMixin.class, "fabrolia$guardReservedDamage",
                ServerLevel.class, DamageSource.class, float.class, Operation.class);
        assertFalse((boolean) damage.invoke(source, null, null, 100F, destroy));
        resolver.set(new Object());
        try {
            assertFalse((boolean) damage.invoke(source, null, null, 100F, destroy));
        } finally {
            resolver.remove();
        }
        assertEquals(5, health[0]);
        assertEquals(12, box.get(DataComponents.CONTAINER).nonEmptyItemCopyStream()
                .mapToInt(ItemStack::getCount).sum());
        assertTrue(drops.isEmpty(), "source-side destruction must not leak drops before transfer resolution");

        resolver.set(source);
        try {
            assertTrue((boolean) damage.invoke(source, null, null, 100F, destroy));
        } finally {
            resolver.remove();
        }
        assertEquals(ItemContainerContents.EMPTY, box.get(DataComponents.CONTAINER));
        assertEquals(12, drops.stream().mapToInt(ItemStack::getCount).sum());
        assertEquals(-95, health[0], "only the exact authorized transaction may change health");
        assertFalse((boolean) damage.invoke(source, null, null, 100F, destroy));
        assertEquals(-95, health[0], "leaving the resolver scope revokes destructive authority");
    }

    @Test
    void failedDirectMergeReopensBothEndpointReservations() throws Exception {
        Orb receiver = allocate(Orb.class);
        ReservedOrb source = allocate(ReservedOrb.class);
        RuntimeException failure = new RuntimeException("native merge failed");
        Operation<Void> merge = args -> {
            assertFalse(receiver.fabrolia$consumptionGuard().tryEnterReservation());
            assertFalse(source.guard.tryEnterReservation());
            throw failure;
        };
        assertSame(failure, assertThrows(RuntimeException.class, () ->
                invoke(ExperienceOrbMixin.class, "fabrolia$guardReservedMerge",
                        new Class<?>[]{ExperienceOrb.class, Operation.class}, receiver, source, merge)));
        assertTrue(receiver.fabrolia$consumptionGuard().tryEnterReservation());
        receiver.fabrolia$consumptionGuard().exitReservation();
        assertTrue(source.guard.tryEnterReservation());
        source.guard.exitReservation();
    }

    @Test
    void nestedSelectedMergeRestoresOuterConsumptionAndReleasesBothAfterFailure() throws Exception {
        ReservedOrb outer = allocate(ReservedOrb.class);
        ReservedOrb inner = allocate(ReservedOrb.class);
        RuntimeException failure = new RuntimeException("native merge failed");
        assertSame(failure, assertThrows(RuntimeException.class, () -> existingMerge(() -> {
            assertFalse(select(outer).isCancelled());
            assertFalse(outer.guard.tryEnterReservation());
            existingMerge(() -> {
                assertFalse(select(inner).isCancelled());
                assertFalse(inner.guard.tryEnterReservation());
                assertFalse(outer.guard.tryEnterReservation());
            });
            assertTrue(inner.guard.tryEnterReservation(), "nested completion must release its selected orb");
            inner.guard.exitReservation();
            assertFalse(outer.guard.tryEnterReservation(), "nested completion must retain outer consumption");
            throw failure;
        })));
        assertTrue(outer.guard.tryEnterReservation(), "exception must retire the outer selected guard");
        outer.guard.exitReservation();
        assertTrue(inner.guard.tryEnterReservation());
        inner.guard.exitReservation();
    }

    private static CallbackInfoReturnable<Boolean> select(ExperienceOrb selected) {
        CallbackInfoReturnable<Boolean> decision = new CallbackInfoReturnable<>("tryMergeToExisting", true);
        invoke(ExperienceOrbMixin.class, "fabrolia$guardSelectedMerge",
                new Class<?>[]{ServerLevel.class, Vec3.class, int.class,
                        CallbackInfoReturnable.class, ExperienceOrb.class},
                null, null, Vec3.ZERO, 1, decision, selected);
        return decision;
    }

    private static void existingMerge(Runnable body) {
        existingMerge(args -> { body.run(); return true; });
    }

    private static boolean existingMerge(Operation<Boolean> operation) {
        return (boolean) invoke(ExperienceOrbMixin.class, "fabrolia$syncExistingMerge",
                new Class<?>[]{ServerLevel.class, Vec3.class, int.class, Operation.class},
                null, null, Vec3.ZERO, 1, operation);
    }

    private static Object invoke(Class<?> owner, String name, Class<?>[] parameters,
                                 Object receiver, Object... arguments) {
        try {
            return method(owner, name, parameters).invoke(receiver, arguments);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static ItemStack stack(Item item, int count) {
        return new ItemStack(new Holder.Direct<>(item, DataComponentMap.builder()
                .set(DataComponents.MAX_STACK_SIZE, 64).build()), count);
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        T value = type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
        if (value instanceof Orb orb) {
            Field guard = ExperienceOrbMixin.class.getDeclaredField("fabrolia$guard");
            guard.setAccessible(true);
            guard.set(orb, new SyncItemPickup.Guard());
        }
        if (value instanceof ReservedOrb orb) orb.guard = new SyncItemPickup.Guard();
        return value;
    }

    private static void setRemovalReason(Entity entity, Entity.RemovalReason reason) {
        try {
            Field removal = Entity.class.getDeclaredField("removalReason");
            removal.setAccessible(true);
            removal.set(entity, reason);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class Orb extends ExperienceOrbMixin implements PortalReservation {
        boolean pending;
        int count;
        Orb() { super(null, null); }
        @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
            throw new AssertionError("unexpected entity construction");
        }
        @Override protected void readAdditionalSaveData(ValueInput input) {
            throw new AssertionError("unexpected entity load");
        }
        @Override protected void addAdditionalSaveData(ValueOutput output) {
            throw new AssertionError("unexpected entity save");
        }
        @Override public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
            throw new AssertionError("unexpected entity damage");
        }
        @Override public boolean fabrolia$isPortalPending() { return pending; }
        @Override public boolean fabrolia$isPortalResolutionOwner() { return resolver.get() == this; }
    }

    private static final class ReservedOrb extends ExperienceOrb implements PortalReservation, SyncItemPickup.Guarded {
        boolean pending;
        int count;
        int age;
        SyncItemPickup.Guard guard;
        @Override public SyncItemPickup.Guard fabrolia$consumptionGuard() { return guard; }
        ReservedOrb() { super(null, null); }
        @Override public boolean fabrolia$isPortalPending() { return pending; }
        @Override public boolean fabrolia$isPortalResolutionOwner() { return false; }
    }

    private static final class ItemSource extends ItemEntityMixin implements PortalReservation {
        boolean pending;
        @Override public boolean fabrolia$isPortalPending() { return pending; }
        @Override public boolean fabrolia$isPortalResolutionOwner() { return resolver.get() == this; }
    }
}
