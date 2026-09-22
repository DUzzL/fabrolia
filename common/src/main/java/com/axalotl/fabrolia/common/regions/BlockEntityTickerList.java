package com.axalotl.fabrolia.common.regions;

import net.minecraft.world.level.block.entity.TickingBlockEntity;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;
import java.util.Set;

/** Immutable cell snapshot: a wrapper and its registration order are published together. */
public final class BlockEntityTickerList extends AbstractList<TickingBlockEntity> implements RandomAccess {
    private final TickingBlockEntity[] tickers;
    private final long[] sequences;

    private BlockEntityTickerList(TickingBlockEntity[] tickers, long[] sequences) {
        this.tickers = tickers;
        this.sequences = sequences;
    }

    @Override public TickingBlockEntity get(int index) { return tickers[index]; }
    @Override public int size() { return tickers.length; }

    BlockEntityTickerList append(TickingBlockEntity ticker, long sequence) {
        int size = tickers.length;
        TickingBlockEntity[] nextTickers = Arrays.copyOf(tickers, size + 1);
        long[] nextSequences = Arrays.copyOf(sequences, size + 1);
        nextTickers[size] = ticker;
        nextSequences[size] = sequence;
        return new BlockEntityTickerList(nextTickers, nextSequences);
    }

    static BlockEntityTickerList singleton(TickingBlockEntity ticker, long sequence) {
        return new BlockEntityTickerList(new TickingBlockEntity[]{ticker}, new long[]{sequence});
    }

    BlockEntityTickerList without(Set<TickingBlockEntity> removed) {
        Builder next = new Builder(tickers.length);
        for (int i = 0; i < tickers.length; i++) {
            if (!removed.contains(tickers[i])) next.add(tickers[i], sequences[i]);
        }
        return next.size == tickers.length ? this : next.build();
    }

    /** Main-only bulk builder. A build never lends its mutable arrays to readers. */
    static final class Builder {
        private TickingBlockEntity[] tickers;
        private long[] sequences;
        private int size;

        Builder(int capacity) {
            tickers = new TickingBlockEntity[capacity];
            sequences = new long[capacity];
        }

        void add(TickingBlockEntity ticker, long sequence) {
            if (size == tickers.length) {
                int capacity = Math.max(8, size + (size >> 1));
                tickers = Arrays.copyOf(tickers, capacity);
                sequences = Arrays.copyOf(sequences, capacity);
            }
            tickers[size] = ticker;
            sequences[size++] = sequence;
        }

        BlockEntityTickerList build() {
            return new BlockEntityTickerList(Arrays.copyOf(tickers, size), Arrays.copyOf(sequences, size));
        }
    }

    /** A current wrapper is observed once; successors are advanced only after its body. */
    interface Cursor {
        boolean advance();
        boolean hasCurrent();
        TickingBlockEntity ticker();
    }

    /** K-way merge with one reusable head per cell, not one allocation per wrapper.
     * Generic/foreign lists retain the historical missing-sequence MIN_VALUE rule.
     * Equal heads use the same PriorityQueue poll/reinsert discipline as before. */
    static final class Merge implements Cursor {
        private Head[] heap;
        private int size;
        private final Map<TickingBlockEntity, Long> fallbackSequences;
        private Head current;
        private TickingBlockEntity currentTicker;
        private boolean tickerObserved;

        Merge(List<? extends List<TickingBlockEntity>> lists, Map<TickingBlockEntity, Long> fallbackSequences) {
            this.fallbackSequences = fallbackSequences;
            heap = new Head[Math.max(1, lists.size())];
            for (int i = 0; i < lists.size(); i++) {
                List<TickingBlockEntity> list = lists.get(i);
                if (list != null && !list.isEmpty()) {
                    Head head = new Head(list);
                    head.sequence = sequence(head);
                    offer(head);
                }
            }
        }

        // Observe generic successor metadata only after the previous ticker body.
        @Override public boolean advance() {
            if (current != null && ++current.index < current.list.size()) {
                current.sequence = sequence(current);
                offer(current);
            }
            current = poll();
            currentTicker = null;
            tickerObserved = false;
            return current != null;
        }

        @Override public boolean hasCurrent() {
            return current != null;
        }

        @Override public TickingBlockEntity ticker() {
            if (!tickerObserved) {
                currentTicker = current.indexed == null
                        ? current.list.get(current.index)
                        : current.indexed.tickers[current.index];
                tickerObserved = true;
            }
            return currentTicker;
        }

        private long sequence(Head head) {
            if (head.indexed != null) return head.indexed.sequences[head.index];
            Long sequence = fallbackSequences.get(head.list.get(head.index));
            return sequence == null ? Long.MIN_VALUE : sequence;
        }

        private void offer(Head head) {
            if (size == heap.length) heap = Arrays.copyOf(heap, size + (size >> 1) + 1);
            int at = size++;
            while (at > 0) {
                int parent = (at - 1) >>> 1;
                Head previous = heap[parent];
                if (head.sequence >= previous.sequence) break;
                heap[at] = previous;
                at = parent;
            }
            heap[at] = head;
        }

        private Head poll() {
            if (size == 0) return null;
            Head first = heap[0];
            Head last = heap[--size];
            heap[size] = null;
            if (size != 0) {
                int at = 0;
                int half = size >>> 1;
                while (at < half) {
                    int child = (at << 1) + 1;
                    Head smaller = heap[child];
                    int right = child + 1;
                    // Preserve PriorityQueue's left-child choice for equal keys.
                    if (right < size && smaller.sequence > heap[right].sequence) {
                        child = right;
                        smaller = heap[right];
                    }
                    if (last.sequence <= smaller.sequence) break;
                    heap[at] = smaller;
                    at = child;
                }
                heap[at] = last;
            }
            return first;
        }

        private static final class Head {
            final List<TickingBlockEntity> list;
            final BlockEntityTickerList indexed;
            int index;
            long sequence;

            Head(List<TickingBlockEntity> list) {
                this.list = list;
                indexed = list instanceof BlockEntityTickerList snapshot ? snapshot : null;
            }
        }
    }
}
