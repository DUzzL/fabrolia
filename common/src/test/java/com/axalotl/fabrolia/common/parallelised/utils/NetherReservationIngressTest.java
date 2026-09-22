package com.axalotl.fabrolia.common.parallelised.utils;

import com.axalotl.fabrolia.api.utils.SyncItemPickup;
import com.axalotl.fabrolia.api.utils.SyncItemPickup.PortalReservation;
import com.axalotl.fabrolia.common.mixin.entity.ContainerMenuPortalReservationMixin;
import com.axalotl.fabrolia.common.mixin.entity.ContainerVehicleInventoryReservationMixin;
import com.axalotl.fabrolia.common.mixin.entity.EntityRidePortalReservationMixin;
import com.axalotl.fabrolia.common.mixin.entity.LivingDamageOverridePortalReservationMixin;
import com.axalotl.fabrolia.common.mixin.world.HopperPortalReservationMixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.Hopper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Executes production entry guards, not a transformed server or the full vanilla mutation body. */
class NetherReservationIngressTest {
    private static final ThreadLocal<Object> resolver = new ThreadLocal<>();

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void hopperRejectsBothDirectionsBeforeLiveStacksAreSplitOrMerged() throws Exception {
        ReservedContainer vehicle = new ReservedContainer();
        ItemStack live = stack(9);
        vehicle.setItem(0, live);
        ItemStack offered = stack(3);
        Method insert = method(HopperPortalReservationMixin.class, "fabrolia$guardReservedInsertion",
                Container.class, Container.class, ItemStack.class, Direction.class, Operation.class);
        Operation<ItemStack> merge = args -> {
            live.grow(offered.getCount());
            offered.setCount(0);
            return ItemStack.EMPTY;
        };
        assertSame(offered, insert.invoke(null, null, vehicle, offered, Direction.UP, merge));
        assertEquals(9, live.getCount());
        assertEquals(3, offered.getCount());
        assertSame(live, vehicle.getItem(0), "reservation must leave the indexed/save-visible inventory intact");

        Method extract = method(HopperPortalReservationMixin.class, "fabrolia$guardReservedExtraction",
                Hopper.class, Container.class, int.class, Direction.class, Operation.class);
        SimpleContainer destination = new SimpleContainer(1);
        Operation<Boolean> take = args -> {
            destination.setItem(0, vehicle.removeItem(0, 1));
            return true;
        };
        assertFalse((boolean) extract.invoke(null, null, vehicle, 0, Direction.DOWN, take));
        assertTrue(destination.isEmpty());
        assertEquals(9, live.getCount());
        vehicle.pending = false;
        assertTrue((boolean) extract.invoke(null, null, vehicle, 0, Direction.DOWN, take));
        assertEquals(8, live.getCount());
        assertEquals(1, destination.getItem(0).getCount());
        assertEquals(9, live.getCount() + destination.getItem(0).getCount());
    }

    @Test
    void aMenuClickCannotBorrowAnotherReservedSlotOrResumeAnOldDrag() throws Exception {
        ReservedContainer first = new ReservedContainer();
        ReservedContainer second = new ReservedContainer();
        ItemStack live = stack(7);
        ItemStack carried = stack(4);
        second.setItem(0, live);
        Menu menu = new Menu();
        menu.slots = NonNullList.create();
        menu.slots.add(new Slot(new SimpleContainer(1), 0, 0, 0));
        menu.slots.add(new Slot(first, 0, 0, 0));
        menu.slots.add(new Slot(second, 0, 0, 0));
        Method click = method(ContainerMenuPortalReservationMixin.class, "fabrolia$guardReservedClick",
                int.class, int.class, ContainerInput.class, Player.class, Operation.class);
        Operation<Void> mergeNonClickedSlot = args -> {
            live.grow(carried.getCount());
            carried.setCount(0);
            return null;
        };
        resolver.set(first);
        try {
            click.invoke(menu, 0, 0, ContainerInput.QUICK_MOVE, null, mergeNonClickedSlot);
            assertEquals(7, live.getCount(), "resolving one slot cannot authorize another exact identity");
            assertEquals(4, carried.getCount());
            assertFalse(menu.dragging);
            first.pending = false;
            resolver.set(second);
            click.invoke(menu, 0, 0, ContainerInput.QUICK_MOVE, null, mergeNonClickedSlot);
            assertEquals(11, live.getCount());
            assertEquals(0, carried.getCount());
        } finally {
            resolver.remove();
        }
        assertSame(live, second.getItem(0));
    }

    @Test
    void pendingLivingDamageCannotConsumeHealthOrEquipmentAndExactResolverExitRevokesIt() throws Exception {
        Living source = new Living();
        ItemStack equipment = stack(2);
        int[] health = {20};
        Method damage = method(LivingDamageOverridePortalReservationMixin.class, "fabrolia$guardReservedDamage",
                ServerLevel.class, DamageSource.class, float.class, Operation.class);
        Operation<Boolean> hit = args -> { health[0] -= 5; equipment.shrink(1); return true; };
        resolver.set(new Object());
        try {
            assertFalse((boolean) damage.invoke(source, null, null, 5F, hit));
            assertEquals(20, health[0]);
            assertEquals(2, equipment.getCount());
            resolver.set(source);
            assertTrue((boolean) damage.invoke(source, null, null, 5F, hit));
        } finally {
            resolver.remove();
        }
        assertFalse((boolean) damage.invoke(source, null, null, 5F, hit));
        assertEquals(15, health[0]);
        assertEquals(1, equipment.getCount());
    }

