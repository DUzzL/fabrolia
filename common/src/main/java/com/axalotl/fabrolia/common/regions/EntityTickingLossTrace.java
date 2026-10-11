package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ChunkLevel;

import java.util.function.LongConsumer;
import java.util.function.LongToIntFunction;

/**
 * Completed-wave filter for "chunk left ENTITY_TICKING" (the pearl-freeze trace).
 *
 * <p>{@code SimulationChunkTracker.setLevel} also sees the intermediate states of
 * a propagation wave: on a level increase the fixed-point propagation first lifts
 * a node to the maximum and then relaxes it back. A chunk whose ticket set merely
 * changed passes through "not entity-ticking" without ever leaving it. So each
 * crossing only records a candidate; the verdict compares the previous completed
 * wave with the relaxed result. Single-threaded: the caller serializes both
 * sides (the chunk-map monitor).
 */
public final class EntityTickingLossTrace {
    private final LongOpenHashSet candidates = new LongOpenHashSet();

    /** {@code setLevel} HEAD: the level of {@code pos} goes {@code from -> to}. */
    public void noteLevelChange(long pos, int from, int to) {
        if (ChunkLevel.isEntityTicking(from) && !ChunkLevel.isEntityTicking(to)) {
            candidates.add(pos);
        }
    }

    public boolean isEmpty() {
        return candidates.isEmpty();
    }

    /**
     * Wave end: reports every candidate that was entity-ticking in the previous
     * completed wave and is not entity-ticking now, then forgets all candidates.
     */
    public void judge(LongToIntFunction previousCompleted, LongToIntFunction current,
                      LongConsumer lost) {
        if (candidates.isEmpty()) return;
        try {
            LongIterator it = candidates.iterator();
            while (it.hasNext()) {
                long pos = it.nextLong();
                if (ChunkLevel.isEntityTicking(previousCompleted.applyAsInt(pos))
                        && !ChunkLevel.isEntityTicking(current.applyAsInt(pos))) {
                    lost.accept(pos);
                }
            }
        } finally {
            candidates.clear();
        }
    }

    public void clear() {
        candidates.clear();
    }
}
