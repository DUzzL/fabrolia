package com.axalotl.fabrolia.common.parallelised.utils;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The collision fast path may skip vanilla's walk only when no collider can touch the query. */
final class HardColliderIndexTest {

    private static final class Collider implements HardColliderIndex.Member {
        private volatile HardColliderIndex.Placement placement;
        private AABB box;

        Collider(AABB box) {
            this.box = box;
        }

        /** Mirrors Entity.setBoundingBox: HEAD hook, then the store. */
        void setBoundingBox(AABB next) {
            HardColliderIndex.Placement current = this.placement;
            if (current != null) HardColliderIndex.onBoundingBox(this, current, next);
            this.box = next;
        }

        @Override
        public HardColliderIndex.Placement fabrolia$hardColliderPlacement() {
            return this.placement;
        }

        @Override
        public void fabrolia$setHardColliderPlacement(HardColliderIndex.Placement placement) {
            this.placement = placement;
        }

        @Override
        public AABB fabrolia$hardColliderBox() {
            return this.box;
        }
    }

    private static AABB cube(double x, double y, double z, double size) {
        return new AABB(x, y, z, x + size, y + size, z + size);
    }

    /** A player-sized movement sweep standing right next to {@code (x, z)}. */
    private static boolean queryAt(HardColliderIndex.LevelIndex index, double x, double y, double z) {
        return HardColliderIndex.anyNear(index, x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
    }

    @Test
    void teleportedColliderIsVisibleAtItsDestinationOnTheNextQuery() {
        HardColliderIndex.LevelIndex index = new HardColliderIndex.LevelIndex();
        Collider shulker = new Collider(cube(10, 64, 10, 1));
        HardColliderIndex.add(index, shulker);
        assertTrue(queryAt(index, 10.5, 64, 10.5));

        shulker.setBoundingBox(cube(5000, 64, -7000, 1));

        assertTrue(queryAt(index, 5000.5, 64, -6999.5), "destination must see the collider immediately");
        assertFalse(queryAt(index, 10.5, 64, 10.5), "origin no longer has a collider");
    }

    @Test
    void shortHopAcrossACellBoundaryIsRefiled() {
        HardColliderIndex.LevelIndex index = new HardColliderIndex.LevelIndex();
        Collider boat = new Collider(cube(120, 64, 5, 1.375));
        HardColliderIndex.add(index, boat);

        boat.setBoundingBox(cube(130, 64, 5, 1.375));
        assertTrue(queryAt(index, 130.5, 64, 5.5));
        boat.setBoundingBox(cube(-3, 64, 5, 1.375));
        assertTrue(queryAt(index, -2.5, 64, 5.5));
        assertFalse(queryAt(index, 130.5, 64, 5.5));
    }

    @Test
    void colliderFarWiderThanAnyFixedMarginIsFoundFromItsEdges() {
        HardColliderIndex.LevelIndex index = new HardColliderIndex.LevelIndex();
        // Scale-attribute happy ghast: 64 blocks wide, centre far from its edges.
        Collider ghast = new Collider(new AABB(1000, 60, 1000, 1064, 124, 1064));
        HardColliderIndex.add(index, ghast);
        assertTrue(queryAt(index, 1063.8, 124.2, 1063.8));
        assertTrue(queryAt(index, 999.8, 59, 1000.2));

        // Growing mid-life (scale attribute) without moving its origin.
        ghast.setBoundingBox(new AABB(1000, 60, 1000, 1400, 124, 1400));
        assertTrue(queryAt(index, 1399.7, 100, 1399.7));
    }

    @Test
    void oversizedColliderIsFoundEverywhereItReaches() {
        HardColliderIndex.LevelIndex index = new HardColliderIndex.LevelIndex();
        Collider platform = new Collider(new AABB(-5000, 0, -5000, 5000, 10, 5000));
        HardColliderIndex.add(index, platform);
        assertTrue(queryAt(index, 4999.9, 5, -4999.9));
        assertFalse(queryAt(index, 5010, 5, 0));

        platform.setBoundingBox(cube(20000, 0, 20000, 2));
        assertTrue(queryAt(index, 20001, 0, 20001));
        assertFalse(queryAt(index, 0, 5, 0));
    }

    @Test
    void marginMatchesTheFlatScanAtTheHalfBlockBoundary() {
        HardColliderIndex.LevelIndex index = new HardColliderIndex.LevelIndex();
        Collider boat = new Collider(new AABB(126.75, 64, 0, 127.75, 65, 1));
        HardColliderIndex.add(index, boat);
        // Collider filed only in cell 0; queries lie in cell 1. Grown edge: 128.25.
        assertTrue(HardColliderIndex.anyNear(index, 128.0, 64, 0, 129, 65, 1));
        assertFalse(HardColliderIndex.anyNear(index, 128.25, 64, 0, 129, 65, 1));
    }

    @Test
    void removedAndReAddedCollidersAreFiledExactlyOnce() {
        HardColliderIndex.LevelIndex index = new HardColliderIndex.LevelIndex();
        Collider boat = new Collider(cube(10, 64, 10, 1));
        HardColliderIndex.add(index, boat);
        HardColliderIndex.add(index, boat);
        HardColliderIndex.remove(boat);
        assertFalse(queryAt(index, 10.5, 64, 10.5));

        HardColliderIndex.LevelIndex other = new HardColliderIndex.LevelIndex();
        HardColliderIndex.add(index, boat);
        HardColliderIndex.add(other, boat);
        assertFalse(queryAt(index, 10.5, 64, 10.5), "a level change must not leave the old entry");
        assertTrue(queryAt(other, 10.5, 64, 10.5));
    }

    @Test
    void removeRacingCellLeavingMovesNeverLeavesAnEntry() throws Exception {
        HardColliderIndex.LevelIndex index = new HardColliderIndex.LevelIndex();
        AABB[] stops = {cube(10, 64, 10, 1), cube(900, 64, -900, 1), cube(-4000, 64, 30, 1)};
        for (int round = 0; round < 2000; round++) {
            Collider boat = new Collider(stops[0]);
            HardColliderIndex.add(index, boat);
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            Thread mover = new Thread(() -> {
                awaitQuietly(start);
                for (int i = 1; i < 60; i++) boat.setBoundingBox(stops[i % stops.length]);
            });
            mover.start();
            start.countDown();
            HardColliderIndex.remove(boat);
            mover.join();
            for (AABB stop : stops) {
                assertFalse(HardColliderIndex.anyNear(index, stop.minX, stop.minY, stop.minZ,
                        stop.maxX, stop.maxY, stop.maxZ), "round " + round + " resurrected a removed collider");
            }
        }
    }

    private static void awaitQuietly(java.util.concurrent.CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
