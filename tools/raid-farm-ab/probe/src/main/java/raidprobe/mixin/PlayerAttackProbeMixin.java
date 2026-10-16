package raidprobe.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import raidprobe.RaidProbe;

/** Records attacks that reach vanilla, including the conditions needed for a sweep. */
@Mixin(Player.class)
public class PlayerAttackProbeMixin {
    @Inject(method = "attack", at = @At("HEAD"))
    private void probe$attack(Entity target, CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (!RaidProbe.near(self.blockPosition())) return;
        RaidProbe.log("PLAYER_ATTACK player=" + self.getScoreboardName()
                + " target=" + target.getType() + " id=" + target.getId()
                + " pos=" + target.position() + " item=" + self.getMainHandItem()
                + " strength=" + self.getAttackStrengthScale(0.5F)
                + " onGround=" + self.onGround() + " sprint=" + self.isSprinting()
                + " movement=" + self.getDeltaMovement());
    }

    @Inject(method = "doSweepAttack", at = @At("HEAD"))
    private void probe$sweep(Entity target, float damage, DamageSource source, float strength, CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (RaidProbe.near(self.blockPosition())) {
            RaidProbe.log("PLAYER_SWEEP player=" + self.getScoreboardName()
                    + " target=" + target.getId() + " damage=" + damage + " strength=" + strength);
        }
    }
}
