package com.axalotl.fabrolia.common.regions;

import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static com.axalotl.fabrolia.common.regions.AsyncIslandPacketPolicy.Lane.ISLAND_FIFO;
import static com.axalotl.fabrolia.common.regions.AsyncIslandPacketPolicy.Lane.MAIN_FIFO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact, two-way Minecraft 26.3 audit of the A5 packet-ownership policy. */
class AsyncIslandPacketPolicyTest {

    private static final String EXPECTED = """
            net.minecraft.network.protocol.game.ServerboundPlayerInputPacket|I
            net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket|I
            net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket|I
            net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket|I
            net.minecraft.network.protocol.game.ServerboundRecipeBookSeenRecipePacket|I
            net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket|I
            net.minecraft.network.protocol.game.ServerboundRecipeBookChangeSettingsPacket|I
            net.minecraft.network.protocol.game.ServerboundSeenAdvancementsPacket|I
            net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket|M
            net.minecraft.network.protocol.game.ServerboundSetCommandBlockPacket|M
            net.minecraft.network.protocol.game.ServerboundSetCommandMinecartPacket|M
            net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket|M
            net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket|M
            net.minecraft.network.protocol.game.ServerboundRenameItemPacket|I
            net.minecraft.network.protocol.game.ServerboundSetBeaconPacket|I
            net.minecraft.network.protocol.game.ServerboundSetGameRulePacket|M
            net.minecraft.network.protocol.game.ServerboundSetStructureBlockPacket|M
            net.minecraft.network.protocol.game.ServerboundSetTestBlockPacket|M
            net.minecraft.network.protocol.game.ServerboundTestInstanceBlockActionPacket|M
            net.minecraft.network.protocol.game.ServerboundSetJigsawBlockPacket|M
            net.minecraft.network.protocol.game.ServerboundJigsawGeneratePacket|M
            net.minecraft.network.protocol.game.ServerboundSelectTradePacket|I
            net.minecraft.network.protocol.game.ServerboundEditBookPacket|M
            net.minecraft.network.protocol.game.ServerboundEntityTagQueryPacket|M
            net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket|I
            net.minecraft.network.protocol.game.ServerboundBlockEntityTagQueryPacket|M
            net.minecraft.network.protocol.game.ServerboundMovePlayerPacket|I
            net.minecraft.network.protocol.game.ServerboundPlayerActionPacket|I
            net.minecraft.network.protocol.game.ServerboundUseItemOnPacket|M
            net.minecraft.network.protocol.game.ServerboundUseItemPacket|I
            net.minecraft.network.protocol.game.ServerboundTeleportToEntityPacket|M
            net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket|I
            net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket|I
            net.minecraft.network.protocol.game.ServerboundChatPacket|M
            net.minecraft.network.protocol.game.ServerboundChatCommandPacket|M
            net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket|M
            net.minecraft.network.protocol.game.ServerboundChatAckPacket|M
            net.minecraft.network.protocol.game.ServerboundPunchPacket|I
            net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket|I
            net.minecraft.network.protocol.ping.ServerboundPingRequestPacket|I
            net.minecraft.network.protocol.game.ServerboundAttackPacket|I
            net.minecraft.network.protocol.game.ServerboundInteractPacket|I
            net.minecraft.network.protocol.game.ServerboundSpectatorActionPacket|M
            net.minecraft.network.protocol.game.ServerboundClientCommandPacket|M
            net.minecraft.network.protocol.game.ServerboundContainerClosePacket|I
            net.minecraft.network.protocol.game.ServerboundContainerClickPacket|I
            net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket|I
            net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket|I
            net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket|I
            net.minecraft.network.protocol.game.ServerboundSignUpdatePacket|M
            net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket|I
            net.minecraft.network.protocol.common.ServerboundClientInformationPacket|M
            net.minecraft.network.protocol.game.ServerboundChangeDifficultyPacket|M
            net.minecraft.network.protocol.game.ServerboundChangeGameModePacket|M
            net.minecraft.network.protocol.game.ServerboundLockDifficultyPacket|M
            net.minecraft.network.protocol.game.ServerboundChatSessionUpdatePacket|M
            net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket|M
            net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket|I
            net.minecraft.network.protocol.game.ServerboundDebugSubscriptionRequestPacket|I
            net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket|M
            net.minecraft.network.protocol.game.ServerboundClientTickEndPacket|I
            net.minecraft.network.protocol.common.ServerboundKeepAlivePacket|I
            net.minecraft.network.protocol.common.ServerboundPongPacket|I
            net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket|M
            net.minecraft.network.protocol.common.ServerboundResourcePackPacket|M
            net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket|M
            """;

