package com.axalotl.fabrolia.common.mixin.world;

import net.minecraft.world.entity.npc.CatSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Countdown of a vanilla custom spawner: its world work runs only when it reaches zero. */
@Mixin(CatSpawner.class)
public interface CatSpawnerTickAccessor {
    @Accessor("nextTick")
    int fabrolia$nextTick();
}
