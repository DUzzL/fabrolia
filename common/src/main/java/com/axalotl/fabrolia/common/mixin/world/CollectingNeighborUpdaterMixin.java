package com.axalotl.fabrolia.common.mixin.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater.NeighborUpdates;
import net.minecraft.world.level.redstone.NeighborUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Per-thread CollectingNeighborUpdater state.
 *
 * Until 2026-06-13 a {@code synchronized} wrap serialised addAndRun across
 * threads. The held region included {@code runUpdates}, which descends into
 * redstone → {@code Level.getBlockState} → {@code ServerChunkCache
 * .fabrolia$awaitAfterTicket}. A worker that grabbed the monitor first and then
 * parked on a chunk-ticket future would wedge the Server thread when it tried
 * to take the same monitor from its own IslandScheduledTicks slice — main
 * never reached the pumpUntilDone barrier, so the chunk-system pollTask was
 * never drained, and the worker's future never completed. Park-cycle.
 *
 * IslandScheduledTicks isolates redstone traffic by position (islands are ≥16
 * unloaded chunks apart, cascades cannot cross), so vanilla's invariant
 * "one thread per CNU" holds per-thread — the cross-thread monitor was
 * unnecessary in the first place. Each thread now owns its own Frame; no
 * shared mutable state remains.
 *
 * The vanilla {@code stack}/{@code addedThisLayer}/{@code count} fields stay
 * so Carpet's accessor {@code setCount} doesn't crash; it now writes a dead
 * field (Carpet's reset feature degrades silently — acceptable trade-off for
 * fixing a real deadlock). Oxidizium's drainEnter/drainExit injections on
 * addAndRun are outside this wrap and still fire in order.
 */
@Mixin(CollectingNeighborUpdater.class)
public abstract class CollectingNeighborUpdaterMixin implements NeighborUpdater {

    @Unique
    private static final Logger FABROLIA$LOG =
            LoggerFactory.getLogger("CollectingNeighborUpdater(fabrolia)");

    @Shadow @Final
    private Level level;

    @Shadow @Final
    private int maxChainedNeighborUpdates;

    @Shadow
    private Consumer<BlockPos> debugListener;

    @Unique
    private final ThreadLocal<Frame> fabrolia$frame = ThreadLocal.withInitial(Frame::new);

    /**
     * Per-thread queue + recursion stack. Single-threaded by construction —
     * each calling thread owns its own Frame. Plain ArrayList / ArrayDeque,
     * no synchronisation, no allocations on the hot path beyond what vanilla
     * already does.
     */
    @Unique
    private static final class Frame {
        final ArrayDeque<NeighborUpdates> stack = new ArrayDeque<>();
        final ArrayList<NeighborUpdates> addedThisLayer = new ArrayList<>();
        int count = 0;
    }

    @WrapMethod(method = "addAndRun")
    private void fabrolia$addAndRun(BlockPos pos, NeighborUpdates update,
                                 Operation<Void> original) {
        // Bypass original entirely — vanilla state lives in shared fields that
        // are now unused. We re-implement vanilla's addAndRun + runUpdates
        // bit-for-bit against per-thread state.
        Frame f = this.fabrolia$frame.get();
        boolean wasEmpty = f.stack.isEmpty() && f.addedThisLayer.isEmpty();
        // Vanilla treats a negative cap as unlimited.  Without the explicit
        // guard, -1 made every update look over-cap and silently discarded the
        // complete neighbour chain.
        boolean overCap = this.maxChainedNeighborUpdates >= 0
                && f.count >= this.maxChainedNeighborUpdates;
        f.count++;
        if (!overCap) {
            f.addedThisLayer.add(update);
        } else if (f.count == this.maxChainedNeighborUpdates + 1) {
            FABROLIA$LOG.error(
                    "Too many chained neighbor updates (>= {}); ignoring further updates",
                    this.maxChainedNeighborUpdates);
        }
        if (wasEmpty) {
            fabrolia$runUpdates(f);
        }
    }

    /** Mirrors vanilla {@code runUpdates} against the per-thread frame.
     *  try/finally captures the vanilla try/catch behaviour of clearing state
     *  on any exit path. */
    @Unique
    private void fabrolia$runUpdates(Frame f) {
        try {
            while (!f.stack.isEmpty() || !f.addedThisLayer.isEmpty()) {
                for (int i = f.addedThisLayer.size() - 1; i >= 0; i--) {
                    f.stack.push(f.addedThisLayer.get(i));
                }
                f.addedThisLayer.clear();
                NeighborUpdates top = f.stack.peek();
                if (this.debugListener != null) {
                    top.forEachUpdatedPos(this.debugListener);
                }
                while (f.addedThisLayer.isEmpty()) {
                    if (!top.runNext(this.level)) {
                        f.stack.pop();
                        break;
                    }
                }
            }
        } finally {
            f.stack.clear();
            f.addedThisLayer.clear();
            f.count = 0;
        }
    }
}
