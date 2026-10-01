package com.axalotl.fabrolia.common.parallelised.utils;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.Arrays;

/**
 * Per-level spatial index of HARD-COLLIDABLE entities (boats, shulkers, happy
 * ghasts — anything whose shape joins block collisions in {@code Entity.collide}).
 *
 * <p>Why: every {@code Entity.collide} fast-path call must first prove there
 * are no entity colliders in range — vanilla's only way is a full
 * EntitySectionStorage walk ({@code getEntityCollisions}), which at 200M+
 * collide calls per session almost always returns an empty list. The question
 * collapses to probing a few 128-block cells of this index and testing LIVE
 * bounding boxes. A positive only routes the call to vanilla's precise path —
 * false positives cost a vanilla walk; false negatives must be impossible.
 *
 * <p>Exactness: every indexed entity is filed in each cell its CURRENT
 * bounding box touches. {@code Entity.setBoundingBox} — the only writer of the
 * box after construction — re-files the entity whenever the new box leaves its
 * filed cells, so teleports, fast vehicles and size changes (scale attribute,
 * shulker peek) are visible to the very next query on the moving thread. A
 * query probes the cells of its box grown by the 0.5 test margin; any collider
 * whose grown live box intersects the query shares at least one cell with it.
 *
 * <p>Concurrency: the cell map and every member array are immutable once
 * published (volatile copy-on-write); queries never lock. Writers (entity add,
 * remove, cell-leaving moves — all rare) serialize on the level index monitor,
 * a leaf lock. The moving thread re-files before storing the new box; a query
 * on ANOTHER thread racing that store may see either state, the same window
 * vanilla's section storage has between {@code setPosRaw} and the box store.
 *
 * <p>Maintenance: PESM {@code addEntity} files (same seam as before), and
 * {@code Callback.onRemove} unfiles via the entity's own placement, so a level
 * change or state change between add and remove can never leak an entry.
 */
public final class HardColliderIndex {

    /** ServerLevel duck owning that level's index. */
    public interface Holder {
        LevelIndex fabrolia$hardColliderIndex();
    }

    /** Entity duck: where the entity is filed, or {@code null} if not indexed. */
    public interface Member {
        Placement fabrolia$hardColliderPlacement();

        void fabrolia$setHardColliderPlacement(Placement placement);

        /** The live bounding box ({@code Entity.getBoundingBox()}). */
        AABB fabrolia$hardColliderBox();
    }

    private static final int CELL_SHIFT = 7; // 128-block cells (region grid cell size)
    /** Boxes spanning more cells per axis than this are scanned by every query instead. */
    private static final int MAX_FILED_SPAN = 4;
    private static final Member[] NONE = new Member[0];

    private HardColliderIndex() {}

    /** Immutable cell range [c0x, c1x] x [c0z, c1z] of one filed entity. */
    public static final class Placement {
        final LevelIndex index;
        final boolean oversized;
        final int c0x, c1x, c0z, c1z;

        private Placement(LevelIndex index, boolean oversized, int c0x, int c1x, int c0z, int c1z) {
            this.index = index;
            this.oversized = oversized;
            this.c0x = c0x;
            this.c1x = c1x;
            this.c0z = c0z;
            this.c1z = c1z;
        }

        boolean covers(AABB box) {
            return this.oversized
                    || (Mth.floor(box.minX) >> CELL_SHIFT) >= this.c0x
                    && (Mth.floor(box.maxX) >> CELL_SHIFT) <= this.c1x
                    && (Mth.floor(box.minZ) >> CELL_SHIFT) >= this.c0z
                    && (Mth.floor(box.maxZ) >> CELL_SHIFT) <= this.c1z;
        }
    }

    /** Members of one cell; the array is replaced, never mutated. */
    private static final class Cell {
        volatile Member[] members;

        Cell(Member[] members) {
            this.members = members;
        }
    }

