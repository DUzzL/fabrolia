package com.axalotl.fabrolia.common.mixin.lithium;

import com.axalotl.fabrolia.common.mixin.utils.MixinCanceller;
import com.axalotl.fabrolia.common.regions.AsyncIslandPlayerTicks;
import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the exact Lithium 0.26.2 (mc26.3) compile and production seams. */
class LithiumLevelActorThreadWiringTest {

    private static final int DEFAULT_MIXIN_PRIORITY = 1000;
    private static final int FABROLIA_MERGED_METHOD_HOOK_PRIORITY = 2000;
    private static final int FABROLIA_CALLER_HOOK_PRIORITY = 900;
    private static final String RETRIEVAL_CLASS =
            "net/caffeinemc/mods/lithium/mixin/util/block_entity_retrieval/LevelMixin.class";
    private static final String ACCESSOR_CLASS =
            "net/caffeinemc/mods/lithium/mixin/util/accessors/LevelAccessor.class";
    private static final String CHUNK_STATUS_TRACKER_CLASS =
            "net/caffeinemc/mods/lithium/common/world/chunk/ChunkStatusTracker.class";
    private static final String CHUNK_STATUS_TRACKER_TARGET =
            "net.caffeinemc.mods.lithium.common.world.chunk.ChunkStatusTracker";
    private static final String LEVEL_THREAD_ACCESSOR =
            "net/caffeinemc/mods/lithium/mixin/util/accessors/LevelAccessor";
    private static final String LEVEL_THREAD_ACCESSOR_DESC = "()Ljava/lang/Thread;";
    private static final String RETRIEVAL_DESC =
            "(Lnet/minecraft/core/BlockPos;)"
                    + "Lnet/minecraft/world/level/block/entity/BlockEntity;";
    private static final String ACCESSIBLE_DESC =
            "(Lnet/minecraft/server/level/ServerLevel;"
                    + "Lnet/minecraft/world/level/chunk/LevelChunk;)V";
    private static final String INACCESSIBLE_DESC =
            "(Lnet/minecraft/server/level/ServerLevel;"
                    + "Lnet/minecraft/world/level/ChunkPos;)V";
    private static final String RETRIEVAL_CLASS_SHA256 =
            "c69fc289ad1357f8da35fce8ac52b85394046af89660e1e32905561eb07aecc8";
    private static final String ACCESSOR_CLASS_SHA256 =
            "db09026bf5b51d8f31d4abc9bb40f6bbf2cd5f7df463e654003a00b1c1b797d4";
    private static final String CHUNK_STATUS_TRACKER_CLASS_SHA256 =
            "d3184c392722d7d67b74e79774fc8fcc350d312ba6e3515faac720c2fb8a40d0";
    private static final String PRODUCTION_0262_JAR_SHA256 =
            "0be5b17ef9f6e9244cd07b24b227553a73aec0cf93158cf28099f3094c167f1b";

