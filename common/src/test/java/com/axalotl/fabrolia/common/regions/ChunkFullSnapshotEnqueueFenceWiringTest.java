package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bytecode pins for the TNT/full-chunk stale-snapshot publication fix. */
class ChunkFullSnapshotEnqueueFenceWiringTest {

    private static final String MIXIN =
            "com/axalotl/fabrolia/common/mixin/server/FabroliaLevelChunkWriteGenMixin";
    private static final String CONNECTION_MIXIN =
            "com/axalotl/fabrolia/common/mixin/network/ConnectionIslandPlayerMixin";
    private static final String LISTENER_MIXIN =
            "com/axalotl/fabrolia/common/mixin/network/"
                    + "ServerCommonPacketListenerPlayerTickFlushMixin";
    private static final String FENCE =
            "com/axalotl/fabrolia/common/regions/ChunkWriteEnqueueFence";
    private static final String CONNECTION_EVENT_LOOP =
            "com/axalotl/fabrolia/common/regions/FabroliaConnectionEventLoop";
    private static final String FULL_CHUNK_PUBLISHER =
            "com/axalotl/fabrolia/common/regions/FabroliaFullChunkPublisher";
    private static final String PUBLICATION_RECEIPT =
            FULL_CHUNK_PUBLISHER + "$PublicationReceipt";
    private static final String CHUNK_FORGET_PUBLISHER =
            "com/axalotl/fabrolia/common/regions/FabroliaChunkForgetPublisher";
    private static final String SENDER_MIXIN =
            "com/axalotl/fabrolia/common/mixin/server/FabroliaPlayerChunkSenderMixin";
    private static final String WITNESS =
            "com/axalotl/fabrolia/common/regions/FabroliaWriteGen";
    private static final String DELIVERY =
            "com/axalotl/fabrolia/common/regions/ChunkDelivery";
    private static final String REGION_SCHEDULER =
            "com/axalotl/fabrolia/common/regions/RegionScheduler";
    private static final String ISLAND_COMMANDS =
            "com/axalotl/fabrolia/common/regions/IslandCommands";
    private static final String ENTRY = DELIVERY + "$Entry";
    private static final String REC = DELIVERY + "$Rec";
    private static final String CHUNK_PACKET =
            "net/minecraft/network/protocol/game/ClientboundLevelChunkWithLightPacket";
    private static final String P_ISLAND =
            "com/axalotl/fabrolia/common/regions/IslandRegistry$PIsland";
    private static final String OPERATION =
            "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
    private static final String INJECT =
            "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String AT =
            "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String LEVEL_CHUNK_CANONICAL_CTOR =
            "<init>(Lnet/minecraft/world/level/Level;"
                    + "Lnet/minecraft/world/level/ChunkPos;"
                    + "Lnet/minecraft/world/level/chunk/UpgradeData;"
                    + "Lnet/minecraft/world/ticks/LevelChunkTicks;"
                    + "Lnet/minecraft/world/ticks/LevelChunkTicks;J"
                    + "[Lnet/minecraft/world/level/chunk/LevelChunkSection;"
                    + "Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;"
                    + "Lnet/minecraft/world/level/levelgen/blending/BlendingData;)V";

