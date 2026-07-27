package com.axalotl.fabrolia.common.mixin.entity.spawn;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.biome.MobSpawnSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Cost-map read for {@link com.axalotl.fabrolia.common.parallelised.utils.SpawnCensus}'s
 *  per-level cost-bearing type precompute (task #21) — vanilla only exposes the
 *  per-type lookup. */
@Mixin(MobSpawnSettings.class)
public interface MobSpawnSettingsAccessor {

    @Accessor("mobSpawnCosts")
    Map<EntityType<?>, MobSpawnSettings.MobSpawnCost> fabrolia$mobSpawnCosts();
}
