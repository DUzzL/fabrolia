package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.entity.NativeGoalDependencyAccessors;
import com.axalotl.fabrolia.common.mixin.entity.NativeNautilusProgramAccessors;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.ActivityData;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.AnimalMakeLove;
import net.minecraft.world.entity.ai.behavior.AnimalPanic;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.ChargeAttack;
import net.minecraft.world.entity.ai.behavior.CountDownCooldownTicks;
import net.minecraft.world.entity.ai.behavior.FollowTemptation;
import net.minecraft.world.entity.ai.behavior.GateBehavior;
import net.minecraft.world.entity.ai.behavior.LookAtTargetSink;
import net.minecraft.world.entity.ai.behavior.MoveToTargetSink;
import net.minecraft.world.entity.ai.behavior.RandomStroll;
import net.minecraft.world.entity.ai.behavior.SetWalkTargetFromLookTarget;
import net.minecraft.world.entity.ai.behavior.ShufflingList;
import net.minecraft.world.entity.ai.behavior.StartAttacking;
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.behavior.declarative.MemoryCondition;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraft.world.entity.ai.sensing.AdultSensor;
import net.minecraft.world.entity.ai.sensing.HurtBySensor;
import net.minecraft.world.entity.ai.sensing.NearestLivingEntitySensor;
import net.minecraft.world.entity.ai.sensing.PlayerSensor;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.ai.sensing.SensorType;
import net.minecraft.world.entity.ai.sensing.TemptingSensor;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.animal.nautilus.Nautilus;
import net.minecraft.world.entity.animal.nautilus.NautilusAi;
import net.minecraft.world.entity.schedule.Activity;

/**
 * Provenance of one actual native Nautilus installation, never gameplay authority.
 * The public methods are dedicated mixin publication seams; admission is package-private.
 * Reflection below reads only the three enumerated declarative node schemas and their
 * hidden final captures. No callback, factory, second Brain, or debug supplier is run.
 */
public final class NativeNautilusProgram {
    private static final ThreadLocal<Installation> CONSTRUCTION = new ThreadLocal<>();
    private static final ThreadLocal<SensorOrder> SENSOR_ORDER = ThreadLocal.withInitial(SensorOrder::new);
    private static TargetingConditions chargeConditions;
    private static Object chargeSelector;
    private static long chargeRevision;

    private NativeNautilusProgram() {}

    /** Accessed only after the caller has established actual source ownership. */
    static Snapshot match(Nautilus source) {
        if (source.getClass() != Nautilus.class) return null;
        Brain<?> brain = ((NativeNautilusProgramAccessors.Source) source).fabrolia$nautilusStoredBrain();
        if (brain == null || brain.getClass() != Brain.class) return null;
        Snapshot snapshot = ((BrainBinding) brain).fabrolia$nautilusProgram();
        return snapshot != null && snapshot.source == source && snapshot.isCurrent() ? snapshot : null;
    }

    public interface BrainBinding {
        Snapshot fabrolia$nautilusProgram();
        long fabrolia$nautilusProgramRevision();
        void fabrolia$bindNautilusProgram(Snapshot snapshot);
    }

    public interface GateBinding {
        void fabrolia$bindNautilusGate(Brain<?> brain);
    }

    public static Installation begin(Nautilus source) {
        Installation installation = new Installation(source, CONSTRUCTION.get());
        CONSTRUCTION.set(installation);
        return installation;
    }

    /** Called exclusively around Nautilus's actual BRAIN_PROVIDER invocation. */
    public static final class Installation implements AutoCloseable {
        private final Nautilus source;
        private final Installation previous;
        private final Object[] roots = new Object[11];
        private List<ActivityData<Nautilus>> activities;
        private Object followingSpeed, followingDistance, finder, food;
        private Object start, startCondition, startFinder;
        private Object swim, swimPosition, swimCondition, swimAtCallsite;
        private Object walk, walkCondition, walkSpeed, walkAtCallsite;
        private boolean valid = true;
        private boolean finished;

        private Installation(Nautilus source, Installation previous) {
            this.source = source;
            this.previous = previous;
        }

        public void bindReturnedBrain(Brain<?> brain) {
            if (finished || CONSTRUCTION.get() != this) return;
            finished = true;
            if (!valid || brain == null || brain.getClass() != Brain.class
                    || ((BrainBinding) brain).fabrolia$nautilusProgram() != null) return;
            try {
                Snapshot snapshot = new Snapshot(this, brain);
                ((BrainBinding) brain).fabrolia$bindNautilusProgram(snapshot);
                ((GateBinding) snapshot.children).fabrolia$bindNautilusGate(brain);
            } catch (UnsupportedProgram ignored) {
                // A different installation remains an ordinary Main-only Brain.
            }
        }

        @Override
        public void close() {
            if (CONSTRUCTION.get() != this) throw new IllegalStateException("Nautilus installation scope mismatch");
            if (previous == null) CONSTRUCTION.remove();
            else CONSTRUCTION.set(previous);
        }
    }