    @Test
    void exactTntInteractionPrimesBeforeAuthoritativeAirWriteWithClientFlag()
            throws Exception {
        ClassNode tnt = readClass("net/minecraft/world/level/block/TntBlock");
        MethodNode use = tnt.methods.stream()
                .filter(candidate -> "useItemOn".equals(candidate.name))
                .findFirst().orElseThrow();
        int prime = callIndex(use, tnt.name, "prime");
        int air = callIndex(use, "net/minecraft/world/level/Level", "setBlock");
        assertTrue(prime >= 0 && air > prime,
                "26.2 publishes PrimedTnt before replacing the source block with AIR");
        AbstractInsnNode flag = previousReal(use.instructions.get(air));
        assertTrue(flag instanceof IntInsnNode immediate
                        && immediate.getOpcode() == Opcodes.BIPUSH
                        && immediate.operand == 11,
                "TNT AIR must retain UPDATE_NEIGHBORS|UPDATE_CLIENTS|UPDATE_IMMEDIATE");

        ClassNode level = readClass("net/minecraft/world/level/Level");
        MethodNode threeArg = method(level, "setBlock",
                "(Lnet/minecraft/core/BlockPos;"
                        + "Lnet/minecraft/world/level/block/state/BlockState;I)Z");
        assertEquals(1, callCount(threeArg, level.name, "setBlock"),
                "TNT's three-argument write must enter the complete four-argument route");
        MethodNode fourArg = method(level, "setBlock",
                "(Lnet/minecraft/core/BlockPos;"
                        + "Lnet/minecraft/world/level/block/state/BlockState;II)Z");
        int chunkWrite = callIndex(fourArg,
                "net/minecraft/world/level/chunk/LevelChunk", "setBlockState");
        int correction = callIndex(fourArg, level.name, "sendBlockUpdated");
        assertTrue(chunkWrite >= 0 && correction > chunkWrite,
                "the gated chunk mutation must complete before its newer correction is sent");
    }

    @Test
    void everyFullSnapshotVisibleMutationMarksBeforeItsUnlockedBody()
            throws Exception {
        ClassNode mixin = readClass(MIXIN);
        assertMarkedWrite(mixin, "fabrolia$fenceSetBlockState");
        assertMarkedWrite(mixin, "fabrolia$fenceSetBlockEntity");
        assertMarkedWrite(mixin, "fabrolia$fenceRemoveBlockEntity");
        assertMarkedWrite(mixin, "fabrolia$fenceMarkUnsaved");

        ClassNode fence = readClass(FENCE);
        MethodNode mark = method(fence, "markWriteStart");
        int lock = callIndex(mark,
                "java/util/concurrent/locks/ReentrantLock", "lock");
        int generationWrite = fieldIndex(mark, Opcodes.PUTFIELD, FENCE, "generation");
        int unlock = callIndexAfter(mark,
                "java/util/concurrent/locks/ReentrantLock", "unlock", generationWrite);
        assertTrue(lock >= 0 && generationWrite > lock && unlock > generationWrite,
                "writer-start generation bump must be one short lock transaction");
        assertFalse(mark.tryCatchBlocks.isEmpty());
        assertTrue(callCount(mark,
                "java/util/concurrent/locks/ReentrantLock", "unlock") >= 2,
                "mark must unlock even when generation exhaustion throws");

        MethodNode init = method(mixin, "fabrolia$initializeWriteFence");
        AnnotationNode inject = annotation(init, INJECT);
        assertNotNull(inject, "write-fence initialization must remain an @Inject");
        assertEquals(List.of(LEVEL_CHUNK_CANONICAL_CTOR),
                annotationValue(inject, "method"),
                "a bare <init> selector misses the canonical constructor in 26.2");
        assertEquals(Integer.valueOf(1), annotationValue(inject, "require"));
        assertEquals(Integer.valueOf(1), annotationValue(inject, "allow"));
        AnnotationNode at = firstAnnotationValue(inject, "at");
        assertNotNull(at);
        assertEquals(AT, at.desc);
        assertEquals("RETURN", annotationValue(at, "value"));
        assertEquals(1, typeAllocationCount(init, FENCE),
                "the target constructor must explicitly initialize one fence");
        assertEquals(1, fieldCount(init, Opcodes.PUTFIELD, MIXIN,
                "fabrolia$writeFence"));
        assertEquals(1, mixin.methods.stream().mapToInt(candidate ->
                        typeAllocationCount(candidate, FENCE)).sum(),
                "only the exact constructor handler may allocate a fence");
        MethodNode accessor = method(mixin, "fabrolia$writeFence",
                "()L" + FENCE + ";");
        assertEquals(0, typeAllocationCount(accessor, FENCE),
                "the fail-closed accessor must never lazily allocate a fence");

        MethodNode generation = method(mixin, "fabrolia$writeGen");
        MethodNode reserve = method(mixin,
                "fabrolia$tryReserveFullChunkEnqueue");
        MethodNode release = method(mixin,
                "fabrolia$releaseFullChunkEnqueue");
        assertEquals(1, callCount(generation, FENCE, "generation"));
        assertEquals(1, callCount(reserve, FENCE, "tryReserveEnqueue"));
        assertEquals(1, callCount(release, FENCE, "releaseEnqueue"));

        ClassNode levelChunk = readClass("net/minecraft/world/level/chunk/LevelChunk");
        MethodNode addAndRegister = method(levelChunk, "addAndRegisterBlockEntity");
        assertEquals(1, callCount(addAndRegister, levelChunk.name, "setBlockEntity"),
                "server block-entity installation must enter the gated set surface");
        ClassNode blockEntity = readClass(
                "net/minecraft/world/level/block/entity/BlockEntity");
        MethodNode setChanged = method(blockEntity, "setChanged", "()V");
        MethodNode staticSetChanged = method(blockEntity, "setChanged",
                "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
                        + "Lnet/minecraft/world/level/block/state/BlockState;)V");
        assertEquals(1, callCount(setChanged, blockEntity.name, "setChanged"));
        assertEquals(1, callCount(staticSetChanged,
                "net/minecraft/world/level/Level", "blockEntityChanged"));
        MethodNode blockEntityChanged = method(
                readClass("net/minecraft/world/level/Level"),
                "blockEntityChanged", "(Lnet/minecraft/core/BlockPos;)V");
        assertEquals(1, callCount(blockEntityChanged, levelChunk.name, "markUnsaved"),
                "standard block-entity NBT dirties must bump before update publication");
    }


