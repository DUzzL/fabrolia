package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact 26.3 client-wire and server-body pins for a real-player spear stab. */
class RealPlayerStabWiringTest {

    private static final String PACKET =
            "net/minecraft/network/protocol/game/ServerboundPlayerActionPacket";
    private static final String ACTION = PACKET + "$Action";
    private static final String CLIENT_GAME_MODE =
            "net/minecraft/client/multiplayer/MultiPlayerGameMode";
    private static final String SERVER_LISTENER =
            "net/minecraft/server/network/ServerGamePacketListenerImpl";
    private static final String PIERCING =
            "net/minecraft/world/item/component/PiercingWeapon";

    @Test
    void clientStabWireUsesTheDocumentedDummyPosition() throws Exception {
        MethodNode stab = method(readClass(CLIENT_GAME_MODE), "piercingAttack",
                "(Lnet/minecraft/world/item/component/SwingAnimation;L" + PIERCING + ";)V");
        int carried = callIndex(stab, CLIENT_GAME_MODE,
                "ensureHasSentCarriedItem", "()V");
        int allocation = typeIndex(stab, Opcodes.NEW, PACKET);
        int action = fieldIndexAfter(stab, Opcodes.GETSTATIC, ACTION, "STAB", allocation);
        int zero = fieldIndexAfter(stab, Opcodes.GETSTATIC,
                "net/minecraft/core/BlockPos", "ZERO", action);
        int down = fieldIndexAfter(stab, Opcodes.GETSTATIC,
                "net/minecraft/core/Direction", "DOWN", zero);
        int construct = callIndexAfter(stab, PACKET, "<init>",
                "(L" + ACTION + ";Lnet/minecraft/core/BlockPos;"
                        + "Lnet/minecraft/core/Direction;)V", down);
        int send = callIndexAfter(stab,
                "net/minecraft/client/multiplayer/ClientPacketListener", "send",
                "(Lnet/minecraft/network/protocol/Packet;)V", construct);
        int localAttack = callIndexAfter(stab,
                "net/minecraft/client/player/LocalPlayer", "onAttack", "()V", send);
        int localPost = callIndexAfter(stab,
                "net/minecraft/client/player/LocalPlayer", "postPiercingAttack", "()V",
                localAttack);
        int sound = callIndexAfter(stab, PIERCING, "makeSound",
                "(Lnet/minecraft/world/entity/Entity;)V", localPost);
        assertTrue(carried >= 0 && allocation > carried && action > allocation
                        && zero > action && down > zero && construct > down && send > construct
                        && localAttack > send && localPost > localAttack && sound > localPost,
                "real-player STAB is a self attack whose wire BlockPos.ZERO is only padding");
        assertEquals(1, fieldCount(stab, Opcodes.GETSTATIC, ACTION, "STAB"));
        assertEquals(1, fieldCount(stab, Opcodes.GETSTATIC,
                "net/minecraft/core/BlockPos", "ZERO"));
        assertEquals(1, callCount(stab, PACKET, "<init>",
                "(L" + ACTION + ";Lnet/minecraft/core/BlockPos;"
                        + "Lnet/minecraft/core/Direction;)V"));
    }

