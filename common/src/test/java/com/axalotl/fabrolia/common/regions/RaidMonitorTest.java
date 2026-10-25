package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.entity.RaidMixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Audit D-15: one monitor per raid serialises the cross-region raid mutators. */
final class RaidMonitorTest {

    @Test
    void mutatorsHoldTheRaidMonitor() throws Exception {
        RaidMixin raid = new RaidMixin();
        Method leader = RaidMixin.class.getDeclaredMethod("fabrolia$removeLeader", int.class, Operation.class);
        leader.setAccessible(true);
        Operation<Void> body = arguments -> {
            assertTrue(Thread.holdsLock(raid), "the mutator must run under the raid's monitor");
            return null;
        };
        leader.invoke(raid, 3, body);
        assertFalse(Thread.holdsLock(raid));
    }

    @Test
    void everyCrossRegionMutatorIsWrappedOnTheRaid() throws Exception {
        String text = source("common/mixin/entity/RaidMixin.java");
        for (String method : new String[]{"addWaveMob(Lnet/minecraft/server/level/ServerLevel;ILnet/minecraft/world/entity/raid/Raider;Z)Z",
                "addWaveMob(Lnet/minecraft/server/level/ServerLevel;ILnet/minecraft/world/entity/raid/Raider;)Z",
                "removeFromRaid", "setLeader", "removeLeader", "addHeroOfTheVillage", "updateBossbar"}) {
            assertTrue(text.contains("@WrapMethod(method = \"" + method + "\")"), method);
        }
        assertFalse(text.contains("tickLockOr"), "the per-region tick lock differs across a raid's regions");
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && root != null; i++, root = root.getParent()) {
            Path direct = root.resolve("src/main/java/com/axalotl/fabrolia").resolve(relative);
            Path viaCommon = root.resolve("common/src/main/java/com/axalotl/fabrolia").resolve(relative);
            if (Files.isRegularFile(direct)) return Files.readString(direct);
            if (Files.isRegularFile(viaCommon)) return Files.readString(viaCommon);
        }
        throw new AssertionError("source tree not available");
    }
}