    /** One level's index. Fields are replaced copy-on-write under {@code this}. */
    public static final class LevelIndex {
        private volatile Long2ObjectOpenHashMap<Cell> cells = new Long2ObjectOpenHashMap<>();
        private volatile Member[] oversized = NONE;

        private Placement placementFor(AABB box) {
            int c0x = Mth.floor(box.minX) >> CELL_SHIFT;
            int c1x = Mth.floor(box.maxX) >> CELL_SHIFT;
            int c0z = Mth.floor(box.minZ) >> CELL_SHIFT;
            int c1z = Mth.floor(box.maxZ) >> CELL_SHIFT;
            boolean oversized = (long) c1x - c0x >= MAX_FILED_SPAN || (long) c1z - c0z >= MAX_FILED_SPAN;
            return new Placement(this, oversized, c0x, c1x, c0z, c1z);
        }

        /** Caller holds {@code this}. */
        private void file(Placement p, Member e) {
            if (p.oversized) {
                this.oversized = with(this.oversized, e);
                return;
            }
            Long2ObjectOpenHashMap<Cell> current = this.cells;
            Long2ObjectOpenHashMap<Cell> grown = null;
            for (int cz = p.c0z; cz <= p.c1z; cz++) {
                for (int cx = p.c0x; cx <= p.c1x; cx++) {
                    long key = cellKey(cx, cz);
                    Cell cell = current.get(key);
                    if (cell != null) {
                        cell.members = with(cell.members, e);
                    } else {
                        if (grown == null) grown = new Long2ObjectOpenHashMap<>(current);
                        grown.put(key, new Cell(new Member[] {e}));
                    }
                }
            }
            if (grown != null) this.cells = grown;
        }

        /** Caller holds {@code this}. */
        private void unfile(Placement p, Member e) {
            if (p.oversized) {
                this.oversized = without(this.oversized, e);
                return;
            }
            Long2ObjectOpenHashMap<Cell> current = this.cells;
            Long2ObjectOpenHashMap<Cell> shrunk = null;
            for (int cz = p.c0z; cz <= p.c1z; cz++) {
                for (int cx = p.c0x; cx <= p.c1x; cx++) {
                    long key = cellKey(cx, cz);
                    Cell cell = current.get(key);
                    if (cell == null) continue;
                    Member[] members = cell.members;
                    Member[] rest = without(members, e);
                    if (rest == members) continue;
                    if (rest.length != 0) {
                        cell.members = rest;
                    } else {
                        // Readers holding the old map may still see e here: a
                        // false positive, never a false negative.
                        if (shrunk == null) shrunk = new Long2ObjectOpenHashMap<>(current);
                        shrunk.remove(key);
                    }
                }
            }
            if (shrunk != null) this.cells = shrunk;
        }
    }

    private static long cellKey(int cx, int cz) {
        return (cx & 0xFFFFFFFFL) | ((long) cz << 32);
    }

    private static Member[] with(Member[] members, Member e) {
        Member[] next = Arrays.copyOf(members, members.length + 1);
        next[members.length] = e;
        return next;
    }

