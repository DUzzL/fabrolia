package raidprobe.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.GoToPotentialJobSite;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import raidprobe.RaidProbe;

@Mixin(GoToPotentialJobSite.class)
public class GoToPotentialJobSiteProbeMixin {
    @Inject(method = "stop(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/npc/villager/Villager;J)V", at = @At("HEAD"))
    private void probe$stop(ServerLevel level, Villager body, long timestamp, CallbackInfo ci) {
        RaidProbe.log("GOTO_JOBSITE_STOP villager@" + body.blockPosition().toShortString() + " site="
                + body.getBrain().getMemoryInternal(MemoryModuleType.POTENTIAL_JOB_SITE).map(Object::toString).orElse("-")
                + " activity=" + body.getBrain().getActiveNonCoreActivity().map(Object::toString).orElse("-")
                + " brain=" + Integer.toHexString(System.identityHashCode(body.getBrain())));
    }
}
