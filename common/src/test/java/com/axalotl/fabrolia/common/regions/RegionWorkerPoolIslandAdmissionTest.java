package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementRequirements;
import com.axalotl.fabrolia.common.parallelised.utils.HardColliderIndex;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.Advancement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Real typed publications, claim-holding coordinators, region FIFOs and unit receipts. */
@Isolated
final class RegionWorkerPoolIslandAdmissionTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void overdueMainWaitYieldsBetweenWholeUnitsAndRetainsDueIslands() throws Exception {
        try (Fixture f = new Fixture(2, RegionGrid.DEFAULT_SHIFT - 1)) {
            field(BlockableEventLoop.class, "blockingCount").set(f.server, 1);
            ((TestDedicatedServer) f.server).setNativeTickDeadline(net.minecraft.util.Util.getNanos() - 1L);
            var islands = List.of(f.island(1, 0, 1), f.island(2, 16, 1), f.island(3, 32, 1));
            List<IslandTickUnit.AttemptReceipt> receipts = new ArrayList<>();
            ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
            AtomicInteger enteredUnits = new AtomicInteger();
            AtomicInteger activeUnits = new AtomicInteger();
            f.source.atTail = island -> {
                assertTrue(RegionScheduler.ownsExclusiveGameplay());
                assertEquals(0, activeUnits.decrementAndGet(),
                        "each globally admitted island must finish before the next enters");
            };
            for (int i = 0; i < islands.size(); i++) {
                var island = islands.get(i);
                island.logicalTick = (i + 1) * 10L;
                f.enqueue(island, () -> {
                    assertTrue(RegionScheduler.ownsExclusiveGameplay());
                    assertTrue(island.unitClaim.get());
                    assertEquals(1, activeUnits.incrementAndGet(), "global units cannot overlap");
                    for (var other : islands) {
                        if (other != island) assertFalse(other.unitClaim.get());
                    }
                    events.add("island-" + island.id + "-first");
                    if (enteredUnits.incrementAndGet() == 1) {
                        for (int control = 1; control <= 2; control++) {
                            int sequence = control;
                            RegionScheduler.scheduleMainControlTask(f.server, () -> {
                                assertFalse(RegionScheduler.ownsExclusiveGameplay());
                                events.add("native-" + sequence);
                            });
                        }
                    }
                });
                f.enqueue(island, () -> events.add("island-" + island.id + "-second"));
                receipts.add(IslandTickUnit.submitDeadlineUnit(f.runtime, island, 0L, 0L));
            }
            for (var receipt : receipts) assertFalse(receipt.isDone());
            long mainGameTime = f.level.getGameTime();
            for (int slice = 0; slice < 16
                    && receipts.stream().anyMatch(receipt -> !receipt.isDone()); slice++) {
                int before = enteredUnits.get();
                f.runMainWait(() -> {
                    while (RconCommandFenceScope.callRunAllTasksPoll(f.server, f::pollMain)) {
                        // Native FIFO work must precede optional gameplay service.
                    }
                    assertEquals(before, enteredUnits.get(),
                            "the first native drain must not spend another gameplay slice");
                    events.add("drained-" + before);
                }, () -> f.server.managedBlock(() -> true));
                assertTrue(enteredUnits.get() - before <= 1,
                        "an overdue Main loop must not execute the whole island backlog");
                events.add("main-" + slice);
            }
            assertTrue(receipts.stream().allMatch(IslandTickUnit.AttemptReceipt::isDone),
                    "yielding must retain and eventually finish every already-published due island");
            for (int i = 0; i < islands.size(); i++) {
                f.completed(receipts.get(i), islands.get(i), (i + 1) * 10L + 1L);
                assertEquals(1L, islands.get(i).unitsCompleted);
            }
            assertEquals(List.of("island-1-first", "island-1-second",
                            "island-2-first", "island-2-second", "island-3-first", "island-3-second"),
                    events.stream().filter(event -> event.startsWith("island-")).toList(),
                    "yielding must preserve whole-unit and per-island FIFO order");
            assertEquals(List.of("native-1", "native-2"),
                    events.stream().filter(event -> event.startsWith("native-")).toList(),
                    "native control callbacks must progress in FIFO order between residual grants");
            List<String> eventOrder = List.copyOf(events);
            for (int island = 1; island < islands.size(); island++) {
                assertTrue(eventOrder.subList(eventOrder.indexOf("island-" + island + "-second") + 1,
                                eventOrder.indexOf("island-" + (island + 1) + "-first"))
                        .stream().anyMatch(event -> event.startsWith("main-")),
                        "Main must regain control between these overdue island units");
            }
            assertTrue(eventOrder.indexOf("native-1") < eventOrder.indexOf("island-2-first"));
            assertTrue(eventOrder.indexOf("native-2") < eventOrder.indexOf("island-3-first"));
            assertEquals(0, activeUnits.get());
            assertEquals(mainGameTime, f.level.getGameTime());
            assertEquals(0, ((TestDedicatedServer) f.server).nativeIdleWaits,
                    "yielding a completed grant must not introduce an empty native park");
        }
    }

    @Test
    void emptyColliderMaintenanceDoesNotStopAnEnteredIslandUnit() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (Fixture f = new Fixture(1)) {
                var island = f.registeredIsland();
                Boat boat = f.collider(512);
                HardColliderIndex.onAdd(f.level, boat);
                HardColliderIndex.rebuildCells(f.level);
                HardColliderIndex.onRemove(boat);
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                f.enqueue(island, () -> { entered.countDown(); await(release); });
                var receipt = IslandTickUnit.submitUnit(f.runtime, island);
                await(entered);
                try {
                    HardColliderIndex.rebuildCells(f.level);
                    assertFalse(receipt.isDone(),
                            "empty index maintenance must not wait for unrelated island gameplay");
                } finally {
                    release.countDown();
                }
                f.completed(receipt, island, 1);
                moveCollider(boat, 0);
                HardColliderIndex.onAdd(f.level, boat);
                assertTrue(HardColliderIndex.anyNear(f.level, 0, 0, 0, 1, 1, 1),
                        "an add after empty eviction must be visible before another rebuild");
            }
        });
    }

    @Test
    void colliderSnapshotWaitsForTheEnteredUnitsFinalPosition() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (Fixture f = new Fixture(1)) {
                var island = f.registeredIsland();
                Boat boat = f.collider(512);
                HardColliderIndex.onAdd(f.level, boat);
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                f.enqueue(island, () -> {
                    entered.countDown();
                    await(release);
                    moveCollider(boat, 0);
                });
                var receipt = IslandTickUnit.submitUnit(f.runtime, island);
                await(entered);
                var returned = new CountDownLatch(1);
                AtomicReference<Throwable> observerFailure = new AtomicReference<>();
                Thread observer = new Thread(() -> {
                    try {
                        until(() -> island.claimWanted || returned.getCount() == 0);
                    } catch (Throwable failure) {
                        observerFailure.set(failure);
                    } finally {
                        release.countDown();
                    }
                }, "collider-snapshot-owner-observer");
                observer.start();
                try {
                    HardColliderIndex.rebuildCells(f.level);
                } finally {
                    returned.countDown();
                    release.countDown();
                    observer.join(5_000L);
                    assertFalse(observer.isAlive());
                }
                if (observerFailure.get() != null) throw new AssertionError(observerFailure.get());
                f.completed(receipt, island, 1);
                assertTrue(HardColliderIndex.anyNear(f.level, 0, 0, 0, 1, 1, 1),
                        "snapshot ownership must precede the live position read, not follow it");
            }
        });
    }

    @Test
    void emptyColliderMaintenanceRethrowsTheSelectedWorkerFailure() throws Exception {
        try (Fixture f = new Fixture(1)) {
            var failure = new IllegalStateException("failed before empty collider maintenance");
            assertTrue(f.pool.enqueueUnit(() -> { throw failure; }));
            assertSame(failure, assertThrows(Throwable.class, f.pool::runExclusiveWorkerStep));
            assertSame(failure,
                    assertThrows(Throwable.class, () -> HardColliderIndex.rebuildCells(f.level)));
        }
    }

    @Test
    void liveOffHeadWaitsForTheEnteredUnitsFinalClockBeforePublishingMode() throws Exception {
        boolean previousAsync = FabroliaConfig.asyncIslands;
        try (Fixture f = new Fixture(1)) {
            FabroliaConfig.asyncIslands = true;
            f.save(field(RegionScheduler.class, "mainCurrentLevel"));
            field(RegionizedServer.class, "failClosedMarkerChecked").setBoolean(f.runtime, true);
            field(Level.class, "levelData").set(f.level, java.lang.reflect.Proxy.newProxyInstance(
                    RawGameTime.class.getClassLoader(),
                    new Class<?>[]{net.minecraft.world.level.storage.WritableLevelData.class, RawGameTime.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("fabrolia$rawGameTime")) return f.level.getGameTime();
                        throw new UnsupportedOperationException(method.getName());
                    }));
            f.runtime.islandRegistry.applyRebuildCells(
                    List.of(new it.unimi.dsi.fastutil.longs.LongArrayList(new long[]{0L})), 0L);
            var island = f.runtime.islandRegistry.byCell(0L);
            island.level = f.level;
            island.logicalTick = 37L;
            Region member = f.runtime.regionizer().regionForGridKey(0L);
            member.domain = island.domain;
            member.doneEpoch = island.domain.epoch;
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            f.enqueue(island, () -> {
                entered.countDown();
                await(release);
                assertTrue(f.runtime.asyncActive, "entered work must still see its original mode");
                assertEquals(37L, island.logicalTick, "Main cannot advance the entered unit's clock");
            });
            IslandTickUnit.submitUnit(f.runtime, island);
            await(entered);
            AtomicReference<Throwable> observerFailure = new AtomicReference<>();
            FabroliaConfig.asyncIslands = false;
            Thread observer = new Thread(() -> {
                try {
                    until(() -> island.claimWanted || !f.runtime.asyncActive);
                    assertTrue(f.runtime.asyncActive, "OFF publication must follow the terminal quiesce");
                    assertEquals(37L, island.logicalTick);
                } catch (Throwable failure) {
                    observerFailure.set(failure);
                } finally {
                    release.countDown();
                }
            }, "off-head-entry-observer");
            RuntimeException afterBoundary = new RuntimeException("stop at the first Main clock read");
            Thread main = Thread.currentThread();
            f.level.clockRead = () -> {
                if (Thread.currentThread() != main) return;
                assertEquals(38L, island.logicalTick,
                        "collapse may sample clocks only after the entered unit commits its final clock");
                throw afterBoundary;
            };
            observer.start();
            try {
                assertSame(afterBoundary, assertThrows(Throwable.class, f.runtime::onLevelTickStart));
                assertFalse(f.runtime.mainHoldsAllClaims(), "failed transition must release only its own claims");
                if (observerFailure.get() != null) throw new AssertionError(observerFailure.get());
            } finally {
                release.countDown();
                observer.join(5_000L);
                assertFalse(observer.isAlive());
            }
        } finally {
            FabroliaConfig.asyncIslands = previousAsync;
        }
    }

    @Test
    void topologyWaitExecutesOnlyPublishedMainSafeReceiptsUnderGlobalMainOwnership() throws Exception {
        try (Fixture f = new Fixture(1)) {
            Region region = f.runtime.regionizer().regionForGridKey(0L);
            EpochDomain oldDomain = region.domain;
            Thread main = Thread.currentThread();
            AtomicInteger bodies = new AtomicInteger();
            AtomicInteger nativeCallbacks = new AtomicInteger();
            AtomicInteger otherRoots = new AtomicInteger();
            f.server.schedule(new TickTask(0, nativeCallbacks::incrementAndGet));
            assertTrue(f.pool.enqueueGlobal(otherRoots::incrementAndGet));
            assertTrue(f.pool.enqueueRegionTask(region, () -> {
                assertSame(main, Thread.currentThread());
                assertTrue(RegionScheduler.ownsExclusiveGameplay());
                assertTrue(f.runtime.currentThreadHoldsLocalAllClaims());
                assertSame(oldDomain, region.domain, "the old-domain receipt precedes migration");
                assertEquals(1, region.activeClaimTransactions(), "dequeue must not retire the body receipt");
                bodies.incrementAndGet();
            }));
            assertEquals(1, region.activeClaimTransactions());
            assertTrue(f.runtime.quiesceAllIslands());
            synchronized (region.admissionLock) {
                assertTrue(region.tryCloseClaimTransactionsForDomainRebase());
            }
            CountDownLatch finished = new CountDownLatch(1);
            Thread watchdog = new Thread(() -> {
                try {
                    if (!finished.await(5, TimeUnit.SECONDS)) {
                        RegionScheduler.publishFatalAndSelectWinner(
                                new AssertionError("topology did not execute its queued Main-safe receipt"));
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }, "topology-main-safe-watchdog");
            watchdog.start();
            try {
                RegionizedServer.awaitClaimTransactionsForDomainRebase(region);
                assertEquals(1, bodies.get());
                assertEquals(0, nativeCallbacks.get(), "rebase progress must not pump the native task FIFO");
                assertEquals(0, otherRoots.get(), "rebase progress must not grant unrelated gameplay");
                assertTrue(f.runtime.currentThreadHoldsLocalAllClaims(),
                        "the inner global body borrows rather than releases topology ownership");
                assertEquals(0, region.activeClaimTransactions());
            } finally {
                finished.countDown();
                watchdog.join(5_000L);
                if (region.claimTransactionsDrainedForDomainRebase()) {
                    synchronized (region.admissionLock) {
                        region.reopenClaimTransactionsAfterDomainRebase();
                    }
                }
                Throwable failure = f.runtime.releaseAllIslands(f.pool.fatalFailureOrNull());
                if (failure != null && f.pool.fatalFailureOrNull() == null) throw new AssertionError(failure);
            }
        }
    }

    @Test
    void distinctIslandUnitsOverlapWithoutAnyMainGameplayGrant() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (Fixture f = new Fixture(2)) {
                IslandRegistry.PIsland a = f.island(1, 0, 1);
                IslandRegistry.PIsland b = f.island(2, 16, 1);
                CountDownLatch entered = new CountDownLatch(2);
                CountDownLatch release = new CountDownLatch(1);
                var holders = new ConcurrentLinkedQueue<Thread>();
                for (var island : List.of(a, b)) {
                    f.enqueue(island, () -> {
                        f.assertLocal(island);
                        holders.add(Thread.currentThread());
                        entered.countDown();
                        await(release);
                    });
                }
                var first = IslandTickUnit.submitUnit(f.runtime, a);
                var second = IslandTickUnit.submitUnit(f.runtime, b);
                try {
                    await(entered);
                    assertEquals(2, holders.stream().distinct().count());
                    assertFalse(RegionScheduler.ownsExclusiveGameplay());
                    assertTrue(a.unitClaim.get());
                    assertTrue(b.unitClaim.get());
                    assertFalse(first.isDone());
                    assertFalse(second.isDone());
                } finally {
                    release.countDown();
                }
                f.completed(first, a, 1);
                f.completed(second, b, 1);
            }
        });
    }

    @Test
    void unusedNbtCriterionDoesNotStopLocalIslandsWhileMainIsBusy() throws Exception {
        try (Fixture f = new Fixture(2)) {
            var advancement = Advancement.CODEC.parse(
                    RegistryOps.create(JsonOps.INSTANCE, RegistryAccess.EMPTY),
                    JsonParser.parseString("""
                            {"criteria":{"unrelated_consumption":{"trigger":"minecraft:consume_item","conditions":{
                              "player":[{"condition":"minecraft:entity_properties","entity":"this",
                                "predicate":{"minecraft:nbt":"{EnderPearls:[]}"}}]}}}}
                            """)).getOrThrow();
            f.advancements.requirements = AdvancementRequirements.prepare(
                    List.of(advancement), RegistryAccess.EMPTY);
            assertTrue(f.advancements.requirements.global());
            var islands = List.of(f.island(1, 0, 1), f.island(2, 16, 1));
            var completed = new CountDownLatch(islands.size());
            var callbacks = new AtomicInteger();
            List<IslandTickUnit.AttemptReceipt> receipts = new ArrayList<>();
            f.source.atTail = island -> completed.countDown();
            for (var island : islands) {
                f.enqueue(island, callbacks::incrementAndGet);
                receipts.add(IslandTickUnit.submitUnit(f.runtime, island));
            }
            // No consumption, NBT capture or foreign access occurs in either
            // island. Physical Main deliberately does not pump gameplay.
            assertTrue(completed.await(3, TimeUnit.SECONDS),
                    "an unused criterion must not make independent local ticks wait for Main");
            for (int i = 0; i < islands.size(); i++) {
                f.completed(receipts.get(i), islands.get(i), 1);
            }
            assertEquals(2, callbacks.get(), "both accepted local bodies must run exactly once");
        }
    }

    @Test
    void nativeControlDoesNotCloseUnrelatedIslandAdmission() throws Exception {
        try (Fixture f = new Fixture(1)) {
            var island = f.registeredIsland();
            var callbacks = new AtomicInteger();
            var receipt = new AtomicReference<IslandTickUnit.AttemptReceipt>();
            RegionScheduler.scheduleTrackedMainControlTask(f.server, () -> {
                assertFalse(RegionScheduler.ownsExclusiveGameplay());
                f.enqueue(island, callbacks::incrementAndGet);
                receipt.set(IslandTickUnit.submitUnit(f.runtime, island));
                until(() -> receipt.get().isDone());
            });
            RegionScheduler.servicePublishedMainControls(f.server);
            assertNotNull(receipt.get());
            var outcome = receipt.get().outcome();
            if (outcome == IslandTickUnit.AttemptOutcome.NO_BODY) {
                // Drain retained work even on the broken admission path.
                f.completed(IslandTickUnit.submitUnit(f.runtime, island), island, 1);
            }
            assertEquals(IslandTickUnit.AttemptOutcome.ADVANCED, outcome,
                    "control scheduling must not close unrelated island admission");
            f.completed(receipt.get(), island, 1);
            assertEquals(1, callbacks.get(),
                    "Main control without island access must not refuse independent local work");
        }
    }

    @Test
    void nativeControlWriterStillWaitsForConflictingIslandWork() throws Exception {
        try (Fixture f = new Fixture(1)) {
            var island = f.registeredIsland();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var writerRan = new AtomicInteger();
            f.enqueue(island, () -> { entered.countDown(); await(release); });
            var first = IslandTickUnit.submitUnit(f.runtime, island);
            await(entered);
            AtomicReference<Throwable> observerFailure = new AtomicReference<>();
            Thread observer = new Thread(() -> {
                try {
                    until(() -> island.claimWanted);
                    assertEquals(0, writerRan.get(), "writer cannot overlap the entered island body");
                } catch (Throwable failure) {
                    observerFailure.set(failure);
                } finally {
                    release.countDown();
                }
            }, "native-control-writer-observer");
            observer.start();
            try {
                RegionScheduler.scheduleTrackedMainControlTask(f.server, () ->
                        RegionScheduler.runGlobalMainBody(f.server, () -> {
                            assertTrue(RegionScheduler.ownsExclusiveGameplay());
                            assertEquals(1, island.logicalTick);
                            var successor = IslandTickUnit.submitUnit(f.runtime, island);
                            until(successor::isDone);
                            successor.throwIfFailed();
                            assertEquals(IslandTickUnit.AttemptOutcome.NO_BODY, successor.outcome(),
                                    "the real writer still excludes conflicting successor work");
                            writerRan.incrementAndGet();
                        }));
                RegionScheduler.servicePublishedMainControls(f.server);
            } finally {
                release.countDown();
                observer.join(5_000L);
            }
            assertFalse(observer.isAlive());
            if (observerFailure.get() != null) throw new AssertionError(observerFailure.get());
            until(first::isDone);
            first.throwIfFailed();
            assertEquals(1, writerRan.get());
            f.completed(IslandTickUnit.submitUnit(f.runtime, island), island, 2);
        }
    }

    @Test
    void acceptedOwnedEntityFrontierRetiresLastNotificationWithoutQuiescingOtherIsland() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (Fixture f = new Fixture(3)) {
                var a = f.island(1, 0, 2);
                var b = f.island(2, 16, 1);
                CountDownLatch entered = new CountDownLatch(2);
                CountDownLatch release = new CountDownLatch(1);
                AtomicInteger callbacks = new AtomicInteger();
                f.enqueue(a, () -> { entered.countDown(); await(release); });
                Region owing = f.runtime.regionizer().peekByGridKey(1L);
                assertTrue(RegionScheduler.enqueueRegionTask(owing, callbacks::incrementAndGet));
                f.enqueue(b, () -> { entered.countDown(); await(release); });
                var first = IslandTickUnit.submitUnit(f.runtime, a);
                var second = IslandTickUnit.submitUnit(f.runtime, b);
                try {
                    await(entered);
                    until(() -> owing.readyNotifications == 0);
                    assertFalse(owing.queue().isEmpty(),
                            "notification retirement must not steal the coordinator's source FIFO");
                    assertFalse(first.isDone());
                    assertFalse(second.isDone(), "the unrelated coordinator remains entered");
                    assertFalse(f.pool.runExclusiveWorkerStep(),
                            "an owned entity frontier must not create a global quiescence turn");
                    assertEquals(0, callbacks.get());
                } finally {
                    release.countDown();
                }
                f.completed(first, a, 1);
                f.completed(second, b, 1);
                assertEquals(1, callbacks.get());
            }
        });
    }

    @Test
    void globalEntryDrainsEnteredUnitsAndClosesAdmissionForTheirSuccessors() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (Fixture f = new Fixture(2)) {
                var a = f.island(1, 0, 1);
                var b = f.island(2, 16, 1);
                CountDownLatch entered = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                AtomicInteger nextBodies = new AtomicInteger();
                AtomicReference<IslandTickUnit.AttemptReceipt> next = new AtomicReference<>();
                f.enqueue(a, () -> { entered.countDown(); await(release); });
                var first = IslandTickUnit.submitUnit(f.runtime, a);
                await(entered);
                AtomicInteger globals = new AtomicInteger();
                assertTrue(f.pool.enqueueGlobal(() -> {
                    assertTrue(first.isDone(), "global entry waits for the complete admitted unit, not its parked body");
                    assertFalse(a.unitClaim.get());
                    assertEquals(0, nextBodies.get(), "a native-service publication cannot enter through the closed local gate");
                    assertTrue(f.pool.ownsExclusiveGameplay(Thread.currentThread()));
                    globals.incrementAndGet();
                }));
                // This exact control callback is serviced only while Main waits
                // for the entered unit. It publishes after the global cut exists.
                assertTrue(f.pool.enqueueGlobalMainOnly(() -> {
                    assertFalse(first.isDone());
                    assertFalse(RegionScheduler.ownsExclusiveGameplay());
                    f.enqueue(b, nextBodies::incrementAndGet);
                    next.set(IslandTickUnit.submitUnit(f.runtime, b));
                    release.countDown();
                }));
                try {
                    assertTrue(f.pool.runExclusiveWorkerStep());
                    assertEquals(1, globals.get());
                } finally {
                    release.countDown();
                }
                f.completed(first, a, 1);
                assertNotNull(next.get());
                f.completed(next.get(), b, 1);
                assertEquals(1, nextBodies.get());
            }
        });
    }

    @Test
    void queuedUnitYieldsToRetainedMainInterestWithoutConsumingItsWork() {
        queuedUnitYieldsToMainAdmission(false, false);
    }

    @Test
    void queuedUnitYieldsToPublishedGlobalIntentWithoutConsumingItsWork() {
        queuedUnitYieldsToMainAdmission(true, false);
    }

    @Test
    void mainInterestPublishedAcrossTheClaimCasStillRefusesTheQueuedBody() {
        queuedUnitYieldsToMainAdmission(false, true);
    }

    private static void queuedUnitYieldsToMainAdmission(boolean global, boolean acrossClaim) {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (Fixture f = new Fixture(1)) {
                var enteredIsland = f.island(1, 0, 1);
                var queuedIsland = f.island(2, 16, 1);
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var bodies = new AtomicInteger();
                var coordinator = new AtomicReference<Thread>();
                f.enqueue(enteredIsland, () -> {
                    coordinator.set(Thread.currentThread());
                    entered.countDown();
                    await(release);
                });
                var first = IslandTickUnit.submitUnit(f.runtime, enteredIsland);
                await(entered);
                f.enqueue(queuedIsland, bodies::incrementAndGet);
                var queued = IslandTickUnit.submitUnit(f.runtime, queuedIsland);
                assertFalse(queued.isDone());
                var interest = new RegionizedServer.AllIslandClaimIntents(1);
                Object frame = new Object();
                try {
                    Runnable publish = () -> {
                        if (global) f.runtime.installGlobalFenceIntent(frame);
                        else interest.include(List.of(queuedIsland));
                    };
                    if (acrossClaim) {
                        // The existing admission monitor stops the real claim
                        // after its precheck; no production scheduling hook.
                        var threads = java.lang.management.ManagementFactory.getThreadMXBean();
                        synchronized (queuedIsland.unitAdmissionLock) {
                            release.countDown();
                            until(() -> {
                                var info = threads.getThreadInfo(coordinator.get().threadId());
                                return info != null && info.getLockInfo() != null
                                        && info.getThreadState() == Thread.State.BLOCKED
                                        && info.getLockInfo().getIdentityHashCode()
                                            == System.identityHashCode(queuedIsland.unitAdmissionLock);
                            });
                            publish.run();
                        }
                    } else {
                        publish.run();
                        release.countDown();
                    }
                    until(queued::isDone);
                    queued.throwIfFailed();
                    assertEquals(IslandTickUnit.AttemptOutcome.NO_BODY, queued.outcome());
                    assertEquals(0, bodies.get(), "queued work must not enter through Main's admission cut");
                    assertEquals(0, queuedIsland.logicalTick, "refusal cannot consume a local tick");
                    assertFalse(queuedIsland.unitClaim.get());
                    assertFalse(queuedIsland.submitQueued.get());
                } finally {
                    release.countDown();
                    if (global) f.runtime.clearGlobalFenceIntent(frame);
                    assertNull(RegionizedServer.clearAllIslandClaimIntents(interest, null));
                }
                f.completed(first, enteredIsland, 1);
                f.completed(IslandTickUnit.submitUnit(f.runtime, queuedIsland), queuedIsland, 1);
                assertEquals(1, bodies.get(), "the original region FIFO must survive admission refusal");
            }
        });
    }

    @Test
    void finiteNativeControlPrefixRetainsTargetClaimsWithoutBlockingUnrelatedUnits() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (Fixture f = new Fixture(1)) {
                f.runtime.islandRegistry.applyRebuildCells(List.of(
                        new it.unimi.dsi.fastutil.longs.LongArrayList(new long[]{0}),
                        new it.unimi.dsi.fastutil.longs.LongArrayList(new long[]{16})), 0);
                var enteredIsland = f.runtime.islandRegistry.byCell(0);
                var queuedIsland = f.runtime.islandRegistry.byCell(16);
                for (var island : List.of(enteredIsland, queuedIsland)) {
                    island.level = f.level;
                    island.domain.epoch = 8;
                    Region region = f.runtime.regionizer().regionForGridKey(
                            island.cells.iterator().nextLong());
                    region.domain = island.domain;
                    region.doneEpoch = island.domain.epoch;
                    f.regions.add(region);
                }
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var bodies = new AtomicInteger();
                var controls = new AtomicInteger();
                f.enqueue(enteredIsland, () -> {
                    f.assertLocal(enteredIsland);
                    entered.countDown();
                    await(release);
                });
                var first = IslandTickUnit.submitUnit(f.runtime, enteredIsland);
                await(entered);
                f.enqueue(queuedIsland, bodies::incrementAndGet);
                try {
                    RegionScheduler.scheduleTrackedMainControlTask(f.server, () -> {
                        release.countDown();
                        f.completed(first, enteredIsland, 1);
                        boolean took = IslandCommands.mainClaimScoped(enteredIsland);
                        try {
                            assertTrue(RegionScheduler.ownsCurrentContext(f.level, BlockPos.ZERO));
                            assertFalse(RegionScheduler.ownsExclusiveGameplay(),
                                    "admission interest and a target claim are not global authority");
                        } finally {
                            if (took) IslandCommands.mainRelease(enteredIsland);
                        }
                        controls.incrementAndGet();
                    });
                    RegionScheduler.scheduleTrackedMainControlTask(f.server, () -> {
                        var queued = IslandTickUnit.submitUnit(f.runtime, queuedIsland);
                        until(queued::isDone);
                        queued.throwIfFailed();
                        assertEquals(IslandTickUnit.AttemptOutcome.ADVANCED, queued.outcome(),
                                "an unrelated island remains runnable during native control service");
                        assertEquals(1, bodies.get());
                        controls.incrementAndGet();
                    });
                    assertFalse(f.server.pollTask(), "the controls are still young without spare time");
                    RegionScheduler.servicePublishedMainControls(f.server);
                } finally {
                    release.countDown();
                }
                assertEquals(2, controls.get());
                assertEquals(1, queuedIsland.logicalTick);
                assertEquals(1, bodies.get(), "the accepted local callback runs exactly once");
            }
        });
    }

    @Test
    void fatalCancellationClosesUnstartedTypedReceiptWithTheOriginalFailure() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (Fixture f = new Fixture(1)) {
                var a = f.island(1, 0, 1);
                var b = f.island(2, 16, 1);
                CountDownLatch entered = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                IllegalStateException original = new IllegalStateException("island body W");
                AtomicInteger unstarted = new AtomicInteger();
                f.enqueue(a, () -> { entered.countDown(); await(release); throw original; });
                var first = IslandTickUnit.submitUnit(f.runtime, a);
                try {
                    await(entered);
                    f.enqueue(b, unstarted::incrementAndGet);
                    var queued = IslandTickUnit.submitUnit(f.runtime, b);
                    assertFalse(queued.isDone());
                    release.countDown();
                    until(() -> f.pool.fatalFailureOrNull() != null);
                    assertSame(original, assertThrows(Throwable.class, f.pool::stop));
                    assertTrue(first.isDone());
                    assertTrue(queued.isDone());
                    assertSame(original, first.failureOrNull());
                    assertSame(original, queued.failureOrNull());
                    assertSame(original, assertThrows(Throwable.class, queued::throwIfFailed));
                    assertSame(original, assertThrows(Throwable.class, f.pool::stop));
                    assertEquals(0, unstarted.get());
                    assertEquals(0, a.logicalTick);
                    assertEquals(0, b.logicalTick);
                    assertFalse(a.unitClaim.get());
                    assertFalse(b.submitQueued.get());
                } finally {
                    release.countDown();
                }
            }
        });
    }

    @Test
    void continuousLargeUnitsRetainEveryCallbackWithoutAccumulatingMetadata() {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            try (Fixture f = new Fixture(1)) {
                int members = 130;
                int rounds = 12;
                var island = f.island(1, 0, members);
                var sprint = new TemporalControl.Session("metadata admission regression");
                sprint.cells.addAll(island.cells);
                sprint.sprintRemaining.set(rounds);
                f.runtime.temporalSessions.add(sprint);
                AtomicInteger callbacks = new AtomicInteger();
                AtomicInteger tails = new AtomicInteger();
                List<Integer> metadataPerUnit = new ArrayList<>();
                // The fixture has no changed chunk holders. Observe the real
                // unit's post-entity/pre-completion boundary, then publish next
                // unit callbacks while the current claim still exists.
                f.source.atTail = current -> {
                    int round = tails.incrementAndGet();
                    assertEquals(members * round, callbacks.get());
                    metadataPerUnit.add(f.notificationCount());
                    if (round < rounds) f.enqueueEveryMember(current, callbacks::incrementAndGet);
                };
                f.enqueueEveryMember(island, callbacks::incrementAndGet);
                var receipt = IslandTickUnit.submitUnit(f.runtime, island);
                until(() -> island.unitsCompleted == rounds && !island.unitClaim.get()
                        && !island.submitQueued.get() || f.pool.fatalFailureOrNull() != null);
                f.pool.throwIfFailed();
                f.completed(receipt, island, rounds);
                assertEquals(members * rounds, callbacks.get());
                assertEquals(rounds, tails.get());
                int warmupBound = metadataPerUnit.subList(0, 3).stream()
                        .mapToInt(Integer::intValue).max().orElseThrow();
                assertTrue(metadataPerUnit.subList(3, rounds).stream()
                                .allMatch(backlog -> backlog <= warmupBound),
                        "repeating the same unit workload must not grow its notification backlog: "
                                + metadataPerUnit);
                until(() -> f.notificationCount() == 0);
                assertFalse(f.pool.ownsExclusiveGameplay(Thread.currentThread()));
            }
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(3, TimeUnit.SECONDS), "admitted body did not reach its synchronization boundary");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void until(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "production admission failed to make progress");
            java.util.concurrent.locks.LockSupport.parkNanos(100_000L);
        }
    }

    private static void moveCollider(Entity collider, double x) {
        try {
            field(Entity.class, "position").set(collider, new Vec3(x, 0, 0));
            field(Entity.class, "bb").set(collider, new AABB(x, 0, 0, x + 1, 1, 1));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Map<Field, Object> previous = new LinkedHashMap<>();
        final Map<ServerLevel, RegionizedServer> levels;
        final List<Region> regions = new ArrayList<>();
        final MinecraftServer server;
        final PreparedAdvancements advancements;
        final TestLevel level;
        final EmptyChunkSource source;
        final RegionizedServer runtime;
        final RegionWorkerPool pool;
        final boolean oldEventScheduler = FabroliaConfig.regionEventScheduler;
        final boolean oldThreading = FabroliaConfig.enableRegionThreading;

        Fixture(int workers) throws Exception {
            this(workers, RegionGrid.DEFAULT_SHIFT);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        Fixture(int workers, int gridShift) throws Exception {
            var unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
            server = (MinecraftServer) unsafe.allocateInstance(TestDedicatedServer.class);
            field(MinecraftServer.class, "serverThread").set(server, Thread.currentThread());
            field(BlockableEventLoop.class, "pendingRunnables").set(server, new ConcurrentLinkedQueue<TickTask>());
            field(MinecraftServer.class, "tickRateManager").set(server, new ServerTickRateManager(server));
            // Use the production immutable preparation result for an actual
            // empty advancement resource set; an unknown provider fails closed.
            advancements = (PreparedAdvancements) unsafe.allocateInstance(PreparedAdvancements.class);
            advancements.requirements = AdvancementRequirements.prepare(List.of(), RegistryAccess.EMPTY);
            var resources = (ReloadableServerResources) unsafe.allocateInstance(ReloadableServerResources.class);
            field(ReloadableServerResources.class, "advancements").set(resources, advancements);
            var resourceField = field(MinecraftServer.class, "resources");
            var constructor = resourceField.getType().getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            resourceField.set(server, constructor.newInstance(null, resources));
            level = (TestLevel) unsafe.allocateInstance(TestLevel.class);
            level.rate = new ServerTickRateManager(server);
            field(ServerLevel.class, "server").set(level, server);
            source = (EmptyChunkSource) unsafe.allocateInstance(EmptyChunkSource.class);
            field(ServerChunkCache.class, "chunkMap").set(source, unsafe.allocateInstance(ChunkMap.class));
            level.source = source;
            // There is no native polling world in this fixture. The tests own
            // only empty native containers, never a loaded/disposable world.
            field(MinecraftServer.class, "levels").set(server, Map.of());
            runtime = new RegionizedServer(level, new RegionGrid(gridShift));
            runtime.asyncActive = true;
            pool = new RegionWorkerPool(workers, server);
            levels = (Map<ServerLevel, RegionizedServer>) field(RegionScheduler.class, "PER_LEVEL").get(null);
            for (String name : new String[]{"pool", "transitionPool", "server", "grid",
                    "active", "accepting", "lifecycleState", "orphanFatalFailure"}) {
                save(field(RegionScheduler.class, name));
            }
            save(field(ParallelProcessor.class, "server"));
            save(field(RegionTickHooks.class, "hook"));
            field(RegionScheduler.class, "pool").set(null, pool);
            field(RegionScheduler.class, "transitionPool").set(null, null);
            field(RegionScheduler.class, "server").set(null, server);
            field(RegionScheduler.class, "grid").set(null, runtime.regionizer().grid);
            field(RegionScheduler.class, "active").set(null, true);
            field(RegionScheduler.class, "accepting").set(null, true);
            field(RegionScheduler.class, "orphanFatalFailure").set(null, null);
            Field lifecycle = field(RegionScheduler.class, "lifecycleState");
            lifecycle.set(null, Enum.valueOf((Class) lifecycle.getType(), "RUNNING"));
            field(ParallelProcessor.class, "server").set(null, server);
            field(RegionTickHooks.class, "hook").set(null, null);
            FabroliaConfig.regionEventScheduler = true;
            FabroliaConfig.enableRegionThreading = true;
            levels.put(level, runtime);
            pool.start();
        }

        boolean pollMain() {
            // JUnit has no woven poll wrapper/RETURN hook; pair the literal
            // native poll with the same production residual policy.
            return RconCommandFenceScope.callMainPoll(server, () -> {
                RegionScheduler.throwWorkerFailureIfAny();
                boolean nativeProgress = server.pollTask();
                return RegionScheduler.serviceMainGameplayAfterPoll(server)
                        || nativeProgress;
            });
        }

        void runMainWait(Runnable runAllTasks, Runnable managedBlock) {
            RconCommandFenceScope.runServerLoopRoot(server, () ->
                    RconCommandFenceScope.runServerEntry(server, () ->
                            RconCommandFenceScope.runServerWaitUntilNextTick(server, () ->
                                    RconCommandFenceScope.runWaitUntilNextTickEntry(server, () -> {
                                        RconCommandFenceScope.runWaitUntilNextTickRunAllTasks(server, runAllTasks);
                                        RconCommandFenceScope.runWaitUntilNextTickManagedBlock(server, managedBlock);
                                    }))));
        }

        private void save(Field field) throws Exception { previous.put(field, field.get(null)); }

        IslandRegistry.PIsland island(long id, int firstCell, int count) {
            var island = new IslandRegistry.PIsland(id, 0);
            island.level = level;
            island.domain.epoch = 8;
            for (int i = 0; i < count; i++) {
                long key = firstCell + i;
                Region region = runtime.regionizer().regionForGridKey(key);
                region.domain = island.domain;
                region.doneEpoch = island.domain.epoch;
                island.cells.add(key);
                regions.add(region);
            }
            return island;
        }

        IslandRegistry.PIsland registeredIsland() {
            runtime.islandRegistry.applyRebuildCells(
                    List.of(new it.unimi.dsi.fastutil.longs.LongArrayList(new long[]{0L})), 0L);
            var island = runtime.islandRegistry.byCell(0L);
            island.level = level;
            island.domain.epoch = 8;
            Region region = runtime.regionizer().regionForGridKey(0L);
            region.domain = island.domain;
            region.doneEpoch = island.domain.epoch;
            regions.add(region);
            return island;
        }

        Boat collider(double x) throws Exception {
            var unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
            Boat boat = (Boat) unsafe.allocateInstance(Boat.class);
            boat.setId(1);
            field(Entity.class, "level").set(boat, level);
            moveCollider(boat, x);
            return boat;
        }

        void assertLocal(IslandRegistry.PIsland island) {
            assertSame(Thread.currentThread(), island.claimHolder);
            assertTrue(island.unitClaim.get());
            assertFalse(RegionScheduler.ownsExclusiveGameplay());
            assertSame(island.domain, ((RegionWorkerThread) Thread.currentThread()).currentDomain);
            Region region = ((RegionWorkerThread) Thread.currentThread()).currentRegion;
            assertNotNull(region);
            int cellX = (int) region.gridKey;
            int cellZ = (int) (region.gridKey >> 32);
            assertTrue(RegionScheduler.ownsCurrentContext(level, new BlockPos(cellX << 7, 0, cellZ << 7)));
        }

        void enqueue(IslandRegistry.PIsland island, Runnable body) {
            long key = island.cells.iterator().nextLong();
            assertTrue(RegionScheduler.enqueueRegionTask(runtime.regionizer().peekByGridKey(key), body));
        }

        void enqueueEveryMember(IslandRegistry.PIsland island, Runnable body) {
            for (long key : island.cells) {
                assertTrue(RegionScheduler.enqueueRegionTask(runtime.regionizer().peekByGridKey(key), body));
            }
        }

        int notificationCount() {
            int count = 0;
            for (Region region : regions) count += region.readyNotifications;
            return count;
        }

        void completed(IslandTickUnit.AttemptReceipt receipt, IslandRegistry.PIsland island, long ticks) {
            until(receipt::isDone);
            receipt.throwIfFailed();
            assertEquals(IslandTickUnit.AttemptOutcome.ADVANCED, receipt.outcome());
            assertEquals(ticks, island.logicalTick);
            assertFalse(island.unitClaim.get());
        }

        @Override
        public void close() throws Exception {
            try {
                if (!pool.isTerminated()) {
                    Throwable failure = pool.fatalFailureOrNull();
                    if (failure == null) pool.stop(pool::runExclusiveWorkerStep);
                    else assertSame(failure, assertThrows(Throwable.class, pool::stop));
                }
            } finally {
                HardColliderIndex.onLevelUnload(level);
                levels.remove(level, runtime);
                for (var saved : previous.entrySet()) saved.getKey().set(null, saved.getValue());
                FabroliaConfig.regionEventScheduler = oldEventScheduler;
                FabroliaConfig.enableRegionThreading = oldThreading;
            }
        }
    }

    private static final class PreparedAdvancements extends ServerAdvancementManager
            implements AdvancementRequirements.Provider {
        AdvancementRequirements requirements;
        private PreparedAdvancements() { super(null); }
        @Override public AdvancementRequirements fabrolia$advancementRequirements() { return requirements; }
    }

    /** No holders exist here; fail rather than pretend to broadcast a real update. */
    private static final class EmptyChunkSource extends ServerChunkCache implements IslandBlockUpdateAccess {
        Consumer<IslandRegistry.PIsland> atTail;
        private EmptyChunkSource() { super(null, null, null, null, null, null, 0, 0, false, null, null); }
        @Override public void fabrolia$flushIslandBlockUpdates(RegionizedServer runtime, IslandRegistry.PIsland island) {
            assertTrue(island.pendingBlockUpdates.isEmpty(), "native holder broadcasts require the woven runtime fixture");
            if (atTail != null) atTail.accept(island);
        }
    }

    private static final class TestLevel extends ServerLevel {
        ServerTickRateManager rate;
        EmptyChunkSource source;
        Runnable clockRead;
        private TestLevel() { super(null, null, null, null, null, null, false, 0L, null, false); }
        @Override public ServerTickRateManager tickRateManager() { return rate; }
        @Override public ServerChunkCache getChunkSource() { return source; }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
        @Override public long getGameTime() {
            if (clockRead != null) clockRead.run();
            return 1_000L;
        }
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
