package com.axalotl.fabrolia.common.mixin;

import com.axalotl.fabrolia.common.mixin.world.LevelTicksMixin;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code @Shadow} field initializer is merged into the target constructor.
 * Writing {@code = null} here therefore destroys vanilla's already-created
 * runtime queue/list instead of merely satisfying javac. Keep vanilla-owned
 * scheduler state declaration-only in the mixin.
 */
final class MixinShadowStateOwnershipTest {

    @Test
    void levelTicksMixinDoesNotOverwriteVanillaRuntimeQueues() throws IOException {
        ClassNode owner = new ClassNode();
        new ClassReader(LevelTicksMixin.class.getName()).accept(owner, 0);
        MethodNode constructor = owner.methods.stream()
                .filter(method -> method.name.equals("<init>"))
                .findFirst()
                .orElseThrow();
        Set<String> vanillaOwned = Set.of("toRunThisTick", "alreadyRunThisTick");

        List<String> overwritten = new ArrayList<>();
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (instruction.getOpcode() != Opcodes.PUTFIELD) continue;
            FieldInsnNode field = (FieldInsnNode) instruction;
            if (field.owner.equals(owner.name) && vanillaOwned.contains(field.name)) {
                overwritten.add(field.name);
            }
        }

        assertTrue(overwritten.isEmpty(), () ->
                "Mixin constructor overwrites vanilla-owned LevelTicks state: "
                        + overwritten);
    }
}
