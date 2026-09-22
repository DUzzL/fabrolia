package com.axalotl.fabrolia.common.mixin.c2me;

import com.axalotl.fabrolia.common.mixin.utils.MixinCanceller;
import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
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

/** Pins the remaining C2ME physical-thread consumers reachable around exact player ticks. */
class C2meDirectThreadConsumerWiringTest {

    private static final String REWRITE_MIXIN =
            "com.ishland.c2me.rewrites.chunksystem.mixin.MixinServerChunkManager";
    private static final String MID_TICK_MIXIN =
            "com.ishland.c2me.opts.scheduling.mixin.mid_tick_chunk_tasks.MixinMinecraftServer";
    private static final String MID_TICK_CALLER =
            "com/ishland/c2me/opts/scheduling/mixin/mid_tick_chunk_tasks/MixinWorld.class";
    private static final String SAVE_MIXIN =
            "com.ishland.c2me.fixes.general.threading_issues.mixin.asynccatchers.MixinMinecraftServer";
    private static final String SAVE_CATCHER = SAVE_MIXIN.replace('.', '/') + ".class";
    private static final String THREAD_DESC = "()Ljava/lang/Thread;";
    private static final String CALLBACK_RETURNABLE_DESC =
            "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V";

    @Test
    void actualRuntimeModulesRetainAllAuditedConsumerShapes() throws Exception {
        Path fixes = configured("FABROLIA_C2ME_FIXES_GENERAL_JAR");
        Path rewrites = configured("FABROLIA_C2ME_REWRITES_CHUNK_SYSTEM_JAR");
        Path scheduling = configured("FABROLIA_C2ME_OPTS_SCHEDULING_JAR");
        Assumptions.assumeTrue(fixes != null && rewrites != null && scheduling != null,
                "set all three processed C2ME module paths for external pins");

        assertEquals("f97bbe8c7a94216d24b867bcc3ec3ed7eed0018067ed99eeab70109cb64b3d68",
                sha256(Files.readAllBytes(fixes)));
        assertEquals("c4ff383e3d9bc7d0dc0160f06646c69f58e833ea4d235e33ed8624f2871520a1",
                sha256(Files.readAllBytes(rewrites)));
        assertEquals("4b6979ab56cf119efa418a191f49e907a41bc971d5f16d1db60d697351b67a5a",
                sha256(Files.readAllBytes(scheduling)));

        try (JarFile jar = new JarFile(fixes.toFile())) {
            byte[] bytes = entryBytes(jar, SAVE_CATCHER);
            assertEquals("4ef23433eb5a61d97ed4594f57585dc852c050c989ac3ae1e7bd002fdea6298d",
                    sha256(bytes));
            assertEquals("7203ed3b1fb810115c266fab2eca719babade78c55600e824ebc95f0504f5d32",
                    sha256(entryBytes(jar, "c2me-fixes-general-threading-issues.mixins.json")));
            assertSaveCatchers(readClass(bytes));
        }
        try (JarFile jar = new JarFile(rewrites.toFile())) {
            byte[] bytes = entryBytes(jar, REWRITE_MIXIN.replace('.', '/') + ".class");
            assertEquals("7bf5a579fe7b8155dec41ae4feb8de882fb9734d205b2456b66fa7eb78db0d4e",
                    sha256(bytes));
            assertEquals("cc1bde8ab0bf5a757357525f7cd15b7704bddd8cdbc4c9e1b343424aaf5ba961",
                    sha256(entryBytes(jar, "c2me-rewrites-chunk-system.mixins.json")));
            assertShortcut(readClass(bytes));
        }
        try (JarFile jar = new JarFile(scheduling.toFile())) {
            byte[] server = entryBytes(jar, MID_TICK_MIXIN.replace('.', '/') + ".class");
            byte[] caller = entryBytes(jar, MID_TICK_CALLER);
            assertEquals("c82d31f1b17648b885891f63ebeb17c2e64d3c3c99fcd964622cc2ee3973f27b",
                    sha256(server));
            assertEquals("df9df407c9a630e75737ac810d277ee3392dcca55b191586b01c016905c5e7cd",
                    sha256(caller));
            assertEquals("3e239d48f749e134aa4a8287768d2022d7b071404409d3fa5aeb9ef8606322c3",
                    sha256(entryBytes(jar, "c2me-opts-scheduling.mixins.json")));
            assertMidTick(readClass(server), readClass(caller));
        }
    }