    @Test
    void exactRecordRetirementAndSenderLifecycleShareOneOrderedTransport()
            throws Exception {
        ClassNode delivery = readClass(DELIVERY);
        ClassNode sender = readClass(SENDER_MIXIN);
        ClassNode listener = readClass(LISTENER_MIXIN);

        MethodNode retire = method(delivery, "retire",
                "(L" + REC + ";L" + DELIVERY + "$St;)Z");
        int gate = fieldIndex(retire, Opcodes.GETFIELD, REC,
                "transportRetireGate");
        int monitor = opcodeIndexAfter(retire, Opcodes.MONITORENTER, gate);
        int exposed = fieldIndexAfter(retire, Opcodes.GETFIELD, REC,
                "clientMayHaveChunk", monitor);
        assertTrue(gate >= 0 && monitor > gate && exposed > monitor,
                "retirement must snapshot exposure under the exact publication gate");
        assertEquals(1, opcodeCount(retire, Opcodes.MONITORENTER));
        assertEquals(0, callCount(retire, FULL_CHUNK_PUBLISHER,
                "fabrolia$enqueueFullChunkBatch"));
        assertEquals(0, callCount(retire, CHUNK_FORGET_PUBLISHER,
                "fabrolia$enqueueChunkForget"));

        MethodNode track = method(delivery, "track",
                "(Lnet/minecraft/server/level/ServerPlayer;"
                        + "Lnet/minecraft/world/level/chunk/LevelChunk;)V");
        assertEquals(1, callCount(track,
                "java/util/concurrent/ConcurrentHashMap", "compute"),
                "same-key replacement must be one CHM key transaction");
        assertEquals(0, callCount(track,
                "java/util/concurrent/ConcurrentHashMap", "put"));
        MethodNode trackTransaction = delivery.methods.stream()
                .filter(candidate -> candidate.name.contains("lambda$track"))
                .filter(candidate -> callCount(candidate, DELIVERY, "retire") == 1)
                .findFirst().orElseThrow();
        int retireOld = callIndex(trackTransaction, DELIVERY, "retire");
        int transferExposure = fieldIndexAfter(trackTransaction, Opcodes.PUTFIELD,
                REC, "clientMayHaveChunk", retireOld);
        assertTrue(transferExposure > retireOld,
                "replacement must transfer exact old exposure before publishing successor");

        MethodNode untrack = method(delivery, "untrack",
                "(Lnet/minecraft/server/level/ServerPlayer;J"
                        + "Lnet/minecraft/world/level/ChunkPos;)Z");
        assertEquals(1, callCount(untrack,
                "java/util/concurrent/ConcurrentHashMap", "compute"),
                "drop retirement and Forget offer must occupy one key transaction");
        assertEquals(0, callCount(untrack,
                "java/util/concurrent/ConcurrentHashMap", "remove"));
        MethodNode dropTransaction = delivery.methods.stream()
                .filter(candidate -> candidate.name.contains("lambda$untrack"))
                .filter(candidate -> callCount(candidate, DELIVERY, "retire") == 1)
                .findFirst().orElseThrow();
        int retireOldOnDrop = callIndex(dropTransaction, DELIVERY, "retire");
        int forgetOffer = callIndexAfter(dropTransaction, CHUNK_FORGET_PUBLISHER,
                "fabrolia$enqueueChunkForget", retireOldOnDrop);
        assertTrue(forgetOffer > retireOldOnDrop,
                "old Rec retirement must precede Forget offer before key release");
        assertEquals(0, callCountByName(dropTransaction, "send"),
                "no listener/network callback may run inside the key transaction");

        assertEquals(0, sender.methods.stream().mapToInt(candidate ->
                        fieldCountByName(candidate, "pendingChunks")).sum(),
                "the sender adapter must never touch vanilla's plain pending mirror");
        MethodNode mark = method(sender, "fabrolia$track");
        MethodNode drop = method(sender, "fabrolia$untrack");
        assertCancellableLifecycleHandler(mark, DELIVERY, "track");
        assertCancellableLifecycleHandler(drop, DELIVERY, "untrack");

        ClassNode vanillaSender = readClass(
                "net/minecraft/server/network/PlayerChunkSender");
        MethodNode vanillaMark = method(vanillaSender, "markChunkPendingToSend");
        MethodNode vanillaDrop = method(vanillaSender, "dropChunk");
        assertEquals(1, callCount(vanillaMark,
                "it/unimi/dsi/fastutil/longs/LongSet", "add"));
        assertEquals(1, callCount(vanillaDrop,
                "it/unimi/dsi/fastutil/longs/LongSet", "remove"));
        assertEquals(1, callCountByName(vanillaDrop, "send"),
                "the cancelled 26.2 drop body has one heuristic Forget send");

        assertTrue(listener.interfaces.contains(CHUNK_FORGET_PUBLISHER));
        MethodNode publishForget = method(listener, "fabrolia$enqueueChunkForget");
        assertEquals(1, callCount(publishForget, PUBLICATION_RECEIPT,
                "publish"));
        assertEquals(0, callCountByName(publishForget, "send"));
        MethodNode forgetReceipt = method(readClass(PUBLICATION_RECEIPT), "publish");
        String taskOffer = FULL_CHUNK_PUBLISHER + "$TaskOffer";
        int offer = callIndex(forgetReceipt, taskOffer, "offer");
        int successCut = callIndexAfter(forgetReceipt,
                "com/axalotl/fabrolia/common/regions/RegionScheduler",
                "commitSuccessIfHealthy", offer);
        int selectWinner = callIndexAfter(forgetReceipt,
                "com/axalotl/fabrolia/common/regions/RegionScheduler",
                "publishFatalAndSelectWinner", successCut);
        int cancelReceipt = fieldIndexAfter(forgetReceipt, Opcodes.PUTFIELD,
                PUBLICATION_RECEIPT, "state", selectWinner);
        assertTrue(offer >= 0 && successCut > offer && selectWinner > successCut
                        && cancelReceipt > selectWinner,
                "fallible offer must precede the healthy COMMITTED cut; W publishes "
                        + "before receipt cancellation");
        assertTrue(opcodeCount(forgetReceipt, Opcodes.MONITORENTER) >= 1,
                "offer-then-throw must cancel before the queued body can enter");
        MethodNode deferredForget = listener.methods.stream()
                .filter(candidate -> candidate.name.contains(
                        "lambda$fabrolia$enqueueChunkForget"))
                .findFirst().orElseThrow();
        assertEquals(1, callCountByName(deferredForget, "send"));
    }

