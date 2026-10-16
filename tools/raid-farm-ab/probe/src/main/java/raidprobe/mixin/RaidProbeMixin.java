package raidprobe.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.raid.Raid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import raidprobe.RaidProbe;

@Mixin(Raid.class)
public abstract class RaidProbeMixin {
    @Shadow private int groupsSpawned;
    @Shadow private int raidCooldownTicks;
    @Shadow private int raidOmenLevel;
    @Shadow private long ticksActive;
    @Unique private static final ThreadLocal<ServerLevel> probe$level = new ThreadLocal<>();
    @Unique private String probe$lastState = "";
    @Unique private int probe$lastLevel = -1;
    @Unique private Boolean probe$lastVillage;

    @Unique
    private String probe$id() {
        Raid raid = (Raid) (Object) this;
        return "raid=" + Integer.toHexString(System.identityHashCode(this)) + " center=" + raid.getCenter().toShortString()
                + " lvl=" + raidOmenLevel + " groups=" + groupsSpawned + " cd=" + raidCooldownTicks + " active=" + ticksActive;
    }

    @Inject(method = "<init>(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/Difficulty;)V", at = @At("RETURN"))
    private void probe$created(BlockPos center, Difficulty difficulty, CallbackInfo ci) {
        RaidProbe.log("RAID_NEW " + probe$id() + " by " + RaidProbe.caller(2, 5));
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void probe$tickHead(ServerLevel level, CallbackInfo ci) {
        probe$level.set(level);
        Raid raid = (Raid) (Object) this;
        if (!raid.isStopped() && !raid.isOver()) {
            boolean village = level.isVillage(raid.getCenter());
            if (probe$lastVillage == null || village != probe$lastVillage) {
                RaidProbe.log("RAID_VILLAGE " + village + " " + probe$id());
                probe$lastVillage = village;
            }
        }
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void probe$tickReturn(ServerLevel level, CallbackInfo ci) {
        Raid raid = (Raid) (Object) this;
        String state = raid.isStopped() ? "STOPPED" : raid.isVictory() ? "VICTORY" : raid.isLoss() ? "LOSS"
                : raid.isStarted() ? "STARTED" : "PENDING";
        if (!state.equals(probe$lastState) || raidOmenLevel != probe$lastLevel) {
            RaidProbe.log("RAID_STATE " + state + " " + probe$id());
            probe$lastState = state;
            probe$lastLevel = raidOmenLevel;
        }
        probe$level.remove();
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void probe$stop(CallbackInfo ci) {
        ServerLevel level = probe$level.get();
        Raid raid = (Raid) (Object) this;
        String village = level == null ? "?" : String.valueOf(level.isVillage(raid.getCenter()));
        RaidProbe.log("RAID_STOP village=" + village + " " + probe$id() + " by " + RaidProbe.caller(2, 4));
    }

    @Inject(method = "spawnGroup", at = @At("HEAD"))
    private void probe$wave(ServerLevel level, BlockPos pos, CallbackInfo ci) {
        RaidProbe.log("RAID_WAVE " + (groupsSpawned + 1) + " at " + pos.toShortString() + " " + probe$id());
    }

    @Inject(method = "absorbRaidOmen", at = @At("RETURN"), require = 0)
    private void probe$absorbVanilla(ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
        RaidProbe.log("RAID_ABSORB(vanilla) " + player.getScoreboardName() + " result=" + cir.getReturnValue() + " " + probe$id());
    }
}
