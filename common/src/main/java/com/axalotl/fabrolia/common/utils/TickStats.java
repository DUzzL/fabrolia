package com.axalotl.fabrolia.common.utils;

import com.axalotl.fabrolia.common.ParallelProcessor;
import net.minecraft.world.entity.EntityType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class TickStats {
    public static final Map<EntityType<?>, AtomicLong> TICK_TIME_NS = new ConcurrentHashMap<>();
    public static final Map<EntityType<?>, AtomicLong> TICK_COUNT = new ConcurrentHashMap<>();
    public static final Map<EntityType<?>, AtomicLong> FABROLIA_TICK_TIME_NS = new ConcurrentHashMap<>();
    public static final Map<EntityType<?>, AtomicLong> FABROLIA_TICK_COUNT = new ConcurrentHashMap<>();

    public static final AtomicLong TOTAL_SYNC_TIME_NS = new AtomicLong();
    public static final AtomicLong TOTAL_FABROLIA_TIME_NS = new AtomicLong();
    public static final AtomicLong TOTAL_SYNC_COUNT = new AtomicLong();
    public static final AtomicLong TOTAL_FABROLIA_COUNT = new AtomicLong();
    public static final AtomicLong TOTAL_BATCH_WALL_NS = new AtomicLong();

    public static final AtomicInteger RECORDING_TICKS_LEFT = new AtomicInteger(0);

    public static void startRecording(int ticks) {
        clean();
        RECORDING_TICKS_LEFT.set(ticks);
    }

    public static void clean() {
        TICK_TIME_NS.clear();
        TICK_COUNT.clear();
        FABROLIA_TICK_TIME_NS.clear();
        FABROLIA_TICK_COUNT.clear();
        TOTAL_SYNC_TIME_NS.set(0L);
        TOTAL_FABROLIA_TIME_NS.set(0L);
        TOTAL_SYNC_COUNT.set(0L);
        TOTAL_FABROLIA_COUNT.set(0L);
        TOTAL_BATCH_WALL_NS.set(0L);
    }

    public static boolean isRecording() {
        return RECORDING_TICKS_LEFT.get() > 0;
    }

    public static double getMSPTForType(EntityType<?> type, int recordedTicks) {
        if (recordedTicks <= 0) return 0;

        double syncMs = 0;
        double fabroliaMs = 0;

        AtomicLong syncTime = TICK_TIME_NS.get(type);
        if (syncTime != null) {
            syncMs = syncTime.get() / 1_000_000.0;
        }

        AtomicLong fabroliaTime = FABROLIA_TICK_TIME_NS.get(type);
        if (fabroliaTime != null) {
            int currentPoolSize = ParallelProcessor.getPoolSize();
            double rawFabroliaMs = fabroliaTime.get() / 1_000_000.0;
            fabroliaMs = currentPoolSize > 0 ? rawFabroliaMs / currentPoolSize : rawFabroliaMs;
        }

        return (syncMs + fabroliaMs) / recordedTicks;
    }

    public static double getTotalMSPT(int recordedTicks) {
        if (recordedTicks <= 0) return 0;
        double syncMs = TOTAL_SYNC_TIME_NS.get() / 1_000_000.0;
        double fabroliaMs = TOTAL_FABROLIA_TIME_NS.get() / 1_000_000.0;
        int pool = ParallelProcessor.getPoolSize();
        if (pool > 0) fabroliaMs /= pool;
        return (syncMs + fabroliaMs) / recordedTicks;
    }

    public static double getBatchWallMSPT(int recordedTicks) {
        if (recordedTicks <= 0) return 0;
        return (TOTAL_BATCH_WALL_NS.get() / 1_000_000.0) / recordedTicks;
    }

    public static long getTotalSyncTicks() { return TOTAL_SYNC_COUNT.get(); }
    public static long getTotalFabroliaTicks() { return TOTAL_FABROLIA_COUNT.get(); }

    public static void resetEntityTickStats() {
        clean();
        RECORDING_TICKS_LEFT.set(0);
    }
}
