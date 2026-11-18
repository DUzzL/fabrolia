package com.axalotl.fabrolia.common.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Drives the production flattened startEachNonRunningBehavior and running list against vanilla's loop shape. */
class BrainFlatBehaviorTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void activityLeavingDuringAnEntryStillStartsThatEntrysRemainingBehaviors() throws Exception {
        BrainMixin<LivingEntity> brain = new BrainMixin<>();
        Set<Activity> active = new HashSet<>(Set.of(Activity.CORE, Activity.IDLE));
        List<String> started = new ArrayList<>();
        Recording first = new Recording("first", started, () -> active.remove(Activity.CORE));
        Recording second = new Recording("second", started, null);
        Recording later = new Recording("later", started, null);
        Recording idle = new Recording("idle", started, null);

        Map<Integer, Map<Activity, Set<BehaviorControl<? super LivingEntity>>>> byPriority = new TreeMap<>();
        byPriority.put(0, entry(Activity.CORE, first, second));
        Map<Activity, Set<BehaviorControl<? super LivingEntity>>> priorityOne = new HashMap<>();
        priorityOne.putAll(entry(Activity.CORE, later));
        priorityOne.putAll(entry(Activity.IDLE, idle));
        byPriority.put(1, priorityOne);
        set(brain, "availableBehaviorsByPriority", byPriority);
        set(brain, "activeActivities", active);

        start(brain, level());

        // Vanilla reads activeActivities once per (priority, activity) entry:
        // the CORE entry that was active when reached runs to its end, the
        // later CORE entry sees it inactive, IDLE is unaffected.
        assertEquals(List.of("first", "second", "idle"), started);
    }

    @Test
    void runningListMatchesVanillasWalkOverEveryActivityInOrder() throws Exception {
        BrainMixin<LivingEntity> brain = new BrainMixin<>();
        List<String> started = new ArrayList<>();
        Recording a = new Recording("a", started, null).status(Behavior.Status.RUNNING);
        Recording b = new Recording("b", started, null);
        Recording c = new Recording("c", started, null).status(Behavior.Status.RUNNING);
        Recording d = new Recording("d", started, null).status(Behavior.Status.RUNNING);
        Recording e = new Recording("e", started, null).status(Behavior.Status.RUNNING);

        Map<Integer, Map<Activity, Set<BehaviorControl<? super LivingEntity>>>> byPriority = new TreeMap<>();
        byPriority.put(5, entry(Activity.REST, e));
        Map<Activity, Set<BehaviorControl<? super LivingEntity>>> priorityZero = new HashMap<>();
        priorityZero.putAll(entry(Activity.CORE, a, b));
        priorityZero.putAll(entry(Activity.WORK, c));
        byPriority.put(0, priorityZero);
        byPriority.put(1, entry(Activity.IDLE, d));
        set(brain, "availableBehaviorsByPriority", byPriority);
        // Only IDLE is active: vanilla collects running behaviors of inactive activities too.
        set(brain, "activeActivities", new HashSet<>(Set.of(Activity.IDLE)));

        // Vanilla's getRunningBehaviors(): priorities ascending, the inner map's own order,
        // each set in insertion order, RUNNING only.
        List<BehaviorControl<? super LivingEntity>> vanilla = new ArrayList<>();
        for (Map<Activity, Set<BehaviorControl<? super LivingEntity>>> byActivity : byPriority.values()) {
            for (Set<BehaviorControl<? super LivingEntity>> behaviors : byActivity.values()) {
                for (BehaviorControl<? super LivingEntity> behavior : behaviors) {
                    if (behavior.getStatus() == Behavior.Status.RUNNING) vanilla.add(behavior);
                }
            }
        }

        List<BehaviorControl<? super LivingEntity>> flat = running(brain);
        assertEquals(vanilla, flat);
        assertEquals(4, flat.size());

        // The list is a snapshot: a behavior that stops while the list is walked stays in it,
        // and the next call reads the statuses again.
        c.status(Behavior.Status.STOPPED);
        assertEquals(4, flat.size());
        assertEquals(3, running(brain).size());
    }

    @SuppressWarnings("unchecked")
    private static List<BehaviorControl<? super LivingEntity>> running(BrainMixin<LivingEntity> brain) throws Exception {
        Method method = BrainMixin.class.getDeclaredMethod("fabrolia$runningFromFlat",
                net.minecraft.world.entity.ai.Brain.class, Operation.class);
        method.setAccessible(true);
        return (List<BehaviorControl<? super LivingEntity>>) method.invoke(brain, null, null);
    }

    private static Map<Activity, Set<BehaviorControl<? super LivingEntity>>> entry(
            Activity activity, Recording... behaviors) {
        Set<BehaviorControl<? super LivingEntity>> set = new LinkedHashSet<>(List.of(behaviors));
        Map<Activity, Set<BehaviorControl<? super LivingEntity>>> map = new HashMap<>();
        map.put(activity, set);
        return map;
    }

    private static void start(BrainMixin<LivingEntity> brain, ServerLevel level) throws Exception {
        Method method = BrainMixin.class.getDeclaredMethod("fabrolia$startEachFlat",
                ServerLevel.class, LivingEntity.class, Operation.class);
        method.setAccessible(true);
        method.invoke(brain, level, null, null);
    }

    private static ServerLevel level() throws Exception {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
        ServerLevel level = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
        Field levelData = Level.class.getDeclaredField("levelData");
        levelData.setAccessible(true);
        levelData.set(level, unsafe.allocateInstance(PrimaryLevelData.class));
        return level;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = BrainMixin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class Recording implements BehaviorControl<LivingEntity> {
        private final String name;
        private final List<String> started;
        private final Runnable onStart;

        Recording(String name, List<String> started, Runnable onStart) {
            this.name = name;
            this.started = started;
            this.onStart = onStart;
        }

        private Behavior.Status status = Behavior.Status.STOPPED;

        Recording status(Behavior.Status status) {
            this.status = status;
            return this;
        }

        @Override public Behavior.Status getStatus() { return status; }
        @Override public Set<MemoryModuleType<?>> getRequiredMemories() { return Set.of(); }

        @Override
        public boolean tryStart(ServerLevel level, LivingEntity body, long time) {
            started.add(name);
            if (onStart != null) onStart.run();
            return false;
        }

        @Override public void tickOrStop(ServerLevel level, LivingEntity body, long time) { }
        @Override public void doStop(ServerLevel level, LivingEntity body, long time) { }
        @Override public String debugString() { return name; }
    }
}
