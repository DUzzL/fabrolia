package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.api.utils.SyncItemPickup;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real admission/cancellation primitives; no Minecraft world simulation is claimed. */
class NetherPreludeLifecycleTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void exactSprintMakesProgressWhileItsFenceSuspendsAPriorTransfer() throws Exception {
        try (Fixture fixture = new Fixture()) {
            IslandRegistry.PIsland island = fixture.island(fixture.destination);
            Object token = fixture.notice(island);
            assertFalse(island.tryClaimAutonomousWork());

            RegionizedServer runtime = (RegionizedServer) fixture.unsafe.allocateInstance(RegionizedServer.class);
            runtime.asyncActive = true;
            Constructor<IslandTickUnit.GlobalSprintRequest> constructor =
                    IslandTickUnit.GlobalSprintRequest.class.getDeclaredConstructor(
                            RegionizedServer.class, IslandRegistry.PIsland.class, long.class);
            constructor.setAccessible(true);
            IslandTickUnit.GlobalSprintRequest request = constructor.newInstance(runtime, island, island.logicalTick);
            island.globalSprintFenced = true;
            island.globalSprintRequest = request;

            assertTrue(request.tryClaim(), "the exact sprint cannot wait on a transfer which waits on its fence");
            assertFalse(request.tryClaim(), "the real claim must still exclude a second acquisition");
            island.unitClaim.set(false);
            assertFalse(island.tryClaimAutonomousWork(), "ordinary work still owes the transfer its priority");

            island.globalSprintRequest = null;
            assertThrows(IllegalStateException.class, request::tryClaim);
            assertFalse(island.unitClaim.get(), "an obsolete sprint receipt must not acquire the island");
            island.globalSprintFenced = false;
            fixture.clearNotice(token);
            assertTrue(island.tryClaimAutonomousWork());
            island.unitClaim.set(false);
        }
    }

    @Test
    void sourceUnloadRemovesTerminalHeadPriorityFromItsSurvivingDestination() throws Exception {
        try (Fixture fixture = new Fixture()) {
            IslandRegistry.PIsland destination = fixture.island(fixture.destination);
            Object token = fixture.notice(destination);
            fixture.terminal(token);
            assertFalse(destination.tryClaimAutonomousWork());

            NetherPortalTransfers.onLevelUnload(fixture.source);

            assertTrue(destination.tryClaimAutonomousWork(),
                    "the completed first member need not remain in SOURCES for its notice to be retired");
            destination.unitClaim.set(false);
        }
    }

    @Test
    void cancellationAfterTransferCompletionCannotPoisonLaterAdmission() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Object token = fixture.transfer();
            fixture.terminal(token);
            Method cancel = token.getClass().getDeclaredMethod("cancel", Throwable.class);
            cancel.setAccessible(true);
            cancel.invoke(token, new AssertionError("obsolete transfer failure"));

            AtomicInteger entered = new AtomicInteger();
            RegionScheduler.runSuccessBodyIfHealthy(entered::incrementAndGet);
            assertEquals(1, entered.get(), "a terminal transfer has no authority over later work");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void arrivedCloneDoesNotInheritItsSourcesLifecycleReservation() throws Exception {
        try (Fixture fixture = new Fixture()) {
            java.util.UUID uuid = new java.util.UUID(0L, 710L);
            GuardedItem source = (GuardedItem) fixture.unsafe.allocateInstance(GuardedItem.class);
            GuardedItem arrival = (GuardedItem) fixture.unsafe.allocateInstance(GuardedItem.class);
            source.guard = new SyncItemPickup.Guard();
            arrival.guard = new SyncItemPickup.Guard();
            for (Entity entity : List.of(source, arrival)) {
                field(Entity.class, "uuid").set(entity, uuid);
                field(Entity.class, "passengers").set(entity, com.google.common.collect.ImmutableList.of());
                field(Entity.class, "level").set(entity, entity == source ? fixture.source : fixture.destination);
            }
            Object token = fixture.transfer();
            field(fixture.transferType, "root").set(token, source);
            ((AtomicInteger) field(fixture.transferType, "state").get(token)).set(
                    field(NetherPortalTransfers.class, "BODY_DONE").getInt(null));
            Map<java.util.UUID, Object> sources = (Map<java.util.UUID, Object>)
                    field(NetherPortalTransfers.class, "SOURCES").get(null);
            sources.put(uuid, token);
            try {
                AtomicInteger mutations = new AtomicInteger();
                NetherPortalPlayerAccess.withRidePublication(new Entity[]{arrival}, mutations::incrementAndGet);
                assertEquals(1, mutations.get(), "the new incarnation may enter its own lifecycle body");
                assertThrows(IllegalStateException.class, () ->
                        NetherPortalPlayerAccess.withRidePublication(new Entity[]{source}, mutations::incrementAndGet));
                assertEquals(1, mutations.get(), "the reserved source still rejects an unowned lifecycle body");
            } finally {
                sources.remove(uuid, token);
            }
        }
    }

    private static final class GuardedItem extends net.minecraft.world.entity.item.ItemEntity implements SyncItemPickup.Guarded {
        SyncItemPickup.Guard guard;
        private GuardedItem() { super(null, null); }
        @Override public SyncItemPickup.Guard fabrolia$consumptionGuard() { return guard; }
    }

    private static final class Fixture implements AutoCloseable {
        final sun.misc.Unsafe unsafe;
        final ServerLevel source;
        final ServerLevel destination;
        final List<IslandRegistry.PIsland> islands = new ArrayList<>();
        final Object previousOrphan;
        final Class<?> transferType = Class.forName(NetherPortalTransfers.class.getName() + "$Transfer");

        Fixture() throws Exception {
            unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
            previousOrphan = field(RegionScheduler.class, "orphanFatalFailure").get(null);
            MinecraftServer server = (MinecraftServer) unsafe.allocateInstance(com.axalotl.fabrolia.common.regions.TestDedicatedServer.class);
            field(MinecraftServer.class, "serverThread").set(server, Thread.currentThread());
            source = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
            destination = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
            field(ServerLevel.class, "server").set(source, server);
            field(ServerLevel.class, "server").set(destination, server);
        }

        IslandRegistry.PIsland island(ServerLevel level) {
            IslandRegistry.PIsland island = new IslandRegistry.PIsland(100 + islands.size(), 0);
            island.level = level;
            islands.add(island);
            return island;
        }

        Object transfer() throws Exception {
            EpochDomain domain = new EpochDomain();
            Constructor<?> constructor = transferType.getDeclaredConstructor(
                    Entity.class, ServerLevel.class, EpochDomain.class, long.class);
            constructor.setAccessible(true);
            return constructor.newInstance(null, source, domain, 0L);
        }

        Object notice(IslandRegistry.PIsland owner) throws Exception {
            Object token = transfer();
            field(transferType, "preludeOwners").set(token, List.of(owner));
            Set<Object> pending = ConcurrentHashMap.newKeySet();
            pending.add(token);
            preludes().put(owner, pending);
            return token;
        }

        void terminal(Object token) throws Exception {
            int terminal = field(NetherPortalTransfers.class, "TERMINAL").getInt(null);
            ((AtomicInteger) field(transferType, "state").get(token)).set(terminal);
        }

        void clearNotice(Object token) throws Exception {
            Method clear = NetherPortalTransfers.class.getDeclaredMethod("clearPrelude", transferType);
            clear.setAccessible(true);
            clear.invoke(null, token);
        }

        @SuppressWarnings("unchecked")
        Map<IslandRegistry.PIsland, Set<Object>> preludes() throws Exception {
            return (Map<IslandRegistry.PIsland, Set<Object>>) field(NetherPortalTransfers.class, "PRELUDES").get(null);
        }

        @Override
        public void close() throws Exception {
            for (IslandRegistry.PIsland island : islands) preludes().remove(island);
            // Restore only the fixture's previous state, including if the stale-W regression fails.
            field(RegionScheduler.class, "orphanFatalFailure").set(null, previousOrphan);
        }
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
