package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.ChunkHolder;

/**
 * Incremental per-region lists of chunk holders that feed
 * {@code ChunkMap.forEachBlockTickingChunk} — the structural
 * replacement for the per-tick O(all-chunks) rebuild+regroup (servercore's
 * cacheBlockTickingChunks iteration + fabrolia$groupAndSubmit bucketing were
 * ~8-9% of the server thread at 100 players / 60k chunks).
 *
 * <p>Maintained from {@code SimulationChunkTracker.setLevel} (main thread, inside
 * {@code runDistanceManagerUpdates}) on ENTITY_TICKING threshold crossings — that is the
 * single mutation point of the exact {@code Long2ByteMap} 26.2's
 * {@code forEachBlockTickingChunk} reads (via {@code DistanceManager.forEachEntityTickingChunk}),
 * and c2me's rewritten ticket propagator updates it reliably. The earlier
 * {@code ChunkMap.updateChunkScheduling} hook fired asymmetrically under c2me (the
 * ENTITY_TICKING-loss transition was dropped), so the list accumulated stale chunks, diverged,
 * and latched off — see SimulationChunkTrackerMixin.
 * Region values are COPY-ON-WRITE
 * {@code ChunkHolder[]} snapshots: transitions are rare (chunks enter/leave
 * ticking range as players move), so the copy cost is negligible, and region
 * workers can materialize {@code holder.getTickingChunk()} from a snapshot
 * mid-phase without racing main's mid-pump ticket updates.
 *
 * <p>Self-certifying: the consumer periodically counts the legacy iteration
 * and compares with {@link #memberCount}; any mismatch permanently latches
 * the structure off (consumers fall back to the legacy path) and logs — a
 * chunk-system mod routing level updates around the vanilla seam degrades to
 * the old behavior instead of silently skipping chunks.
 *
 * <p>Main-thread confined writes; readers take the map reference and array
 * snapshots. NOT thread-safe for concurrent writers by design.
 */
public final class RegionTickingChunks {

    private static final ChunkHolder[] EMPTY = new ChunkHolder[0];

    private final Long2ObjectOpenHashMap<ChunkHolder[]> byRegion = new Long2ObjectOpenHashMap<>(64);
    private final Long2LongOpenHashMap memberRegion = new Long2LongOpenHashMap(1024);
    private final int shift;
    private long members;
    private volatile boolean disabled;

    public RegionTickingChunks(int shift) {
        this.shift = shift;
        memberRegion.defaultReturnValue(Long.MIN_VALUE);
    }

    private long regionKey(long chunkPos) {
        int cx = (int) chunkPos;
        int cz = (int) (chunkPos >> 32);
        long rx = (cx >> shift);
        long rz = (cz >> shift);
        return (rx & 0xFFFFFFFFL) | ((rz & 0xFFFFFFFFL) << 32);
    }

    public void onBecameTicking(long chunkPos, ChunkHolder holder) {
        if (disabled || holder == null || memberRegion.containsKey(chunkPos)) {
            return;
        }
        long key = regionKey(chunkPos);
        ChunkHolder[] old = byRegion.getOrDefault(key, EMPTY);
        ChunkHolder[] now = new ChunkHolder[old.length + 1];
        System.arraycopy(old, 0, now, 0, old.length);
        now[old.length] = holder;
        byRegion.put(key, now);
        memberRegion.put(chunkPos, key);
        members++;
    }

    public void onStoppedTicking(long chunkPos) {
        if (disabled) {
            return;
        }
        long key = memberRegion.remove(chunkPos);
        if (key == Long.MIN_VALUE) {
            return;
        }
        members--;
        ChunkHolder[] old = byRegion.get(key);
        if (old == null) {
            return;
        }
        int at = -1;
        for (int i = 0; i < old.length; i++) {
            if (old[i].getPos().pack() == chunkPos) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            return;   // membership said yes but the snapshot disagrees — benign
        }
        if (old.length == 1) {
            byRegion.remove(key);
            return;
        }
        ChunkHolder[] now = new ChunkHolder[old.length - 1];
        System.arraycopy(old, 0, now, 0, at);
        System.arraycopy(old, at + 1, now, at, old.length - at - 1);
        byRegion.put(key, now);
    }

    public Long2ObjectOpenHashMap<ChunkHolder[]> byRegion() {
        return byRegion;
    }

    public long memberCount() {
        return members;
    }

    /** Count the chunks this structure would actually hand to the worker path.
     *  This mirrors {@code ChunkMap.forEachBlockTickingChunk}'s null
     *  {@code getTickingChunk()} filter and is used only by the periodic
     *  self-check. */
    public long materializedCount() {
        long count = 0;
        for (ChunkHolder[] holders : byRegion.values()) {
            for (ChunkHolder holder : holders) {
                if (holder.getTickingChunk() != null) {
                    count++;
                }
            }
        }
        return count;
    }

    public boolean usable() {
        return !disabled && members > 0;
    }

    /** Permanent latch-off: consumers revert to the legacy per-tick path. Reserved for a
     *  genuine composition mismatch (e.g. ServerCore owning the tick source); routine c2me
     *  drift is healed by {@link #clearForRebuild} + re-add, not by disabling. */
    public void disable() {
        disabled = true;
        byRegion.clear();
        memberRegion.clear();
        members = 0;
    }

    /** Drop all membership WITHOUT latching off — the consumer immediately re-adds the
     *  authoritative {@code forEachBlockTickingChunk} set. Used by the periodic self-check to
     *  re-sync after drift: c2me-notickvd mutates the simulation level set in bulk off the
     *  {@code SimulationChunkTracker.setLevel} seam (so some onBecameTicking/onStoppedTicking
     *  are missed), and a chunk reload can swap a holder under a still-live membership. A full
     *  re-sync fixes both over- and under-count in one cheap O(ticking) pass; the submit path's
     *  authoritative inEntityTickingRange filter keeps ticks correct in between re-syncs. */
    public void clearForRebuild() {
        byRegion.clear();
        memberRegion.clear();
        members = 0;
    }
}
