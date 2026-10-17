package raidprobe.mixin;

import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.schedule.Activity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import raidprobe.RaidProbe;

import java.util.Optional;

@Mixin(Brain.class)
public abstract class BrainProbeMixin {
    @Unique private Villager probe$owner;
    @Unique private long probe$nextSample = Long.MIN_VALUE;

    @Inject(method = "tick", at = @At("HEAD"))
    private void probe$tick(ServerLevel level, LivingEntity body, CallbackInfo ci) {
        if (!(body instanceof Villager villager) || !RaidProbe.near(body.blockPosition())) return;
        probe$owner = villager;
        long time = level.getGameTime();
        if (time < probe$nextSample) return;
        probe$nextSample = time + 20;
        Brain<?> self = (Brain<?>) (Object) this;
        var raid = level.getRaidAt(body.blockPosition());
        RaidProbe.log("VILLAGER_AI uuid=" + body.getUUID() + " pos=" + body.blockPosition().toShortString()
                + " brain=" + Integer.toHexString(System.identityHashCode(this))
                + " ctxTime=" + time + " rawTime=" + RaidProbe.rawTime()
                + " cant=" + self.getMemoryInternal(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE)
                + " activity=" + self.getActiveNonCoreActivity()
                + " raid=" + (raid == null ? "-" : Integer.toHexString(System.identityHashCode(raid)))
                + " raidOver=" + (raid != null && raid.isOver())
                + " job=" + self.getMemoryInternal(MemoryModuleType.JOB_SITE)
                + " potential=" + self.getMemoryInternal(MemoryModuleType.POTENTIAL_JOB_SITE)
                + " meeting=" + self.getMemoryInternal(MemoryModuleType.MEETING_POINT)
                + " path=" + self.getMemoryInternal(MemoryModuleType.PATH));
    }

    @Inject(method = "setMemoryInternal(Lnet/minecraft/world/entity/ai/memory/MemoryModuleType;Ljava/lang/Object;)V", at = @At("HEAD"))
    private void probe$set(MemoryModuleType<?> type, Object value, CallbackInfo ci) {
        probe$memory(type, Optional.ofNullable(value));
    }

    @Inject(method = "setMemoryInternal(Lnet/minecraft/world/entity/ai/memory/MemoryModuleType;Ljava/lang/Object;J)V", at = @At("HEAD"))
    private void probe$setExpiring(MemoryModuleType<?> type, Object value, long ttl, CallbackInfo ci) {
        probe$memory(type, Optional.ofNullable(value));
    }

    @Inject(method = "eraseMemory", at = @At("HEAD"))
    private void probe$erase(MemoryModuleType<?> type, CallbackInfo ci) {
        probe$memory(type, Optional.empty());
    }

    @Unique
    private void probe$memory(MemoryModuleType<?> type, Optional<?> value) {
        if (type == MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE) {
            // A game-time stamp: log it with both clocks (context = what getGameTime() returns here).
            Brain<?> self = (Brain<?>) (Object) this;
            Optional<?> old = self.getMemoryInternal(type);
            if (old == null) return;
            if (!old.equals(value) && RaidProbe.server != null) {
                RaidProbe.log("CANT_REACH brain=" + Integer.toHexString(System.identityHashCode(this))
                        + " " + old.map(Object::toString).orElse("-") + " -> " + value.map(Object::toString).orElse("-")
                        + " ctxTime=" + RaidProbe.server.overworld().getGameTime() + " rawTime=" + RaidProbe.rawTime()
                        + " owner=" + (probe$owner == null ? "?" : probe$owner.getUUID())
                        + " by " + RaidProbe.caller(3, 7));
            }
            return;
        }
        if (type == MemoryModuleType.JOB_SITE || type == MemoryModuleType.POTENTIAL_JOB_SITE
                || type == MemoryModuleType.HOME || type == MemoryModuleType.MEETING_POINT) {
            Brain<?> self = (Brain<?>) (Object) this;
            Optional<?> old = self.getMemoryInternal(type);
            if (old == null) return;
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
