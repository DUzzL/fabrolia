package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.api.utils.SyncItemPickup;
import com.axalotl.fabrolia.common.ParallelProcessor;
import com.google.common.collect.ImmutableList;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises lifecycle authority against real region claims and session transitions. */
@Isolated
@Timeout(20)
final class AsyncIslandPlayerLifecycleWiringTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void neighborDeathIsAuthorizedWithoutDiscardingItsOwedLateTurn() throws Exception {
        try (Fixture f = new Fixture()) {
            f.onWorker(worker -> f.withClaim(worker, () -> {
                assertTrue(f.owns(), "a golem can hit a player across a cell edge before its early tick");
                var early = f.player.state.session.tryReserveEarly(f.owner, 1L).orElseThrow();
                assertFalse(f.owns(), "another executor owns the reserved packet/early prefix");
                assertTrue(f.player.state.session.enterIslandBody(early));
                assertFalse(f.owns(), "movement during another worker's early body is not authority");
                assertTrue(f.player.state.session.completeRealEarly(early));
                assertTrue(f.owns(), "EARLY_DONE is quiescent until the island entity barrier finishes");
                assertTrue(f.player.state.session.isAwaitingRealLate(early));
                assertTrue(f.player.state.session.beginRealLate(early));
                assertFalse(f.owns(), "another executor's late body remains exclusive");
                assertTrue(f.player.state.session.completeRealLate(early));
            }));
        }
    }

    /**
     * Production crash 2026-10-11 06:40: a piglin ticked by the serial island
     * entity pass killed an island player. After the queue-only join every
     * member region is done for the epoch, so the color-gate authority is
     * false; the exclusive phase scope must authorize the death instead.
     */
    @Test
    void serialIslandEntityPassAuthorizesAMobKillAfterTheQueueJoin() throws Exception {
        try (Fixture f = new Fixture()) {
            f.onWorker(worker -> f.withClaim(worker, () -> {
                f.source.doneEpoch = f.island.domain.epoch;
                assertFalse(f.owns(), "outside the serial pass a done region is no authority");

                f.island.domain.queueOnlyEntityEpoch = f.island.domain.epoch;
                f.island.domain.islandEntityQueueEpoch = f.island.domain.epoch;
                f.island.domain.islandEntityQueueBarrier = CompletableFuture.completedFuture(null);
                RegionWorkerThread self = (RegionWorkerThread) Thread.currentThread();
                Region claimed = self.currentRegion;
                self.currentRegion = null;
                try (IslandEntityPhaseScope ignored = IslandEntityPhaseScope.enter(f.runtime, f.island)) {
                    self.currentRegion = claimed;
                    assertTrue(f.owns(), "the exclusive serial pass owns its island's player lifecycle");
                } finally {
                    self.currentRegion = claimed;
                }
                assertFalse(f.owns(), "the authority ends with the serial pass");
            }));
        }
    }

    @Test
    void inactiveAsyncWorkerLeavesCreditsToTheNativeCaller() throws Exception {
        try (Fixture f = new Fixture()) {
            f.runtime.asyncActive = false;
            var credits = new java.util.concurrent.atomic.AtomicInteger();
            f.onWorker(worker -> assertFalse(
                    com.axalotl.fabrolia.common.parallelised.utils.PlayerDimensionTransition
                            .deferEndCredits(f.player, credits::incrementAndGet)));
            assertEquals(0, credits.get(), "declining ownership must not also execute the native callback");
        }
    }

    @Test
    void blockEntityCreditsContinuationWaitsForLateAndDropsDuplicateLifecycle() throws Exception {
        try (Fixture f = new Fixture()) {
            Object previousEnabled = get(RegionalPlayerEntityTicksV2.class, null, "enabled");
            Object previousMain = get(com.axalotl.fabrolia.common.ParallelProcessor.class,
                    null, "server");
            set(RegionalPlayerEntityTicksV2.class, null, "enabled", true);
            set(com.axalotl.fabrolia.common.ParallelProcessor.class,
                    null, "server", f.level.getServer());
            try {
                var effects = new java.util.ArrayList<String>();
                var early = f.player.state.session.tryReserveEarly(f.owner, 1L).orElseThrow();
                assertTrue(f.player.state.session.enterIslandBody(early));
                assertTrue(f.player.state.session.completeRealEarly(early));
                // Untransformed test players are subclasses. Substitute only the
                // captured Mixin lifecycle token; admission and Main replay are real.
                var constructor = AsyncIslandPacketContinuations.ContinuationOwner.class
                        .getDeclaredConstructor(ServerGamePacketListenerImpl.class, ServerPlayer.class,
                                AsyncIslandPlayerTicks.PlayerState.class, ServerLevel.class,
                                RegionizedServer.class, long.class);
                constructor.setAccessible(true);
                var continuation = constructor.newInstance(f.player.connection, f.player, f.player.state,
                        f.level, f.runtime, f.player.fabrolia$playerLifecycleEpoch());
                f.onWorker(worker -> {
                    worker.currentDomain = f.island.domain;
                    f.island.domain.entityPending.set(0);
                    f.island.domain.entityBarrier = CompletableFuture.completedFuture(null);
                    assertTrue(f.island.tryClaimAutonomousWork());
                    f.island.noteClaim("credits-block-entity-regression");
                    try {
                        assertTrue(f.owns());
                        assertFalse(AsyncIslandPlayerTicks.isIslandPlayerTickOf(f.player));
                        assertTrue(AsyncIslandPacketContinuations.enqueueMain(continuation, "End credits",
                                () -> AsyncIslandPlayerTicks.runFencedMainMutation("End credits", () -> {
                                    assertTrue(RegionalPlayerEntityTicksV2.isPhysicalServerThread(f.level.getServer()));
                                    assertTrue(IslandCommands.fenceWindowHeld());
                                    assertEquals(IslandPlayerTurnSession.Phase.MAIN,
                                            f.player.state.session.view().phase());
                                    effects.add("credits");
                                    f.player.fabrolia$bumpPlayerLifecycleEpoch();
                                })));
                        assertTrue(AsyncIslandPacketContinuations.enqueueMain(continuation, "End credits",
                                () -> fail("a duplicate source contact must not repeat native removal")));
                        assertEquals(java.util.List.of(), effects);
                        assertFalse(f.player.state.mainIngressScheduled);
                        assertTrue(f.player.state.session.isAwaitingRealLate(early));
                    } finally {
                        f.island.clearClaim();
                        f.island.unitClaim.set(false);
                    }
                });
                assertTrue(f.player.state.session.beginRealLate(early));
                effects.add("late");
                assertTrue(f.player.state.session.completeRealLate(early));
                var handoff = f.player.state.session.beginMainHandoff(f.owner).orElseThrow();
                assertTrue(f.player.state.session.completeMainHandoff(handoff).orElseThrow().isEmpty());
                assertTrue(f.player.state.session.finishMainHandoff(handoff));
                f.player.state.mainIngressScheduled = true;
                var drain = AsyncIslandPlayerTicks.class.getDeclaredMethod("drainMainIngress",
                        AsyncIslandPlayerTicks.PlayerState.class, ServerGamePacketListenerImpl.class);
                drain.setAccessible(true);
                drain.invoke(null, f.player.state, f.player.connection);
                assertEquals(java.util.List.of("late", "credits"), effects);
            } finally {
                set(com.axalotl.fabrolia.common.ParallelProcessor.class,
                        null, "server", previousMain);
                set(RegionalPlayerEntityTicksV2.class, null, "enabled", previousEnabled);
            }
        }
    }

    @Test
    void consecutiveOwnVenueMainHeadsShareOneIslandClaim() throws Exception {
        try (Fixture f = new Fixture()) {
            Object previousEnabled = get(RegionalPlayerEntityTicksV2.class, null, "enabled");
            Object previousMain = get(com.axalotl.fabrolia.common.ParallelProcessor.class,
                    null, "server");
            set(RegionalPlayerEntityTicksV2.class, null, "enabled", true);
            set(com.axalotl.fabrolia.common.ParallelProcessor.class,
                    null, "server", f.level.getServer());
            try {
                // A MAIN-phase player (riders never attach) with two deferred
                // listener ticks queued at its Main ingress boundary.
                var handoff = f.player.state.session.beginMainHandoff(f.owner).orElseThrow();
                assertTrue(f.player.state.session.completeMainHandoff(handoff).orElseThrow().isEmpty());
                assertTrue(f.player.state.session.finishMainHandoff(handoff));
                assertEquals(IslandPlayerTurnSession.Phase.MAIN, f.player.state.session.view().phase());
                var held = new java.util.ArrayList<Boolean>();
                Runnable listenerTick = () -> held.add(IslandCommands.mainHolds(f.island));
                synchronized (f.player.state) {
                    f.player.state.listenerGate = f.player.connection;
                    f.player.state.mainIngress.add(AsyncIslandPlayerTicks.InboundPacket.listenerTick(
                            f.player.connection, listenerTick));
                    f.player.state.mainIngress.add(AsyncIslandPlayerTicks.InboundPacket.listenerTick(
                            f.player.connection, listenerTick));
                    f.player.state.mainIngressScheduled = true;
                }
                long claimsBefore = IslandCommands.MAIN_CLAIMS.sum();

                var drain = AsyncIslandPlayerTicks.class.getDeclaredMethod("drainMainIngress",
                        AsyncIslandPlayerTicks.PlayerState.class, ServerGamePacketListenerImpl.class);
                drain.setAccessible(true);
                drain.invoke(null, f.player.state, f.player.connection);

                assertEquals(java.util.List.of(true, true), held,
                        "every listener tick still runs under the player's island claim");
                assertEquals(1L, IslandCommands.MAIN_CLAIMS.sum() - claimsBefore,
                        "consecutive heads of the same venue take that island claim once");
                assertFalse(IslandCommands.mainHolds(f.island), "the drain releases its venue claim");
                assertFalse(f.island.unitClaim.get());
                assertTrue(f.player.state.mainIngress.isEmpty());
                assertFalse(f.player.state.mainIngressScheduled);
            } finally {
                set(com.axalotl.fabrolia.common.ParallelProcessor.class,
                        null, "server", previousMain);
                set(RegionalPlayerEntityTicksV2.class, null, "enabled", previousEnabled);
            }
        }
    }

    @Test
    void replacedListenerAndRetiredIslandCannotLendOwnership() throws Exception {
        try (Fixture f = new Fixture()) {
            f.onWorker(worker -> f.withClaim(worker, () -> {
                assertTrue(f.owns());
                ServerGamePacketListenerImpl original = f.player.connection;
                f.player.connection = (ServerGamePacketListenerImpl) unsafe().allocateInstance(ServerGamePacketListenerImpl.class);
                assertFalse(f.owns(), "the owner receipt names the exact listener lifetime");
                f.player.connection = original;
                f.island.retired = true;
                assertFalse(f.owns(), "retirement revokes a cached island receipt");
            }));
        }
    }

    @Test
    void hardExitAuthorityNeverSpreadsAcrossRideComponentNeighbors() {
        var hard = RegionalPlayerEntityTicksV2.LifecycleMutationKind.HARD_EXIT;
        var soft = RegionalPlayerEntityTicksV2.LifecycleMutationKind.SOFT_HANDOFF;
        var death = RegionalPlayerEntityTicksV2.LifecycleMutationKind.DEATH;
        assertEquals(hard, AsyncIslandPlayerLifecycle.effectivePlayerMutationKind(hard, true));
        assertEquals(soft, AsyncIslandPlayerLifecycle.effectivePlayerMutationKind(hard, false));
        assertEquals(death, AsyncIslandPlayerLifecycle.effectivePlayerMutationKind(death, true));
        assertEquals(soft, AsyncIslandPlayerLifecycle.effectivePlayerMutationKind(death, false));
    }

    @FunctionalInterface
    private interface CheckedBody { void run() throws Exception; }

    @FunctionalInterface
    private interface WorkerBody { void run(RegionWorkerThread worker) throws Exception; }

    @Test
    void mountedCameraPublicationClosesNestedAdmissionWithoutTeleportingItsOldRideComponent() throws Exception {
        try (Fixture f = new Fixture()) {
            Entity target = guardedItem();
            Entity vehicle = guardedItem();
            Entity copassenger = guardedItem();
            long identity = 1L;
            for (Entity entity : java.util.List.of(target, vehicle, copassenger, f.player)) {
                set(Entity.class, entity, "level", f.level);
                set(Entity.class, entity, "uuid", new java.util.UUID(0L, identity++));
                set(Entity.class, entity, "passengers", com.google.common.collect.ImmutableList.of());
            }
            set(Entity.class, vehicle, "passengers", com.google.common.collect.ImmutableList.of(f.player, copassenger));
            set(Entity.class, f.player, "vehicle", vehicle);
            set(Entity.class, copassenger, "vehicle", vehicle);
            f.player.cameraTarget = target;
            try {
                f.onWorker(worker -> f.withClaim(worker, () -> {
                    NetherPortalPlayerAccess.publishCamera(f.player);
                    f.player.rejectRideRead = true;
                    Object request = new Object();
                    var reservation = NetherPortalPlayerAccess.reserve(target, request);
                    assertNotNull(reservation);
                    try {
                        assertEquals(identitySet(java.util.List.of(target, f.player, vehicle, copassenger)),
                                identitySet(reservation.sourceReferences()));
                        assertTrue(reservation.isTeleportCandidate(f.player));
                        assertFalse(reservation.isTeleportCandidate(vehicle));
                        assertFalse(reservation.isTeleportCandidate(copassenger));
                        assertSame(request, NetherPortalPlayerAccess.requestForLifecycle(vehicle));
                        assertSame(request, NetherPortalPlayerAccess.requestForLifecycle(copassenger));
                    } finally {
                        reservation.cancel(null);
                        f.player.rejectRideRead = false;
                    }
                    assertNull(NetherPortalPlayerAccess.requestForLifecycle(vehicle));
                    NetherPortalPlayerAccess.withRidePublication(new Entity[]{vehicle}, () -> {
                        try {
                            set(Entity.class, f.player, "vehicle", null);
                            set(Entity.class, vehicle, "passengers", com.google.common.collect.ImmutableList.of(copassenger));
                        } catch (Exception problem) {
                            throw new AssertionError(problem);
                        }
                        NetherPortalPlayerAccess.withRidePublication(new Entity[]{f.player}, () -> {
                            NetherPortalPlayerAccess.publishCamera(f.player);
                            return null;
                        });
                        assertNull(NetherPortalPlayerAccess.reserve(target, new Object()),
                                "nested dismount publication cannot reopen the outer vehicle mutation");
                        return null;
                    });
                    var detached = NetherPortalPlayerAccess.reserve(target, new Object());
                    assertNotNull(detached);
                    try {
                        assertEquals(identitySet(java.util.List.of(target, f.player)), identitySet(detached.sourceReferences()),
                                "outer publication must retain the detached follower, not its former vehicle");
                    } finally {
                        detached.cancel(null);
                    }
                }));
            } finally {
                NetherPortalPlayerAccess.forgetCamera(f.player);
            }
        }
    }

    @Test
    void mainTrackingViewExcludesTheRetainedSourceSessionAfterAnOwnerlessCrossing() throws Exception {
        try (Fixture f = new Fixture()) {
            f.position(128, 0, f.source.gridKey);
            assertNull(f.runtime.islandOwnerOfChunk(f.player.chunkPosition().pack()));
            var early = f.player.state.session.tryReserveEarly(f.owner, 901L).orElseThrow();
            assertTrue(f.player.state.session.enterIslandBody(early));
            assertFalse(AsyncIslandPlayerTicks.tryRunMainTrackingView(f.player,
                    () -> fail("the source's entered movement still owns this player")));
            assertTrue(f.player.state.session.completeRealEarly(early));
            assertTrue(f.player.state.session.beginRealLate(early));
            assertTrue(f.player.state.session.completeRealLate(early));

            var admit = AsyncIslandPlayerTicks.class.getDeclaredMethod("ensureOwner",
                    AsyncIslandPlayerTicks.PlayerState.class, ServerPlayer.class,
                    ServerGamePacketListenerImpl.class, RegionizedServer.class,
                    IslandRegistry.PIsland.class, EpochDomain.class,
                    IslandPlayerTurnSession.PlayerKind.class);
            admit.setAccessible(true);
            synchronized (f.player.state) {
                assertSame(f.owner, admit.invoke(null, f.player.state, f.player, f.player.connection,
                        f.runtime, f.island, f.island.domain, IslandPlayerTurnSession.PlayerKind.REAL),
                        "the fixture must admit the idle source before the view acquires its lease");
            }
            assertTrue(AsyncIslandPlayerTicks.tryRunMainTrackingView(f.player, () -> {
                assertTrue(IslandCommands.mainHolds(f.island),
                        "source session identity, not current ownerless coordinates, grants exclusion");
                synchronized (f.player.state) {
                    try {
                        assertNull(admit.invoke(null, f.player.state, f.player, f.player.connection,
                                f.runtime, f.island, f.island.domain, IslandPlayerTurnSession.PlayerKind.REAL),
                                "a new island turn cannot attach during the complete view transaction");
                    } catch (ReflectiveOperationException failure) {
                        throw new AssertionError(failure);
                    }
                }
            }));
            synchronized (f.player.state) {
                assertSame(f.owner, admit.invoke(null, f.player.state, f.player, f.player.connection,
                        f.runtime, f.island, f.island.domain, IslandPlayerTurnSession.PlayerKind.REAL),
                        "the optional view must release session admission after its body");
            }
            assertFalse(f.island.unitClaim.get());
            assertFalse(IslandCommands.mainHolds(f.island));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void foreignCameraCustodyRejectsBusyPickupSoMainsLifecycleFenceCanFinish() throws Exception {
        try (Fixture f = new Fixture()) {
            Object previousEnabled = get(RegionalPlayerEntityTicksV2.class, null, "enabled");
            Object previousMain = get(ParallelProcessor.class, null, "server");
            Object previousFailure = get(RegionScheduler.class, null, "orphanFatalFailure");
            set(RegionalPlayerEntityTicksV2.class, null, "enabled", true);
            set(ParallelProcessor.class, null, "server", f.level.getServer());
            set(MinecraftServer.class, f.level.getServer(), "levels", Map.of());
            GuardedItem target = guardedItem();
            GuardedItem consumed = guardedItem();
            var handoff = f.player.state.session.beginMainHandoff(f.owner).orElseThrow();
            assertTrue(f.player.state.session.completeMainHandoff(handoff).orElseThrow().isEmpty());
            assertTrue(f.player.state.session.finishMainHandoff(handoff));
            int identity = 100;
            for (Entity entity : java.util.List.of(target, consumed, f.player)) {
                set(Entity.class, entity, "uuid", new java.util.UUID(0L, identity++));
                set(Entity.class, entity, "level", f.level);
                set(Entity.class, entity, "passengers", ImmutableList.of());
            }
            set(Entity.class, consumed, "passengers", ImmutableList.of(f.player));
            set(Entity.class, f.player, "vehicle", consumed);
            f.player.cameraTarget = target;
            Class<?> transferType = Class.forName(NetherPortalTransfers.class.getName() + "$Transfer");
            var constructor = transferType.getDeclaredConstructor(Entity.class, ServerLevel.class,
                    EpochDomain.class, long.class);
            constructor.setAccessible(true);
            Object transfer = constructor.newInstance(target, f.level, f.island.domain, 0L);
            java.util.concurrent.CountDownLatch custodyReady = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicBoolean lifecycleFinished = new java.util.concurrent.atomic.AtomicBoolean();
            FutureTask<Boolean> capture = new FutureTask<>(() -> {
                assertTrue(f.island.tryClaimAutonomousWork());
                f.island.noteClaim("foreign-camera-capture");
                try {
                    assertTrue((boolean) invokeTransfer(transfer, "captureTree", new Class<?>[]{Entity.class}, target));
                    NetherPortalPlayerAccess.publishCamera(f.player);
                    var reservation = NetherPortalPlayerAccess.reserve(target, transfer);
                    assertNotNull(reservation);
                    set(transferType, transfer, "players", reservation);
                    set(transferType, transfer, "capturing", true);
                    assertTrue(reservation.sourceReferences().stream().anyMatch(entity -> entity == consumed),
                            "camera follower custody must include the foreign ride's consumable");
                    custodyReady.countDown();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (f.runtime.globalFenceIntentReceipt() == null && System.nanoTime() < deadline)
                        Thread.onSpinWait();
                    assertNotNull(f.runtime.globalFenceIntentReceipt(), "native lifecycle must actually request its Main fence");
                    boolean accepted = (boolean) invokeTransfer(transfer, "captureCustody", new Class<?>[0]);
                    assertFalse(consumed.pending);
                    assertFalse(((Map<?, ?>) get(NetherPortalTransfers.class, null, "SOURCES"))
                            .containsKey(consumed.getUUID()));
                    return accepted;
                } finally {
                    custodyReady.countDown();
                    invokeTransfer(transfer, "cancel", new Class<?>[]{Throwable.class}, (Object) null);
                    invokeTransfer(transfer, "finishCapture", new Class<?>[0]);
                    f.island.clearClaim();
                    f.island.unitClaim.set(false);
                }
            });
            Thread capturer = new RegionWorkerThread(capture, "foreign-camera-capture");
            Thread watchdog = Thread.ofPlatform().daemon().unstarted(() -> {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!lifecycleFinished.get() && System.nanoTime() < deadline)
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
                if (!lifecycleFinished.get()) RegionScheduler.publishFatalAndSelectWinner(
                        new AssertionError("Main lifecycle fence and source custody retained each other's ownership"));
            });
            try {
                SyncItemPickup.wrap(null, consumed, () -> {
                    capturer.start();
                    try {
                        assertTrue(custodyReady.await(5, TimeUnit.SECONDS));
                        watchdog.start();
                        var scope = AsyncIslandPlayerLifecycle.begin(
                                RegionalPlayerEntityTicksV2.LifecycleMutationKind.SOFT_HANDOFF, consumed);
                        try {
                            assertTrue(IslandCommands.fenceWindowHeld(), "the actual lifecycle body must own Main's fence");
                            assertEquals(Boolean.FALSE, capture.get(5, TimeUnit.SECONDS),
                                    "custody must reject before Main releases the consumable");
                            assertFalse(consumed.pending);
                        } finally {
                            assertNull(scope.finish(null));
                        }
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    } finally {
                        lifecycleFinished.set(true);
                    }
                });
                assertTrue(f.player.lifecycle > 0, "the native lifecycle scope must advance the player identity");
                assertEquals(IslandPlayerTurnSession.Phase.MAIN, f.player.state.session.view().phase());
            } finally {
                lifecycleFinished.set(true);
                capturer.join(5_000L);
                watchdog.join(5_000L);
                NetherPortalPlayerAccess.forgetCamera(f.player);
                set(RegionalPlayerEntityTicksV2.class, null, "enabled", previousEnabled);
                set(ParallelProcessor.class, null, "server", previousMain);
                set(RegionScheduler.class, null, "orphanFatalFailure", previousFailure);
            }
        }
    }

    private static Object invokeTransfer(Object transfer, String name, Class<?>[] parameters,
                                         Object... arguments) throws Exception {
        var method = transfer.getClass().getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(transfer, arguments);
    }

    private static GuardedItem guardedItem() throws Exception {
        GuardedItem item = (GuardedItem) unsafe().allocateInstance(GuardedItem.class);
        item.guard = new SyncItemPickup.Guard();
        return item;
    }

    private static final class GuardedItem extends net.minecraft.world.entity.item.ItemEntity
            implements SyncItemPickup.Guarded, ParallelProcessor.TickGuard {
        SyncItemPickup.Guard guard;
        volatile boolean pending;
        private GuardedItem() { super(null, null); }
        @Override public SyncItemPickup.Guard fabrolia$consumptionGuard() { return guard; }
        @Override public net.minecraft.world.phys.Vec3 position() { return net.minecraft.world.phys.Vec3.ZERO; }
        @Override public BlockPos blockPosition() { return BlockPos.ZERO; }
        @Override public net.minecraft.world.phys.Vec3 getDeltaMovement() { return net.minecraft.world.phys.Vec3.ZERO; }
        @Override public net.minecraft.world.entity.Pose getPose() { return net.minecraft.world.entity.Pose.STANDING; }
        @Override public boolean fabrolia$isPortalPending() { return pending; }
        @Override public void fabrolia$setPortalPending(boolean value) { pending = value; }
        @Override public EntityTickLease fabrolia$tickLease() { throw new AssertionError("not ticking"); }
        @Override public byte fabrolia$getSyncCache() { return 0; }
        @Override public void fabrolia$setSyncCache(byte value) {}
    }

    private static java.util.Set<Entity> identitySet(java.util.Collection<? extends Entity> entities) {
        java.util.Set<Entity> identities = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        identities.addAll(entities);
        return identities;
    }

    private static final class Fixture implements AutoCloseable {
        final RegionWorkerPool pool;
        final IslandRegistry.PIsland island;
        final TestLevel level;
        final RegionizedServer runtime;
        final TestPlayer player;
        final IslandPlayerTurnSession.OwnerStamp owner;
        final Region source;
        final Region neighbor;
        final Region distant;
        final Map<ServerLevel, RegionizedServer> levels;
        final Object previousPool = get(RegionScheduler.class, null, "pool");
        final Object previousServer = get(RegionScheduler.class, null, "server");
        final Object previousActive = get(RegionScheduler.class, null, "active");
        final boolean previousThreading = FabroliaConfig.enableRegionThreading;

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            assertNull(previousPool);
            MinecraftServer server = (MinecraftServer) unsafe().allocateInstance(com.axalotl.fabrolia.common.regions.TestDedicatedServer.class);
            set(MinecraftServer.class, server, "serverThread", Thread.currentThread());
            set(MinecraftServer.class, server, "levels", Map.of());
            set(net.minecraft.util.thread.BlockableEventLoop.class, server, "pendingRunnables",
                    new java.util.concurrent.ConcurrentLinkedQueue<net.minecraft.server.TickTask>());
            pool = new RegionWorkerPool(1);
            level = (TestLevel) unsafe().allocateInstance(TestLevel.class);
            level.server = server;
            runtime = new RegionizedServer(level, new RegionGrid(3));
            runtime.asyncActive = true;
            var cells = new it.unimi.dsi.fastutil.longs.LongArrayList();
            for (int chunk : new int[]{0, 8, 16}) {
                cells.add(runtime.regionizer().grid.keyFor(chunk, 0));
            }
            runtime.islandRegistry.applyRebuildCells(java.util.List.of(cells), 0L);
            island = runtime.islandRegistry.byCell(cells.getLong(0));
            island.level = level;
            island.domain.epoch = 8L;
            island.domain.entityDispatchCutEpoch = 7L;
            Long2ObjectOpenHashMap<EpochDomain> domains = new Long2ObjectOpenHashMap<>();
            for (int chunk : new int[]{0, 8, 16}) domains.put(runtime.regionizer().grid.keyFor(chunk, 0), island.domain);
            set(RegionizedServer.class, runtime, "cellDomainSnapshot", domains);
            source = runtime.regionizer().regionForGridKey(runtime.regionizer().grid.keyFor(0, 0));
            neighbor = runtime.regionizer().regionForGridKey(runtime.regionizer().grid.keyFor(8, 0));
            distant = runtime.regionizer().regionForGridKey(runtime.regionizer().grid.keyFor(16, 0));
            source.groupedEpoch = neighbor.groupedEpoch = distant.groupedEpoch = 8L;
            island.domain.entityDispatchCutEpoch = island.domain.dispatchedEpoch = 8L;
            player = (TestPlayer) unsafe().allocateInstance(TestPlayer.class);
            player.localLevel = level;
            player.state = new AsyncIslandPlayerTicks.PlayerState();
            TestListener listener = (TestListener) unsafe().allocateInstance(TestListener.class);
            listener.state = player.state;
            player.connection = listener;
            player.connection.player = player;
            owner = new IslandPlayerTurnSession.OwnerStamp(player, player.connection, island,
                    island.id, island.gen, island.domain, 0L, 1L, IslandPlayerTurnSession.PlayerKind.REAL);
            assertTrue(player.state.session.attach(owner));
            position(8, 0, neighbor.gridKey);
            levels = (Map<ServerLevel, RegionizedServer>) get(RegionScheduler.class, null, "PER_LEVEL");
            pool.start();
            set(RegionScheduler.class, null, "pool", pool);
            set(RegionScheduler.class, null, "server", server);
            set(RegionScheduler.class, null, "active", true);
            FabroliaConfig.enableRegionThreading = true;
            levels.put(level, runtime);
        }

        void position(int chunkX, int chunkZ, long indexedKey) throws Exception {
            set(Entity.class, player, "chunkPosition", new ChunkPos(chunkX, chunkZ));
            set(Entity.class, player, "blockPosition", new BlockPos(chunkX * 16, 64, chunkZ * 16));
            player.indexedKey = indexedKey;
        }

        boolean owns() { return AsyncIslandPlayerTicks.ownsLifecycleMutation(player); }

        void withClaim(RegionWorkerThread worker, CheckedBody body) throws Exception {
            worker.currentDomain = island.domain;
            assertTrue(island.tryClaimAutonomousWork());
            island.noteClaim("lifecycle-regression");
            worker.currentRegion = source;
            assertTrue(source.tryClaim(worker, source.domain, source.domain.epoch));
            try { body.run(); }
            finally {
                source.release(worker);
                source.finishClaimTransaction();
                island.clearClaim();
                island.unitClaim.set(false);
                worker.currentDomain = null;
            }
        }

        void onWorker(WorkerBody body) throws Exception {
            CompletableFuture<Void> completion = new CompletableFuture<>();
            assertTrue(pool.enqueueGlobal(() -> {
                try {
                    body.run((RegionWorkerThread) Thread.currentThread());
                    completion.complete(null);
                } catch (Throwable failure) {
                    completion.completeExceptionally(failure);
                }
            }));
            completion.get(5L, TimeUnit.SECONDS);
        }

        @Override public void close() throws Exception {
            try { pool.stop(() -> pool.drainMainSafeAll(true)); }
            finally {
                levels.remove(level, runtime);
                FabroliaConfig.enableRegionThreading = previousThreading;
                set(RegionScheduler.class, null, "active", previousActive);
                set(RegionScheduler.class, null, "server", previousServer);
                set(RegionScheduler.class, null, "pool", previousPool);
            }
        }
    }

    private static final class TestLevel extends ServerLevel {
        MinecraftServer server;
        private TestLevel() { super(null, null, null, null, null, null, false, 0, null, false); }
        @Override public MinecraftServer getServer() { return server; }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
    }

    private static final class TestPlayer extends ServerPlayer
            implements AsyncIslandPlayerAccess, RegionKeyed, PlayerTickPhaseStamp, ParallelProcessor.TickGuard {
        AsyncIslandPlayerTicks.PlayerState state;
        ServerLevel localLevel;
        long indexedKey;
        Entity cameraTarget;
        boolean rejectRideRead;
        long lifecycle;
        volatile boolean pending;
        private TestPlayer() { super(null, null, null, null); }
        @Override public ServerLevel level() { return localLevel; }
        @Override public Entity getCamera() { return cameraTarget == null ? this : cameraTarget; }
        @Override public Entity getVehicle() {
            if (rejectRideRead) throw new AssertionError("admission live-read a foreign follower's ride topology");
            return super.getVehicle();
        }
        @Override public boolean fabrolia$isPortalPending() { return pending; }
        @Override public void fabrolia$setPortalPending(boolean value) { pending = value; }
        @Override public EntityTickLease fabrolia$tickLease() { throw new AssertionError("not ticking"); }
        @Override public byte fabrolia$getSyncCache() { return 0; }
        @Override public void fabrolia$setSyncCache(byte value) {}
        @Override public AsyncIslandPlayerTicks.PlayerState fabrolia$asyncIslandPlayerState() { return state; }
        @Override public long fabrolia$playerLifecycleEpoch() { return lifecycle; }
        @Override public void fabrolia$bumpPlayerLifecycleEpoch() { lifecycle++; }
        @Override public long fabrolia$getRegionKey() { return indexedKey; }
        @Override public void fabrolia$setRegionKey(long key) { indexedKey = key; }
        @Override public boolean fabrolia$tryMarkRegionTick(long token) { throw new AssertionError("not an entity tick"); }
        @Override public boolean fabrolia$carryRegionTickMark(long from, long to) { throw new AssertionError("not a migration"); }
    }

    private static final class TestListener extends ServerGamePacketListenerImpl implements AsyncIslandListenerAccess {
        AsyncIslandPlayerTicks.PlayerState state;
        private TestListener() { super(null, null, null, null); }
        @Override public AsyncIslandPlayerTicks.PlayerState fabrolia$asyncIslandPlayerState() { return state; }
        @Override public boolean fabrolia$bindAsyncIslandPlayerState(AsyncIslandPlayerTicks.PlayerState candidate) {
            if (state != null) return state == candidate;
            state = candidate;
            return true;
        }
        @Override public boolean fabrolia$unbindAsyncIslandPlayerState(AsyncIslandPlayerTicks.PlayerState expected) {
            if (state != expected) return false;
            state = null;
            return true;
        }
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        return (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
    }

    private static Object get(Class<?> type, Object target, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