    @Test
    void compileLithium0262HasTheExactAuditedLevelSeams() throws Exception {
        URL retrievalUrl = LithiumLevelActorThreadWiringTest.class.getClassLoader()
                .getResource(RETRIEVAL_CLASS);
        assertNotNull(retrievalUrl, "Lithium LevelMixin must be on the common compile/test classpath");
        byte[] retrieval = read(retrievalUrl);
        URL accessorUrl = LithiumLevelActorThreadWiringTest.class.getClassLoader()
                .getResource(ACCESSOR_CLASS);
        assertNotNull(accessorUrl);
        byte[] accessor = read(accessorUrl);
        URL trackerUrl = LithiumLevelActorThreadWiringTest.class.getClassLoader()
                .getResource(CHUNK_STATUS_TRACKER_CLASS);
        assertNotNull(trackerUrl);
        byte[] tracker = read(trackerUrl);

        assertEquals(RETRIEVAL_CLASS_SHA256, sha256(retrieval));
        assertEquals(ACCESSOR_CLASS_SHA256, sha256(accessor));
        assertEquals(CHUNK_STATUS_TRACKER_CLASS_SHA256, sha256(tracker));
        assertLithiumLevelSeams(retrieval, accessor, tracker);

        assertEquals("jar", retrievalUrl.getProtocol(),
                "the compile Lithium class must retain its Maven artifact identity");
        JarURLConnection connection = (JarURLConnection) retrievalUrl.openConnection();
        try (JarFile jar = new JarFile(Path.of(connection.getJarFileURL().toURI()).toFile())) {
            String metadata = new String(jar.getInputStream(
                    jar.getJarEntry("META-INF/neoforge.mods.toml")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(metadata.contains("version = \"0.26.2\""),
                    "common must compile against the audited Lithium 0.26.2 API");
            assertLithiumConfig(jar);
        }
    }

    @Test
    void productionLithium0262HasTheByteIdenticalAuditedLevelSeams() throws Exception {
        String configured = System.getenv("FABROLIA_LITHIUM_0262_JAR");
        Assumptions.assumeTrue(configured != null && !configured.isBlank(),
                "set FABROLIA_LITHIUM_0262_JAR for the external production-artifact pin");
        Path jarPath = Path.of(configured);
        assertTrue(Files.isRegularFile(jarPath), jarPath.toString());
        assertEquals(PRODUCTION_0262_JAR_SHA256, sha256(Files.readAllBytes(jarPath)));

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            String metadata = new String(jar.getInputStream(jar.getJarEntry("fabric.mod.json"))
                    .readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(metadata.contains("\"version\": \"0.26.2+mc26.3\""));
            byte[] retrieval = jar.getInputStream(jar.getJarEntry(RETRIEVAL_CLASS)).readAllBytes();
            byte[] accessor = jar.getInputStream(jar.getJarEntry(ACCESSOR_CLASS)).readAllBytes();
            byte[] tracker = jar.getInputStream(
                    jar.getJarEntry(CHUNK_STATUS_TRACKER_CLASS)).readAllBytes();
            assertEquals(RETRIEVAL_CLASS_SHA256, sha256(retrieval));
            assertEquals(ACCESSOR_CLASS_SHA256, sha256(accessor));
            assertEquals(CHUNK_STATUS_TRACKER_CLASS_SHA256, sha256(tracker));
            assertLithiumLevelSeams(retrieval, accessor, tracker);
            assertLithiumConfig(jar);
        }
    }

    @Test
    void fabroliaHooksPinThreeRealCallSitesAndNeverTargetTheGeneratedAccessor() throws Exception {
        ClassNode levelHook = readClass(FabroliaLithiumLevelActorThreadMixin.class);
        AnnotationNode mixin = annotation(levelHook, "Lorg/spongepowered/asm/mixin/Mixin;");
        assertNotNull(mixin);
        assertEquals(List.of(Type.getType("Lnet/minecraft/world/level/Level;")),
                annotationValue(mixin, "value"));
        assertEquals(FABROLIA_MERGED_METHOD_HOOK_PRIORITY,
                annotationValue(mixin, "priority"));
        assertTrue(FABROLIA_MERGED_METHOD_HOOK_PRIORITY > DEFAULT_MIXIN_PRIORITY,
                "an injector may not modify a method merged by a higher-priority mixin");

        FieldNode thread = levelHook.fields.stream()
                .filter(field -> field.name.equals("thread")
                        && field.desc.equals("Ljava/lang/Thread;"))
                .findFirst().orElseThrow();
        assertNotNull(annotation(thread, "Lorg/spongepowered/asm/mixin/Shadow;"));
        assertNotNull(annotation(thread, "Lorg/spongepowered/asm/mixin/Final;"));

        MethodNode blockEntityThread = method(levelHook,
                "fabrolia$logicalLoadedBlockEntityThread",
                "(Ljava/lang/Thread;)Ljava/lang/Thread;");
        AnnotationNode expressionHook = annotation(blockEntityThread,
                "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;");
        assertInjector(expressionHook,
                "lithium$getLoadedExistingBlockEntity" + RETRIEVAL_DESC,
                "INVOKE", "Ljava/lang/Thread;currentThread()Ljava/lang/Thread;");
        assertEquals(0, annotationValue(atAnnotation(expressionHook), "ordinal"));
        int blockEntityActor = callIndex(blockEntityThread,
                internal(RegionalPlayerEntityTicksV2.class), "inExactPlayerActor", "()Z");
        int autonomousOwner = callIndex(blockEntityThread,
                internal(AsyncIslandPlayerTicks.class), "inOwnerTurn", "()Z");
        int physicalThread = fieldIndex(blockEntityThread, Opcodes.GETFIELD,
                internal(FabroliaLithiumLevelActorThreadMixin.class), "thread");
        assertEquals(1, callCount(blockEntityThread,
                internal(RegionalPlayerEntityTicksV2.class), "inExactPlayerActor", "()Z"));
        assertEquals(1, callCount(blockEntityThread,
                internal(AsyncIslandPlayerTicks.class), "inOwnerTurn", "()Z"));
        assertTrue(blockEntityActor >= 0 && autonomousOwner > blockEntityActor
                        && physicalThread > autonomousOwner,
                "exact v2 OR autonomous A5 ownership must substitute Level.thread for "
                        + "Lithium's loaded-block-entity cache path");

        assertEquals(0, levelHook.methods.stream().filter(method -> annotation(method,
                        "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;") != null).count(),
                "Accessor bodies are generated after injector prepare and must never be targets");

        ClassNode trackerHook = readClass(
                FabroliaLithiumChunkStatusTrackerActorThreadMixin.class);
        AnnotationNode trackerMixin = annotation(trackerHook,
                "Lorg/spongepowered/asm/mixin/Mixin;");
        assertNotNull(trackerMixin);
        assertEquals(List.of(CHUNK_STATUS_TRACKER_TARGET),
                annotationValue(trackerMixin, "targets"));
        assertEquals(FABROLIA_CALLER_HOOK_PRIORITY,
                annotationValue(trackerMixin, "priority"));
        assertEquals(Boolean.FALSE, annotationValue(trackerMixin, "remap"));

        assertChunkStatusHandler(trackerHook, "fabrolia$logicalAccessibleThread",
                "onChunkAccessible" + ACCESSIBLE_DESC);
        assertChunkStatusHandler(trackerHook, "fabrolia$logicalInaccessibleThread",
                "onChunkInaccessible" + INACCESSIBLE_DESC);
    }

    @Test
    void lithiumHooksAreRegisteredAndCancelledExactlyWhenLithiumIsAbsent() throws Exception {
        String levelShortName = "lithium.FabroliaLithiumLevelActorThreadMixin";
        String trackerShortName =
                "lithium.FabroliaLithiumChunkStatusTrackerActorThreadMixin";
        try (InputStream in = LithiumLevelActorThreadWiringTest.class.getClassLoader()
                .getResourceAsStream("fabrolia.common.mixins.json")) {
            assertNotNull(in);
            String config = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(1, occurrences(config, "\"" + levelShortName + "\""));
            assertEquals(1, occurrences(config, "\"" + trackerShortName + "\""));
        }

        String levelHook = "com.axalotl.fabrolia.common.mixin." + levelShortName;
        String trackerHook = "com.axalotl.fabrolia.common.mixin." + trackerShortName;
        List<String> levelTarget = List.of("net.minecraft.world.level.Level");
        List<String> trackerTarget = List.of(CHUNK_STATUS_TRACKER_TARGET);
        MixinCanceller withoutLithium = new MixinCanceller();
        assertTrue(withoutLithium.shouldCancel(levelTarget, levelHook));
        assertTrue(withoutLithium.shouldCancel(trackerTarget, trackerHook));

        MixinCanceller withLithium = new MixinCanceller();
        assertFalse(withLithium.shouldCancel(levelTarget,
                "net.caffeinemc.mods.lithium.mixin.util.accessors.LevelAccessor"));
        assertFalse(withLithium.shouldCancel(levelTarget, levelHook));
        assertFalse(withLithium.shouldCancel(trackerTarget, trackerHook));
    }

    private static void assertLithiumLevelSeams(byte[] retrievalBytes,
                                                 byte[] accessorBytes,
                                                 byte[] trackerBytes) {
        ClassNode retrieval = readClass(retrievalBytes);
        assertMixinTarget(retrieval, "Lnet/minecraft/world/level/Level;");
        assertEquals(DEFAULT_MIXIN_PRIORITY, mixinPriority(retrieval),
                "Lithium retrieval provider must retain the default priority");
        MethodNode loaded = method(retrieval,
                "lithium$getLoadedExistingBlockEntity", RETRIEVAL_DESC);
        assertEquals(1, callCount(loaded, "java/lang/Thread", "currentThread",
                "()Ljava/lang/Thread;"));
        int current = callIndex(loaded, "java/lang/Thread", "currentThread",
                "()Ljava/lang/Thread;");
        int thread = fieldIndex(loaded, Opcodes.GETFIELD, retrieval.name, "thread");
        int reject = opcodeIndexAfter(loaded, Opcodes.IF_ACMPNE, thread);
        assertTrue(current >= 0 && thread > current && reject > thread,
                "Lithium thread check must stay currentThread() != Level.thread");
        assertEquals(1, callCount(loaded, retrieval.name, "getChunk",
                "(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;"));
        assertEquals(1, callCount(loaded,
                "net/minecraft/world/level/chunk/ChunkAccess", "getBlockEntity",
                RETRIEVAL_DESC));
        assertEquals(2, opcodeCount(loaded, Opcodes.ARETURN));

        ClassNode accessor = readClass(accessorBytes);
        assertMixinTarget(accessor, "Lnet/minecraft/world/level/Level;");
        assertEquals(DEFAULT_MIXIN_PRIORITY, mixinPriority(accessor),
                "Lithium accessor provider must retain the default priority");
        MethodNode getThread = method(accessor, "getThread", "()Ljava/lang/Thread;");
        assertTrue((getThread.access & Opcodes.ACC_ABSTRACT) != 0);
        assertNotNull(annotation(getThread, "Lorg/spongepowered/asm/mixin/gen/Accessor;"));

        ClassNode tracker = readClass(trackerBytes);
        assertChunkStatusCallSite(method(tracker, "onChunkAccessible", ACCESSIBLE_DESC));
        assertChunkStatusCallSite(method(tracker, "onChunkInaccessible", INACCESSIBLE_DESC));
        assertEquals(2, tracker.methods.stream().mapToInt(candidate -> callCount(candidate,
                        LEVEL_THREAD_ACCESSOR, "getThread", LEVEL_THREAD_ACCESSOR_DESC)).sum(),
                "the audited Lithium artifact must retain exactly two Level.getThread consumers");
    }

    private static void assertChunkStatusCallSite(MethodNode method) {
        assertTrue((method.access & Opcodes.ACC_STATIC) != 0);
        assertEquals(1, callCount(method, LEVEL_THREAD_ACCESSOR,
                "getThread", LEVEL_THREAD_ACCESSOR_DESC));
        assertEquals(1, callCount(method, "java/lang/Thread", "currentThread",
                LEVEL_THREAD_ACCESSOR_DESC));
        int levelThread = callIndex(method, LEVEL_THREAD_ACCESSOR,
                "getThread", LEVEL_THREAD_ACCESSOR_DESC);
        int currentThread = callIndex(method, "java/lang/Thread", "currentThread",
                LEVEL_THREAD_ACCESSOR_DESC);
        int accept = opcodeIndexAfter(method, Opcodes.IF_ACMPEQ, currentThread);
        assertTrue(levelThread >= 0 && currentThread > levelThread && accept > currentThread,
                method.name + " must stay LevelAccessor.getThread() == Thread.currentThread()");
    }

    private static void assertChunkStatusHandler(ClassNode hook, String handlerName,
                                                  String targetMethod) {
        MethodNode handler = method(hook, handlerName,
                "(Ljava/lang/Thread;)Ljava/lang/Thread;");
        assertTrue((handler.access & Opcodes.ACC_STATIC) != 0);
        AnnotationNode expressionHook = annotation(handler,
                "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;");
        assertInjector(expressionHook, targetMethod, "INVOKE",
                "L" + LEVEL_THREAD_ACCESSOR + ";getThread" + LEVEL_THREAD_ACCESSOR_DESC);
        int actor = callIndex(handler, internal(RegionalPlayerEntityTicksV2.class),
                "inExactPlayerActor", "()Z");
        int logicalThread = callIndex(handler, "java/lang/Thread", "currentThread",
                LEVEL_THREAD_ACCESSOR_DESC);
        assertEquals(0, callCount(handler, internal(AsyncIslandPlayerTicks.class),
                "inOwnerTurn", "()Z"),
                "Lithium chunk-status callbacks remain physical-Main/v2-only");
        assertTrue(actor >= 0 && logicalThread > actor,
                "disabled/non-actor paths must return Lithium's original thread value");
    }

    private static void assertLithiumConfig(JarFile jar) throws Exception {
        String config = new String(jar.getInputStream(jar.getJarEntry("lithium.mixins.json"))
                .readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(1, occurrences(config, "\"util.accessors.LevelAccessor\""));
        assertEquals(1, occurrences(config, "\"util.block_entity_retrieval.LevelMixin\""));
    }

    private static void assertInjector(AnnotationNode injector, String method,
                                       String atValue, String atTarget) {
        assertNotNull(injector);
        Object selector = annotationValue(injector, "method");
        if (selector instanceof List<?> list) assertEquals(List.of(method), list);
        else assertEquals(method, selector);
        assertEquals(1, annotationValue(injector, "require"));
        assertEquals(1, annotationValue(injector, "allow"));
        assertEquals(Boolean.FALSE, annotationValue(injector, "remap"));
        AnnotationNode at = atAnnotation(injector);
        assertEquals(atValue, annotationValue(at, "value"));
        if (atTarget != null) assertEquals(atTarget, annotationValue(at, "target"));
    }

    private static byte[] read(URL resource) throws Exception {
        try (InputStream in = resource.openStream()) {
            return in.readAllBytes();
        }
    }

    private static ClassNode readClass(Class<?> type) throws Exception {
        String resource = "/" + internal(type) + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            return readClass(in.readAllBytes());
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

    private static AnnotationNode annotation(FieldNode field, String desc) {
        AnnotationNode found = annotation(field.visibleAnnotations, desc);
        return found != null ? found : annotation(field.invisibleAnnotations, desc);
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

    private static void assertMixinTarget(ClassNode mixin, String targetDescriptor) {
        AnnotationNode annotation = annotation(mixin, "Lorg/spongepowered/asm/mixin/Mixin;");
        assertNotNull(annotation);
        assertEquals(List.of(Type.getType(targetDescriptor)), annotationValue(annotation, "value"));
    }

    private static int mixinPriority(ClassNode mixin) {
        AnnotationNode annotation = annotation(mixin, "Lorg/spongepowered/asm/mixin/Mixin;");
        assertNotNull(annotation);
        Object configured = annotationValue(annotation, "priority");
        return configured instanceof Integer priority ? priority : DEFAULT_MIXIN_PRIORITY;
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

    private static int fieldIndex(MethodNode method, int opcode, String owner, String name) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == opcode
                    && field.owner.equals(owner) && field.name.equals(name)) return index;
            index++;
        }
        return -1;
    }

    private static int opcodeIndexAfter(MethodNode method, int opcode, int after) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (index > after && instruction.getOpcode() == opcode) return index;
            index++;
        }
        return -1;
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
