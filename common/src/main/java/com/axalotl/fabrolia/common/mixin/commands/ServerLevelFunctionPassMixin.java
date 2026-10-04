package com.axalotl.fabrolia.common.mixin.commands;

import com.axalotl.fabrolia.common.regions.FunctionPassMark;
import com.axalotl.fabrolia.common.regions.IslandFunctionHead;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Entity tick boundary of {@link FunctionPassMark}: an admitted vanilla
 * {@code tickNonPassenger} body (no gate precedes its commonTick) re-opens the
 * entity's per-tick function pass. Passengers re-open at their exact
 * {@code commonTick} invoke (ServerLevelMixin), after vanilla's gates.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelFunctionPassMixin {

    @Inject(method = "tickNonPassenger(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"))
    private void fabrolia$functionPassTick(Entity entity, CallbackInfo ci) {
        IslandFunctionHead.entityTicked((FunctionPassMark) entity);
    }
}
