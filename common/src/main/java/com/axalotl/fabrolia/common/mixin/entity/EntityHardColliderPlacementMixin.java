package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.parallelised.utils.HardColliderIndex;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Keeps {@link HardColliderIndex} exact: {@code setBoundingBox} is the only
 * writer of {@code Entity.bb} after construction. Non-indexed entities pay one
 * field read; {@code @ModifyVariable} avoids a per-call CallbackInfo.
 */
@Mixin(Entity.class)
public abstract class EntityHardColliderPlacementMixin implements HardColliderIndex.Member {

    @Unique
    private volatile HardColliderIndex.Placement fabrolia$hardColliderPlacement;

    @Shadow
    public abstract AABB getBoundingBox();

    @Override
    public AABB fabrolia$hardColliderBox() {
        return this.getBoundingBox();
    }

    @Override
    public HardColliderIndex.Placement fabrolia$hardColliderPlacement() {
        return this.fabrolia$hardColliderPlacement;
    }

    @Override
    public void fabrolia$setHardColliderPlacement(HardColliderIndex.Placement placement) {
        this.fabrolia$hardColliderPlacement = placement;
    }

    @ModifyVariable(method = "setBoundingBox", at = @At("HEAD"), argsOnly = true, require = 1, allow = 1)
    private AABB fabrolia$refileHardCollider(AABB next) {
        HardColliderIndex.Placement placement = this.fabrolia$hardColliderPlacement;
        if (placement != null) {
            HardColliderIndex.onBoundingBox(this, placement, next);
        }
        return next;
    }
}
