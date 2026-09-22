package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;

import java.util.List;
import java.util.Queue;
import java.util.function.LongPredicate;

/**
 * Duck interface installed by LevelTicksMixin — exposes the scheduler's shared
 * structures (already swapped to concurrent implementations there) to the
 * island-partitioned tick path in {@link IslandScheduledTicks}.
 */
public interface LevelTicksIslandAccess<T> {

    Long2ObjectMap<LevelChunkTicks<T>> fabrolia$allContainers();

    Long2LongMap fabrolia$nextTickForContainer();

    LongPredicate fabrolia$tickCheck();

    /** The scheduler's structural lock (schedule/addContainer/removeContainer
     *  synchronize on it) — consumption-point tag enforcement rebases under
     *  the same lock so a concurrent schedule can never interleave a drain. */
    Object fabrolia$tickLock();

    /** Scheduler-owned visibility domain for custom collected slices.  The
     *  read side covers publication/detachment and retirement/restoration;
     *  clear/copy take the write side so neither can observe the gap between
     *  a durable container and its in-flight replica. */
    IslandScheduledTicks.SliceRegistry fabrolia$sliceRegistry();

    /** Vanilla private in-flight queues, needed only while copyAreaFrom holds
     *  the scheduler visibility/structural cut. */
    Queue<ScheduledTick<T>> fabrolia$vanillaToRunThisTick();

    List<ScheduledTick<T>> fabrolia$vanillaAlreadyRunThisTick();

    /** Resolve a detached or durable source tag using the same authority as
     * save/rebase. An unregistered vanilla scheduler has only a WALL clock. */
    default long fabrolia$clockNow(long island) {
        RegionizedServer rs = RegionScheduler.forTicksInstance(
                (net.minecraft.world.ticks.LevelTicks<?>) this);
        if (rs != null) return TickDomains.resolveNow(rs.level, island);
        if (island == FabroliaTickDomain.WALL) return 0L;
        throw new IllegalStateException("island-tagged ticks have no registered clock: " + island);
    }
}
