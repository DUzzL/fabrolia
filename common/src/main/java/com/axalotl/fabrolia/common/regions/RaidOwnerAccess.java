package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;

/** Immutable lookup metadata; mutable raid bodies remain on their spatial owner. */
public interface RaidOwnerAccess {
    record View(BlockPos center, boolean active, boolean stopped) {}

    View fabrolia$raidView();

    boolean fabrolia$absorbOmenIfEligible(ServerPlayer player, MobEffectInstance effect);
}
