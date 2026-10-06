package com.axalotl.fabrolia.common.mixin.carpet;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact-version structural proof for the Carpet A5 command adapter. */
class CarpetPlayerCommandIslandWiringTest {

    private static final String MIXIN =
            "com/axalotl/fabrolia/common/mixin/carpet/CarpetPlayerCommandIslandMixin";
    private static final String ROUTER =
            "com/axalotl/fabrolia/common/regions/CarpetPlayerMutationRouter";
    private static final String SCHEDULER =
            "com/axalotl/fabrolia/common/regions/RegionScheduler";
    private static final String A5 =
            "com/axalotl/fabrolia/common/regions/AsyncIslandPlayerTicks";
    private static final String ISLAND_COMMANDS =
            "com/axalotl/fabrolia/common/regions/IslandCommands";
    private static final String OPERATION =
            "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
    private static final String EXPECTED_CARPET_SHA256 =
            "f6ada912af65c91536d4b0d80adf26cc438253252ddaf259f2c6617ae471311c";

    @Test
    void commandMutationHubAndKillAreExactRequiredCallsites() throws Exception {
        ClassNode mixin = readClass(MIXIN);
        assertTrue(hasAnnotation(mixin, "Lorg/spongepowered/asm/mixin/Pseudo;"));
        assertTrue(annotationStrings(mixin).contains("carpet.commands.PlayerCommand"));

        MethodNode action = method(mixin, "fabrolia$routeActionPackMutation");
        List<String> actionPins = annotationStrings(action);
        assertTrue(actionPins.contains("manipulate(Lcom/mojang/brigadier/context/CommandContext;"
                + "Ljava/util/function/Consumer;)I"));
        assertTrue(actionPins.contains(
                "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V"));
        assertTrue(annotationInts(action).contains(1), "action-pack wrapper must require one seam");
        assertEquals(1, calls(action, ROUTER, "route"));
        assertEquals(1, calls(action, OPERATION, "call"),
                "non-A5 players retain exactly one original Carpet invocation");

        MethodNode kill = method(mixin, "fabrolia$routeFakeKill");
        List<String> killPins = annotationStrings(kill);
        assertTrue(killPins.contains("kill(Lcom/mojang/brigadier/context/CommandContext;)I"));
        assertTrue(killPins.contains("Lnet/minecraft/server/level/ServerPlayer;"
                + "kill(Lnet/minecraft/server/level/ServerLevel;)V"));
        assertTrue(annotationInts(kill).contains(1), "kill wrapper must require one seam");
        assertEquals(1, calls(kill, ROUTER, "route"));
        assertEquals(1, calls(kill, OPERATION, "call"));
    }


