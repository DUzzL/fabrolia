package com.axalotl.fabrolia.common.parallelised.utils;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-level live set of HARD-COLLIDABLE entities (boats, shulkers, happy
 * ghasts — anything whose shape joins block collisions in {@code Entity.collide}).
 *
 * <p>Why: every {@code Entity.collide} fast-path call must first prove there
 * are no entity colliders in range — vanilla's only way is a full
 * EntitySectionStorage walk ({@code getEntityCollisions}), which at 200M+
 * collide calls per session almost always returns an empty list. Hard
 * colliders are a handful per dimension, so the EMPTINESS question collapses
 * to iterating this tiny set against LIVE bounding boxes — no section walk,
 * no allocation, no move-tracking (positions are read at query time; the
 * bounding-box reference read is atomic).
 *
 * <p>Maintenance: the same PESM tracking-start/remove seams that feed
 * {@link SpawnIndex}. Removal is unconditional (predicate-independent), so a
 * state change between add and remove (shulker death) can never leak an
 * entry. A positive here only routes the call to vanilla's precise path —
 * false positives cost a vanilla walk, false negatives are impossible for
 * tracked entities.
 */
public final class HardColliderIndex {

    private static final ConcurrentHashMap<ServerLevel, Set<Entity>> BY_LEVEL =
            new ConcurrentHashMap<>();

    /** Level-unload teardown (audit #8): all three per-level maps retained
     *  unloaded ServerLevels forever with dynamic-dimension mods. */
    public static void onLevelUnload(ServerLevel level) {
        BY_LEVEL.remove(level);
        CELLS_BY_LEVEL.remove(level);
        RECENT_ADDS.remove(level);
    }

    private HardColliderIndex() {}

