package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.commands.RegionCommand;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.axalotl.fabrolia.common.mixin.commands.ServerFunctionManagerMixin;
import com.axalotl.fabrolia.common.mixin.network.PacketProcessorPlayerTickMixin;
import com.axalotl.fabrolia.common.mixin.network.ServerCommonPacketListenerPlayerTickFlushMixin;
import com.axalotl.fabrolia.common.mixin.server.MinecraftServerMixin;
import com.axalotl.fabrolia.common.mixin.server.ServerChunkCacheMainThreadExecutorMixin;
import com.axalotl.fabrolia.common.mixin.server.ServerPlayerGameModeAccessor;
import com.axalotl.fabrolia.common.mixin.server.ServerPlayerMixin;
import com.axalotl.fabrolia.common.mixin.utils.ProfilerAccessor;
import com.axalotl.fabrolia.common.mixin.world.ServerLevelMixin;
import net.minecraft.network.PacketProcessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerTickV2WiringTest {

    private enum FsitPoseFixture { STANDING, SITTING }

    @Test
    void leaseWaitProgressAdmitsEachChunkCallbackAtomically() throws Exception {
        ClassNode coordinator = readClass(RegionalPlayerEntityTicksV2.class);
        int connectionAdmissions = coordinator.methods.stream()
                .filter(method -> method.name.startsWith("lambda$beforeConnections$"))
                .mapToInt(method -> callCount(method, internal(RegionScheduler.class),
                        "callSuccessBooleanIfHealthy"))
                .sum();
        int levelAdmissions = coordinator.methods.stream()
                .filter(method -> method.name.startsWith("lambda$finishLevel$"))
                .mapToInt(method -> callCount(method, internal(RegionScheduler.class),
                        "callSuccessBooleanIfHealthy"))
                .sum();

        assertEquals(1, connectionAdmissions,
                "connection-barrier progress must atomically admit its one callback");
        assertEquals(1, levelAdmissions,
                "level-barrier progress must atomically admit its one callback");
    }

    @Test
    void exactlyOneMutableGateAndNoV1ConfigGateExists() {
        List<String> mutableBooleans = new ArrayList<>();
        for (var field : RegionalPlayerEntityTicksV2.class.getDeclaredFields()) {
            if (field.getType() == boolean.class && !Modifier.isFinal(field.getModifiers())) {
                mutableBooleans.add(field.getName());
            }
        }
        assertEquals(List.of("enabled"), mutableBooleans);
        assertFalse(hasField(com.axalotl.fabrolia.common.config.FabroliaConfig.class,
                "enableRegionPlayerTicks"));
        assertFalse(hasField(com.axalotl.fabrolia.common.config.FabroliaConfig.class,
                "regionalPlayerTicksExperimental"));
    }

    @Test
    void refusedSnapshotExceptionalCleanupDoesNotDereferenceDisabledSentinel() throws Exception {
        var currentField = RegionalPlayerEntityTicksV2.class.getDeclaredField("CURRENT");
        var disabledField = RegionalPlayerEntityTicksV2.class.getDeclaredField("DISABLED_PHASE");
        currentField.setAccessible(true);
        disabledField.setAccessible(true);
        @SuppressWarnings("unchecked")
        AtomicReference<Object> current = (AtomicReference<Object>) currentField.get(null);
        Object disabled = disabledField.get(null);
        assertTrue(current.compareAndSet(null, disabled));
        try {
            assertNull(RegionalPlayerEntityTicksV2.abortServerTick());
            assertNull(current.get(), "disabled sentinel must not latch the next server phase");
        } finally {
            current.compareAndSet(disabled, null);
        }
    }

    @Test
    void liveAsyncEnableWaitsForTheOpenExactPlayerWallPhase() throws Exception {
        var currentField = RegionalPlayerEntityTicksV2.class.getDeclaredField("CURRENT");
        var disabledField =
                RegionalPlayerEntityTicksV2.class.getDeclaredField("DISABLED_PHASE");
        currentField.setAccessible(true);
        disabledField.setAccessible(true);
        @SuppressWarnings("unchecked")
        AtomicReference<Object> current = (AtomicReference<Object>) currentField.get(null);
        Object disabled = disabledField.get(null);

        Class<?> phaseType = Class.forName(
                RegionalPlayerEntityTicksV2.class.getName() + "$GlobalPhase");
        var constructor = phaseType.getDeclaredConstructor(
                long.class, boolean.class, MinecraftServer.class);
        constructor.setAccessible(true);
        Object exactPhase = constructor.newInstance(1L, true, null);

        assertTrue(current.compareAndSet(null, exactPhase));
        try {
            assertFalse(RegionalPlayerEntityTicksV2
                            .asyncActivationAllowedAtLevelHead(),
                    "an online-player exact phase must retain the whole wall tick");
            assertTrue(current.compareAndSet(exactPhase, disabled));
            assertTrue(RegionalPlayerEntityTicksV2
                            .asyncActivationAllowedAtLevelHead(),
                    "a disabled exact phase permits autonomous island activation");
            assertTrue(current.compareAndSet(disabled, null));
            assertTrue(RegionalPlayerEntityTicksV2
                            .asyncActivationAllowedAtLevelHead(),
                    "outside a wall phase there is no competing player owner");
        } finally {
            current.set(null);
        }
    }

    @Test
    void exceptionalFailureMergePreservesPrimaryWhenLaterCleanupIsClean() throws Exception {
        var merge = RegionalPlayerEntityTicksV2.class.getDeclaredMethod(
                "mergeFailure", Throwable.class, Throwable.class);
        merge.setAccessible(true);
        RuntimeException primary = new RuntimeException("exact world failure");
        assertSame(primary, merge.invoke(null, primary, null));
        assertEquals(0, primary.getSuppressed().length);
    }


    @Test
    void v2CallsGuardedEntityConsumerAndNeverDoTickOrPacketHandlers() throws Exception {
        ClassNode coordinator = readClass(RegionalPlayerEntityTicksV2.class);
        assertTrue(calls(coordinator, internal(PlayerTickActor.class),
                "executeExactAndAwait"));
        assertFalse(calls(coordinator, internal(ParallelProcessor.class), "tickEntityVanillaGuarded"),
                "the removed post-fan-in player lane must not remain callable");
        assertFalse(calls(coordinator, "net/minecraft/server/level/ServerPlayer", "tick"));
        assertFalse(calls(coordinator, "net/minecraft/server/level/ServerPlayer", "doTick"));
        assertFalse(anyOwnerContains(coordinator, "PacketUtils"));
        assertFalse(anyMethodNameStarts(coordinator, "handle"));
        assertTrue(calls(coordinator, internal(PlayerTickKnownModEnvelope.class), "idle"),
                "known Carpet/FSit active state must fail closed to the main path");

        MethodNode beginServerTick = method(coordinator, "beginServerTick",
                "(Lnet/minecraft/server/MinecraftServer;)V");
        int actorFatal = callIndex(beginServerTick, internal(PlayerTickActor.class),
                "terminalFailure");
        int publishPhase = callIndex(beginServerTick,
                "java/util/concurrent/atomic/AtomicReference", "compareAndSet");
        assertTrue(actorFatal >= 0 && publishPhase > actorFatal,
                "an actor failure must reach physical Main before a successor phase opens");

        for (ClassNode removedLaneOwner : List.of(coordinator,
                readClass(RegionWorkerPool.class), readClass(RegionizedServer.class))) {
            assertFalse(removedLaneOwner.fields.stream()
                            .anyMatch(field -> field.name.equals("playerTasks")),
                    "the removed post-fan-in playerTasks lane returned in "
                            + removedLaneOwner.name);
            assertFalse(removedLaneOwner.methods.stream()
                            .anyMatch(method -> method.name.equals("routePlayerTree")),
                    "the removed reconstructed player-tree route returned in "
                            + removedLaneOwner.name);
        }

        ClassNode phaseStamp = readClass(PlayerTickPhaseStamp.class);
        List<String> stampContract = phaseStamp.methods.stream()
                .filter(method -> !method.name.startsWith("<"))
                .map(method -> method.name + method.desc)
                .sorted().toList();
        assertEquals(List.of("fabrolia$bumpPlayerLifecycleEpoch()V",
                        "fabrolia$playerLifecycleEpoch()J"), stampContract,
                "a wall-phase consumed stamp would reject vanilla-legal sequential direct consumers");
    }


    @Test
    void statusExplainsOnlineFallbacksWithoutReplacingTheHotPathPredicate() throws Exception {
        ClassNode coordinator = readClass(RegionalPlayerEntityTicksV2.class);
        String coordinatorOwner = internal(RegionalPlayerEntityTicksV2.class);
        String envelopeOwner = internal(PlayerTickKnownModEnvelope.class);
        assertFalse(hasField(RegionalPlayerEntityTicksV2.class, "ACTOR_TICKS"));
        assertFalse(hasField(RegionalPlayerEntityTicksV2.class, "MAIN_FALLBACKS"));

        MethodNode status = method(coordinator, "status",
                "(Lnet/minecraft/server/MinecraftServer;)Ljava/lang/String;");
        assertEquals(1, callCount(status, coordinatorOwner, "onlineEligibilityStatus"),
                "status must append one online eligibility summary");

        MethodNode online = method(coordinator, "onlineEligibilityStatus",
                "(Lnet/minecraft/server/MinecraftServer;)Ljava/lang/String;");
        assertEquals(1, callCount(online, coordinatorOwner, "onlinePlayerRefusal"),
                "the diagnostic loop must explain each online player's local refusal");

        MethodNode diagnostic = method(coordinator, "onlinePlayerRefusal",
                "(Lnet/minecraft/server/MinecraftServer;"
                        + "Lnet/minecraft/server/level/ServerPlayer;)Ljava/lang/String;");
        assertEquals(1, callCount(diagnostic, envelopeOwner, "refusalReason"),
                "the status path must obtain a concrete Carpet/FSit refusal reason");
        assertEquals(0, callCount(diagnostic, envelopeOwner, "idle"),
                "the diagnostic must not discard the known-mod refusal reason");
        assertEquals(1, fieldAccessCount(diagnostic, Opcodes.GETFIELD,
                        "net/minecraft/server/level/ServerPlayer", "portalProcess"),
                "diagnostics must distinguish worker-safe Nether charge from inline portals");
        assertEquals(1, typeInstructionCount(diagnostic, Opcodes.INSTANCEOF,
                        "net/minecraft/world/level/block/NetherPortalBlock"),
                "non-Nether portal transitions must remain Main-owned");

        MethodNode hotPath = method(coordinator, "playerEnvelope",
                "(Lcom/axalotl/fabrolia/common/regions/RegionalPlayerEntityTicksV2$LevelBatch;"
                        + "Lnet/minecraft/server/level/ServerPlayer;)Z");
        assertEquals(1, callCount(hotPath, envelopeOwner, "idle"),
                "the per-tick path must retain the allocation-free boolean probe");
        assertEquals(0, callCount(hotPath, envelopeOwner, "refusalReason"),
                "status diagnostics must not leak into the per-tick hot path");
        assertEquals(1, fieldAccessCount(hotPath, Opcodes.GETFIELD,
                        "net/minecraft/server/level/ServerPlayer", "portalProcess"),
                "the hot envelope must classify, not blanket-reject, a live portal charge");
        assertEquals(1, typeInstructionCount(hotPath, Opcodes.INSTANCEOF,
                        "net/minecraft/world/level/block/NetherPortalBlock"),
                "only Nether portal charge may advance inside the player actor");
        assertEquals(1, callCount(hotPath,
                        "com/axalotl/fabrolia/common/ParallelProcessor$TickGuard",
                        "fabrolia$isPortalPending"),
                "only the queued transfer, not portal charge, freezes player simulation");

        ClassNode envelope = readClass(PlayerTickKnownModEnvelope.class);
        MethodNode refusal = method(envelope, "refusalReason",
                "(Lnet/minecraft/server/level/ServerPlayer;)Ljava/lang/String;");
        assertEquals(2, callCount(refusal,
                        internal(PlayerTickKnownModEnvelope.class) + "$Probe", "idle"),
                "diagnostics must preserve both fail-closed Carpet and FSit probes");
        assertTrue(calls(envelope, internal(PlayerTickKnownModPoseState.class), "idle"),
                "the FSit reflection probe must use the tested fresh-player pose predicate");
    }

    @Test
    void freshFsitStandingPoseMayBeUnsentButActiveOrUnknownPosesFailClosed() {
        // Exact runtime shape from fsit 3.0.1-beta.2: PlayerMixin initialises the
        // current pose to STANDING, while ServerPlayerMixin leaves lastSentPose null
        // until fsit$sendPose writes the first packet state.
        assertTrue(PlayerTickKnownModPoseState.idle(FsitPoseFixture.STANDING, null));
        assertTrue(PlayerTickKnownModPoseState.idle(
                FsitPoseFixture.STANDING, FsitPoseFixture.STANDING));
        assertFalse(PlayerTickKnownModPoseState.idle(FsitPoseFixture.SITTING, null));
        assertFalse(PlayerTickKnownModPoseState.idle(
                FsitPoseFixture.STANDING, FsitPoseFixture.SITTING));
        assertThrows(ClassCastException.class,
                () -> PlayerTickKnownModPoseState.idle(null, null));
        assertThrows(ClassCastException.class,
                () -> PlayerTickKnownModPoseState.idle(FsitPoseFixture.STANDING, "STANDING"));
    }


    @Test
    void exactActorAndQueueOnlyEpochAreWiredAtTheCanonicalOrdinal() throws Exception {
        ClassNode coordinator = readClass(RegionalPlayerEntityTicksV2.class);
        MethodNode execute = method(coordinator, "executeExactOrdinal",
                "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;"
                        + "Ljava/lang/Runnable;)Z");
        List<Integer> executeFatalChecks = callIndices(execute,
                coordinator.name + "$LevelBatch", "throwIfFailed");
        int fatalBeforeOrdinal = executeFatalChecks.getFirst();
        int entityClassGate = callIndex(execute, "java/lang/Object", "getClass");
        int reserve = callIndexByName(execute, "reserveCandidate");
        int start = callIndex(execute, internal(PlayerTickPhase.class), "startWorker");
        int actor = callIndex(execute, internal(PlayerTickActor.class),
                "executeExactAndAwait");
        assertTrue(fatalBeforeOrdinal >= 0 && entityClassGate > fatalBeforeOrdinal
                        && reserve > entityClassGate && start > reserve && actor > start,
                "a published failure must stop even a non-player ordinal before class/body "
                        + "dispatch; healthy claims admit before actor publication");
        assertEquals(1, callCount(execute, internal(PlayerTickActor.class),
                "executeExactAndAwait"));
        assertEquals(2, executeFatalChecks.size(),
                "physical main must rethrow a phase failure both before ordinal dispatch "
                        + "and immediately after the accepted actor obligation returns");
        assertTrue(executeFatalChecks.getLast() > actor,
                "an actor-side failure may not remain ledgered while physical main "
                        + "returns to the vanilla entity loop");
        int enteredBeforeFallback = callIndex(execute, internal(PlayerTickPhase.class),
                "vanillaConsumerEntered");
        int mainFallbackIndex = callIndex(execute, internal(RegionalPlayerEntityTicksV2.class),
                "runExactMainFallback");
        assertTrue(enteredBeforeFallback > actor && mainFallbackIndex > enteredBeforeFallback,
                "an actor rejection after vanilla body entry must never retry on main");

        String tickContext = coordinator.name + "$TickContext";
        List<Integer> contextAllocations = callIndices(execute, tickContext, "<init>");
        int profilerCapture = callIndex(execute,
                "net/minecraft/util/profiling/Profiler", "get");
        assertEquals(2, contextAllocations.size(),
                "worker and already-allocated physical-main fallback contexts are both required");
        assertTrue(contextAllocations.stream().allMatch(index -> index > reserve && index < profilerCapture)
                        && profilerCapture < start && actor > start,
                "both TickContexts and Profiler.get must finish before RUNNING publication");
        List<TryCatchBlockNode> allocationCatches = execute.tryCatchBlocks.stream()
                .filter(block -> "java/lang/Throwable".equals(block.type)
                        && contextAllocations.stream().allMatch(index -> covers(execute, block, index))
                        && covers(execute, block, profilerCapture)
                        && !covers(execute, block, start))
                .toList();
        assertEquals(1, allocationCatches.size(),
                "every pre-RUNNING allocation must share one reservation-cancelling failure region");
        int allocationHandler = instructionIndex(execute, allocationCatches.getFirst().handler);
        int allocationFail = callIndexAfter(execute, coordinator.name + "$LevelBatch",
                "failBeforeBody", allocationHandler);
        int allocationCancel = callIndexAfter(execute, coordinator.name + "$LevelBatch",
                "cancelReservation", allocationFail);
        assertTrue(allocationFail > allocationHandler && allocationCancel > allocationFail
                        && allocationCancel < start,
                "allocation failure must terminalize and release RESERVED before startWorker");

        MethodNode actorTurn = method(coordinator, "runExactActorTurn",
                "(Lcom/axalotl/fabrolia/common/regions/RegionalPlayerEntityTicksV2$TickContext;"
                        + "Ljava/lang/Runnable;"
                        + "Lnet/minecraft/util/profiling/ProfilerFiller;)V");
        int publishOwner = callIndex(actorTurn,
                "java/util/concurrent/atomic/AtomicReference", "compareAndSet");
        int actorBeginLeases = callIndex(actorTurn, tickContext, "beginLeases");
        int actorInstallContext = callIndex(actorTurn,
                internal(RegionalPlayerEntityTicksV2.class), "installContext");
        int profilerAccessor = callIndex(actorTurn, internal(ProfilerAccessor.class),
                "fabrolia$activeProfiler");
        int profilerInstall = callIndex(actorTurn, "java/lang/ThreadLocal", "set");
        List<Integer> actorFatalChecks = callIndices(actorTurn,
                coordinator.name + "$LevelBatch", "throwIfFailed");
        int actorFatalCheck = actorFatalChecks.getFirst();
        int body = callIndex(actorTurn, internal(RegionScheduler.class),
                "runPausedExactActorBodyIfHealthy");
        List<Integer> actorFinishes = callIndices(actorTurn,
                internal(RegionalPlayerEntityTicksV2.class), "finishContext");
        int actorPublishFailure = callIndexAfter(actorTurn,
                coordinator.name + "$GlobalPhase", "fail", body);
        int actorFailureFinish = callIndexAfter(actorTurn,
                internal(RegionalPlayerEntityTicksV2.class), "finishContext",
                actorPublishFailure);
        List<Integer> profilerRemoves = callIndices(actorTurn,
                "java/lang/ThreadLocal", "remove");
        List<Integer> ownerTransitions = callIndices(actorTurn,
                "java/util/concurrent/atomic/AtomicReference", "compareAndSet");
        List<Integer> terminals = callIndices(actorTurn,
                internal(RegionalPlayerEntityTicksV2.class), "completeAndRetire");
        assertTrue(publishOwner >= 0 && actorBeginLeases > publishOwner
                        && actorInstallContext > actorBeginLeases
                        && profilerAccessor > actorInstallContext
                        && profilerInstall > profilerAccessor
                        && actorFatalCheck > profilerInstall && body > actorFatalCheck,
                "the actor must bind its claim, publish context/profiler state, and recheck "
                        + "the fatal slot immediately before the body");
        assertEquals(0, callCount(actorTurn, "java/lang/Runnable", "run"),
                "the actor consumer must not bypass its paused-owner atomic cut");
        assertEquals(2, actorFatalChecks.size(),
                "the actor must recheck the fatal slot after its one accepted body");
        assertTrue(actorFatalChecks.getLast() > body,
                "a failure published during the actor body must become that ordinal's failure");
        assertTrue(actorPublishFailure > body
                        && actorFailureFinish > actorPublishFailure,
                "an exact actor Throwable must win the global slot before release cleanup");
        assertEquals(3, terminals.size(),
                "normal, caught-body-failure, and catch-all exits each need terminal cleanup");
        assertEquals(terminals.size(), actorFinishes.size(),
                "every actor terminal copy must finish its context");
        assertEquals(terminals.size(), profilerRemoves.size(),
                "every actor terminal copy must clear an installed profiler");
        assertEquals(terminals.size() + 1, ownerTransitions.size(),
                "each terminal copy must clear logical ownership in addition to initial publication");
        for (int i = 0; i < terminals.size(); i++) {
            int finish = actorFinishes.get(i);
            int profilerRemove = profilerRemoves.get(i);
            int ownerClear = ownerTransitions.get(i + 1);
            int terminal = terminals.get(i);
            assertTrue(finish > body && profilerRemove > finish
                            && ownerClear > profilerRemove && terminal > ownerClear,
                    "actor exit " + i
                            + " must finish context, clear profiler/logical owner, then terminalize");
        }

        MethodNode mainFallback = method(coordinator, "runExactMainFallback",
                "(Lcom/axalotl/fabrolia/common/regions/RegionalPlayerEntityTicksV2$TickContext;"
                        + "Ljava/lang/Runnable;)V");
        List<Integer> mainFinishes = callIndices(mainFallback,
                internal(RegionalPlayerEntityTicksV2.class), "finishContext");
        List<Integer> mainTerminals = callIndices(mainFallback,
                internal(RegionalPlayerEntityTicksV2.class), "completeAndRetire");
        int mainBeginLeases = callIndex(mainFallback, tickContext, "beginLeases");
        int mainInstallContext = callIndex(mainFallback,
                internal(RegionalPlayerEntityTicksV2.class), "installContext");
        List<Integer> mainFatalChecks = callIndices(mainFallback,
                coordinator.name + "$LevelBatch", "throwIfFailed");
        int mainFatalCheck = mainFatalChecks.getFirst();
        int mainBody = callIndex(mainFallback, internal(RegionScheduler.class),
                "runSuccessBodyIfHealthy");
        int mainPublishFailure = callIndexAfter(mainFallback,
                coordinator.name + "$GlobalPhase", "fail", mainBody);
        int mainFailureFinish = callIndexAfter(mainFallback,
                internal(RegionalPlayerEntityTicksV2.class), "finishContext",
                mainPublishFailure);
        assertTrue(mainBeginLeases >= 0 && mainInstallContext > mainBeginLeases
                        && mainFatalCheck > mainInstallContext && mainBody > mainFatalCheck,
                "physical fallback must bind RUNNING and recheck fatal state before body");
        assertEquals(0, callCount(mainFallback, "java/lang/Runnable", "run"),
                "physical Main fallback must not use a check-then-raw-body entry");
        assertEquals(2, mainFatalChecks.size(),
                "physical fallback must recheck the fatal slot after its one accepted body");
        assertTrue(mainFatalChecks.getLast() > mainBody,
                "a failure published during fallback may not be hidden by normal return");
        assertTrue(mainPublishFailure > mainBody
                        && mainFailureFinish > mainPublishFailure,
                "a fallback Throwable must win the global slot before release cleanup");
        assertEquals(3, mainFinishes.size(),
                "main fallback needs normal, caught-body-failure, and catch-all cleanup copies");
        assertEquals(mainFinishes.size(), mainTerminals.size());
        for (int i = 0; i < mainTerminals.size(); i++) {
            assertTrue(mainFinishes.get(i) < mainTerminals.get(i),
                    "main fallback exit " + i + " may not terminalize before context cleanup");
        }

        MethodNode finishContext = method(coordinator, "finishContext",
                "(Lcom/axalotl/fabrolia/common/regions/RegionalPlayerEntityTicksV2$TickContext;"
                        + "ZLjava/lang/Throwable;)Ljava/lang/Throwable;");
        int installedFinish = callIndex(finishContext,
                internal(RegionalPlayerEntityTicksV2.class), "finishAndClearContext");
        int uninstalledFinish = callIndex(finishContext, tickContext, "finish");
        assertTrue(coveredTogether(finishContext, "java/lang/Throwable",
                        installedFinish, uninstalledFinish),
                "installed and pre-install context cleanup failures must both be merged");

        MethodNode completeAndRetire = method(coordinator, "completeAndRetire",
                "(Lcom/axalotl/fabrolia/common/regions/RegionalPlayerEntityTicksV2$TickContext;"
                        + "Ljava/lang/Throwable;)Ljava/lang/Throwable;");
        int offer = callIndexByName(completeAndRetire, "offerCompletion");
        int cut = callIndex(completeAndRetire,
                internal(RegionScheduler.class), "commitSuccessIfHealthy");
        int consume = callIndexByName(completeAndRetire,
                "consumeAcceptedCompletionAndRetire");
        int poison = callIndexByName(completeAndRetire,
                "poisonCompletionAndRetire");
        int signal = callIndexByName(completeAndRetire, "signalCompletion");
        assertTrue(offer >= 0 && cut > offer && consume > cut
                        && poison > consume && signal > poison,
                "receipt offer must precede the fixed healthy cut; terminal consume/retirement/"
                        + "waiter notification must all happen after that cut");
        assertTrue(coversAny(completeAndRetire, consume, "java/lang/Throwable")
                        && coversAny(completeAndRetire, poison, "java/lang/Throwable")
                        && coversAny(completeAndRetire, signal, "java/lang/Throwable"),
                "terminal consume, retirement, and notification failures must all select W");
        assertEquals(0, callCount(completeAndRetire,
                        internal(PlayerTickPhase.class), "complete"),
                "the normal V2 terminal may not notify from inside the scheduler cut");

        Class<?> completionReceiptType = Class.forName(
                PlayerTickPhase.class.getName() + "$CompletionReceipt");
        ClassNode completionReceipt = readClass(completionReceiptType);
        FieldNode receiptState = completionReceipt.fields.stream()
                .filter(field -> field.name.equals("successReceipt") && field.desc.equals("I"))
                .findFirst().orElseThrow();
        assertTrue((receiptState.access & Opcodes.ACC_VOLATILE) != 0,
                "the lifecycle cut receipt must publish by one volatile fixed write");
        MethodNode acceptSuccess = method(completionReceipt, "acceptSuccess", "()V");
        assertEquals(1, fieldAccessCount(acceptSuccess, Opcodes.PUTFIELD,
                completionReceipt.name, "successReceipt"));
        assertEquals(0, methodCallCount(acceptSuccess),
                "receipt acceptance may not call validation, monitors, or callbacks");
        assertEquals(0, opcodeCount(acceptSuccess, Opcodes.MONITORENTER));

        Class<?> levelBatchType = Class.forName(
                RegionalPlayerEntityTicksV2.class.getName() + "$LevelBatch");
        ClassNode levelBatch = readClass(levelBatchType);
        String receiptDescriptor = "L" + internal(completionReceiptType) + ";";
        MethodNode consumeReceipt = method(levelBatch, "consumeAcceptedCompletionAndRetire",
                "(" + receiptDescriptor + ")Ljava/lang/Throwable;");
        MethodNode poisonReceipt = method(levelBatch, "poisonCompletionAndRetire",
                "(" + receiptDescriptor + "Ljava/lang/Throwable;)Ljava/lang/Throwable;");
        assertTrue((consumeReceipt.access & Opcodes.ACC_SYNCHRONIZED) != 0
                        && (poisonReceipt.access & Opcodes.ACC_SYNCHRONIZED) != 0,
                "completion consumption must establish LevelBatch -> ledger lock order");
        assertTrue(calls(consumeReceipt, internal(PlayerTickPhase.class),
                        "consumeAcceptedCompletion")
                        && calls(poisonReceipt, internal(PlayerTickPhase.class),
                        "poisonCompletion"));
        int consumeLedger = callIndex(consumeReceipt, internal(PlayerTickPhase.class),
                "consumeAcceptedCompletion");
        int consumeRetire = callIndexByName(consumeReceipt, "retireCandidate");
        int poisonLedger = callIndex(poisonReceipt, internal(PlayerTickPhase.class),
                "poisonCompletion");
        int poisonRetire = callIndexByName(poisonReceipt, "retireCandidate");
        assertTrue(consumeLedger >= 0 && consumeRetire > consumeLedger
                        && poisonLedger >= 0 && poisonRetire > poisonLedger,
                "each success/failure terminal must consume ledger then retire while "
                        + "LevelBatch remains the outer monitor");
        MethodNode signalReceipt = method(levelBatch, "signalCompletion",
                "(" + receiptDescriptor + ")V");
        assertEquals(0, signalReceipt.access & Opcodes.ACC_SYNCHRONIZED,
                "waiter notification must not reacquire LevelBatch after ledger consumption");
        assertTrue(calls(signalReceipt, internal(PlayerTickPhase.class), "signalCompletion"));

        MethodNode finishLevel = method(coordinator, "finishLevel",
                "(Lcom/axalotl/fabrolia/common/regions/RegionizedServer;)V");
        int barrier = callIndex(finishLevel, internal(PlayerTickPhase.class), "sealAndAwait");
        int globalWinner = callIndexAfter(finishLevel,
                levelBatch.name, "firstFailure", barrier);
        int verify = callIndex(finishLevel, internal(PlayerTickPhase.class), "verifyTerminal");
        assertTrue(barrier >= 0 && globalWinner > barrier && verify > globalWinner,
                "the level waiter must observe global W before any successor verification");

        MethodNode mergeFailure = method(coordinator, "mergeFailure",
                "(Ljava/lang/Throwable;Ljava/lang/Throwable;)Ljava/lang/Throwable;");
        int readSuppressed = callIndex(mergeFailure, "java/lang/Throwable", "getSuppressed");
        int addSuppressed = callIndex(mergeFailure, "java/lang/Throwable", "addSuppressed");
        assertTrue(readSuppressed >= 0 && addSuppressed > readSuppressed
                        && coveredTogether(mergeFailure, "java/lang/Throwable",
                        readSuppressed, addSuppressed),
                "diagnostic suppression must never abort later actor cleanup");

        MethodNode finishAndClear = method(coordinator, "finishAndClearContext",
                "(Lcom/axalotl/fabrolia/common/regions/RegionalPlayerEntityTicksV2$TickContext;"
                        + "Ljava/lang/Throwable;)Ljava/lang/Throwable;");
        int contextFinish = callIndex(finishAndClear, tickContext, "finish");
        int installedContext = callIndex(finishAndClear, "java/lang/ThreadLocal", "get");
        int mergeMismatch = callIndex(finishAndClear,
                internal(RegionalPlayerEntityTicksV2.class), "mergeFailure");
        int recordMismatch = callIndexAfter(finishAndClear,
                coordinator.name + "$LevelBatch", "fail", mergeMismatch);
        int removeOwn = callIndex(finishAndClear, "java/lang/ThreadLocal", "remove");
        JumpInsnNode ownContextBranch = firstJumpBetween(finishAndClear, Opcodes.IF_ACMPEQ,
                installedContext, mergeMismatch);
        int mismatchReturn = opcodeIndexAfter(finishAndClear, Opcodes.ARETURN, recordMismatch);
        assertNotNull(ownContextBranch,
                "identity match must branch around the sticky foreign-context failure path");
        int ownContextTarget = instructionIndex(finishAndClear, ownContextBranch.label);
        assertTrue(contextFinish >= 0 && installedContext > contextFinish
                        && mergeMismatch > installedContext && recordMismatch > mergeMismatch
                        && mismatchReturn > recordMismatch && ownContextTarget > mismatchReturn
                        && removeOwn >= ownContextTarget,
                "foreign replacement must merge diagnostics, select W, and return W without "
                        + "clobbering it; own context removes");
        assertEquals(1, callCount(finishAndClear, "java/lang/ThreadLocal", "remove"));

        MethodNode retireCandidate = method(readClass(levelBatchType), "retireCandidate",
                "(Lcom/axalotl/fabrolia/common/regions/PlayerTickPhase$Claim;)V");
        int liveReservation = callIndex(retireCandidate, internal(EntityTickLease.class),
                "isReservedBy");
        int firstTombstoneRemove = callIndex(retireCandidate,
                "java/util/IdentityHashMap", "remove");
        assertTrue(liveReservation >= 0 && firstTombstoneRemove > liveReservation,
                "candidate/tree identity may retire only after its exact lease is no longer live");
        assertTrue(calls(method(coordinator, "finishPlayerEntityBody",
                        "(Lnet/minecraft/world/entity/Entity;)V"),
                "java/lang/ThreadLocal", "remove"),
                "the direct-consumer scope must close instead of stamping the whole wall phase");

        ClassNode regionizer = readClass(RegionizedServer.class);
        FieldNode queueOnlyEpoch = regionizer.fields.stream()
                .filter(field -> field.name.equals("queueOnlyEntityEpoch")
                        && field.desc.equals("J"))
                .findFirst().orElseThrow();
        assertTrue((queueOnlyEpoch.access & Opcodes.ACC_VOLATILE) != 0);

        MethodNode preEntity = method(regionizer, "dispatchPreEntityWork",
                "()Ljava/util/concurrent/CompletableFuture;");
        int falseFlag = opcodeIndex(preEntity, Opcodes.ICONST_0);
        int queueOnlyDispatch = callIndex(preEntity, internal(RegionizedServer.class),
                "dispatchOwnedEntityTicks");
        assertTrue(falseFlag >= 0 && queueOnlyDispatch > falseFlag,
                "pre-entity dispatch must pass tickEntities=false to the production path");

        MethodNode dispatch = method(regionizer, "dispatchEntityPhase",
                "(Lcom/axalotl/fabrolia/common/regions/EpochDomain;Ljava/util/List;Z)"
                        + "Ljava/util/concurrent/CompletableFuture;");
        assertEquals(1, callCount(dispatch, internal(RegionScheduler.class),
                "commitSuccessIfHealthy"),
                "queue-only and grouped epochs must publish in the shared success-vs-W cut");
        MethodNode dispatchCommit = regionizer.methods.stream()
                .filter(candidate -> fieldAccessCount(candidate, Opcodes.PUTFIELD,
                        internal(RegionizedServer.class), "queueOnlyEntityEpoch") > 0)
                .filter(candidate -> fieldAccessCount(candidate, Opcodes.PUTFIELD,
                        internal(Region.class), "groupedEpoch") > 0)
                .findFirst().orElseThrow();
        int queueOnlyStore = fieldIndex(dispatchCommit, Opcodes.PUTFIELD,
                internal(RegionizedServer.class), "queueOnlyEntityEpoch");
        int groupedStore = fieldIndex(dispatchCommit, Opcodes.PUTFIELD,
                internal(Region.class), "groupedEpoch");
        assertTrue(queueOnlyStore >= 0 && groupedStore > queueOnlyStore,
                "queue-only epoch must publish before any worker-visible grouped epoch");

        MethodNode owes = method(regionizer, "owesEntityPhase",
                "(Lcom/axalotl/fabrolia/common/regions/Region;)Z");
        int queueOnlyRead = callIndex(owes,
                internal(RegionizedServer.class), "isQueueOnlyEntityEpoch");
        int suppressedReturn = opcodeIndexAfter(owes, Opcodes.IRETURN, queueOnlyRead);
        int groupedRead = fieldIndex(owes, Opcodes.GETFIELD,
                internal(Region.class), "groupedEpoch");
        int entityEpoch = fieldIndex(owes, Opcodes.GETFIELD,
                internal(Region.class), "entityEpoch");
        assertTrue(queueOnlyRead >= 0
                        && suppressedReturn > queueOnlyRead && groupedRead > suppressedReturn
                        && entityEpoch > groupedRead,
                "matching domain queue-only epoch must return false before the owed-phase test");
    }


    @Test
    void globalBarrierIsImmediatelyBeforeConnectionPhase() throws Exception {
        MethodNode tickChildren = method(readClass(MinecraftServer.class), "tickChildren",
                "(Ljava/util/function/BooleanSupplier;)V");
        int lastLevel = lastCallIndex(tickChildren, "net/minecraft/server/level/ServerLevel", "tick");
        int connection = callIndex(tickChildren, "net/minecraft/server/MinecraftServer", "tickConnection");
        assertTrue(lastLevel >= 0 && connection > lastLevel);

        ClassNode mixin = readClass(MinecraftServerMixin.class);
        assertTrue(calls(mixin, internal(RegionalPlayerEntityTicksV2.class), "beginServerTick"));
        assertTrue(calls(mixin, internal(RegionalPlayerEntityTicksV2.class), "beforeConnections"));
        assertTrue(calls(mixin, internal(RegionalPlayerEntityTicksV2.class), "finishServerTick"));
        assertTrue(calls(mixin, internal(RegionalPlayerEntityTicksV2.class), "abortServerTick"));
    }

    @Test
    void everyRawTickChildrenStageHasAnAtomicEntryCut() throws Exception {
        MethodNode vanilla = method(readClass(MinecraftServer.class), "tickChildren",
                "(Ljava/util/function/BooleanSupplier;)V");
        String server = "net/minecraft/server/MinecraftServer";
        int suspend = callIndex(vanilla, "java/util/List", "forEach");
        int functions = callIndex(vanilla,
                "net/minecraft/server/ServerFunctionManager", "tick");
        int clock = callIndex(vanilla,
                "net/minecraft/world/clock/ServerClockManager", "tick");
        int timeSync = callIndex(vanilla, server, "forceGameTimeSynchronization");
        int respawn = callIndex(vanilla, server, "updateEffectiveRespawnData");
        int level = callIndex(vanilla, "net/minecraft/server/level/ServerLevel", "tick");
        int connection = callIndex(vanilla, server, "tickConnection");
        int players = callIndex(vanilla, "net/minecraft/server/players/PlayerList", "tick");
        int debug = callIndex(vanilla,
                "net/minecraft/util/debug/ServerDebugSubscribers", "tick");
        int gameTests = callIndex(vanilla,
                "net/minecraft/gametest/framework/GameTestTicker", "tick");
        int tickable = callIndex(vanilla, "java/lang/Runnable", "run");
        int send = callIndex(vanilla,
                "net/minecraft/server/network/PlayerChunkSender", "sendNextChunks");
        int resume = callIndex(vanilla,
                "net/minecraft/server/network/ServerGamePacketListenerImpl",
                "resumeFlushing");
        int activity = callIndex(vanilla,
                "net/minecraft/server/notifications/ServerActivityMonitor", "tick");
        assertTrue(suspend >= 0 && functions > suspend && clock > functions
                        && timeSync > clock && respawn > timeSync && level > respawn
                        && connection > level && players > connection && debug > players
                        && gameTests > debug && tickable > gameTests && send > tickable
                        && resume > send && activity > resume,
                "the 26.2 top-level tickChildren stage shape changed");

        ClassNode mixin = readClass(MinecraftServerMixin.class);
        String operation = "Lcom/llamalad7/mixinextras/injector/"
                + "wrapoperation/Operation;";
        MethodNode suspendHandler = method(mixin, "fabrolia$admitSuspendFlushing",
                "(Ljava/util/List;Ljava/util/function/Consumer;" + operation + ")V");
        AnnotationNode suspendWrap = annotation(suspendHandler,
                "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");
        assertNotNull(suspendWrap);
        assertMethodValue(suspendWrap,
                "tickChildren(Ljava/util/function/BooleanSupplier;)V");
        assertEquals(1, annotationValue(suspendWrap, "require"));
        assertEquals(1, annotationValue(suspendWrap, "allow"));
        assertAt(suspendWrap, "INVOKE",
                "Ljava/util/List;forEach(Ljava/util/function/Consumer;)V");
        assertEquals(1, callCount(suspendHandler, internal(RegionScheduler.class),
                        "runSuccessBodyIfHealthy"),
                "the whole list must catch empty-list and pre-loop W");
        assertEquals(1, mixin.methods.stream()
                        .filter(candidate -> candidate.name.startsWith(
                                "lambda$fabrolia$admitSuspendFlushing$"))
                        .mapToInt(candidate -> callCount(candidate,
                                "com/llamalad7/mixinextras/injector/wrapoperation/Operation",
                                "call"))
                        .sum(),
                "the canonical List.forEach must run once with the admitted consumer");
        assertEquals(1, mixin.methods.stream()
                        .filter(candidate -> candidate.name.startsWith(
                                "lambda$fabrolia$admitSuspendFlushing$"))
                        .mapToInt(candidate -> callCount(candidate,
                                internal(RegionScheduler.class),
                                "runSuccessBodyIfHealthy"))
                        .sum(),
                "each player's suspendFlushing body must get its own admission");
        assertEquals(1, mixin.methods.stream()
                        .filter(candidate -> candidate.name.startsWith(
                                "lambda$fabrolia$admitSuspendFlushing$"))
                        .mapToInt(candidate -> callCount(candidate,
                                "java/util/function/Consumer", "accept"))
                        .sum(),
                "the admitted per-player body must invoke the original consumer once");
        assertAtomicTickChildrenStage(mixin, "fabrolia$admitClockTick",
                "(Lnet/minecraft/world/clock/ServerClockManager;" + operation + ")V",
                "Lnet/minecraft/world/clock/ServerClockManager;tick()V");
        assertAtomicTickChildrenStage(mixin, "fabrolia$admitGameTimeSynchronization",
                "(Lnet/minecraft/server/MinecraftServer;" + operation + ")V",
                "Lnet/minecraft/server/MinecraftServer;forceGameTimeSynchronization()V");
        assertAtomicTickChildrenStage(mixin, "fabrolia$admitRespawnDataUpdate",
                "(Lnet/minecraft/server/MinecraftServer;" + operation + ")V",
                "Lnet/minecraft/server/MinecraftServer;updateEffectiveRespawnData()V");
        assertAtomicTickChildrenStage(mixin, "fabrolia$admitPlayerListTick",
                "(Lnet/minecraft/server/players/PlayerList;" + operation + ")V",
                "Lnet/minecraft/server/players/PlayerList;tick()V");
        assertAtomicTickChildrenStage(mixin, "fabrolia$admitDebugSubscribersTick",
                "(Lnet/minecraft/util/debug/ServerDebugSubscribers;" + operation + ")V",
                "Lnet/minecraft/util/debug/ServerDebugSubscribers;tick()V");
        assertAtomicTickChildrenStage(mixin, "fabrolia$admitGameTestTick",
                "(Lnet/minecraft/gametest/framework/GameTestTicker;" + operation + ")V",
                "Lnet/minecraft/gametest/framework/GameTestTicker;tick()V");
        assertAtomicTickChildrenStage(mixin, "fabrolia$admitTickable",
                "(Ljava/lang/Runnable;" + operation + ")V",
                "Ljava/lang/Runnable;run()V");
        assertAtomicTickChildrenStage(mixin, "fabrolia$admitSendNextChunks",
                "(Lnet/minecraft/server/network/PlayerChunkSender;"
                        + "Lnet/minecraft/server/level/ServerPlayer;" + operation + ")V",
                "Lnet/minecraft/server/network/PlayerChunkSender;"
                        + "sendNextChunks(Lnet/minecraft/server/level/ServerPlayer;)V");
        assertAtomicTickChildrenStage(mixin, "fabrolia$admitResumeFlushing",
                "(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;"
                        + operation + ")V",
                "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;"
                        + "resumeFlushing()V");
        assertAtomicTickChildrenStage(mixin, "fabrolia$admitActivityMonitorTick",
                "(Lnet/minecraft/server/notifications/ServerActivityMonitor;"
                        + operation + ")V",
                "Lnet/minecraft/server/notifications/ServerActivityMonitor;tick()V");

        ClassNode functionMixin = readClass(ServerFunctionManagerMixin.class);
        MethodNode functionGate = method(functionMixin, "fabrolia$markTickTag",
                "(Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)V");
        assertEquals(2, callCount(functionGate, internal(RegionScheduler.class),
                        "runSuccessBodyIfHealthy"),
                "ServerFunctionManager.tick must separately admit its advancement drain "
                        + "and exact vanilla function body");
        int markerReset = lastFieldIndex(functionGate, Opcodes.PUTFIELD,
                internal(ServerFunctionManagerMixin.class), "fabrolia$inTickTag");
        int cleanupCommit = callIndex(functionGate, internal(RegionScheduler.class),
                "commitSuccessIfHealthy");
        int exactRethrow = callIndex(functionGate,
                internal(ServerFunctionManagerMixin.class), "fabrolia$rethrow");
        assertEquals(1, callCount(functionGate, internal(RegionScheduler.class),
                "commitSuccessIfHealthy"));
        assertTrue(markerReset >= 0 && cleanupCommit > markerReset
                        && exactRethrow > cleanupCommit,
                "function tick must refuse normal return when W wins during marker cleanup");
        assertEquals(1, functionMixin.methods.stream()
                        .filter(candidate -> candidate.name.startsWith(
                                "lambda$fabrolia$markTickTag$"))
                        .mapToInt(candidate -> callCount(candidate,
                                "com/axalotl/fabrolia/common/parallelised/advancement/"
                                        + "AdvancementBuffer",
                                "beginTickHeadWindow"))
                        .sum(),
                "the pre-function advancement window must itself be admitted");
        assertEquals(1, functionMixin.methods.stream()
                        .filter(candidate -> candidate.name.startsWith(
                                "lambda$fabrolia$markTickTag$"))
                        .mapToInt(candidate -> callCount(candidate,
                                "com/llamalad7/mixinextras/injector/wrapoperation/Operation",
                                "call"))
                        .sum(),
                "the function tick gate must contain its exact original body once");
    }



    @Test
    void logicalActorPublicationAloneNeverAuthorizesExactExecution() throws Exception {
        ClassNode scheduler = readClass(RegionScheduler.class);
        MethodNode ownsExact = method(scheduler, "ownsExactExecutionTurn", "()Z");
        assertTrue(calls(ownsExact, internal(RegionalPlayerEntityTicksV2.class),
                "inExactPlayerActor"), "the authenticated actor remains a legal second executor");
        assertTrue(calls(ownsExact, internal(RegionWorkerPool.class),
                "ownsPausedExactTurn"), "physical main authority must come from the pool fence");

        ClassNode coordinator = readClass(RegionalPlayerEntityTicksV2.class);
        MethodNode actorCheck = method(coordinator, "inExactPlayerActor", "()Z");
        int turnRead = callIndex(actorCheck,
                "java/util/concurrent/atomic/AtomicReference", "get");
        int actorIdentity = callIndex(actorCheck, "java/lang/Thread", "currentThread");
        int poolFence = callIndex(actorCheck, internal(RegionScheduler.class),
                "isExactPlayerTurnPausedBy");
        assertTrue(turnRead >= 0 && actorIdentity > turnRead && poolFence > actorIdentity,
                "actor identity must also validate its captured physical-main pool pause");
        MethodNode logicalServer = method(coordinator, "logicalServerThread",
                "(Lnet/minecraft/server/MinecraftServer;)Ljava/lang/Thread;");
        assertTrue(callIndex(logicalServer, internal(RegionScheduler.class),
                "isExactPlayerTurnPausedBy") >= 0,
                "logical server ownership must expire with the captured pool pause");

        var poolField = RegionScheduler.class.getDeclaredField("pool");
        var logicalTurnField = RegionalPlayerEntityTicksV2.class
                .getDeclaredField("LOGICAL_TURN");
        poolField.setAccessible(true);
        logicalTurnField.setAccessible(true);
        @SuppressWarnings("unchecked")
        AtomicReference<Object> logicalTurn =
                (AtomicReference<Object>) logicalTurnField.get(null);
        Object previousPool = poolField.get(null);
        Object previousTurn = logicalTurn.get();

        Class<?> logicalTurnType = Class.forName(
                RegionalPlayerEntityTicksV2.class.getName() + "$LogicalTurn");
        var constructor = logicalTurnType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        Object[] arguments = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> parameter = parameterTypes[i];
            if (parameter == Thread.class) arguments[i] = Thread.currentThread();
            else if (parameter == long.class) arguments[i] = 1L;
            else if (parameter == int.class) arguments[i] = 0;
            else if (parameter == boolean.class) arguments[i] = false;
            else arguments[i] = null;
        }
        Object staleLogicalTurn = constructor.newInstance(arguments);

        var ownsExactExecutionTurn = RegionScheduler.class
                .getDeclaredMethod("ownsExactExecutionTurn");
        ownsExactExecutionTurn.setAccessible(true);
        try {
            poolField.set(null, null);
            logicalTurn.set(staleLogicalTurn);
            assertFalse(RegionalPlayerEntityTicksV2.inExactPlayerActor(),
                    "a logical actor stamp must not survive without its pool fence");
            assertEquals(Boolean.FALSE, ownsExactExecutionTurn.invoke(null),
                    "a logical-turn stamp without its captured live pool pause is stale/unauthorized");
        } finally {
            logicalTurn.set(previousTurn);
            poolField.set(null, previousPool);
        }
    }

    @Test
    void contextualFlushMixinAndEligibilityAccessorArePackaged() throws Exception {
        ClassNode packetProcessorMixin = readClass(PacketProcessorPlayerTickMixin.class);
        assertMixinTarget(packetProcessorMixin, "Lnet/minecraft/network/PacketProcessor;");
        MethodNode packetThread = method(packetProcessorMixin,
                "fabrolia$logicalPlayerActorThread", "(Z)Z");
        AnnotationNode packetModify = annotation(packetThread,
                "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;");
        assertNotNull(packetModify);
        assertMethodValue(packetModify, "isSameThread()Z");
        assertEquals(1, annotationValue(packetModify, "require"));
        assertAtValue(packetModify, "RETURN");
        assertTrue(calls(packetThread, internal(RegionalPlayerEntityTicksV2.class),
                "inExactPlayerActor"));
        MethodNode vanillaPacketThread = method(readClass(PacketProcessor.class),
                "isSameThread", "()Z");
        assertEquals(1, opcodeCount(vanillaPacketThread, Opcodes.IRETURN),
                "exact 26.2 PacketProcessor.isSameThread must keep one RETURN target");

        ClassNode flush = readClass(ServerCommonPacketListenerPlayerTickFlushMixin.class);
        assertTrue(calls(flush, internal(RegionalPlayerEntityTicksV2.class), "inWorkerPlayerTick"));
        assertFalse(calls(flush, internal(AsyncIslandPlayerTicks.class), "inOwnerTurn"),
                "autonomous A5 sends must retain vanilla off-main flush=true");
        assertFalse(calls(flush,
                "com/llamalad7/mixinextras/injector/wrapoperation/Operation", "call"));
        assertFalse(hasOpcode(flush, Opcodes.ANEWARRAY),
                "disabled packet send path must not allocate WrapOperation varargs");
        MethodNode flushHandler = method(flush, "fabrolia$preserveSuspendedFlush", "(Z)Z");
        AnnotationNode modify = annotation(flushHandler,
                "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;");
        assertNotNull(modify);
        assertMethodValue(modify,
                "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V");
        assertEquals(1, annotationValue(modify, "require"));
        assertAt(modify, "INVOKE", "Lnet/minecraft/server/MinecraftServer;isSameThread()Z");
        assertNotNull(readClass(ServerPlayerGameModeAccessor.class));

        ClassNode profilerAccessor = readClass(ProfilerAccessor.class);
        assertMixinTarget(profilerAccessor, "Lnet/minecraft/util/profiling/Profiler;");
        MethodNode activeProfiler = method(profilerAccessor, "fabrolia$activeProfiler",
                "()Ljava/lang/ThreadLocal;");
        AnnotationNode accessor = annotation(activeProfiler,
                "Lorg/spongepowered/asm/mixin/gen/Accessor;");
        assertNotNull(accessor);
        assertEquals("ACTIVE", annotationValue(accessor, "value"));
        assertTrue((activeProfiler.access & Opcodes.ACC_STATIC) != 0);
        FieldNode vanillaActive = readClass(net.minecraft.util.profiling.Profiler.class).fields
                .stream().filter(field -> field.name.equals("ACTIVE")
                        && field.desc.equals("Ljava/lang/ThreadLocal;"))
                .findFirst().orElseThrow();
        assertTrue((vanillaActive.access & Opcodes.ACC_PRIVATE) != 0);
        assertTrue((vanillaActive.access & Opcodes.ACC_STATIC) != 0);
        assertTrue((vanillaActive.access & Opcodes.ACC_FINAL) != 0);
        assertEquals("Ljava/lang/ThreadLocal<Lnet/minecraft/util/profiling/ProfilerFiller;>;",
                vanillaActive.signature);

        MethodNode send = method(readClass(ServerCommonPacketListenerImpl.class), "send",
                "(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V");
        assertEquals(1, callCount(send, "net/minecraft/server/MinecraftServer", "isSameThread"));

        try (InputStream in = PlayerTickV2WiringTest.class.getClassLoader()
                .getResourceAsStream("fabrolia.common.mixins.json")) {
            assertNotNull(in);
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("network.PacketProcessorPlayerTickMixin"));
            assertTrue(json.contains("network.ServerCommonPacketListenerPlayerTickFlushMixin"));
            assertTrue(json.contains("server.ServerChunkCacheMainThreadExecutorMixin"));
            assertTrue(json.contains("server.ServerPlayerGameModeAccessor"));
            assertTrue(json.contains("utils.ProfilerAccessor"));
            assertFalse(json.contains("ServerGamePacketRoutingMixin"));
            assertFalse(json.contains("PacketUtilsWorkerMixin"));
        }
    }

    @Test
    void fabroliaStillAddsNoDirectDoTickCaller() throws Exception {
        for (Class<?> type : List.of(RegionalPlayerEntityTicksV2.class, ParallelProcessor.class,
                ServerPlayerMixin.class, ServerLevelMixin.class, MinecraftServerMixin.class)) {
            assertFalse(calls(readClass(type), "net/minecraft/server/level/ServerPlayer", "doTick"),
                    "direct worker doTick returned in " + type.getName());
        }
    }


    @Test
    void schedulerLifecycleNeverHoldsASynchronizedMethodAcrossWorkerJoin() throws Exception {
        ClassNode coordinator = readClass(RegionalPlayerEntityTicksV2.class);
        MethodNode lifecycleGuard = method(coordinator, "schedulerLifecycleTransitionAllowed",
                "(Lnet/minecraft/server/MinecraftServer;)Z");
        int physical = callIndex(lifecycleGuard,
                internal(RegionalPlayerEntityTicksV2.class), "isPhysicalServerThread");
        int closedPhase = callIndex(lifecycleGuard,
                "java/util/concurrent/atomic/AtomicReference", "get");
        assertTrue(physical >= 0 && closedPhase > physical,
                "scheduler transitions require both physical main and no open wall phase");

        ClassNode scheduler = readClass(RegionScheduler.class);
        MethodNode start = method(scheduler, "start",
                "(Lnet/minecraft/server/MinecraftServer;)V");
        MethodNode stop = method(scheduler, "stop", "()V");
        assertEquals(0, start.access & Opcodes.ACC_SYNCHRONIZED);
        assertEquals(0, stop.access & Opcodes.ACC_SYNCHRONIZED);
        int startPreflight = callIndex(start, internal(RegionalPlayerEntityTicksV2.class),
                "schedulerLifecycleTransitionAllowed");
        int safetyMutation = callIndex(start, internal(RegionScheduler.class),
                "callSuccessBooleanIfHealthy");
        int startLifecycleMutation = fieldIndex(start, Opcodes.PUTSTATIC,
                internal(RegionScheduler.class), "lifecycleState");
        assertTrue(startPreflight >= 0 && safetyMutation > startPreflight
                        && startLifecycleMutation > startPreflight,
                "start must preflight before the atomically admitted config normalization "
                        + "or lifecycle mutation");

        List<Integer> stopPreflights = callIndices(stop,
                internal(RegionalPlayerEntityTicksV2.class),
                "schedulerLifecycleTransitionAllowed");
        int stopLifecycleMutation = fieldIndex(stop, Opcodes.PUTSTATIC,
                internal(RegionScheduler.class), "lifecycleState");
        assertEquals(2, stopPreflights.size(),
                "stop must preflight both the observed and authoritative server generation");
        assertTrue(stopLifecycleMutation > stopPreflights.getLast(),
                "stop must revalidate inside the lifecycle lock before mutation");

        MethodNode masterCommand = method(readClass(RegionCommand.class), "setEnabled",
                "(Lnet/minecraft/commands/CommandSourceStack;Z)V");
        int commandPhysical = callIndex(masterCommand,
                internal(RegionalPlayerEntityTicksV2.class), "isPhysicalServerThread");
        int commandPreflight = callIndex(masterCommand,
                internal(RegionalPlayerEntityTicksV2.class),
                "schedulerLifecycleTransitionAllowed");
        int commandMutation = fieldIndex(masterCommand, Opcodes.PUTSTATIC,
                internal(FabroliaConfig.class), "enableRegionThreading");
        int configSave = callIndex(masterCommand,
                "com/axalotl/fabrolia/common/platform/PlatformUtils", "saveConfig");
        assertTrue(commandPhysical >= 0 && commandPreflight > commandPhysical
                        && commandMutation > commandPreflight && configSave > commandMutation,
                "master command must reject an open phase before mutation and persistence");

        MethodNode outside = method(scheduler, "stopOutsideLifecycleLock", "()V");
        assertTrue(calls(outside, internal(RegionWorkerPool.class), "stop"));
        assertTrue(calls(outside, internal(RegionWorkerPool.class), "isTerminated"));
    }

    


    @Test
    void regionRetirementAndExternalPublicationShareOneGenerationLockedAdmission() throws Exception {
        ClassNode regionClass = readClass(Region.class);
        FieldNode admission = regionClass.fields.stream()
                .filter(field -> field.name.equals("admissionLock")
                        && field.desc.equals("Ljava/lang/Object;"))
                .findFirst().orElseThrow();
        assertTrue((admission.access & Opcodes.ACC_FINAL) != 0);
        assertEquals(0, admission.access & Opcodes.ACC_STATIC);

        ClassNode pool = readClass(RegionWorkerPool.class);
        assertGenerationLockedPublication(method(pool, "enqueueReady",
                        "(Lcom/axalotl/fabrolia/common/regions/Region;"
                                + "Lcom/axalotl/fabrolia/common/regions/"
                                + "EpochDomain$EntityDispatchPublication;J)Z"),
                internal(RegionWorkerPool.class), "offerReady");
        assertGenerationLockedPublication(method(pool, "enqueueRegionTask",
                        "(Lcom/axalotl/fabrolia/common/regions/Region;Ljava/lang/Runnable;)Z"),
                internal(RegionWorkerPool.class), "reserveExternalTaskLocked");
        assertGenerationLockedPublication(method(pool, "enqueueHelpTask",
                        "(Lcom/axalotl/fabrolia/common/regions/Region;Ljava/lang/Runnable;)Z"),
                internal(RegionWorkerPool.class), "reserveExternalTaskLocked");

        ClassNode scheduler = readClass(RegionScheduler.class);
        MethodNode owns = method(scheduler, "ownsRuntime",
                "(Lcom/axalotl/fabrolia/common/regions/RegionWorkerPool;"
                        + "Lcom/axalotl/fabrolia/common/regions/Region;)Z");
        assertTrue(fieldIndex(owns, Opcodes.GETSTATIC, internal(RegionScheduler.class), "active") >= 0);
        assertTrue(fieldIndex(owns, Opcodes.GETSTATIC, internal(RegionScheduler.class), "pool") >= 0);
        assertTrue(calls(owns, internal(Region.class), "state"));
        assertTrue(calls(owns, internal(RegionizedServer.class), "ownsRegion"));

        ClassNode regionizer = readClass(ThreadedRegionizer.class);
        MethodNode evict = method(regionizer, "evictIdle",
                "(Lcom/axalotl/fabrolia/common/regions/RegionizedServer;"
                        + "Lcom/axalotl/fabrolia/common/regions/EpochDomain;J)I");
        MethodNode drop = method(regionizer, "drop",
                "(Lcom/axalotl/fabrolia/common/regions/Region;)V");
        assertRetiresInsideAdmissionLock(evict);
        assertRetiresInsideAdmissionLock(drop);

        MethodNode evictEntry = method(readClass(RegionizedServer.class), "evictIdleRegions", "()V");
        int async = fieldIndex(evictEntry, Opcodes.GETFIELD,
                internal(RegionizedServer.class), "asyncActive");
        int reclaim = callIndex(evictEntry, internal(ThreadedRegionizer.class), "evictIdle");
        assertTrue(async >= 0 && reclaim > async,
                "async-island mode must fail closed before idle-region reclamation");
    }

    private static void assertGenerationLockedPublication(MethodNode method,
                                                           String publicationOwner,
                                                           String publicationName) {
        int lock = fieldIndex(method, Opcodes.GETFIELD, internal(Region.class), "admissionLock");
        int enter = opcodeIndex(method, Opcodes.MONITORENTER);
        int identity = callIndex(method, internal(RegionScheduler.class), "ownsRuntime");
        int publication = callIndex(method, publicationOwner, publicationName);
        int exit = lastOpcodeIndex(method, Opcodes.MONITOREXIT);
        assertTrue(lock >= 0 && enter > lock && identity > enter
                        && publication > identity && exit > publication,
                method.name + " must validate and publish inside Region.admissionLock");
    }

    private static void assertRetiresInsideAdmissionLock(MethodNode method) {
        int lock = fieldIndex(method, Opcodes.GETFIELD, internal(Region.class), "admissionLock");
        int enter = opcodeIndex(method, Opcodes.MONITORENTER);
        int state = callIndex(method, internal(Region.class), "state");
        if (state < 0) state = callIndex(method, internal(Region.class), "compareAndSetState");
        int remove = callIndexByName(method, "remove");
        int exit = lastOpcodeIndex(method, Opcodes.MONITOREXIT);
        assertTrue(lock >= 0 && enter > lock && state > enter && remove > state && exit > remove,
                method.name + " must check and retire inside Region.admissionLock");
    }


    private static boolean hasField(Class<?> type, String name) {
        for (var f : type.getDeclaredFields()) if (f.getName().equals(name)) return true;
        return false;
    }

    private static String internal(Class<?> type) { return type.getName().replace('.', '/'); }

    private static ClassNode readClass(Class<?> type) throws Exception {
        String resource = "/" + internal(type) + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, 0);
            return node;
        }
    }

    private static void assertAtomicTickChildrenStage(
            ClassNode mixin, String handlerName, String handlerDescriptor,
            String target) {
        MethodNode handler = method(mixin, handlerName, handlerDescriptor);
        AnnotationNode wrap = annotation(handler,
                "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");
        assertNotNull(wrap);
        assertMethodValue(wrap, "tickChildren(Ljava/util/function/BooleanSupplier;)V");
        assertEquals(1, annotationValue(wrap, "require"));
        assertEquals(1, annotationValue(wrap, "allow"));
        assertAt(wrap, "INVOKE", target);
        assertEquals(1, callCount(handler, internal(RegionScheduler.class),
                        "runSuccessBodyIfHealthy"),
                handlerName + " must atomically admit its top-level vanilla body");
        assertEquals(1, mixin.methods.stream()
                        .filter(candidate -> candidate.name.startsWith(
                                "lambda$" + handlerName + "$"))
                        .mapToInt(candidate -> callCount(candidate,
                                "com/llamalad7/mixinextras/injector/wrapoperation/Operation",
                                "call"))
                        .sum(),
                handlerName + " must retain exactly one original operation");
    }


    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc))
                .findFirst().orElseThrow(() -> new AssertionError(node.name + "." + name + desc));
    }

    private static boolean calls(ClassNode node, String owner, String name) {
        return node.methods.stream().anyMatch(m -> calls(m, owner, name));
    }

    private static boolean calls(MethodNode method, String owner, String name) {
        return callIndex(method, owner, name) >= 0;
    }

    private static int callIndex(MethodNode method, String owner, String name) {
        int index = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static int callIndexAfter(MethodNode method, String owner, String name, int after) {
        int index = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (index > after && insn instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static int nextRealOpcode(MethodNode method, int after) {
        for (int index = after + 1; index < method.instructions.size(); index++) {
            int opcode = method.instructions.get(index).getOpcode();
            if (opcode >= 0) return opcode;
        }
        return -1;
    }

    private static List<Integer> callIndices(MethodNode method, String owner, String name) {
        ArrayList<Integer> found = new ArrayList<>();
        int index = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)) {
                found.add(index);
            }
            index++;
        }
        return found;
    }

    private static int lastCallIndex(MethodNode method, String owner, String name) {
        int found = -1;
        int index = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) {
                found = index;
            }
            index++;
        }
        return found;
    }

    private static int fieldIndex(MethodNode method, int opcode, String owner, String name) {
        int index = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode field && field.getOpcode() == opcode
                    && field.owner.equals(owner) && field.name.equals(name)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static int fieldAccessCount(
            MethodNode method, int opcode, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == opcode
                    && field.owner.equals(owner) && field.name.equals(name)) {
                count++;
            }
        }
        return count;
    }

    private static int lastFieldIndex(MethodNode method, int opcode, String owner, String name) {
        int found = -1;
        int index = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode field && field.getOpcode() == opcode
                    && field.owner.equals(owner) && field.name.equals(name)) {
                found = index;
            }
            index++;
        }
        return found;
    }

    private static int opcodeIndex(MethodNode method, int opcode) {
        int index = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == opcode) return index;
            index++;
        }
        return -1;
    }

    private static int opcodeIndexAfter(MethodNode method, int opcode, int after) {
        int index = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (index > after && insn.getOpcode() == opcode) return index;
            index++;
        }
        return -1;
    }

    private static int lastOpcodeIndex(MethodNode method, int opcode) {
        int found = -1;
        int index = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == opcode) found = index;
            index++;
        }
        return found;
    }

    private static int callCount(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) count++;
        }
        return count;
    }

    private static int methodCallCount(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode) count++;
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
    private static int typeInstructionCount(
            MethodNode method, int opcode, String internalName) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode type
                    && type.getOpcode() == opcode
                    && type.desc.equals(internalName)) {
                count++;
            }
        }
        return count;
    }


    private static boolean anyOwnerContains(ClassNode node, String text) {
        for (MethodNode method : node.methods) for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.contains(text)) return true;
        }
        return false;
    }

    private static boolean anyMethodNameStarts(ClassNode node, String prefix) {
        for (MethodNode method : node.methods) for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.name.startsWith(prefix)) return true;
        }
        return false;
    }


    private static boolean covers(MethodNode method, TryCatchBlockNode block, int index) {
        return instructionIndex(method, block.start) <= index
                && index < instructionIndex(method, block.end);
    }

    private static boolean coversAny(MethodNode method, int index, String type) {
        return method.tryCatchBlocks.stream()
                .anyMatch(block -> type.equals(block.type) && covers(method, block, index));
    }

    private static boolean coveredTogether(MethodNode method, String type, int... indices) {
        return method.tryCatchBlocks.stream().anyMatch(block -> type.equals(block.type)
                && java.util.Arrays.stream(indices).allMatch(index -> covers(method, block, index)));
    }

    private static int instructionIndex(MethodNode method, AbstractInsnNode instruction) {
        int index = method.instructions.indexOf(instruction);
        if (index < 0) throw new AssertionError(
                "instruction is not in " + method.name + method.desc);
        return index;
    }

    private static JumpInsnNode firstJumpBetween(MethodNode method, int opcode,
                                                 int after, int before) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (index > after && index < before && instruction instanceof JumpInsnNode jump
                    && jump.getOpcode() == opcode) return jump;
            index++;
        }
        return null;
    }

    private static boolean hasOpcode(ClassNode node, int opcode) {
        for (MethodNode method : node.methods) for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == opcode) return true;
        }
        return false;
    }

    private static int callIndexByName(MethodNode method, String name) {
        int index = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals(name)) return index;
            index++;
        }
        return -1;
    }

    private static AnnotationNode annotation(MethodNode method, String descriptor) {
        if (method.visibleAnnotations != null) {
            for (AnnotationNode annotation : method.visibleAnnotations) {
                if (descriptor.equals(annotation.desc)) return annotation;
            }
        }
        if (method.invisibleAnnotations != null) {
            for (AnnotationNode annotation : method.invisibleAnnotations) {
                if (descriptor.equals(annotation.desc)) return annotation;
            }
        }
        return null;
    }

    private static AnnotationNode annotation(ClassNode type, String descriptor) {
        if (type.visibleAnnotations != null) {
            for (AnnotationNode annotation : type.visibleAnnotations) {
                if (descriptor.equals(annotation.desc)) return annotation;
            }
        }
        if (type.invisibleAnnotations != null) {
            for (AnnotationNode annotation : type.invisibleAnnotations) {
                if (descriptor.equals(annotation.desc)) return annotation;
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

    private static void assertAt(AnnotationNode injection, String value, String target) {
        AnnotationNode at = atAnnotation(injection);
        assertEquals(value, annotationValue(at, "value"));
        assertEquals(target, annotationValue(at, "target"));
    }


    private static void assertAtValue(AnnotationNode injection, String value) {
        assertEquals(value, annotationValue(atAnnotation(injection), "value"));
    }

    private static AnnotationNode atAnnotation(AnnotationNode injection) {
        Object raw = annotationValue(injection, "at");
        if (raw instanceof AnnotationNode node) return node;
        if (raw instanceof List<?> list && !list.isEmpty()) return (AnnotationNode) list.getFirst();
        throw new AssertionError("missing @At on " + injection.desc);
    }

    private static void assertMethodValue(AnnotationNode annotation, String expected) {
        Object raw = annotationValue(annotation, "method");
        if (raw instanceof List<?> list) assertEquals(List.of(expected), list);
        else assertEquals(expected, raw);
    }

    private static void assertMixinTarget(ClassNode mixin, String descriptor) {
        AnnotationNode annotation = annotation(mixin,
                "Lorg/spongepowered/asm/mixin/Mixin;");
        assertNotNull(annotation);
        assertEquals(List.of(Type.getType(descriptor)), annotationValue(annotation, "value"));
    }
}
