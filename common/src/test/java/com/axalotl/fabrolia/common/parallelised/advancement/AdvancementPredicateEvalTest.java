package com.axalotl.fabrolia.common.parallelised.advancement;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated
@Timeout(20)
final class AdvancementPredicateEvalTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private record Item(Object owner, int entries, int id) {}

    private static final AdvancementPredicateEval.Shape<Item> SHAPE =
            new AdvancementPredicateEval.Shape<>() {
                @Override
                public Object owner(Item item) {
                    return item.owner;
                }

                @Override
                public int entries(Item item) {
                    return item.entries;
                }
            };

    /** One token per task body; the inline path binds it on the caller. */
    private static final ThreadLocal<Object> TASK = new ThreadLocal<>();
    private static final Consumer<Runnable> TASK_SCOPE = body -> {
        TASK.set(new Object());
        try {
            body.run();
        } finally {
            TASK.remove();
        }
    };

    private static boolean decide(Item item, int entry) {
        return (item.id * 31 + entry) % 3 == 0;
    }

    private static int[][] serial(Item[] items) {
        int[][] expected = new int[items.length][];
        for (int i = 0; i < items.length; i++) {
            ArrayList<Integer> hits = new ArrayList<>();
            for (int e = 0; e < items[i].entries; e++) {
                if (decide(items[i], e)) hits.add(e);
            }
            expected[i] = hits.isEmpty() ? null : hits.stream().mapToInt(Integer::intValue).toArray();
        }
        return expected;
    }

    private static Item[] items(int n, int owners, long seed) {
        Random random = new Random(seed);
        Object[] ownerIds = new Object[owners];
        for (int o = 0; o < owners; o++) ownerIds[o] = new Object();
        Item[] items = new Item[n];
        for (int i = 0; i < n; i++) {
            items[i] = new Item(ownerIds[random.nextInt(owners)], random.nextInt(7), i);
        }
        return items;
    }

    @Test
    void fanOutReportsMatchesInSerialOrderAndKeepsEachOwnerInOneTask() throws Exception {
        Item[] items = items(600, 24, 7L);
        Map<Object, Object> ownerTask = new ConcurrentHashMap<>();
        Map<Object, AtomicInteger> inflight = new ConcurrentHashMap<>();
        AtomicInteger overlaps = new AtomicInteger();
        AdvancementPredicateEval.EntryPredicate<Item> predicate = (item, entry) -> {
            Object task = TASK.get();
            Object first = ownerTask.putIfAbsent(item.owner, task);
            if (first != null && first != task) overlaps.addAndGet(1_000_000);
            AtomicInteger busy = inflight.computeIfAbsent(item.owner, k -> new AtomicInteger());
            if (busy.incrementAndGet() > 1) overlaps.incrementAndGet();
            Thread.onSpinWait();
            busy.decrementAndGet();
            return decide(item, entry);
        };
        try (Pool pool = new Pool(4)) {
            int[][] actual = AdvancementPredicateEval.evaluate(items, items.length, SHAPE, predicate, TASK_SCOPE);
            assertArrayEquals(serial(items), actual, "matches must be keyed by serial pending index");
            assertEquals(0, overlaps.get(), "one owner must never be evaluated by two tasks or concurrently");
            IdentityHashMap<Object, Boolean> tasks = new IdentityHashMap<>();
            for (Object task : ownerTask.values()) tasks.put(task, Boolean.TRUE);
            assertTrue(tasks.size() > 1, "a batch above the grain must actually fan out");
            assertTrue(tasks.size() <= 8, "at most 2 × parallelism tasks");
        }
    }

    @Test
    void batchesBelowTheGrainOrWithOneOwnerStayInlineOnTheCaller() throws Exception {
        Object owner = new Object();
        Item[] single = new Item[40];
        for (int i = 0; i < single.length; i++) single[i] = new Item(owner, 5, i);
        Item[] tiny = items(4, 3, 3L);
        Map<Object, Thread> threads = new ConcurrentHashMap<>();
        AdvancementPredicateEval.EntryPredicate<Item> predicate = (item, entry) -> {
            threads.put(item.owner, Thread.currentThread());
            return decide(item, entry);
        };
        try (Pool pool = new Pool(4)) {
            assertArrayEquals(serial(single),
                    AdvancementPredicateEval.evaluate(single, single.length, SHAPE, predicate, TASK_SCOPE));
            assertArrayEquals(serial(tiny),
                    AdvancementPredicateEval.evaluate(tiny, tiny.length, SHAPE, predicate, TASK_SCOPE));
            for (Thread thread : threads.values()) assertSame(Thread.currentThread(), thread);
        }
    }

    @Test
    void aFailingPredicateFailsTheWholeFanInWithItsExactPrimary() throws Exception {
        Item[] items = items(300, 12, 11L);
        IllegalStateException boom = new IllegalStateException("predicate boom");
        AdvancementPredicateEval.EntryPredicate<Item> predicate = (item, entry) -> {
            if (item.id == 150) throw boom;
            return decide(item, entry);
        };
        Object previousFailure = get(RegionScheduler.class, "orphanFatalFailure");
        try (Pool pool = new Pool(4)) {
            RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                    AdvancementPredicateEval.evaluate(items, items.length, SHAPE, predicate, TASK_SCOPE));
            assertSame(boom, thrown, "the task primary must surface unchanged on Main");
            assertSame(boom, get(RegionScheduler.class, "orphanFatalFailure"),
                    "a task failure is published as the generation fatal");
        } finally {
            set(RegionScheduler.class, "orphanFatalFailure", previousFailure);
        }
    }

    private static final class Pool implements AutoCloseable {
        private final ExecutorService previous = ParallelProcessor.executor;
        private final ForkJoinPool pool;

        Pool(int parallelism) {
            pool = new ForkJoinPool(parallelism);
            ParallelProcessor.executor = pool;
        }

        @Override
        public void close() {
            ParallelProcessor.executor = previous;
            pool.shutdownNow();
        }
    }

    private static Object get(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void set(Class<?> type, String name, Object value) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
