package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact 26.2 bytecode pins plus a state model for the island-to-Main
 * chunk-batch acknowledgement ownership transfer.
 */
class ChunkBatchAckOwnershipTest {

    private static final String LISTENER =
            "net/minecraft/server/network/ServerGamePacketListenerImpl";
    private static final String SENDER =
            "net/minecraft/server/network/PlayerChunkSender";
    private static final String SERVER = "net/minecraft/server/MinecraftServer";
    private static final String ACK_PACKET =
            "net/minecraft/network/protocol/game/ServerboundChunkBatchReceivedPacket";
    private static final String PACKET_UTILS = "net/minecraft/network/protocol/PacketUtils";
    private static final String SENDER_MIXIN =
            "com/axalotl/fabrolia/common/mixin/server/FabroliaPlayerChunkSenderMixin";
    private static final String LISTENER_MIXIN =
            "com/axalotl/fabrolia/common/mixin/server/FabroliaChunkAckMixin";
    private static final String DELIVERY =
            "com/axalotl/fabrolia/common/regions/ChunkDelivery";
    private static final String REPLAY =
            "com/axalotl/fabrolia/common/regions/ChunkBatchAckReplay";
    private static final String REGION_SCHEDULER =
            "com/axalotl/fabrolia/common/regions/RegionScheduler";
    private static final String OPERATION =
            "com/llamalad7/mixinextras/injector/wrapoperation/Operation";

    @Test
    void exact262HandlerAndPacingMutationSurfaceArePinned() throws Exception {
        MethodNode handler = method(readClass(LISTENER), "handleChunkBatchReceived",
                "(L" + ACK_PACKET + ";)V");
        int ensure = callIndex(handler, PACKET_UTILS, "ensureRunningOnSameThread");
        int desired = callIndex(handler, ACK_PACKET, "desiredChunksPerTick");
        int pacing = callIndex(handler, SENDER, "onChunkBatchReceivedByClient");
        assertTrue(ensure >= 0 && ensure < desired && desired < pacing,
                "26.2 ACK handler must remain ensure-thread -> raw desired -> sender pacing");
        assertEquals(1, callCount(handler, PACKET_UTILS, "ensureRunningOnSameThread"));
        assertEquals(1, callCount(handler, ACK_PACKET, "desiredChunksPerTick"));
        assertEquals(1, callCount(handler, SENDER, "onChunkBatchReceivedByClient"));

        MethodNode ack = method(readClass(SENDER), "onChunkBatchReceivedByClient", "(F)V");
        assertEquals(0, ack.access & Opcodes.ACC_SYNCHRONIZED);
        assertEquals(0, opcodeCount(ack, Opcodes.MONITORENTER));
        assertEquals(0, opcodeCount(ack, Opcodes.MONITOREXIT));
        assertEquals(3, fieldCount(ack, Opcodes.GETFIELD, SENDER,
                "unacknowledgedBatches"));
        assertEquals(2, fieldCount(ack, Opcodes.PUTFIELD, SENDER,
                "unacknowledgedBatches"));
        assertEquals(1, fieldCount(ack, Opcodes.PUTFIELD, SENDER,
                "desiredChunksPerTick"));
        assertEquals(1, fieldCount(ack, Opcodes.PUTFIELD, SENDER, "batchQuota"));
        assertEquals(1, fieldCount(ack, Opcodes.PUTFIELD, SENDER,
                "maxUnacknowledgedBatches"));
        assertEquals(1, callCount(ack, "java/lang/Double", "isNaN"));
        assertEquals(1, callCount(ack, "net/minecraft/util/Mth", "clamp"));
        assertEquals(0, opcodeCount(ack, Opcodes.ATHROW),
                "the exact vanilla replay body is bounded non-throwing scalar math");
        assertEquals(1, integerConstantCount(ack, 10),
                "the compact mailbox bound is justified by vanilla's exact max window");

        MethodNode ledger = method(readClass(DELIVERY), "onAck",
                "(Lnet/minecraft/server/level/ServerPlayer;)V");
        assertEquals(0, opcodeCount(ledger, Opcodes.ATHROW),
                "the installed ledger retire has no throwing control-flow edge");
    }