    @Test
    void reachableChecksArePatchedAtTheirExactOwners() throws Exception {
        ClassNode shortcut = readClass(FabroliaC2meChunkShortcutActorThreadMixin.class);
        MethodNode shortcutHook = method(shortcut,
                "fabrolia$logicalC2meChunkShortcutThread",
                "(Ljava/lang/Thread;)Ljava/lang/Thread;");
        AnnotationNode targetHandler = annotation(shortcutHook,
                "Lcom/bawnorton/mixinsquared/TargetHandler;");
        assertNotNull(targetHandler);
        assertEquals(REWRITE_MIXIN, value(targetHandler, "mixin"));
        assertEquals("shortcutGetChunk", value(targetHandler, "name"));
        assertEquals("handler", value(targetHandler, "prefix"));
        assertLogicalThreadHook(shortcutHook, "@MixinSquared:Handler", "mainThread");

        ClassNode midTick = readClass(FabroliaC2meMidTickActorThreadMixin.class);
        MethodNode midTickHook = method(midTick, "fabrolia$logicalC2meMidTickThread",
                "(Ljava/lang/Thread;)Ljava/lang/Thread;");
        assertLogicalThreadHook(midTickHook,
                "executeTasksMidTick(Lnet/minecraft/server/level/ServerLevel;)V", "serverThread");

        ClassNode saves = readClass(FabroliaC2meSaveActorThreadMixin.class);
        assertTargetedLogicalThreadHook(saves,
                "fabrolia$logicalC2meSaveAllChunksThread", "preventAsyncSave");
        assertTargetedLogicalThreadHook(saves,
                "fabrolia$logicalC2meSaveEverythingThread", "preventAsyncSaveAll");
    }

