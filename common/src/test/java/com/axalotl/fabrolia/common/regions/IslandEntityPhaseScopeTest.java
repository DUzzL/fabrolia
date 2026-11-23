package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.config.FabroliaConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Actual 26.3 explosion callbacks must see completed block removal on a grid boundary. */
@Isolated
@Timeout(20)
class IslandEntityPhaseScopeTest {
    private static final BlockPos SOURCE = new BlockPos(127, 64, 16);
    private static final BlockPos TARGET = new BlockPos(128, 64, 16);
    private static final BlockPos FOREIGN = new BlockPos(384, 64, 16);
    private static final BlockPos GLOBAL = new BlockPos(512, 64, 16);

    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void completeVanillaTntCallbackPrimesOnceAcrossTheGridBoundary() throws Exception {
        try (Fixture fixture = new Fixture()) {
            // The baseline runs the same actual 26.3 callbacks without worker deferral.
            fixture.level.resetBlock();
            hitTwice(fixture.level);
            PrimedTnt baseline = onlyTnt(fixture.level);
            int expectedFuse = baseline.getFuse();
            Vec3 expectedVelocity = baseline.getDeltaMovement();

            onWorker(() -> {
                RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                fixture.claimUnit(worker);
                worker.currentRegion = fixture.source;
                try {
                    // Reproduce the original bug with the actual production guard.
                    fixture.level.resetBlock();
                    hitTwice(fixture.level);
                    assertEquals(2, fixture.level.entities.size());
                    assertTrue(fixture.level.block.is(Blocks.TNT));
                    assertEquals(2, fixture.own.domain.deferredOps.size());
                    assertNotEquals(fixture.level.entities.get(0).getUUID(),
                            fixture.level.entities.get(1).getUUID());

                    fixture.own.domain.deferredOps.clear();
                    fixture.level.resetBlock();
                    try (var serial = IslandEntityPhaseScope.enter(fixture.runtime, fixture.own)) {
                        serial.selectRegion(fixture.source);
                        assertTrue(fixture.source.tryClaimIslandEntityPhase(worker, fixture.own.domain, fixture.own.domain.epoch));
                        try {
                            hitTwice(fixture.level);
                            PrimedTnt actual = onlyTnt(fixture.level);
                            assertEquals(expectedFuse, actual.getFuse());
                            assertEquals(expectedVelocity, actual.getDeltaMovement());
                            assertTrue(fixture.level.block.isAir());
                            assertTrue(fixture.own.domain.deferredOps.isEmpty());
                        } finally {
                            fixture.source.release(worker);
                            fixture.source.finishClaimTransaction();
                        }
                    }
                    assertSame(fixture.runtime,
                            CrossRegionGuard.crossRegionTargetOrNull(fixture.level, TARGET));
                } finally {
                    fixture.releaseUnit(worker);
                }
            });
        }
    }

