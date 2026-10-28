package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.commands.RegionCommand;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.axalotl.fabrolia.common.mixin.commands.ServerFunctionManagerMixin;
import com.axalotl.fabrolia.common.mixin.network.PacketProcessorPlayerTickMixin;
import com.axalotl.fabrolia.common.mixin.network.ServerCommonPacketListenerPlayerTickFlushMixin;
import com.axalotl.fabrolia.common.mixin.server.MinecraftServerMixin;
import com.axalotl.fabrolia.common.mixin.server.ServerPlayerMixin;
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
        assertFalse(calls(coordinator, internal(ParallelProcessor.class), "tickEntityVanillaGuarded"),
                "the removed post-fan-in player lane must not remain callable");
        assertFalse(calls(coordinator, "net/minecraft/server/level/ServerPlayer", "tick"));
        assertFalse(calls(coordinator, "net/minecraft/server/level/ServerPlayer", "doTick"));
        assertFalse(anyOwnerContains(coordinator, "PacketUtils"));
        assertFalse(anyMethodNameStarts(coordinator, "handle"));

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

        ClassNode envelope = readClass(PlayerTickKnownModEnvelope.class);
        MethodNode admission = method(envelope, "idleForAsync",
                "(Lnet/minecraft/server/level/ServerPlayer;L"
                        + internal(IslandPlayerTurnSession.PlayerKind.class) + ";)Z");
        assertEquals(2, callCount(admission,
                        internal(PlayerTickKnownModEnvelope.class) + "$Probe", "idle"),
                "A5 admission must preserve both fail-closed Carpet and FSit probes");
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
    void failureMergeAndQueueOnlyEpochAreWired() throws Exception {
        ClassNode coordinator = readClass(RegionalPlayerEntityTicksV2.class);
        MethodNode mergeFailure = method(coordinator, "mergeFailure",
                "(Ljava/lang/Throwable;Ljava/lang/Throwable;)Ljava/lang/Throwable;");
        int readSuppressed = callIndex(mergeFailure, "java/lang/Throwable", "getSuppressed");
        int addSuppressed = callIndex(mergeFailure, "java/lang/Throwable", "addSuppressed");
        assertTrue(readSuppressed >= 0 && addSuppressed > readSuppressed
                        && coveredTogether(mergeFailure, "java/lang/Throwable",
                        readSuppressed, addSuppressed),
                "diagnostic suppression must never abort later actor cleanup");


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
    void onlyThePoolFenceAuthorizesExactExecution() throws Exception {
        ClassNode scheduler = readClass(RegionScheduler.class);
        MethodNode ownsExact = method(scheduler, "ownsExactExecutionTurn", "()Z");
        assertTrue(calls(ownsExact, internal(RegionWorkerPool.class),
                "ownsPausedExactTurn"), "physical main authority must come from the pool fence");
        assertFalse(calls(ownsExact, internal(RegionalPlayerEntityTicksV2.class),
                "inExactPlayerActor"), "the removed exact player actor must not return");
        ClassNode coordinator = readClass(RegionalPlayerEntityTicksV2.class);
        assertFalse(coordinator.methods.stream().anyMatch(method ->
                        method.name.equals("inExactPlayerActor")
                                || method.name.equals("logicalServerThread")),
                "no logical server-thread identity besides physical Main may exist");
    }

    @Test
    void packetThreadAndFlushMixinsArePackagedWithoutActorHooks() throws Exception {
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
        assertFalse(calls(packetThread, internal(RegionalPlayerEntityTicksV2.class),
                "inExactPlayerActor"));
        assertTrue(calls(packetThread, internal(AsyncIslandPlayerTicks.class),
                "inPacketReplay"), "A5 packet replay remains inline on its island owner");
        MethodNode vanillaPacketThread = method(readClass(PacketProcessor.class),
                "isSameThread", "()Z");
        assertEquals(1, opcodeCount(vanillaPacketThread, Opcodes.IRETURN),
                "exact 26.2 PacketProcessor.isSameThread must keep one RETURN target");

        ClassNode flush = readClass(ServerCommonPacketListenerPlayerTickFlushMixin.class);
        assertFalse(calls(flush, internal(AsyncIslandPlayerTicks.class), "inOwnerTurn"),
                "autonomous A5 sends must retain vanilla off-main flush=true");
        assertFalse(flush.methods.stream().anyMatch(method ->
                        method.name.equals("fabrolia$preserveSuspendedFlush")),
                "send keeps vanilla's flush decision; the actor-only override is gone");

        MethodNode send = method(readClass(ServerCommonPacketListenerImpl.class), "send",
                "(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V");
        assertEquals(1, callCount(send, "net/minecraft/server/MinecraftServer", "isSameThread"));

        try (InputStream in = PlayerTickV2WiringTest.class.getClassLoader()
                .getResourceAsStream("fabrolia.common.mixins.json")) {
            assertNotNull(in);
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("network.PacketProcessorPlayerTickMixin"));
            assertTrue(json.contains("network.ServerCommonPacketListenerPlayerTickFlushMixin"));
            assertFalse(json.contains("server.ServerChunkCacheMainThreadExecutorMixin"));
            assertFalse(json.contains("server.ServerPlayerGameModeAccessor"));
            assertFalse(json.contains("utils.ProfilerAccessor"));
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
