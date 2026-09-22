package com.axalotl.fabrolia.common.mixin.server;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ServerLevel.EntityCallbacks.class)
public class ServerLevelEntityCallbacksMixin {

    @Unique
    private final Object fabrolia$lock = new Object();

    @WrapMethod(method = "onTickingStart(Lnet/minecraft/world/entity/Entity;)V")
    private void onTickingStart(Entity entity, Operation<Void> original) {
        synchronized (fabrolia$lock) {
            original.call(entity);
        }
    }

    @WrapMethod(method = "onTickingEnd(Lnet/minecraft/world/entity/Entity;)V")
    private void onTickingEnd(Entity entity, Operation<Void> original) {
        synchronized (fabrolia$lock) {
            original.call(entity);
        }
    }

    @org.spongepowered.asm.mixin.injection.Inject(
            method = "onTrackingStart(Lnet/minecraft/world/entity/Entity;)V",
            at = @org.spongepowered.asm.mixin.injection.At("RETURN"))
    private void fabrolia$publishCameraMembership(Entity entity,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (entity instanceof net.minecraft.server.level.ServerPlayer player) {
            com.axalotl.fabrolia.common.regions.NetherPortalPlayerAccess.publishTrackedCamera(player);
        }
    }

    @org.spongepowered.asm.mixin.injection.Inject(
            method = "onTrackingEnd(Lnet/minecraft/world/entity/Entity;)V",
            at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
    private void fabrolia$retireCameraMembership(Entity entity,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (entity instanceof net.minecraft.server.level.ServerPlayer player) {
            com.axalotl.fabrolia.common.regions.NetherPortalPlayerAccess.forgetCamera(player);
        }
    }
}