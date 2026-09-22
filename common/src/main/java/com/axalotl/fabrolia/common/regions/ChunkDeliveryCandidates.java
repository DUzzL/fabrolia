package com.axalotl.fabrolia.common.regions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * Main-owned preparation cache over the concurrent delivery lifecycle map.
 * The map retains acknowledged records for untrack/Forget; this cache does not.
 * Only irreversible terminal states may be pruned: in particular REQUESTED must
 * keep polling its lease, and RESERVED may return to READY without a map change.
 *
 * <p>Structural publishers call {@link #changed()} AFTER their map transaction.
 * Preparation captures that generation BEFORE iterating, so a concurrent or
 * not-yet-published registration always invalidates the next preparation. No
 * state-transition notification, pending counter, or repair sweep is required.
 */
final class ChunkDeliveryCandidates<R> {
    private final Collection<R> records;
    private final ToLongFunction<R> chunkKey;
    private final Predicate<R> terminal;
    private final AtomicLong generation = new AtomicLong();
    // Keep the source encounter order independently of distance order. Stable
    // sorts must retain the original CHM tie order even after a player moves.
    private final ArrayList<R> encounterOrder = new ArrayList<>();
    private ArrayList<R> ordered = new ArrayList<>();
    private long preparedGeneration = -1;
    private long preparedPosition;

    ChunkDeliveryCandidates(Collection<R> records, ToLongFunction<R> chunkKey,
                            Predicate<R> terminal) {
        this.records = records;
        this.chunkKey = chunkKey;
        this.terminal = terminal;
    }

    /** Any thread, after publishing a structural mutation (including replacement). */
    void changed() {
        generation.incrementAndGet();
    }

    /** Main only. Returned snapshots remain stable across reentrant preparations. */
    ArrayList<R> prepare(long playerPosition) {
        long observedGeneration = generation.get();
        boolean rebuilt = preparedGeneration != observedGeneration;
        boolean pruned = false;
        if (rebuilt) {
            encounterOrder.clear();
            for (R record : records) {
                if (!terminal.test(record)) encounterOrder.add(record);
            }
        } else {
            pruned = encounterOrder.removeIf(terminal);
        }
        if (rebuilt || preparedPosition != playerPosition) {
            ArrayList<R> next = new ArrayList<>(encounterOrder);
            next.sort((a, b) -> Long.compare(
                    distance(chunkKey.applyAsLong(a), playerPosition),
                    distance(chunkKey.applyAsLong(b), playerPosition)));
            ordered = next;
            preparedPosition = playerPosition;
            // Never read generation again here: doing so could swallow a
            // registration which our weakly-consistent iteration did not see.
            preparedGeneration = observedGeneration;
        } else if (pruned) {
            ArrayList<R> next = new ArrayList<>(encounterOrder.size());
            for (R record : ordered) {
                if (!terminal.test(record)) next.add(record);
            }
            ordered = next;
        }
        return ordered;
    }

    private static long distance(long key, long playerPosition) {
        long dx = (int) key - (int) playerPosition;
        long dz = (int) (key >>> 32) - (int) (playerPosition >>> 32);
        return dx * dx + dz * dz;
    }
}
