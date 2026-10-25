package com.axalotl.fabrolia.common.mixin.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Loot tables, fishing, piglin barter and trades draw from one server-wide
 * sequence per table ({@code RandomSequences#get} wraps the shared
 * {@code RandomSequence.random()} in a fresh {@code DirtyMarkingRandomSource}).
 * Islands drew concurrently: two workers could read the same Xoroshiro state
 * (duplicate rolls, the stream advancing once) or tear it (audit C-04). Every
 * draw now holds the shared source's monitor; wrappers of one sequence share it.
 * Which island draws first stays scheduling-dependent.
 */
@Mixin(targets = "net.minecraft.world.RandomSequences$DirtyMarkingRandomSource")
public abstract class RandomSequenceDrawMixin {

    @Shadow @Final private RandomSource random;

    @WrapMethod(method = "fork")
    private RandomSource fabrolia$fork(Operation<RandomSource> original) {
        synchronized (this.random) { return original.call(); }
    }

    @WrapMethod(method = "forkPositional")
    private PositionalRandomFactory fabrolia$forkPositional(Operation<PositionalRandomFactory> original) {
        synchronized (this.random) { return original.call(); }
    }

    @WrapMethod(method = "setSeed")
    private void fabrolia$setSeed(long seed, Operation<Void> original) {
        synchronized (this.random) { original.call(seed); }
    }

    @WrapMethod(method = "nextInt()I")
    private int fabrolia$nextInt(Operation<Integer> original) {
        synchronized (this.random) { return original.call(); }
    }

    @WrapMethod(method = "nextInt(I)I")
    private int fabrolia$nextIntBound(int bound, Operation<Integer> original) {
        synchronized (this.random) { return original.call(bound); }
    }

    @WrapMethod(method = "nextLong")
    private long fabrolia$nextLong(Operation<Long> original) {
        synchronized (this.random) { return original.call(); }
    }

    @WrapMethod(method = "nextBoolean")
    private boolean fabrolia$nextBoolean(Operation<Boolean> original) {
        synchronized (this.random) { return original.call(); }
    }

    @WrapMethod(method = "nextFloat")
    private float fabrolia$nextFloat(Operation<Float> original) {
        synchronized (this.random) { return original.call(); }
    }

    @WrapMethod(method = "nextDouble")
    private double fabrolia$nextDouble(Operation<Double> original) {
        synchronized (this.random) { return original.call(); }
    }

    @WrapMethod(method = "nextGaussian")
    private double fabrolia$nextGaussian(Operation<Double> original) {
        synchronized (this.random) { return original.call(); }
    }
}
