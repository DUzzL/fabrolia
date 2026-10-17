package raidprobe.mixin;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.AcquirePoi;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import raidprobe.RaidProbe;

import java.util.Set;

/** Observe the candidates already chosen by vanilla/Lithium; do not issue new POI queries. */
@Mixin(AcquirePoi.class)
public class AcquirePoiProbeMixin {
    @Inject(method = "findPathToPois", at = @At("RETURN"))
    private static void probe$path(Mob mob, Set<Pair<Holder<PoiType>, BlockPos>> candidates,
                                   CallbackInfoReturnable<Path> cir) {
        if (!(mob instanceof Villager villager) || !RaidProbe.near(mob.blockPosition())) return;
        Path path = cir.getReturnValue();
        RaidProbe.log("ACQUIRE_PATH uuid=" + mob.getUUID() + " pos=" + mob.blockPosition().toShortString()
                + " brain=" + Integer.toHexString(System.identityHashCode(villager.getBrain()))
                + " candidates=" + candidates.stream().map(p -> p.getFirst().getRegisteredName() + "@" + p.getSecond().toShortString()).sorted().toList()
                + " target=" + (path == null ? "-" : path.getTarget())
                + " reached=" + (path != null && path.canReach()));
    }
}
