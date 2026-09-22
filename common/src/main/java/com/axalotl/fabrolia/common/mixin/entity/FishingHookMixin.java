package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.parallelised.utils.PlayerDimensionTransition;
import com.axalotl.fabrolia.common.utils.FabroliaDebug;
import com.axalotl.fabrolia.common.regions.FishingHookRemovalTrace;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps FishingHook owner checks serializable with ServerPlayer's temporary
 * CHANGED_DIMENSION removal. Vanilla cannot tick the hook inside that removal
 * window; a region worker can, and would otherwise discard it as ownerless.
 */
@Mixin(FishingHook.class)
public abstract class FishingHookMixin implements FishingHookRemovalTrace {

    @Unique
    private static final org.slf4j.Logger FABROLIA$FISHING_LOG =
            org.slf4j.LoggerFactory.getLogger("Fabrolia-FishingStasis");

    /**
     * Diagnostic state is written immediately before vanilla's discard call and
     * consumed synchronously by remove(). It never performs owner recovery from
     * remove HEAD: getPlayerOwner() can rewrite Projectile.owner and would make
     * the probe itself behavioral.
     */
    @Unique private String fabrolia$pendingRemovalCause;
    @Unique private String fabrolia$shouldStopCaller = "TICK";
    @Unique private String fabrolia$lastOwnerSnapshot = "owner-not-observed";
    @Unique private boolean fabrolia$lastRawCanInteract;
    @Unique private boolean fabrolia$lastCanInteract;
    @Unique private boolean fabrolia$lastActiveSession;
    @Unique private boolean fabrolia$lastMainRod;
    @Unique private boolean fabrolia$lastOffRod;
    @Unique private double fabrolia$lastDistanceSqr = Double.NaN;

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

