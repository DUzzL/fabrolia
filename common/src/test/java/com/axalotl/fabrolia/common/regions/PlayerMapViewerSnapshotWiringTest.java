package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-bytecode pins for the component-local player-view snapshot boundary.
 *
 * <p>The 2026-08-25 production deadlock was a lock-order inversion: Main held
 * ChunkMap and entered a C2ME holder, while an island publisher held that holder
 * and entered ChunkMap through {@code getPlayers}. These checks make the forbidden
 * viewer-read edge visible without needing a transformed Minecraft runtime.</p>
 */
class PlayerMapViewerSnapshotWiringTest {

    private static final String CHUNK_MAP_MIXIN =
            "com/axalotl/fabrolia/common/mixin/server/ChunkMapMixin";
    private static final String CHUNK_MAP =
            "net/minecraft/server/level/ChunkMap";
    private static final String GENERATION_CHUNK_HOLDER =
            "net/minecraft/server/level/GenerationChunkHolder";
    private static final String PLAYER_MAP = "net/minecraft/server/level/PlayerMap";
    private static final String PLAYER_MAP_MIXIN =
            "com/axalotl/fabrolia/common/mixin/server/PlayerMapMixin";
    private static final String MOVE_SNAPSHOT =
            "com/axalotl/fabrolia/common/regions/PlayerMoveReadSnapshot";
    private static final String IDENTITY_SET =
            "it/unimi/dsi/fastutil/objects/ReferenceLinkedOpenHashSet";
    private static final String SERVER_PLAYER =
            "net/minecraft/server/level/ServerPlayer";
    private static final String OPERATION =
            "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
    private static final String OPERATION_CALL_DESC =
            "([Ljava/lang/Object;)Ljava/lang/Object;";

    @Test
    void chunkMapKeepsWriteTransactionsButViewerReadsAreMonitorFree() throws Exception {
        ClassNode mixin = readClass(CHUNK_MAP_MIXIN);

        MethodNode move = method(mixin, "fabrolia$syncPlayerMove");
        assertConditionalPlayerMoveRoute(move);
        assertMoveDecisionSnapshotWiring(mixin, move);
        assertSynchronizedRoute(method(mixin, "fabrolia$syncPlayerMapMembership"),
                "updatePlayerStatus");

        MethodNode viewers = method(mixin, "fabrolia$snapshotBroadcastPlayers");
        assertMonitorFreeRoute(viewers, "getPlayers");
        assertEquals(1, calls(viewers, "java/util/List", "copyOf",
                "(Ljava/util/Collection;)Ljava/util/List;"),
                "compatibility viewer lists must be detached without ChunkMap");

        MethodNode spawning = method(mixin,
                "fabrolia$snapshotPlayersCloseForSpawning");
        assertMonitorFreeRoute(spawning, "getPlayersCloseForSpawning");
        assertEquals(1, calls(spawning, "java/util/List", "copyOf",
                "(Ljava/util/Collection;)Ljava/util/List;"),
                "compatibility spawn lists must be detached without ChunkMap");
    }

    @Test
    void generationReferenceReleaseNeverAcquiresChunkMap() throws Exception {
        ClassNode mixin = readClass(CHUNK_MAP_MIXIN);
        long releaseWrappers = mixin.methods.stream()
                .filter(candidate -> annotationStrings(candidate).contains(
                        "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;"))
                .filter(candidate -> annotationStrings(candidate).contains(
                        "releaseGeneration"))
                .count();
        assertEquals(0, releaseWrappers,
                "the atomic generation-reference release must not grow a ChunkMap lock");
        assertEquals(0, mixin.methods.stream()
                        .filter(candidate -> "releaseGeneration".equals(candidate.name))
                        .count(),
                "the obsolete releaseGeneration mixin handler must stay deleted");

        MethodNode release = method(readClass(CHUNK_MAP), "releaseGeneration",
                "(L" + GENERATION_CHUNK_HOLDER + ";)V");
        assertTrue((release.access & Opcodes.ACC_PUBLIC) != 0,
                "exact 26.2 public releaseGeneration seam changed");
        assertEquals(0, release.access & Opcodes.ACC_SYNCHRONIZED,
                "vanilla releaseGeneration must stay monitor-free");
        assertEquals(0, opcodeCount(release, Opcodes.MONITORENTER));
        assertEquals(0, opcodeCount(release, Opcodes.MONITOREXIT));
        assertEquals(1, calls(release, GENERATION_CHUNK_HOLDER,
                "decreaseGenerationRefCount", "()V"),
                "releaseGeneration must remain the exact atomic holder decrement");
        assertEquals(1, invocationCount(release),
                "releaseGeneration gained non-holder work that needs a fresh lock audit");
    }