    @Test
    void acceptedTransportTaskIsCancelledWithExactPeerWinnerBeforeItsBody()
            throws Exception {
        Field pool = RegionScheduler.class.getDeclaredField("pool");
        Field orphanFatal = RegionScheduler.class.getDeclaredField("orphanFatalFailure");
        pool.setAccessible(true);
        orphanFatal.setAccessible(true);
        Object previousPool = pool.get(null);
        Object previousFatal = orphanFatal.get(null);
        AssertionError winner = new AssertionError("peer publication W");
        AtomicReference<Runnable> accepted = new AtomicReference<>();
        AtomicBoolean bodyEntered = new AtomicBoolean();
        try {
            pool.set(null, null);
            orphanFatal.set(null, null);
            FabroliaFullChunkPublisher.PublicationReceipt receipt =
                    new FabroliaFullChunkPublisher.PublicationReceipt();
            AssertionError escaped = assertThrows(AssertionError.class,
                    () -> receipt.publish(task -> {
                        accepted.set(task);
                        assertSame(winner,
                                RegionScheduler.publishFatalAndSelectWinner(winner));
                    }, () -> bodyEntered.set(true)));
            assertSame(winner, escaped);
            assertNotNull(accepted.get(), "the OFFERING receipt must exist before queue publication");

            AssertionError queued = assertThrows(AssertionError.class, accepted.get()::run);
            assertSame(winner, queued);
            assertFalse(bodyEntered.get(),
                    "an accepted task whose COMMITTED cut lost to W cannot enter send body");
        } finally {
            pool.set(null, previousPool);
            orphanFatal.set(null, previousFatal);
        }
    }

