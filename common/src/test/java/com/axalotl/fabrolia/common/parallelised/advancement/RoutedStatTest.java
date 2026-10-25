package com.axalotl.fabrolia.common.parallelised.advancement;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Audit D-12: an off-owner stat change is routed and applied to the player on the drain. */
final class RoutedStatTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void routedStatAppliesTheAwardOrReset() throws Exception {
        Recorder player = (Recorder) unsafe().allocateInstance(Recorder.class);
        player.calls = new ArrayList<>();
        Stat<?> bred = Stats.CUSTOM.get(Stats.ANIMALS_BRED);
        AdvancementBuffer.RoutedStat award = new AdvancementBuffer.RoutedStat(null, player, bred, 2, false);
        AdvancementBuffer.RoutedStat reset = new AdvancementBuffer.RoutedStat(null, player, bred, 0, true);
        assertTrue(award.needsFence(), "stats apply on Main under the fence, as the owner would");
        award.apply();
        reset.apply();
        assertEquals(List.of("award " + bred + " 2", "reset " + bred), player.calls);
    }

    @Test
    void serverPlayerStatsAreOwnerRouted() throws Exception {
        String mixin = source("common/src/main/java/com/axalotl/fabrolia/common/mixin/server/ServerPlayerStatOwnershipMixin.java");
        assertTrue(mixin.contains("@WrapMethod(method = \"awardStat(Lnet/minecraft/stats/Stat;I)V\")")
                && mixin.contains("@WrapMethod(method = \"resetStat(Lnet/minecraft/stats/Stat;)V\")"));
        assertTrue(mixin.contains("if (PlayerTurnOwnership.currentThreadOwns(self))")
                && mixin.contains("AdvancementBuffer.routeStat(self, stat, count, false)")
                && mixin.contains("AdvancementBuffer.routeStat(self, stat, 0, true)"));
        String config = source("common/src/main/resources/fabrolia.common.mixins.json");
        assertTrue(config.contains("\"server.ServerPlayerStatOwnershipMixin\""));
    }

    private static final class Recorder extends ServerPlayer {
        List<String> calls;
        private Recorder() { super(null, null, null, null); }
        @Override public void awardStat(Stat<?> stat, int count) { calls.add("award " + stat + " " + count); }
        @Override public void resetStat(Stat<?> stat) { calls.add("reset " + stat); }
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && root != null; i++, root = root.getParent()) {
            Path candidate = root.resolve(relative);
            if (Files.isRegularFile(candidate)) return Files.readString(candidate);
        }
        throw new AssertionError("source tree not available");
    }
}
