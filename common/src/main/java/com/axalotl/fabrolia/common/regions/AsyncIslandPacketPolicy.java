package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.entity.EntityMovementAccessor;
import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementCaptureOwnership;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.*;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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
    /** Immutable implementation identity, never an interaction callback probe. */
    private static final ClassValue<Boolean> NATIVE_INTERACTION = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            var source = type.getProtectionDomain().getCodeSource();
            var nativeSource = net.minecraft.world.entity.Entity.class.getProtectionDomain().getCodeSource();
            return source != null && nativeSource != null
                    && type.getClassLoader() == net.minecraft.world.entity.Entity.class.getClassLoader()
                    && source.equals(nativeSource);
        }
    };


    public enum Lane { ISLAND_FIFO, MAIN_FIFO }

    private static final Lane I = Lane.ISLAND_FIFO;
    private static final Lane M = Lane.MAIN_FIFO;

    // An admission bound, not a replacement for native movement validation.
    private static final double MAX_LOCAL_MOVEMENT_SPAN = 128.0;
    // EntityGetter inflates by 1e-7, then EntitySectionStorage searches +/-2.
    // This also contains BlockCollisions' floor(bound +/-1e-7) +/-1 halo.
    private static final double MOVEMENT_QUERY_HALO = 2.0 + 1.0E-7;

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
     * Main ingress has no retained movement-footprint certificate. Its source
     * claim cannot cover a destination, lastGood sweep, rollback or vehicle.
     * This is body admission, not FIFO routing: proved island replay stays local.
     */
    static boolean requiresGlobalMainMovement(Packet<?> packet) {
        return packet instanceof ServerboundMovePlayerPacket
                || packet instanceof ServerboundMoveVehiclePacket
                || packet instanceof ServerboundAcceptTeleportationPacket;
    }

    /**
     * Owner-only refinement immediately before packet.handle, never at decoded
     * Netty ingress. Unproven movement footprints, item-use producers, another
     * player's lifecycle and remote fishing hooks owe a whole fenced Main packet,
     * not a continuation appended behind the already reserved island prefix.
     */
    static boolean requiresMainInteraction(Packet<?> packet, ServerPlayer player) {
        if (packet instanceof ServerboundAcceptTeleportationPacket) {
            // Native acknowledgement absSnapTo's awaitingPositionFromClient and
            // replaces lastGood. The MovePlayer certificate neither runs here nor
            // covers that retained destination; hand off before the native prefix.
            return true;
        }
        // Lifecycle/dimension packets already use Main FIFO. The remaining
        // native handlers retain the E/L closure, including already-active use.
        if (requiresGlobalUseProducer(packet, player)
                || !(packet instanceof ServerboundUseItemPacket)
                    && !(packet instanceof ServerboundUseItemOnPacket)
                    && AdvancementCaptureOwnership.requiresGlobalPlayerTickEnvelope(
                            player, player.isUsingItem())) return true;
        if (packet instanceof ServerboundMoveVehiclePacket) {
            // A5 excludes vehicles/passengers. Do not infer a vehicle claim from
            // the rider or use player.lastGood in place of vehicleLastGood.
            return true;
        }
        if (packet instanceof ServerboundMovePlayerPacket movement) {
            return !ownsMovementFootprint(movement, player);
        }
        if (packet instanceof ServerboundUseItemPacket use) {
            return player.fishing != null
                    && player.getItemInHand(use.getHand()).getItem() instanceof FishingRodItem;
        }
        if (packet instanceof ServerboundInteractPacket interact) {
            if (!(player.level() instanceof ServerLevel level)) return true;
            var target = level.getEntityOrPart(interact.entityId());
            if (target == null) return false;
            // The entire originating packet moves before any target/item/Fabric
            // interaction body. In particular a native target does not certify an
            // opaque held item's interactLivingEntity callback or its ride graph.
            return target instanceof ServerPlayer
                    || requiresEntityInteractionFence(player, target)
                    || !NATIVE_INTERACTION.get(player.getItemInHand(interact.hand()).getItem().getClass());
        }
        if (packet instanceof ServerboundAttackPacket attack) {
            if (!(player.level() instanceof ServerLevel level)) return true;
            var target = level.getEntityOrPart(attack.entityId());
            return target instanceof ServerPlayer
                    || target != null && requiresEntityInteractionFence(player, target);
        }
        return false;
    }
    /**
     * Damage and interaction can reach an NPC's owner, kill credit or breeding
     * player. The target's native closure must be local, not just its class.
     */
    public static boolean requiresEntityInteractionFence(
            ServerPlayer player, net.minecraft.world.entity.Entity target) {
        if (!NATIVE_INTERACTION.get(target.getClass())
                || !NativePlayerObservations.supportsRidingClosure(target)) return true;
        if (!AdvancementCaptureOwnership.mayRequireGlobalEntityInteractionEnvelope()) return false;
        RegionizedServer runtime = RegionScheduler.forLevel(player.level());
        Region region = runtime == null || !(player instanceof RegionKeyed keyed)
                ? null : runtime.regionizer().peekByGridKey(keyed.fabrolia$getRegionKey());
        return region == null || NativeEntityDependencies.requiresFence(runtime, region, target);
    }


    /**
     * Proves columns for the complete native body, without entering collision
     * queries or changing listener state. Invalid input still reaches vanilla
     * exactly once on Main (including infinities that vanilla clamps, not rejects).
     */
    private static boolean ownsMovementFootprint(
            ServerboundMovePlayerPacket packet, ServerPlayer player) {
        if (!AsyncIslandPlayerTicks.inPacketReplay(player)
                || !AsyncIslandPlayerTicks.ownsAdvancementObservation(player)
                || !AsyncIslandPlayerTicks.supportsIslandPlayerType(player)
                || !(player.connection instanceof AsyncIslandMovementListenerAccess listener)
                || !(player instanceof EntityMovementAccessor movement)) return false;

        // Read lastGood only after real player/listener ownership, never at ingress.
        // updateAwaitingTeleport can re-teleport to a retained, non-local position.
        if (listener.fabrolia$hasAwaitingPosition()
                || player.getVehicle() != null || !player.getPassengers().isEmpty()) return false;
        if (!Double.isFinite(packet.getX(0.0))
                || !Double.isFinite(packet.getY(0.0))
                || !Double.isFinite(packet.getZ(0.0))
                || !Float.isFinite(packet.getYRot(0.0F))
                || !Float.isFinite(packet.getXRot(0.0F))) return false;

        Vec3 current = player.position();
        if (!finitePosition(current)) return false;
        double targetX = Mth.clamp(packet.getX(current.x), -3.0E7, 3.0E7);
        double targetY = Mth.clamp(packet.getY(current.y), -2.0E7, 2.0E7);
        double targetZ = Mth.clamp(packet.getZ(current.z), -3.0E7, 3.0E7);
        double dx = targetX - listener.fabrolia$lastGoodX();
        double dy = targetY - listener.fabrolia$lastGoodY();
        double dz = targetZ - listener.fabrolia$lastGoodZ();
        double targetDx = targetX - current.x;
        double targetDy = targetY - current.y;
        double targetDz = targetZ - current.z;
        if (!boundedMovement(dx) || !boundedMovement(dy) || !boundedMovement(dz)
                || !boundedMovement(targetDx) || !boundedMovement(targetDy)
                || !boundedMovement(targetDz)) return false;

        // Native stuck-speed and edge/collision clipping only shorten the sweep.
        // A mod-written multiplier outside that contract cannot use this proof.
        Vec3 multiplier = movement.fabrolia$stuckSpeedMultiplier();
        if (multiplier == null || !nativeSlowdown(multiplier.x)
                || !nativeSlowdown(multiplier.y) || !nativeSlowdown(multiplier.z)) return false;
        float step = player.maxUpStep();
        if (!Float.isFinite(step) || step < 0.0F || step > MAX_LOCAL_MOVEMENT_SPAN) return false;

        AABB box = player.getBoundingBox();
        if (!finiteBox(box) || current.x < box.minX || current.x > box.maxX
                || current.y < box.minY || current.y > box.maxY
                || current.z < box.minZ || current.z > box.maxZ) return false;

        // Entity.move starts at CURRENT but receives target-LAST_GOOD. The
        // target box alone therefore misses the overshoot when those differ.
        // Union current/rollback, that complete sweep, and the requested target.
        // tickCount==0 resetPosition uses target-current, also inside this union.
        double minX = Math.nextDown(box.minX + Math.min(0.0, Math.min(dx, targetDx)) - MOVEMENT_QUERY_HALO);
        double maxX = Math.nextUp(box.maxX + Math.max(0.0, Math.max(dx, targetDx)) + MOVEMENT_QUERY_HALO);
        double minZ = Math.nextDown(box.minZ + Math.min(0.0, Math.min(dz, targetDz)) - MOVEMENT_QUERY_HALO);
        double maxZ = Math.nextUp(box.maxZ + Math.max(0.0, Math.max(dz, targetDz)) + MOVEMENT_QUERY_HALO);
        if (!boundedMovement(maxX - minX) || !boundedMovement(maxZ - minZ)) return false;

        // Claims cover entire columns: step-up, supporting-block/fall queries and
        // noBlocksAround's inflate(.0625).expandTowards(0,-.55,0) are contained.
        // The retained supporting block can precede the packet-start position.
        BlockPos support = player.mainSupportingBlockPos.orElse(null);
        if (support != null && (!AsyncIslandPlayerTicks.ownsPacketInteractionTarget(
                player, ChunkPos.pack(support))
                || !RegionScheduler.ownsCurrentContext(player.level(), support))) return false;
        int minChunkX = Mth.floor(minX) >> 4;
        int maxChunkX = Mth.floor(maxX) >> 4;
        int minChunkZ = Mth.floor(minZ) >> 4;
        int maxChunkZ = Mth.floor(maxZ) >> 4;
        BlockPos.MutableBlockPos column = new BlockPos.MutableBlockPos();
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                if (!AsyncIslandPlayerTicks.ownsPacketInteractionTarget(
                        player, ChunkPos.pack(x, z))
                        || !RegionScheduler.ownsCurrentContext(
                        player.level(), column.set(x << 4, 0, z << 4))) return false;
            }
        }
        return true;
    }

    private static boolean finitePosition(Vec3 position) {
        return position != null && Double.isFinite(position.x)
                && Double.isFinite(position.y) && Double.isFinite(position.z)
                && Math.abs(position.x) <= 3.0E7 && Math.abs(position.y) <= 2.0E7
                && Math.abs(position.z) <= 3.0E7;
    }

    private static boolean boundedMovement(double value) {
        return Double.isFinite(value) && Math.abs(value) <= MAX_LOCAL_MOVEMENT_SPAN;
    }

    private static boolean nativeSlowdown(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }

    private static boolean finiteBox(AABB box) {
        return Double.isFinite(box.minX) && Double.isFinite(box.minY)
                && Double.isFinite(box.minZ) && Double.isFinite(box.maxX)
                && Double.isFinite(box.maxY) && Double.isFinite(box.maxZ)
                && box.minX <= box.maxX && box.minY <= box.maxY && box.minZ <= box.maxZ
                && boundedMovement(box.maxX - box.minX)
                && boundedMovement(box.maxY - box.minY)
                && boundedMovement(box.maxZ - box.minZ);
    }

    /** Owner-only selection: neither use packet requires active use at entry. */
    static boolean requiresGlobalUseProducer(Packet<?> packet, ServerPlayer player) {
        return (packet instanceof ServerboundUseItemPacket
                    || packet instanceof ServerboundUseItemOnPacket)
                && AdvancementCaptureOwnership.requiresGlobalPlayerUseEnvelope(player);
    }

    /** Package-visible immutable audit surface for the two-way 26.2 pin. */
    static Map<Class<?>, Lane> auditedPolicies() {
        return AUDITED_26_2;
    }

    static Set<Class<?>> auditedPacketTypes() {
        return AUDITED_26_2.keySet();
    }
}
