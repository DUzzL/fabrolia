package com.axalotl.fabrolia.common.regions;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerFunctionLibrary;
import net.minecraft.server.ServerFunctionManager;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.timers.TimerQueue;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The partitioned executor against the serial reference: the same production
 * shaped root lines (parsed by the real command tree, executed through vanilla
 * ExecutionContext/BuildContexts) over N disjoint partitions must leave exactly
 * the state one whole-world pass leaves; the pass mark admits an entity to one
 * pass between ticks; the loop re-arm keeps vanilla schedule-replace semantics.
 */
class FunctionPartitionedExecutionTest {

    private static final sun.misc.Unsafe UNSAFE = unsafe();

    @AfterEach
    void clear() {
        FunctionAnalyzer.clear();
    }

    /** Explicit membership; pass marks held in a map (no mixins in unit tests). */
    private static final class ListPartition extends FunctionPartition {
        final List<Entity> members;
        final Map<Entity, Long> marks;

        ListPartition(List<Entity> members, Map<Entity, Long> marks) {
            this.members = members;
            this.marks = marks;
        }

        @Override protected List<Entity> candidates() { return members; }
        @Override public boolean owns(Entity entity) { return members.contains(entity); }
        @Override public boolean ownsChunk(ServerLevel level, int cx, int cz) { return true; }
        @Override public String describe() { return "test"; }

        @Override
        protected FunctionPassMark.Claim claim(Entity entity, long passId) {
            long mark = marks.getOrDefault(entity, FunctionPassMark.OWED);
            if (mark == passId) return FunctionPassMark.Claim.SAME;
            if (mark != FunctionPassMark.OWED) return FunctionPassMark.Claim.TAKEN;
            marks.put(entity, passId);
            return FunctionPassMark.Claim.NEW;
        }
    }

    private static final class World {
        final MinecraftServer server;
        final TestLevel level;
        final ServerScoreboard scoreboard;
        final List<Entity> markers = new ArrayList<>();

        World(int entities) throws Exception {
            FunctionPlanFixture.bootstrap();
            server = (MinecraftServer) UNSAFE.allocateInstance(TestDedicatedServer.class);
            scoreboard = new ServerScoreboard(server);
            set(MinecraftServer.class, server, "scoreboard", scoreboard);
            level = (TestLevel) UNSAFE.allocateInstance(TestLevel.class);
            level.rules = new GameRules(FeatureFlagSet.of());
            set(ServerLevel.class, level, "server", server);
            for (String obj : new String[]{"a", "b", "c"}) {
                scoreboard.addObjective(obj, ObjectiveCriteria.DUMMY, Component.literal(obj),
                        ObjectiveCriteria.RenderType.INTEGER, false, null);
            }
            for (int i = 0; i < entities; i++) {
                Entity e = new net.minecraft.world.entity.Marker(EntityTypes.MARKER, level);
                if (i % 2 == 0) e.addTag("t");
                scoreboard.getOrCreatePlayerScore(e, objective("a")).set(i + 1);
                scoreboard.getOrCreatePlayerScore(e, objective("b")).set(10 * i);
                markers.add(e);
            }
            scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly("#k"), objective("c")).set(3);
        }

        Objective objective(String name) {
            return scoreboard.getObjective(name);
        }

