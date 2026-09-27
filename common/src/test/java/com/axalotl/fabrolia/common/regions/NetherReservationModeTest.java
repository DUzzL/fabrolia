package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.api.utils.SyncItemPickup;
import com.axalotl.fabrolia.common.ParallelProcessor;
import com.google.common.collect.ImmutableList;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.waypoints.Waypoint;
import net.minecraft.world.waypoints.WaypointTransmitter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The reservation mode is fixed by {@link NetherPortalPlayerAccess#reserve}
 * from the source custody: no player and no transmitting waypoint means the
 * worker finishes the transfer without Main (portal farms); anything else keeps
 * the ordered Main tail. Every metadata predicate reads that one decision.
 */
@Isolated
class NetherReservationModeTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void itemWithoutPlayersReservesInlineAndDefersNothing() throws Exception {
        try (Fixture f = new Fixture()) {
            Entity item = f.item(11L, false);
            Object transfer = f.transfer(item);
            var reservation = NetherPortalPlayerAccess.reserve(item, transfer);
            assertNotNull(reservation);
            try {
                assertTrue(f.inline(reservation));
                f.enterBody(reservation, transfer);
                try {
                    ArrayList<String> ran = new ArrayList<>();
                    assertFalse(NetherPortalPlayerAccess.capturesMetadata(), "inline body captures no builder");
                    assertFalse(NetherPortalPlayerAccess.deferMetadata(() -> ran.add("deferred")),
                            "inline body runs vanilla metadata in place");
                    assertTrue(ran.isEmpty());
                    assertNull(NetherPortalPlayerAccess.metadataOwner(item), "no owner: waypoint calls stay vanilla");
                } finally {
                    f.leaveBody();
                }
            } finally {
                reservation.cancel(null);
            }
        }
    }

    @Test
    void transmittingWaypointInCustodyKeepsTheMainTail() throws Exception {
        try (Fixture f = new Fixture()) {
            Entity root = f.item(21L, false);
            Entity passenger = f.item(22L, true);
            set(Entity.class, root, "passengers", ImmutableList.of(passenger));
            set(Entity.class, passenger, "vehicle", root);
            Object transfer = f.transfer(root);
            var reservation = NetherPortalPlayerAccess.reserve(root, transfer);
            assertNotNull(reservation);
            try {
                assertFalse(f.inline(reservation), "a transmitting waypoint anywhere in custody needs ordered Main metadata");
                f.enterBody(reservation, transfer);
                try {
                    ArrayList<String> ran = new ArrayList<>();
                    assertTrue(NetherPortalPlayerAccess.capturesMetadata());
                    assertTrue(NetherPortalPlayerAccess.deferMetadata(() -> ran.add("deferred")));
                    assertTrue(ran.isEmpty(), "deferred builders wait for the Main tail");
                    assertSame(reservation, NetherPortalPlayerAccess.metadataOwner(passenger));
                } finally {
                    f.leaveBody();
                }
            } finally {
                reservation.cancel(null);
            }
        }
    }

    private static class GuardedItem extends ItemEntity implements SyncItemPickup.Guarded, ParallelProcessor.TickGuard {
        SyncItemPickup.Guard guard;
        EntityTickLease lease;
        byte syncCache;
        boolean portalPending;
        private GuardedItem() { super(null, null); }
        @Override public SyncItemPickup.Guard fabrolia$consumptionGuard() { return guard; }
        @Override public EntityTickLease fabrolia$tickLease() { return lease; }
        @Override public byte fabrolia$getSyncCache() { return syncCache; }
        @Override public void fabrolia$setSyncCache(byte value) { syncCache = value; }
        @Override public boolean fabrolia$isPortalPending() { return portalPending; }
        @Override public void fabrolia$setPortalPending(boolean value) { portalPending = value; }
    }

    /** A non-player custody member that transmits a locator-bar waypoint. */
    private static final class TransmittingItem extends GuardedItem implements WaypointTransmitter {
        private TransmittingItem() {}
        @Override public boolean isTransmittingWaypoint() { return true; }
        @Override public Optional<Connection> makeWaypointConnectionWith(ServerPlayer player) { return Optional.empty(); }
        @Override public Waypoint.Icon waypointIcon() { return Waypoint.Icon.NULL; }
    }

    private static final class TestLevel extends ServerLevel {
        private TestLevel() { super(null, null, null, null, null, null, false, 0L, null, false); }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
    }

    private static final class Fixture implements AutoCloseable {
        final sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        final TestLevel level = (TestLevel) unsafe.allocateInstance(TestLevel.class);
        final ThreadLocal<Object> current;
        final ThreadLocal<Object> active;
        final java.lang.reflect.Constructor<?> scopeConstructor;
        final java.lang.reflect.Constructor<?> transferConstructor;
        final ArrayList<Entity> created = new ArrayList<>();

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            current = (ThreadLocal<Object>) field(NetherPortalPlayerAccess.class, "CURRENT").get(null);
            active = (ThreadLocal<Object>) field(NetherPortalTransfers.class, "ACTIVE").get(null);
            Class<?> transferType = Class.forName(NetherPortalTransfers.class.getName() + "$Transfer");
            transferConstructor = transferType.getDeclaredConstructor(Entity.class, ServerLevel.class, EpochDomain.class, long.class);
            transferConstructor.setAccessible(true);
            scopeConstructor = Class.forName(NetherPortalTransfers.class.getName() + "$Scope").getDeclaredConstructors()[0];
            scopeConstructor.setAccessible(true);
        }

        Object transfer(Entity root) throws Exception {
            return transferConstructor.newInstance(root, level, null, 0L);
        }

        Entity item(long identity, boolean transmitting) throws Exception {
            GuardedItem entity = (GuardedItem) unsafe.allocateInstance(
                    transmitting ? TransmittingItem.class : GuardedItem.class);
            entity.guard = new SyncItemPickup.Guard();
            entity.lease = new EntityTickLease();
            set(Entity.class, entity, "level", level);
            set(Entity.class, entity, "uuid", new UUID(0L, identity));
            set(Entity.class, entity, "passengers", ImmutableList.of());
            set(Entity.class, entity, "blockPosition", BlockPos.ZERO);
            created.add(entity);
            return entity;
        }

        boolean inline(NetherPortalPlayerAccess.Reservation reservation) throws Exception {
            return field(NetherPortalPlayerAccess.Reservation.class, "inline").getBoolean(reservation);
        }

        /** The worker body view: CURRENT and the transfer scope bound, metadata tail not running. */
        void enterBody(NetherPortalPlayerAccess.Reservation reservation, Object transfer) throws Exception {
            field(NetherPortalPlayerAccess.Reservation.class, "active").setBoolean(reservation, true);
            current.set(reservation);
            active.set(scopeConstructor.newInstance(transfer, null));
        }

        void leaveBody() {
            current.remove();
            active.remove();
        }

        @Override
        public void close() {
            leaveBody();
        }
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void set(Class<?> type, Object target, String name, Object value) throws ReflectiveOperationException {
        field(type, name).set(target, value);
    }
}
