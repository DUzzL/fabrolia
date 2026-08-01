package com.axalotl.fabrolia.common.mixin.c2me;

import com.axalotl.fabrolia.common.utils.FabroliaPoiLoadGate;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Task #38, review D3 — C2ME's merged {@code c2me$unloadPoi} was a full
 * participant in the POI storage lifecycle that the blanket sync used to
 * cover and the redesign initially never audited. It runs on the main-thread
 * executor (so inside {@code pumpUntilDone} drains, concurrent with every
 * region worker), and its body is {@code flush(pos)} → per-section
 * {@code storage.remove} → {@code loadLock{loadedChunks.remove,
 * pendingLoads.remove}} — i.e. {@code storage} SHRINKS at runtime, and only
 * the last step was under {@code loadLock}.
 *
 * <p>The killer interleave is unload-vs-unpack: a worker's
 * {@code unpackChunk} claims the chunk and parks in the disk join; main
 * unloads the same chunk (flush, removes, clears the bookkeeping); the worker
 * resumes and fans out every section. Final state: sections present +
 * {@code loadedChunks} absent ⇒ the NEXT unpack re-reads disk and overwrites
 * the live sections with stale copies — lost tickets, villager brains holding
 * orphaned {@code PoiRecord}s (the double-bed class of bug). The reverse
 * order leaves {@code columns} bits set for removed sections.
 *
 * <p>Fix: the whole unload body runs under {@code loadLock} (its own inner
 * {@code synchronized (loadLock)} is reentrant), and the unload is SKIPPED
 * while a load is in flight for the chunk — either a pending disk read or an
 * unpack winner between claim and fan-out (the {@code PoiChunkLoadFence}
 * state, reached through {@link FabroliaPoiLoadGate}). Combined with the D4
 * handshake this makes unload and unpack mutually exclusive per chunk. A
 * skipped unload is retried by C2ME's next downgrade pass — the chunk merely
 * stays resident one cycle longer.
 *
 * <p>Lock order: {@code loadLock → storageRW.read/write} (via flush's
 * {@code writeChunk} reads and the removes) — the same direction main's
 * {@code unpackPendingLoads} already uses, and {@code PoiSection::pack} takes
 * no monitor (invariant (ii) in {@code SectionStorageMixin}'s javadoc).
 *
 * <p>Priority 2000 so the method exists at apply time (C2ME merges it at
 * 1000); cancelled by {@code MixinCanceller} when C2ME is absent so a rotted
 * target string fails loudly whenever the method should exist.
 */
@Mixin(value = SectionStorage.class, priority = 2000)
public abstract class PoiUnloadGuardMixin<R, P> {

    @Shadow
    @Final
    private Object loadLock;

    @Shadow
    @Final
    private Long2ObjectMap<CompletableFuture<Optional<SectionStorage.PackedChunk<P>>>> pendingLoads;

    @WrapMethod(method = "c2me$unloadPoi", remap = false)
    private void fabrolia$guardPoiUnload(ChunkPos pos, Operation<Void> original) {
        synchronized (this.loadLock) {
            long chunkKey = pos.pack();
            if (this.pendingLoads.containsKey(chunkKey)
                    || ((FabroliaPoiLoadGate) this).fabrolia$poiLoadInFlight(chunkKey)) {
                // A load is in flight — unloading now would strand it
                // (sections-present / loadedChunks-absent divergence).
                return;
            }
            original.call(pos);
        }
    }
}
