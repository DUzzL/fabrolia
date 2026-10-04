package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Main's heal backstop visits only the dirty set — the dispatch members of a
 * domain whose entity barrier is pending, plus their same-domain neighbours —
 * and still rescues what it exists for: an orphan born after the cut that
 * holds a member's color gate, and a member that ran without contributing.
 */
@Isolated
class HealDirtySetTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void scopedPassSealsAnOrphanNextToAMemberAndClosesThePendingBarrier() throws Exception {
        try (Fixture f = new Fixture()) {
            EpochDomain d = f.runtime.globalDomain;
            CompletableFuture<Void> barrier = f.arm(d, 5L, System.nanoTime());
            Region member = f.region(0, 0, d, 5L, 5L, 4L);   // grouped, ran, never sealed/contributed
            Region orphan = f.region(1, 0, d, 4L, 4L, 4L);   // born after the cut, adjacent
            Region far = f.region(6, 6, d, 4L, 4L, 4L);      // not adjacent to any member
            d.healScope = List.of(member);

            assertTrue(f.heal(d, 0L), "an orphan seal and a contribution are real rescues");
            assertTrue(orphan.hasSealedDone(5L), "the post-cut orphan no longer holds the member's gate");
            assertTrue(member.hasSealedDone(5L));
            assertEquals(0, d.entityPending.get(), "the member's missing contribution was settled");
            assertTrue(barrier.isDone(), "the pending barrier completed");
            assertFalse(far.hasSealedDone(5L), "a region outside the dirty set is not scanned");
        }
    }

    @Test
    void islandBarrierYoungerThanTheBackstopAgeIsLeftToItsCoordinator() throws Exception {
        try (Fixture f = new Fixture()) {
            IslandRegistry.PIsland island = new IslandRegistry.PIsland(9L, 0L);
            EpochDomain d = island.domain;
            long now = System.nanoTime();
            f.arm(d, 7L, now);
            Region member = f.region(0, 0, d, 7L, 7L, 6L);
            d.healScope = List.of(member);

            assertFalse(f.heal(d, RegionScheduler.ISLAND_HEAL_BACKSTOP_NANOS));
            assertFalse(member.hasSealedDone(7L), "a fresh island barrier belongs to its coordinator");
            assertEquals(1, d.entityPending.get());

            d.entityBarrierArmedNanos = now - 2 * RegionScheduler.ISLAND_HEAL_BACKSTOP_NANOS;
            assertTrue(f.heal(d, RegionScheduler.ISLAND_HEAL_BACKSTOP_NANOS));
            assertTrue(member.hasSealedDone(7L), "an old island barrier is backstopped by Main");
            assertEquals(0, d.entityPending.get());
        }
    }

    @Test
    void domainWithoutAPendingBarrierIsNotScanned() throws Exception {
        try (Fixture f = new Fixture()) {
            EpochDomain d = f.runtime.globalDomain;
            f.arm(d, 3L, System.nanoTime());
            d.entityPending.set(0);
            Region member = f.region(0, 0, d, 3L, 3L, 2L);
            Region orphan = f.region(1, 0, d, 2L, 2L, 2L);
            d.healScope = List.of(member);

            assertFalse(f.heal(d, 0L));
            assertFalse(orphan.hasSealedDone(3L), "nothing waits on this domain's color gates");
            assertFalse(member.hasSealedDone(3L));
        }
    }

    private static final class TestLevel extends ServerLevel {
        private TestLevel() { super(null, null, null, null, null, null, false, 0L, null, false); }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
    }

    private static final class Fixture implements AutoCloseable {
        final sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        final Object oldServer = field(ParallelProcessor.class, "server").get(null);
        final Object oldFailure = field(RegionScheduler.class, "orphanFatalFailure").get(null);
        final RegionizedServer runtime;

        Fixture() throws Exception {
            assertNull(field(RegionScheduler.class, "pool").get(null));
            MinecraftServer server = (MinecraftServer) unsafe.allocateInstance(TestDedicatedServer.class);
            field(MinecraftServer.class, "serverThread").set(server, Thread.currentThread());
            field(ParallelProcessor.class, "server").set(null, server);
            field(RegionScheduler.class, "orphanFatalFailure").set(null, null);
            TestLevel level = (TestLevel) unsafe.allocateInstance(TestLevel.class);
            field(ServerLevel.class, "server").set(level, server);
            runtime = new RegionizedServer(level, new RegionGrid(3));
        }

        /** Dispatched event epoch with one owing participant. */
        CompletableFuture<Void> arm(EpochDomain d, long epoch, long armedNanos) {
            CompletableFuture<Void> barrier = new CompletableFuture<>();
            d.epoch = epoch;
            d.dispatchedEpoch = epoch;
            d.entityBarrierArmedEpoch = epoch;
            d.entityBarrierArmedNanos = armedNanos;
            d.entityBarrier = barrier;
            d.entityPending.set(1);
            return barrier;
        }

        Region region(int rx, int rz, EpochDomain d, long grouped, long entity, long done) {
            long key = (rx & 0xFFFFFFFFL) | (((long) rz & 0xFFFFFFFFL) << 32);
            Region r = runtime.regionizer().regionForGridKey(key);
            r.domain = d;
            r.groupedEpoch = grouped;
            r.entityEpoch = entity;
            r.doneEpoch = done;
            return r;
        }

        boolean heal(EpochDomain d, long minAge) throws Exception {
            Method heal = RegionScheduler.class.getDeclaredMethod("healDomainScope",
                    RegionizedServer.class, EpochDomain.class, long.class, long.class);
            heal.setAccessible(true);
            return (boolean) heal.invoke(null, runtime, d, System.nanoTime(), minAge);
        }

        @Override public void close() throws Exception {
            field(ParallelProcessor.class, "server").set(null, oldServer);
            field(RegionScheduler.class, "orphanFatalFailure").set(null, oldFailure);
        }
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
