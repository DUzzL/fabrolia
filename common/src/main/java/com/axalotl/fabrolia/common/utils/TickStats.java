package com.axalotl.fabrolia.common.utils;

import com.axalotl.fabrolia.common.ParallelProcessor;
import net.minecraft.world.entity.EntityType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

public class TickStats {
    public static final Map<EntityType<?>, LongAdder> TICK_TIME_NS = new ConcurrentHashMap<>();
    public static final Map<EntityType<?>, LongAdder> TICK_COUNT = new ConcurrentHashMap<>();
    public static final Map<EntityType<?>, LongAdder> FABROLIA_TICK_TIME_NS = new ConcurrentHashMap<>();
    public static final Map<EntityType<?>, LongAdder> FABROLIA_TICK_COUNT = new ConcurrentHashMap<>();

    public static final LongAdder TOTAL_SYNC_TIME_NS = new LongAdder();
    public static final LongAdder TOTAL_FABROLIA_TIME_NS = new LongAdder();
    public static final LongAdder TOTAL_SYNC_COUNT = new LongAdder();
    public static final LongAdder TOTAL_FABROLIA_COUNT = new LongAdder();
    public static final LongAdder TOTAL_BATCH_WALL_NS = new LongAdder();

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
        TOTAL_SYNC_TIME_NS.reset();
        TOTAL_FABROLIA_TIME_NS.reset();
        TOTAL_SYNC_COUNT.reset();
        TOTAL_FABROLIA_COUNT.reset();
        TOTAL_BATCH_WALL_NS.reset();
    }

    public static boolean isRecording() {
        return RECORDING_TICKS_LEFT.get() > 0;
    }

    public static double getMSPTForType(EntityType<?> type, int recordedTicks) {
        if (recordedTicks <= 0) return 0;

        double syncMs = 0;
        double fabroliaMs = 0;

        LongAdder syncTime = TICK_TIME_NS.get(type);
        if (syncTime != null) {
            syncMs = syncTime.sum() / 1_000_000.0;
        }

        LongAdder fabroliaTime = FABROLIA_TICK_TIME_NS.get(type);
        if (fabroliaTime != null) {
            int currentPoolSize = ParallelProcessor.getPoolSize();
            double rawFabroliaMs = fabroliaTime.sum() / 1_000_000.0;
            fabroliaMs = currentPoolSize > 0 ? rawFabroliaMs / currentPoolSize : rawFabroliaMs;
        }

        return (syncMs + fabroliaMs) / recordedTicks;
    }

    public static double getTotalMSPT(int recordedTicks) {
        if (recordedTicks <= 0) return 0;
        double syncMs = TOTAL_SYNC_TIME_NS.sum() / 1_000_000.0;
        double fabroliaMs = TOTAL_FABROLIA_TIME_NS.sum() / 1_000_000.0;
        int pool = ParallelProcessor.getPoolSize();
        if (pool > 0) fabroliaMs /= pool;
        return (syncMs + fabroliaMs) / recordedTicks;
    }

    public static double getBatchWallMSPT(int recordedTicks) {
        if (recordedTicks <= 0) return 0;
        return (TOTAL_BATCH_WALL_NS.sum() / 1_000_000.0) / recordedTicks;
    }

    public static long getTotalSyncTicks() { return TOTAL_SYNC_COUNT.sum(); }
    public static long getTotalFabroliaTicks() { return TOTAL_FABROLIA_COUNT.sum(); }

    public static void resetEntityTickStats() {
        clean();
        RECORDING_TICKS_LEFT.set(0);
    }
}