    public static <T> T root(int slot, T root) {
        Installation construction = CONSTRUCTION.get();
        if (construction != null) {
            if (slot < 0 || slot >= construction.roots.length || construction.roots[slot] != null) construction.valid = false;
            else construction.roots[slot] = root;
        }
        return root;
    }

    public static void following(Object root, Object speed, Object distance) {
        root(7, root);
        Installation construction = CONSTRUCTION.get();
        if (construction != null) {
            construction.followingSpeed = speed;
            construction.followingDistance = distance;
        }
    }

    public static void attacking(Object root, Object finder) {
        root(8, root);
        Installation construction = CONSTRUCTION.get();
        if (construction != null) construction.finder = finder;
    }

    public static void swimming(Object root, float speed) {
        Installation construction = CONSTRUCTION.get();
        if (construction != null) {
            if (construction.swimAtCallsite != null || speed != 1.0F) construction.valid = false;
            construction.swimAtCallsite = root;
        }
    }

    public static void walking(Object root, float speed, int distance) {
        Installation construction = CONSTRUCTION.get();
        if (construction != null) {
            if (construction.walkAtCallsite != null || speed != 1.0F || distance != 3) construction.valid = false;
            construction.walkAtCallsite = root;
        }
    }

    public static void startFactory(Object root, Object condition, Object finder) {
        Installation construction = CONSTRUCTION.get();
        if (construction != null) {
            if (construction.start != null) construction.valid = false;
            construction.start = root;
            construction.startCondition = condition;
            construction.startFinder = finder;
        }
    }

    /** This seam is specifically RandomStroll.swim -> strollFlyOrSwim, never stroll/fly. */
    public static void swimFactory(Object root, float speed, Object position, Object condition) {
        Installation construction = CONSTRUCTION.get();
        if (construction != null) {
            if (construction.swim != null || speed != 1.0F) construction.valid = false;
            construction.swim = root;
            construction.swimPosition = position;
            construction.swimCondition = condition;
        }
    }

    public static void walkFactory(Object root, Object condition, Object speed, int distance) {
        Installation construction = CONSTRUCTION.get();
        if (construction != null) {
            if (construction.walk != null || distance != 3) construction.valid = false;
            construction.walk = root;
            construction.walkCondition = condition;
            construction.walkSpeed = speed;
        }
    }

    public static void activities(List<ActivityData<Nautilus>> activities) {
        Installation construction = CONSTRUCTION.get();
        if (construction != null) {
            if (construction.activities != null) construction.valid = false;
            construction.activities = activities;
        }
    }

    public static void food(Object predicate) {
        Installation construction = CONSTRUCTION.get();
        if (construction != null) {
            if (construction.food != null) construction.valid = false;
            construction.food = predicate;
        }
    }

    /** Captures the actual NautilusAi static selector callsite, not a host-name guess. */
    public static void chargeConditions(TargetingConditions conditions, Object selector) {
        chargeConditions = conditions;
        chargeSelector = selector;
    }

    public static void beforeTargetingMutation(TargetingConditions conditions) {
        if (conditions == chargeConditions) chargeRevision++;
    }

    /** Immutable identities/configuration; ordinary gameplay state stays in the native objects. */
    public static final class Snapshot {
        private final Nautilus source;
        private final Brain<?> brain;
        private final long brainRevision;
        private final NativeNautilusProgramAccessors.Program program;
        private final Object[] roots;
        private final Sensor<?>[] sensors;
        private final Object[] requirements;
        private final ShufflingList<?> children;
        private final Object firstChild, secondChild;
        private final Retained[] retained;
        private final Object panicDamage, panicPosition, followingSpeed, followingDistance, temptations;
        private final TargetingConditions targeting;
        private final Object selector;
        private final long targetingRevision;

