package com.axalotl.fabrolia.common.regions;

import net.minecraft.util.thread.BlockableEventLoop;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the declaration-level exact-once guard and its complete causal proof. */
final class RegionMainBlockingWaitGuardWiringTest {
    private static final String MIXIN =
            "com/axalotl/fabrolia/common/mixin/server/"
                    + "BlockableEventLoopMainWaitGuardMixin";
    private static final String GUARD =
            "com/axalotl/fabrolia/common/regions/RegionMainBlockingWaitGuard";
    private static final String SCHEDULER =
            "com/axalotl/fabrolia/common/regions/RegionScheduler";
    private static final String POOL =
            "com/axalotl/fabrolia/common/regions/RegionWorkerPool";
    private static final String MINECRAFT_SERVER =
            "net/minecraft/server/MinecraftServer";

    @Test
    void declarationGuardMixinIsRegistered() throws Exception {
        try (InputStream input = RegionMainBlockingWaitGuardWiringTest.class
                .getClassLoader().getResourceAsStream("fabrolia.common.mixins.json")) {
            assertNotNull(input, "fabrolia.common.mixins.json");
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("\"server.BlockableEventLoopMainWaitGuardMixin\""));
        }
    }

    @Test
    void vanilla262DeclaresTheOneRunnableBlockingWaitOnBlockableEventLoop()
            throws Exception {
        ClassNode loop = readClass(BlockableEventLoop.class);
        MethodNode executeBlocking = uniqueMethod(
                loop, "executeBlocking", "(Ljava/lang/Runnable;)V");
        int sameThread = callIndex(executeBlocking, loop.name,
                "isSameThread", "()Z");
        int submit = callIndex(executeBlocking, loop.name,
                "submitAsync",
                "(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;");
        int join = callIndex(executeBlocking, "java/util/concurrent/CompletableFuture",
                "join", "()Ljava/lang/Object;");
        int direct = callIndex(executeBlocking, "java/lang/Runnable",
                "run", "()V");
        assertTrue(sameThread >= 0 && submit > sameThread && join > submit
                        && direct > join,
                "vanilla must retain its off-thread submit/join and same-thread direct branch");
        assertEquals(1, calls(executeBlocking, loop.name, "isSameThread", "()Z"));
        assertEquals(1, calls(executeBlocking, loop.name, "submitAsync",
                "(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;"));
        assertEquals(1, calls(executeBlocking,
                "java/util/concurrent/CompletableFuture", "join",
                "()Ljava/lang/Object;"));
        assertEquals(1, calls(executeBlocking,
                "java/lang/Runnable", "run", "()V"));
        assertEquals(0, executeBlocking.access & Opcodes.ACC_SYNCHRONIZED);
        assertEquals(0, opcodeCount(executeBlocking, Opcodes.MONITORENTER)
                + opcodeCount(executeBlocking, Opcodes.MONITOREXIT));
    }

    @Test
    void mixinFiltersMinecraftServerAndFailsBeforeItsSingleOriginalCall()
            throws Exception {
        ClassNode mixin = readClass(MIXIN);
        AnnotationNode target = annotation(mixin.visibleAnnotations,
                mixin.invisibleAnnotations,
                "Lorg/spongepowered/asm/mixin/Mixin;");
        @SuppressWarnings("unchecked")
        List<Type> targets = (List<Type>) annotationValues(target).get("value");
        assertEquals(List.of(Type.getType(BlockableEventLoop.class)), targets);

        MethodNode handler = uniqueMethod(mixin,
                "fabrolia$failBeforeCausalServerMainWait",
                "(Ljava/lang/Runnable;"
                        + "Lorg/spongepowered/asm/mixin/injection/callback/"
                        + "CallbackInfo;)V");
        assertTrue((handler.access & Opcodes.ACC_PRIVATE) != 0,
                "mixin handler must remain private");
        assertEquals(0, handler.access & Opcodes.ACC_STATIC);

        AnnotationNode inject = annotation(handler.visibleAnnotations,
                handler.invisibleAnnotations,
                "Lorg/spongepowered/asm/mixin/injection/Inject;");
        Map<String, Object> values = annotationValues(inject);
        assertEquals(List.of("executeBlocking(Ljava/lang/Runnable;)V"),
                values.get("method"));
        assertEquals(1, values.get("require"));
        assertEquals(1, values.get("allow"));
        @SuppressWarnings("unchecked")
        List<AnnotationNode> points = (List<AnnotationNode>) values.get("at");
        assertEquals(1, points.size());
        assertEquals("HEAD", annotationValues(points.getFirst()).get("value"));

        assertEquals(1, typeOpcodeCount(handler, Opcodes.INSTANCEOF,
                MINECRAFT_SERVER), "only MinecraftServer receivers are guarded");
        int sameThread = callIndex(handler, MINECRAFT_SERVER,
                "isSameThread", "()Z");
        int guard = callIndex(handler, GUARD,
                "failIfCurrentThreadParticipates", "(Ljava/lang/String;)V");
        assertTrue(sameThread >= 0 && guard > sameThread,
                "vanilla-same-thread/causal proof must run at method HEAD");
        assertEquals(1, calls(handler, MINECRAFT_SERVER,
                "isSameThread", "()Z"),
                "A5's authenticated logical actor must retain vanilla's inline branch");
        assertEquals(1, calls(handler, GUARD,
                "failIfCurrentThreadParticipates", "(Ljava/lang/String;)V"));
        assertEquals(0, handler.tryCatchBlocks.size(),
                "the wait guard must not catch, retry, or suppress failures");
    }

    @Test
    void guardPublishesOneExactWinnerAndReadsEveryParticipationWitness()
            throws Exception {
        ClassNode guardClass = readClass(GUARD);
        MethodNode fail = uniqueMethod(guardClass,
                "failIfCurrentThreadParticipates", "(Ljava/lang/String;)V");
        int active = callIndex(fail, SCHEDULER, "isActive", "()Z");
        int classify = callIndex(fail, GUARD,
                "currentThreadParticipates", "()Z");
        int publish = callIndex(fail, SCHEDULER,
                "publishFatalAndSelectWinner",
                "(Ljava/lang/Throwable;)Ljava/lang/Throwable;");
        int rethrow = callIndex(fail,
                "com/axalotl/fabrolia/common/regions/FailStops", "rethrow",
                "(Ljava/lang/Throwable;)V");
        assertTrue(active >= 0 && classify > active && publish > classify
                        && rethrow > publish);
        assertEquals(1, calls(fail, SCHEDULER,
                "publishFatalAndSelectWinner",
                "(Ljava/lang/Throwable;)Ljava/lang/Throwable;"));
        assertEquals(1, typeOpcodeCount(fail, Opcodes.NEW,
                "java/lang/IllegalStateException"));
        assertEquals(0, fail.tryCatchBlocks.size(),
                "fatal publication is not a catch-and-continue policy");

        MethodNode classifyMethod = uniqueMethod(guardClass,
                "currentThreadParticipates", "()Z");
        assertEquals(1, fieldCount(classifyMethod, Opcodes.GETFIELD,
                "com/axalotl/fabrolia/common/regions/RegionWorkerThread",
                "currentRegion"));
        assertEquals(1, fieldCount(classifyMethod, Opcodes.GETFIELD,
                "com/axalotl/fabrolia/common/regions/RegionWorkerThread",
                "currentDomain"));
        assertEquals(1, calls(classifyMethod, POOL,
                "currentThreadHasLiveCausalStep", "()Z"));
        assertEquals(1, calls(classifyMethod, SCHEDULER,
                "currentThreadHoldsLiveIslandClaim", "()Z"));

        MethodNode causal = uniqueMethod(readClass(POOL),
                "currentThreadHasLiveCausalStep", "()Z");
        assertEquals(1, fieldCount(causal, Opcodes.GETSTATIC, POOL,
                "CAUSAL_STEP"));
        assertEquals(1, opcodeCount(causal, Opcodes.MONITORENTER),
                "StepToken.active must be validated under its owner lifecycle lock");

        MethodNode claims = uniqueMethod(readClass(SCHEDULER),
                "currentThreadHoldsLiveIslandClaim", "()Z");
        assertEquals(1, fieldCount(claims, Opcodes.GETFIELD,
                "com/axalotl/fabrolia/common/regions/IslandRegistry$PIsland",
                "unitClaim"));
        assertEquals(1, fieldCount(claims, Opcodes.GETFIELD,
                "com/axalotl/fabrolia/common/regions/IslandRegistry$PIsland",
                "claimHolder"));
    }

    private static AnnotationNode annotation(
            List<AnnotationNode> visible, List<AnnotationNode> invisible,
            String desc) {
        AnnotationNode found = findAnnotation(visible, desc);
        if (found == null) found = findAnnotation(invisible, desc);
        if (found != null) return found;
        throw new AssertionError("annotation not found: " + desc);
    }

    private static AnnotationNode findAnnotation(
            List<AnnotationNode> annotations, String desc) {
        if (annotations == null) return null;
        for (AnnotationNode annotation : annotations) {
            if (annotation.desc.equals(desc)) return annotation;
        }
        return null;
    }

    private static Map<String, Object> annotationValues(AnnotationNode annotation) {
        Map<String, Object> values = new HashMap<>();
        if (annotation.values == null) return values;
        for (int i = 0; i < annotation.values.size(); i += 2) {
            values.put((String) annotation.values.get(i),
                    annotation.values.get(i + 1));
        }
        return values;
    }

    private static MethodNode uniqueMethod(
            ClassNode owner, String name, String desc) {
        List<MethodNode> matches = owner.methods.stream()
                .filter(method -> method.name.equals(name) && method.desc.equals(desc))
                .toList();
        assertEquals(1, matches.size(), owner.name + "." + name + desc);
        return matches.getFirst();
    }

    private static int calls(
            MethodNode method, String owner, String name, String desc) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)
                    && call.desc.equals(desc)) count++;
        }
        return count;
    }

    private static int callIndex(
            MethodNode method, String owner, String name, String desc) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)
                    && call.desc.equals(desc)) return index;
            index++;
        }
        return -1;
    }

    private static int fieldCount(
            MethodNode method, int opcode, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == opcode && field.owner.equals(owner)
                    && field.name.equals(name)) count++;
        }
        return count;
    }

    private static int typeOpcodeCount(
            MethodNode method, int opcode, String type) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode typed
                    && typed.getOpcode() == opcode && typed.desc.equals(type)) count++;
        }
        return count;
    }

    private static int opcodeCount(MethodNode method, int opcode) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) count++;
        }
        return count;
    }

    private static ClassNode readClass(Class<?> type) throws Exception {
        String resource = "/" + Type.getInternalName(type) + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return read(input.readAllBytes());
        }
    }

    private static ClassNode readClass(String internalName) throws Exception {
        try (InputStream input = RegionMainBlockingWaitGuardWiringTest.class
                .getClassLoader().getResourceAsStream(internalName + ".class")) {
            assertNotNull(input, internalName);
            return read(input.readAllBytes());
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode result = new ClassNode();
        new ClassReader(bytes).accept(result,
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return result;
    }
}
