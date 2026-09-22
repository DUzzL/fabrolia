package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.utils.PathDoorAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.InteractWithDoor;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InteractWithDoor.class)
public class InteractWithDoorMixin {

    @Inject(
            method = "isMobComingThroughDoor",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void injectIsMobComingThroughDoor(Brain<?> otherBrain, BlockPos doorPos, CallbackInfoReturnable<Boolean> cir) {
        Path path = otherBrain.getMemory(MemoryModuleType.PATH).orElse(null);
        cir.setReturnValue(path != null
                && ((PathDoorAccess) (Object) path).fabrolia$isComingThroughDoor(doorPos));
    }
}