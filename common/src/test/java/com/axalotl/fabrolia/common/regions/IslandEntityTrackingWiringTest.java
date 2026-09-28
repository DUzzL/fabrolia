package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Compiled-bytecode pins for Main-membership/island-publication entity tracking. */
class IslandEntityTrackingWiringTest {

    private static final String TRACKING =
            "com/axalotl/fabrolia/common/regions/IslandEntityTracking";
    private static final String TRACKER_ACCESS =
            "com/axalotl/fabrolia/common/regions/IslandTrackedEntityAccess";
    private static final String SERVER_ENTITY = "net/minecraft/server/level/ServerEntity";
    private static final String SERVER_ENTITY_MIXIN =
            "com/axalotl/fabrolia/common/mixin/server/ServerEntityIslandTrackingMixin";
    private static final String TRACKED_ENTITY_MIXIN =
            "com/axalotl/fabrolia/common/mixin/server/ChunkMapTrackedEntityMixin";
    private static final String PENDING_PAIRING =
            "com/axalotl/fabrolia/common/regions/IslandPendingPairing";
    private static final String PAIRING_INTENT = PENDING_PAIRING + "$Intent";
    /** Identity-keyed, insertion-ordered: O(1) per-viewer coalescing with FIFO commit. */
    private static final String PENDING_QUEUE =
            "it/unimi/dsi/fastutil/objects/Reference2ReferenceLinkedOpenHashMap";
    private static final String P_ISLAND =
            "com/axalotl/fabrolia/common/regions/IslandRegistry$PIsland";
    private static final String REGIONIZED_SERVER =
            "com/axalotl/fabrolia/common/regions/RegionizedServer";
    private static final String REGION_SCHEDULER =
            "com/axalotl/fabrolia/common/regions/RegionScheduler";
    private static final String A5 =
            "com/axalotl/fabrolia/common/regions/AsyncIslandPlayerTicks";

    @Test
    void registeredServerEntityWrapperSerializesDeferOrOriginalOnExactTracker()
            throws Exception {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("fabrolia.common.mixins.json")) {
            assertNotNull(input, "compiled common mixin config");
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(1, occurrences(json, "server.ServerEntityIslandTrackingMixin"),
                    "the ServerEntity publication seam must be registered exactly once");
        }

        ClassNode mixin = readClass(SERVER_ENTITY_MIXIN);
        assertTrue(mixinTargets(mixin, SERVER_ENTITY),
                "the deferral belongs on ServerEntity.sendChanges itself");

        MethodNode wrapper = method(mixin,
                "fabrolia$routeAutonomousEntityPublication",
                "(Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)V");
        AnnotationNode wrap = annotation(wrapper,
                "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");
        assertNotNull(wrap, "sendChanges must use an exact required WrapMethod seam");
        assertTrue(annotationValueContains(wrap, "method", "sendChanges"));

        int monitorEnter = opcodeIndex(wrapper, Opcodes.MONITORENTER);
        assertTrue(monitorEnter >= 0, "publication venue selection needs the tracker monitor");
        AbstractInsnNode enter = realInstructionAt(wrapper, monitorEnter);
        AbstractInsnNode monitorStore = previousReal(enter);
        AbstractInsnNode monitorDup = previousReal(monitorStore);
        AbstractInsnNode monitorField = previousReal(monitorDup);
        assertTrue(monitorStore instanceof VarInsnNode store
                        && store.getOpcode() == Opcodes.ASTORE
                        && monitorDup != null && monitorDup.getOpcode() == Opcodes.DUP
                        && monitorField instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.GETFIELD
                        && SERVER_ENTITY_MIXIN.equals(field.owner)
                        && "synchronizer".equals(field.name),
                "the monitor must be the exact ServerEntity.Synchronizer/TrackedEntity");

        int route = callIndex(wrapper, TRACKING, "route",
                "(Ljava/lang/Object;Lnet/minecraft/world/entity/Entity;)L"
                        + TRACKING + "$Route;");
        int beginMain = callIndexAfter(wrapper, TRACKING, "beginMainPublication",
                "(Ljava/lang/Object;Lnet/minecraft/world/entity/Entity;)Z", route);
        int deferDuplicate = callIndexAfter(wrapper, TRACKING,
                "deferDuplicateMainPublication", "(Ljava/lang/Object;)V", beginMain);
        int original = callIndex(wrapper,
                "com/llamalad7/mixinextras/injector/wrapoperation/Operation",
                "call", "([Ljava/lang/Object;)Ljava/lang/Object;");
        int markPublished = callIndexAfter(wrapper, TRACKER_ACCESS,
                "fabrolia$markIslandTrackingPublished", "()V", original);
        int commitPairings = callIndex(wrapper, TRACKING,
                "commitPendingPairingsAfterMain", "(Ljava/lang/Object;)V");
        int finishMain = callIndexAfter(wrapper, TRACKING,
                "finishMainPublication", "(Ljava/lang/Object;)V", commitPairings);
        assertTrue(route > monitorEnter && beginMain > route
                        && deferDuplicate > beginMain && original > deferDuplicate
                        && hasBranchBetween(wrapper, beginMain, original)
                        && markPublished > original
                        && commitPairings > markPublished && finishMain > commitPairings,
                "Route.MAIN must acquire its per-tick stamp or defer; only an actual original "
                        + "send increments generation, commits membership, and maintains cadence");
        assertEquals(1, callCount(wrapper, TRACKING, "route"));
        assertEquals(1, callCount(wrapper,
                "com/llamalad7/mixinextras/injector/wrapoperation/Operation", "call"));
        assertTrue(opcodeCount(wrapper, Opcodes.MONITOREXIT) >= 2
                        && !wrapper.tryCatchBlocks.isEmpty(),
                "the exact tracker monitor must release on normal and exceptional exits");

