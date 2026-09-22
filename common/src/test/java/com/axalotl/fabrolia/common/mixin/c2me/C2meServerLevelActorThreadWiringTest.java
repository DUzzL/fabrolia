package com.axalotl.fabrolia.common.mixin.c2me;

import com.axalotl.fabrolia.common.mixin.utils.MixinCanceller;
import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the complete C2ME entity-id wrapper we replace, the vanilla 26.2 seam,
 * and the packaged Fabrolia compatibility pair.  The external-artifact test is
 * enabled with {@code FABROLIA_C2ME_FIXES_WORLDGEN_JAR} and intentionally uses
 * Fabric Loader's processed runtime module rather than the nested distribution
 * jar: those are the bytes Mixin actually consumes on the production stack.
 */
class C2meServerLevelActorThreadWiringTest {

    private static final String UPSTREAM_MIXIN =
            "com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading.MixinServerWorld";
    private static final String UPSTREAM_CLASS = UPSTREAM_MIXIN.replace('.', '/') + ".class";
    private static final String UPSTREAM_CONFIG =
            "c2me-fixes-worldgen-threading-issues.mixins.json";
    private static final String UPSTREAM_HANDLER_DESC =
            "(Lnet/minecraft/server/level/ServerChunkCache;I"
                    + "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)Z";
    private static final String CHUNK_SOURCE =
            "net/minecraft/server/level/ServerChunkCache";
    private static final String COLLISION_DESC = "(I)Z";
    private static final String C2ME_WORLD_ACCESSOR =
            "com/ishland/c2me/base/mixin/access/IWorld";
    private static final String THREAD_DESC = "()Ljava/lang/Thread;";

    private static final String RUNTIME_MODULE_SHA256 =
            "e9ff4b63d49f3db9ae9389c892761af6852fa5f7a1a2bc957f16026b3f32362c";
    private static final String RUNTIME_MIXIN_CLASS_SHA256 =
            "faf2125b4410eda27f7431325a2310e9bcc46c61c802d76aeadd9d1cc003e8a1";
    private static final String RUNTIME_MIXIN_CONFIG_SHA256 =
            "b232e961bff0dc3638d06b323f2347a21344bb5d4cc8c002e606060a4fa850c0";

    @Test
    void vanilla262RetainsExactlyOneEntityIdCollisionSeam() throws Exception {
        ClassNode level = readClass(net.minecraft.server.level.ServerLevel.class);
        MethodNode nextId = method(level, "getNextEntityId", "()I");
        assertEquals(1, callCount(nextId, CHUNK_SOURCE, "hasEntityWithId", COLLISION_DESC),
                "the exact vanilla seam must neither disappear nor become ambiguous");
    }

    @Test
    void processedRuntimeC2meHasExactlyTheOneAuditedHook() throws Exception {
        String configured = System.getenv("FABROLIA_C2ME_FIXES_WORLDGEN_JAR");
        Assumptions.assumeTrue(configured != null && !configured.isBlank(),
                "set FABROLIA_C2ME_FIXES_WORLDGEN_JAR to pin the processed runtime module");
        Path module = Path.of(configured);
        assertTrue(Files.isRegularFile(module), module.toString());
        assertEquals(RUNTIME_MODULE_SHA256, sha256(Files.readAllBytes(module)));

        try (JarFile jar = new JarFile(module.toFile())) {
            byte[] mixinBytes = entryBytes(jar, UPSTREAM_CLASS);
            byte[] configBytes = entryBytes(jar, UPSTREAM_CONFIG);
            assertEquals(RUNTIME_MIXIN_CLASS_SHA256, sha256(mixinBytes));
            assertEquals(RUNTIME_MIXIN_CONFIG_SHA256, sha256(configBytes));

            String metadata = new String(entryBytes(jar, "fabric.mod.json"),
                    StandardCharsets.UTF_8);
            assertTrue(metadata.contains("\"id\": \"c2me-fixes-worldgen-threading-issues\""));
            assertTrue(metadata.contains("\"version\": \"0.4.2-alpha.0.41+26.2\""));

            String config = new String(configBytes, StandardCharsets.UTF_8);
            assertEquals(1, occurrences(config, "\"threading.MixinServerWorld\""));
            assertUpstreamSoleHook(readClass(mixinBytes));
        }
    }