    @Test
    void senderWrapperIsTheOnlyAckVenueSplitAndMainRetiresExactlyOnce() throws Exception {
        ClassNode listenerMixin = readClass(LISTENER_MIXIN);
        assertFalse(listenerMixin.methods.stream().anyMatch(method ->
                        "fabrolia$ack".equals(method.name)),
                "a listener TAIL/HEAD hook would double-retire or retire on the island");
        assertEquals(0, listenerMixin.methods.stream()
                        .mapToInt(method -> callCount(method, DELIVERY, "onAck")).sum(),
                "the delivery ledger must be reached only through the sender Main branch");

        ClassNode senderMixin = readClass(SENDER_MIXIN);
        MethodNode route = method(senderMixin,
                "fabrolia$routeChunkBatchAck",
                "(FL" + OPERATION + ";)V");
        List<String> annotations = annotationStrings(route);
        assertTrue(annotations.contains(
                "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;"));
        assertTrue(annotations.contains("onChunkBatchReceivedByClient"));

        int permit = callIndex(route, REPLAY, "consume");
        int original = callIndex(route, OPERATION, "call");
        int ledgerAdmission = callIndex(route, REGION_SCHEDULER,
                "runSuccessBodyIfHealthy");
        int enqueue = callIndex(route, SENDER_MIXIN,
                "fabrolia$enqueueChunkBatchAckBits");
        assertTrue(permit >= 0 && permit < original && original < ledgerAdmission
                        && ledgerAdmission < enqueue,
                "only a consumed replay permit may run transformed vanilla, then admit "
                        + "one separately-gated ledger ACK");
        assertEquals(1, callCount(route, REPLAY, "consume"));
        assertEquals(1, callCount(route, OPERATION, "call"));
        assertEquals(1, callCount(route, REGION_SCHEDULER,
                "runSuccessBodyIfHealthy"));
        assertEquals(1, senderMixin.methods.stream()
                        .mapToInt(method -> callCount(method, DELIVERY, "onAck")).sum(),
                "the admitted ACK tail must retire the delivery ledger exactly once");
        assertEquals(1, callCount(route, "java/lang/Float", "floatToRawIntBits"));
        assertEquals(0, opcodeCount(route, Opcodes.MONITORENTER));
    }

    @Test
    void replayPermitIsPhysicalMainExactSenderOneShotAndConsumedBeforeOriginal()
            throws Exception {
        ClassNode replay = readClass(REPLAY);
        MethodNode arm = method(replay, "replay", "(L" + SENDER + ";F)V");
        int physical = callIndex(arm,
                "com/axalotl/fabrolia/common/regions/RegionalPlayerEntityTicksV2",
                "isPhysicalServerThread");
        int activeSet = callIndex(arm, "java/lang/ThreadLocal", "set");
        int target = callIndex(arm, SENDER, "onChunkBatchReceivedByClient");
        int cleanup = callIndexAfter(arm, "java/lang/ThreadLocal", "remove", target);
        assertTrue(physical >= 0 && physical < activeSet && activeSet < target
                        && target < cleanup,
                "helper must assert physical Main, arm before target and clean up in finally");
        assertEquals(2, callCount(arm, "java/lang/ThreadLocal", "set"),
                "separate active and one-shot markers prevent nested replay");
        assertTrue(callCount(arm, "java/lang/ThreadLocal", "remove") >= 2);

        MethodNode consume = method(replay, "consume", "(L" + SENDER + ";)Z");
        int get = callIndex(consume, "java/lang/ThreadLocal", "get");
        int remove = callIndex(consume, "java/lang/ThreadLocal", "remove");
        assertTrue(get >= 0 && get < remove,
                "exact-sender permit must be removed before wrapper invokes original.call");
        assertEquals(1, callCount(consume, "java/lang/ThreadLocal", "remove"));
        assertEquals(0, callCount(consume, SENDER, "onChunkBatchReceivedByClient"));
    }

