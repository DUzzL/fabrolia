package raidprobe.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import raidprobe.RaidProbe;

@Mixin(targets = "net.minecraft.world.effect.RaidOmenMobEffect")
public class RaidOmenProbeMixin {
    @Inject(method = "applyEffectTick", at = @At("HEAD"))
    private void probe$raidOmen(ServerLevel level, LivingEntity mob, int amplifier, CallbackInfoReturnable<Boolean> cir) {
        if (mob instanceof ServerPlayer player) {
            BlockPos omen = player.getRaidOmenPosition();
            RaidProbe.log("RAID_OMEN_EXPIRE " + player.getScoreboardName() + " amp=" + amplifier + " omenPos="
                    + (omen == null ? "null" : omen.toShortString())
                    + " village=" + (omen == null ? "-" : String.valueOf(level.isVillage(omen))));
        }
    }
}
