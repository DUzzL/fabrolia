package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundChatAckPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.util.FutureChain;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises listener callbacks through the production listener session and Main FIFO. */
@Isolated
@Timeout(20)
class AsyncIslandMainListenerTickTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void canonicalNativeDrainRecoversAcceptedHandoffWithoutAgingItsFifoSlot() throws Exception {
        try (Fixture f = new Fixture()) {
            f.pool = new RegionWorkerPool(1, f.server);
            f.pool.start();
            set(RegionScheduler.class, null, "pool", f.pool);
            set(MinecraftServer.class, f.server, "tickRateManager",
                    new net.minecraft.server.ServerTickRateManager(f.server));
            set(BlockableEventLoop.class, f.server, "blockingCount", 0);
            // Unsafe leaves the native deadline expired. Keep real shouldRun/
            // haveTime selection, rather than granting the fixture a spare-time poll.
            int tick = f.server.getTickCount();
            TickTask predecessor = new TickTask(tick - 1,
                    () -> f.listener.effects.add("native predecessor"));
            f.server.schedule(predecessor);
            f.attachIslandOwner();

            // A real portal exclusion retains independently owed P/E/L bodies.
            // Releasing it does not dispatch them or close the island session.
            NetherPortalPlayerAccess.Reservation reservation =
                    NetherPortalPlayerAccess.reserve(f.player, new Object());
            assertNotNull(reservation);
            try {
                assertTrue(reservation.ready());
                f.packet(1);
                assertTrue(AsyncIslandPlayerTicks.tryRunNativeDependencyFallback(f.player));
                f.listenerTick("owed L");
                f.packet(2);
            } finally {
                reservation.cancel(null);
            }
            assertTrue(AsyncIslandPlayerTicks.tryRunNativeDependencyFallback(f.player));
            f.packet(3);
            assertEquals(IslandPlayerTurnSession.Phase.MAIN_HANDOFF,
                    f.state.session.view().phase());
            assertEquals(List.of(), f.listener.effects);
            assertSame(predecessor, f.tasks.peek());
            assertFalse(f.server.pollTask(),
                    "the real native FIFO must refuse this young head with no spare-time budget");
            assertSame(predecessor, f.tasks.peek(),
                    "native refusal must retain the ordinary predecessor before the accepted closer");

            // Each pass is a fresh exact canonical wait, not an island/entity
            // barrier. Bound the opportunities without advancing Minecraft ticks.
            for (int pass = 0; pass < 16; pass++) {
                f.runCanonicalNativeWait();
                RegionScheduler.drainMainSyncTicks();
            }
            assertEquals(tick, f.server.getTickCount());
            assertEquals(IslandPlayerTurnSession.Phase.MAIN, f.state.session.view().phase(),
                    "an accepted closer must recover listener ownership before native task aging");
            assertEquals(List.of("native predecessor", "P1", "E:original", "owed L:original",
                    "P2", "E:original", "P3"), f.listener.effects,
                    "native FIFO service must resume every original obligation once in order");

            f.listenerTick("recovered L");
            f.runCanonicalNativeWait();
            RegionScheduler.drainMainSyncTicks();
            assertEquals(List.of("native predecessor", "P1", "E:original", "owed L:original",
                    "P2", "E:original", "P3", "recovered L:original"), f.listener.effects);
            assertEquals(2, f.player.listenerTicks);
            assertNull(f.pool.fatalFailureOrNull());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void foreignMainControlWaitsForRequestedOrPausedExactTurn(boolean whileRequested)
            throws Exception {
        try (Fixture f = new Fixture()) {
            f.pool = new RegionWorkerPool(1, f.server);
            f.pool.start();
            set(RegionScheduler.class, null, "pool", f.pool);
            f.server.schedule(new TickTask(f.server.getTickCount(),
                    () -> f.listener.effects.add("native predecessor")));
            Runnable publish = () -> CompletableFuture.runAsync(() ->
                    RegionScheduler.scheduleTrackedMainControlTask(f.server, () -> {
                        assertSame(Thread.currentThread(), f.server.getRunningThread());
                        assertFalse(RegionScheduler.ownsExclusiveGameplay(),
                                "the control receipt must not grant gameplay ownership");
                        f.listener.effects.add("packet control");
                    })).join();
            if (whileRequested) {
                RegionWorkerPool.ExternalPermit previous = f.pool.reserveExternalPermit();
                assertNotNull(previous);
                try {
                    assertDoesNotThrow(() -> f.pool.pauseExactTurnAndAwait(() -> {
                        if (previous.cancelBeforeRun()) publish.run();
                    }));
                } finally {
                    previous.cancelBeforeRun();
                }
            } else {
                f.pool.pauseExactTurnAndAwait(() -> {});
            }
            try {
                if (!whileRequested) publish.run();
                f.drain();
                assertEquals(List.of("native predecessor"), f.listener.effects,
                        "new foreign controls must not enter the pre-turn drain");
                assertNull(f.pool.fatalFailureOrNull());
            } finally {
                f.pool.resumeExactTurn();
            }
            f.drain();
            assertEquals(List.of("native predecessor", "packet control"), f.listener.effects);
            f.drain();
            assertEquals(List.of("native predecessor", "packet control"), f.listener.effects);
            assertNull(f.pool.fatalFailureOrNull());
        }
    }

    @Test
    void nativeControlCheckpointExcludesUnpublishedAndNewlyAppendedControls() throws Exception {
        CountDownLatch offering = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> publisher = new AtomicReference<>();
        Queue<TickTask> nativeTasks = new ConcurrentLinkedQueue<>() {
            @Override public boolean add(TickTask task) {
                if (Thread.currentThread() == publisher.get()) {
                    offering.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                }
                return super.add(task);
            }
        };
        try (Fixture f = new Fixture(nativeTasks)) {
            f.pool = new RegionWorkerPool(1, f.server);
            f.pool.start();
            set(RegionScheduler.class, null, "pool", f.pool);
            set(MinecraftServer.class, f.server, "tickRateManager",
                    new net.minecraft.server.ServerTickRateManager(f.server));
            int tick = f.server.getTickCount();
            f.server.schedule(new TickTask(tick - 1,
                    () -> f.listener.effects.add("predecessor")));
            CompletableFuture<Void> publication = CompletableFuture.runAsync(() -> {
                publisher.set(Thread.currentThread());
                RegionScheduler.scheduleTrackedMainControlTask(f.server,
                        () -> f.listener.effects.add("delayed publication"));
            });
            try {
                assertTrue(offering.await(5, TimeUnit.SECONDS));
                RegionScheduler.scheduleTrackedMainControlTask(f.server, () -> {
                    assertFalse(RegionScheduler.ownsExclusiveGameplay(),
                            "a native control receipt grants lifetime, not gameplay authority");
                    f.listener.effects.add("captured control");
                    RegionScheduler.scheduleTrackedMainControlTask(f.server,
                            () -> f.listener.effects.add("successor"));
                });
                TickTask suffix = new TickTask(tick, () -> {
                    f.listener.effects.add("unrelated suffix");
                    // A broken census checkpoint terminates too: crossing this
                    // native suffix releases the missing publication it awaits.
                    release.countDown();
                    publication.join();
                });
                f.server.schedule(suffix);
                f.runCanonicalNativeWait();
                assertEquals(List.of("predecessor", "captured control"), f.listener.effects);
                assertSame(suffix, nativeTasks.peek(),
                        "neither a census-only slot nor a new successor extends this checkpoint");
            } finally {
                release.countDown();
                publication.get(5, TimeUnit.SECONDS);
            }
            f.runCanonicalNativeWait();
            assertEquals(List.of("predecessor", "captured control", "unrelated suffix",
                    "successor", "delayed publication"), f.listener.effects);
            assertEquals(tick, f.server.getTickCount());
            assertNull(f.pool.fatalFailureOrNull());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"after-early", "nested-after-early", "nested-during-early",
            "failed-outer", "untracked-epoch", "mixed-batch"})
    void auditedSoftMoveRetainsOneOriginalLateBodyOnMain(String transition) throws Exception {
        try (Fixture f = new Fixture()) {
            f.attachIslandOwner();
            var owner = f.state.session.view().owner();
            var island = (IslandRegistry.PIsland) owner.islandIdentity();
            island.domain.unitGameTime = 41L;
            Thread main = Thread.currentThread();
            boolean duringEarly = transition.equals("nested-during-early");
            boolean nested = !transition.equals("after-early");
            boolean rejected = transition.equals("failed-outer") || transition.equals("untracked-epoch");
            var outerFailure = new IllegalStateException("original lifecycle body failed");
            boolean mixedBatch = transition.equals("mixed-batch");
            if (mixedBatch) {
                sun.misc.Unsafe unsafe = (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
                var other = (TestListener) unsafe.allocateInstance(TestListener.class);
                other.player = f.replacement;
                other.binding = new AtomicReference<>();
                other.effects = f.listener.effects;
                other.bodyServerTicks = new ArrayList<>();
                other.accepting = true;
                f.replacement.connection = other;
                f.replacement.localState.listenerGate = other;
                f.attachIslandOwner(f.replacement, other);
            }
            var completeEarly = AsyncIslandPlayerTicks.class.getDeclaredMethod(
                    "completeRealEarlyAndPublishLate", AsyncIslandPlayerTicks.PendingLate.class);
            completeEarly.setAccessible(true);
            f.tracker = new TestPlayerTracker(f.player, island);
            f.tracker.pairingsPending = false;
            f.tracker.onSend = () -> {
                assertSame(main, Thread.currentThread());
                assertTrue(IslandCommands.ownsPlayerLateOnMain(f.player, island));
                assertTrue(RegionScheduler.ownsCurrentContext(f.level, f.player.blockPosition()));
                f.listener.effects.add("tracking");
            };
            IslandEntityTracking.registerPlayerTracker(f.tracker);
            synchronized (f.tracker) {
                synchronized (f.state) {
                    island.pendingEntityUpdates.add(f.tracker);
                    f.state.trackingPublication.admit(f.tracker, null, owner, island);
                }
            }
            f.listener.tickBody = () -> {
                assertSame(main, Thread.currentThread());
                assertTrue(IslandCommands.ownsPlayerLateOnMain(f.player, island));
                assertEquals(41L, IslandClock.contextTime(), "the owed L retains its original unit clock");
                f.recordListenerBody("L");
            };
            // A headless pool drives the exact retained-claim protocol here;
            // the isolated native campaign supplies the full IslandTickUnit body.
            f.pool = new RegionWorkerPool(1);
            set(RegionScheduler.class, null, "pool", f.pool);
            var completed = new CountDownLatch(1);
            var workerFailure = new AtomicReference<Throwable>();
            f.pool.start();
            assertTrue(f.pool.enqueueUnit(() -> {
                RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                boolean claimed = false;
                try {
                    assertTrue(island.tryClaimAutonomousWork());
                    claimed = true;
                    island.noteClaim("soft-late-regression");
                    worker.currentDomain = island.domain;
                    var permit = f.state.session.tryReserveEarly(owner, 41L).orElseThrow();
                    assertTrue(f.state.session.enterIslandBody(permit));
                    f.level.tickNonPassenger(f.player);
                    var pending = new AsyncIslandPlayerTicks.PendingLate(
                            f.state, f.player, f.listener, owner, permit);
                    if (!duringEarly) completeEarly.invoke(null, pending);
                    assertTrue(IslandCommands.routeCommandBlock("tp original 512 64 0",
                            f.level, BlockPos.ZERO, null, () -> {
                                // Replace only the unsupported fixture subclass's
                                // outer participant classifier, as attachIslandOwner
                                // does. Execute the real audited mutation transaction.
                                var token = AsyncIslandPlayerTicks.prepareLifecycleMutationBody(f.player);
                                var idleToken = mixedBatch
                                        ? AsyncIslandPlayerTicks.prepareLifecycleMutationBody(f.replacement) : null;
                                var tokens = mixedBatch ? List.of(token, idleToken) : List.of(token);
                                AsyncIslandPlayerTicks.beginLifecycleMutationBody(token);
                                if (idleToken != null) {
                                    AsyncIslandPlayerTicks.beginLifecycleMutationBody(idleToken);
                                }
                                Throwable failure = null;
                                try {
                                    assertTrue(RegionScheduler.ownsExclusiveGameplay());
                                    if (idleToken != null) {
                                        AsyncIslandPlayerTicks.invalidateForLifecycleMutation(f.replacement,
                                                RegionalPlayerEntityTicksV2.LifecycleMutationKind.SOFT_HANDOFF);
                                        f.replacement.fabrolia$bumpPlayerLifecycleEpoch();
                                    }
                                    AsyncIslandPlayerTicks.invalidateForLifecycleMutation(f.player,
                                            RegionalPlayerEntityTicksV2.LifecycleMutationKind.SOFT_HANDOFF);
                                    f.player.fabrolia$bumpPlayerLifecycleEpoch();
                                    if (nested) {
                                        var inner = AsyncIslandPlayerTicks.prepareLifecycleMutationBody(f.player);
                                        AsyncIslandPlayerTicks.beginLifecycleMutationBody(inner);
                                        AsyncIslandPlayerTicks.invalidateForLifecycleMutation(f.player,
                                                RegionalPlayerEntityTicksV2.LifecycleMutationKind.SOFT_HANDOFF);
                                        f.player.fabrolia$bumpPlayerLifecycleEpoch();
                                        assertNull(AsyncIslandPlayerTicks.finishLifecycleMutationBody(inner, null));
                                        assertNull(AsyncIslandPlayerTicks.finishLifecycleHandoffPublications(
                                                List.of(inner), 1, null));
                                    }
                                    set(Entity.class, f.player, "chunkPosition",
                                            f.replacement.chunkPosition());
                                    set(Entity.class, f.player, "blockPosition",
                                            f.replacement.blockPosition());
                                    f.listener.effects.add("move");
                                    if (transition.equals("failed-outer")) throw outerFailure;
                                } catch (Throwable thrown) {
                                    failure = thrown;
                                } finally {
                                    if (idleToken != null) {
                                        failure = AsyncIslandPlayerTicks.finishLifecycleMutationBody(idleToken, failure);
                                    }
                                    failure = AsyncIslandPlayerTicks.finishLifecycleMutationBody(token, failure);
                                    if (transition.equals("untracked-epoch")) {
                                        f.player.fabrolia$bumpPlayerLifecycleEpoch();
                                    }
                                    failure = AsyncIslandPlayerTicks.finishLifecycleHandoffPublications(
                                            tokens, tokens.size(), failure);
                                }
                                if (failure != null) throw new AssertionError(failure);
                                if (mixedBatch) {
                                    f.drain();
                                    assertEquals(IslandPlayerTurnSession.Phase.MAIN,
                                            f.replacement.localState.session.view().phase());
                                }
                                return true;
                            }));
                    if (duringEarly) completeEarly.invoke(null, pending);
                    AsyncIslandPlayerTicks.runLate(island);
                } catch (Throwable failure) {
                    workerFailure.set(failure);
                } finally {
                    worker.currentDomain = null;
                    if (claimed) {
                        island.clearClaim();
                        island.unitClaim.set(false);
                    }
                    completed.countDown();
                }
            }));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (completed.getCount() != 0) {
                assertTrue(System.nanoTime() < deadline, "the original E/L turn must settle");
                try {
                    IslandCommands.drainRoutedBatch();
                } catch (Throwable failure) {
                    if (!rejected) throw failure;
                    assertSame(f.pool.fatalFailureOrNull(), failure);
                }
                java.util.concurrent.locks.LockSupport.parkNanos(100_000L);
            }
            if (rejected) {
                assertInstanceOf(IllegalStateException.class, workerFailure.get());
                assertSame(f.pool.fatalFailureOrNull(), workerFailure.get());
                if (transition.equals("failed-outer")) assertSame(outerFailure, workerFailure.get());
                assertEquals(List.of("E:original", "move"), f.listener.effects);
                assertEquals(0, f.player.listenerTicks);
                assertEquals(0, f.tracker.publications);
                assertFalse(island.unitClaim.get());
                assertSame(workerFailure.get(), assertThrows(Throwable.class, f::stopPool));
                f.pool = null;
                return;
            }
            assertNull(workerFailure.get());
            f.drain();
            assertEquals(List.of("E:original", "move", "L:original", "tracking"), f.listener.effects);
            assertEquals(1, f.player.listenerTicks);
            assertEquals(IslandPlayerTurnSession.Phase.MAIN, f.state.session.view().phase());
            assertTrue(island.pendingPlayerLate.isEmpty());
            assertNull(f.pool.fatalFailureOrNull());
        }
    }

    @Test
    void stopDrainsAcceptedEntityOrdinalsBeforeLateNativeIngressWake() throws Exception {
        try (Fixture f = new Fixture()) {
            f.pool = new RegionWorkerPool(1, f.server);
            f.pool.start();
            set(RegionScheduler.class, null, "pool", f.pool);
            f.server.schedule(new TickTask(f.server.getTickCount(),
                    () -> f.listener.effects.add("native A")));
            f.retainedPacket(1);
            f.oweEntity();
            f.listenerTick("L");
            f.oweEntity();
            f.packet(2);
            f.server.schedule(new TickTask(f.server.getTickCount(),
                    () -> f.listener.effects.add("native Z")));
            assertEquals(List.of(), f.listener.effects);
            List<TickTask> staleWakes = f.tasks.stream()
                    .filter(RegionScheduler::isMainControlTask).toList();
            assertFalse(staleWakes.isEmpty());

            // Leave the native queue to shutdown's tracked-slot progress service.
            // Retain its old wake to exercise post-terminal delivery explicitly.
            f.stopPool();
            assertTrue(f.pool.isTerminated());
            assertEquals(List.of("native A", "P1", "E:original", "L:original",
                            "E:original", "P2"), f.listener.effects,
                    "stop must preserve the earlier native slot without draining the unrelated suffix");
            set(RegionScheduler.class, null, "pool", null);
            set(RegionScheduler.class, null, "active", false);
            f.drain();
            for (TickTask wake : staleWakes) wake.run();
            assertEquals(List.of("native A", "P1", "E:original", "L:original",
                    "E:original", "P2", "native Z"), f.listener.effects);

            // An old completed wake must not acquire a replacement pool either.
            f.pool = new RegionWorkerPool(1, f.server);
            f.pool.start();
            set(RegionScheduler.class, null, "pool", f.pool);
            set(RegionScheduler.class, null, "active", true);
            for (TickTask wake : staleWakes) wake.run();
            f.stopPool();
            assertEquals(List.of("native A", "P1", "E:original", "L:original",
                    "E:original", "P2", "native Z"), f.listener.effects);
            assertNull(f.pool.fatalFailureOrNull());
        }
    }

    @Test
    void stopResumesDeferredNativeCommandBeforeItsAcceptedEntitySuffix() throws Exception {
        try (Fixture f = new Fixture()) {
            f.pool = new RegionWorkerPool(1, f.server);
            f.pool.start();
            set(RegionScheduler.class, null, "pool", f.pool);
            f.retainedPacket(1);
            f.oweEntity();
            f.command("C");
            f.oweEntity();
            f.packet(2);

            // Native Main reaches C's original slot while E1 is still in MAIN_SYNC.
            // C must defer without entering; E1 completion owns its later slot.
            f.drain();
            assertEquals(List.of("P1"), f.listener.effects);
            assertTrue(RegionScheduler.drainMainSyncTicks());
            assertEquals(List.of("P1", "E:original"), f.listener.effects);
            List<TickTask> deferredWakes = new ArrayList<>(f.tasks);
            assertFalse(deferredWakes.isEmpty());
            f.stopPool();
            assertTrue(f.pool.isTerminated());
            assertEquals(List.of("P1", "E:original", "C", "E:original", "P2"),
                    f.listener.effects);

            set(RegionScheduler.class, null, "pool", null);
            set(RegionScheduler.class, null, "active", false);
            f.drain();
            for (TickTask wake : deferredWakes) wake.run();
            assertEquals(List.of("P1", "E:original", "C", "E:original", "P2"),
                    f.listener.effects,
                    "the retired deferred native slot cannot repeat C or publish its E suffix");
        }
    }

    @Test
    void failedStopCancelsUnenteredIngressWithoutReplacingItsFailure() throws Exception {
        try (Fixture f = new Fixture()) {
            f.pool = new RegionWorkerPool(1, f.server);
            f.pool.start();
            set(RegionScheduler.class, null, "pool", f.pool);
            f.retainedPacket(1);
            f.oweEntity();
            f.listenerTick("unentered L");
            f.command("unentered C");
            f.oweEntity();
            IllegalStateException failure = new IllegalStateException("original generation failure");
            f.pool.publishFatalFailure(failure);

            assertSame(failure, assertThrows(IllegalStateException.class, f::stopPool));
            assertTrue(f.pool.isTerminated());
            assertEquals(List.of(), f.listener.effects);
            set(RegionScheduler.class, null, "pool", null);
            set(RegionScheduler.class, null, "active", false);
            set(RegionScheduler.class, null, "orphanFatalFailure", null);
            f.drain();
            assertEquals(List.of(), f.listener.effects,
                    "cancelled wake cannot revive packets, listeners, or accepted entity bodies");
            assertSame(failure, f.pool.fatalFailureOrNull());
            f.pool = null;
        }
    }

    @Test
    void directMainListenerExcludesGameplayAndRetainsVanillaProgressWithActorsDisabled()
            throws Exception {
        try (Fixture f = new Fixture()) {
            set(RegionalPlayerEntityTicksV2.class, null, "enabled", false);
            f.pool = new RegionWorkerPool(1, f.server);
            f.pool.start();
            set(RegionScheduler.class, null, "pool", f.pool);
            var successor = new CompletableFuture<Void>();
            AsyncIslandPlayerTicks.runMainListenerTick(f.listener, () -> {
                f.recordListenerBody("direct L1");
                assertTrue(f.pool.enqueueGlobal(() -> successor.complete(null)));
                assertFalse(f.pool.runExclusiveWorkerStep(),
                        "the listener's global gameplay body must exclude another gameplay root");
                assertFalse(successor.isDone());
            });
            assertTrue(f.pool.runExclusiveWorkerStep(),
                    "the queued gameplay root must become admissible after the listener returns");
            assertTrue(successor.isDone());
            f.listenerTick("direct L2");
            assertEquals(List.of("direct L1:original", "direct L2:original"), f.listener.effects);
            assertEquals(2, f.player.listenerTicks);
            IslandRegistry.PIsland island =
                    f.runtime.islandOwnerOfChunk(f.player.chunkPosition().pack());
            assertFalse(IslandCommands.mainHolds(island));
            assertFalse(island.unitClaim.get());
        }
    }

    @Test
    void offIntentWithoutListenerBindingStillClaimsTheCommittedSpatialOwner() throws Exception {
        try (Fixture f = new Fixture()) {
            set(RegionalPlayerEntityTicksV2.class, null, "enabled", false);
            set(RegionScheduler.class, null, "active", false);
            f.listener.binding.set(null);
            f.listenerTick("OFF intent L");
            assertEquals(List.of("OFF intent L:original"), f.listener.effects);
            assertEquals(1, f.player.listenerTicks);
            assertFalse(IslandCommands.mainHolds(
                    f.runtime.islandOwnerOfChunk(f.player.chunkPosition().pack())));
        }
    }

    @Test
    void directListenerFailureKeepsItsWinnerWhileReleasingClaimAndLease() throws Exception {
        try (Fixture f = new Fixture()) {
            IllegalStateException failure = new IllegalStateException("entered direct listener");
            assertSame(failure, assertThrows(IllegalStateException.class, () ->
                    AsyncIslandPlayerTicks.runMainListenerTick(f.listener, () -> {
                        f.recordListenerBody("direct");
                        throw failure;
                    })));
            assertEquals(List.of("direct:original"), f.listener.effects);
            assertSame(failure, get(RegionScheduler.class, null, "orphanFatalFailure"));
            assertFalse(IslandCommands.mainHolds(
                    f.runtime.islandOwnerOfChunk(f.player.chunkPosition().pack())));
        }
    }

    @Test
    void firstMainPortalWithPlayerActorsDisabledRetainsItsEnteredBodyAndIngressOrder()
            throws Exception {
        try (Fixture f = new Fixture()) {
            set(RegionalPlayerEntityTicksV2.class, null, "enabled", false);
            f.listener.binding.set(null);
            AtomicReference<NetherPortalPlayerAccess.Reservation> captured = new AtomicReference<>();
            try {
                AsyncIslandPlayerTicks.runMainListenerTick(f.listener, () -> {
                    NetherPortalPlayerAccess.Reservation reservation =
                            NetherPortalPlayerAccess.reserve(f.player, new Object());
                    captured.set(reservation);
                    assertNotNull(reservation);
                    assertFalse(reservation.ready(),
                            "the first portal cannot enter while its original Main listener body still runs");
                    f.packet(1);
                    AsyncIslandPlayerTicks.runMainListenerTick(
                            f.listener, () -> f.listener.effects.add("later-listener"));
                    f.drain();
                    assertEquals(List.of(), f.listener.effects,
                            "neither packet nor listener suffix may pass the reserved portal");
                });
                assertTrue(captured.get().ready(),
                        "the portal must become admissible after the original Main body returns");
                captured.getAndSet(null).cancel(null);
                f.drain();
                assertEquals(List.of("P1", "later-listener"), f.listener.effects);
            } finally {
                NetherPortalPlayerAccess.Reservation reservation = captured.get();
                if (reservation != null) reservation.cancel(null);
            }
        }
    }

    @Test
    void mainOnlySubclassBindingRoutesOnlyWhileAnOlderObligationRemains() throws Exception {
        try (Fixture f = new Fixture()) {
            f.listener.binding.set(null);
            assertFalse(AsyncIslandPlayerTicks.enqueuePacket(
                    new ServerboundChatAckPacket(0), f.listener),
                    "ordinary first-bind policy must leave unsupported subclasses on vanilla");
            AsyncIslandPlayerTicks.runMainListenerTick(
                    f.listener, () -> f.listener.effects.add("original"));
            assertFalse(AsyncIslandPlayerTicks.enqueuePacket(
                    new ServerboundChatAckPacket(0), f.listener),
                    "identity-only Main binding must not enable permanent packet routing");

            NetherPortalPlayerAccess.Reservation reservation =
                    NetherPortalPlayerAccess.reserve(f.player, new Object());
            assertNotNull(reservation);
            try {
                f.packet(1);
                f.listenerTick("owed L");
                f.packet(2);
                f.drain();
                assertEquals(List.of("original"), f.listener.effects);
            } finally {
                reservation.cancel(null);
            }
            f.drain();
            assertEquals(List.of("original", "P1", "owed L:original", "P2"),
                    f.listener.effects);
            assertFalse(AsyncIslandPlayerTicks.enqueuePacket(
                    new ServerboundChatAckPacket(3), f.listener),
                    "draining the reservation suffix must restore vanilla admission");
        }
    }

    @Test
    void independentlyOwedListenerCallsKeepTheirPacketPositionsEvenWithinOneServerTick()
            throws Exception {
        try (Fixture f = new Fixture()) {
            int serverTick = f.server.getTickCount();
            f.retainedPacket(1);
            f.listenerTick("L1");
            f.packet(2);
            f.listenerTick("L2");
            f.packet(3);
            assertEquals(List.of(), f.listener.effects,
                    "a physical-Main listener call must wait for its older packet");

            f.drain();

            assertEquals(List.of("P1", "L1:original", "P2", "L2:original", "P3"),
                    f.listener.effects);
            assertEquals(2, f.player.listenerTicks,
                    "two accepted listener bodies cannot collapse into one tick credit");
            assertEquals(List.of(serverTick, serverTick), f.listener.bodyServerTicks);
        }
    }

    @Test
    void olderPacketReplacingThePlayerPreservesTheOwedListenerCallAndClaimsItsSuccessor()
            throws Exception {
        try (Fixture f = new Fixture()) {
            f.listener.replacement = f.replacement;
            f.retainedPacket(1);
            f.listenerTick("L");
            f.packet(2);
            assertEquals(List.of(), f.listener.effects);

            f.drain();

            assertEquals(List.of("P1", "L:replacement", "P2"), f.listener.effects);
            assertEquals(0, f.player.listenerTicks);
            assertEquals(1, f.replacement.listenerTicks,
                    "the listener owes progress to its current player, not the pre-respawn object");
            AsyncIslandPlayerTicks.runMainListenerTick(f.listener, () -> {
                NetherPortalPlayerAccess.Reservation reservation =
                        NetherPortalPlayerAccess.reserve(f.replacement, new Object());
                assertNotNull(reservation);
                try {
                    assertFalse(reservation.ready(),
                            "the successor's fresh player-local field cannot replace the entered listener's gate");
                } finally {
                    reservation.cancel(null);
                }
            });
        }
    }

    @Test
    void terminalDisconnectCancelsQueuedListenerCallsAndRejectsLaterCallbackEffects()
            throws Exception {
        try (Fixture f = new Fixture()) {
            f.retainedPacket(1);
            f.listenerTick("queued L");
            f.packet(2);
            assertEquals(List.of(), f.listener.effects);

            AsyncIslandPlayerTicks.runPhysicalMainDisconnect(f.listener, f.server, () -> {
                f.listener.effects.add("disconnect");
                f.listenerTick("pending-disconnect L");
            });
            f.listenerTick("terminal L");
            f.packet(3);
            f.drain();

            assertEquals(List.of("disconnect"), f.listener.effects,
                    "neither retained nor newly arriving work may progress a terminal listener");
            assertEquals(0, f.player.listenerTicks);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"deferred-disconnect", "direct-disconnect", "direct-respawn"})
    void routedLifecycleCompletesBeforeTheNextListenerBodyCanEnter(String transition)
            throws Exception {
        try (Fixture f = new Fixture()) {
            boolean deferred = transition.equals("deferred-disconnect");
            boolean respawn = transition.equals("direct-respawn");
            if (deferred) {
                f.retainedPacket(1);
                f.drain();
                f.retainedPacket(2);
                f.listenerTick("owed L");
            }
            IslandRegistry.PIsland island = f.runtime.islandOwnerOfChunk(f.player.chunkPosition().pack());
            // Main now grants the complete worker root before admitting another
            // listener producer; a direct L cannot begin a claim wait beside it.
            f.onWorker(() -> {
                RegionWorkerThread self = (RegionWorkerThread) Thread.currentThread();
                assertTrue(island.tryClaimAutonomousWork());
                island.noteClaim("listener-disconnect-regression");
                self.currentDomain = island.domain;
                try {
                    assertTrue(IslandCommands.routeCommandBlock(
                            respawn ? "respawn FixturePlayer" : "kick FixturePlayer", f.level,
                            BlockPos.ZERO, null, () -> {
                                assertTrue(IslandCommands.fenceWindowHeld());
                                assertEquals(0, f.player.listenerTicks);
                                if (respawn) {
                                    f.player.fabrolia$bumpPlayerLifecycleEpoch();
                                    f.listener.player = f.replacement;
                                    f.listener.effects.add("respawn");
                                } else {
                                    AsyncIslandPlayerTicks.runPhysicalMainDisconnect(f.listener, f.server,
                                            () -> f.listener.effects.add("routed disconnect"));
                                }
                                return true;
                            }));
                } finally {
                    self.currentDomain = null;
                    island.clearClaim();
                    island.unitClaim.set(false);
                }
            });
            if (deferred) f.drain();
            else f.listenerTick("direct L");
            assertEquals(0, f.player.listenerTicks,
                    "the source lifetime cannot enter a listener body after routed lifecycle completion");
            if (respawn) {
                assertSame(f.replacement, f.listener.player);
                assertEquals(List.of("respawn", "direct L:replacement"), f.listener.effects);
                assertEquals(1, f.replacement.listenerTicks);
                f.listenerTick("successor L");
                assertEquals(List.of("respawn", "direct L:replacement", "successor L:replacement"),
                        f.listener.effects);
                assertEquals(2, f.replacement.listenerTicks);
            } else {
                assertSame(f.player, f.listener.player,
                        "terminalization is recognized when both listener/player pointers survive");
                assertEquals(deferred ? List.of("P1", "routed disconnect")
                        : List.of("routed disconnect"), f.listener.effects);
                f.listenerTick("terminal L");
                assertEquals(0, f.player.listenerTicks);
            }
            assertEquals(0, f.state.mainExecutionLeases);
            assertFalse(island.unitClaim.get());
            assertFalse(IslandCommands.mainHolds(island));
            assertFalse(IslandCommands.fenceWindowHeld());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"say nested interest", "scoreboard players add test objective 1"})
    void nestedRoutedFenceRetainsMainInterestUntilTheOuterBodyReturns(String command) throws Exception {
        try (Fixture f = new Fixture()) {
            f.retainedPacket(1);
            f.listenerTick("owed L");
            f.packet(2);
            IslandRegistry.PIsland island = f.runtime.islandOwnerOfChunk(f.player.chunkPosition().pack());
            TemporalControl.Session sprint = new TemporalControl.Session("nested interest");
            sprint.cells.addAll(island.cells);
            sprint.sprintRemaining.set(2L);
            set(RegionizedServer.class, f.runtime, "temporalSessions",
                    new java.util.concurrent.CopyOnWriteArrayList<>(List.of(sprint)));
            set(MinecraftServer.class, f.server, "tickRateManager",
                    new net.minecraft.server.ServerTickRateManager(f.server));
            var prepareCompletion = IslandTickUnit.class.getDeclaredMethod(
                    "prepareCompletion", IslandRegistry.PIsland.class, long.class);
            prepareCompletion.setAccessible(true);
            var chain = prepareCompletion.getReturnType().getDeclaredMethod("chain");
            chain.setAccessible(true);
            java.util.function.BooleanSupplier successorAllowed = () -> {
                try {
                    return (Boolean) chain.invoke(prepareCompletion.invoke(null, island, System.nanoTime()));
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError(failure);
                }
            };
            assertTrue(successorAllowed.getAsBoolean(),
                    "the matching production sprint must otherwise permit a successor");
            f.onWorker(() -> {
                RegionWorkerThread self = (RegionWorkerThread) Thread.currentThread();
                assertTrue(island.tryClaimAutonomousWork());
                island.noteClaim("nested-interest-regression");
                self.currentDomain = island.domain;
                try {
                    assertTrue(IslandCommands.routeCommandBlock(command, f.level,
                            BlockPos.ZERO, null, () -> {
                                assertTrue(IslandCommands.fenceWindowHeld());
                                assertFalse(successorAllowed.getAsBoolean());
                                AsyncIslandPlayerTicks.runFencedMainMutation("nested interest", () -> {
                                    assertTrue(IslandCommands.fenceWindowHeld());
                                    assertFalse(successorAllowed.getAsBoolean());
                                });
                                assertTrue(f.runtime.mainClaimsFenceActive(),
                                        "nested release cannot revoke the outer routed Main fence");
                                assertFalse(successorAllowed.getAsBoolean(),
                                        "the actual completion plan must refuse a sprint successor while Main owns the body");
                                assertEquals(List.of(), f.listener.effects,
                                        "the waiting listener FIFO cannot enter during the selected root");
                                return true;
                            }));
                    assertFalse(f.runtime.mainClaimsFenceActive());
                    assertFalse(island.claimWanted);
                    assertFalse(successorAllowed.getAsBoolean(),
                            "releasing the routed fence cannot chain a local unit inside the selected global root");
                } finally {
                    self.currentDomain = null;
                    island.clearClaim();
                    island.unitClaim.set(false);
                }
            });
            assertTrue(successorAllowed.getAsBoolean(),
                    "the unconsumed sprint becomes admissible after the complete global root returns");
            f.drain();
            assertEquals(List.of("P1", "owed L:original", "P2"), f.listener.effects);
            assertEquals(0, f.state.mainExecutionLeases);
            assertFalse(island.claimWanted);
            assertFalse(island.unitClaim.get());
            assertFalse(IslandCommands.mainHolds(island));
            assertFalse(IslandCommands.fenceWindowHeld());
        }
    }

    @Test
    void enteredListenerFailureEscapesUnchangedAndForbidsItsPacketAndListenerSuffix()
            throws Exception {
        try (Fixture f = new Fixture()) {
            RuntimeException failure = new IllegalStateException("entered listener failed");
            f.retainedPacket(1);
            AsyncIslandPlayerTicks.runMainListenerTick(f.listener, () -> {
                f.recordListenerBody("failing L");
                throw failure;
            });
            f.packet(2);
            f.listenerTick("suffix L");
            f.packet(3);
            assertEquals(List.of(), f.listener.effects);

            assertSame(failure, assertThrows(RuntimeException.class, f::drain));
            assertEquals(List.of("P1", "failing L:original"), f.listener.effects);
            assertEquals(1, f.player.listenerTicks,
                    "an entered failed body is not replayable and its untouched suffix cannot enter");
        }
    }

    @Test
    void codecValidNegativeAcknowledgementsRejectLocallyBeforeMutationAndKeepFifo()
            throws Exception {
        try (Fixture f = new Fixture()) {
            f.retainedPacket(7);
            List<Packet<?>> invalid = new ArrayList<>();
            invalid.add(decoded(ServerboundUseItemPacket.STREAM_CODEC,
                    new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, -1, 0, 0)));
            invalid.add(decoded(ServerboundUseItemOnPacket.STREAM_CODEC,
                    new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.ZERO, Direction.UP, BlockPos.ZERO, false), -1)));
            for (var action : List.of(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK)) {
                invalid.add(decoded(ServerboundPlayerActionPacket.STREAM_CODEC,
                        new ServerboundPlayerActionPacket(action, BlockPos.ZERO, Direction.UP, -1)));
            }
            for (Packet<?> packet : invalid) {
                assertTrue(AsyncIslandPlayerTicks.enqueuePacket(packet, f.listener));
            }
            assertTrue(AsyncIslandPlayerTicks.enqueuePacket(
                    new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 0, 0, 0), f.listener));
            for (var action : ServerboundPlayerActionPacket.Action.values()) {
                if (action == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                        || action == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK
                        || action == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) continue;
                assertTrue(AsyncIslandPlayerTicks.enqueuePacket(
                        new ServerboundPlayerActionPacket(action, BlockPos.ZERO, Direction.UP, -1),
                        f.listener));
            }
            f.packet(8);
            f.drain();
            List<String> expected = new ArrayList<>(List.of("P7"));
            for (int ignored = 0; ignored < invalid.size(); ignored++) expected.add("rejected");
            expected.add("use:0");
            for (var action : ServerboundPlayerActionPacket.Action.values()) {
                if (action == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                        || action == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK
                        || action == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) continue;
                expected.add("action:" + action);
            }
            expected.add("P8");
            assertEquals(expected, f.listener.effects);
            assertNull(get(RegionScheduler.class, null, "orphanFatalFailure"));
        }
    }

    @Test
    void unloadedClientKeepsVanillasIgnoredNegativeSequence() throws Exception {
        try (Fixture f = new Fixture()) {
            f.retainedPacket(7);
            set(ServerGamePacketListenerImpl.class, f.listener, "waitingForRespawn", true);
            assertTrue(AsyncIslandPlayerTicks.enqueuePacket(
                    new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, -1, 0, 0), f.listener));
            f.packet(8);
            f.drain();
            assertEquals(List.of("P7", "P8"), f.listener.effects);
            assertNull(get(RegionScheduler.class, null, "orphanFatalFailure"));
        }
    }

    @Test
    void admittedPacketFailureAfterMutationStillStopsTheGenerationAndSuffix()
            throws Exception {
        try (Fixture f = new Fixture()) {
            f.retainedPacket(7);
            IllegalArgumentException failure = new IllegalArgumentException("after item mutation");
            f.listener.itemFailure = failure;
            assertTrue(AsyncIslandPlayerTicks.enqueuePacket(
                    new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 0, 0, 0), f.listener));
            f.packet(8);
            assertSame(failure, assertThrows(IllegalArgumentException.class, f::drain));
            assertEquals(List.of("P7", "use:0"), f.listener.effects);
            assertSame(failure, get(RegionScheduler.class, null, "orphanFatalFailure"));
        }
    }

    @Test
    void delayedChatKeySurvivesPlayerLifecycleAndRespawnWithoutReorderingTheChain()
            throws Exception {
        try (Fixture f = new Fixture()) {
            f.retainedPacket(7);
            var owner = AsyncIslandPacketContinuations.captureChatSession(
                    f.continuationOwner());
            Queue<Runnable> completions = new ConcurrentLinkedQueue<>();
            CompletableFuture<Integer> filtering = new CompletableFuture<>();
            try (FutureChain chain = new FutureChain(completions::add)) {
                chain.append(filtering, ignored -> f.listener.effects.add("filtered"));
                chain.append(() -> assertTrue(AsyncIslandPacketContinuations.enqueueChatSession(
                        owner, () -> f.listener.effects.add("key:" + ((TestPlayer) f.listener.player).label))));
                f.player.fabrolia$bumpPlayerLifecycleEpoch();
                filtering.complete(1);
                for (Runnable task; (task = completions.poll()) != null;) task.run();
                f.listener.player = f.replacement;
                f.packet(8);
                f.drain();
                assertEquals(List.of("filtered", "P7", "key:replacement", "P8"), f.listener.effects);
                assertNull(get(RegionScheduler.class, null, "orphanFatalFailure"));
            }
        }
    }

    @Test
    void delayedEarlierKeyStillPrecedesItsSignedMessageWhenANewerDecoderIsInstalled()
            throws Exception {
        try (Fixture f = new Fixture()) {
            f.retainedPacket(7);
            Queue<Runnable> completions = new ConcurrentLinkedQueue<>();
            CompletableFuture<Integer> filtering = new CompletableFuture<>();
            AtomicReference<String> publishedKey = new AtomicReference<>();
            var playerOwner = f.continuationOwner();
            try (FutureChain chain = new FutureChain(completions::add)) {
                chain.append(filtering, ignored -> {});
                var first = AsyncIslandPacketContinuations.captureChatSession(playerOwner);
                chain.append(() -> assertTrue(AsyncIslandPacketContinuations.enqueueChatSession(
                        first, () -> {
                            publishedKey.set("key1");
                            f.listener.effects.add("key1");
                        })));
                AsyncIslandPacketContinuations.mainTaskChainer(
                        playerOwner, chain, "signed-chat").append(
                        CompletableFuture.completedFuture("key1"), signingKey -> {
                            assertEquals(signingKey, publishedKey.get(),
                                    "the signed message requires its intermediate key publication");
                            f.listener.effects.add("message1");
                        });
                // resetPlayerChatState has installed the newer decoder before
                // either pending key callback can execute. It must not erase key1.
                var second = AsyncIslandPacketContinuations.captureChatSession(playerOwner);
                chain.append(() -> assertTrue(AsyncIslandPacketContinuations.enqueueChatSession(
                        second, () -> {
                            publishedKey.set("key2");
                            f.listener.effects.add("key2");
                        })));
                filtering.complete(1);
                for (Runnable task; (task = completions.poll()) != null;) task.run();
                f.drain();
                assertEquals(List.of("P7", "key1", "message1", "key2"), f.listener.effects);
                assertEquals("key2", publishedKey.get());
                assertNull(get(RegionScheduler.class, null, "orphanFatalFailure"));
            }
        }
    }

    @Test
    void replacedListenerCannotPublishItsQueuedChatKey() throws Exception {
        try (Fixture f = new Fixture()) {
            f.retainedPacket(7);
            var owner = AsyncIslandPacketContinuations.captureChatSession(
                    f.continuationOwner());
            assertTrue(AsyncIslandPacketContinuations.enqueueChatSession(
                    owner, () -> f.listener.effects.add("stale-key")));
            f.player.connection = null;
            f.drain();
            assertEquals(List.of("P7"), f.listener.effects);
        }
    }

    @Test
    void listenerLeavingPlayCannotPublishItsQueuedChatKey() throws Exception {
        try (Fixture f = new Fixture()) {
            f.retainedPacket(7);
            var owner = AsyncIslandPacketContinuations.captureChatSession(
                    f.continuationOwner());
            assertTrue(AsyncIslandPacketContinuations.enqueueChatSession(
                    owner, () -> f.listener.effects.add("closed-key")));
            f.listener.accepting = false;
            f.drain();
            assertEquals(List.of("P7"), f.listener.effects);
        }
    }

    private static <T> T decoded(StreamCodec<FriendlyByteBuf, T> codec, T packet) {
        FriendlyByteBuf bytes = new FriendlyByteBuf(Unpooled.buffer());
        try {
            codec.encode(bytes, packet);
            return codec.decode(bytes);
        } finally {
            bytes.release();
        }
    }

    @Test
    void fencedMainSyncRetainsGlobalOwnershipWithoutAutonomousIslands() throws Exception {
        try (Fixture f = new Fixture()) {
            f.runtime.asyncActive = false;
            set(RegionizedServer.class, f.runtime, "cellDomainSnapshot", null);
            set(RegionalPlayerEntityTicksV2.class, null, "enabled", false);
            assertFalse(IslandCommands.requiresMainFence());
            ((TestLevel) f.level).entityBody = () -> {
                assertTrue(RegionScheduler.ownsExclusiveGameplay());
                RegionScheduler.runGlobalMainBody(f.server,
                        () -> f.listener.effects.add("nested mutation"));
                assertTrue(RegionScheduler.ownsExclusiveGameplay(),
                        "the complete producer must remain owned after its nested mutation");
                f.listener.effects.add("native tail");
            };
            f.onWorker(() -> RegionScheduler.queueMainSyncFencedTick(f.player, () -> {
                assertTrue(RegionScheduler.ownsExclusiveGameplay(),
                        "even native admission reads require the complete Main envelope");
                f.listener.effects.add("admission");
                return true;
            }, () -> f.listener.effects.add("completion")));
            assertFalse(RegionScheduler.ownsExclusiveGameplay(),
                    "physical Main alone must not authenticate the queued producer");

            assertTrue(RegionScheduler.drainMainSyncTicks());

            assertEquals(List.of("admission", "E:original", "nested mutation",
                    "native tail", "completion"), f.listener.effects);
            assertFalse(RegionScheduler.ownsExclusiveGameplay());
            assertNull(f.pool.fatalFailureOrNull());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void synchronousDisconnectOwnsItsCompleteNativeProducer(boolean boundListener) throws Exception {
        try (Fixture f = new Fixture()) {
            f.runtime.asyncActive = false;
            set(RegionizedServer.class, f.runtime, "cellDomainSnapshot", null);
            set(RegionalPlayerEntityTicksV2.class, null, "enabled", false);
            if (!boundListener) f.listener.binding.set(null);
            f.onWorker(() -> {});
            assertFalse(IslandCommands.requiresMainFence());
            assertFalse(RegionScheduler.ownsExclusiveGameplay());

            AsyncIslandPlayerTicks.runPhysicalMainDisconnect(f.listener, f.server, () -> {
                com.axalotl.fabrolia.common.parallelised.advancement.AdvancementCaptureOwnership
                        .requireGlobalProducerEnvelope();
                RegionScheduler.runGlobalMainBody(f.server,
                        () -> f.listener.effects.add("native removal"));
                com.axalotl.fabrolia.common.parallelised.advancement.AdvancementCaptureOwnership
                        .requireGlobalProducerEnvelope();
                f.listener.effects.add("clear triggers");
            });

            assertEquals(List.of("native removal", "clear triggers"), f.listener.effects);
            assertFalse(RegionScheduler.ownsExclusiveGameplay());
            assertNull(f.pool.fatalFailureOrNull());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mainMovementWaitsForDestinationOwnershipBeforeItsPacketAndFifoSuffix(
            boolean ownerlessSource) throws Exception {
        try (Fixture f = new Fixture()) {
            if (ownerlessSource) {
                set(Entity.class, f.player, "chunkPosition", new ChunkPos(64, 0));
                set(Entity.class, f.player, "blockPosition", new BlockPos(1024, 64, 0));
            }
            ChunkPos source = f.player.chunkPosition();
            ChunkPos target = f.replacement.chunkPosition();
            IslandRegistry.PIsland destination = f.runtime.islandOwnerOfChunk(target.pack());
            assertNotNull(destination);
            assertNotSame(destination, f.runtime.islandOwnerOfChunk(source.pack()));
            if (ownerlessSource) assertNull(f.runtime.islandOwnerOfChunk(source.pack()));
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
            set(ServerPlayer.class, f.player, "advancements",
                    unsafe.allocateInstance(MovementAdvancements.class));
            // As in the retained-L fixture, run the exact claim protocol without
            // a server-bound pool requiring a native IslandTickUnit publication.
            f.pool = new RegionWorkerPool(1);
            f.pool.start();
            set(RegionScheduler.class, null, "pool", f.pool);

            var packet = new ServerboundMovePlayerPacket.Pos(
                    target.x() * 16.0, 64.0, target.z() * 16.0, true, false);
            var reservation = NetherPortalPlayerAccess.reserve(f.player, new Object());
            assertNotNull(reservation);
            try {
                assertTrue(AsyncIslandPlayerTicks.enqueuePacket(packet, f.listener));
                f.packet(2);
            } finally {
                reservation.cancel(null);
            }

            var claimed = new CountDownLatch(1);
            var queryComplete = new CompletableFuture<Void>();
            var packetEntered = new java.util.concurrent.atomic.AtomicBoolean();
            var abort = new java.util.concurrent.atomic.AtomicBoolean();
            f.listener.movementBody = () -> {
                try {
                    set(Entity.class, f.player, "chunkPosition", target);
                    set(Entity.class, f.player, "blockPosition",
                            new BlockPos(target.x() << 4, 64, target.z() << 4));
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                packetEntered.set(true);
            };
            assertTrue(f.pool.enqueueUnit(() -> {
                RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                assertTrue(destination.tryClaimAutonomousWork());
                destination.noteClaim("movement-destination-query");
                worker.currentDomain = destination.domain;
                try {
                    claimed.countDown();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!destination.claimWanted && !packetEntered.get() && !abort.get()) {
                        if (System.nanoTime() >= deadline) fail("Main never reached movement admission");
                        java.util.concurrent.locks.LockSupport.parkNanos(20_000L);
                    }
                    assertFalse(packetEntered.get(),
                            "the complete movement packet must wait for the destination query owner");
                    assertEquals(source, f.player.chunkPosition(),
                            "a retained query volume must not observe a Main-owned player crossing into it");
                    queryComplete.complete(null);
                } catch (Throwable failure) {
                    queryComplete.completeExceptionally(failure);
                } finally {
                    worker.currentDomain = null;
                    destination.clearClaim();
                    destination.unitClaim.set(false);
                }
            }));
            try {
                assertTrue(claimed.await(5, TimeUnit.SECONDS));
                f.drain();
                queryComplete.get(5, TimeUnit.SECONDS);
                assertEquals(target, f.player.chunkPosition());
                assertEquals(List.of("move", "P2"), f.listener.effects,
                        "the packet and its suffix run once, with no synthesized E or L");
            } finally {
                abort.set(true);
            }
        }
    }

    private static final class MovementAdvancements extends net.minecraft.server.PlayerAdvancements
            implements com.axalotl.fabrolia.common.parallelised.advancement.AdvancementRequirements.ActiveListeners {
        private MovementAdvancements() { super(null, null, null, null, null); }
        @Override
        public Map<net.minecraft.advancements.triggers.CriterionTrigger<?>,
                Map<TriggerInstanceKey, ? extends net.minecraft.advancements.CriterionTriggerInstance>>
                fabrolia$activeTriggers() {
            return Map.of();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final AsyncIslandPlayerTicks.PlayerState state = new AsyncIslandPlayerTicks.PlayerState();
        final TestPlayer player;
        final TestPlayer replacement;
        final TestListener listener;
        final MinecraftServer server;
        final ServerLevel level;
        final RegionizedServer runtime;
        final Map<ServerLevel, RegionizedServer> levels;
        final Object previousActive;
        final Object previousEnabled;
        final Object previousPool;
        final Object previousFailure;
        final Object previousServer;
        final Object previousSchedulerServer;
        final Queue<TickTask> tasks;
        RegionWorkerPool pool;
        TestPlayerTracker tracker;

        Fixture() throws Exception {
            this(new ConcurrentLinkedQueue<>());
        }

        @SuppressWarnings("unchecked")
        Fixture(Queue<TickTask> tasks) throws Exception {
            this.tasks = tasks;
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
            server = (MinecraftServer) unsafe.allocateInstance(com.axalotl.fabrolia.common.regions.TestDedicatedServer.class);
            PreparedAdvancementTestResources.install(server);
            level = (ServerLevel) unsafe.allocateInstance(TestLevel.class);
            listener = (TestListener) unsafe.allocateInstance(TestListener.class);
            set(MinecraftServer.class, server, "serverThread", Thread.currentThread());
            // Claim waits have no chunk-service queues to pump in this fixture.
            set(MinecraftServer.class, server, "levels", Map.of());
            set(BlockableEventLoop.class, server, "pendingRunnables", tasks);
            // Both are essential: without level.server the public API passes straight through.
            set(ServerLevel.class, level, "server", server);
            runtime = new RegionizedServer(level, new RegionGrid(3));
            runtime.asyncActive = true;

            IslandRegistry registry = runtime.islandRegistry;
            ThreadedRegionizer regionizer = runtime.regionizer();
            ChunkPos originalChunk = new ChunkPos(0, 0);
            ChunkPos replacementChunk = new ChunkPos(32, 0);
            long originalCell = regionizer.grid.keyFor(originalChunk.pack());
            long replacementCell = regionizer.grid.keyFor(replacementChunk.pack());
            registry.applyRebuildCells(List.of(new LongArrayList(new long[]{originalCell}),
                    new LongArrayList(new long[]{replacementCell})), 0L);
            for (IslandRegistry.PIsland island : registry.islands()) island.level = level;
            // Assemble the cold fixture topology before exposing it through its owner.
            regionizer.owner = null;
            regionizer.regionForGridKey(originalCell)
                    .initializeDomainAtCreation(registry.byCell(originalCell).domain);
            regionizer.regionForGridKey(replacementCell)
                    .initializeDomainAtCreation(registry.byCell(replacementCell).domain);
            regionizer.owner = runtime;

            player = player(unsafe, "original", originalChunk);
            replacement = player(unsafe, "replacement", replacementChunk);
            replacement.localState = new AsyncIslandPlayerTicks.PlayerState();
            listener.player = player;
            listener.binding = new AtomicReference<>(state);
            listener.effects = new ArrayList<>();
            listener.bodyServerTicks = new ArrayList<>();
            listener.accepting = true;
            state.listenerGate = listener;

            levels = (Map<ServerLevel, RegionizedServer>) get(RegionScheduler.class, null, "PER_LEVEL");
            previousActive = get(RegionScheduler.class, null, "active");
            previousEnabled = get(RegionalPlayerEntityTicksV2.class, null, "enabled");
            previousPool = get(RegionScheduler.class, null, "pool");
            previousFailure = get(RegionScheduler.class, null, "orphanFatalFailure");
            previousServer = get(ParallelProcessor.class, null, "server");
            previousSchedulerServer = get(RegionScheduler.class, null, "server");
            set(ParallelProcessor.class, null, "server", server);
            set(RegionScheduler.class, null, "server", server);
            levels.put(level, runtime);
            set(RegionScheduler.class, null, "active", true);
            set(RegionalPlayerEntityTicksV2.class, null, "enabled", true);
            set(RegionScheduler.class, null, "pool", null);
            set(RegionScheduler.class, null, "orphanFatalFailure", null);
        }

        AsyncIslandPacketContinuations.ContinuationOwner continuationOwner() throws Exception {
            // The untransformed fixture replaces only the exact player-class capture.
            var constructor = AsyncIslandPacketContinuations.ContinuationOwner.class
                    .getDeclaredConstructor(ServerGamePacketListenerImpl.class, ServerPlayer.class,
                            AsyncIslandPlayerTicks.PlayerState.class, ServerLevel.class,
                            RegionizedServer.class, long.class);
            constructor.setAccessible(true);
            return constructor.newInstance(listener, player, state, level, runtime,
                    player.fabrolia$playerLifecycleEpoch());
        }

        private TestPlayer player(sun.misc.Unsafe unsafe, String name, ChunkPos chunk) throws Exception {
            TestPlayer result = (TestPlayer) unsafe.allocateInstance(TestPlayer.class);
            result.localState = state;
            result.localLevel = level;
            result.label = name;
            result.lease = new EntityTickLease();
            result.connection = listener;
            set(Entity.class, result, "passengers", com.google.common.collect.ImmutableList.of());
            set(Entity.class, result, "chunkPosition", chunk);
            long packed = chunk.pack();
            set(Entity.class, result, "blockPosition",
                    new BlockPos((int) packed << 4, 64, (int) (packed >>> 32) << 4));
            return result;
        }

        void retainedPacket(int sequence) {
            // The unsupported fixture subclass is normally vanilla-owned. Establish
            // a real older FIFO obligation instead of relying on its prebound field
            // to enable routing that production deliberately leaves inert.
            NetherPortalPlayerAccess.Reservation reservation =
                    NetherPortalPlayerAccess.reserve(listener.player, new Object());
            assertNotNull(reservation);
            try {
                packet(sequence);
            } finally {
                reservation.cancel(null);
            }
        }

        void packet(int sequence) {
            // This audited Main-required packet needs no world/inventory fixture.
            assertTrue(AsyncIslandPlayerTicks.enqueuePacket(new ServerboundChatAckPacket(sequence), listener));
        }

        void listenerTick(String label) {
            AsyncIslandPlayerTicks.runMainListenerTick(listener, () -> recordListenerBody(label));
        }

        void recordListenerBody(String label) {
            TestPlayer current = (TestPlayer) listener.player;
            assertTrue(RegionScheduler.ownsExclusiveGameplay(),
                    "native listener producers require global gameplay ownership, including after replacement");
            assertTrue(RegionScheduler.ownsCurrentContext(level, current.blockPosition()),
                    "the listener's real gameplay grant must cover the current player's location");
            current.listenerTicks++;
            listener.bodyServerTicks.add(server.getTickCount());
            listener.effects.add(label + ":" + current.label);
        }

        void onWorker(Runnable body) throws Exception {
            assertNull(pool);
            pool = new RegionWorkerPool(1, server);
            pool.start();
            set(RegionScheduler.class, null, "pool", pool);
            assertTrue(pool.enqueueGlobal(body));
            assertTrue(pool.runExclusiveWorkerStep());
        }

        void attachIslandOwner() throws Exception {
            attachIslandOwner(player, listener);
        }

        void attachIslandOwner(TestPlayer player, TestListener listener) throws Exception {
            AsyncIslandPlayerTicks.PlayerState state = player.localState;
            IslandRegistry.PIsland island = runtime.islandOwnerOfChunk(player.chunkPosition().pack());
            var method = AsyncIslandPlayerTicks.class.getDeclaredMethod("ensureOwner",
                    AsyncIslandPlayerTicks.PlayerState.class, ServerPlayer.class,
                    ServerGamePacketListenerImpl.class, RegionizedServer.class,
                    IslandRegistry.PIsland.class, EpochDomain.class,
                    IslandPlayerTurnSession.PlayerKind.class);
            method.setAccessible(true);
            // Replace only the unsupported fixture subclass's admission classifier;
            // production installs the session, listener binding and source listing.
            listener.binding.set(null);
            synchronized (state) {
                assertNotNull(method.invoke(null, state, player, listener, runtime, island,
                        island.domain, IslandPlayerTurnSession.PlayerKind.REAL));
            }
        }

        void runCanonicalNativeWait() {
            RconCommandFenceScope.runServerLoopRoot(server, () ->
                    RconCommandFenceScope.runServerEntry(server, () ->
                            RconCommandFenceScope.runServerWaitUntilNextTick(server, () ->
                                    RconCommandFenceScope.runWaitUntilNextTickEntry(server, () -> {
                                        RconCommandFenceScope.runWaitUntilNextTickRunAllTasks(
                                                server, this::drainNativeTasks);
                                        RconCommandFenceScope.runWaitUntilNextTickManagedBlock(
                                                server, () -> server.managedBlock(() -> true));
                                    }))));
        }

        void drainNativeTasks() {
            // JUnit does not weave the native poll hooks. Reproduce their public
            // wrappers around the actual MinecraftServer queue selection.
            for (int poll = 0; poll < 64; poll++) {
                boolean progressed = RconCommandFenceScope.callRunAllTasksPoll(server, () ->
                        RconCommandFenceScope.callMainPoll(server, () -> {
                            RegionScheduler.throwWorkerFailureIfAny();
                            boolean nativeProgress = server.pollTask();
                            return RegionScheduler.serviceMainGameplayAfterPoll(
                                    server) || nativeProgress;
                        }));
                if (!progressed) return;
            }
            fail("canonical native drain exceeded the finite fixture's poll bound");
        }

        void oweEntity() throws Exception {
            var method = AsyncIslandPlayerTicks.class.getDeclaredMethod(
                    "markMainFallback", ServerPlayer.class, boolean.class);
            method.setAccessible(true);
            assertEquals(Boolean.TRUE, method.invoke(null, player, true));
        }

        void command(String label) throws Exception {
            Runnable command = AsyncIslandPacketContinuations.enqueueMainCommand(
                    continuationOwner(), label, () -> listener.effects.add(label));
            assertNotNull(command);
            RegionScheduler.scheduleTrackedMainControlTask(server, command);
        }

        void stopPool() {
            pool.stop(() -> {
                pool.drainMainSafeAll(true);
                RegionScheduler.drainTrackedMainControlTasks(pool);
                RegionScheduler.drainMainSyncTicks();
                IslandCommands.drainRoutedBatch();
            });
        }

        void drain() {
            for (TickTask task; (task = tasks.poll()) != null;) task.run();
        }

        @Override
        public void close() throws Exception {
            try {
                IslandCommands.releaseAllExcept();
                if (pool != null) {
                    Throwable existingFailure = pool.fatalFailureOrNull();
                    try {
                        stopPool();
                    } catch (Throwable closeFailure) {
                        assertSame(existingFailure, closeFailure,
                                "cleanup may rethrow only the already-observed generation failure");
                    }
                }
            } finally {
                if (tracker != null) IslandEntityTracking.unregisterPlayerTracker(tracker);
                tasks.clear();
                set(ParallelProcessor.class, null, "server", previousServer);
                set(RegionScheduler.class, null, "server", previousSchedulerServer);
                levels.remove(level, runtime);
                set(RegionScheduler.class, null, "pool", previousPool);
                set(RegionScheduler.class, null, "orphanFatalFailure", previousFailure);
                set(RegionScheduler.class, null, "active", previousActive);
                set(RegionalPlayerEntityTicksV2.class, null, "enabled", previousEnabled);
            }
        }
    }

    private static final class TestLevel extends ServerLevel {
        Runnable entityBody;
        private TestLevel() { super(null, null, null, null, null, null, false, 0, null, false); }
        @Override public net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension() {
            return net.minecraft.world.level.Level.OVERWORLD;
        }
        @Override public void tickNonPassenger(Entity entity) {
            TestPlayer player = (TestPlayer) entity;
            ((TestListener) player.connection).effects.add("E:" + player.label);
            if (entityBody != null) entityBody.run();
        }
    }

    // Substitute Mixin storage and observable vanilla-body effects, not queue/claim decisions.
    private static final class TestPlayer extends ServerPlayer
            implements AsyncIslandPlayerAccess, PlayerTickPhaseStamp, ParallelProcessor.TickGuard {
        AsyncIslandPlayerTicks.PlayerState localState;
        ServerLevel localLevel;
        String label;
        int listenerTicks;
        long lifecycle;
        EntityTickLease lease;
        private TestPlayer() { super(null, null, null, null); }
        @Override public AsyncIslandPlayerTicks.PlayerState fabrolia$asyncIslandPlayerState() { return localState; }
        @Override public ServerLevel level() { return localLevel; }
        @Override public float getHealth() { return 20.0F; }
        @Override public boolean isUsingItem() { return false; }
        @Override public long fabrolia$playerLifecycleEpoch() { return lifecycle; }
        @Override public void fabrolia$bumpPlayerLifecycleEpoch() { lifecycle++; }
        @Override public void checkDespawn() {}
        @Override public EntityTickLease fabrolia$tickLease() { return lease; }
        @Override public byte fabrolia$getSyncCache() { return 0; }
        @Override public void fabrolia$setSyncCache(byte value) {}
    }

    private static final class TestListener extends ServerGamePacketListenerImpl implements AsyncIslandListenerAccess {
        AtomicReference<AsyncIslandPlayerTicks.PlayerState> binding;
        List<String> effects;
        List<Integer> bodyServerTicks;
        TestPlayer replacement;
        RuntimeException itemFailure;
        boolean accepting;
        Runnable tickBody;
        Runnable movementBody;
        private TestListener() { super(null, null, null, null); }
        @Override public void tick() {
            if (tickBody != null) tickBody.run();
            else super.tick();
        }
        @Override public boolean isAcceptingMessages() { return accepting; }
        @Override public AsyncIslandPlayerTicks.PlayerState fabrolia$asyncIslandPlayerState() { return binding.get(); }
        @Override public boolean fabrolia$bindAsyncIslandPlayerState(AsyncIslandPlayerTicks.PlayerState expected) {
            return binding.compareAndSet(null, expected);
        }
        @Override public boolean fabrolia$unbindAsyncIslandPlayerState(AsyncIslandPlayerTicks.PlayerState expected) {
            return binding.compareAndSet(expected, null);
        }
        @Override public void handleChatAck(ServerboundChatAckPacket packet) {
            effects.add("P" + packet.offset());
            if (packet.offset() == 1 && replacement != null) player = replacement;
        }
        @Override public void handleMovePlayer(ServerboundMovePlayerPacket packet) {
            effects.add("move");
            movementBody.run();
        }
        @Override public void onPacketError(Packet packet, Exception failure) {
            assertInstanceOf(IllegalArgumentException.class, failure);
            effects.add("rejected");
        }
        @Override public void handleUseItem(ServerboundUseItemPacket packet) {
            if (!hasClientLoaded()) return;
            effects.add("use:" + packet.getSequence());
            ackBlockChangesUpTo(packet.getSequence());
            if (itemFailure != null) throw itemFailure;
        }
        @Override public void handleUseItemOn(ServerboundUseItemOnPacket packet) {
            if (!hasClientLoaded()) return;
            effects.add("use-on:" + packet.getSequence());
            ackBlockChangesUpTo(packet.getSequence());
        }
        @Override public void handlePlayerAction(ServerboundPlayerActionPacket packet) {
            if (!hasClientLoaded()) return;
            effects.add("action:" + packet.getAction());
            switch (packet.getAction()) {
                case START_DESTROY_BLOCK, ABORT_DESTROY_BLOCK, STOP_DESTROY_BLOCK ->
                        ackBlockChangesUpTo(packet.getSequence());
                default -> {}
            }
        }
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
