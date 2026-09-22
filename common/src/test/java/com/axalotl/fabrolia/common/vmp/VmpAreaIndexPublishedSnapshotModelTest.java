package com.axalotl.fabrolia.common.vmp;

import com.axalotl.fabrolia.common.mixin.vmp.FabroliaVmpAreaPlayerChunkWatchingManagerMixin;
import com.axalotl.fabrolia.common.mixin.vmp.FabroliaVmpAreaMapAccessor;

import com.ishland.vmp.common.maps.AreaMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.world.level.ChunkPos;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Concurrency model for VMP's serialized mutations and published hot readers. */
class VmpAreaIndexPublishedSnapshotModelTest {

    @Test
    void hotReaderCompletesWhileMutationLeaseIsHeld() throws Exception {
        PublishedLong2ObjectMap<Object[]> snapshots = new PublishedLong2ObjectMap<>();
        long chunk = 0x1234_5678_9abc_def0L;
        Object[] oldSnapshot = {new Object()};
        Object[] newSnapshot = {new Object()};
        snapshots.put(chunk, oldSnapshot);

        ReentrantLock mutationLock = new ReentrantLock();
        CountDownLatch writerEntered = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> writer = executor.submit(() -> {
            mutationLock.lock();
            try {
                writerEntered.countDown();
                await(releaseWriter);
                snapshots.put(chunk, newSnapshot);
            } finally {
                mutationLock.unlock();
            }
        });
        assertTrue(writerEntered.await(5, TimeUnit.SECONDS));

        Future<Object[]> reader = executor.submit(() -> snapshots.get(chunk));
        assertSame(oldSnapshot, reader.get(1, TimeUnit.SECONDS),
                "spawn/despawn readers must not join the VMP writer queue");
        releaseWriter.countDown();
        writer.get(5, TimeUnit.SECONDS);
        assertSame(newSnapshot, snapshots.get(chunk));

        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void mutationLeaseIsReentrantForTickCallingMovePlayer() {
        ReentrantLock mutationLock = new ReentrantLock();
        mutationLock.lock();
        mutationLock.lock();
        try {
            assertEquals(2, mutationLock.getHoldCount(),
                    "VMP tick may invoke the wrapped movePlayer while mutating");
        } finally {
            mutationLock.unlock();
            mutationLock.unlock();
        }
        assertFalse(mutationLock.isLocked());
    }

    @Test
    void publishedArrayIsDetachedAndChangesAsOneCellGeneration() {
        PublishedLong2ObjectMap<Object[]> snapshots = new PublishedLong2ObjectMap<>();
        long chunk = 42L;
        Object first = new Object();
        Object second = new Object();
        Object[] liveVmpStorage = {first, null};

        Object[] firstGeneration = liveVmpStorage.clone();
        snapshots.put(chunk, firstGeneration);
        liveVmpStorage[0] = second;
        liveVmpStorage[1] = first;
        assertArrayEquals(new Object[]{first, null}, snapshots.get(chunk),
                "later AreaMap writes must not mutate an already published generation");

        Object[] secondGeneration = liveVmpStorage.clone();
        snapshots.put(chunk, secondGeneration);
        assertSame(secondGeneration, snapshots.get(chunk),
                "array readers may share the immutable published generation");
        assertArrayEquals(new Object[]{second, first}, snapshots.get(chunk));
    }

    @Test
    void laterDistanceRequestCannotReplaceRadiusAlreadyAppliedByVmp()
            throws Exception {
        Method appliedViewDistance =
                FabroliaVmpAreaPlayerChunkWatchingManagerMixin.class
                        .getDeclaredMethod("fabrolia$appliedViewDistance",
                                AreaMap.class, Object.class);
        appliedViewDistance.setAccessible(true);
        Method refreshTransition =
                FabroliaVmpAreaPlayerChunkWatchingManagerMixin.class
                        .getDeclaredMethod("fabrolia$refreshTransition",
                                AreaMap.class, PublishedLong2ObjectMap.class,
                                long.class, int.class, long.class, int.class);
        refreshTransition.setAccessible(true);

        AccessibleAreaMap<Object> source = new AccessibleAreaMap<>();
        PublishedLong2ObjectMap<Object[]> snapshots = new PublishedLong2ObjectMap<>();
        Set<Long> touched = new HashSet<>();
        Object player = new Object();
        long start = ChunkPos.pack(0, 0);
        int requestedDistance = 2;
        source.add(player, 0, 0, requestedDistance);
        publishAndAssert(refreshTransition, source, snapshots, touched,
                0L, -1, start, requestedDistance);

        long moved = ChunkPos.pack(1, 0);
        source.update(player, 1, 0, requestedDistance);
        requestedDistance = 5; // Published externally while its callback waits.
        int moveApplied = (int) appliedViewDistance.invoke(null, source, player);
        assertEquals(2, moveApplied,
                "bookkeeping must use AreaMap's committed radius, not the newer request");
        publishAndAssert(refreshTransition, source, snapshots, touched,
                start, 2, moved, moveApplied);

        // The delayed distance-change callback then applies and publishes 5.
        source.update(player, 1, 0, requestedDistance);
        int callbackApplied = (int) appliedViewDistance.invoke(null, source, player);
        assertEquals(5, callbackApplied);
        publishAndAssert(refreshTransition, source, snapshots, touched,
                moved, moveApplied, moved, callbackApplied);

        Object absent = new Object();
        InvocationTargetException missing = assertThrows(
                InvocationTargetException.class,
                () -> appliedViewDistance.invoke(null, source, absent));
        assertTrue(missing.getCause() instanceof IllegalStateException,
                "missing applied state must fail closed instead of defaulting to radius zero");
    }

    @Test
    void transitionPublicationMatchesPinnedVmpAcrossEveryMutationShape()
            throws Exception {
        Method refreshTransition =
                FabroliaVmpAreaPlayerChunkWatchingManagerMixin.class.getDeclaredMethod(
                        "fabrolia$refreshTransition",
                        AreaMap.class, PublishedLong2ObjectMap.class,
                        long.class, int.class, long.class, int.class);
        refreshTransition.setAccessible(true);

        AreaMap<Object> source = new AreaMap<>();
        PublishedLong2ObjectMap<Object[]> snapshots = new PublishedLong2ObjectMap<>();
        Set<Long> touched = new HashSet<>();
        Object first = new Object();
        Object second = new Object();

        long firstStart = ChunkPos.pack(0, 0);
        source.add(first, 0, 0, 2);
        publishAndAssert(refreshTransition, source, snapshots, touched,
                0L, -1, firstStart, 2);

        long secondStart = ChunkPos.pack(2, 0);
        source.add(second, 2, 0, 1);
        publishAndAssert(refreshTransition, source, snapshots, touched,
                0L, -1, secondStart, 1);

        long firstMoved = ChunkPos.pack(1, -1);
        source.update(first, 1, -1, 3);
        publishAndAssert(refreshTransition, source, snapshots, touched,
                firstStart, 2, firstMoved, 3);

        source.update(second, 2, 0, 0);
        publishAndAssert(refreshTransition, source, snapshots, touched,
                secondStart, 1, secondStart, 0);

        long farCenter = ChunkPos.pack(1_000_000, -1_000_000);
        assertTimeout(Duration.ofSeconds(2), () -> {
            source.update(first, 1_000_000, -1_000_000, 1);
            publishAndAssert(refreshTransition, source, snapshots, touched,
                    firstMoved, 3, farCenter, 1);
        }, "a far teleport must walk only the old and new view-distance squares");

        source.remove(second);
        publishAndAssert(refreshTransition, source, snapshots, touched,
                secondStart, 0, 0L, -1);
        source.remove(first);
        publishAndAssert(refreshTransition, source, snapshots, touched,
                farCenter, 1, 0L, -1);
        assertEquals(0, snapshots.size());
    }

    private static void publishAndAssert(
            Method refreshTransition,
            AreaMap<Object> source,
            PublishedLong2ObjectMap<Object[]> snapshots,
            Set<Long> touched,
            long oldCenter, int oldDistance,
            long newCenter, int newDistance) throws Exception {
        markSquare(touched, oldCenter, oldDistance);
        markSquare(touched, newCenter, newDistance);
        refreshTransition.invoke(
                new FabroliaVmpAreaPlayerChunkWatchingManagerMixin() {},
                source, snapshots, oldCenter, oldDistance, newCenter, newDistance);

        int populatedCells = 0;
        for (long chunk : touched) {
            Object[] live = source.getObjectsInRangeArray(chunk);
            Object[] published = snapshots.get(chunk);
            if (live.length == 0) {
                assertNull(published, "empty cell " + chunk + " must be absent");
            } else {
                populatedCells++;
                assertArrayEquals(live, published,
                        "published cell " + chunk + " must exactly clone VMP order");
            }
        }
        assertEquals(populatedCells, snapshots.size(),
                "the publication map must contain exactly VMP's populated cells");
    }

    private static void markSquare(Set<Long> chunks, long center, int distance) {
        if (distance < 0) {
            return;
        }
        int centerX = ChunkPos.getX(center);
        int centerZ = ChunkPos.getZ(center);
        for (int x = centerX - distance; x <= centerX + distance; x++) {
            for (int z = centerZ - distance; z <= centerZ + distance; z++) {
                chunks.add(ChunkPos.pack(x, z));
            }
        }
    }

    private static final class AccessibleAreaMap<T> extends AreaMap<T>
            implements FabroliaVmpAreaMapAccessor {
        private final Reference2IntOpenHashMap<Object> appliedDistances =
                new Reference2IntOpenHashMap<>();

        @Override
        public void add(T object, int x, int z, int viewDistance) {
            super.add(object, x, z, viewDistance);
            appliedDistances.put(object, viewDistance);
        }

        @Override
        public void update(T object, int x, int z, int viewDistance) {
            super.update(object, x, z, viewDistance);
            appliedDistances.put(object, viewDistance);
        }

        @Override
        public void remove(T object) {
            super.remove(object);
            appliedDistances.removeInt(object);
        }

        @Override
        public Reference2IntOpenHashMap<Object> fabrolia$getAppliedViewDistances() {
            return appliedDistances;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