    @Test
    void forcedMountCannotDetachFromReservedOldVehicleOrAttachToReservedNewVehicle() throws Exception {
        ReservedEntity oldVehicle = entity();
        ReservedEntity nextVehicle = entity();
        Ride rider = new Ride();
        rider.vehicle = oldVehicle;
        Method mount = method(EntityRidePortalReservationMixin.class, "fabrolia$guardReservedMount",
                Entity.class, boolean.class, boolean.class, Operation.class);
        Operation<Boolean> moveEdge = args -> { rider.vehicle = (Entity) args[0]; return true; };
        oldVehicle.pending = true;
        assertFalse((boolean) mount.invoke(rider, nextVehicle, true, true, moveEdge));
        assertSame(oldVehicle, rider.vehicle);
        oldVehicle.pending = false;
        nextVehicle.pending = true;
        assertFalse((boolean) mount.invoke(rider, nextVehicle, true, true, moveEdge));
        assertSame(oldVehicle, rider.vehicle);
        resolver.set(nextVehicle);
        try {
            assertFalse((boolean) mount.invoke(rider, nextVehicle, true, true, moveEdge),
                    "a fixture resolver flag cannot authorize mutation of a real Entity");
            assertSame(oldVehicle, rider.vehicle);
        } finally {
            resolver.remove();
        }
        nextVehicle.pending = false;
        assertTrue((boolean) mount.invoke(rider, nextVehicle, true, true, moveEdge));
        assertSame(nextVehicle, rider.vehicle, "release must restore the original mount path");
        nextVehicle.pending = true;
        Method dismount = method(EntityRidePortalReservationMixin.class, "fabrolia$guardReservedDismount", Operation.class);
        Operation<Void> removeEdge = args -> { rider.vehicle = null; return null; };
        dismount.invoke(rider, removeEdge);
        assertSame(nextVehicle, rider.vehicle, "both-end protection also covers a later dismount");
        nextVehicle.pending = false;
        dismount.invoke(rider, removeEdge);
        assertNull(rider.vehicle, "release must restore the original dismount path");
    }

    @Test
    void directVehicleExtractionCannotConsumeSaveVisibleContents() throws Exception {
        VehicleInventory vehicle = new VehicleInventory();
        SimpleContainer contents = new SimpleContainer(1);
        ItemStack live = stack(6);
        contents.setItem(0, live);
        Method extract = method(ContainerVehicleInventoryReservationMixin.class, "fabrolia$guardReservedExtraction",
                int.class, int.class, Operation.class);
        Operation<ItemStack> split = args -> contents.removeItem((int) args[0], (int) args[1]);
        assertSame(ItemStack.EMPTY, extract.invoke(vehicle, 0, 2, split));
        assertSame(live, contents.getItem(0));
        assertEquals(6, live.getCount());
        resolver.set(vehicle);
        try {
            ItemStack removed = (ItemStack) extract.invoke(vehicle, 0, 2, split);
            assertEquals(2, removed.getCount());
            assertEquals(4, live.getCount());
            assertEquals(6, removed.getCount() + live.getCount());
        } finally {
            resolver.remove();
        }
    }

    private static ItemStack stack(int count) {
        DataComponentMap components = DataComponentMap.builder()
                .set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 64).build();
        return new ItemStack(new Holder.Direct<>(Items.STONE, components), count);
    }

    private static Method method(Class<?> owner, String name, Class<?>... arguments) throws Exception {
        Method method = owner.getDeclaredMethod(name, arguments);
        method.setAccessible(true);
        return method;
    }

    private static ReservedEntity entity() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        ReservedEntity entity = (ReservedEntity) ((sun.misc.Unsafe) field.get(null)).allocateInstance(ReservedEntity.class);
        entity.guard = new SyncItemPickup.Guard();
        return entity;
    }

    private static final class ReservedContainer extends SimpleContainer implements PortalReservation {
        boolean pending = true;
        ReservedContainer() { super(1); }
        @Override public boolean fabrolia$isPortalPending() { return pending; }
        @Override public boolean fabrolia$isPortalResolutionOwner() { return resolver.get() == this; }
    }

    private static final class Living extends LivingDamageOverridePortalReservationMixin implements PortalReservation {
        @Override public boolean fabrolia$isPortalPending() { return true; }
        @Override public boolean fabrolia$isPortalResolutionOwner() { return resolver.get() == this; }
    }

    private static final class VehicleInventory extends ContainerVehicleInventoryReservationMixin implements PortalReservation {
        @Override public boolean fabrolia$isPortalPending() { return true; }
        @Override public boolean fabrolia$isPortalResolutionOwner() { return resolver.get() == this; }
    }

    private static final class Menu extends ContainerMenuPortalReservationMixin {
        boolean dragging = true;
        @Override protected void resetQuickCraft() { dragging = false; }
    }

    private static final class Ride extends EntityRidePortalReservationMixin {
        Entity vehicle;
        @Override public Entity getVehicle() { return vehicle; }
        @Override public List<Entity> getPassengers() { return List.of(); }
    }

    private static final class ReservedEntity extends ItemEntity implements PortalReservation, SyncItemPickup.Guarded {
        boolean pending;
        SyncItemPickup.Guard guard;
        @Override public SyncItemPickup.Guard fabrolia$consumptionGuard() { return guard; }
        private ReservedEntity() { super(null, null); }
        @Override public boolean fabrolia$isPortalPending() { return pending; }
        @Override public boolean fabrolia$isPortalResolutionOwner() { return resolver.get() == this; }
    }
}
