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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * <p>Explicitly NOT covered here (needs a live server rig, stated rather than
 * mirrored): {@code unloadedScanBody} (real ChunkMap + region files + IO
 * worker), {@code runBatch} (MinecraftServer task queue, chunk loading, the
 * all-islands quiesce fence), and the {@code cancel} command plumbing. The
 * classification/apply cores those paths delegate to are covered by
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
        int result = TickRepairRun.execute(List.of(fx.level), tmp.resolve("throw"),
                TickRepair.Mode.DRY, fb, (reportFile, backupDir, backedUp) -> {
                    throw new RuntimeException("scan thread could not start");
                });
        assertEquals(0, result);
        assertFalse(TickRepair.RUNNING.get(),
                "a starter throw must release RUNNING — a leaked flag disables the facility until restart");
        assertEquals(1, fb.failures.size());
        assertTrue(fb.failures.get(0).contains("tick repair failed"));
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
    // audit15 regressions: the production NPE path (a container throwing
    // from inside the loaded pass) must release RUNNING, must release the
    // all-islands quiesce fence, and must produce a failure message that
    // states the committed-mutation status — never a bare "command failed".
    // ------------------------------------------------------------------

    /** Reproduces the crash SHAPE of the audit14 production failure: a
     *  container that throws the Lithium NPE when the loaded pass touches it
     *  (on the server the throw came from the packed-ticks internal accessor;
     *  the lifecycle contract is the same for any container-sourced throw). */
    static final class NpeChunkTicks extends TickRepairProductionPathTest.RepairChunkTicks<net.minecraft.world.level.block.Block> {
        @Override public java.util.stream.Stream<ScheduledTick<net.minecraft.world.level.block.Block>> getAll() {
            throw new NullPointerException("Cannot invoke \"java.util.Set.remove(Object)\" "
                    + "because \"this.ticksPerPosition\" is null");
        }
    }

    @Test
    void runningFlagReleasedAndNoCommitStatedWhenLoadedPassThrowsProductionNpe() {
        TickRepairProductionPathTest.Fixture fx = staleFixture(1_000);
        NpeChunkTicks npe = new NpeChunkTicks();
        npe.schedule(new ScheduledTick<>(Blocks.STONE, new BlockPos(17, 64, 17),
                1_004L, TickPriority.NORMAL, 1));   // vanilla schedule; only getAll throws
        fx.level.blockLane.containers.put(fx.chunkKey, npe);
        RecordingFeedback fb = new RecordingFeedback();
        int result = TickRepairRun.execute(List.of(fx.level), tmp.resolve("npe"),
                TickRepair.Mode.CONFIRM, fb, (a, b, c) -> {});
        assertEquals(0, result);
        assertFalse(TickRepair.RUNNING.get(),
                "the production NPE thrown inside runLoadedPass must release RUNNING");
        assertEquals(1, fb.failures.size());
        assertTrue(fb.failures.get(0).contains("ticksPerPosition"),
                "the failure must surface the underlying throwable: " + fb.failures.get(0));
        assertTrue(fb.failures.get(0).contains("no mutations were committed"),
                "the failure must state the committed-mutation status: " + fb.failures.get(0));
    }

    @Test
    void failureAfterCommitIdentifiesCommittedChunksAndReportPath() {
        TickRepairProductionPathTest.Fixture fx = staleFixture(1_000);
        RecordingFeedback fb = new RecordingFeedback();
        // the scan starter throws AFTER the loaded pass committed its repair —
        // the failure output must identify what was already mutated
        int result = TickRepairRun.execute(List.of(fx.level), tmp.resolve("post-commit"),
                TickRepair.Mode.CONFIRM, fb, (reportFile, backupDir, backedUp) -> {
                    throw new RuntimeException("scan thread could not start");
                });
        assertEquals(0, result);
        assertFalse(TickRepair.RUNNING.get());
        assertEquals(0, fx.block.count(), "the loaded-pass repair itself did commit");
        assertEquals(1, fb.failures.size());
        String failure = fb.failures.get(0);
        assertTrue(failure.contains("MUTATIONS WERE COMMITTED"),
                "the failure must state that mutations were committed: " + failure);
        assertTrue(failure.contains("minecraft:overworld chunk[1,1]"),
                "the failure must identify the committed chunk (dimension + coords): " + failure);
        assertTrue(failure.contains("full record: "),
                "the failure must point at the report file: " + failure);
        assertTrue(failure.contains("tick-repair-"),
                "the report path must be the actual report file: " + failure);
    }

    /** The all-islands quiesce fence around command dispatch: CommandsMixin
     *  wraps {@code performPrefixedCommand} in
     *  {@code quiesceAllExcept … try { dispatch } finally { releaseAllExcept }}
     *  (source-verified — mixins cannot be applied in unit tests), so this
     *  pins the {@code releaseAllExcept} half: after a fenced dispatch THROWS,
     *  every island claim and every mainHoldsAllClaims mark must be released
     *  and the fence must be re-takable — a leak here would freeze all
     *  islands forever after one crashing repair command. */
    @Test
    void quiesceFenceFullyReleasedAfterThrowingFencedDispatchAndReTakable() throws Exception {
        TickRepairProductionPathTest.Fixture fx = staleFixture(1_000);
        RegionizedServer rs = new RegionizedServer(fx.level, new RegionGrid(2));
        rs.asyncActive = true;
        IslandRegistry.PIsland island = new IslandRegistry.PIsland(7, 0);
        Field islandsF = IslandRegistry.class.getDeclaredField("islands");
        islandsF.setAccessible(true);
        @SuppressWarnings("unchecked")
        var islands = (it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<IslandRegistry.PIsland>)
                islandsF.get(rs.islandRegistry);
        islands.put(7L, island);
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
            assertTrue(island.unitClaim.get(), "the island must be claimed by the fence");
            // the CommandsMixin discipline around a dispatch that throws the
            // production NPE:
            boolean threw = false;
            try {
                try {
                    throw new NullPointerException("Cannot invoke \"java.util.Set.remove(Object)\" "
                            + "because \"this.ticksPerPosition\" is null");
                } finally {
                    IslandCommands.releaseAllExcept();
                }
            } catch (NullPointerException expected) {
                threw = true;
            }
            assertTrue(threw);
            assertFalse(IslandCommands.fenceWindowHeld(),
                    "the fence window must be fully released after the throwing dispatch");
            assertFalse(island.unitClaim.get(), "the island claim must be released");
            // and the NEXT command can fence again — nothing leaked
            assertTrue(IslandCommands.quiesceAllExcept(null, 1_000_000_000L),
                    "the fence must be re-takable after the throw — a leak disables all islands");
            assertTrue(island.unitClaim.get());
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
        int result = TickRepairRun.execute(List.of(fx.level), worldRoot,
                TickRepair.Mode.CONFIRM, fb, (a, b, c) -> started[0] = true);
        assertEquals(0, result);
        assertEquals(1, fb.failures.size());
        assertTrue(fb.failures.get(0).contains("ABORTED"),
                "an unwritable report must abort the mutating run");
        assertEquals(before, fx.block.getAll().toList(),
                "NOTHING may be mutated when the report cannot be written");
        assertFalse(started[0], "the unloaded scan must not start after an abort");
        assertFalse(TickRepair.RUNNING.get(), "the abort path must release RUNNING");
    }
}
