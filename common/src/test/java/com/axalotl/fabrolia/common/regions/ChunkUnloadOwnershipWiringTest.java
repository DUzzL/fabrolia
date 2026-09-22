package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the whole-transaction owner fence around ChunkMap's unload continuation. */
final class ChunkUnloadOwnershipWiringTest {

    private static final String CHUNK_MIXIN =
            "com/axalotl/fabrolia/common/mixin/server/ChunkMapMixin";
    private static final String UNLOAD_TASK = CHUNK_MIXIN
            + "$FabroliaOwnerSafeUnloadTask";
    private static final String SERVER_LEVEL_MIXIN =
            "com/axalotl/fabrolia/common/mixin/world/ServerLevelMixin";
    private static final String REGION_ACCESS =
            "com/axalotl/fabrolia/common/regions/RegionTickingAccess";
    private static final String REGION_RUNTIME =
            "com/axalotl/fabrolia/common/regions/RegionizedServer";
    private static final String ISLAND_COMMANDS =
            "com/axalotl/fabrolia/common/regions/IslandCommands";
    private static final String REGION_SCHEDULER =
            "com/axalotl/fabrolia/common/regions/RegionScheduler";
    private static final String OPERATION =
            "com/llamalad7/mixinextras/injector/wrapoperation/Operation";

    @Test
    void scheduleUnloadRetainsItsFutureAndDefersOwnerResolutionUntilExecution()
            throws Exception {
        ClassNode mixin = read(CHUNK_MIXIN);
        MethodNode wrapper = method(mixin,
                "fabrolia$fenceChunkUnloadContinuation");
        List<String> annotations = annotationStrings(wrapper);
        assertTrue(annotations.contains(
                "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;"));
        assertTrue(annotations.contains("scheduleUnload"));
        assertTrue(annotations.contains(
                "Ljava/util/concurrent/CompletableFuture;thenRunAsync("
                        + "Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)"
                        + "Ljava/util/concurrent/CompletableFuture;"));
        assertEquals(1, typeCount(wrapper, Opcodes.NEW, UNLOAD_TASK));
        assertEquals(1, callCount(wrapper, OPERATION, "call"));
        assertEquals(1, callCount(wrapper, "java/util/concurrent/CompletableFuture",
                "thenCompose"),
                "vanilla whenComplete must observe the deferred body's real terminal state");
        assertEquals(1, callCount(wrapper, REGION_SCHEDULER,
                        "publishFatalAndSelectWinner"),
                "synchronous continuation registration failures must enter fail-stop");
        assertEquals(1, callCount(wrapper, UNLOAD_TASK, "failBeforeDispatch"));
        assertEquals(0, callCount(wrapper, REGION_RUNTIME, "islandOwnerOfChunk"),
                "an owner captured when the future is registered can retire before execution");

        ClassNode taskType = read(UNLOAD_TASK);
        MethodNode execute = method(taskType, "execute");
        int revival = callIndex(execute,
                "it/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap",
                "get");
        int owner = callIndex(execute, REGION_RUNTIME, "islandOwnerOfChunk");
        int deferCommit = callIndex(execute,
                "com/axalotl/fabrolia/common/regions/RegionScheduler",
                "commitSuccessIfHealthy");
        assertTrue(revival >= 0 && owner > revival && deferCommit > owner,
                "holder identity must be revalidated before execution-time ownership and defer");
        assertEquals(1, taskType.methods.stream().mapToInt(candidate ->
                        callCount(candidate,
                                "java/util/concurrent/ConcurrentLinkedQueue",
                                "add")).sum(),
                "an owned continuation has exactly one deferred publication edge");
        assertEquals(1, callCount(execute, REGION_RUNTIME, "islandOwnerOfChunk"));
        assertEquals(1, callCount(execute, "java/util/concurrent/CompletableFuture",
                "completeExceptionally"),
                "fail-stop must terminate the future retained by vanilla's callback");
        assertEquals(1, callCount(method(taskType, "failBeforeDispatch"),
                        "java/util/concurrent/CompletableFuture",
                        "completeExceptionally"),
                "a synchronous scheduling failure must terminalize the hidden task future");

        MethodNode claimed = method(read(UNLOAD_TASK), "runWithOwnerClaim");
        int claim = callIndex(claimed, ISLAND_COMMANDS, "mainClaimScoped");
        int body = callIndex(claimed, UNLOAD_TASK, "runBody");
        int release = callIndex(claimed, ISLAND_COMMANDS, "mainRelease");
        assertTrue(claim >= 0 && body > claim && release > body,
                "the whole vanilla unload body must remain inside the current owner claim");
    }

