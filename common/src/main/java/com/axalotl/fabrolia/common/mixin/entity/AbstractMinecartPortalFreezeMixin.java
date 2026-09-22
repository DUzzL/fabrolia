package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.regions.NetherPortalTransfers;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartBehavior;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps source motion and collision participation consistent with vanilla's
 * immediate departure while physical Main owns deferred portal resolution.
 *
 * <p>The source-claim visibility barrier closes in-claim writers before the
 * resolver reads its snapshot. Pending-source and rider-tree exclusions keep
 * later claims from treating the retained source as a physical obstacle.
 * Global save/removal visibility and exact resolver authority stay unchanged.
 */
@Mixin(AbstractMinecart.class)
public abstract class AbstractMinecartPortalFreezeMixin {

    /**
     * The submitted source stays indexed until Main resolves it. Both its box
     * and its independently indexed passengers must be absent from a later
     * claimant's cart collisions, without changing save/removal visibility.
     */
    @WrapMethod(method = "canCollideWith")
    private boolean fabrolia$skipPortalReservedCollision(Entity other, Operation<Boolean> original) {
        Entity self = (Entity) (Object) this;
        if (NetherPortalTransfers.isSourceMutationBlocked(self)
                || NetherPortalTransfers.isSourceMutationBlocked(other)
                || NetherPortalTransfers.isSourceMutationBlocked(self.getRootVehicle())
                || NetherPortalTransfers.isSourceMutationBlocked(other.getRootVehicle())) {
            return false;
        }
        return original.call(other);
    }

    @WrapMethod(method = "isPushable")
    private boolean fabrolia$notPushableWhilePortalReserved(Operation<Boolean> original) {
        Entity self = (Entity) (Object) this;
        return !NetherPortalTransfers.isSourceMutationBlocked(self)
                && !NetherPortalTransfers.isSourceMutationBlocked(self.getRootVehicle())
                && original.call();
    }

    // AbstractMinecart overrides push without calling Entity.push: guarding only
    // the superclass leaves both carts' momentum exposed to this implementation.
    @WrapMethod(method = "push(Lnet/minecraft/world/entity/Entity;)V")
    private void fabrolia$skipPortalReservedCartPush(Entity other, Operation<Void> original) {
        Entity self = (Entity) (Object) this;
        if (NetherPortalTransfers.isSourceMutationBlocked(self)
                || NetherPortalTransfers.isSourceMutationBlocked(other)
                || NetherPortalTransfers.isSourceMutationBlocked(self.getRootVehicle())
                || NetherPortalTransfers.isSourceMutationBlocked(other.getRootVehicle())) {
            return;
        }
        original.call(other);
    }

    @WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/vehicle/minecart/MinecartBehavior;tick()V"))
    private void fabrolia$skipMoveWhilePortalPending(MinecartBehavior behavior, Operation<Void> original) {
        // Two independent reasons to be causally gone, and BOTH are needed:
        //
        //  (a) hasDepartedThisTick — this cart submitted a transfer earlier in
        //      THIS tick frame, on THIS thread. Written and read by the source
        //      worker alone.
        //  (b) portalPending — a transfer submitted on an EARLIER tick is still
        //      queued (belt-and-braces; tickOne already skips those ticks).
        //
        // (a) is not redundant. portalPending is cleared by MAIN in the drain's
        // finally, and main may resolve the whole transfer before this worker
        // reaches this very call — under xfer11, which tested (b) alone, the
        // guard would then read false and tick the behavior of a cart main had
        // already teleported out of this level. The thread-local marker cannot
        // be un-set underneath the tick that is still running.
        if (NetherPortalTransfers.hasDepartedThisTick(this)) {
            return;
        }
        if (((Object) this) instanceof ParallelProcessor.TickGuard tg && tg.fabrolia$isPortalPending()) {
            return;
        }
        original.call(behavior);
    }
}
