package com.axalotl.fabrolia.common.parallelised.utils;

import net.minecraft.core.BlockPos;

public interface FabroliaSafeNavigation {
    boolean fabrolia$shouldRecomputePathSafe(BlockPos pos);

    /** The navigation's owning mob — leak audit (task #24). */
    default net.minecraft.world.entity.Mob fabrolia$navMob() {
        return null;
    }
}