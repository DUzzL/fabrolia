package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact actor-correction wiring that prevents a block-prediction ACK overtake. */
class PredictedBlockCorrectionWiringTest {

    private static final String A5 =
            "com/axalotl/fabrolia/common/regions/AsyncIslandPlayerTicks";
    private static final String PLAYER_STATE = A5 + "$PlayerState";
    private static final String PENDING_LATE = A5 + "$PendingLate";
    private static final String CORRECTION = A5 + "$PredictedBlockChange";
    private static final String OWNER_STAMP =
            "com/axalotl/fabrolia/common/regions/IslandPlayerTurnSession$OwnerStamp";
    private static final String LISTENER =
            "net/minecraft/server/network/ServerGamePacketListenerImpl";
    private static final String BLOCK_PACKET =
            "net/minecraft/network/protocol/game/ClientboundBlockUpdatePacket";
    private static final String CACHE_MIXIN =
            "com/axalotl/fabrolia/common/mixin/server/ServerChunkCacheMixin";
    private static final String INTERACTION_MIXIN =
            "com/axalotl/fabrolia/common/mixin/server/PlayerInteractionOwnerMixin";
    private static final String SERVER_LEVEL_MIXIN =
            "com/axalotl/fabrolia/common/mixin/world/ServerLevelMixin";
    private static final String REGIONIZED_SERVER =
            "com/axalotl/fabrolia/common/regions/RegionizedServer";
    private static final String REGION_SCHEDULER =
            "com/axalotl/fabrolia/common/regions/RegionScheduler";
    private static final String P_ISLAND =
            "com/axalotl/fabrolia/common/regions/IslandRegistry$PIsland";

    @Test
    void successfulBlockChangedTailAlwaysOffersTheReplayActorCorrectionFirst()
            throws Exception {
        MethodNode hook = method(readClass(CACHE_MIXIN),
                "fabrolia$queueIslandBlockUpdate",
                "(Lnet/minecraft/core/BlockPos;"
                        + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;"
                        + "Lnet/minecraft/server/level/ChunkHolder;)V");

        int correction = callIndex(hook, A5, "recordPredictedBlockChange",
                "(Lnet/minecraft/core/BlockPos;)V");
        int runtime = callIndex(hook,
                "com/axalotl/fabrolia/common/regions/RegionScheduler",
                "forLevel", null);
        assertTrue(correction >= 0 && runtime > correction,
                "the actor journal must not depend on target-island lookup or viewer membership");
        assertEquals(1, callCount(hook, A5, "recordPredictedBlockChange"));
    }

    @Test
    void journalAdmissionAuthenticatesTheExactReplayTurnAndListener()
            throws Exception {
        ClassNode owner = readClass(A5);
        MethodNode record = method(owner, "recordPredictedBlockChange",
                "(Lnet/minecraft/core/BlockPos;)V");

        assertEquals(1, fieldReadCount(record, A5, "PACKET_REPLAY"));
        assertEquals(1, fieldReadCount(record, A5, "TURN"));
        assertEquals(2, callCount(record, "java/lang/ThreadLocal", "get"),
                "the replay capability and its current actor turn must both be present");
        int exactTurn = referenceCompareIndexAfter(record,
                fieldOpcodeIndex(record, Opcodes.GETSTATIC, A5, "PACKET_REPLAY"));
        int listener = callIndex(record, OWNER_STAMP, "listenerIdentity",
                "()Ljava/lang/Object;");
        int pack = callIndex(record, "net/minecraft/core/BlockPos", "asLong", "()J");
        int create = callIndex(record, CORRECTION, "<init>", null);
        int queue = callIndex(record, "java/util/concurrent/ConcurrentLinkedQueue",
                "add", "(Ljava/lang/Object;)Z");
        assertTrue(exactTurn >= 0 && listener > exactTurn
                        && pack > listener && create > pack && queue > create,
                "only the exact replay TurnContext and current listener may publish a correction");
        assertEquals(0, callCount(record, OWNER_STAMP, "matchesExactly"));
        assertEquals(1, callCount(record,
                "java/util/concurrent/ConcurrentLinkedQueue", "add"));

        ClassNode state = readClass(PLAYER_STATE);
        FieldNode journal = field(state, "predictedBlockChanges");
        assertEquals("Ljava/util/concurrent/ConcurrentLinkedQueue;", journal.desc);
        assertTrue((journal.access & Opcodes.ACC_FINAL) != 0,
                "the listener-lifetime journal identity must never be swapped");
        assertNotNull(journal.signature);
        assertTrue(journal.signature.contains("L" + CORRECTION + ";"),
                "the queue must reject unowned bare block positions by type");

        ClassNode entry = readClass(CORRECTION);
        assertTrue((entry.access & Opcodes.ACC_RECORD) != 0,
                "journal entries must be immutable after MPSC publication");
        FieldNode position = field(entry, "position");
        FieldNode stamp = field(entry, "owner");
        assertEquals("J", position.desc);
        assertEquals("L" + OWNER_STAMP + ";", stamp.desc);
        assertTrue((position.access & Opcodes.ACC_FINAL) != 0
                        && (stamp.access & Opcodes.ACC_FINAL) != 0);
    }