    @Test
    void serverStabCaseIgnoresDummyPositionAndAppliesCanonicalLungeToSource()
            throws Exception {
        ClassNode switchMap = readClass(SERVER_LISTENER + "$1");
        MethodNode initializeSwitch = method(switchMap, "<clinit>", "()V");
        int stabConstant = fieldIndex(initializeSwitch, Opcodes.GETSTATIC, ACTION, "STAB");
        int stabOrdinal = callIndexAfter(initializeSwitch, ACTION, "ordinal", "()I",
                stabConstant);
        AbstractInsnNode caseNumber = nextReal(instructionAt(initializeSwitch, stabOrdinal));
        AbstractInsnNode storeCase = nextReal(caseNumber);
        assertTrue(stabConstant >= 0 && stabOrdinal > stabConstant
                        && caseNumber != null && caseNumber.getOpcode() == Opcodes.ICONST_1
                        && storeCase != null && storeCase.getOpcode() == Opcodes.IASTORE,
                "the synthetic 26.3 switch must continue mapping STAB to case one");

        MethodNode handler = method(readClass(SERVER_LISTENER), "handlePlayerAction",
                "(L" + PACKET + ";)V");
        int ensure = callIndex(handler,
                "net/minecraft/network/protocol/PacketUtils", "ensureRunningOnSameThread",
                null);
        int loaded = callIndexAfter(handler, SERVER_LISTENER, "hasClientLoaded", "()Z",
                ensure);
        int dummyRead = callIndexAfter(handler, PACKET, "getPos",
                "()Lnet/minecraft/core/BlockPos;", loaded);
        int activity = callIndexAfter(handler,
                "net/minecraft/server/level/ServerPlayer", "resetLastActionTime", "()V",
                dummyRead);
        int actionRead = callIndexAfter(handler, PACKET, "getAction",
                "()L" + ACTION + ";", activity);

        TableSwitchInsnNode actionSwitch = firstTableSwitch(handler);
        assertEquals(1, actionSwitch.min);
        // 26.3 added CHANGE_DESTROY_DIRECTION (ninth action).
        assertEquals(9, actionSwitch.max);
        int stabCase = handler.instructions.indexOf(actionSwitch.labels.getFirst());
        int nextCase = handler.instructions.indexOf(actionSwitch.labels.get(1));
        int cooldown = callIndexAfter(handler,
                "net/minecraft/server/level/ServerPlayer", "cannotAttackWithItem",
                "(Lnet/minecraft/world/item/ItemStack;I)Z", stabCase);
        int component = fieldIndexAfter(handler, Opcodes.GETSTATIC,
                "net/minecraft/core/component/DataComponents", "PIERCING_WEAPON", cooldown);
        int componentRead = callIndexAfter(handler, "net/minecraft/world/item/ItemStack", "get",
                "(Lnet/minecraft/core/component/DataComponentType;)Ljava/lang/Object;", component);
        int attack = callIndexAfter(handler, PIERCING, "attack",
                "(Lnet/minecraft/world/entity/LivingEntity;"
                        + "Lnet/minecraft/world/entity/EquipmentSlot;)V", componentRead);
        int stabReturn = opcodeIndexAfter(handler, Opcodes.RETURN, attack);
        assertTrue(ensure >= 0 && loaded > ensure && dummyRead > loaded
                        && activity > dummyRead && actionRead > activity
                        && stabCase > actionRead && cooldown > stabCase
                        && component > cooldown && componentRead > component
                        && attack > componentRead && stabReturn > attack
                        && stabReturn < nextCase,
                "case-one STAB must run the canonical cooldown/component/Lunge body only");
        assertEquals(0, variableOpcodeCountBetween(handler, Opcodes.ALOAD, 2,
                stabCase, stabReturn),
                "the STAB case must not consume the packet's dummy BlockPos local");

        AbstractInsnNode cooldownTicks = previousReal(instructionAt(handler, cooldown));
        assertNotNull(cooldownTicks);
        assertEquals(Opcodes.ICONST_5, cooldownTicks.getOpcode(),
                "server authority uses the canonical five-tick spear gate");
        AbstractInsnNode slot = previousReal(instructionAt(handler, attack));
        AbstractInsnNode sourcePlayer = previousReal(slot);
        assertTrue(slot instanceof FieldInsnNode equipment
                        && equipment.getOpcode() == Opcodes.GETSTATIC
                        && "net/minecraft/world/entity/EquipmentSlot".equals(equipment.owner)
                        && "MAINHAND".equals(equipment.name)
                        && sourcePlayer instanceof FieldInsnNode player
                        && player.getOpcode() == Opcodes.GETFIELD
                        && SERVER_LISTENER.equals(player.owner)
                        && "player".equals(player.name),
                "PiercingWeapon.attack must target the listener's source player and main hand");
        assertEquals(1, callCount(handler, PIERCING, "attack",
                "(Lnet/minecraft/world/entity/LivingEntity;"
                        + "Lnet/minecraft/world/entity/EquipmentSlot;)V"));
    }

    private static ClassNode readClass(String internalName) throws Exception {
        try (InputStream input = RealPlayerStabWiringTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            assertNotNull(input, "missing class " + internalName);
            ClassNode type = new ClassNode();
            new ClassReader(input).accept(type, 0);
            return type;
        }
    }

    private static MethodNode method(ClassNode owner, String name, String desc) {
        List<MethodNode> matches = owner.methods.stream()
                .filter(candidate -> name.equals(candidate.name) && desc.equals(candidate.desc))
                .toList();
        assertEquals(1, matches.size(), "expected exact " + owner.name + "." + name + desc);
        return matches.getFirst();
    }

    private static int callCount(MethodNode method, String owner, String name, String desc) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && owner.equals(call.owner)
                    && name.equals(call.name) && desc.equals(call.desc)) count++;
        }
        return count;
    }

    private static int callIndex(MethodNode method, String owner, String name, String desc) {
        return callIndexAfter(method, owner, name, desc, -1);
    }

    private static int callIndexAfter(MethodNode method, String owner, String name,
                                      String desc, int after) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (index > after && instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && (desc == null || desc.equals(call.desc))) return index;
            index++;
        }
        return -1;
    }

    private static int fieldCount(MethodNode method, int opcode, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == opcode
                    && owner.equals(field.owner) && name.equals(field.name)) count++;
        }
        return count;
    }

    private static int fieldIndex(MethodNode method, int opcode, String owner, String name) {
        return fieldIndexAfter(method, opcode, owner, name, -1);
    }

    private static int fieldIndexAfter(MethodNode method, int opcode, String owner,
                                       String name, int after) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (index > after && instruction instanceof FieldInsnNode field
                    && field.getOpcode() == opcode && owner.equals(field.owner)
                    && name.equals(field.name)) return index;
            index++;
        }
        return -1;
    }

    private static int typeIndex(MethodNode method, int opcode, String descriptor) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode type && type.getOpcode() == opcode
                    && descriptor.equals(type.desc)) return index;
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

    private static int variableOpcodeCountBetween(MethodNode method, int opcode, int variable,
                                                  int start, int end) {
        int count = 0;
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (index > start && index < end && instruction instanceof VarInsnNode load
                    && load.getOpcode() == opcode && load.var == variable) count++;
            index++;
        }
        return count;
    }

    private static TableSwitchInsnNode firstTableSwitch(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TableSwitchInsnNode table) return table;
        }
        throw new AssertionError("missing player-action switch");
    }

    private static AbstractInsnNode instructionAt(MethodNode method, int index) {
        return method.instructions.get(index);
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        for (AbstractInsnNode cursor = instruction.getPrevious(); cursor != null;
             cursor = cursor.getPrevious()) {
            if (cursor.getOpcode() >= 0) return cursor;
        }
        return null;
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        for (AbstractInsnNode cursor = instruction.getNext(); cursor != null;
             cursor = cursor.getNext()) {
            if (cursor.getOpcode() >= 0) return cursor;
        }
        return null;
    }
}
