package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.world.RedstoneWireSignalMixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.block.RedstoneWireBlock;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Audit B-04: the wire's shouldSignal suppression is per thread. */
final class RedstoneWireSignalTest {

    @Test
    void suppressionOnOneThreadIsInvisibleToAnother() throws Exception {
        RedstoneWireSignalMixin mixin = new RedstoneWireSignalMixin() {};
        Method read = RedstoneWireSignalMixin.class.getDeclaredMethod("fabrolia$readThreadSignal",
                RedstoneWireBlock.class, Operation.class);
        Method write = RedstoneWireSignalMixin.class.getDeclaredMethod("fabrolia$writeThreadSignal",
                RedstoneWireBlock.class, boolean.class, Operation.class);
        read.setAccessible(true);
        write.setAccessible(true);
        Operation<Object> unused = arguments -> { throw new AssertionError("the shared field is never touched"); };

        assertTrue((boolean) read.invoke(mixin, null, unused));
        write.invoke(mixin, null, false, unused);
        assertFalse((boolean) read.invoke(mixin, null, unused), "suppressed inside this thread's getBlockSignal");
        AtomicBoolean other = new AtomicBoolean();
        Thread island = new Thread(() -> {
            try {
                other.set((boolean) read.invoke(mixin, null, unused));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        island.start();
        island.join();
        assertTrue(other.get(), "another island still sees the wire's power");
        write.invoke(mixin, null, true, unused);
        assertTrue((boolean) read.invoke(mixin, null, unused));
    }

    @Test
    void vanillaReadsAndWritesMatchTheWrappedPoints() throws Exception {
        ClassNode node = new ClassNode();
        try (var input = RedstoneWireBlock.class.getResourceAsStream("RedstoneWireBlock.class")) {
            new ClassReader(input.readAllBytes()).accept(node, 0);
        }
        int reads = 0;
        int writes = 0;
        for (var method : node.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field && field.name.equals("shouldSignal")) {
                    if (field.getOpcode() == Opcodes.GETFIELD) {
                        assertTrue(method.name.equals("getSignal") || method.name.equals("getDirectSignal")
                                || method.name.equals("isSignalSource"), method.name);
                        reads++;
                    } else if (field.getOpcode() == Opcodes.PUTFIELD && !method.name.equals("<init>")) {
                        assertEquals("getBlockSignal", method.name);
                        writes++;
                    }
                }
            }
        }
        assertEquals(3, reads);
        assertEquals(2, writes);
    }
}
