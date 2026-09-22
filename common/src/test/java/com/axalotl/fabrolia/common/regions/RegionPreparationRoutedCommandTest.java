package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementBuffer;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.ExecutionCommandSource;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;

import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.commands.TagCommand;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.BaseCommandBlock;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real pool step -> routed command -> Main receipt, crossed by preparation admission. */
@Isolated
class RegionPreparationRoutedCommandTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void commandPublishedAfterPauseRequestPreservesFalseAndNextUnitFifo() throws Exception {
        exercise(false, false, null);
    }

    @Test
    void scoreboardCommandQueuedBeforePauseAdmissionPreservesTrueAndNextUnitFifo() throws Exception {
        exercise(true, true, null);
    }

    @Test
    void commandFailureAfterPauseRequestEscapesUnchangedWithoutPreparation() throws Exception {
        exercise(false, false, new IllegalArgumentException("original routed command failure"));
    }

    @Test
    void simultaneousPlayerLateWaitersContainTrackingWithExactTurnsBeforeResuming() throws Exception {
        exercisePlayerLate(false);
    }

    @Test
    void fatalDuringMainPlayerLateRetainsBothClaimsAndCancelsTheUnenteredBody() throws Exception {
        exercisePlayerLate(true);
    }

    @Test
    void entityMainRendezvousRetainsMemberAndOrdinalBeforeTheUnitTail() throws Exception {
        exerciseEntityMain(false, false, false);
    }

    @Test
    void entityMainFailureEscapesUnchangedWithoutAdvancingTheNextOrdinal() throws Exception {
        exerciseEntityMain(true, false, false);
    }

    @Test
    void topologyQuiescenceKeepsAdmissionClosedWhileAnEnteredEntityFinishesOnMain() throws Exception {
        exerciseEntityMain(false, true, false);
    }

    @Test
    void contendedTargetClaimKeepsSuccessorInterestBetweenReleasedRoutedFrames() throws Exception {
        exerciseEntityMain(false, false, true);
    }

    @Test
    void contendedTargetClaimFailureRestoresInheritedInterestsWithoutClaimingTheTarget() throws Exception {
        exerciseEntityMain(true, false, true);
    }

    private static void exerciseEntityMain(
            boolean failDuringBody, boolean quiesce, boolean targetClaim) throws Exception {
        try (Fixture f = new Fixture()) {
            if (quiesce) {
                set(RegionizedServer.class, f.runtime, "quiescedIslands", new java.util.ArrayList<>());
                set(net.minecraft.world.level.Level.class, f.level, "dimension",
                        net.minecraft.world.level.Level.OVERWORLD);
            }
            f.runtime.islandRegistry.applyRebuildCells(List.of(
                    new LongArrayList(new long[]{0L}),
                    new LongArrayList(new long[]{100L}),
                    new LongArrayList(new long[]{200L})), 0L);
            IslandRegistry.PIsland other = f.runtime.islandRegistry.byCell(100L);
            IslandRegistry.PIsland inherited = f.runtime.islandRegistry.byCell(200L);
            inherited.claimWanted = true;
            Region member = f.runtime.regionizer().regionForGridKey(0L);
            member.domain = f.island.domain;
            member.domain.epoch = 1L;
            member.groupedEpoch = 1L;
            member.domain.unitGameTime = 47L;
            var effects = new CopyOnWriteArrayList<String>();
            var fatal = new IllegalArgumentException("native entity body failure");
            var failure = new AtomicReference<Throwable>();
            var done = new CountDownLatch(1);
            f.pool.start();
            try {
                assertTrue(f.pool.enqueueUnit(() -> {
                    RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                    boolean claimed = false;
                    try {
                        assertTrue(f.island.tryClaimAutonomousWork());
                        f.island.noteClaim("entity-main-ordinal");
                        worker.currentDomain = member.domain;
                        assertTrue(member.tryClaim(worker, member.domain, 1L));
                        claimed = true;
                        worker.currentRegion = member;
                        effects.add("before");
                        IslandCommands.runEntityTickOnMain(f.runtime, member, () -> {
                            assertSame(f.main, Thread.currentThread());
                            assertSame(worker, member.currentWorker());
                            assertSame(member, worker.currentRegion);
                            assertTrue(f.island.unitClaim.get());
                            assertSame(f.island, IslandCommands.routedUnitSourceOnMain(f.runtime));
                            assertEquals(47L, IslandClock.contextTime());
                            assertEquals(f.island.id, IslandClock.contextId());
                            if (quiesce) {
                                assertTrue(f.island.claimWanted,
                                        "routed progress must retain the outer no-successor admission cut");
                            }
                            effects.add("entity");
                            if (failDuringBody && !targetClaim) throw fatal;
                        });
                        assertNull(f.runtime.globalFenceIntentReceipt());
                        assertSame(worker, member.currentWorker());
                        if (targetClaim) {
                            // This is the released-frame gap on the original
                            // source stack, not a check inside global HELD.
                            assertTrue(other.claimWanted,
                                    "new pacing/chaining must yield until the contended target is acquired");
                            assertFalse(f.runtime.mainHoldsAllClaims());
                            assertFalse(other.unitClaim.get());
                            assertFalse(IslandCommands.mainHolds(other));
                            IslandCommands.runEntityTickOnMain(f.runtime, member, () -> {
                                assertSame(f.main, Thread.currentThread());
                                assertSame(worker, member.currentWorker());
                                assertEquals(47L, IslandClock.contextTime());
                                effects.add("second-entity");
                                if (failDuringBody) throw fatal;
                            });
                            assertNull(f.runtime.globalFenceIntentReceipt());
                            assertTrue(other.claimWanted,
                                    "another released frame must not erase the outer waiter interest");
                        }
                        effects.add("next-entity");
                        effects.add("unit-tail");
                    } catch (Throwable problem) {
                        failure.set(RegionScheduler.publishFatalAndSelectWinner(problem));
                    } finally {
                        worker.currentRegion = null;
                        worker.currentDomain = null;
                        if (claimed) {
                            member.release(worker);
                            member.finishClaimTransaction();
                        }
                        f.island.clearClaim();
                        f.island.unitClaim.set(false);
                        done.countDown();
                    }
                }));
                await(f.published, "entity ordinal did not publish its Main receipt");
                assertEquals(List.of("before"), effects);
                if (targetClaim) {
                    if (failDuringBody) {
                        assertSame(fatal, assertThrows(IllegalArgumentException.class,
                                () -> IslandCommands.mainClaim(f.island)));
                    } else {
                        IslandCommands.mainClaim(f.island);
                        try {
                            assertTrue(IslandCommands.mainHolds(f.island));
                            assertFalse(IslandCommands.mainHolds(other));
                            assertFalse(other.claimWanted,
                                    "the exact target body must not retain global successor interest");
                        } finally {
                            IslandCommands.mainRelease(f.island);
                        }
                    }
                } else if (failDuringBody) {
                    assertSame(fatal, assertThrows(IllegalArgumentException.class, IslandCommands::drainRoutedBatch));
                } else if (quiesce) {
                    try {
                        assertTrue(f.runtime.quiesceAllIslands());
                        assertTrue(f.runtime.currentThreadHoldsLocalAllClaims());
                    } finally {
                        f.runtime.releaseAllIslands();
                    }
                } else {
                    IslandCommands.drainRoutedBatch();
                }
                await(done, "entity source stack did not resume after fence release");
                assertEquals(failDuringBody
                        ? targetClaim ? List.of("before", "entity", "second-entity") : List.of("before", "entity")
                        : targetClaim ? List.of("before", "entity", "second-entity", "next-entity", "unit-tail")
                        : List.of("before", "entity", "next-entity", "unit-tail"), effects);
                assertSame(failDuringBody ? fatal : null, failure.get());
                assertEquals(-1L, IslandClock.contextTime());
                assertFalse(IslandCommands.hasRoutedPending());
                assertFalse(f.island.claimWanted);
                assertFalse(other.claimWanted);
                assertTrue(inherited.claimWanted, "outer interest must survive both success and raw failure");
                assertFalse(f.island.unitClaim.get());
                assertFalse(other.unitClaim.get());
            } finally {
                if (done.getCount() != 0) {
                    f.pool.publishFatalFailure(new AssertionError("entity rendezvous fixture cancelled"));
                }
                if (f.pool.hasFatalFailure()) IslandCommands.cancelFailedGeneration(fatal);
                try {
                    f.pool.stop();
                } catch (Throwable problem) {
                    if (problem != fatal) throw problem;
                }
            }
        }
    }

    @Test
    void completedRoutedReceiptCannotParkItsContinuationBehindAnotherWriter() throws Throwable {
        try (Fixture f = new Fixture()) {
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
            initializeEntityCursorEnvironment(f, unsafe);
            set(MinecraftServer.class, f.server, "levels",
                    Map.of(net.minecraft.world.level.Level.OVERWORLD, f.level));
            var acknowledgementHeld = new CountDownLatch(1);
            var releaseAcknowledgement = new CountDownLatch(1);
            var done = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();
            var effects = new CopyOnWriteArrayList<String>();
            @SuppressWarnings("unchecked")
            var acknowledgements = (java.util.concurrent.ConcurrentHashMap<IslandRegistry.PIsland, Object>)
                    get(IslandCommands.class, null, "GLOBAL_FENCE_PARK_ACKS");
            // Hold the real ACK's map bin after its body begins. Main can
            // release the first writer and complete the receipt, but its worker
            // cannot finish ACK removal until the later writer is acquiring.
            // No receipt or authority is substituted by this timing control.
            Thread acknowledgementBlocker = new Thread(() -> {
                try {
                    acknowledgements.compute(f.island, (island, acknowledgement) -> {
                        assertNotNull(acknowledgement);
                        acknowledgementHeld.countDown();
                        await(releaseAcknowledgement, "later writer never started acquisition");
                        return acknowledgement;
                    });
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                    f.pool.publishFatalFailure(problem);
                    acknowledgementHeld.countDown();
                }
            }, "completed-receipt-acknowledgement");
            f.pool.start();
            try {
                assertTrue(f.pool.enqueueUnit(() -> {
                    RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                    boolean claimed = false;
                    try {
                        assertTrue(f.island.tryClaimAutonomousWork());
                        claimed = true;
                        f.island.noteClaim("completed-routed-continuation");
                        worker.currentDomain = f.island.domain;
                        assertTrue(IslandCommands.routeCommandBlock(
                                "scoreboard objectives list", f.level, BlockPos.ZERO, null, () -> {
                                    assertTrue(f.runtime.currentThreadHoldsAllClaims());
                                    acknowledgementBlocker.start();
                                    await(acknowledgementHeld, "writer ACK timing control never entered");
                                    effects.add("routed-command");
                                    return true;
                                }));
                        effects.add("source-continuation");
                    } catch (Throwable problem) {
                        failure.set(problem);
                        f.pool.publishFatalFailure(problem);
                    } finally {
                        worker.currentDomain = null;
                        if (claimed) {
                            f.island.clearClaim();
                            f.island.unitClaim.set(false);
                        }
                        done.countDown();
                    }
                }));
                await(f.published, "source did not publish its command");
                IslandCommands.drainRoutedBatch();
                assertEquals(List.of("routed-command"), effects);
                ((CursorChunkCache) f.level.getChunkSource()).onPoll = () -> {
                    assertNotNull(f.runtime.globalFenceIntentReceipt());
                    assertFalse(f.runtime.currentThreadHoldsAllClaims());
                    releaseAcknowledgement.countDown();
                };
                withPoolDeadline(f, () -> RegionScheduler.runGlobalMainBody(
                        f.server, () -> effects.add("later-writer")));
                await(done, "completed source did not finish");
                assertNull(failure.get());
                assertEquals(List.of("routed-command", "source-continuation", "later-writer"), effects);
                assertNull(f.runtime.globalFenceIntentReceipt());
                assertFalse(f.island.unitClaim.get());
            } finally {
                releaseAcknowledgement.countDown();
                acknowledgementBlocker.join(2_000L);
                assertFalse(acknowledgementBlocker.isAlive());
                if (done.getCount() != 0) {
                    f.pool.publishFatalFailure(new AssertionError("completed receipt fixture cancelled"));
                }
                Throwable fatal = f.pool.fatalFailureOrNull();
                if (fatal != null) IslandCommands.cancelFailedGeneration(fatal);
                try {
                    f.pool.stop();
                } catch (Throwable problem) {
                    if (problem != fatal) throw problem;
                }
            }
        }
    }

    @Test
    void mergedBlockTickerRunsObserveSuccessorChangesAndStopAtLocalAndCarriedEntries() throws Throwable {
        try (Fixture f = new Fixture()) {
            initializeBlockTickerIndex(f);
            var effects = new CopyOnWriteArrayList<String>();
            var skip = java.util.Collections.newSetFromMap(
                    new java.util.IdentityHashMap<TickingBlockEntity, Boolean>());
            var seen = java.util.Collections.newSetFromMap(
                    new java.util.IdentityHashMap<TickingBlockEntity, Boolean>());
            BlockTickerProof last = blockTicker(f, true, () -> effects.add("last"));
            BlockTickerProof first = blockTicker(f, true, () -> {
                effects.add("first");
                // Generic successor metadata is live, not a pre-collected batch.
                f.runtime.beSeqOf.put(last, 6L);
            });
            BlockTickerProof local = blockTicker(f, false, () -> effects.add("local"));
            BlockTickerProof carried = blockTicker(f, true, () -> fail("carried ticker entered twice"));
            BlockTickerProof beforeCarry = blockTicker(f, true, () -> {
                assertTrue(seen.isEmpty(), "a global prefix may not mark its skipped successor early");
                effects.add("before-carry");
            });
            BlockTickerProof afterCarry = blockTicker(f, true, () -> {
                assertTrue(seen.contains(carried));
                effects.add("after-carry");
            });
            skip.add(carried);
            f.runtime.beSeqOf.putAll(Map.of(first, 0L, last, 1L, local, 2L,
                    beforeCarry, 3L, carried, 4L, afterCarry, 5L));
            var gates = new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();
            gates.put(0L, (byte) 1);
            assertNull(runBlockTickerPhase(f, () -> IslandBlockEntityTicks.tickMerged(
                    f.level, f.runtime,
                    List.of(List.of(first, last), List.of(local, beforeCarry, carried, afterCarry)),
                    new java.util.ArrayDeque<>(), gates, skip, seen)));
            assertEquals(List.of("first", "local", "before-carry", "after-carry", "last"), effects);
            assertEquals(0, carried.observations);
            assertEquals(java.util.Set.of(carried), seen);
        }
    }

    @Test
    void singleListBlockTickerFailureKeepsRawWinnerAndLeavesSuffixUnobserved() throws Throwable {
        exerciseBlockTickerFailure(false);
    }

    @Test
    void normalBlockTickerInvalidatingItsSourceCannotEnterTheBatchSuffix() throws Throwable {
        exerciseBlockTickerFailure(true);
    }

    private static void exerciseBlockTickerFailure(boolean invalidateSource) throws Throwable {
        try (Fixture f = new Fixture()) {
            initializeBlockTickerIndex(f);
            var effects = new CopyOnWriteArrayList<String>();
            AssertionError raw = new AssertionError("native block ticker failed");
            BlockTickerProof first = blockTicker(f, true, () -> effects.add("first"));
            BlockTickerProof cut = blockTicker(f, true, () -> {
                effects.add("cut");
                if (invalidateSource) f.island.retired = true;
                else throw raw;
            });
            BlockTickerProof next = blockTicker(f, true, () -> fail("global suffix entered"));
            BlockTickerProof local = blockTicker(f, false, () -> fail("local suffix entered"));
            var gates = new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();
            gates.put(0L, (byte) 1);
            Throwable failure = runBlockTickerPhase(f, () -> IslandBlockEntityTicks.tickMerged(
                    f.level, f.runtime, List.of(List.of(first, cut, next, local)),
                    new java.util.ArrayDeque<>(), gates, null, null));
            if (invalidateSource) assertInstanceOf(IllegalStateException.class, failure);
            else assertSame(raw, failure);
            assertEquals(List.of("first", "cut"), effects);
            assertEquals(0, next.observations);
            assertEquals(0, local.observations);
        }
    }

    private static void initializeBlockTickerIndex(Fixture f) throws Exception {
        set(RegionizedServer.class, f.runtime, "beSeqOf", new java.util.concurrent.ConcurrentHashMap<>());
        set(RegionizedServer.class, f.runtime, "beMainBodyTickers",
                java.util.concurrent.ConcurrentHashMap.newKeySet());
        f.island.domain.unitGameTime = 47L;
    }

    private static BlockTickerProof blockTicker(Fixture f, boolean main, Runnable body) {
        BlockTickerProof ticker = new BlockTickerProof(() -> {
            assertTrue(f.island.unitClaim.get());
            if (main) {
                assertSame(f.main, Thread.currentThread());
                assertSame(f.island, IslandCommands.routedUnitSourceOnMain(f.runtime));
                assertEquals(47L, IslandClock.contextTime());
            } else {
                RegionWorkerThread worker = assertInstanceOf(RegionWorkerThread.class, Thread.currentThread());
                assertSame(f.island.domain, worker.currentDomain);
                assertFalse(f.runtime.currentThreadHoldsAllClaims());
            }
            body.run();
        });
        if (main) f.runtime.beMainBodyTickers.add(ticker);
        return ticker;
    }

    private static final class BlockTickerProof implements TickingBlockEntity {
        final Runnable body;
        int observations;

        BlockTickerProof(Runnable body) { this.body = body; }
        @Override public void tick() { body.run(); }
        @Override public boolean isRemoved() { observations++; return false; }
        @Override public BlockPos getPos() { return BlockPos.ZERO; }
        @Override public String getType() { return "test:ordered-ticker"; }
    }

    private static Throwable runBlockTickerPhase(Fixture f, Runnable body) throws Throwable {
        return runBlockTickerPhase(f, body, null);
    }

    private static Throwable runBlockTickerPhase(
            Fixture f, Runnable body, CountDownLatch anotherEntered) throws Throwable {
        return runBlockTickerPhase(f, body, anotherEntered, null);
    }

    private static Throwable runBlockTickerPhase(
            Fixture f, Runnable body, CountDownLatch anotherEntered, CountDownLatch othersDone) throws Throwable {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        AtomicReference<Throwable> mainFailure = new AtomicReference<>();
        f.pool.start();
        try {
            assertTrue(f.pool.enqueueUnit(() -> {
                RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                try {
                    assertTrue(f.island.tryClaimAutonomousWork());
                    f.island.noteClaim("block-ticker-run");
                    worker.currentDomain = f.island.domain;
                    body.run();
                } catch (Throwable failure) {
                    workerFailure.set(failure);
                    f.pool.publishFatalFailure(failure);
                } finally {
                    worker.currentDomain = null;
                    f.island.clearClaim();
                    f.island.unitClaim.set(false);
                    done.countDown();
                }
            }));
            await(f.published, "block ticker never reached its Main rendezvous");
            if (anotherEntered != null) await(anotherEntered, "the second source never entered");
            withPoolDeadline(f, () -> {
                while (!done.await(1, TimeUnit.MILLISECONDS)
                        || othersDone != null && othersDone.getCount() != 0) {
                    if (IslandCommands.hasRoutedPending()) {
                        try {
                            IslandCommands.drainRoutedBatch();
                        } catch (Throwable failure) {
                            mainFailure.compareAndSet(null, failure);
                        }
                    }
                }
            });
            assertSame(mainFailure.get(), workerFailure.get());
            assertSame(workerFailure.get(), f.pool.fatalFailureOrNull());
            assertNull(f.runtime.globalFenceIntentReceipt());
            assertFalse(f.runtime.currentThreadHoldsAllClaims());
            assertFalse(IslandCommands.fenceWindowHeld());
            assertFalse(f.island.unitClaim.get());
            assertFalse(IslandCommands.hasRoutedPending());
            assertEquals(-1L, IslandClock.contextTime());
            return workerFailure.get();
        } finally {
            if (done.getCount() != 0) {
                f.pool.publishFatalFailure(new AssertionError("block ticker scenario cancelled"));
            }
            Throwable fatal = f.pool.fatalFailureOrNull();
            if (fatal != null) IslandCommands.cancelFailedGeneration(fatal);
            try {
                f.pool.stop();
            } catch (Throwable failure) {
                if (failure != fatal) throw failure;
            }
        }
    }

    @Test
    void lateNativeCohortKeepsCommandPublicationFifo() throws Throwable {
        try (Fixture f = new Fixture(ServerLevel.class, 3)) {
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
            initializeEntityCursorEnvironment(f, unsafe);
            f.island.domain.unitGameTime = 47L;
            f.runtime.islandRegistry.applyRebuildCells(List.of(
                    new LongArrayList(new long[]{0L}), new LongArrayList(new long[]{100L}),
                    new LongArrayList(new long[]{200L})), 0L);
            IslandRegistry.PIsland[] owners = {
                    f.runtime.islandRegistry.byCell(100L), f.runtime.islandRegistry.byCell(200L)};
            for (IslandRegistry.PIsland owner : owners) {
                owner.level = f.level;
                owner.domain.unitGameTime = 61L;
            }
            var entered = new CountDownLatch(2);
            var publishTail = new CountDownLatch(1);
            var othersDone = new CountDownLatch(2);
            CountDownLatch[] returned = {new CountDownLatch(1), new CountDownLatch(1)};
            var failure = new AtomicReference<Throwable>();
            var effects = new CopyOnWriteArrayList<String>();
            set(MinecraftServer.class, f.server, "levels", Map.of(net.minecraft.world.level.Level.OVERWORLD, f.level));
            ((CursorChunkCache) f.level.getChunkSource()).onPoll = () -> {
                assertFalse(f.runtime.currentThreadHoldsAllClaims());
                publishTail.countDown();
                awaitRoutedPublication(owners[0], returned[0]);
                awaitRoutedPublication(owners[1], returned[1]);
            };
            Thread publisher = new Thread(() -> {
                try {
                    await(f.published, "first source did not publish");
                    for (int i = 0; i < owners.length; i++) {
                        int index = i;
                        IslandRegistry.PIsland owner = owners[i];
                        assertTrue(f.pool.enqueueUnit(() -> {
                            RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                            boolean claimed = false;
                            try {
                                assertTrue(owner.tryClaimAutonomousWork());
                                claimed = true;
                                owner.noteClaim("late-mixed-request");
                                worker.currentDomain = owner.domain;
                                entered.countDown();
                                await(publishTail, "Main never reached native acquisition service");
                                if (index == 0) {
                                    IslandCommands.runBlockEntityTickOnMain(f.runtime, owner,
                                            () -> effects.add("late-native"));
                                } else {
                                    // Native and command writer requests retain
                                    // their actual publication order.
                                    awaitRoutedPublication(owners[0], returned[0]);
                                    assertTrue(IslandCommands.routeCommandBlock(
                                            "scoreboard objectives list", f.level,
                                            new BlockPos(200 << (f.runtime.regionizer().grid.shift + 4), 0, 0),
                                            null, () -> {
                                                assertTrue(f.runtime.currentThreadHoldsAllClaims());
                                                assertTrue(IslandCommands.fenceWindowHeld());
                                                effects.add("late-scoreboard");
                                                return true;
                                            }));
                                }
                            } catch (Throwable problem) {
                                failure.compareAndSet(null, problem);
                                f.pool.publishFatalFailure(problem);
                            } finally {
                                worker.currentDomain = null;
                                if (claimed) {
                                    owner.clearClaim();
                                    owner.unitClaim.set(false);
                                }
                                returned[index].countDown();
                                othersDone.countDown();
                            }
                        }));
                    }
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                    f.pool.publishFatalFailure(problem);
                }
            }, "late-mixed-request-publisher");
            publisher.start();
            try {
                assertNull(runBlockTickerPhase(f, () ->
                        IslandCommands.runBlockEntityTickOnMain(f.runtime, f.island, () ->
                                effects.add("first-native")), entered, othersDone));
                assertNull(failure.get());
                assertEquals(List.of("first-native", "late-native", "late-scoreboard"), effects);
            } finally {
                publisher.join(2_000L);
                assertFalse(publisher.isAlive());
            }
        }
    }

    @Test
    void entityRunLeavesThePlayerPrefixUntouchedUntilTheReleasedBatchReturns() throws Throwable {
        exerciseEntityCursor(false, false);
    }

    @Test
    void entityRunNativeFailureStopsBeforeObservingTheNextIteratorEntry() throws Throwable {
        exerciseEntityCursor(true, false);
    }

    @Test
    void writerPublishedDuringAcquisitionFinishesBeforeTheEarlierReceiptReturns() throws Throwable {
        exerciseEntityCursor(false, true);
    }

    private static void exerciseEntityCursor(boolean failBody, boolean lateWriter) throws Throwable {
        try (Fixture f = new Fixture(ServerLevel.class, 2)) {
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
            initializeEntityCursorEnvironment(f, unsafe);
            f.runtime.islandRegistry.applyRebuildCells(List.of(
                    new LongArrayList(new long[]{0L}), new LongArrayList(new long[]{100L})), 0L);
            IslandRegistry.PIsland other = f.runtime.islandRegistry.byCell(100L);
            other.level = f.level;
            other.domain.unitGameTime = 61L;
            Region member = f.runtime.regionizer().regionForGridKey(0L);
            member.domain = f.island.domain;
            member.domain.epoch = member.groupedEpoch = 1L;
            member.domain.unitGameTime = 47L;
            var effects = new CopyOnWriteArrayList<String>();
            var raw = new AssertionError("native entity despawn body failed");
            var first = (CursorFrame) unsafe.allocateInstance(CursorFrame.class);
            var player = (CursorPlayer) unsafe.allocateInstance(CursorPlayer.class);
            first.lease = new EntityTickLease();
            player.lease = new EntityTickLease();
            first.prefix = () -> {
                assertInstanceOf(RegionWorkerThread.class, Thread.currentThread());
                effects.add("frame-prefix");
            };
            player.prefix = () -> {
                assertInstanceOf(RegionWorkerThread.class, Thread.currentThread());
                assertFalse(f.runtime.currentThreadHoldsAllClaims());
                effects.add("player-prefix");
            };
            first.body = () -> {
                assertSame(f.main, Thread.currentThread());
                assertSame(f.island, IslandCommands.routedUnitSourceOnMain(f.runtime));
                assertEquals(47L, IslandClock.contextTime());
                effects.add("despawn");
                if (failBody) throw raw;
            };
            for (Entity entity : List.of(first, player)) {
                set(Entity.class, entity, "level", f.level);
                set(Entity.class, entity, "chunkPosition", new net.minecraft.world.level.ChunkPos(0, 0));
            }
            CursorTickRateManager rate = (CursorTickRateManager) f.server.tickRateManager();
            rate.player = player;
            var selected = new java.util.ArrayList<Entity>();
            java.util.Iterator<Entity> iterator = new java.util.Iterator<>() {
                int index;
                @Override public boolean hasNext() { return index < 2; }
                @Override public Entity next() {
                    Entity entity = index++ == 0 ? first : player;
                    selected.add(entity);
                    return entity;
                }
            };
            var otherEntered = new CountDownLatch(1);
            var otherDone = new CountDownLatch(1);
            var otherFailure = new AtomicReference<Throwable>();
            var publishOther = new CountDownLatch(lateWriter ? 1 : 0);
            if (lateWriter) {
                set(MinecraftServer.class, f.server, "levels", Map.of(net.minecraft.world.level.Level.OVERWORLD, f.level));
                ((CursorChunkCache) f.level.getChunkSource()).onPoll = () -> {
                    assertFalse(f.runtime.currentThreadHoldsAllClaims());
                    publishOther.countDown();
                    awaitRoutedPublication(other, otherDone);
                };
            }
            // B joins either the initial cohort or the native acquisition pump.
            // Its writer must precede A's still-untouched player prefix.
            Thread secondPublisher = new Thread(() -> {
                try {
                    await(f.published, "entity run did not publish");
                    assertTrue(f.pool.enqueueUnit(() -> {
                        RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                        try {
                            assertTrue(other.tryClaimAutonomousWork());
                            other.noteClaim("entity-cursor-later-request");
                            worker.currentDomain = other.domain;
                            if (lateWriter) otherEntered.countDown();
                            await(publishOther, "Main did not service the entered source during acquisition");
                            IslandCommands.runBlockEntityTickOnMain(f.runtime, other, () -> {
                                assertEquals(List.of(first, player), selected);
                                assertEquals(List.of("frame-prefix", "despawn"), effects);
                                effects.add("later-writer");
                                rate.freezePlayer = true;
                            });
                        } catch (Throwable failure) {
                            otherFailure.set(failure);
                        } finally {
                            worker.currentDomain = null;
                            other.clearClaim();
                            other.unitClaim.set(false);
                            otherDone.countDown();
                        }
                    }));
                    if (!lateWriter) {
                        awaitRoutedPublication(other, otherDone);
                        otherEntered.countDown();
                    }
                } catch (Throwable failure) {
                    otherFailure.set(failure);
                    f.pool.publishFatalFailure(failure);
                    otherEntered.countDown();
                    otherDone.countDown();
                }
            }, "entity-cursor-second-publisher");
            // The late case parks B before publication; the ordinary case publishes both.
            if (!failBody) secondPublisher.start();
            Throwable failure = runBlockTickerPhase(f, () -> {
                RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                assertTrue(member.tryClaim(worker, member.domain, 1L));
                worker.currentRegion = member;
                try {
                    Class<?> type = Class.forName(RegionizedServer.class.getName() + "$EntityTickCursor");
                    var constructor = type.getDeclaredConstructor(
                            RegionizedServer.class, Region.class, java.util.Iterator.class);
                    constructor.setAccessible(true);
                    Object cursor = constructor.newInstance(f.runtime, member, iterator);
                    Method tick = type.getDeclaredMethod("tick");
                    tick.setAccessible(true);
                    tick.invoke(cursor);
                } catch (java.lang.reflect.InvocationTargetException wrapped) {
                    RegionPreparationRoutedCommandTest.<RuntimeException>throwCursorFailure(wrapped.getCause());
                } catch (ReflectiveOperationException problem) {
                    throw new AssertionError(problem);
                } finally {
                    worker.currentRegion = null;
                    member.release(worker);
                    member.finishClaimTransaction();
                }
            }, failBody ? null : otherEntered);
            if (failBody) {
                assertSame(raw, failure);
                assertEquals(List.of(first), selected);
                assertEquals(List.of("frame-prefix", "despawn"), effects);
            } else {
                secondPublisher.join(2_000L);
                await(otherDone, "later writer did not retire");
                assertNull(otherFailure.get());
                assertNull(failure);
                assertEquals(List.of(first, player), selected);
                assertEquals(List.of("frame-prefix", "despawn", "later-writer", "player-prefix"), effects);
                assertEquals(1, rate.playerFreezeChecks);
            }
        }
    }

    private static void awaitRoutedPublication(IslandRegistry.PIsland island, CountDownLatch done) {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (!island.waitingOnRoutedCommand && done.getCount() != 0 && System.nanoTime() < deadline) {
            java.util.concurrent.locks.LockSupport.parkNanos(100_000L);
        }
        assertTrue(island.waitingOnRoutedCommand, "second request was not published");
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwCursorFailure(Throwable failure) throws T {
        throw (T) failure;
    }

    private static void initializeEntityCursorEnvironment(Fixture f, sun.misc.Unsafe unsafe) throws Exception {
        var chunks = new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();
        chunks.defaultReturnValue((byte) 127);
        var tracker = (net.minecraft.server.level.SimulationChunkTracker)
                unsafe.allocateInstance(net.minecraft.server.level.SimulationChunkTracker.class);
        set(net.minecraft.server.level.SimulationChunkTracker.class, tracker, "chunks", chunks);
        Object distance = unsafe.allocateInstance(Class.forName("net.minecraft.server.level.ChunkMap$DistanceManager"));
        set(net.minecraft.server.level.DistanceManager.class, distance, "simulationChunkTracker", tracker);
        var map = (net.minecraft.server.level.ChunkMap)
                unsafe.allocateInstance(net.minecraft.server.level.ChunkMap.class);
        set(net.minecraft.server.level.ChunkMap.class, map, "distanceManager", distance);
        var cache = (CursorChunkCache) unsafe.allocateInstance(CursorChunkCache.class);
        set(net.minecraft.server.level.ServerChunkCache.class, cache, "chunkMap", map);
        set(ServerLevel.class, f.level, "chunkSource", cache);
        set(MinecraftServer.class, f.server, "tickRateManager", new CursorTickRateManager(f.server));
    }

    private static final class CursorChunkCache extends net.minecraft.server.level.ServerChunkCache {
        Runnable onPoll;
        private CursorChunkCache() { super(null, null, null, null, null, null, 0, 0, false, null, null); }
        @Override public boolean pollTask() {
            Runnable action = onPoll;
            onPoll = null;
            if (action != null) action.run();
            return false;
        }
    }

    private static final class CursorTickRateManager extends net.minecraft.server.ServerTickRateManager {
        Entity player;
        boolean freezePlayer;
        int playerFreezeChecks;
        CursorTickRateManager(MinecraftServer server) { super(server); }
        @Override public boolean isEntityFrozen(Entity entity) {
            if (entity != player) return false;
            playerFreezeChecks++;
            assertTrue(freezePlayer, "the player prefix ran before the later accepted writer");
            return true;
        }
    }

    private interface CursorEntity extends RegionKeyed, ParallelProcessor.TickGuard {
        Runnable prefix();
        @Override default long fabrolia$getRegionKey() { return 0L; }
        @Override default void fabrolia$setRegionKey(long key) { fail("unexpected migration"); }
        @Override default boolean fabrolia$tryMarkRegionTick(long token) { prefix().run(); return true; }
        @Override default boolean fabrolia$carryRegionTickMark(long oldToken, long newToken) {
            throw new AssertionError("unexpected token carry");
        }
        @Override default byte fabrolia$getSyncCache() { return 0; }
        @Override default void fabrolia$setSyncCache(byte value) {}
    }

    private static final class CursorFrame extends net.minecraft.world.entity.decoration.ItemFrame
            implements CursorEntity {
        EntityTickLease lease;
        Runnable prefix;
        Runnable body;
        private CursorFrame() { super(null, null); }
        @Override public Runnable prefix() { return prefix; }
        @Override public EntityTickLease fabrolia$tickLease() { return lease; }
        @Override public void checkDespawn() { body.run(); }
    }

    private static final class CursorPlayer extends net.minecraft.server.level.ServerPlayer implements CursorEntity {
        EntityTickLease lease;
        Runnable prefix;
        private CursorPlayer() { super(null, null, null, null); }
        @Override public Runnable prefix() { return prefix; }
        @Override public EntityTickLease fabrolia$tickLease() { return lease; }
    }

    @Test
    void entityCommandKeepsSourceClockAndNextUnitRegistration() throws Exception {
        exerciseSourceCommand(SourceCommandMode.ENTITY);
    }

    @Test
    void scheduledCommandKeepsOriginalSameDrainWithoutCapturingNativeService() throws Exception {
        exerciseSourceCommand(SourceCommandMode.SCHEDULED);
    }

    @Test
    void chunkCommandKeepsEffectiveHandlingOverrideWithoutInventingCapture() throws Exception {
        exerciseSourceCommand(SourceCommandMode.CHUNK_HANDLING);
    }

    @Test
    void scoreboardCommandRequiresWriterOwnershipAndRetainsSourceRegistration() throws Exception {
        exerciseSourceCommand(SourceCommandMode.SCOREBOARD);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void islandEventRepostDistinguishesPendingFromExecuted(boolean repostExecuted) throws Exception {
        exerciseBlockEventRepost(false, repostExecuted);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void globalEventRepostDistinguishesPendingFromExecuted(boolean repostExecuted) throws Exception {
        exerciseBlockEventRepost(true, repostExecuted);
    }

    private static void exerciseBlockEventRepost(boolean global, boolean repostExecuted) throws Exception {
        try (Fixture f = new Fixture(BlockEventLevel.class)) {
            BlockEventLevel level = initializeBlockEventLevel(f);
            f.pool.start();
            var a = event(0);
            var b = event(1);
            var observed = new java.util.ArrayList<net.minecraft.world.level.BlockEventData>();
            level.eventBody = ev -> {
                observed.add(ev);
                if (observed.size() == 1) {
                    var repost = repostExecuted ? a : b;
                    level.blockEvent(repost.pos(), repost.block(), repost.paramA(), repost.paramB());
                }
            };
            if (!global) {
                f.runtime.regionizer().regionForGridKey(0L).initializeDomainAtCreation(f.island.domain);
                IslandBlockEvents.beginUnitCapture(f.island);
            }
            try {
                level.blockEvent(a.pos(), a.block(), a.paramA(), a.paramB());
                level.blockEvent(b.pos(), b.block(), b.paramA(), b.paramB());
                if (global) assertTrue(IslandBlockEvents.runPartitioned(level));
                else IslandBlockEvents.runIslandEvents(f.runtime, f.island, f.island.domain);
                assertEquals(repostExecuted ? List.of(a, b, a) : List.of(a, b), observed);
            } finally {
                if (!global) IslandBlockEvents.endUnitCapture(level);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mainAndTailEventsKeepOriginalPostingOrderAcrossDelayedPartition(boolean mainFirst) throws Exception {
        try (Fixture f = new Fixture(BlockEventLevel.class)) {
            BlockEventLevel level = initializeBlockEventLevel(f);
            f.pool.start();
            f.runtime.regionizer().regionForGridKey(0L).initializeDomainAtCreation(f.island.domain);
            var main = event(0);
            var tail = event(1);
            var duringDrain = event(2);
            var observed = new java.util.ArrayList<net.minecraft.world.level.BlockEventData>();
            level.eventBody = ev -> {
                observed.add(ev);
                if (observed.size() == 1) f.island.stashNextUnitEvent(duringDrain);
            };
            if (!mainFirst) f.island.stashNextUnitEvent(tail);
            level.blockEvent(main.pos(), main.block(), main.paramA(), main.paramB());
            if (mainFirst) f.island.stashNextUnitEvent(tail);
            assertTrue(IslandBlockEvents.runPartitioned(level));
            IslandBlockEvents.beginUnitCapture(f.island);
            try {
                IslandBlockEvents.runIslandEvents(f.runtime, f.island, f.island.domain);
                assertEquals(mainFirst ? List.of(main, tail) : List.of(tail, main), observed);
                IslandBlockEvents.runIslandEvents(f.runtime, f.island, f.island.domain);
                assertEquals(mainFirst ? List.of(main, tail, duringDrain)
                        : List.of(tail, main, duringDrain), observed);
            } finally {
                IslandBlockEvents.endUnitCapture(level);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pendingIdentitySpansSharedAndIslandQueuesWithoutChangingEligibility(boolean sharedFirst) throws Exception {
        try (Fixture f = new Fixture(BlockEventLevel.class)) {
            BlockEventLevel level = initializeBlockEventLevel(f);
            f.pool.start();
            f.runtime.regionizer().regionForGridKey(0L).initializeDomainAtCreation(f.island.domain);
            var pending = event(0);
            var eligible = event(1);
            var observed = new java.util.ArrayList<net.minecraft.world.level.BlockEventData>();
            level.eventBody = observed::add;
            if (!sharedFirst) f.island.stashNextUnitEvent(pending);
            level.blockEvent(pending.pos(), pending.block(), pending.paramA(), pending.paramB());
            if (sharedFirst) f.island.stashNextUnitEvent(pending);
            f.island.stashNextUnitEvent(eligible);
            IslandBlockEvents.beginUnitCapture(f.island);
            try {
                IslandBlockEvents.runIslandEvents(f.runtime, f.island, f.island.domain);
                assertEquals(sharedFirst ? List.of(eligible) : List.of(pending, eligible), observed,
                        "duplicate publication cannot accelerate the original event's eligibility");
                assertTrue(IslandBlockEvents.runPartitioned(level));
                IslandBlockEvents.runIslandEvents(f.runtime, f.island, f.island.domain);
                assertEquals(sharedFirst ? List.of(eligible, pending) : List.of(pending, eligible), observed,
                        "later partition cannot replay an already consumed pending identity");
            } finally {
                IslandBlockEvents.endUnitCapture(level);
            }
        }
    }

    @Test
    void partitionDuringAnEventPassCannotPullExternalInputIntoItsFrozenCapture() throws Exception {
        try (Fixture f = new Fixture(BlockEventLevel.class)) {
            f.pool.start();
            BlockEventLevel level = initializeBlockEventLevel(f);
            f.runtime.regionizer().regionForGridKey(0L).initializeDomainAtCreation(f.island.domain);
            var first = event(1);
            var external = event(2);
            var recursive = event(3);
            var observed = new java.util.ArrayList<net.minecraft.world.level.BlockEventData>();
            level.eventBody = ev -> {
                observed.add(ev);
                if (ev.equals(first)) {
                    // A distinct Main ingress arrives after this capture's eligibility cut.
                    IslandBlockEvents.publishShared(level, external);
                    assertTrue(IslandBlockEvents.runPartitioned(level));
                    level.blockEvent(external.pos(), external.block(), external.paramA(), external.paramB());
                    level.blockEvent(recursive.pos(), recursive.block(), recursive.paramA(), recursive.paramB());
                }
            };
            f.island.stashNextUnitEvent(first);
            IslandBlockEvents.beginUnitCapture(f.island);
            try {
                IslandBlockEvents.runIslandEvents(f.runtime, f.island, f.island.domain);
                assertEquals(List.of(first, recursive), observed);
                IslandBlockEvents.runIslandEvents(f.runtime, f.island, f.island.domain);
                assertEquals(List.of(first, recursive, external), observed);
            } finally {
                IslandBlockEvents.endUnitCapture(level);
            }
        }
    }

    @Test
    void topologyCarryRetainsPendingIdentityAndOrderAheadOfNewerSharedInput() throws Exception {
        try (Fixture f = new Fixture(BlockEventLevel.class)) {
            f.pool.start();
            BlockEventLevel level = initializeBlockEventLevel(f);
            f.runtime.regionizer().regionForGridKey(0L).initializeDomainAtCreation(f.island.domain);
            var first = event(1);
            var second = event(2);
            var newer = event(3);
            var observed = new java.util.ArrayList<net.minecraft.world.level.BlockEventData>();
            level.eventBody = observed::add;
            f.island.stashNextUnitEvent(first);
            f.island.stashNextUnitEvent(second);
            var carry = f.island.takeAllStampedEvents(100L);
            level.blockEvent(newer.pos(), newer.block(), newer.paramA(), newer.paramB());
            level.blockEvent(first.pos(), first.block(), first.paramA(), first.paramB());
            for (int i = carry.size() - 1; i >= 0; i--) IslandBlockEvents.restoreShared(level, carry.get(i));
            assertTrue(IslandBlockEvents.runPartitioned(level));
            IslandBlockEvents.beginUnitCapture(f.island);
            try {
                IslandBlockEvents.runIslandEvents(f.runtime, f.island, f.island.domain);
                assertEquals(List.of(first, second, newer), observed);
            } finally {
                IslandBlockEvents.endUnitCapture(level);
            }
        }
    }

    private static net.minecraft.world.level.BlockEventData event(int id) {
        return new net.minecraft.world.level.BlockEventData(
                BlockPos.ZERO, net.minecraft.world.level.block.Blocks.PISTON, id, 0);
    }

    private static BlockEventLevel initializeBlockEventLevel(Fixture f) {
        BlockEventLevel level = (BlockEventLevel) f.level;
        level.eventLock = new Object();
        level.events = new it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet<>();
        level.publications = new IslandBlockEvents.Publications();
        level.eventState = new BlockEventState();
        return level;
    }

    /** Keep native doBlockEvent; substitute only its world inputs and state callback. */
    private static final class BlockEventLevel extends ServerLevel implements BlockEventQueueAccess {
        Object eventLock;
        it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet<net.minecraft.world.level.BlockEventData> events;
        IslandBlockEvents.Publications publications;
        java.util.function.Consumer<net.minecraft.world.level.BlockEventData> eventBody;
        net.minecraft.world.level.block.state.BlockState eventState;

        private BlockEventLevel() {
            super(null, null, null, null, null, null, false, 0L, List.of(), false);
            throw new AssertionError("allocated without starting a world");
        }

        @Override public Object fabrolia$blockEventLock() { return eventLock; }
        @Override public it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet<net.minecraft.world.level.BlockEventData>
                fabrolia$blockEvents() { return events; }
        @Override public IslandBlockEvents.Publications fabrolia$blockEventPublications() { return publications; }
        @Override public long getGameTime() { return 100L; }
        @Override public boolean shouldTickBlocksAt(BlockPos pos) { return true; }
        @Override public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos pos) {
            return eventState;
        }
        @Override public void blockEvent(BlockPos pos, net.minecraft.world.level.block.Block block, int a, int b) {
            var ev = new net.minecraft.world.level.BlockEventData(pos, block, a, b);
            if (!IslandBlockEvents.captureLocal(this, ev)) {
                IslandBlockEvents.publishShared(this, ev);
            }
        }
    }

    private static final class BlockEventState extends net.minecraft.world.level.block.state.BlockState {
        BlockEventState() {
            super(net.minecraft.world.level.block.Blocks.PISTON,
                    new net.minecraft.world.level.block.state.properties.Property<?>[]{
                            net.minecraft.world.level.block.piston.PistonBaseBlock.EXTENDED,
                            net.minecraft.world.level.block.piston.PistonBaseBlock.FACING},
                    new Comparable<?>[]{false, net.minecraft.core.Direction.NORTH});
        }
        @Override public boolean triggerEvent(net.minecraft.world.level.Level level, BlockPos pos, int a, int b) {
            ((BlockEventLevel) level).eventBody.accept(
                    new net.minecraft.world.level.BlockEventData(pos, getBlock(), a, b));
            return false;
        }
    }

    private enum SourceCommandMode { ENTITY, SCHEDULED, CHUNK_HANDLING, SCOREBOARD }

    private static void exerciseSourceCommand(SourceCommandMode mode) throws Exception {
        try (Fixture f = new Fixture(BlockEventLevel.class)) {
            BlockEventLevel level = initializeBlockEventLevel(f);
            var observed = new java.util.ArrayList<net.minecraft.world.level.BlockEventData>();
            level.eventBody = observed::add;
            boolean capturing = mode == SourceCommandMode.SCHEDULED || mode == SourceCommandMode.SCOREBOARD;
            Region member = f.runtime.regionizer().regionForGridKey(0L);
            member.initializeDomainAtCreation(f.island.domain);
            member.groupedEpoch = 1L;
            f.island.domain.epoch = 1L;
            f.island.domain.unitGameTime = 47L;
            f.island.domain.handlingTickPhase = capturing;
            var block = net.minecraft.world.level.block.Blocks.PISTON;
            var seed = new net.minecraft.world.level.BlockEventData(BlockPos.ZERO, block, 0, 0);
            var own = new net.minecraft.world.level.BlockEventData(BlockPos.ZERO, block, 1, 0);
            var afterService = new net.minecraft.world.level.BlockEventData(BlockPos.ZERO, block, 2, 0);
            var nativeService = new net.minecraft.world.level.BlockEventData(BlockPos.ZERO, block, 3, 0);
            var prelude = new net.minecraft.world.level.BlockEventData(BlockPos.ZERO, block, 5, 0);
            var foreign = new net.minecraft.world.level.BlockEventData(
                    new BlockPos(100 << (f.runtime.regionizer().grid.shift + 4), 0, 0), block, 4, 0);
            var effects = new CopyOnWriteArrayList<String>();
            var failure = new AtomicReference<Throwable>();
            var done = new CountDownLatch(1);
            f.pool.start();
            try {
                assertTrue(f.pool.enqueueUnit(() -> {
                    RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                    boolean memberClaimed = false;
                    try {
                        assertTrue(f.island.tryClaimAutonomousWork());
                        f.island.noteClaim("source-command-context");
                        worker.currentDomain = f.island.domain;
                        if (mode == SourceCommandMode.ENTITY) {
                            assertTrue(member.tryClaim(worker, member.domain, 1L));
                            memberClaimed = true;
                            worker.currentRegion = member;
                        }
                        if (capturing) {
                            IslandBlockEvents.beginUnitCapture(f.island);
                            assertTrue(IslandBlockEvents.captureLocal(f.level, seed));
                        }
                        Runnable command = () -> {
                            assertFalse(IslandCommands.routeCommandBlock(
                                    mode == SourceCommandMode.SCOREBOARD
                                            ? "scoreboard objectives list" : "say source command",
                                    f.level, BlockPos.ZERO, null, () -> {
                                        effects.add("body");
                                        assertSame(f.main, Thread.currentThread());
                                        assertEquals(47L, IslandClock.contextTime());
                                        assertEquals(f.island.id, IslandClock.contextId());
                                        assertEquals(mode != SourceCommandMode.ENTITY,
                                                IslandClock.workerHandlingTick(f.level));
                                        assertTrue(RegionScheduler.ownsExclusiveGameplay());
                                        assertTrue(f.runtime.currentThreadHoldsAllClaims());
                                        assertSame(f.island, IslandCommands.routedUnitSourceOnMain(f.runtime));
                                        assertTrue(IslandBlockEvents.captureLocal(f.level, own));
                                        assertFalse(IslandBlockEvents.captureLocal(f.level, foreign));
                                        RegionScheduler.runNativeMainServiceBody(() -> {
                                            assertEquals(-1L, IslandClock.contextTime());
                                            assertNull(IslandClock.workerHandlingTick(f.level));
                                            assertNull(IslandCommands.routedUnitSourceOnMain(f.runtime));
                                            assertFalse(IslandBlockEvents.captureLocal(f.level, nativeService));
                                            effects.add("native-service");
                                        });
                                        assertEquals(47L, IslandClock.contextTime());
                                        assertTrue(IslandBlockEvents.captureLocal(f.level, afterService));
                                        return false;
                                    }));
                        };
                        if (mode == SourceCommandMode.CHUNK_HANDLING) {
                            IslandClock.runHandlingTick(f.level, command, Runnable::run);
                        } else {
                            command.run();
                        }
                        assertNull(f.runtime.globalFenceIntentReceipt());
                        assertTrue(f.island.unitClaim.get());
                        assertTrue(observed.isEmpty(), "routing queues events without running them inside the producer");
                        if (!capturing) IslandBlockEvents.beginUnitCapture(f.island);
                        IslandBlockEvents.runIslandEvents(f.runtime, f.island, f.island.domain);
                        assertEquals(capturing
                                ? List.of(seed, own, afterService)
                                : List.of(own, afterService), observed);
                        effects.add("resumed");
                    } catch (Throwable problem) {
                        failure.set(problem);
                        f.pool.publishFatalFailure(problem);
                    } finally {
                        IslandBlockEvents.endUnitCapture(f.level);
                        worker.currentRegion = null;
                        worker.currentDomain = null;
                        if (memberClaimed) {
                            member.release(worker);
                            member.finishClaimTransaction();
                        }
                        f.island.clearClaim();
                        f.island.unitClaim.set(false);
                        done.countDown();
                    }
                }));
                await(f.published, "source command did not publish its Main receipt");
                AdvancementBuffer.deferDirect(() -> {
                    assertSame(f.main, Thread.currentThread());
                    assertTrue(RegionScheduler.ownsExclusiveGameplay());
                    assertEquals(-1L, IslandClock.contextTime());
                    assertNull(IslandCommands.routedUnitSourceOnMain(f.runtime));
                    assertFalse(IslandBlockEvents.captureLocal(f.level, prelude));
                    effects.add("prelude");
                });
                IslandCommands.drainRoutedBatch();
                await(done, "source command coordinator did not resume");
                assertNull(failure.get());
                assertEquals(List.of("prelude", "body", "native-service", "resumed"), effects);
                assertEquals(-1L, IslandClock.contextTime());
                assertFalse(IslandCommands.hasRoutedPending());
            } finally {
                if (done.getCount() != 0) {
                    var cancelled = new AssertionError("source command fixture cancelled");
                    f.pool.publishFatalFailure(cancelled);
                    IslandCommands.cancelFailedGeneration(cancelled);
                }
                Throwable fatal = f.pool.fatalFailureOrNull();
                if (fatal == null) f.pool.stop();
                else {
                    IslandCommands.cancelFailedGeneration(fatal);
                    assertSame(fatal, assertThrows(Throwable.class, f.pool::stop));
                }
            }
        }
    }

    @Test
    void blockEntityMainRendezvousKeepsSourceClockWithoutBorrowingPlayerLate() throws Exception {
        try (Fixture f = new Fixture()) {
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
            var player = (net.minecraft.server.level.ServerPlayer)
                    unsafe.allocateInstance(net.minecraft.server.level.ServerPlayer.class);
            LateTrackingProof tracking = new LateTrackingProof(player, f.island);
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            f.island.domain.unitGameTime = 47L;
            f.pool.start();
            try {
                assertTrue(f.pool.enqueueUnit(() -> {
                    RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                    try {
                        assertTrue(f.island.tryClaimAutonomousWork());
                        f.island.noteClaim("block-entity-main-clock");
                        worker.currentDomain = f.island.domain;
                        IslandCommands.runBlockEntityTickOnMain(f.runtime, f.island, () -> {
                            assertSame(f.main, Thread.currentThread());
                            assertSame(f.island, IslandCommands.routedUnitSourceOnMain(f.runtime));
                            assertEquals(47L, IslandClock.contextTime());
                            assertEquals(f.island.id, IslandClock.contextId());
                            assertEquals(Boolean.FALSE, IslandClock.workerHandlingTick(f.level));
                            assertFalse(IslandCommands.ownsPlayerLateOnMain(player, f.island));
                            tracking.assertPublication(IslandEntityTracking.PostPhysicsResult.INVALID);
                            RegionScheduler.runNativeMainServiceBody(() -> {
                                assertNull(IslandCommands.routedUnitSourceOnMain(f.runtime));
                                assertEquals(-1L, IslandClock.contextTime());
                                assertEquals(FabroliaTickDomain.WALL, IslandClock.contextId());
                                assertNull(IslandClock.workerHandlingTick(f.level));
                                tracking.assertPublication(IslandEntityTracking.PostPhysicsResult.INVALID);
                            });
                            assertEquals(47L, IslandClock.contextTime());
                        });
                        assertSame(worker, Thread.currentThread());
                        assertTrue(f.island.unitClaim.get());
                    } catch (Throwable problem) {
                        failure.set(problem);
                    } finally {
                        worker.currentDomain = null;
                        f.island.clearClaim();
                        f.island.unitClaim.set(false);
                        done.countDown();
                    }
                }));
                await(f.published, "block-entity body did not reach Main");
                IslandCommands.drainRoutedBatch();
                await(done, "block-entity coordinator did not resume");
                assertNull(failure.get());
                assertEquals(-1L, IslandClock.contextTime());
                assertFalse(IslandCommands.hasRoutedPending());
            } finally {
                if (done.getCount() != 0) {
                    var cancelled = new AssertionError("block-entity fixture cancelled");
                    f.pool.publishFatalFailure(cancelled);
                    IslandCommands.cancelFailedGeneration(cancelled);
                }
                f.pool.stop();
            }
        }
    }

    @Test
    void queuedPlayerLateCancelledBeforeMainNeverReportsBodySuccess() throws Exception {
        try (Fixture f = new Fixture()) {
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
            var player = (net.minecraft.server.level.ServerPlayer)
                    unsafe.allocateInstance(net.minecraft.server.level.ServerPlayer.class);
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean entered = new AtomicBoolean();
            AtomicBoolean resumedNormally = new AtomicBoolean();
            IllegalArgumentException fatal = new IllegalArgumentException("failed before player L");
            f.island.domain.unitGameTime = 31;
            f.pool.start();
            try {
                assertTrue(f.pool.enqueueUnit(() -> {
                    RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                    try {
                        assertTrue(f.island.tryClaimAutonomousWork());
                        f.island.noteClaim("cancelled-player-late");
                        worker.currentDomain = f.island.domain;
                        IslandCommands.runPlayerLateOnMain(f.runtime, f.island, player,
                                () -> entered.set(true));
                        resumedNormally.set(true);
                    } catch (Throwable problem) {
                        failure.set(problem);
                    } finally {
                        worker.currentDomain = null;
                        f.island.clearClaim();
                        f.island.unitClaim.set(false);
                        done.countDown();
                    }
                }));
                await(f.published, "typed L request did not reach the control queue");
                f.pool.publishFatalFailure(fatal);
                assertSame(fatal, IslandCommands.cancelFailedGeneration(fatal));
                await(done, "cancelled L did not release its original stack");
                assertSame(fatal, failure.get());
                assertFalse(entered.get());
                assertFalse(resumedNormally.get());
                assertFalse(IslandCommands.hasRoutedPending());
            } finally {
                f.pool.publishFatalFailure(fatal);
                IslandCommands.cancelFailedGeneration(fatal);
                assertSame(fatal, assertThrows(IllegalArgumentException.class, f.pool::stop));
            }
        }
    }

    @Test
    void softLatePublishesAtDestinationAndRetainsSourceTrackerPairings() throws Exception {
        exerciseSoftLateTracking(false);
    }

    @Test
    void softLateRetainsAlreadyCarriedTrackerWithoutBorrowingSourcePublication() throws Exception {
        exerciseSoftLateTracking(true);
    }

    private static void exerciseSoftLateTracking(boolean carried) throws Exception {
        try (Fixture f = new Fixture()) {
            var destinationChunk = new net.minecraft.world.level.ChunkPos(800, 0);
            long destinationCell = f.runtime.regionizer().grid.keyFor(destinationChunk.pack());
            f.runtime.islandRegistry.applyRebuildCells(List.of(new LongArrayList(new long[]{0L}),
                    new LongArrayList(new long[]{destinationCell})), 0L);
            var destination = f.runtime.islandRegistry.byCell(destinationCell);
            destination.level = f.level;
            var committed = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<EpochDomain>();
            committed.put(0L, f.island.domain);
            committed.put(destinationCell, destination.domain);
            set(RegionizedServer.class, f.runtime, "cellDomainSnapshot", committed);
            f.island.domain.unitGameTime = 73L;
            destination.domain.unitGameTime = 900L;
            try (SoftTrackingProof proof = new SoftTrackingProof(f)) {
                CountDownLatch done = new CountDownLatch(1);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                f.pool.start();
                try {
                    assertTrue(f.pool.enqueueUnit(() -> {
                        RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                        boolean claimed = false;
                        try {
                            assertTrue(f.island.tryClaimAutonomousWork());
                            claimed = true;
                            f.island.noteClaim("soft-tracker-main");
                            worker.currentDomain = f.island.domain;
                            IslandCommands.runPlayerLateOnMain(f.runtime, f.island, proof.player, () -> {
                                try {
                                    proof.move(destinationChunk, carried ? destination : f.island);
                                    proof.enterLate();
                                    assertTrue(AsyncIslandPlayerTicks.ownsSoftLifecycleLateOnMain(
                                            proof.pending, f.island));
                                    RegionScheduler.runNativeMainServiceBody(() ->
                                            proof.assertRejected());
                                    Object soft = get(proof.context.getClass(), proof.context, "softLate");
                                    set(proof.context.getClass(), proof.context, "softLate", null);
                                    proof.assertRejected();
                                    set(proof.context.getClass(), proof.context, "softLate", soft);
                                    var lookalike = new AsyncIslandPlayerTicks.PendingLate(proof.state,
                                            proof.player, proof.listener, proof.pending.owner, proof.pending.permit);
                                    assertEquals(IslandEntityTracking.PostPhysicsResult.INVALID,
                                            IslandEntityTracking.publishPendingRealPlayerAfterSoftLifecycleOnMain(
                                                    lookalike, f.island));
                                    assertEquals(0, proof.tracker.publications);
                                    proof.tracker.owner.pendingEntityUpdates.remove(proof.tracker);
                                    proof.assertRejected();
                                    proof.tracker.owner.pendingEntityUpdates.add(proof.tracker);
                                    proof.tracker.onSend = () -> {
                                        assertSame(f.main, Thread.currentThread());
                                        assertTrue(IslandCommands.ownsPlayerLateOnMain(proof.player, f.island));
                                        assertTrue(RegionScheduler.ownsCurrentContext(
                                                f.level, proof.player.blockPosition()));
                                        assertSame(destination, f.runtime.islandOwnerOfChunk(
                                                proof.player.chunkPosition().pack()));
                                        assertEquals(73L, IslandClock.contextTime());
                                        assertEquals(IslandEntityTracking.Route.MAIN,
                                                IslandEntityTracking.route(proof.tracker, proof.player));
                                    };
                                    assertEquals(IslandEntityTracking.PostPhysicsResult.HANDOFF,
                                            IslandEntityTracking.publishPendingRealPlayerAfterSoftLifecycleOnMain(
                                                    proof.pending, f.island));
                                    assertEquals(1, proof.tracker.publications);
                                    assertEquals(0, proof.tracker.pairingCommits);
                                    assertTrue(proof.tracker.pairingsPending);
                                    assertNull(proof.tracker.owner);
                                    assertFalse(f.island.pendingEntityUpdates.contains(proof.tracker));
                                    assertFalse(destination.pendingEntityUpdates.contains(proof.tracker));
                                    assertSame(proof.tracker, proof.state.trackingTopologyGap.tracker());
                                    assertTrue(proof.state.session.isRunningRealLate(proof.pending.permit));
                                    proof.finishHandoff();
                                    proof.tracker.onSend = () ->
                                            assertTrue(RegionScheduler.ownsCurrentContext(
                                                    f.level, proof.player.blockPosition()));
                                    IslandEntityTracking.finishTopologyGapPublicationOnMain(
                                            proof.state.trackingTopologyGap);
                                    assertEquals(1, proof.tracker.pairingCommits);
                                    assertFalse(proof.tracker.pairingsPending);
                                    assertEquals(1, proof.tracker.publications,
                                            "pairing completion must not repeat this Main-clock publication");
                                    assertNull(proof.state.trackingTopologyGap);
                                } catch (Exception problem) {
                                    throw new AssertionError(problem);
                                } finally {
                                    proof.turn.remove();
                                }
                            });
                        } catch (Throwable problem) {
                            failure.set(problem);
                        } finally {
                            worker.currentDomain = null;
                            if (claimed) {
                                f.island.clearClaim();
                                f.island.unitClaim.set(false);
                            }
                            done.countDown();
                        }
                    }));
                    await(f.published, "soft tracker L did not reach Main");
                    IslandCommands.drainRoutedBatch();
                    await(done, "soft tracker source did not resume");
                    assertNull(failure.get());
                } finally {
                    if (done.getCount() != 0) {
                        var cancelled = new AssertionError("soft tracker fixture cancelled");
                        f.pool.publishFatalFailure(cancelled);
                        IslandCommands.cancelFailedGeneration(cancelled);
                    }
                    f.pool.stop();
                }
            }
        }
    }

    private static void exercisePlayerLate(boolean failDuringBody) throws Exception {
        try (Fixture f = new Fixture(ServerLevel.class, 2)) {
            f.runtime.islandRegistry.applyRebuildCells(List.of(
                    new LongArrayList(new long[]{0L}), new LongArrayList(new long[]{100L})), 0L);
            IslandRegistry.PIsland other = f.runtime.islandRegistry.byCell(100L);
            other.level = f.level;
            List<IslandRegistry.PIsland> islands = List.of(f.island, other);
            CountDownLatch done = new CountDownLatch(2);
            CountDownLatch bodyEntered = new CountDownLatch(1);
            CountDownLatch observedFatal = new CountDownLatch(1);
            CountDownLatch scenarioDone = new CountDownLatch(1);
            List<Throwable> failures = new CopyOnWriteArrayList<>();
            List<String> effects = new CopyOnWriteArrayList<>();
            AtomicReference<Throwable> observerFailure = new AtomicReference<>();
            IllegalArgumentException fatal = new IllegalArgumentException("peer failed during native L");
            Thread observer = new Thread(() -> {
                try {
                    if (failDuringBody) {
                        await(bodyEntered, "typed Main L never entered");
                        f.pool.publishFatalFailure(fatal);
                        assertFalse(done.await(100, TimeUnit.MILLISECONDS),
                                "W must not unwind a coordinator while Main owns its ACK");
                        for (IslandRegistry.PIsland island : islands) {
                            assertTrue(island.unitClaim.get(), "Main body lost a source claim on W");
                            assertTrue(island.waitingOnRoutedCommand);
                        }
                        observedFatal.countDown();
                    }
                    if (!scenarioDone.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("simultaneous player L fence deadlocked");
                    }
                } catch (Throwable problem) {
                    observerFailure.set(problem);
                    f.pool.publishFatalFailure(problem);
                    observedFatal.countDown();
                }
            }, "typed-player-late-watchdog");
            observer.setDaemon(true);
            f.pool.start();
            observer.start();
            try {
                sun.misc.Unsafe unsafe = (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
                for (IslandRegistry.PIsland island : islands) {
                    var player = (net.minecraft.server.level.ServerPlayer)
                            unsafe.allocateInstance(net.minecraft.server.level.ServerPlayer.class);
                    LateTrackingProof tracking = new LateTrackingProof(player, island);
                    tracking.assertPublication(IslandEntityTracking.PostPhysicsResult.INVALID);
                    island.domain.unitGameTime = 100L + island.id;
                    assertTrue(f.pool.enqueueUnit(() -> {
                        RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                        try {
                            assertTrue(island.tryClaimAutonomousWork());
                            island.noteClaim("typed-player-late-regression");
                            worker.currentDomain = island.domain;
                            tracking.assertPublication(IslandEntityTracking.PostPhysicsResult.HANDOFF);
                            effects.add("E" + island.id);
                            IslandCommands.runPlayerLateOnMain(f.runtime, island, player, () -> {
                                assertSame(f.main, Thread.currentThread());
                                assertSame(island, IslandCommands.routedUnitSourceOnMain(f.runtime));
                                assertTrue(IslandCommands.ownsPlayerLateOnMain(player, island));
                                assertFalse(IslandCommands.ownsPlayerLateOnMain(player,
                                        island == f.island ? other : f.island));
                                assertTrue(f.runtime.currentThreadHoldsAllClaims());
                                assertEquals(100L + island.id, IslandClock.contextTime());
                                assertEquals(island.id, IslandClock.contextId());
                                assertEquals(Boolean.FALSE, IslandClock.workerHandlingTick(f.level));
                                tracking.assertPublication(IslandEntityTracking.PostPhysicsResult.HANDOFF);
                                tracking.assertListenerReplacementRejected();
                                RegionScheduler.runNativeMainServiceBody(() -> {
                                    assertNull(IslandCommands.routedUnitSourceOnMain(f.runtime));
                                    assertFalse(IslandCommands.ownsPlayerLateOnMain(player, island));
                                    assertEquals(-1L, IslandClock.contextTime());
                                    assertEquals(FabroliaTickDomain.WALL, IslandClock.contextId());
                                    assertNull(IslandClock.workerHandlingTick(f.level));
                                    tracking.assertPublication(IslandEntityTracking.PostPhysicsResult.INVALID);
                                });
                                effects.add("L" + island.id);
                                bodyEntered.countDown();
                                if (failDuringBody) {
                                    await(observedFatal, "peer did not inspect retained claims");
                                    for (IslandRegistry.PIsland source : islands) {
                                        assertTrue(source.unitClaim.get());
                                    }
                                }
                            });
                            assertSame(worker, Thread.currentThread());
                            assertSame(island.domain, worker.currentDomain);
                            assertTrue(island.unitClaim.get());
                            effects.add("tail" + island.id);
                        } catch (Throwable problem) {
                            failures.add(problem);
                        } finally {
                            worker.currentDomain = null;
                            island.clearClaim();
                            island.unitClaim.set(false);
                            done.countDown();
                        }
                    }));
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!f.island.waitingOnRoutedCommand || !other.waitingOnRoutedCommand) {
                    assertTrue(System.nanoTime() < deadline, "both typed requests must publish before Main");
                    Thread.onSpinWait();
                }
                if (failDuringBody) {
                    assertSame(fatal, assertThrows(IllegalArgumentException.class, IslandCommands::drainRoutedBatch));
                } else {
                    IslandCommands.drainRoutedBatch();
                }
                await(done, "original unit stacks did not resume");
                assertEquals(-1L, IslandClock.contextTime(), "typed clock must not leak after body cleanup");
                assertFalse(IslandCommands.hasRoutedPending());
                if (failDuringBody) {
                    assertEquals(2, failures.size());
                    failures.forEach(problem -> assertSame(fatal, problem));
                    assertEquals(1L, effects.stream().filter(event -> event.startsWith("L")).count());
                    assertFalse(effects.stream().anyMatch(event -> event.startsWith("tail")));
                } else {
                    assertTrue(failures.isEmpty(), () -> "unexpected actor failure: " + failures);
                    for (IslandRegistry.PIsland island : islands) {
                        assertTrue(effects.indexOf("E" + island.id) < effects.indexOf("L" + island.id));
                        assertTrue(effects.indexOf("L" + island.id) < effects.indexOf("tail" + island.id));
                    }
                    assertTrue(effects.indexOf("L" + f.island.id) < effects.indexOf("tail" + other.id));
                    assertTrue(effects.indexOf("L" + other.id) < effects.indexOf("tail" + f.island.id));
                }
            } finally {
                scenarioDone.countDown();
                observer.join(2_000L);
                if (done.getCount() != 0) {
                    f.pool.publishFatalFailure(new AssertionError("typed L fixture cancelled"));
                }
                if (f.pool.hasFatalFailure()) {
                    IslandCommands.cancelFailedGeneration(fatal);
                }
                try {
                    f.pool.stop();
                } catch (Throwable problem) {
                    if (problem != fatal) throw problem;
                }
            }
            assertNull(observerFailure.get(), () -> "claim observer failed: " + observerFailure.get());
        }
    }

    @Test
    void cloneWithOwnedArgumentsButForeignTranslatedEndIsNotWorkerLocal() throws Exception {
        try (Fixture f = new Fixture()) {
            ThreadedRegionizer regionizer = f.runtime.regionizer();
            regionizer.regionForGridKey(0L).domain = f.island.domain;
            int lastOwned = (1 << (regionizer.grid.shift + 4)) - 1;
            assertTrue(IslandCommands.isIslandLocal(
                    "setblock " + lastOwned + " 64 0 stone", BlockPos.ZERO,
                    f.runtime, f.island.domain));
            assertFalse(IslandCommands.isIslandLocal(
                    "clone 0 64 0 1 64 0 " + lastOwned + " 64 0", BlockPos.ZERO,
                    f.runtime, f.island.domain));
        }
    }

    @Test
    void fillAuthenticatesInteriorCellsOfNonrectangularIsland() throws Exception {
        try (Fixture f = new Fixture()) {
            ThreadedRegionizer reg = f.runtime.regionizer();
            int cellChunks = 1 << reg.grid.shift;
            int cellBlocks = cellChunks << 4;
            reg.regionForGridKey(reg.grid.keyFor(0, 0)).domain = f.island.domain;
            reg.regionForGridKey(reg.grid.keyFor(0, cellChunks)).domain = f.island.domain;
            reg.regionForGridKey(reg.grid.keyFor(cellChunks, cellChunks)).domain = f.island.domain;
            String fill = "fill " + cellBlocks + " 64 " + cellBlocks + " 0 64 0 stone";
            assertFalse(IslandCommands.isIslandLocal(fill, BlockPos.ZERO, f.runtime, f.island.domain),
                    "owned corners of an L-shaped island do not own the whole rectangle");
            reg.regionForGridKey(reg.grid.keyFor(cellChunks, 0)).domain = f.island.domain;
            assertTrue(IslandCommands.isIslandLocal(fill, BlockPos.ZERO, f.runtime, f.island.domain));
        }
    }

    @Test
    void offIntentCannotRemovePublishedOwnershipFromTheCommandFence() throws Exception {
        try (Fixture f = new Fixture()) {
            f.pool.start();
            try {
                var committed = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<EpochDomain>();
                committed.put(0L, f.island.domain);
                set(RegionizedServer.class, f.runtime, "cellDomainSnapshot", committed);
                f.runtime.asyncActive = false;
                set(RegionScheduler.class, null, "active", false);
                assertTrue(IslandCommands.requiresMainFence());
                assertTrue(IslandCommands.quiesceAllExcept(null),
                        "the published old topology still requires actual claim acquisition");
                assertTrue(f.island.unitClaim.get());
                assertTrue(f.runtime.currentThreadHoldsAllClaims());
                IslandCommands.releaseAllExcept();

                set(RegionizedServer.class, f.runtime, "cellDomainSnapshot", null);
                assertFalse(IslandCommands.requiresMainFence());
                assertTrue(f.island.tryClaimAutonomousWork());
                try {
                    assertTrue(IslandCommands.requiresMainFence(),
                            "a retained exact claim remains authoritative even without a cell snapshot");
                } finally {
                    f.island.unitClaim.set(false);
                }
                assertFalse(IslandCommands.requiresMainFence());
            } finally {
                IslandCommands.releaseAllExcept();
                f.pool.stop();
            }
        }
    }

    @Test
    void offIntentPreservesAColdCommittedEntityClaimUntilCollapse() throws Exception {
        try (Fixture f = new Fixture()) {
            f.pool.start();
            try {
                var committed = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<EpochDomain>();
                committed.put(0L, f.island.domain);
                set(RegionizedServer.class, f.runtime, "cellDomainSnapshot", committed);
                f.runtime.asyncActive = false;
                set(RegionScheduler.class, null, "active", false);
                assertNull(f.runtime.regionizer().peekByGridKey(0L));
                sun.misc.Unsafe unsafe = (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
                Entity entity = (Entity) unsafe.allocateInstance(ArmorStand.class);
                set(Entity.class, entity, "level", f.level);
                set(Entity.class, entity, "chunkPosition", new net.minecraft.world.level.ChunkPos(0, 0));
                AtomicBoolean ran = new AtomicBoolean();
                RegionScheduler.runWithMainEntityClaim(entity, () -> {
                    boolean entered = f.island.tryClaimAutonomousWork();
                    if (entered) f.island.unitClaim.set(false);
                    assertFalse(entered, "the vanilla Main body must exclude the old autonomous owner");
                    ran.set(true);
                });
                assertTrue(ran.get());
                assertTrue(f.island.tryClaimAutonomousWork(),
                        "the exact Main receipt must release its claim after the body");
                f.island.unitClaim.set(false);
            } finally {
                f.pool.stop();
            }
        }
    }

    @Test
    void globalMainBatchAppliesDeferredAdvancementsBeforeTheNextRoot() throws Throwable {
        try (Fixture f = new Fixture()) {
            List<String> effects = new java.util.ArrayList<>();
            f.pool.start();
            try {
                RegionScheduler.runGlobalMainBatch(f.server, () -> {
                    RegionScheduler.runGlobalMainBody(f.server, () -> {
                        assertTrue(f.runtime.currentThreadHoldsAllClaims());
                        assertFalse(f.island.tryClaimAutonomousWork());
                        assertTrue(f.island.unitClaim.get());
                        effects.add("first");
                        AdvancementBuffer.deferDirect(() -> {
                            assertSame(f.main, Thread.currentThread());
                            assertTrue(f.runtime.currentThreadHoldsAllClaims());
                            effects.add("advancement");
                        });
                    });
                    assertEquals(List.of("first"), effects);
                    RegionScheduler.runGlobalMainBody(f.server, () -> {
                        assertTrue(f.runtime.currentThreadHoldsAllClaims());
                        assertTrue(f.island.unitClaim.get());
                        assertEquals(List.of("first", "advancement"), effects);
                        effects.add("second");
                    });
                });
                assertEquals(List.of("first", "advancement", "second"), effects);
                assertNull(f.runtime.globalFenceIntentReceipt());
                assertFalse(f.runtime.currentThreadHoldsAllClaims());
                assertFalse(IslandCommands.fenceWindowHeld());
                assertFalse(f.island.unitClaim.get());
                assertFalse(f.island.claimWanted);
            } finally {
                f.pool.stop();
            }
        }
    }

    @Test
    void globalMainBatchDeferredRawFailurePreventsTheSecondAndLaterRoots() throws Throwable {
        try (Fixture f = new Fixture()) {
            List<String> effects = new java.util.ArrayList<>();
            Throwable raw = new Throwable("deferred advancement failure");
            f.pool.start();
            try {
                assertSame(raw, assertThrows(Throwable.class,
                        () -> RegionScheduler.runGlobalMainBatch(f.server, () -> {
                            RegionScheduler.runGlobalMainBody(f.server, () -> {
                                assertTrue(f.runtime.currentThreadHoldsAllClaims());
                                assertFalse(f.island.tryClaimAutonomousWork());
                                assertTrue(f.island.unitClaim.get());
                                effects.add("first");
                                AdvancementBuffer.deferDirect(() -> {
                                    assertSame(f.main, Thread.currentThread());
                                    assertTrue(f.runtime.currentThreadHoldsAllClaims());
                                    effects.add("advancement");
                                    RegionPreparationRoutedCommandTest.<RuntimeException>throwCursorFailure(raw);
                                });
                            });
                            assertEquals(List.of("first"), effects);
                            effects.add("before-second");
                            RegionScheduler.runGlobalMainBody(f.server, () -> effects.add("second"));
                            RegionScheduler.runGlobalMainBody(f.server, () -> effects.add("third"));
                        })));
                assertEquals(List.of("first", "before-second", "advancement"), effects);
                assertSame(raw, f.pool.fatalFailureOrNull());
                assertSame(raw, assertThrows(Throwable.class, RegionScheduler::throwWorkerFailureIfAny));
                assertNull(f.runtime.globalFenceIntentReceipt());
                assertFalse(f.runtime.currentThreadHoldsAllClaims());
                assertFalse(IslandCommands.fenceWindowHeld());
                assertFalse(f.island.unitClaim.get());
                assertFalse(f.island.claimWanted);
            } finally {
                Throwable fatal = f.pool.fatalFailureOrNull();
                if (fatal != null) IslandCommands.cancelFailedGeneration(fatal);
                try {
                    f.pool.stop();
                } catch (Throwable cleanup) {
                    if (cleanup != fatal) throw cleanup;
                }
            }
        }
    }

    @Test
    void nestedGlobalMainBatchesDoNotReleaseTheEnclosingRootsClaims() throws Throwable {
        try (Fixture f = new Fixture()) {
            List<String> effects = new java.util.ArrayList<>();
            f.pool.start();
            try {
                RegionScheduler.runGlobalMainBody(f.server, () -> {
                    effects.add("outer");
                    RegionScheduler.runGlobalMainBatch(f.server, () ->
                            RegionScheduler.runGlobalMainBatch(f.server, () ->
                                    RegionScheduler.runGlobalMainBody(f.server, () -> {
                                        assertTrue(f.runtime.currentThreadHoldsAllClaims());
                                        assertFalse(f.island.tryClaimAutonomousWork());
                                        assertTrue(f.island.unitClaim.get());
                                        effects.add("nested");
                                    })));
                    assertTrue(f.runtime.currentThreadHoldsAllClaims());
                    assertTrue(f.island.unitClaim.get());
                    effects.add("outer-tail");
                });
                assertEquals(List.of("outer", "nested", "outer-tail"), effects);
                assertNull(f.runtime.globalFenceIntentReceipt());
                assertFalse(f.runtime.currentThreadHoldsAllClaims());
                assertFalse(IslandCommands.fenceWindowHeld());
                assertFalse(f.island.unitClaim.get());
                assertFalse(f.island.claimWanted);
                assertTrue(f.island.tryClaimAutonomousWork());
                f.island.unitClaim.set(false);
            } finally {
                f.pool.stop();
            }
        }
    }

    @Test
    void scopedMainClaimInheritsTheWriterWithoutWaitingOrReleasingItsIsland() throws Throwable {
        exerciseScopedWriterClaim(false);
    }

    @Test
    void scopedMainClaimInheritsLocalQuiesceWithoutReleasingItsIsland() throws Throwable {
        exerciseScopedWriterClaim(true);
    }

    private static void exerciseScopedWriterClaim(boolean local) throws Throwable {
        try (Fixture f = new Fixture()) {
            List<String> effects = new java.util.ArrayList<>();
            // No worker can acquire this island. A broken nested claim reports
            // the retained flag immediately instead of self-waiting indefinitely.
            try {
                Runnable nested = () -> {
                    boolean release = IslandCommands.mainClaimScoped(f.island);
                    try {
                        assertFalse(release, "the enclosing writer owns the release obligation");
                        assertFalse(f.island.tryClaimAutonomousWork());
                        effects.add("nested");
                    } finally {
                        if (release) IslandCommands.mainRelease(f.island);
                    }
                    assertFalse(f.island.tryClaimAutonomousWork(),
                            "returning from the nested scope must preserve outer exclusion");
                    effects.add("outer-tail");
                };
                if (local) {
                    set(RegionizedServer.class, f.runtime, "quiescedIslands",
                            new java.util.ArrayList<>());
                    set(net.minecraft.world.level.Level.class, f.level, "dimension",
                            net.minecraft.world.level.Level.OVERWORLD);
                    assertTrue(f.runtime.quiesceAllIslands());
                    try {
                        nested.run();
                    } finally {
                        f.runtime.releaseAllIslands();
                    }
                } else {
                    RegionScheduler.runGlobalMainBody(f.server, nested);
                }
                assertEquals(List.of("nested", "outer-tail"), effects);
                assertTrue(f.island.tryClaimAutonomousWork());
                f.island.unitClaim.set(false);
            } finally {
                Throwable fatal = f.pool.fatalFailureOrNull();
                if (fatal != null) IslandCommands.cancelFailedGeneration(fatal);
                try {
                    f.pool.stop();
                } catch (Throwable cleanup) {
                    if (cleanup != fatal) throw cleanup;
                }
            }
        }
    }

    @Test
    void scopedMainClaimRejectsForeignEntryBeforeItsBody() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicReference<Throwable> rejected = new AtomicReference<>();
            AtomicBoolean entered = new AtomicBoolean();
            Thread foreign = new Thread(() -> {
                try {
                    boolean release = IslandCommands.mainClaimScoped(f.island);
                    try {
                        entered.set(true);
                    } finally {
                        if (release) IslandCommands.mainRelease(f.island);
                    }
                } catch (Throwable failure) {
                    rejected.set(failure);
                }
            }, "foreign-scoped-claim");
            foreign.start();
            foreign.join(2_000L);
            assertFalse(foreign.isAlive());
            assertInstanceOf(IllegalStateException.class, rejected.get());
            assertFalse(entered.get());
            assertFalse(f.island.unitClaim.get());
            assertTrue(f.island.tryClaimAutonomousWork());
            f.island.unitClaim.set(false);
        }
    }

    @Test
    void retiredOwnerPreservesAcceptedStopFifoAndLateCoalescedWork() throws Exception {
        try (Fixture f = new Fixture()) {
            f.pool.start();
            try {
                sun.misc.Unsafe unsafe = (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
                var nav = (net.minecraft.world.entity.ai.navigation.PathNavigation)
                        unsafe.allocateInstance(net.minecraft.world.entity.ai.navigation.GroundPathNavigation.class);
                var path = (net.minecraft.world.level.pathfinder.Path)
                        unsafe.allocateInstance(net.minecraft.world.level.pathfinder.Path.class);
                set(net.minecraft.world.entity.ai.navigation.PathNavigation.class, nav, "path", path);
                assertTrue(NavRecompute.stopDeferred(f.runtime, f.island, nav, nav::stop));
                List<String> order = new java.util.ArrayList<>();
                Object key = new Object();
                CommandShards.enqueueIslandTask(f.island, key, () -> {
                    assertNull(nav.getPath(), "the preceding accepted navigation stop must not disappear");
                    order.add("before");
                });
                CommandShards.enqueueIslandTask(f.island, key, () -> fail("duplicate coalesced task"));
                assertSame(path, nav.getPath());

                assertTrue(IslandCommands.quiesceAllExcept(null));
                try {
                    f.runtime.islandRegistry.applyRebuildCells(
                            List.of(new LongArrayList(new long[]{3L})), 1L);
                    assertTrue(f.island.retired);
                    CommandShards.enqueueIslandTask(f.island, null, () -> order.add("at retirement"));
                    CommandShards.finishTopology(f.runtime, List.of(f.island));
                } finally {
                    IslandCommands.releaseAllExcept();
                }
                assertEquals(List.of("before", "at retirement"), order);
                CommandShards.enqueueIslandTask(f.island, key, () -> {
                    assertSame(f.main, Thread.currentThread());
                    assertTrue(IslandCommands.fenceWindowHeld());
                    order.add("late");
                });
                CommandShards.enqueueIslandTask(f.island, key, () -> fail("late duplicate coalesced task"));
                assertEquals(List.of("before", "at retirement"), order,
                        "a retired-owner publisher must not enter the callback inline");
                for (TickTask task; (task = f.tasks.poll()) != null;) task.run();
                assertEquals(List.of("before", "at retirement", "late"), order);
                assertTrue(f.island.pendingIslandTaskKeys.isEmpty(),
                        "completion must release the original owner's coalescing receipt");
            } finally {
                IslandCommands.releaseAllExcept();
                f.pool.stop();
            }
        }
    }

    @Test
    void normalSchedulerStopFinishesALateRetiredTaskBeforeReturning() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.pool.start();
            f.island.retired = true;
            // This identity belongs to a topology which already collapsed; no
            // live world remains to manufacture a successor island unit for it.
            f.levels.remove(f.level, f.runtime);
            List<String> order = new java.util.ArrayList<>();
            assertTrue(f.pool.enqueueGlobalMainOnly(() -> {
                order.add("accepted producer");
                CommandShards.enqueueIslandTask(f.island, "late", () -> {
                    assertSame(f.main, Thread.currentThread());
                    order.add("retired task");
                });
                assertEquals(List.of("accepted producer"), order,
                        "publication must not execute the fenced body inline");
            }));
            withPoolDeadline(f, () -> stopFixtureScheduler(f));
            order.add("stop returned");
            assertEquals(List.of("accepted producer", "retired task", "stop returned"), order);
            assertTrue(f.pool.isTerminated());
            assertTrue(f.island.pendingIslandTaskKeys.isEmpty());
            assertFalse(IslandCommands.hasRoutedPending());
            for (TickTask task; (task = f.tasks.poll()) != null;) task.run();
            assertEquals(3, order.size(), "the queued liveness nudge cannot replay the completed task");
        }
    }

    @Test
    void canonicalPauseFinishesAnAcceptedRetiredTaskWithoutRunningItsRawNudge() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.pool.start();
            assertTrue(IslandCommands.quiesceAllExcept(null));
            try {
                f.runtime.islandRegistry.applyRebuildCells(
                        List.of(new LongArrayList(new long[]{3L})), 1L);
            } finally {
                IslandCommands.releaseAllExcept();
            }
            AtomicBoolean ran = new AtomicBoolean();
            CommandShards.enqueueIslandTask(f.island, null, () -> {
                assertSame(f.main, Thread.currentThread());
                assertTrue(IslandCommands.fenceWindowHeld());
                ran.set(true);
            });
            try {
                withPoolDeadline(f, () -> RegionScheduler.pauseExactPlayerTurn());
                assertTrue(ran.get(), "the accepted external obligation must settle before the pause");
                assertTrue(f.pool.ownsPausedExactTurn(f.main));
                assertFalse(IslandCommands.hasRoutedPending());
            } finally {
                if (f.pool.ownsPausedExactTurn(f.main)) RegionScheduler.resumeExactPlayerTurn();
                f.pool.stop();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preparationYieldsToNativeControlsWithoutStealingTheirFifo(boolean queuedBefore)
            throws Throwable {
        try (Fixture f = new Fixture()) {
            f.pool.start();
            List<String> effects = new java.util.ArrayList<>();
            f.server.schedule(new TickTask(f.server.getTickCount(),
                    () -> effects.add("native predecessor")));
            Runnable publish = () -> RegionScheduler.scheduleTrackedMainControlTask(
                    f.server, () -> {
                        assertFalse(f.pool.ownsPausedExactTurn(f.main));
                        effects.add("control");
                    });
            try {
                if (queuedBefore) publish.run();
                else assertTrue(f.pool.enqueueGlobalMainOnly(publish));
                assertDoesNotThrow(() -> withPoolDeadline(f,
                        () -> assertFalse(RegionScheduler.pauseMainPreparationTurn())));
                assertFalse(f.pool.ownsPausedExactTurn(f.main));
                assertEquals(List.of(), effects,
                        "preparation must unwind instead of polling a foreign native predecessor");
                for (TickTask task; (task = f.tasks.poll()) != null;) task.run();
                assertEquals(List.of("native predecessor", "control"), effects);
                assertTrue(RegionScheduler.pauseMainPreparationTurn());
                assertTrue(f.pool.ownsPausedExactTurn(f.main));
            } finally {
                if (f.pool.ownsPausedExactTurn(f.main)) RegionScheduler.resumeExactPlayerTurn();
                Throwable failure = f.pool.fatalFailureOrNull();
                if (failure != null) RegionScheduler.cancelTrackedMainControlTasks(f.pool, failure);
                f.pool.stop();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mandatoryExactTurnFinishesNativeControlsAndTheirCausalChildren(boolean queuedBefore)
            throws Throwable {
        try (Fixture f = new Fixture(ServerLevel.class, 1, true)) {
            // The mandatory V2 phase is enabled only outside autonomous island mode.
            f.runtime.asyncActive = false;
            f.pool.start();
            List<String> effects = new java.util.ArrayList<>();
            f.server.schedule(new TickTask(f.server.getTickCount(),
                    () -> RegionScheduler.runGlobalMainBody(f.server, () -> {
                        assertTrue(RegionScheduler.ownsExclusiveGameplay());
                        effects.add("native predecessor");
                    })));
            Runnable publish = () -> RegionScheduler.scheduleTrackedMainControlTask(
                    f.server, () -> {
                        assertFalse(f.pool.ownsPausedExactTurn(f.main));
                        effects.add("control");
                        RegionScheduler.scheduleTrackedMainControlTask(f.server,
                                () -> effects.add("child"));
                    });
            try {
                if (queuedBefore) publish.run();
                else assertTrue(f.pool.enqueueGlobalMainOnly(publish));
                assertDoesNotThrow(() -> withPoolDeadline(f, RegionScheduler::pauseExactPlayerTurn));
                assertTrue(f.pool.ownsPausedExactTurn(f.main));
                assertEquals(List.of("native predecessor", "control", "child"), effects);
                assertNull(f.pool.fatalFailureOrNull());
            } finally {
                if (f.pool.ownsPausedExactTurn(f.main)) RegionScheduler.resumeExactPlayerTurn();
                Throwable failure = f.pool.fatalFailureOrNull();
                if (failure != null) RegionScheduler.cancelTrackedMainControlTasks(f.pool, failure);
                f.pool.stop();
            }
        }
    }

    @Test
    void fatalStopCancelsRetiredContinuationAndItsNudgeCannotEnterTheNextGeneration() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.pool.start();
            f.island.retired = true;
            f.levels.remove(f.level, f.runtime);
            List<String> effects = new java.util.ArrayList<>();
            CommandShards.enqueueIslandTask(f.island, "failed", () -> effects.add("failed generation"));
            TickTask oldNudge = f.tasks.remove();
            IllegalStateException primary = new IllegalStateException("retired continuation failure cut");
            f.pool.publishFatalFailure(primary);
            withPoolDeadline(f, () -> assertSame(primary,
                    assertThrows(IllegalStateException.class, () -> stopFixtureScheduler(f))));
            assertTrue(f.pool.isTerminated());
            assertFalse(IslandCommands.hasRoutedPending());
            assertEquals(List.of(), effects);
            assertTrue(f.island.pendingIslandTasks.isEmpty());
            assertTrue(f.island.pendingIslandTaskKeys.isEmpty());

            RegionWorkerPool next = new RegionWorkerPool(1);
            next.start();
            set(RegionScheduler.class, null, "pool", next);
            set(RegionScheduler.class, null, "orphanFatalFailure", null);
            try {
                IslandCommands.enqueueFencedBody(() -> {
                    effects.add("next generation");
                });
                oldNudge.run();
                assertEquals(List.of(), effects,
                        "an old terminal receipt cannot drain even a fresh generation's commands");
                assertTrue(IslandCommands.hasRoutedPending());
                f.tasks.remove().run();
                assertEquals(List.of("next generation"), effects);
            } finally {
                next.stop();
            }
        }
    }

    private static void withPoolDeadline(Fixture f,
                                         org.junit.jupiter.api.function.Executable body) throws Throwable {
        CountDownLatch done = new CountDownLatch(1);
        AssertionError timeout = new AssertionError("pool scenario did not reach its terminal cut");
        Thread watchdog = new Thread(() -> {
            try {
                if (!done.await(3, TimeUnit.SECONDS)) f.pool.publishFatalFailure(timeout);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "pool-scenario-watchdog");
        watchdog.start();
        try {
            body.execute();
            assertNotSame(timeout, f.pool.fatalFailureOrNull());
        } finally {
            done.countDown();
            watchdog.join();
        }
    }

    private static void stopFixtureScheduler(Fixture f) throws Exception {
        Object previousPortalServer = get(NetherPortalTransfers.class, null, "server");
        Map<String, Object> previous = new java.util.LinkedHashMap<>();
        for (String name : List.of("lifecycleState", "lifecycleOwner", "accepting",
                "transitionPool", "grid", "mainCurrentLevel", "asyncLive",
                "pool", "active", "server", "orphanFatalFailure")) {
            previous.put(name, get(RegionScheduler.class, null, name));
        }
        try {
            NetherPortalTransfers.init(f.server);
            Object running = java.util.Arrays.stream(previous.get("lifecycleState").getClass()
                            .getEnumConstants())
                    .filter(value -> ((Enum<?>) value).name().equals("RUNNING"))
                    .findFirst().orElseThrow();
            set(RegionScheduler.class, null, "lifecycleState", running);
            set(RegionScheduler.class, null, "lifecycleOwner", null);
            set(RegionScheduler.class, null, "accepting", true);
            RegionScheduler.stop();
        } finally {
            for (var entry : previous.entrySet()) {
                set(RegionScheduler.class, null, entry.getKey(), entry.getValue());
            }
            set(NetherPortalTransfers.class, null, "server", previousPortalServer);
        }
    }

    @Test
    void raidSubscriptionsFollowFreshOwnershipBeforeTheNextMainRaidTick() throws Exception {
        try (Fixture f = new Fixture(CommandLevel.class)) {
            ((CommandLevel) f.level).rules = new GameRules(FeatureFlagSet.of());
            f.pool.start();
            try {
                int cellBlocks = 1 << (f.runtime.regionizer().grid.shift + 4);
                BlockPos center = new BlockPos(3 * cellBlocks, 64, 0);
                TestRaid live = new TestRaid(center, false);
                TestRaid stopped = new TestRaid(center, true);
                RaidIslandDispatch.assign(f.island, live);
                RaidIslandDispatch.assign(f.island, stopped);
                assertTrue(IslandCommands.quiesceAllExcept(null));
                try {
                    f.runtime.islandRegistry.applyRebuildCells(
                            List.of(new LongArrayList(new long[]{3L})), 1L);
                    IslandRegistry.PIsland next = f.runtime.islandRegistry.byCell(3L);
                    f.runtime.regionizer().regionForGridKey(3L).domain = next.domain;
                    RaidIslandDispatch.reassignAfterTopology(f.runtime, List.of(f.island));
                    RaidIslandDispatch.service(f.runtime, f.island, f.level);
                    assertEquals(0, live.ticks);
                    RaidIslandDispatch.service(f.runtime, next, f.level);
                    assertEquals(1, live.ticks);
                    assertEquals(0, stopped.ticks);
                } finally {
                    IslandCommands.releaseAllExcept();
                }
            } finally {
                f.pool.stop();
            }
        }
    }

    private static final class TestRaid extends net.minecraft.world.entity.raid.Raid
            implements RaidOwnerAccess {
        private final View view;
        int ticks;

        TestRaid(BlockPos center, boolean stopped) {
            super(center, net.minecraft.world.Difficulty.NORMAL);
            view = new View(center, true, stopped);
        }

        @Override
        public View fabrolia$raidView() { return view; }

        @Override
        public boolean fabrolia$absorbOmenIfEligible(net.minecraft.server.level.ServerPlayer player,
                                                    net.minecraft.world.effect.MobEffectInstance effect) {
            throw new UnsupportedOperationException("omen mutation is outside this scheduling fixture");
        }

        @Override
        public void tick(ServerLevel level) { ticks++; }
    }

    @Test
    void selectorCommandsKeepTheVanillaCommandBlockEnvelope() throws Exception {
        try (Fixture f = new Fixture(CommandLevel.class)) {
            CommandLevel level = (CommandLevel) f.level;
            level.rules = new GameRules(FeatureFlagSet.of());
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
            Entity target = (Entity) unsafe.allocateInstance(ArmorStand.class);
            set(Entity.class, target, "tags", new HashSet<String>());
            set(Entity.class, target, "level", level);
            set(Entity.class, target, "position", Vec3.ZERO);
            Commands commands = (Commands) unsafe.allocateInstance(Commands.class);
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            TagCommand.register(dispatcher);
            dispatcher.setConsumer(ExecutionCommandSource.resultConsumer());
            set(Commands.class, commands, "dispatcher", dispatcher);
            ReloadableServerResources managers = PreparedAdvancementTestResources.install(f.server);
            set(ReloadableServerResources.class, managers, "commands", commands);

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            f.pool.start();
            try {
                assertTrue(f.pool.enqueueUnit(() -> {
                    RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                    boolean claimed = false;
                    try {
                        assertTrue(f.island.tryClaimAutonomousWork());
                        claimed = true;
                        f.island.noteClaim("command-envelope-regression");
                        worker.currentDomain = f.island.domain;
                        CommandBlock disabled = new CommandBlock(target, "tag @s add disabled");
                        disabled.setSuccessCount(7);
                        assertTrue(executeBlock(f, disabled));
                        assertFalse(target.entityTags().contains("disabled"));
                        assertEquals(0, disabled.getSuccessCount());
                        assertFalse(executeBlock(f, disabled),
                                "the same command block cannot execute twice in one tick");
                        assertTrue(executeBlock(f,
                                new CommandBlock(target, "effect give @s minecraft:speed")));

                        level.commandBlocksEnabled = true;
                        CommandBlock noOp = new CommandBlock(target, "tag @s remove missing");
                        assertTrue(executeBlock(f, noOp),
                                "valid no-op must allow the next unconditional chain block");
                        assertEquals(0, noOp.getSuccessCount());
                        CommandBlock next = new CommandBlock(target, "tag @s add reached");
                        assertTrue(executeBlock(f, next));
                        assertTrue(target.entityTags().contains("reached"));
                        assertEquals(1, next.getSuccessCount(),
                                "vanilla success callbacks must update comparator/conditional state");
                        assertFalse(executeBlock(f, next));
                    } catch (Throwable problem) {
                        failure.set(problem);
                    } finally {
                        worker.currentDomain = null;
                        if (claimed) {
                            f.island.clearClaim();
                            f.island.unitClaim.set(false);
                        }
                        done.countDown();
                    }
                }));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!done.await(1, TimeUnit.MILLISECONDS)) {
                    for (TickTask task; (task = f.tasks.poll()) != null;) task.run();
                    assertTrue(System.nanoTime() < deadline, "command envelope route did not complete");
                }
                assertNull(failure.get(), () -> "command envelope failed: " + failure.get());
            } finally {
                if (done.getCount() != 0) {
                    Throwable cancelled = new AssertionError("command envelope fixture cancelled");
                    f.pool.publishFatalFailure(cancelled);
                    IslandCommands.cancelFailedGeneration(cancelled);
                }
                f.pool.stop();
            }
        }
    }

    private static boolean executeBlock(Fixture fixture, CommandBlock block) {
        CommandSourceStack source = block.createCommandSourceStack(fixture.level, CommandSource.NULL);
        return IslandCommands.routeCommandBlock(block.getCommand(), fixture.level,
                BlockPos.ZERO, source, () -> {
                    assertSame(fixture.main, Thread.currentThread());
                    return block.performCommand(fixture.level);
                });
    }

    private static final class CommandBlock extends BaseCommandBlock {
        private final Entity target;

        CommandBlock(Entity target, String command) {
            this.target = target;
            setCommand(command);
            setTrackOutput(false);
        }

        @Override
        public void onUpdated(ServerLevel level) {}

        @Override
        public boolean isValid() { return true; }

        @Override
        public CommandSourceStack createCommandSourceStack(ServerLevel level, CommandSource source) {
            return new CommandSourceStack(source, Vec3.ZERO, Vec2.ZERO, level,
                    PermissionSet.ALL_PERMISSIONS, "fixture", getName(), level.getServer(), target);
        }
    }

    /** Only environmental inputs are substituted; command parsing/execution stays vanilla. */
    private static final class CommandLevel extends ServerLevel {
        GameRules rules;
        boolean commandBlocksEnabled;

        private CommandLevel() {
            super(null, null, null, null, null, null, false, 0L, List.of(), false);
            throw new AssertionError("allocated without starting a world");
        }

        @Override
        public long getGameTime() { return 10L; }

        @Override
        public boolean isCommandBlockEnabled() { return commandBlocksEnabled; }

        @Override
        public GameRules getGameRules() { return rules; }
    }

    private static void exercise(boolean queuedBefore, boolean commandResult,
                                 RuntimeException commandFailure) throws Exception {
        try (Fixture f = new Fixture()) {
            CountDownLatch workerEntered = new CountDownLatch(1);
            CountDownLatch publishCommand = new CountDownLatch(1);
            CountDownLatch workerDone = new CountDownLatch(1);
            CountDownLatch scenarioDone = new CountDownLatch(1);
            AtomicReference<Boolean> result = new AtomicReference<>();
            AtomicReference<Throwable> workerFailure = new AtomicReference<>();
            AtomicReference<Throwable> mainFailure = new AtomicReference<>();
            AtomicBoolean prepared = new AtomicBoolean();
            AtomicBoolean timedOut = new AtomicBoolean();
            List<String> effects = new CopyOnWriteArrayList<>();
            RegionizedTaskQueue nextUnit = new RegionizedTaskQueue();
            AssertionError timeout = new AssertionError(
                    "Main preparation waited for an entered worker whose real routed receipt requires Main");
            Thread watchdog = new Thread(() -> {
                try {
                    if (!scenarioDone.await(5, TimeUnit.SECONDS)) {
                        timedOut.set(true);
                        f.pool.publishFatalFailure(timeout);
                        publishCommand.countDown();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }, "preparation-route-watchdog");
            watchdog.setDaemon(true);
            f.pool.start();
            watchdog.start();
            try {
                assertTrue(f.pool.enqueueUnit(() -> {
                    RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                    boolean claimed = false;
                    try {
                        assertTrue(f.island.tryClaimAutonomousWork());
                        claimed = true;
                        f.island.noteClaim("preparation-route-regression");
                        worker.currentDomain = f.island.domain;
                        workerEntered.countDown();
                        await(publishCommand, "worker publication was never released");
                        String command = queuedBefore ? "scoreboard objectives list" : "say preparation regression";
                        result.set(IslandCommands.routeCommandBlock(command, f.level,
                                BlockPos.ZERO, null, () -> {
                                    assertSame(Thread.currentThread(), f.main,
                                            "the original nonlocal command must execute on physical Main");
                                    assertTrue(f.pool.mayServiceDetachedTransfers(),
                                            "command execution requires the preparation pause intent to unwind");
                                    assertTrue(f.runtime.currentThreadHoldsAllClaims());
                                    assertTrue(IslandCommands.fenceWindowHeld());
                                    assertFalse(prepared.get(), "preparation overtook an entered command");
                                    effects.add("command");
                                    if (commandFailure != null) throw commandFailure;
                                    return commandResult;
                                }));
                        effects.add("receipt");
                    } catch (Throwable failure) {
                        workerFailure.set(failure);
                    } finally {
                        worker.currentDomain = null;
                        if (claimed) {
                            f.island.clearClaim();
                            f.island.unitClaim.set(false);
                        }
                        workerDone.countDown();
                    }
                }));
                await(workerEntered, "the actual pool step never entered");
                nextUnit.submit(regionalTask(f.pool, () -> effects.add("next-first")));
                nextUnit.submit(regionalTask(f.pool, () -> effects.add("next-second")));
                if (queuedBefore) {
                    publishCommand.countDown();
                    await(f.published, "the real Main receipt was not queued before admission");
                } else {
                    // The sole worker is inside its unit. Only pause progress can consume
                    // this Main-only root, so publication happens strictly after request.
                    assertTrue(f.pool.enqueueGlobalMainOnly(() -> {
                        assertFalse(f.pool.mayServiceDetachedTransfers(),
                                "late-publication latch must run inside the pause request");
                        publishCommand.countDown();
                        await(f.published, "the worker did not publish its causal Main receipt");
                    }));
                }
                assertTrue(RegionalPlayerEntityTicksV2.schedulerLifecycleTransitionAllowed(f.server),
                        "fixture Main must be at a physically completed server-tick boundary");
                if (!RegionScheduler.pauseMainPreparationTurn()) {
                    assertFalse(f.pool.ownsPausedExactTurn(Thread.currentThread()));
                    // A deferred attempt must return all ownership. Only the ordinary
                    // outer Main turn, never the pause progress hook, drains this queue.
                    assertTrue(f.pool.mayServiceDetachedTransfers());
                    for (TickTask task; (task = f.tasks.poll()) != null;) task.run();
                    await(workerDone, "routed command failed to release its entered worker");
                    assertTrue(RegionScheduler.pauseMainPreparationTurn());
                }
                assertTrue(f.pool.ownsPausedExactTurn(Thread.currentThread()));
                assertEquals(0L, workerDone.getCount(),
                        "preparation may proceed only after the command and entered unit finish");
                assertEquals(List.of("command", "receipt"), effects,
                        "preparation must retain queued next-unit regional mutations");
                prepared.set(true);
                effects.add("preparation");
                RegionScheduler.resumeExactPlayerTurn();
                nextUnit.drain(Integer.MAX_VALUE);
            } catch (Throwable failure) {
                mainFailure.set(failure);
            } finally {
                publishCommand.countDown();
                if (f.pool.ownsPausedExactTurn(Thread.currentThread())) {
                    try {
                        RegionScheduler.resumeExactPlayerTurn();
                    } catch (Throwable cleanup) {
                        mainFailure.compareAndSet(null, cleanup);
                    }
                }
                if (mainFailure.get() != null) {
                    f.pool.publishFatalFailure(mainFailure.get());
                    mainFailure.set(IslandCommands.cancelFailedGeneration(mainFailure.get()));
                }
                // Cancel retained receipts without entering their future-unit bodies.
                for (Runnable task; (task = nextUnit.poll()) != null;) {
                    ((RegionTaskFanIn.CancellableQueueEntry) task).cancelQueuedBeforeStart();
                }
                scenarioDone.countDown();
                watchdog.join(2_000L);
                await(workerDone, "fail-stop cleanup did not release the actual pool worker");
                try {
                    f.pool.stop();
                } catch (Throwable cleanup) {
                    if (cleanup != mainFailure.get()) throw new AssertionError("unexpected pool cleanup failure", cleanup);
                }
            }
            assertFalse(timedOut.get(), timeout.getMessage());
            assertFalse(IslandCommands.hasRoutedPending(), "the routed receipt must reach a terminal state");
            if (commandFailure != null) {
                assertSame(commandFailure, mainFailure.get());
                assertSame(commandFailure, workerFailure.get());
                assertNull(result.get(), "a failed command cannot manufacture a boolean result");
                assertFalse(prepared.get());
                assertEquals(List.of("command"), effects);
            } else {
                assertNull(mainFailure.get(), "physical Main failed before preparation completed");
                assertNull(workerFailure.get(), "the routed worker must complete normally");
                assertEquals(Boolean.valueOf(commandResult), result.get());
                assertEquals(List.of("command", "receipt", "preparation", "next-first", "next-second"), effects);
            }
        }
    }

    /** Replaces Mixin storage/native packet emission, not publication routing. */
    private static final class SoftTrackingProof implements AutoCloseable {
        final Fixture fixture;
        final AsyncIslandPlayerTicks.PlayerState state = new AsyncIslandPlayerTicks.PlayerState();
        final SoftTrackingPlayer player;
        final TrackingListener listener;
        final AsyncIslandPlayerTicks.PendingLate pending;
        final TestPlayerTracker tracker;
        final ThreadLocal<Object> turn;
        final Object context;

        @SuppressWarnings("unchecked")
        SoftTrackingProof(Fixture fixture) throws Exception {
            this.fixture = fixture;
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
            player = (SoftTrackingPlayer) unsafe.allocateInstance(SoftTrackingPlayer.class);
            player.state = state;
            player.localLevel = fixture.level;
            listener = (TrackingListener) unsafe.allocateInstance(TrackingListener.class);
            listener.state = state;
            listener.player = player;
            player.connection = listener;
            state.listenerGate = listener;
            state.eligible = true;
            set(Entity.class, player, "passengers", com.google.common.collect.ImmutableList.of());
            set(Entity.class, player, "chunkPosition", new net.minecraft.world.level.ChunkPos(0, 0));
            set(Entity.class, player, "blockPosition", BlockPos.ZERO);
            var island = fixture.island;
            var owner = new IslandPlayerTurnSession.OwnerStamp(player, listener, island,
                    island.id, island.gen, island.domain, 0L, 1L,
                    IslandPlayerTurnSession.PlayerKind.REAL);
            assertTrue(state.session.attach(owner));
            var permit = state.session.tryReserveEarly(owner, 73L).orElseThrow();
            assertTrue(state.session.enterIslandBody(permit));
            assertTrue(state.session.completeRealEarly(permit));
            pending = new AsyncIslandPlayerTicks.PendingLate(state, player, listener, owner, permit);
            island.pendingPlayerLate.add(pending);
            tracker = new TestPlayerTracker(player, island);
            IslandEntityTracking.registerPlayerTracker(tracker);
            synchronized (tracker) {
                synchronized (state) {
                    island.pendingEntityUpdates.add(tracker);
                    state.trackingPublication.admit(tracker, null, owner, island);
                }
            }
            turn = (ThreadLocal<Object>) get(AsyncIslandPlayerTicks.class, null, "TURN");
            Class<?> type = Class.forName(AsyncIslandPlayerTicks.class.getName() + "$TurnContext");
            var constructor = type.getDeclaredConstructor(AsyncIslandPlayerTicks.PlayerState.class,
                    net.minecraft.server.level.ServerPlayer.class, IslandRegistry.PIsland.class,
                    IslandPlayerTurnSession.OwnerStamp.class,
                    IslandPlayerTurnSession.EarlyPermit.class, boolean.class);
            constructor.setAccessible(true);
            context = constructor.newInstance(state, player, island, owner, permit, true);
        }

        void move(net.minecraft.world.level.ChunkPos destination,
                  IslandRegistry.PIsland cadence) throws Exception {
            var token = AsyncIslandPlayerTicks.prepareLifecycleMutationBody(player);
            AsyncIslandPlayerTicks.beginLifecycleMutationBody(token);
            Throwable failure = null;
            try {
                AsyncIslandPlayerTicks.invalidateForLifecycleMutation(player,
                        RegionalPlayerEntityTicksV2.LifecycleMutationKind.SOFT_HANDOFF);
                player.fabrolia$bumpPlayerLifecycleEpoch();
                set(Entity.class, player, "chunkPosition", destination);
                set(Entity.class, player, "blockPosition",
                        new BlockPos(destination.x() * 16, 64, destination.z() * 16));
                if (cadence != tracker.owner) {
                    synchronized (tracker) {
                        synchronized (state) {
                            var prior = tracker.owner;
                            prior.pendingEntityUpdates.remove(tracker);
                            tracker.owner = cadence;
                            cadence.pendingEntityUpdates.add(tracker);
                            state.trackingPublication.carry(tracker, pending.owner, prior, cadence);
                        }
                    }
                }
            } catch (Throwable thrown) {
                failure = thrown;
            } finally {
                failure = AsyncIslandPlayerTicks.finishLifecycleMutationBody(token, failure);
                failure = AsyncIslandPlayerTicks.finishLifecycleHandoffPublications(List.of(token), 1, failure);
            }
            if (failure != null) throw new AssertionError(failure);
        }

        void enterLate() throws Exception {
            assertTrue(state.session.beginRealLate(pending.permit));
            assertSame(pending, fixture.island.pendingPlayerLate.remove());
            set(context.getClass(), context, "softLate", get(state.getClass(), state, "softLate"));
            set(state.getClass(), state, "softLate", null);
            turn.set(context);
        }

        void assertRejected() {
            assertEquals(IslandEntityTracking.PostPhysicsResult.INVALID,
                    IslandEntityTracking.publishPendingRealPlayerAfterSoftLifecycleOnMain(
                            pending, fixture.island));
            assertEquals(0, tracker.publications);
            assertTrue(tracker.pairingsPending);
            assertNull(state.trackingTopologyGap);
        }

        void finishHandoff() {
            turn.remove();
            assertTrue(state.session.completeRealLate(pending.permit));
            var handoff = state.session.beginMainHandoff(pending.owner).orElseThrow();
            assertTrue(state.session.completeMainHandoff(handoff).orElseThrow().isEmpty());
            assertTrue(state.session.finishMainHandoff(handoff));
            state.trackingPublication.clear();
        }

        @Override
        public void close() {
            turn.remove();
            fixture.island.pendingPlayerLate.remove(pending);
            IslandEntityTracking.retire(tracker);
        }
    }

    private static final class SoftTrackingPlayer extends net.minecraft.server.level.ServerPlayer
            implements AsyncIslandPlayerAccess, PlayerTickPhaseStamp {
        AsyncIslandPlayerTicks.PlayerState state;
        ServerLevel localLevel;
        long lifecycle;
        private SoftTrackingPlayer() { super(null, null, null, null); }
        @Override public AsyncIslandPlayerTicks.PlayerState fabrolia$asyncIslandPlayerState() { return state; }
        @Override public ServerLevel level() { return localLevel; }
        @Override public long fabrolia$playerLifecycleEpoch() { return lifecycle; }
        @Override public void fabrolia$bumpPlayerLifecycleEpoch() { lifecycle++; }
    }


    /**
     * Exercises the real post-physics containment API without inventing native
     * death state. The lifecycle marker represents an already-entered hard cut;
     * native die/listener.tick still requires the transformed runtime scenario.
     */
    private static final class LateTrackingProof {
        final AsyncIslandPlayerTicks.PlayerState state = new AsyncIslandPlayerTicks.PlayerState();
        final AsyncIslandPlayerTicks.PendingLate pending;
        final IslandRegistry.PIsland island;
        final TrackingListener listener;
        final ThreadLocal<Object> turn;
        final Object context;

        @SuppressWarnings("unchecked")
        LateTrackingProof(net.minecraft.server.level.ServerPlayer player,
                          IslandRegistry.PIsland island) throws Exception {
            this.island = island;
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
            listener = (TrackingListener) unsafe.allocateInstance(TrackingListener.class);
            listener.state = state;
            listener.player = player;
            player.connection = listener;
            state.listenerGate = listener;
            state.hardLifecycleExitPending = true;
            var owner = new IslandPlayerTurnSession.OwnerStamp(player, listener, island,
                    island.id, island.gen, island.domain, 0L, 1L,
                    IslandPlayerTurnSession.PlayerKind.REAL);
            assertTrue(state.session.attach(owner));
            var permit = state.session.tryReserveEarly(owner, 1L).orElseThrow();
            assertTrue(state.session.enterIslandBody(permit));
            assertTrue(state.session.completeRealEarly(permit));
            assertTrue(state.session.beginRealLate(permit));
            pending = new AsyncIslandPlayerTicks.PendingLate(state, player, listener, owner, permit);
            turn = (ThreadLocal<Object>) get(AsyncIslandPlayerTicks.class, null, "TURN");
            Class<?> contextType = Class.forName(AsyncIslandPlayerTicks.class.getName() + "$TurnContext");
            var constructor = contextType.getDeclaredConstructor(
                    AsyncIslandPlayerTicks.PlayerState.class, net.minecraft.server.level.ServerPlayer.class,
                    IslandRegistry.PIsland.class, IslandPlayerTurnSession.OwnerStamp.class,
                    IslandPlayerTurnSession.EarlyPermit.class, boolean.class);
            constructor.setAccessible(true);
            context = constructor.newInstance(state, player, island, owner, permit, true);
        }

        void assertPublication(IslandEntityTracking.PostPhysicsResult expected) {
            assertNull(turn.get());
            assertEquals(IslandEntityTracking.PostPhysicsResult.INVALID,
                    IslandEntityTracking.publishPendingRealPlayerAfterPhysics(pending, island),
                    "even the typed Main rendezvous requires its exact active TURN");
            turn.set(context);
            try {
                assertEquals(expected,
                        IslandEntityTracking.publishPendingRealPlayerAfterPhysics(pending, island));
                assertTrue(state.session.isRunningRealLate(pending.permit),
                        "tracking must not consume the original listener permit");
            } finally {
                turn.remove();
            }
        }

        void assertListenerReplacementRejected() {
            listener.state = new AsyncIslandPlayerTicks.PlayerState();
            try {
                assertPublication(IslandEntityTracking.PostPhysicsResult.INVALID);
            } finally {
                listener.state = state;
            }
            assertPublication(IslandEntityTracking.PostPhysicsResult.HANDOFF);
            // Hard-cut containment deliberately authenticates the retained
            // listener, not the mutable connection now installed on the player.
            pending.player.connection = null;
            try {
                assertPublication(IslandEntityTracking.PostPhysicsResult.HANDOFF);
            } finally {
                pending.player.connection = listener;
            }
        }
    }

    private static final class TrackingListener
            extends net.minecraft.server.network.ServerGamePacketListenerImpl
            implements AsyncIslandListenerAccess {
        AsyncIslandPlayerTicks.PlayerState state;

        private TrackingListener() { super(null, null, null, null); }

        @Override
        public AsyncIslandPlayerTicks.PlayerState fabrolia$asyncIslandPlayerState() { return state; }

        @Override
        public boolean fabrolia$bindAsyncIslandPlayerState(AsyncIslandPlayerTicks.PlayerState candidate) {
            if (state != null) return state == candidate;
            state = candidate;
            return true;
        }

        @Override
        public boolean fabrolia$unbindAsyncIslandPlayerState(AsyncIslandPlayerTicks.PlayerState expected) {
            if (state != expected) return false;
            state = null;
            return true;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Thread main = Thread.currentThread();
        final RegionWorkerPool pool;
        final CountDownLatch published = new CountDownLatch(1);
        final ConcurrentLinkedQueue<TickTask> tasks = new ConcurrentLinkedQueue<>() {
            @Override
            public boolean add(TickTask task) {
                boolean added = super.add(task);
                published.countDown();
                return added;
            }
        };
        final MinecraftServer server;
        final ServerLevel level;
        final RegionizedServer runtime;
        final IslandRegistry.PIsland island;
        final Map<ServerLevel, RegionizedServer> levels;
        final Object previousPool = get(RegionScheduler.class, null, "pool");
        final Object previousActive = get(RegionScheduler.class, null, "active");
        final Object previousFailure = get(RegionScheduler.class, null, "orphanFatalFailure");
        final Object previousServer = get(ParallelProcessor.class, null, "server");
        final Object previousSchedulerServer = get(RegionScheduler.class, null, "server");
        final boolean previousThreading = FabroliaConfig.enableRegionThreading;

        Fixture() throws Exception {
            this(ServerLevel.class);
        }

        @SuppressWarnings("unchecked")
        Fixture(Class<? extends ServerLevel> levelClass) throws Exception {
            this(levelClass, 1);
        }

        Fixture(Class<? extends ServerLevel> levelClass, int workers) throws Exception {
            this(levelClass, workers, false);
        }

        @SuppressWarnings("unchecked")
        Fixture(Class<? extends ServerLevel> levelClass, int workers, boolean serverBound) throws Exception {
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
            server = (MinecraftServer) unsafe.allocateInstance(com.axalotl.fabrolia.common.regions.TestDedicatedServer.class);
            pool = serverBound ? new RegionWorkerPool(workers, server) : new RegionWorkerPool(workers);
            set(MinecraftServer.class, server, "tickRateManager",
                    new net.minecraft.server.ServerTickRateManager(server));
            PreparedAdvancementTestResources.install(server);
            level = (ServerLevel) unsafe.allocateInstance(levelClass);
            runtime = (RegionizedServer) unsafe.allocateInstance(RegionizedServer.class);
            set(MinecraftServer.class, server, "serverThread", main);
            // There are no chunk-main queues/world ticks in this focused fixture.
            set(MinecraftServer.class, server, "levels", Map.of());
            set(BlockableEventLoop.class, server, "pendingRunnables", tasks);
            set(ServerLevel.class, level, "server", server);
            set(RegionizedServer.class, runtime, "level", level);
            set(RegionizedServer.class, runtime, "regionizer",
                    new ThreadedRegionizer(level, new RegionGrid(RegionGrid.DEFAULT_SHIFT)));
            IslandRegistry registry = new IslandRegistry();
            registry.applyRebuildCells(List.of(new LongArrayList(new long[]{0L})), 0L);
            island = registry.byCell(0L);
            island.level = level;
            set(RegionizedServer.class, runtime, "islandRegistry", registry);
            runtime.asyncActive = true;
            levels = (Map<ServerLevel, RegionizedServer>) get(RegionScheduler.class, null, "PER_LEVEL");
            levels.put(level, runtime);
            set(ParallelProcessor.class, null, "server", server);
            set(RegionScheduler.class, null, "server", server);
            set(RegionScheduler.class, null, "pool", pool);
            set(RegionScheduler.class, null, "active", true);
            set(RegionScheduler.class, null, "orphanFatalFailure", null);
            // Keep the unrelated color-heal drive inert, as in SprintRoutedCommandPumpTest.
            FabroliaConfig.enableRegionThreading = false;
        }

        @Override
        public void close() throws Exception {
            try {
                IslandCommands.releaseAllExcept();
                if (pool.isRunning() && !pool.isTerminated()) {
                    Throwable failure = pool.fatalFailureOrNull();
                    if (failure == null) pool.stop();
                    else assertSame(failure, assertThrows(Throwable.class, pool::stop));
                }
            } finally {
                tasks.clear();
                levels.remove(level, runtime);
                set(ParallelProcessor.class, null, "server", previousServer);
                set(RegionScheduler.class, null, "server", previousSchedulerServer);
                set(RegionScheduler.class, null, "pool", previousPool);
                set(RegionScheduler.class, null, "active", previousActive);
                set(RegionScheduler.class, null, "orphanFatalFailure", previousFailure);
                FabroliaConfig.enableRegionThreading = previousThreading;
            }
        }
    }

    private static Runnable regionalTask(RegionWorkerPool pool, Runnable body) throws Exception {
        Method reserve = RegionWorkerPool.class.getDeclaredMethod("reserveExternalTask", Runnable.class);
        reserve.setAccessible(true);
        return (Runnable) reserve.invoke(pool, body);
    }

    private static void await(CountDownLatch latch, String message) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS), message);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static Object get(Class<?> owner, Object target, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
