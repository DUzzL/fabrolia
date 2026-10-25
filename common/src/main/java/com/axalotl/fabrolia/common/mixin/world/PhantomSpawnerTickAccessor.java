package com.axalotl.fabrolia.common.mixin.world;

import net.minecraft.world.level.levelgen.PhantomSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Countdown of a vanilla custom spawner: its world work runs only when it reaches zero. */
@Mixin(PhantomSpawner.class)
public interface PhantomSpawnerTickAccessor {
    @Accessor("nextTick")
    int fabrolia$nextTick();
}
