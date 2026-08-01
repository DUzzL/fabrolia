package com.axalotl.fabrolia.common.mixin.lithium;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Fix A of the villager-freeze repair (see {@link FabroliaLithiumBrainMemoryBumpMixin}
 * for Fix B and for the full defect writeup).
 *
 * <p>MixinSquared into lithium's own {@code ai.task.memory_changes.BrainMixin}.
 * That mixin carries the per-{@code Brain} memory-modification counter that
 * lithium's {@code BehaviorMixin} caches {@code hasRequiredMemories()} against:
 *
 * <pre>{@code
 * @Unique private long memoryModCount = 1;                        // PLAIN long
 * public long lithium$getMemoryValueModCount() { return this.memoryModCount; }
 * public void lithium$onMemoryModified()       { this.memoryModCount++; }
 * }</pre>
 *
 * <p>Two defects, both fatal once brains are written from more than one thread:
 * <ul>
 *   <li>{@code memoryModCount++} is a non-atomic read-modify-write. Two workers
 *       bumping the same brain concurrently lose an update — and the lost bump is
 *       exactly the one that would have re-armed a behavior whose start gate is
 *       already latched on a stale count (Fix B).</li>
 *   <li>The field is non-volatile. Fabrolia's end-of-tick barrier chain does give
 *       a release/acquire pair between ticks, so a cross-thread bump is visible in
 *       tick N+1 — but there is no barrier BETWEEN workers WITHIN a tick, and
 *       brains are written cross-entity ({@code BehaviorUtils.setWalkAndLookTargetMemories},
 *       {@code LivingEntity.setLastHurtByMob}, raid/golem/trading paths), so a bump
 *       can stay invisible for the remainder of the tick it was made in.</li>
 * </ul>
 *
 * <p>Replacing the field with an {@link AtomicLong} makes the bump an atomic RMW
 * with release semantics and the read an acquire. Lithium's {@code memoryModCount}
 * is abandoned in place — neither wrapper calls {@code original}, so the plain field
 * is never read or written again. Cost is one {@code AtomicLong} per brain and one
 * CAS per memory-PRESENCE change (not per memory write: lithium only bumps on a
 * null/non-null transition, and Fix B replicates that condition exactly), against a
 * plain volatile read on the hot side — {@code hasRequiredMemories} runs once per
 * non-running behavior per brain tick.
 *
 * <p>Direct house precedent: {@link FabroliaLithiumEntityMovementTrackerMixin}
 * replaces lithium's plain {@code long maxChangeTime} with a CAS-max
 * {@code AtomicLong} the same way.
 *
 * <p>Casts to nothing lithium-specific itself, but it cannot load without lithium's
 * mixin class existing — {@code MixinCanceller} gates it behind {@code !LITHIUM}.
 */
@Mixin(targets = "net.caffeinemc.mods.lithium.mixin.ai.task.memory_changes.BrainMixin", remap = false)
public abstract class FabroliaLithiumBrainMemoryCounterMixin {

    /**
     * Supersedes lithium's shadowed plain {@code memoryModCount}. Seeded to 1 to
     * match lithium's initial value: {@code BehaviorMixin.cachedMemoryModCount}
     * starts at -1, so the first {@code hasRequiredMemories} call must miss.
     */
    @Unique
    private final AtomicLong fabrolia$memoryModCount = new AtomicLong(1L);

    /** Does NOT call original — lithium's {@code memoryModCount++} is dead code from here on. */
    @WrapMethod(method = "lithium$onMemoryModified")
    private void fabrolia$onMemoryModified(Operation<Void> original) {
        this.fabrolia$memoryModCount.incrementAndGet();
    }

    /** Does NOT call original. */
    @WrapMethod(method = "lithium$getMemoryValueModCount")
    private long fabrolia$getMemoryValueModCount(Operation<Long> original) {
        return this.fabrolia$memoryModCount.get();
    }
}
