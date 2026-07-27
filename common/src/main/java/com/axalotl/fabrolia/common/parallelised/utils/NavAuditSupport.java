package com.axalotl.fabrolia.common.parallelised.utils;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

/** Package bridge for the nav-leak sweep (task #24): the authoritative
 *  liveness test is SECTION MEMBERSHIP — c2me's unload path drops entities
 *  with {@code isRemoved} still false (the task #32 census lesson), so flag
 *  checks alone cannot see the leak. */
public final class NavAuditSupport {

    private NavAuditSupport() {}

    public static boolean gone(ServerLevel level, Mob mob) {
        if (mob == null || mob.isRemoved()) {
            return true;
        }
        if (mob.level() != level) {
            return true;
        }
        return !ParallelSpawnHelper.fabrolia$inSectionsTolerant(level, mob);
    }
}
