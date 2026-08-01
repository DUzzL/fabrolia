package com.axalotl.fabrolia.common.mixin.lithium;

import com.axalotl.fabrolia.api.fastutil.LockedLong2ObjectMap;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.BitSet;
import java.util.Optional;
import java.util.concurrent.locks.StampedLock;

/**
 * Task #38 — guards for the state Lithium's {@code ai.poi.SectionStorageMixin}
 * merges onto {@code SectionStorage}: the {@code columns}
 * {@code Long2ObjectOpenHashMap<BitSet>} sidecar and the storage reads its
 * consumers do. Cancelled by {@code MixinCanceller} when Lithium is absent
 * (the merged methods would not exist and every injector here would hard-fail,
 * which is exactly the loud behaviour we want when Lithium IS present and a
 * target string rots).
 *
 * <p>Priority 2000: must apply after Lithium (default 1000) so the merged
 * {@code lithium$*} methods exist at apply time. Verified against the merged
 * runtime bytecode in {@code .mixin.out} — the methods and the raw
 * {@code columns} field are present on {@code SectionStorage} with these exact
 * names, and both {@code columns.get} call sites in
 * {@code lithium$getNonEmptyPOISections} are
 * {@code invokevirtual Long2ObjectOpenHashMap.get(J)}.
 *
 * <h3>Why this shape and not a lock around the whole method (review D1)</h3>
 * {@code lithium$getNonEmptyPOISections} on a {@code columns} miss calls
 * {@code unpackChunk} → {@code storage.put} → the storage WRITE lock, and
 * takes {@code loadLock}. Any read-hold across the method therefore
 * self-deadlocks (StampedLock cannot upgrade) or builds
 * {@code storageRW → loadLock}, an AB-BA against main's
 * {@code loadLock → storageRW} in {@code unpackPendingLoads}. So the lock is
 * taken ONLY around each {@code columns.get} probe — hit-check, release,
 * unpack unlocked, re-probe — and the BitSet is <b>cloned inside the read
 * lock</b> so the live, in-place-mutated BitSet never escapes to callers
 * iterating it with {@code nextSetBit} (the Sepals-proven defensive clone).
 * The design's original recipe (read lock across the original, plus outer
 * wraps on the two column consumers) was unimplementable as written; the
 * consumer wraps are deliberately ABSENT, not forgotten.
 */
@Mixin(value = SectionStorage.class, priority = 2000)
public abstract class FabroliaLithiumPoiStorageMixin<R> {

    @Shadow
    @Final
    private Long2ObjectMap<Optional<R>> storage;

    /**
     * Both {@code columns.get} probes (initial hit-check and the
     * post-unpack {@code requireNonNull} re-probe): read-locked against the
     * storage write lock — inside which Lithium's listeners mutate
     * {@code columns} — and cloned before the lock is released. Between the
     * two probes the method runs {@code unpackChunk} with NO lock held.
     * {@code require = 2} pins both call sites; a Lithium update that changes
     * the shape fails loudly instead of leaving one probe bare.
     */
    @WrapOperation(
            method = "lithium$getNonEmptyPOISections",
            at = @At(value = "INVOKE",
                    target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;get(J)Ljava/lang/Object;",
                    remap = false),
            require = 2,
            remap = false)
    private Object fabrolia$snapshotColumn(Long2ObjectOpenHashMap<?> columns, long key, Operation<Object> original) {
        if (this.storage instanceof LockedLong2ObjectMap<?> locked) {
            StampedLock lock = locked.lock();
            long stamp = lock.readLock();
            try {
                Object flags = original.call(columns, key);
                return flags == null ? null : ((BitSet) flags).clone();
            } finally {
                lock.unlockRead(stamp);
            }
        }
        // Wrapper defeated — already screamed about by the one-shot startup
        // assert in SectionStorageMixin; degrade to stock Lithium behaviour.
        return original.call(columns, key);
    }

    /**
     * Rare-by-construction retry: with the C2ME unload guard, an unload can
     * still complete between this method's unpack and its re-probe (the
     * unload is only excluded while a load is in flight). The re-probe then
     * sees null and Lithium throws its
     * {@code "Failed to load POI section data!"} NPE — but the unload also
     * cleared {@code loadedChunks}, so simply running the body again re-reads
     * disk and succeeds. Bounded; anything persistent still surfaces as
     * Lithium's own exception.
     */
    @WrapMethod(method = "lithium$getNonEmptyPOISections", remap = false)
    private BitSet fabrolia$retryColumnAfterUnload(int chunkX, int chunkZ, Operation<BitSet> original) {
        NullPointerException unloadRace = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return original.call(chunkX, chunkZ);
            } catch (NullPointerException e) {
                if (!"Failed to load POI section data!".equals(e.getMessage())) {
                    throw e;
                }
                unloadRace = e;
            }
        }
        throw unloadRace;
    }

    /**
     * The review's third hazard: both column consumers dereference
     * {@code storage.get(...)} with no null check
     * ({@code .orElse(null)} / {@code lithium$getElementAt(...).isPresent()}
     * in {@code NearbyPointOfInterestStream}), and a section can be evicted
     * by the C2ME unload between the BitSet snapshot and the get. Absent
     * section → empty Optional = "nothing here", the weakly-consistent read
     * model every other query path already lives under. The third consumer —
     * the {@code lithium$getInChunkColumn} iterator — is a separate anonymous
     * class, guarded in {@code FabroliaLithiumPoiColumnIteratorMixin}.
     */
    @WrapOperation(
            method = {"lithium$getFirstInRangeInChunkColumn", "lithium$getElementAt"},
            at = @At(value = "INVOKE",
                    target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;get(J)Ljava/lang/Object;",
                    remap = false),
            require = 2,
            remap = false)
    private Object fabrolia$emptyWhenEvicted(Long2ObjectMap<?> storageMap, long key, Operation<Object> original) {
        Object section = original.call(storageMap, key);
        return section == null ? Optional.empty() : section;
    }
}
