package com.axalotl.fabrolia.common.utils;

import com.axalotl.fabrolia.api.fastutil.AtomicMobCategoryCounts;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIntBiConsumer;
import net.minecraft.world.entity.MobCategory;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.*;

final class AtomicMobCategoryCountsContractTest {
    private static AtomicMobCategoryCounts counts() {
        return new AtomicMobCategoryCounts(new AtomicIntegerArray(MobCategory.values().length));
    }

    @Test
    void inheritedMutatorsAddressTheFixedCategoryCounts() {
        var counts = counts();
        MobCategory category = MobCategory.MONSTER;
        counts.put(category, 3);
        assertEquals(3, counts.putIfAbsent(category, 99));
        assertEquals(3, counts.computeIfAbsent(category,
                (ToIntFunction<MobCategory>) key -> fail("category is always present")));
        assertTrue(counts.replace(category, 3, 4));
        assertFalse(counts.replace(category, 3, 99));
        assertEquals(4, counts.replace(category, 5));
        assertEquals(7, counts.computeIntIfPresent(category, (key, value) -> value + 2));
        assertEquals(10, counts.mergeInt(category, 3, (java.util.function.IntBinaryOperator) Integer::sum));
        assertEquals(12, counts.merge(category, 2, Integer::sum));
        assertEquals(12, counts.getInt(category));
        assertTrue(counts.remove(category, 12));
        assertEquals(0, counts.getInt(category));
        assertTrue(counts.containsKey(category));
        counts.replaceAll((key, old) -> old + 1);
        for (MobCategory key : MobCategory.values()) assertEquals(1, counts.getInt(key));
    }

    @Test
    void traversalHashAndCloneDescribeCountsRatherThanUnusedParentStorage() {
        var counts = counts();
        counts.put(MobCategory.MONSTER, 9);
        Object2IntOpenHashMap<MobCategory> expected = new Object2IntOpenHashMap<>();
        for (MobCategory category : MobCategory.values()) expected.put(category, counts.getInt(category));
        AtomicInteger total = new AtomicInteger();
        counts.forEach((ObjectIntBiConsumer<MobCategory>) (category, value) -> total.addAndGet(value));
        assertEquals(9, total.get());
        assertEquals(expected.hashCode(), counts.hashCode());
        var clone = counts.clone();
        clone.put(MobCategory.MONSTER, 20);
        assertEquals(9, counts.getInt(MobCategory.MONSTER));
        assertEquals(20, clone.getInt(MobCategory.MONSTER));
        var iterator = counts.object2IntEntrySet().iterator();
        while (iterator.hasNext()) iterator.next();
        assertThrows(NoSuchElementException.class, iterator::next);
    }

    @Test
    void remappingDoesNotOverwriteAnIncrementThatWinsDuringCallback() throws Exception {
        var counts = counts();
        MobCategory category = MobCategory.MONSTER;
        counts.put(category, 10);
        CountDownLatch computing = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        FutureTask<Integer> remap = new FutureTask<>(() -> counts.computeInt(category, (key, previous) -> {
            if (first.getAndSet(false)) {
                computing.countDown();
                try { assertTrue(resume.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }
            return previous + 1;
        }));
        Thread worker = new Thread(remap, "mob-count-remap");
        worker.setDaemon(true);
        worker.start();
        try {
            assertTrue(computing.await(5, TimeUnit.SECONDS));
            counts.addTo(category, 5);
        } finally {
            resume.countDown();
        }
        assertEquals(16, remap.get(5, TimeUnit.SECONDS));
        assertEquals(16, counts.getInt(category));
    }
}
