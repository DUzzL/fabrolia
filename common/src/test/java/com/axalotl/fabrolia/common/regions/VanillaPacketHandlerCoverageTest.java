package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact Minecraft 26.2 packet-entry census. This is deliberately two-way:
 * adding, removing, renaming or changing the descriptor of any handler fails
 * until its vanilla threading/ordering policy is reviewed and classified.
 */
class VanillaPacketHandlerCoverageTest {

    private enum Policy { ENSURED_MAIN, INTENTIONAL_DIRECT }

    private record Expected(String descriptor, Policy policy) {}

    private static final String GAME = "net/minecraft/network/protocol/game/";
    private static final String COMMON = "net/minecraft/network/protocol/common/";

    private static final String DECLARED = """
            handlePlayerInput|net/minecraft/network/protocol/game/ServerboundPlayerInputPacket|E
            handleMoveVehicle|net/minecraft/network/protocol/game/ServerboundMoveVehiclePacket|E
            handleAcceptTeleportPacket|net/minecraft/network/protocol/game/ServerboundAcceptTeleportationPacket|E
            handleAcceptPlayerLoad|net/minecraft/network/protocol/game/ServerboundPlayerLoadedPacket|E
            handleRecipeBookSeenRecipePacket|net/minecraft/network/protocol/game/ServerboundRecipeBookSeenRecipePacket|E
            handleBundleItemSelectedPacket|net/minecraft/network/protocol/game/ServerboundSelectBundleItemPacket|E
            handleRecipeBookChangeSettingsPacket|net/minecraft/network/protocol/game/ServerboundRecipeBookChangeSettingsPacket|E
            handleSeenAdvancements|net/minecraft/network/protocol/game/ServerboundSeenAdvancementsPacket|E
            handleCustomCommandSuggestions|net/minecraft/network/protocol/game/ServerboundCommandSuggestionPacket|E
            handleSetCommandBlock|net/minecraft/network/protocol/game/ServerboundSetCommandBlockPacket|E
            handleSetCommandMinecart|net/minecraft/network/protocol/game/ServerboundSetCommandMinecartPacket|E
            handlePickItemFromBlock|net/minecraft/network/protocol/game/ServerboundPickItemFromBlockPacket|E
            handlePickItemFromEntity|net/minecraft/network/protocol/game/ServerboundPickItemFromEntityPacket|E
            handleRenameItem|net/minecraft/network/protocol/game/ServerboundRenameItemPacket|E
            handleSetBeaconPacket|net/minecraft/network/protocol/game/ServerboundSetBeaconPacket|E
            handleSetGameRule|net/minecraft/network/protocol/game/ServerboundSetGameRulePacket|E
            handleSetStructureBlock|net/minecraft/network/protocol/game/ServerboundSetStructureBlockPacket|E
            handleSetTestBlock|net/minecraft/network/protocol/game/ServerboundSetTestBlockPacket|E
            handleTestInstanceBlockAction|net/minecraft/network/protocol/game/ServerboundTestInstanceBlockActionPacket|E
            handleSetJigsawBlock|net/minecraft/network/protocol/game/ServerboundSetJigsawBlockPacket|E
            handleJigsawGenerate|net/minecraft/network/protocol/game/ServerboundJigsawGeneratePacket|E
            handleSelectTrade|net/minecraft/network/protocol/game/ServerboundSelectTradePacket|E
            handleEditBook|net/minecraft/network/protocol/game/ServerboundEditBookPacket|D
            handleEntityTagQuery|net/minecraft/network/protocol/game/ServerboundEntityTagQueryPacket|E
            handleContainerSlotStateChanged|net/minecraft/network/protocol/game/ServerboundContainerSlotStateChangedPacket|E
            handleBlockEntityTagQuery|net/minecraft/network/protocol/game/ServerboundBlockEntityTagQueryPacket|E
            handleMovePlayer|net/minecraft/network/protocol/game/ServerboundMovePlayerPacket|E
            handlePlayerAction|net/minecraft/network/protocol/game/ServerboundPlayerActionPacket|E
            handleUseItemOn|net/minecraft/network/protocol/game/ServerboundUseItemOnPacket|E
            handleUseItem|net/minecraft/network/protocol/game/ServerboundUseItemPacket|E
            handleTeleportToEntityPacket|net/minecraft/network/protocol/game/ServerboundTeleportToEntityPacket|E
            handlePaddleBoat|net/minecraft/network/protocol/game/ServerboundPaddleBoatPacket|E
            handleSetCarriedItem|net/minecraft/network/protocol/game/ServerboundSetCarriedItemPacket|E
            handleChat|net/minecraft/network/protocol/game/ServerboundChatPacket|D
            handleChatCommand|net/minecraft/network/protocol/game/ServerboundChatCommandPacket|D
            handleSignedChatCommand|net/minecraft/network/protocol/game/ServerboundChatCommandSignedPacket|D
            handleChatAck|net/minecraft/network/protocol/game/ServerboundChatAckPacket|D
            handleAnimate|net/minecraft/network/protocol/game/ServerboundSwingPacket|E
            handlePlayerCommand|net/minecraft/network/protocol/game/ServerboundPlayerCommandPacket|E
            handlePingRequest|net/minecraft/network/protocol/ping/ServerboundPingRequestPacket|D
            handleAttack|net/minecraft/network/protocol/game/ServerboundAttackPacket|E
            handleInteract|net/minecraft/network/protocol/game/ServerboundInteractPacket|E
            handleSpectatorAction|net/minecraft/network/protocol/game/ServerboundSpectatorActionPacket|E
            handleClientCommand|net/minecraft/network/protocol/game/ServerboundClientCommandPacket|E
            handleContainerClose|net/minecraft/network/protocol/game/ServerboundContainerClosePacket|E
            handleContainerClick|net/minecraft/network/protocol/game/ServerboundContainerClickPacket|E
            handlePlaceRecipe|net/minecraft/network/protocol/game/ServerboundPlaceRecipePacket|E
            handleContainerButtonClick|net/minecraft/network/protocol/game/ServerboundContainerButtonClickPacket|E
            handleSetCreativeModeSlot|net/minecraft/network/protocol/game/ServerboundSetCreativeModeSlotPacket|E
            handleSignUpdate|net/minecraft/network/protocol/game/ServerboundSignUpdatePacket|D
            handlePlayerAbilities|net/minecraft/network/protocol/game/ServerboundPlayerAbilitiesPacket|E
            handleClientInformation|net/minecraft/network/protocol/common/ServerboundClientInformationPacket|E
            handleChangeDifficulty|net/minecraft/network/protocol/game/ServerboundChangeDifficultyPacket|E
            handleChangeGameMode|net/minecraft/network/protocol/game/ServerboundChangeGameModePacket|E
            handleLockDifficulty|net/minecraft/network/protocol/game/ServerboundLockDifficultyPacket|E
            handleChatSessionUpdate|net/minecraft/network/protocol/game/ServerboundChatSessionUpdatePacket|E
            handleConfigurationAcknowledged|net/minecraft/network/protocol/game/ServerboundConfigurationAcknowledgedPacket|D
            handleChunkBatchReceived|net/minecraft/network/protocol/game/ServerboundChunkBatchReceivedPacket|E
            handleDebugSubscriptionRequest|net/minecraft/network/protocol/game/ServerboundDebugSubscriptionRequestPacket|E
            handleCustomPayload|net/minecraft/network/protocol/common/ServerboundCustomPayloadPacket|D
            handleClientTickEnd|net/minecraft/network/protocol/game/ServerboundClientTickEndPacket|E
            """;

