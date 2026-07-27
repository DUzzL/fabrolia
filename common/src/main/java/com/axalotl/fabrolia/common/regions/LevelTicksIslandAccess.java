package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.ticks.LevelChunkTicks;

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
}
