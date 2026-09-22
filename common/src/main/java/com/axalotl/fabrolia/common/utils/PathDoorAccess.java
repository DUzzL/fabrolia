package com.axalotl.fabrolia.common.utils;

import net.minecraft.core.BlockPos;

/** Last complete step published by the path owner; foreign readers never lock navigation. */
public interface PathDoorAccess {
    Step fabrolia$doorStep();

    default boolean fabrolia$isComingThroughDoor(BlockPos position) {
        Step step = fabrolia$doorStep();
        return step != null && step.contains(position);
    }

    /** Called only at path mutation/publication boundaries, including copy and decode. */
    void fabrolia$publishDoorStep();

    record Step(long previous, long next) {
        public boolean contains(BlockPos position) {
            long packed = position.asLong();
            return previous == packed || next == packed;
        }
    }
}
