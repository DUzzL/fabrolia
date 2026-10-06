package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ChunkHolder;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Main's pass over vanilla's global block-update set ({@code ServerChunkCache.broadcastChangedChunks}).
 *
 * <p>Publishing a holder ({@code ChunkHolder.broadcastChanges}) reads its chunk: block states and the
 * block-entity map ({@code Level.getBlockEntity}, which may also create a missing entity). An island
 * unit writes the chunks of its cells without the holder monitor, so Main may publish such a holder
 * only while it holds that island's claim; reading it beside a running unit raced the unit's own
 * block-entity map writes (2026-10-06 production crash: a piston block event rehashed the map while
 * Main read it).
 *
 * <p>Holders on Main's lane (no live island owner) are published in place. Each island's holders are
 * published under one non-blocking claim of that island. A busy island receives them as unit-tail
 * hints instead and publishes them itself before its listener tail
 * ({@code fabrolia$flushIslandBlockUpdates}); the global token stays in the set, so a later pass
 * retries a holder whose island stops or retires before publishing it.
 */
public final class GlobalBlockUpdateDrain {

    /** The level-side operations one pass needs; {@link #drain} holds the policy. */
    public interface Lane<H, I> {
        /** Live island whose units may write this holder's chunk, or {@code null} for Main's lane. */
        I writer(H holder);

        boolean mainHolds(I island);

        /** Non-blocking claim; {@code false} means the island is busy and nothing is owned. */
        boolean tryClaim(I island);

        void release(I island);

        /** The island publishes this holder at its next unit tail. */
        void handOff(I island, H holder);

        void publish(H holder);
    }

    private GlobalBlockUpdateDrain() {}

    /** Physical Main, inside the level's chunk-source tick. */
    public static void drainLevel(RegionizedServer runtime, List<ChunkHolder> snapshot,
                                  Consumer<ChunkHolder> publish) {
        drain(snapshot, new LevelLane(runtime, publish));
    }

    public static <H, I> void drain(List<H> snapshot, Lane<H, I> lane) {
        Map<I, List<H>> byIsland = null;
        for (H holder : snapshot) {
            I island = lane.writer(holder);
            if (island == null) {
                lane.publish(holder);
                continue;
            }
            if (byIsland == null) byIsland = new IdentityHashMap<>();
            byIsland.computeIfAbsent(island, k -> new ArrayList<>()).add(holder);
        }
        if (byIsland == null) return;
        for (Map.Entry<I, List<H>> entry : byIsland.entrySet()) {
            I island = entry.getKey();
            List<H> holders = entry.getValue();
            if (lane.mainHolds(island)) {
                for (H holder : holders) lane.publish(holder);
                continue;
            }
            if (!lane.tryClaim(island)) {
                for (H holder : holders) lane.handOff(island, holder);
                continue;
            }
            Throwable failure = null;
            try {
                for (H holder : holders) lane.publish(holder);
            } catch (Throwable thrown) {
                failure = thrown;
            } finally {
                try {
                    lane.release(island);
                } catch (Throwable cleanup) {
                    if (failure == null) failure = cleanup;
                    else failure.addSuppressed(cleanup);
                }
            }
            if (failure != null) GlobalBlockUpdateDrain.<RuntimeException>rethrow(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T) failure;
    }

    private record LevelLane(RegionizedServer runtime, Consumer<ChunkHolder> publisher)
            implements Lane<ChunkHolder, IslandRegistry.PIsland> {
        @Override
        public IslandRegistry.PIsland writer(ChunkHolder holder) {
            if (runtime == null || !runtime.asyncActive) return null;
            IslandRegistry.PIsland owner = runtime.islandOwnerOfChunk(holder.getPos().pack());
            // Same rule as the blockChanged hint: a retired owner leaves Main as publisher.
            return owner == null || owner.retired ? null : owner;
        }

        @Override
        public boolean mainHolds(IslandRegistry.PIsland island) {
            return runtime.currentThreadHoldsAllClaims() || IslandCommands.mainHolds(island);
        }

        /**
         * Opportunistic, so it yields to a queued unit on both sides of the CAS (the
         * incremental-save rule, {@code ChunkMapMixin.fabrolia$ownerSafeIncrementalSave}):
         * winning the queue→claim gap repeatedly would stall the island's timeline.
         */
        @Override
        public boolean tryClaim(IslandRegistry.PIsland island) {
            if (island.submitQueued.get()) return false;
            if (!IslandCommands.tryMainClaim(island, "bcastDrain")) return false;
            if (island.submitQueued.get()) {
                IslandCommands.mainRelease(island);
                return false;
            }
            return true;
        }

        @Override
        public void release(IslandRegistry.PIsland island) {
            IslandCommands.mainRelease(island);
        }

        @Override
        public void handOff(IslandRegistry.PIsland island, ChunkHolder holder) {
            island.pendingBlockUpdates.add(holder);
        }

        @Override
        public void publish(ChunkHolder holder) {
            publisher.accept(holder);
        }
    }
}
