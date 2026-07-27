package com.axalotl.fabrolia.common.regions;

import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.concurrent.atomic.LongAdder;

/**
 * Island-unit chunk-packet snapshot pipeline (2026-07-14, real-player flight
 * regression): the previous design acquired the owner-island claim PER PACKET
 * — pool prebuilds burned a bounded spin then usually fell back, and every
 * main-thread fallback parked main in {@code mainClaim} behind a whole island
 * unit. A real client's continuous chunk-batch acks keep the sender pipeline
 * flowing, so a flight left main rebuilding its entire send backlog at
 * one-claim-wait-per-chunk: ~30ms MSPT persisting after the player stopped,
 * instantly cured by disconnect (the sender state dies with the connection).
 *
 * <p>Now the sender never builds island-owned packets. It REQUESTS them
 * ({@link #request}); the owning unit drains up to {@link #UNIT_BUDGET}
 * requests at END OF UNIT — after the BE phase, with every member region
 * quiescent under the unit claim the coordinator already holds — builds the
 * full packet (the constructor serializes everything: one coherent instant,
 * same guarantee as the claim-held design, zero additional claim traffic),
 * and publishes it to {@code rs.readyChunkPackets}. Main consumes ready
 * packets exactly once, re-validating chunk identity so unload/reload can
 * never ship a stale snapshot. A request whose packet is not ready simply
 * defers that chunk to the sender's next collect pass (vanilla re-offers it)
 * — bounded latency of about one island tick, no main wait, ever.
 */
public final class ChunkPacketSnapshots {

    private static final int UNIT_BUDGET = 64;
    private static final int READY_CAP = 1024;

    public static final LongAdder REQUESTS = new LongAdder();
    public static final LongAdder DEFERRED = new LongAdder();
    public static final LongAdder UNIT_BUILDS = new LongAdder();
    public static final LongAdder TAKEN = new LongAdder();
    public static final LongAdder STALE_DISCARDED = new LongAdder();
    public static final LongAdder INLINE_OWNERLESS = new LongAdder();

    private ChunkPacketSnapshots() {}

    /** Sender-side (main): ask the owner island for a snapshot of chunkKey.
     *  Deduplicated; returns immediately. */
    public static void request(IslandRegistry.PIsland owner, long chunkKey) {
        if (owner.pendingSnapshotKeys.add(chunkKey)) {
            owner.pendingChunkSnapshots.add(chunkKey);
            REQUESTS.increment();
        }
    }

    /** Sender-side (main): take a ready packet for exactly-once send.
     *  Identity-checked against the chunk vanilla resolved THIS tick. */
    public static ClientboundLevelChunkWithLightPacket take(RegionizedServer rs,
                                                            LevelChunk chunk) {
        long key = chunk.getPos().pack();
        Object[] entry = rs.readyChunkPackets.get(key);
        if (entry == null) {
            return null;
        }
        // Identity: unload/reload between build and send — discard, rebuild.
        if (entry[0] != chunk) {
            rs.readyChunkPackets.remove(key, entry);
            STALE_DISCARDED.increment();
            return null;
        }
        // Freshness: a snapshot older than ~1 island tick must not serve a
        // LATE tracker (its full packet would predate incremental updates the
        // area produced since). Within the bound, entries are SHARED — one
        // coherent snapshot serves every player tracking the chunk (the old
        // consume-on-take let one sender steal another's snapshot: the victim
        // fell back to an inline main build).
        IslandRegistry.PIsland owner = (IslandRegistry.PIsland) entry[2];
        long builtAtL = (Long) entry[3];
        if (owner != null && owner.logicalTick - builtAtL > 1) {
            rs.readyChunkPackets.remove(key, entry);
            STALE_DISCARDED.increment();
            return null;
        }
        TAKEN.increment();
        return (ClientboundLevelChunkWithLightPacket) entry[1];
    }

    /** Unit-side (coordinator, END of unit, under the island claim): build up
     *  to {@link #UNIT_BUDGET} requested snapshots. The packet constructor
     *  runs in owner context — the ctor wrap detects the owning coordinator
     *  and skips all claim logic. */
    static void buildPending(RegionizedServer rs, IslandRegistry.PIsland island) {
        if (island.pendingChunkSnapshots.isEmpty()) {
            return;
        }
        ServerLevel level = rs.level;
        int built = 0;
        Long key;
        while (built < UNIT_BUDGET && (key = island.pendingChunkSnapshots.poll()) != null) {
            island.pendingSnapshotKeys.remove(key);
            // Overflow guard: if main stopped consuming (mass disconnect),
            // don't grow the ready store without bound — drop; the sender
            // re-requests if anyone still wants it.
            if (rs.readyChunkPackets.size() >= READY_CAP) {
                continue;
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(
                    net.minecraft.world.level.ChunkPos.getX(key),
                    net.minecraft.world.level.ChunkPos.getZ(key));
            if (chunk == null) {
                continue; // unloaded since the request — nothing to send
            }
            ClientboundLevelChunkWithLightPacket packet =
                    new ClientboundLevelChunkWithLightPacket(
                            chunk, level.getLightEngine(), null, null);
            rs.readyChunkPackets.put(key.longValue(),
                    new Object[]{chunk, packet, island, island.logicalTick});
            built++;
            UNIT_BUILDS.increment();
        }
    }

    public static String status() {
        return "chunkSnap req=" + REQUESTS.sum() + " deferred=" + DEFERRED.sum()
                + " unitBuilds=" + UNIT_BUILDS.sum() + " taken=" + TAKEN.sum()
                + " stale=" + STALE_DISCARDED.sum()
                + " inlineOwnerless=" + INLINE_OWNERLESS.sum();
    }
}
