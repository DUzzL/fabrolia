package com.axalotl.fabrolia.common.mixin.timer;

import net.minecraft.world.entity.decoration.ArmorStand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Timer clock fix: the double-hit window ({@code TimerClocks}). */
@Mixin(ArmorStand.class)
public interface ArmorStandTimerAccessor {
    @Accessor("lastHit")
    long fabrolia$lastHit();

    @Accessor("lastHit")
    void fabrolia$setLastHit(long value);
}
