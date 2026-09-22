package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.server.MinecraftServerMixin;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.thread.BlockableEventLoop;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the physical-Main boundary that bypasses vanilla's task catch-and-log. */
class MainExecutorFailStopWiringTest {

    @Test
    void stickyFatalIsCheckedBeforeMinecraftServerPollsAnotherTask() throws Exception {
        MethodNode hook = method(readClass(MinecraftServerMixin.class),
                "fabrolia$failStopBeforeNextMainTask",
                "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V");
        AnnotationNode inject = annotation(hook,
                "Lorg/spongepowered/asm/mixin/injection/Inject;");

        assertNotNull(inject);
        assertEquals(List.of("pollTask()Z"), annotationValue(inject, "method"));
        assertEquals(1, annotationValue(inject, "require"));
        Object rawAt = annotationValue(inject, "at");
        AnnotationNode at = rawAt instanceof List<?> values
                ? (AnnotationNode) values.getFirst()
                : (AnnotationNode) rawAt;
        assertNotNull(at);
        assertEquals("HEAD", annotationValue(at, "value"));
        assertEquals(1, callCount(hook, RegionScheduler.class, "throwWorkerFailureIfAny"));
    }

    @Test
    void vanillaDrainDispatchesThroughTheOverriddenPollBoundary() throws Exception {
        ClassNode server = readClass(MinecraftServer.class);
        MethodNode serverPoll = method(server, "pollTask", "()Z");
        assertTrue((serverPoll.access & Opcodes.ACC_STATIC) == 0);

        MethodNode runAll = method(readClass(BlockableEventLoop.class), "runAllTasks", "()V");
        assertEquals(1, callCount(runAll, BlockableEventLoop.class, "pollTask"));
    }

    private static Object annotationValue(AnnotationNode annotation, String key) {
        List<Object> values = annotation.values;
        if (values == null) return null;
        for (int i = 0; i < values.size(); i += 2) {
            if (key.equals(values.get(i))) return values.get(i + 1);
        }
        return null;
    }

    private static AnnotationNode annotation(MethodNode method, String descriptor) {
        if (method.visibleAnnotations != null) {
            for (AnnotationNode annotation : method.visibleAnnotations) {
                if (descriptor.equals(annotation.desc)) return annotation;
            }
        }
        if (method.invisibleAnnotations != null) {
            for (AnnotationNode annotation : method.invisibleAnnotations) {
                if (descriptor.equals(annotation.desc)) return annotation;
            }
        }
        return null;
    }

    private static int callCount(MethodNode method, Class<?> owner, String name) {
        String internalOwner = Type.getInternalName(owner);
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && internalOwner.equals(call.owner)
                    && name.equals(call.name)) {
                count++;
            }
        }
        return count;
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .filter(method -> name.equals(method.name) && descriptor.equals(method.desc))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        owner.name + " is missing " + name + descriptor));
    }

    private static ClassNode readClass(Class<?> type) throws IOException {
        String resource = "/" + Type.getInternalName(type) + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
