package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.ClassReader;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact wiring pins for island-side incremental block publication.
 *
 * <p>The unit JVM does not apply mixins, so this test checks both halves of
 * every fragile seam: exact 26.2 vanilla bytecode and Fabrolia's compiled
 * injector bytecode. A local-table reshape or an accidentally restored
 * iterate-then-clear drain therefore fails before it can become a silent
 * ghost-block regression in production.</p>
 */
class IslandBlockPublicationWiringTest {

    private static final String CACHE = "net/minecraft/server/level/ServerChunkCache";
    private static final String CACHE_MIXIN =
            "com/axalotl/fabrolia/common/mixin/server/ServerChunkCacheMixin";
    private static final String HOLDER = "net/minecraft/server/level/ChunkHolder";
    private static final String OPERATION =
            "com/llamalad7/mixinextras/injector/wrapoperation/Operation";

    @Test
    void blockChangedTailCapturesTheExactUniqueChunkHolderLocal() throws Exception {
        MethodNode vanilla = method(readClass(CACHE), "blockChanged",
                "(Lnet/minecraft/core/BlockPos;)V");

        List<LocalVariableNode> holders = vanilla.localVariables.stream()
                .filter(local -> ("L" + HOLDER + ";").equals(local.desc))
                .toList();
        assertEquals(1, holders.size(),
                "@Local-by-type is safe only while blockChanged has one ChunkHolder local");
        LocalVariableNode holder = holders.getFirst();

        List<MethodInsnNode> lookups = calls(vanilla).stream()
                .filter(call -> CACHE.equals(call.owner)
                        && "getVisibleChunkIfPresent".equals(call.name)
                        && ("(J)L" + HOLDER + ";").equals(call.desc))
                .toList();
        assertEquals(1, lookups.size(), "exact holder lookup changed in vanilla");
        AbstractInsnNode store = nextOpcode(lookups.getFirst());
        assertTrue(store instanceof VarInsnNode variable
                        && store.getOpcode() == Opcodes.ASTORE
                        && variable.var == holder.index,
                "the captured local must be the result of getVisibleChunkIfPresent");

        ClassNode mixin = readClass(CACHE_MIXIN);
        MethodNode injector = method(mixin, "fabrolia$queueIslandBlockUpdate",
                "(Lnet/minecraft/core/BlockPos;"
                        + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;"
                        + "L" + HOLDER + ";)V");
        List<String> injectorAnnotations = annotationStrings(injector);
        assertTrue(injectorAnnotations.contains(
                "Lorg/spongepowered/asm/mixin/injection/Inject;"));
        assertTrue(injectorAnnotations.contains("blockChanged"));
        assertTrue(injectorAnnotations.contains("TAIL"));

        List<AnnotationNode>[] parameterAnnotations = injector.invisibleParameterAnnotations;
        assertNotNull(parameterAnnotations, "compiled @Local parameter annotation");
        assertEquals(3, parameterAnnotations.length);
        assertTrue(annotationStrings(parameterAnnotations[2]).contains(
                        "Lcom/llamalad7/mixinextras/sugar/Local;"),
                "the exact unique ChunkHolder parameter must remain @Local-captured");
        assertEquals(1, calls(injector, "java/util/Set", "add",
                "(Ljava/lang/Object;)Z"));
    }

    @Test
    void demotionAfterAdmissionIsALegalVanillaHolderLifecycle() throws Exception {
        MethodNode holderChange = method(readClass(HOLDER), "blockChanged",
                "(Lnet/minecraft/core/BlockPos;)Z");
        int ticking = callIndex(holderChange, HOLDER, "getTickingChunk",
                "()Lnet/minecraft/world/level/chunk/LevelChunk;");
        int dirtyState = fieldIndex(holderChange, Opcodes.GETFIELD,
                HOLDER, "hasChangedSections");
        assertTrue(ticking >= 0 && dirtyState > ticking
                        && hasBooleanReturnBetween(holderChange, false, ticking, dirtyState),
                "exact 26.2 rejects a blockChanged generation while getTickingChunk is null");

        MethodNode cacheChange = method(readClass(CACHE), "blockChanged",
                "(Lnet/minecraft/core/BlockPos;)V");
        int holderAdmission = callIndex(cacheChange, HOLDER, "blockChanged",
                "(Lnet/minecraft/core/BlockPos;)Z");
        int globalToken = callIndex(cacheChange, "java/util/Set", "add",
                "(Ljava/lang/Object;)Z");
        assertTrue(holderAdmission >= 0 && globalToken > holderAdmission
                        && hasConditionalJumpBetween(cacheChange,
                        holderAdmission, globalToken),
                "ServerChunkCache publishes a global token only for a successfully admitted "
                        + "ticking-holder generation");
    }

