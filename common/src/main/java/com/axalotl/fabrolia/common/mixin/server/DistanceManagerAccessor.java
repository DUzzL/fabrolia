package com.axalotl.fabrolia.common.mixin.server;

import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.SimulationChunkTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes {@code DistanceManager.simulationChunkTracker} so {@code ChunkMapMixin.<init>}
 *  can hand the tracker its owning ChunkMap back-ref (see {@link
 *  com.axalotl.fabrolia.common.regions.RegionTickingTrackerBind}). The field is declared on
 *  the base {@code DistanceManager}; the runtime instance is {@code ChunkMap$DistanceManager}
 *  (subclass), which inherits this mixed-in accessor. */
@Mixin(DistanceManager.class)
public interface DistanceManagerAccessor {
    @Accessor("simulationChunkTracker")
    SimulationChunkTracker fabrolia$simulationChunkTracker();
}
