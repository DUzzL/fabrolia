package com.axalotl.fabrolia.common.mixin.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Vanilla 26.1.2 RedstoneTorchBlock concurrency hazard:
 *
 *   - {@code RECENT_TOGGLES} is a {@code WeakHashMap} — non-thread-safe even
 *     for {@code get()} (internal expungeStaleEntries mutates the table).
 *   - {@code tick()} drains stale entries via {@code toggles.remove(0)} in a
 *     while-loop with NO synchronization (bytecode offsets 9..73 in tick).
 *   - {@code isToggledTooFrequently()} does {@code add()} + iteration.
 *
 * {@link com.axalotl.fabrolia.common.regions.IslandScheduledTicks} runs scheduled
 * torch ticks on region workers AND main concurrently. The two paths race the
 * same per-level ArrayList: worker A's {@code remove(0)} arraycopy-shifts and
 * nulls the trailing slot just as worker B's iterator reads
 * {@code elementData[i]}, returning null → NPE on {@code toggle.pos}
 * (crash 2026-06-13 22:18:30).
 *
 * Fix: replace the backing structures with lock-free thread-safe equivalents:
 *
 *   - {@code WeakHashMap} → {@link ConcurrentHashMap}: Level instances live
 *     for the server's lifetime, so we never needed weak-ref semantics.
 *     Lock-free get/computeIfAbsent.
 *   - per-level {@code ArrayList} → {@link CopyOnWriteArrayList}: snapshot
 *     iterators never race {@code remove(0)} (it swaps a volatile array
 *     reference for a new one; in-flight iterators keep reading their frozen
 *     snapshot). Per-position lists are bounded to ~8-60 entries before the
 *     burnout fires, so the {@code O(N)} array copy on {@code remove(0)} /
 *     {@code add(...)} is a few hundred bytes — negligible vs the lock
 *     contention we eliminate.
 *
 * The previous static lock around {@code isToggledTooFrequently} is removed:
 * it never covered the {@code tick()} purge-loop in the first place, and with
 * thread-safe collections it's no longer needed for the add+iterate path.
 */
@Mixin(RedstoneTorchBlock.class)
public abstract class RedstoneTorchBlockMixin {

    @Shadow
    @Final
    @Mutable
    private static Map<BlockGetter, List<RedstoneTorchBlock.Toggle>> RECENT_TOGGLES;

    @Shadow
    protected abstract boolean hasNeighborSignal(Level level, BlockPos pos, BlockState state);

    @Shadow
    private static boolean isToggledTooFrequently(Level level, BlockPos pos, boolean add) {
        throw new AssertionError();
    }

    /**
     * Swap the WeakHashMap for a ConcurrentHashMap as soon as the class is
     * loaded. JVM spec serializes {@code <clinit>}, so no other thread can
     * observe RECENT_TOGGLES before our TAIL injection completes.
     */
    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void fabrolia$swapToConcurrentToggleMap(CallbackInfo ci) {
        RECENT_TOGGLES = new ConcurrentHashMap<>();
    }

    /**
     * Replace the body: vanilla's stale-toggle purge is a NON-ATOMIC compound —
     * {@code while (!isEmpty() && gameTime - get(0).when > 60) remove(0)} — and
     * two torches of the SAME level ticking on DIFFERENT island workers race it
     * on the shared per-level list. CoW made each index op safe but not the
     * check-then-act between them: thread B's remove(0) empties the list between
     * thread A's isEmpty and its get(0)/remove(0) — both flavors crashed live
     * (AIOOBE at RedstoneTorchBlock.tick:76 and :77, crash reports 2026-07-07
     * 03:49 + 04:12, 11 players). The purge is now ONE atomic {@code removeIf}
     * (CoW mutators serialize on the internal lock): semantically identical —
     * toggles are appended in gameTime order, so the expired set IS the head
     * prefix — and correct under any interleaving; concurrent purges are
     * idempotent (both remove the same expired entries, nothing else shrinks
     * the list). The LIT logic below is vanilla verbatim; isToggledTooFrequently
     * dispatches to the wrapped snapshot-count above.
     */
    @WrapMethod(method = "tick")
    private void fabrolia$atomicPurgeTick(BlockState state, ServerLevel level, BlockPos pos,
                                       RandomSource random, Operation<Void> original) {
        boolean neighborSignal = this.hasNeighborSignal(level, pos, state);
        List<RedstoneTorchBlock.Toggle> toggles = RECENT_TOGGLES.get(level);
        if (toggles != null && !toggles.isEmpty()) {
            long gameTime = level.getGameTime();
            toggles.removeIf(t -> gameTime - t.when > 60L);
        }
        if (state.getValue(RedstoneTorchBlock.LIT)) {
            if (neighborSignal) {
                level.setBlock(pos, state.setValue(RedstoneTorchBlock.LIT, false), 3);
                if (isToggledTooFrequently(level, pos, true)) {
                    level.levelEvent(1502, pos, 0);
                    level.scheduleTick(pos, level.getBlockState(pos).getBlock(), 160);
                }
            }
        } else if (!neighborSignal && !isToggledTooFrequently(level, pos, false)) {
            level.setBlock(pos, state.setValue(RedstoneTorchBlock.LIT, true), 3);
        }
    }

    /**
     * Replace the body to use a CoW list — bit-for-bit identical semantics to
     * vanilla (optionally append a toggle for {@code pos}, count matches at
     * {@code pos}, return true at 8). We DO NOT call {@code original.call(...)}
     * because vanilla's body would (re)create an ArrayList via the
     * {@code computeIfAbsent} lambda and undo the type swap.
     */
    @WrapMethod(method = "isToggledTooFrequently")
    private static boolean fabrolia$lockFreeToggleCheck(Level level, BlockPos pos, boolean add,
                                                     Operation<Boolean> original) {
        List<RedstoneTorchBlock.Toggle> toggles = RECENT_TOGGLES.computeIfAbsent(
                level, k -> new CopyOnWriteArrayList<>());
        if (add) {
            toggles.add(new RedstoneTorchBlock.Toggle(pos.immutable(), level.getGameTime()));
        }
        int count = 0;
        // CoW iterator reads a frozen snapshot — concurrent remove(0) from
        // tick()'s purge-loop swaps the volatile array reference without
        // touching ours.
        for (RedstoneTorchBlock.Toggle toggle : toggles) {
            if (toggle.pos.equals(pos)) {
                if (++count >= 8) return true;
            }
        }
        return false;
    }
}
