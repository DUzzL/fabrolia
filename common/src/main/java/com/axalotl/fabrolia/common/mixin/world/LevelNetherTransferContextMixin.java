package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.NetherPortalTransfers;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/** A transfer owns two timelines; PrimaryLevelData alone cannot identify the queried level. */
@Mixin(LevelAccessor.class)
public interface LevelNetherTransferContextMixin {
    /**
     * @author Fabrolia
     * @reason Retain the exact queried-level timeline during a two-owner transfer.
     */
    @Overwrite
    default long getGameTime() {
        LevelAccessor self = (LevelAccessor) this;
        if (self instanceof ServerLevel level) {
            long time = NetherPortalTransfers.gameTimeFor(level);
            if (time != Long.MIN_VALUE) return time;
        }
        return self.getLevelData().getGameTime();
    }
}