    @Test
    void blockingDrainRunsBeforeVanillaIterationAndCannotReenterItself()
            throws Exception {
        ClassNode mixin = read(CHUNK_MIXIN);
        MethodNode head = method(mixin,
                "fabrolia$drainOwnerSafeUnloadsAtProcessHead");
        List<String> annotations = annotationStrings(head);
        assertTrue(annotations.contains("processUnloads"));
        assertTrue(annotations.contains("HEAD"));
        assertEquals(1, callCount(head, CHUNK_MIXIN,
                "fabrolia$drainDeferredOwnerUnloads"));

        MethodNode drain = method(mixin, "fabrolia$drainDeferredOwnerUnloads");
        assertEquals(1, callCount(drain, UNLOAD_TASK, "runAtProcessHead"));
        assertTrue(drain.tryCatchBlocks.stream().anyMatch(block -> block.type == null),
                "the re-entry flag must clear on every exceptional drain exit");

        MethodNode hasWork = method(mixin,
                "fabrolia$includeDeferredOwnerUnloads");
        assertTrue(annotationStrings(hasWork).contains("hasWork"));
        assertEquals(1, callCount(hasWork,
                "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable",
                "setReturnValue"),
                "the accepted custom queue must keep vanilla shutdown/idle draining alive");
    }

    @Test
    void levelSaveFlushesAcceptedUnloadsInBothClaimBranches() throws Exception {
        ClassNode serverLevelMixin = read(SERVER_LEVEL_MIXIN);
        method(serverLevelMixin, "fabrolia$quiesceIslandsForLevelSave");
        assertEquals(4, serverLevelMixin.methods.stream().mapToInt(candidate ->
                        callCount(candidate, REGION_ACCESS,
                                "fabrolia$flushDeferredOwnerUnloads")).sum(),
                "both claim branches must flush before and after the vanilla save body");

        MethodNode flush = method(read(CHUNK_MIXIN),
                "fabrolia$flushDeferredOwnerUnloads");
        assertTrue((flush.access & Opcodes.ACC_PUBLIC) != 0);
        assertEquals(1, callCount(flush, CHUNK_MIXIN,
                "fabrolia$drainDeferredOwnerUnloads"));
        method(read(REGION_ACCESS), "fabrolia$flushDeferredOwnerUnloads");
    }

    @Test
    void vanillaStillUsesIdentityCheckedRevivalAndOneAsyncContinuation()
            throws Exception {
        ClassNode vanilla = read("net/minecraft/server/level/ChunkMap");
        MethodNode schedule = method(vanilla, "scheduleUnload");
        assertEquals(1, callCount(schedule,
                "java/util/concurrent/CompletableFuture", "thenRunAsync"));
        MethodNode tick = method(vanilla, "tick");
        assertEquals(1, callCount(tick, vanilla.name, "processUnloads"),
                "hasWork liveness relies on every vanilla chunk-map tick "
                        + "reaching processUnloads");
        assertEquals(1, vanilla.methods.stream()
                        .filter(candidate -> candidate.name.startsWith(
                                "lambda$scheduleUnload"))
                        .flatMap(candidate -> methodCalls(candidate).stream())
                        .filter(call -> call.name.equals("remove")
                                && call.desc.equals("(JLjava/lang/Object;)Z"))
                        .count(),
                "a stale deferred task may only remove its exact original holder");
    }

    private static ClassNode read(String internalName) throws Exception {
        try (InputStream input = ChunkUnloadOwnershipWiringTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            assertNotNull(input, "missing class " + internalName);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }

    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(candidate -> candidate.name.equals(name))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "missing " + owner.name + "." + name));
    }

    private static int typeCount(MethodNode method, int opcode, String type) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode typed
                    && typed.getOpcode() == opcode && typed.desc.equals(type)) {
                count++;
            }
        }
        return count;
    }

    private static int callCount(MethodNode method, String owner, String name) {
        int count = 0;
        for (MethodInsnNode call : methodCalls(method)) {
            if (call.owner.equals(owner) && call.name.equals(name)) count++;
        }
        return count;
    }

    private static int callIndex(MethodNode method, String owner, String name) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static int callIndexByName(MethodNode method, String name, int occurrence) {
        int index = 0;
        int seen = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals(name)
                    && seen++ == occurrence) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static List<MethodInsnNode> methodCalls(MethodNode method) {
        ArrayList<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) calls.add(call);
        }
        return calls;
    }

    private static List<String> annotationStrings(MethodNode method) {
        ArrayList<String> values = new ArrayList<>();
        collectAnnotations(method.visibleAnnotations, values);
        collectAnnotations(method.invisibleAnnotations, values);
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
