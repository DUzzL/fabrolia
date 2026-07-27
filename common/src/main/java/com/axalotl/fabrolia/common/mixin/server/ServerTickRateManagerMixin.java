package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.TemporalControl;
import net.minecraft.server.ServerTickRateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Region-aware /tick rate (2026-07-14): vanilla sets the MAIN loop rate;
 *  every island domain receives the same target (and explicit island rate
 *  overrides are cleared — the command means "set every domain"). Lag
 *  isolation is preserved: this changes each island's deadline pacing, not
 *  a global barrier; a heavy island still slips alone. */
@Mixin(ServerTickRateManager.class)
public abstract class ServerTickRateManagerMixin {

    @Inject(method = "setTickRate(F)V", at = @At("TAIL"))
    private void fabrolia$propagateRate(float tps, CallbackInfo ci) {
        TemporalControl.setGlobalRate(tps);
    }
}