    @WrapOperation(method = "tick()V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/projectile/FishingHook;discard()V",
                    ordinal = 0))
    private void fabrolia$markNullOwnerDiscard(
            FishingHook hook, Operation<Void> original) {
        if (FabroliaDebug.FISHING_TRACE
                && hook.level() instanceof ServerLevel) {
            this.fabrolia$pendingRemovalCause = "TICK_NULL_OWNER";
        }
        original.call(hook);
    }

    @WrapOperation(method = "tick()V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/projectile/FishingHook;discard()V",
                    ordinal = 1))
    private void fabrolia$markGroundTimeoutDiscard(
            FishingHook hook, Operation<Void> original) {
        if (FabroliaDebug.FISHING_TRACE
                && hook.level() instanceof ServerLevel) {
            this.fabrolia$pendingRemovalCause = "TICK_GROUND_TIMEOUT";
        }
        original.call(hook);
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
        boolean effectiveInteract = vanillaInteract;
        FishingHook self = (FishingHook) (Object) this;
        if (!vanillaInteract && player instanceof ServerPlayer owner
                && owner.fishing == self) {
            effectiveInteract = PlayerDimensionTransition.recheckCanInteractWithLevel(
                    owner, () -> original.call(player));
        }

        if (FabroliaDebug.FISHING_TRACE
                && self.level() instanceof ServerLevel) {
            this.fabrolia$lastRawCanInteract = vanillaInteract;
            this.fabrolia$lastCanInteract = effectiveInteract;
            this.fabrolia$lastMainRod =
                    player.getMainHandItem().is(Items.FISHING_ROD);
            this.fabrolia$lastOffRod =
                    player.getOffhandItem().is(Items.FISHING_ROD);
            this.fabrolia$lastDistanceSqr = Double.NaN;
            this.fabrolia$lastActiveSession = player.fishing == self;
            this.fabrolia$lastOwnerSnapshot =
                    player.getUUID() + "#" + System.identityHashCode(player)
                            + " ownerDim=" + player.level().dimension().identifier()
                            + " hookDim=" + self.level().dimension().identifier()
                            + " activeSession=" + (player.fishing == self)
                            + " transitioning="
                            + (player instanceof ServerPlayer owner
                            && PlayerDimensionTransition.contains(owner));
        }
        return effectiveInteract;
    }

    @WrapOperation(method = "shouldStopFishing(Lnet/minecraft/world/entity/player/Player;)Z",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/projectile/FishingHook;"
                            + "distanceToSqr(Lnet/minecraft/world/entity/Entity;)D"))
    private double fabrolia$captureShouldStopDistance(
            FishingHook hook, Entity owner, Operation<Double> original) {
        double distanceSqr = original.call(hook, owner);
        if (FabroliaDebug.FISHING_TRACE
                && hook.level() instanceof ServerLevel) {
            this.fabrolia$lastDistanceSqr = distanceSqr;
        }
        return distanceSqr;
    }

    @Inject(method = "shouldStopFishing(Lnet/minecraft/world/entity/player/Player;)Z",
            at = @At("RETURN"))
    private void fabrolia$traceShouldStopDecision(
            Player player, CallbackInfoReturnable<Boolean> cir) {
        if (!FabroliaDebug.FISHING_TRACE) {
            return;
        }
        FishingHook self = (FishingHook) (Object) this;
        boolean result = cir.getReturnValueZ();
        boolean remote = player.level() != self.level();
        if (!result && !remote) {
            return;
        }
        String stage;
        if (!this.fabrolia$lastCanInteract) {
            stage = "INTERACT_FAIL";
        } else if (!this.fabrolia$lastMainRod && !this.fabrolia$lastOffRod) {
            stage = "ROD_FAIL";
        } else if (!Double.isNaN(this.fabrolia$lastDistanceSqr)
                && this.fabrolia$lastDistanceSqr > 1024.0D) {
            stage = "DISTANCE_FAIL";
        } else {
            stage = "PASS";
        }
        FABROLIA$FISHING_LOG.warn(
                "[fishing-stasis] SHOULD-STOP hook={} caller={} result={} stage={} "
                        + "owner={}#{} ownerRemoval={} transitioning={} "
                        + "rawCanInteract={} effectiveCanInteract={} mainRod={} "
                        + "offRod={} mainItem={} offItem={} distanceSqr={} "
                        + "hookDim={} ownerDim={} activeSessionAtCheck={} "
                        + "asyncAny={} thread={}",
                self.getUUID(), this.fabrolia$shouldStopCaller, result, stage,
                player.getUUID(), System.identityHashCode(player),
                player.getRemovalReason(),
                player instanceof ServerPlayer owner
                        && PlayerDimensionTransition.contains(owner),
                this.fabrolia$lastRawCanInteract, this.fabrolia$lastCanInteract,
                this.fabrolia$lastMainRod, this.fabrolia$lastOffRod,
                player.getMainHandItem(), player.getOffhandItem(),
                this.fabrolia$lastDistanceSqr,
                self.level().dimension().identifier(),
                player.level().dimension().identifier(),
                this.fabrolia$lastActiveSession,
                RegionScheduler.anyAsyncActive(), Thread.currentThread().getName());
    }

    @WrapOperation(method = "shouldStopFishing(Lnet/minecraft/world/entity/player/Player;)Z",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/projectile/FishingHook;discard()V"))
    private void fabrolia$markShouldStopDiscard(
            FishingHook hook, Operation<Void> original) {
        if (FabroliaDebug.FISHING_TRACE
                && hook.level() instanceof ServerLevel) {
            this.fabrolia$pendingRemovalCause =
                    "SHOULD_STOP caller=" + this.fabrolia$shouldStopCaller
                            + " canInteract=" + this.fabrolia$lastCanInteract
                            + " mainRod=" + this.fabrolia$lastMainRod
                            + " offRod=" + this.fabrolia$lastOffRod
                            + " distanceSqr=" + this.fabrolia$lastDistanceSqr
                            + " " + this.fabrolia$lastOwnerSnapshot;
        }
        original.call(hook);
    }

    @WrapOperation(method = "retrieve(Lnet/minecraft/world/item/ItemStack;)I",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/projectile/FishingHook;"
                            + "shouldStopFishing(Lnet/minecraft/world/entity/player/Player;)Z"))
    private boolean fabrolia$markRetrieveShouldStopCaller(
            FishingHook hook, Player player, Operation<Boolean> original) {
        if (!FabroliaDebug.FISHING_TRACE) {
            return original.call(hook, player);
        }
        String previous = this.fabrolia$shouldStopCaller;
        this.fabrolia$shouldStopCaller = "RETRIEVE";
        try {
            return original.call(hook, player);
        } finally {
            this.fabrolia$shouldStopCaller = previous;
        }
    }

    @WrapOperation(method = "retrieve(Lnet/minecraft/world/item/ItemStack;)I",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/projectile/FishingHook;discard()V"))
    private void fabrolia$markRetrieveDiscard(
            FishingHook hook, Operation<Void> original) {
        if (FabroliaDebug.FISHING_TRACE
                && hook.level() instanceof ServerLevel) {
            this.fabrolia$pendingRemovalCause = "RETRIEVE";
        }
        original.call(hook);
    }

    @Override
    public void fabrolia$traceRemoval(Entity.RemovalReason reason) {
        FishingHook self = (FishingHook) (Object) this;
        try {
            if (!(self.level() instanceof ServerLevel)
                    || !FabroliaDebug.FISHING_TRACE) {
                return;
            }
            EntityReference<Entity> reference =
                    ((ProjectileOwnerAccessor) (Object) self).fabrolia$getOwnerReference();
            String cause = this.fabrolia$pendingRemovalCause == null
                    ? "EXTERNAL_SET_REMOVED"
                    : this.fabrolia$pendingRemovalCause;
            this.fabrolia$pendingRemovalCause = null;
            FABROLIA$FISHING_LOG.warn(
                    "[fishing-stasis] HOOK-REMOVE hook={} reason={} cause={} dim={} "
                            + "chunk={} ownerRef={} asyncAny={} thread={}",
                    self.getUUID(), reason, cause,
                    self.level().dimension().identifier(), self.chunkPosition(),
                    reference == null ? "-" : reference.getUUID(),
                    RegionScheduler.anyAsyncActive(),
                    Thread.currentThread().getName());
        } catch (Throwable diagnosticFailure) {
            FABROLIA$FISHING_LOG.warn(
                    "[fishing-stasis] HOOK-REMOVE trace failed hook={} reason={}",
                    self.getUUID(), reason, diagnosticFailure);
        }
    }
}