        CommandSourceStack root() {
            return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, level,
                    PermissionSet.ALL_PERMISSIONS, Component.literal("Server"), server)
                    .withSuppressedOutput();
        }

        String state() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < markers.size(); i++) {
                Entity e = markers.get(i);
                sb.append(i).append(':')
                        .append(scoreboard.getOrCreatePlayerScore(e, objective("a")).get()).append(',')
                        .append(scoreboard.getOrCreatePlayerScore(e, objective("b")).get()).append(',')
                        .append(e.entityTags()).append(' ');
            }
            return sb.toString();
        }
    }

    private static final String[] ROOT = {
            "execute as @e[type=marker,tag=t] run scoreboard players operation @s b += @s a",
            "execute as @e[type=marker,scores={b=20..}] run tag @s add big",
            "execute if score #k c matches 3 as @e[type=marker,tag=!t] run scoreboard players operation @s a *= #k c",
            "execute as @e[type=marker,tag=big] run scoreboard players operation @s a += @s b"
    };

    private static FunctionPlan.RootPlan plan() {
        FunctionPlanFixture f = new FunctionPlanFixture()
                .function("pack:tick", ROOT)
                .tag("minecraft:tick", "pack:tick");
        FunctionPlan.RootPlan root = f.plan().tickRoots.getFirst();
        assertTrue(root.partitioned(), root.blocking());
        return root;
    }

    private static void run(World w, FunctionPlan.RootPlan root, List<FunctionPartition.Pass> passes) {
        for (FunctionPartition.Pass pass : passes) {
            for (FunctionPlan.LinePlan line : root.lines()) {
                IslandFunctionHead.runLine(line, w.root(), IslandFunctionHead.fanOutOf(pass));
            }
        }
    }

    @Test
    void partitionedPassesLeaveTheStateOfOneSerialPass() throws Exception {
        FunctionPlan.RootPlan root = plan();
        World serial = new World(9);
        World split = new World(9);

        run(serial, root, List.of(new ListPartition(serial.markers, new HashMap<>()).open(-1, false)));
        HashMap<Entity, Long> marks = new HashMap<>();
        List<Entity> m = split.markers;
        run(split, root, List.of(
                new ListPartition(List.of(m.get(4), m.get(0), m.get(7)), marks).open(1, false),
                new ListPartition(List.of(m.get(1), m.get(5)), marks).open(2, false),
                new ListPartition(List.of(m.get(2), m.get(3), m.get(6), m.get(8)), marks).open(3, false)));

        assertEquals(serial.state(), split.state());
        assertTrue(serial.state().contains("big"), serial.state());
    }

    @Test
    void anEntityGetsOnePassBetweenTwoOfItsTicks() throws Exception {
        FunctionPlan.RootPlan root = plan();
        World once = new World(4);
        World twice = new World(4);
        run(once, root, List.of(new ListPartition(once.markers, new HashMap<>()).open(-7, false)));

        HashMap<Entity, Long> marks = new HashMap<>();
        // An island pass, then a membership change hands the same entities to Main
        // before they tick: Main's pass must not take them again.
        FunctionPartition.Pass island = new ListPartition(twice.markers, marks).open(5, false);
        FunctionPartition.Pass main = new ListPartition(twice.markers, marks).open(-7, true);
        assertTrue(main.isEmpty());
        run(twice, root, List.of(island, main));
        assertEquals(once.state(), twice.state());

        // After a tick the entity owes the next pass again.
        marks.clear();
        assertEquals(4, new ListPartition(twice.markers, marks).open(-8, false).entities.size());
    }

    /**
     * An entity ticked by MAIN_SYNC whose Main tick lags its island: head U_n
     * passes it, U_{n+1} finds it TAKEN (T_n not run yet), and the Main-sync
     * body of T_{n+1} must recover the missing pass before that tick — one pass
     * per tick, never two between ticks. Island and Main-sync passes use the
     * same claim protocol (FunctionPartition.open).
     */
    @Test
    void mainSyncBodyRecoversThePassALaggingMainTickMissed() throws Exception {
        World w = new World(1);
        Entity e = w.markers.getFirst();
        HashMap<Entity, Long> marks = new HashMap<>();
        java.util.function.LongFunction<Integer> pass =
                id -> new ListPartition(List.of(e), marks).open(id, false).entities.size();
        Runnable tick = () -> marks.remove(e);

        int passes = pass.apply(1);   // island head U_n
        passes += pass.apply(2);      // island head U_{n+1}: T_n still queued on Main
        assertEquals(1, passes);
        passes += pass.apply(3);      // Main-sync body of T_n: already passed
        tick.run();                   // T_n
        assertEquals(1, passes);
        passes += pass.apply(4);      // Main-sync body of T_{n+1}: recovers the pass
        tick.run();                   // T_{n+1}
        assertEquals(2, passes, "one pass per tick across the lag");
        passes += pass.apply(5);      // next island head
        assertEquals(3, passes);
    }

    @Test
    void loopRearmKeepsVanillaScheduleReplaceSemantics() throws Exception {
        FunctionPlanFixture f = new FunctionPlanFixture()
                .function("pack:loop", "execute as @e[type=marker] run tag @s add l",
                        "schedule function pack:loop 1t");
        FunctionPlan plan = f.plan();
        FunctionPlan.RootPlan loop = plan.partitionedLoop(Identifier.parse("pack:loop"));
        World w = new World(1);
        TimerQueue<MinecraftServer> queue = new TimerQueue<>();
        set(MinecraftServer.class, w.server, "scheduledEvents", queue);
        installFunctions(w.server, Map.of(Identifier.parse("pack:loop"), f.fn("pack:loop")));
        queue.schedule("pack:loop", 11L, new net.minecraft.world.level.timers.FunctionCallback(
                Identifier.parse("pack:loop")));
        queue.schedule("pack:loop", 40L, new net.minecraft.world.level.timers.FunctionCallback(
                Identifier.parse("pack:loop")));

        FunctionPlan.LinePlan rearm = loop.lines().get(1);
        assertEquals(FunctionPlan.LineKind.REARM, rearm.kind());
        IslandFunctionHead.runRearm(rearm, w.root());

        // replace: every pending event of the id is dropped, one remains at game time + 1.
        assertEquals(1, queue.remove("pack:loop"));
        IslandFunctionHead.runRearm(rearm, w.root());
        queue.tick(w.server, 10L);
        assertTrue(queue.getEventsIds().contains("pack:loop"), "not due before game time + 1");

        // Arming follows the TimerQueue: a loop that did not fire this tick is disarmed.
        FunctionAnalyzer.activateInitial(FunctionAnalyzer.emptyPrepared(), plan);
        IslandFunctionHead.beginTimerTick();
        IslandFunctionHead.endTimerTick(w.server);
        assertTrue(IslandFunctionHead.armedLoops().isEmpty());
    }

    private static void installFunctions(MinecraftServer server,
                                         Map<Identifier, CommandFunction<CommandSourceStack>> functions)
            throws Exception {
        ServerFunctionLibrary library = (ServerFunctionLibrary) UNSAFE.allocateInstance(ServerFunctionLibrary.class);
        set(ServerFunctionLibrary.class, library, "functions", functions);
        set(ServerFunctionLibrary.class, library, "tags", Map.of());
        ServerFunctionManager manager = (ServerFunctionManager) UNSAFE.allocateInstance(ServerFunctionManager.class);
        set(ServerFunctionManager.class, manager, "library", library);
        set(ServerFunctionManager.class, manager, "server", server);
        set(MinecraftServer.class, server, "functionManager", manager);
    }

    /** Only environmental inputs are substituted; command execution stays vanilla. */
    private static final class TestLevel extends ServerLevel {
        GameRules rules;

        private TestLevel() {
            super(null, null, null, null, null, null, false, 0L, List.of(), false);
            throw new AssertionError("allocated without starting a world");
        }

        @Override public long getGameTime() { return 10L; }
        @Override public GameRules getGameRules() { return rules; }
        @Override public FeatureFlagSet enabledFeatures() { return FeatureFlags.VANILLA_SET; }
        int nextId;
        @Override public int getNextEntityId() { return ++nextId; }
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static sun.misc.Unsafe unsafe() {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