    /**
     * Class CAPABILITY, not instance state: membership must be time-invariant
     * for the "false negatives are impossible" contract to hold, and
     * collidability is NOT — HappyGhast.canBeCollidedWith is true only during
     * its still-timeout (and only once adult), so snapshotting the instance at
     * tracking-start missed every ghast that later became a platform. The
     * Oxidizium fast path then skipped getEntityCollisions for players
     * standing on one; the un-supported player tripped the vanilla floating
     * counter — "Flying is not enabled on this server" kicks (2026-07-10).
     * Any class that OVERRIDES canBeCollidedWith below Entity (26.2 vanilla:
     * AbstractBoat, Shulker, HappyGhast; plus modded platforms) may answer
     * true at some point in its life, so all its instances are indexed —
     * false positives only cost a vanilla walk.
     */
    private static final ClassValue<Boolean> CAN_EVER_COLLIDE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> cls) {
            try {
                return cls.getMethod("canBeCollidedWith", Entity.class).getDeclaringClass() != Entity.class;
            } catch (ReflectiveOperationException | SecurityException unavailable) {
                return Boolean.TRUE; // can't prove the default — index conservatively
            }
        }
    };

    private static boolean isHard(Entity e) {
        if (CAN_EVER_COLLIDE.get(e.getClass())) {
            return true;
        }
        try {
            // Catches mixins that patch Entity's default itself (override-detection
            // can't see those); state-dependent cases of that shape remain unindexed,
            // same blindness the pre-2026-07-10 instance snapshot had for everything.
            return e.canBeCollidedWith(null);
        } catch (RuntimeException probeFailure) {
            // This null-argument capability probe is not the vanilla collision
            // body.  Route the entity through vanilla's exact collider query when
            // it cannot be classified; VM errors remain fatal.
            return true;
        }
    }

    public static void onAdd(ServerLevel level, Entity e) {
        if (isHard(e)) {
            BY_LEVEL.computeIfAbsent(level, l -> ConcurrentHashMap.newKeySet()).add(e);
            // Mid-tick adds are invisible to the tick-head cell snapshot; anyNear
            // scans this small overflow list so a just-placed boat blocks movement
            // the same tick, exactly like the flat scan did. Drained at rebuild.
            RECENT_ADDS.computeIfAbsent(level, l -> new java.util.concurrent.ConcurrentLinkedQueue<>()).add(e);
        }
    }

    public static void onRemove(Entity e) {
        if (e.level() instanceof ServerLevel sl) {
            Set<Entity> set = BY_LEVEL.get(sl);
            if (set != null) {
                set.remove(e);
            }
        }
    }

    /**
     * Per-tick cell buckets over the collider set (cells = the region grid's
     * 128-block cells; key = cell coords packed as (cx | cz<<32)). Rebuilt on
     * main at level-tick head from tick-head positions, published as an
     * immutable snapshot, queried lock-free by every mover's collide call.
     *
     * <p>Why: {@code anyNear} used to scan EVERY hard collider per call. The
     * 2026-07-11 classification fix (class capability, not instance state)
     * correctly grew the set — every boat, shulker and happy ghast — and the
     * scan showed up at 1.94 core-ms self on the 2026-07-11 profiles, paid by
     * every moving entity every tick. Bucketing narrows candidate ENUMERATION
     * only; the precise test still reads LIVE bounding boxes, so the answer is
     * bit-identical. No false negatives: a query probes its box's cell range
     * expanded by {@link #DRIFT_MARGIN} (+ the 0.5 margin), far beyond any
     * collider's per-tick movement, so a collider's live box cannot overlap a
     * query whose probed range misses the collider's tick-head cell; mid-tick
     * ADDS are covered by the RECENT_ADDS overflow list.
     */
    private static final ConcurrentHashMap<ServerLevel, it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Entity[]>> CELLS_BY_LEVEL =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ServerLevel, java.util.concurrent.ConcurrentLinkedQueue<Entity>> RECENT_ADDS =
            new ConcurrentHashMap<>();
    private static final int CELL_SHIFT = 7; // 128-block cells (region grid cell size)
    /** Snapshot-staleness slack: no hard collider moves anywhere near this many
     *  blocks in one tick (boats/ghasts < ~1.5), so probing the query range
     *  expanded by it can never miss a collider bucketed at tick-head. */
    private static final double DRIFT_MARGIN = 4.0;

    private static long cellKey(int cx, int cz) {
        return (cx & 0xFFFFFFFFL) | ((long) cz << 32);
    }

    /** Main level-head maintenance. */
    public static void rebuildCells(ServerLevel level) {
        Set<Entity> set = BY_LEVEL.get(level);
        if (set == null || set.isEmpty()) {
            CELLS_BY_LEVEL.remove(level);
            return;
        }
        java.util.concurrent.ConcurrentLinkedQueue<Entity> recent = RECENT_ADDS.get(level);
        if (recent != null) recent.clear(); // now covered by the snapshot below
        java.util.HashMap<Long, java.util.ArrayList<Entity>> tmp = new java.util.HashMap<>();
        for (Entity e : set) {
            if (e.isRemoved()) continue;
            long k = cellKey(net.minecraft.util.Mth.floor(e.getX()) >> CELL_SHIFT,
                             net.minecraft.util.Mth.floor(e.getZ()) >> CELL_SHIFT);
            tmp.computeIfAbsent(k, kk -> new java.util.ArrayList<>(4)).add(e);
        }
        var snap = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Entity[]>(Math.max(8, tmp.size() * 2));
        for (var en : tmp.entrySet()) {
            snap.put(en.getKey().longValue(), en.getValue().toArray(new Entity[0]));
        }
        CELLS_BY_LEVEL.put(level, snap);
    }

    /**
     * Any hard collider whose live bounding box touches {@code box} (with a
     * margin)? Called from the Oxidizium collision fast path via a
     * MethodHandle bridge. Bucketed candidate enumeration + live-box precise
     * test (see {@link #rebuildCells}); falls back to the flat scan until the
     * first snapshot exists.
     */
    public static boolean anyNear(ServerLevel level,
                                  double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ) {
        Set<Entity> set = BY_LEVEL.get(level);
        if (set == null || set.isEmpty()) {
            return false;
        }
        var snap = CELLS_BY_LEVEL.get(level);
        if (snap != null) {
            int c0x = net.minecraft.util.Mth.floor(minX - 0.5 - DRIFT_MARGIN) >> CELL_SHIFT;
            int c1x = net.minecraft.util.Mth.floor(maxX + 0.5 + DRIFT_MARGIN) >> CELL_SHIFT;
            int c0z = net.minecraft.util.Mth.floor(minZ - 0.5 - DRIFT_MARGIN) >> CELL_SHIFT;
            int c1z = net.minecraft.util.Mth.floor(maxZ + 0.5 + DRIFT_MARGIN) >> CELL_SHIFT;
            for (int cz = c0z; cz <= c1z; cz++) {
                for (int cx = c0x; cx <= c1x; cx++) {
                    Entity[] bucket = snap.get(cellKey(cx, cz));
                    if (bucket == null) continue;
                    for (Entity e : bucket) {
                        AABB bb = e.getBoundingBox();
                        if (bb.minX - 0.5 < maxX && bb.maxX + 0.5 > minX
                                && bb.minY - 0.5 < maxY && bb.maxY + 0.5 > minY
                                && bb.minZ - 0.5 < maxZ && bb.maxZ + 0.5 > minZ) {
                            return true;
                        }
                    }
                }
            }
            // Mid-tick additions not yet in the snapshot (drained every rebuild).
            java.util.concurrent.ConcurrentLinkedQueue<Entity> recent = RECENT_ADDS.get(level);
            if (recent != null && !recent.isEmpty()) {
                for (Entity e : recent) {
                    AABB bb = e.getBoundingBox();
                    if (bb.minX - 0.5 < maxX && bb.maxX + 0.5 > minX
                            && bb.minY - 0.5 < maxY && bb.maxY + 0.5 > minY
                            && bb.minZ - 0.5 < maxZ && bb.maxZ + 0.5 > minZ) {
                        return true;
                    }
                }
            }
            return false;
        }
        for (Entity e : set) {
            AABB bb = e.getBoundingBox();
            if (bb.minX - 0.5 < maxX && bb.maxX + 0.5 > minX
                    && bb.minY - 0.5 < maxY && bb.maxY + 0.5 > minY
                    && bb.minZ - 0.5 < maxZ && bb.maxZ + 0.5 > minZ) {
                return true;
            }
        }
        return false;
    }
}
