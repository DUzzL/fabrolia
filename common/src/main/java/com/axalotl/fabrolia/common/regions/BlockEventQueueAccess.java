package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.world.level.BlockEventData;

/**
 * A2 duck interface on ServerLevel (implemented by ServerLevelMixin): exposes
 * the vanilla block-event queue and the lock Fabrolia already serializes it
 * with, so the island partition (IslandBlockEvents) can extract/re-add events
 * under the exact same discipline as the queueing redirects. The set's
 * SEMANTICS are load-bearing: insertion order + dedup (a piston re-triggered
 * in one tick queues ONE event — losing dedup double-fired flying machines).
 */
public interface BlockEventQueueAccess {

    Object fabrolia$blockEventLock();

    ObjectLinkedOpenHashSet<BlockEventData> fabrolia$blockEvents();
}
