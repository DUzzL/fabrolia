package com.axalotl.fabrolia.common.mixin.world;

import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to the targeting radius for player-index bucket pruning —
 *  the distance LOGIC stays inside vanilla's {@code test} (which we call per
 *  candidate); the radius only bounds which buckets are probed. */
@Mixin(TargetingConditions.class)
public interface TargetingConditionsAccessor {

    @Accessor("range")
    double fabrolia$range();
}
