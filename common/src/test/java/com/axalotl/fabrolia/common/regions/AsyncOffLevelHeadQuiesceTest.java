package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.config.FabroliaConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live ASYNC→SYNC flip at the level head ({@code /fabrolia regions async off}
 * and FAIL-CLOSED containment) while an island unit admitted before the flip is
 * still running (2026-11-18 toggle crashes: {@code island logical clock changed
 * mid-unit}, the flip tick's shadow-clock advance wrote L under the running
 * unit; {@code A5 post-physics player publication violated exact ownership},
 * the running body read the flipped latch). The REAL
 * {@link RegionizedServer#onLevelTickStart} runs on the test thread; a second
 * thread holds the island's unit claim exactly as an admitted unit does and
 * moves L only through its own completion. Main must not flip the level latch,
 * touch the island clock or its container tags before that unit released its
 * claim.
 */
@Isolated
final class AsyncOffLevelHeadQuiesceTest {

    private static final long WALL = 1_000L;
    private static final long L_AT_UNIT_START = 100L;
    private static final long TRIGGER_IN_ISLAND_TIME = 105L;

    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void liveAsyncOffWaitsForTheAdmittedUnitBeforeMovingItsClockOrTags() throws Exception {
        runFlipAgainstRunningUnit(false);
    }

    @Test
    void failClosedContainmentTakesTheSameFencedCollapse() throws Exception {
        runFlipAgainstRunningUnit(true);
    }

    @Test
    void levelHeadPinsQuiesceThenLatchFlipAndTagCollapseThenCatchUpThenShadowClock() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/axalotl/fabrolia/common/regions/RegionizedServer.java"));
        int head = source.indexOf("public void onLevelTickStart() {");
        int headEnd = source.indexOf("\n    }\n", head);
        String body = source.substring(head, headEnd);
        int fence = body.indexOf("switchToWallUnderQuiesce();");
        int catchUp = body.indexOf("IslandFunctionHead.catchUpRetiredIslands(level);");
        int shadow = body.indexOf("islandRegistry.advanceShadow(this, level.getGameTime());");
        assertTrue(fence >= 0 && fence < catchUp, "the function catch-up runs after every admitted unit finished");
        assertTrue(catchUp < shadow, "the shadow clock moves only after the OFF quiesce");
        assertEquals(-1, body.indexOf("asyncActive = false"),
                "the level head must never flip the latch OFF outside the all-island quiesce");
        assertEquals(-1, body.indexOf("asyncActive = wantAsync"),
                "the OFF direction must not share the unfenced latch write");
        assertEquals(-1, body.indexOf("TickDomains.collapseAllToWall("),
                "the level head must never collapse island tags outside the all-island quiesce");

        int helper = source.indexOf("private void switchToWallUnderQuiesce() {");
        assertTrue(helper >= 0, "OFF switch helper is missing");
        String fenced = source.substring(helper, source.indexOf("\n    }\n", helper));
        int quiesce = fenced.indexOf("boolean quiesced = quiesceAllIslands();");
        int admitted = fenced.indexOf("RegionScheduler.runSuccessBodyIfHealthy(() -> {");
        int holds = fenced.indexOf("if (!currentThreadHoldsAllClaims()) {");
        int flip = fenced.indexOf("asyncActive = false;");
        int live = fenced.indexOf("RegionScheduler.updateAsyncLive();");
        int collapse = fenced.indexOf("TickDomains.collapseAllToWall(java.util.List.of(this));");
        int winner = fenced.indexOf("failure = RegionScheduler.publishFatalAndSelectWinner(primary);");
        int release = fenced.indexOf("failure = releaseAllIslands(failure);");
        int cut = fenced.indexOf("RegionScheduler.commitSuccessIfHealthy(() -> {});");
        int rethrow = fenced.indexOf("FailStops.<RuntimeException>rethrow(");
        assertTrue(quiesce >= 0 && quiesce < admitted && admitted < holds && holds < flip
                        && flip < live && live < collapse,
                "the latch flip and the tag collapse are one admitted body under every island claim");
        assertTrue(collapse < winner && winner < release && release < cut && cut < rethrow,
                "canonical fail-stop shape: winner, claim release, success cut, exact rethrow");
    }

    private void runFlipAgainstRunningUnit(boolean failClosed) throws Exception {
        try (Fixture f = new Fixture(failClosed)) {
            IslandRegistry.PIsland island = f.island;
            CountDownLatch admitted = new CountDownLatch(1);
            AtomicBoolean mainReturned = new AtomicBoolean();
            AtomicReference<String> unitViolation = new AtomicReference<>();
            AtomicReference<Throwable> unitFailure = new AtomicReference<>();
            AtomicLong tagSeenByUnit = new AtomicLong(Long.MIN_VALUE);
            AtomicBoolean asyncSeenAtBodyEnd = new AtomicBoolean();
            Thread unit = new Thread(() -> {
                try {
                    // Admission exactly as IslandTickUnit.runCoordinated: claim, then
                    // read the async latch (still ON: admitted before the flip).
                    if (!island.unitClaim.compareAndSet(false, true)) {
                        throw new AssertionError("unit could not claim its island");
                    }
                    island.noteClaim("unit@" + Thread.currentThread().getName());
                    if (!f.runtime.asyncActive) throw new AssertionError("unit was not admitted before the flip");
                    long lAtStart = island.logicalTick;
                    admitted.countDown();
                    // The body runs until Main is visibly waiting for this claim
                    // (or, on a broken head, has already returned).
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (!island.claimInterestAnnounced() && !mainReturned.get()) {
                        if (System.nanoTime() > deadline) {
                            throw new AssertionError("Main neither waited for the unit nor returned");
                        }
                        Thread.onSpinWait();
                    }
                    tagSeenByUnit.set(f.container.fabrolia$domainIsland());
                    // The A5 late publication and tracker routing of a running body
                    // read the level latch; it must not flip under an admitted unit.
                    asyncSeenAtBodyEnd.set(f.runtime.asyncActive);
                    if (island.logicalTick != lAtStart) {
                        unitViolation.set("island logical clock changed mid-unit: "
                                + lAtStart + " -> " + island.logicalTick);
                    }
                    // complete(): the only legal L writer, then the claim release.
                    island.logicalTick = lAtStart + 1;
                    island.clearClaim();
                    island.unitClaim.set(false);
                } catch (Throwable failure) {
                    unitFailure.set(failure);
                    admitted.countDown();
                }
            }, "Fabrolia-Region-Worker-test-unit");
            unit.start();
            assertTrue(admitted.await(5, TimeUnit.SECONDS));
            if (unitFailure.get() != null) throw new AssertionError(unitFailure.get());

            try {
                f.runtime.onLevelTickStart();
            } finally {
                mainReturned.set(true);
                unit.join(15_000L);
            }
            assertFalse(unit.isAlive(), "unit thread did not finish");
            if (unitFailure.get() != null) throw new AssertionError(unitFailure.get());

            assertNull(unitViolation.get(), "the OFF head wrote L under a running unit");
            assertTrue(asyncSeenAtBodyEnd.get(),
                    "the OFF head flipped asyncActive under a running unit's body");
            assertEquals(1L, tagSeenByUnit.get(),
                    "the OFF head collapsed the running unit's container tags under its claim");
            assertFalse(f.runtime.asyncActive);
            assertEquals(FabroliaTickDomain.WALL, f.container.fabrolia$domainIsland(),
                    "the OFF head must still collapse every island tag to wall");
            // Delay-preserving against the island's FINAL L (101): due in 4 island
            // ticks stays due in 4 wall ticks.
            assertEquals(TRIGGER_IN_ISLAND_TIME + (WALL - (L_AT_UNIT_START + 1)),
                    f.container.peek().triggerTick(),
                    "tags were translated against a stale island clock");
            assertEquals(L_AT_UNIT_START + 2, island.logicalTick,
                    "unit completion plus exactly one shadow-clock tick for the flip tick");
            assertFalse(island.unitClaim.get(), "the OFF quiesce leaked the island claim");
            assertFalse(island.claimInterestAnnounced(), "the OFF quiesce leaked its claim intent");
            assertFalse(f.runtime.mainHoldsAllClaims(), "the OFF quiesce leaked its all-claims receipt");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final TickDomainsProductionPathTest.TestLevel level = TickDomainsProductionPathTest.TestLevel.alloc();
        final TickDomainsProductionPathTest.TestLevelTicks<Block> blockTicks =
                new TickDomainsProductionPathTest.TestLevelTicks<>();
        final TickDomainsProductionPathTest.TestLevelTicks<Fluid> fluidTicks =
                new TickDomainsProductionPathTest.TestLevelTicks<>();
        final TickDomainsProductionPathTest.TaggedChunkTicks<Block> container =
                new TickDomainsProductionPathTest.TaggedChunkTicks<>();
        final RegionizedServer runtime;
        final IslandRegistry.PIsland island = new IslandRegistry.PIsland(1L, L_AT_UNIT_START);
        final Map<ServerLevel, RegionizedServer> levels;
        final Object previousPool = getStatic(RegionScheduler.class, "pool");
        final Object previousActive = getStatic(RegionScheduler.class, "active");
        final Object previousOrphan = getStatic(RegionScheduler.class, "orphanFatalFailure");
        final Object previousMainLevel = getStatic(RegionScheduler.class, "mainCurrentLevel");
        final Object previousAsyncLive = getStatic(RegionScheduler.class, "asyncLive");
        final Object previousMarkerChecked = getStatic(RegionizedServer.class, "failClosedMarkerChecked");
        final Object previousArmed = getStatic(IslandFunctionHead.class, "ARMED");
        final boolean previousAsyncIslands = FabroliaConfig.asyncIslands;
        final boolean previousTripped = FabroliaConfig.asyncFailClosedTripped;

        @SuppressWarnings("unchecked")
        Fixture(boolean failClosed) throws Exception {
            assertNull(previousPool, "fixture requires no live scheduler generation");
            level.fabricatedGameTime = WALL;
            level.fabricatedLevelData = new TickDomainsProductionPathTest.TestLevelData(WALL);
            level.fabricatedBlockTicks = blockTicks;
            level.fabricatedFluidTicks = fluidTicks;
            container.fabrolia$setDomain(island.id, 1L);
            container.schedule(new ScheduledTick<>(net.minecraft.world.level.block.Blocks.REPEATER,
                    new BlockPos(3, 64, 3), TRIGGER_IN_ISLAND_TIME, TickPriority.NORMAL, 0L));
            blockTicks.containers.put(ChunkPos.pack(0, 0), container);

            runtime = new RegionizedServer(level, new RegionGrid(3));
            runtime.asyncActive = true;
            island.level = level;
            ((Long2ObjectOpenHashMap<IslandRegistry.PIsland>) get(
                    IslandRegistry.class, runtime.islandRegistry, "islands")).put(island.id, island);
            levels = (Map<ServerLevel, RegionizedServer>) getStatic(RegionScheduler.class, "PER_LEVEL");
            levels.put(level, runtime);
            setStatic(RegionScheduler.class, "active", true);
            setStatic(RegionScheduler.class, "orphanFatalFailure", null);
            setStatic(RegionizedServer.class, "failClosedMarkerChecked", true);
            setStatic(IslandFunctionHead.class, "ARMED", new IslandFunctionHead.Armed(
                    FunctionPlan.EMPTY, List.of(), List.of()));
            if (failClosed) {
                FabroliaConfig.asyncFailClosedTripped = true;
            } else {
                FabroliaConfig.asyncIslands = false;
            }
        }

        @Override
        public void close() throws Exception {
            FabroliaConfig.asyncIslands = previousAsyncIslands;
            FabroliaConfig.asyncFailClosedTripped = previousTripped;
            levels.remove(level);
            setStatic(IslandFunctionHead.class, "ARMED", previousArmed);
            setStatic(RegionizedServer.class, "failClosedMarkerChecked", previousMarkerChecked);
            setStatic(RegionScheduler.class, "asyncLive", previousAsyncLive);
            setStatic(RegionScheduler.class, "mainCurrentLevel", previousMainLevel);
            setStatic(RegionScheduler.class, "orphanFatalFailure", previousOrphan);
            setStatic(RegionScheduler.class, "active", previousActive);
        }
    }

    private static Object get(Class<?> type, Object target, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object getStatic(Class<?> type, String name) {
        try {
            return get(type, null, name);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setStatic(Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
}
