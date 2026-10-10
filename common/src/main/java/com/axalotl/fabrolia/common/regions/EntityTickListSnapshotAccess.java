package com.axalotl.fabrolia.common.regions;

import net.minecraft.world.entity.Entity;

import java.util.List;

/** A finite, insertion-ordered cut of vanilla's current ticking membership. */
public interface EntityTickListSnapshotAccess {
    List<Entity> fabrolia$snapshotEntities();
}
