package com.axalotl.fabrolia.common.parallelised.utils;

import com.axalotl.fabrolia.api.utils.SyncItemPickup.PortalReservation;
import com.axalotl.fabrolia.common.mixin.entity.ContainerVehiclePortalReservationMixin;
import com.axalotl.fabrolia.common.mixin.entity.TntVehiclePortalReservationMixin;
import com.axalotl.fabrolia.common.mixin.entity.VehiclePortalReservationMixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.Item;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Invokes real mixin entry guards; no transformed world or vanilla drop engine is claimed. */
class PortalDestructiveEntryTest {
    private static final ThreadLocal<Object> resolver = new ThreadLocal<>();

    @Test
    void pendingVehicleCannotRepeatDamageOrDirectDropsBeforeExactResolution() throws Exception {
        Vehicle source = new Vehicle();
        AtomicInteger effects = new AtomicInteger();
        Method damage = method(VehiclePortalReservationMixin.class, "fabrolia$guardPortalDamage",
                ServerLevel.class, DamageSource.class, float.class, Operation.class);
        Method drops = method(VehiclePortalReservationMixin.class, "fabrolia$guardPortalDrop",
                ServerLevel.class, Item.class, Operation.class);
        Operation<Boolean> destructiveDamage = args -> { effects.incrementAndGet(); return true; };
        Operation<Void> destructiveDrop = args -> { effects.incrementAndGet(); return null; };

        assertTrue((boolean) damage.invoke(source, null, null, 100F, destructiveDamage));
        assertTrue((boolean) damage.invoke(source, null, null, 100F, destructiveDamage));
        drops.invoke(source, null, null, destructiveDrop);
        assertEquals(0, effects.get(), "removal-only suppression permits destructive body effects");

        resolver.set(new Object());
        try {
            drops.invoke(source, null, null, destructiveDrop);
            assertEquals(0, effects.get(), "a neighbouring resolver cannot borrow this source");
        } finally {
            resolver.remove();
        }
        resolver.set(source);
        try {
            drops.invoke(source, null, null, destructiveDrop);
            assertEquals(1, effects.get(), "the exact transfer body must remain authorized");
        } finally {
            resolver.remove();
        }
        drops.invoke(source, null, null, destructiveDrop);
        assertEquals(1, effects.get(), "resolver exit must revoke destructive authority");
    }

    @Test
    void containerOverrideCannotDropInventoryWhileTransferIsQueued() throws Exception {
        Container source = new Container();
        AtomicInteger inventoryDrops = new AtomicInteger();
        Method destroy = method(ContainerVehiclePortalReservationMixin.class,
                "fabrolia$guardPortalInventoryDrops", ServerLevel.class, DamageSource.class, Operation.class);
        Operation<Void> body = args -> { inventoryDrops.incrementAndGet(); return null; };
        destroy.invoke(source, null, null, body);
        destroy.invoke(source, null, null, body);
        assertEquals(0, inventoryDrops.get());

        source.pending = false;
        destroy.invoke(source, null, null, body);
        assertEquals(1, inventoryDrops.get(), "reservation release must restore normal gameplay");
    }

    @Test
    void tntPreSuperDamageAndDirectExplosionCannotEnterWhilePending() throws Exception {
        Tnt source = new Tnt();
        AtomicInteger explosions = new AtomicInteger();
        Method damage = method(TntVehiclePortalReservationMixin.class, "fabrolia$guardPortalDamage",
                ServerLevel.class, DamageSource.class, float.class, Operation.class);
        Method explode = method(TntVehiclePortalReservationMixin.class, "fabrolia$guardPortalExplosion",
                DamageSource.class, double.class, Operation.class);
        Operation<Boolean> burningArrow = args -> { explosions.incrementAndGet(); return true; };
        Operation<Void> explosion = args -> { explosions.incrementAndGet(); return null; };
        damage.invoke(source, null, null, 100F, burningArrow);
        explode.invoke(source, null, 100D, explosion);
        assertEquals(0, explosions.get(), "base VehicleEntity guards are too late for TNT effects");
        source.pending = false;
        explode.invoke(source, null, 100D, explosion);
        assertEquals(1, explosions.get());
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) throws Exception {
        Method method = owner.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static final class Vehicle extends VehiclePortalReservationMixin implements PortalReservation {
        @Override public boolean fabrolia$isPortalPending() { return true; }
        @Override public boolean fabrolia$isPortalResolutionOwner() {
            return resolver.get() == this;
        }
    }
    private static final class Container extends ContainerVehiclePortalReservationMixin implements PortalReservation {
        boolean pending = true;
        @Override public boolean fabrolia$isPortalPending() { return pending; }
        @Override public boolean fabrolia$isPortalResolutionOwner() {
            return resolver.get() == this;
        }
    }
    private static final class Tnt extends TntVehiclePortalReservationMixin implements PortalReservation {
        boolean pending = true;
        @Override public boolean fabrolia$isPortalPending() { return pending; }
        @Override public boolean fabrolia$isPortalResolutionOwner() {
            return resolver.get() == this;
        }
    }
}
