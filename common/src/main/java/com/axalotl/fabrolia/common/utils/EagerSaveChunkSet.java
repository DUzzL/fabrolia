package com.axalotl.fabrolia.common.utils;

import it.unimi.dsi.fastutil.longs.AbstractLongSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * {@code ChunkMap#chunksToEagerlySave}. Island workers add from
 * {@code LevelChunk#markUnsaved} (the chunk's unsaved listener) while Main
 * iterates and removes: vanilla {@code saveChunksEagerly} and C2ME's idle
 * {@code c2me$runOneChunkAutoSave}, which runs many times per idle tick and
 * removes through the iterator. A synchronized wrapper does not cover that
 * iterator, and holding a monitor across the chunk saves inside those loops would
 * block island block writes behind Main's saves.
 *
 * <p>Order is first-add order, as in vanilla's {@code LongLinkedOpenHashSet}.
 * Iteration is lazy over a lock-free queue of per-position nodes, so a loop that
 * stops after a few entries costs only what it consumed.
 * {@link LongIterator#remove()} of a position re-dirtied after the iterator
 * returned it (an island write racing Main's save of that chunk) re-queues it at
 * the tail: exactly vanilla's remove followed by the unsaved listener's add.
 */
public final class EagerSaveChunkSet extends AbstractLongSet {

    private static final class Node {
        final long pos;
        /** Latest add stamp; written under the set monitor. */
        long stamp;

        Node(long pos, long stamp) {
            this.pos = pos;
            this.stamp = stamp;
        }
    }

    /** Position to its one live node; guarded by this. */
    private final Long2ObjectOpenHashMap<Node> live = new Long2ObjectOpenHashMap<>();
    /** Live nodes in first-add order, plus dead ones not yet unlinked by an iterator. */
    private final ConcurrentLinkedQueue<Node> order = new ConcurrentLinkedQueue<>();
    private long clock;

    @Override
    public synchronized boolean add(long pos) {
        Node node = this.live.get(pos);
        if (node != null) {
            node.stamp = ++this.clock;
            return false;
        }
        node = new Node(pos, ++this.clock);
        this.live.put(pos, node);
        this.order.offer(node);
        return true;
    }

    @Override
    public synchronized boolean contains(long pos) {
        return this.live.containsKey(pos);
    }

    /** Leaves the node queued as dead; the next iterator passing it unlinks it. */
    @Override
    public synchronized boolean remove(long pos) {
        return this.live.remove(pos) != null;
    }

    @Override
    public synchronized int size() {
        return this.live.size();
    }

    @Override
    public synchronized void clear() {
        this.live.clear();
        this.order.clear();
    }

    @Override
    public LongIterator iterator() {
        return new LongIterator() {
            private final Iterator<Node> nodes = EagerSaveChunkSet.this.order.iterator();
            private Node next;
            private long nextStamp;
            private Node last;
            private long lastStamp;
            /** The queue iterator's last returned node is {@link #last}. */
            private boolean queueOnLast;

            @Override
            public boolean hasNext() {
                while (this.next == null && this.nodes.hasNext()) {
                    this.queueOnLast = false;
                    Node candidate = this.nodes.next();
                    synchronized (EagerSaveChunkSet.this) {
                        if (EagerSaveChunkSet.this.live.get(candidate.pos) == candidate) {
                            this.next = candidate;
                            this.nextStamp = candidate.stamp;
                            continue;
                        }
                    }
                    this.nodes.remove();   // dead: removed directly or by another iterator
                }
                return this.next != null;
            }

            @Override
            public long nextLong() {
                if (!hasNext()) throw new NoSuchElementException();
                this.last = this.next;
                this.lastStamp = this.nextStamp;
                this.next = null;
                this.queueOnLast = true;
                return this.last.pos;
            }

            @Override
            public void remove() {
                Node node = this.last;
                if (node == null) throw new IllegalStateException();
                this.last = null;
                boolean unlink = true;
                synchronized (EagerSaveChunkSet.this) {
                    Long2ObjectOpenHashMap<Node> map = EagerSaveChunkSet.this.live;
                    if (map.get(node.pos) == node) {
                        if (node.stamp == this.lastStamp) {
                            map.remove(node.pos);
                        } else {
                            Node fresh = new Node(node.pos, node.stamp);
                            map.put(node.pos, fresh);
                            EagerSaveChunkSet.this.order.offer(fresh);
                        }
                    }
                }
                // A lookahead hasNext() may have moved the queue iterator past this
                // node; the dead node is then unlinked by a later pass.
                if (unlink && this.queueOnLast) this.nodes.remove();
                this.queueOnLast = false;
            }
        };
    }
}