    @Test
    void islandTailOrdersBlockEntitiesThenPublicationThenListenerAck() throws Exception {
        ClassNode tickUnit = readClass(
                "com/axalotl/fabrolia/common/regions/IslandTickUnit");
        List<MethodNode> matching = tickUnit.methods.stream()
                .filter(method -> callIndex(method,
                        "com/axalotl/fabrolia/common/regions/IslandBlockEntityTicks",
                        "tickIslandCells", null) >= 0)
                .filter(method -> callIndex(method,
                        "com/axalotl/fabrolia/common/regions/IslandBlockUpdateAccess",
                        "fabrolia$flushIslandBlockUpdates", null) >= 0)
                .filter(method -> callIndex(method,
                        "com/axalotl/fabrolia/common/regions/AsyncIslandPlayerTicks",
                        "runLate", null) >= 0)
                .toList();
        assertEquals(1, matching.size(),
                "all three phases must remain in one claimed island-unit body");
        MethodNode body = matching.getFirst();
        int blockEntities = callIndex(body,
                "com/axalotl/fabrolia/common/regions/IslandBlockEntityTicks",
                "tickIslandCells", null);
        int flush = callIndex(body,
                "com/axalotl/fabrolia/common/regions/IslandBlockUpdateAccess",
                "fabrolia$flushIslandBlockUpdates", null);
        int late = callIndex(body,
                "com/axalotl/fabrolia/common/regions/AsyncIslandPlayerTicks",
                "runLate", null);
        assertTrue(blockEntities < flush && flush < late,
                "authoritative block packets must precede listener prediction ACKs");

        long flushCalls = tickUnit.methods.stream()
                .flatMap(method -> calls(method).stream())
                .filter(call -> "com/axalotl/fabrolia/common/regions/IslandBlockUpdateAccess"
                        .equals(call.owner)
                        && "fabrolia$flushIslandBlockUpdates".equals(call.name))
                .count();
        assertEquals(1, flushCalls, "one publication slot per island unit");
    }

