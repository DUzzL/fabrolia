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
        exercise(null);
    }

    @Test
    void commandFailureAfterPauseRequestEscapesUnchangedWithoutPreparation() throws Exception {
        exercise(new IllegalArgumentException("original routed command failure"));
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
    void scopedMainClaimInheritsTheWriterWithoutWaitingOrReleasingItsIsland() throws Throwable {
        try (Fixture f = new Fixture()) {
            List<String> effects = new java.util.ArrayList<>();
            // No worker can acquire this island. A broken nested claim reports
            // the retained flag immediately instead of self-waiting indefinitely.
            try {
                IslandCommands.mainClaim(f.island);
                try {
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
                } finally {
                    IslandCommands.mainRelease(f.island);
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
            installCommands(f.server, commands);

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

    /** Bypasses resource reload: only the command dispatcher is needed by this fixture. */
    private static void installCommands(MinecraftServer server, Commands commands) throws Exception {
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
        var managers = (ReloadableServerResources) unsafe.allocateInstance(ReloadableServerResources.class);
        set(ReloadableServerResources.class, managers, "commands", commands);
        Field resources = MinecraftServer.class.getDeclaredField("resources");
        resources.setAccessible(true);
        var constructor = resources.getType().getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        resources.set(server, constructor.newInstance(null, managers));
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

    private static void exercise(RuntimeException commandFailure) throws Exception {
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
                        result.set(IslandCommands.routeCommandBlock("say preparation regression", f.level,
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
                                    return false;
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
                // The sole worker is inside its unit. Only pause progress can consume
                // this Main-only root, so publication happens strictly after request.
                assertTrue(f.pool.enqueueGlobalMainOnly(() -> {
                    assertFalse(f.pool.mayServiceDetachedTransfers(),
                            "late-publication latch must run inside the pause request");
                    publishCommand.countDown();
                    await(f.published, "the worker did not publish its causal Main receipt");
                }));
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
                assertEquals(Boolean.FALSE, result.get());
                assertEquals(List.of("command", "receipt", "preparation", "next-first", "next-second"), effects);
            }
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

        @SuppressWarnings("unchecked")
        Fixture(Class<? extends ServerLevel> levelClass, int workers) throws Exception {
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
            server = (MinecraftServer) unsafe.allocateInstance(com.axalotl.fabrolia.common.regions.TestDedicatedServer.class);
            pool = new RegionWorkerPool(workers);
            set(MinecraftServer.class, server, "tickRateManager",
                    new net.minecraft.server.ServerTickRateManager(server));
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
