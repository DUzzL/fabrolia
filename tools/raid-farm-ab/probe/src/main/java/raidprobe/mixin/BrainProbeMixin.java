package raidprobe.mixin;

import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.schedule.Activity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import raidprobe.RaidProbe;

import java.util.Optional;

@Mixin(Brain.class)
public abstract class BrainProbeMixin {
    @Inject(method = "setMemory(Lnet/minecraft/world/entity/ai/memory/MemoryModuleType;Ljava/util/Optional;)V", at = @At("HEAD"))
    private void probe$memory(MemoryModuleType<?> type, Optional<?> value, CallbackInfo ci) {
        if (type == MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE) {
            // A game-time stamp: log it with both clocks (context = what getGameTime() returns here).
            Brain<?> self = (Brain<?>) (Object) this;
            Optional<?> old = self.getMemoryInternal(type);
            if (!old.equals(value) && RaidProbe.server != null) {
                RaidProbe.log("CANT_REACH brain=" + Integer.toHexString(System.identityHashCode(this))
                        + " " + old.map(Object::toString).orElse("-") + " -> " + value.map(Object::toString).orElse("-")
                        + " ctxTime=" + RaidProbe.server.overworld().getGameTime() + " rawTime=" + RaidProbe.rawTime());
            }
            return;
        }
        if (type == MemoryModuleType.JOB_SITE || type == MemoryModuleType.POTENTIAL_JOB_SITE
                || type == MemoryModuleType.HOME || type == MemoryModuleType.MEETING_POINT) {
            Brain<?> self = (Brain<?>) (Object) this;
            Optional<?> old = self.getMemoryInternal(type);
            if (!old.equals(value)) {
                RaidProbe.log("BRAIN_MEMORY brain=" + Integer.toHexString(System.identityHashCode(this)) + " " + type
                        + " " + old.map(Object::toString).orElse("-") + " -> " + value.map(Object::toString).orElse("-"));
            }
        }
    }

    @Inject(method = "setActiveActivity", at = @At("HEAD"))
    private void probe$activity(Activity activity, CallbackInfo ci) {
        Brain<?> self = (Brain<?>) (Object) this;
        Optional<Activity> current = self.getActiveNonCoreActivity();
        if (current.isEmpty() || current.get() != activity) {
            RaidProbe.log("BRAIN_ACTIVITY brain=" + Integer.toHexString(System.identityHashCode(this)) + " "
                    + current.map(Activity::toString).orElse("-") + " -> " + activity);
        }
    }
}
