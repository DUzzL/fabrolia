package com.axalotl.fabrolia.common.parallelised.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;

import java.util.function.Consumer;

/** CollectingNeighborUpdater duck backing {@link FabroliaThreadNeighborUpdaters}. */
public interface FabroliaNeighborUpdaterState {
    int fabrolia$maxChainedNeighborUpdates();

    Consumer<BlockPos> fabrolia$debugListener();

    /** Makes this per-thread instance follow {@code primary}'s debug listener. */
    void fabrolia$followDebugListenerOf(CollectingNeighborUpdater primary);
}