    @Test
    void mailboxMonitorContainsOnlyBoundedPrimitiveTransactions() throws Exception {
        ClassNode mixin = readClass(SENDER_MIXIN);
        MethodNode enqueue = method(mixin, "fabrolia$enqueueChunkBatchAckBits", "(I)V");
        MethodNode take = method(mixin, "fabrolia$takeChunkBatchAcks", "()J");
        MethodNode discard = method(mixin, "fabrolia$discardChunkBatchAcks", "()V");
        for (MethodNode transaction : List.of(enqueue, take, discard)) {
            assertTrue((transaction.access & Opcodes.ACC_SYNCHRONIZED) != 0,
                    transaction.name + " must linearize on the exact sender monitor");
            assertEquals(0, calls(transaction).size(),
                    transaction.name + " must not call world/network/ledger/another monitor");
            assertEquals(0, opcodeCount(transaction, Opcodes.MONITORENTER),
                    "ACC_SYNCHRONIZED keeps the whole transaction mechanically obvious");
        }
        assertEquals(1, integerConstantCount(enqueue, 10));
        assertEquals(2, fieldCount(enqueue, Opcodes.PUTFIELD, SENDER_MIXIN,
                "fabrolia$latestAckDesiredBits")
                        + fieldCount(enqueue, Opcodes.PUTFIELD, SENDER_MIXIN,
                        "fabrolia$pendingAckCount"));
        assertEquals(1, fieldCount(take, Opcodes.PUTFIELD, SENDER_MIXIN,
                "fabrolia$pendingAckCount"));
        assertEquals(1, fieldCount(discard, Opcodes.PUTFIELD, SENDER_MIXIN,
                "fabrolia$pendingAckCount"));
        assertEquals(1, fieldCount(discard, Opcodes.PUTFIELD, SENDER_MIXIN,
                "fabrolia$ackMailboxClosed"));
    }

