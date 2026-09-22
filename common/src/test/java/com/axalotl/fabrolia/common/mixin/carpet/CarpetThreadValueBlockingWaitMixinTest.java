package com.axalotl.fabrolia.common.mixin.carpet;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact-artifact and fail-stop wiring proof for Carpet's opaque task waits. */
class CarpetThreadValueBlockingWaitMixinTest {

    private static final String MIXIN =
            "com/axalotl/fabrolia/common/mixin/carpet/CarpetThreadValueBlockingWaitMixin";
    private static final String THREAD_VALUE = "carpet/script/value/ThreadValue";
    private static final String CARPET_VALUE = "Lcarpet/script/value/Value;";
    private static final String CALLBACK =
            "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
    private static final String GUARD =
            "com/axalotl/fabrolia/common/regions/RegionMainBlockingWaitGuard";
    private static final String GUARD_METHOD = "failIfCurrentThreadParticipates";
    private static final String GUARD_DESC = "(Ljava/lang/String;)V";
    private static final String EXPECTED_CARPET_SHA256 =
            "f6ada912af65c91536d4b0d80adf26cc438253252ddaf259f2c6617ae471311c";

    @Test
    void pseudoMixinIsRegistered() throws Exception {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("fabrolia.common.mixins.json")) {
            assertNotNull(in, "fabrolia.common.mixins.json");
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("\"carpet.CarpetThreadValueBlockingWaitMixin\""));
        }
    }

    @Test
    void pseudoMixinGuardsBothTypedBlockingEntrypointsAtHead() throws Exception {
        ClassNode mixin = readClass(MIXIN);
        assertTrue(hasAnnotation(mixin, "Lorg/spongepowered/asm/mixin/Pseudo;"));
        AnnotationNode target = annotation(mixin, "Lorg/spongepowered/asm/mixin/Mixin;");
        assertEquals(List.of("carpet.script.value.ThreadValue"),
                annotationValue(target, "targets"));
        assertEquals(Boolean.FALSE, annotationValue(target, "remap"));

        assertHeadGuard(mixin, "fabrolia$guardJoin",
                "join()" + CARPET_VALUE, "Carpet ThreadValue.join()");
        assertHeadGuard(mixin, "fabrolia$guardAwait",
                "next()" + CARPET_VALUE, "Carpet ThreadValue.next()/task_await");
    }

    @Test
    void exactCarpetArtifactRetainsTheAuditedWaitDescriptorsAndBridge() throws Exception {
        Path artifact = carpetArtifact();
        Assumptions.assumeTrue(artifact != null,
                "exact Carpet artifact is optional outside the full-modpack workspace");
        assertEquals(EXPECTED_CARPET_SHA256, sha256(artifact),
                "a new Carpet artifact requires a fresh opaque-wait audit");

        try (ZipFile zip = new ZipFile(artifact.toFile())) {
            ClassNode threadValue = readJarClass(zip, THREAD_VALUE);
            MethodNode join = method(threadValue, "join", "()" + CARPET_VALUE);
            MethodNode next = method(threadValue, "next", "()" + CARPET_VALUE);
            MethodNode bridge = method(threadValue, "next", "()Ljava/lang/Object;");

            assertPublicInstance(join);
            assertPublicInstance(next);
            assertEquals(1, calls(join, "java/util/concurrent/CompletableFuture",
                    "get", "()Ljava/lang/Object;"));
            assertEquals(0, calls(join, "java/util/concurrent/CompletableFuture",
                    "getNow", "(Ljava/lang/Object;)Ljava/lang/Object;"));
            assertEquals(1, calls(next, "java/util/Deque", "wait", "(J)V"));

            assertTrue((bridge.access & Opcodes.ACC_PUBLIC) != 0);
            assertTrue((bridge.access & Opcodes.ACC_BRIDGE) != 0);
            assertTrue((bridge.access & Opcodes.ACC_SYNTHETIC) != 0);
            assertEquals(1, calls(bridge, THREAD_VALUE, "next", "()" + CARPET_VALUE),
                    "the iterator bridge must continue through the one guarded typed method");
            assertEquals(0, calls(bridge, "java/util/Deque", "wait", "(J)V"),
                    "guarding the bridge too would double-check one iterator call");
        }
    }

    private static void assertHeadGuard(ClassNode mixin, String handlerName,
                                        String targetMethod, String operation) {
        MethodNode handler = method(mixin, handlerName, "(L" + CALLBACK + ";)V");
        assertEquals(Opcodes.ACC_PRIVATE, handler.access
                        & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE));
        assertFalse((handler.access & Opcodes.ACC_STATIC) != 0);

        AnnotationNode inject = annotation(handler,
                "Lorg/spongepowered/asm/mixin/injection/Inject;");
        assertEquals(List.of(targetMethod), annotationValue(inject, "method"));
        assertEquals(Boolean.FALSE, annotationValue(inject, "remap"));
        assertEquals(1, annotationValue(inject, "require"));
        assertEquals(1, annotationValue(inject, "allow"));
        assertEquals(null, annotationValue(inject, "cancellable"),
                "a returning guard must fall through to Carpet's original exactly once");

        AnnotationNode at = firstNestedAnnotation(inject, "at");
        assertEquals("HEAD", annotationValue(at, "value"),
                "a rejected wait must fail before any Carpet instruction executes");
        assertEquals(1, calls(handler, GUARD, GUARD_METHOD, GUARD_DESC));
        assertEquals(List.of(operation), stringConstants(handler));
        assertTrue(handler.tryCatchBlocks.isEmpty(),
                "the selected failure must escape; the adapter may not suppress it");
        assertEquals(0, opcodeCount(handler, Opcodes.ATHROW),
                "the guard is the sole failure selector");
        assertEquals(0, branchCount(handler),
                "the adapter may not retry, defer, replay, or skip a permitted original");
        assertEquals(1, opcodeCount(handler, Opcodes.RETURN));
    }

    private static void assertPublicInstance(MethodNode method) {
        assertTrue((method.access & Opcodes.ACC_PUBLIC) != 0);
        assertFalse((method.access & Opcodes.ACC_STATIC) != 0);
    }

    private static Path carpetArtifact() {
        String configured = System.getProperty("fabrolia.carpetArtifact");
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured).toAbsolutePath().normalize();
            return Files.isRegularFile(path) ? path : null;
        }
        List<Path> candidates = List.of(
                Path.of("runtime-smoke.NTEH8H/server/mods/fabric-carpet-26.2+v260616.jar"),
                Path.of("../runtime-smoke.NTEH8H/server/mods/fabric-carpet-26.2+v260616.jar"),
                Path.of("/home/furrymileon/.local/share/atlauncher/servers/"
                        + "Minecraft262withFabric/mods/fabric-carpet-26.2+v260616.jar"));
        return candidates.stream().map(path -> path.toAbsolutePath().normalize())
                .filter(Files::isRegularFile).findFirst().orElse(null);
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static ClassNode readJarClass(ZipFile zip, String internalName) throws Exception {
        ZipEntry entry = zip.getEntry(internalName + ".class");
        assertNotNull(entry, "missing Carpet class " + internalName);
        try (InputStream in = zip.getInputStream(entry)) {
            return readClass(in.readAllBytes());
        }
    }

    private static ClassNode readClass(String internalName) throws Exception {
        try (InputStream in = CarpetThreadValueBlockingWaitMixinTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            assertNotNull(in, "missing class " + internalName);
            return readClass(in.readAllBytes());
        }
    }

    private static ClassNode readClass(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode type, String name, String desc) {
        return type.methods.stream().filter(candidate -> name.equals(candidate.name)
                        && desc.equals(candidate.desc)).findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing " + type.name + "." + name + desc));
    }

    private static AnnotationNode annotation(ClassNode type, String desc) {
        AnnotationNode visible = annotation(type.visibleAnnotations, desc);
        return visible != null ? visible : annotation(type.invisibleAnnotations, desc);
    }

    private static AnnotationNode annotation(MethodNode method, String desc) {
        AnnotationNode visible = annotation(method.visibleAnnotations, desc);
        return visible != null ? visible : annotation(method.invisibleAnnotations, desc);
    }

    private static AnnotationNode annotation(List<AnnotationNode> annotations, String desc) {
        if (annotations == null) return null;
        return annotations.stream().filter(candidate -> desc.equals(candidate.desc))
                .findFirst().orElse(null);
    }

    private static boolean hasAnnotation(ClassNode type, String desc) {
        return annotation(type, desc) != null;
    }

    private static Object annotationValue(AnnotationNode annotation, String name) {
        assertNotNull(annotation, "missing annotation for " + name);
        if (annotation.values == null) return null;
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static AnnotationNode firstNestedAnnotation(AnnotationNode annotation, String name) {
        Object value = annotationValue(annotation, name);
        if (value instanceof AnnotationNode nested) return nested;
        if (value instanceof List<?> list && !list.isEmpty()
                && list.getFirst() instanceof AnnotationNode nested) {
            return nested;
        }
        throw new AssertionError("missing nested annotation " + name);
    }

    private static int calls(MethodNode method, String owner, String name, String desc) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && desc.equals(call.desc)) {
                count++;
            }
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

    private static int branchCount(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            int opcode = instruction.getOpcode();
            if ((opcode >= Opcodes.IFEQ && opcode <= Opcodes.JSR)
                    || opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL
                    || opcode == Opcodes.TABLESWITCH || opcode == Opcodes.LOOKUPSWITCH) {
                count++;
            }
        }
        return count;
    }

    private static List<String> stringConstants(MethodNode method) {
        return method.instructions.iterator().hasNext()
                ? java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(
                                method.instructions.iterator(), 0), false)
                        .filter(LdcInsnNode.class::isInstance)
                        .map(LdcInsnNode.class::cast)
                        .map(instruction -> instruction.cst)
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .toList()
                : List.of();
    }
}
