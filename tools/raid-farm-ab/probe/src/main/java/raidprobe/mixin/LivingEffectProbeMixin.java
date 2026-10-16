package raidprobe.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import raidprobe.RaidProbe;

import java.util.Collection;

@Mixin(LivingEntity.class)
public abstract class LivingEffectProbeMixin {
    @Unique
    private static boolean probe$omen(MobEffectInstance effect) {
        return effect != null && (effect.is(MobEffects.BAD_OMEN) || effect.is(MobEffects.RAID_OMEN));
    }

    @Inject(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z", at = @At("RETURN"))
    private void probe$add(MobEffectInstance effect, Entity source, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerPlayer player && probe$omen(effect)) {
            LivingEntity self = (LivingEntity) (Object) this;
            RaidProbe.log("EFFECT_ADD " + player.getScoreboardName() + " " + effect.getEffect().getRegisteredName()
                    + " amp=" + effect.getAmplifier() + " dur=" + effect.getDuration() + " changed=" + cir.getReturnValue()
                    + " now bad=" + self.hasEffect(MobEffects.BAD_OMEN) + " raid=" + self.hasEffect(MobEffects.RAID_OMEN));
        }
    }

    @Inject(method = "onEffectsRemoved", at = @At("HEAD"))
    private void probe$removed(Collection<MobEffectInstance> effects, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer player) {
            for (MobEffectInstance effect : effects) {
                if (probe$omen(effect)) {
                    RaidProbe.log("EFFECT_REMOVED " + player.getScoreboardName() + " " + effect.getEffect().getRegisteredName()
                            + " amp=" + effect.getAmplifier() + " dur=" + effect.getDuration());
                }
            }
        }
    }
}