    @Test
    void correctionDrainPollsExactOwnerDeduplicatesAndDirectlySends()
            throws Exception {
        MethodNode drain = method(readClass(A5), "sendPredictedBlockCorrections",
                "(L" + PENDING_LATE + ";)V");

        int poll = callIndex(drain, "java/util/concurrent/ConcurrentLinkedQueue",
                "poll", "()Ljava/lang/Object;");
        int ownerMatch = callIndex(drain, OWNER_STAMP, "matchesExactly",
                "(L" + OWNER_STAMP + ";)Z");
        int deduplicate = callIndex(drain, "java/util/Set", "add",
                "(Ljava/lang/Object;)Z");
        int unpack = callIndex(drain, "net/minecraft/core/BlockPos", "of",
                "(J)Lnet/minecraft/core/BlockPos;");
        int allocation = typeIndex(drain, Opcodes.NEW, BLOCK_PACKET);
        int construct = callIndex(drain, BLOCK_PACKET, "<init>", null);
        int send = callIndex(drain, LISTENER, "send",
                "(Lnet/minecraft/network/protocol/Packet;)V");
        assertTrue(poll >= 0 && ownerMatch > poll && deduplicate > ownerMatch
                        && unpack > deduplicate && allocation > unpack
                        && construct > allocation && send > construct,
                "poll -> exact owner -> per-drain dedup -> direct authoritative packet is the "
                        + "only valid actor-correction sequence");
        assertEquals(1, callCount(drain,
                "java/util/concurrent/ConcurrentLinkedQueue", "poll"));
        assertEquals(0, callCount(drain,
                "java/util/concurrent/ConcurrentLinkedQueue", "clear"),
                "clear could erase a concurrently recorded packet mutation");
        assertEquals(0, callCount(drain, "java/util/Collection", "clear"));
        assertEquals(1, callCount(drain, LISTENER, "send"));
    }

    

    @Test
    void targetProofAuthenticatesReplayThenComparesExactCurrentIslandIdentity()
            throws Exception {
        ClassNode a5 = readClass(A5);
        MethodNode target = method(a5, "ownsPacketInteractionTarget",
                "(Lnet/minecraft/server/level/ServerPlayer;J)Z");
        int replay = callIndex(target, A5, "inPacketReplay",
                "(Lnet/minecraft/server/level/ServerPlayer;)Z");
        int turn = fieldOpcodeIndex(target, Opcodes.GETSTATIC, A5, "TURN");
        int turnGet = callIndexAfter(target, "java/lang/ThreadLocal", "get",
                "()Ljava/lang/Object;", turn);
        int runtime = callIndex(target,
                "com/axalotl/fabrolia/common/regions/RegionScheduler",
                "forLevel", null);
        int targetOwner = callIndex(target,
                "com/axalotl/fabrolia/common/regions/RegionizedServer",
                "islandOwnerOfChunk", "(J)Lcom/axalotl/fabrolia/common/regions/IslandRegistry$PIsland;");
        int claimedIsland = fieldOpcodeIndexAfter(target, Opcodes.GETFIELD,
                A5 + "$TurnContext", "island", targetOwner);
        int exactCompare = referenceCompareIndexAfter(target, claimedIsland);
        assertTrue(replay >= 0 && turn > replay && turnGet > turn
                        && runtime > turnGet && targetOwner > runtime
                        && claimedIsland > targetOwner && exactCompare > claimedIsland,
                "the helper must authenticate replay/player/OwnerStamp first, reject dead "
                        + "runtime claims, then compare target owner by island identity");
        assertEquals(1, callCount(target, A5, "inPacketReplay"));
        assertEquals(1, callCount(target,
                "com/axalotl/fabrolia/common/regions/RegionizedServer",
                "islandOwnerOfChunk"));

        MethodNode replayProof = method(a5, "inPacketReplay", "()Z");
        int replayContext = fieldOpcodeIndex(
                replayProof, Opcodes.GETSTATIC, A5, "PACKET_REPLAY");
        int exactTurn = referenceCompareIndexAfter(replayProof, replayContext);
        assertTrue(replayContext >= 0 && exactTurn > replayContext,
                "packet replay must carry the exact current TurnContext capability");
        assertEquals(0, callCount(replayProof, OWNER_STAMP, "matchesExactly"),
                "one immutable TurnContext identity replaces duplicate stamp comparison");
    }

    


