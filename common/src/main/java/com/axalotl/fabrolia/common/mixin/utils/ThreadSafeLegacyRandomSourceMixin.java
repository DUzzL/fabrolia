package com.axalotl.fabrolia.common.mixin.utils;

import com.axalotl.fabrolia.common.parallelised.utils.FabroliaRandomStriping;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.MarsagliaPolarGaussian;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe per-instance vanilla LCG streams. Ordinary sources preserve
 * explicit-seed replay and sequential Main/worker handoffs; a worker label
 * alone must not change a menu, entity, or seeded calculation's random stream.
 *
 * <p>Only the owning level may opt its shared world RNG into contention-free
 * worker stripes before publication. An explicit reseed revokes that opt-in:
 * reproducible calculations take precedence over shared-stream throughput.
 */
@Mixin(LegacyRandomSource.class)
public abstract class ThreadSafeLegacyRandomSourceMixin implements FabroliaRandomStriping.SharedWorldSource {

    @Shadow
    @Final
    private AtomicLong seed;

    @Shadow
    @Final
    private MarsagliaPolarGaussian gaussianSource;

    @Unique
    private volatile boolean fabrolia$sharedWorldRandom;

    @Override
    public void fabrolia$markSharedWorldRandom() {
        this.fabrolia$sharedWorldRandom = true;
    }

    /**
     * @author Fabrolia
     * @reason Thread-safe: vanilla body minus the ThreadingDetector throw.
     */
    @Overwrite
    public void setSeed(long seed) {
        this.fabrolia$sharedWorldRandom = false;
        this.seed.set((seed ^ 25214903917L) & 281474976710655L);
        this.gaussianSource.reset();
    }

    /**
     * @author Fabrolia
     * @reason Ordinary sources retain their canonical LCG on every venue.
     *         Only explicitly shared world entropy is striped on workers;
     *         its previous 30-worker CAS contention cost 52% in prof19.
     */
    @Overwrite
    public int next(int bits) {
        if (this.fabrolia$sharedWorldRandom && FabroliaRandomStriping.onWorker()) {
            return FabroliaRandomStriping.next(this, this.seed, bits);
        }
        long current;
        long next;
        do {
            current = this.seed.get();
            next = (current * 25214903917L + 11L) & 281474976710655L;
        } while (!this.seed.compareAndSet(current, next));
        return (int) (next >>> (48 - bits));
    }

    /**
     * @author Fabrolia
     * @reason MarsagliaPolarGaussian caches the second draw of each polar
     *         pair in plain instance fields — serialize pair production.
     *         No nested locking: the inner nextDouble→next() path is the
     *         lock-free CAS above.
     */
    @Overwrite
    public double nextGaussian() {
        synchronized (this.gaussianSource) {
            return this.gaussianSource.nextGaussian();
        }
    }
}