    @Test
    void directTransportExecutorDefersItsInlineAttemptUntilReceiptCommit()
            throws Exception {
        Field pool = RegionScheduler.class.getDeclaredField("pool");
        Field orphanFatal = RegionScheduler.class.getDeclaredField("orphanFatalFailure");
        pool.setAccessible(true);
        orphanFatal.setAccessible(true);
        Object previousPool = pool.get(null);
        Object previousFatal = orphanFatal.get(null);
        AtomicBoolean bodyEntered = new AtomicBoolean();
        try {
            pool.set(null, null);
            orphanFatal.set(null, null);
            FabroliaFullChunkPublisher.PublicationReceipt receipt =
                    new FabroliaFullChunkPublisher.PublicationReceipt();

            receipt.publish(Runnable::run, () -> {
                assertFalse(bodyEntered.getAndSet(true),
                        "a direct offer may execute the accepted body only once");
            });

            assertTrue(bodyEntered.get(),
                    "the inline attempt must run after the receipt's healthy commit");
        } finally {
            pool.set(null, previousPool);
            orphanFatal.set(null, previousFatal);
        }
    }

    @Test
    void committedTransportTaskRefusesBodyWhenPeerWinnerArrivesBeforeEntry()
            throws Exception {
        Field pool = RegionScheduler.class.getDeclaredField("pool");
        Field orphanFatal = RegionScheduler.class.getDeclaredField("orphanFatalFailure");
        pool.setAccessible(true);
        orphanFatal.setAccessible(true);
        Object previousPool = pool.get(null);
        Object previousFatal = orphanFatal.get(null);
        AtomicReference<Runnable> accepted = new AtomicReference<>();
        AtomicBoolean bodyEntered = new AtomicBoolean();
        AssertionError winner = new AssertionError("post-commit peer W");
        try {
            pool.set(null, null);
            orphanFatal.set(null, null);
            FabroliaFullChunkPublisher.PublicationReceipt receipt =
                    new FabroliaFullChunkPublisher.PublicationReceipt();
            receipt.publish(accepted::set, () -> bodyEntered.set(true));

            assertSame(winner, RegionScheduler.publishFatalAndSelectWinner(winner));
            assertSame(winner, assertThrows(AssertionError.class, accepted.get()::run));
            assertFalse(bodyEntered.get(),
                    "a committed task still needs exact generation body admission");
        } finally {
            pool.set(null, previousPool);
            orphanFatal.set(null, previousFatal);
        }
    }