        private Snapshot(Installation installation, Brain<?> brain) {
            source = installation.source;
            this.brain = brain;
            brainRevision = ((BrainBinding) brain).fabrolia$nautilusProgramRevision();
            program = (NativeNautilusProgramAccessors.Program) brain;
            roots = installation.roots.clone();
            for (int index = 0; index < roots.length; index++) {
                require(roots[index] != null);
                if (index != 8) require(roots[index].getClass() == Defaults.ROOT_TYPES[index]);
            }
            require(nativeClass(Nautilus.class) && nativeClass(Brain.class));
            require(installation.start == roots[8] && installation.startFinder == installation.finder);
            require(installation.swim != null && installation.swim == installation.swimAtCallsite);
            require(installation.walk != null && installation.walk == installation.walkAtCallsite);
            firstChild = installation.swim;
            secondChild = installation.walk;
            children = ((NativeNautilusProgramAccessors.Gate) roots[9]).fabrolia$nautilusGateChildren();
            require(children != null && children.getClass() == ShufflingList.class);

            ArrayList<Retained> links = new ArrayList<>();
            zeroCapture(installation.finder, NautilusAi.class, links);
            zeroCapture(installation.startCondition, StartAttacking.class, links);
            zeroCapture(installation.swimPosition, RandomStroll.class, links);
            zeroCapture(installation.swimCondition, RandomStroll.class, links);
            zeroCapture(installation.walkCondition, SetWalkTargetFromLookTarget.class, links);
            lambda(installation.walkSpeed, SetWalkTargetFromLookTarget.class, links, 1.0F);
            oneShot(roots[8], false, MemoryModuleType.ATTACK_TARGET, MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE,
                    false, StartAttacking.class, links, installation.startCondition, installation.finder);
            oneShot(firstChild, true, MemoryModuleType.WALK_TARGET, null, false, RandomStroll.class,
                    links, installation.swimCondition, installation.swimPosition, 1.0F);
            oneShot(secondChild, false, MemoryModuleType.WALK_TARGET, MemoryModuleType.LOOK_TARGET,
                    true, SetWalkTargetFromLookTarget.class, links, installation.walkCondition, installation.walkSpeed, 3);

            var panic = (NativeNautilusProgramAccessors.Panic) roots[0];
            panicDamage = panic.fabrolia$nautilusPanicDamage();
            panicPosition = panic.fabrolia$nautilusPanicPosition();
            zeroCapture(panicDamage, AnimalPanic.class, links);
            zeroCapture(panicPosition, AnimalPanic.class, links);
            followingSpeed = installation.followingSpeed;
            followingDistance = installation.followingDistance;
            zeroCapture(followingSpeed, NautilusAi.class, links);
            zeroCapture(followingDistance, NautilusAi.class, links);
            targeting = chargeConditions;
            selector = chargeSelector;
            targetingRevision = chargeRevision;
            require(targeting != null && targeting.getClass() == TargetingConditions.class);
            zeroCapture(selector, NautilusAi.class, links);

            Map<SensorType<?>, Sensor<?>> sensorMap = program.fabrolia$nautilusSensors();
            require(sensorMap != null && sensorMap.getClass() == LinkedHashMap.class && sensorMap.size() == 5);
            sensors = new Sensor<?>[5];
            int sensorIndex = 0;
            for (var entry : sensorMap.entrySet()) {
                require(entry.getKey() == Defaults.SENSOR_TYPES[sensorIndex]);
                Sensor<?> sensor = entry.getValue();
                require(sensor != null && sensor.getClass() == Defaults.SENSOR_CLASSES[sensorIndex]
                        && nativeClass(sensor.getClass()));
                require(((NativeNautilusProgramAccessors.Cadence) sensor).fabrolia$nautilusScanRate() == 20);
                sensors[sensorIndex++] = sensor;
            }
            temptations = ((NativeNautilusProgramAccessors.Temptations) sensors[4]).fabrolia$nautilusTemptations();
            zeroCapture(installation.food, NautilusAi.class, links);
            lambda(temptations, TemptingSensor.class, links, installation.food);

            requirements = new Object[3];
            require(installation.activities != null && safeImmutableList(installation.activities)
                    && installation.activities.size() == 3);
            for (int activityIndex = 0; activityIndex < 3; activityIndex++) {
                ActivityData<?> activity = installation.activities.get(activityIndex);
                require(activity != null && activity.getClass() == ActivityData.class
                        && activity.activityType() == Defaults.ACTIVITIES[activityIndex]);
                var pairs = activity.behaviorPriorityPairs();
                require(safeImmutableList(pairs));
                int start = activityIndex == 0 ? 0 : activityIndex == 1 ? 6 : 10;
                int count = activityIndex == 0 ? 6 : activityIndex == 1 ? 4 : 1;
                require(pairs.size() == count);
                for (int i = 0; i < count; i++) {
                    Pair<?, ?> pair = pairs.get(i);
                    require(pair != null && pair.getClass() == Pair.class
                            && pair.getFirst() instanceof Integer priority
                            && priority == Defaults.PRIORITIES[start + i] && pair.getSecond() == roots[start + i]);
                }
                Set<?> conditions = activity.conditions();
                require(safeImmutableSet(conditions));
                if (activityIndex != 2) require(conditions.isEmpty());
                else {
                    require(conditions.size() == 4);
                    for (Object condition : conditions) {
                        require(condition != null && condition.getClass() == Pair.class);
                        Pair<?, ?> pair = (Pair<?, ?>) condition;
                        Object memory = pair.getFirst(), status = pair.getSecond();
                        require(memory == MemoryModuleType.ATTACK_TARGET && status == MemoryStatus.VALUE_PRESENT
                                || (memory == MemoryModuleType.TEMPTING_PLAYER || memory == MemoryModuleType.BREED_TARGET
                                    || memory == MemoryModuleType.CHARGE_COOLDOWN_TICKS) && status == MemoryStatus.VALUE_ABSENT);
                    }
                }
                requirements[activityIndex] = conditions;
                Set<?> erased = activity.memoriesToEraseWhenStopped();
                require(erased != null && (erased.getClass() == HashSet.class || safeImmutableSet(erased)) && erased.isEmpty());
            }
            for (int i = 0; i < roots.length; i++) {
                if (i == 8 || i == 9) continue;
                var timed = (NativeNautilusProgramAccessors.Timed) roots[i];
                require(immutableMemoryMap(timed.fabrolia$nautilusEntry(), Defaults.ENTRY[i]));
            }
            var gate = (NativeNautilusProgramAccessors.Gate) roots[9];
            require(immutableMemoryMap(gate.fabrolia$nautilusGateEntry(), Defaults.GATE_ENTRY));
            require(safeImmutableSet(gate.fabrolia$nautilusGateExit()) && gate.fabrolia$nautilusGateExit().isEmpty());
            retained = links.toArray(Retained[]::new);
            require(configurationCurrent() && topologyCurrent());
        }