    @Test
    void replacementReproducesPhysicalBranchAndAddsOnlyAuthenticatedActor() throws Exception {
        ClassNode accessor = readClass(FabroliaC2meLevelThreadAccessor.class);
        assertMixinTarget(accessor, "Lnet/minecraft/world/level/Level;");
        MethodNode physicalThread = method(accessor,
                "fabrolia$getPhysicalLevelThread", THREAD_DESC);
        assertTrue((physicalThread.access & Opcodes.ACC_ABSTRACT) != 0);
        AnnotationNode accessorAnnotation = annotation(physicalThread,
                "Lorg/spongepowered/asm/mixin/gen/Accessor;");
        assertNotNull(accessorAnnotation);
        assertEquals("thread", annotationValue(accessorAnnotation, "value"));

        ClassNode replacement = readClass(FabroliaC2meServerLevelActorThreadMixin.class);
        assertMixinTarget(replacement, "Lnet/minecraft/server/level/ServerLevel;");
        MethodNode handler = method(replacement,
                "fabrolia$c2meActorAwareEntityIdCollisionCheck", UPSTREAM_HANDLER_DESC);
        AnnotationNode wrap = annotation(handler,
                "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");
        assertNotNull(wrap);
        assertSelector(wrap, "getNextEntityId()I");
        assertEquals(1, annotationValue(wrap, "require"));
        assertEquals(1, annotationValue(wrap, "allow"));
        AnnotationNode at = atAnnotation(wrap);
        assertEquals("INVOKE", annotationValue(at, "value"));
        assertEquals("Lnet/minecraft/server/level/ServerChunkCache;hasEntityWithId(I)Z",
                annotationValue(at, "target"));

        assertEquals(1, callCount(handler, internal(FabroliaC2meLevelThreadAccessor.class),
                "fabrolia$getPhysicalLevelThread", THREAD_DESC));
        assertEquals(1, callCount(handler, "java/lang/Thread", "currentThread", THREAD_DESC));
        assertEquals(1, callCount(handler, internal(RegionalPlayerEntityTicksV2.class),
                "inExactPlayerActor", "()Z"));
        assertEquals(1, callCount(handler,
                "com/llamalad7/mixinextras/injector/wrapoperation/Operation",
                "call", "([Ljava/lang/Object;)Ljava/lang/Object;"));

        int physical = callIndex(handler, internal(FabroliaC2meLevelThreadAccessor.class),
                "fabrolia$getPhysicalLevelThread", THREAD_DESC);
        int current = callIndex(handler, "java/lang/Thread", "currentThread", THREAD_DESC);
        int actor = callIndex(handler, internal(RegionalPlayerEntityTicksV2.class),
                "inExactPlayerActor", "()Z");
        int original = callIndex(handler,
                "com/llamalad7/mixinextras/injector/wrapoperation/Operation",
                "call", "([Ljava/lang/Object;)Ljava/lang/Object;");
        assertTrue(physical >= 0 && current > physical && actor > current && original > actor,
                "physical comparison and authenticated actor gate must precede the original call");
        assertEquals(0, c2meTypeReferenceCount(accessor) + c2meTypeReferenceCount(replacement),
                "the replacement must not link against C2ME implementation/accessor types");
    }

    @Test
    void cancellationAndRegistrationAreExactAndC2meGated() throws Exception {
        String accessorShort = "c2me.FabroliaC2meLevelThreadAccessor";
        String wrapperShort = "c2me.FabroliaC2meServerLevelActorThreadMixin";
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("fabrolia.common.mixins.json")) {
            assertNotNull(input);
            String config = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(1, occurrences(config, "\"" + accessorShort + "\""));
            assertEquals(1, occurrences(config, "\"" + wrapperShort + "\""));
        }

        String prefix = "com.axalotl.fabrolia.common.mixin.";
        List<String> levelTarget = List.of("net.minecraft.world.level.Level");
        List<String> serverLevelTarget = List.of("net.minecraft.server.level.ServerLevel");
        MixinCanceller absent = new MixinCanceller();
        assertTrue(absent.shouldCancel(levelTarget, prefix + accessorShort));
        assertTrue(absent.shouldCancel(serverLevelTarget, prefix + wrapperShort));

