package com.axalotl.fabrolia.common.mixin.entity.sensor;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.NearestItemSensor;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the actual mixin body; only the query result and the externally observable hooks are supplied. */
class NearestItemSensorTest {
    private static MethodHandle tick;
    private static sun.misc.Unsafe unsafe;
    private static Field position;

    @BeforeAll
    static void setup() throws ReflectiveOperationException {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        unsafe = (sun.misc.Unsafe) field.get(null);
        position = Entity.class.getDeclaredField("position");
        position.setAccessible(true);
        tick = MethodHandles.privateLookupIn(NearestItemSensorMixin.class, MethodHandles.lookup())
                .findVirtual(NearestItemSensorMixin.class, "doTick",
                        MethodType.methodType(void.class, ServerLevel.class, Mob.class, Operation.class));
    }

    @Test
    void stableTiesAndShortCircuitDoNotModifyWorldsEncounterOrder() throws ReflectiveOperationException {
        Fixture f = new Fixture();
        Item winner = f.item("tie-A", 4);
        Item tied = f.item("tie-B", -4);
        Item invisible = f.item("hidden", 3);
        invisible.visible = false;
        Item distant = f.item("range", 2);
        distant.near = false;
        Item rejected = f.item("reject", 1);
        rejected.wanted = false;
        Item far = f.item("far", 20);
        List<Item> order = List.of(far, winner, tied, far, invisible, far, rejected, distant, far);
        f.level.items = new ArrayList<>(order);
        f.run();
        assertSame(winner, f.mob.memory.value.orElseThrow());
        assertEquals(order, f.level.items);
        assertEquals(List.of("brain", "query", "position", "get reject", "want reject", "get range", "want range",
                "near range", "get hidden", "want hidden", "near hidden", "see hidden", "get tie-A", "want tie-A",
                "near tie-A", "see tie-A", "publish"), f.trace);

        // Vanilla sorts the private query result in place instead of snapshotting.
        // The existing radix sorter reads the body's position once; compare gameplay hooks.
        List<String> expectedHooks = f.trace.stream().filter(s -> !s.equals("position")).toList();
        f.level.items = new ArrayList<>(order);
        f.trace.clear();
        new VanillaSensor().run(f);
        assertSame(winner, f.mob.memory.value.orElseThrow());
        assertEquals(expectedHooks, f.trace);
    }

    private static final class VanillaSensor extends NearestItemSensor {
        void run(Fixture f) { doTick(f.level, f.mob); }
    }

    @Test
    void customArrayListUsesOnlyItsOwnUntypedSnapshot() throws ReflectiveOperationException {
        Fixture f = new Fixture();
        Item winner = f.item("modded", 0);
        f.level.items = new ArrayList<>() {
            @Override public Object[] toArray() { f.trace.add("snapshot"); return new Object[]{winner}; }
            @Override public <T> T[] toArray(T[] a) { throw new AssertionError("typed snapshot"); }
            @Override public int size() { throw new AssertionError("extra size read"); }
            @Override public Item get(int i) { throw new AssertionError("indexed observation"); }
        };
        f.run();
        assertSame(winner, f.mob.memory.value.orElseThrow());
        assertEquals(List.of("brain", "query", "snapshot", "position", "get modded", "want modded",
                "near modded", "see modded", "publish"), f.trace);
    }

    @Test
    void customSnapshotFailureStopsBeforeAnyMobPositionOrPredicate() throws ReflectiveOperationException {
        Fixture f = new Fixture();
        Error fatal = new LinkageError("snapshot");
        f.level.items = new ArrayList<>() {
            @Override public Object[] toArray() { throw fatal; }
            @Override public int size() { throw new AssertionError("extra size read"); }
        };
        assertSame(fatal, assertThrows(LinkageError.class, f::run));
        assertEquals(List.of("brain", "query"), f.trace);
        assertNull(f.mob.memory.value);
    }

    @Test
    void nestedSensorAndWorldListMutationCannotReplaceOuterSnapshot() throws ReflectiveOperationException {
        Fixture outer = new Fixture(), inner = new Fixture();
        Item reject = outer.item("reject", 1), winner = outer.item("outer", 2);
        Item nested = inner.item("inner", 0);
        inner.level.items = new ArrayList<>(List.of(nested));
        outer.level.items = new ArrayList<>(List.of(winner, reject));
        reject.near = false;
        reject.onRange = () -> {
            outer.level.items.clear();
            inner.run();
        };
        outer.run();
        assertSame(winner, outer.mob.memory.value.orElseThrow());
        assertSame(nested, inner.mob.memory.value.orElseThrow());
    }

    @Test
    void overflowEmptyAndOneKeepTheirExactSizesAndClearRetainedSlots() throws ReflectiveOperationException {
        Fixture f = new Fixture();
        Item winner = f.item("wanted", 0), reject = f.item("rejected", 0);
        reject.wanted = false;
        f.level.items = new ArrayList<>(List.of(winner, reject));
        f.run();

        f.level.items = new ArrayList<>(java.util.Collections.nCopies(1024, reject));
        f.level.items.add(winner);
        f.run();
        assertSame(winner, f.mob.memory.value.orElseThrow());
        assertTrue(cachedCandidates().length < f.level.items.size(), "do not retain the overflow allocation");
        Optional<?> oldMemory = f.mob.memory.value;
        f.level.items.clear();
        f.run();
        assertTrue(f.mob.memory.value.isEmpty());
        assertSame(winner, oldMemory.orElseThrow());
        f.level.items.add(winner);
        f.run();
        assertSame(winner, f.mob.memory.value.orElseThrow());
        for (Object slot : cachedCandidates()) assertNull(slot);
    }