    @Test
    void islandBroadcastViewerReadsNeverReenterChunkMapsMembershipMonitor() throws Exception {
        ClassNode mixin = readClass(
                "com/axalotl/fabrolia/common/mixin/server/ChunkMapMixin");
        MethodNode move = method(mixin, "fabrolia$syncPlayerMove");
        assertWrap(move, "move");
        assertEquals(0, move.access & Opcodes.ACC_SYNCHRONIZED,
                "same-section movement must not inherit ChunkMap's method monitor");
        assertEquals(1, opcodeCount(move, Opcodes.MONITORENTER),
                "only the real membership-transition branch enters ChunkMap");
        assertEquals(2, opcodeCount(move, Opcodes.MONITOREXIT),
                "the explicit transition transaction needs normal and exceptional exits");
        assertEquals(2, calls(move, OPERATION, "call",
                "([Ljava/lang/Object;)Ljava/lang/Object;"),
                "fast and transition branches must each retain the complete vanilla body");
        assertEquals(1, calls(move,
                "com/axalotl/fabrolia/common/regions/PlayerMoveLockPolicy",
                "requiresGlobalTransaction", "(JJZZ)Z"));
        int decision = callIndex(move,
                "com/axalotl/fabrolia/common/regions/PlayerMoveLockPolicy",
                "requiresGlobalTransaction", "(JJZZ)Z");
        int enter = firstOpcode(move, Opcodes.MONITORENTER);
        assertTrue(decision >= 0 && enter > decision
                        && hasConditionalJumpBetween(move, decision, enter),
                "the policy decision must guard, rather than follow, the global monitor");

        MethodNode status = method(mixin, "fabrolia$syncPlayerMapMembership");
        assertSynchronizedWrap(status, "updatePlayerStatus");
        assertEquals(1, calls(status, OPERATION, "call",
                "([Ljava/lang/Object;)Ljava/lang/Object;"));

        MethodNode viewers = method(mixin, "fabrolia$snapshotBroadcastPlayers");
        assertUnsynchronizedWrap(viewers, "getPlayers");
        assertEquals(1, calls(viewers, OPERATION, "call",
                "([Ljava/lang/Object;)Ljava/lang/Object;"));
        assertEquals(1, calls(viewers, "java/util/List", "copyOf",
                "(Ljava/util/Collection;)Ljava/util/List;"),
                "broadcast viewers must remain detached without entering ChunkMap");

        MethodNode spawning = method(mixin,
                "fabrolia$snapshotPlayersCloseForSpawning");
        assertUnsynchronizedWrap(spawning, "getPlayersCloseForSpawning");
        assertEquals(1, calls(spawning, OPERATION, "call",
                "([Ljava/lang/Object;)Ljava/lang/Object;"));
        assertEquals(1, calls(spawning, "java/util/List", "copyOf",
                "(Ljava/util/Collection;)Ljava/util/List;"),
                "spawn viewers must remain detached without entering ChunkMap");

        ClassNode vanilla = readClass("net/minecraft/server/level/ChunkMap");
        method(vanilla, "move",
                "(Lnet/minecraft/server/level/ServerPlayer;)V");
        method(vanilla, "updatePlayerStatus",
                "(Lnet/minecraft/server/level/ServerPlayer;Z)V");
        method(vanilla, "getPlayers",
                "(Lnet/minecraft/world/level/ChunkPos;Z)Ljava/util/List;");
        method(vanilla, "getPlayersCloseForSpawning",
                "(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/List;");
    }

    @Test
    void pendingGateUsesChunkDeliveryInsteadOfThePlainVanillaLongSet() throws Exception {
        ClassNode mixin = readClass(
                "com/axalotl/fabrolia/common/mixin/server/FabroliaPlayerChunkSenderMixin");
        MethodNode pending = method(mixin, "fabrolia$isPendingConcurrent",
                "(JLcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)Z");
        List<String> wrap = annotationStrings(pending);
        assertTrue(wrap.contains(
                "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;"));
        assertTrue(wrap.contains("isPending"));
        assertEquals(1, calls(pending,
                "com/axalotl/fabrolia/common/regions/ChunkDelivery", "isPending",
                "(Lnet/minecraft/server/level/ServerPlayer;J)Z"));
        assertEquals(1, calls(pending, OPERATION, "call",
                "([Ljava/lang/Object;)Ljava/lang/Object;"),
                "the vanilla fallback is retained only until a player is bound");
        assertEquals(0, fieldReads(pending,
                "com/axalotl/fabrolia/common/mixin/server/FabroliaPlayerChunkSenderMixin",
                "pendingChunks"),
                "island publication must never touch vanilla's plain LongOpenHashSet");
        assertEquals(0, calls(pending, "it/unimi/dsi/fastutil/longs/LongSet",
                "contains", "(J)Z"));

        method(readClass("net/minecraft/server/network/PlayerChunkSender"),
                "isPending", "(J)Z");
    }

    private static void assertSynchronizedWrap(MethodNode method, String target) {
        assertTrue((method.access & Opcodes.ACC_SYNCHRONIZED) != 0,
                method.name + " must take ChunkMap's intrinsic monitor");
        assertWrap(method, target);
    }

    private static void assertUnsynchronizedWrap(MethodNode method, String target) {
        assertEquals(0, method.access & Opcodes.ACC_SYNCHRONIZED,
                method.name + " must not take ChunkMap's intrinsic monitor");
        assertEquals(0, opcodeCount(method, Opcodes.MONITORENTER),
                method.name + " must not enter any replacement monitor");
        assertEquals(0, opcodeCount(method, Opcodes.MONITOREXIT),
                method.name + " must not exit any replacement monitor");
        assertWrap(method, target);
    }

    private static void assertWrap(MethodNode method, String target) {
        List<String> annotations = annotationStrings(method);
        assertTrue(annotations.contains(
                "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;"));
        assertTrue(annotations.contains(target),
                method.name + " must bind exact target " + target);
    }