    @Test
    void exactClaimAndSuccessfulCurrentQueueReceiptAreRequired() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertThrows(IllegalStateException.class,
                    () -> IslandEntityPhaseScope.enter(fixture.runtime, fixture.own));
            onWorker(() -> {
                RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                worker.currentDomain = fixture.own.domain;
                assertThrows(IllegalStateException.class,
                        () -> IslandEntityPhaseScope.enter(fixture.runtime, fixture.own));
                fixture.claimUnit(worker);
                try {
                    EpochDomain domain = fixture.own.domain;
                    CompletableFuture<Void> successful = domain.islandEntityQueueBarrier;
                    for (CompletableFuture<Void> unfinished : List.<CompletableFuture<Void>>of(
                            new CompletableFuture<>(),
                            CompletableFuture.failedFuture(new IllegalStateException("queue failure")))) {
                        domain.islandEntityQueueBarrier = unfinished;
                        assertThrows(IllegalStateException.class,
                                () -> IslandEntityPhaseScope.enter(fixture.runtime, fixture.own));
                        assertNull(worker.exclusiveEntityPhase);
                    }
                    CompletableFuture<Void> cancelled = new CompletableFuture<>();
                    cancelled.cancel(false);
                    domain.islandEntityQueueBarrier = cancelled;
                    assertThrows(IllegalStateException.class,
                            () -> IslandEntityPhaseScope.enter(fixture.runtime, fixture.own));
                    domain.islandEntityQueueBarrier = successful;
                    domain.islandEntityQueueEpoch--;
                    assertThrows(IllegalStateException.class,
                            () -> IslandEntityPhaseScope.enter(fixture.runtime, fixture.own));
                    domain.islandEntityQueueEpoch++;
                    fixture.own.claimHolder = new Thread();
                    assertThrows(IllegalStateException.class,
                            () -> IslandEntityPhaseScope.enter(fixture.runtime, fixture.own));
                    fixture.own.claimHolder = worker;
                    fixture.own.retired = true;
                    assertThrows(IllegalStateException.class,
                            () -> IslandEntityPhaseScope.enter(fixture.runtime, fixture.own));
                    fixture.own.retired = false;
                    try (var ignored = IslandEntityPhaseScope.enter(fixture.runtime, fixture.own)) {
                        assertTrue(IslandEntityPhaseScope.owns(fixture.source));
                        assertTrue(IslandEntityPhaseScope.owns(fixture.target));
                        assertFalse(IslandEntityPhaseScope.owns(fixture.foreign));
                    }
                } finally {
                    fixture.releaseUnit(worker);
                }
            });
        }
    }

    @Test
    void completedQueueSealOnlyReopensForItsAuthenticatedEntityCoordinator() throws Exception {
        try (Fixture fixture = new Fixture()) {
            onWorker(() -> {
                RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                fixture.claimUnit(worker);
                EpochDomain domain = fixture.own.domain;
                long epoch = domain.epoch;
                fixture.source.sealedDoneEpoch = fixture.source.doneEpoch = epoch;
                try {
                    assertFalse(fixture.source.tryClaim(worker, domain, epoch));
                    assertNull(fixture.source.currentWorker());
                    assertThrows(IllegalStateException.class,
                            () -> fixture.source.tryClaimIslandEntityPhase(worker, domain, epoch));
                    try (var ignored = IslandEntityPhaseScope.enter(fixture.runtime, fixture.own)) {
                        assertFalse(fixture.source.tryClaim(worker, domain, epoch),
                                "ordinary queue consumers remain sealed inside the exclusive pass");
                        assertThrows(IllegalStateException.class,
                                () -> fixture.source.tryClaimIslandEntityPhase(
                                        new RegionWorkerThread(() -> {}, "foreign-worker"), domain, epoch));
                        assertFalse(fixture.source.tryClaimIslandEntityPhase(worker, domain, epoch - 1));
                        assertNull(fixture.source.currentWorker());
                        assertTrue(fixture.source.tryClaimIslandEntityPhase(worker, domain, epoch));
                        assertSame(worker, fixture.source.currentWorker());
                        assertFalse(fixture.source.tryClaimIslandEntityPhase(worker, domain, epoch),
                                "exclusive authority does not permit a duplicate region claim");
                        fixture.source.release(worker);
                        assertNull(fixture.source.currentWorker());
                        assertThrows(IllegalStateException.class,
                                () -> fixture.foreign.tryClaimIslandEntityPhase(worker,
                                        fixture.other.domain, fixture.other.domain.epoch));
                    }
                    assertFalse(fixture.source.tryClaim(worker, domain, epoch));
                    assertThrows(IllegalStateException.class,
                            () -> fixture.source.tryClaimIslandEntityPhase(worker, domain, epoch));
                } finally {
                    if (fixture.source.currentWorker() == worker) fixture.source.release(worker);
                    fixture.releaseUnit(worker);
                }
            });
        }
    }

    @Test
    @Timeout(30)
    void strictPreflightSurvivesTwoSuccessiveEightSecondClaimHolders() throws Exception {
        try (Fixture f = new Fixture()) {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            Object previousPool = get(RegionScheduler.class, null, "pool");
            var firstClaim = new java.util.concurrent.CountDownLatch(1);
            var releaseFirst = new java.util.concurrent.CountDownLatch(1);
            var first = new FutureTask<Void>(() -> {
                assertTrue(f.own.unitClaim.compareAndSet(false, true));
                f.own.noteClaim("first holder");
                firstClaim.countDown();
                try {
                    assertTrue(releaseFirst.await(25, TimeUnit.SECONDS));
                } finally {
                    f.own.clearClaim();
                    f.own.unitClaim.set(false);
                }
                return null;
            });
            var constructor = IslandTickUnit.GlobalSprintRequest.class.getDeclaredConstructor(
                    RegionizedServer.class, IslandRegistry.PIsland.class, long.class);
            constructor.setAccessible(true);
            var request = constructor.newInstance(f.runtime, f.own, f.own.logicalTick);
            f.own.globalSprintRequest = request;
            f.own.globalSprintFenced = true;
            pool.start();
            try {
                set(RegionScheduler.class, null, "pool", pool);
                Thread.ofPlatform().daemon().start(first);
                assertTrue(firstClaim.await(3, TimeUnit.SECONDS));
                var waiter = new FutureTask<>(request::awaitClaims);
                Thread worker = Thread.ofPlatform().daemon().start(waiter);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (java.util.concurrent.locks.LockSupport.getBlocker(worker) != request) {
                    assertFalse(waiter.isDone());
                    assertTrue(System.nanoTime() < deadline, "strict request never reached BUSY");
                    Thread.onSpinWait();
                }
                // Model real elapsed contention, not time used as a thread-start oracle.
                assertThrows(java.util.concurrent.TimeoutException.class, () -> waiter.get(8, TimeUnit.SECONDS));
                synchronized (f.own.unitAdmissionLock) {
                    releaseFirst.countDown();
                    first.get(2, TimeUnit.SECONDS);
                    assertTrue(f.own.unitClaim.compareAndSet(false, true));
                    f.own.noteClaim("second holder");
                }
                try {
                    assertThrows(java.util.concurrent.TimeoutException.class, () -> waiter.get(8, TimeUnit.SECONDS));
                } finally {
                    f.own.clearClaim();
                    f.own.unitClaim.set(false);
                }
                assertNull(waiter.get(3, TimeUnit.SECONDS), "exact request acquired source only");
                assertTrue(f.own.unitClaim.get());
                assertFalse(request.receipt.isDone());
                assertEquals(request.fromLogicalTick, f.own.logicalTick);
            } finally {
                releaseFirst.countDown();
                f.own.globalSprintRequest = null;
                f.own.globalSprintFenced = false;
                f.own.unitClaim.set(false);
                try {
                    pool.stop(() -> pool.drainMainSafeAll(true));
                } finally {
                    set(RegionScheduler.class, null, "pool", previousPool);
                }
            }
        }
    }

    @Test
    void borrowedOwnerCallbackWritesInlineOnlyDuringTheExactSerialPhase() throws Exception {
        try (Fixture f = new Fixture()) {
            var pool = new RegionWorkerPool(1);
            Object previousPool = get(RegionScheduler.class, null, "pool");
            boolean previousDebug = FabroliaConfig.fabroliaDebugRegionAccess;
            pool.start();
            try {
                set(RegionScheduler.class, null, "pool", pool);
                FabroliaConfig.fabroliaDebugRegionAccess = true;
                onWorker(() -> {
                    RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                    f.claimUnit(worker);
                    try (var window = new CopperIslandClaims.Window(f.own, false)) {
                        assertTrue(window.test(f.other));
                        assertFalse(RegionScheduler.ownsCurrentContext(f.level, FOREIGN));
                        try (var phase = IslandEntityPhaseScope.enter(f.runtime, f.own)) {
                            for (Region stamp : new Region[]{null, f.source}) {
                                phase.selectRegion(stamp);
                                if (stamp != null) assertTrue(stamp.tryClaimIslandEntityPhase(worker, f.own.domain, f.own.domain.epoch));
                                f.level.resetBlock();
                                assertTrue(RegionScheduler.ownsCurrentContext(f.level, TARGET));
                                assertTrue(RegionScheduler.ownsCurrentContext(f.level, FOREIGN));
                                com.axalotl.fabrolia.common.api.FabroliaIslandApi.executeOnOwner(f.level, FOREIGN,
                                        () -> f.level.setBlock(FOREIGN, Blocks.AIR.defaultBlockState(), 3, 512));
                                assertTrue(f.level.block.isAir());
                                assertTrue(f.other.domain.deferredOps.isEmpty());
                                if (stamp != null) {
                                    worker.currentRegion = f.target; // same-domain helper, not a serial visit
                                    assertFalse(RegionScheduler.ownsCurrentContext(f.level, FOREIGN));
                                    assertSame(f.runtime, CrossRegionGuard.crossRegionTargetOrNull(f.level, FOREIGN));
                                    worker.currentRegion = stamp;
                                    stamp.release(worker);
                                    stamp.finishClaimTransaction();
                                }
                            }
                            phase.selectRegion(null);
                            assertFalse(RegionScheduler.ownsCurrentContext(f.otherLevel, FOREIGN));
                            worker.currentRegion = f.foreign;
                            assertFalse(RegionScheduler.ownsCurrentContext(f.level, FOREIGN));
                            worker.currentRegion = null;
                            f.own.domain.islandEntityQueueEpoch--;
                            assertFalse(RegionScheduler.ownsCurrentContext(f.level, FOREIGN));
                            assertSame(f.runtime, CrossRegionGuard.crossRegionTargetOrNull(f.level, FOREIGN));
                            f.own.domain.islandEntityQueueEpoch++;
                            f.other.claimHolder = new Thread();
                            assertFalse(RegionScheduler.ownsCurrentContext(f.level, FOREIGN));
                            f.other.claimHolder = worker;
                        }
                    } finally {
                        f.releaseUnit(worker);
                    }
                });
            } finally {
                try {
                    pool.stop(() -> pool.drainMainSafeAll(true));
                } finally {
                    set(RegionScheduler.class, null, "pool", previousPool);
                    FabroliaConfig.fabroliaDebugRegionAccess = previousDebug;
                }
            }
        }
    }

    @Test
    void foreignOwnersAndDimensionsRemainProtectedEvenWithoutARegionStamp() throws Exception {
        try (Fixture fixture = new Fixture()) {
            onWorker(() -> {
                RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                fixture.claimUnit(worker);
                try (var ignored = IslandEntityPhaseScope.enter(fixture.runtime, fixture.own)) {
                    assertNull(worker.currentRegion);
                    assertNull(CrossRegionGuard.crossRegionTargetOrNull(fixture.level, SOURCE));
                    assertNull(CrossRegionGuard.crossRegionTargetOrNull(fixture.level, TARGET));
                    assertSame(fixture.runtime,
                            CrossRegionGuard.crossRegionTargetOrNull(fixture.level, FOREIGN));
                    assertSame(fixture.runtime,
                            CrossRegionGuard.crossRegionTargetOrNull(fixture.level, GLOBAL));
                    assertSame(fixture.otherRuntime,
                            CrossRegionGuard.crossRegionTargetOrNull(fixture.otherLevel, TARGET));
                    worker.currentRegion = fixture.foreign;
                    assertFalse(IslandEntityPhaseScope.owns(fixture.source),
                            "a nested foreign helper cannot inherit the coordinator's scope");
                    assertSame(fixture.runtime,
                            CrossRegionGuard.crossRegionTargetOrNull(fixture.level, TARGET));
                    worker.currentRegion = null;
                    fixture.own.unitClaim.set(false);
                    assertSame(fixture.runtime,
                            CrossRegionGuard.crossRegionTargetOrNull(fixture.level, TARGET));
                    fixture.own.unitClaim.set(true);
                } finally {
                    fixture.releaseUnit(worker);
                }
            });
        }
    }

    @Test
    void nestedScopesCloseInStrictStackOrderAndExceptionRestoresOuterAuthority() throws Exception {
        try (Fixture fixture = new Fixture()) {
            onWorker(() -> {
                RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                fixture.claimUnit(worker);
                try {
                    var outer = IslandEntityPhaseScope.enter(fixture.runtime, fixture.own);
                    var inner = IslandEntityPhaseScope.enter(fixture.runtime, fixture.own);
                    assertThrows(IllegalStateException.class, outer::close);
                    assertSame(inner, worker.exclusiveEntityPhase);
                    inner.close();
                    assertSame(outer, worker.exclusiveEntityPhase);
                    assertThrows(IllegalStateException.class, inner::close);
                    RuntimeException primary = new RuntimeException("entity callback failed");
                    assertSame(primary, assertThrows(RuntimeException.class, () -> {
                        try (var ignored = IslandEntityPhaseScope.enter(fixture.runtime, fixture.own)) {
                            throw primary;
                        }
                    }));
                    assertSame(outer, worker.exclusiveEntityPhase);
                    outer.close();
                    assertNull(worker.exclusiveEntityPhase);
                    assertThrows(IllegalStateException.class, outer::close);
                } finally {
                    fixture.releaseUnit(worker);
                }
            });
        }
    }

    @Test
    void oversizedCutVisitsEveryEntityAndUnwindsBothClaimsOnPostFailure() throws Exception {
        try (Fixture f = new Fixture()) {
            PassCut cut = installCut(f);
            List<Object> trace = new ArrayList<>();
            List<Entity> members = new ArrayList<>();
            for (int id = 1; id <= 8_192; id++) members.add(VisitProbe.at(id, f.source, 0, trace));
            members.add(VisitProbe.at(8_193, f.target, 8, trace));
            cut.entities = List.copyOf(members);
            onWorker(() -> {
                RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                f.claimUnit(worker);
                Object oldHook = get(RegionTickHooks.class, null, "hook");
                Object oldFailure = get(RegionScheduler.class, null, "orphanFatalFailure");
                Object oldPool = get(RegionScheduler.class, null, "pool");
                Object oldTransition = get(RegionScheduler.class, null, "transitionPool");
                AssertionError primary = new AssertionError("second post failed");
                try {
                    set(RegionScheduler.class, null, "pool", null);
                    set(RegionScheduler.class, null, "transitionPool", null);
                    set(RegionScheduler.class, null, "orphanFatalFailure", null);
                    RegionTickHooks.register(new RegionTickHooks.Hook() {
                        @Override public void preEntityTick(Region r) { trace.add(r); }
                        @Override public void postEntityTick(Region r) {
                            trace.add(r);
                            if (r == f.target) throw primary;
                        }
                    });
                    assertSame(primary, assertThrows(AssertionError.class,
                            () -> f.runtime.tickIslandEntitiesInVanillaOrder(f.own)));
                    assertSame(primary, assertThrows(AssertionError.class, RegionScheduler::throwWorkerFailureIfAny));
                    int n = members.size();
                    assertEquals(n + 4, trace.size());
                    assertEquals(List.of(f.source, f.target), trace.subList(0, 2));
                    for (int i = 0; i < n; i++) assertEquals(i + 1, trace.get(i + 2));
                    assertEquals(List.of(f.source, f.target), trace.subList(n + 2, n + 4));
                    for (Region region : List.of(f.source, f.target)) {
                        assertNull(region.currentWorker());
                        assertEquals(0, region.activeClaimTransactions());
                    }
                    assertNull(worker.currentRegion);
                    assertIdleVisitsReleased(worker);
                } finally {
                    set(RegionTickHooks.class, null, "hook", oldHook);
                    set(RegionScheduler.class, null, "orphanFatalFailure", oldFailure);
                    set(RegionScheduler.class, null, "transitionPool", oldTransition);
                    set(RegionScheduler.class, null, "pool", oldPool);
                    f.releaseUnit(worker);
                }
            });
        }
    }

    @Test
    void nestedPreHookCannotReplaceOuterVisitsOrReassignTheirCapturedPhases() throws Exception {
        try (Fixture f = new Fixture()) {
            PassCut cut = installCut(f);
            List<Object> trace = new ArrayList<>();
            VisitProbe first = VisitProbe.at(30, f.source, 0, trace);
            VisitProbe second = VisitProbe.at(20, f.target, 8, trace);
            VisitProbe third = VisitProbe.at(10, f.source, 0, trace);
            VisitProbe nested = VisitProbe.at(40, f.target, 8, trace);
            cut.entities = List.of(first, second, third);
            onWorker(() -> {
                RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                f.claimUnit(worker);
                Object oldHook = get(RegionTickHooks.class, null, "hook");
                try {
                    RegionTickHooks.register(new RegionTickHooks.Hook() {
                        @Override public void preEntityTick(Region r) {
                            assertSame(worker, r.currentWorker());
                            assertSame(r, worker.currentRegion);
                            trace.add(r == f.source ? "pre source" : "pre target");
                            if (r == f.source) {
                                // The target hasn't been claimed by the outer pass yet.
                                second.key = f.foreign.gridKey;
                                cut.entities = List.of(nested);
                                f.runtime.tickIslandEntitiesInVanillaOrder(f.own);
                            }
                        }

                        @Override public void postEntityTick(Region r) {
                            assertSame(worker, r.currentWorker());
                            assertSame(r, worker.currentRegion);
                            trace.add(r == f.source ? "post source" : "post target");
                        }
                    });
                    f.runtime.tickIslandEntitiesInVanillaOrder(f.own);
                    assertEquals(List.of("pre source", "pre target", 40, "post target",
                            "pre target", 30, 20, 10, "post source", "post target"), trace);
                    assertNull(worker.currentRegion);
                    assertEquals(0, f.source.activeClaimTransactions());
                    assertEquals(0, f.target.activeClaimTransactions());
                    assertIdleVisitsReleased(worker);
                    trace.clear();
                    f.runtime.tickIslandEntitiesInVanillaOrder(f.own);
                    assertEquals(List.of("pre target", 40, "post target"), trace);
                    assertIdleVisitsReleased(worker);
                } finally {
                    set(RegionTickHooks.class, null, "hook", oldHook);
                    f.releaseUnit(worker);
                }
            });
        }
    }

    private static void assertIdleVisitsReleased(RegionWorkerThread worker) {
        Object idle = worker.idleIslandVisits;
        assertNotNull(idle);
        for (String field : List.of("entities", "phases")) {
            Object[] refs = (Object[]) get(idle.getClass(), idle, field);
            assertTrue(refs.length <= 4096, "a peak cut cannot pin its arrays on the worker");
            for (Object ref : refs) assertNull(ref, "a returned frame cannot retain world references");
        }
    }

    private static PassCut installCut(Fixture f) throws Exception {
        PassCut cut = new PassCut();
        set(ServerLevel.class, f.level, "entityTickList", cut);
        var chunks = (ServerChunkCache) unsafe().allocateInstance(ServerChunkCache.class);
        set(ServerChunkCache.class, chunks, "chunkMap", unsafe().allocateInstance(ChunkMap.class));
        set(ServerLevel.class, f.level, "chunkSource", chunks);
        f.level.rate = new TickRateManager();
        return cut;
    }

    /** Mixins aren't applied here. Supply their immutable snapshot at the existing cut. */
    private static final class PassCut extends EntityTickList implements EntityTickListSnapshotAccess {
        List<Entity> entities;
        @Override public List<Entity> fabrolia$snapshotEntities() { return entities; }
    }

    /** Observe the real tick admission on its captured region, then decline the token. */
    private static final class VisitProbe extends PrimedTnt implements RegionKeyed {
        private long key;
        private Region capturedRegion;
        private List<Object> trace;

        private VisitProbe() { super(null, null); }

        static VisitProbe at(int id, Region region, int chunkX, List<Object> trace) throws Exception {
            VisitProbe probe = (VisitProbe) unsafe().allocateInstance(VisitProbe.class);
            set(Entity.class, probe, "chunkPosition", new ChunkPos(chunkX, 0));
            probe.setId(id);
            probe.key = region.gridKey;
            probe.capturedRegion = region;
            probe.trace = trace;
            return probe;
        }

        @Override public long fabrolia$getRegionKey() { return key; }
        @Override public void fabrolia$setRegionKey(long key) { this.key = key; }
        @Override public boolean fabrolia$tryMarkRegionTick(long token) {
            assertSame(capturedRegion, ((RegionWorkerThread) Thread.currentThread()).currentRegion);
            trace.add(getId());
            return false;
        }
        @Override public boolean fabrolia$carryRegionTickMark(long from, long to) {
            throw new AssertionError("unexpected carry");
        }
    }

    @Test
    void ordinaryVanillaBlastAndFinalMovementStayInsideTheExclusiveHalo() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertTrue(TntExplosionFootprint.ownsFootprint(fixture.runtime, fixture.own,
                    127.5, 16, 0.8, 0, 4));
            assertFalse(TntExplosionFootprint.ownsFootprint(fixture.runtime, fixture.own,
                    127.5, 16, 0, 0, 128), "serialized large blasts cannot use a normal halo");
            assertFalse(TntExplosionFootprint.ownsFootprint(fixture.runtime, fixture.own,
                    127.5, 16, 150, 0, 4), "the final movement can leave the owner before detonation");
        }
    }

    @Test
    void blastAdmissionRejectsForeignOrUnownedCellsEvenForAnOwnedCenter() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertFalse(TntExplosionFootprint.ownsFootprint(fixture.runtime, fixture.own,
                    255.5, 16, 0, 0, 4));
            assertFalse(TntExplosionFootprint.ownsFootprint(fixture.runtime, fixture.own,
                    383.5, 16, 0, 0, 4));
            fixture.own.retired = true;
            assertFalse(TntExplosionFootprint.ownsFootprint(fixture.runtime, fixture.own,
                    127.5, 16, 0, 0, 4));
        }
    }

    @Test
    void negativeCoordinatesRequireTheirActualNegativeHaloCells() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertFalse(TntExplosionFootprint.ownsFootprint(fixture.runtime, fixture.own,
                    -0.5, -0.5, 0, 0, 4));
            var domains = new Long2ObjectOpenHashMap<EpochDomain>();
            for (int x : new int[]{-8, 0}) {
                for (int z : new int[]{-8, 0}) {
                    domains.put(fixture.runtime.regionizer().grid.keyFor(x, z), fixture.own.domain);
                }
            }
            set(RegionizedServer.class, fixture.runtime, "cellDomainSnapshot", domains);
            assertTrue(TntExplosionFootprint.ownsFootprint(fixture.runtime, fixture.own,
                    -0.5, -0.5, 0, 0, 4));
        }
    }

    @Test
    void invalidOrUnboundedBlastFootprintsTakeTheFencedLane() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, 30_000_000}) {
                assertFalse(TntExplosionFootprint.ownsFootprint(fixture.runtime, fixture.own,
                        invalid, 16, 0, 0, 4));
            }
            for (float power : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -1, 129}) {
                assertFalse(TntExplosionFootprint.ownsFootprint(fixture.runtime, fixture.own,
                        127.5, 16, 0, 0, power));
            }
            assertFalse(TntExplosionFootprint.ownsFootprint(fixture.runtime, fixture.own,
                    127.5, 16, Double.NaN, 0, 4));
            assertFalse(TntExplosionFootprint.ownsFootprint(fixture.runtime, fixture.own,
                    127.5, 16, 1_000_000, 1_000_000, 4));
        }
    }

    private static void hitTwice(TestLevel level) {
        Explosion explosion = new TestExplosion(level);
        for (int i = 0; i < 2; i++) {
            level.getBlockState(TARGET).onExplosionHit(level, TARGET, explosion, (stack, pos) -> {});
        }
    }

    private static PrimedTnt onlyTnt(TestLevel level) {
        assertEquals(1, level.entities.size());
        return assertInstanceOf(PrimedTnt.class, level.entities.get(0));
    }

    private static void onWorker(CheckedRunnable body) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> { body.run(); return null; });
        Thread worker = new RegionWorkerThread(task, "island-entity-scope-regression");
        worker.start();
        try { task.get(5, TimeUnit.SECONDS); }
        finally { worker.join(5_000); }
        assertFalse(worker.isAlive());
    }

    @FunctionalInterface
    private interface CheckedRunnable { void run() throws Exception; }

    private static final class Fixture implements AutoCloseable {
        final TestLevel level = TestLevel.create();
        final TestLevel otherLevel = TestLevel.create();
        final RegionizedServer runtime = new RegionizedServer(level, new RegionGrid(3));
        final RegionizedServer otherRuntime = new RegionizedServer(otherLevel, new RegionGrid(3));
        final IslandRegistry.PIsland own = new IslandRegistry.PIsland(1, 0);
        final IslandRegistry.PIsland other = new IslandRegistry.PIsland(2, 0);
        final Region source;
        final Region target;
        final Region foreign;
        final Map<ServerLevel, RegionizedServer> levels;
        final Object previousActive = get(RegionScheduler.class, null, "active");
        final Object previousGrid = get(RegionScheduler.class, null, "grid");
        final boolean previousDisabled = FabroliaConfig.disabled;
        final boolean previousThreading = FabroliaConfig.enableRegionThreading;
        final boolean previousGuard = FabroliaConfig.fabroliaRedstoneCrossRegionGuard;

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            levels = (Map<ServerLevel, RegionizedServer>) get(RegionScheduler.class, null, "PER_LEVEL");
            own.level = other.level = level;
            Long2ObjectOpenHashMap<EpochDomain> domains = new Long2ObjectOpenHashMap<>();
            domains.put(runtime.regionizer().grid.keyFor(0, 0), own.domain);
            domains.put(runtime.regionizer().grid.keyFor(8, 0), own.domain); // write halo
            domains.put(runtime.regionizer().grid.keyFor(24, 0), other.domain);
            set(RegionizedServer.class, runtime, "cellDomainSnapshot", domains);
            runtime.asyncActive = true;
            source = runtime.regionizer().regionForGridKey(runtime.regionizer().grid.keyFor(0, 0));
            target = runtime.regionizer().regionForGridKey(runtime.regionizer().grid.keyFor(8, 0));
            foreign = runtime.regionizer().regionForGridKey(runtime.regionizer().grid.keyFor(24, 0));
            EpochDomain domain = own.domain;
            domain.epoch = domain.dispatchedEpoch = domain.entityDispatchCutEpoch = 8;
            domain.queueOnlyEntityEpoch = domain.islandEntityQueueEpoch = 8;
            domain.islandEntityQueueBarrier = CompletableFuture.completedFuture(null);
            runtime.asyncActive = true;
            levels.put(level, runtime);
            levels.put(otherLevel, otherRuntime);
            set(RegionScheduler.class, null, "active", true);
            set(RegionScheduler.class, null, "grid", runtime.regionizer().grid);
            FabroliaConfig.disabled = false;
            FabroliaConfig.enableRegionThreading = true;
            FabroliaConfig.fabroliaRedstoneCrossRegionGuard = true;
        }

        void claimUnit(RegionWorkerThread worker) {
            worker.currentDomain = own.domain;
            assertTrue(own.unitClaim.compareAndSet(false, true));
            own.noteClaim("exclusive-entity-scope-regression");
        }

        void releaseUnit(RegionWorkerThread worker) {
            assertNull(worker.exclusiveEntityPhase, "scope leaked after its entity pass");
            worker.currentRegion = null;
            worker.currentDomain = null;
            own.clearClaim();
            own.unitClaim.set(false);
        }

        @Override
        public void close() throws Exception {
            levels.remove(level, runtime);
            levels.remove(otherLevel, otherRuntime);
            set(RegionScheduler.class, null, "active", previousActive);
            set(RegionScheduler.class, null, "grid", previousGrid);
            FabroliaConfig.disabled = previousDisabled;
            FabroliaConfig.enableRegionThreading = previousThreading;
            FabroliaConfig.fabroliaRedstoneCrossRegionGuard = previousGuard;
        }
    }

    private static final class TestLevel extends ServerLevel {
        BlockState block;
        List<Entity> entities;
        RandomSource random;
        GameRules rules;
        TickRateManager rate;
        int ids;
        private TestLevel() { super(null, null, null, null, null, null, false, 0, null, false); }

        static TestLevel create() {
            try {
                TestLevel level = (TestLevel) unsafe().allocateInstance(TestLevel.class);
                level.rules = new GameRules(FeatureFlags.DEFAULT_FLAGS);
                level.resetBlock();
                return level;
            } catch (InstantiationException exception) {
                throw new AssertionError(exception);
            }
        }

        void resetBlock() {
            block = Blocks.TNT.defaultBlockState();
            entities = new ArrayList<>();
            random = RandomSource.create(1);
        }

        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
        @Override public TickRateManager tickRateManager() { return rate; }
        @Override public GameRules getGameRules() { return rules; }
        @Override public RandomSource getRandom() { return random; }
        @Override public int getNextEntityId() { return ++ids; }
        @Override public BlockState getBlockState(BlockPos pos) { return block; }
        @Override public boolean addFreshEntity(Entity entity) { entities.add(entity); return true; }
        @Override public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursion) {
            RegionAccessDetector.onBlockWrite(this, pos);
            RegionizedServer deferred = CrossRegionGuard.crossRegionTargetOrNull(this, pos);
            if (deferred != null) deferred.deferCrossRegion(() -> block = state, pos.immutable());
            else block = state;
            return true;
        }
    }

    private record TestExplosion(TestLevel level) implements Explosion {
        @Override public BlockInteraction getBlockInteraction() { return BlockInteraction.DESTROY; }
        @Override public LivingEntity getIndirectSourceEntity() { return null; }
        @Override public Entity getDirectSourceEntity() { return null; }
        @Override public float radius() { return 4; }
        @Override public Vec3 center() { return new Vec3(127.5, 64, 16.5); }
        @Override public boolean canTriggerBlocks() { return false; }
        @Override public boolean shouldAffectBlocklikeEntities() { return true; }
    }

    private static sun.misc.Unsafe unsafe() {
        return (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
    }

    private static Object get(Class<?> type, Object target, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void set(Class<?> type, Object target, String name, Object value) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
