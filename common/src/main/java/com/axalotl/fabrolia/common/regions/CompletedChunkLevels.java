package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongConsumer;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.Arrays;
import java.util.function.IntPredicate;

/**
 * Immutable snapshot of per-chunk levels as of one completed propagation wave,
 * stored as 32x32-chunk pages. A lookup is one small-map get plus an array
 * index: no allocation, and pages untouched by a wave are SHARED with the
 * previous snapshot, so they stay warm in worker caches across ticks. The flat
 * predecessor re-copied every entry into a fresh map on every changed wave,
 * which left every worker's per-chunk gate reading a cold table (2026-10-03
 * production: 44 s of worker time in the block-ticking gate over 3 min).
 *
 * <p>Keys are vanilla packed chunk positions ({@code ChunkPos.pack}: x in the
 * low 32 bits, z in the high 32 bits). Absent chunks read the default level.
 */
public final class CompletedChunkLevels {
    static final int PAGE_SHIFT = 5;
    static final int PAGE_SIDE = 1 << PAGE_SHIFT;
    static final int PAGE_MASK = PAGE_SIDE - 1;
    static final int PAGE_AREA = PAGE_SIDE * PAGE_SIDE;

    private final Long2ObjectOpenHashMap<byte[]> pages;
    private final byte defaultLevel;

    private CompletedChunkLevels(Long2ObjectOpenHashMap<byte[]> pages, byte defaultLevel) {
        this.pages = pages;
        this.defaultLevel = defaultLevel;
    }

    public static CompletedChunkLevels empty(byte defaultLevel) {
        return new CompletedChunkLevels(new Long2ObjectOpenHashMap<>(), defaultLevel);
    }

    public int get(long chunkPos) {
        int x = (int) chunkPos;
        int z = (int) (chunkPos >>> 32);
        byte[] page = pages.get(pageKey(x, z));
        return page == null ? defaultLevel : page[slot(x, z)];
    }

    /** Every present (non-default) chunk whose level matches, page by page. */
    public void forEach(IntPredicate levelFilter, LongConsumer consumer) {
        for (Long2ObjectMap.Entry<byte[]> entry : Long2ObjectMaps.fastIterable(pages)) {
            long pageKey = entry.getLongKey();
            int baseX = ((int) pageKey) << PAGE_SHIFT;
            int baseZ = ((int) (pageKey >>> 32)) << PAGE_SHIFT;
            byte[] page = entry.getValue();
            for (int i = 0; i < PAGE_AREA; i++) {
                byte level = page[i];
                if (level != defaultLevel && levelFilter.test(level)) {
                    consumer.accept(pack(baseX + (i & PAGE_MASK), baseZ + (i >>> PAGE_SHIFT)));
                }
            }
        }
    }

    static long pageKey(int chunkX, int chunkZ) {
        return pack(chunkX >> PAGE_SHIFT, chunkZ >> PAGE_SHIFT);
    }

    static int slot(int chunkX, int chunkZ) {
        return ((chunkZ & PAGE_MASK) << PAGE_SHIFT) | (chunkX & PAGE_MASK);
    }

    static long pack(int x, int z) {
        return (x & 0xFFFFFFFFL) | ((z & 0xFFFFFFFFL) << 32);
    }

    /**
     * Single-writer live mirror of the tracker's level map. {@link #set} follows
     * every mutation; {@link #publish} returns a snapshot sharing every page no
     * write touched since the previous publication.
     */
    public static final class Builder {
        private final byte defaultLevel;
        private final Long2ObjectOpenHashMap<Page> live = new Long2ObjectOpenHashMap<>();
        private final LongOpenHashSet dirty = new LongOpenHashSet();
        private int present;
        private CompletedChunkLevels published;

        public Builder(byte defaultLevel) {
            this.defaultLevel = defaultLevel;
            this.published = empty(defaultLevel);
        }

        /** Non-default chunks in the live mirror (comparable with the mirrored map's size). */
        public int size() {
            return present;
        }

        /** Rebuilds the mirror from {@code source}; the next publish rewrites every affected page. */
        public void resetFrom(it.unimi.dsi.fastutil.longs.Long2ByteMap source) {
            for (LongIterator it = live.keySet().iterator(); it.hasNext(); ) dirty.add(it.nextLong());
            live.clear();
            present = 0;
            for (it.unimi.dsi.fastutil.longs.Long2ByteMap.Entry e : source.long2ByteEntrySet()) {
                set(e.getLongKey(), e.getByteValue());
            }
        }

        public CompletedChunkLevels published() {
            return published;
        }

        public void set(long chunkPos, byte level) {
            int x = (int) chunkPos;
            int z = (int) (chunkPos >>> 32);
            long key = pageKey(x, z);
            int slot = slot(x, z);
            Page page = live.get(key);
            if (level == defaultLevel) {
                if (page == null || page.levels[slot] == defaultLevel) return;
                page.levels[slot] = defaultLevel;
                present--;
                if (--page.present == 0) live.remove(key);
            } else {
                if (page == null) {
                    page = new Page(defaultLevel);
                    live.put(key, page);
                }
                byte previous = page.levels[slot];
                if (previous == level) return;
                if (previous == defaultLevel) {
                    page.present++;
                    present++;
                }
                page.levels[slot] = level;
            }
            dirty.add(key);
        }

        /** Publishes the mirror's current state; unchanged pages keep their identity. */
        public CompletedChunkLevels publish() {
            if (dirty.isEmpty()) return published;
            // clone() copies the table as-is: no rehash, and no fastutil
            // primary-clustering blowup from re-inserting in table order.
            Long2ObjectOpenHashMap<byte[]> next = published.pages.clone();
            for (LongIterator it = dirty.iterator(); it.hasNext(); ) {
                long key = it.nextLong();
                Page page = live.get(key);
                if (page == null) next.remove(key);
                else next.put(key, page.levels.clone());
            }
            dirty.clear();
            published = new CompletedChunkLevels(next, defaultLevel);
            return published;
        }
    }

    private static final class Page {
        final byte[] levels = new byte[PAGE_AREA];
        int present;

        Page(byte defaultLevel) {
            Arrays.fill(levels, defaultLevel);
        }
    }
}