    @Test
    void mixinIsRegistered() throws Exception {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("fabrolia.common.mixins.json")) {
            assertNotNull(in, "compiled mixin config");
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("carpet.CarpetPlayerCommandIslandMixin"));
        }
    }

    @Test
    void installedCarpetArtifactStillHasTheAuditedMutationTopology() throws Exception {
        Path artifact = carpetArtifact();
        Assumptions.assumeTrue(artifact != null,
                "exact Carpet artifact is optional outside the full-modpack workspace");
        assertEquals(EXPECTED_CARPET_SHA256, sha256(artifact),
                "a new Carpet artifact requires a fresh owner-concurrency audit");

        try (ZipFile zip = new ZipFile(artifact.toFile())) {
            ClassNode command = readJarClass(zip, "carpet/commands/PlayerCommand");
            MethodNode manipulate = method(command, "manipulate",
                    "(Lcom/mojang/brigadier/context/CommandContext;Ljava/util/function/Consumer;)I");
            assertEquals(1, calls(manipulate, "java/util/function/Consumer", "accept"),
                    "every /player action-pack mutator must still converge on one hub");
            int accept = callIndex(manipulate,
                    "java/util/function/Consumer", "accept", Opcodes.INVOKEINTERFACE);
            assertTrue(localCovers(manipulate, accept,
                            "Lnet/minecraft/server/level/ServerPlayer;"),
                    "the exact player local captured by @Local must cover the mutation hub");
            MethodNode kill = method(command, "kill",
                    "(Lcom/mojang/brigadier/context/CommandContext;)I");
            assertEquals(1, calls(kill, "net/minecraft/server/level/ServerPlayer", "kill"));

            ClassNode actionPack = readJarClass(zip,
                    "carpet/helpers/EntityPlayerActionPack");
            MethodNode mount = method(actionPack, "mount",
                    "(Z)Lcarpet/helpers/EntityPlayerActionPack;");
            assertEquals(1, calls(mount,
                    "net/minecraft/server/level/ServerPlayer", "startRiding"));
            assertEquals(1, calls(mount,
                    "net/minecraft/world/entity/animal/equine/AbstractHorse", "mobInteract"));
            MethodNode dismount = method(actionPack, "dismount",
                    "()Lcarpet/helpers/EntityPlayerActionPack;");
            assertEquals(1, calls(dismount,
                    "net/minecraft/server/level/ServerPlayer", "stopRiding"));
            MethodNode drop = method(actionPack, "dropItemFromSlot", "(IZ)V");
            assertEquals(1, calls(drop,
                    "net/minecraft/server/level/ServerPlayer", "drop"),
                    "the audited action hub still contains world-mutating work which needs A5's fence");

            ClassNode actionMixin = readJarClass(zip,
                    "carpet/mixins/ServerPlayer_actionPackMixin");
            MethodNode onTick = method(actionMixin, "onTick");
            assertEquals(1, calls(onTick,
                    "carpet/helpers/EntityPlayerActionPack", "onUpdate"));
            List<String> injection = annotationStrings(onTick);
            assertTrue(injection.contains("tick"));
            assertTrue(injection.contains("HEAD"),
                    "the shared action pack must still update at ServerPlayer.tick HEAD");

            ClassNode fake = readJarClass(zip, "carpet/patches/EntityPlayerMPFake");
            MethodNode fakeTick = method(fake, "tick", "()V");
            int superTick = callIndex(fakeTick,
                    "net/minecraft/server/level/ServerPlayer", "tick", Opcodes.INVOKESPECIAL);
            int doTick = callIndex(fakeTick,
                    "carpet/patches/EntityPlayerMPFake", "doTick", Opcodes.INVOKEVIRTUAL);
            assertTrue(superTick >= 0 && doTick > superTick);
            assertTrue(npeHandlerCovers(fakeTick, superTick, doTick),
                    "Carpet's combined body still swallows NPE, so A5 must translate failures");

            MethodNode fakeKill = method(fake, "kill",
                    "(Lnet/minecraft/network/chat/Component;)V");
            MethodNode scheduledKill = method(fake, "lambda$kill$0",
                    "(Lnet/minecraft/network/chat/Component;)V");
            assertEquals(1, calls(fakeKill,
                    "net/minecraft/server/MinecraftServer", "schedule"),
                    "ordinary fake kill must still publish the audited Main task");
            assertEquals(1, calls(fakeKill,
                    "net/minecraft/server/network/ServerGamePacketListenerImpl",
                    "onDisconnect"),
                    "duplicate-login kill retains its direct disconnect bypass");
            assertEquals(1, calls(scheduledKill,
                    "net/minecraft/server/network/ServerGamePacketListenerImpl",
                    "onDisconnect"),
                    "the scheduled ordinary kill retains its direct disconnect bypass");
        }
    }

    private static Path carpetArtifact() {
        String configured = System.getProperty("fabrolia.carpetArtifact");
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured).toAbsolutePath().normalize();
            return Files.isRegularFile(path) ? path : null;
        }
        List<Path> candidates = List.of(
                Path.of("runtime-smoke.NTEH8H/server/mods/fabric-carpet-26.2+v260616.jar"),
                Path.of("../runtime-smoke.NTEH8H/server/mods/fabric-carpet-26.2+v260616.jar"));
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

    private static boolean npeHandlerCovers(MethodNode method, int first, int second) {
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            if (!"java/lang/NullPointerException".equals(block.type)) continue;
            int start = method.instructions.indexOf(block.start);
            int end = method.instructions.indexOf(block.end);
            if (start <= first && end > second) return true;
        }
        return false;
    }

    private static boolean throwableHandlerCovers(MethodNode method, int instruction) {
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            if (!"java/lang/Throwable".equals(block.type)) continue;
            int start = method.instructions.indexOf(block.start);
            int end = method.instructions.indexOf(block.end);
            if (start <= instruction && end > instruction) return true;
        }
        return false;
    }

    private static boolean catchAllCleanupCovers(MethodNode method, int instruction,
                                                 String owner, String name) {
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            int start = method.instructions.indexOf(block.start);
            int end = method.instructions.indexOf(block.end);
            int handler = method.instructions.indexOf(block.handler);
            if (block.type != null || start < 0 || start > instruction
                    || end <= instruction || handler < 0) continue;
            int index = 0;
            for (AbstractInsnNode candidate : method.instructions) {
                if (index >= handler && candidate instanceof MethodInsnNode call
                        && owner.equals(call.owner) && name.equals(call.name)) return true;
                index++;
            }
        }
        return false;
    }

    private static int callIndex(MethodNode method, String owner, String name, int opcode) {
        int index = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == opcode
                    && owner.equals(call.owner) && name.equals(call.name)) return index;
            index++;
        }
        return -1;
    }

    private static int callIndexAfter(MethodNode method, String owner,
                                      String name, int after) {
        int index = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (index > after && insn instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) return index;
            index++;
        }
        return -1;
    }

    private static int lastCallIndex(MethodNode method, String owner, String name) {
        int found = -1;
        int index = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) found = index;
            index++;
        }
        return found;
    }

    private static int fieldIndex(MethodNode method, int opcode,
                                  String owner, String name) {
        int index = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof org.objectweb.asm.tree.FieldInsnNode field
                    && field.getOpcode() == opcode && owner.equals(field.owner)
                    && name.equals(field.name)) return index;
            index++;
        }
        return -1;
    }

    private static int lastFieldIndex(MethodNode method, int opcode,
                                      String owner, String name) {
        int found = -1;
        int index = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof org.objectweb.asm.tree.FieldInsnNode field
                    && field.getOpcode() == opcode && owner.equals(field.owner)
                    && name.equals(field.name)) found = index;
            index++;
        }
        return found;
    }

    private static int fieldCount(MethodNode method, int opcode,
                                  String owner, String name) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof org.objectweb.asm.tree.FieldInsnNode field
                    && field.getOpcode() == opcode && owner.equals(field.owner)
                    && name.equals(field.name)) count++;
        }
        return count;
    }

    private static int typeCount(MethodNode method, int opcode, String type) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof TypeInsnNode typed && typed.getOpcode() == opcode
                    && type.equals(typed.desc)) count++;
        }
        return count;
    }

    private static boolean hasImmediateNullArguments(MethodNode method, int call,
                                                     int argumentCount) {
        int found = 0;
        for (int index = call - 1; index >= 0; index--) {
            int opcode = method.instructions.get(index).getOpcode();
            if (opcode < 0) continue;
            if (opcode != Opcodes.ACONST_NULL) return false;
            if (++found == argumentCount) return true;
        }
        return false;
    }

    private static void assertField(ClassNode owner, String name, String descriptor) {
        assertEquals(1, owner.fields.stream().filter(field ->
                field.name.equals(name) && field.desc.equals(descriptor)).count(),
                "expected one " + owner.name + "." + name + descriptor);
    }

    private static boolean localCovers(MethodNode method, int instruction,
                                       String descriptor) {
        if (method.localVariables == null) return false;
        for (LocalVariableNode local : method.localVariables) {
            if (!descriptor.equals(local.desc)) continue;
            int start = method.instructions.indexOf(local.start);
            int end = method.instructions.indexOf(local.end);
            if (start <= instruction && end > instruction) return true;
        }
        return false;
    }

    private static int calls(ClassNode type, String owner, String name) {
        return type.methods.stream().mapToInt(method -> calls(method, owner, name)).sum();
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) count++;
        }
        return count;
    }

    private static int opcodeCount(MethodNode method, int opcode) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == opcode) count++;
        }
        return count;
    }

    private static int realInstructionCount(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() >= 0) count++;
        }
        return count;
    }

    private static int opcodeIndex(MethodNode method, int opcode) {
        int index = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == opcode) return index;
            index++;
        }
        return -1;
    }

    private static ClassNode readClass(String internalName) throws Exception {
        try (InputStream in = CarpetPlayerCommandIslandWiringTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            assertNotNull(in, "missing class " + internalName);
            ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, 0);
            return node;
        }
    }

    private static ClassNode readJarClass(ZipFile zip, String internalName) throws Exception {
        ZipEntry entry = zip.getEntry(internalName + ".class");
        assertNotNull(entry, "missing Carpet class " + internalName);
        try (InputStream in = zip.getInputStream(entry)) {
            ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, 0);
            return node;
        }
    }

    private static MethodNode method(ClassNode type, String name) {
        List<MethodNode> found = type.methods.stream().filter(m -> name.equals(m.name)).toList();
        assertEquals(1, found.size(), "expected one " + type.name + "." + name);
        return found.getFirst();
    }

    private static MethodNode method(ClassNode type, String name, String desc) {
        return type.methods.stream().filter(m -> name.equals(m.name) && desc.equals(m.desc))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "missing " + type.name + "." + name + desc));
    }

    private static boolean hasAnnotation(ClassNode type, String desc) {
        return (type.visibleAnnotations != null
                    && type.visibleAnnotations.stream()
                            .anyMatch(annotation -> desc.equals(annotation.desc)))
                || (type.invisibleAnnotations != null
                    && type.invisibleAnnotations.stream()
                            .anyMatch(annotation -> desc.equals(annotation.desc)));
    }

    private static List<String> annotationStrings(ClassNode type) {
        ArrayList<String> out = new ArrayList<>();
        collect(type.visibleAnnotations, out, null);
        collect(type.invisibleAnnotations, out, null);
        return out;
    }

    private static List<String> annotationStrings(MethodNode method) {
        ArrayList<String> out = new ArrayList<>();
        collect(method.visibleAnnotations, out, null);
        collect(method.invisibleAnnotations, out, null);
        return out;
    }

    private static List<Integer> annotationInts(MethodNode method) {
        ArrayList<Integer> out = new ArrayList<>();
        collect(method.visibleAnnotations, null, out);
        collect(method.invisibleAnnotations, null, out);
        return out;
    }

    private static void collect(List<AnnotationNode> annotations, List<String> strings,
                                List<Integer> ints) {
        if (annotations == null) return;
        for (AnnotationNode annotation : annotations) collectValue(annotation.values, strings, ints);
    }

    @SuppressWarnings("unchecked")
    private static void collectValue(Object value, List<String> strings, List<Integer> ints) {
        if (value == null) return;
        if (value instanceof String string) {
            if (strings != null) strings.add(string);
        } else if (value instanceof Integer integer) {
            if (ints != null) ints.add(integer);
        } else if (value instanceof AnnotationNode annotation) {
            collectValue(annotation.values, strings, ints);
        } else if (value instanceof List<?> list) {
            for (Object nested : list) collectValue(nested, strings, ints);
        }
    }
}
