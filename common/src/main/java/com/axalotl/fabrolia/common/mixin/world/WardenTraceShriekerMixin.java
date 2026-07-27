package com.axalotl.fabrolia.common.mixin.world;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.level.block.entity.SculkShriekerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shrieker response tracer (task #16): logs every {@code tryRespond} entry,
 * the {@code canRespond} verdict (with the live nearby-Warden count — the
 * stale-ghost suspect), and every {@code trySummonWarden} verdict, so a
 * shriek that produces no Warden names the exact gate that rejected it.
 * Gated on {@code -Dfabrolia.debug.wardenTrace=true}.
 */
@Mixin(SculkShriekerBlockEntity.class)
public abstract class WardenTraceShriekerMixin {

    private static final boolean fabrolia$WT = Boolean.getBoolean("fabrolia.debug.wardenTrace");

    private void fabrolia$wt(ServerLevel level, String msg) {
        SculkShriekerBlockEntity self = (SculkShriekerBlockEntity) (Object) this;
        org.slf4j.LoggerFactory.getLogger("Fabrolia-WardenTrace").info(
                "[wtrace] {} W={} @{} thread={}", msg,
                com.axalotl.fabrolia.common.regions.IslandClock.wallTime(level),
                self.getBlockPos().toShortString(), Thread.currentThread().getName());
    }

    @Inject(method = "tryRespond", at = @At("HEAD"))
    private void fabrolia$traceRespond(ServerLevel level, CallbackInfo ci) {
        if (fabrolia$WT) {
            SculkShriekerBlockEntity self = (SculkShriekerBlockEntity) (Object) this;
            int nearby = level.getEntitiesOfClass(Warden.class,
                    new net.minecraft.world.phys.AABB(self.getBlockPos()).inflate(48.0)).size();
            fabrolia$wt(level, "RESPOND wardensNear=" + nearby);
        }
    }

    @Inject(method = "canRespond", at = @At("RETURN"))
    private void fabrolia$traceCanRespond(ServerLevel level, CallbackInfoReturnable<Boolean> cir) {
        if (fabrolia$WT) {
            fabrolia$wt(level, "CANRESPOND=" + cir.getReturnValue());
        }
    }

    @Inject(method = "trySummonWarden", at = @At("RETURN"))
    private void fabrolia$traceSummon(ServerLevel level, CallbackInfoReturnable<Boolean> cir) {
        if (fabrolia$WT) {
            fabrolia$wt(level, "SUMMON=" + cir.getReturnValue());
        }
    }
}
