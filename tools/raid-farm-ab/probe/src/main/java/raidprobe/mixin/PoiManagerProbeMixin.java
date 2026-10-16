package raidprobe.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import raidprobe.RaidProbe;

@Mixin(PoiManager.class)
public class PoiManagerProbeMixin {
    @Inject(method = "add", at = @At("HEAD"))
    private void probe$add(BlockPos pos, Holder<PoiType> type, CallbackInfoReturnable<PoiRecord> cir) {
        if (RaidProbe.near(pos)) RaidProbe.log("POI_ADD " + pos.toShortString() + " " + type.getRegisteredName());
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void probe$remove(BlockPos pos, CallbackInfo ci) {
        if (RaidProbe.near(pos)) RaidProbe.log("POI_REMOVE " + pos.toShortString());
    }
}
