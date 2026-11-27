package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.TemporalControl;
import net.minecraft.server.ServerTickRateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Region-aware /tick rate (2026-07-14): vanilla sets the MAIN loop rate;
 *  every island domain receives the same target (and explicit island rate
 *  overrides are cleared — the command means "set every domain"). Lag
 *  isolation is preserved: this changes each island's deadline pacing, not
 *  a global barrier; a heavy island still slips alone. */
@Mixin(ServerTickRateManager.class)
public abstract class ServerTickRateManagerMixin implements TemporalControl.ManualTickMode {
    @Shadow public abstract boolean isSprinting();
    @Unique private volatile boolean fabrolia$manual;

    // Vanilla changes the sprint count before calling setFrozen at both ends of a
    // sprint. A manual step stays frozen even on its last live frame.
    @Inject(method = "setFrozen", at = @At("HEAD"))
    private void fabrolia$publishManualMode(boolean frozen, CallbackInfo ci) {
        fabrolia$manual = frozen || isSprinting();
    }

    @Override public boolean fabrolia$manuallyDriven() { return fabrolia$manual; }

    @Inject(method = "setTickRate(F)V", at = @At("TAIL"))
    private void fabrolia$propagateRate(float tps, CallbackInfo ci) {
        TemporalControl.setGlobalRate(tps);
    }
}
