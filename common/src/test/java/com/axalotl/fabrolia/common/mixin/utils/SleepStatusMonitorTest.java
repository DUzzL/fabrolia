package com.axalotl.fabrolia.common.mixin.utils;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit D-16: SleepStatus is in SyncAllMixin's blanket set, so Main and island
 * workers update and read the sleeper counters under one monitor. Run against
 * the real plugin and the real vanilla bytecode.
 */
final class SleepStatusMonitorTest {
    private static final String TARGET = "net.minecraft.server.players.SleepStatus";

    @Test
    void everySleepStatusMethodIsSynchronized() throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = getClass().getResourceAsStream("/net/minecraft/server/players/SleepStatus.class")) {
            assertNotNull(in, "vanilla SleepStatus bytecode must be on the test classpath");
            new ClassReader(in).accept(node, 0);
        }
        SynchronisePlugin plugin = new SynchronisePlugin();
        plugin.onLoad("com.axalotl.fabrolia.common.mixin");
        plugin.postApply(TARGET, node, "com.axalotl.fabrolia.common.mixin.utils.SyncAllMixin", null);
        for (String name : List.of("update", "removeAllSleepers", "areEnoughSleeping",
                "areEnoughDeepSleeping", "sleepersNeeded", "amountSleeping")) {
            MethodNode method = node.methods.stream().filter(m -> m.name.equals(name)).findFirst()
                    .orElseThrow(() -> new AssertionError("no method " + name));
            assertTrue((method.access & Opcodes.ACC_SYNCHRONIZED) != 0, name + " must hold the monitor");
        }
    }

    @Test
    void sleepStatusIsInTheBlanketSet() throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        String source = null;
        for (int i = 0; i < 6 && root != null && source == null; i++, root = root.getParent()) {
            for (Path candidate : List.of(
                    root.resolve("src/main/java/com/axalotl/fabrolia/common/mixin/utils/SyncAllMixin.java"),
                    root.resolve("common/src/main/java/com/axalotl/fabrolia/common/mixin/utils/SyncAllMixin.java"))) {
                if (Files.isRegularFile(candidate)) source = Files.readString(candidate);
            }
        }
        assertNotNull(source, "source tree not available");
        assertTrue(source.contains("SleepStatus.class"), "SleepStatus must stay in the blanket set");
    }
}