        public ChargeAttack charge() { return (ChargeAttack) roots[10]; }
        public MoveToTargetSink movement() { return (MoveToTargetSink) roots[2]; }
        public TemptingSensor tempting() { return (TemptingSensor) sensors[4]; }

        /** No graph reconstruction/list copies, no behavior/selector callbacks. */
        public boolean isCurrent() {
            if (!bound() || !configurationCurrent() || !topologyCurrent()) return false;
            for (Retained field : retained) if (!field.matches()) return false;
            return true;
        }

        private boolean bound() {
            BrainBinding binding = (BrainBinding) brain;
            return ((NativeNautilusProgramAccessors.Source) source).fabrolia$nautilusStoredBrain() == brain
                    && binding.fabrolia$nautilusProgram() == this
                    && binding.fabrolia$nautilusProgramRevision() == brainRevision && targetingRevision == chargeRevision;
        }

        public boolean idleAndChargeStopped() {
            if (!isCurrent()) return false;
            Set<Activity> active = program.fabrolia$nautilusActive();
            return active.getClass() == HashSet.class && active.size() == 2
                    && active.contains(Activity.CORE) && active.contains(Activity.IDLE)
                    && ((NativeNautilusProgramAccessors.Timed) charge()).fabrolia$nautilusStatus() == Behavior.Status.STOPPED;
        }

        /** The receiver is fixed by this source binding, never supplied by the caller. */
        public boolean visibleMemoryMatches(NearestVisibleLivingEntities visible) {
            if (!bound()) return false;
            if (visible == null || visible.getClass() != NearestVisibleLivingEntities.class) return false;
            if (visible == NearestVisibleLivingEntities.empty()) return true;
            if (!(source.level() instanceof ServerLevel level)) return false;
            var access = (NativeNautilusProgramAccessors.Visible) visible;
            if (!safeVisibleList(access.fabrolia$nautilusVisibleList())) return false;
            Object predicate = access.fabrolia$nautilusVisiblePredicate();
            if (!hiddenFrom(predicate, NearestVisibleLivingEntities.class)) return false;
            Field[] outer = FINAL_FIELDS.get(predicate.getClass());
            if (outer == null || outer.length != 2) return false;
            try {
                Object first = outer[0].get(predicate), second = outer[1].get(predicate);
                Object target = first != null && first.getClass() == Object2BooleanOpenHashMap.class ? second
                        : second != null && second.getClass() == Object2BooleanOpenHashMap.class ? first : null;
                if (!hiddenFrom(target, NearestVisibleLivingEntities.class)) return false;
                Field[] captures = FINAL_FIELDS.get(target.getClass());
                if (captures == null || captures.length != 2) return false;
                Object a = captures[0].get(target), b = captures[1].get(target);
                return a == source && b == level || a == level && b == source;
            } catch (IllegalAccessException incompatible) {
                return false;
            }
        }

        private boolean configurationCurrent() {
            for (int i = 0; i < roots.length; i++) {
                if (i == 8 || i == 9) continue;
                var timed = (NativeNautilusProgramAccessors.Timed) roots[i];
                if (timed.fabrolia$nautilusMinDuration() != Defaults.MIN_DURATION[i]
                        || timed.fabrolia$nautilusMaxDuration() != Defaults.MAX_DURATION[i]
                        || !immutableMemoryMap(timed.fabrolia$nautilusEntry(), Defaults.ENTRY[i])) return false;
            }
            var panic = (NativeNautilusProgramAccessors.Panic) roots[0];
            if (panic.fabrolia$nautilusPanicSpeed() != 1.6F || panic.fabrolia$nautilusPanicDamage() != panicDamage
                    || panic.fabrolia$nautilusPanicPosition() != panicPosition) return false;
            for (int i = 3; i <= 5; i++) {
                if (((NativeNautilusProgramAccessors.Cooldown) roots[i]).fabrolia$nautilusCooldown() != Defaults.COOLDOWNS[i - 3]) return false;
            }
            var breeding = (NativeNautilusProgramAccessors.Breeding) roots[6];
            if (breeding.fabrolia$nautilusPartnerType() != EntityTypes.NAUTILUS
                    || breeding.fabrolia$nautilusBreedingSpeed() != 0.4F || breeding.fabrolia$nautilusBreedingDistance() != 2) return false;
            var following = (NativeNautilusProgramAccessors.Following) roots[7];
            if (following.fabrolia$nautilusFollowingSpeed() != followingSpeed
                    || following.fabrolia$nautilusFollowingDistance() != followingDistance || following.fabrolia$nautilusFollowingEyes()) return false;
            var charge = (NativeNautilusProgramAccessors.Charge) roots[10];
            if (charge.fabrolia$nautilusAttackInterval() != 80 || charge.fabrolia$nautilusChargeTargeting() != targeting
                    || charge.fabrolia$nautilusChargeSpeed() != 0.6F || charge.fabrolia$nautilusKnockback() != 2.0F
                    || charge.fabrolia$nautilusChargeDistance() != 12.0 || charge.fabrolia$nautilusDetectionDistance() != 11.0
                    || charge.fabrolia$nautilusChargeSound() != SoundEvents.NAUTILUS_DASH) return false;
            var conditions = (NativeGoalDependencyAccessors.Targeting) targeting;
            if (!conditions.fabrolia$queryCombat() || conditions.fabrolia$queryRange() != -1.0
                    || !conditions.fabrolia$queryLineOfSight() || !conditions.fabrolia$queryTestInvisible()
                    || conditions.fabrolia$querySelector() != selector) return false;
            for (Sensor<?> sensor : sensors) {
                if (((NativeNautilusProgramAccessors.Cadence) sensor).fabrolia$nautilusScanRate() != 20) return false;
            }
            return ((NativeNautilusProgramAccessors.Temptations) sensors[4]).fabrolia$nautilusTemptations() == temptations;
        }