    @Test
    void exactMinecraft263SurfaceAndEveryPolicyRowArePinnedBothWays() throws Exception {
        byte[] gameBytes = classBytes(ServerGamePacketListenerImpl.class);
        byte[] commonBytes = classBytes(ServerCommonPacketListenerImpl.class);
        assertEquals("b963daf1a78106f08397f54c3fdefeda512a65b8f058031394c99b0e84a4ca8c",
                sha256(gameBytes), "ServerGamePacketListenerImpl is not the audited 26.3 class");
        assertEquals("e5f7229b060a9f50b5754e4c1a9d371af835377f5cb230f30167362e44ae30f4",
                sha256(commonBytes), "ServerCommonPacketListenerImpl is not the audited 26.3 class");

        Set<Class<?>> actualSurface = effectivePacketParameterTypes(
                read(gameBytes), read(commonBytes));
        Map<Class<?>, AsyncIslandPacketPolicy.Lane> expected = parseExpected();
        Map<Class<?>, AsyncIslandPacketPolicy.Lane> expectedWire =
                expectedWirePolicies(expected);
        Map<Class<?>, AsyncIslandPacketPolicy.Lane> production =
                AsyncIslandPacketPolicy.auditedPolicies();

        assertEquals(66, actualSurface.size());
        assertEquals(actualSurface, expected.keySet(),
                "a 26.3 handler parameter was added/removed without a policy review");
        assertEquals(expectedWire.keySet(), production.keySet(),
                "production has a missing or stale concrete wire-class policy row");
        assertEquals(expectedWire, production,
                "a reviewed concrete ISLAND_FIFO/MAIN_FIFO decision changed");
        for (Map.Entry<Class<?>, AsyncIslandPacketPolicy.Lane> entry : expected.entrySet()) {
            if (entry.getKey() == ServerboundMovePlayerPacket.class) {
                assertSame(MAIN_FIFO, AsyncIslandPacketPolicy.classifyType(entry.getKey()),
                        "the abstract handler base is not a wire packet");
            } else {
                assertSame(entry.getValue(), AsyncIslandPacketPolicy.classifyType(entry.getKey()),
                        entry.getKey().getName());
            }
        }

        assertEquals(69, production.size());
    }

    @Test
    void policyIsExactClassAndUnknownEntriesFailClosedToMain() {
        assertSame(MAIN_FIFO, AsyncIslandPacketPolicy.classifyType(null));
        assertSame(MAIN_FIFO, AsyncIslandPacketPolicy.classify(null));
        assertSame(MAIN_FIFO, AsyncIslandPacketPolicy.classify(new UnknownPacket()));
        assertTrueMain(new UnknownPacket());
        assertSame(MAIN_FIFO, AsyncIslandPacketPolicy.classifyType(String.class));
        assertSame(MAIN_FIFO,
                AsyncIslandPacketPolicy.classifyType(ClientboundGameEventPacket.class),
                "even a real but non-audited Minecraft packet must fail closed");
        assertFalse(AsyncIslandPacketPolicy.auditedPacketTypes().contains(String.class));
    }

