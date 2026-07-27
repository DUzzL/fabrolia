package com.axalotl.fabrolia.common.mixin.world;

import net.minecraft.world.level.block.entity.DaylightDetectorBlockEntity;
import org.spongepowered.asm.mixin.Mixin;

/** Per-detector wall-epoch memo for DaylightDetectorBlockMixin's gate. */
@Mixin(DaylightDetectorBlockEntity.class)
public abstract class DaylightDetectorBlockEntityMixin implements com.axalotl.fabrolia.common.regions.DetectorEpochHolder {
    @org.spongepowered.asm.mixin.Unique
    private long fabrolia$lastEpoch = Long.MIN_VALUE;
    @Override public long fabrolia$lastDetectorEpoch() { return fabrolia$lastEpoch; }
    @Override public void fabrolia$setDetectorEpoch(long e) { fabrolia$lastEpoch = e; }
}
