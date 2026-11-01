package com.axalotl.fabrolia.common.mixin.timer;

import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.GateBehavior;
import net.minecraft.world.entity.ai.behavior.ShufflingList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Timer clock fix: behaviours nested in a gate ({@code TimerClocks}). */
@Mixin(GateBehavior.class)
public interface GateBehaviorTimerAccessor {
    @Accessor("behaviors")
    ShufflingList<BehaviorControl<?>> fabrolia$behaviors();
}
