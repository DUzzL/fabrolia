package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class IslandClockHandlingPhaseTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void deferredGlobalChunkBodyRetainsVanillaPhaseAndRestoresEntityPhase() throws Exception {
        ServerLevel level = allocateLevel();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        RegionWorkerThread worker = new RegionWorkerThread(() -> {
            try {
                RegionWorkerThread current = (RegionWorkerThread) Thread.currentThread();
                current.currentDomain = new EpochDomain();
                assertNull(IslandClock.workerHandlingTick(level), "global native phase must not be overridden false");
                IslandClock.runHandlingTick(level, level, ignored ->
                        assertEquals(Boolean.TRUE, IslandClock.workerHandlingTick(level)));
                assertNull(IslandClock.workerHandlingTick(level), "entity bodies return to the native phase");
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "handling-phase-global-worker");
        worker.start();
        worker.join();
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    @Test
    void mainStealAndNestedCrossLevelFailureRestoreTheirExactPhase() throws Exception {
        ServerLevel first = allocateLevel();
        ServerLevel second = allocateLevel();
        IllegalStateException expected = new IllegalStateException("chunk body");
        IslandClock.runHandlingTick(first, first, ignored -> {
            assertEquals(Boolean.TRUE, IslandClock.workerHandlingTick(first));
            assertNull(IslandClock.workerHandlingTick(second));
            assertSame(expected, assertThrows(IllegalStateException.class, () ->
                    IslandClock.runHandlingTick(second, second, nested -> {
                        assertEquals(Boolean.TRUE, IslandClock.workerHandlingTick(second));
                        assertNull(IslandClock.workerHandlingTick(first));
                        throw expected;
                    })));
            assertEquals(Boolean.TRUE, IslandClock.workerHandlingTick(first));
            assertNull(IslandClock.workerHandlingTick(second));
        });
        assertNull(IslandClock.workerHandlingTick(first));
        assertNull(IslandClock.workerHandlingTick(second));
    }

    private static ServerLevel allocateLevel() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (ServerLevel) ((sun.misc.Unsafe) field.get(null)).allocateInstance(ServerLevel.class);
    }
}
