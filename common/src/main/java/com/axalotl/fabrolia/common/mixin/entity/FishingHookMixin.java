package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.parallelised.utils.PlayerDimensionTransition;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps FishingHook owner checks serializable with ServerPlayer's temporary
 * CHANGED_DIMENSION removal. Vanilla cannot tick the hook inside that removal
 * window; a region worker can, and would otherwise discard it as ownerless.
 */
@Mixin(FishingHook.class)
public abstract class FishingHookMixin {

    /**
     * EntityReference converts a temporarily removed direct owner to UUID and
     * returns null. Recover only the exact session published by the dimension
     * transition, then restore the direct reference for this same tick and for
     * remove/updateOwnerInfo cleanup.
     */
    @WrapMethod(method = "getPlayerOwner()Lnet/minecraft/world/entity/player/Player;")
    private Player fabrolia$recoverTransitioningOwner(Operation<Player> original) {
        Player vanillaOwner = original.call();
        if (vanillaOwner != null) {
            return vanillaOwner;
        }

        FishingHook self = (FishingHook) (Object) this;
        if (!(self.level() instanceof ServerLevel)) {
            return null;
        }
        ProjectileOwnerAccessor ownerAccess = (ProjectileOwnerAccessor) (Object) self;
        EntityReference<Entity> reference = ownerAccess.fabrolia$getOwnerReference();
        if (reference == null) {
            return null;
        }

        Player recovered = PlayerDimensionTransition.resolvePlayer(
                reference.getUUID(), () -> original.call());
        if (!(recovered instanceof ServerPlayer owner) || owner.fishing != self) {
            return null;
        }
        ownerAccess.fabrolia$setOwnerReference(EntityReference.<Entity>of(owner));
        return owner;
    }

    /**
     * A transition may start after getPlayerOwner returned normally. Recheck a
     * failed canInteractWithLevel in one order with marker enter/exit; every
     * rod and distance condition remains in vanilla shouldStopFishing.
     */
    @WrapOperation(method = "shouldStopFishing(Lnet/minecraft/world/entity/player/Player;)Z",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;canInteractWithLevel()Z"))
    private boolean fabrolia$recheckTransitioningOwnerInteraction(
            Player player, Operation<Boolean> original) {
        boolean vanillaInteract = original.call(player);
        FishingHook self = (FishingHook) (Object) this;
        if (!vanillaInteract && player instanceof ServerPlayer owner
                && owner.fishing == self) {
            return PlayerDimensionTransition.recheckCanInteractWithLevel(
                    owner, () -> original.call(player));
        }
        return vanillaInteract;
    }
}
