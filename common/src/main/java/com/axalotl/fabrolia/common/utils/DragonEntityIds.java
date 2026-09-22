package com.axalotl.fabrolia.common.utils;

import com.axalotl.fabrolia.common.mixin.world.ServerLevelEntityCounterAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;

import java.util.concurrent.atomic.AtomicInteger;

/** Keeps server multipart identities equal to the client's parent-id offsets. */
public final class DragonEntityIds {
    private DragonEntityIds() {}

    /**
     * Assign only after all parts exist and before any entity, part, tracker or
     * entity-hash publication. The constructor's independent temporary ids cannot
     * be reused: another island may have allocated between any two of them.
     */
    public static void assignUnpublished(ServerLevel level, EnderDragon dragon) {
        EnderDragonPart[] parts = dragon.getSubEntities();
        int first = reserve(level, ServerLevelEntityCounterAccessor.fabrolia$entityCounter(),
                parts.length + 1);
        dragon.setId(first);
        for (int i = 0; i < parts.length; i++) {
            parts[i].setId(first + i + 1);
        }
    }

    /**
     * Shares vanilla's counter, without serializing unrelated constructors.
     * Collision checks cover this level's currently addressable identities; like
     * vanilla, this is not a registry of unpublished ids across a full counter cycle.
     */
    static int reserve(ServerLevel level, AtomicInteger counter, int count) {
        reservation:
        while (true) {
            int first = counter.getAndAdd(count) + 1;
            for (int offset = 0; offset < count; offset++) {
                // Signed overflow agrees with the client's int offset arithmetic.
                int id = first + offset;
                // Use the concurrent entity and synchronized part indexes, not
                // ChunkMap's main-confined tracker map (including with C2ME).
                // Tracking inserts the entity index before the tracker and removes
                // it after the tracker, so this also covers vanilla's live-id check.
                if (id == 0 || level.getEntityOrPart(id) != null) {
                    continue reservation;
                }
            }
            return first;
        }
    }
}
