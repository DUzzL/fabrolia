package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises source staging, seal routing and destination publication, not queue-field copies. */
@Isolated
class CrossDimArrivalOwnerRoutingTest {
    private static final long A = ChunkPos.pack(0, 0);
    private static final long A_NEIGHBOR = ChunkPos.pack(8, 0);
    private static final long B = ChunkPos.pack(64, 0);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void independentDestinationsWaitForTheirOwnPhaseHeadAndKeepLocalFifo() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.onSource(() -> {
                f.stage(A, "a-regen", f.a);
                f.stage(B, "b-passenger", f.b);
                f.stage(A_NEIGHBOR, "a-entity", f.a);
                f.stage(B, "b-vehicle", f.b);
                CrossDimArrivals.seal(f.source);
            });
            f.publish(f.a);
            assertEquals(List.of("a-regen", "a-entity"), f.delivered);
            f.delivered.add("a-entity-phase");
            f.publish(f.b);
            assertEquals(List.of("a-regen", "a-entity", "a-entity-phase",
                    "b-passenger", "b-vehicle"), f.delivered);
        }
    }

    @Test
    void coldPublishedCellsStillWaitForTheirActualOwner() throws Exception {
        try (Fixture f = new Fixture(false)) {
            assertNull(f.runtime.regionizer().peekRegionForChunk(B));
            assertTrue(CrossDimArrivals.islandOwnedDestination(f.runtime, B),
                    "Main must stage a cold cell instead of publishing outside its island");
            f.onSource(() -> {
                f.stage(A, "a", f.a);
                f.stage(B, "b", f.b);
                CrossDimArrivals.seal(f.source);
            });
            f.publish(f.a);
            assertEquals(List.of("a"), f.delivered);
            CrossDimArrivals.publishGlobal(f.runtime);
            assertEquals(List.of("a"), f.delivered);
            f.publish(f.b);
            assertEquals(List.of("a", "b"), f.delivered);
        }
    }

    @Test
    void snapshotReplacementRetainingOwnerKeepsTheWholeCohortOnItsPhaseHead() throws Exception {
        assertSnapshotReplacement(false, false);
    }

    @Test
    void snapshotReplacementSplittingOwnerRoutesEachMemberToItsCurrentPhaseHead() throws Exception {
        assertSnapshotReplacement(true, false);
    }

    @Test
    void snapshotReplacementRetiringOwnerRoutesTheWholeCohortExactlyOnce() throws Exception {
        assertSnapshotReplacement(false, true);
    }

    private static void assertSnapshotReplacement(boolean split, boolean retire) throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            IslandRegistry.PIsland first = retire ? fixture.b : fixture.a;
            IslandRegistry.PIsland second = split ? fixture.b : first;
            Long2ObjectOpenHashMap<EpochDomain> replacement = fixture.domains.clone();
            replacement.put(fixture.cell(A), first.domain);
            replacement.put(fixture.cell(A_NEIGHBOR), second.domain);
            Long2ObjectOpenHashMap<EpochDomain> changing = new Long2ObjectOpenHashMap<>(fixture.domains) {
                private boolean changed;
                @Override public EpochDomain get(long key) {
                    EpochDomain result = super.get(key);
                    if (!changed) {
                        changed = true;
                        if (retire) fixture.a.retired = true;
                        fixture.publishTopology(replacement);
                        CrossDimArrivals.reroute(fixture.runtime, fixture.a);
                    }
                    return result;
                }
            };
            fixture.publishTopology(changing);
            fixture.onSource(() -> {
                fixture.stage(A, "regen", first);
                fixture.stage(A_NEIGHBOR, "entity", second);
                fixture.stage(A_NEIGHBOR, "passenger", second);
                CrossDimArrivals.seal(fixture.source);
            });
            CrossDimArrivals.publishGlobal(fixture.runtime);
            assertEquals(List.of(), fixture.delivered,
                    "a stale route is transport, never authority to execute on Main");
            fixture.publish(first);
            assertEquals(split ? List.of("regen") : List.of("regen", "entity", "passenger"),
                    fixture.delivered);
            if (split) fixture.publish(second);
            assertEquals(List.of("regen", "entity", "passenger"), fixture.delivered);
            CrossDimArrivals.publishGlobal(fixture.runtime);
            fixture.publish(first);
            if (split) fixture.publish(second);
            assertEquals(List.of("regen", "entity", "passenger"), fixture.delivered);
        }
    }

    @Test
    void formerlyGlobalCohortWaitsForNewOwnerAndPrecedesLaterArrival() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.publishTopology(new Long2ObjectOpenHashMap<>());
            f.onSource(() -> {
                f.stage(A, "old-regen", f.a);
                f.stage(A_NEIGHBOR, "old-entity", f.a);
                CrossDimArrivals.seal(f.source);
            });
            f.publishTopology(f.domains);
            f.onSource(() -> {
                f.stage(A, "new-entity", f.a);
                CrossDimArrivals.seal(f.source);
            });
            f.publish(f.a);
            assertEquals(List.of(), f.delivered, "the newer cohort cannot overtake a retained prefix");
            CrossDimArrivals.publishGlobal(f.runtime);
            assertEquals(List.of(), f.delivered, "Main cannot execute inside the newly owned cell");
            f.publish(f.a);
            assertEquals(List.of("old-regen", "old-entity", "new-entity"), f.delivered);
        }
    }

    @Test
    void asyncOffIntentCannotPublishInsideAnUncollapsedOwner() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.runtime.asyncActive = false;
            f.onSource(() -> {
                f.stage(A, "regen", f.a);
                f.stage(A_NEIGHBOR, "entity", f.a);
                CrossDimArrivals.seal(f.source);
            });
            CrossDimArrivals.publishGlobal(f.runtime);
            assertEquals(List.of(), f.delivered,
                    "OFF intent does not retire the destination's committed owner");
            f.publish(f.a);
            assertEquals(List.of("regen", "entity"), f.delivered);
        }
    }

    @Test
    void transitionTimeOffersRetainTheirFifoUntilTheCommittedOwnerHead() throws Exception {
        try (Fixture f = new Fixture(true)) {
            Field transition = field(RegionizedServer.class, "trackingTopologyTransition");
            transition.set(f.runtime, new RegionizedServer.TrackingTopologyTransitionReceipt(
                    f.runtime, new Object(), Thread.currentThread(), 1L, null));
            try {
                f.onSource(() -> {
                    f.stage(A, "inherited-regen", f.b);
                    f.stage(A_NEIGHBOR, "inherited-entity", f.b);
                    CrossDimArrivals.seal(f.source);
                });
                f.publish(f.a);
                assertEquals(List.of(), f.delivered);
                Long2ObjectOpenHashMap<EpochDomain> next = f.domains.clone();
                next.put(f.cell(A), f.b.domain);
                next.put(f.cell(A_NEIGHBOR), f.b.domain);
                f.a.retired = true;
                f.publishTopology(next);
                CrossDimArrivals.reroute(f.runtime, f.a);
            } finally {
                transition.set(f.runtime, null);
            }
            f.onSource(() -> {
                f.stage(A, "fresh-entity", f.b);
                CrossDimArrivals.seal(f.source);
            });
            f.publish(f.b);
            assertEquals(List.of(), f.delivered);
            CrossDimArrivals.publishGlobal(f.runtime);
            assertEquals(List.of(), f.delivered);
            f.publish(f.b);
            assertEquals(List.of("inherited-regen", "inherited-entity", "fresh-entity"),
                    f.delivered);
        }
    }

    @Test
    void repeatedSnapshotReplacementRetainsTheWholeBatchWithoutRetryingForever() throws Exception {
        try (Fixture f = new Fixture(true)) {
            Fixture fixture = f;
            Long2ObjectOpenHashMap<EpochDomain> changing = new Long2ObjectOpenHashMap<>(f.domains) {
                @Override public EpochDomain get(long key) {
                    EpochDomain domain = super.get(key);
                    fixture.publishTopology(clone());
                    return domain;
                }
            };
            f.publishTopology(changing);
            f.onSource(() -> {
                f.stage(A, "regen", f.a);
                f.stage(A_NEIGHBOR, "entity", f.a);
                CrossDimArrivals.seal(f.source);
            });
            CrossDimArrivals.publishGlobal(f.runtime);
            f.publish(f.a);
            assertEquals(List.of(), f.delivered);
            f.publishTopology(f.domains);
            CrossDimArrivals.publishGlobal(f.runtime);
            assertEquals(List.of(), f.delivered);
            f.publish(f.a);
            assertEquals(List.of("regen", "entity"), f.delivered);
            CrossDimArrivals.publishGlobal(f.runtime);
            f.publish(f.a);
            assertEquals(List.of("regen", "entity"), f.delivered);
        }
    }

    @Test
    void retainedCohortsStillWaitForThePreviousPlatformLandingAfterOwnerMigration() throws Exception {
        try (Fixture f = new Fixture(true)) {
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
            var first = (net.minecraft.world.entity.item.FallingBlockEntity)
                    unsafe.allocateInstance(net.minecraft.world.entity.item.FallingBlockEntity.class);
            var second = (net.minecraft.world.entity.item.FallingBlockEntity)
                    unsafe.allocateInstance(net.minecraft.world.entity.item.FallingBlockEntity.class);
            java.util.concurrent.atomic.AtomicBoolean landed = new java.util.concurrent.atomic.AtomicBoolean();
            field(RegionizedServer.class, "platformHandoff").set(f.runtime,
                    new EndPlatformHandoff.Tracker(handle -> handle == first && landed.get()));
            f.publishTopology(new Long2ObjectOpenHashMap<>());
            f.onSource(() -> {
                f.stage(A, "first-regen", f.a);
                f.stage(A, "first-entity", f.a, first);
                CrossDimArrivals.seal(f.source);
            });
            f.publishTopology(f.domains);
            f.onSource(() -> {
                CrossDimArrivals.CARRIED_SOURCE.set(new long[]{21L, 91L});
                f.stage(A, "second-regen", f.a);
                f.stage(A, "second-entity", f.a, second);
                CrossDimArrivals.seal(f.source);
            });
            CrossDimArrivals.publishGlobal(f.runtime);
            assertEquals(List.of(), f.delivered);
            f.publish(f.a);
            assertEquals(List.of("first-regen", "first-entity"), f.delivered);
            f.a.logicalTick = 200L;
            f.publish(f.a);
            assertEquals(List.of("first-regen", "first-entity"), f.delivered,
                    "elapsed source spacing is not a completed landing");
            landed.set(true);
            f.publish(f.a);
            assertEquals(List.of("first-regen", "first-entity", "second-regen", "second-entity"),
                    f.delivered);
        }
    }

    @Test
    void cancellationRemainsSharedAfterOfferingOwnerRetires() throws Exception {
        try (Fixture f = new Fixture(true)) {
            AtomicReference<CrossDimArrivals.PreparedSeal> prepared = new AtomicReference<>();
            f.onSource(() -> {
                f.stage(A, "a", f.a);
                f.stage(B, "b", f.b);
                prepared.set(CrossDimArrivals.prepareSeal(f.source));
            });
            f.a.retired = true;
            f.publishTopology(new Long2ObjectOpenHashMap<>());
            CrossDimArrivals.reroute(f.runtime, f.a);
            IllegalStateException failure = new IllegalStateException("source did not commit");
            // Isolate transport cancellation from generation fail-stop: with a
            // live fatal generation no destination body could enter regardless
            // of whether migration accidentally accepted this receipt.
            field(RegionScheduler.class, "active").setBoolean(null, false);
            try {
                assertSame(failure, prepared.get().cancel(failure));
            } finally {
                field(RegionScheduler.class, "active").setBoolean(null, true);
            }
            CrossDimArrivals.publishGlobal(f.runtime);
            f.publish(f.b);
            assertEquals(List.of(), f.delivered);
        }
    }

    @Test
    void acceptedRetirementMigrationPreservesOwnerFifoAcrossSourceEpochs() throws Exception {
        try (Fixture f = new Fixture(true)) {
            Long2ObjectOpenHashMap<EpochDomain> old = f.domains.clone();
            old.put(f.cell(B), f.a.domain);
            f.publishTopology(old);
            f.runtime.regionizer().peekRegionForChunk(B).domain = f.a.domain;
            f.onSource(() -> {
                f.stage(A, "a-first", f.a);
                f.stage(B, "b-first", f.b);
                CrossDimArrivals.seal(f.source);
                CrossDimArrivals.CARRIED_SOURCE.set(new long[]{21L, 91L});
                f.stage(B, "b-second", f.b);
                f.stage(A, "a-second", f.a);
                CrossDimArrivals.seal(f.source);
            });
            IslandRegistry.PIsland successor = f.island(3L);
            Long2ObjectOpenHashMap<EpochDomain> next = f.domains.clone();
            next.put(f.cell(A), successor.domain);
            next.put(f.cell(A_NEIGHBOR), successor.domain);
            f.a.retired = true;
            f.publishTopology(next);
            f.runtime.regionizer().peekRegionForChunk(A).domain = successor.domain;
            f.runtime.regionizer().peekRegionForChunk(B).domain = f.b.domain;
            f.expectedSuccessor = successor;
            // A fresh source can observe the committed split before Main carries
            // the old queue. Its arrivals must not overtake the inherited prefix.
            f.onSource(() -> {
                CrossDimArrivals.CARRIED_SOURCE.set(new long[]{22L, 91L});
                f.stage(B, "b-new", f.b);
                f.stage(A, "a-new", f.a);
                CrossDimArrivals.seal(f.source);
            });
            CrossDimArrivals.reroute(f.runtime, f.a);
            f.publish(f.b);
            assertEquals(List.of("b-first", "b-second", "b-new"), f.delivered);
            f.publish(successor);
            assertEquals(List.of("b-first", "b-second", "b-new",
                    "a-first", "a-second", "a-new"), f.delivered);
        }
    }

    @Test
    void anIslandAwaitingAcceptanceDoesNotBlockAnotherSealOrAbsorbItsTail() throws Exception {
        assertAcceptanceWaitReleasesQueue(false);
    }

    @Test
    void globalAcceptanceWaitDoesNotBlockAnotherSealOrAbsorbItsTail() throws Exception {
        assertAcceptanceWaitReleasesQueue(true);
    }

    private static void assertAcceptanceWaitReleasesQueue(boolean global) throws Exception {
        try (Fixture f = new Fixture(true)) {
            java.util.concurrent.CountDownLatch inspecting = new java.util.concurrent.CountDownLatch(1);
            if (global) {
                f.a.retired = true;
                f.publishTopology(new Long2ObjectOpenHashMap<>());
                field(RegionizedServer.class, "globalArrivals").set(f.runtime,
                        new java.util.concurrent.ConcurrentLinkedQueue<CrossDimArrivals.StagedOp>() {
                            @Override public Object[] toArray() {
                                inspecting.countDown();
                                return super.toArray();
                            }
                            @Override public CrossDimArrivals.StagedOp poll() {
                                inspecting.countDown();
                                return super.poll();
                            }
                        });
            } else {
                field(IslandRegistry.PIsland.class, "pendingArrivals").set(f.a,
                        new ArrayList<CrossDimArrivals.StagedOp>() {
                            @Override public java.util.Iterator<CrossDimArrivals.StagedOp> iterator() {
                                inspecting.countDown();
                                return super.iterator();
                            }
                            @Override public List<CrossDimArrivals.StagedOp> subList(int from, int to) {
                                inspecting.countDown();
                                return super.subList(from, to);
                            }
                        });
            }
            AtomicReference<CrossDimArrivals.PreparedSeal> first = new AtomicReference<>();
            f.onSource(() -> {
                f.stage(A, "first", global ? null : f.a);
                first.set(CrossDimArrivals.prepareSeal(f.source));
            });
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread consumer = new Thread(() -> {
                try {
                    if (global) CrossDimArrivals.publishGlobal(f.runtime);
                    else f.publish(f.a);
                } catch (Throwable thrown) { failure.compareAndSet(null, thrown); }
            }, "arrival-waiting-head");
            java.util.concurrent.CountDownLatch secondSealed = new java.util.concurrent.CountDownLatch(1);
            Thread producer = new Thread(() -> {
                try {
                    f.onSource(() -> {
                        f.stage(A, "second", global ? null : f.a);
                        CrossDimArrivals.seal(f.source);
                    });
                } catch (Throwable thrown) { failure.compareAndSet(null, thrown); }
                finally { secondSealed.countDown(); }
            }, "arrival-concurrent-seal");
            boolean sealedWhileOffering = false;
            consumer.start();
            try {
                assertTrue(inspecting.await(5, java.util.concurrent.TimeUnit.SECONDS));
                producer.start();
                sealedWhileOffering = secondSealed.await(2, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                // Also unblocks the pre-fix monitor deadlock, so the differential
                // failure cannot strand a spinning publication thread in the suite.
                first.get().commit();
                consumer.join(5_000L);
                if (producer.getState() != Thread.State.NEW) producer.join(5_000L);
            }
            assertFalse(consumer.isAlive());
            assertFalse(producer.isAlive());
            assertNull(failure.get());
            assertTrue(sealedWhileOffering, "receipt waiting held a producer's queue monitor");
            assertEquals(List.of("first"), f.delivered,
                    "arrivals offered during the acceptance wait belong to the next phase head");
            if (global) CrossDimArrivals.publishGlobal(f.runtime);
            else f.publish(f.a);
            assertEquals(List.of("first", "second"), f.delivered);
        }
    }

    @Test
    void retiredOwnerBucketsReuniteInOriginalFifoWithoutLosingAcceptedOperations() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.a.retired = true;
            f.b.retired = true;
            f.publishTopology(new Long2ObjectOpenHashMap<>());
            f.onSource(() -> {
                f.stage(A, "a-first", null);
                f.stage(B, "b-first", null);
                f.stage(A_NEIGHBOR, "a-second", null);
                f.stage(B, "b-second", null);
                CrossDimArrivals.seal(f.source);
            });
            CrossDimArrivals.publishGlobal(f.runtime);
            assertEquals(List.of("a-first", "b-first", "a-second", "b-second"), f.delivered);
            CrossDimArrivals.publishGlobal(f.runtime);
            assertEquals(List.of("a-first", "b-first", "a-second", "b-second"), f.delivered);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final TestLevel level;
        final RegionizedServer runtime;
        final EpochDomain source = new EpochDomain();
        final IslandRegistry.PIsland a;
        final IslandRegistry.PIsland b;
        final Long2ObjectOpenHashMap<EpochDomain> domains = new Long2ObjectOpenHashMap<>();
        final List<String> delivered = new ArrayList<>();
        final Map<ServerLevel, RegionizedServer> levels;
        final boolean previousActive;
        final Object previousFailure;
        IslandRegistry.PIsland expectedSuccessor;

        @SuppressWarnings("unchecked")
        Fixture(boolean materialize) throws Exception {
            assertNull(field(RegionScheduler.class, "pool").get(null));
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
            level = (TestLevel) unsafe.allocateInstance(TestLevel.class);
            runtime = new RegionizedServer(level, new RegionGrid(3));
            runtime.asyncActive = true;
            a = island(1L);
            b = island(2L);
            domains.put(cell(A), a.domain);
            domains.put(cell(A_NEIGHBOR), a.domain);
            domains.put(cell(B), b.domain);
            publishTopology(domains);
            if (materialize) {
                for (long chunk : new long[]{A, A_NEIGHBOR, B}) {
                    runtime.regionizer().regionForGridKey(cell(chunk));
                }
            }
            levels = (Map<ServerLevel, RegionizedServer>) field(RegionScheduler.class, "PER_LEVEL").get(null);
            previousActive = field(RegionScheduler.class, "active").getBoolean(null);
            previousFailure = field(RegionScheduler.class, "orphanFatalFailure").get(null);
            field(RegionScheduler.class, "orphanFatalFailure").set(null, null);
            levels.put(level, runtime);
            // Exercise registered runtime routing without a live worker pool.
            field(RegionScheduler.class, "active").setBoolean(null, true);
        }

        IslandRegistry.PIsland island(long id) {
            IslandRegistry.PIsland island = new IslandRegistry.PIsland(id, 100L);
            island.level = level;
            return island;
        }

        long cell(long chunk) { return runtime.regionizer().grid.keyFor(chunk); }

        void publishTopology(Long2ObjectOpenHashMap<EpochDomain> next) {
            try {
                field(RegionizedServer.class, "cellDomainSnapshot").set(runtime, next);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }

        void stage(long chunk, String name, IslandRegistry.PIsland expectedOwner) {
            stage(chunk, name, expectedOwner, null);
        }

        void stage(long chunk, String name, IslandRegistry.PIsland expectedOwner,
                   net.minecraft.world.entity.Entity entity) {
            CrossDimArrivals.stage(level, chunk, () -> {
                assertTrue(CrossDimArrivals.PUBLISHING.get());
                IslandRegistry.PIsland owner = expectedOwner == a && expectedSuccessor != null
                        ? expectedSuccessor : expectedOwner;
                if (owner == null) {
                    assertFalse(Thread.currentThread() instanceof RegionWorkerThread);
                } else {
                    RegionWorkerThread worker = assertInstanceOf(RegionWorkerThread.class, Thread.currentThread());
                    assertSame(owner.domain, worker.currentDomain,
                            name + " became visible under a foreign destination owner");
                    assertTrue(owner.unitClaim.get());
                }
                delivered.add(name);
            }, name.endsWith("regen") ? "regen" : "entity", entity);
        }

        void onSource(Runnable body) throws Exception {
            onWorker(() -> {
                ((RegionWorkerThread) Thread.currentThread()).currentDomain = source;
                CrossDimArrivals.CARRIED_SOURCE.set(new long[]{20L, 91L});
                try { body.run(); }
                finally { CrossDimArrivals.CARRIED_SOURCE.remove(); }
            });
        }

        void publish(IslandRegistry.PIsland owner) throws Exception {
            onWorker(() -> {
                assertTrue(owner.unitClaim.compareAndSet(false, true));
                ((RegionWorkerThread) Thread.currentThread()).currentDomain = owner.domain;
                try { CrossDimArrivals.publishIsland(owner); }
                finally { owner.unitClaim.set(false); }
            });
        }

        @Override public void close() throws Exception {
            levels.remove(level);
            field(RegionScheduler.class, "active").setBoolean(null, previousActive);
            field(RegionScheduler.class, "orphanFatalFailure").set(null, previousFailure);
        }
    }

    private static void onWorker(Runnable body) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new RegionWorkerThread(body, "arrival-owner-routing");
        worker.setUncaughtExceptionHandler((thread, thrown) -> failure.set(thrown));
        worker.start();
        worker.join(5_000L);
        assertFalse(worker.isAlive(), "arrival publication did not settle");
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static final class TestLevel extends ServerLevel {
        private TestLevel() { super(null, null, null, null, null, null, false, 0, null, false); }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
        @Override public long getGameTime() { return 100L; }
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
