package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.config.FabroliaConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
                    try (var ignored = IslandEntityPhaseScope.enter(fixture.runtime, fixture.own)) {
                        hitTwice(fixture.level);
                        PrimedTnt actual = onlyTnt(fixture.level);
                        assertEquals(expectedFuse, actual.getFuse());
                        assertEquals(expectedVelocity, actual.getDeltaMovement());
                        assertTrue(fixture.level.block.isAir());
                        assertTrue(fixture.own.domain.deferredOps.isEmpty());
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
        @Override public GameRules getGameRules() { return rules; }
        @Override public RandomSource getRandom() { return random; }
        @Override public int getNextEntityId() { return ++ids; }
        @Override public BlockState getBlockState(BlockPos pos) { return block; }
        @Override public boolean addFreshEntity(Entity entity) { entities.add(entity); return true; }
        @Override public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursion) {
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
