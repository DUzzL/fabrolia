package com.axalotl.fabrolia.common.mixin.timer;

import net.minecraft.world.entity.ai.behavior.VillagerMakeLove;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Timer clock fix: when the villager baby is due ({@code TimerClocks}). */
@Mixin(VillagerMakeLove.class)
public interface VillagerMakeLoveTimerAccessor {
    @Accessor("birthTimestamp")
    long fabrolia$birthTimestamp();

    @Accessor("birthTimestamp")
    void fabrolia$setBirthTimestamp(long value);
}
