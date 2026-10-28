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
    void waitLedgerSumsPerCallerAndSortsByWaitTime() {
        MainFallbackStats.WaitLedger ledger = new MainFallbackStats.WaitLedger(8);
        ledger.record("A.tail", 2_000_000L);
        ledger.record("A.tail", 6_000_000L);
        ledger.record("B.head", 1_000_000L);
        ledger.recordHold("A.tail", 3_000_000L);

        assertEquals(List.of(
                new MainFallbackStats.WaitEntry("A.tail", 2, 8_000_000L, 6_000_000L, 3_000_000L),
                new MainFallbackStats.WaitEntry("B.head", 1, 1_000_000L, 1_000_000L, 0L)),
                ledger.top(10));
        assertEquals(3, ledger.events());
        assertEquals(9_000_000L, ledger.waitNanos());
        assertEquals(3_000_000L, ledger.holdNanos());
        assertEquals(1, ledger.top(1).size());
    }

    @Test
    void waitLedgerCapsCallersIntoOtherAndResetClears() {
        MainFallbackStats.WaitLedger ledger = new MainFallbackStats.WaitLedger(2);
        for (int i = 0; i < 5; i++) ledger.record("caller-" + i, 10L);
        ledger.record("caller-0", 10L);

        assertEquals(3, ledger.distinctKeys(), "two callers plus the overflow key");
        assertEquals(60L, ledger.waitNanos());
        assertTrue(ledger.top(10).stream().anyMatch(e ->
                e.caller().equals(MainFallbackStats.CauseCounter.OTHER) && e.events() == 3));
        ledger.reset();
        assertEquals(0, ledger.events());
        assertTrue(ledger.top(10).isEmpty());
    }

    @Test
    void resetAllClearsWaitLedgersAndRestartsTheWindow() throws Exception {
        MainFallbackStats.FENCE_WAITS.record("A.fence", 5L);
        MainFallbackStats.CLAIM_WAITS.record("A.claim", 5L);
        MainFallbackStats.BUCKET_WAITS.record("A.drain", 5L);
        Thread.sleep(20L);
        long before = MainFallbackStats.windowNanos();
        MainFallbackStats.resetAll();

        assertEquals(0, MainFallbackStats.FENCE_WAITS.events());
        assertEquals(0, MainFallbackStats.CLAIM_WAITS.events());
        assertEquals(0, MainFallbackStats.BUCKET_WAITS.events());
        assertTrue(MainFallbackStats.windowNanos() < before);
    }

    @Test
    void callerKeySkipsWaitMachineryAndNamesThreeCallers() {
        String key = MainFallbackStats.callerKey(java.util.stream.Stream.of(
                frame("java.lang.StackWalker", "walk"),
                frame("com.axalotl.fabrolia.common.regions.MainFallbackStats", "lambda$currentCallerKey$0"),
                frame("com.axalotl.fabrolia.common.regions.MainFallbackStats$WaitLedger", "record"),
                frame("com.axalotl.fabrolia.common.regions.IslandCommands", "mainClaim"),
                frame("com.axalotl.fabrolia.common.regions.IslandCommands", "mainClaimScoped"),
                frame("com.axalotl.fabrolia.common.regions.RegionScheduler", "runSuccessBodyIfHealthy"),
                frame("com.axalotl.fabrolia.common.regions.AsyncIslandPlayerTicks", "lambda$drainMainIngress$12"),
                frame("com.axalotl.fabrolia.common.regions.AsyncIslandPlayerTicks", "drainMainIngress"),
                frame("com.llamalad7.mixinextras.injector.wrapoperation.Operation", "call"),
                frame("net.minecraft.server.MinecraftServer", "handler$zfb000$fabrolia$drainIngress"),
                frame("net.minecraft.server.MinecraftServer", "tickServer")));

        assertEquals("AsyncIslandPlayerTicks.drainMainIngress < MinecraftServer.fabrolia$drainIngress"
                + " < MinecraftServer.tickServer", key);
    }

    @Test
    void callerKeyKeepsRealIslandCommandsCallersAndBucketDrainCallers() {
        assertEquals("IslandCommands.drainRoutedBatch < RegionScheduler.drainOverdueMainTracking",
                MainFallbackStats.callerKey(java.util.stream.Stream.of(
                        frame("com.axalotl.fabrolia.common.regions.IslandCommands", "quiesceAllExcept"),
                        frame("com.axalotl.fabrolia.common.regions.IslandCommands", "drainRoutedBatch"),
                        frame("com.axalotl.fabrolia.common.regions.RegionScheduler$IslandBuckets",
                                "waitForRemaining"),
                        frame("com.axalotl.fabrolia.common.regions.RegionScheduler$IslandBuckets",
                                "flushOverdue"),
                        frame("com.axalotl.fabrolia.common.regions.RegionScheduler",
                                "drainOverdueMainTracking"))));
        assertEquals("unknown", MainFallbackStats.callerKey(java.util.stream.Stream.of(
                frame("java.lang.Thread", "run"))));
    }

    @Test
    void methodNamesCollapseLambdasAndMixinHandlers() {
        assertEquals("drainMainIngress", MainFallbackStats.methodName("lambda$drainMainIngress$12"));
        assertEquals("fabrolia$fenceFunction",
                MainFallbackStats.methodName("lambda$handler$zfb000$fabrolia$fenceFunction$3"));
        assertEquals("fabrolia$quiesceIslandsForServerSave",
                MainFallbackStats.methodName("wrapMethod$bbc000$fabrolia$quiesceIslandsForServerSave"));
        assertEquals("fabrolia$x", MainFallbackStats.methodName("fabrolia$x"));
        assertEquals("tick", MainFallbackStats.methodName("tick"));
    }

    @Test
    void classNamesDropTheMixinMergeHash() {
        assertEquals("ChunkMap$FabroliaOwnerSafeUnloadTask", MainFallbackStats.simpleClassName(
                "net.minecraft.server.level.ChunkMap$FabroliaOwnerSafeUnloadTask$6c1ac4dd3f8a3039843b37bbb3e1a2ed"));
        assertEquals("RegionScheduler$MainSyncService", MainFallbackStats.simpleClassName(
                "com.axalotl.fabrolia.common.regions.RegionScheduler$MainSyncService"));
        assertEquals("Foo$1", MainFallbackStats.simpleClassName("a.Foo$1"));
    }

    @Test
    void liveCallerKeyNamesTheRecordingTestMethod() {
        MainFallbackStats.WaitLedger ledger = new MainFallbackStats.WaitLedger(4);
        String caller = ledger.record(1L);
        assertTrue(caller.startsWith("MainFallbackStatsTest.liveCallerKeyNamesTheRecordingTestMethod"),
                caller);
        assertEquals(1, ledger.events());
    }

    private static MainFallbackStats.FrameName frame(String className, String method) {
        return new MainFallbackStats.FrameName(className, method);
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
