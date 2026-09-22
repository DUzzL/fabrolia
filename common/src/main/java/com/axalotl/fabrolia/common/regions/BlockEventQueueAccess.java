package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.world.level.BlockEventData;

/**
 * Exposes the native shared queue, level-wide pending identities and their
 * common lock. Native insertion order and dedup extend across island stashes
 * and unit captures until single-event dequeue, not merely while an event
 * happens to reside in the shared set.
 */
public interface BlockEventQueueAccess {

    Object fabrolia$blockEventLock();

    ObjectLinkedOpenHashSet<BlockEventData> fabrolia$blockEvents();

    IslandBlockEvents.Publications fabrolia$blockEventPublications();
}
