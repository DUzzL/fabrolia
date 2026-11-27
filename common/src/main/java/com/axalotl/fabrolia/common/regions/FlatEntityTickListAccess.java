package com.axalotl.fabrolia.common.regions;

import net.minecraft.world.entity.Entity;

import java.util.function.Consumer;

/** Main's synchronous entity phase. Select one callback for the entire vanilla forEach cut;
 * membership changes during callbacks belong to the next cut. The map is never exposed. */
public interface FlatEntityTickListAccess {
    /** Returns true if the ordered callback ran instead of collecting work for the flat pool. */
    boolean fabrolia$visitFlatEntities(Consumer<Entity> collect, Consumer<Entity> ordered);
}
