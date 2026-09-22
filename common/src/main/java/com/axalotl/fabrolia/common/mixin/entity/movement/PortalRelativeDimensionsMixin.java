package com.axalotl.fabrolia.common.mixin.entity.movement;

import com.axalotl.fabrolia.common.regions.NetherPortalPreparation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Entity.class)
public abstract class PortalRelativeDimensionsMixin {
    @WrapOperation(method = "getRelativePortalPosition", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getDimensions(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;"))
    private EntityDimensions fabrolia$acceptedPortalDimensions(Entity entity, Pose pose,
                                                               Operation<EntityDimensions> original) {
        EntityDimensions accepted = NetherPortalPreparation.portalCalculationDimensions(entity);
        return accepted != null ? accepted : original.call(entity, pose);
    }
}
