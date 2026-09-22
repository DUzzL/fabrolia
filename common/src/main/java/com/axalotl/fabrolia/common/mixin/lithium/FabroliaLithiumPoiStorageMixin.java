package com.axalotl.fabrolia.common.mixin.lithium;

import com.axalotl.fabrolia.api.fastutil.LockedLong2ObjectMap;
import com.axalotl.fabrolia.common.utils.FabroliaPoiLoadGate;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.caffeinemc.mods.lithium.common.util.functions.FunLongAnd5;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.BitSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Predicate;

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
 * taken ONLY around each {@code columns.get} probe and the BitSet is cloned
 * before unlocking. A separate per-column acquisition lease spans unpack and
 * the second probe, and also the synchronous mapper or eager iterable capture.
 * C2ME unload is deferred rather than waiting for that lease, so a mapper can
 * call Main without a lock inversion. No lease escapes in a lazy iterable.
 */
@Mixin(value = SectionStorage.class, priority = 2000)
public abstract class FabroliaLithiumPoiStorageMixin<R> {

    @Shadow
    @Final
    private Long2ObjectMap<Optional<R>> storage;

    @Shadow
    protected abstract void unpackChunk(ChunkPos chunkPos);

    @WrapMethod(method = "lithium$getNonEmptyPOISections", remap = false)
    private BitSet fabrolia$acquireColumnFlags(int chunkX, int chunkZ, Operation<BitSet> original) {
        long chunkKey = ChunkPos.pack(chunkX, chunkZ);
        return ((FabroliaPoiLoadGate) this).fabrolia$acquirePoiColumn(chunkKey, () -> {
            // The column can be partially visible even on the initial hit
            // while another worker is still unpacking its remaining sections.
            this.unpackChunk(ChunkPos.unpack(chunkKey));
            return original.call(chunkX, chunkZ);
        });
    }

    @WrapMethod(method = "lithium$getFirstInRangeInChunkColumn", remap = false)
    private <S, T, U> U fabrolia$acquireMappedColumn(int chunkX, int chunkZ, long deltaYSqMargin,
            BlockPos center, long radiusSq,
            FunLongAnd5<R, BlockPos, Predicate<Holder<S>>, Predicate<BlockPos>, T, U> sectionMapper,
            Predicate<Holder<S>> predicate, Predicate<BlockPos> filter, T status, Operation<U> original) {
        return ((FabroliaPoiLoadGate) this).fabrolia$acquirePoiColumn(ChunkPos.pack(chunkX, chunkZ),
                () -> original.call(chunkX, chunkZ, deltaYSqMargin, center, radiusSq,
                        sectionMapper, predicate, filter, status));
    }

    @WrapMethod(method = "lithium$getInChunkColumn", remap = false)
    private Iterable<R> fabrolia$acquireColumnElements(int chunkX, int chunkZ, Operation<Iterable<R>> original) {
        return ((FabroliaPoiLoadGate) this).fabrolia$acquirePoiColumn(ChunkPos.pack(chunkX, chunkZ), () -> {
            ArrayList<R> elements = new ArrayList<>();
            original.call(chunkX, chunkZ).forEach(elements::add);
            return List.copyOf(elements);
        });
    }

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
     * NearbyPointOfInterestStream keeps a cloned BitSet after its acquisition
     * lease ends. Its individual probes are weakly consistent and explicitly
     * test Optional.isPresent. Missing sections must therefore be represented
     * as empty, never null. The first-in-range mapper instead runs inside the
     * column acquisition lease above; it cannot safely accept a missing section.
     */
    @WrapOperation(
            method = "lithium$getElementAt",
            at = @At(value = "INVOKE",
                    target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;get(J)Ljava/lang/Object;",
                    remap = false),
            require = 1,
            remap = false)
    private Object fabrolia$emptyWhenEvicted(Long2ObjectMap<?> storageMap, long key, Operation<Object> original) {
        Object section = original.call(storageMap, key);
        return section == null ? Optional.empty() : section;
    }
}
