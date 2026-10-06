package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.ticks.ProtoChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression pin for the 2026-08-06 save-race fix
 * ({@link TickDomains#packTicksConsistent} + atomic {@link TickDomains#rebaseTo}).
 *
 * <p>The race: chunk serialization (c2me IO thread) resolved the save basis in
 * one instant and packed the triggers in another; a concurrent rebase
 * (assignDomain at rebuild, region on/off collapse) landing between them —
 * or a retag landing between a rebase's own shift and a concurrent basis
 * read — wrote {@code delay = (targetNow + d) − sourceNow}, ± the full
 * wall↔island divergence, to disk as a self-consistent record no load-side
 * heal could classify (manual {@code /fabrolia regions ticks clear-stale}
 * was the only repair). Here a "main" thread flip-flops one chunk's
 * containers between WALL and an island clock — every rebase preserves the
 * 70-tick remaining delay — while an "IO" thread packs through the REAL
 * {@link TickDomains#packTicksConsistent}. Every packed delay must come out
 * exactly 70: the pre-fix code produces 70 ± (200_000 − 110_000).
 *
 * <p>Harness reuses the production-path fixtures from
 * {@link TickDomainsProductionPathTest}: REAL vanilla containers with the
 * mixin's tag duck, REAL rebase/pack code — only the mixin-runtime pieces are
 * stubbed. Probabilistic by nature (it pins a race): WITH the fix the pass is
 * deterministic, since the lock discipline admits no interleaving at all.
 */
class TickSaveRaceTest {

    private static final long WALL = FabroliaTickDomain.WALL;
    private static final long ISLAND = 101;
    private static final long L = 110_000;
    private static final long W = 200_000;
    private static final long DELAY = 70;
    private static final int ITERATIONS = 4_000;

    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @AfterEach
    void resetGlobalState() {
        TickDomains.clearFinalClocks();
    }

    @Test
    void packNeverObservesAMidRebaseContainer() throws Exception {
        TickDomainsProductionPathTest.TestLevel level = TickDomainsProductionPathTest.TestLevel.alloc();
        level.fabricatedLevelData = new TickDomainsProductionPathTest.TestLevelData(W);
        level.fabricatedGameTime = W;
        TickDomainsProductionPathTest.TestLevelTicks<String> blockLane = new TickDomainsProductionPathTest.TestLevelTicks<>();
        TickDomainsProductionPathTest.TestLevelTicks<String> fluidLane = new TickDomainsProductionPathTest.TestLevelTicks<>();
        level.fabricatedBlockTicks = erased(blockLane);
        level.fabricatedFluidTicks = erased(fluidLane);
        TickDomainsProductionPathTest.seedFinalClock(level, ISLAND, L);

        TickDomainsProductionPathTest.TaggedChunkTicks<String> block = new TickDomainsProductionPathTest.TaggedChunkTicks<>();
        TickDomainsProductionPathTest.TaggedChunkTicks<String> fluid = new TickDomainsProductionPathTest.TaggedChunkTicks<>();
        block.fabrolia$setDomain(ISLAND, 1);
        fluid.fabrolia$setDomain(ISLAND, 1);
        BlockPos pos = new BlockPos(3, 64, 3);
        block.schedule(new ScheduledTick<>("water", pos, L + DELAY, TickPriority.NORMAL, 0));
        fluid.schedule(new ScheduledTick<>("water", pos, L + DELAY, TickPriority.NORMAL, 0));

        TickDomainsProductionPathTest.TestChunk chunk = TickDomainsProductionPathTest.TestChunk.alloc();
        chunk.fabricatedPos = new ChunkPos(0, 0);
        chunk.fabricatedBlockTicks = cast(block);
        chunk.fabricatedFluidTicks = cast(fluid);

        ConcurrentLinkedQueue<Long> packedDelays = new ConcurrentLinkedQueue<>();
        AtomicReference<Throwable> ioFailure = new AtomicReference<>();
        AtomicReference<Throwable> mainFailure = new AtomicReference<>();

        // "IO thread": pack through the REAL production save seam. The spin
        // between basis resolution and the pack widens the exact interleaving
        // window the bug needed (deterministic pass with the fix: the locks
        // admit no interleaving at all).
        Thread io = new Thread(() -> {
            try {
                for (int i = 0; i < ITERATIONS; i++) {
                    TickDomains.packTicksConsistent(level, chunk, W, basis -> {
                        long spin = System.nanoTime() + 2_000L;
                        while (System.nanoTime() < spin) {
                            Thread.onSpinWait();
                        }
                        packedDelays.add((long) block.pack(basis).get(0).delay());
                        packedDelays.add((long) fluid.pack(basis).get(0).delay());
                        return null;
                    });
                }
            } catch (Throwable t) {
                ioFailure.set(t);
            }
        }, "fabrolia-test-io");

        // "Main": rebuild-shaped domain migrations — each rebase preserves
        // the remaining delay exactly, so any packed delay != DELAY is proof
        // of a mid-rebase observation.
        Thread main = new Thread(() -> {
            try {
                for (int i = 0; i < ITERATIONS; i++) {
                    TickDomains.rebaseTo(level, block, WALL, -1, W);
                    TickDomains.rebaseTo(level, fluid, WALL, -1, W);
                    TickDomains.rebaseTo(level, block, ISLAND, 1, L);
                    TickDomains.rebaseTo(level, fluid, ISLAND, 1, L);
                }
            } catch (Throwable t) {
                mainFailure.set(t);
            }
        }, "fabrolia-test-main");

        io.start();
        main.start();
        io.join(60_000);
        main.join(60_000);

        if (ioFailure.get() != null) throw new AssertionError("io thread threw", ioFailure.get());
        if (mainFailure.get() != null) throw new AssertionError("main thread threw", mainFailure.get());
        assertEquals(ITERATIONS * 2L, packedDelays.size(), "every iteration must pack both lanes");
        for (long d : packedDelays) {
            assertEquals(DELAY, d,
                    "packed delay must be the true remaining delay in whichever domain the "
                            + "container was in — a ±" + (W - L) + " value means the pack raced a rebase");
        }
    }

    /** Proto/worldgen chunks (no live {@code LevelChunkTicks} containers):
     *  vanilla wall semantics — the raw pack runs with the caller's basis,
     *  untouched, and no lock is needed. */
    @Test
    void packTicksConsistentFallsBackToVanillaForProtoChunks() {
        TickDomainsProductionPathTest.TestLevel level = TickDomainsProductionPathTest.TestLevel.alloc();
        level.fabricatedLevelData = new TickDomainsProductionPathTest.TestLevelData(W);
        level.fabricatedGameTime = W;
        TickDomainsProductionPathTest.TestChunk chunk = TickDomainsProductionPathTest.TestChunk.alloc();
        chunk.fabricatedPos = new ChunkPos(0, 0);
        chunk.fabricatedBlockTicks = new ProtoChunkTicks<>();
        chunk.fabricatedFluidTicks = new ProtoChunkTicks<>();
        long[] seenBasis = new long[1];
        TickDomains.packTicksConsistent(level, chunk, W, basis -> {
            seenBasis[0] = basis;
            return null;
        });
        assertEquals(W, seenBasis[0], "proto chunks must pack against the caller's wall clock (vanilla)");
    }

    /** Empty live containers: nothing to pack, but the seam must still answer
     *  a basis and run the raw pack without throwing. */
    @Test
    void packTicksConsistentHandlesEmptyContainers() {
        TickDomainsProductionPathTest.TestLevel level = TickDomainsProductionPathTest.TestLevel.alloc();
        level.fabricatedLevelData = new TickDomainsProductionPathTest.TestLevelData(W);
        level.fabricatedGameTime = W;
        TickDomainsProductionPathTest.TestLevelTicks<String> blockLane = new TickDomainsProductionPathTest.TestLevelTicks<>();
        TickDomainsProductionPathTest.TestLevelTicks<String> fluidLane = new TickDomainsProductionPathTest.TestLevelTicks<>();
        level.fabricatedBlockTicks = erased(blockLane);
        level.fabricatedFluidTicks = erased(fluidLane);
        TickDomainsProductionPathTest.TaggedChunkTicks<String> block = new TickDomainsProductionPathTest.TaggedChunkTicks<>();
        TickDomainsProductionPathTest.TaggedChunkTicks<String> fluid = new TickDomainsProductionPathTest.TaggedChunkTicks<>();
        TickDomainsProductionPathTest.TestChunk chunk = TickDomainsProductionPathTest.TestChunk.alloc();
        chunk.fabricatedPos = new ChunkPos(0, 0);
        chunk.fabricatedBlockTicks = cast(block);
        chunk.fabricatedFluidTicks = cast(fluid);
        boolean[] ran = new boolean[1];
        TickDomains.packTicksConsistent(level, chunk, W, basis -> {
            ran[0] = true;
            assertTrue(block.pack(basis).isEmpty() && fluid.pack(basis).isEmpty());
            return null;
        });
        assertTrue(ran[0], "the raw pack must run even with nothing pending");
    }

    // ---- harness plumbing (mirrors TickDomainsProductionPathTest) ----

    @SuppressWarnings("unchecked")
    private static <T> net.minecraft.world.ticks.TickContainerAccess<T> cast(
            net.minecraft.world.ticks.LevelChunkTicks<?> c) {
        return (net.minecraft.world.ticks.TickContainerAccess<T>) c;
    }

    @SuppressWarnings("unchecked")
    private static <T> net.minecraft.world.ticks.LevelTicks<T> erased(
            TickDomainsProductionPathTest.TestLevelTicks<?> lane) {
        return (net.minecraft.world.ticks.LevelTicks<T>) (net.minecraft.world.ticks.LevelTicks<?>) lane;
    }
}
