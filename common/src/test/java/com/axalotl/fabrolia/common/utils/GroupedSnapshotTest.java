package com.axalotl.fabrolia.common.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the detached grouped-membership boundary used by
 * {@code PoiSection}. The live shape is deliberately the same dangerous pair
 * as vanilla: one {@link HashMap} whose values are mutable hash sets.
 */
@Timeout(30)
class GroupedSnapshotTest {

    @Test
    void captureIsEagerDetachedOrderedAndImmutable() {
        Object monitor = new Object();
        Map<String, LinkedHashSet<Integer>> live = new LinkedHashMap<>();
        live.put("beds", new LinkedHashSet<>(List.of(3, 1, 2)));
        live.put("jobs", new LinkedHashSet<>(List.of(8, 5)));

        GroupedSnapshot<String, Integer> snapshot =
                GroupedSnapshot.capture(monitor, live);
        Iterator<Integer> escaped = snapshot.stream(key -> true, value -> true).iterator();

        synchronized (monitor) {
            live.get("beds").clear();
            live.get("beds").add(99);
            live.remove("jobs");
            live.put("bells", new LinkedHashSet<>(List.of(42)));
        }

        assertEquals(List.of("beds", "jobs"),
                snapshot.groups().stream().map(GroupedSnapshot.Group::key).toList(),
                "snapshot must preserve the map's raw group iteration order");
        assertEquals(List.of(3, 1, 2), snapshot.groups().get(0).values(),
                "nested membership must be copied eagerly, not observed lazily");
        assertEquals(List.of(8, 5), snapshot.groups().get(1).values());

        List<Integer> detachedTraversal = new ArrayList<>();
        escaped.forEachRemaining(detachedTraversal::add);
        assertEquals(List.of(3, 1, 2, 8, 5), detachedTraversal,
                "an iterator obtained from the snapshot must never touch the live HashMap/HashSet");

        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.groups().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.groups().get(0).values().add(100));
    }

    @Test
    void predicatesAndConsumersRunAfterTheMutationMonitorIsReleased() {
        Object monitor = new Object();
        Map<String, LinkedHashSet<Integer>> live = new LinkedHashMap<>();
        live.put("village", new LinkedHashSet<>(List.of(1, 2, 3)));
        AtomicInteger keyCalls = new AtomicInteger();
        AtomicInteger valueCalls = new AtomicInteger();
        AtomicInteger consumerCalls = new AtomicInteger();

        GroupedSnapshot.capture(monitor, live)
                .stream(key -> {
                    assertFalse(Thread.holdsLock(monitor),
                            "caller type predicates must not run under the mutation monitor");
                    keyCalls.incrementAndGet();
                    return true;
                }, value -> {
                    assertFalse(Thread.holdsLock(monitor),
                            "caller record predicates must not run under the mutation monitor");
                    valueCalls.incrementAndGet();
                    return true;
                })
                .forEach(value -> {
                    assertFalse(Thread.holdsLock(monitor),
                            "caller consumers must not run under the mutation monitor");
                    consumerCalls.incrementAndGet();
                });

        assertEquals(1, keyCalls.get());
        assertEquals(3, valueCalls.get());
        assertEquals(3, consumerCalls.get());
    }

    @Test
    void concurrentHashMapAndHashSetWriterNeverLeaksACmeIntoSnapshotReaders()
            throws Exception {
        Object monitor = new Object();
        Map<Integer, LinkedHashSet<Integer>> live = new HashMap<>();
        synchronized (monitor) {
            for (int group = 0; group < 16; group++) {
                LinkedHashSet<Integer> values = new LinkedHashSet<>();
                for (int member = 0; member < 12; member++) {
                    values.add(encoded(group, member));
                }
                live.put(group, values);
            }
        }

        ExecutorService threads = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> writer = threads.submit(() -> {
            start.await();
            for (int round = 0; round < 50_000; round++) {
                int group = round & 15;
                synchronized (monitor) {
                    LinkedHashSet<Integer> values = live.computeIfAbsent(
                            group, ignored -> new LinkedHashSet<>());
                    values.add(encoded(group, 100 + round));
                    if (values.size() > 24) {
                        values.remove(values.iterator().next());
                    }
                    if ((round & 127) == 0) {
                        live.remove((group + 7) & 15);
                    }
                }
            }
            return null;
        });
        Future<?> reader = threads.submit(() -> {
            start.await();
            for (int iteration = 0; iteration < 5_000; iteration++) {
                GroupedSnapshot<Integer, Integer> snapshot =
                        GroupedSnapshot.capture(monitor, live);
                for (GroupedSnapshot.Group<Integer, Integer> group : snapshot.groups()) {
                    for (int value : group.values()) {
                        assertEquals(group.key().intValue(), decodedGroup(value),
                                "snapshot paired a member with the wrong live bucket");
                    }
                }
                snapshot.stream(key -> true, value -> true).forEach(value -> { });
            }
            return null;
        });

        start.countDown();
        try {
            writer.get(20, TimeUnit.SECONDS);
            reader.get(20, TimeUnit.SECONDS);
        } finally {
            threads.shutdownNow();
            assertTrue(threads.awaitTermination(5, TimeUnit.SECONDS),
                    "snapshot stress threads did not terminate");
        }
    }

    private static int encoded(int group, int member) {
        return group * 1_000_000 + member;
    }

    private static int decodedGroup(int value) {
        return value / 1_000_000;
    }
}