    @Test
    void chunkGatePublishesOnlyAnOrderedTaskAndRunsPublicSendLater()
            throws Exception {
        ClassNode listenerMixin = readClass(LISTENER_MIXIN);
        ClassNode connectionMixin = readClass(CONNECTION_MIXIN);
        assertTrue(listenerMixin.interfaces.contains(FULL_CHUNK_PUBLISHER));
        assertTrue(connectionMixin.interfaces.contains(CONNECTION_EVENT_LOOP));

        MethodNode publish = method(listenerMixin,
                "fabrolia$enqueueFullChunkBatch");
        assertEquals(0, publish.access & Opcodes.ACC_SYNCHRONIZED);
        int physicalMain = callIndex(publish,
                "com/axalotl/fabrolia/common/regions/RegionalPlayerEntityTicksV2",
                "isPhysicalServerThread");
        int queue = callIndex(publish, CONNECTION_EVENT_LOOP,
                "fabrolia$enqueueEventLoopTask");
        assertTrue(physicalMain >= 0 && queue > physicalMain,
                "full snapshots may enter the event-loop FIFO only from physical Main");
        assertEquals(0, callCount(publish,
                "net/minecraft/server/network/ServerCommonPacketListenerImpl", "send"),
                "the listener body must not run while the LevelChunk gate is held");

        MethodNode deferredSend = listenerMixin.methods.stream()
                .filter(candidate -> candidate.name.contains(
                        "lambda$fabrolia$enqueueFullChunkBatch"))
                .findFirst().orElseThrow();
        assertEquals(1, callCount(deferredSend,
                FULL_CHUNK_PUBLISHER + "$FullChunkBatchPublication",
                "runPublished"));
        assertEquals(0, callCountByName(deferredSend, "send"),
                "the queued wrapper delegates through the receipt before any send body");

        MethodNode eventLoop = method(connectionMixin,
                "fabrolia$enqueueEventLoopTask");
        assertEquals(0, eventLoop.access & Opcodes.ACC_SYNCHRONIZED);
        int selectLoop = callIndex(eventLoop, "io/netty/channel/Channel", "eventLoop");
        int tracked = callIndex(eventLoop, REGION_SCHEDULER, "executeTrackedExternal");
        assertTrue(selectLoop >= 0 && tracked > selectLoop,
                "the transport generation permit must be reserved before EventLoop visibility");
        assertEquals(0, callCount(eventLoop, "io/netty/channel/EventLoop", "execute"),
                "raw EventLoop publication would reacquire generation authority too late");
        assertEquals(0, callCount(eventLoop, "net/minecraft/network/Connection", "send"));
        assertEquals(0, callCount(eventLoop, "net/minecraft/network/Connection", "flushQueue"));
        assertEquals(0, opcodeCount(eventLoop, Opcodes.MONITORENTER));
        assertEquals(0, opcodeCount(eventLoop, Opcodes.MONITOREXIT));
        MethodNode eventLoopBody = connectionMixin.methods.stream()
                .filter(candidate -> candidate.name.contains(
                        "lambda$fabrolia$enqueueEventLoopTask"))
                .filter(candidate -> callCount(
                        candidate, "java/lang/Runnable", "run") == 1)
                .findFirst().orElseThrow();
        assertEquals(1, callCount(eventLoopBody, "java/lang/Runnable", "run"),
                "the accepted external permit must execute the exact transport receipt once");
        assertEquals(1, callCount(eventLoopBody, ISLAND_COMMANDS, "publishAndPropagate"),
                "a Netty body failure must still arm physical-Main fail-stop");
        assertEquals(0, callCount(eventLoopBody, REGION_SCHEDULER,
                "runSuccessBodyIfHealthy"),
                "Netty execution must inherit its pre-publication permit, not acquire a new root");
    }

