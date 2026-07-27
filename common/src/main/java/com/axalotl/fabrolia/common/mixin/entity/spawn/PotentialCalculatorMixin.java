package com.axalotl.fabrolia.common.mixin.entity.spawn;

import com.axalotl.fabrolia.api.utils.LockFreeAppendList;
import net.minecraft.world.level.PotentialCalculator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

@Mixin(PotentialCalculator.class)
public class PotentialCalculatorMixin {

    @Shadow
    private final List<PotentialCalculator.PointCharge> charges = new LockFreeAppendList<>();
}
