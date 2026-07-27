package com.axalotl.fabrolia.api.utils;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.function.BiFunction;

/**
 * Sync wrap for custom pickUpItem / item-intake paths (mob pickup, hopper/minecart intake,
 * dolphin/fox/allay/… item grabs). Serializes them so two entities can't dup the same item.
 *
 * <h2>Monitor scope</h2>
 * The monitor is keyed by the ITEM's region when a {@code monitorResolver} is registered
 * (common wires {@code FabroliaLocks.itemLockOr}): the lock of the region that owns the item's
 * chunk, {@link #fallback()} when the region scheduler is inactive. Keying by the ITEM (not by
 * the current thread) is load-bearing: every toucher of item I — region worker, help-fanout
 * helper, the main-thread barrier pump running a stolen help batch ({@code RegionWorkerPool
 * .stealOne}), main-sync-lane entities, island BE hopper intake — resolves the SAME monitor,
 * so the mutual exclusion the old JVM-wide static lock provided survives main/worker mixing
 * (the mixed-monitor dupe window found 2026-07-01).
 *
 * <p>Deadlock-freedom: on a region worker the item's region is the worker's own region (or an
 * adjacent one that ColorGate guarantees is not concurrently ticking), so nesting with the
 * sibling thread-scoped monitors ({@code MinecartHopper.suckInItems} / {@code Mob.tryEquip},
 * which use {@code FabroliaLocks.tickLockOr}) is a plain reentrant lock — the AB-BA wedge fixed
 * 2026-07-01 stays dead. Main only ever holds one region lock at a time here and workers never
 * take the fallback, so no cross-thread cycle through {@link #FALLBACK} exists either.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * SyncItemPickup.wrap(level, entity, () -> { this.onItemPickup(entity); ... });
 * }</pre>
 */
public class SyncItemPickup {

    /** JVM-wide fallback monitor — scheduler inactive, or before the resolver is registered. */
    private static final Object FALLBACK = new Object();

    /** Item-region monitor resolver, registered by common ({@code FabroliaLocks.itemLockOr}). */
    private static volatile BiFunction<ServerLevel, ItemEntity, Object> monitorResolver;

    private SyncItemPickup() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** The JVM-wide fallback monitor — pass this as the {@code FabroliaLocks.itemLockOr} fallback. */
    public static Object fallback() {
        return FALLBACK;
    }

    /** Register the item-region monitor resolver
     *  (common: {@code (level, item) -> FabroliaLocks.itemLockOr(level, item, fallback())}). */
    public static void setMonitorResolver(BiFunction<ServerLevel, ItemEntity, Object> resolver) {
        monitorResolver = resolver;
    }

    /**
     * Executes {@code action} under the (item-region-keyed, reentrant) item-sync monitor,
     * skipping it if {@code entity} was already removed (picked up / merged by another path).
     */
    public static void wrap(ServerLevel level, ItemEntity entity, Runnable action) {
        BiFunction<ServerLevel, ItemEntity, Object> r = monitorResolver;
        Object monitor = (r != null) ? r.apply(level, entity) : FALLBACK;
        synchronized (monitor) {
            if (!entity.isRemoved()) {
                action.run();
            }
        }
    }
}