    @Test
    void mainSnapshotsBeforeSendAndCommitsUnackedBeforeVanillaFlushSlot() throws Exception {
        ClassNode mixin = readClass(SENDER_MIXIN);
        MethodNode drain = method(mixin, "fabrolia$replayChunkBatchAcksOnMain",
                "(J)V");
        assertEquals(0, callCount(drain, SENDER_MIXIN, "fabrolia$takeChunkBatchAcks"));
        assertEquals(1, callCount(drain, REGION_SCHEDULER,
                "runSuccessBodyIfHealthy"),
                "each loop iteration must win a fresh body-entry cut before replay");
        assertEquals(0, callCount(drain, REPLAY, "replay"),
                "the transformed replay body must live behind the admission lambda");
        assertEquals(1, mixin.methods.stream()
                        .mapToInt(method -> callCount(method, REPLAY, "replay")).sum());
        assertEquals(0, callCount(drain, SENDER, "onChunkBatchReceivedByClient"));
        assertEquals(0, callCount(drain, DELIVERY, "onAck"),
                "target wrapper, not the drain, owns exactly-once ledger retirement");
        assertEquals(0, opcodeCount(drain, Opcodes.MONITORENTER));

        MethodNode send = method(mixin, "fabrolia$send",
                "(Lnet/minecraft/server/level/ServerPlayer;"
                        + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V");
        int fatalGate = callIndex(send, REGION_SCHEDULER,
                "throwWorkerFailureIfAny");
        int ackSnapshot = callIndex(send, SENDER_MIXIN,
                "fabrolia$takeChunkBatchAcks");
        int completed = callIndex(send, DELIVERY, "applyCompletedChunkBatch");
        int successSequence = callIndex(send, SENDER_MIXIN,
                "fabrolia$applyCompletedAndReplay");
        int gate = fieldIndex(send, Opcodes.GETFIELD, SENDER_MIXIN,
                "unacknowledgedBatches");
        int delivery = callIndex(send, DELIVERY, "sendNextChunks");
        assertTrue(fatalGate >= 0 && ackSnapshot > fatalGate && completed > ackSnapshot
                        && successSequence > completed && gate > successSequence
                        && delivery > gate,
                "fatal gate must precede ACK mutation; the snapshot then precedes the real "
                        + "completion and the staged pacing/ACK success sequence; "
                        + "a raced ACK stays for the next pass and a new offer is never counted");
        assertEquals(0, callCount(send, REGION_SCHEDULER,
                "commitSuccessIfHealthy"),
                "the full transformed ACK body may not execute under lifecycleLock");

        MethodNode sequence = method(mixin, "fabrolia$applyCompletedAndReplay", "(IJ)V");
        int completionCut = callIndex(sequence, REGION_SCHEDULER,
                "commitSuccessIfHealthy");
        int ackDrain = callIndex(sequence, SENDER_MIXIN,
                "fabrolia$replayChunkBatchAcksOnMain");
        int refillCut = callIndexAfter(sequence, REGION_SCHEDULER,
                "commitSuccessIfHealthy", ackDrain);
        assertTrue(completionCut >= 0 && ackDrain > completionCut
                        && refillCut > ackDrain,
                "fixed completion pacing, individually admitted ACK bodies and fixed quota "
                        + "refill must be separate success-vs-W stages");
        assertEquals(2, callCount(sequence, REGION_SCHEDULER,
                "commitSuccessIfHealthy"));

        MethodNode tickChildren = method(readClass(SERVER), "tickChildren",
                "(Ljava/util/function/BooleanSupplier;)V");
        int sendSlot = callIndex(tickChildren, SENDER, "sendNextChunks");
        int flush = callIndex(tickChildren, LISTENER, "resumeFlushing");
        assertTrue(sendSlot >= 0 && sendSlot < flush,
                "vanilla exposes the batch only after sender replacement returns/commits");
    }

    @Test
    void cappedLatestValueCompactionMatchesEveryLegal262PacingAndLedgerState() {
        float[] values = {
                Float.NaN, Float.NEGATIVE_INFINITY, -4.0f, 0.005f,
                0.01f, 1.0f, 9.0f, 64.0f, 100.0f, Float.POSITIVE_INFINITY
        };
        float[] multipliers = {0.0f, 0.25f, 1.0f, 2.0f};
        for (int initialUnacked = 0; initialUnacked <= 10; initialUnacked++) {
            for (int outstanding = 0; outstanding <= 10; outstanding++) {
                for (int length = 1; length <= 32; length++) {
                    for (int seed = 0; seed < values.length; seed++) {
                        for (float multiplier : multipliers) {
                            Pacing exact = new Pacing(initialUnacked, 3, 7.25f, 4.5f);
                            float latest = 0.0f;
                            for (int i = 0; i < length; i++) {
                                latest = values[(seed + i * 3) % values.length];
                                exact.ack(latest, multiplier);
                            }

                            Pacing compact = new Pacing(initialUnacked, 3, 7.25f, 4.5f);
                            int replayCount = Math.min(10, length);
                            for (int i = 0; i < replayCount; i++) {
                                compact.ack(latest, multiplier);
                            }
                            assertPacingEquals(exact, compact);
                            assertEquals(Math.max(0, outstanding - length),
                                    Math.max(0, outstanding - replayCount),
                                    "ten intents retire every possible 26.2 batch obligation");
                        }
                    }
                }
            }
        }
    }

    @Test
    void ackArrivingDuringSendCannotBeConsumedBeforeThatBatchCommit() {
        MailboxModel mailbox = new MailboxModel();

        Snapshot beforeConstruction = mailbox.take();
        assertEquals(0, beforeConstruction.count);

        // The client response races the world/network portion after Main's
        // pre-send snapshot. It can only populate the next mailbox generation.
        mailbox.enqueue(Float.floatToRawIntBits(30.0f));
        Pacing main = new Pacing(0, 10, 1.0f, 9.0f);
        main.unacknowledged++;

        Snapshot nextMainSlot = mailbox.take();
        assertEquals(1, nextMainSlot.count);
        for (int i = 0; i < nextMainSlot.count; i++) {
            main.ack(Float.intBitsToFloat(nextMainSlot.latestBits), 1.0f);
        }
        assertEquals(0, main.unacknowledged,
                "the ACK observes the committed obligation instead of being clamped away early");
        assertEquals(0, mailbox.take().count);
    }

    @Test
    void disconnectClosureRejectsAStalePostTerminalAck() {
        MailboxModel mailbox = new MailboxModel();
        mailbox.enqueue(Float.floatToRawIntBits(4.0f));
        mailbox.close();
        mailbox.enqueue(Float.floatToRawIntBits(30.0f));
        assertEquals(0, mailbox.take().count,
                "a stale island callback cannot resurrect intent after delivery quit");
    }

    @Test
    void islandAckThenMainHandoffAckRetainsTheNewerDesiredRate() {
        MailboxModel mailbox = new MailboxModel();
        mailbox.enqueue(Float.floatToRawIntBits(4.0f));   // ACK1 on island
        mailbox.enqueue(Float.floatToRawIntBits(30.0f));  // ACK2 after Main hand-off

        Snapshot ordered = mailbox.take();
        assertEquals(2, ordered.count);
        Pacing compact = new Pacing(2, 10, 5.0f, 9.0f);
        for (int i = 0; i < ordered.count; i++) {
            compact.ack(Float.intBitsToFloat(ordered.latestBits), 1.0f);
        }
        assertEquals(Float.floatToIntBits(30.0f), Float.floatToIntBits(compact.desired));

        Pacing forbiddenDirectMain = new Pacing(2, 10, 5.0f, 9.0f);
        forbiddenDirectMain.ack(30.0f, 1.0f); // ACK2 bypasses queued ACK1
        forbiddenDirectMain.ack(4.0f, 1.0f);  // late drain incorrectly overwrites it
        assertNotEquals(Float.floatToIntBits(compact.desired),
                Float.floatToIntBits(forbiddenDirectMain.desired),
                "every external Main ACK must join the same mailbox as the island prefix");
    }

    @Test
    void dimensionChangeRetainsFifoSkeletonsThatPinExactRecordIdentity()
            throws Exception {
        String batch = DELIVERY + "$Batch";
        String rec = DELIVERY + "$Rec";
        ClassNode delivery = readClass(DELIVERY);
        MethodNode track = method(delivery, "track",
                "(Lnet/minecraft/server/level/ServerPlayer;"
                        + "Lnet/minecraft/world/level/chunk/LevelChunk;)V");
        int physical = callIndex(track,
                "com/axalotl/fabrolia/common/regions/RegionalPlayerEntityTicksV2",
                "isPhysicalServerThread");
        int retireSweep = callIndex(track, "java/util/concurrent/ConcurrentHashMap",
                "clear");
        assertTrue(physical >= 0 && physical < retireSweep,
                "cross-level epoch mutation must assert physical Main before its sweep");
        assertEquals(0, callCount(track, "java/util/ArrayDeque", "clear"),
                "dimension change must retain old ACK skeletons in connection FIFO");

        MethodNode quit = method(delivery, "quit",
                "(Lnet/minecraft/server/level/ServerPlayer;)V");
        assertEquals(1, callCount(quit, "java/util/ArrayDeque", "clear"),
                "only connection teardown destroys outstanding ACK obligations");

        ClassNode batchNode = readClass(batch);
        assertTrue(batchNode.fields.stream().anyMatch(field ->
                        "records".equals(field.name) && ("[L" + rec + ";").equals(field.desc)),
                "Batch must pin exact Rec identities");
        assertFalse(batchNode.fields.stream().anyMatch(field ->
                        "keys".equals(field.name) && "[J".equals(field.desc)),
                "reusable chunk keys are not ACK capabilities");

        MethodNode onAck = method(delivery, "onAck",
                "(Lnet/minecraft/server/level/ServerPlayer;)V");
        assertEquals(1, fieldCount(onAck, Opcodes.GETFIELD, batch, "records"));
        assertEquals(1, callCount(onAck, rec, "cas"));
        assertEquals(0, fieldCount(onAck, Opcodes.GETFIELD,
                        DELIVERY + "$PlayerState", "records"),
                "ACK must never resolve a reusable key through current player records");

        ClassNode publication = readClass(DELIVERY + "$BatchPublication");
        MethodNode apply = method(publication, "applySuccessOnMain", "()I");
        assertEquals(1, callCount(apply, REGION_SCHEDULER,
                        "runSuccessBodyIfHealthy"),
                "ACK preparation must enter through one exact success-body admission");
        int batchConstructions = 0;
        int outstandingAdds = 0;
        for (MethodNode candidate : publication.methods) {
            if (!candidate.name.startsWith("lambda$applySuccessOnMain$")) continue;
            batchConstructions += callCount(candidate, batch, "<init>");
            outstandingAdds += callCount(candidate, "java/util/ArrayDeque", "addLast");
        }
        assertEquals(1, batchConstructions,
                "ACK skeleton must not exist before the real EventLoop body succeeds");
        assertEquals(1, outstandingAdds,
                "only a real EventLoop success may install the skeleton on Main");
    }

    @Test
    void lateOldDimensionAndSameKeyAcksCannotRetireANewerRecord() {
        DeliveryRec old = new DeliveryRec(42L, true);
        DeliveryRec replacement = new DeliveryRec(42L, true);
        ArrayDeque<DeliveryBatch> fifo = new ArrayDeque<>();
        fifo.addLast(new DeliveryBatch(old));

        old.enqueued = false; // old dimension/retracking retirement
        fifo.addLast(new DeliveryBatch(replacement));
        fifo.removeFirst().ack(); // late ACK(old)
        assertTrue(replacement.enqueued,
                "old skeleton targets retired identity, not replacement sharing key 42");
        fifo.removeFirst().ack(); // ACK(new)
        assertFalse(replacement.enqueued);

        DeliveryRec retriedAfterFailedEnqueue = new DeliveryRec(7L, true);
        fifo.addLast(new DeliveryBatch()); // earlier Finished carried no successful record
        fifo.addLast(new DeliveryBatch(retriedAfterFailedEnqueue));
        fifo.removeFirst().ack();
        assertTrue(retriedAfterFailedEnqueue.enqueued,
                "an empty/failed earlier batch cannot retire a later retry of the same Rec");
        fifo.removeFirst().ack();
        assertFalse(retriedAfterFailedEnqueue.enqueued);
    }

    private static final class Pacing {
        int unacknowledged;
        int maxUnacknowledged;
        float quota;
        float desired;

        Pacing(int unacknowledged, int maxUnacknowledged, float quota, float desired) {
            this.unacknowledged = unacknowledged;
            this.maxUnacknowledged = maxUnacknowledged;
            this.quota = quota;
            this.desired = desired;
        }

        void ack(float rawDesired, float c2meMultiplier) {
            unacknowledged = Math.max(0, unacknowledged - 1);
            desired = Float.isNaN(rawDesired)
                    ? 0.01f : Math.max(0.01f, Math.min(64.0f, rawDesired));
            if (unacknowledged == 0) quota = 1.0f;
            maxUnacknowledged = 10;
            if (c2meMultiplier > 0.0f) desired *= c2meMultiplier;
        }
    }

    private static void assertPacingEquals(Pacing expected, Pacing actual) {
        assertEquals(expected.unacknowledged, actual.unacknowledged);
        assertEquals(expected.maxUnacknowledged, actual.maxUnacknowledged);
        assertEquals(Float.floatToIntBits(expected.quota),
                Float.floatToIntBits(actual.quota));
        assertEquals(Float.floatToIntBits(expected.desired),
                Float.floatToIntBits(actual.desired));
    }

    private static final class MailboxModel {
        int count;
        int latestBits;
        boolean closed;

        synchronized void enqueue(int bits) {
            if (closed) return;
            latestBits = bits;
            if (count < 10) count++;
        }

        synchronized Snapshot take() {
            Snapshot result = new Snapshot(count, latestBits);
            count = 0;
            return result;
        }

        synchronized void close() {
            count = 0;
            closed = true;
        }
    }

    private record Snapshot(int count, int latestBits) { }

    private static final class DeliveryRec {
        final long key;
        boolean enqueued;

        DeliveryRec(long key, boolean enqueued) {
            this.key = key;
            this.enqueued = enqueued;
        }
    }

    private record DeliveryBatch(DeliveryRec... records) {
        void ack() {
            for (DeliveryRec record : records) {
                if (record.enqueued) record.enqueued = false;
            }
        }
    }

    private static ClassNode readClass(String internalName) throws Exception {
        try (InputStream in = ChunkBatchAckOwnershipTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            if (in == null) throw new AssertionError("missing class: " + internalName);
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    private static MethodNode method(ClassNode owner, String name, String desc) {
        return owner.methods.stream()
                .filter(method -> name.equals(method.name) && desc.equals(method.desc))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing " + owner.name + "." + name + desc));
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        ArrayList<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) result.add(call);
        }
        return result;
    }

    private static int callCount(MethodNode method, String owner, String name) {
        return (int) calls(method).stream()
                .filter(call -> owner.equals(call.owner) && name.equals(call.name))
                .count();
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

    private static int callIndexByName(MethodNode method, String name) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && name.equals(call.name)) return index;
            index++;
        }
        return -1;
    }

    private static int callIndexAfter(MethodNode method, String owner,
                                      String name, int after) {
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
                    && field.getOpcode() == opcode
                    && owner.equals(field.owner) && name.equals(field.name)) count++;
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
                    && field.getOpcode() == opcode
                    && owner.equals(field.owner) && name.equals(field.name)) return index;
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

    private static int integerConstantCount(MethodNode method, int wanted) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            int value;
            if (instruction.getOpcode() >= Opcodes.ICONST_M1
                    && instruction.getOpcode() <= Opcodes.ICONST_5) {
                value = instruction.getOpcode() - Opcodes.ICONST_0;
            } else if (instruction instanceof IntInsnNode integer) {
                value = integer.operand;
            } else if (instruction instanceof LdcInsnNode ldc
                    && ldc.cst instanceof Integer integer) {
                value = integer;
            } else {
                continue;
            }
            if (value == wanted) count++;
        }
        return count;
    }

    private static List<String> annotationStrings(MethodNode method) {
        ArrayList<String> result = new ArrayList<>();
        collectAnnotations(result, method.visibleAnnotations);
        collectAnnotations(result, method.invisibleAnnotations);
        return result;
    }

    private static void collectAnnotations(List<String> result,
                                           List<AnnotationNode> annotations) {
        if (annotations == null) return;
        for (AnnotationNode annotation : annotations) {
            result.add(annotation.desc);
            collectAnnotationValues(result, annotation.values);
        }
    }

    private static void collectAnnotationValues(List<String> result, List<Object> values) {
        if (values == null) return;
        for (Object value : values) {
            if (value instanceof String string) result.add(string);
            else if (value instanceof AnnotationNode annotation) {
                result.add(annotation.desc);
                collectAnnotationValues(result, annotation.values);
            } else if (value instanceof List<?> list) {
                for (Object nested : list) {
                    if (nested instanceof String string) result.add(string);
                    else if (nested instanceof AnnotationNode annotation) {
                        result.add(annotation.desc);
                        collectAnnotationValues(result, annotation.values);
                    }
                }
            }
        }
    }
}
