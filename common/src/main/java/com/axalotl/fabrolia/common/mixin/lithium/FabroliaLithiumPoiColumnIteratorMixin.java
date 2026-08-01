package com.axalotl.fabrolia.common.mixin.lithium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

/**
 * Task #38 — the third {@code columns} consumer: the anonymous
 * {@code AbstractIterator} returned by {@code lithium$getInChunkColumn}. Its
 * {@code computeNext} does {@code loadedElements.get(...)} then
 * {@code next.isPresent()} with no null check; a section evicted by the C2ME
 * unload between the BitSet snapshot and this get NPEs mid-iteration on a
 * worker. Absent → empty Optional = the iterator skips the section, the same
 * weakly-consistent answer every other query path gives.
 *
 * <p>The anonymous class of a mixin is a real class on the classpath, so it is
 * a legal mixin target (precedent: Lithium itself targets
 * {@code GateBehavior$RunningPolicy$1}). Cancelled when Lithium is absent.
 */
@Mixin(targets = "net.caffeinemc.mods.lithium.mixin.ai.poi.SectionStorageMixin$1", remap = false)
public abstract class FabroliaLithiumPoiColumnIteratorMixin {

    @WrapOperation(
            method = "computeNext",
            at = @At(value = "INVOKE",
                    target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;get(J)Ljava/lang/Object;"))
    private Object fabrolia$emptyWhenEvicted(Long2ObjectMap<?> storageMap, long key, Operation<Object> original) {
        Object section = original.call(storageMap, key);
        return section == null ? Optional.empty() : section;
    }
}