        private boolean topologyCurrent() {
            Map<Integer, Map<Activity, Set<BehaviorControl<?>>>> priorities = program.fabrolia$nautilusBehaviors();
            if (priorities == null || priorities.getClass() != TreeMap.class || ((TreeMap<?, ?>) priorities).comparator() != null
                    || priorities.size() != 6) return false;
            for (int priority = 0; priority < 6; priority++) {
                Map<Activity, Set<BehaviorControl<?>>> activities = priorities.get(priority);
                int expected = priority < 5 ? 2 : 1;
                if (activities == null || activities.getClass() != HashMap.class || activities.size() != expected) return false;
                if (!singleRoot(activities.get(Activity.CORE), roots[priority])) return false;
                if (priority == 0 && !singleRoot(activities.get(Activity.FIGHT), roots[10])) return false;
                if (priority > 0 && priority < 5 && !singleRoot(activities.get(Activity.IDLE), roots[priority + 5])) return false;
            }
            Map<Activity, Set<Pair<MemoryModuleType<?>, MemoryStatus>>> requirements = program.fabrolia$nautilusRequirements();
            if (requirements == null || requirements.getClass() != HashMap.class || requirements.size() != 3) return false;
            for (int i = 0; i < 3; i++) if (requirements.get(Defaults.ACTIVITIES[i]) != this.requirements[i]) return false;
            Map<?, ?> exits = program.fabrolia$nautilusExitMemories();
            if (exits == null || exits.getClass() != HashMap.class || !exits.isEmpty()) return false;
            Set<Activity> core = program.fabrolia$nautilusCore();
            if (!safeImmutableSet(core) || core.size() != 1 || !core.contains(Activity.CORE)
                    || program.fabrolia$nautilusDefault() != Activity.IDLE || program.fabrolia$nautilusSchedule() != null) return false;
            Set<Activity> active = program.fabrolia$nautilusActive();
            if (active == null || active.getClass() != HashSet.class || active.size() != 2 || !active.contains(Activity.CORE)
                    || !(active.contains(Activity.IDLE) || active.contains(Activity.FIGHT))) return false;
            var gate = (NativeNautilusProgramAccessors.Gate) roots[9];
            if (gate.fabrolia$nautilusGateChildren() != children || gate.fabrolia$nautilusGateOrder() != GateBehavior.OrderPolicy.ORDERED
                    || gate.fabrolia$nautilusGateRunning() != GateBehavior.RunningPolicy.TRY_ALL
                    || !immutableMemoryMap(gate.fabrolia$nautilusGateEntry(), Defaults.GATE_ENTRY)
                    || !safeImmutableSet(gate.fabrolia$nautilusGateExit()) || !gate.fabrolia$nautilusGateExit().isEmpty()) return false;
            List<? extends ShufflingList.WeightedEntry<?>> entries = ((NativeNautilusProgramAccessors.Weighted) children).fabrolia$nautilusWeightedEntries();
            if (entries == null || entries.getClass() != ArrayList.class || entries.size() != 2
                    || !weightedEntry(entries.get(0), firstChild, 2) || !weightedEntry(entries.get(1), secondChild, 3)) return false;
            Map<SensorType<?>, Sensor<?>> sensorMap = program.fabrolia$nautilusSensors();
            if (sensorMap == null || sensorMap.getClass() != LinkedHashMap.class || sensorMap.size() != 5) return false;
            // LinkedHashMap.forEach walks insertion order without allocating an iterator.
            SensorOrder order = SENSOR_ORDER.get();
            if (order.snapshot != null) return false;
            order.snapshot = this;
            order.index = 0;
            order.matches = true;
            try {
                sensorMap.forEach(order);
                return order.matches && order.index == 5;
            } finally {
                order.snapshot = null;
            }
        }
    }

    private static final class SensorOrder implements BiConsumer<SensorType<?>, Sensor<?>> {
        private Snapshot snapshot;
        private int index;
        private boolean matches;

