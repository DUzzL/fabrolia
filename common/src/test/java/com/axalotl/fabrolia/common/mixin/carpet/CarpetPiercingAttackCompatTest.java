package com.axalotl.fabrolia.common.mixin.carpet;

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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact-artifact, wiring and cooldown-model proof for Carpet spear attacks. */
class CarpetPiercingAttackCompatTest {

    private static final String MIXIN =
            "com/axalotl/fabrolia/common/mixin/carpet/CarpetPiercingAttackMixin";
    private static final String HELPER =
            "com/axalotl/fabrolia/common/parallelised/utils/CarpetPiercingAttackCompat";
    private static final String PLAYER = "net/minecraft/server/level/ServerPlayer";
    private static final String PIERCING =
            "net/minecraft/world/item/component/PiercingWeapon";
    private static final String CALLBACK =
            "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
    private static final String EXPECTED_CARPET_SHA256 =
            "f6ada912af65c91536d4b0d80adf26cc438253252ddaf259f2c6617ae471311c";

    @Test
    void pseudoMixinCancelsAtTheOnePreBranchCarpetFieldRead() throws Exception {
        ClassNode mixin = readClass(MIXIN);
        assertTrue(hasAnnotation(mixin, "Lorg/spongepowered/asm/mixin/Pseudo;"));
        AnnotationNode target = annotation(mixin, "Lorg/spongepowered/asm/mixin/Mixin;");
        assertEquals(List.of("carpet.helpers.EntityPlayerActionPack$ActionType$2"),
                annotationValue(target, "targets"));
        assertEquals(Boolean.FALSE, annotationValue(target, "remap"));

        MethodNode handler = method(mixin, "fabrolia$useCanonicalPiercingAttack",
                "(Lnet/minecraft/server/level/ServerPlayer;Ljava/lang/Object;L" + CALLBACK
                        + ";)V");
        AnnotationNode inject = annotation(handler,
                "Lorg/spongepowered/asm/mixin/injection/Inject;");
        assertEquals(List.of("execute(Lnet/minecraft/server/level/ServerPlayer;"
                        + "Lcarpet/helpers/EntityPlayerActionPack$Action;)Z"),
                annotationValue(inject, "method"));
        assertEquals(Boolean.TRUE, annotationValue(inject, "cancellable"));
        assertEquals(1, annotationValue(inject, "require"));
        assertEquals(1, annotationValue(inject, "allow"));

        AnnotationNode at = firstNestedAnnotation(inject, "at");
        assertEquals("FIELD", annotationValue(at, "value"));
        assertEquals("Lcarpet/helpers/EntityPlayerActionPack$Action;isContinuous:Z",
                annotationValue(at, "target"));
        assertEquals(Opcodes.GETFIELD, annotationValue(at, "opcode"));
        assertEquals(0, annotationValue(at, "ordinal"));
        assertEquals(Boolean.FALSE, annotationValue(at, "remap"));
        assertTrue(hasParameterAnnotation(handler, 1,
                "Lorg/spongepowered/asm/mixin/injection/Coerce;"),
                "the absent optional Carpet Action argument must be @Coerce Object");

        assertEquals(1, calls(handler, HELPER, "tryHandleAttack"));
        assertEquals(1, calls(handler, CALLBACK, "setReturnValue"));
        assertEquals(0, calls(handler, PLAYER, "attack"));
    }

