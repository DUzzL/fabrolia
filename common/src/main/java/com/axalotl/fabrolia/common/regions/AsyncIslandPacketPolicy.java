package com.axalotl.fabrolia.common.regions;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.*;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.FishingRodItem;

import java.util.Map;
import java.util.Set;

/**
 * Exact Minecraft 26.2 ingress policy for an A5-owned play listener.
 *
 * <p>The policy is intentionally an exact-class allow-list.  A packet may enter
 * the island FIFO only after its complete vanilla 26.2 handler body has been
 * audited against the player/listener ownership transaction.  New vanilla
 * packet classes, subclasses, loader packets and mod/custom packets therefore
 * fail closed to {@link Lane#MAIN_FIFO} until explicitly reviewed.
 *
 * <p>Island entries are limited to ordinary movement, entity interaction,
 * inventory/menu and listener-local state. Main retains whole block-use
 * transactions, lifecycle/protocol replacement, cross-world spectator operations,
 * global/admin state, command and text-processing chains, world/tag/pick queries, chunk-delivery
 * bookkeeping and every extensible custom entry point.
 *
 * <p>Class literals are deliberate: Loom/NeoForge remap them with the rest of
 * the bytecode.  Comparing named class-name strings would work in development
 * and silently route every packet to Main in a remapped production runtime.
 *
 * <p>This class only decides the ingress venue; it does not relax FIFO order.
 * The caller must classify and commit the result while holding the exact
 * listener {@code PlayerState} monitor. Committing one Main entry creates an
 * ordered boundary: it prevents a new island reservation until every older
 * island packet and that exact consecutive Main-required prefix commit. A later
 * island-safe suffix is then transferred to a new exact owner instead of being
 * dragged through physical Main. In particular, a lifecycle packet cannot
 * coexist with a new {@code tryReserveEarly} claim merely because a later packet
 * class is normally island-safe.
 */
public final class AsyncIslandPacketPolicy {
    public enum Lane { ISLAND_FIFO, MAIN_FIFO }

    private static final Lane I = Lane.ISLAND_FIFO;
    private static final Lane M = Lane.MAIN_FIFO;