    @Test
    void allConcreteMovePlayerWirePacketsStayOnTheIslandButUnknownSubclassesFailClosed() {
        Set<Class<?>> exactMinecraft263WireTypes = Set.of(
                ServerboundMovePlayerPacket.Pos.class,
                ServerboundMovePlayerPacket.Rot.class,
                ServerboundMovePlayerPacket.PosRot.class,
                ServerboundMovePlayerPacket.StatusOnly.class);
        for (Class<?> type : exactMinecraft263WireTypes) {
            assertSame(ISLAND_FIFO, AsyncIslandPacketPolicy.classifyType(type), type.getName());
        }
        assertSame(MAIN_FIFO, AsyncIslandPacketPolicy.classifyType(UnknownMovePacket.class),
                "a modded fifth movement subclass must not inherit island authority");
    }
    @Test
    void ordinaryEntityInteractionsDoNotCreateAPhysicalMainBoundary() {
        assertSame(ISLAND_FIFO,
                AsyncIslandPacketPolicy.classifyType(ServerboundAttackPacket.class));
        assertSame(ISLAND_FIFO,
                AsyncIslandPacketPolicy.classifyType(ServerboundInteractPacket.class));
    }

    @Test
    void blockUseRequiresSynchronousMainWhileAirUseRetainsIslandCooldown() {
        assertSame(MAIN_FIFO,
                AsyncIslandPacketPolicy.classifyType(ServerboundUseItemOnPacket.class));
        assertSame(ISLAND_FIFO,
                AsyncIslandPacketPolicy.classifyType(ServerboundUseItemPacket.class));
    }


    private static void assertTrueMain(Packet<?> packet) {
        assertTrue(AsyncIslandPacketPolicy.usesMainFifo(packet));
        assertFalse(AsyncIslandPacketPolicy.usesIslandFifo(packet));
    }

    private static final class UnknownPacket implements Packet<PacketListener> {
        @Override
        public PacketType<? extends Packet<PacketListener>> type() {
            return null;
        }

        @Override
        public void handle(PacketListener listener) {}
    }

    private static final class UnknownMovePacket extends ServerboundMovePlayerPacket {
        private UnknownMovePacket() {
            super(0.0, 0.0, 0.0, 0.0F, 0.0F,
                    true, false, true, false);
        }

        @Override
        public PacketType<? extends ServerboundMovePlayerPacket> type() {
            return null;
        }

        @Override
        public void handle(ServerGamePacketListener listener) {}
    }

    @Test
    void requiredMainBoundariesCannotRegressIntoTheIslandAllowList() throws Exception {
        Map<Class<?>, AsyncIslandPacketPolicy.Lane> expected = parseExpected();
        Set<String> requiredMainSimpleNames = Set.of(
                "ServerboundClientCommandPacket",
                "ServerboundConfigurationAcknowledgedPacket",
                "ServerboundSpectatorActionPacket",
                "ServerboundTeleportToEntityPacket",
                "ServerboundChatPacket",
                "ServerboundChatCommandPacket",
                "ServerboundChatCommandSignedPacket",
                "ServerboundChatAckPacket",
                "ServerboundChatSessionUpdatePacket",
                "ServerboundCommandSuggestionPacket",
                "ServerboundSetCommandBlockPacket",
                "ServerboundSetCommandMinecartPacket",
                "ServerboundSetStructureBlockPacket",
                "ServerboundSetTestBlockPacket",
                "ServerboundTestInstanceBlockActionPacket",
                "ServerboundSetJigsawBlockPacket",
                "ServerboundJigsawGeneratePacket",
                "ServerboundSetGameRulePacket",
                "ServerboundChangeDifficultyPacket",
                "ServerboundChangeGameModePacket",
                "ServerboundLockDifficultyPacket",
                "ServerboundEntityTagQueryPacket",
                "ServerboundBlockEntityTagQueryPacket",
                "ServerboundPickItemFromBlockPacket",
                "ServerboundPickItemFromEntityPacket",
                "ServerboundCustomPayloadPacket"
        );
        for (Map.Entry<Class<?>, AsyncIslandPacketPolicy.Lane> entry : expected.entrySet()) {
            if (requiredMainSimpleNames.contains(entry.getKey().getSimpleName())) {
                assertSame(MAIN_FIFO, entry.getValue(), entry.getKey().getName());
            }
        }
        assertEquals(requiredMainSimpleNames.size(), expected.keySet().stream()
                .filter(type -> requiredMainSimpleNames.contains(type.getSimpleName())).count());
    }