    @Test
    void sealedTransportPacketOwnsItsSnapshotPayload() throws Exception {
        ClassNode entry = readClass(ENTRY);
        assertTrue(entry.fields.stream().anyMatch(field -> field.name.equals("packet")
                && (field.access & Opcodes.ACC_FINAL) != 0));

        ClassNode packet = readClass(CHUNK_PACKET);
        assertFalse(packet.fields.stream().anyMatch(field ->
                        field.desc.contains("world/level/chunk/LevelChunk")),
                "queued packet must not retain the mutable source chunk");
        for (String payload : new String[] {"x", "z", "chunkData", "lightData"}) {
            assertTrue(packet.fields.stream().anyMatch(field -> field.name.equals(payload)
                            && (field.access & Opcodes.ACC_FINAL) != 0),
                    "packet payload field must be final: " + payload);
        }
    }


    @Test
    void deployedNettyExecuteIsQueueOfferNotInlineOrBackpressureWait()
            throws Exception {
        ClassNode executor = readClass(
                "io/netty/util/concurrent/SingleThreadEventExecutor");
        MethodNode publicExecute = method(executor, "execute",
                "(Ljava/lang/Runnable;)V");
        assertEquals(1, callCount(publicExecute, executor.name, "execute0"));
        assertEquals(0, callCount(publicExecute, "java/lang/Runnable", "run"));

        MethodNode execute = method(executor, "execute",
                "(Ljava/lang/Runnable;Z)V");
        assertEquals(1, callCount(execute, executor.name, "addTask"));
        assertEquals(0, callCount(execute, "java/lang/Runnable", "run"));
        assertEquals(0, opcodeCount(execute, Opcodes.MONITORENTER));
        assertEquals(0, opcodeCount(execute, Opcodes.MONITOREXIT));
        for (String wait : new String[] {"get", "join", "sync", "await", "park"}) {
            assertEquals(0, callCountByName(execute, wait),
                    "EventLoop.execute must not wait for transport/backpressure: " + wait);
        }

        MethodNode offer = method(executor, "offerTask",
                "(Ljava/lang/Runnable;)Z");
        assertEquals(1, callCount(offer, "java/util/Queue", "offer"));
    }

    private static void assertMarkedWrite(ClassNode mixin, String methodName) {
        MethodNode wrapper = method(mixin, methodName);
        int mark = callIndex(wrapper, FENCE, "markWriteStart");
        int body = callIndex(wrapper, OPERATION, "call");
        assertTrue(mark >= 0 && body > mark,
                methodName + " must invalidate the old generation before original.call");
        assertEquals(1, callCount(wrapper, FENCE, "markWriteStart"));
        assertEquals(0, callCount(wrapper, FENCE, "beginWrite"));
        assertEquals(0, callCount(wrapper, FENCE, "endWrite"));
        assertEquals(0, callCount(wrapper,
                "java/util/concurrent/locks/ReentrantLock", "lock"),
                "no LevelChunk body/callback may retain a chunk fence");
        assertEquals(0, wrapper.access & Opcodes.ACC_SYNCHRONIZED);
        assertEquals(0, opcodeCount(wrapper, Opcodes.MONITORENTER));
        assertEquals(0, opcodeCount(wrapper, Opcodes.MONITOREXIT));
    }