    @Test
    void dragonPartMainResolutionUsesALinearizableIndexAndStableSnapshot()
            throws Exception {
        ClassNode level = readClass(SERVER_LEVEL_MIXIN);
        FieldNode parts = field(level, "dragonParts");
        assertEquals("Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;", parts.desc);

        MethodNode init = method(level, "init",
                "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V");
        int partsRead = fieldOpcodeIndex(init, Opcodes.GETFIELD,
                SERVER_LEVEL_MIXIN, "dragonParts");
        int synchronize = callIndex(init,
                "it/unimi/dsi/fastutil/ints/Int2ObjectMaps", "synchronize",
                "(Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;)"
                        + "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;");
        int partsWrite = fieldOpcodeIndexAfter(init, Opcodes.PUTFIELD,
                SERVER_LEVEL_MIXIN, "dragonParts", synchronize);
        assertTrue(partsRead >= 0 && synchronize > partsRead && partsWrite > synchronize,
                "construction must replace the vanilla dragon-part id index with the exact "
                        + "synchronized wrapper used by Main getEntityOrPart resolution");
        assertEquals(1, callCount(init,
                "it/unimi/dsi/fastutil/ints/Int2ObjectMaps", "synchronize"));

        MethodNode snapshot = method(level, "fabrolia$snapshotDragonParts",
                "(Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)"
                        + "Ljava/util/Collection;");
        int monitorEnter = opcodeIndexAfter(snapshot, Opcodes.MONITORENTER, -1);
        int original = callIndex(snapshot,
                "com/llamalad7/mixinextras/injector/wrapoperation/Operation", "call",
                "([Ljava/lang/Object;)Ljava/lang/Object;");
        int copy = callIndex(snapshot, "java/util/List", "copyOf",
                "(Ljava/util/Collection;)Ljava/util/List;");
        int monitorExit = opcodeIndexAfter(snapshot, Opcodes.MONITOREXIT, copy);
        assertTrue(monitorEnter >= 0 && original > monitorEnter && copy > original
                        && monitorExit > copy,
                "dragonParts iteration must copy under the synchronized-map mutex before "
                        + "publishing a lock-free collection snapshot");
        assertTrue(opcodeCount(snapshot, Opcodes.MONITOREXIT) >= 2,
                "the synchronized snapshot must release its exact monitor normally and on throw");
    }

    private static FieldNode field(ClassNode type, String name) {
        return type.fields.stream().filter(candidate -> name.equals(candidate.name))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "missing field " + type.name + "." + name));
    }

    private static MethodNode method(ClassNode type, String name, String descriptor) {
        return type.methods.stream()
                .filter(candidate -> name.equals(candidate.name)
                        && descriptor.equals(candidate.desc))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "missing exact method " + type.name + "." + name + descriptor));
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
                    && (descriptor == null || descriptor.equals(call.desc))) return index;
            index++;
        }
        return -1;
    }

    private static int fieldReadCount(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && owner.equals(field.owner) && name.equals(field.name)) count++;
        }
        return count;
    }

    private static int typeIndex(MethodNode method, int opcode, String descriptor) {
        return typeIndexAfter(method, opcode, descriptor, -1);
    }

    private static int typeCount(MethodNode method, int opcode, String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode type
                    && type.getOpcode() == opcode && descriptor.equals(type.desc)) count++;
        }
        return count;
    }

    private static int typeIndexAfter(MethodNode method, int opcode, String descriptor,
                                      int after) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (index > after && instruction instanceof TypeInsnNode type
                    && type.getOpcode() == opcode && descriptor.equals(type.desc)) return index;
            index++;
        }
        return -1;
    }

    private static int fieldOpcodeIndex(MethodNode method, int opcode,
                                        String owner, String name) {
        return fieldOpcodeIndexAfter(method, opcode, owner, name, -1);
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

    private static boolean hasJumpBetween(MethodNode method, int from, int to) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (index > from && index < to && instruction instanceof JumpInsnNode) return true;
            index++;
        }
        return false;
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        for (AbstractInsnNode previous = instruction.getPrevious(); previous != null;
             previous = previous.getPrevious()) {
            if (previous.getOpcode() >= 0) return previous;
        }
        return null;
    }

    private static ClassNode readClass(String internalName) throws Exception {
        try (InputStream input = PredictedBlockCorrectionWiringTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            assertNotNull(input, "missing compiled class " + internalName);
            ClassNode type = new ClassNode();
            new ClassReader(input).accept(type, 0);
            return type;
        }
    }
}
