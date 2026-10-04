package com.axalotl.fabrolia.common.parallelised.utils;

import com.axalotl.fabrolia.api.fastutil.Long2IntConcurrentHashMap;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@Isolated
class SpawnCensusQueryAcceptanceTest {
    private final Field[] fields = new Field[3];
    private final Object[] previous = new Object[3];
    private long previousAdds;
    private long previousRemoves;

    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @BeforeEach
    void isolateFailureAuthority() throws Exception {
        previousAdds = SpawnCensusDiag.EV_ADD.get();
        previousRemoves = SpawnCensusDiag.EV_REMOVE.get();
        String[] names = {"pool", "transitionPool", "orphanFatalFailure"};
        for (int i = 0; i < names.length; i++) {
            fields[i] = RegionScheduler.class.getDeclaredField(names[i]);
            fields[i].setAccessible(true);
            previous[i] = fields[i].get(null);
            fields[i].set(null, null);
        }
    }

    @AfterEach
    void restoreFailureAuthority() throws Exception {
        SpawnCensusDiag.EV_ADD.set(previousAdds);
        SpawnCensusDiag.EV_REMOVE.set(previousRemoves);
        for (int i = 0; i < fields.length; i++) fields[i].set(null, previous[i]);
    }

    @Test
    void eachQueryResetsAcceptanceAndReadsVectorsAndTickingAfterTheQuery() {
        SpawnCensus.LevelCensus census = new SpawnCensus.LevelCensus();
        census.live.put(1L, vector(2, 1));
        census.corpse.put(1L, vector(30, 20));
        census.live.put(2L, vector(100, 100));
        census.live.put(3L, vector(9, 9));
        census.corpse.put(3L, vector(6, 6));
        Long2IntConcurrentHashMap ticking = new Long2IntConcurrentHashMap();
        List<Long> queries = new ArrayList<>();
        NaturalSpawner.ChunkGetter getter = (key, accepted) -> {
            queries.add(key);
            if (key == 2L) return;
            if (key == 3L) {
                census.live.put(3L, vector(4, 4));
                ticking.put(3L, 1);
            }
            // The census callback records eligibility only; it does not inspect a chunk.
            accepted.accept(null);
        };
        long[] keys = {1, 2, 3};
        SpawnCensus.CensusPart part = new SpawnCensus.CensusPart(0, keys.length);
        SpawnCensus.fabrolia$reduceRange(census, ticking, getter, keys, part, null);
        SpawnCensus.Reduced result = SpawnCensus.fabrolia$mergeParts(keys, new SpawnCensus.CensusPart[] {part});

        assertEquals(List.of(1L, 2L, 3L), queries);
        assertEquals(12, result.counts().getInt(MobCategory.values()[0]));
        assertEquals(11, result.totalMobs());
        assertEquals(1, result.mobSeed().get(1L)[0]);
        assertFalse(result.mobSeed().containsKey(2L));
        assertEquals(10, result.mobSeed().get(3L)[0]);
    }

    @Test
    void failedQueryIsTheExactWinnerAndCannotAdvanceToAnotherKey() {
        SpawnCensus.LevelCensus census = new SpawnCensus.LevelCensus();
        census.live.put(1L, vector(2, 1));
        RuntimeException failure = new RuntimeException("query failed");
        List<Long> queries = new ArrayList<>();
        NaturalSpawner.ChunkGetter getter = (key, accepted) -> {
            queries.add(key);
            if (key == 2L) throw failure;
            accepted.accept(null);
        };
        SpawnCensus.CensusPart part = new SpawnCensus.CensusPart(0, 3);
        assertSame(failure, assertThrows(RuntimeException.class, () ->
                SpawnCensus.fabrolia$reduceRange(census, new Long2IntConcurrentHashMap(),
                        getter, new long[]{1, 2, 3}, part, null)));
        assertEquals(List.of(1L, 2L), queries);
        assertEquals(2, part.counts[0]);
        assertSame(failure, assertThrows(Throwable.class, RegionScheduler::throwWorkerFailureIfAny));
    }

