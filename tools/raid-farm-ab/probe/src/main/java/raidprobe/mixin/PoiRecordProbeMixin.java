package raidprobe.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import raidprobe.RaidProbe;

@Mixin(PoiRecord.class)
public class PoiRecordProbeMixin {
    @Shadow @Final private BlockPos pos;
    @Shadow @Final private Holder<PoiType> poiType;
    @Shadow private int freeTickets;

    @Inject(method = "acquireTicket", at = @At("RETURN"))
    private void probe$acquire(CallbackInfoReturnable<Boolean> cir) {
        if (RaidProbe.near(pos)) RaidProbe.log("POI_ACQUIRE " + pos.toShortString() + " " + poiType.getRegisteredName()
                + " ok=" + cir.getReturnValue() + " free=" + freeTickets + " by " + RaidProbe.caller(2, 3));
    }

    @Inject(method = "releaseTicket", at = @At("RETURN"))
    private void probe$release(CallbackInfoReturnable<Boolean> cir) {
        // Releases are rare; the full caller chain names the behaviour that dropped the claim.
        if (RaidProbe.near(pos)) RaidProbe.log("POI_RELEASE " + pos.toShortString() + " " + poiType.getRegisteredName()
                + " ok=" + cir.getReturnValue() + " free=" + freeTickets + " by " + RaidProbe.caller(2, 24));
    }
}
