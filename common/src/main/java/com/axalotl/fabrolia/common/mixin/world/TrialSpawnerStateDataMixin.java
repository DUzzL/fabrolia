package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.parallelised.utils.TrialSpawnerLineOfSightCache;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawner;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerStateData;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Seeds line-of-sight cache misses on the source owner before native detection.
 * Vanilla retains its detector-specific filters and consumes the real ray results.
 */
@Mixin(TrialSpawnerStateData.class)
public abstract class TrialSpawnerStateDataMixin {

    @Inject(
            method = "tryDetectPlayers",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/entity/trialspawner/PlayerDetector;detect(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/block/entity/trialspawner/PlayerDetector$EntitySelector;Lnet/minecraft/core/BlockPos;DZ)Ljava/util/List;")
    )
    private void fabrolia$prefetchLineOfSight(ServerLevel level, BlockPos pos, TrialSpawner spawner, CallbackInfo ci) {
        // The vanilla detect() about to fire from here is the (level, selector, pos,
        // requiredPlayerRange, useLOS=true) variant. Seed on this same owner;
        // no live world reads or native callbacks are dispatched to helpers.
        //
        // We deliberately use the same player-selection criterion as the vanilla detectors
        // (closerThan + !creative + !spectator) — close enough that 99% of candidates we
        // pre-raytrace are also the candidates vanilla would test. Over-coverage costs at
        // most a few extra raytraces (cache misses that vanilla skips), zero correctness.
        int range = spawner.getRequiredPlayerRange();
        if (range <= 0) return;
        List<ServerPlayer> nearby = level.getPlayers(p ->
                p.blockPosition().closerThan(pos, range)
                        && !p.isCreative()
                        && !p.isSpectator());
        // Small candidate sets use the native per-ray cache path without a batch.
        if (nearby.size() < 4) return;

        Vec3 from = Vec3.atCenterOf(pos);
        List<Vec3> targets = new ArrayList<>(nearby.size());
        for (ServerPlayer p : nearby) targets.add(p.getEyePosition());
        TrialSpawnerLineOfSightCache.prefetch(level, from, targets);
    }
}
