package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.junit.jupiter.api.Assertions.*;

class PortalTicketRequestRegressionTest {
    @Test void deferredTicketCapturesDestinationAndNeverRechecksTheEntity() throws Exception {
        ClassNode node = new ClassNode(Opcodes.ASM9);
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "com/axalotl/fabrolia/common/mixin/utils/PortalTicketMixin.class")) {
            assertNotNull(input); new ClassReader(input).accept(node, 0);
        }
        var lambda = node.methods.stream().filter(method -> method.name.startsWith("lambda$")).findFirst().orElseThrow();
        assertTrue(lambda.desc.contains("ServerLevel;"));
        assertTrue(lambda.desc.contains("ChunkPos;"));
        assertFalse(lambda.desc.contains("world/entity/Entity;"));
        boolean ticket = false;
        for (var instruction : lambda.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                assertNotEquals("isRemoved", call.name);
                assertNotEquals("placePortalTicket", call.name);
                ticket |= call.name.equals("addTicketWithRadius");
            }
        }
        assertTrue(ticket, "the accepted request must directly add its captured ticket");
    }
}
