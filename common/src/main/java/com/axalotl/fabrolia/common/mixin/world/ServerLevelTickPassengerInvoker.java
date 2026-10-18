package com.axalotl.fabrolia.common.mixin.world;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Vanilla's passenger tick for a subtree whose body a region worker handed to
 * physical Main ({@code RegionScheduler.deferForeignPlayerPassenger}). The call
 * enters the composed method, so {@code ServerLevelMixin.fabrolia$guardTickPassenger}
 * and vanilla's removed/vehicle gate run exactly as in the passenger loop.
 */
@Mixin(ServerLevel.class)
public interface ServerLevelTickPassengerInvoker {
    @Invoker("tickPassenger")
    void fabrolia$tickPassenger(Entity vehicle, Entity passenger);
}