    private static Member[] without(Member[] members, Member e) {
        for (int i = 0; i < members.length; i++) {
            if (members[i] == e) {
                if (members.length == 1) return NONE;
                Member[] next = new Member[members.length - 1];
                System.arraycopy(members, 0, next, 0, i);
                System.arraycopy(members, i + 1, next, i, members.length - i - 1);
                return next;
            }
        }
        return members;
    }

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
        if (!isHard(e)) return;
        add(((Holder) level).fabrolia$hardColliderIndex(), (Member) e);
    }

    public static void onRemove(Entity e) {
        remove((Member) e);
    }

    static void add(LevelIndex index, Member member) {
        // Re-added without an intervening remove: never file twice.
        remove(member);
        Placement placement;
        synchronized (index) {
            placement = index.placementFor(member.fabrolia$hardColliderBox());
            index.file(placement, member);
            member.fabrolia$setHardColliderPlacement(placement);
        }
        // A box stored after the read above but before publication found no
        // placement to re-file; check the stored box once more.
        onBoundingBox(member, placement, member.fabrolia$hardColliderBox());
    }

    /*
     * A placement is replaced only under the monitor of the index that owns it
     * (a null placement: the index being filed into). Writers re-read the
     * placement under that monitor and retry if it now belongs to another
     * index, so a remove racing a re-file can never resurrect a dead entry.
     */

    static void remove(Member member) {
        Placement seen = member.fabrolia$hardColliderPlacement();
        while (seen != null) {
            LevelIndex index = seen.index;
            synchronized (index) {
                Placement current = member.fabrolia$hardColliderPlacement();
                if (current != null && current.index == index) {
                    index.unfile(current, member);
                    member.fabrolia$setHardColliderPlacement(null);
                    return;
                }
                seen = current;
            }
        }
    }

    /**
     * {@code Entity.setBoundingBox} HEAD hook for indexed entities, called with
     * the box about to be stored. Lock-free unless the box leaves its cells.
     */
    public static void onBoundingBox(Member member, Placement placement, AABB next) {
        Placement seen = placement;
        while (seen != null && !seen.covers(next)) {
            LevelIndex index = seen.index;
            synchronized (index) {
                Placement current = member.fabrolia$hardColliderPlacement();
                if (current != null && current.index == index) {
                    if (current.covers(next)) return;
                    Placement moved = index.placementFor(next);
                    index.file(moved, member);
                    index.unfile(current, member);
                    member.fabrolia$setHardColliderPlacement(moved);
                    return;
                }
                seen = current;
            }
        }
    }

    /**
     * Any hard collider whose live bounding box, grown by 0.5, intersects the
     * query box? Called from the Oxidizium/rustmi collision fast path through
     * a {@code publicLookup().findStatic} MethodHandle — keep this signature.
     */
    public static boolean anyNear(ServerLevel level,
                                  double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ) {
        return anyNear(((Holder) level).fabrolia$hardColliderIndex(), minX, minY, minZ, maxX, maxY, maxZ);
    }

    static boolean anyNear(LevelIndex index,
                           double minX, double minY, double minZ,
                           double maxX, double maxY, double maxZ) {
        for (Member e : index.oversized) {
            if (touches(e, minX, minY, minZ, maxX, maxY, maxZ)) return true;
        }
        Long2ObjectOpenHashMap<Cell> cells = index.cells;
        if (cells.isEmpty()) return false;
        int c0x = Mth.floor(minX - 0.5) >> CELL_SHIFT;
        int c1x = Mth.floor(maxX + 0.5) >> CELL_SHIFT;
        int c0z = Mth.floor(minZ - 0.5) >> CELL_SHIFT;
        int c1z = Mth.floor(maxZ + 0.5) >> CELL_SHIFT;
        if (((long) c1x - c0x + 1) * ((long) c1z - c0z + 1) > cells.size()) {
            // Query wider than the occupied set: walk occupied cells instead.
            for (Long2ObjectMap.Entry<Cell> entry : cells.long2ObjectEntrySet()) {
                for (Member e : entry.getValue().members) {
                    if (touches(e, minX, minY, minZ, maxX, maxY, maxZ)) return true;
                }
            }
            return false;
        }
        for (int cz = c0z; cz <= c1z; cz++) {
            for (int cx = c0x; cx <= c1x; cx++) {
                Cell cell = cells.get(cellKey(cx, cz));
                if (cell == null) continue;
                for (Member e : cell.members) {
                    if (touches(e, minX, minY, minZ, maxX, maxY, maxZ)) return true;
                }
            }
        }
        return false;
    }

    private static boolean touches(Member e,
                                   double minX, double minY, double minZ,
                                   double maxX, double maxY, double maxZ) {
        AABB bb = e.fabrolia$hardColliderBox();
        return bb.minX - 0.5 < maxX && bb.maxX + 0.5 > minX
                && bb.minY - 0.5 < maxY && bb.maxY + 0.5 > minY
                && bb.minZ - 0.5 < maxZ && bb.maxZ + 0.5 > minZ;
    }
}
