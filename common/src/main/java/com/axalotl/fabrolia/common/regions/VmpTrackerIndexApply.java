package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Field;

/**
 * Duck installed on VMP's NearbyEntityTracking: publish one off-Main
 * application of its queued tracker add/remove changes (see
 * {@code NearbyEntityTrackingMixin#fabrolia$scheduleIndexApply}).
 */
public interface VmpTrackerIndexApply {

    /** Main only, inside a healthy body. No-op when nothing is queued. */
    void fabrolia$scheduleIndexApply();

    /** Main, level tick head: schedule for this level's VMP tracker when installed. */
    static void scheduleFor(ServerLevel level) {
        Field field = Holder.FIELD;
        if (field == null) return;
        Object tracker;
        try {
            tracker = field.get(level.getChunkSource().chunkMap);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("VMP tracker field became inaccessible", failure);
        }
        if (tracker instanceof VmpTrackerIndexApply apply) apply.fabrolia$scheduleIndexApply();
    }

    /** The VMP-merged tracker field of ChunkMap, or null without VMP. */
    final class Holder {
        static final Field FIELD = find();

        private Holder() {}

        private static Field find() {
            for (Field candidate : ChunkMap.class.getDeclaredFields()) {
                if (VmpTrackerIndexApply.class.isAssignableFrom(candidate.getType())) {
                    candidate.setAccessible(true);
                    return candidate;
                }
            }
            return null;
        }
    }
}