    @Test
    void vanillaMoveDecisionAndMembershipCallerCensusStayExact() throws Exception {
        ClassNode chunkMap = readClass(CHUNK_MAP);
        MethodNode move = method(chunkMap, "move",
                "(L" + SERVER_PLAYER + ";)V");
        assertEquals(1, calls(move, SERVER_PLAYER, "getLastSectionPos",
                "()Lnet/minecraft/core/SectionPos;"));
        assertEquals(1, calls(move, "net/minecraft/core/SectionPos", "of",
                "(Lnet/minecraft/world/level/entity/EntityAccess;)"
                        + "Lnet/minecraft/core/SectionPos;"));
        assertEquals(1, calls(move, PLAYER_MAP, "ignored",
                "(L" + SERVER_PLAYER + ";)Z"));
        assertEquals(1, calls(move, CHUNK_MAP, "skipPlayer",
                "(L" + SERVER_PLAYER + ";)Z"));

        String statusDesc = "(L" + SERVER_PLAYER + ";Z)V";
        MethodNode add = method(chunkMap, "addEntity",
                "(Lnet/minecraft/world/entity/Entity;)V");
        MethodNode remove = method(chunkMap, "removeEntity",
                "(Lnet/minecraft/world/entity/Entity;)V");
        assertEquals(1, calls(add, CHUNK_MAP, "updatePlayerStatus", statusDesc));
        assertEquals(1, calls(remove, CHUNK_MAP, "updatePlayerStatus", statusDesc));
        assertEquals(2, chunkMap.methods.stream()
                        .mapToInt(candidate -> calls(candidate, CHUNK_MAP,
                                "updatePlayerStatus", statusDesc))
                        .sum(),
                "exact 26.2 membership transitions are tracking start/end only; skipPlayer "
                        + "can still change independently, so move needs stable read replay");
    }

    @Test
    void vanillaPlayerMapOwnsAllSevenMapOperationsAndDetachesItsOnlyLiveView()
            throws Exception {
        ClassNode vanilla = readClass(PLAYER_MAP);
        List<FieldNode> maps = vanilla.fields.stream()
                .filter(field -> "players".equals(field.name)
                        && "Lit/unimi/dsi/fastutil/objects/Object2BooleanMap;"
                        .equals(field.desc))
                .toList();
        assertEquals(1, maps.size(), "exact 26.2 PlayerMap storage shape changed");

        MethodNode vanillaView = method(vanilla, "getAllPlayers",
                "()Ljava/util/Set;");
        assertEquals(1, calls(vanillaView,
                "it/unimi/dsi/fastutil/objects/Object2BooleanMap", "keySet",
                "()Lit/unimi/dsi/fastutil/objects/ObjectSet;"),
                "getAllPlayers is the sole live fastutil view that must be detached");

        Map<String, String> pureRoutes = Map.of(
                "fabrolia$lockedAddPlayer", "addPlayer",
                "fabrolia$lockedRemovePlayer", "removePlayer",
                "fabrolia$lockedIgnorePlayer", "ignorePlayer",
                "fabrolia$lockedUnIgnorePlayer", "unIgnorePlayer",
                "fabrolia$lockedIgnoredOrUnknown", "ignoredOrUnknown",
                "fabrolia$lockedIgnored", "ignored");

        ClassNode mixin = readClass(PLAYER_MAP_MIXIN);
        for (Map.Entry<String, String> route : pureRoutes.entrySet()) {
            MethodNode handler = method(mixin, route.getKey());
            assertSynchronizedRoute(handler, route.getValue());
            assertEquals(0, allocations(handler),
                    route.getKey() + " must remain a pure guarded map operation");
            assertEquals(0, calls(handler, IDENTITY_SET, "addAll",
                    "(Ljava/util/Collection;)Z"));
        }

        MethodNode snapshot = method(mixin, "fabrolia$snapshotAllPlayers");
        assertSynchronizedRoute(snapshot, "getAllPlayers");
        int original = callIndex(snapshot, OPERATION, "call", OPERATION_CALL_DESC);
        int copy = callIndex(snapshot, IDENTITY_SET, "addAll",
                "(Ljava/util/Collection;)Z");
        assertTrue(original >= 0 && copy > original,
                "the live key set must be copied while PlayerMap's method monitor is held");
        assertEquals(1, allocations(snapshot),
                "getAllPlayers must allocate exactly one detached identity snapshot");
        assertEquals(1, calls(snapshot, IDENTITY_SET, "<init>", "(I)V"),
                "the snapshot must be pre-sized from the live view");
        assertEquals(1, calls(snapshot, IDENTITY_SET, "addAll",
                "(Ljava/util/Collection;)Z"),
                "the snapshot must preserve identity and source iteration order");
        assertEquals(0, calls(snapshot, "java/util/Set", "copyOf",
                "(Ljava/util/Collection;)Ljava/util/Set;"),
                "Set.copyOf would discard PlayerMap iteration and identity semantics");

        method(vanilla, "addPlayer", "(L" + SERVER_PLAYER + ";Z)V");
        method(vanilla, "removePlayer", "(L" + SERVER_PLAYER + ";)V");
        method(vanilla, "ignorePlayer", "(L" + SERVER_PLAYER + ";)V");
        method(vanilla, "unIgnorePlayer", "(L" + SERVER_PLAYER + ";)V");
        method(vanilla, "ignoredOrUnknown", "(L" + SERVER_PLAYER + ";)Z");
        method(vanilla, "ignored", "(L" + SERVER_PLAYER + ";)Z");
    }

