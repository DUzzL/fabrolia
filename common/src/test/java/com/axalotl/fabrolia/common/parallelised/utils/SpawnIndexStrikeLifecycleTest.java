package com.axalotl.fabrolia.common.parallelised.utils;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.cow.Cow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnIndexStrikeLifecycleTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void leavingTheVisibleSetEndsAPendingPurgeStrike() throws Exception {
        sun.misc.Unsafe unsafe = unsafe();
        ServerLevel level = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
        Cow mob = (Cow) unsafe.allocateInstance(Cow.class);
        mob.setId(42);
        try {
            SpawnIndex.onTrackingStart(level, mob);
            assertFalse(SpawnIndex.strikeForPurge(level, mob), "first failed sighting only strikes");

            SpawnIndex.onTrackingEnd(level, mob);

            // The strike ledger must not keep the departed entity: had it kept
            // it, this sighting after a re-entry would count as the second.
            SpawnIndex.onTrackingStart(level, mob);
            assertFalse(SpawnIndex.strikeForPurge(level, mob),
                    "a strike taken before the entity left cannot carry over");
            assertTrue(SpawnIndex.strikeForPurge(level, mob), "consecutive sightings still purge");
        } finally {
            SpawnIndex.clearLevel(level);
        }
    }

    private static sun.misc.Unsafe unsafe() throws ReflectiveOperationException {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }
}
