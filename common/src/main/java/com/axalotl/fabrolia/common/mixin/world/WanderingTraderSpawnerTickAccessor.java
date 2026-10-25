package com.axalotl.fabrolia.common.mixin.world;

import net.minecraft.world.entity.npc.wanderingtrader.WanderingTraderSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Countdown of a vanilla custom spawner: its world work runs only when it reaches zero. */
@Mixin(WanderingTraderSpawner.class)
public interface WanderingTraderSpawnerTickAccessor {
    @Accessor("tickDelay")
    int fabrolia$tickDelay();
}
