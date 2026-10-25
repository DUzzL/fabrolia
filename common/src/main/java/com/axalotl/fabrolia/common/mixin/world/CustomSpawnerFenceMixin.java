package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.CustomSpawnerFence;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.CustomSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Vanilla custom spawners do their world work under the global fence: see CustomSpawnerFence (audit C-02). */
@Mixin(ServerLevel.class)
public abstract class CustomSpawnerFenceMixin {

    @WrapOperation(method = "tickCustomSpawners",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/CustomSpawner;tick(Lnet/minecraft/server/level/ServerLevel;Z)V"))
    private void fabrolia$fenceSpawnerWork(CustomSpawner spawner, ServerLevel level, boolean spawnEnemies,
                                           Operation<Void> original) {
        CustomSpawnerFence.tick(level, spawner, spawnEnemies, () -> original.call(spawner, level, spawnEnemies));
    }
}