    private static final String INHERITED = """
            handleKeepAlive|net/minecraft/network/protocol/common/ServerboundKeepAlivePacket|D
            handlePong|net/minecraft/network/protocol/common/ServerboundPongPacket|D
            handleCustomClickAction|net/minecraft/network/protocol/common/ServerboundCustomClickActionPacket|E
            handleResourcePackResponse|net/minecraft/network/protocol/common/ServerboundResourcePackPacket|E
            handleCookieResponse|net/minecraft/network/protocol/cookie/ServerboundCookieResponsePacket|D
            """;

    @Test
    void exactDeclaredHandlersAndThreadPoliciesMatchMinecraft262() throws Exception {
        byte[] bytes = classBytes(ServerGamePacketListenerImpl.class);
        assertEquals("7ae6a2f6b2653b05cfbae93c0e9f40b2a3bfc045f66cbc3bc940bbde0d543c26",
                hex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                "resolved ServerGamePacketListenerImpl is not the audited 26.2 class");

        ClassNode node = read(bytes);
        Map<String, Expected> expected = parse(DECLARED);
        Map<String, MethodNode> actual = publicPacketHandlers(node);
        assertEquals(61, actual.size());
        assertEquals(expected.keySet(), actual.keySet(),
                "new/removed/changed public handler requires explicit classification");

        for (Map.Entry<String, Expected> entry : expected.entrySet()) {
            MethodNode method = actual.get(entry.getKey());
            boolean ensures = callsPacketUtils(method);
            assertEquals(entry.getValue().policy == Policy.ENSURED_MAIN, ensures,
                    entry.getKey() + " vanilla PacketUtils policy changed");
        }
        assertEquals(52, expected.values().stream()
                .filter(e -> e.policy == Policy.ENSURED_MAIN).count());
        assertEquals(9, expected.values().stream()
                .filter(e -> e.policy == Policy.INTENTIONAL_DIRECT).count());
    }

