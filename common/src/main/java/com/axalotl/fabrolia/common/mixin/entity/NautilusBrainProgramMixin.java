package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.regions.NativeNautilusProgram;
import net.minecraft.world.entity.ai.Brain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Brain.class)
public abstract class NautilusBrainProgramMixin implements NativeNautilusProgram.BrainBinding {
    @Unique private NativeNautilusProgram.Snapshot fabrolia$nautilusProgram;
    @Unique private long fabrolia$nautilusProgramRevision;

    @Override
    public NativeNautilusProgram.Snapshot fabrolia$nautilusProgram() {
        return fabrolia$nautilusProgram;
    }

    @Override
    public long fabrolia$nautilusProgramRevision() {
        return fabrolia$nautilusProgramRevision;
    }

    @Override
    public void fabrolia$bindNautilusProgram(NativeNautilusProgram.Snapshot snapshot) {
        if (snapshot == null) fabrolia$nautilusProgramRevision++;
        fabrolia$nautilusProgram = snapshot;
    }

    // Publication must already be owner-routed/exclusive. Invalidate BEFORE the
    // first program write, not at RETURN like the separate flat execution cache.
    @Inject(method = {"addActivity", "removeAllBehaviors", "setCoreActivities", "setDefaultActivity", "setSchedule"},
            at = @At("HEAD"))
    private void fabrolia$invalidateNautilusProgram(CallbackInfo ci) {
        fabrolia$bindNautilusProgram(null);
    }
}
