package com.axalotl.fabrolia.common.mixin.entity.spawn;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.axalotl.fabrolia.common.parallelised.utils.ParallelSpawnHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


@Mixin(value = NaturalSpawner.class, priority = 1100)
public abstract class NaturalSpawnerMixin {

    @Inject(method = "createState", at = @At("HEAD"), cancellable = true)
    private static void fabrolia$parallelCreateState(int spawnableChunkCount, Iterable<Entity> entities, NaturalSpawner.ChunkGetter chunkGetter, LocalMobCapCalculator localMobCapCalculator, CallbackInfoReturnable<NaturalSpawner.SpawnState> cir) {
        if (FabroliaConfig.disabled || !FabroliaConfig.enableFabroliaSpawn) return;
        if (ParallelProcessor.executor == null) return;
        cir.setReturnValue(ParallelSpawnHelper.buildState(spawnableChunkCount, entities, chunkGetter, localMobCapCalculator));
    }

    // Deliberately NO getMobForSpawn cap bail and NO spawnForChunk state binding.
    // A previous pair of injects here cancelled mob CREATION (returned null) once
    // the global count crossed the cap mid-tick — and a null from getMobForSpawn
    // makes vanilla abort the chunk's ENTIRE spawn attempt (all three pack
    // groups), not just one mob. Vanilla's global cap is a tick-entry filter
    // (getFilteredSpawningCategories) and the within-tick overshoot it allows is
    // the burst-refill rate cap-saturated farms depend on; clamping it read as an
    // intermittent "mobcap saturation" slowdown, binding only under cap pressure
    // (gold-farm report, removed 2026-07-07 together with the canSpawn bail in
    // SpawnStateMixin and the SPAWN_STATE ScopedValue that only served this).
}
