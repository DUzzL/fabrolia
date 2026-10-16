package raidprobe.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import raidprobe.RaidProbe;

@Mixin(targets = "net.minecraft.world.effect.BadOmenMobEffect")
public class BadOmenProbeMixin {
    @Inject(method = "applyEffectTick", at = @At("RETURN"))
    private void probe$badOmen(ServerLevel level, LivingEntity mob, int amplifier, CallbackInfoReturnable<Boolean> cir) {
        if (mob instanceof ServerPlayer player && !cir.getReturnValueZ()) {
            RaidProbe.log("BAD_OMEN_CONVERT " + player.getScoreboardName() + " amp=" + amplifier + " at "
                    + player.blockPosition().toShortString());
        }
    }
}
