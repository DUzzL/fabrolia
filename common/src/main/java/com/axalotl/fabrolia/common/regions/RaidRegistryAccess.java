package com.axalotl.fabrolia.common.regions;

import net.minecraft.world.entity.raid.Raid;

/** Identity-conditional registry removal after the raid owner has stopped its body. */
public interface RaidRegistryAccess {
    void fabrolia$removeStoppedRaid(Raid raid);
}
