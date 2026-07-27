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
     * Monitor for item-sync sites ({@code SyncItemPickup.wrap}), keyed by the ITEM's region —
     * NOT by the current thread. Thread-keyed resolution ({@link #tickLockOr}) breaks for item
     * exclusion the moment the same region's entity work runs on a non-RegionWorkerThread:
     * the main-thread barrier pump executes stolen dense-region help batches
     * ({@code RegionWorkerPool.stealOne}) and main-sync-lane entities tick on main WHILE the
     * region's workers run — thread-keyed, those two touchers of one ItemEntity would hold
     * DIFFERENT monitors (fallback vs region.lock) and both pass the isRemoved gate → dupe.
     * Item-keyed, they always contend on the same region.lock.
     *
     * <p>Identity stability: the item's own region holds the item in its entity index
     * (entityCount &gt; 0), so {@code evictIdle} never reclaims it while the item lives —
     * every resolver gets the same Region instance, hence the same monitor. Create-if-absent
     * from workers is the established {@code executeAt} path (regionForGridKey.compute is
     * atomic and race-safe vs eviction).
     *
     * <p>Deadlock-freedom vs the thread-scoped sibling monitors: worker nesting is
     * region.lock → same-or-adjacent region.lock, and ColorGate forbids adjacent regions
     * running concurrently, so opposite-order pairs cannot be held at once; main resolves the
     * fallback only via {@link #tickLockOr} and is a single thread — no cycle through it.
     */
    public static Object itemLockOr(net.minecraft.server.level.ServerLevel level,
                                    net.minecraft.world.entity.item.ItemEntity entity,
                                    Object fallback) {
        RegionizedServer rs = RegionScheduler.forLevel(level);
        if (rs == null) return fallback;   // scheduler inactive → original global serialization
        return rs.itemMonitor(entity.chunkPosition().pack());
    }
}
