package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.entity.movement.PoiManagerDistanceMixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code PoiManager#sectionsToVillage} reads the distance graph's plain level map;
 * the read must hold the tracker monitor that SyncAllMixin's graph writes hold.
 */
final class PoiVillageReadMonitorTest {
    private static final String TRACKER = "net/minecraft/world/entity/ai/village/poi/PoiManager$DistanceTracker";

    @Test
    void vanillaQueryReadsTheTrackerLevelExactlyOnce() throws Exception {
        ClassNode node = new ClassNode();
        try (var input = PoiManager.class.getResourceAsStream("PoiManager.class")) {
            new ClassReader(input.readAllBytes()).accept(node, 0);
        }
        var method = node.methods.stream().filter(m -> m.name.equals("sectionsToVillage"))
                .findFirst().orElseThrow();
        int reads = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(TRACKER)
                    && call.name.equals("getLevel") && call.desc.equals("(J)I")) {
                reads++;
            }
        }
        assertEquals(1, reads, "the wrapped INVOKE target must exist exactly once");
    }

    @Test
    void levelReadRunsUnderTheTrackerMonitor() throws Exception {
        Object tracker = new Object();
        Method hook = PoiManagerDistanceMixin.class.getDeclaredMethod(
                "fabrolia$readLevelUnderGraphMonitor", Object.class, long.class, Operation.class);
        hook.setAccessible(true);
        Operation<Integer> read = arguments -> {
            assertTrue(Thread.holdsLock(arguments[0]), "getLevel must run under the graph monitor");
            assertEquals(42L, arguments[1]);
            return 3;
        };
        Object level = hook.invoke(new PoiManagerDistanceMixin() {}, tracker, 42L, read);
        assertEquals(3, level);
        assertTrue(!Thread.holdsLock(tracker), "the monitor is released after the read");
    }
}
