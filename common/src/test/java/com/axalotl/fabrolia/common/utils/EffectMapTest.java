package com.axalotl.fabrolia.common.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class EffectMapTest {
    @Test
    void currentEntryCanBeReplacedThenRemovedThroughTheSameIterator() {
        EffectMap<Integer, String> map = new EffectMap<>();
        map.put(1, "old");
        synchronized (map) {
            Iterator<Map.Entry<Integer, String>> iterator = map.entrySet().iterator();
            iterator.next().setValue("replacement");
            iterator.remove();
            assertTrue(map.isEmpty());
        }
    }

    @Test
    void ownedTraversalObservesReplacementAndCanContinueAfterItsOwnRemoval() {
        EffectMap<Integer, String> map = new EffectMap<>();
        map.put(1, "first");
        map.put(2, "second");
        synchronized (map) {
            Iterator<Map.Entry<Integer, String>> iterator = map.entrySet().iterator();
            int first = iterator.next().getKey();
            iterator.remove();
            int remaining = first == 1 ? 2 : 1;
            map.replace(remaining, "replacement");
            Map.Entry<Integer, String> next = iterator.next();
            assertEquals(remaining, next.getKey());
            assertEquals("replacement", next.getValue(), "a prefetched value must not hide nonstructural replacement");
            iterator.remove();
            assertFalse(iterator.hasNext());
            assertTrue(map.isEmpty());
        }
    }

    @Test
    void removeAndReinsertInvalidatesAnOwnedCursorEvenWhenSizeAndValueMatch() {
        EffectMap<Integer, String> map = new EffectMap<>();
        map.put(1, "first");
        map.put(2, "second");
        synchronized (map) {
            Iterator<Integer> iterator = map.keySet().iterator();
            int retained = iterator.next();
            int changed = retained == 1 ? 2 : 1;
            String previous = map.get(changed);
            map.compute(changed, (key, value) -> null);
            map.merge(changed, previous, (left, right) -> right);
            assertThrows(ConcurrentModificationException.class, iterator::next);
            assertThrows(ConcurrentModificationException.class, iterator::remove);
            assertTrue(map.containsKey(retained), "a stale remove must not delete its previously returned key");
        }
    }

    @Test
    void insertionDuringExpirationDoesNotRetainTheCurrentRegistration() {
        EffectMap<Integer, String> map = new EffectMap<>();
        map.put(1, "old");
        map.put(2, "waiting");
        synchronized (map) {
            Iterator<Integer> iterator = map.keySet().iterator();
            int current = iterator.next();
            assertTrue(iterator.hasNext(), "the original traversal has a prefetched successor");
            map.put(3, "successor");
            iterator.remove();
            assertFalse(map.containsKey(current));
            assertEquals(2, map.size());
            assertEquals("successor", map.get(3));
            assertThrows(ConcurrentModificationException.class, iterator::next,
                    "insertion must not admit a successor into this effect turn");
        }
    }

    @Test
    void conditionalViewRemovalDoesNotDeleteAValueReplacedByItsPredicate() {
        EffectMap<String, String> map = new EffectMap<>();
        map.put("effect", "old");
        assertFalse(map.values().removeIf(value -> {
            map.replace("effect", "updated");
            return true;
        }));
        assertEquals(Map.of("effect", "updated"), map);
        assertFalse(map.entrySet().removeIf(entry -> {
            entry.setValue("updated-again");
            return true;
        }));
        assertEquals(Map.of("effect", "updated-again"), map);
    }

    @Test
    void compoundMutationCannotEnterItsCallbackInsideAnotherOwnedTurn() throws Exception {
        EffectMap<String, String> map = new EffectMap<>();
        CountDownLatch attempted = new CountDownLatch(1);
        AtomicBoolean callbackEntered = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            attempted.countDown();
            try {
                map.computeIfAbsent("effect", key -> {
                    callbackEntered.set(true);
                    return "installed";
                });
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "effect-map-computation");
        try {
            synchronized (map) {
                writer.start();
                assertTrue(attempted.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!callbackEntered.get() && writer.getState() != Thread.State.BLOCKED
                        && writer.isAlive() && System.nanoTime() < deadline) Thread.onSpinWait();
                assertFalse(callbackEntered.get(), "ConcurrentMap defaults must not run the callback before acquiring the mutation monitor");
                assertEquals(Thread.State.BLOCKED, writer.getState());
            }
        } finally {
            writer.join(5000);
        }
        assertFalse(writer.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertTrue(callbackEntered.get());
        assertEquals(Map.of("effect", "installed"), map);
    }
}
