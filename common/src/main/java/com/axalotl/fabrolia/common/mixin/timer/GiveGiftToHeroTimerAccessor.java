package com.axalotl.fabrolia.common.mixin.timer;

import net.minecraft.world.entity.ai.behavior.GiveGiftToHero;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Timer clock fix: when the gift walk started ({@code TimerClocks}). */
@Mixin(GiveGiftToHero.class)
public interface GiveGiftToHeroTimerAccessor {
    @Accessor("timeSinceStart")
    long fabrolia$timeSinceStart();

    @Accessor("timeSinceStart")
    void fabrolia$setTimeSinceStart(long value);
}
