package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.google.common.collect.ImmutableList;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The per-entity custody flag is the lock-free first gate of every push and
 * collision check: it must read true whenever CAPTURED holds the entity and
 * return to false only once no reservation can answer for it.
 */
@Isolated
class NetherCaptureFlagTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void flagMirrorsCustodyOfEveryCapturedIdentityAcrossReserveAndCancel() throws Exception {
        try (Fixture f = new Fixture()) {
            Frame root = f.frame(), passenger = f.frame();
            f.ride(root, passenger);
            Object transfer = f.transfer(root);
            for (Frame entity : new Frame[]{root, passenger}) {
                assertFalse(entity.captured);
                assertNull(NetherPortalPlayerAccess.requestForLifecycle(entity));
                assertFalse(NetherPortalTransfers.isSourceMutationBlocked(entity));
            }
            var reservation = NetherPortalPlayerAccess.reserve(root, transfer);
            assertNotNull(reservation);
            for (Frame entity : new Frame[]{root, passenger}) {
                assertTrue(f.captured.containsKey(entity));
                assertTrue(entity.captured, "custody publication must precede the map read");
                assertSame(transfer, NetherPortalPlayerAccess.requestForLifecycle(entity));
                assertTrue(NetherPortalTransfers.isSourceMutationBlocked(entity),
                        "a waiting transfer's custody blocks pushes without a pending source cut");
            }
            reservation.cancel(null);
            for (Frame entity : new Frame[]{root, passenger}) {
                assertFalse(f.captured.containsKey(entity));
                assertFalse(entity.captured, "release must retire the flag with the custody entry");
                assertNull(NetherPortalPlayerAccess.requestForLifecycle(entity));
                assertFalse(NetherPortalTransfers.isSourceMutationBlocked(entity));
            }
        }
    }

    @Test
    void refusedAndSupersededReservationsNeverClearALiveCustodyFlag() throws Exception {
        try (Fixture f = new Fixture()) {
            Frame root = f.frame();
            Object first = f.transfer(root), second = f.transfer(root);
            var holder = NetherPortalPlayerAccess.reserve(root, first);
            assertNotNull(holder);
            assertNull(NetherPortalPlayerAccess.reserve(root, second),
                    "an identity already in custody refuses a second reservation");
            assertTrue(root.captured, "a refused reservation must not touch the holder's flag");
            assertSame(first, NetherPortalPlayerAccess.requestForLifecycle(root));
            holder.cancel(null);
            assertFalse(root.captured);

            var successor = NetherPortalPlayerAccess.reserve(root, second);
            assertNotNull(successor);
            assertTrue(root.captured);
            // A terminal predecessor may run its cleanup again (finish and cancel
            // both unregister); it owns no entry any more and must leave the flag.
            f.unregisterCaptured(holder);
            assertTrue(root.captured, "stale cleanup cleared the successor's negative cache");
            assertSame(second, NetherPortalPlayerAccess.requestForLifecycle(root));
            successor.cancel(null);
            assertFalse(root.captured);
            assertNull(NetherPortalPlayerAccess.requestForLifecycle(root));
        }
    }

    @Test
    void guardWithoutAFlagAlwaysConsultsCustody() throws Exception {
        try (Fixture f = new Fixture()) {
            Flagless entity = f.flagless();
            assertTrue(entity.fabrolia$mayBePortalCaptured(), "no flag means the map must answer");
            assertNull(NetherPortalPlayerAccess.requestForLifecycle(entity));
            Object transfer = f.transfer(entity);
            var reservation = NetherPortalPlayerAccess.reserve(entity, transfer);
            assertNotNull(reservation);
            assertSame(transfer, NetherPortalPlayerAccess.requestForLifecycle(entity));
            reservation.cancel(null);
            assertNull(NetherPortalPlayerAccess.requestForLifecycle(entity));
        }
    }

    private static class Flagless extends ItemFrame implements ParallelProcessor.TickGuard {
        private Flagless() { super(null, null); }
        @Override public EntityTickLease fabrolia$tickLease() { throw new AssertionError("not ticking"); }
        @Override public byte fabrolia$getSyncCache() { return 0; }
        @Override public void fabrolia$setSyncCache(byte value) {}
    }

    private static final class Frame extends Flagless {
        volatile boolean captured;
        @Override public boolean fabrolia$mayBePortalCaptured() { return captured; }
        @Override public void fabrolia$setPortalCaptured(boolean value) { captured = value; }
    }

    private static final class Fixture implements AutoCloseable {
        final sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        final Class<?> transferType = Class.forName(NetherPortalTransfers.class.getName() + "$Transfer");
        final Map<Entity, ?> captured;

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            captured = (Map<Entity, ?>) field(NetherPortalPlayerAccess.class, "CAPTURED").get(null);
            assertTrue(captured.isEmpty());
        }

        Frame frame() throws Exception { return entity(Frame.class); }
        Flagless flagless() throws Exception { return entity(Flagless.class); }

        private <T extends Entity> T entity(Class<T> type) throws Exception {
            T entity = type.cast(unsafe.allocateInstance(type));
            field(Entity.class, "passengers").set(entity, ImmutableList.of());
            return entity;
        }

        void ride(Entity vehicle, Entity passenger) throws Exception {
            field(Entity.class, "passengers").set(vehicle, ImmutableList.of(passenger));
            field(Entity.class, "vehicle").set(passenger, vehicle);
        }

        Object transfer(Entity root) throws Exception {
            Constructor<?> constructor = transferType.getDeclaredConstructor(
                    Entity.class, ServerLevel.class, EpochDomain.class, long.class);
            constructor.setAccessible(true);
            return constructor.newInstance(root, null, null, 0L);
        }

        void unregisterCaptured(NetherPortalPlayerAccess.Reservation reservation) throws Exception {
            Method method = NetherPortalPlayerAccess.Reservation.class.getDeclaredMethod("unregisterCaptured");
            method.setAccessible(true);
            method.invoke(reservation);
        }

        @Override public void close() {
            assertTrue(captured.isEmpty());
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