    @Test
    void playerMapMixinIsRegisteredExactlyOnce() throws Exception {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("fabrolia.common.mixins.json")) {
            assertNotNull(input, "missing common mixin registration");
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            String registration = "\"server.PlayerMapMixin\"";
            assertEquals(1, occurrences(json, registration),
                    "PlayerMap component lock must be installed exactly once");
        }
    }

    private static void assertSynchronizedRoute(MethodNode method, String target) {
        assertTrue((method.access & Opcodes.ACC_SYNCHRONIZED) != 0,
                method.name + " must use its target instance monitor");
        assertEquals(0, method.access & Opcodes.ACC_STATIC,
                method.name + " must not synchronize on a class monitor");
        assertWrapRoute(method, target);
    }

    private static void assertConditionalPlayerMoveRoute(MethodNode method) {
        assertEquals(0, method.access & Opcodes.ACC_SYNCHRONIZED,
                "ordinary same-section movement must remain monitor-free");
        assertEquals(1, opcodeCount(method, Opcodes.MONITORENTER),
                "one explicit ChunkMap transaction must guard real membership changes");
        assertEquals(2, opcodeCount(method, Opcodes.MONITOREXIT),
                "the transition transaction must release on normal and exceptional exits");
        List<String> annotations = annotationStrings(method);
        assertTrue(annotations.contains(
                "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;"));
        assertTrue(annotations.contains("move"));
        assertEquals(2, calls(method, OPERATION, "call", OPERATION_CALL_DESC),
                "fast and transition branches must both execute vanilla move");
        assertEquals(1, calls(method,
                "com/axalotl/fabrolia/common/regions/PlayerMoveLockPolicy",
                "requiresGlobalTransaction", "(JJZZ)Z"));
    }

    private static void assertMoveDecisionSnapshotWiring(ClassNode mixin, MethodNode move) {
        assertEquals(1, calls(move, MOVE_SNAPSHOT, "pushStable",
                "(Lnet/minecraft/core/SectionPos;Lnet/minecraft/core/SectionPos;ZZ)V"),
                "the monitor-free branch must publish one immutable decision tuple");
        assertEquals(1, calls(move, MOVE_SNAPSHOT, "pushLive", "()V"),
                "a nested transition move must mask any outer fast-path tuple");
        assertEquals(4, calls(move, MOVE_SNAPSHOT, "pop", "()V"),
                "both branches must restore their snapshot stack on success and failure");
        List<Integer> originals = callIndices(move, OPERATION, "call", OPERATION_CALL_DESC);
        assertEquals(2, originals.size());
        for (int original : originals) {
            assertTrue(hasThrowableCleanupAroundCall(move, original, MOVE_SNAPSHOT, "pop"),
                    "every vanilla move body must restore its snapshot after Throwable");
        }

        assertExpressionSnapshot(mixin, "fabrolia$stableMoveLastSection",
                "Lnet/minecraft/server/level/ServerPlayer;getLastSectionPos()"
                        + "Lnet/minecraft/core/SectionPos;",
                "lastSectionOr", "(Lnet/minecraft/core/SectionPos;)"
                        + "Lnet/minecraft/core/SectionPos;");
        assertExpressionSnapshot(mixin, "fabrolia$stableMoveCurrentSection",
                "Lnet/minecraft/core/SectionPos;of("
                        + "Lnet/minecraft/world/level/entity/EntityAccess;)"
                        + "Lnet/minecraft/core/SectionPos;",
                "currentSectionOr", "(Lnet/minecraft/core/SectionPos;)"
                        + "Lnet/minecraft/core/SectionPos;");
        assertExpressionSnapshot(mixin, "fabrolia$stableMoveIgnored",
                "Lnet/minecraft/server/level/PlayerMap;ignored("
                        + "Lnet/minecraft/server/level/ServerPlayer;)Z",
                "ignoredOr", "(Z)Z");
        assertExpressionSnapshot(mixin, "fabrolia$stableMoveSkip",
                "Lnet/minecraft/server/level/ChunkMap;skipPlayer("
                        + "Lnet/minecraft/server/level/ServerPlayer;)Z",
                "skipOr", "(Z)Z");
    }

    private static void assertExpressionSnapshot(ClassNode mixin, String handlerName,
                                                 String target, String helperName,
                                                 String helperDesc) {
        MethodNode handler = method(mixin, handlerName);
        List<String> annotations = annotationStrings(handler);
        assertTrue(annotations.contains(
                "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;"));
        assertTrue(annotations.contains("move"));
        assertTrue(annotations.contains(target),
                handlerName + " must bind one exact vanilla decision read");
        assertEquals(1, calls(handler, MOVE_SNAPSHOT, helperName, helperDesc));
    }

    private static void assertMonitorFreeRoute(MethodNode method, String target) {
        assertEquals(0, method.access & Opcodes.ACC_SYNCHRONIZED,
                method.name + " must not use ChunkMap's method monitor");
        assertEquals(0, opcodeCount(method, Opcodes.MONITORENTER),
                method.name + " must not enter a substitute ChunkMap lock");
        assertEquals(0, opcodeCount(method, Opcodes.MONITOREXIT),
                method.name + " must not exit a substitute ChunkMap lock");
        assertWrapRoute(method, target);
    }

    private static void assertWrapRoute(MethodNode method, String target) {
        List<String> annotations = annotationStrings(method);
        assertTrue(annotations.contains(
                "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;"));
        assertTrue(annotations.contains(target),
                method.name + " must bind exact target " + target);
        assertEquals(1, calls(method, OPERATION, "call", OPERATION_CALL_DESC),
                method.name + " must retain exactly one original body");
    }

    private static int allocations(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.NEW) {
                count++;
            }
        }
        return count;
    }

    private static int invocationCount(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode) count++;
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

    private static int callIndex(MethodNode method, String owner,
                                 String name, String desc) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && desc.equals(call.desc)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static int calls(MethodNode method, String owner,
                             String name, String desc) {
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

    private static List<Integer> callIndices(MethodNode method, String owner,
                                             String name, String desc) {
        java.util.ArrayList<Integer> result = new java.util.ArrayList<>();
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && desc.equals(call.desc)) result.add(index);
            index++;
        }
        return result;
    }

    private static boolean hasThrowableCleanupAroundCall(
            MethodNode method, int guardedCall, String cleanupOwner, String cleanupName) {
        for (var block : method.tryCatchBlocks) {
            int start = method.instructions.indexOf(block.start);
            int end = method.instructions.indexOf(block.end);
            int handler = method.instructions.indexOf(block.handler);
            if (block.type != null && !"java/lang/Throwable".equals(block.type)
                    || start < 0 || start > guardedCall || end <= guardedCall
                    || handler < 0) continue;
            boolean cleaned = false;
            int index = 0;
            for (AbstractInsnNode instruction : method.instructions) {
                if (index < handler) {
                    index++;
                    continue;
                }
                if (instruction instanceof MethodInsnNode call
                        && cleanupOwner.equals(call.owner)
                        && cleanupName.equals(call.name)) cleaned = true;
                if (instruction.getOpcode() == Opcodes.ATHROW && cleaned) return true;
                index++;
            }
        }
        return false;
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private static MethodNode method(ClassNode owner, String name) {
        List<MethodNode> matches = owner.methods.stream()
                .filter(method -> name.equals(method.name))
                .toList();
        assertEquals(1, matches.size(), owner.name + "." + name);
        return matches.getFirst();
    }

    private static MethodNode method(ClassNode owner, String name, String desc) {
        return owner.methods.stream()
                .filter(method -> name.equals(method.name) && desc.equals(method.desc))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing " + owner.name + "." + name + desc));
    }

    private static ClassNode readClass(String internalName) throws Exception {
        String resource = internalName + ".class";
        try (InputStream input = PlayerMapViewerSnapshotWiringTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, "missing class resource " + resource);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.EXPAND_FRAMES);
            return node;
        }
    }

    private static List<String> annotationStrings(MethodNode method) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        if (method.visibleAnnotations != null) {
            method.visibleAnnotations.forEach(annotation -> collect(annotation, values));
        }
        if (method.invisibleAnnotations != null) {
            method.invisibleAnnotations.forEach(annotation -> collect(annotation, values));
        }
        return values;
    }

    private static void collect(AnnotationNode annotation, List<String> values) {
        values.add(annotation.desc);
        if (annotation.values == null) return;
        for (Object value : annotation.values) collectValue(value, values);
    }

    @SuppressWarnings("unchecked")
    private static void collectValue(Object value, List<String> values) {
        if (value instanceof String string) {
            values.add(string);
        } else if (value instanceof AnnotationNode annotation) {
            collect(annotation, values);
        } else if (value instanceof List<?> list) {
            list.forEach(element -> collectValue(element, values));
        }
    }
}