    private static Map<Class<?>, AsyncIslandPacketPolicy.Lane> parseExpected()
            throws ClassNotFoundException {
        Map<Class<?>, AsyncIslandPacketPolicy.Lane> result = new LinkedHashMap<>();
        for (String row : EXPECTED.strip().split("\\R")) {
            String[] columns = row.strip().split("\\|");
            Class<?> type = Class.forName(columns[0], false,
                    AsyncIslandPacketPolicyTest.class.getClassLoader());
            AsyncIslandPacketPolicy.Lane old = result.put(type,
                    columns[1].equals("I") ? ISLAND_FIFO : MAIN_FIFO);
            assertEquals(null, old, "duplicate expected row " + row);
        }
        return Map.copyOf(result);
    }

    private static Map<Class<?>, AsyncIslandPacketPolicy.Lane> expectedWirePolicies(
            Map<Class<?>, AsyncIslandPacketPolicy.Lane> handlerPolicies) {
        Map<Class<?>, AsyncIslandPacketPolicy.Lane> result =
                new LinkedHashMap<>(handlerPolicies);
        assertSame(ISLAND_FIFO, result.remove(ServerboundMovePlayerPacket.class));
        result.put(ServerboundMovePlayerPacket.Pos.class, ISLAND_FIFO);
        result.put(ServerboundMovePlayerPacket.Rot.class, ISLAND_FIFO);
        result.put(ServerboundMovePlayerPacket.PosRot.class, ISLAND_FIFO);
        result.put(ServerboundMovePlayerPacket.StatusOnly.class, ISLAND_FIFO);
        return Map.copyOf(result);
    }

    private static Set<Class<?>> effectivePacketParameterTypes(
            ClassNode game, ClassNode common) throws ClassNotFoundException {
        Map<String, Class<?>> declared = publicPacketHandlers(game);
        Map<String, Class<?>> inherited = publicPacketHandlers(common);
        inherited.keySet().removeAll(declared.keySet());
        Map<String, Class<?>> effective = new TreeMap<>(declared);
        effective.putAll(inherited);
        assertEquals(61, declared.size());
        assertEquals(5, inherited.size());
        assertEquals(66, effective.size());
        return Set.copyOf(effective.values());
    }

    private static Map<String, Class<?>> publicPacketHandlers(ClassNode owner)
            throws ClassNotFoundException {
        Map<String, Class<?>> result = new TreeMap<>();
        for (MethodNode method : owner.methods) {
            if ((method.access & Opcodes.ACC_PUBLIC) == 0
                    || (method.access & Opcodes.ACC_STATIC) != 0
                    || !method.name.startsWith("handle")) {
                continue;
            }
            Type[] arguments = Type.getArgumentTypes(method.desc);
            if (arguments.length != 1
                    || arguments[0].getSort() != Type.OBJECT
                    || !arguments[0].getInternalName()
                    .startsWith("net/minecraft/network/protocol/")) {
                continue;
            }
            Class<?> parameter = Class.forName(arguments[0].getClassName(), false,
                    AsyncIslandPacketPolicyTest.class.getClassLoader());
            result.put(method.name + method.desc, parameter);
        }
        return result;
    }

    private static byte[] classBytes(Class<?> type) throws Exception {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            assertNotNull(in, "missing " + resource);
            return in.readAllBytes();
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode result = new ClassNode();
        new ClassReader(bytes).accept(result, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return result;
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }
}
