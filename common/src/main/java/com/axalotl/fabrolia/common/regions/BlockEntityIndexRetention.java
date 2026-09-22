package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.api.fastutil.Long2ObjectConcurrentHashMap;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;

import java.util.Map;
import java.util.Queue;
import java.util.Set;

/** Lifecycle-only operations for the derived block-entity ticker index. */
final class BlockEntityIndexRetention {
    private BlockEntityIndexRetention() {}

    /**
     * Release every strong reference owned only by the derived index.
     * The authoritative vanilla ticker list is deliberately not involved.
     */
    static <T> void clear(Long2ObjectConcurrentHashMap<?> cells,
                          Map<T, Long> sequenceByTicker,
                          Reference2LongOpenHashMap<T> cellByTicker,
                          Set<T> pendingSweep,
                          Queue<T> unitRemoved) {
        cells.clear();
        sequenceByTicker.clear();
        cellByTicker.clear();
        pendingSweep.clear();
        unitRemoved.clear();
    }
}
