package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.api.utils.SyncItemPickup;
import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real core claims and reservation admission, without simulating a Minecraft world tick. */
@Isolated
class NetherTransferAdmissionTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void mainFenceBetweenRootAndPassengerChecksDoesNotRevokeHeldClaims() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CountDownLatch checkedRoot = new CountDownLatch(1);
            CountDownLatch publishedFence = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean reserved = new AtomicBoolean();
            fixture.entity.beforeSecondPositionRead = () -> {
                checkedRoot.countDown();
                await(publishedFence);
            };
            Thread worker = new RegionWorkerThread(() -> {
                try (Fixture.Claim claim = fixture.acquire()) {
                    assertTrue(NetherPortalTransfers.owns(fixture.level, BlockPos.ZERO));
                    reserved.set(claim.reservation.reserveExecution());
                    assertTrue(NetherPortalTransfers.owns(fixture.entity));
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            }, "nether-admission-regression");
            Object receipt = new Object();
            worker.start();
            try {
                assertTrue(checkedRoot.await(5, TimeUnit.SECONDS), "reservation did not reach the ownership boundary");
                fixture.runtime.installGlobalFenceIntent(receipt);
                assertTrue(fixture.owner.unitClaim.get());
                assertSame(worker, fixture.owner.claimHolder);
                publishedFence.countDown();
                worker.join(5_000L);
                assertFalse(worker.isAlive(), "reservation did not release its held claims");
                if (failure.get() != null) throw new AssertionError(failure.get());
                assertTrue(reserved.get(), "admission intent must not revoke existing source custody");
            } finally {
                publishedFence.countDown();
                worker.join(5_000L);
                fixture.runtime.clearGlobalFenceIntent(receipt);
            }
        }
    }

    @Test
    void anEarlierMainFenceStillRefusesNewClaims() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Object receipt = new Object();
            fixture.runtime.installGlobalFenceIntent(receipt);
            try {
                assertNull(fixture.tryClaims());
                assertFalse(fixture.owner.unitClaim.get());
            } finally {
                fixture.runtime.clearGlobalFenceIntent(receipt);
            }
        }
    }

    @Test
    void aPassengerOutsideTheHeldFootprintStillFailsClosed() throws Exception {
        try (Fixture fixture = new Fixture()) {
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
            TestEntity passenger = (TestEntity) unsafe.allocateInstance(TestEntity.class);
            passenger.guard = new SyncItemPickup.Guard();
            field(Entity.class, "level").set(passenger, fixture.level);
            field(Entity.class, "passengers").set(passenger, ImmutableList.of());
            passenger.location = new BlockPos(512, 0, 0);
            field(Entity.class, "passengers").set(fixture.entity, ImmutableList.of(passenger));
            try (Fixture.Claim claim = fixture.acquire()) {
                assertTrue(NetherPortalTransfers.owns(fixture.entity));
                assertThrows(IllegalStateException.class, claim.reservation::reserveExecution);
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Main did not publish the fence");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server;
        final TestLevel level;
        final RegionizedServer runtime;
        final IslandRegistry.PIsland owner;
        final IslandRegistry.PIsland arrival;
        final IslandRegistry.PIsland other;
        final TestEntity entity;
        final Map<ServerLevel, RegionizedServer> levels;
        final Object previousPool = field(RegionScheduler.class, "pool").get(null);
        final Object previousActive = field(RegionScheduler.class, "active").get(null);
        final Object previousFailure = field(RegionScheduler.class, "orphanFatalFailure").get(null);
        final Object previousServer = field(RegionScheduler.class, "server").get(null);
        final Class<?> transferType = Class.forName(NetherPortalTransfers.class.getName() + "$Transfer");
        final Class<?> claimsType = Class.forName(NetherPortalTransfers.class.getName() + "$Claims");
        final Class<?> scopeType = Class.forName(NetherPortalTransfers.class.getName() + "$Scope");
        final ThreadLocal<Object> active;

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            assertNull(previousPool, "requires no live scheduler generation");
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
            server = (MinecraftServer) unsafe.allocateInstance(com.axalotl.fabrolia.common.regions.TestDedicatedServer.class);
            field(MinecraftServer.class, "serverThread").set(server, Thread.currentThread());
            field(MinecraftServer.class, "levels").set(server, Map.of());
            level = (TestLevel) unsafe.allocateInstance(TestLevel.class);
            field(ServerLevel.class, "server").set(level, server);
            runtime = new RegionizedServer(level, new RegionGrid(3));
            runtime.asyncActive = true;
            runtime.islandRegistry.applyRebuildCells(List.of(
                    new LongArrayList(new long[]{0L}),
                    new LongArrayList(new long[]{4L}),
                    new LongArrayList(new long[]{8L})), 0L);
            owner = runtime.islandRegistry.byCell(0L);
            arrival = runtime.islandRegistry.byCell(4L);
            other = runtime.islandRegistry.byCell(8L);
            owner.level = level;
            arrival.level = level;
            other.level = level;
            Long2ObjectOpenHashMap<EpochDomain> domains = new Long2ObjectOpenHashMap<>();
            domains.put(0L, owner.domain);
            domains.put(4L, arrival.domain);
            domains.put(8L, other.domain);
            field(RegionizedServer.class, "cellDomainSnapshot").set(runtime, domains);
            entity = (TestEntity) unsafe.allocateInstance(TestEntity.class);
            entity.guard = new SyncItemPickup.Guard();
            entity.location = BlockPos.ZERO;
            field(Entity.class, "level").set(entity, level);
            field(Entity.class, "passengers").set(entity, ImmutableList.of());
            levels = (Map<ServerLevel, RegionizedServer>) field(RegionScheduler.class, "PER_LEVEL").get(null);
            active = (ThreadLocal<Object>) field(NetherPortalTransfers.class, "ACTIVE").get(null);
            levels.put(level, runtime);
            field(RegionScheduler.class, "active").set(null, true);
            field(RegionScheduler.class, "server").set(null, server);
            field(RegionScheduler.class, "pool").set(null, new RegionWorkerPool(1));
            field(RegionScheduler.class, "orphanFatalFailure").set(null, null);
        }

        AutoCloseable tryClaims() throws Exception {
            return tryClaims(List.of(new NetherPortalPreparation.ChunkRef(level, 0L)));
        }

        AutoCloseable tryClaims(List<NetherPortalPreparation.ChunkRef> footprint) throws Exception {
            Method acquire = claimsType.getDeclaredMethod("tryAcquire", List.class, boolean.class);
            acquire.setAccessible(true);
            return (AutoCloseable) acquire.invoke(null, footprint, false);
        }

        Claim acquire() throws Exception {
            AutoCloseable claims = tryClaims();
            assertNotNull(claims);
            Constructor<?> transferConstructor = transferType.getDeclaredConstructor(
                    Entity.class, ServerLevel.class, EpochDomain.class, long.class);
            transferConstructor.setAccessible(true);
            Object transfer = transferConstructor.newInstance(entity, level, owner.domain, 0L);
            NetherPortalPlayerAccess.Reservation reservation = NetherPortalPlayerAccess.reserve(entity, transfer);
            assertNotNull(reservation);
            field(transferType, "players").set(transfer, reservation);
            Constructor<?> scopeConstructor = scopeType.getDeclaredConstructor(transferType, claimsType);
            scopeConstructor.setAccessible(true);
            assertNull(active.get());
            active.set(scopeConstructor.newInstance(transfer, claims));
            return new Claim(claims, reservation);
        }

        final class Claim implements AutoCloseable {
            final AutoCloseable claims;
            final NetherPortalPlayerAccess.Reservation reservation;
            Claim(AutoCloseable claims, NetherPortalPlayerAccess.Reservation reservation) {
                this.claims = claims;
                this.reservation = reservation;
            }
            @Override public void close() throws Exception {
                try {
                    reservation.releaseExecution();
                    reservation.cancel(null);
                } finally {
                    active.remove();
                    claims.close();
                }
            }
        }

        @Override public void close() throws Exception {
            levels.remove(level, runtime);
            field(RegionScheduler.class, "pool").set(null, previousPool);
            field(RegionScheduler.class, "active").set(null, previousActive);
            field(RegionScheduler.class, "orphanFatalFailure").set(null, previousFailure);
            field(RegionScheduler.class, "server").set(null, previousServer);
        }
    }

    private static final class TestEntity extends ItemEntity implements SyncItemPickup.Guarded {
        Runnable beforeSecondPositionRead;
        BlockPos location;
        int positionReads;
        SyncItemPickup.Guard guard;
        @Override public SyncItemPickup.Guard fabrolia$consumptionGuard() { return guard; }
        private TestEntity() { super(null, 0, 0, 0, null); }
        @Override public BlockPos blockPosition() {
            if (++positionReads == 2 && beforeSecondPositionRead != null) beforeSecondPositionRead.run();
            return location;
        }
    }

    private static final class TestLevel extends ServerLevel {
        private TestLevel() { super(null, null, null, null, null, null, false, 0, null, false); }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
