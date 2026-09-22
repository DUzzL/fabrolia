package com.axalotl.fabrolia.common.utils;

import com.axalotl.fabrolia.api.utils.ConcurrentList;
import org.junit.jupiter.api.Test;

import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class ConcurrentListBulkTest {
    @Test
    void selfBulkOperationsCompleteAndPreserveOrder() throws Exception {
        ConcurrentList<Integer> list = new ConcurrentList<>(List.of(1, 2));
        run(() -> {
            assertTrue(list.addAll(list));
            assertEquals(List.of(1, 2, 1, 2), list);
            assertTrue(list.addAll(1, list));
            assertEquals(List.of(1, 1, 2, 1, 2, 2, 1, 2), list);
            assertFalse(list.retainAll(list));
            assertTrue(list.removeAll(list));
            assertTrue(list.isEmpty());
        });
    }

    @Test
    void reciprocalBulkCopiesDoNotHoldDestinationWhileReadingSource() throws Exception {
        ConcurrentList<Integer> left = new ConcurrentList<>(List.of(1));
        ConcurrentList<Integer> right = new ConcurrentList<>(List.of(2));
        CountDownLatch entered = new CountDownLatch(2);
        Collection<Integer> leftSource = rendezvous(left, entered);
        Collection<Integer> rightSource = rendezvous(right, entered);
        FutureTask<Void> a = start(() -> left.addAll(0, rightSource));
        FutureTask<Void> b = start(() -> right.addAll(0, leftSource));
        a.get(5, TimeUnit.SECONDS);
        b.get(5, TimeUnit.SECONDS);
        assertTrue(left.containsAll(List.of(1, 2)));
        assertTrue(right.containsAll(List.of(1, 2)));
    }

    @Test
    void reciprocalMembershipCallbacksRunWithoutDestinationLocks() throws Exception {
        ConcurrentList<Integer> left = new ConcurrentList<>(List.of(1));
        ConcurrentList<Integer> right = new ConcurrentList<>(List.of(1));
        CountDownLatch entered = new CountDownLatch(2);
        FutureTask<Void> a = start(() -> left.removeAll(rendezvous(right, entered)));
        FutureTask<Void> b = start(() -> right.retainAll(rendezvous(left, entered)));
        a.get(5, TimeUnit.SECONDS);
        b.get(5, TimeUnit.SECONDS);
        assertTrue(left.isEmpty());
    }

    @Test
    void throwingPredicateDoesNotDuplicateRetainedElements() {
        ConcurrentList<Integer> list = new ConcurrentList<>(List.of(1, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> list.removeIf(value -> {
            if (value == 3) throw new IllegalArgumentException();
            return value == 1;
        }));
        assertEquals(List.of(1, 2, 3), list);
    }

    @Test
    void filteringKeepsConcurrentEqualReplacementAndNewOccurrences() throws Exception {
        String captured = new String("value");
        String replacement = new String("value");
        ConcurrentList<String> list = new ConcurrentList<>(List.of(captured));
        CountDownLatch selected = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        FutureTask<Void> filter = start(() -> list.removeIf(value -> {
            selected.countDown();
            await(resume);
            return true;
        }));
        try {
            await(selected);
            list.set(0, replacement);
            list.add(replacement);
        } finally {
            resume.countDown();
        }
        filter.get(5, TimeUnit.SECONDS);
        assertEquals(2, list.size());
        assertSame(replacement, list.get(0));
        assertSame(replacement, list.get(1));
    }

    @Test
    void membershipUsesSourceIdentitySemantics() {
        String a = new String("equal");
        String b = new String("equal");
        var identities = Collections.newSetFromMap(new java.util.IdentityHashMap<String, Boolean>());
        identities.add(a);
        ConcurrentList<String> list = new ConcurrentList<>(List.of(a, b));
        list.removeAll(identities);
        assertEquals(1, list.size());
        assertSame(b, list.get(0));
    }

    private static Collection<Integer> rendezvous(ConcurrentList<Integer> source, CountDownLatch entered) {
        return new AbstractCollection<>() {
            @Override public int size() { return source.size(); }
            @Override public Iterator<Integer> iterator() { meet(); return source.iterator(); }
            @Override public Object[] toArray() { meet(); return source.toArray(); }
            @Override public boolean contains(Object value) { meet(); return source.contains(value); }
            private void meet() { entered.countDown(); await(entered); }
        };
    }

    private static void run(Runnable body) throws Exception {
        start(body).get(5, TimeUnit.SECONDS);
    }

    private static FutureTask<Void> start(Runnable body) {
        FutureTask<Void> task = new FutureTask<>(body, null);
        Thread thread = new Thread(task, "concurrent-list-bulk");
        thread.setDaemon(true);
        thread.start();
        return task;
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