    @Test
    void helperIsTheCanonicalSinglePiercingAttackAndHasNoCarpetLinkage() throws Exception {
        byte[] bytes = readClassBytes(HELPER);
        assertFalse(new String(bytes, StandardCharsets.ISO_8859_1)
                        .contains("carpet/helpers/"),
                "the always-loaded helper may not link an optional Carpet class");
        ClassNode helper = readClass(bytes);
        MethodNode attack = method(helper, "tryHandleAttack",
                "(Lnet/minecraft/server/level/ServerPlayer;)Z");
        assertTrue((attack.access & Opcodes.ACC_PUBLIC) != 0);
        assertTrue((attack.access & Opcodes.ACC_STATIC) != 0);

        int hand = callIndex(attack, PLAYER, "getItemInHand");
        int component = fieldIndex(attack, Opcodes.GETSTATIC,
                "net/minecraft/core/component/DataComponents", "PIERCING_WEAPON");
        int activity = callIndex(attack, PLAYER, "resetLastActionTime");
        int gate = callIndex(attack, PLAYER, "cannotAttackWithItem");
        int piercing = callIndex(attack, PIERCING, "attack");
        assertTrue(hand >= 0 && component > hand && activity > component
                        && gate > activity && piercing > gate,
                "piercing attacks must preserve activity, pass the vanilla gate, then attack");
        assertEquals(Opcodes.ICONST_5, previousRealOpcode(attack, gate),
                "the canonical 26.2 piercing grace argument is five ticks");
        assertEquals(1, calls(attack, PLAYER, "cannotAttackWithItem"));
        assertEquals(1, calls(attack, PIERCING, "attack"));
        assertEquals(0, calls(attack, PLAYER, "attack"));
        assertEquals(0, calls(attack, PLAYER, "swing"));
        assertEquals(0, calls(attack, PLAYER, "resetAttackStrengthTicker"),
                "a rejected stab must retain its accumulated cooldown");
    }

    @Test
    void exactCarpetArtifactStillHasTheAuditedBrokenEntityArm() throws Exception {
        Path artifact = carpetArtifact();
        Assumptions.assumeTrue(artifact != null,
                "exact Carpet artifact is optional outside the full-modpack workspace");
        assertEquals(EXPECTED_CARPET_SHA256, sha256(artifact),
                "a new Carpet artifact requires a fresh piercing-action audit");

        try (ZipFile zip = new ZipFile(artifact.toFile())) {
            ClassNode action = readJarClass(zip,
                    "carpet/helpers/EntityPlayerActionPack$ActionType$2");
            MethodNode execute = method(action, "execute",
                    "(Lnet/minecraft/server/level/ServerPlayer;"
                            + "Lcarpet/helpers/EntityPlayerActionPack$Action;)Z");
            int continuous = fieldIndex(execute, Opcodes.GETFIELD,
                    "carpet/helpers/EntityPlayerActionPack$Action", "isContinuous");
            int playerAttack = callIndex(execute, PLAYER, "attack");
            int swing = callIndex(execute, PLAYER, "swing");
            int resetAttack = callIndex(execute, PLAYER, "resetAttackStrengthTicker");
            int resetActivity = callIndex(execute, PLAYER, "resetLastActionTime");
            assertTrue(continuous >= 0 && playerAttack > continuous && swing > playerAttack
                            && resetAttack > swing && resetActivity > resetAttack,
                    "the exact entity arm changed; re-audit the pre-branch injection seam");
            assertEquals(1, fields(execute, Opcodes.GETFIELD,
                    "carpet/helpers/EntityPlayerActionPack$Action", "isContinuous"));
            assertEquals(1, calls(execute, PLAYER, "attack"));
            assertEquals(2, calls(execute, PLAYER, "swing"),
                    "one swing belongs to the entity arm and one to the block arm");
            assertEquals(1, calls(execute, PLAYER, "resetAttackStrengthTicker"));
            assertEquals(0, calls(execute, PLAYER, "cannotAttackWithItem"));
            assertEquals(0, calls(execute, PIERCING, "attack"));
            assertEquals(0, fields(execute, Opcodes.GETSTATIC,
                    "net/minecraft/core/component/DataComponents", "PIERCING_WEAPON"));
        }
    }

    @Test
    void onceIntervalAndContinuousPiercingModesKeepMaturingTheirCooldown() {
        assertEquals(new Outcome(1, 0, 1), simulate(Mode.ONCE, true, 100, 5));
        assertEquals(new Outcome(5, 0, 20), simulate(Mode.INTERVAL, true, 100, 5));
        assertEquals(new Outcome(6, 0, 100), simulate(Mode.CONTINUOUS, true, 100, 5));

        // Non-piercing behavior remains Carpet's exact 26.2 behavior, including its
        // deliberate continuous-entity skip.
        assertEquals(new Outcome(0, 1, 1), simulate(Mode.ONCE, false, 100, 5));
        assertEquals(new Outcome(0, 20, 20), simulate(Mode.INTERVAL, false, 100, 5));
        assertEquals(new Outcome(0, 0, 100), simulate(Mode.CONTINUOUS, false, 100, 5));
    }