    @Test
    void zeroSweepCannotDiscardAReaddedContribution() throws Exception {
        SpawnCensus.LevelCensus census = new SpawnCensus.LevelCensus();
        PausingVectors vectors = installVectors(census, false, false);
        int mob = 1 | 4 | (MobCategory.MONSTER.ordinal() << 3);
        int nonMob = 1 | (MobCategory.MONSTER.ordinal() << 3);
        apply(census, mob, 1);
        apply(census, mob, -1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            vectors.armed.set(true);
            Future<?> sweep = worker.submit(() -> sweep(vectors, census.liveCells));
            assertTrue(vectors.paused.await(10, TimeUnit.SECONDS));
            apply(census, mob, 1);
            apply(census, nonMob, 1);
            vectors.resume.countDown();
            sweep.get(10, TimeUnit.SECONDS);
            assertCounts(census, 2, 1);
            assertArrayEquals(new long[]{1L}, indexedKeys(census));

            apply(census, mob, -1);
            sweep(vectors, census.liveCells);
            assertCounts(census, 1, 0);
            apply(census, nonMob, -1);
            sweep(vectors, census.liveCells);
            assertTrue(vectors.isEmpty());
            assertArrayEquals(new long[0], indexedKeys(census));
        } finally {
            vectors.resume.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void contributionCannotLandInAVectorDetachedBySweep() throws Exception {
        SpawnCensus.LevelCensus census = new SpawnCensus.LevelCensus();
        PausingVectors vectors = installVectors(census, true, true);
        int mob = 2 | 4 | (MobCategory.MONSTER.ordinal() << 3);
        apply(census, mob, 1);
        apply(census, mob, -1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            vectors.armed.set(true);
            Future<?> update = worker.submit(() -> apply(census, mob, 1));
            assertTrue(vectors.paused.await(10, TimeUnit.SECONDS));
            sweep(vectors, census.corpseCells);
            assertTrue(vectors.isEmpty());
            vectors.resume.countDown();
            update.get(10, TimeUnit.SECONDS);
            assertCounts(census, 1, 1);
            assertArrayEquals(new long[]{1L}, indexedKeys(census),
                    "a vector re-created after the sweep is indexed again");

            apply(census, mob, -1);
            sweep(vectors, census.corpseCells);
            assertTrue(vectors.isEmpty());
            assertArrayEquals(new long[0], indexedKeys(census));
        } finally {
            vectors.resume.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void islandSnapshotReadsExactlyItsOwnCellsLiveAndCorpseKeys() {
        SpawnCensus.LevelCensus census = new SpawnCensus.LevelCensus();
        int live = 1 | (MobCategory.MONSTER.ordinal() << 3);
        int corpse = 2 | (MobCategory.MONSTER.ordinal() << 3);
        long a = net.minecraft.world.level.ChunkPos.pack(-1, -1);  // cell (-1,-1)
        long b = net.minecraft.world.level.ChunkPos.pack(-8, 0);   // cell (-1, 0)
        long c = net.minecraft.world.level.ChunkPos.pack(7, 7);    // cell ( 0, 0)
        long d = net.minecraft.world.level.ChunkPos.pack(8, 7);    // cell ( 1, 0)
        long e = net.minecraft.world.level.ChunkPos.pack(-9, -9);  // cell (-2,-2)
        applyAt(census, a, live, 1);
        applyAt(census, b, corpse, 1);
        applyAt(census, c, live, 1);
        applyAt(census, c, corpse, 1);
        applyAt(census, d, live, 1);
        applyAt(census, e, corpse, 1);

        long[] own = {SpawnCensus.CELL_GRID.keyFor(a), SpawnCensus.CELL_GRID.keyFor(c),
                SpawnCensus.CELL_GRID.keyFor(e)};
        long[] expected = {a, c, e};
        java.util.Arrays.sort(expected);
        assertArrayEquals(expected, SpawnCensus.fabrolia$snapshotCellKeys(census, own));
    }

    private static PausingVectors installVectors(
            SpawnCensus.LevelCensus census, boolean corpse, boolean pauseWriter) throws Exception {
        PausingVectors vectors = new PausingVectors(pauseWriter);
        Field field = SpawnCensus.LevelCensus.class.getDeclaredField(corpse ? "corpse" : "live");
        field.setAccessible(true);
        field.set(census, vectors);
        return vectors;
    }

    private static void apply(SpawnCensus.LevelCensus census, int state, int sign) {
        applyAt(census, 1L, state, sign);
    }

    private static void applyAt(SpawnCensus.LevelCensus census, long chunkKey, int state, int sign) {
        invokeCensus("fabrolia$apply",
                new Class<?>[]{SpawnCensus.LevelCensus.class, long.class, int.class, int.class},
                census, chunkKey, state, sign);
    }

    private static void sweep(ConcurrentHashMap<Long, AtomicIntegerArray> vectors,
                              SpawnCensus.CellIndex index) {
        SpawnCensus.fabrolia$sweepZeroVectors(vectors, index);
    }

    /** The ledger keys an island lane owning chunk 1's cell would snapshot. */
    private static long[] indexedKeys(SpawnCensus.LevelCensus census) {
        return SpawnCensus.fabrolia$snapshotCellKeys(census,
                new long[]{SpawnCensus.CELL_GRID.keyFor(1L)});
    }

    private static void invokeCensus(String name, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Method method = SpawnCensus.class.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            method.invoke(null, arguments);
        } catch (InvocationTargetException failure) {
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void assertCounts(SpawnCensus.LevelCensus census, int entities, int mobs) {
        Long2IntConcurrentHashMap ticking = new Long2IntConcurrentHashMap();
        ticking.put(1L, 1);
        long[] keys = {1L};
        SpawnCensus.CensusPart part = new SpawnCensus.CensusPart(0, keys.length);
        SpawnCensus.fabrolia$reduceRange(
                census, ticking, (key, accepted) -> accepted.accept(null), keys, part, null);
        SpawnCensus.Reduced result = SpawnCensus.fabrolia$mergeParts(keys, new SpawnCensus.CensusPart[] {part});
        assertEquals(entities, result.counts().getInt(MobCategory.MONSTER));
        assertEquals(mobs, result.totalMobs());
        if (mobs == 0) {
            assertFalse(result.mobSeed().containsKey(1L));
        } else {
            assertEquals(mobs, result.mobSeed().get(1L)[MobCategory.MONSTER.ordinal()]);
        }
    }

    /** Gates the competing operation at the map boundary, not inside its key lock. */
    private static final class PausingVectors extends ConcurrentHashMap<Long, AtomicIntegerArray> {
        final AtomicBoolean armed = new AtomicBoolean();
        final CountDownLatch paused = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);
        final boolean pauseWriter;

        PausingVectors(boolean pauseWriter) {
            this.pauseWriter = pauseWriter;
        }

        private void pause(boolean writer) {
            if (writer != pauseWriter || !armed.compareAndSet(true, false)) return;
            paused.countDown();
            try {
                if (!resume.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("census interleaving was not released");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
        }

        @Override
        public AtomicIntegerArray computeIfAbsent(
                Long key, Function<? super Long, ? extends AtomicIntegerArray> mappingFunction) {
            AtomicIntegerArray vector = super.computeIfAbsent(key, mappingFunction);
            pause(true);
            return vector;
        }

        @Override
        public AtomicIntegerArray compute(Long key,
                BiFunction<? super Long, ? super AtomicIntegerArray, ? extends AtomicIntegerArray> function) {
            pause(true);
            return super.compute(key, function);
        }

        @Override
        public boolean remove(Object key, Object value) {
            pause(false);
            return super.remove(key, value);
        }

        @Override
        public AtomicIntegerArray computeIfPresent(Long key,
                BiFunction<? super Long, ? super AtomicIntegerArray, ? extends AtomicIntegerArray> function) {
            pause(false);
            return super.computeIfPresent(key, function);
        }
    }

    private static AtomicIntegerArray vector(int entities, int mobs) {
        int categories = MobCategory.values().length;
        AtomicIntegerArray result = new AtomicIntegerArray(2 * categories);
        result.set(0, entities);
        result.set(categories, mobs);
        return result;
    }
}
