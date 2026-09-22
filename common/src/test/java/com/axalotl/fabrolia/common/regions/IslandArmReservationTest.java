package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact accounting for island chunk arms which cross an entity-plan cutoff. */
class IslandArmReservationTest {

    @Test
    void sameGameTimeMayReserveManyButLaterTimeWaitsForTerminalAndConsumption() {
        IslandRegistry.PIsland island = new IslandRegistry.PIsland(1L, 0L);

        IslandRegistry.ArmGate first = island.reserveChunkArm(40L);
        IslandRegistry.ArmGate second = island.reserveChunkArm(40L);
        assertTrue(first.isIslandArm());
        assertSame(first, second, "one island token must be reused without per-arm allocation");
        assertEquals(2, island.chunkArmReservations());
        assertTrue(island.reserveChunkArm(41L).isSkipped(),
                "a later physical tick stacked over live arm tasks");

        island.finishChunkArm();
        island.consumeChunkArmIfQuiescent();
        assertEquals(0L, island.armConsumedGameTime,
                "the unit consumed an arm while one accepted task was still live");
        assertTrue(island.reserveChunkArm(41L).isSkipped());

        island.finishChunkArm();
        assertEquals(0, island.chunkArmReservations());
        assertTrue(island.reserveChunkArm(41L).isSkipped(),
                "task terminal alone must not masquerade as unit consumption");
        island.consumeChunkArmIfQuiescent();
        assertEquals(40L, island.armConsumedGameTime);
        assertTrue(island.reserveChunkArm(41L).isIslandArm());
    }

    @Test
    void sameGameTimeRearmAfterPriorConsumptionCreatesFreshUnitDebt() {
        IslandRegistry.PIsland island = new IslandRegistry.PIsland(6L, 0L);

        // U1 consumes the first gt=40 cohort completely.
        assertTrue(island.reserveChunkArm(40L).isIslandArm());
        island.finishChunkArm();
        island.consumeChunkArmIfQuiescent();
        assertEquals(40L, island.armConsumedGameTime);
        assertEquals(island.armReservationGeneration, island.armConsumedGeneration);

        // A second vanilla phase of the SAME physical tick arrives after U1.
        // Its task can terminalize before U2 reaches its success commit; the
        // generation debt, not pending count or the equal stamp pair, must keep
        // gt=41 closed throughout that window.
        assertTrue(island.reserveChunkArm(40L).isIslandArm());
        island.finishChunkArm();
        assertEquals(0, island.chunkArmReservations());
        assertTrue(island.armReservationGeneration > island.armConsumedGeneration);
        assertTrue(island.reserveChunkArm(41L).isSkipped(),
                "same-gt post-consume cohort vanished when its task terminalized");

        // U2 is the sole authority which consumes that second cohort.
        island.consumeChunkArmIfQuiescent();
        assertEquals(island.armReservationGeneration, island.armConsumedGeneration);
        assertTrue(island.reserveChunkArm(41L).isIslandArm(),
                "next physical arm stayed closed after U2 consumed the exact debt");
    }

    @Test
    void runFailureAndQueuedCancellationEachCloseExactlyOneReservation() {
        IslandRegistry.PIsland runOwner = new IslandRegistry.PIsland(2L, 0L);
        IslandRegistry.ArmGate runGate = runOwner.reserveChunkArm(10L);
        RuntimeException primary = new RuntimeException("vanilla body failed");
        Runnable failing = RegionizedServer.armReservedTask(runGate, () -> {
            throw primary;
        });
        assertSame(primary, assertThrows(RuntimeException.class, failing::run));
        assertEquals(0, runOwner.chunkArmReservations());
        assertThrows(IllegalStateException.class, failing::run,
                "a terminal arm task was entered twice");

        IslandRegistry.PIsland cancelOwner = new IslandRegistry.PIsland(3L, 0L);
        IslandRegistry.ArmGate cancelGate = cancelOwner.reserveChunkArm(10L);
        AtomicInteger bodies = new AtomicInteger();
        Runnable cancelled = RegionizedServer.armReservedTask(
                cancelGate, bodies::incrementAndGet);
        ((RegionTaskFanIn.CancellableQueueEntry) cancelled)
                .cancelQueuedBeforeStartWithFailure(primary);
        assertEquals(0, cancelOwner.chunkArmReservations());
        cancelled.run();
        assertEquals(0, bodies.get(), "cancelled arm task entered its vanilla body");
        ((RegionTaskFanIn.CancellableQueueEntry) cancelled).cancelQueuedBeforeStart();
        assertEquals(0, cancelOwner.chunkArmReservations(),
                "repeated failed-generation cancellation double-closed the reservation");
    }

    @Test
    void terminalClosesTheExactRetiredOwnerNotTheReplacementIdentity() {
        IslandRegistry.PIsland oldOwner = new IslandRegistry.PIsland(4L, 0L);
        IslandRegistry.PIsland replacement = new IslandRegistry.PIsland(5L, 0L);
        Runnable oldTask = RegionizedServer.armReservedTask(
                oldOwner.reserveChunkArm(22L), () -> {});
        replacement.reserveChunkArm(22L);
        oldOwner.retired = true;

        oldTask.run();
        assertEquals(0, oldOwner.chunkArmReservations());
        assertEquals(1, replacement.chunkArmReservations(),
                "old task terminal decremented the cell's replacement owner");
    }

    @Test
    void productionRejectionClosesBeforeRethrowAndUnitUsesLockedCommit() throws Exception {
        String mixin = source("common/mixin/server/ServerChunkCacheMixin.java");
        int helper = mixin.indexOf(
                "private static void fabrolia$enqueueIslandArmTask(");
        int enqueue = mixin.indexOf("RegionScheduler.enqueueRegionTask", helper);
        int rejected = mixin.indexOf("if (accepted) return;", enqueue);
        int cancel = mixin.indexOf("cancelQueuedBeforeStartWithFailure", rejected);
        int rethrow = mixin.indexOf("fabrolia$rethrow(failure)", cancel);
        assertTrue(helper >= 0 && enqueue > helper && rejected > enqueue
                        && cancel > rejected && rethrow > cancel,
                "false/throw enqueue must terminalize the exact arm before fail-stop");

        String unit = source("common/regions/IslandTickUnit.java");
        int commit = unit.indexOf("void commit() {");
        int armCommit = unit.indexOf("island.consumeChunkArmIfQuiescent();", commit);
        int logical = unit.indexOf("island.logicalTick = nextLogicalTick;", armCommit);
        assertTrue(commit >= 0 && armCommit > commit && logical > armCommit);
        assertFalse(unit.substring(commit, logical).contains(
                        "island.armConsumedGameTime ="),
                "unit success bypasses the reservation lock with a stale arm snapshot");
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && root != null; i++, root = root.getParent()) {
            Path direct = root.resolve("src/main/java/com/axalotl/fabrolia").resolve(relative);
            Path viaCommon = root.resolve("common/src/main/java/com/axalotl/fabrolia").resolve(relative);
            if (Files.isRegularFile(direct)) return Files.readString(direct);
            if (Files.isRegularFile(viaCommon)) return Files.readString(viaCommon);
        }
        Assumptions.assumeTrue(false, "source tree not available");
        throw new AssertionError("unreachable");
    }
}
