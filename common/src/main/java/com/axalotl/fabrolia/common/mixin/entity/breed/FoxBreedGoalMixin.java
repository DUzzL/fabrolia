package com.axalotl.fabrolia.common.mixin.entity.breed;

import net.minecraft.world.entity.ai.goal.BreedGoal;
import net.minecraft.world.entity.animal.fox.Fox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(Fox.FoxBreedGoal.class)
public abstract class FoxBreedGoalMixin extends BreedGoal {

    public FoxBreedGoalMixin(Fox fox, double speedModifier) {
        super(fox, speedModifier);
    }

    /**
     * Pair key → game time of its breed. The guard only has to outlive the tick
     * in which both foxes of a pair can reach breed() (the winner resets both
     * loves, so the pair's goals stop at their next goal tick); older entries
     * are dropped on the next breed anywhere instead of living forever.
     */
    @Unique
    private static final Map<String, Long> breedingPairs = new ConcurrentHashMap<>();

    @Unique
    private static final long GUARD_TICKS = 20L;

    @Inject(method = "start", at = @At("HEAD"))
    private void resetBreedingFlag(CallbackInfo ci) {
        breedingPairs.remove(getPairKey());
    }

    @Inject(method = "breed", at = @At("HEAD"), cancellable = true)
    private void preventDoubleBreed(CallbackInfo ci) {
        long now = this.level.getGameTime();
        breedingPairs.values().removeIf(at -> now - at > GUARD_TICKS);
        if (breedingPairs.putIfAbsent(getPairKey(), now) != null) {
            ci.cancel();
        }
    }

    @Unique
    private String getPairKey() {
        UUID id1 = this.animal.getUUID();
        UUID id2 = null;
        if (this.partner != null) {
            id2 = this.partner.getUUID();
        }
        String s1 = id1.toString();
        String s2 = null;
        if (id2 != null) {
            s2 = id2.toString();
        }
        if (s2 != null) {
            return s1.compareTo(s2) <= 0 ? s1 + "|" + s2 : s2 + "|" + s1;
        }
        return s1;
    }
}