        MixinCanceller present = new MixinCanceller();
        assertTrue(present.shouldCancel(serverLevelTarget, UPSTREAM_MIXIN),
                "the audited upstream provider must be replaced, not stacked");
        assertFalse(present.shouldCancel(levelTarget, prefix + accessorShort));
        assertFalse(present.shouldCancel(serverLevelTarget, prefix + wrapperShort));
        assertFalse(present.shouldCancel(serverLevelTarget, UPSTREAM_MIXIN + "Extra"),
                "the cancellation must not expand by prefix/suffix");
        assertFalse(present.shouldCancel(serverLevelTarget,
                "com.ishland.c2me.opts.scheduling.mixin.shutdown.MixinServerWorld"),
                "unrelated C2ME ServerLevel mixins must remain active");
    }

    private static void assertUpstreamSoleHook(ClassNode upstream) {
        assertMixinTarget(upstream, "Lnet/minecraft/server/level/ServerLevel;");
        assertTrue(upstream.fields.isEmpty(), "the cancelled class must contribute no state");
        assertEquals(2, upstream.methods.size(),
                "the cancelled class must contain only its constructor and sole handler");
        assertEquals(1, upstream.methods.stream().filter(method -> !method.name.equals("<init>"))
                .count());

        MethodNode handler = method(upstream, "wrapIsTrackingEntity", UPSTREAM_HANDLER_DESC);
        AnnotationNode wrap = annotation(handler,
                "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");
        assertNotNull(wrap);
        assertSelector(wrap, "getNextEntityId");
        AnnotationNode at = atAnnotation(wrap);
        assertEquals("INVOKE", annotationValue(at, "value"));
        assertEquals("Lnet/minecraft/server/level/ServerChunkCache;hasEntityWithId(I)Z",
                annotationValue(at, "target"));

        assertEquals(1, callCount(handler, C2ME_WORLD_ACCESSOR, "getThread", THREAD_DESC));
        assertEquals(1, callCount(handler, "java/lang/Thread", "currentThread", THREAD_DESC));
        assertEquals(1, opcodeCount(handler, Opcodes.IF_ACMPNE));
        assertEquals(1, callCount(handler,
                "com/llamalad7/mixinextras/injector/wrapoperation/Operation",
                "call", "([Ljava/lang/Object;)Ljava/lang/Object;"));
        assertEquals(1, upstream.methods.stream().mapToInt(method ->
                injectionAnnotationCount(method)).sum(),
                "cancelling the class must cancel exactly one injection and no hidden hooks");
    }

    private static int injectionAnnotationCount(MethodNode method) {
        int count = 0;
        if (method.visibleAnnotations != null) {
            count += (int) method.visibleAnnotations.stream()
                    .filter(annotation -> annotation.desc.contains("/injector/")).count();
        }
        if (method.invisibleAnnotations != null) {
            count += (int) method.invisibleAnnotations.stream()
                    .filter(annotation -> annotation.desc.contains("/injector/")).count();
        }
        return count;
    }

    private static int c2meTypeReferenceCount(ClassNode type) {
        int count = type.name.contains("com/ishland/c2me/") ? 1 : 0;
        if (type.superName != null && type.superName.contains("com/ishland/c2me/")) count++;
        for (String iface : type.interfaces) {
            if (iface.contains("com/ishland/c2me/")) count++;
        }
        for (MethodNode method : type.methods) {
            if (method.desc.contains("com/ishland/c2me/")) count++;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && (call.owner.contains("com/ishland/c2me/")
                        || call.desc.contains("com/ishland/c2me/"))) count++;
            }
        }
        return count;
    }

    private static byte[] entryBytes(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name);
        assertNotNull(entry, name);
        try (InputStream input = jar.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }

    private static ClassNode readClass(Class<?> type) throws Exception {
        try (InputStream input = type.getResourceAsStream("/" + internal(type) + ".class")) {
            assertNotNull(input, type.getName());
            return readClass(input.readAllBytes());
        }
    }

    private static ClassNode readClass(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode type, String name, String desc) {
        return type.methods.stream().filter(method -> method.name.equals(name)
                        && method.desc.equals(desc))
                .findFirst().orElseThrow(() -> new AssertionError(type.name + "." + name + desc));
    }

    private static AnnotationNode annotation(ClassNode type, String desc) {
        AnnotationNode found = annotation(type.visibleAnnotations, desc);
        return found != null ? found : annotation(type.invisibleAnnotations, desc);
    }

    private static AnnotationNode annotation(MethodNode method, String desc) {
        AnnotationNode found = annotation(method.visibleAnnotations, desc);
        return found != null ? found : annotation(method.invisibleAnnotations, desc);
    }

    private static AnnotationNode annotation(List<AnnotationNode> annotations, String desc) {
        if (annotations != null) {
            for (AnnotationNode annotation : annotations) {
                if (annotation.desc.equals(desc)) return annotation;
            }
        }
        return null;
    }

    private static Object annotationValue(AnnotationNode annotation, String key) {
        if (annotation.values == null) return null;
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static AnnotationNode atAnnotation(AnnotationNode injector) {
        Object raw = annotationValue(injector, "at");
        if (raw instanceof AnnotationNode node) return node;
        if (raw instanceof List<?> list && !list.isEmpty()) return (AnnotationNode) list.getFirst();
        throw new AssertionError("missing @At on " + injector.desc);
    }

    private static void assertSelector(AnnotationNode injector, String expected) {
        Object selector = annotationValue(injector, "method");
        if (selector instanceof List<?> list) assertEquals(List.of(expected), list);
        else assertEquals(expected, selector);
    }

    private static void assertMixinTarget(ClassNode mixin, String descriptor) {
        AnnotationNode annotation = annotation(mixin, "Lorg/spongepowered/asm/mixin/Mixin;");
        assertNotNull(annotation);
        assertEquals(List.of(Type.getType(descriptor)), annotationValue(annotation, "value"));
    }

    private static int callIndex(MethodNode method, String owner, String name, String desc) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner)
                    && call.name.equals(name) && call.desc.equals(desc)) return index;
            index++;
        }
        return -1;
    }

    private static int callCount(MethodNode method, String owner, String name, String desc) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner)
                    && call.name.equals(name) && call.desc.equals(desc)) count++;
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

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = 0; (at = text.indexOf(needle, at)) >= 0; at += needle.length()) count++;
        return count;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String internal(Class<?> type) {
        return type.getName().replace('.', '/');
    }
}
