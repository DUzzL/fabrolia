package com.axalotl.fabrolia.common.mixin.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface EntityNetherPortalInvoker {
    @Invoker("calculatePassengerTransition")
    TeleportTransition fabrolia$passengerTransition(TeleportTransition transition, Entity passenger);
}
