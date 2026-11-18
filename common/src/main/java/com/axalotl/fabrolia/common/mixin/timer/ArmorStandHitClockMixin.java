package com.axalotl.fabrolia.common.mixin.timer;

import com.axalotl.fabrolia.common.regions.TimerClocks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Timer clock fix: the double-hit window is read and written by the attacker's
 * thread, not by the stand's own tick. {@code hurtServer} compares
 * {@code getGameTime()} with {@code lastHit} and stores the new time; a player
 * ticking on Main reads W there while the stand keeps its timers in its island's
 * clock. One hit from Main then left a W stamp in an island-time field, and the
 * next island hit saw a difference below the five-tick window and broke the
 * stand (raid farm A/B, 2026-11-18: island 21 ticks behind W, difference -1).
 * Move the stand's timers to the clock this thread reads first; the stand's next
 * own tick moves them back ({@code EntityTimerClockMixin}). The attacker owns the
 * stand for the hit, as for the write vanilla does here.
 */
@Mixin(ArmorStand.class)
public abstract class ArmorStandHitClockMixin {

    @Inject(method = "hurtServer", at = @At("HEAD"))
    private void fabrolia$hitWindowOnTheAttackersClock(ServerLevel level, DamageSource source, float damage,
                                                      CallbackInfoReturnable<Boolean> cir) {
        TimerClocks.ensureCurrent((TimerClocks.Stamped) (Object) this, level);
    }
}