    private static int fieldReads(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && owner.equals(field.owner) && name.equals(field.name)) {
                count++;
            }
        }
        return count;
    }

    private static int fieldIndex(MethodNode method, int opcode,
                                  String owner, String name) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == opcode && owner.equals(field.owner)
                    && name.equals(field.name)) return index;
            index++;
        }
        return -1;
    }

    private static boolean hasConditionalJumpBetween(MethodNode method,
                                                     int after, int before) {
        for (int index = after + 1; index < before; index++) {
            int opcode = method.instructions.get(index).getOpcode();
            if (opcode >= Opcodes.IFEQ && opcode <= Opcodes.IF_ACMPNE) return true;
            if (opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL) return true;
        }
        return false;
    }

    private static int firstOpcode(MethodNode method, int opcode) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) return index;
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

    private static int callIndex(MethodNode method, String owner, String name,
                                 String descriptor) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && (descriptor == null || descriptor.equals(call.desc))) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static boolean hasBooleanReturnBetween(MethodNode method, boolean value,
                                                   int after, int before) {
        int expected = value ? Opcodes.ICONST_1 : Opcodes.ICONST_0;
        boolean loaded = false;
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (index <= after) {
                index++;
                continue;
            }
            if (index >= before) return false;
            int opcode = instruction.getOpcode();
            if (opcode == expected) loaded = true;
            else if (loaded && opcode == Opcodes.IRETURN) return true;
            else if (loaded && (opcode == Opcodes.ALOAD || opcode == Opcodes.ILOAD
                    || opcode == Opcodes.ISTORE || opcode == Opcodes.MONITOREXIT)) {
                // javac releases the synthetic synchronized monitor between
                // loading/storing the return value and IRETURN.
            } else if (opcode >= 0) loaded = false;
            index++;
        }
        return false;
    }

    private static int calls(MethodNode method, String owner, String name,
                             String descriptor) {
        int count = 0;
        for (MethodInsnNode call : calls(method)) {
            if (owner.equals(call.owner) && name.equals(call.name)
                    && descriptor.equals(call.desc)) count++;
        }
        return count;
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        ArrayList<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) calls.add(call);
        }
        return calls;
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        for (AbstractInsnNode next = instruction.getNext(); next != null;
             next = next.getNext()) {
            if (next.getOpcode() >= 0) return next;
        }
        return null;
    }

    private static MethodNode method(ClassNode type, String name) {
        List<MethodNode> methods = type.methods.stream()
                .filter(method -> name.equals(method.name)).toList();
        assertEquals(1, methods.size(), "expected one " + type.name + "." + name);
        return methods.getFirst();
    }

    private static MethodNode method(ClassNode type, String name, String descriptor) {
        return type.methods.stream()
                .filter(method -> name.equals(method.name)
                        && descriptor.equals(method.desc))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "missing exact method " + type.name + "." + name + descriptor));
    }

    private static ClassNode readClass(String internalName) throws Exception {
        try (InputStream input = IslandBlockPublicationWiringTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            assertNotNull(input, "missing class " + internalName);
            ClassNode type = new ClassNode();
            new ClassReader(input).accept(type, 0);
            return type;
        }
    }

    private static List<String> annotationStrings(MethodNode method) {
        ArrayList<String> values = new ArrayList<>();
        collectAnnotations(method.visibleAnnotations, values);
        collectAnnotations(method.invisibleAnnotations, values);
        return values;
    }

    private static List<String> annotationStrings(List<AnnotationNode> annotations) {
        ArrayList<String> values = new ArrayList<>();
        collectAnnotations(annotations, values);
        return values;
    }

    private static void collectAnnotations(List<AnnotationNode> annotations,
                                           List<String> values) {
        if (annotations == null) return;
        for (AnnotationNode annotation : annotations) {
            values.add(annotation.desc);
            collectValue(annotation.values, values);
        }
    }

    private static void collectValue(Object value, List<String> values) {
        if (value == null) return;
        if (value instanceof String string) {
            values.add(string);
        } else if (value instanceof AnnotationNode annotation) {
            values.add(annotation.desc);
            collectValue(annotation.values, values);
        } else if (value instanceof List<?> list) {
            for (Object nested : list) collectValue(nested, values);
        }
    }
}
