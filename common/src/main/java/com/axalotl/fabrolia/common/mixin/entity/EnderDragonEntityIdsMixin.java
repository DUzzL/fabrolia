package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.utils.DragonEntityIds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EnderDragon.class)
public abstract class EnderDragonEntityIdsMixin {
    @Inject(method = "<init>", at = @At("TAIL"), require = 1, allow = 1)
    private void fabrolia$assignMultipartIds(EntityType<? extends EnderDragon> type,
                                            Level level, CallbackInfo ci) {
        if (level instanceof ServerLevel serverLevel) {
            DragonEntityIds.assignUnpublished(serverLevel, (EnderDragon) (Object) this);
        }
    }
}
