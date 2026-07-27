package com.axalotl.fabrolia.common.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ExperienceOrb.class)
public abstract class ExperienceOrbMixin {

    @Unique
    private static final Object fabrolia$pickupLock = new Object();

    /**
     * Orb absorption gate. Orbs tick fabrolia, and with enableRegionPlayerTicks players tick
     * on region workers too — without a global gate two players touching the same orb on
     * different workers can both pass the pre-discard checks and double-award it (vanilla
     * race scenario: both read takeXpDelay==0 / count>0 before either discards). Global
     * monitor + isRemoved re-check preserves vanilla "one orb, one award". Pickup is a
     * rare event relative to ticks, so a single static monitor costs nothing measurable.
     */
    @WrapMethod(method = "playerTouch")
    private void fabrolia$syncOrbPickup(Player player, Operation<Void> original) {
        synchronized (fabrolia$pickupLock) {
            if (!((ExperienceOrb) (Object) this).isRemoved()) {
                original.call(player);
            }
        }
    }
}
