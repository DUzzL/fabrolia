package com.axalotl.fabrolia.common.regions;

import net.minecraft.world.entity.raid.Raid;

/** Identity-conditional registry removal after the raid owner has stopped its body. */
public interface RaidRegistryAccess {
    /** Private native-order lifecycle cursor; remove only after owning/stopping its current raid. */
    java.util.Iterator<Raid> fabrolia$raidIterator();

    /** Published registry membership only; does not read a mutable raid body. */
    boolean fabrolia$isRegistered(Raid raid);
}