    /**
     * One entry for every effective public serverbound handler parameter on
     * {@code ServerGamePacketListenerImpl} in the audited 26.2 artifact:
     * 61 declared handlers plus five effective inherited handlers.
     */
    private static final Map<Class<?>, Lane> AUDITED_26_2 = Map.ofEntries(
            // Player/listener-local movement and ordinary session state.
            Map.entry(ServerboundPlayerInputPacket.class, I),
            Map.entry(ServerboundMoveVehiclePacket.class, I),
            Map.entry(ServerboundAcceptTeleportationPacket.class, I),
            Map.entry(ServerboundPlayerLoadedPacket.class, I),

            // Player-owned recipe book, inventory and menu state.
            Map.entry(ServerboundRecipeBookSeenRecipePacket.class, I),
            Map.entry(ServerboundSelectBundleItemPacket.class, I),
            Map.entry(ServerboundRecipeBookChangeSettingsPacket.class, I),
            Map.entry(ServerboundSeenAdvancementsPacket.class, I),

            // Commands/suggestions and operator-controlled world state stay Main.
            Map.entry(ServerboundCommandSuggestionPacket.class, M),
            Map.entry(ServerboundSetCommandBlockPacket.class, M),
            Map.entry(ServerboundSetCommandMinecartPacket.class, M),

            // Pick/tag requests read arbitrary world state and are not owner-local.
            Map.entry(ServerboundPickItemFromBlockPacket.class, M),
            Map.entry(ServerboundPickItemFromEntityPacket.class, M),

            Map.entry(ServerboundRenameItemPacket.class, I),
            Map.entry(ServerboundSetBeaconPacket.class, I),

            Map.entry(ServerboundSetGameRulePacket.class, M),
            Map.entry(ServerboundSetStructureBlockPacket.class, M),
            Map.entry(ServerboundSetTestBlockPacket.class, M),
            Map.entry(ServerboundTestInstanceBlockActionPacket.class, M),
            Map.entry(ServerboundSetJigsawBlockPacket.class, M),
            Map.entry(ServerboundJigsawGeneratePacket.class, M),

            Map.entry(ServerboundSelectTradePacket.class, I),

            // Text filtering completes asynchronously on the server executor.
            Map.entry(ServerboundEditBookPacket.class, M),
            Map.entry(ServerboundEntityTagQueryPacket.class, M),
            Map.entry(ServerboundContainerSlotStateChangedPacket.class, I),
            Map.entry(ServerboundBlockEntityTagQueryPacket.class, M),

            // Player/world actions execute inside the exact island owner turn.
            // The handler parameter is abstract.  These are the complete concrete
            // 26.2 wire family; keeping the abstract base here made exact lookup send
            // every ordinary movement heartbeat to sticky Main fallback.
            Map.entry(ServerboundMovePlayerPacket.Pos.class, I),
            Map.entry(ServerboundMovePlayerPacket.Rot.class, I),
            Map.entry(ServerboundMovePlayerPacket.PosRot.class, I),
            Map.entry(ServerboundMovePlayerPacket.StatusOnly.class, I),
            Map.entry(ServerboundPlayerActionPacket.class, I),
            // Block use is a synchronous transaction: BlockItem.place must observe
            // its setBlock before importing block-entity contents and consuming the
            // stack. Region containment may defer only setBlock and return true,
            // which otherwise creates an empty placed container. Keep the WHOLE
            // handler (including mod hooks and multi-block placement) on Main;
            // neither the clicked position nor held item bounds its write footprint.
            Map.entry(ServerboundUseItemOnPacket.class, M),
            Map.entry(ServerboundUseItemPacket.class, I),
            // The owner-side refinement below hands this exact unentered packet
            // to globally fenced Main when item-use predicates require it. Type
            // admission alone is not permission to enter a use/start-use body.

            // Spectator/cross-world teleport can replace the spatial owner.
            Map.entry(ServerboundTeleportToEntityPacket.class, M),

            Map.entry(ServerboundPaddleBoatPacket.class, I),
            Map.entry(ServerboundSetCarriedItemPacket.class, I),

            // Signed-message state, command execution and broadcasts stay Main.
            Map.entry(ServerboundChatPacket.class, M),
            Map.entry(ServerboundChatCommandPacket.class, M),
            Map.entry(ServerboundChatCommandSignedPacket.class, M),
            Map.entry(ServerboundChatAckPacket.class, M),

            Map.entry(ServerboundSwingPacket.class, I),
            Map.entry(ServerboundPlayerCommandPacket.class, I),
            Map.entry(ServerboundPingRequestPacket.class, I),
            // Entity ids are client-controlled. The replay wrapper resolves and
            // revalidates the target against the already-claimed player island;
            // stale or foreign ids are benign rejection, never a Main boundary.
            Map.entry(ServerboundAttackPacket.class, I),
            Map.entry(ServerboundInteractPacket.class, I),

            Map.entry(ServerboundSpectatorActionPacket.class, M),
            Map.entry(ServerboundClientCommandPacket.class, M),

            Map.entry(ServerboundContainerClosePacket.class, I),
            Map.entry(ServerboundContainerClickPacket.class, I),
            Map.entry(ServerboundPlaceRecipePacket.class, I),
            Map.entry(ServerboundContainerButtonClickPacket.class, I),
            Map.entry(ServerboundSetCreativeModeSlotPacket.class, I),

            // Sign filtering has the same deferred mutation shape as book text.
            Map.entry(ServerboundSignUpdatePacket.class, M),
            Map.entry(ServerboundPlayerAbilitiesPacket.class, I),

            // Options broadcast, administrative controls and chat identity are global.
            Map.entry(ServerboundClientInformationPacket.class, M),
            Map.entry(ServerboundChangeDifficultyPacket.class, M),
            Map.entry(ServerboundChangeGameModePacket.class, M),
            Map.entry(ServerboundLockDifficultyPacket.class, M),
            Map.entry(ServerboundChatSessionUpdatePacket.class, M),

            // Protocol/listener replacement must complete on physical Main.
            Map.entry(ServerboundConfigurationAcknowledgedPacket.class, M),

            // Island records only a bounded primitive ACK intent. Physical Main
            // drains it at sendNextChunks before touching pacing fields or the
            // Main-owned ChunkDelivery outstanding FIFO.
            Map.entry(ServerboundChunkBatchReceivedPacket.class, I),

            Map.entry(ServerboundDebugSubscriptionRequestPacket.class, I),

            // Mods attach behavior to these extensible entry points.
            Map.entry(ServerboundCustomPayloadPacket.class, M),

            Map.entry(ServerboundClientTickEndPacket.class, I),

            // Effective handlers inherited from ServerCommonPacketListenerImpl.
            Map.entry(ServerboundKeepAlivePacket.class, I),
            Map.entry(ServerboundPongPacket.class, I),
            Map.entry(ServerboundCustomClickActionPacket.class, M),
            Map.entry(ServerboundResourcePackPacket.class, M),
            Map.entry(ServerboundCookieResponsePacket.class, M)
    );

    private AsyncIslandPacketPolicy() {}

    /** Returns the queue that must own this exact packet implementation. */
    public static Lane classify(Packet<?> packet) {
        return packet == null ? M : classifyType(packet.getClass());
    }

    /**
     * Type-level form used by admission code and artifact verification.  Exact
     * lookup (rather than assignability) is the fail-closed boundary.
     */
    public static Lane classifyType(Class<?> packetType) {
        return packetType == null ? M : AUDITED_26_2.getOrDefault(packetType, M);
    }

    public static boolean usesIslandFifo(Packet<?> packet) {
        return classify(packet) == I;
    }

    public static boolean usesMainFifo(Packet<?> packet) {
        return classify(packet) == M;
    }

    /**
     * Owner-only refinement immediately before packet.handle, never at decoded
     * Netty ingress. Another player's lifecycle and remote fishing hooks owe a
     * whole fenced Main packet, not a continuation appended behind the already
     * reserved island prefix.
     */
    static boolean requiresMainInteraction(Packet<?> packet, ServerPlayer player) {
        if (packet instanceof ServerboundUseItemPacket use) {
            return player.fishing != null
                    && player.getItemInHand(use.getHand()).getItem() instanceof FishingRodItem;
        }
        if (packet instanceof ServerboundInteractPacket interact) {
            return player.level() instanceof ServerLevel level
                    && level.getEntityOrPart(interact.entityId()) instanceof ServerPlayer;
        }
        if (packet instanceof ServerboundAttackPacket attack) {
            return player.level() instanceof ServerLevel level
                    && level.getEntityOrPart(attack.entityId()) instanceof ServerPlayer;
        }
        return false;
    }

    /** Package-visible immutable audit surface for the two-way 26.2 pin. */
    static Map<Class<?>, Lane> auditedPolicies() {
        return AUDITED_26_2;
    }

    static Set<Class<?>> auditedPacketTypes() {
        return AUDITED_26_2.keySet();
    }
}
