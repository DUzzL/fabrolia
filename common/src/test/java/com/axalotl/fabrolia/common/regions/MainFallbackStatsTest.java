package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainFallbackStatsTest {

    @Test
    void countsPerCauseAndSortsTopDescending() {
        MainFallbackStats.CauseCounter counter = new MainFallbackStats.CauseCounter(16);
        for (int i = 0; i < 5; i++) counter.increment("map update");
        for (int i = 0; i < 9; i++) counter.increment("ordered interaction packet");
        counter.increment("End credits");
        counter.increment("b");
        counter.increment("a");

        List<MainFallbackStats.Entry> top = counter.top(3, String::valueOf);
        assertEquals(List.of(
                new MainFallbackStats.Entry("ordered interaction packet", 9),
                new MainFallbackStats.Entry("map update", 5),
                new MainFallbackStats.Entry("End credits", 1)), top);
        assertEquals(17, counter.total());
        assertEquals(5, counter.top(10, String::valueOf).size());
    }

    @Test
    void capsDistinctKeysIntoOther() {
        MainFallbackStats.CauseCounter counter = new MainFallbackStats.CauseCounter(3);
        for (int i = 0; i < 10; i++) counter.increment("channel-" + i);
        counter.increment("channel-0");

        assertEquals(4, counter.distinctKeys(), "three causes plus the overflow key");
        assertEquals(2, counter.count("channel-0"), "existing keys keep counting");
        assertEquals(7, counter.count(MainFallbackStats.CauseCounter.OTHER));
        assertEquals(11, counter.total());
    }

    @Test
    void nullCauseCountsAsUnknownAndResetClears() {
        MainFallbackStats.CauseCounter counter = new MainFallbackStats.CauseCounter(4);
        counter.increment(null);
        assertEquals(1, counter.count("unknown"));
        counter.reset();
        assertEquals(0, counter.total());
        assertTrue(counter.top(5, String::valueOf).isEmpty());
    }

    @Test
    void concurrentIncrementsAreNotLost() throws Exception {
        MainFallbackStats.CauseCounter counter = new MainFallbackStats.CauseCounter(8);
        int threads = 8;
        int perThread = 10_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int t = 0; t < threads; t++) {
                String key = "cause-" + (t % 3);
                pool.execute(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < perThread; i++) counter.increment(key);
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals((long) threads * perThread, counter.total());
        assertEquals(3, counter.distinctKeys());
    }

    @Test
    void describesPacketCausesWithoutTheirClassPath() {
        Object key = new MainFallbackStats.PacketCause("packet", String.class);
        assertEquals("packet String", MainFallbackStats.describe(key));
        assertEquals("interaction ServerboundCustomPayloadPacket[minecraft:brand]",
                MainFallbackStats.describe(new MainFallbackStats.PacketCause("interaction",
                        new MainFallbackStats.PayloadKey("minecraft:brand"))));
        assertEquals("topology gap", MainFallbackStats.describe("topology gap"));
    }
}