    @Test
    void compatMixinsAreRegisteredAndDisappearWithoutC2me() throws Exception {
        String shortcut = "c2me.FabroliaC2meChunkShortcutActorThreadMixin";
        String midTick = "c2me.FabroliaC2meMidTickActorThreadMixin";
        String saves = "c2me.FabroliaC2meSaveActorThreadMixin";
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("fabrolia.common.mixins.json")) {
            assertNotNull(input);
            String config = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(1, occurrences(config, "\"" + shortcut + "\""));
            assertEquals(1, occurrences(config, "\"" + midTick + "\""));
            assertEquals(1, occurrences(config, "\"" + saves + "\""));
        }
        MixinCanceller absent = new MixinCanceller();
        String prefix = "com.axalotl.fabrolia.common.mixin.";
        assertTrue(absent.shouldCancel(List.of("net.minecraft.server.level.ServerChunkCache"),
                prefix + shortcut));
        assertTrue(absent.shouldCancel(List.of("net.minecraft.server.MinecraftServer"),
                prefix + midTick));
        assertTrue(absent.shouldCancel(List.of("net.minecraft.server.MinecraftServer"),
                prefix + saves));
        MixinCanceller present = new MixinCanceller();
        assertFalse(present.shouldCancel(List.of("unused"), REWRITE_MIXIN));
        assertFalse(present.shouldCancel(List.of("unused"), prefix + shortcut));
        assertFalse(present.shouldCancel(List.of("unused"), prefix + midTick));
        assertFalse(present.shouldCancel(List.of("unused"), SAVE_MIXIN),
                "save catcher provider must stay active; TargetHandler preserves its body");
        assertFalse(present.shouldCancel(List.of("unused"), prefix + saves));
    }

    private static void assertSaveCatchers(ClassNode owner) {
        assertEquals(1, owner.fields.size());
        assertEquals(3, owner.methods.size(), "provider shape must remain ctor + two handlers");
        for (List<String> pin : List.of(
                List.of("preventAsyncSave", "saveAllChunks"),
                List.of("preventAsyncSaveAll", "saveEverything"))) {
            MethodNode method = method(owner, pin.get(0), CALLBACK_RETURNABLE_DESC);
            AnnotationNode inject = annotation(method,
                    "Lorg/spongepowered/asm/mixin/injection/Inject;");
            assertNotNull(inject);
            assertSelector(inject, pin.get(1));
            AnnotationNode at = firstAnnotation(value(inject, "at"));
            assertEquals("HEAD", value(at, "value"));
            assertEquals(1, callCount(method, "java/lang/Thread", "currentThread", THREAD_DESC));
            assertEquals(1, fieldCount(method, owner.name, "serverThread"));
            assertEquals(1, opcodeCount(method, Opcodes.IF_ACMPEQ));
            assertEquals(1, callCount(method, "java/util/ConcurrentModificationException",
                    "<init>", "(Ljava/lang/String;)V"));
            assertEquals(1, callCount(method, "java/util/ConcurrentModificationException",
                    "printStackTrace", "()V"));
            assertEquals(1, opcodeCount(method, Opcodes.ATHROW));
        }
    }

    private static void assertTargetedLogicalThreadHook(ClassNode owner, String hookName,
                                                         String upstreamHandler) {
        MethodNode hook = method(owner, hookName,
                "(Ljava/lang/Thread;)Ljava/lang/Thread;");
        AnnotationNode target = annotation(hook,
                "Lcom/bawnorton/mixinsquared/TargetHandler;");
        assertNotNull(target);
        assertEquals(SAVE_MIXIN, value(target, "mixin"));
        assertEquals(upstreamHandler, value(target, "name"));
        assertEquals("handler", value(target, "prefix"));
        assertLogicalThreadHook(hook, "@MixinSquared:Handler", "serverThread");
    }

    private static void assertShortcut(ClassNode owner) {
        MethodNode method = uniqueMethod(owner, "shortcutGetChunk");
        AnnotationNode inject = annotation(method, "Lorg/spongepowered/asm/mixin/injection/Inject;");
        assertNotNull(inject);
        assertSelector(inject, "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)"
                + "Lnet/minecraft/world/level/chunk/ChunkAccess;");
        assertEquals(Boolean.TRUE, value(inject, "cancellable"));
        assertEquals(1, callCount(method, "java/lang/Thread", "currentThread", THREAD_DESC));
        assertEquals(1, fieldCount(method, owner.name, "mainThread"));
        assertEquals(1, opcodeCount(method, Opcodes.IF_ACMPEQ));
    }

    private static void assertMidTick(ClassNode server, ClassNode caller) {
        MethodNode execute = method(server, "executeTasksMidTick",
                "(Lnet/minecraft/server/level/ServerLevel;)V");
        assertEquals(1, callCount(execute, "java/lang/Thread", "currentThread", THREAD_DESC));
        assertEquals(1, fieldCount(execute, server.name, "serverThread"));
        assertEquals(1, opcodeCount(execute, Opcodes.IF_ACMPEQ));

        MethodNode tail = uniqueMethod(caller, "onPostTickEntity");
        AnnotationNode inject = annotation(tail, "Lorg/spongepowered/asm/mixin/injection/Inject;");
        assertNotNull(inject);
        assertSelector(inject, "guardEntityTick");
        AnnotationNode at = firstAnnotation(value(inject, "at"));
        assertEquals("TAIL", value(at, "value"));
        assertEquals(1, callCount(tail,
                "com/ishland/c2me/opts/scheduling/common/ServerMidTickTask",
                "executeTasksMidTick", "(Lnet/minecraft/server/level/ServerLevel;)V"));
    }

    private static void assertLogicalThreadHook(MethodNode hook, String selector, String field) {
        AnnotationNode modify = annotation(hook,
                "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;");
        assertNotNull(modify);
        assertSelector(modify, selector);
        assertEquals(1, value(modify, "require"));
        assertEquals(1, value(modify, "allow"));
        AnnotationNode at = firstAnnotation(value(modify, "at"));
        assertEquals("Ljava/lang/Thread;currentThread()Ljava/lang/Thread;", value(at, "target"));
        assertEquals(1, callCount(hook, internal(RegionalPlayerEntityTicksV2.class),
                "inExactPlayerActor", "()Z"));
        assertEquals(1, fieldCount(hook, hookOwner(hook), field));
    }

    private static String hookOwner(MethodNode hook) {
        // ASM MethodNode has no owner; each hook contains exactly one GETFIELD and
        // fieldCount below accepts null as an owner wildcard.
        return null;
    }

    private static Path configured(String variable) {
        String value = System.getenv(variable);
        if (value == null || value.isBlank()) return null;
        Path path = Path.of(value);
        return Files.isRegularFile(path) ? path : null;
    }

    private static byte[] entryBytes(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name);
        assertNotNull(entry, name);
        try (InputStream input = jar.getInputStream(entry)) { return input.readAllBytes(); }
    }

    private static ClassNode readClass(Class<?> type) throws Exception {
        try (InputStream input = type.getResourceAsStream("/" + internal(type) + ".class")) {
            assertNotNull(input);
            return readClass(input.readAllBytes());
        }
    }

    private static ClassNode readClass(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode owner, String name, String desc) {
        return owner.methods.stream().filter(method -> method.name.equals(name)
                        && method.desc.equals(desc)).findFirst().orElseThrow();
    }

    private static MethodNode uniqueMethod(ClassNode owner, String name) {
        List<MethodNode> methods = owner.methods.stream().filter(method -> method.name.equals(name)).toList();
        assertEquals(1, methods.size(), owner.name + "." + name);
        return methods.getFirst();
    }

    private static AnnotationNode annotation(MethodNode method, String desc) {
        AnnotationNode found = annotation(method.visibleAnnotations, desc);
        return found != null ? found : annotation(method.invisibleAnnotations, desc);
    }

    private static AnnotationNode annotation(List<AnnotationNode> annotations, String desc) {
        if (annotations != null) for (AnnotationNode annotation : annotations)
            if (annotation.desc.equals(desc)) return annotation;
        return null;
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation == null || annotation.values == null) return null;
        for (int i = 0; i < annotation.values.size(); i += 2)
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        return null;
    }

    private static AnnotationNode firstAnnotation(Object value) {
        if (value instanceof AnnotationNode node) return node;
        return (AnnotationNode) ((List<?>) value).getFirst();
    }

    private static void assertSelector(AnnotationNode annotation, String expected) {
        Object selector = value(annotation, "method");
        if (selector instanceof List<?> list) assertEquals(List.of(expected), list);
        else assertEquals(expected, selector);
    }

    private static int callCount(MethodNode method, String owner, String name, String desc) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner)
                    && call.name.equals(name) && (desc == null || call.desc.equals(desc))) count++;
        }
        return count;
    }

    private static int fieldCount(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode field && (owner == null || field.owner.equals(owner))
                    && field.name.equals(name)) count++;
        }
        return count;
    }

    private static int opcodeCount(MethodNode method, int opcode) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() == opcode) count++;
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

    private static String internal(Class<?> type) { return type.getName().replace('.', '/'); }
}
