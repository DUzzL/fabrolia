package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.NetherPortalPreparation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.NetherPortalBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(NetherPortalBlock.class)
public abstract class NetherPortalDimensionsMixin {
    @WrapOperation(method = "createDimensionTransition", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getDimensions(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;"))
    private static EntityDimensions fabrolia$acceptedPortalDimensions(Entity entity, Pose pose,
                                                                      Operation<EntityDimensions> original) {
        EntityDimensions accepted = NetherPortalPreparation.portalCalculationDimensions(entity);
        return accepted != null ? accepted : original.call(entity, pose);
    }
}
