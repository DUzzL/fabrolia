package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The old packet Runnable lane is quarantined; v2 must never recreate it. */
class PacketLaneProductionPathTest {

    @Test
    void oldLaneClassesAreAbsentAndV2HasNoPacketOrDoTickCallSite() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        for (String resource : new String[] {
                "com/axalotl/fabrolia/common/regions/RegionalPlayerTicks.class",
                "com/axalotl/fabrolia/common/parallelised/utils/FabroliaListenerLane.class",
                "com/axalotl/fabrolia/common/mixin/server/ServerGamePacketRoutingMixin.class",
                "com/axalotl/fabrolia/common/mixin/network/PacketUtilsWorkerMixin.class"
        }) assertNull(loader.getResource(resource), resource);

        ClassNode node = read(RegionalPlayerEntityTicksV2.class);
        for (var method : node.methods) for (AbstractInsnNode insn : method.instructions) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            assertFalse(call.owner.contains("PacketUtils"), call.owner + "." + call.name);
            assertFalse(call.owner.endsWith("ServerGamePacketListenerImpl")
                    && (call.name.equals("tickPlayer") || call.name.startsWith("handle")),
                    call.owner + "." + call.name);
            assertFalse(call.owner.equals("net/minecraft/server/level/ServerPlayer")
                    && call.name.equals("doTick"), call.owner + "." + call.name);
        }
    }

    private static ClassNode read(Class<?> type) throws Exception {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, 0);
            return node;
        }
    }
}