        @Override
        public void accept(SensorType<?> type, Sensor<?> sensor) {
            if (index >= 5 || type != Defaults.SENSOR_TYPES[index] || sensor != snapshot.sensors[index]) matches = false;
            index++;
        }
    }

    private static boolean singleRoot(Set<?> set, Object root) {
        return set != null && set.getClass() == LinkedHashSet.class && set.size() == 1 && set.contains(root);
    }

    private static boolean weightedEntry(ShufflingList.WeightedEntry<?> entry, Object child, int weight) {
        return entry != null && entry.getClass() == ShufflingList.WeightedEntry.class
                && entry.getData() == child && entry.getWeight() == weight;
    }

    private static final ClassValue<Boolean> NATIVE_CLASS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            var nativeSource = Entity.class.getProtectionDomain().getCodeSource();
            var source = type.getProtectionDomain().getCodeSource();
            return source != null && nativeSource != null && source.equals(nativeSource)
                    && type.getClassLoader() == Entity.class.getClassLoader();
        }
    };

    private static boolean nativeClass(Class<?> type) { return NATIVE_CLASS.get(type); }

    private static boolean hiddenFrom(Object value, Class<?> host) {
        return value != null && value.getClass().isHidden() && value.getClass().getNestHost() == host && nativeClass(host);
    }

    private static final ClassValue<Field[]> FINAL_FIELDS = new ClassValue<>() {
        @Override
        protected Field[] computeValue(Class<?> type) {
            Field[] fields = type.getDeclaredFields();
            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers()) || !Modifier.isFinal(field.getModifiers()) || !field.trySetAccessible()) return null;
            }
            return fields;
        }
    };

    /** One final field of the enumerated schemas, retained once at installation. */
    private static final class Retained {
        private final Object receiver;
        private final Field field;
        private final Object value;
        private final int primitive;

        private Retained(Object receiver, Field field) {
            this.receiver = receiver;
            this.field = field;
            try {
                if (field.getType() == float.class) {
                    primitive = Float.floatToRawIntBits(field.getFloat(receiver));
                    value = null;
                } else if (field.getType() == int.class) {
                    primitive = field.getInt(receiver);
                    value = null;
                } else {
                    require(!field.getType().isPrimitive());
                    value = field.get(receiver);
                    primitive = 0;
                }
            } catch (IllegalAccessException incompatible) {
                throw UnsupportedProgram.INSTANCE;
            }
        }

        private boolean matches() {
            try {
                if (field.getType() == float.class) return Float.floatToRawIntBits(field.getFloat(receiver)) == primitive;
                if (field.getType() == int.class) return field.getInt(receiver) == primitive;
                return field.get(receiver) == value;
            } catch (IllegalAccessException incompatible) {
                return false;
            }
        }
    }

    private static void zeroCapture(Object callback, Class<?> host, ArrayList<Retained> retained) {
        lambda(callback, host, retained);
    }

    private static void lambda(Object callback, Class<?> host, ArrayList<Retained> retained, Object... expected) {
        require(hiddenFrom(callback, host));
        Field[] fields = FINAL_FIELDS.get(callback.getClass());
        require(fields != null && fields.length == expected.length);
        boolean[] matched = new boolean[expected.length];
        for (Field field : fields) {
            Object value = fieldValue(field, callback);
            boolean found = false;
            for (int i = 0; i < expected.length; i++) {
                if (!matched[i] && (field.getType().isPrimitive() ? value.equals(expected[i]) : value == expected[i])) {
                    matched[i] = true;
                    found = true;
                    break;
                }
            }
            require(found);
            retained.add(new Retained(callback, field));
        }
    }

    private static Object fieldValue(Field field, Object receiver) {
        try {
            return field.get(receiver);
        } catch (IllegalAccessException incompatible) {
            throw UnsupportedProgram.INSTANCE;
        }
    }

    private static Object nodeField(Object node, String suffix, String fieldName, int fieldCount, ArrayList<Retained> retained) {
        require(node != null && node.getClass().getName().equals(BehaviorBuilder.class.getName() + suffix) && nativeClass(node.getClass()));
        Field[] fields = FINAL_FIELDS.get(node.getClass());
        require(fields != null && fields.length == fieldCount);
        for (Field field : fields) {
            if (field.getName().equals(fieldName)) {
                retained.add(new Retained(node, field));
                return fieldValue(field, node);
            }
        }
        throw UnsupportedProgram.INSTANCE;
    }

    private static void memoryNode(Object node, MemoryModuleType<?> memory, int kind, ArrayList<Retained> retained) {
        Object condition = nodeField(node, "$PureMemory$1", "val$condition", 1, retained);
        require(condition != null);
        if (kind == 0) require(condition.getClass() == MemoryCondition.Absent.class && ((MemoryCondition.Absent<?>) condition).memory() == memory);
        else if (kind == 1) require(condition.getClass() == MemoryCondition.Present.class && ((MemoryCondition.Present<?>) condition).memory() == memory);
        else require(condition.getClass() == MemoryCondition.Registered.class && ((MemoryCondition.Registered<?>) condition).memory() == memory);
        Field[] fields = FINAL_FIELDS.get(condition.getClass());
        require(fields != null && fields.length == 1 && fieldValue(fields[0], condition) == memory);
        retained.add(new Retained(condition, fields[0]));
    }

    private static void oneShot(Object root, boolean unary, MemoryModuleType<?> first, MemoryModuleType<?> second,
            boolean secondPresent, Class<?> host, ArrayList<Retained> retained, Object... leaves) {
        Object resolved = nodeField(root, "$1", "val$resolvedBuilder", 1, retained);
        String suffix = unary ? "$Instance$1" : "$Instance$3";
        int count = unary ? 2 : 3;
        memoryNode(nodeField(resolved, suffix, "val$aTrigger", count, retained), first, 0, retained);
        if (!unary) memoryNode(nodeField(resolved, suffix, "val$bTrigger", count, retained), second, secondPresent ? 1 : 2, retained);
        Object constant = nodeField(resolved, suffix, "val$fTrigger", count, retained);
        Object function = nodeField(constant, "$Constant$1", "val$a", 2, retained);
        Object debug = nodeField(constant, "$Constant$1", "val$debugString", 2, retained);
        lambda(debug, BehaviorBuilder.class, retained, function);
        if (host != SetWalkTargetFromLookTarget.class) lambda(function, host, retained, leaves);
        else {
            require(hiddenFrom(function, host));
            Field[] fields = FINAL_FIELDS.get(function.getClass());
            require(fields != null && fields.length == 4);
            Object instance = null;
            for (Field field : fields) {
                Object value = fieldValue(field, function);
                if (value != null && value.getClass() == BehaviorBuilder.Instance.class) {
                    require(instance == null && FINAL_FIELDS.get(value.getClass()) != null && FINAL_FIELDS.get(value.getClass()).length == 0);
                    instance = value;
                }
            }
            require(instance != null);
            lambda(function, host, retained, leaves[0], instance, leaves[1], leaves[2]);
        }
    }

    private static boolean safeImmutableList(Object list) {
        if (list == null) return false;
        Class<?> type = list.getClass();
        return type == Defaults.JDK_LIST_EMPTY || type == Defaults.JDK_LIST_SMALL || type == Defaults.JDK_LIST_LARGE
                || type == Defaults.GUAVA_LIST_EMPTY || type == Defaults.GUAVA_LIST_SMALL || type == Defaults.GUAVA_LIST_LARGE;
    }

    private static boolean safeVisibleList(Object list) {
        return list != null && (list.getClass() == ArrayList.class || list.getClass() == ObjectArrayList.class || safeImmutableList(list));
    }

    private static boolean safeImmutableSet(Object set) {
        if (set == null) return false;
        Class<?> type = set.getClass();
        return type == Defaults.GUAVA_SET_EMPTY || type == Defaults.GUAVA_SET_SMALL || type == Defaults.GUAVA_SET_LARGE;
    }

    private static boolean immutableMemoryMap(Map<MemoryModuleType<?>, MemoryStatus> actual,
            ImmutableList<Map.Entry<MemoryModuleType<?>, MemoryStatus>> expected) {
        if (actual == null) return false;
        Class<?> type = actual.getClass();
        if (type != Defaults.JDK_MAP_SMALL && type != Defaults.JDK_MAP_LARGE
                && type != Defaults.GUAVA_MAP_EMPTY && type != Defaults.GUAVA_MAP_SMALL && type != Defaults.GUAVA_MAP_LARGE) return false;
        if (actual.size() != expected.size()) return false;
        for (int i = 0; i < expected.size(); i++) {
            var entry = expected.get(i);
            if (actual.get(entry.getKey()) != entry.getValue()) return false;
        }
        return true;
    }

    private static void require(boolean supported) {
        if (!supported) throw UnsupportedProgram.INSTANCE;
    }

    private static final class UnsupportedProgram extends RuntimeException {
        private static final UnsupportedProgram INSTANCE = new UnsupportedProgram();
        private UnsupportedProgram() { super(null, null, false, false); }
    }

    /** Lazily initialized after native static factories finish; these are data, not a mirror Brain. */
    private static final class Defaults {
        private static final Class<?> JDK_LIST_EMPTY = List.of().getClass();
        private static final Class<?> JDK_LIST_SMALL = List.of(1).getClass();
        private static final Class<?> JDK_LIST_LARGE = List.of(1, 2, 3).getClass();
        private static final Class<?> GUAVA_LIST_EMPTY = ImmutableList.of().getClass();
        private static final Class<?> GUAVA_LIST_SMALL = ImmutableList.of(1).getClass();
        private static final Class<?> GUAVA_LIST_LARGE = ImmutableList.of(1, 2, 3).getClass();
        private static final Class<?> GUAVA_SET_EMPTY = ImmutableSet.of().getClass();
        private static final Class<?> GUAVA_SET_SMALL = ImmutableSet.of(1).getClass();
        private static final Class<?> GUAVA_SET_LARGE = ImmutableSet.of(1, 2, 3).getClass();
        private static final Class<?> JDK_MAP_SMALL = Map.of(1, 1).getClass();
        private static final Class<?> JDK_MAP_LARGE = Map.of(1, 1, 2, 2).getClass();
        private static final Class<?> GUAVA_MAP_EMPTY = ImmutableMap.of().getClass();
        private static final Class<?> GUAVA_MAP_SMALL = ImmutableMap.of(1, 1).getClass();
        private static final Class<?> GUAVA_MAP_LARGE = ImmutableMap.of(1, 1, 2, 2).getClass();
        private static final Activity[] ACTIVITIES = {Activity.CORE, Activity.IDLE, Activity.FIGHT};
        private static final int[] PRIORITIES = {0, 1, 2, 3, 4, 5, 1, 2, 3, 4, 0};
        private static final int[] MIN_DURATION = {100, 45, 150, 60, 60, 60, 110, 60, 0, 0, 60};
        private static final int[] MAX_DURATION = {120, 90, 250, 60, 60, 60, 110, 60, 0, 0, 60};
        private static final Class<?>[] ROOT_TYPES = {AnimalPanic.class, LookAtTargetSink.class, MoveToTargetSink.class,
                CountDownCooldownTicks.class, CountDownCooldownTicks.class, CountDownCooldownTicks.class,
                AnimalMakeLove.class, FollowTemptation.class, null, GateBehavior.class, ChargeAttack.class};
        private static final SensorType<?>[] SENSOR_TYPES = {SensorType.NEAREST_LIVING_ENTITIES, SensorType.NEAREST_ADULT,
                SensorType.NEAREST_PLAYERS, SensorType.HURT_BY, SensorType.NAUTILUS_TEMPTATIONS};
        private static final Class<?>[] SENSOR_CLASSES = {NearestLivingEntitySensor.class, AdultSensor.class,
                PlayerSensor.class, HurtBySensor.class, TemptingSensor.class};
        private static final MemoryModuleType<?>[] COOLDOWNS = {MemoryModuleType.TEMPTATION_COOLDOWN_TICKS,
                MemoryModuleType.CHARGE_COOLDOWN_TICKS, MemoryModuleType.ATTACK_TARGET_COOLDOWN};
        private static final ImmutableList<Map.Entry<MemoryModuleType<?>, MemoryStatus>> GATE_ENTRY =
                ImmutableMap.<MemoryModuleType<?>, MemoryStatus>of(MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT).entrySet().asList();
        private static final ImmutableList<ImmutableMap<MemoryModuleType<?>, MemoryStatus>> ENTRIES = ImmutableList.of(
                ImmutableMap.of(MemoryModuleType.IS_PANICKING, MemoryStatus.REGISTERED, MemoryModuleType.HURT_BY, MemoryStatus.REGISTERED),
                ImmutableMap.of(MemoryModuleType.LOOK_TARGET, MemoryStatus.VALUE_PRESENT),
                ImmutableMap.of(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE, MemoryStatus.REGISTERED, MemoryModuleType.PATH, MemoryStatus.VALUE_ABSENT, MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_PRESENT),
                ImmutableMap.of(MemoryModuleType.TEMPTATION_COOLDOWN_TICKS, MemoryStatus.VALUE_PRESENT),
                ImmutableMap.of(MemoryModuleType.CHARGE_COOLDOWN_TICKS, MemoryStatus.VALUE_PRESENT),
                ImmutableMap.of(MemoryModuleType.ATTACK_TARGET_COOLDOWN, MemoryStatus.VALUE_PRESENT),
                ImmutableMap.of(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES, MemoryStatus.VALUE_PRESENT, MemoryModuleType.BREED_TARGET, MemoryStatus.VALUE_ABSENT,
                        MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED, MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED, MemoryModuleType.IS_PANICKING, MemoryStatus.VALUE_ABSENT),
                ImmutableMap.<MemoryModuleType<?>, MemoryStatus>builder().put(MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED).put(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED)
                        .put(MemoryModuleType.TEMPTATION_COOLDOWN_TICKS, MemoryStatus.VALUE_ABSENT).put(MemoryModuleType.IS_TEMPTED, MemoryStatus.VALUE_ABSENT)
                        .put(MemoryModuleType.TEMPTING_PLAYER, MemoryStatus.VALUE_PRESENT).put(MemoryModuleType.BREED_TARGET, MemoryStatus.VALUE_ABSENT).put(MemoryModuleType.IS_PANICKING, MemoryStatus.VALUE_ABSENT).build(),
                ImmutableMap.of(), ImmutableMap.of(),
                ImmutableMap.of(MemoryModuleType.CHARGE_COOLDOWN_TICKS, MemoryStatus.VALUE_ABSENT, MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_PRESENT));
        private static final ImmutableList<Map.Entry<MemoryModuleType<?>, MemoryStatus>>[] ENTRY = entries();

        @SuppressWarnings("unchecked")
        private static ImmutableList<Map.Entry<MemoryModuleType<?>, MemoryStatus>>[] entries() {
            var entries = (ImmutableList<Map.Entry<MemoryModuleType<?>, MemoryStatus>>[]) new ImmutableList<?>[ENTRIES.size()];
            for (int i = 0; i < entries.length; i++) entries[i] = ENTRIES.get(i).entrySet().asList();
            return entries;
        }
    }
}