    @Test
    void nestedHookFailurePropagatesByIdentityAndReleasesTheOuterBorrow() throws ReflectiveOperationException {
        Fixture outer = new Fixture(), inner = new Fixture();
        Item a = outer.item("outer", 1), b = inner.item("inner", 0);
        outer.level.items = new ArrayList<>(List.of(a, a));
        inner.level.items = new ArrayList<>(List.of(b));
        outer.run();
        Optional<?> prior = outer.mob.memory.value;
        Error fatal = new LinkageError("hook");
        b.onRange = () -> { throw fatal; };
        a.onRange = inner::run;
        assertSame(fatal, assertThrows(LinkageError.class, outer::run));
        assertSame(prior, outer.mob.memory.value);
        assertNull(inner.mob.memory.value);
        for (Object slot : cachedCandidates()) assertNull(slot);
        a.onRange = null;
        outer.run();
        assertSame(a, outer.mob.memory.value.orElseThrow());
    }

    @Test
    void otherWorkerFinishingInsideOuterHookHasItsOwnStorage() throws ReflectiveOperationException {
        Fixture outer = new Fixture(), other = new Fixture();
        Item reject = outer.item("reject", 1), winner = outer.item("outer", 2);
        Item remote = other.item("worker", 0);
        other.level.items = new ArrayList<>(List.of(remote));
        outer.level.items = new ArrayList<>(List.of(reject, winner));
        outer.run();
        reject.near = false;
        try (var worker = Executors.newSingleThreadExecutor()) {
            reject.onRange = () -> {
                try {
                    worker.submit(other::run).get(10, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            };
            outer.run();
        }
        assertSame(winner, outer.mob.memory.value.orElseThrow());
        assertSame(remote, other.mob.memory.value.orElseThrow());
    }

    // Inspect only idle storage: deterministic checks for leaks, without forcing a GC.
    private static Object[] cachedCandidates() throws ReflectiveOperationException {
        Field idle = NearestItemSensorMixin.class.getDeclaredField("fabrolia$idleCandidates");
        idle.setAccessible(true);
        return (Object[]) ((ThreadLocal<?>) idle.get(null)).get();
    }

    private static final class Fixture {
        final List<String> trace = new ArrayList<>();
        final Level level = allocate(Level.class);
        final Body mob = allocate(Body.class);

        Fixture() throws ReflectiveOperationException {
            level.trace = trace;
            mob.trace = trace;
            mob.memory = new Memory(trace);
            mob.setBoundingBox(new AABB(0, 0, 0, 1, 1, 1));
            position.set(mob, Vec3.ZERO);
        }

        Item item(String name, double x) throws ReflectiveOperationException {
            Item item = allocate(Item.class);
            item.body = mob;
            item.name = name;
            item.wanted = item.near = item.visible = true;
            position.set(item, new Vec3(x, 0, 0));
            return item;
        }

        void run() {
            try {
                tick.invokeExact(new NearestItemSensorMixin(), (ServerLevel) level, (Mob) mob, (Operation<Void>) null);
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Throwable unexpected) {
                throw new AssertionError(unexpected);
            }
        }
    }

    private static <T> T allocate(Class<T> type) throws InstantiationException {
        return type.cast(unsafe.allocateInstance(type));
    }

    private static final class Level extends ServerLevel {
        private Level() { super(null, null, null, null, null, null, false, 0, null, false); }
        List<String> trace;
        List<Item> items;

        @Override @SuppressWarnings("unchecked")
        public <T extends Entity> List<T> getEntitiesOfClass(Class<T> type, AABB box, Predicate<? super T> filter) {
            assertSame(ItemEntity.class, type);
            assertEquals(new AABB(-32, -16, -32, 33, 17, 33), box);
            trace.add("query");
            return (List<T>) items;
        }
    }

    private static final class Memory extends Brain<Piglin> {
        final List<String> trace;
        Optional<?> value;
        Memory(List<String> trace) { this.trace = trace; }
        @Override public <U> void setMemory(MemoryModuleType<U> type, Optional<? extends U> value) {
            assertSame(MemoryModuleType.NEAREST_VISIBLE_WANTED_ITEM, type);
            trace.add("publish");
            this.value = value;
        }
    }

    private static final class Body extends Piglin {
        private Body() { super(null, null); }
        List<String> trace;
        Memory memory;
        Item current;

        @Override public Brain<Piglin> getBrain() { trace.add("brain"); return memory; }
        @Override public Vec3 position() { trace.add("position"); return Vec3.ZERO; }
        @Override public boolean wantsToPickUp(ServerLevel level, ItemStack stack) {
            trace.add("want " + current.name);
            return current.wanted;
        }
        @Override public boolean hasLineOfSight(Entity entity) {
            Item item = (Item) entity;
            trace.add("see " + item.name);
            return item.visible;
        }
    }

    private static final class Item extends ItemEntity {
        private Item() { super(null, null); }
        Body body;
        String name;
        boolean wanted, near, visible;
        Runnable onRange;

        @Override public ItemStack getItem() {
            body.trace.add("get " + name);
            body.current = this;
            return ItemStack.EMPTY;
        }
        @Override public boolean closerThan(Entity other, double distance) {
            assertSame(body, other);
            assertEquals(32.0, distance);
            body.trace.add("near " + name);
            if (onRange != null) onRange.run();
            return near;
        }
    }
}
