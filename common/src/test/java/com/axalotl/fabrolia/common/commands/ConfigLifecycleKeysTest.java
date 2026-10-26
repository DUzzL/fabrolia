package com.axalotl.fabrolia.common.commands;

import com.axalotl.fabrolia.common.config.FabroliaConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit D-02: a config reload keeps the running lifecycle keys, toggle refuses
 * while region threading runs, and the switches workers read are volatile.
 */
final class ConfigLifecycleKeysTest {

    @Test
    void reloadKeepsEveryRunningLifecycleKey() {
        ConfigCommand.LifecycleKeys saved = ConfigCommand.LifecycleKeys.capture();
        try {
            ConfigCommand.LifecycleKeys live = ConfigCommand.LifecycleKeys.capture();
            assertNull(live.restoreChanged(), "an unchanged reload keeps nothing");
            FabroliaConfig.disabled = !live.disabled();
            FabroliaConfig.enableRegionThreading = !live.enableRegionThreading();
            FabroliaConfig.regionWorkerCount = live.regionWorkerCount() + 3;
            String kept = live.restoreChanged();
            assertTrue(kept.contains("disabled") && kept.contains("enableRegionThreading")
                    && kept.contains("regionWorkerCount"), kept);
            assertEquals(live, ConfigCommand.LifecycleKeys.capture(), "every key is back at its running value");
        } finally {
            saved.restoreChanged();
        }
    }

    @Test
    void asyncIslandsIsNotAConfigKeyAndFollowsTheSyncModeProperty() throws Exception {
        assertEquals(!Boolean.getBoolean(FabroliaConfig.SYNC_MODE_PROPERTY), FabroliaConfig.asyncIslands);
        for (String path : new String[]{
                "../fabric/src/main/java/com/axalotl/fabrolia/fabric/config/FabroliaConfig.java",
                "../neoforge/src/main/java/com/axalotl/fabrolia/neoforge/config/FabroliaConfig.java"}) {
            Path file = Paths.get("").toAbsolutePath().resolve(path).normalize();
            assertTrue(Files.isRegularFile(file), "loader config source not found: " + file);
            String source = Files.readString(file);
            assertTrue(!source.contains("\"asyncIslands\", "), file + " must not define the key");
            assertTrue(!source.contains("asyncIslandsPersister"), file + " must not persist it");
        }
    }

    @Test
    void workerReadSwitchesAreVolatile() throws Exception {
        for (String name : new String[]{"disabled", "enableRegionThreading", "asyncIslands"}) {
            assertTrue(Modifier.isVolatile(FabroliaConfig.class.getField(name).getModifiers()), name);
        }
    }

    @Test
    void toggleAndRegionStartRefuseTheUnguardedCombination() throws Exception {
        String config = source("common/commands/ConfigCommand.java");
        int toggle = config.indexOf("return literal(\"toggle\")");
        int guard = config.indexOf("if (!FabroliaConfig.disabled && com.axalotl.fabrolia.common.regions.RegionScheduler.isActive())", toggle);
        int flip = config.indexOf("FabroliaConfig.disabled = !FabroliaConfig.disabled;", toggle);
        assertTrue(toggle >= 0 && guard > toggle && flip > guard, "toggle must refuse while regions run");
        int reload = config.indexOf("LifecycleKeys live = LifecycleKeys.capture();");
        int platform = config.indexOf("PlatformUtils.reloadConfig();", reload);
        int restore = config.indexOf("live.restoreChanged()", platform);
        assertTrue(reload >= 0 && platform > reload && restore > platform);

        String regions = source("common/commands/RegionCommand.java");
        int start = regions.indexOf("if (enable && FabroliaConfig.disabled)");
        int write = regions.indexOf("FabroliaConfig.enableRegionThreading = enable;", start);
        assertTrue(start >= 0 && write > start, "regions on must refuse while Fabrolia is disabled");

        String trigger = source("common/mixin/advancement/SimpleCriterionTriggerMixin.java");
        assertTrue(!trigger.contains("FabroliaConfig.disabled"),
                "advancement triggers no longer bypass owner routing on the disabled switch");
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
