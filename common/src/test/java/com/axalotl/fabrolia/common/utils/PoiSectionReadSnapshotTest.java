package com.axalotl.fabrolia.common.utils;

import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the immutable value that is volatile-published by PoiSectionMixin. */
@Timeout(30)
class PoiSectionReadSnapshotTest {

    private static final Holder<PoiType> BEDS =
            Holder.direct(new PoiType(Set.of(), 1, 1));
    private static final Holder<PoiType> JOBS =
            Holder.direct(new PoiType(Set.of(), 2, 1));

    @Test
    void oldAndNewPublicationsFreezeEveryStructuralReaderSurfaceTogether() {
        Object monitor = new Object();
        Short2ObjectOpenHashMap<PoiRecord> records = new Short2ObjectOpenHashMap<>();
        Map<Holder<PoiType>, Set<PoiRecord>> grouped = new LinkedHashMap<>();
        grouped.put(BEDS, new LinkedHashSet<>());
        grouped.put(JOBS, new LinkedHashSet<>());

        PoiRecord bed = record(new BlockPos(1, 2, 3), BEDS);
        PoiRecord job = record(new BlockPos(4, 5, 6), JOBS);
        synchronized (monitor) {
            put(records, grouped, bed);
            put(records, grouped, job);
        }
        List<PoiRecord> firstPackOrder = new ArrayList<>(records.values());
        PoiSectionReadSnapshot first =
                PoiSectionReadSnapshot.capture(monitor, grouped, records, false);

        PoiRecord replacement = record(new BlockPos(7, 8, 9), BEDS);
        synchronized (monitor) {
            remove(records, grouped, bed);
            put(records, grouped, replacement);
        }
        List<PoiRecord> secondPackOrder = new ArrayList<>(records.values());
        PoiSectionReadSnapshot second =
                PoiSectionReadSnapshot.capture(monitor, grouped, records, true);

        assertEquals(firstPackOrder, first.records(),
                "pack order must be frozen from records.values(), not reconstructed by group order");
        assertSame(bed, first.recordAt(bed.getPos()));
        assertNull(first.recordAt(replacement.getPos()));
        assertFalse(first.isValid());
        assertEquals(List.of(bed), first.groupedRecords().groups().get(0).values());
        assertEquals(List.of(job), first.groupedRecords().groups().get(1).values());

        assertEquals(secondPackOrder, second.records());
        assertNull(second.recordAt(bed.getPos()));
        assertSame(replacement, second.recordAt(replacement.getPos()));
        assertTrue(second.isValid());
        assertEquals(List.of(replacement),
                second.groupedRecords().groups().get(0).values());

        synchronized (monitor) {
            records.clear();
            grouped.clear();
        }
        assertSame(bed, first.recordAt(bed.getPos()),
                "an old volatile publication must remain detached after later writes");
        assertSame(replacement, second.recordAt(replacement.getPos()));
        assertThrows(UnsupportedOperationException.class,
                () -> first.records().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> first.groupedRecords().groups().get(0).values().clear());
    }

    @Test
    void cachedPredicatesAndConsumersNeverRunUnderTheWriterMonitor() {
        Object monitor = new Object();
        Short2ObjectOpenHashMap<PoiRecord> records = new Short2ObjectOpenHashMap<>();
        Map<Holder<PoiType>, Set<PoiRecord>> grouped = new LinkedHashMap<>();
        PoiRecord bed = record(new BlockPos(2, 3, 4), BEDS);
        grouped.put(BEDS, new LinkedHashSet<>());
        synchronized (monitor) {
            put(records, grouped, bed);
        }

        PoiSectionReadSnapshot snapshot =
                PoiSectionReadSnapshot.capture(monitor, grouped, records, true);
        snapshot.groupedRecords().stream(type -> {
            assertFalse(Thread.holdsLock(monitor));
            return true;
        }, poi -> {
            assertFalse(Thread.holdsLock(monitor));
            return true;
        }).forEach(poi -> assertFalse(Thread.holdsLock(monitor)));
    }

    @Test
    void concurrentWriterRepublishAndLockFreeReadersStayCoherentAndCmeFree()
            throws Exception {
        Object monitor = new Object();
        Short2ObjectOpenHashMap<PoiRecord> records = new Short2ObjectOpenHashMap<>();
        Map<Holder<PoiType>, Set<PoiRecord>> grouped = new HashMap<>();
        grouped.put(BEDS, new HashSet<>());
        grouped.put(JOBS, new HashSet<>());
        for (int slot = 0; slot < 32; slot++) {
            PoiRecord record = record(position(slot), (slot & 1) == 0 ? BEDS : JOBS);
            put(records, grouped, record);
        }

        Published published = new Published();
        published.snapshot = PoiSectionReadSnapshot.capture(
                monitor, grouped, records, true);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> writer = threads.submit(() -> {
            start.await();
            for (int round = 0; round < 20_000; round++) {
                int slot = round & 31;
                BlockPos pos = position(slot);
                synchronized (monitor) {
                    PoiRecord old = records.get(SectionPos.sectionRelativePos(pos));
                    remove(records, grouped, old);
                    Holder<PoiType> nextType = (round & 1) == 0 ? JOBS : BEDS;
                    put(records, grouped, record(pos, nextType));
                    published.snapshot = PoiSectionReadSnapshot.capture(
                            monitor, grouped, records, (round & 1) == 0);
                }
            }
            return null;
        });
        Future<?> reader = threads.submit(() -> {
            start.await();
            for (int iteration = 0; iteration < 10_000; iteration++) {
                PoiSectionReadSnapshot snapshot = published.snapshot;
                Set<PoiRecord> groupedRecords = new HashSet<>();
                snapshot.groupedRecords().groups().forEach(
                        group -> groupedRecords.addAll(group.values()));
                assertEquals(snapshot.records().size(), groupedRecords.size(),
                        "one publication exposed different grouped and pack membership");
                for (PoiRecord record : snapshot.records()) {
                    assertTrue(groupedRecords.contains(record));
                    assertSame(record, snapshot.recordAt(record.getPos()),
                            "one publication exposed a torn positional index");
                }
            }
            return null;
        });

        start.countDown();
        try {
            writer.get(20, TimeUnit.SECONDS);
            reader.get(20, TimeUnit.SECONDS);
        } finally {
            threads.shutdownNow();
            assertTrue(threads.awaitTermination(5, TimeUnit.SECONDS),
                    "cached POI view stress threads did not terminate");
        }
    }

    private static PoiRecord record(BlockPos pos, Holder<PoiType> type) {
        return new PoiRecord(pos, type, () -> { });
    }

    private static BlockPos position(int slot) {
        return new BlockPos(slot & 15, (slot >>> 4) & 15, (slot * 7) & 15);
    }

    private static void put(Short2ObjectOpenHashMap<PoiRecord> records,
                            Map<Holder<PoiType>, Set<PoiRecord>> grouped,
                            PoiRecord record) {
        records.put(SectionPos.sectionRelativePos(record.getPos()), record);
        grouped.computeIfAbsent(record.getPoiType(), ignored -> new HashSet<>()).add(record);
    }

    private static void remove(Short2ObjectOpenHashMap<PoiRecord> records,
                               Map<Holder<PoiType>, Set<PoiRecord>> grouped,
                               PoiRecord record) {
        records.remove(SectionPos.sectionRelativePos(record.getPos()));
        grouped.get(record.getPoiType()).remove(record);
    }

    private static final class Published {
        private volatile PoiSectionReadSnapshot snapshot;
    }
}
