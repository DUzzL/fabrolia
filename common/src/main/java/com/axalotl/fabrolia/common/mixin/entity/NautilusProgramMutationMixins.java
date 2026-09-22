package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.regions.NativeNautilusProgram;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.ShufflingList;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Publication still requires genuine owner/exclusive authority, never these stamps alone. */
public final class NautilusProgramMutationMixins {
    private NautilusProgramMutationMixins() {}

    @Mixin(ShufflingList.class)
    public abstract static class GateChildren implements NativeNautilusProgram.GateBinding {
        @Unique private Brain<?> fabrolia$nautilusGateBrain;

        @Override
        public void fabrolia$bindNautilusGate(Brain<?> brain) {
            fabrolia$nautilusGateBrain = brain;
        }

        @Inject(method = {"add", "shuffle"}, at = @At("HEAD"))
        private void fabrolia$invalidateBeforeChildMutation(CallbackInfoReturnable<?> cir) {
            if (fabrolia$nautilusGateBrain != null) {
                ((NativeNautilusProgram.BrainBinding) fabrolia$nautilusGateBrain).fabrolia$bindNautilusProgram(null);
                fabrolia$nautilusGateBrain = null;
            }
        }
    }

    @Mixin(TargetingConditions.class)
    public abstract static class ChargeConditions {
        @Inject(method = {"range", "ignoreLineOfSight", "ignoreInvisibilityTesting", "selector"}, at = @At("HEAD"))
        private void fabrolia$invalidateBeforeConditionMutation(CallbackInfoReturnable<TargetingConditions> cir) {
            NativeNautilusProgram.beforeTargetingMutation((TargetingConditions) (Object) this);
        }
    }
}
