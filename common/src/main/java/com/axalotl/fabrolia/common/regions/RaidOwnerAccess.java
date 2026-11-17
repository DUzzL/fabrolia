package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;

/** Immutable lookup metadata; mutable raid bodies remain on their spatial owner. */
public interface RaidOwnerAccess {
    record View(BlockPos center, boolean active, boolean stopped) {}

    View fabrolia$raidView();
}
