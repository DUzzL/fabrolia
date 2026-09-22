package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.api.utils.SyncItemPickup;
import com.google.common.collect.ImmutableList;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real metadata queue cut, not a simulated Minecraft transfer. */
class NetherMetadataOrderingTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void concurrentRemovalDuringTrackCannotBeLostOrOvertakeItsPublication() throws Exception {
        try (Fixture fixture = new Fixture(); var caller = Executors.newSingleThreadExecutor()) {
            AtomicInteger tracking = new AtomicInteger();
            assertTrue(fixture.reservation.deferMetadata(() -> {
                assertEquals(0, tracking.getAndSet(1));
                try {
                    assertTrue(caller.submit(() -> fixture.reservation.deferMetadata(() -> {
                        assertEquals(1, tracking.getAndSet(2));
                    })).get(5, TimeUnit.SECONDS), "enqueue cannot wait on the running callback's monitor");
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }));

            fixture.drain();
            assertEquals(2, tracking.get(), "the later removal must follow the initial track");

            Runnable retrack = () -> assertEquals(2, tracking.getAndSet(3));
            if (!fixture.reservation.deferMetadata(retrack)) retrack.run();
            assertEquals(3, tracking.get(), "a stale owner lookup must fall back after healthy closure");
        }
    }

    @Test
    void failedMetadataNeverFallsBackIntoAnotherSuccessBody() throws Exception {
        try (Fixture fixture = new Fixture()) {
            IllegalStateException failure = new IllegalStateException("metadata generation failed");
            fixture.reservation.cancel(failure);
            AtomicInteger mutations = new AtomicInteger();
            assertSame(failure, assertThrows(IllegalStateException.class, () -> {
                if (!fixture.reservation.deferMetadata(mutations::incrementAndGet)) mutations.incrementAndGet();
            }));
            assertEquals(0, mutations.get());
        }
    }

    private static final class GuardedItem extends ItemEntity implements SyncItemPickup.Guarded {
        SyncItemPickup.Guard guard;
        private GuardedItem() { super(null, null); }
        @Override public SyncItemPickup.Guard fabrolia$consumptionGuard() { return guard; }
    }

    private static final class Fixture implements AutoCloseable {
        final Object previousOrphan = field(RegionScheduler.class, "orphanFatalFailure").get(null);
        final NetherPortalPlayerAccess.Reservation reservation;

        Fixture() throws Exception {
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
            GuardedItem root = (GuardedItem) unsafe.allocateInstance(GuardedItem.class);
            root.guard = new SyncItemPickup.Guard();
            field(Entity.class, "passengers").set(root, ImmutableList.of());
            var constructor = NetherPortalPlayerAccess.Reservation.class.getDeclaredConstructor(Entity.class, Object.class);
            constructor.setAccessible(true);
            reservation = constructor.newInstance(root, new Object());
        }

        void drain() throws Exception {
            var method = NetherPortalPlayerAccess.Reservation.class.getDeclaredMethod("drainOutbox");
            method.setAccessible(true);
            try {
                method.invoke(reservation);
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof Error error) throw error;
                if (failure.getCause() instanceof Exception exception) throw exception;
                throw failure;
            }
        }

        @Override
        public void close() throws Exception {
            field(RegionScheduler.class, "orphanFatalFailure").set(null, previousOrphan);
        }
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