    private static void assertCancellableLifecycleHandler(MethodNode handler,
                                                           String owner,
                                                           String name) {
        AnnotationNode inject = annotation(handler, INJECT);
        assertNotNull(inject, "lifecycle replacement must remain an @Inject");
        assertEquals(Boolean.TRUE, annotationValue(inject, "cancellable"));
        int route = callIndex(handler, owner, name);
        int cancel = callIndexAfter(handler,
                "org/spongepowered/asm/mixin/injection/callback/CallbackInfo",
                "cancel", route);
        assertTrue(route >= 0 && cancel > route,
                "authoritative lifecycle route must complete before vanilla cancellation");
    }

    private static AnnotationNode annotation(MethodNode method, String desc) {
        if (method.visibleAnnotations != null) {
            for (AnnotationNode annotation : method.visibleAnnotations) {
                if (desc.equals(annotation.desc)) return annotation;
            }
        }
        if (method.invisibleAnnotations != null) {
            for (AnnotationNode annotation : method.invisibleAnnotations) {
                if (desc.equals(annotation.desc)) return annotation;
            }
        }
        return null;
    }

    private static Object annotationValue(AnnotationNode annotation, String key) {
        if (annotation == null || annotation.values == null) return null;
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static AnnotationNode firstAnnotationValue(AnnotationNode annotation,
                                                       String key) {
        Object value = annotationValue(annotation, key);
        if (value instanceof AnnotationNode nested) return nested;
        if (value instanceof List<?> list && !list.isEmpty()
                && list.getFirst() instanceof AnnotationNode nested) return nested;
        return null;
    }

    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(candidate -> name.equals(candidate.name))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "missing method " + owner.name + "." + name));
    }

    private static MethodNode method(ClassNode owner, String name, String desc) {
        return owner.methods.stream().filter(candidate -> name.equals(candidate.name)
                        && desc.equals(candidate.desc))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "missing method " + owner.name + "." + name + desc));
    }

    private static int callCount(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) count++;
        }
        return count;
    }

    private static int callCountByName(MethodNode method, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && name.equals(call.name)) count++;
        }
        return count;
    }

    private static int callIndex(MethodNode method, String owner, String name) {
        return callIndexAfter(method, owner, name, -1);
    }

    private static int callIndexAfter(MethodNode method, String owner, String name,
                                      int after) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (index > after && instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) return index;
            index++;
        }
        return -1;
    }

    private static int fieldCount(MethodNode method, int opcode,
                                  String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == opcode && owner.equals(field.owner)
                    && name.equals(field.name)) count++;
        }
        return count;
    }

    private static int fieldCountByName(MethodNode method, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && name.equals(field.name)) count++;
        }
        return count;
    }

    private static int fieldIndex(MethodNode method, int opcode,
                                  String owner, String name) {
        return fieldIndexAfter(method, opcode, owner, name, -1);
    }

    private static int fieldIndexAfter(MethodNode method, int opcode,
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

    private static int typeAllocationCount(MethodNode method, String internalName) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == Opcodes.NEW
                    && instruction instanceof org.objectweb.asm.tree.TypeInsnNode type
                    && internalName.equals(type.desc)) count++;
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

    private static int opcodeIndexAfter(MethodNode method, int opcode, int after) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (index > after && instruction.getOpcode() == opcode) return index;
            index++;
        }
        return -1;
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode instruction) {
        for (AbstractInsnNode previous = instruction == null ? null
                : instruction.getPrevious(); previous != null;
             previous = previous.getPrevious()) {
            if (previous.getOpcode() >= 0) return previous;
        }
        return null;
    }

    private static ClassNode readClass(String internalName) throws Exception {
        try (InputStream input = ChunkFullSnapshotEnqueueFenceWiringTest.class
                .getClassLoader().getResourceAsStream(internalName + ".class")) {
            assertNotNull(input, "missing compiled class " + internalName);
            ClassNode type = new ClassNode();
            new ClassReader(input).accept(type, 0);
            return type;
        }
    }
}
