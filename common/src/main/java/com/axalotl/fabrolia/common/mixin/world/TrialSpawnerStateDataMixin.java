package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.parallelised.utils.TrialSpawnerLineOfSightCache;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.trialspawner.PlayerDetector;
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
 * Parallel LOS prefetch: just before vanilla calls {@code PlayerDetector.detect}, we
 * batch-raytrace every candidate player on the FJP pool and seed
 * {@link TrialSpawnerLineOfSightCache}. Vanilla's serial detect() loop then sees every
 * {@code inLineOfSight} as a cache hit (the
 * {@code PlayerDetectorLineOfSightMixin} short-circuits via the same cache).
 *
 * <p>The full path becomes: 1 burst of parallel raytraces on cache-miss + 0-cost serial
 * filter inside vanilla detect(), instead of N serial raytraces blocking main thread.
 *
 * <p>Hybrid logic, not just cache, not just multithread:
 * <ul>
 *   <li>2nd-Nth detection within TTL window → 100% cache hits → 0 work</li>
 *   <li>1st detection / blocks changed / player moved through grid → cache misses, but
 *       miss raytraces run on N FJP workers concurrently instead of serially on main</li>
 *   <li>Fewer than {@code PARALLEL_MIN_MISSES} misses → serial fill (FJP dispatch
 *       overhead exceeds work)</li>
 * </ul>
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
        // requiredPlayerRange, useLOS=true) variant. We pre-fill the LOS cache with
        // parallel raytraces so detect()'s subsequent serial filter is all cache hits.
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
        if (nearby.size() < TrialSpawnerLineOfSightCache.PARALLEL_MIN_MISSES) {
            // Too few players for parallel to be worth the dispatch overhead. The
            // single-LOS HEAD cache hit/miss path in PlayerDetectorLineOfSightMixin
            // covers this scale just fine.
            return;
        }

        Vec3 from = Vec3.atCenterOf(pos);
        List<Vec3> targets = new ArrayList<>(nearby.size());
        for (ServerPlayer p : nearby) targets.add(p.getEyePosition());
        TrialSpawnerLineOfSightCache.parallelPrefetch(level, from, targets);
    }
}