    @Test
    void privateHelpersAndEffectiveInheritedSurfaceAreAlsoPinned() throws Exception {
        ClassNode game = read(classBytes(ServerGamePacketListenerImpl.class));
        Map<String, String> privateHandleMethods = new TreeMap<>();
        for (MethodNode method : game.methods) {
            if (method.name.startsWith("handle") && (method.access & Opcodes.ACC_PUBLIC) == 0) {
                privateHandleMethods.put(method.name, method.desc);
            }
        }
        assertEquals(Map.of(
                "handleMessageDecodeFailure",
                "(Lnet/minecraft/network/chat/SignedMessageChain$DecodeException;)V",
                "handlePlayerKnownMovement", "(Lnet/minecraft/world/phys/Vec3;)V"),
                privateHandleMethods);

        ClassNode common = read(classBytes(ServerCommonPacketListenerImpl.class));
        Map<String, MethodNode> declared = publicPacketHandlers(game);
        Map<String, MethodNode> inherited = publicPacketHandlers(common);
        inherited.keySet().removeAll(declared.keySet());
        Map<String, Expected> expectedInherited = parse(INHERITED);
        assertEquals(expectedInherited.keySet(), inherited.keySet());
        assertEquals(66, declared.size() + inherited.size(),
                "effective public packet surface changed");
        for (Map.Entry<String, Expected> entry : expectedInherited.entrySet()) {
            assertEquals(entry.getValue().policy == Policy.ENSURED_MAIN,
                    callsPacketUtils(inherited.get(entry.getKey())), entry.getKey());
        }
    }

    private static Map<String, Expected> parse(String table) {
        Map<String, Expected> result = new TreeMap<>();
        for (String line : table.strip().split("\\R")) {
            String[] columns = line.strip().split("\\|");
            String descriptor = "(L" + columns[1] + ";)V";
            Expected old = result.put(columns[0] + descriptor,
                    new Expected(descriptor, columns[2].equals("E")
                            ? Policy.ENSURED_MAIN : Policy.INTENTIONAL_DIRECT));
            assertEquals(null, old, "duplicate policy row " + line);
        }
        return result;
    }

    private static Map<String, MethodNode> publicPacketHandlers(ClassNode node) {
        Map<String, MethodNode> result = new TreeMap<>();
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_PUBLIC) != 0
                    && (method.access & Opcodes.ACC_STATIC) == 0
                    && method.name.startsWith("handle")
                    && method.desc.startsWith("(Lnet/minecraft/network/protocol/")
                    && method.desc.endsWith(";)V")) {
                result.put(method.name + method.desc, method);
            }
        }
        return result;
    }

    private static boolean callsPacketUtils(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/network/protocol/PacketUtils")
                    && call.name.equals("ensureRunningOnSameThread")) {
                return true;
            }
        }
        return false;
    }

    private static byte[] classBytes(Class<?> type) throws Exception {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            assertNotNull(in, "missing " + resource);
            return in.readAllBytes();
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }
}
