package com.axalotl.fabrolia.common.regions;

/**
 * Tick-scoped lock helper for static-final-Object lock sites in Fabrolia15
 * entity/world mixins. These mixins were originally added to protect vanilla
 * invariants (twin-baby race in AnimalMakeLove, hive overcap in Bee,
 * pickup-duplicate in Hopper/Mob, etc.) against the original fabrolia-tick pool
 * that ran arbitrary entities concurrently. A single static monitor per mixin
 * serialized ALL instances of that mixin across the JVM — overkill, and the
 * primary reason the region scheduler shows no win at high entity counts:
 * different regions' workers all contend on the same VillagerMixin.lock /
 * MobMixin.lock / etc., reducing N-way region parallelism to 1.
 *
 * <p>This helper returns:
 * <ul>
 *   <li>The current region's lock — when called from a {@link RegionWorkerThread}
 *       that has claimed a region. The "one worker per region" invariant means
 *       this monitor is uncontended from the worker side; cross-region work
 *       takes different locks → no serialization across regions.</li>
 *   <li>The mixin's original static fallback — otherwise (server thread,
 *       Fabrolia-Tick-Pool worker, scheduler dormant). Preserves the original
 *       protection scope, so non-region-mode behavior is unchanged.</li>
 * </ul>
 *
 * <p>Race-safety reasoning for per-region scope: all vanilla invariants
 * protected by these locks are LOCAL (gossip area ≤16 blocks for villager
 * golems; breeding partners ≤8 blocks; hive bees within hive's chunk; raid
 * waves within one raid's area; etc.), so entities involved in an invariant
 * usually share a grid cell and therefore a monitor.
 *
 * <p>KNOWN GAP (until the dynamic merge-buffer regionizer lands): the fixed
 * grid can cut straight through an interaction area — two breeding partners
 * 8 blocks apart on opposite sides of a 128-block grid line take DIFFERENT
 * region monitors, reopening a narrow window for the protected race (extra
 * golem, twin baby, raid-wave double-count). Probability per tick is small
 * and the consequences are vanilla-bug-shaped rather than corrupting; Folia
 * closes this class by construction with merge-buffered regions (our A4).
 */
public final class FabroliaLocks {
    private FabroliaLocks() {}

    public static Object tickLockOr(Object fallback) {
        Thread t = Thread.currentThread();
        if (t instanceof RegionWorkerThread w) {
            Region r = w.currentRegion;
            if (r != null) return r.lock;
        }
        return fallback;
    }

    /**
     * Legacy item-monitor surface. The exact ItemEntity identity is now the
     * synchronization authority; resolving through a mutable chunk/region can
     * select a different monitor while the item crosses a grid boundary.
     */
    @Deprecated
    public static Object itemLockOr(net.minecraft.server.level.ServerLevel level,
                                    net.minecraft.world.entity.item.ItemEntity entity,
                                    Object fallback) {
        return entity == null ? fallback : entity;
    }

    /**
     * Outer serial lane for consumable ticks and native merges. Acquire before
     * either endpoint's stable guard. Gap/synchronous work shares the fallback;
     * autonomous islands have independent lanes separated beyond merge reach.
     */
    public static Object itemTickLaneOr(net.minecraft.server.level.ServerLevel level,
                                        net.minecraft.world.entity.Entity entity,
                                        Object fallback) {
        return entity == null ? fallback : itemTickLaneOr(level, entity.chunkPosition().pack(), fallback);
    }

    /** Static XP award resolves its lane before native selection of an orb. */
    public static Object itemTickLaneOr(net.minecraft.server.level.ServerLevel level,
                                        long packedChunk, Object fallback) {
        RegionizedServer rs = RegionScheduler.forLevel(level);
        if (rs == null || !rs.asyncActive) return fallback;
        IslandRegistry.PIsland island = rs.islandOwnerOfChunk(packedChunk);
        return island == null || island.retired ? fallback : island.itemTickLane;
    }
}
