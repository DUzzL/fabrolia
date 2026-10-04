package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The paged snapshot must answer exactly like a flat copy of the tracker map at each publish. */
final class CompletedChunkLevelsTest {
    private static final byte ABSENT = 33;

    @Test
    void everyPublishedSnapshotEqualsTheFlatMapAtItsPublicationAndNeverChangesAfter() {
        // Vanilla SimulationChunkTracker: level >= 33 removes the entry, default 33.
        assertMatchesFlatMap(ABSENT);
    }

    @Test
    void shiftedRemovalThresholdStoresLevel33() {
        // C2ME notickvd moves the removal threshold and the default to 34.
        assertMatchesFlatMap((byte) 34);
    }

    @Test
    void resyncFromTheLiveMapRepairsAMirrorThatMissedWrites() {
        CompletedChunkLevels.Builder builder = new CompletedChunkLevels.Builder(ABSENT);
        long kept = CompletedChunkLevels.pack(5, 5);
        long stale = CompletedChunkLevels.pack(-40, 70);
        long missed = CompletedChunkLevels.pack(100, -3);
        builder.set(kept, (byte) 20);
        builder.set(stale, (byte) 21);
        builder.publish();

        Long2ByteOpenHashMap live = new Long2ByteOpenHashMap();
        live.defaultReturnValue(ABSENT);
        live.put(kept, (byte) 22);
        live.put(missed, (byte) 30);
        builder.resetFrom(live);
        CompletedChunkLevels repaired = builder.publish();
        assertEquals(2, builder.size());
        assertEquals(22, repaired.get(kept));
        assertEquals(ABSENT, repaired.get(stale));
        assertEquals(30, repaired.get(missed));
    }

    private static void assertMatchesFlatMap(byte absent) {
        Random random = new Random(26_3L + absent);
        CompletedChunkLevels.Builder builder = new CompletedChunkLevels.Builder(absent);
        Long2ByteOpenHashMap live = new Long2ByteOpenHashMap();
        live.defaultReturnValue(absent);
        List<CompletedChunkLevels> snapshots = new ArrayList<>();
        List<Long2ByteOpenHashMap> expected = new ArrayList<>();
        LongOpenHashSet touched = new LongOpenHashSet();

        for (int wave = 0; wave < 200; wave++) {
            int writes = random.nextInt(400);
            for (int i = 0; i < writes; i++) {
                // Straddle page and sign boundaries: x/z in [-70, 70).
                int x = random.nextInt(140) - 70;
                int z = random.nextInt(140) - 70;
                long key = CompletedChunkLevels.pack(x, z);
                int level = random.nextInt(absent + 3);
                if (level >= absent) live.remove(key);
                else live.put(key, (byte) level);
                builder.set(key, live.get(key));
                touched.add(key);
            }
            assertEquals(live.size(), builder.size());
            snapshots.add(builder.publish());
            expected.add(new Long2ByteOpenHashMap(live));
        }

        for (int s = 0; s < snapshots.size(); s++) {
            CompletedChunkLevels snapshot = snapshots.get(s);
            Long2ByteOpenHashMap flat = expected.get(s);
            flat.defaultReturnValue(absent);
            for (long key : touched) {
                assertEquals(flat.get(key), snapshot.get(key), "snapshot " + s + " key " + key);
            }
            if (s % 20 == 0) {
                // The pass cursor: runs of same-page reads, page transitions in
                // both directions and a page that does not exist in between
                // must never answer from a stale page.
                CompletedChunkLevels.Reader reader = snapshot.reader();
                for (int z = -70; z < 70; z++) {
                    for (int x = -70; x < 70; x++) {
                        long key = CompletedChunkLevels.pack(x, z);
                        assertEquals(flat.get(key), reader.get(key), "reader " + s + " key " + key);
                    }
                    assertEquals(absent, reader.get(CompletedChunkLevels.pack(10_000, -10_000)));
                }
            }
            Long2ByteOpenHashMap iterated = new Long2ByteOpenHashMap();
            snapshot.forEach(level -> level <= 31, key -> iterated.put(key, (byte) snapshot.get(key)));
            Long2ByteOpenHashMap filtered = new Long2ByteOpenHashMap();
            flat.long2ByteEntrySet().forEach(e -> {
                if (e.getByteValue() <= 31) filtered.put(e.getLongKey(), e.getByteValue());
            });
            assertEquals(filtered, iterated, "forEach of snapshot " + s);
        }
    }

    @Test
    void clearingAChunkLeavesEarlierSnapshotsIntact() {
        CompletedChunkLevels.Builder builder = new CompletedChunkLevels.Builder(ABSENT);
        long key = CompletedChunkLevels.pack(-1, -1);
        builder.set(key, (byte) 31);
        CompletedChunkLevels first = builder.publish();
        builder.set(key, (byte) 31);
        assertEquals(31, builder.publish().get(key));
        builder.set(key, ABSENT);
        CompletedChunkLevels cleared = builder.publish();
        assertEquals(ABSENT, cleared.get(key));
        assertEquals(31, first.get(key), "a published snapshot is immutable");
    }
}