        for (String fieldName : List.of("entity", "synchronizer")) {
            FieldNode field = field(mixin, fieldName);
            assertNotNull(annotation(field, "Lorg/spongepowered/asm/mixin/Shadow;"));
            assertNotNull(annotation(field, "Lorg/spongepowered/asm/mixin/Final;"));
        }
    }

    @Test
    void trackerDuckPublishesThroughServerEntityUnderThePairingMonitor()
            throws Exception {
        ClassNode tracker = readClass(TRACKED_ENTITY_MIXIN);
        assertTrue(mixinTargets(tracker, "net/minecraft/server/level/ChunkMap$TrackedEntity"));
        assertTrue(tracker.interfaces.contains(TRACKER_ACCESS),
                "the exact pairing object must also be the persistent island tracker key");

        MethodNode entity = method(tracker, "fabrolia$trackedEntity",
                "()Lnet/minecraft/world/entity/Entity;");
        assertEquals(1, fieldReadCount(entity, TRACKED_ENTITY_MIXIN, "entity"));

        MethodNode state = method(tracker, "fabrolia$islandTrackingState", "()I");
        MethodNode viewers = method(tracker, "fabrolia$hasIslandTrackingViewers", "()Z");
        MethodNode pending = method(tracker, "fabrolia$hasPendingIslandPairings", "()Z");
        MethodNode queueBulk = method(tracker, "fabrolia$queueIslandBulkPairingRefresh",
                "()Z");
        MethodNode queue = method(tracker, "fabrolia$queueIslandPairing",
                "(Lnet/minecraft/server/level/ServerPlayer;)Z");
        MethodNode queueRemoval = method(tracker, "fabrolia$queueIslandRemoval",
                "(Lnet/minecraft/server/level/ServerPlayer;)Z");
        MethodNode cancel = method(tracker, "fabrolia$cancelIslandPairing",
                "(Lnet/minecraft/server/level/ServerPlayer;)V");
        MethodNode commit = method(tracker, "fabrolia$commitIslandPairings", "()V");
        MethodNode owner = method(tracker, "fabrolia$islandTrackingOwner",
                "()L" + P_ISLAND + ";");
        MethodNode setOwner = method(tracker, "fabrolia$setIslandTrackingOwner",
                "(L" + P_ISLAND + ";)V");
        MethodNode deactivate = method(tracker, "fabrolia$deactivateIslandTracking", "()V");
        MethodNode quarantine = method(tracker, "fabrolia$quarantineIslandTracking", "()V");
        MethodNode tryMain = method(tracker, "fabrolia$tryScheduleMainTracking", "()J");
        MethodNode finishMain = method(tracker, "fabrolia$finishMainTrackingSchedule", "(J)V");
        MethodNode beginMain = method(tracker,
                "fabrolia$tryBeginMainTrackingPublication", "(I)Z");
        MethodNode generation = method(tracker,
                "fabrolia$islandTrackingPublicationGeneration", "()J");
        MethodNode markPublished = method(tracker,
                "fabrolia$markIslandTrackingPublished", "()V");
        MethodNode publish = method(tracker, "fabrolia$sendIslandChanges", "()Z");
        for (MethodNode guarded : List.of(state, viewers, pending, queueBulk, queue, queueRemoval,
                cancel, commit, owner, setOwner, deactivate, quarantine, tryMain,
                finishMain, beginMain, generation, markPublished, publish)) {
            assertTrue((guarded.access & Opcodes.ACC_SYNCHRONIZED) != 0,
                    guarded.name + " must share the tracker intrinsic monitor");
        }
        assertEquals(1, fieldReadCount(viewers, TRACKED_ENTITY_MIXIN, "seenBy"));
        assertEquals(1, callCount(viewers, "java/util/Set", "isEmpty"));
        assertEquals(1, fieldReadCount(owner, TRACKED_ENTITY_MIXIN,
                "fabrolia$islandTrackingOwner"));
        assertEquals(1, fieldWriteCount(setOwner, TRACKED_ENTITY_MIXIN,
                "fabrolia$islandTrackingOwner"));
        int generationBefore = fieldReadIndex(publish, TRACKED_ENTITY_MIXIN,
                "fabrolia$islandTrackingPublicationGeneration");
        int exactSend = callIndex(publish, SERVER_ENTITY, "sendChanges", "()V");
        int generationAfter = fieldOpcodeIndexAfter(publish, Opcodes.GETFIELD,
                TRACKED_ENTITY_MIXIN, "fabrolia$islandTrackingPublicationGeneration",
                exactSend);
        assertTrue(generationBefore >= 0 && exactSend > generationBefore
                        && generationAfter > exactSend
                        && opcodeCount(publish, Opcodes.LCMP) == 1,
                "sendIslandChanges reports success only when the wrapped original increments "
                        + "the monotonic publication generation");
        assertEquals(1, callCount(publish, SERVER_ENTITY, "sendChanges"));
        assertEquals(1, fieldReadCount(publish, TRACKED_ENTITY_MIXIN, "serverEntity"));
        assertEquals(1, fieldReadCount(publish, TRACKED_ENTITY_MIXIN,
                "fabrolia$islandTrackingState"));

        FieldNode lastMainTick = field(tracker, "fabrolia$lastMainTrackingServerTick");
        FieldNode publicationGeneration = field(tracker,
                "fabrolia$islandTrackingPublicationGeneration");
        assertEquals("I", lastMainTick.desc);
        assertEquals("J", publicationGeneration.desc);
        MethodNode constructorHook = method(tracker,
                "fabrolia$initializeIslandTrackingState",
                "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V");
        AnnotationNode constructorInject = annotation(constructorHook,
                "Lorg/spongepowered/asm/mixin/injection/Inject;");
        assertNotNull(constructorInject,
                "runtime state must come from a merged target-constructor hook");
        assertTrue(annotationValueContains(constructorInject, "method",
                "<init>(Lnet/minecraft/server/level/ChunkMap;"
                        + "Lnet/minecraft/world/entity/Entity;IIZ)V"));
        AnnotationNode constructorAt = firstNestedAnnotation(
                constructorInject, "at");
        assertEquals("RETURN", annotationValue(constructorAt, "value"),
                "the target must be fully initialized before Fabrolia state is installed");
        assertEquals(Integer.valueOf(1), annotationValue(constructorInject, "require"),
                "a missing runtime constructor seam must fail mixin application");

        int activeWrite = fieldWriteIndex(constructorHook, TRACKED_ENTITY_MIXIN,
                "fabrolia$islandTrackingState");
        int initialTickWrite = fieldWriteIndex(constructorHook, TRACKED_ENTITY_MIXIN,
                "fabrolia$lastMainTrackingServerTick");
        AbstractInsnNode initialTick = previousReal(realInstructionAt(
                constructorHook, initialTickWrite));
        int pendingAllocation = typeIndex(constructorHook, Opcodes.NEW, PENDING_QUEUE);
        int pendingConstructor = callIndexAfter(constructorHook,
                PENDING_QUEUE, "<init>", "(I)V", pendingAllocation);
        int pendingWrite = fieldWriteIndex(constructorHook, TRACKED_ENTITY_MIXIN,
                "fabrolia$pendingIslandPairings");
        int selfTrackerRegistration = callIndexAfter(constructorHook, TRACKING,
                "registerPlayerTracker", "(L" + TRACKER_ACCESS + ";)V", pendingWrite);
        AbstractInsnNode pendingCapacity = previousReal(realInstructionAt(
                constructorHook, pendingConstructor));
        assertTrue(activeWrite >= 0 && initialTickWrite > activeWrite
                        && pendingAllocation > initialTickWrite
                        && pendingConstructor > pendingAllocation
                        && pendingWrite > pendingConstructor
                        && selfTrackerRegistration > pendingWrite,
                "the exact target-constructor hook must install every non-default tracker value "
                        + "before registering the player's self publisher");
        assertTrue(initialTick instanceof LdcInsnNode constant
                        && Integer.valueOf(Integer.MIN_VALUE).equals(constant.cst),
                "server tick zero must remain a valid first publication generation");
        assertTrue(pendingCapacity != null && pendingCapacity.getOpcode() == Opcodes.ICONST_2,
                "the first login must see the initialized bounded pairing queue");
        MethodNode discardedMixinConstructor = method(tracker, "<init>", "()V");
        assertEquals(0, fieldWriteCount(discardedMixinConstructor,
                TRACKED_ENTITY_MIXIN, "fabrolia$pendingIslandPairings"),
                "tests must not rely on the mixin Java initializer discarded at transform time");
        assertEquals(0, fieldWriteCount(discardedMixinConstructor,
                TRACKED_ENTITY_MIXIN, "fabrolia$lastMainTrackingServerTick"));
        int beginRead = fieldReadIndex(beginMain, TRACKED_ENTITY_MIXIN,
                "fabrolia$lastMainTrackingServerTick");
        int beginWrite = fieldWriteIndex(beginMain, TRACKED_ENTITY_MIXIN,
                "fabrolia$lastMainTrackingServerTick");
        assertTrue(beginRead >= 0 && beginWrite > beginRead
                        && hasBranchBetween(beginMain, beginRead, beginWrite),
                "the exact tracker accepts at most one persistent Main publication stamp per "
                        + "server tick");
        assertEquals(1, fieldReadCount(generation, TRACKED_ENTITY_MIXIN,
                "fabrolia$islandTrackingPublicationGeneration"));
        assertEquals(1, fieldReadCount(markPublished, TRACKED_ENTITY_MIXIN,
                "fabrolia$islandTrackingPublicationGeneration"));
        assertEquals(1, fieldWriteCount(markPublished, TRACKED_ENTITY_MIXIN,
                "fabrolia$islandTrackingPublicationGeneration"));

        FieldNode pendingField = field(tracker, "fabrolia$pendingIslandPairings");
        assertEquals("L" + PENDING_QUEUE + ";", pendingField.desc,
                "viewer removal must not scan a per-tracker list; identity keys survive respawn id reuse");
        assertEquals(0, pendingField.access & Opcodes.ACC_FINAL,
                "the exact constructor hook, not a discarded final initializer, owns assignment");
        assertNotNull(pendingField.signature);
        assertTrue(pendingField.signature.contains("L" + PENDING_PAIRING + ";"));
        assertEquals(1, fieldReadCount(pending, TRACKED_ENTITY_MIXIN,
                "fabrolia$pendingIslandPairings"));
        assertEquals(1, fieldReadCount(pending, TRACKED_ENTITY_MIXIN,
                "fabrolia$pendingBulkPairingRefresh"),
                "the one bulk token participates in pending-work lifecycle decisions");
        assertEquals(1, callCount(pending, PENDING_QUEUE, "isEmpty"));

        FieldNode bulkField = field(tracker, "fabrolia$pendingBulkPairingRefresh");
        assertEquals("Z", bulkField.desc,
                "the all-player VMP burst must collapse to one tracker-local bit");
        int bulkQueueState = fieldReadIndex(queueBulk, TRACKED_ENTITY_MIXIN,
                "fabrolia$islandTrackingState");
        int bulkQueueWrite = fieldWriteIndex(queueBulk, TRACKED_ENTITY_MIXIN,
                "fabrolia$pendingBulkPairingRefresh");
        assertTrue(bulkQueueState >= 0 && bulkQueueWrite > bulkQueueState,
                "a live tracker publishes exactly one idempotent bulk-refresh obligation");
        assertEquals(0, typeCount(queueBulk, Opcodes.NEW, PENDING_PAIRING),
                "bulk staging must never expand all players into per-session records");

        assertEquals(1, callCount(queue, "java/util/Set", "contains"),
                "seenBy selects ADD versus REVALIDATE for the exact listener");
        int queuedEntity = fieldReadIndex(queue, TRACKED_ENTITY_MIXIN, "entity");
        int queuedSelfReject = referenceCompareIndexAfter(queue, queuedEntity);
        int queuedVisibility = callIndex(queue, "java/util/Set", "contains",
                "(Ljava/lang/Object;)Z");
        assertTrue(queuedEntity >= 0 && queuedSelfReject > queuedEntity
                        && queuedVisibility > queuedSelfReject,
                "the tracked ServerPlayer itself is never converted into pending seenBy "
                        + "membership; self publication is a persistent-clock concern");
        assertEquals(1, callCount(queue, PENDING_PAIRING, "matchesExactly"),
                "pending deduplication must include the exact listener session");
        assertEquals(2, typeCount(queue, Opcodes.NEW, PENDING_PAIRING),
                "an intent change replaces the record while a new session appends one");
        assertEquals(1, callCount(queue, PENDING_QUEUE, "get"));
        assertEquals(1, callCount(queue, PENDING_QUEUE, "put"),
                "an intent change keeps the session's queue position");
        assertEquals(1, callCount(queue, PENDING_QUEUE, "putAndMoveToLast"),
                "a new identity, or a defensively tolerated rebound session, takes the tail");
        assertEquals(1, fieldOpcodeCount(queue, Opcodes.GETSTATIC,
                PAIRING_INTENT, "ADD"));
        assertEquals(1, fieldOpcodeCount(queue, Opcodes.GETSTATIC,
                PAIRING_INTENT, "REVALIDATE"));

        assertEquals(1, callCount(queueRemoval, PENDING_QUEUE, "remove"),
                "desired-ABSENT first coalesces every obligation for the player identity");
        assertEquals(1, callCount(queueRemoval, "java/util/Set", "contains"));
        assertEquals(1, typeCount(queueRemoval, Opcodes.NEW, PENDING_PAIRING));
        assertEquals(1, callCount(queueRemoval, PENDING_QUEUE, "put"));
        assertEquals(1, fieldOpcodeCount(queueRemoval, Opcodes.GETSTATIC,
                PAIRING_INTENT, "REMOVE"));

        int retiredClear = callIndex(commit, PENDING_QUEUE, "clear", "()V");
        int bulkCommit = callIndex(commit, TRACKING, "commitBulkPairingRefresh",
                "(L" + TRACKER_ACCESS + ";Ljava/lang/Runnable;)V");
        int bulkClearAfterSuccess = fieldOpcodeIndexAfter(commit, Opcodes.PUTFIELD,
                TRACKED_ENTITY_MIXIN, "fabrolia$pendingBulkPairingRefresh", bulkCommit);
        int entryAdmission = callIndexAfter(commit, REGION_SCHEDULER,
                "callSuccessBooleanIfHealthy", null, bulkClearAfterSuccess);
        List<MethodNode> entryBodies = tracker.methods.stream()
                .filter(method -> method.name.startsWith(
                        "lambda$fabrolia$commitIslandPairings$"))
                .filter(method -> callCount(method, PENDING_QUEUE,
                        "removeFirst") == 1)
                .toList();
        assertEquals(1, entryBodies.size(),
                "one admitted helper must own pending pairing detach and body");
        MethodNode entryBody = entryBodies.getFirst();
        int exactSession = callIndex(entryBody, PENDING_PAIRING,
                "matchesExactly", "(Lnet/minecraft/server/level/ServerPlayer;)Z");
        int removalPlayer = callIndex(entryBody, PENDING_PAIRING,
                "matchesRemovalPlayer", "(Lnet/minecraft/server/level/ServerPlayer;)Z");
        int removePending = callIndex(entryBody, PENDING_QUEUE, "removeFirst",
                "()Ljava/lang/Object;");
        int commitScope = callIndex(entryBody, TRACKING,
                "commitPairingInAdmittedBody",
                "(L" + TRACKER_ACCESS + ";L" + PENDING_PAIRING
                        + ";Ljava/lang/Runnable;)V");
        assertTrue(retiredClear >= 0 && bulkCommit > retiredClear
                        && bulkClearAfterSuccess > bulkCommit
                        && entryAdmission > bulkClearAfterSuccess
                        && removePending >= 0
                        && removalPlayer > removePending && exactSession > removePending
                        && commitScope > exactSession,
                "retired trackers clear all work; a live bulk bit clears only after successful "
                        + "transformed updatePlayers, then one admitted helper detaches, validates "
                        + "and enters each bounded intent");
        assertEquals(2, fieldWriteCount(commit, TRACKED_ENTITY_MIXIN,
                "fabrolia$pendingBulkPairingRefresh"),
                "only retirement or a normally returned bulk commit may consume the bit");
        assertEquals(2, callCount(entryBody, TRACKING,
                        "commitPairingInAdmittedBody"),
                "desired-PRESENT and desired-ABSENT both use the admitted commit ThreadLocal");
        assertEquals(0, callCount(entryBody, TRACKING, "commitPairing"),
                "an already-detached pairing must not face a second admission");
        assertEquals(0, callCount(entryBody, "java/util/Set", "contains"),
                "a failed pairing body must escape without rollback publication work");
        List<MethodNode> retryBodies = tracker.methods.stream()
                .filter(method -> method.name.startsWith(
                        "lambda$fabrolia$commitIslandPairings$"))
                .filter(method -> callCount(method, TRACKED_ENTITY_MIXIN,
                        "fabrolia$restagePairing") == 1)
                .toList();
        assertEquals(1, retryBodies.size(),
                "only a still-present REMOVE decline owns one admitted bounded retry");
        MethodNode restage = method(tracker, "fabrolia$restagePairing",
                "(L" + PENDING_PAIRING + ";)V");
        assertEquals(1, callCount(restage, PENDING_QUEUE, "putIfAbsent"),
                "a retry re-arms at the tail and fails stop on a second record for its identity");
        assertEquals(0, callCount(commit, TRACKED_ENTITY_MIXIN,
                "fabrolia$requeueAfterFailure"),
                "a failed transformed body must never schedule a successor retry");
        assertFalse(commit.tryCatchBlocks.stream().anyMatch(block ->
                        "java/lang/RuntimeException".equals(block.type)
                                || "java/lang/Error".equals(block.type)),
                "unchecked pairing failures must propagate directly out of the current ordinal");
        List<MethodNode> pairingBodies = tracker.methods.stream()
                .filter(method -> method.name.startsWith("lambda$fabrolia$commitIslandPairings$"))
                .filter(method -> callCount(method, TRACKED_ENTITY_MIXIN,
                        "updatePlayer") == 1)
                .toList();
        assertEquals(1, pairingBodies.size(),
                "the owner-tail commit must re-enter the exact transformed updatePlayer method");
        List<MethodNode> removalBodies = tracker.methods.stream()
                .filter(method -> method.name.startsWith("lambda$fabrolia$commitIslandPairings$"))
                .filter(method -> callCount(method, TRACKED_ENTITY_MIXIN,
                        "fabrolia$removeCapturedPairing") == 1)
                .toList();
        assertEquals(1, removalBodies.size(),
                "desired-ABSENT must commit through the captured-listener removal seam");
        List<MethodNode> bulkBodies = tracker.methods.stream()
                .filter(method -> method.name.startsWith("lambda$fabrolia$commitIslandPairings$"))
                .filter(method -> callCount(method, TRACKED_ENTITY_MIXIN,
                        "updatePlayers") == 1)
                .toList();
        assertEquals(1, bulkBodies.size(),
                "one bulk token re-enters the exact transformed updatePlayers method");
        int currentPlayers = callIndex(bulkBodies.getFirst(),
                "net/minecraft/server/level/ServerLevel", "players", "()Ljava/util/List;");
        int transformedBulk = callIndexAfter(bulkBodies.getFirst(), TRACKED_ENTITY_MIXIN,
                "updatePlayers", "(Ljava/util/List;)V", currentPlayers);
        assertTrue(currentPlayers >= 0 && transformedBulk > currentPlayers,
                "bulk commit expands the current level SnapshotList, never a captured stale list");

        MethodNode retryIntent = method(tracker, "fabrolia$mustRetry",
                "(L" + PENDING_PAIRING + ";)Z");
        assertEquals(1, callCount(retryIntent, "java/util/Set", "contains"),
                "successful ADD/REVALIDATE declines are terminal; only REMOVE retries while "
                        + "the captured listener remains");
        MethodNode capturedRemoval = method(tracker, "fabrolia$removeCapturedPairing",
                "(L" + PENDING_PAIRING + ";)V");
        assertEquals(1, callCount(capturedRemoval, TRACKED_ENTITY_MIXIN, "removePlayer"),
                "a still-current listener may re-enter the transformed vanilla/VMP removal");
        assertEquals(1, callCount(capturedRemoval, TRACKED_ENTITY_MIXIN,
                "fabrolia$removeCapturedPairingDirect"));
        assertTrue(referenceCompareCount(capturedRemoval) >= 1,
                "session replacement is selected by listener identity");
        MethodNode directRemoval = method(tracker, "fabrolia$removeCapturedPairingDirect",
                "(L" + PENDING_PAIRING + ";)V");
        int removeListener = callIndex(directRemoval, "java/util/Set", "remove",
                "(Ljava/lang/Object;)Z");
        int stopSeen = callIndexAfter(directRemoval,
                "net/minecraft/world/entity/Entity", "stopSeenByPlayer",
                "(Lnet/minecraft/server/level/ServerPlayer;)V", removeListener);
        int removePacket = typeIndex(directRemoval, Opcodes.NEW,
                "net/minecraft/network/protocol/game/ClientboundRemoveEntitiesPacket");
        int sendCaptured = callNameIndexAfter(directRemoval, "send", removePacket);
        int dropCaptured = callNameIndexAfter(directRemoval, "dropEntity", sendCaptured);
        assertTrue(removeListener >= 0 && stopSeen > removeListener
                        && removePacket > stopSeen && sendCaptured > removePacket
                        && dropCaptured > sendCaptured,
                "captured REMOVE preserves the exact transformed vanilla operation order");
        assertTrue(directRemoval.tryCatchBlocks.isEmpty(),
                "a failed callback must prevent later destroy/debug bodies from starting");

        MethodNode update = method(tracker, "updatePlayer",
                "(Lnet/minecraft/server/level/ServerPlayer;"
                        + "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)V");
        MethodNode updatePlayers = method(tracker, "fabrolia$stageBulkUpdatePlayers",
                "(Ljava/util/List;"
                        + "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)V");
        MethodNode remove = method(tracker, "fabrolia$removePlayer");
        MethodNode teardown = method(tracker, "fabrolia$broadcastRemoved");
        assertTrue(opcodeIndex(update, Opcodes.MONITORENTER) >= 0,
                "the full updatePlayer decision and original body need the tracker monitor");
        assertTrue(opcodeIndex(remove, Opcodes.MONITORENTER) >= 0,
                "pending cancellation and vanilla removal need one tracker transaction");
        assertTrue(opcodeIndex(updatePlayers, Opcodes.MONITORENTER) >= 0,
                "the all-player stage/bypass decision needs the same tracker transaction");
        assertTrue((teardown.access & Opcodes.ACC_SYNCHRONIZED) != 0,
                "broadcast teardown must share the tracker intrinsic monitor");
        AnnotationNode updateWrap = annotation(update,
                "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");
        AnnotationNode removeWrap = annotation(remove,
                "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");
        AnnotationNode updatePlayersWrap = annotation(updatePlayers,
                "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");
        assertNotNull(updateWrap);
        assertNotNull(removeWrap);
        assertNotNull(updatePlayersWrap);
        assertTrue(annotationValueContains(updateWrap, "method",
                "updatePlayer(Lnet/minecraft/server/level/ServerPlayer;)V"));
        assertTrue(annotationValueContains(removeWrap, "method",
                "removePlayer(Lnet/minecraft/server/level/ServerPlayer;)V"),
                "the global transformed wrappers must stage every VMP update/remove call site");
        assertTrue(annotationValueContains(updatePlayersWrap, "method",
                "updatePlayers(Ljava/util/List;)V"),
                "VMP all-player staging must pass one global transformed bulk wrapper");
        int bulkUpdateReentry = callIndex(update, TRACKING, "isBulkPairingCommit",
                "(L" + TRACKER_ACCESS + ";)Z");
        int commitReentry = callIndex(update, TRACKING, "isPairingCommit",
                "(L" + TRACKER_ACCESS
                        + ";Lnet/minecraft/server/level/ServerPlayer;)Z");
        int updateStateGate = fieldReadIndex(update, TRACKED_ENTITY_MIXIN,
                "fabrolia$islandTrackingState");
        int selfViewerGate = referenceCompareIndexAfter(update, updateStateGate);
        int disconnectGate = callIndex(update,
                "net/minecraft/server/level/ServerPlayer", "hasDisconnected", "()Z");
        int listenerPlayerGate = fieldOpcodeIndexAfter(update, Opcodes.GETFIELD,
                "net/minecraft/server/network/ServerGamePacketListenerImpl", "player",
                disconnectGate);
        int firstLevelGate = callNameIndexAfter(update, "level", listenerPlayerGate);
        int secondLevelGate = callNameIndexAfter(update, "level", firstLevelGate);
        int levelIdentityGate = referenceCompareIndexAfter(update, secondLevelGate);
        assertTrue(updateStateGate > opcodeIndex(update, Opcodes.MONITORENTER)
                        && selfViewerGate > updateStateGate
                        && disconnectGate > selfViewerGate
                        && listenerPlayerGate > disconnectGate
                        && firstLevelGate > listenerPlayerGate
                        && secondLevelGate > firstLevelGate
                        && levelIdentityGate > secondLevelGate
                        && bulkUpdateReentry > levelIdentityGate
                        && commitReentry > bulkUpdateReentry,
                "under the tracker monitor, RETIRED/self/removed/disconnected/rebound/level-stale "
                        + "viewers must fail closed before either staged or vanilla pairing");
        assertEquals(2, callNameCount(update, "isRemoved"));
        assertEquals(2, fieldReadCount(update,
                "net/minecraft/server/level/ServerPlayer", "connection"));
        int stage = callIndex(update, TRACKING, "stagePairingIfOwned",
                "(L" + TRACKER_ACCESS
                        + ";Lnet/minecraft/server/level/ServerPlayer;)Z");
        int bulkPlayerOriginal = callIndexAfter(update,
                "com/llamalad7/mixinextras/injector/wrapoperation/Operation",
                "call", "([Ljava/lang/Object;)Ljava/lang/Object;", bulkUpdateReentry);
        int pairingOriginal = callIndexAfter(update,
                "com/llamalad7/mixinextras/injector/wrapoperation/Operation",
                "call", "([Ljava/lang/Object;)Ljava/lang/Object;", commitReentry);
        int updateOriginal = callIndexAfter(update,
                "com/llamalad7/mixinextras/injector/wrapoperation/Operation",
                "call", "([Ljava/lang/Object;)Ljava/lang/Object;", stage);
        int register = callIndex(update, TRACKING, "registerFromPairing",
                "(L" + TRACKER_ACCESS + ";)V");
        int updateMonitorExit = opcodeIndexAfter(update, Opcodes.MONITOREXIT,
                updateOriginal);
        assertTrue(bulkUpdateReentry >= 0 && bulkPlayerOriginal > bulkUpdateReentry
                        && commitReentry > bulkPlayerOriginal && pairingOriginal > commitReentry
                        && stage > pairingOriginal
                        && updateOriginal > stage && hasBranchBetween(update, stage, updateOriginal)
                        && updateMonitorExit > updateOriginal && register > updateMonitorExit,
                "pairing commits/stages/falls through under one lock; a failed original has no "
                        + "rollback/retry body before propagating");
        assertEquals(3, callCount(update,
                "com/llamalad7/mixinextras/injector/wrapoperation/Operation", "call"));
        assertFalse(update.tryCatchBlocks.stream().anyMatch(block ->
                        "java/lang/RuntimeException".equals(block.type)
                                || "java/lang/Error".equals(block.type)),
                "unchecked original failures must not enter a compensating publication body");
        int bulkRemovalReentry = callIndex(remove, TRACKING, "isBulkPairingCommit",
                "(L" + TRACKER_ACCESS + ";)Z");
        int removalReentry = callIndex(remove, TRACKING, "isRemovalCommit",
                "(L" + TRACKER_ACCESS
                        + ";Lnet/minecraft/server/level/ServerPlayer;)Z");
        int stageRemoval = callIndex(remove, TRACKING, "stageRemovalIfOwned",
                "(L" + TRACKER_ACCESS
                        + ";Lnet/minecraft/server/level/ServerPlayer;)Z");
        int stagedUnregister = callIndex(remove, TRACKING, "unregisterIfIdle",
                "(L" + TRACKER_ACCESS + ";)V");
        int removeOriginal = callIndex(remove,
                "com/llamalad7/mixinextras/injector/wrapoperation/Operation",
                "call", "([Ljava/lang/Object;)Ljava/lang/Object;");
        int unregister = callIndexAfter(remove, TRACKING, "unregisterIfIdle",
                "(L" + TRACKER_ACCESS + ";)V", removeOriginal);
        int removeMonitorExit = opcodeIndexAfter(remove, Opcodes.MONITOREXIT,
                unregister);
        assertTrue(bulkRemovalReentry >= 0 && removalReentry > bulkRemovalReentry
                        && stageRemoval > removalReentry
                        && stagedUnregister > stageRemoval && removeOriginal > stagedUnregister
                        && unregister > removeOriginal
                        && removeMonitorExit > unregister,
                "removePlayer stages or runs vanilla; unregister follows only a normally "
                        + "returned original under the same lock");
        assertFalse(remove.tryCatchBlocks.stream().anyMatch(block ->
                        "java/lang/RuntimeException".equals(block.type)
                                || "java/lang/Error".equals(block.type)),
                "a failed transformed removal must not start client/debug recovery work");
        assertEquals(0, callCount(remove, TRACKED_ENTITY_MIXIN,
                "fabrolia$cancelIslandPairing"),
                "a visible removal may not be collapsed into cancellation");

        int bulkStateGate = fieldReadIndex(updatePlayers, TRACKED_ENTITY_MIXIN,
                "fabrolia$islandTrackingState");
        int bulkCommitReentry = callIndexAfter(updatePlayers, TRACKING,
                "isBulkPairingCommit", "(L" + TRACKER_ACCESS + ";)Z", bulkStateGate);
        int bulkOriginal = callIndexAfter(updatePlayers,
                "com/llamalad7/mixinextras/injector/wrapoperation/Operation",
                "call", "([Ljava/lang/Object;)Ljava/lang/Object;", bulkCommitReentry);
        int bulkStage = callIndexAfter(updatePlayers, TRACKING,
                "stageBulkPairingRefreshIfOwned", "(L" + TRACKER_ACCESS + ";)Z",
                bulkOriginal);
        int bulkFallbackOriginal = callIndexAfter(updatePlayers,
                "com/llamalad7/mixinextras/injector/wrapoperation/Operation",
                "call", "([Ljava/lang/Object;)Ljava/lang/Object;", bulkStage);
        assertTrue(bulkStateGate >= 0 && bulkCommitReentry > bulkStateGate
                        && bulkOriginal > bulkCommitReentry && bulkStage > bulkOriginal
                        && bulkFallbackOriginal > bulkStage,
                "bulk re-entry runs the exact original list once; ordinary VMP bursts stage one "
                        + "bit, while non-island fallback preserves vanilla immediately");
        assertEquals(2, callCount(updatePlayers,
                "com/llamalad7/mixinextras/injector/wrapoperation/Operation", "call"));

        int retire = callIndex(teardown, TRACKED_ENTITY_MIXIN,
                "fabrolia$deactivateIslandTracking", "()V");
        int detach = callIndex(teardown, TRACKING, "retire",
                "(L" + TRACKER_ACCESS + ";)V");
        int original = callIndex(teardown,
                "com/llamalad7/mixinextras/injector/wrapoperation/Operation",
                "call", "([Ljava/lang/Object;)Ljava/lang/Object;");
        assertTrue(retire >= 0 && detach > retire && original > detach,
                "this generation must retire and detach before remove packets/teardown");

        ClassNode access = readClass(TRACKER_ACCESS);
        assertTrackingConstant(access, "TRACKING_ACTIVE", 0);
        assertTrackingConstant(access, "TRACKING_RETIRED", 1);
        assertTrackingConstant(access, "TRACKING_QUARANTINED", 2);
        assertNotNull(method(access, "fabrolia$islandTrackingState", "()I"));
        assertNotNull(method(access, "fabrolia$hasIslandTrackingViewers", "()Z"));
        assertNotNull(method(access, "fabrolia$hasPendingIslandPairings", "()Z"));
        assertNotNull(method(access, "fabrolia$queueIslandBulkPairingRefresh", "()Z"));
        assertNotNull(method(access, "fabrolia$queueIslandPairing",
                "(Lnet/minecraft/server/level/ServerPlayer;)Z"));
        assertNotNull(method(access, "fabrolia$queueIslandRemoval",
                "(Lnet/minecraft/server/level/ServerPlayer;)Z"));
        assertNotNull(method(access, "fabrolia$cancelIslandPairing",
                "(Lnet/minecraft/server/level/ServerPlayer;)V"));
        assertNotNull(method(access, "fabrolia$commitIslandPairings", "()V"));
        assertNotNull(method(access, "fabrolia$islandTrackingOwner",
                "()L" + P_ISLAND + ";"));
        assertNotNull(method(access, "fabrolia$setIslandTrackingOwner",
                "(L" + P_ISLAND + ";)V"));
        assertNotNull(method(access, "fabrolia$deactivateIslandTracking", "()V"));
        assertNotNull(method(access, "fabrolia$quarantineIslandTracking", "()V"));
        assertNotNull(method(access, "fabrolia$tryScheduleMainTracking", "()J"));
        assertNotNull(method(access, "fabrolia$finishMainTrackingSchedule", "(J)V"));
        assertNotNull(method(access, "fabrolia$tryBeginMainTrackingPublication", "(I)Z"));
        assertNotNull(method(access,
                "fabrolia$islandTrackingPublicationGeneration", "()J"));
        assertNotNull(method(access, "fabrolia$markIslandTrackingPublished", "()V"));
        assertNotNull(method(access, "fabrolia$sendIslandChanges", "()Z"));
    }

    @Test
    void routeStateMachineUsesStablePlayerOwnershipAndViewerAwarePairing()
            throws Exception {
        ClassNode tracking = readClass(TRACKING);
        String routeType = "L" + TRACKING + "$Route;";
        MethodNode route = method(tracking, "route",
                "(Ljava/lang/Object;Lnet/minecraft/world/entity/Entity;)" + routeType);
        assertEquals(1, callCount(route, TRACKER_ACCESS,
                "fabrolia$islandTrackingState"));
        int state = callIndex(route, TRACKER_ACCESS,
                "fabrolia$islandTrackingState", "()I");
        int retiredDetach = callIndex(route, TRACKING, "detach",
                "(L" + TRACKER_ACCESS + ";)V");
        int retiredDrop = fieldOpcodeIndexAfter(route, Opcodes.GETSTATIC,
                TRACKING + "$Route", "DROP", retiredDetach);
        assertTrue(state >= 0 && retiredDetach > state && retiredDrop > retiredDetach,
                "a stale retired generation must detach and DROP, never mutate ServerEntity again");
        int activeOwnerHandoff = callIndex(route, TRACKING,
                "handoffMainPublicationToActiveOwnerLocked",
                "(L" + TRACKER_ACCESS + ";Lnet/minecraft/world/entity/Entity;)Z");
        int activeOwnerDrop = fieldOpcodeIndexAfter(route, Opcodes.GETSTATIC,
                TRACKING + "$Route", "DROP", activeOwnerHandoff);
        int runtimeRead = callIndex(route, TRACKING, "runtime",
                "(Lnet/minecraft/world/entity/Entity;)L" + REGIONIZED_SERVER + ";");
        int routingAdmission = callIndexAfter(route, REGIONIZED_SERVER,
                "enterTrackingTopologyRouting", "()V", runtimeRead);
        int topologyReceipt = callIndexAfter(route, REGIONIZED_SERVER,
                "trackingTopologyTransitionReceipt", null, activeOwnerDrop);
        int transitionDetach = callIndexAfter(route, TRACKING, "detach",
                "(L" + TRACKER_ACCESS + ";)V", topologyReceipt);
        int transitionSchedule = callIndexAfter(route, TRACKING,
                "scheduleMainPublication", "(L" + TRACKER_ACCESS + ";)V",
                transitionDetach);
        int runtimeDetach = callIndexAfter(route, TRACKING, "detach",
                "(L" + TRACKER_ACCESS + ";)V", transitionSchedule);
        int quarantineRoute = callIndexAfter(route, TRACKING, "mainRouteOrSchedule",
                null, runtimeDetach);
        assertTrue(runtimeRead > retiredDrop && routingAdmission > runtimeRead
                        && activeOwnerHandoff > routingAdmission
                        && activeOwnerDrop > activeOwnerHandoff
                        && topologyReceipt > activeOwnerDrop
                        && transitionDetach > topologyReceipt
                        && transitionSchedule > transitionDetach
                        && runtimeDetach > transitionSchedule
                        && quarantineRoute > runtimeDetach,
                "tracker routing must enter the topology admission cut, preserve exact A5 "
                        + "handoff first, then divert a transition-time non-A5 call to one Main "
                        + "successor before runtime-OFF or quarantine routing");
        assertEquals(0, callCount(route, TRACKING, "activeIslandPlayerOwner"),
                "route must not make a racy owner precheck separate from handoff validation");
        assertTrue(quarantineRoute > runtimeDetach,
                "a quarantined live generation must detach and enter the common claimed-Main "
                        + "route rather than publishing directly");
        assertEquals(4, callCount(route, TRACKING, "mainRouteOrSchedule"),
                "quarantine, hard-Main type, forced player, and ineligible non-player all "
                        + "share one claim-aware Main publication gate");
        assertTrue(fieldOpcodeCount(route, Opcodes.GETSTATIC,
                        TRACKING + "$Route", "MAIN") >= 3
                        && fieldOpcodeCount(route, Opcodes.GETSTATIC,
                        TRACKING + "$Route", "ISLAND") >= 2
                        && fieldOpcodeCount(route, Opcodes.GETSTATIC,
                        TRACKING + "$Route", "DROP") >= 3,
                "route must retain distinct physical-Main, island, and stale/no-viewer outcomes");
        assertEquals(1, fieldOpcodeCount(route, Opcodes.GETSTATIC,
                TRACKING, "PUBLISHING"),
                "recursive island publication must re-enter the original under one bypass");
        int pairingCommit = fieldOpcodeIndexAfter(route, Opcodes.GETSTATIC,
                TRACKING, "PAIRING_COMMIT", state);
        int commitIntent = callIndexAfter(route, PENDING_PAIRING, "intent",
                "()L" + PAIRING_INTENT + ";", pairingCommit);
        int removeIntent = fieldOpcodeIndexAfter(route, Opcodes.GETSTATIC,
                PAIRING_INTENT, "REMOVE", commitIntent);
        int pairingDrop = fieldOpcodeIndexAfter(route, Opcodes.GETSTATIC,
                TRACKING + "$Route", "DROP", removeIntent);
        assertTrue(pairingCommit > state && commitIntent > pairingCommit
                        && removeIntent > commitIntent && pairingDrop > removeIntent
                        && activeOwnerHandoff > pairingDrop,
                "ADD/REVALIDATE re-entry drops VMP's redundant refresh, while REMOVE is "
                        + "allowed to reach the transformed removal body");
        int bulkCommitMarker = fieldOpcodeIndexAfter(route, Opcodes.GETSTATIC,
                TRACKING, "BULK_PAIRING_COMMIT", pairingDrop);
        int bulkCommitDrop = fieldOpcodeIndexAfter(route, Opcodes.GETSTATIC,
                TRACKING + "$Route", "DROP", bulkCommitMarker);
        assertTrue(bulkCommitMarker > pairingDrop && bulkCommitDrop > bulkCommitMarker
                        && activeOwnerHandoff > bulkCommitDrop,
                "the exact bulk transformed re-entry drops its redundant nested sendChanges");

        int hardMainType = callIndexAfter(route, TRACKING, "requiresPersistentMainPublisher",
                "(Lnet/minecraft/world/entity/Entity;)Z", quarantineRoute);
        int hardMainOwner = callIndexAfter(route, REGIONIZED_SERVER,
                "islandOwnerOfChunk", "(J)L" + P_ISLAND + ";", hardMainType);
        int hardMainPending = callIndexAfter(route, TRACKER_ACCESS,
                "fabrolia$hasPendingIslandPairings", "()Z", hardMainOwner);
        int hardMainViewers = callIndexAfter(route, TRACKER_ACCESS,
                "fabrolia$hasIslandTrackingViewers", "()Z", hardMainPending);
        int hardMainEligible = callIndexAfter(route, TRACKING,
                "persistentMainClockEligible",
                "(Lnet/minecraft/world/entity/Entity;L" + REGIONIZED_SERVER + ";)Z",
                hardMainViewers);
        int hardMainAttach = callIndexAfter(route, TRACKING, "attach",
                "(L" + TRACKER_ACCESS + ";L" + P_ISLAND + ";)V", hardMainEligible);
        int hardMainRoute = callIndexAfter(route, TRACKING, "mainRouteOrSchedule",
                null, hardMainAttach);
        assertTrue(hardMainType > quarantineRoute && hardMainOwner > hardMainType
                        && hardMainPending > hardMainOwner
                        && hardMainViewers > hardMainPending
                        && hardMainEligible > hardMainViewers
                        && hardMainAttach > hardMainEligible
                        && hardMainRoute > hardMainAttach,
                "Wither/Dragon keep an island owner only as a persistent cadence token, then "
                        + "route every ServerEntity refresh through claimed physical Main");

        int deferPlayer = callIndex(route,
                "com/axalotl/fabrolia/common/regions/AsyncIslandPlayerTicks",
                "shouldDeferTrackingPublication", null);
        int firstAttach = callIndexAfter(route, TRACKING, "attach",
                "(L" + TRACKER_ACCESS + ";L" + P_ISLAND + ";)V", deferPlayer);
        assertTrue(deferPlayer >= 0 && firstAttach > deferPlayer,
                "every exact deferred player phase must retain the persistent self publisher");
        assertEquals(0, callCount(route, A5, "ownsTrackingPublication"),
                "tracker lifetime must not collapse to the transient READY phase");
        assertEquals(1, callCount(route, TRACKER_ACCESS,
                "fabrolia$hasIslandTrackingViewers"),
                "only hard-Main cadence admission is viewer-aware at the route seam");
        assertEquals(1, callCount(route, TRACKER_ACCESS,
                "fabrolia$hasPendingIslandPairings"),
                "only hard-Main cadence admission reads pending work at the route seam");
        assertEquals(1, callCount(route, TRACKING, "requiresPersistentMainPublisher"));
        assertEquals(1, callCount(route, TRACKING, "eligibleNonPlayer"),
                "ordinary non-player routing retains the full ticking-range fence");
        int portalRoot = callIndexAfter(route, TRACKING, "portalDeparturePending",
                "(Lnet/minecraft/world/entity/Entity;)Z", firstAttach);
        int portalEntity = callIndexAfter(route, TRACKING, "portalDeparturePending",
                "(Lnet/minecraft/world/entity/Entity;)Z", portalRoot);
        int portalCompatible = callIndexAfter(route, TRACKING,
                "islandCompatibleNonPlayer",
                "(Lnet/minecraft/world/entity/Entity;L" + P_ISLAND + ";)Z",
                portalEntity);
        int portalAttach = callIndexAfter(route, TRACKING, "attach",
                "(L" + TRACKER_ACCESS + ";L" + P_ISLAND + ";)V", portalCompatible);
        int portalDrop = fieldOpcodeIndexAfter(route, Opcodes.GETSTATIC,
                TRACKING + "$Route", "DROP", portalAttach);
        assertTrue(portalRoot > firstAttach && portalEntity > portalRoot
                        && portalCompatible > portalEntity && portalAttach > portalCompatible
                        && portalDrop > portalAttach,
                "portal-pending island-compatible trackers stay attached to the source and DROP "
                        + "instead of escaping to a premature Main refresh");
        assertEquals(4, callCount(route, TRACKING, "attach"),
                "hard-Main cadence, player, portal-retained, and ordinary non-player routes "
                        + "each attach explicitly");

        MethodNode register = method(tracking, "registerFromPairing",
                "(L" + TRACKER_ACCESS + ";)V");
        assertEquals(2, callCount(register, TRACKER_ACCESS,
                "fabrolia$islandTrackingState"),
                "pairing registration revalidates the tracker generation after taking its "
                        + "monitor and topology admission");
        assertEquals(1, callCount(register, TRACKER_ACCESS,
                "fabrolia$hasIslandTrackingViewers"));
        assertEquals(1, callCount(register, TRACKER_ACCESS,
                "fabrolia$hasPendingIslandPairings"));
        assertEquals(2, callCount(register, TRACKING, "attach"),
                "player and non-player pure-vanilla pairing branches must seed the clock");
        assertEquals(1, callCount(register, TRACKING, "requiresPersistentMainPublisher"));
        assertEquals(1, callCount(register, A5, "shouldDeferTrackingPublication"),
                "a paired zero-view player retains its tracker throughout attached ownership");

        MethodNode unregister = method(tracking, "unregisterIfIdle",
                "(L" + TRACKER_ACCESS + ";)V");
        int unregisterMonitor = opcodeIndex(unregister, Opcodes.MONITORENTER);
        assertTrue(unregisterMonitor >= 0,
                "idle observation and detach must be one exact-tracker transaction");
        AbstractInsnNode unregisterEnter = realInstructionAt(unregister, unregisterMonitor);
        AbstractInsnNode unregisterStore = previousReal(unregisterEnter);
        AbstractInsnNode unregisterDup = previousReal(unregisterStore);
        AbstractInsnNode unregisterLoad = previousReal(unregisterDup);
        assertTrue(unregisterStore instanceof VarInsnNode store
                        && store.getOpcode() == Opcodes.ASTORE
                        && unregisterDup != null && unregisterDup.getOpcode() == Opcodes.DUP
                        && unregisterLoad instanceof VarInsnNode load
                        && load.getOpcode() == Opcodes.ALOAD && load.var == 0,
                "unregisterIfIdle must synchronize the exact tracker argument");
        assertEquals(1, callCount(unregister, TRACKER_ACCESS,
                "fabrolia$hasIslandTrackingViewers"));
        assertEquals(1, callCount(unregister, TRACKER_ACCESS,
                "fabrolia$hasPendingIslandPairings"));
        assertEquals(2, callCount(unregister, TRACKING, "detach"),
                "transition-time idle teardown and ordinary idle teardown each detach under the "
                        + "same tracker/topology admission");
        int deferredPlayer = callIndex(unregister,
                "com/axalotl/fabrolia/common/regions/AsyncIslandPlayerTicks",
                "shouldDeferTrackingPublication", null);
        int attachedPlayer = callIndexAfter(unregister, A5,
                "hasAttachedOwner", null, deferredPlayer);
        int retainedAttach = callIndexAfter(unregister, TRACKING, "attach",
                "(L" + TRACKER_ACCESS + ";L" + P_ISLAND + ";)V", attachedPlayer);
        int idleUnregister = callIndexAfter(unregister, TRACKING, "detach",
                "(L" + TRACKER_ACCESS + ";)V", retainedAttach);
        int unregisterExit = opcodeIndexAfter(unregister, Opcodes.MONITOREXIT,
                idleUnregister);
        assertTrue(deferredPlayer >= 0 && attachedPlayer > deferredPlayer
                        && retainedAttach > attachedPlayer
                        && idleUnregister > retainedAttach
                        && unregisterExit > idleUnregister,
                "a supported attached player keeps its self-recipient publisher in every owner "
                        + "phase; only an ownerless idle tracker detaches");

        ClassNode a5 = readClass(
                "com/axalotl/fabrolia/common/regions/AsyncIslandPlayerTicks");
        MethodNode ownsLocked = method(a5, "ownsTrackingPublicationLocked",
                "(L" + A5 + "$PlayerState;Lnet/minecraft/server/level/ServerPlayer;"
                        + "L" + P_ISLAND + ";)Z");
        assertEquals(1, fieldOpcodeCount(ownsLocked, Opcodes.GETSTATIC,
                "com/axalotl/fabrolia/common/regions/IslandPlayerTurnSession$Phase",
                "ISLAND_READY"),
                "actual packet publication requires the stable ready phase");
        assertEquals(1, callCount(ownsLocked, "java/lang/Thread", "holdsLock"),
                "the stable player predicate must reject an unlocked state read");

        MethodNode runOwned = method(a5, "runTrackingPublicationIfOwned",
                "(Lnet/minecraft/server/level/ServerPlayer;L" + P_ISLAND
                        + ";Ljava/lang/Runnable;)Z");
        int stateMonitor = opcodeIndex(runOwned, Opcodes.MONITORENTER);
        int revalidate = callIndex(runOwned, A5,
                "ownsTrackingPublicationLocked", null);
        int publication = callIndex(runOwned, "java/lang/Runnable", "run", "()V");
        int stateRelease = opcodeIndexAfter(runOwned, Opcodes.MONITOREXIT, publication);
        assertTrue(stateMonitor >= 0 && revalidate > stateMonitor
                        && publication > revalidate && stateRelease > publication,
                "PlayerState ownership must stay locked across the ServerEntity state machine");
        MethodNode shouldDefer = method(a5, "shouldDeferTrackingPublication",
                "(Lnet/minecraft/server/level/ServerPlayer;L" + P_ISLAND + ";)Z");
        assertEquals(1, callCount(shouldDefer, A5,
                "isIslandListenerOwnedPhase"),
                "handoff/early/late phases must still suppress a concurrent Main publisher");

        MethodNode claimedMain = method(tracking, "mainRouteOrSchedule",
                "(L" + TRACKER_ACCESS + ";Lnet/minecraft/world/entity/Entity;L"
                        + REGIONIZED_SERVER + ";)L" + TRACKING + "$Route;");
        int physicalMain = callIndex(claimedMain, TRACKING, "physicalMain",
                "(Lnet/minecraft/world/entity/Entity;)Z");
        int owner = callIndexAfter(claimedMain, REGIONIZED_SERVER,
                "islandOwnerOfChunk", "(J)L" + P_ISLAND + ";", physicalMain);
        int allClaims = callIndexAfter(claimedMain, REGIONIZED_SERVER,
                "currentThreadHoldsAllClaims", "()Z", owner);
        int exactClaim = callIndexAfter(claimedMain,
                "com/axalotl/fabrolia/common/regions/IslandCommands",
                "mainHolds", "(L" + P_ISLAND + ";)Z", allClaims);
        int schedule = callIndexAfter(claimedMain, TRACKING,
                "scheduleMainPublication", "(L" + TRACKER_ACCESS + ";)V", exactClaim);
        int drop = fieldOpcodeIndexAfter(claimedMain, Opcodes.GETSTATIC,
                TRACKING + "$Route", "DROP", schedule);
        assertTrue(physicalMain >= 0 && owner > physicalMain && allClaims > owner
                        && exactClaim > allClaims && schedule > exactClaim && drop > schedule,
                "an active async world may return MAIN only while physical Main owns all claims "
                        + "or the entity's exact owner; otherwise it schedules and drops");

        MethodNode beginPublication = method(tracking, "beginMainPublication",
                "(Ljava/lang/Object;Lnet/minecraft/world/entity/Entity;)Z");
        int beginPublishing = fieldOpcodeIndexAfter(beginPublication, Opcodes.GETSTATIC,
                TRACKING, "POST_PHYSICS_CROSSING_PUBLISHING", -1);
        int beginPublishingGet = callIndexAfter(beginPublication,
                "java/lang/ThreadLocal", "get", "()Ljava/lang/Object;", beginPublishing);
        int beginBypassBranch = opcodeIndexAfter(beginPublication,
                Opcodes.IF_ACMPNE, beginPublishingGet);
        int beginBypassReturn = opcodeIndexAfter(beginPublication,
                Opcodes.IRETURN, beginBypassBranch);
        int beginRuntime = callIndexAfter(beginPublication, TRACKING, "runtime",
                "(Lnet/minecraft/world/entity/Entity;)L" + REGIONIZED_SERVER + ";",
                beginBypassReturn);
        int persistentPublisher = callIndexAfter(beginPublication, TRACKING,
                "requiresPersistentMainPublisher",
                "(Lnet/minecraft/world/entity/Entity;)Z", beginRuntime);
        int serverTick = callIndexAfter(beginPublication,
                "net/minecraft/server/MinecraftServer", "getTickCount", "()I",
                persistentPublisher);
        int exactStamp = callIndexAfter(beginPublication, TRACKER_ACCESS,
                "fabrolia$tryBeginMainTrackingPublication", "(I)Z", serverTick);
        assertTrue(beginPublishing >= 0 && beginPublishingGet > beginPublishing
                        && beginBypassBranch > beginPublishingGet
                        && beginBypassReturn > beginBypassBranch
                        && beginRuntime > beginBypassReturn
                        && persistentPublisher > beginRuntime
                        && serverTick > persistentPublisher && exactStamp > serverTick,
                "an authenticated recursive island publication bypasses hard-Main stamping; only "
                        + "a real persistent Main publisher stamps the exact current server tick");

        MethodNode deferDuplicate = method(tracking, "deferDuplicateMainPublication",
                "(Ljava/lang/Object;)V");
        assertEquals(0, callCount(deferDuplicate, TRACKING,
                "scheduleMainPublication"),
                "a same-tick duplicate is terminal and must not replay the caller later");
        assertEquals(0, typeCount(deferDuplicate, Opcodes.NEW,
                "net/minecraft/server/TickTask"));
    }

    @Test
    void stagedMembershipUsesExactCommitScopeAndTrackingMainTypeFence() throws Exception {
        ClassNode tracking = readClass(TRACKING);

        MethodNode stage = method(tracking, "stagePairingIfOwned",
                "(L" + TRACKER_ACCESS
                        + ";Lnet/minecraft/server/level/ServerPlayer;)Z");
        int state = callIndex(stage, TRACKER_ACCESS,
                "fabrolia$islandTrackingState", "()I");
        int queue = callIndex(stage, TRACKER_ACCESS,
                "fabrolia$queueIslandPairing",
                "(Lnet/minecraft/server/level/ServerPlayer;)Z");
        int routeObligation = callIndex(stage, TRACKING, "routeMembershipObligation",
                "(L" + TRACKER_ACCESS + ";Lnet/minecraft/world/entity/Entity;L"
                        + REGIONIZED_SERVER + ";)V");
        assertTrue(state >= 0 && queue > state && routeObligation > queue,
                "a full updatePlayer is suppressed only after its exact session is queued, then "
                        + "the common membership router selects owner or deferred Main");

        MethodNode stageRemoval = method(tracking, "stageRemovalIfOwned",
                "(L" + TRACKER_ACCESS
                        + ";Lnet/minecraft/server/level/ServerPlayer;)Z");
        int removalState = callIndex(stageRemoval, TRACKER_ACCESS,
                "fabrolia$islandTrackingState", "()I");
        int queueRemoval = callIndex(stageRemoval, TRACKER_ACCESS,
                "fabrolia$queueIslandRemoval",
                "(Lnet/minecraft/server/level/ServerPlayer;)Z");
        int routeRemoval = callIndex(stageRemoval, TRACKING, "routeMembershipObligation",
                "(L" + TRACKER_ACCESS + ";Lnet/minecraft/world/entity/Entity;L"
                        + REGIONIZED_SERVER + ";)V");
        assertTrue(removalState >= 0 && queueRemoval > removalState
                        && routeRemoval > queueRemoval,
                "removePlayer uses the same lossless staged owner protocol as updatePlayer");

        MethodNode stageBulk = method(tracking, "stageBulkPairingRefreshIfOwned",
                "(L" + TRACKER_ACCESS + ";)Z");
        int bulkMonitor = opcodeIndex(stageBulk, Opcodes.MONITORENTER);
        int bulkState = callIndexAfter(stageBulk, TRACKER_ACCESS,
                "fabrolia$islandTrackingState", "()I", bulkMonitor);
        int bulkQueue = callIndexAfter(stageBulk, TRACKER_ACCESS,
                "fabrolia$queueIslandBulkPairingRefresh", "()Z", bulkState);
        int bulkRoute = callIndexAfter(stageBulk, TRACKING, "routeMembershipObligation",
                "(L" + TRACKER_ACCESS + ";Lnet/minecraft/world/entity/Entity;L"
                        + REGIONIZED_SERVER + ";)V", bulkQueue);
        assertTrue(bulkMonitor >= 0 && bulkState > bulkMonitor
                        && bulkQueue > bulkState && bulkRoute > bulkQueue,
                "direct all-player VMP staging must hold one tracker transaction while it "
                        + "revalidates, publishes the bulk token and selects its owner/Main venue");
        assertEquals(1, opcodeCount(stageBulk, Opcodes.MONITORENTER),
                "bulk staging must not expose a queue-to-routing monitor gap");

        MethodNode isBulkCommit = method(tracking, "isBulkPairingCommit",
                "(L" + TRACKER_ACCESS + ";)Z");
        assertEquals(1, fieldOpcodeCount(isBulkCommit, Opcodes.GETSTATIC,
                TRACKING, "BULK_PAIRING_COMMIT"));
        assertEquals(1, callCount(isBulkCommit, "java/lang/ThreadLocal", "get"));
        assertTrue(referenceCompareCount(isBulkCommit) >= 1,
                "bulk bypass authenticates the exact tracker identity");

        MethodNode bulkCommitScope = method(tracking, "commitBulkPairingRefresh",
                "(L" + TRACKER_ACCESS + ";Ljava/lang/Runnable;)V");
        int bulkLockProof = callIndex(bulkCommitScope, "java/lang/Thread", "holdsLock",
                "(Ljava/lang/Object;)Z");
        int bulkEnter = callIndex(bulkCommitScope, "java/lang/ThreadLocal", "set", null);
        int bulkBody = callIndex(bulkCommitScope, REGION_SCHEDULER,
                "runSuccessBodyIfHealthy", "(Ljava/lang/Runnable;)V");
        int bulkLeave = callIndex(bulkCommitScope, "java/lang/ThreadLocal", "remove", "()V");
        assertTrue(bulkLockProof >= 0 && bulkEnter > bulkLockProof
                        && bulkBody > bulkEnter && bulkLeave > bulkBody,
                "BulkPairingCommit must hold the exact tracker across transformed updatePlayers");
        assertEquals(2, callCount(bulkCommitScope, "java/lang/ThreadLocal", "get"),
                "bulk commit rejects nesting with both bulk and per-player commit scopes");
        assertEquals(1, callCount(bulkCommitScope, "java/lang/ThreadLocal", "remove"),
                "the unified cleanup edge clears the bulk bypass after either outcome");

        MethodNode exactCommit = method(tracking, "isPairingCommit",
                "(L" + TRACKER_ACCESS
                        + ";Lnet/minecraft/server/level/ServerPlayer;)Z");
        assertEquals(1, fieldOpcodeCount(exactCommit, Opcodes.GETSTATIC,
                TRACKING, "PAIRING_COMMIT"));
        assertEquals(1, callCount(exactCommit, PENDING_PAIRING, "intent"));
        assertEquals(1, fieldOpcodeCount(exactCommit, Opcodes.GETSTATIC,
                PAIRING_INTENT, "REMOVE"),
                "only desired-PRESENT may re-enter updatePlayer");
        MethodNode exactSession = method(tracking, "pairingCommitStillCurrent",
                "(L" + TRACKER_ACCESS
                        + ";Lnet/minecraft/server/level/ServerPlayer;)Z");
        assertEquals(1, callCount(exactSession, PENDING_PAIRING, "matchesExactly"),
                "updatePlayer authenticates the captured listener immediately before its body");
        MethodNode removalCommit = method(tracking, "isRemovalCommit",
                "(L" + TRACKER_ACCESS
                        + ";Lnet/minecraft/server/level/ServerPlayer;)Z");
        assertEquals(1, fieldOpcodeCount(removalCommit, Opcodes.GETSTATIC,
                PAIRING_INTENT, "REMOVE"));
        assertEquals(1, callCount(removalCommit, PENDING_PAIRING,
                "matchesRemovalPlayer"),
                "remove re-entry deliberately survives listener/player lifecycle rebinding");

        MethodNode commit = method(tracking, "commitPairing",
                "(L" + TRACKER_ACCESS + ";L" + PENDING_PAIRING
                        + ";Ljava/lang/Runnable;)V");
        assertEquals(1, callCount(commit, REGION_SCHEDULER,
                "runSuccessBodyIfHealthy"),
                "non-detached callers still require an exact pairing admission");
        MethodNode admittedCommit = method(tracking, "commitPairingInAdmittedBody",
                "(L" + TRACKER_ACCESS + ";L" + PENDING_PAIRING
                        + ";Ljava/lang/Runnable;)V");
        int lockProof = callIndex(admittedCommit, "java/lang/Thread", "holdsLock",
                "(Ljava/lang/Object;)Z");
        int enter = callIndex(admittedCommit, "java/lang/ThreadLocal", "set", null);
        int body = callIndex(admittedCommit, "java/lang/Runnable", "run", "()V");
        int leave = callIndex(admittedCommit, "java/lang/ThreadLocal", "remove", "()V");
        assertTrue(lockProof >= 0 && enter > lockProof && body > enter && leave > body,
                "PairingCommit must cover the transformed update/remove body under the exact "
                        + "tracker lock");
        assertEquals(0, callCount(admittedCommit, REGION_SCHEDULER,
                "runSuccessBodyIfHealthy"),
                "an already-detached pairing body must not face a second refusal cut");
        assertEquals(1, callCount(admittedCommit, "java/lang/ThreadLocal", "remove"),
                "the unified cleanup edge must clear PairingCommit after either outcome");

        MethodNode mainOwned = method(tracking, "mainOwnedEntityType",
                "(Lnet/minecraft/world/entity/Entity;)Z");
        assertEquals(1, typeCount(mainOwned, Opcodes.INSTANCEOF,
                "net/minecraft/world/entity/boss/wither/WitherBoss"));
        assertEquals(1, typeCount(mainOwned, Opcodes.INSTANCEOF,
                "net/minecraft/world/entity/boss/enderdragon/EnderDragon"));

        MethodNode persistentMain = method(tracking, "requiresPersistentMainPublisher",
                "(Lnet/minecraft/world/entity/Entity;)Z");
        assertEquals(1, callCount(persistentMain, TRACKING, "mainOwnedEntityType"),
                "Wither/Dragon are the direct hard-Main publisher set");
        assertEquals(1, typeCount(persistentMain, Opcodes.INSTANCEOF,
                "net/minecraft/server/level/ServerPlayer"));
        int removedPlayer = callIndex(persistentMain,
                "net/minecraft/server/level/ServerPlayer", "isRemoved", "()Z");
        int disconnectedPlayer = callIndexAfter(persistentMain,
                "net/minecraft/server/level/ServerPlayer", "hasDisconnected", "()Z",
                removedPlayer);
        int attachedSupported = callIndexAfter(persistentMain, A5,
                "supportsIslandPlayerType", "(Lnet/minecraft/server/level/ServerPlayer;)Z",
                disconnectedPlayer);
        int attachedOwner = callIndexAfter(persistentMain, A5,
                "hasAttachedOwner", "(Lnet/minecraft/server/level/ServerPlayer;)Z",
                attachedSupported);
        int forcedPlayer = callIndexAfter(persistentMain,
                "com/axalotl/fabrolia/common/ParallelProcessor", "isForcedMainThread",
                "(Lnet/minecraft/world/entity/Entity;)Z", attachedOwner);
        int supportedPlayer = callIndexAfter(persistentMain, A5,
                "supportsIslandPlayerType", "(Lnet/minecraft/server/level/ServerPlayer;)Z",
                forcedPlayer);
        int playerRuntime = callIndexAfter(persistentMain, TRACKING, "runtime",
                "(Lnet/minecraft/world/entity/Entity;)L" + REGIONIZED_SERVER + ";",
                supportedPlayer);
        int playerOwner = callIndexAfter(persistentMain, TRACKING, "currentOwner",
                "(L" + REGIONIZED_SERVER
                        + ";Lnet/minecraft/world/entity/Entity;)L" + P_ISLAND + ";",
                playerRuntime);
        int deferSupported = callIndexAfter(persistentMain, A5,
                "shouldDeferTrackingPublication",
                "(Lnet/minecraft/server/level/ServerPlayer;L" + P_ISLAND + ";)Z",
                playerOwner);
        assertTrue(removedPlayer >= 0 && disconnectedPlayer > removedPlayer
                        && attachedSupported > disconnectedPlayer
                        && attachedOwner > attachedSupported && forcedPlayer > attachedOwner
                        && supportedPlayer > forcedPlayer && playerRuntime > supportedPlayer
                        && playerOwner > playerRuntime && deferSupported > playerOwner,
                "removed/disconnected players stop; an attached supported A5 owner is never "
                        + "reclassified Main by a spatial crossing, while forced/unsupported or "
                        + "durably non-deferred players retain the hard-Main path");
        assertEquals(2, callCount(persistentMain, A5, "supportsIslandPlayerType"));
        assertEquals(1, callCount(persistentMain,
                "net/minecraft/world/entity/Entity", "getRootVehicle"));
        assertEquals(2, callCount(persistentMain,
                "com/axalotl/fabrolia/common/ParallelProcessor", "requiresMainThread"),
                "non-player persistent Main publication follows both root and exact entity "
                        + "simulation affinity");

        ClassNode parallel = readClass(
                "com/axalotl/fabrolia/common/ParallelProcessor");
        MethodNode recursiveHardMain = method(parallel,
                "containsMainOwnedTrackingEntity",
                "(Lnet/minecraft/world/entity/Entity;)Z");
        assertEquals(1, typeCount(recursiveHardMain, Opcodes.INSTANCEOF,
                "net/minecraft/world/entity/boss/wither/WitherBoss"));
        assertEquals(1, typeCount(recursiveHardMain, Opcodes.INSTANCEOF,
                "net/minecraft/world/entity/boss/enderdragon/EnderDragon"));
        assertEquals(1, callCount(recursiveHardMain,
                "net/minecraft/world/entity/Entity", "getPassengers"));
        assertEquals(1, callCount(recursiveHardMain, parallel.name,
                "containsMainOwnedTrackingEntity"),
                "Wither/Dragon hard-Main authority must cover the full passenger tree");
        MethodNode requiresMain = method(parallel, "requiresMainThread",
                "(Lnet/minecraft/world/entity/Entity;)Z");
        MethodNode synchronousTick = method(parallel, "shouldTickSynchronously",
                "(Lnet/minecraft/world/entity/Entity;)Z");
        assertEquals(1, callCount(requiresMain, parallel.name,
                "containsMainOwnedTrackingEntity"));
        assertEquals(1, callCount(synchronousTick, parallel.name,
                "containsMainOwnedTrackingEntity"),
                "Wither and Dragon simulation/tracking remain recursively hard Main");

        ClassNode pending = readClass(PENDING_PAIRING);
        assertEquals("Lnet/minecraft/server/level/ServerPlayer;",
                field(pending, "player").desc);
        assertEquals("Lnet/minecraft/server/network/ServerGamePacketListenerImpl;",
                field(pending, "listener").desc);
        assertEquals("L" + PAIRING_INTENT + ";", field(pending, "intent").desc);
        ClassNode intent = readClass(PAIRING_INTENT);
        for (String name : List.of("ADD", "REVALIDATE", "REMOVE")) {
            FieldNode constant = field(intent, name);
            assertEquals("L" + PAIRING_INTENT + ";", constant.desc);
            assertTrue((constant.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC
                    | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM))
                    == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC
                    | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM));
        }
        MethodNode matches = method(pending, "matchesExactly",
                "(Lnet/minecraft/server/level/ServerPlayer;)Z");
        assertTrue(referenceCompareCount(matches) >= 3,
                "the staged record must authenticate candidate, connection, and listener.player "
                        + "by identity");
        MethodNode removalMatches = method(pending, "matchesRemovalPlayer",
                "(Lnet/minecraft/server/level/ServerPlayer;)Z");
        assertEquals(1, referenceCompareCount(removalMatches),
                "desired-ABSENT is anchored only to captured player identity, not live session");
    }

    

    

    

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = 0; (at = text.indexOf(needle, at)) >= 0; at += needle.length()) {
            count++;
        }
        return count;
    }

    private static boolean mixinTargets(ClassNode type, String internalName) {
        AnnotationNode mixin = annotation(type, "Lorg/spongepowered/asm/mixin/Mixin;");
        if (mixin == null || mixin.values == null) return false;
        for (Object value : mixin.values) {
            if (value instanceof Type target
                    && internalName.equals(target.getInternalName())) return true;
            if (value instanceof List<?> targets) {
                for (Object target : targets) {
                    if (target instanceof Type targetType
                            && internalName.equals(targetType.getInternalName())) return true;
                }
            }
        }
        return false;
    }

    private static Object annotationValue(AnnotationNode annotation, String name) {
        if (annotation.values == null) return null;
        for (int index = 0; index + 1 < annotation.values.size(); index += 2) {
            if (name.equals(annotation.values.get(index))) {
                return annotation.values.get(index + 1);
            }
        }
        return null;
    }

    private static AnnotationNode firstNestedAnnotation(AnnotationNode annotation,
                                                        String name) {
        Object value = annotationValue(annotation, name);
        if (value instanceof AnnotationNode nested) return nested;
        if (value instanceof List<?> values && !values.isEmpty()
                && values.getFirst() instanceof AnnotationNode nested) return nested;
        throw new AssertionError("missing nested annotation " + name);
    }

    private static boolean annotationValueContains(AnnotationNode annotation,
                                                   String name, String expected) {
        Object value = annotationValue(annotation, name);
        if (expected.equals(value)) return true;
        return value instanceof List<?> values && values.contains(expected);
    }

    private static AnnotationNode annotation(MethodNode method, String descriptor) {
        AnnotationNode found = annotation(method.visibleAnnotations, descriptor);
        return found != null ? found : annotation(method.invisibleAnnotations, descriptor);
    }

    private static AnnotationNode annotation(FieldNode field, String descriptor) {
        AnnotationNode found = annotation(field.visibleAnnotations, descriptor);
        return found != null ? found : annotation(field.invisibleAnnotations, descriptor);
    }

    private static AnnotationNode annotation(ClassNode type, String descriptor) {
        AnnotationNode found = annotation(type.visibleAnnotations, descriptor);
        return found != null ? found : annotation(type.invisibleAnnotations, descriptor);
    }

    private static AnnotationNode annotation(List<AnnotationNode> annotations,
                                             String descriptor) {
        if (annotations == null) return null;
        return annotations.stream().filter(candidate -> descriptor.equals(candidate.desc))
                .findFirst().orElse(null);
    }

    private static FieldNode field(ClassNode type, String name) {
        return type.fields.stream().filter(candidate -> name.equals(candidate.name))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "missing field " + type.name + "." + name));
    }

    private static void assertTrackingConstant(ClassNode access, String name, int value) {
        FieldNode field = field(access, name);
        assertEquals("I", field.desc);
        assertEquals(Integer.valueOf(value), field.value);
        assertTrue((field.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL))
                        == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL),
                name + " must remain an inlinable interface state constant");
    }

    private static MethodNode method(ClassNode type, String name) {
        List<MethodNode> methods = type.methods.stream()
                .filter(candidate -> name.equals(candidate.name)).toList();
        assertEquals(1, methods.size(), "expected one " + type.name + "." + name);
        return methods.getFirst();
    }

    private static MethodNode method(ClassNode type, String name, String descriptor) {
        return type.methods.stream()
                .filter(candidate -> name.equals(candidate.name)
                        && descriptor.equals(candidate.desc))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "missing exact method " + type.name + "." + name + descriptor));
    }

    private static int invokeDynamicCount(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof InvokeDynamicInsnNode) count++;
        }
        return count;
    }

    private static int invokedynamicTargetCount(MethodNode method, String owner,
                                                String name, String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof InvokeDynamicInsnNode dynamic)) continue;
            for (Object argument : dynamic.bsmArgs) {
                if (argument instanceof Handle target
                        && owner.equals(target.getOwner())
                        && name.equals(target.getName())
                        && descriptor.equals(target.getDesc())) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int callCount(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) count++;
        }
        return count;
    }

    private static int callIndex(MethodNode method, String owner, String name,
                                 String descriptor) {
        return callIndexAfter(method, owner, name, descriptor, -1);
    }

    private static int callIndexAfter(MethodNode method, String owner, String name,
                                      String descriptor, int after) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (index > after && instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && (descriptor == null || descriptor.equals(call.desc))) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static int callNameIndexAfter(MethodNode method, String name, int after) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (index > after && instruction instanceof MethodInsnNode call
                    && name.equals(call.name)) return index;
            index++;
        }
        return -1;
    }

    private static int callNameCount(MethodNode method, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && name.equals(call.name)) count++;
        }
        return count;
    }

    private static int lastCallIndexBefore(MethodNode method, String owner,
                                           String name, int before) {
        int found = -1;
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (index >= before) break;
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) found = index;
            index++;
        }
        return found;
    }

    private static int fieldReadCount(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && owner.equals(field.owner) && name.equals(field.name)) count++;
        }
        return count;
    }

    private static int fieldWriteCount(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && owner.equals(field.owner) && name.equals(field.name)) count++;
        }
        return count;
    }

    private static int fieldOpcodeCount(MethodNode method, int opcode,
                                        String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == opcode && owner.equals(field.owner)
                    && name.equals(field.name)) count++;
        }
        return count;
    }

    private static int fieldOpcodeIndexAfter(MethodNode method, int opcode,
                                             String owner, String name, int after) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (index > after && instruction instanceof FieldInsnNode field
                    && field.getOpcode() == opcode && owner.equals(field.owner)
                    && name.equals(field.name)) return index;
            index++;
        }
        return -1;
    }

    private static int lastFieldOpcodeIndexBefore(MethodNode method, int opcode,
                                                  String owner, String name, int before) {
        int found = -1;
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (index >= before) break;
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == opcode && owner.equals(field.owner)
                    && name.equals(field.name)) found = index;
            index++;
        }
        return found;
    }

    private static int fieldReadIndex(MethodNode method, String owner, String name) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && owner.equals(field.owner) && name.equals(field.name)) return index;
            index++;
        }
        return -1;
    }

    private static int fieldWriteIndex(MethodNode method, String owner, String name) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && owner.equals(field.owner) && name.equals(field.name)) return index;
            index++;
        }
        return -1;
    }

    private static int typeCount(MethodNode method, int opcode, String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode type
                    && type.getOpcode() == opcode && descriptor.equals(type.desc)) count++;
        }
        return count;
    }

    private static int typeIndex(MethodNode method, int opcode, String descriptor) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode type
                    && type.getOpcode() == opcode && descriptor.equals(type.desc)) return index;
            index++;
        }
        return -1;
    }

    private static int opcodeIndex(MethodNode method, int opcode) {
        return opcodeIndexAfter(method, opcode, -1);
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

    private static int referenceCompareIndexAfter(MethodNode method, int after) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (index > after && (instruction.getOpcode() == Opcodes.IF_ACMPEQ
                    || instruction.getOpcode() == Opcodes.IF_ACMPNE)) return index;
            index++;
        }
        return -1;
    }

    private static int referenceCompareCount(MethodNode method) {
        return opcodeCount(method, Opcodes.IF_ACMPEQ)
                + opcodeCount(method, Opcodes.IF_ACMPNE);
    }

    private static AbstractInsnNode realInstructionAt(MethodNode method, int index) {
        return method.instructions.get(index);
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        for (AbstractInsnNode previous = instruction.getPrevious(); previous != null;
             previous = previous.getPrevious()) {
            if (previous.getOpcode() >= 0) return previous;
        }
        return null;
    }

    private static boolean hasBranchBetween(MethodNode method, int from, int to) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (index > from && index < to) {
                if (instruction instanceof JumpInsnNode jump
                        && jump.getOpcode() != Opcodes.GOTO
                        && jump.getOpcode() != Opcodes.JSR) return true;
                if (instruction instanceof TableSwitchInsnNode
                        || instruction instanceof LookupSwitchInsnNode) return true;
            }
            index++;
        }
        return false;
    }

    private static boolean hasConditionalJumpTargetBetween(
            MethodNode method, int branchAfter, int branchBefore,
            int targetAfter, int targetBefore) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (index > branchAfter && index < branchBefore
                    && instruction instanceof JumpInsnNode jump
                    && jump.getOpcode() != Opcodes.GOTO
                    && jump.getOpcode() != Opcodes.JSR) {
                int target = method.instructions.indexOf(jump.label);
                if (target > targetAfter && target < targetBefore) return true;
            }
            index++;
        }
        return false;
    }

    private static AbstractInsnNode catchAllHandlerCovering(MethodNode method,
                                                           int instructionIndex) {
        for (var block : method.tryCatchBlocks) {
            int start = method.instructions.indexOf(block.start);
            int end = method.instructions.indexOf(block.end);
            if (block.type == null && start <= instructionIndex
                    && instructionIndex < end) {
                return block.handler;
            }
        }
        return null;
    }

    private static String productionSource(String relative) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path direct = root.resolve("src/main/java/com/axalotl/fabrolia/common")
                .resolve(relative);
        Path viaCommon = root.resolve("common/src/main/java/com/axalotl/fabrolia/common")
                .resolve(relative);
        if (Files.isRegularFile(direct)) return Files.readString(direct);
        if (Files.isRegularFile(viaCommon)) return Files.readString(viaCommon);
        throw new AssertionError("missing production source " + relative);
    }

    private static ClassNode readClass(String internalName) throws Exception {
        try (InputStream input = IslandEntityTrackingWiringTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            assertNotNull(input, "missing compiled class " + internalName);
            ClassNode type = new ClassNode();
            new ClassReader(input).accept(type, 0);
            return type;
        }
    }
}
