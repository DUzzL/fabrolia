package com.axalotl.fabrolia.common.mixin.entity.spawn;

import com.axalotl.fabrolia.api.utils.LockFreeAppendList;
import net.minecraft.world.level.PotentialCalculator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(PotentialCalculator.class)
public class PotentialCalculatorMixin {

    @Shadow @Final @Mutable
    private List<PotentialCalculator.PointCharge> charges;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void fabrolia$initCharges(CallbackInfo ci) {
        // Shared spawn checks traverse a bounded prefix while other regions append.
        // Install after vanilla initialization, using the growable concurrent delegate.
        List<PotentialCalculator.PointCharge> concurrentCharges = new LockFreeAppendList<>();
        concurrentCharges.addAll(charges);
        charges = concurrentCharges;
    }
}
