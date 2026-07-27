package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.parallelised.utils.PortalTeleportationManager;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartBehavior;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * task #26 (2026-07-24) — THE portal-minecart arrival-Y-truncation FIX.
 *
 * <p>Root cause, localized by the operator's {@code yChain} capture: on a failing
 * crossing the source cart's Y drops from the submit snapshot ({@code 131.0625})
 * to the block boundary ({@code 131.0}) between {@code snapPos} and
 * {@code preDestCalc} — i.e. the main-thread deferred drain's
 * {@code entity.setPos(snapPos)} is overwritten by a CONCURRENT worker write. The
 * writer is the SOURCE cart's own tick: {@code handlePortal} submits the transfer
 * mid-tick and returns, then {@code AbstractMinecart.tick()} continues into
 * {@code this.behavior.tick()}, which moves the cart (off-rail inside the portal
 * it is falling, gravity + {@code Entity.move}) — dropping its Y a fraction. That
 * mutated live pose then races the drain and the destination placement reads it,
 * so the arrival lands at the portal floor instead of rail height and the cart
 * traps.
 *
 * <p>Vanilla does NOT have this: {@code handlePortal} teleports and REMOVES the
 * source entity, so its subsequent {@code behavior.tick()} runs on a dead entity
 * with no effect. The async deferral keeps the source alive (frozen for FUTURE
 * ticks via portalPending) but the CURRENT tick's {@code behavior.tick()} still
 * runs — the exact "freezing future ticks is not sufficient" gap.
 *
 * <p>xfer12 CORRECTION — what this mixin is and is not. Suppressing
 * {@code behavior.tick()} does NOT close the race, and xfer11 (which relied on
 * it alone) was incomplete in two ways:
 *
 * <ul>
 *   <li>{@code behavior.tick()} is only the FIRST post-submission mutator.
 *       {@code AbstractMinecart.tick} continues into
 *       {@code updateFluidInteraction()} (fluid push writes deltaMovement) and
 *       the lava branch. Enumerating writers can never be proven exhaustive.</li>
 *   <li>The pending flag it tested is cleared by MAIN, so main could resolve the
 *       whole transfer before the worker even reached this call — see the guard
 *       comment below.</li>
 * </ul>
 *
 * <p>The load-bearing fix is now the transfer-visibility barrier in
 * {@link com.axalotl.fabrolia.common.parallelised.utils.PortalTeleportationManager}:
 * main cannot observe the transfer until the source worker's entity tick has
 * fully returned, so NO writer can race the destination calc regardless of
 * whether we named it. This mixin remains for VANILLA FIDELITY — vanilla's
 * source entity is removed by {@code handlePortal} and moves no further, so
 * neither should ours — and as defence in depth. It is no longer what makes the
 * transfer correct. Scoped to the pending window only; normal ticks untouched.
 */
@Mixin(AbstractMinecart.class)
public abstract class AbstractMinecartPortalFreezeMixin {

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
        if (PortalTeleportationManager.hasDepartedThisTick(this)) {
            return;
        }
        if (((Object) this) instanceof ParallelProcessor.TickGuard tg && tg.fabrolia$isPortalPending()) {
            return;
        }
        original.call(behavior);
    }
}
