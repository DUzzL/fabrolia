package com.axalotl.fabrolia.common.mixin.carpet;

import com.axalotl.fabrolia.common.regions.RegionMainBlockingWaitGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fail-stop guard for Carpet task waits entered from a live region step.
 *
 * <p>Carpet {@code 26.2+v260616}'s {@code ThreadValue.join()} blocks on its opaque
 * {@code CompletableFuture}, while {@code next()} is the blocking implementation behind
 * {@code task_await} and the iterator bridge.  Neither wait publishes a receipt which Main
 * can safely drain while an island claim is held.  Rejecting at method HEAD therefore
 * happens before Carpet observes or waits on either future/condition; a permitted call
 * continues through Carpet's original method exactly once.
 */
@Pseudo
@Mixin(targets = "carpet.script.value.ThreadValue", remap = false)
public abstract class CarpetThreadValueBlockingWaitMixin {

    @Inject(
            method = "join()Lcarpet/script/value/Value;",
            at = @At("HEAD"),
            remap = false,
            require = 1,
            allow = 1)
    private void fabrolia$guardJoin(CallbackInfoReturnable<Object> cir) {
        RegionMainBlockingWaitGuard.failIfCurrentThreadParticipates(
                "Carpet ThreadValue.join()");
    }

    @Inject(
            method = "next()Lcarpet/script/value/Value;",
            at = @At("HEAD"),
            remap = false,
            require = 1,
            allow = 1)
    private void fabrolia$guardAwait(CallbackInfoReturnable<Object> cir) {
        RegionMainBlockingWaitGuard.failIfCurrentThreadParticipates(
                "Carpet ThreadValue.next()/task_await");
    }
}
