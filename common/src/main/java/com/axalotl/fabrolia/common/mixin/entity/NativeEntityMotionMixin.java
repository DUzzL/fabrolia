package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.regions.NativeEntityDependencies;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Identity argument guards avoid callback objects and boxed movement coordinates. */
@Mixin(Entity.class)
public abstract class NativeEntityMotionMixin {
    @ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Vec3 fabrolia$guardMove(Vec3 movement, @Local(argsOnly = true) MoverType type) {
        NativeEntityDependencies.requireLocalEntityMove((Entity) (Object) this, type, movement);
        return movement;
    }

    @ModifyVariable(method = "setPosRaw", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private double fabrolia$guardPosition(double x,
            @Local(argsOnly = true, ordinal = 1) double y,
            @Local(argsOnly = true, ordinal = 2) double z) {
        NativeEntityDependencies.requireLocalEntityPosition((Entity) (Object) this, x, y, z);
        return x;
    }

    @ModifyVariable(method = "setBoundingBox", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private AABB fabrolia$guardBounds(AABB box) {
        NativeEntityDependencies.requireLocalEntityBox((Entity) (Object) this, box);
        return box;
    }
}