    /** MC-free model of the seam: accepted attacks reset, rejected attempts do not. */
    private static Outcome simulate(Mode mode, boolean piercing, int ticks, int interval) {
        int attackTicker = 18;
        int piercingAttacks = 0;
        int playerAttacks = 0;
        int attempts = 0;
        for (int tick = 0; tick < ticks; tick++) {
            boolean attempt = switch (mode) {
                case ONCE -> tick == 0;
                case INTERVAL -> tick % interval == 0;
                case CONTINUOUS -> true;
            };
            if (attempt) {
                attempts++;
                if (piercing) {
                    if (attackTicker >= 18) {
                        piercingAttacks++;
                        attackTicker = 0;
                    }
                } else if (mode != Mode.CONTINUOUS) {
                    playerAttacks++;
                    attackTicker = 0;
                } else {
                    // Carpet reaches its unconditional reset without attacking.
                    attackTicker = 0;
                }
            }
            attackTicker++;
        }
        return new Outcome(piercingAttacks, playerAttacks, attempts);
    }

    private enum Mode { ONCE, INTERVAL, CONTINUOUS }

    private record Outcome(int piercingAttacks, int playerAttacks, int attempts) {
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

    private static byte[] readClassBytes(String internalName) throws Exception {
        try (InputStream in = CarpetPiercingAttackCompatTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            assertNotNull(in, "missing class " + internalName);
            return in.readAllBytes();
        }
    }

    private static ClassNode readClass(String internalName) throws Exception {
        return readClass(readClassBytes(internalName));
    }

    private static ClassNode readClass(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode type, String name, String desc) {
        return type.methods.stream().filter(method -> name.equals(method.name)
                        && desc.equals(method.desc)).findFirst()
                .orElseThrow(() -> new AssertionError("missing " + type.name + "." + name + desc));
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

    private static Object annotationValue(AnnotationNode annotation, String key) {
        assertNotNull(annotation, "missing annotation carrying " + key);
        if (annotation.values == null) return null;
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static AnnotationNode firstNestedAnnotation(AnnotationNode annotation, String key) {
        Object value = annotationValue(annotation, key);
        if (value instanceof AnnotationNode nested) return nested;
        if (value instanceof List<?> list && !list.isEmpty()
                && list.getFirst() instanceof AnnotationNode nested) return nested;
        throw new AssertionError("missing nested annotation " + key);
    }

    private static boolean hasParameterAnnotation(MethodNode method, int parameter, String desc) {
        return hasParameterAnnotation(method.visibleParameterAnnotations, parameter, desc)
                || hasParameterAnnotation(method.invisibleParameterAnnotations, parameter, desc);
    }

    private static boolean hasParameterAnnotation(List<AnnotationNode>[] annotations,
                                                  int parameter, String desc) {
        return annotations != null && parameter < annotations.length
                && annotation(annotations[parameter], desc) != null;
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) count++;
        }
        return count;
    }

    private static int callIndex(MethodNode method, String owner, String name) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) return index;
            index++;
        }
        return -1;
    }

    private static int fields(MethodNode method, int opcode, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == opcode
                    && owner.equals(field.owner) && name.equals(field.name)) count++;
        }
        return count;
    }

    private static int fieldIndex(MethodNode method, int opcode, String owner, String name) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == opcode
                    && owner.equals(field.owner) && name.equals(field.name)) return index;
            index++;
        }
        return -1;
    }

    private static int previousRealOpcode(MethodNode method, int before) {
        for (int index = before - 1; index >= 0; index--) {
            int opcode = method.instructions.get(index).getOpcode();
            if (opcode >= 0) return opcode;
        }
        return -1;
    }
}
