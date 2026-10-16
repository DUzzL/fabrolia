package raidprobe.mixin;

import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import raidprobe.RaidProbe;

/**
 * Villager.releasePoi: which memory is dropped, by whom, and with which clocks.
 * SetWalkTargetFromBlockMemory releases when getGameTime() - CANT_REACH_WALK_TARGET_SINCE
 * exceeds its limit, so both the stamp and both clocks matter.
 */
@Mixin(Villager.class)
public class VillagerReleaseProbeMixin {
    @Inject(method = "releasePoi", at = @At("HEAD"))
    private void probe$releasePoi(MemoryModuleType<?> memoryType, CallbackInfo ci) {
        Villager self = (Villager) (Object) this;
        if (!RaidProbe.near(self.blockPosition())) return;
        RaidProbe.log("VILLAGER_RELEASE " + memoryType + " villager@" + self.blockPosition().toShortString()
                + " brain=" + Integer.toHexString(System.identityHashCode(self.getBrain()))
                + " memory=" + self.getBrain().getMemoryInternal(memoryType).map(Object::toString).orElse("-")
                + " cantReachSince=" + self.getBrain().getMemoryInternal(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE)
                        .map(Object::toString).orElse("-")
                + " ctxTime=" + self.level().getGameTime() + " rawTime=" + RaidProbe.rawTime()
                + " activity=" + self.getBrain().getActiveNonCoreActivity().map(Object::toString).orElse("-")
                + " by " + RaidProbe.caller(2, 6));
    }
}
