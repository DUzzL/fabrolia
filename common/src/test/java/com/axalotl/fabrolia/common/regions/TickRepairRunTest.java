package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Orchestrator-lifecycle pins for {@link TickRepairRun#execute} (2026-07-27
 * review finding #8): the RUNNING flag must never leak — a leaked flag
 * permanently disables the facility until restart — and a mutating run must
 * abort BEFORE any mutation when the report file cannot be written.
 *
 * <p>Uses the production {@code execute} core with the harness level from
 * {@link TickRepairProductionPathTest}; the {@code Feedback}/{@code ScanStarter}
 * seams exist precisely so these paths are drivable without a live server.
 *
 * <p>The real ChunkMap/task-queue execution still needs a live-server rig;
 * source-order assertions below pin its non-negotiable fail-stop wiring, while
 * the classification/apply cores are covered by
 * {@link TickRepairProductionPathTest}.
 */
class TickRepairRunTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @TempDir
    Path tmp;

    @AfterEach
    void resetGlobalState() throws ReflectiveOperationException {
        IslandCommands.releaseAllExcept();   // never leak a test-held fence
        TickRepair.RUNNING.set(false);
        TickRepair.CANCEL.set(false);
        Field f = RegionScheduler.class.getDeclaredField("active");
        f.setAccessible(true);
        f.set(null, false);
        Field p = RegionScheduler.class.getDeclaredField("PER_LEVEL");
        p.setAccessible(true);
        ((Map<?, ?>) p.get(null)).clear();
        Field orphanFatal = RegionScheduler.class.getDeclaredField("orphanFatalFailure");
        orphanFatal.setAccessible(true);
        orphanFatal.set(null, null);
        TickDomains.clearFinalClocks();
    }

    private static final class RecordingFeedback implements TickRepairRun.Feedback {
        final List<String> replies = new ArrayList<>();
        final List<String> failures = new ArrayList<>();

        @Override public void reply(String text) { replies.add(text); }
        @Override public void fail(String text) { failures.add(text); }
    }

    /** Harness level with one stale (type-mismatch) block entry — enough to
     *  make CONFIRM actionable. */
    private static TickRepairProductionPathTest.Fixture staleFixture(long wall) {
        TickRepairProductionPathTest.Fixture fx = new TickRepairProductionPathTest.Fixture(wall);
        BlockPos pos = new BlockPos(17, 64, 17);
        fx.level.states.put(pos, Blocks.STONE.defaultBlockState());
        fx.block.schedule(new ScheduledTick<>(Blocks.REDSTONE_ORE, pos, wall + 4, TickPriority.NORMAL, 1));
        return fx;
    }

    @Test
    void runningFlagClearedWhenScanStarterThrows() {
        TickRepairProductionPathTest.Fixture fx = staleFixture(1_000);
        RecordingFeedback fb = new RecordingFeedback();
        RuntimeException primary = new RuntimeException("scan thread could not start");
        Throwable thrown = assertThrows(Throwable.class,
                () -> TickRepairRun.execute(List.of(fx.level), tmp.resolve("throw"),
                        TickRepair.Mode.DRY, fb,
                        (reportFile, backupDir, backedUp) -> { throw primary; }));
        assertSame(primary, thrown,
                "an entered starter failure must remain the exact physical-Main primary");
        assertFalse(TickRepair.RUNNING.get(),
                "a starter throw must release RUNNING — a leaked flag disables the facility until restart");
        assertTrue(fb.failures.isEmpty(),
                "an entered failure is not a Brigadier result-zero/failure-message path");
    }

    @Test
    void runningFlagHeldAfterHandoffAndConcurrentRunRejected() {
        TickRepairProductionPathTest.Fixture fx = staleFixture(1_000);
        RecordingFeedback fb = new RecordingFeedback();
        boolean[] started = {false};
        int result = TickRepairRun.execute(List.of(fx.level), tmp.resolve("handoff"),
                TickRepair.Mode.DRY, fb, (reportFile, backupDir, backedUp) -> started[0] = true);
        assertEquals(1, result);
        assertTrue(started[0], "the scan starter must have been invoked");
        assertTrue(TickRepair.RUNNING.get(),
                "after a successful handoff the scan/batcher owns the flag");
        assertTrue(fb.replies.stream().anyMatch(s -> s.equals(TickRepair.SCOPE)),
                "output must lead with the scope statement");
        assertTrue(fb.replies.stream().anyMatch(s -> s.equals(TickRepair.SEMANTICS)),
                "output must carry the due-time vs scan-time disclosure");
        assertTrue(fb.replies.stream().anyMatch(s -> s.contains("COMPETES with player chunk load/save")),
                "the IO-worker contention of the unloaded scan must be disclosed");

        // a second run while the first is in flight is rejected
        RecordingFeedback fb2 = new RecordingFeedback();
        int second = TickRepairRun.execute(List.of(fx.level), tmp.resolve("handoff2"),
                TickRepair.Mode.DRY, fb2, (a, b, c) -> {});
        assertEquals(0, second);
        assertEquals(1, fb2.failures.size());
        assertTrue(fb2.failures.get(0).contains("already in progress"));
    }

    @Test
    void clearAmbiguousModeReleasesRunningWithoutStartingScan() {
        TickRepairProductionPathTest.Fixture fx = staleFixture(1_000);
        RecordingFeedback fb = new RecordingFeedback();
        boolean[] started = {false};
        int result = TickRepairRun.execute(List.of(fx.level), tmp.resolve("clear"),
                TickRepair.Mode.CLEAR_DRY, fb, (a, b, c) -> started[0] = true);
        assertEquals(1, result);
        assertFalse(started[0], "clear-ambiguous modes never run the unloaded scan");
        assertFalse(TickRepair.RUNNING.get(), "clear modes release the flag synchronously");
    }

    // ------------------------------------------------------------------
    // A production container failure must release lifecycle state and escape
    // as the exact fatal Throwable. It is never converted to command result 0.
    // ------------------------------------------------------------------

    /** Reproduces the crash SHAPE of the audit14 production failure: a
     *  container that throws the Lithium NPE when the loaded pass touches it
     *  (on the server the throw came from the packed-ticks internal accessor;
     *  the lifecycle contract is the same for any container-sourced throw). */
    static final class NpeChunkTicks extends TickRepairProductionPathTest.RepairChunkTicks<net.minecraft.world.level.block.Block> {
        final NullPointerException primary = new NullPointerException(
                "Cannot invoke \"java.util.Set.remove(Object)\" because \"this.ticksPerPosition\" is null");

        @Override public java.util.stream.Stream<ScheduledTick<net.minecraft.world.level.block.Block>> getAll() {
            throw primary;
        }
    }

    @Test
    void runningFlagReleasedAndExactPrimaryEscapesLoadedPassProductionNpe() {
        TickRepairProductionPathTest.Fixture fx = staleFixture(1_000);
        NpeChunkTicks npe = new NpeChunkTicks();
        npe.schedule(new ScheduledTick<>(Blocks.STONE, new BlockPos(17, 64, 17),
                1_004L, TickPriority.NORMAL, 1));   // vanilla schedule; only getAll throws
        fx.level.blockLane.containers.put(fx.chunkKey, npe);
        RecordingFeedback fb = new RecordingFeedback();
        Throwable thrown = assertThrows(Throwable.class,
                () -> TickRepairRun.execute(List.of(fx.level), tmp.resolve("npe"),
                        TickRepair.Mode.CONFIRM, fb, (a, b, c) -> {}));
        assertSame(npe.primary, thrown,
                "the container failure must escape as the same Throwable object");
        assertFalse(TickRepair.RUNNING.get(),
                "the production NPE thrown inside runLoadedPass must release RUNNING");
        assertTrue(fb.failures.isEmpty());
    }

    @Test
    void failureAfterCommitStillFailStopsWithExactPrimary() {
        TickRepairProductionPathTest.Fixture fx = staleFixture(1_000);
        RecordingFeedback fb = new RecordingFeedback();
        // The scan starter throws AFTER the loaded pass committed its repair.
        // That does not make the later failure recoverable or result-valued.
        RuntimeException primary = new RuntimeException("scan thread could not start");
        Throwable thrown = assertThrows(Throwable.class,
                () -> TickRepairRun.execute(List.of(fx.level), tmp.resolve("post-commit"),
                        TickRepair.Mode.CONFIRM, fb,
                        (reportFile, backupDir, backedUp) -> { throw primary; }));
        assertSame(primary, thrown);
        assertFalse(TickRepair.RUNNING.get());
        assertEquals(0, fx.block.count(), "the loaded-pass repair itself did commit");
        assertTrue(fb.failures.isEmpty(),
                "a post-commit primary must crash, not be acknowledged as command result zero");
    }

    /** The all-islands fence cleanup is exhaustive and returns the exact body
     *  primary. Publication is deliberately tested elsewhere: once published,
     *  that fatal must poison all later work rather than permit another
     *  command. */
    @Test
    void quiesceFenceCleanupPreservesPrimaryAndReleasesEveryClaim() throws Exception {
        TickRepairProductionPathTest.Fixture fx = staleFixture(1_000);
        RegionizedServer rs = new RegionizedServer(fx.level, new RegionGrid(2));
        rs.asyncActive = true;
        rs.islandRegistry.applyRebuildCells(
                List.of(new it.unimi.dsi.fastutil.longs.LongArrayList(new long[]{7L})), 0L);
        IslandRegistry.PIsland island = rs.islandRegistry.byCell(7L);
        Field active = RegionScheduler.class.getDeclaredField("active");
        active.setAccessible(true);
        active.set(null, true);
        Field p = RegionScheduler.class.getDeclaredField("PER_LEVEL");
        p.setAccessible(true);
        @SuppressWarnings("unchecked")
        var perLevel = (Map<net.minecraft.server.level.ServerLevel, RegionizedServer>) p.get(null);
        perLevel.put(fx.level, rs);
        try {
            assertTrue(IslandCommands.quiesceAllExcept(null, 1_000_000_000L),
                    "the fence must be takable in this fixture");
            assertTrue(IslandCommands.fenceWindowHeld());
            assertTrue(rs.mainHoldsAllClaims(),
                    "skip-claim authority publishes only with the acquired frame");
            assertTrue(rs.mainClaimsFenceActive());
            boolean[] foreign = new boolean[2];
            Thread observer = Thread.ofPlatform().start(() -> {
                foreign[0] = IslandCommands.fenceWindowHeld();
                foreign[1] = rs.currentThreadHoldsAllClaims();
            });
            observer.join();
            assertFalse(foreign[0],
                    "a foreign thread must not borrow the global HELD receipt");
            assertFalse(foreign[1],
                    "a foreign thread must not borrow per-level ownership");
            assertTrue(island.unitClaim.get(), "the island must be claimed by the fence");
            NullPointerException primary = new NullPointerException(
                    "Cannot invoke \"java.util.Set.remove(Object)\" because \"this.ticksPerPosition\" is null");
            assertSame(primary, IslandCommands.releaseAllExcept(primary),
                    "fence cleanup must not replace the entered body failure");
            assertFalse(IslandCommands.fenceWindowHeld(),
                    "the fence window must be fully released after the throwing dispatch");
            assertFalse(rs.mainHoldsAllClaims());
            assertFalse(rs.mainClaimsFenceActive());
            assertFalse(island.unitClaim.get(), "the island claim must be released");
            Throwable selected = assertThrows(Throwable.class,
                    RegionScheduler::throwWorkerFailureIfAny);
            assertSame(primary, selected,
                    "releaseAllExcept(primary) must publish the exact W before any successor");
            assertFalse(island.unitClaim.get(),
                    "a selected W forbids a second fence admission, not exhaustive release");
        } finally {
            IslandCommands.releaseAllExcept();
        }
        assertFalse(island.unitClaim.get());
    }

    @Test
    void confirmAbortsBeforeAnyMutationWhenReportCannotBeWritten() throws Exception {
        TickRepairProductionPathTest.Fixture fx = staleFixture(2_000);
        List<ScheduledTick<net.minecraft.world.level.block.Block>> before = fx.block.getAll().toList();
        Path worldRoot = tmp.resolve("abort");
        // sabotage the report location: a regular FILE where the report DIR
        // must be created
        Files.createDirectories(worldRoot.resolve("fabrolia"));
        Files.writeString(worldRoot.resolve("fabrolia").resolve("tick-repair"), "blocker");
        RecordingFeedback fb = new RecordingFeedback();
        boolean[] started = {false};
        assertThrows(java.io.IOException.class,
                () -> TickRepairRun.execute(List.of(fx.level), worldRoot,
                        TickRepair.Mode.CONFIRM, fb, (a, b, c) -> started[0] = true));
        assertTrue(fb.failures.isEmpty(),
                "report admission failure must escape exact, not become result zero");
        assertEquals(before, fx.block.getAll().toList(),
                "NOTHING may be mutated when the report cannot be written");
        assertFalse(started[0], "the unloaded scan must not start after an abort");
        assertFalse(TickRepair.RUNNING.get(), "the abort path must release RUNNING");
    }

    @Test
    void sourcePinsExactFailStopForScanBatchFenceAndBackup() throws Exception {
        String source = tickRepairRunSource();

        assertFalse(source.contains("FENCE_TRY_NANOS"));
        assertFalse(source.contains("fenceDeferrals"));
        assertFalse(source.contains("retrying each tick"),
                "a fence miss must not acknowledge this invocation and replay it later");
        assertFalse(source.contains("totals.skipped++"),
                "an entered chunk failure is not a skipped item");
        assertFalse(source.contains("failed to repair unloaded chunk"));
        assertFalse(source.contains("BATCHER FAILED:"));
        assertFalse(source.contains("could not append"),
                "report IO failure must not be warn-and-continue");
        assertFalse(source.contains("repair proceeds"),
                "backup failure is a hard pre-mutation abort");
        assertFalse(source.contains("tick repair failed:"),
                "entered command failure must not become feedback plus result zero");

        int batch = source.indexOf("private static void runBatchEntered(");
        int finish = source.indexOf("private static void finishBatcher(", batch);
        String batchBody = source.substring(batch, finish);
        int fence = batchBody.indexOf("IslandCommands.quiesceAllExcept(null)");
        int enteredCatch = batchBody.indexOf("catch (Throwable primary)", fence);
        int release = batchBody.indexOf("IslandCommands.releaseAllExcept(failure)", enteredCatch);
        int failureGate = batchBody.indexOf("if (failure != null)", release);
        int candidateAdmission = batchBody.indexOf(
                "RegionScheduler.callSuccessBooleanIfHealthy(() -> {", fence);
        int candidateDetach = batchBody.indexOf(
                "Candidate candidate = queue.remove()", candidateAdmission);
        int candidateMutation = batchBody.indexOf(
                "TickRepair.applySingleChunk(", candidateDetach);
        int reportAdmission = batchBody.indexOf(
                "RegionScheduler.runSuccessBodyIfHealthy(() -> {", failureGate);
        int reportAppend = batchBody.indexOf(
                "TickRepair.appendLines(reportFile, lines)", reportAdmission);
        int successorAdmission = batchBody.indexOf(
                "RegionScheduler.runSuccessBodyIfHealthy(() -> server.schedule(", failureGate);
        int successor = batchBody.indexOf("new TickTask(", successorAdmission);
        assertTrue(fence >= 0 && enteredCatch > fence && release > enteredCatch
                        && failureGate > release
                        && candidateAdmission > fence && candidateDetach > candidateAdmission
                        && candidateMutation > candidateDetach
                        && reportAdmission > failureGate && reportAppend > reportAdmission
                        && successorAdmission > reportAppend
                        && successor > successorAdmission,
                "candidate detach+mutation, durable applied records and successor enqueue need "
                        + "ordered atomic W-vs-entry cuts after exact fence cleanup");

        int boundary = source.indexOf("private static void runBatch(");
        String batchBoundary = source.substring(boundary, batch);
        int catchFailure = batchBoundary.indexOf("catch (Throwable failure)");
        int selectWinner = batchBoundary.indexOf(
                "Throwable published = publishFatalPreserving(failure)", catchFailure);
        int clearRunning = batchBoundary.indexOf("TickRepair.RUNNING.set(false)", selectWinner);
        int publish = batchBoundary.indexOf(
                "IslandCommands.publishAndPropagate(published)", clearRunning);
        assertTrue(catchFailure >= 0 && selectWinner > catchFailure
                        && clearRunning > selectWinner && publish > clearRunning,
                "the TickTask boundary must select exact W before terminal bookkeeping, "
                        + "then propagate that selected object");
        assertFalse(batchBoundary.contains("server.schedule"));
        assertFalse(batchBoundary.contains("reply(server"));

        int scan = source.indexOf("private static boolean unloadedScanBodyEntered(");
        String scanBody = source.substring(scan, source.indexOf("// Mutation batcher", scan));
        assertTrue(scanBody.contains("Optional.empty"));
        assertTrue(scanBody.contains("joinExact("));
        assertTrue(scanBody.contains("catch (NoSuchFileException absent)"));
        int initialAdmission = scanBody.indexOf(
                "RegionScheduler.runSuccessBodyIfHealthy(() -> server.schedule(");
        int initialBatch = scanBody.indexOf("new TickTask(", initialAdmission);
        assertTrue(initialAdmission >= 0 && initialBatch > initialAdmission,
                "the first mutating batch enqueue needs the same W-vs-entry cut");
        assertFalse(scanBody.contains("catch (Throwable"),
                "unexpected read/classifier failures must escape to the terminal scan boundary");

        int messaging = source.indexOf("// Messaging");
        String messages = source.substring(messaging);
        assertTrue(messages.contains(
                "RegionScheduler.runSuccessBodyIfHealthy(() -> server.schedule("));
        assertTrue(messages.contains("RegionScheduler.runSuccessBodyIfHealthy(() -> {"));
        assertFalse(messages.contains("RegionScheduler.throwWorkerFailureIfAny();\n"
                        + "        server.schedule("),
                "feedback must not retain check-then-enqueue or check-then-body windows");

        int backup = source.indexOf("private static void backupRegionFileEntered(");
        String backupBody = source.substring(backup, source.indexOf("// Messaging", backup));
        assertTrue(backupBody.contains("joinExact("));
        assertTrue(backupBody.contains("Files.copy(source, destination)"));
        assertTrue(backupBody.indexOf("Files.copy(source, destination)")
                        < backupBody.indexOf("backedUp.add(source)"),
                "a failed copy must not publish backup success in the ledger");
    }

    private static String tickRepairRunSource() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null) {
            Path direct = root.resolve(
                    "src/main/java/com/axalotl/fabrolia/common/regions/TickRepairRun.java");
            Path viaCommon = root.resolve(
                    "common/src/main/java/com/axalotl/fabrolia/common/regions/TickRepairRun.java");
            if (Files.isRegularFile(direct)) return Files.readString(direct);
            if (Files.isRegularFile(viaCommon)) return Files.readString(viaCommon);
            root = root.getParent();
        }
        throw new IllegalStateException("cannot locate TickRepairRun.java");
    }

}
