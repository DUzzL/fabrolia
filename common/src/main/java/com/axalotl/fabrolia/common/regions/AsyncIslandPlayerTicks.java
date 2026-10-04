package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RunningOnDifferentThreadException;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.level.block.NetherPortalBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * A5: player simulation owned by an autonomous async island.
 *
 * <p>This is deliberately separate from {@link RegionalPlayerEntityTicksV2}'s serial
 * exact-ordinal actor. Unsupported owed L bodies rendezvous with Main before entry,
 * retaining their source unit and exact permit. A supported player's turn is one owner transaction:
 *
 * <ol>
 *   <li>claim the exact player/listener/island/lifecycle session and capture the inbound
 *       packet FIFO tail;</li>
 *   <li>replay that prefix on the island owner, then run the ordinary guarded entity
 *       consumer at its region ordinal;</li>
 *   <li>after the island BE phase, run the real listener's complete {@code tick()}
 *       bracket once; Carpet's exact fake subclass already performs doTick in its
 *       combined entity consumer and therefore has no late half.</li>
 * </ol>
 *
 * <p>The MC-free {@link IslandPlayerTurnSession} is the linearization authority.  Once
 * an island body enters, the token can be contained but never retried on Main.
 */
public final class AsyncIslandPlayerTicks {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-A5-Players");
    private static final String CARPET_FAKE = "carpet.patches.EntityPlayerMPFake";

    private static final ThreadLocal<TurnContext> TURN = new ThreadLocal<>();
    private static final ThreadLocal<TurnContext> PACKET_REPLAY = new ThreadLocal<>();

    private static final PacketType<RoutedMutationPacket> ROUTED_MUTATION_TYPE =
            new PacketType<>(PacketFlow.SERVERBOUND,
                    Identifier.fromNamespaceAndPath(
                            "fabrolia", "a5_routed_player_mutation"));

    private static final LongAdder REAL_EARLY = new LongAdder();
    private static final LongAdder REAL_LATE = new LongAdder();
    private static final LongAdder CARPET_COMBINED = new LongAdder();
    private static final LongAdder PACKETS_QUEUED = new LongAdder();
    private static final LongAdder PACKETS_REPLAYED = new LongAdder();
    private static final LongAdder MAIN_FALLBACKS = new LongAdder();
    private static final LongAdder TERMINAL_PRE_BODY_CANCELLATIONS = new LongAdder();
    private static final LongAdder REPLACEMENT_PRE_BODY_CANCELLATIONS = new LongAdder();
    private static final LongAdder POST_PHYSICS_HANDOFFS = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();
    private static final int MAX_ROUTED_MUTATIONS = 4_096;

    private enum MainDisconnectOrigin {
        DEFERRED_OWNER,
        PHYSICAL_MAIN
    }
    private enum ReplayDisposition {
        CONTINUE_ENTITY,
        LIFECYCLE_HANDOFF,
        PACKET_HANDOFF,
        DISCONNECT
    }

    /**
     * A Main stall may let autonomous island cadence publish several distinct player
     * ordinals before physical Main finishes the first one.  Keep that backlog bounded,
     * but never coalesce it: overflow is a fail-stop before publication, not a skipped
     * vanilla body.
     */
    private static final int MAX_OWED_MAIN_ENTITY_ORDINALS = 4_096;

    /** Accepted in-process control work may never disappear as a protocol disconnect. */
    private static final class AcceptedRoutedMutationLoss extends IllegalStateException {
        private AcceptedRoutedMutationLoss(int count) {
            super("A5 disconnect discarded " + count
                    + " accepted routed player mutation(s) before commit");
        }
    }


    private AsyncIslandPlayerTicks() {}

    /**
     * Per-listener ownership state. The first player lifecycle object supplies the
     * storage, then the listener binding keeps that same sequencer across respawn and
     * Carpet shadow replacement. All {@code mainIngress*} fields are guarded by this
     * object's monitor.
     */
    public static final class PlayerState {
        final IslandPlayerTurnSession<InboundPacket> session =
                new IslandPlayerTurnSession<>();
        final AtomicLong nextSessionEpoch = new AtomicLong(1L);
        final ArrayDeque<InboundPacket> mainIngress = new ArrayDeque<>();
        /** Exact command identities retained in FIFO order after terminal clear. */
        final ArrayDeque<Packet<?>> terminalMainCommands = new ArrayDeque<>();
        final ConcurrentLinkedQueue<PredictedBlockChange> predictedBlockChanges =
                new ConcurrentLinkedQueue<>();
        ServerGamePacketListenerImpl listenerGate;
        NetherPortalPlayerAccess.PlayerReservation netherReservation;
        ServerGamePacketListenerImpl terminalDisconnectListener;
        ServerPlayer terminalDisconnectPlayer;
        boolean mainIngressScheduled;
        boolean mainIngressHandlerActive;
        /** Exact unentered interaction held ahead of every later decoded packet. */
        InboundPacket mainInteractionPacket;
        boolean mainEntityFallbackPending;
        boolean mainEntityFallbackAdmitted;
        InboundPacket activeMainEntityFallback;
        int owedMainEntityOrdinals;
        long nextMainEntityOrdinal = 1L;
        /**
         * Published by the authenticated island turn or physical Main under this
         * state monitor, but sampled by the island turn at several fail-fast
         * boundaries where taking the monitor would invert its ownership edge. Volatile is the
         * cross-thread publication contract; compound disconnect transitions remain
         * serialized by the state monitor.
         */
        volatile boolean mainDisconnectPending;
        MainDisconnectOrigin mainDisconnectOrigin;
        int mainExecutionLeases;
        int routedIngressCount;
        long nextRoutedSequence = 1L;
        long causalRoutedCutoff;
        int lastIslandListenerServerTick = Integer.MIN_VALUE;
        ServerPlayer lastIslandListenerPlayer;
        volatile IslandRegistry.PIsland listedIsland;
        volatile boolean eligible;
        /** Exact self-tracker generation and authorized cadence, under this monitor. */
        final TrackingPublicationBinding<IslandTrackedEntityAccess, IslandRegistry.PIsland>
                trackingPublication = new TrackingPublicationBinding<>();
        /** One topology-gap tracker continuation owned by the current Main handoff. */
        TrackingTopologyGap trackingTopologyGap;
        volatile boolean lifecycleInvalidated;
        /** Audited wrapper mutation with an expected lifecycle-epoch advance. */
        volatile boolean auditedLifecycleMutationPending;
        /** Terminal subset of audited lifecycle mutations; never a topology fallback. */
        volatile boolean hardLifecycleExitPending;
        /** Exact post-bump epoch authorized only by the ServerPlayer.die wrapper. */
        volatile long deathLateLifecycleEpoch = -1L;
        /**
         * Exact wrapper-body depth and its structural Main-handoff completion edge.
         * A closer created by a lifecycle mutation is retained here until the last
         * exact wrapper body and its fence cleanup have succeeded. A failed wrapper
         * cancels and rolls back that closer without ever publishing a successor.
         */
        volatile int lifecycleMutationBodyDepth;
        Throwable lifecycleMutationFailure;
        MainHandoffContinuation pendingLifecycleHandoff;
        volatile boolean fallbackEntityAfterHandoff;
    }

    private enum TrackingPublicationDisposition {
        NONE,
        OWED,
        SUPPRESSED
    }

    /**
     * Exact self-tracker obligation transferred with an island-to-Main close.
     * Identity is the receipt: it is installed under tracker -> PlayerState and
     * consumed only after that same session has published MAIN.
     */
    static final class TrackingTopologyGap {
        private final PlayerState state;
        private final ServerPlayer player;
        private final ServerGamePacketListenerImpl listener;
        private final IslandTrackedEntityAccess tracker;
        private final long sessionEpoch;
        /**
         * Monotonic publication state. Lifecycle invalidation may move NONE or
         * OWED to SUPPRESSED; no path may restore a suppressed old generation.
         */
        private TrackingPublicationDisposition publicationDisposition;

        private TrackingTopologyGap(
                PlayerState state, ServerPlayer player,
                ServerGamePacketListenerImpl listener,
                IslandTrackedEntityAccess tracker, long sessionEpoch,
                TrackingPublicationDisposition publicationDisposition) {
            this.state = state;
            this.player = player;
            this.listener = listener;
            this.tracker = tracker;
            this.sessionEpoch = sessionEpoch;
            this.publicationDisposition = publicationDisposition;
        }

        ServerPlayer player() {
            return player;
        }

        IslandTrackedEntityAccess tracker() {
            return tracker;
        }
    }

    /**
     * One listener-lifetime FIFO for packets, entity ordinals,
     * and owed Main listener ticks. Separate queues cannot preserve
     * {@code P0,E1,L1,P1,E2,L2,P2} while an earlier entry is in flight.
     */
    record InboundPacket(Packet<?> packet, PacketListener listener,
                         long routedSequence, boolean entityOrdinal,
                         ServerPlayer fallbackPlayer, long fallbackOrdinal,
                         Runnable listenerTick) {
        InboundPacket(Packet<?> packet, PacketListener listener) {
            this(packet, listener, 0L, false, null, 0L, null);
        }

        InboundPacket(Packet<?> packet, PacketListener listener,
                      long routedSequence) {
            this(packet, listener, routedSequence, false, null, 0L, null);
        }

        static InboundPacket entityOrdinal(ServerGamePacketListenerImpl listener,
                                            ServerPlayer player, long ordinal) {
            return new InboundPacket(null, listener, 0L, true,
                    Objects.requireNonNull(player, "player"), ordinal, null);
        }

        static InboundPacket listenerTick(ServerGamePacketListenerImpl listener, Runnable body) {
            return new InboundPacket(null, listener, 0L, false, null, 0L,
                    Objects.requireNonNull(body, "body"));
        }

        InboundPacket {
            Objects.requireNonNull(listener, "listener");
            if (entityOrdinal) {
                if (packet != null || routedSequence != 0L || fallbackPlayer == null
                        || fallbackOrdinal <= 0L || listenerTick != null) {
                    throw new IllegalArgumentException(
                            "invalid A5 Main entity-ordinal envelope");
                }
            } else if (listenerTick != null) {
                if (packet != null || routedSequence != 0L
                        || fallbackPlayer != null || fallbackOrdinal != 0L) {
                    throw new IllegalArgumentException("invalid A5 Main listener-tick envelope");
                }
            } else if (packet == null || fallbackPlayer != null || fallbackOrdinal != 0L) {
                throw new IllegalArgumentException("invalid A5 packet envelope");
            }
        }

        boolean isRoutedMutation() {
            return !entityOrdinal && routedSequence != 0L;
        }
    }

    /**
     * Optional-mod mutation body. Routed mutations are deliberately committed on
     * physical Main through the listener-lifetime FIFO; the three owner arguments are
     * therefore null. Keeping this shape avoids coupling optional-mod adapters to the
     * packet implementation while making an accidental island commit fail visibly.
     */
    @FunctionalInterface
    public interface RoutedPlayerMutation {
        void run(RegionizedServer runtime, Region region, IslandRegistry.PIsland island);
    }

    /** In-process only; exact-class packet policy intentionally routes it to Main. */
    private record RoutedMutationPacket(PlayerState expectedState,
                                        ServerPlayer expectedPlayer,
                                        String operation,
                                        RoutedPlayerMutation mutation)
            implements Packet<ServerGamePacketListenerImpl> {

        @Override
        public PacketType<? extends Packet<ServerGamePacketListenerImpl>> type() {
            return ROUTED_MUTATION_TYPE;
        }

        @Override
        public void handle(ServerGamePacketListenerImpl listener) {
            synchronized (expectedState) {
                if (expectedState.listenerGate != listener
                        || expectedPlayer.connection != listener
                        || listener.player != expectedPlayer
                        || expectedState.session.view().phase()
                        != IslandPlayerTurnSession.Phase.MAIN
                        || !expectedState.mainIngressScheduled
                        || !expectedState.mainIngressHandlerActive) {
                    throw new IllegalStateException(
                            "A5 routed player mutation '" + operation
                                    + "' lost its exact Main listener session");
                }
            }
            runFencedRoutedMutation(operation, mutation);
        }
    }

    /**
     * Runs one rare physical-Main mutation while every autonomous island is
     * quiescent. Worker callers must publish through the listener FIFO instead;
     * blocking there while holding an island claim would deadlock this fence.
     */
    public static void runFencedMainMutation(String operation, Runnable mutation) {
        Objects.requireNonNull(mutation, "mutation");
        MinecraftServer physicalOwner = ParallelProcessor.getServer();
        if (physicalOwner != null
                && !RegionalPlayerEntityTicksV2.isPhysicalServerThread(physicalOwner)) {
            throw new IllegalStateException(
                    "fenced Main mutation '" + operation + "' entered off physical Main");
        }
        runFencedRoutedMutation(operation,
                (runtime, region, island) -> mutation.run());
    }

    /**
     * Carpet action-pack commands are not uniformly player-local: mount/dismount
     * mutate a ride component and drop spawns an entity. The command wrapper returns
     * as soon as this FIFO entry is accepted, so its original command fence is no
     * longer live when physical Main reaches the body. Re-establish the ordinary
     * all-island mutation window here; this is a rare operator action, never a
     * per-player/per-unit barrier.
     */
    private static void runFencedRoutedMutation(String operation,
                                                RoutedPlayerMutation mutation) {
        boolean asyncLive = IslandCommands.requiresMainFence();
        boolean tookFence = asyncLive && IslandCommands.quiesceAllExcept(null);
        if (asyncLive && !tookFence && !IslandCommands.fenceWindowHeld()) {
            throw new IllegalStateException(
                    "A5 routed player mutation '" + operation
                            + "' could not establish its Main world fence");
        }
        Throwable failure = null;
        try {
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> mutation.run(null, null, null));
        } catch (Throwable thrown) {
            // Select W before releasing the existing command/lifecycle fence.
            failure = publishFatalFailure(thrown);
        }
        if (tookFence) {
            failure = IslandCommands.releaseAllExcept(failure);
        }
        if (failure == null) {
            try {
                RegionScheduler.commitSuccessIfHealthy(() -> {});
            } catch (Throwable peerFailure) {
                failure = publishFatalFailure(peerFailure);
            }
        }
        if (failure != null) throwUnchecked(publishFatalFailure(failure));
    }

    /** Linearizes a physical-Main listener/command body against island attach. */
    private static final class MainExecutionLease {
        private final PlayerState state;
        private final boolean runOriginal;
        private boolean finished;

        private MainExecutionLease(PlayerState state, boolean runOriginal) {
            this.state = state;
            this.runOriginal = runOriginal;
        }


        public Throwable finish(Throwable primary) {
            if (finished) {
                return merge(primary, new IllegalStateException(
                        "A5 Main execution lease finished twice"));
            }
            finished = true;
            if (!runOriginal || state == null) return primary;
            synchronized (state) {
                if (state.mainExecutionLeases <= 0) {
                    return publishFatalFailure(merge(primary, new IllegalStateException(
                            "A5 Main execution lease accounting underflow")));
                }
                state.mainExecutionLeases--;
            }
            return primary;
        }

        /**
         * Execute the exact physical-Main listener body while this admission receipt
         * remains held.  A body failure selects W before {@link #finish(Throwable)}
         * releases the lease; cleanup is then merged behind that same winner.
         */
        public void execute(Runnable body) {
            Objects.requireNonNull(body, "body");
            Throwable failure = null;
            if (runOriginal) {
                try {
                    RegionScheduler.runSuccessBodyIfHealthy(body);
                } catch (Throwable thrown) {
                    failure = state == null ? thrown : publishFatalFailure(thrown);
                }
            }
            try {
                failure = finish(failure);
            } catch (Throwable cleanup) {
                failure = merge(failure, cleanup);
            }
            if (failure == null) {
                try {
                    RegionScheduler.commitSuccessIfHealthy(() -> {});
                } catch (Throwable peerFailure) {
                    failure = state == null
                            ? peerFailure : publishFatalFailure(peerFailure);
                }
            }
            if (failure != null) {
                throwUnchecked(state == null ? failure : publishFatalFailure(failure));
            }
        }

    }

    /** Physical-Main disconnect publication spanning vanilla executeBlocking. */
    public static final class MainDisconnectScope implements AutoCloseable {
        private final PlayerState state;
        private final ServerGamePacketListenerImpl listener;
        private boolean releaseGlobalFence;
        private boolean terminalizationArmed;
        private boolean closed;

        private MainDisconnectScope(PlayerState state,
                                    ServerGamePacketListenerImpl listener) {
            this.state = state;
            this.listener = listener;
        }

        /** Select an entered disconnect-body failure before terminalizing this scope. */
        public void execute(Runnable body) {
            Objects.requireNonNull(body, "body");
            Throwable failure = null;
            try {
                RegionScheduler.runSuccessBodyIfHealthy(body);
            } catch (Throwable thrown) {
                failure = state == null ? thrown : publishFatalFailure(thrown);
            }
            try {
                close();
            } catch (Throwable cleanup) {
                failure = merge(failure, cleanup);
            }
            if (failure == null) {
                try {
                    RegionScheduler.commitSuccessIfHealthy(() -> {});
                } catch (Throwable peerFailure) {
                    failure = state == null
                            ? peerFailure : publishFatalFailure(peerFailure);
                }
            }
            if (failure != null) {
                throwUnchecked(state == null ? failure : publishFatalFailure(failure));
            }
        }

        @Override
        public void close() {
            if (closed) throw new IllegalStateException(
                    "A5 Main disconnect scope closed twice");
            closed = true;
            Throwable failure = null;
            if (terminalizationArmed) {
                try {
                    finishPhysicalMainDisconnect(state, listener);
                } catch (Throwable terminalFailure) {
                    // The fence is mandatory ownership cleanup. Preserve the exact
                    // terminal failure as W, release every island, then rethrow W.
                    failure = state == null
                            ? terminalFailure : publishFatalFailure(terminalFailure);
                }
            }
            if (releaseGlobalFence) {
                failure = IslandCommands.releaseAllExcept(failure);
            }
            if (failure != null) {
                throwUnchecked(state == null ? failure : publishFatalFailure(failure));
            }
        }
    }

    static final class PendingLate {
        final PlayerState state;
        final ServerPlayer player;
        final ServerGamePacketListenerImpl listener;
        final IslandPlayerTurnSession.OwnerStamp owner;
        final IslandPlayerTurnSession.EarlyPermit<InboundPacket> permit;

        PendingLate(PlayerState state, ServerPlayer player,
                    ServerGamePacketListenerImpl listener,
                    IslandPlayerTurnSession.OwnerStamp owner,
                    IslandPlayerTurnSession.EarlyPermit<InboundPacket> permit) {
            this.state = state;
            this.player = player;
            this.listener = listener;
            this.owner = owner;
            this.permit = permit;
        }
    }

    /** Exact state identity prepared before any lifecycle depth is incremented. */
    static final class LifecycleBodyToken {
        final PlayerState state;
        boolean armed;
        MainHandoffContinuation completedHandoff;

        private LifecycleBodyToken(PlayerState state) {
            this.state = state;
        }
    }

    private static final class TurnContext {
        final PlayerState state;
        final ServerPlayer player;
        final IslandRegistry.PIsland island;
        final IslandPlayerTurnSession.OwnerStamp owner;
        final IslandPlayerTurnSession.EarlyPermit<InboundPacket> permit;
        final boolean late;
        boolean serverPlayerTickEntered;
        boolean packetBodyEntered;

        TurnContext(PlayerState state, ServerPlayer player,
                    IslandRegistry.PIsland island,
                    IslandPlayerTurnSession.OwnerStamp owner,
                    IslandPlayerTurnSession.EarlyPermit<InboundPacket> permit,
                    boolean late) {
            this.state = state;
            this.player = player;
            this.island = island;
            this.owner = owner;
            this.permit = permit;
            this.late = late;
        }
    }

    private record PredictedBlockChange(
            long position, IslandPlayerTurnSession.OwnerStamp owner) {}

    public static boolean enabledForAsync() {
        return RegionalPlayerEntityTicksV2.enabled() && RegionScheduler.anyAsyncActive();
    }

    static boolean enabledForAsync(RegionizedServer rs) {
        return rs != null && rs.asyncActive && RegionalPlayerEntityTicksV2.enabled();
    }

    static PlayerState state(ServerPlayer player) {
        // The listener binding, not a player replacement's fresh field, owns ingress.
        ServerGamePacketListenerImpl listener = player == null ? null : player.connection;
        if (listener instanceof AsyncIslandListenerAccess access) {
            PlayerState bound = access.fabrolia$asyncIslandPlayerState();
            if (bound != null) return bound;
        }
        return ((AsyncIslandPlayerAccess) player).fabrolia$asyncIslandPlayerState();
    }

    private static PlayerState bindListenerState(ServerGamePacketListenerImpl listener) {
        AsyncIslandListenerAccess listenerAccess = (AsyncIslandListenerAccess) listener;
        PlayerState bound = listenerAccess.fabrolia$asyncIslandPlayerState();
        if (bound != null) return bound;
        ServerPlayer player = listener.player;
        if (!(player instanceof AsyncIslandPlayerAccess)
                || classifyPlayer(player) == null || !enabledForAsync()) return null;
        return bindListenerIdentity(listener);
    }

    /** Listener-lifetime identity is independent of eligibility for island simulation. */
    private static PlayerState bindListenerIdentity(ServerGamePacketListenerImpl listener) {
        AsyncIslandListenerAccess listenerAccess = (AsyncIslandListenerAccess) listener;
        PlayerState bound = listenerAccess.fabrolia$asyncIslandPlayerState();
        if (bound != null) return bound;
        if (!(listener.player instanceof AsyncIslandPlayerAccess access)) return null;
        listenerAccess.fabrolia$bindAsyncIslandPlayerState(
                access.fabrolia$asyncIslandPlayerState());
        return listenerAccess.fabrolia$asyncIslandPlayerState();
    }

    static boolean hasAttachedOwner(ServerPlayer player) {
        IslandPlayerTurnSession.PlayerKind kind = classifyPlayer(player);
        if (!(player instanceof AsyncIslandPlayerAccess) || kind == null) return false;
        PlayerState state = state(player);
        synchronized (state) {
            IslandPlayerTurnSession.View view = state.session.view();
            IslandPlayerTurnSession.OwnerStamp owner = view.owner();
            return owner != null
                    && owner.playerIdentity() == player
                    && owner.listenerIdentity() == player.connection
                    && owner.kind() == kind
                    && view.phase() != IslandPlayerTurnSession.Phase.MAIN
                    && view.phase() != IslandPlayerTurnSession.Phase.DISCONNECTED;
        }
    }

    /**
     * Main's optional chunk-view reconciliation owns both the player session
     * and its island. Coordinates alone are not authority while an old-owner
     * turn is crossing a boundary. The existing Main execution lease also
     * prevents a new island from rebinding an idle session after our snapshot.
     */
    public static boolean tryRunMainTrackingView(ServerPlayer player, Runnable body) {
        if (physicalMain(player) != MinecraftThreadCheck.YES) {
            throw new IllegalStateException("Main tracking view entered off physical Main");
        }
        PlayerState state = player instanceof AsyncIslandPlayerAccess ? state(player) : null;
        MainExecutionLease lease;
        IslandRegistry.PIsland attached = null;
        if (state == null) {
            lease = new MainExecutionLease(null, true);
        } else {
            synchronized (state) {
                var view = state.session.view();
                if (state.netherReservation != null || state.mainDisconnectPending
                        || (view.phase() != IslandPlayerTurnSession.Phase.MAIN
                            && view.phase() != IslandPlayerTurnSession.Phase.ISLAND_READY)) {
                    return false;
                }
                if (view.phase() == IslandPlayerTurnSession.Phase.ISLAND_READY) {
                    var stamp = view.owner();
                    if (stamp == null || stamp.playerIdentity() != player
                            || stamp.listenerIdentity() != player.connection
                            || !(stamp.islandIdentity() instanceof IslandRegistry.PIsland island)
                            || stamp.islandId() != island.id || stamp.islandGeneration() != island.gen
                            || stamp.domainIdentity() != island.domain || island.retired) {
                        return false;
                    }
                    attached = island;
                }
                if (state.mainExecutionLeases == Integer.MAX_VALUE) {
                    throw new IllegalStateException("A5 Main execution lease count exhausted");
                }
                lease = new MainExecutionLease(state, true);
                state.mainExecutionLeases++;
            }
        }
        IslandRegistry.PIsland owner = attached;
        boolean release = false;
        boolean ran = false;
        Throwable failure = null;
        try {
            RegionizedServer runtime = RegionScheduler.forLevel(player.level());
            if (owner == null && runtime != null) {
                owner = runtime.islandOwnerOfChunk(player.chunkPosition().pack());
            }
            if (owner == null
                    || (runtime != null && runtime.currentThreadHoldsAllClaims())
                    || IslandCommands.mainHolds(owner)) {
                RegionScheduler.runSuccessBodyIfHealthy(body);
                ran = true;
            } else if (IslandCommands.tryMainClaim(owner)) {
                release = true;
                RegionScheduler.runSuccessBodyIfHealthy(body);
                ran = true;
            }
        } catch (Throwable thrown) {
            failure = publishFatalFailure(thrown);
        } finally {
            if (release) {
                try {
                    IslandCommands.mainRelease(owner);
                } catch (Throwable cleanup) {
                    failure = publishFatalFailure(merge(failure, cleanup));
                }
            }
            failure = lease.finish(failure);
        }
        if (failure != null) throwUnchecked(publishFatalFailure(failure));
        RegionScheduler.commitSuccessIfHealthy(() -> {});
        return ran;
    }

    /**
     * Exact island venue which owns tracker publication for this listener lifetime.
     * MAIN_FALLBACK retains an OwnerStamp only as a rollback/ordering receipt; its
     * vanilla body and tracker publication are physical-Main work, so it must not
     * masquerade as an island-tail continuation.
     */
    static IslandRegistry.PIsland trackingPublicationOwner(
            ServerPlayer player, IslandTrackedEntityAccess tracker) {
        IslandPlayerTurnSession.PlayerKind kind = classifyPlayer(player);
        if (!(player instanceof AsyncIslandPlayerAccess)
                || kind == null || tracker == null) return null;
        PlayerState state = state(player);
        synchronized (state) {
            IslandPlayerTurnSession.View view = state.session.view();
            IslandPlayerTurnSession.OwnerStamp owner = view.owner();
            if (owner == null || owner.playerIdentity() != player
                    || owner.listenerIdentity() != player.connection
                    || owner.kind() != kind
                    || !isIslandListenerOwnedPhase(view.phase())
                    || state.mainDisconnectPending
                    || !state.trackingPublication.matches(tracker, owner)) {
                return null;
            }
            return owner.islandIdentity() instanceof IslandRegistry.PIsland island
                    ? island : null;
        }
    }

    /**
     * Transfers a detached self-tracker through the exact already-quiescent A5
     * session close. The rebuild caller owns every island claim and the tracker
     * monitor; no player body is manufactured by this tracker-only handoff.
     */
    static boolean handoffTrackingTopologyGapLocked(
            ServerPlayer player, IslandTrackedEntityAccess tracker) {
        if (player == null || tracker == null || !Thread.holdsLock(tracker)) {
            throw new IllegalStateException(
                    "A5 topology-gap tracker handoff without tracker lock");
        }
        IslandPlayerTurnSession.PlayerKind kind = classifyPlayer(player);
        if (!(player instanceof AsyncIslandPlayerAccess) || kind == null) return false;
        if (!IslandEntityTracking.currentLivePlayerTrackerLocked(tracker, player)
                || tracker.fabrolia$islandTrackingOwner() != null) {
            throw new IllegalStateException(
                    "A5 topology-gap handoff lost its detached live self-tracker");
        }
        if (!(player.level() instanceof ServerLevel level)) {
            throw new IllegalStateException("A5 topology-gap handoff lost its level");
        }
        RegionizedServer runtime = RegionScheduler.forLevel(level);
        if (runtime == null || runtime.level != level
                || physicalMain(player) != MinecraftThreadCheck.YES
                || !runtime.currentThreadHoldsAllClaims()
                || runtime.islandOwnerOfChunk(player.chunkPosition().pack()) != null) {
            throw new IllegalStateException(
                    "A5 topology-gap handoff lacks its physical-Main all-claims gap");
        }

        PlayerState state = state(player);
        synchronized (state) {
            IslandPlayerTurnSession.View view = state.session.view();
            IslandPlayerTurnSession.OwnerStamp owner = view.owner();
            IslandPlayerTurnSession.Phase phase = view.phase();
            ServerGamePacketListenerImpl listener = player.connection;
            if (owner == null) return false;
            if (listener == null || listener.player != player
                    || owner.playerIdentity() != player
                    || owner.listenerIdentity() != listener
                    || owner.kind() != kind
                    || state.listenerGate != listener
                    || !state.trackingPublication.matches(tracker, owner)
                    || state.mainDisconnectPending
                    || state.lifecycleMutationBodyDepth != 0
                    || state.pendingLifecycleHandoff != null
                    || state.auditedLifecycleMutationPending
                    || state.hardLifecycleExitPending
                    || (state.lifecycleInvalidated
                    && phase != IslandPlayerTurnSession.Phase.MAIN_HANDOFF)) {
                throw new IllegalStateException(
                        "A5 topology-gap handoff lost its exact player/session binding");
            }
            if (phase != IslandPlayerTurnSession.Phase.ISLAND_READY
                    && phase != IslandPlayerTurnSession.Phase.MAIN_HANDOFF) {
                throw new IllegalStateException(
                        "A5 topology-gap handoff observed non-quiescent phase " + phase);
            }
            TrackingTopologyGap existing = state.trackingTopologyGap;
            if (existing != null) {
                if (existing.state != state || existing.player != player
                        || existing.listener != listener || existing.tracker != tracker
                        || existing.sessionEpoch != owner.sessionEpoch()) {
                    throw new IllegalStateException(
                            "A5 topology-gap handoff crossed an existing tracker receipt");
                }
                if (tracker.fabrolia$hasPendingIslandPairings()
                        && existing.publicationDisposition
                        != TrackingPublicationDisposition.SUPPRESSED) {
                    existing.publicationDisposition =
                            TrackingPublicationDisposition.OWED;
                }
                return true;
            }
            TrackingTopologyGap receipt = new TrackingTopologyGap(
                    state, player, listener, tracker, owner.sessionEpoch(),
                    TrackingPublicationDisposition.OWED);
            state.trackingTopologyGap = receipt;
            state.eligible = false;
            state.lifecycleInvalidated = true;
            if (phase == IslandPlayerTurnSession.Phase.ISLAND_READY
                    && !scheduleMainHandoffLocked(state, player, false)) {
                state.trackingTopologyGap = null;
                throw new IllegalStateException(
                        "A5 topology-gap tracker could not enter its exact Main handoff");
            }
            return true;
        }
    }

    /**
     * Installs the exact route shield before a post-physics source tracker is
     * detached. Caller owns tracker -> PlayerState; a hard lifecycle exit keeps
     * the receipt only as a monotonic no-publication shield.
     */
    static void handoffPostPhysicsTrackingGapLocked(
            PendingLate pending, IslandTrackedEntityAccess tracker,
            boolean suppressPublication) {
        if (pending == null || tracker == null || !Thread.holdsLock(tracker)
                || !Thread.holdsLock(pending.state)) {
            throw new IllegalStateException(
                    "A5 post-physics tracker handoff without tracker -> state lock");
        }
        PlayerState state = pending.state;
        IslandPlayerTurnSession.View view = state.session.view();
        IslandPlayerTurnSession.OwnerStamp owner = view.owner();
        if (!(pending.owner.islandIdentity() instanceof IslandRegistry.PIsland source)
                || view.phase() != IslandPlayerTurnSession.Phase.LATE_RUNNING
                || owner == null || !owner.matchesExactly(pending.owner)
                || owner.kind() != IslandPlayerTurnSession.PlayerKind.REAL
                || state.listenerGate != pending.listener
                || !state.session.isRunningRealLate(pending.permit)
                || !IslandEntityTracking.currentLivePlayerTrackerLocked(
                tracker, pending.player)
                || !state.trackingPublication.matches(tracker, pending.owner)
                || tracker.fabrolia$islandTrackingOwner() != source
                || !source.pendingEntityUpdates.contains(tracker)) {
            throw new IllegalStateException(
                    "A5 post-physics tracker handoff lost its exact source binding");
        }
        if (!suppressPublication
                && (pending.player.connection != pending.listener
                || state.mainDisconnectPending
                || state.hardLifecycleExitPending
                || classifyPlayer(pending.player) != owner.kind())) {
            throw new IllegalStateException(
                    "A5 publishable post-physics tracker handoff lost live authority");
        }
        if (state.trackingTopologyGap != null) {
            throw new IllegalStateException(
                    "A5 post-physics tracker handoff crossed an existing receipt");
        }
        TrackingPublicationDisposition disposition = suppressPublication
                ? TrackingPublicationDisposition.SUPPRESSED
                : tracker.fabrolia$hasPendingIslandPairings()
                ? TrackingPublicationDisposition.OWED
                : TrackingPublicationDisposition.NONE;
        state.trackingTopologyGap = new TrackingTopologyGap(
                state, pending.player, pending.listener, tracker,
                pending.owner.sessionEpoch(), disposition);
        state.eligible = false;
        state.lifecycleInvalidated = true;
    }

    /** Exact receipt shield while its accepted Main closer owns publication. */
    static boolean ownsTrackingTopologyGap(
            ServerPlayer player, IslandTrackedEntityAccess tracker) {
        if (player == null || tracker == null || !Thread.holdsLock(tracker)
                || !(player instanceof AsyncIslandPlayerAccess)) return false;
        PlayerState state = state(player);
        synchronized (state) {
            TrackingTopologyGap receipt = state.trackingTopologyGap;
            if (receipt == null) return false;
            if (receipt.state != state || receipt.player != player
                    || receipt.tracker != tracker
                    || !state.mainDisconnectPending
                    && receipt.publicationDisposition
                    != TrackingPublicationDisposition.SUPPRESSED
                    && receipt.listener != player.connection) {
                throw new IllegalStateException(
                        "A5 topology-gap publication crossed its exact receipt");
            }
            IslandPlayerTurnSession.View view = state.session.view();
            IslandPlayerTurnSession.Phase phase = view.phase();
            IslandPlayerTurnSession.OwnerStamp owner = view.owner();
            boolean sourcePhase = phase == IslandPlayerTurnSession.Phase.LATE_RUNNING
                    || phase == IslandPlayerTurnSession.Phase.ISLAND_READY;
            boolean handoffPhase = phase == IslandPlayerTurnSession.Phase.MAIN_HANDOFF
                    || phase == IslandPlayerTurnSession.Phase.MAIN_DRAINING;
            boolean ownerPhase = sourcePhase || handoffPhase;
            boolean mainPhase = phase == IslandPlayerTurnSession.Phase.MAIN;
            if (!ownerPhase && !mainPhase
                    || ownerPhase && (owner == null
                    || owner.playerIdentity() != player
                    || owner.listenerIdentity() != receipt.listener
                    || owner.sessionEpoch() != receipt.sessionEpoch
                    || !state.trackingPublication.matches(tracker, owner)
                    || state.eligible || !state.lifecycleInvalidated)
                    || mainPhase && (owner != null
                    || state.trackingPublication.tracker() != null
                    || state.trackingPublication.sessionEpoch() != -1L)) {
                throw new IllegalStateException(
                        "A5 topology-gap receipt lost its Main-close authority");
            }
            // A terminal/hard lifecycle close owns tracker retirement. Until that
            // cleanup commits, suppress stale publication without manufacturing a
            // replacement body for a dead listener.
            if (state.mainDisconnectPending) {
                receipt.publicationDisposition =
                        TrackingPublicationDisposition.SUPPRESSED;
            } else if (receipt.publicationDisposition
                    != TrackingPublicationDisposition.SUPPRESSED) {
                receipt.publicationDisposition = TrackingPublicationDisposition.OWED;
            }
            return true;
        }
    }

    /**
     * Tracker-side terminal barrier for a hard lifecycle close. A pre-close VMP
     * caller holding this tracker observes SUPPRESSED before the receipt is cleared.
     */
    static boolean suppressTrackingTopologyGapIfRequired(
            TrackingTopologyGap receipt) {
        if (receipt == null || !Thread.holdsLock(receipt.tracker)) {
            throw new IllegalStateException(
                    "A5 topology-gap suppression without tracker lock");
        }
        synchronized (receipt.state) {
            IslandPlayerTurnSession.Phase phase = receipt.state.session.view().phase();
            if (receipt.state.trackingTopologyGap != receipt) {
                if (receipt.state.trackingTopologyGap == null
                        && (phase == IslandPlayerTurnSession.Phase.DISCONNECTED
                        || receipt.tracker.fabrolia$islandTrackingState()
                        == IslandTrackedEntityAccess.TRACKING_RETIRED)) {
                    return true;
                }
                throw new IllegalStateException(
                        "A5 terminal topology-gap lost its exact receipt");
            }
            if (receipt.publicationDisposition
                    != TrackingPublicationDisposition.SUPPRESSED) return false;
            if (phase != IslandPlayerTurnSession.Phase.MAIN
                    || receipt.state.trackingPublication.tracker() != null
                    || receipt.state.trackingPublication.sessionEpoch() != -1L) {
                throw new IllegalStateException(
                        "A5 suppressed topology-gap lost its terminal Main binding");
            }
            receipt.state.trackingTopologyGap = null;
            return true;
        }
    }

    /**
     * Read the one-shot body obligation only after the ordered Main listener/entity
     * prefix is terminal. A null result leaves the receipt installed for that
     * prefix's exact completion edge; it is not a retry or a skipped body.
     */
    static Boolean trackingTopologyGapPublicationIfReady(
            TrackingTopologyGap receipt) {
        if (receipt == null || !Thread.holdsLock(receipt.tracker)) {
            throw new IllegalStateException(
                    "A5 topology-gap obligation read without tracker lock");
        }
        synchronized (receipt.state) {
            if (receipt.state.trackingTopologyGap != receipt
                    || receipt.publicationDisposition
                    == TrackingPublicationDisposition.SUPPRESSED
                    || receipt.state.session.view().phase()
                    != IslandPlayerTurnSession.Phase.MAIN
                    || receipt.player.connection != receipt.listener
                    || receipt.listener.player != receipt.player
                    || receipt.state.trackingPublication.tracker() != null
                    || receipt.state.trackingPublication.sessionEpoch() != -1L) {
                throw new IllegalStateException(
                        "A5 topology-gap obligation lost its exact Main receipt");
            }
            boolean netherPrefixComplete = receipt.state.netherReservation != null
                    && receipt.state.netherReservation.player == receipt.player
                    && receipt.state.netherReservation.listener == receipt.listener
                    && receipt.state.mainIngress.peekFirst() == receipt.state.netherReservation.marker;
            if (receipt.state.mainIngressScheduled
                    || receipt.state.mainIngressHandlerActive
                    || receipt.state.mainEntityFallbackPending
                    || receipt.state.mainEntityFallbackAdmitted
                    || receipt.state.activeMainEntityFallback != null
                    || receipt.state.mainDisconnectPending
                    || !receipt.state.mainIngress.isEmpty() && !netherPrefixComplete) {
                return null;
            }
            if (receipt.state.owedMainEntityOrdinals != 0 && !netherPrefixComplete) {
                throw new IllegalStateException(
                        "A5 topology-gap receipt found an unarmed Main entity ordinal");
            }
            return receipt.publicationDisposition
                    == TrackingPublicationDisposition.OWED;
        }
    }

    /** Consume the exact gap receipt after its optional publication attempt. */
    static void completeTrackingTopologyGap(
            TrackingTopologyGap receipt, boolean publicationAttempted) {
        if (receipt == null || !Thread.holdsLock(receipt.tracker)) {
            throw new IllegalStateException(
                    "A5 topology-gap completion without tracker lock");
        }
        synchronized (receipt.state) {
            TrackingPublicationDisposition expected = publicationAttempted
                    ? TrackingPublicationDisposition.OWED
                    : TrackingPublicationDisposition.NONE;
            if (receipt.state.trackingTopologyGap != receipt
                    || receipt.publicationDisposition != expected
                    || receipt.state.session.view().phase()
                    != IslandPlayerTurnSession.Phase.MAIN
                    || receipt.player.connection != receipt.listener
                    || receipt.listener.player != receipt.player
                    || receipt.state.trackingPublication.tracker() != null
                    || receipt.state.trackingPublication.sessionEpoch() != -1L) {
                throw new IllegalStateException(
                        "A5 topology-gap completion lost its exact Main receipt");
            }
            receipt.state.trackingTopologyGap = null;
        }
    }

    /** Compare-clear performed while the retiring exact tracker monitor is held. */
    static void clearTrackingPublicationTracker(
            ServerPlayer player, IslandTrackedEntityAccess tracker) {
        if (!(player instanceof AsyncIslandPlayerAccess) || tracker == null) return;
        PlayerState state = state(player);
        synchronized (state) {
            state.trackingPublication.retire(tracker);
            if (state.trackingTopologyGap != null
                    && state.trackingTopologyGap.tracker == tracker) {
                state.trackingTopologyGap = null;
            }
        }
    }

    /** Exact player ownership predicate used by island entity publication. */
    static boolean ownsTrackingPublication(ServerPlayer player,
                                             IslandRegistry.PIsland island) {
        if (!(player instanceof AsyncIslandPlayerAccess) || island == null) return false;
        PlayerState state = state(player);
        synchronized (state) {
            return ownsTrackingPublicationLocked(state, player, island);
        }
    }

    /** Holds the exact PlayerState edge across the ServerEntity state machine. */
    static boolean runTrackingPublicationIfOwned(ServerPlayer player,
                                                  IslandRegistry.PIsland island,
                                                  Runnable publication) {
        if (!(player instanceof AsyncIslandPlayerAccess) || island == null) return false;
        PlayerState state = state(player);
        synchronized (state) {
            if (!ownsTrackingPublicationLocked(state, player, island)) return false;
            try {
                publication.run();
            } catch (Throwable failure) {
                // ServerEntity publication is an entered vanilla body.  Select the
                // generation winner before releasing the exact PlayerState ownership
                // edge; a later tracker/player publication must not observe a partially
                // advanced tracker state.
                throwUnchecked(publishFatalFailure(failure));
            }
            return true;
        }
    }

    private static boolean ownsTrackingPublicationLocked(
            PlayerState state, ServerPlayer player, IslandRegistry.PIsland island) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException("A5 tracking ownership read without state lock");
        }
        IslandPlayerTurnSession.View view = state.session.view();
        IslandPlayerTurnSession.OwnerStamp owner = view.owner();
        return owner != null
                && owner.playerIdentity() == player
                && owner.listenerIdentity() == player.connection
                && owner.islandIdentity() == island
                && view.phase() == IslandPlayerTurnSession.Phase.ISLAND_READY
                && state.eligible
                && !state.lifecycleInvalidated
                && !state.mainDisconnectPending;
    }

    /** Main must not enter the same tracker while an island/handoff owns it. */
    static boolean shouldDeferTrackingPublication(ServerPlayer player,
                                                   IslandRegistry.PIsland island) {
        if (!(player instanceof AsyncIslandPlayerAccess) || island == null) return false;
        PlayerState state = state(player);
        synchronized (state) {
            IslandPlayerTurnSession.View view = state.session.view();
            IslandPlayerTurnSession.OwnerStamp owner = view.owner();
            return owner != null
                    && owner.playerIdentity() == player
                    && owner.listenerIdentity() == player.connection
                    && owner.islandIdentity() == island
                    && isIslandListenerOwnedPhase(view.phase())
                    && !state.mainDisconnectPending;
        }
    }

    /** Exact supported subclass classification; unknown subclasses remain main-owned. */
    static IslandPlayerTurnSession.PlayerKind classifyPlayer(ServerPlayer player) {
        Class<?> type = player.getClass();
        if (type == ServerPlayer.class) return IslandPlayerTurnSession.PlayerKind.REAL;
        if (CARPET_FAKE.equals(type.getName())) return IslandPlayerTurnSession.PlayerKind.CARPET;
        return null;
    }

    public static boolean isCarpetFake(ServerPlayer player) {
        return player != null && CARPET_FAKE.equals(player.getClass().getName());
    }

    /**
     * Region entity-ordinal admission. Returns false only when the established
     * per-player MAIN_SYNC path may execute this turn. Any older Main FIFO work keeps
     * the admission cut closed and the fallback body is published only after that FIFO.
     */
    public static boolean tryRunEarly(Region region, ServerPlayer player) {
        if (region == null || player == null || !(player.level() instanceof ServerLevel level)) {
            return false;
        }
        RegionizedServer rs = RegionScheduler.forLevel(level);
        IslandRegistry.PIsland island = region.domain == null ? null : region.domain.owner;
        IslandPlayerTurnSession.PlayerKind kind = classifyPlayer(player);
        long currentChunk = player.chunkPosition().pack();
        if (!enabledForAsync(rs) || island == null || island.retired
                || region.domain != island.domain || kind == null
                || region.state() != RegionState.ACTIVE
                || !rs.regionMatches(region, player.chunkPosition())
                || rs.islandOwnerOfChunk(currentChunk) != island
                || !eligible(player, level, kind)) {
            return markMainFallback(player, true);
        }

        PlayerState state = state(player);
        long token = region.domain.tickToken();
        IslandPlayerTurnSession.OwnerStamp owner = null;
        Optional<IslandPlayerTurnSession.EarlyPermit<InboundPacket>> reserved =
                Optional.empty();
        IslandTrackedEntityAccess playerTracker =
                IslandEntityTracking.currentPlayerTracker(player);
        if (playerTracker == null) {
            return markMainFallback(player, true);
        }
        // Tracker publication always nests PlayerState (tracker -> state). Hold the
        // self tracker across owner installation so no sendChanges route can observe
        // a live A5 owner before that tracker has its exact island-tail cadence entry.
        synchronized (playerTracker) {
            if (IslandEntityTracking.currentLivePlayerTrackerLocked(
                    playerTracker, player)) {
                synchronized (state) {
                    // Recheck the identity registry inside both ownership edges. A
                    // replaced/retired tracker declines this turn before owner CAS.
                    if (IslandEntityTracking.currentLivePlayerTrackerLocked(
                            playerTracker, player)) {
                        // Attach/reuse and the turn cutoff are one ownership edge. A
                        // decoded packet, internal mutation, or physical-Main lease
                        // published before this monitor edge is observed before this
                        // island body can reserve.
                        IslandPlayerTurnSession.OwnerStamp priorOwner =
                                state.session.view().owner();
                        owner = ensureOwner(state, player, player.connection, rs,
                                island, region.domain, kind);
                        if (owner != null) {
                            IslandEntityTracking.attachPlayerTrackerAtAdmissionLocked(
                                    playerTracker, player, state, rs, island, owner,
                                    priorOwner);
                            reserved = state.session.tryReserveEarly(owner, token);
                        }
                    }
                }
            }
        }
        if (owner == null) {
            return markMainFallback(player, true);
        }

        if (reserved.isEmpty()) {
            IslandPlayerTurnSession.View observed = state.session.view();
            if (observed.activeTurnToken() == token
                    || (observed.ledger().hasTerminal()
                    && observed.ledger().lastTerminalToken() == token)) {
                return true;
            }
            return markMainFallback(player, true);
        }
        IslandPlayerTurnSession.EarlyPermit<InboundPacket> permit = reserved.get();
        if (!stillCurrent(owner, player, rs, island, region.domain)) {
            Optional<IslandPlayerTurnSession.MainFallback<InboundPacket>> rollback =
                    state.session.tryMoveToMainFallback(permit);
            if (rollback.isEmpty()
                    || !state.session.cancelMainFallbackBeforeBody(rollback.get())) {
                ServerGamePacketListenerImpl sessionListener =
                        owner.listenerIdentity() instanceof ServerGamePacketListenerImpl game
                                ? game : null;
                synchronized (state) {
                    if (consumeExactTerminalPreBodyOrdinalLocked(
                            state, player, sessionListener)) {
                        return true;
                    }
                }
                throw new IllegalStateException(
                        "A5 stale early reservation could not restore its exact token");
            }
            return markMainFallback(player, true);
        }

        TurnContext context = new TurnContext(state, player, island, owner, permit, false);
        Throwable failure = null;
        boolean complete = false;
        boolean bodyEntered = false;
        boolean turnInstalled = false;
        ReplayDisposition replayDisposition = ReplayDisposition.CONTINUE_ENTITY;
        try {
            if (TURN.get() != null) {
                throw new IllegalStateException("nested A5 player turn");
            }
            // PacketProcessor authentication requires both the exact owner turn and
            // the narrower per-envelope replay identity. The reserved FIFO runs
            // before the irreversible entity-body edge. Its first entered packet
            // body is separately terminal: it may be contained or hand the still-
            // owed entity body to Main, but the consumed prefix is never retried.
            TURN.set(context);
            turnInstalled = true;
            replayDisposition = replayReservedPackets(context);
            boolean terminalPreBodyCancellation = false;
            if (replayDisposition == ReplayDisposition.CONTINUE_ENTITY) {
                if (packetSessionChanged(context)) {
                    throw new IllegalStateException(
                            "A5 player session changed before early body");
                }
                synchronized (state) {
                    if (state.mainDisconnectPending) {
                        replayDisposition = ReplayDisposition.DISCONNECT;
                    } else if (explicitLifecycleMutationPending(state)) {
                        replayDisposition = ReplayDisposition.LIFECYCLE_HANDOFF;
                    } else if (!state.session.enterIslandBody(permit)) {
                        ServerGamePacketListenerImpl sessionListener =
                                owner.listenerIdentity()
                                        instanceof ServerGamePacketListenerImpl game
                                                ? game : null;
                        if (consumeExactTerminalPreBodyOrdinalLocked(
                                state, player, sessionListener)) {
                            terminalPreBodyCancellation = true;
                        } else {
                            throw new IllegalStateException(
                                    "A5 early body lost its exact reserved claim");
                        }
                    } else {
                        bodyEntered = true;
                    }
                }
            }
            if (terminalPreBodyCancellation) {
                if (TURN.get() != context) {
                    throw new IllegalStateException(
                            "A5 terminal pre-body cancellation lost its turn context");
                }
                TURN.remove();
                return true;
            }
            if (replayDisposition == ReplayDisposition.CONTINUE_ENTITY) {
                if (!stillCurrent(owner, player, rs, island, region.domain)) {
                    throw new IllegalStateException(
                            "A5 player session changed before early body");
                }
                ParallelProcessor.tickEntityVanillaGuarded(level, player, true);
                if (!context.serverPlayerTickEntered) {
                    throw new IllegalStateException(
                            "A5 guarded consumer did not enter ServerPlayer.tick");
                }
                complete = true;
            }
        } catch (Throwable thrown) {
            // TURN/session cleanup changes admission-visible ownership. Close the
            // global gate first so no peer can start after a partial packet/entity body.
            failure = publishFatalFailure(thrown);
        }
        try {
            if (turnInstalled) {
                if (TURN.get() != context) {
                    throw new IllegalStateException(
                            "A5 early turn context was replaced before cleanup");
                }
                TURN.remove();
            }
        } catch (Throwable cleanup) {
            failure = publishFatalFailure(merge(failure, cleanup));
        }

        if (failure == null
                && (replayDisposition == ReplayDisposition.LIFECYCLE_HANDOFF
                || replayDisposition == ReplayDisposition.PACKET_HANDOFF)) {
            try {
                synchronized (state) {
                    if (state.mainDisconnectPending) {
                        replayDisposition = ReplayDisposition.DISCONNECT;
                    } else if (!schedulePacketPrefixMainHandoffLocked(
                            state, player, permit)) {
                        throw new IllegalStateException(
                                "A5 packet lifecycle cut could not publish its owed Main body");
                    } else {
                        MAIN_FALLBACKS.increment();
                    }
                }
            } catch (Throwable completionFailure) {
                failure = publishFatalFailure(completionFailure);
            }
        }

        if (failure == null && complete) {
            try {
                if (kind == IslandPlayerTurnSession.PlayerKind.CARPET) {
                    completeCarpetCombinedSuccess(state, player, permit);
                } else {
                    PendingLate pendingPublication = new PendingLate(
                            state, player, player.connection, owner, permit);
                    RegionScheduler.commitSuccessIfHealthy(() -> {
                        if (!state.session.completeRealEarly(permit)) {
                            throw new IllegalStateException("real-player A5 early half did not publish");
                        }
                        island.pendingPlayerLate.add(pendingPublication);
                        REAL_EARLY.increment();
                    });
                }
            } catch (Throwable completionFailure) {
                failure = publishFatalFailure(completionFailure);
            }
        }
        if (failure != null) {
            // Completion invariants can fail after the body returned. They select the
            // same first-wins W before exact permit containment and local admission cuts.
            failure = publishFatalFailure(failure);
            try {
                if (bodyEntered) {
                    state.session.containIslandFailure(permit);
                } else if (state.session.containIslandReplayFailure(permit)
                        != IslandPlayerTurnSession.AfterBodyResult
                        .CONTAINED_NO_MAIN_FALLBACK) {
                    IslandPlayerTurnSession.View view = state.session.view();
                    if (!view.ledger().hasTerminal()
                            || view.ledger().lastTerminalToken()
                            != permit.turnToken()) {
                        Optional<IslandPlayerTurnSession.MainFallback<InboundPacket>>
                                rollback = state.session.tryMoveToMainFallback(permit);
                        if (rollback.isEmpty()
                                || !state.session.cancelMainFallbackBeforeBody(
                                rollback.get())) {
                            throw new IllegalStateException(
                                    "A5 failed replay could not contain its exact claim");
                        }
                    }
                }
            } catch (Throwable cleanup) {
                failure = publishFatalFailure(merge(failure, cleanup));
            }
            try {
                state.eligible = false;
                FAILURES.increment();
            } catch (Throwable cleanup) {
                failure = publishFatalFailure(merge(failure, cleanup));
            }
            // The exact selected W escapes this invocation. No recovery handoff or
            // later player body may be published from the failure branch.
            throwUnchecked(publishFatalFailure(failure));
        }
        return true;
    }

    /** Late real-player listener slot: after BEs, before final chunk snapshots. */
    public static void runLate(IslandRegistry.PIsland island) {
        if (island == null) return;
        // Polling and consuming one exact PendingLate token are a single admitted
        // vanilla obligation.  If a peer selects W first, the token stays queued;
        // if this body wins entry, its external permit observes any later peer W
        // before this loop may poll (and therefore start) the next listener tick.
        while (RegionScheduler.callSuccessBooleanIfHealthy(
                () -> runOneLateBody(island))) {
            // fixed point for the already-published late FIFO
        }
    }

    private static boolean runOneLateBody(IslandRegistry.PIsland island) {
            PendingLate pending = island.pendingPlayerLate.poll();
            if (pending == null) return false;
            boolean immutableLateAuthority;
            boolean lifecycleCut;
            boolean hardLifecycleCut;
            boolean deathLateAuthority;
            long deathLateLifecycleEpoch;
            synchronized (pending.state) {
                immutableLateAuthority =
                        pendingRealPlayerBeforeLateInvariantLocked(pending, island);
                lifecycleCut = pending.state.lifecycleInvalidated
                        || pending.state.auditedLifecycleMutationPending
                        || pending.state.hardLifecycleExitPending
                        || pending.state.mainDisconnectPending;
                hardLifecycleCut = pending.state.hardLifecycleExitPending
                        || pending.state.mainDisconnectPending;
                deathLateAuthority = immutableLateAuthority
                        && hardLifecycleCut
                        && pendingDeathLateAuthorityLocked(pending, island);
                deathLateLifecycleEpoch = pending.state.deathLateLifecycleEpoch;
            }
            if (!immutableLateAuthority) {
                throw failPendingLateBeforeListener(pending,
                        "A5 late entry violated immutable PendingLate authority",
                        "A5 invalid PendingLate containment rejected its exact permit");
            }
            if (hardLifecycleCut && !deathLateAuthority) {
                throw failPendingLateBeforeListener(pending,
                        "A5 hard lifecycle transition could not preserve the required "
                                + "listener tick",
                        "A5 hard lifecycle cut could not close PendingLate");
            }
            if (lifecycleCut && !deathLateAuthority) {
                throw failPendingLateBeforeListener(pending,
                        "A5 soft lifecycle transition lost the source-island authority "
                                + "required by the listener tick",
                        "A5 soft lifecycle cut could not close PendingLate");
            }
            if (pending.owner.islandIdentity() != island || isCarpetFake(pending.player)) {
                throw failPendingLateBeforeListener(pending,
                        "A5 late entry targeted the wrong island/player kind",
                        "A5 wrong-owner PendingLate containment rejected its exact permit");
            }
            RegionizedServer rs = RegionScheduler.forLevel(pending.player.level());
            boolean currentForLate = deathLateAuthority
                    ? stillCurrentForDeathLate(pending.owner, pending.player,
                    pending.listener, rs, island, island.domain,
                    deathLateLifecycleEpoch)
                    : stillCurrent(pending.owner, pending.player, rs, island, island.domain);
            if (!currentForLate || pending.player.connection != pending.listener
                    || !pending.state.session.beginRealLate(pending.permit)) {
                throw failPendingLateBeforeListener(pending,
                        "A5 real-player session changed before late listener",
                        "A5 stale PendingLate containment rejected its exact permit");
            }
            TurnContext context = new TurnContext(pending.state, pending.player,
                    island, pending.owner, pending.permit, true);
            Throwable failure = null;
            boolean postPhysicsHandoff = deathLateAuthority;
            try {
                if (TURN.get() != null) throw new IllegalStateException("nested A5 late turn");
                TURN.set(context);
                // Keep the complete listener state machine on one owner. Splitting
                // only tickPlayer left its ack/keepalive/throttler/idle tail running
                // concurrently on physical Main and also lost tickPlayer's early-
                // return semantics. Connection.tick skips this exact listener call
                // while the session is island-owned; its transport/disconnect/flush
                // shell remains on Main.
                // Minecraft 26.2 listener.tick reaches ServerPlayer.doTick via
                // tickPlayer; its Player.tick advances ItemCooldowns exactly once.
                // Keep that clock here: adding a separate Main/island cooldown tick would
                // either reconnect island cadence to Main or halve cooldowns.
                MinecraftServer server = pending.player.level().getServer();
                if (server == null) {
                    throw new IllegalStateException("A5 late listener lost its server");
                }
                synchronized (pending.state) {
                    pending.state.lastIslandListenerServerTick = server.getTickCount();
                    pending.state.lastIslandListenerPlayer = pending.player;
                }
                sendPredictedBlockCorrections(pending);
                pending.listener.tick();
                // doTick is this player's entity body; unlike the regional
                // tickEntity path it has no outer departure-visibility closer.
                // The queued transfer stays reserved, but source tracking must
                // see the real live player after the completed physics body.
                NetherPortalTransfers.completeTickLocalDeparture(pending.player);
                if (!deathLateAuthority) {
                    // Packet replay may have applied a Lunge impulse before the
                    // early entity body. Vanilla publishes player motion only
                    // after listener.tick/doTick physics has consumed that impulse
                    // (including block friction). Keep the exact LATE_RUNNING
                    // permit live across this one self-tracker generation.
                    IslandEntityTracking.PostPhysicsResult publication =
                            IslandEntityTracking.publishPendingRealPlayerAfterPhysics(
                                    pending, island);
                    if (publication == IslandEntityTracking.PostPhysicsResult.INVALID) {
                        throw new IllegalStateException(
                                "A5 post-physics player publication violated exact ownership");
                    }
                    postPhysicsHandoff = publication
                            == IslandEntityTracking.PostPhysicsResult.HANDOFF;
                }
            } catch (Throwable thrown) {
                // listener.tick and post-physics publication are entered simulation.
                // Select W before clearing TURN or touching the exact late permit.
                failure = publishFatalFailure(thrown);
                try {
                    NetherPortalTransfers.abortTickLocalTransfers();
                } catch (Throwable cleanup) {
                    failure = publishFatalFailure(merge(failure, cleanup));
                }
            }
            try {
                if (TURN.get() == context) TURN.remove();
                else if (TURN.get() != null) TURN.remove();
            } catch (Throwable cleanup) {
                failure = publishFatalFailure(merge(failure, cleanup));
            }
            if (failure == null) {
                try {
                    completeRealLateSuccess(pending, postPhysicsHandoff);
                } catch (Throwable completionFailure) {
                    failure = publishFatalFailure(completionFailure);
                }
            }
            if (failure != null) {
                failure = publishFatalFailure(failure);
                try {
                    failure = cleanupKnownListenerFailureState(pending.player, failure);
                    failure = publishFatalFailure(failure);
                } catch (Throwable cleanup) {
                    failure = publishFatalFailure(merge(failure, cleanup));
                }
                try {
                    pending.state.session.containIslandFailure(pending.permit);
                } catch (Throwable cleanup) {
                    failure = publishFatalFailure(merge(failure, cleanup));
                }
                try {
                    synchronized (pending.state) {
                        pending.state.eligible = false;
                        pending.state.lifecycleInvalidated = true;
                    }
                    FAILURES.increment();
                } catch (Throwable cleanup) {
                    failure = publishFatalFailure(merge(failure, cleanup));
                }
                // Stop at the first failed late body. The enclosing island unit
                // performs only mandatory cleanup; this invocation already selected
                // and now rethrows the exact global W before another late stage.
                throwUnchecked(publishFatalFailure(failure));
            }
            return true;
    }

    /**
     * EARLY_DONE already records an entered entity body.  A rejected late permit is
     * therefore a generation-fatal invariant, not a reason to contain locally and
     * continue the island.  Select its exact candidate before any admission-visible
     * cleanup, then merge cleanup only behind that already-selected W.
     */
    private static RuntimeException failPendingLateBeforeListener(
            PendingLate pending, String message, String containmentMessage) {
        Throwable failure = publishFatalFailure(new IllegalStateException(message));
        try {
            pending.state.eligible = false;
        } catch (Throwable cleanup) {
            failure = publishFatalFailure(merge(failure, cleanup));
        }
        try {
            IslandPlayerTurnSession.AfterBodyResult contained =
                    pending.state.session.containIslandFailure(pending.permit);
            if (contained != IslandPlayerTurnSession.AfterBodyResult
                    .CONTAINED_NO_MAIN_FALLBACK) {
                throw new IllegalStateException(containmentMessage);
            }
        } catch (Throwable cleanup) {
            failure = publishFatalFailure(merge(failure, cleanup));
        }
        return AsyncIslandPlayerTicks.<RuntimeException>rethrowAs(
                publishFatalFailure(failure));
    }

    /**
     * Carpet terminalization and its optional Main successor share one success-vs-W
     * cut.  The fallible server queue offer happens first while the exact permit is
     * still running; the cut performs only fixed terminal/metric/receipt writes.
     */
    private static void completeCarpetCombinedSuccess(
            PlayerState state, ServerPlayer player,
            IslandPlayerTurnSession.EarlyPermit<InboundPacket> permit) {
        TurnHandoffReceipt receipt = null;
        Throwable failure = null;
        synchronized (state) {
            try {
                if (terminalHandoffRequiredLocked(state, player, false)) {
                    receipt = prepareTurnHandoffLocked(state, player, null);
                }
                TurnHandoffReceipt accepted = receipt;
                RegionScheduler.commitSuccessIfHealthy(() -> {
                    if (!state.session.completeCarpetCombined(permit)) {
                        throw new IllegalStateException(
                                "Carpet combined A5 turn did not terminalize");
                    }
                    CARPET_COMBINED.increment();
                    if (accepted != null) accepted.acceptPublication();
                });
            } catch (Throwable completionFailure) {
                failure = publishFatalFailure(completionFailure);
                if (receipt != null) failure = receipt.cancelPublication(failure);
            }
        }
        if (failure != null) {
            throw AsyncIslandPlayerTicks.<RuntimeException>rethrowAs(
                    publishFatalFailure(failure));
        }
        if (receipt != null) receipt.runInlineIfNeeded();
    }

    /** Same receipt cut for the real-player terminal plus its optional Main closer. */
    private static void completeRealLateSuccess(
            PendingLate pending, boolean postPhysicsHandoff) {
        TurnHandoffReceipt receipt = null;
        Throwable failure = null;
        synchronized (pending.state) {
            try {
                if (terminalHandoffRequiredLocked(
                        pending.state, pending.player, postPhysicsHandoff)) {
                    receipt = prepareTurnHandoffLocked(
                            pending.state, pending.player,
                            postPhysicsHandoff ? pending : null);
                }
                TurnHandoffReceipt accepted = receipt;
                RegionScheduler.commitSuccessIfHealthy(() -> {
                    if (!pending.state.session.completeRealLate(pending.permit)) {
                        throw new IllegalStateException(
                                "A5 real-player late half did not terminalize");
                    }
                    REAL_LATE.increment();
                    if (postPhysicsHandoff) {
                        // This admission cut must become visible with the terminal
                        // token, before the accepted Main receipt is allowed to run.
                        pending.state.eligible = false;
                        pending.state.lifecycleInvalidated = true;
                        POST_PHYSICS_HANDOFFS.increment();
                    }
                    if (accepted != null) accepted.acceptPublication();
                });
            } catch (Throwable completionFailure) {
                failure = publishFatalFailure(completionFailure);
                if (receipt != null) failure = receipt.cancelPublication(failure);
            }
        }
        if (failure != null) {
            throw AsyncIslandPlayerTicks.<RuntimeException>rethrowAs(
                    publishFatalFailure(failure));
        }
        if (receipt != null) receipt.runInlineIfNeeded();
    }

    /** Caller holds the same monitor used by Netty ingress and owner admission. */
    private static boolean terminalHandoffRequiredLocked(
            PlayerState state, ServerPlayer player, boolean forcedPostPhysics) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException(
                    "A5 terminal handoff decision without PlayerState lock");
        }
        IslandPlayerTurnSession.View view = state.session.view();
        IslandPlayerTurnSession.OwnerStamp owner = view.owner();
        if (owner == null || owner.playerIdentity() != player
                || owner.listenerIdentity() != player.connection) {
            throw new IllegalStateException(
                    "A5 terminal handoff lost its exact player/listener owner");
        }
        if (state.mainDisconnectPending) return false;
        if (!forcedPostPhysics && netherOwnsQuarantinedSuffixLocked(state, player)) return false;
        return forcedPostPhysics || !state.mainIngress.isEmpty()
                || state.lifecycleInvalidated;
    }

    /** Offer the current-tick task before entering the scheduler success cut. */
    private static TurnHandoffReceipt prepareTurnHandoffLocked(
            PlayerState state, ServerPlayer player, PendingLate postPhysics) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException(
                    "A5 terminal handoff offer without PlayerState lock");
        }
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            throw new IllegalStateException("A5 terminal handoff lost its server");
        }
        TurnHandoffReceipt receipt =
                new TurnHandoffReceipt(server, state, player, postPhysics);
        receipt.preparePublication();
        return receipt;
    }

    /**
     * Immutable proof for the retained PendingLate FIFO head. Mutable
     * lifecycle/connection/level/removal state is deliberately excluded so a proven
     * hard exit can contain this exact token without masking token replacement.
     */
    static boolean pendingRealPlayerBeforeLateInvariantLocked(
            PendingLate pending, IslandRegistry.PIsland island) {
        if (pending == null || island == null || !Thread.holdsLock(pending.state)
                || pending.player == null || pending.listener == null
                || pending.owner == null || pending.permit == null
                || pending.state.listenerGate != pending.listener
                || pending.owner.playerIdentity() != pending.player
                || pending.owner.listenerIdentity() != pending.listener
                || pending.owner.islandIdentity() != island
                || pending.owner.islandId() != island.id
                || pending.owner.islandGeneration() != island.gen
                || pending.owner.domainIdentity() != island.domain
                || pending.owner.kind() != IslandPlayerTurnSession.PlayerKind.REAL
                || !pending.permit.owner().matchesExactly(pending.owner)
                || !pending.state.session.isAwaitingRealLate(pending.permit)) {
            return false;
        }
        return pending.listener instanceof AsyncIslandListenerAccess listenerAccess
                && listenerAccess.fabrolia$asyncIslandPlayerState() == pending.state;
    }

    /**
     * {@link ServerPlayer#die} keeps the exact player/listener/level identity but marks
     * the client unloaded before the ordinary connection listener slot. That one
     * vanilla stage is still owed. Every destructive/replacement hard exit fails this
     * proof and therefore cannot borrow the old island claim.
     */
    private static boolean pendingDeathLateAuthorityLocked(
            PendingLate pending, IslandRegistry.PIsland island) {
        if (!Thread.holdsLock(pending.state)) {
            throw new IllegalStateException("A5 death-late proof without state lock");
        }
        ServerPlayer player = pending.player;
        long lifecycle = ((PlayerTickPhaseStamp) player).fabrolia$playerLifecycleEpoch();
        return pending.state.hardLifecycleExitPending
                && !pending.state.mainDisconnectPending
                && pending.state.deathLateLifecycleEpoch == lifecycle
                && lifecycleEpochMatchesPendingAuthority(
                lifecycle, pending.owner.lifecycleEpoch(), true)
                && player.isDeadOrDying()
                && !player.isRemoved()
                && player.getRemovalReason() == null
                && !player.isChangingDimension()
                && !player.hasDisconnected()
                && player.connection == pending.listener
                && pending.listener.player == player
                && player.level() == island.domainLevel();
    }

    /** Same-source death proof, intentionally bypassing only hasClientLoaded(). */
    private static boolean stillCurrentForDeathLate(
            IslandPlayerTurnSession.OwnerStamp owner,
            ServerPlayer player,
            ServerGamePacketListenerImpl listener,
            RegionizedServer rs,
            IslandRegistry.PIsland island,
            EpochDomain domain,
            long deathLateLifecycleEpoch) {
        long lifecycle = ((PlayerTickPhaseStamp) player).fabrolia$playerLifecycleEpoch();
        return rs != null
                && enabledForAsync(rs)
                && rs.level == player.level()
                && !island.retired
                && island.domain == domain
                && ownerFieldsMatch(owner, player, listener, island, domain,
                owner.lifecycleEpoch(), IslandPlayerTurnSession.PlayerKind.REAL)
                && lifecycleEpochMatchesPendingAuthority(
                lifecycle, owner.lifecycleEpoch(), true)
                && lifecycle == deathLateLifecycleEpoch
                && player.isDeadOrDying()
                && !player.isRemoved()
                && player.getRemovalReason() == null
                && !player.isChangingDimension()
                && !player.hasDisconnected()
                && player.connection == listener
                && listener.player == player;
    }

    /**
     * Contains a post-physics topology/tracker loss after the exact late body has
     * terminalized.  A concurrent packet-policy handoff or disconnect may already
     * own the close; otherwise this publishes the sole ordered Main closer.  No Main
     * entity fallback is requested because both player halves ran on the island.
     */
    private static void schedulePostPhysicsMainHandoff(PendingLate pending) {
        synchronized (pending.state) {
            pending.state.eligible = false;
            pending.state.lifecycleInvalidated = true;
            if (pending.state.mainDisconnectPending) return;

            IslandPlayerTurnSession.View view = pending.state.session.view();
            if (view.phase() == IslandPlayerTurnSession.Phase.DISCONNECTED
                    || view.phase() == IslandPlayerTurnSession.Phase.MAIN) {
                return;
            }
            if (view.phase() == IslandPlayerTurnSession.Phase.ISLAND_READY
                    || isMainHandoffPhase(view.phase())) {
                scheduleMainHandoffLocked(pending.state, pending.player, false);
                return;
            }
            // A later already-entered turn owns the same serial session.  Its own
            // terminal tail observes lifecycleInvalidated and publishes the handoff;
            // stealing or retrying that body here would violate exact-once.
        }
    }

    /**
     * Sends every block state changed by this exact replay/session directly to
     * its actor before {@link ServerGamePacketListenerImpl#tick()} emits the
     * prediction ACK.  Polling (never {@code clear}) makes a producer either
     * part of this drain or a later one, and the exact owner stamp prevents a
     * respawn/transfer successor from inheriting stale corrections.
     */
    private static void sendPredictedBlockCorrections(PendingLate pending) {
        Set<Long> sent = new HashSet<>();
        PredictedBlockChange correction;
        while ((correction = pending.state.predictedBlockChanges.poll()) != null) {
            if (!correction.owner.matchesExactly(pending.owner)
                    || !sent.add(correction.position)) {
                continue;
            }
            BlockPos pos = BlockPos.of(correction.position);
            pending.listener.send(new ClientboundBlockUpdatePacket(
                    pending.player.level(), pos));
        }
    }

    /** Called from ServerPlayer.tick's inherited wrapper to authenticate the exact body. */
    public static void markServerPlayerTickEntered(ServerPlayer player) {
        TurnContext context = TURN.get();
        if (context == null) return;
        if (context.player != player || context.late) {
            throw new IllegalStateException("ServerPlayer.tick entered outside its A5 early owner");
        }
        if (context.serverPlayerTickEntered) {
            throw new IllegalStateException("ServerPlayer.tick entered twice in one A5 turn");
        }
        context.serverPlayerTickEntered = true;
    }

    public static boolean isIslandPlayerTickOf(Entity entity) {
        TurnContext context = TURN.get();
        return context != null && context.player == entity;
    }

    public static boolean inOwnerTurn() {
        return TURN.get() != null;
    }

    public static boolean inPacketReplay() {
        TurnContext replay = PACKET_REPLAY.get();
        return replay != null && replay == TURN.get();
    }

    public static boolean inPacketReplay(ServerPlayer player) {
        TurnContext replay = PACKET_REPLAY.get();
        return replay != null && replay.player == player && replay == TURN.get();
    }


    /** Exact supported player types whose vanilla callbacks may run on an island. */
    public static boolean supportsIslandPlayerType(ServerPlayer player) {
        return player != null && classifyPlayer(player) != null;
    }

    /**
     * A decoded interaction may mutate only the island already claimed by its
     * exact player turn. A false result is either benign client-selected input
     * outside that venue or an internal source-local ownership violation; the
     * handler wrapper distinguishes those cases before entering vanilla code.
     */
    public static boolean ownsPacketInteractionTarget(ServerPlayer player,
                                                       long chunkPos) {
        if (!inPacketReplay(player)) return false;
        TurnContext turn = TURN.get();
        RegionizedServer runtime = RegionScheduler.forLevel(player.level());
        if (turn == null || runtime == null || !runtime.asyncActive
                || turn.island.retired) return false;
        return runtime.islandOwnerOfChunk(chunkPos) == turn.island;
    }

    /**
     * Journals a block changed by the exact packet currently replaying on an
     * island.  The world-wide ChunkHolder broadcast and this actor correction
     * serve different purposes: observers follow the island publication clock,
     * while the actor must receive its final authoritative state before the
     * listener emits the prediction sequence ACK.
     */
    public static void recordPredictedBlockChange(BlockPos pos) {
        if (pos == null) return;
        TurnContext replay = PACKET_REPLAY.get();
        if (replay == null || replay != TURN.get()
                || replay.owner.listenerIdentity() != replay.player.connection
                || replay.owner.kind() != IslandPlayerTurnSession.PlayerKind.REAL) {
            return;
        }
        replay.state.predictedBlockChanges.add(
                new PredictedBlockChange(pos.asLong(), replay.owner));
    }

    /** Network-thread MPSC publication; never blocks on the island or Main. */
    public static boolean enqueuePacket(Packet<?> packet, PacketListener listener) {
        if (!(listener instanceof ServerGamePacketListenerImpl game) || packet == null) {
            return false;
        }
        PlayerState state = ((AsyncIslandListenerAccess) game)
                .fabrolia$asyncIslandPlayerState();
        if (state == null) state = bindListenerState(game);
        if (state == null) return false;
        synchronized (state) {
            if (state.listenerGate != null && state.listenerGate != game) {
                throw new IllegalStateException("A5 listener gate changed identity");
            }
            state.listenerGate = game;
            IslandPlayerTurnSession.View view = state.session.view();
            IslandPlayerTurnSession.OwnerStamp owner = view.owner();
            ServerPlayer currentPlayer = game.player;

            if (view.phase() == IslandPlayerTurnSession.Phase.DISCONNECTED
                    || state.mainDisconnectPending) {
                // channelRead0 may already have crossed Connection's read-only edge.
                // Once terminalization is published this retained listener gate owns
                // the stale packet.  It must neither escape into vanilla's Main queue
                // nor open a competing MAIN_HANDOFF behind the disconnect task.
                return true;
            }

            // Retaining a listener binding sequences older work, but does not itself
            // opt an OFF venue or an unsupported player subclass into packet routing.
            // Once those obligations drain, resume the exact vanilla PacketUtils path.
            // Decode admission reads published ownership only. The current level,
            // removal state and inventory belong to the owner that will replay it.
            if ((!enabledForAsync() || currentPlayer == null || classifyPlayer(currentPlayer) == null)
                    && view.phase() == IslandPlayerTurnSession.Phase.MAIN
                    && !hasOwnerAdmissionCutLocked(state)) {
                return false;
            }
            InboundPacket inbound = new InboundPacket(packet, listener);
            AsyncIslandPacketPolicy.Lane policy = hasInvalidBlockAckSequence(packet)
                    ? AsyncIslandPacketPolicy.Lane.MAIN_FIFO
                    : AsyncIslandPacketPolicy.classify(packet);

            // The old FIFO stays open only while this exact listener still points at
            // the old player. A respawn packet changes listener.player inside replay;
            // later decoded packets then belong to the successor and must wait in the
            // same listener-lifetime Main ingress lane, never in the old owner queue.
            boolean exactIslandOwner = owner != null
                    && owner.listenerIdentity() == listener
                    && owner.playerIdentity() == currentPlayer;
            boolean handingOff = state.lifecycleInvalidated
                    || isMainHandoffPhase(view.phase());
            boolean mayUseIslandFifo = !state.mainIngressScheduled
                    && state.mainIngress.isEmpty()
                    && !state.mainDisconnectPending
                    && policy == AsyncIslandPacketPolicy.Lane.ISLAND_FIFO
                    && exactIslandOwner
                    && (handingOff || state.eligible && enabledForAsync());
            if (mayUseIslandFifo
                    && state.session.enqueuePacket(owner, inbound).isPresent()) {
                PACKETS_QUEUED.increment();
                return true;
            }

            // Once a listener has participated in A5 it remains the sequencer across
            // MAIN_HANDOFF, MAIN_DRAINING, a temporary compatibility fallback and a
            // respawn/shadow successor. A single Main task drains this FIFO before a
            // new island attach is allowed, closing the old vanilla scheduling gap.
            enqueueMainIngressLocked(state, game, inbound);
            // A MAIN_FIFO decision creates one ordered boundary, not a permanent
            // venue switch. If the old owner is quiescent, start its handoff now; if
            // a body is active, its terminal edge below starts it. Existing island
            // packets remain before this entry, while a later island-safe suffix is
            // handed back after the exact Main-required prefix commits.
            if (view.phase() == IslandPlayerTurnSession.Phase.ISLAND_READY
                    && owner != null
                    && owner.playerIdentity() instanceof ServerPlayer oldPlayer
                    && !netherOwnsQuarantinedSuffixLocked(state, oldPlayer)) {
                scheduleMainHandoffLocked(state, oldPlayer, false);
            }
            PACKETS_QUEUED.increment();
            return true;
        }
    }

    /**
     * Pure decoded-input validation, before any vanilla/mod handler can mutate.
     * Only the four destroy-family actions consume a PlayerAction sequence in
     * vanilla (ackBlockChangesUpTo throws on a negative one); the other actions
     * deliberately ignore that field.
     */
    private static boolean hasInvalidBlockAckSequence(Packet<?> packet) {
        if (packet == null) return false;
        Class<?> type = packet.getClass();
        if (type == ServerboundUseItemPacket.class) {
            return ((ServerboundUseItemPacket) packet).sequence() < 0;
        }
        if (type == ServerboundUseItemOnPacket.class) {
            return ((ServerboundUseItemOnPacket) packet).sequence() < 0;
        }
        if (type == ServerboundPlayerActionPacket.class) {
            ServerboundPlayerActionPacket action = (ServerboundPlayerActionPacket) packet;
            return switch (action.getAction()) {
                case START_DESTROY_BLOCK, CHANGE_DESTROY_DIRECTION, ABORT_DESTROY_BLOCK,
                     STOP_DESTROY_BLOCK -> action.getSequence() < 0;
                default -> false;
            };
        }
        return false;
    }

    /** Start an ordered packet-policy or lifecycle handoff after body terminalization. */
    private static void schedulePendingMainIngressHandoff(PlayerState state,
                                                           ServerPlayer player) {
        synchronized (state) {
            if (state.mainDisconnectPending) return;
            if (state.mainIngress.isEmpty() && !state.lifecycleInvalidated) return;
            if (netherOwnsQuarantinedSuffixLocked(state, player)) return;
            IslandPlayerTurnSession.View view = state.session.view();
            if (view.phase() != IslandPlayerTurnSession.Phase.ISLAND_READY) return;
            IslandPlayerTurnSession.OwnerStamp owner = view.owner();
            if (owner == null || owner.playerIdentity() != player) {
                throw new IllegalStateException(
                        "A5 Main ingress boundary terminalized under another player owner");
            }
            scheduleMainHandoffLocked(state, player, false);
        }
    }

    /**
     * Physical-Main ownership edge for the complete play-listener state machine.
     * Binding and lease admission share the same monitor as {@link #ensureOwner}; a
     * negative island observation therefore cannot race into a duplicate Main body.
     */
    public static void runMainListenerTick(ServerGamePacketListenerImpl listener, Runnable body) {
        Objects.requireNonNull(body, "body");
        MainExecutionLease lease = beginMainListenerTick(listener, body);
        if (!lease.runOriginal || listener == null || listener.player == null
                || physicalMain(listener.player) != MinecraftThreadCheck.YES) {
            lease.execute(body);
            return;
        }
        // Even OFF intent may leave a committed autonomous owner. The spatial
        // claim is independent of player-actor enablement and a retained A5 lease.
        lease.execute(() -> {
            ServerPlayer player = listener.player;
            long lifecycle = player instanceof PlayerTickPhaseStamp stamp
                    ? stamp.fabrolia$playerLifecycleEpoch() : 0L;
            RegionScheduler.runWithMainEntityClaim(player, () -> {
                if (!cancelDirectMainListenerTick(
                        lease.state, listener, player, lifecycle)) body.run();
            }, () -> cancelDirectMainListenerTick(lease.state, listener, player, lifecycle));
        });
    }

    /** A claim wait may pump a lifecycle command before this direct L enters. */
    private static boolean cancelDirectMainListenerTick(
            PlayerState state, ServerGamePacketListenerImpl listener,
            ServerPlayer player, long lifecycle) {
        if (state == null) {
            return listener.player != player || player.connection != listener
                    || !listener.isAcceptingMessages()
                    || player instanceof PlayerTickPhaseStamp stamp
                    && stamp.fabrolia$playerLifecycleEpoch() != lifecycle;
        }
        synchronized (state) {
            if (isExactCleanTerminalDisconnectLocked(state, player, listener)) return true;
            if (state.listenerGate != listener
                    || ((AsyncIslandListenerAccess) listener)
                    .fabrolia$asyncIslandPlayerState() != state
                    || state.mainExecutionLeases <= 0) {
                throw new IllegalStateException("A5 direct listener lost its Main lease");
            }
            ServerPlayer current = listener.player;
            if (current == null || current.connection != listener) {
                throw new IllegalStateException("A5 direct listener lost its current player");
            }
            if (current != player
                    || player instanceof PlayerTickPhaseStamp stamp
                    && stamp.fabrolia$playerLifecycleEpoch() != lifecycle) {
                // The owed direct callback never entered. A later Connection tick
                // obtains the successor's own lease and spatial claim.
                return true;
            }
            if (state.session.view().phase() != IslandPlayerTurnSession.Phase.MAIN
                    || state.mainDisconnectPending) {
                throw new IllegalStateException("A5 direct listener changed ownership during claim");
            }
            return false;
        }
    }

    private static MainExecutionLease beginMainListenerTick(
            ServerGamePacketListenerImpl listener, Runnable body) {
        if (listener == null) {
            return new MainExecutionLease(null, true);
        }
        ServerPlayer player = listener.player;
        if (player == null || physicalMain(player) != MinecraftThreadCheck.YES) {
            return new MainExecutionLease(null, true);
        }
        PlayerState state = ((AsyncIslandListenerAccess) listener)
                .fabrolia$asyncIslandPlayerState();
        if (state == null) {
            if (!RegionScheduler.isActive() || !(player instanceof AsyncIslandPlayerAccess)) {
                return new MainExecutionLease(null, true);
            }
            // A portal captured inside the original body must observe this lease,
            // even when player simulation is OFF or this subclass is Main-only.
            state = bindListenerIdentity(listener);
        }
        if (state == null) throw new IllegalStateException(
                "A5 could not publish the Main listener gate");
        synchronized (state) {
            if (state.listenerGate != null && state.listenerGate != listener) {
                throw new IllegalStateException("A5 Main listener crossed its gate identity");
            }
            state.listenerGate = listener;
            IslandPlayerTurnSession.Phase phase = state.session.view().phase();
            if (phase == IslandPlayerTurnSession.Phase.DISCONNECTED
                    || state.mainDisconnectPending
                    || isIslandListenerOwnedPhase(phase)
                    && !(phase == IslandPlayerTurnSession.Phase.ISLAND_READY
                    && state.netherReservation != null
                    && netherSourceReadyLocked(state.netherReservation))) {
                // A normal Connection loop stops before this point once the channel
                // closes.  Keep the ownership boundary fail-closed for exceptional
                // teardown paths which terminalized the A5 session first but left the
                // transport briefly observable as connected.
                return new MainExecutionLease(state, false);
            }
            if (state.netherReservation != null) {
                if (state.lastIslandListenerPlayer != player
                        || state.lastIslandListenerServerTick != player.level().getServer().getTickCount()) {
                    enqueueMainIngressLocked(state, listener, InboundPacket.listenerTick(listener, body));
                }
                return new MainExecutionLease(state, false);
            }
            MinecraftServer server = player.level().getServer();
            if (server != null && state.lastIslandListenerPlayer == player
                    && state.lastIslandListenerServerTick == server.getTickCount()) {
                // The island late slot may complete and hand ownership back before
                // this physical tick reaches Connection.tick.  The phase is MAIN at
                // that point, but running the complete listener again would duplicate
                // tickPlayer/doTick.  Suppress precisely that same-tick transition.
                return new MainExecutionLease(state, false);
            }
            if (phase == IslandPlayerTurnSession.Phase.MAIN
                    && !state.mainIngressScheduled
                    && !state.mainIngress.isEmpty()) {
                // A released island-safe suffix normally reattaches from the next
                // region ordinal. If A5 was toggled OFF, topology disappeared, or
                // compatibility changed before that ordinal, physical Main is the
                // guaranteed liveness observer and arms the exact same head instead.
                armMainIngressIfRequiredLocked(state, listener);
            }
            if (hasOwnerAdmissionCutLocked(state)) {
                if (phase == IslandPlayerTurnSession.Phase.MAIN
                        && (state.mainEntityFallbackPending || !state.mainIngress.isEmpty())
                        && !islandPrefixHeadMayResumeLocked(
                                state, listener, player, state.session.view())) {
                    // Preserve this physical tick's listener obligation at the
                    // same FIFO boundary as packets and entity ordinals. Dropping
                    // it stalls doTick/use-item progress for mounted Main players.
                    enqueueMainIngressLocked(state, listener, InboundPacket.listenerTick(listener, body));
                }
                return new MainExecutionLease(state, false);
            }
            IslandPlayerTurnSession.PlayerKind kind = RegionalPlayerEntityTicksV2.enabled()
                    ? classifyPlayer(player) : null;
            if (kind != null) {
                RegionizedServer runtime = player.level() instanceof ServerLevel level
                        ? RegionScheduler.forLevel(level) : null;
                if (enabledForAsync(runtime)) {
                    long chunk = player.chunkPosition().pack();
                    IslandRegistry.PIsland venue = runtime.islandOwnerOfChunk(chunk);
                    Region venueRegion = runtime.regionizer().peekRegionForChunk(chunk);
                    if (venue != null && !venue.retired && venue.domain != null
                            && venue.domain.owner == venue && venueRegion != null
                            && venueRegion.state() == RegionState.ACTIVE
                            && venueRegion.domain == venue.domain
                            && runtime.regionMatches(venueRegion, player.chunkPosition())
                            && eligible(player, (ServerLevel) player.level(), kind)) {
                        // MAIN is only the quiescent publication state between owners. An
                        // eligible exact player is about to be admitted by its autonomous
                        // island; letting Connection.tick run first would give this wall tick
                        // one Main listener body and one later island listener body.
                        return new MainExecutionLease(state, false);
                    }
                }
            }
            state.lastIslandListenerPlayer = null;
            if (state.mainExecutionLeases == Integer.MAX_VALUE) {
                throw new IllegalStateException("A5 Main execution lease count exhausted");
            }
            state.mainExecutionLeases++;
            return new MainExecutionLease(state, true);
        }
    }

    private static void replayMainListenerTick(PlayerState state,
                                               ServerGamePacketListenerImpl listener,
                                               Runnable body) {
        ServerPlayer player;
        MainExecutionLease lease;
        synchronized (state) {
            player = listener.player;
            if (state.listenerGate != listener || player == null || player.connection != listener
                    || physicalMain(player) != MinecraftThreadCheck.YES
                    || state.session.view().phase() != IslandPlayerTurnSession.Phase.MAIN
                    || !state.mainIngressScheduled || !state.mainIngressHandlerActive) {
                throw new IllegalStateException("A5 deferred listener tick lost its Main session");
            }
            if (state.mainExecutionLeases == Integer.MAX_VALUE) {
                throw new IllegalStateException("A5 Main execution lease count exhausted");
            }
            lease = new MainExecutionLease(state, true);
            state.mainExecutionLeases++;
        }
        // Respawn retains the listener but replaces its player. Claim the current
        // receiver, never the predecessor sampled when this L was enqueued.
        lease.execute(() -> RegionScheduler.runWithMainEntityClaim(player, () -> {
            synchronized (state) {
                // Main claim waits may pump a routed disconnect. The queue clear
                // cannot cancel this already-polled, still-unentered L itself.
                if (isExactCleanTerminalDisconnectLocked(state, player, listener)) return;
                if (state.listenerGate != listener || listener.player != player
                        || player.connection != listener
                        || state.session.view().phase() != IslandPlayerTurnSession.Phase.MAIN
                        || !state.mainIngressScheduled || !state.mainIngressHandlerActive) {
                    throw new IllegalStateException("A5 deferred listener session changed during claim");
                }
            }
            body.run();
        }));
    }

    /**
     * Replaces {@link MinecraftServer#executeBlocking(Runnable)} only when a
     * disconnect originates inside an authenticated player venue.  Waiting for Main
     * while an A5 worker holds {@code island.unitClaim} forms a direct cycle with a
     * concurrent topology/save fence; the exact actor has the analogous Main-await
     * cycle.  Publication remains Main-owned, but the producer never joins it.
     */
    public static boolean deferBlockingDisconnect(
            ServerGamePacketListenerImpl listener, MinecraftServer server,
            Runnable disconnect) {
        Objects.requireNonNull(disconnect, "disconnect");
        if (server == null) return false;
        if (RegionalPlayerEntityTicksV2.inExactPlayerActor()) {
            try {
                server.schedule(new TickTask(server.getTickCount(),
                        () -> runFailStopMainTask(disconnect)));
            } catch (Throwable publicationFailure) {
                throwUnchecked(publishFatalFailure(publicationFailure));
            }
            return true;
        }

        TurnContext context = TURN.get();
        if (context == null || listener == null || context.player.connection != listener
                || context.owner.listenerIdentity() != listener
                || context.state.listenerGate != listener) {
            return false;
        }
        PlayerState state = context.state;
        IslandPlayerTurnSession.OwnerStamp disconnectOwner = context.owner;
        synchronized (state) {
            if (state.mainDisconnectPending) return true;
            if (state.mainDisconnectOrigin != null) {
                throw new IllegalStateException(
                        "A5 idle listener retained a disconnect origin");
            }
            state.mainDisconnectOrigin = MainDisconnectOrigin.DEFERRED_OWNER;
            state.mainDisconnectPending = true;
            state.eligible = false;
        }
        try {
            server.schedule(new TickTask(server.getTickCount(),
                    () -> runFailStopMainTask(() -> finishDeferredDisconnectOnMain(
                            state, listener, server, disconnectOwner, disconnect))));
            return true;
        } catch (Throwable publicationFailure) {
            Throwable failure = publishFatalFailure(publicationFailure);
            try {
                synchronized (state) {
                    if (state.mainDisconnectOrigin
                            != MainDisconnectOrigin.DEFERRED_OWNER) {
                        throw new IllegalStateException(
                                "A5 deferred disconnect rollback lost its origin");
                    }
                    state.mainDisconnectOrigin = null;
                    state.mainDisconnectPending = false;
                }
            } catch (Throwable cleanup) {
                failure = publishFatalFailure(merge(failure, cleanup));
            }
            throwUnchecked(publishFatalFailure(failure));
            return true;
        }
    }

    /**
     * Marks a vanilla physical-Main disconnect before its blocking teardown runs.
     * The lifecycle wrappers then quiesce an attached island without publishing a
     * competing handoff; {@link MainDisconnectScope#close()} performs the one terminal
     * state transition after vanilla has removed/replaced the player.
     */
    public static MainDisconnectScope beginPhysicalMainDisconnect(
            ServerGamePacketListenerImpl listener, MinecraftServer server) {
        if (listener == null || server == null
                || !RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)
                || !(listener instanceof AsyncIslandListenerAccess access)) {
            return new MainDisconnectScope(null, null);
        }
        PlayerState state = access.fabrolia$asyncIslandPlayerState();
        if (state == null) return new MainDisconnectScope(null, null);
        MainDisconnectScope scope = new MainDisconnectScope(state, listener);

        // Vanilla serializes the complete disconnect body against entity ticking.
        // Establish that same cut before publishing mainDisconnectPending: publishing
        // first would let a worker enter the gap before PlayerList.remove takes its
        // later lifecycle fence. Quiescence waits an entered island unit through
        // runLate before returning, so no accepted PendingLate token can survive
        // this cut and observe the subsequently removed player. A borrowed outer
        // command/lifecycle fence is retained by its owner; only this exact scope
        // releases a frame it acquired itself.
        boolean asyncLive = IslandCommands.requiresMainFence();
        boolean tookFence = asyncLive && IslandCommands.quiesceAllExcept(null);
        if (asyncLive && !tookFence && !IslandCommands.fenceWindowHeld()) {
            throw new IllegalStateException(
                    "A5 physical-Main disconnect could not establish its Main fence");
        }
        scope.releaseGlobalFence = tookFence;

        try {
            synchronized (state) {
                ServerPlayer player = listener.player;
                if (access.fabrolia$asyncIslandPlayerState() != state
                        || player == null || player.connection != listener
                        || state.listenerGate != null && state.listenerGate != listener) {
                    throw new IllegalStateException(
                            "A5 physical-Main disconnect crossed listener identity");
                }
                state.listenerGate = listener;
                IslandPlayerTurnSession.Phase phase = state.session.view().phase();
                if (phase == IslandPlayerTurnSession.Phase.DISCONNECTED) {
                    if (!isExactCleanTerminalDisconnectLocked(
                            state, player, listener)) {
                        throw new IllegalStateException(
                                "A5 physical-Main disconnect observed an unauthenticated "
                                        + "terminal session");
                    }
                    return scope;
                }
                if (isEnteredPlayerBodyPhase(phase)) {
                    throw new IllegalStateException(
                            "A5 physical-Main disconnect crossed entered player phase "
                                    + phase);
                }
                if (state.terminalDisconnectListener != null
                        || state.terminalDisconnectPlayer != null) {
                    throw new IllegalStateException(
                            "A5 live listener retained a terminal disconnect receipt");
                }
                if (state.mainDisconnectPending) {
                    if (state.mainDisconnectOrigin
                            != MainDisconnectOrigin.DEFERRED_OWNER) {
                        throw new IllegalStateException(
                                "A5 physical-Main disconnect was armed twice");
                    }
                    // A direct physical teardown may win before the previously
                    // queued owner task. It becomes the one body authority; the
                    // stale deferred task authenticates this provenance and exits.
                    state.mainDisconnectOrigin = MainDisconnectOrigin.PHYSICAL_MAIN;
                } else {
                    if (state.mainDisconnectOrigin != null) {
                        throw new IllegalStateException(
                                "A5 idle listener retained a disconnect origin");
                    }
                    state.mainDisconnectOrigin = MainDisconnectOrigin.PHYSICAL_MAIN;
                    state.mainDisconnectPending = true;
                }
                state.eligible = false;
                scope.terminalizationArmed = true;
                return scope;
            }
        } catch (Throwable publicationFailure) {
            Throwable failure = publishFatalFailure(publicationFailure);
            if (scope.releaseGlobalFence) {
                scope.releaseGlobalFence = false;
                failure = IslandCommands.releaseAllExcept(failure);
            }
            throwUnchecked(publishFatalFailure(failure));
            return scope;
        }
    }

    /** Run one exact physical-Main disconnect body under its full terminal scope. */
    public static void runPhysicalMainDisconnect(
            ServerGamePacketListenerImpl listener, MinecraftServer server,
            Runnable disconnect) {
        Objects.requireNonNull(disconnect, "disconnect");
        if (server == null
                || !RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)) {
            throw new IllegalStateException(
                    "A5 physical disconnect body missed physical Main");
        }
        if (NetherPortalPlayerAccess.deferDisconnect(listener,
                () -> runPhysicalMainDisconnect(listener, server, disconnect))) return;
        MainDisconnectScope scope = beginPhysicalMainDisconnect(listener, server);
        scope.execute(disconnect);
    }

    private static void finishPhysicalMainDisconnect(
            PlayerState state, ServerGamePacketListenerImpl listener) {
        synchronized (state) {
            try {
                if (state.listenerGate != listener) {
                    throw new IllegalStateException(
                            "A5 physical-Main disconnect lost its listener gate");
                }
                if (!state.mainDisconnectPending) {
                    if (isExactCleanTerminalDisconnectLocked(
                            state, listener.player, listener)) {
                        return;
                    }
                    throw new IllegalStateException(
                            "A5 physical-Main disconnect lost its pending receipt");
                }
                if (state.mainDisconnectOrigin
                        != MainDisconnectOrigin.PHYSICAL_MAIN) {
                    throw new IllegalStateException(
                            "A5 physical-Main disconnect lost its origin receipt");
                }
                if (!terminalizeMainListenerLocked(state, listener)) {
                    // A previously-published handoff closer already owns the only legal
                    // MAIN_HANDOFF -> MAIN edge. It observes mainDisconnectPending and
                    // performs terminalization before exposing the listener again.
                    IslandPlayerTurnSession.Phase phase = state.session.view().phase();
                    if (!isMainHandoffPhase(phase)) {
                        throw new IllegalStateException(
                                "A5 physical-Main disconnect could not terminalize phase "
                                        + phase);
                    }
                }
            } catch (Throwable failure) {
                throwUnchecked(publishFatalFailure(failure));
            }
        }
    }

    /** Caller holds state. Returns false only while the existing handoff owns close. */
    private static boolean terminalizeMainListenerLocked(
            PlayerState state, ServerGamePacketListenerImpl listener) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException(
                    "A5 listener terminalization without state lock");
        }
        ServerPlayer terminalPlayer = listener == null ? null : listener.player;
        if (!(listener instanceof AsyncIslandListenerAccess access)
                || access.fabrolia$asyncIslandPlayerState() != state
                || terminalPlayer == null || terminalPlayer.connection != listener
                || state.listenerGate != listener) {
            throw new IllegalStateException(
                    "A5 listener terminalization crossed its exact connection generation");
        }
        IslandPlayerTurnSession.View view = state.session.view();
        if (view.phase() == IslandPlayerTurnSession.Phase.DISCONNECTED) {
            if (isExactCleanTerminalDisconnectLocked(
                    state, terminalPlayer, listener)) {
                return true;
            }
            throw new IllegalStateException(
                    "A5 listener terminalization observed an unauthenticated terminal session");
        }
        if (isMainHandoffPhase(view.phase())) return false;
        if (state.terminalDisconnectListener != null
                || state.terminalDisconnectPlayer != null) {
            throw new IllegalStateException(
                    "A5 non-terminal listener retained a disconnect receipt");
        }
        IslandPlayerTurnSession.OwnerStamp owner = view.owner();
        boolean disconnected = owner == null
                ? state.session.disconnectMain()
                : state.session.disconnect(owner).isPresent();
        if (!disconnected) {
            throw new IllegalStateException(
                    "A5 listener terminalization rejected phase " + view.phase());
        }
        int lostRouted = state.routedIngressCount;
        if (lostRouted != 0) {
            FAILURES.add(lostRouted);
            LOGGER.error("A5 disconnect terminalized {} accepted routed player "
                            + "mutation(s) before commit", lostRouted);
        }
        verifyMainEntityOrdinalAccountingLocked(state);
        if (!state.terminalMainCommands.isEmpty()) {
            throw new IllegalStateException(
                    "A5 listener terminalization reused a command receipt FIFO");
        }
        for (InboundPacket inbound : state.mainIngress) {
            if (!AsyncIslandPacketContinuations
                    .isMainCommandPacket(inbound.packet())) {
                continue;
            }
            if (inbound.entityOrdinal() || inbound.isRoutedMutation()
                    || inbound.listener() != listener) {
                throw new IllegalStateException(
                        "A5 terminal Main command capture found a corrupt marker");
            }
            state.terminalMainCommands.addLast(inbound.packet());
        }
        state.mainIngress.clear();
        state.mainInteractionPacket = null;
        while (state.predictedBlockChanges.poll() != null) {
            // Terminal listener generation: corrections are stale-owned drops.
        }
        state.routedIngressCount = 0;
        state.causalRoutedCutoff = 0L;
        state.mainIngressScheduled = false;
        state.mainEntityFallbackPending = false;
        state.mainEntityFallbackAdmitted = false;
        state.activeMainEntityFallback = null;
        state.owedMainEntityOrdinals = 0;
        state.fallbackEntityAfterHandoff = false;
        state.eligible = false;
        state.lifecycleInvalidated = false;
        state.auditedLifecycleMutationPending = false;
        state.hardLifecycleExitPending = false;
        state.deathLateLifecycleEpoch = -1L;
        state.lastIslandListenerPlayer = null;
        state.trackingTopologyGap = null;
        clearTrackingPublicationBindingLocked(state);
        IslandRegistry.PIsland listed = state.listedIsland;
        if (listed != null) listed.playerStates.remove(state);
        if (state.listedIsland == listed) state.listedIsland = null;
        state.terminalDisconnectPlayer = terminalPlayer;
        state.terminalDisconnectListener = listener;
        state.mainDisconnectOrigin = null;
        state.mainDisconnectPending = false;
        if (!isExactCleanTerminalDisconnectLocked(
                state, terminalPlayer, listener)) {
            throw new IllegalStateException(
                    "A5 listener terminalization did not publish its exact clean receipt");
        }
        scheduleFirstDeferredMainCommand(state, listener);
        if (lostRouted != 0) throw new AcceptedRoutedMutationLoss(lostRouted);
        return true;
    }

    /**
     * Exact receipt for a listener generation whose disconnect terminalization and
     * entity-ordinal cleanup both committed. This predicate is used only before an
     * entity body enters: it cannot turn EARLY_RUNNING (or any other entered body)
     * into success, and a broad pending/removed observation is never sufficient.
     */
    private static boolean isExactCleanTerminalDisconnectLocked(
            PlayerState state, ServerPlayer player,
            ServerGamePacketListenerImpl listener) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException(
                    "A5 terminal disconnect receipt checked without state lock");
        }
        if (player == null || listener == null
                || !(listener instanceof AsyncIslandListenerAccess access)
                || access.fabrolia$asyncIslandPlayerState() != state
                || state.listenerGate != listener
                || state.terminalDisconnectListener != listener
                || state.terminalDisconnectPlayer != player
                || player.connection != listener || listener.player != player) {
            return false;
        }
        IslandPlayerTurnSession.View view = state.session.view();
        IslandPlayerTurnSession.OwnerStamp retainedOwner = view.owner();
        if (view.phase() != IslandPlayerTurnSession.Phase.DISCONNECTED
                || retainedOwner != null
                && (retainedOwner.playerIdentity() != player
                || retainedOwner.listenerIdentity() != listener)) {
            return false;
        }
        return !state.mainDisconnectPending
                && state.mainDisconnectOrigin == null
                && state.mainIngress.isEmpty()
                && !state.mainIngressScheduled
                && !state.mainEntityFallbackPending
                && !state.mainEntityFallbackAdmitted
                && state.activeMainEntityFallback == null
                && state.owedMainEntityOrdinals == 0
                && state.routedIngressCount == 0
                && state.causalRoutedCutoff == 0L
                && state.predictedBlockChanges.isEmpty()
                && !state.eligible
                && !state.lifecycleInvalidated
                && !state.auditedLifecycleMutationPending
                && !state.hardLifecycleExitPending
                && state.deathLateLifecycleEpoch == -1L
                && !state.fallbackEntityAfterHandoff
                && state.lastIslandListenerPlayer == null
                && state.trackingPublication.tracker() == null
                && state.trackingPublication.sessionEpoch() == -1L
                && state.trackingTopologyGap == null
                && state.listedIsland == null;
    }

    static boolean netherTerminalLocked(NetherPortalPlayerAccess.PlayerReservation reservation) {
        return isExactCleanTerminalDisconnectLocked(
                reservation.state, reservation.player, reservation.listener);
    }

    static PlayerState reserveNetherListener(ServerPlayer player) {
        PlayerState state = bindListenerIdentity(player.connection);
        if (state == null) throw new IllegalStateException("Nether player lacks listener sequencer");
        return state;
    }

    static void enqueueNetherMarkerLocked(NetherPortalPlayerAccess.PlayerReservation reservation) {
        PlayerState state = reservation.state;
        if (state.netherReservation != null) {
            throw new IllegalStateException("Nether player already reserved");
        }
        state.listenerGate = reservation.listener;
        state.mainIngress.addLast(reservation.marker);
        state.netherReservation = reservation;
        reservation.installed = true;
        armMainIngressIfRequiredLocked(state, reservation.listener);
        IslandPlayerTurnSession.View view = state.session.view();
        if (view.phase() == IslandPlayerTurnSession.Phase.ISLAND_READY
                && !netherOwnsQuarantinedSuffixLocked(state, reservation.player)) {
            scheduleMainHandoffLocked(state, reservation.player, false);
        }
    }

    /** The portal continuation precedes this retained suffix; no older boundary is being skipped. */
    private static boolean netherOwnsQuarantinedSuffixLocked(PlayerState state, ServerPlayer player) {
        if (!Thread.holdsLock(state)) throw new IllegalStateException("Nether FIFO cut without state lock");
        var reservation = state.netherReservation;
        return reservation != null && reservation.player == player
                && reservation.listener == state.listenerGate
                && state.mainIngress.peekFirst() == reservation.marker
                && !state.session.hasQueuedPackets()
                && !state.mainIngressScheduled && !state.mainIngressHandlerActive
                && state.mainExecutionLeases == 0
                && !state.mainEntityFallbackPending && !state.mainEntityFallbackAdmitted
                && state.activeMainEntityFallback == null
                && !state.mainDisconnectPending && !state.lifecycleInvalidated
                && !state.auditedLifecycleMutationPending && !state.hardLifecycleExitPending
                && !state.fallbackEntityAfterHandoff && state.lifecycleMutationBodyDepth == 0
                && state.trackingTopologyGap == null;
    }

    static boolean netherSourceReadyLocked(NetherPortalPlayerAccess.PlayerReservation reservation) {
        PlayerState state = reservation.state;
        if (!netherOwnsQuarantinedSuffixLocked(state, reservation.player)) return false;
        IslandPlayerTurnSession.View view = state.session.view();
        IslandPlayerTurnSession.OwnerStamp owner = view.owner();
        return view.phase() == IslandPlayerTurnSession.Phase.ISLAND_READY
                && owner != null && owner.playerIdentity() == reservation.player
                && owner.listenerIdentity() == reservation.listener
                && owner.lifecycleEpoch() == reservation.lifecycle
                && owner.islandIdentity() == state.listedIsland;
    }

    /** Close only empty ownership bookkeeping under real source claims, never replay a Main body. */
    static boolean closeNetherSourceOwnerLocked(NetherPortalPlayerAccess.PlayerReservation reservation) {
        PlayerState state = reservation.state;
        if (state.session.view().phase() == IslandPlayerTurnSession.Phase.MAIN) return reservation.ready();
        if (!netherSourceReadyLocked(reservation)) return false;
        if (!NetherPortalTransfers.owns(reservation.player)) {
            throw new IllegalStateException("Nether empty-source handoff lacks its exact spatial claims");
        }
        var owner = state.session.view().owner();
        validateMainHandoffSource(owner, state.listedIsland);
        var handoff = state.session.prepareMainHandoff(owner).orElseThrow(
                () -> new IllegalStateException("Nether empty-source handoff lost its quiescent receipt"));
        Throwable failure = null;
        try {
            RegionScheduler.commitSuccessIfHealthy(() -> acceptPreparedMainHandoffLocked(state, handoff));
            finishMainHandoff(state, reservation.player, handoff, false);
        } catch (Throwable problem) {
            failure = publishFatalFailure(problem);
        }
        try {
            failure = containMainHandoffFailure(state, handoff, failure);
        } catch (Throwable cleanup) {
            failure = publishFatalFailure(merge(failure, cleanup));
        }
        if (failure != null) throwUnchecked(publishFatalFailure(failure));
        return reservation.ready();
    }

    static void releaseNetherMarkerLocked(
            NetherPortalPlayerAccess.PlayerReservation reservation, boolean resume) {
        PlayerState state = reservation.state;
        if (state.netherReservation != reservation) {
            throw new IllegalStateException("Nether player reservation identity changed");
        }
        if (!state.mainIngress.removeFirstOccurrence(reservation.marker)
                && !netherTerminalLocked(reservation)) {
            throw new IllegalStateException("Nether player lost its FIFO marker");
        }
        state.netherReservation = null;
        reservation.installed = false;
        if (resume) {
            armMainIngressIfRequiredLocked(state, reservation.listener);
            scheduleFirstDeferredMainCommand(state, reservation.listener);
        }
    }

    /** A requested disconnect excludes new bodies until its physical callback commits. */
    private static boolean isExactPendingPreBodyDisconnectLocked(
            PlayerState state, ServerPlayer player,
            ServerGamePacketListenerImpl listener, IslandPlayerTurnSession.View view) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException(
                    "A5 pending disconnect receipt checked without state lock");
        }
        IslandPlayerTurnSession.OwnerStamp owner = view.owner();
        boolean preBody = state.mainDisconnectOrigin == MainDisconnectOrigin.DEFERRED_OWNER
                ? view.phase() == IslandPlayerTurnSession.Phase.ISLAND_READY
                || view.phase() == IslandPlayerTurnSession.Phase.EARLY_RESERVED
                : state.mainDisconnectOrigin == MainDisconnectOrigin.PHYSICAL_MAIN
                && isMainHandoffPhase(view.phase());
        return preBody && state.mainDisconnectPending && !state.eligible
                && player != null && listener instanceof AsyncIslandListenerAccess access
                && access.fabrolia$asyncIslandPlayerState() == state
                && state.listenerGate == listener
                && player.connection == listener && listener.player == player
                && owner != null && owner.playerIdentity() == player
                && owner.listenerIdentity() == listener
                && state.terminalDisconnectPlayer == null
                && state.terminalDisconnectListener == null
                && !state.mainEntityFallbackAdmitted;
    }

    /** The original callback either committed, or its existing physical handoff owns close. */
    private static boolean deferredDisconnectCallbackOwnsReceiptLocked(
            PlayerState state, ServerGamePacketListenerImpl listener,
            IslandPlayerTurnSession.OwnerStamp expectedOwner) {
        ServerPlayer player = (ServerPlayer) expectedOwner.playerIdentity();
        IslandPlayerTurnSession.View view = state.session.view();
        if (view.owner() != null && !view.owner().matchesExactly(expectedOwner)) return false;
        return isExactCleanTerminalDisconnectLocked(state, player, listener)
                || state.mainDisconnectOrigin == MainDisconnectOrigin.PHYSICAL_MAIN
                && isExactPendingPreBodyDisconnectLocked(state, player, listener, view);
    }

    /** Consume one stale registry ordinal only after exact terminal publication. */
    private static boolean consumeExactTerminalPreBodyOrdinalLocked(
            PlayerState state, ServerPlayer player,
            ServerGamePacketListenerImpl listener) {
        if (!isExactCleanTerminalDisconnectLocked(state, player, listener)) {
            return false;
        }
        TERMINAL_PRE_BODY_CANCELLATIONS.increment();
        return true;
    }

    /** Main task: attempt vanilla callback delivery without joining the channel-close future. */
    private static void finishDeferredDisconnectOnMain(
            PlayerState state, ServerGamePacketListenerImpl listener,
            MinecraftServer server, IslandPlayerTurnSession.OwnerStamp expectedOwner,
            Runnable disconnect) {
        if (!RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)) {
            throw new IllegalStateException("A5 deferred disconnect missed physical Main");
        }
        ServerPlayer player = (ServerPlayer) expectedOwner.playerIdentity();
        synchronized (state) {
            if (deferredDisconnectCallbackOwnsReceiptLocked(state, listener, expectedOwner)) {
                return;
            }
        }
        boolean asyncLive = IslandCommands.requiresMainFence();
        boolean tookFence = asyncLive && IslandCommands.quiesceAllExcept(null);
        if (asyncLive && !tookFence && !IslandCommands.fenceWindowHeld()) {
            throw new IllegalStateException(
                    "A5 deferred disconnect could not establish its Main fence");
        }
        Throwable failure = null;
        boolean attemptCallback = true;
        try {
            synchronized (state) {
                // Reauthenticate after quiescence before entering the callable.
                // Any superseding physical receipt still leaves our fence to release.
                if (deferredDisconnectCallbackOwnsReceiptLocked(state, listener, expectedOwner)) {
                    attemptCallback = false;
                } else {
                    IslandPlayerTurnSession.View view = state.session.view();
                    if (state.mainDisconnectOrigin != MainDisconnectOrigin.DEFERRED_OWNER
                            || !isExactPendingPreBodyDisconnectLocked(state, player, listener, view)
                            || !view.owner().matchesExactly(expectedOwner)) {
                        throw new IllegalStateException(
                                "A5 deferred disconnect lost its listener gate");
                    }
                    if (state.mainIngressHandlerActive) {
                        throw new IllegalStateException(
                                "A5 disconnect crossed an active Main ingress handler");
                    }
                }
            }
        } catch (Throwable authenticationFailure) {
            // The exact consumer still owns mandatory teardown. Select W before
            // invoking it so an invalid queued receipt cannot expose partial state.
            failure = publishFatalFailure(authenticationFailure);
        }
        if (attemptCallback) {
            try {
                disconnect.run();
            } catch (Throwable teardownFailure) {
                failure = publishFatalFailure(merge(failure, teardownFailure));
            }
        }
        try {
            synchronized (state) {
                IslandPlayerTurnSession.View view = state.session.view();
                // Connection.handleDisconnection returns while its channel is open.
                // The send-completion callback can close it just after that return,
                // so a second channel-open check cannot authenticate this outcome.
                // Retain the exact pending owner until ordinary onDisconnect runs;
                // neither a completed attempt nor a closed socket is terminalization.
                boolean pendingCallback = state.mainDisconnectOrigin == MainDisconnectOrigin.DEFERRED_OWNER
                        && isExactPendingPreBodyDisconnectLocked(state, player, listener, view)
                        && view.owner().matchesExactly(expectedOwner);
                if (!pendingCallback
                        && !deferredDisconnectCallbackOwnsReceiptLocked(state, listener, expectedOwner)) {
                    throw new IllegalStateException(
                            "A5 deferred disconnect lost its exact callback or pending receipt");
                }
            }
        } catch (Throwable cleanup) {
            failure = publishFatalFailure(merge(failure, cleanup));
        }
        if (tookFence) {
            failure = IslandCommands.releaseAllExcept(failure);
        }
        if (failure != null) throwUnchecked(publishFatalFailure(failure));
    }

    /**
     * Publishes one optional-mod player mutation to the listener-lifetime serial
     * mailbox. For every exact A5-supported player, an enabled A5 runtime owns the
     * decision completely: the caller must not perform a negative observation and
     * then mutate inline. Mutations intentionally use the same ordered physical-Main
     * prefix as decoded MAIN packets. This costs one compatibility handoff on a rare
     * command, but makes action-pack writes, lifecycle changes and packet callbacks
     * one total listener order without dragging a later island-safe suffix to Main.
     *
     * @return false only when the player class is outside the audited exact envelope,
     *         or A5 is inactive and this listener has no older A5 obligation left;
     *         true means the mutation was appended.
     */
    public static boolean enqueueRoutedMutation(ServerPlayer player, String operation,
                                                RoutedPlayerMutation mutation) {
        Objects.requireNonNull(mutation, "mutation");
        if (player == null || !(player instanceof AsyncIslandPlayerAccess)
                || classifyPlayer(player) == null) {
            return false;
        }

        ServerGamePacketListenerImpl listener = player.connection;
        if (listener == null || listener.player != player) return false;
        RegionizedServer currentRuntime = player.level() instanceof ServerLevel level
                ? RegionScheduler.forLevel(level) : null;
        boolean venueActive = enabledForAsync(currentRuntime);
        PlayerState state = state(player);
        if (venueActive) {
            PlayerState bound = bindListenerState(listener);
            if (bound != null) state = bound;
        }
        if (state == null) {
            throw new IllegalStateException("A5 could not bind a routed mutation state");
        }
        String label = operation == null || operation.isBlank() ? "unknown" : operation;
        synchronized (state) {
            IslandPlayerTurnSession.View view = state.session.view();
            if (!venueActive && view.phase() == IslandPlayerTurnSession.Phase.MAIN
                    && !hasOwnerAdmissionCutLocked(state)) {
                return false;
            }
            if (view.phase() == IslandPlayerTurnSession.Phase.DISCONNECTED
                    || state.mainDisconnectPending) {
                throw new IllegalStateException(
                        "A5 routed player mutation '" + label
                                + "' targeted a disconnecting listener");
            }
            if (state.listenerGate != null && state.listenerGate != listener) {
                throw new IllegalStateException(
                        "A5 routed mutation crossed listener identity");
            }
            if (state.routedIngressCount >= MAX_ROUTED_MUTATIONS) {
                throw new IllegalStateException(
                        "A5 routed mutation FIFO exceeded "
                                + MAX_ROUTED_MUTATIONS + " entries");
            }
            long sequence = state.nextRoutedSequence;
            if (sequence <= 0L || sequence == Long.MAX_VALUE) {
                throw new IllegalStateException("A5 routed mutation sequence exhausted");
            }
            state.nextRoutedSequence = sequence + 1L;
            state.routedIngressCount++;
            InboundPacket inbound = new InboundPacket(
                    new RoutedMutationPacket(state, player, label, mutation),
                    listener, sequence);
            enqueueMainIngressLocked(state, listener, inbound);
            if (isReentrantMainBodyPublisherLocked(state, player)) {
                // A command invoked by the exact physical-Main packet/entity body
                // must commit before that body exposes its next envelope. A foreign
                // publisher which merely overlaps the body remains at the FIFO tail;
                // overlap is not causal authority to cross an older P/E boundary.
                // The cutoff includes every earlier routed sequence so routed-command
                // FIFO is preserved when this exact reentrant publication is promoted.
                state.causalRoutedCutoff = sequence;
            }
            IslandPlayerTurnSession.OwnerStamp owner = view.owner();
            if (view.phase() == IslandPlayerTurnSession.Phase.ISLAND_READY
                    && owner != null
                    && owner.playerIdentity() instanceof ServerPlayer oldPlayer
                    && !netherOwnsQuarantinedSuffixLocked(state, oldPlayer)) {
                scheduleMainHandoffLocked(state, oldPlayer, false);
            }
            PACKETS_QUEUED.increment();
            return true;
        }
    }

    private enum MinecraftThreadCheck { YES, NO }

    private static MinecraftThreadCheck physicalMain(ServerPlayer player) {
        var server = player.level().getServer();
        return server != null && RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)
                ? MinecraftThreadCheck.YES : MinecraftThreadCheck.NO;
    }

    /** Caller holds state; only the exact entered physical-Main body owns crossing. */
    private static boolean isReentrantMainBodyPublisherLocked(
            PlayerState state, ServerPlayer player) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException(
                    "A5 routed causal-authority read without state lock");
        }
        if (physicalMain(player) != MinecraftThreadCheck.YES) return false;
        if (state.mainIngressHandlerActive) return true;
        if (!state.mainEntityFallbackAdmitted
                || !Boolean.TRUE.equals(ParallelProcessor.IN_ENTITY_TICK.get())) {
            return false;
        }
        InboundPacket active = state.activeMainEntityFallback;
        return isActiveMainEntityOrdinalLocked(state, active)
                && fallbackPlayer(active) == player;
    }

    /** Carpet's fake maintenance modulo follows island L only inside its owner turn. */
    public static int carpetTickCount(int physicalTick) {
        TurnContext context = TURN.get();
        if (context == null || !isCarpetFake(context.player)) return physicalTick;
        return (int) context.island.domain.unitGameTime;
    }

    /**
     * Carpet intentionally catches {@link NullPointerException} around its combined
     * {@code super.tick()+doTick()} body. That legacy containment is safe on Main but
     * would make A5 commit a partially-applied island turn. Translate only an
     * authenticated exact Carpet owner failure so its outer NPE catch cannot hide it.
     */
    public static RuntimeException translateCarpetCombinedFailure(
            ServerPlayer player, NullPointerException failure) {
        TurnContext context = TURN.get();
        if (context != null && context.player == player && !context.late
                && isCarpetFake(player)) {
            // Carpet catches NPE around the combined vanilla body.  Install that exact
            // NPE as W before changing its surface type solely to escape Carpet's
            // catch-and-continue wrapper.  The outer A5 boundary re-selects and throws
            // the already-installed exact object.
            Throwable winner = publishFatalFailure(failure);
            return new IllegalStateException(
                    "Carpet fake-player combined island turn failed partially", winner);
        }
        return failure;
    }

    /**
     * Mark an interaction-owner invariant before vanilla's packet-error callback
     * contains the exception. Without this origin-side accounting the public A5
     * status could report {@code failures=0} after rejecting a real packet body.
     */
    public static IllegalStateException packetInteractionFailure(String message) {
        FAILURES.increment();
        return new IllegalStateException(message);
    }

    static String playerRefusal(ServerPlayer player) {
        if (player == null) return "player is absent";
        IslandPlayerTurnSession.PlayerKind kind = classifyPlayer(player);
        if (kind == null) return "unsupported ServerPlayer subclass " + player.getClass().getName();
        if (!(player.level() instanceof ServerLevel level)) return "not in a ServerLevel";
        return eligible(player, level, kind) ? null : "outside the current A5 player envelope";
    }

    public static String status() {
        return "islandEarly=" + REAL_EARLY.sum()
                + ", islandLate=" + REAL_LATE.sum()
                + ", carpetCombined=" + CARPET_COMBINED.sum()
                + ", packets=" + PACKETS_REPLAYED.sum() + "/" + PACKETS_QUEUED.sum()
                + ", mainFallbacks=" + MAIN_FALLBACKS.sum()
                + ", terminalPreBody=" + TERMINAL_PRE_BODY_CANCELLATIONS.sum()
                + ", replacementPreBody=" + REPLACEMENT_PRE_BODY_CANCELLATIONS.sum()
                + ", postPhysicsHandoffs=" + POST_PHYSICS_HANDOFFS.sum()
                + ", failures=" + FAILURES.sum();
    }

    /** Invalidate cached ownership after an async collapse or explicit gate-off fence. */
    public static void clearIsland(IslandRegistry.PIsland island) {
        if (island == null) return;
        if (island.pendingPlayerLate.peek() != null) {
            throw new IllegalStateException(
                    "A5 island clear crossed an owed vanilla listener tick");
        }
        for (PlayerState state : island.playerStates) {
            synchronized (state) {
                state.lifecycleInvalidated = true;
                IslandPlayerTurnSession.OwnerStamp owner = state.session.view().owner();
                Object identity = owner == null ? null : owner.playerIdentity();
                if (identity instanceof ServerPlayer player) {
                    if (state.mainDisconnectPending) {
                        // The already-published disconnect task owns terminalization.
                        // Starting MAIN_HANDOFF here would make the session temporarily
                        // non-disconnectible when that task reaches physical Main.
                        state.eligible = false;
                    } else {
                        scheduleMainHandoffLocked(state, player, false);
                    }
                } else {
                    state.eligible = false;
                    state.lifecycleInvalidated = false;
                    state.auditedLifecycleMutationPending = false;
                    state.hardLifecycleExitPending = false;
                    state.deathLateLifecycleEpoch = -1L;
                    island.playerStates.remove(state);
                    if (state.listedIsland == island) state.listedIsland = null;
                }
            }
        }
    }

    /** Quarantine begins after admission, not before this exact already-entered source E/L ends. */
    static boolean ownsPendingNetherTurn(Entity entity) {
        TurnContext context = TURN.get();
        if (context == null || context.player != entity
                || context.player.connection != context.owner.listenerIdentity()) return false;
        synchronized (context.state) {
            var reservation = context.state.netherReservation;
            IslandPlayerTurnSession.View view = context.state.session.view();
            return reservation != null && reservation.player == entity
                    && reservation.listener == context.player.connection
                    && view.owner() != null && view.owner().matchesExactly(context.owner)
                    && (context.late
                    ? view.phase() == IslandPlayerTurnSession.Phase.LATE_RUNNING
                    : view.phase() == IslandPlayerTurnSession.Phase.EARLY_RUNNING);
        }
    }

    /** Exact player turn or the sole coordinator retaining its live island claim. */
    static boolean ownsLifecycleMutation(ServerPlayer player) {
        if (NetherPortalPlayerAccess.owns(player)) return true;
        TurnContext context = TURN.get();
        if (context != null && context.player == player
                && context.owner.playerIdentity() == player
                && context.owner.islandIdentity() == context.island) {
            return true;
        }
        if (!(Thread.currentThread() instanceof RegionWorkerThread worker)
                || !(player instanceof AsyncIslandPlayerAccess)) {
            return false;
        }
        IslandPlayerTurnSession.View view = state(player).session.view();
        IslandPlayerTurnSession.OwnerStamp owner = view.owner();
        if (owner == null || owner.playerIdentity() != player
                || owner.listenerIdentity() != player.connection
                || !(owner.islandIdentity() instanceof IslandRegistry.PIsland island)
                || owner.islandId() != island.id || owner.islandGeneration() != island.gen
                || owner.domainIdentity() != island.domain || island.retired) {
            return false;
        }
        // Live coordinates can cross a cell while the player's old cell is still
        // executing its turn. Adjacency to that new position cannot authorize a
        // second worker to enter the same player body.
        if (view.phase() == IslandPlayerTurnSession.Phase.EARLY_RESERVED
                || view.phase() == IslandPlayerTurnSession.Phase.EARLY_RUNNING
                || view.phase() == IslandPlayerTurnSession.Phase.LATE_RUNNING
                || view.phase() == IslandPlayerTurnSession.Phase.MAIN_FALLBACK_RUNNING) {
            return false;
        }
        ServerLevel level = player.level();
        RegionizedServer rs = RegionScheduler.forLevel(level);
        if (rs == null || !rs.asyncActive || island.domainLevel() != level
                || !RegionScheduler.ownsRuntimeGeneration(rs)) {
            return false;
        }
        Region current = worker.currentRegion;
        if (current == null) {
            return worker.currentDomain == island.domain
                    && RegionScheduler.ownsCurrentContext(level, player.blockPosition());
        }
        if (current.currentWorker() != worker || !rs.ownsRegion(current)
                || current.domain != island.domain
                || !(player instanceof RegionKeyed keyed)) {
            return false;
        }
        Region indexed = rs.regionizer().peekByGridKey(keyed.fabrolia$getRegionKey());
        if (indexed == null || indexed.domain != island.domain
                || !neighboringLifecycleCell(current.gridKey, indexed.gridKey)
                || !neighboringLifecycleCell(current.gridKey,
                        rs.regionizer().grid.keyFor(player.chunkPosition()))) {
            return false;
        }
        EpochDomain domain = island.domain;
        long epoch = domain.epoch;
        return FabroliaConfig.useRegionColorGate()
                && domain.entityDispatchPublication.isAccepted()
                && domain.entityDispatchCutEpoch == epoch
                && domain.dispatchedEpoch == epoch
                && current.groupedEpoch == epoch
                && ColorGate.eligible(current, rs);
    }

    private static boolean neighboringLifecycleCell(long source, long target) {
        return Math.abs((long) (int) source - (int) target) <= 1L
                && Math.abs((long) (int) (source >>> 32) - (int) (target >>> 32)) <= 1L;
    }


    /** Allocates an exact state token before any lifecycle depth is changed. */
    static LifecycleBodyToken prepareLifecycleMutationBody(ServerPlayer player) {
        if (!(player instanceof AsyncIslandPlayerAccess)) {
            throw new IllegalStateException(
                    "A5 lifecycle body marker requires player state access");
        }
        return new LifecycleBodyToken(state(player));
    }

    /** Arms the wrapper-body completion edge before its handoff can be published. */
    static void beginLifecycleMutationBody(LifecycleBodyToken token) {
        if (token == null) {
            throw new IllegalArgumentException("A5 lifecycle body token is required");
        }
        PlayerState state = token.state;
        synchronized (state) {
            if (token.armed) {
                throw new IllegalStateException("A5 lifecycle body token armed twice");
            }
            if (state.lifecycleMutationBodyDepth == Integer.MAX_VALUE) {
                throw new IllegalStateException("A5 lifecycle body depth overflow");
            }
            token.armed = true;
            state.lifecycleMutationBodyDepth++;
        }
    }

    /**
     * Retires one unique wrapper body after its original and fence cleanup return.
     * A successful zero-depth edge stages its retained closer on this exact token;
     * publication waits until the enclosing scope has retired every token. Any
     * body/cleanup failure terminally cancels and rolls the transaction back.
     */
    static Throwable finishLifecycleMutationBody(
            LifecycleBodyToken token, Throwable primary) {
        if (token == null) {
            throw new IllegalArgumentException("A5 lifecycle body token is required");
        }
        PlayerState state = token.state;
        MainHandoffContinuation closer = null;
        Throwable lifecycleFailure = null;
        synchronized (state) {
            if (!token.armed || state.lifecycleMutationBodyDepth <= 0) {
                throw new IllegalStateException("A5 lifecycle body depth underflow");
            }
            if (primary != null) {
                state.lifecycleMutationFailure = merge(
                        state.lifecycleMutationFailure, primary);
            }
            token.armed = false;
            state.lifecycleMutationBodyDepth--;
            if (state.lifecycleMutationBodyDepth == 0) {
                closer = state.pendingLifecycleHandoff;
                state.pendingLifecycleHandoff = null;
                lifecycleFailure = state.lifecycleMutationFailure;
                state.lifecycleMutationFailure = null;
            }
        }
        // lifecycleMutationFailure is already the monitor-ordered first failure;
        // every later primary was attached to it above. Do not merge it back into
        // the later object and create a suppressed-reference cycle.
        Throwable failure = lifecycleFailure == null ? primary : lifecycleFailure;
        if (closer == null) return failure;
        if (failure != null) return cancelAndAbortMainHandoff(closer, failure);
        if (token.completedHandoff != null) {
            return cancelAndAbortMainHandoff(closer, new IllegalStateException(
                    "A5 lifecycle token staged two Main handoff continuations"));
        }
        token.completedHandoff = closer;
        return null;
    }

    /**
     * Scope-wide commit edge: after every exact token retired successfully, publish
     * one physical-Main batch for every staged closer. A failure anywhere before or
     * during that sole publication cancels all transactions without a successor.
     */
    static Throwable finishLifecycleHandoffPublications(
            List<LifecycleBodyToken> tokens, int markedBodyCount, Throwable primary) {
        Throwable failure = primary;
        LifecycleHandoffBatch batch = null;
        if (failure == null) {
            try {
                MinecraftServer server = null;
                int ready = 0;
                for (int index = 0; index < markedBodyCount; index++) {
                    MainHandoffContinuation closer = tokens.get(index).completedHandoff;
                    if (closer == null) continue;
                    if (server != null && server != closer.server) {
                        throw new IllegalStateException(
                                "one A5 lifecycle scope staged closers for different servers");
                    }
                    server = closer.server;
                    ready++;
                }
                if (ready == 0) {
                    return null;
                }
                batch = new LifecycleHandoffBatch(
                        server, tokens, markedBodyCount, ready);
                RegionScheduler.throwWorkerFailureIfAny();
                batch.publishOnce();
                return null;
            } catch (Throwable publicationFailure) {
                failure = publicationFailure;
            }
        }
        failure = publishFatalFailure(failure);
        Throwable contained = batch == null
                ? cancelStagedLifecycleHandoffs(tokens, markedBodyCount, failure)
                : batch.cancelAndAbortAll(failure);
        return publishFatalFailure(contained);
    }

    private static boolean lifecycleMutationBodyActive(PlayerState state) {
        // This volatile read is now an invariant guard, not a polling/retry branch:
        // lifecycle-created closers are staged only after the exact depth reaches
        // zero and published after the whole enclosing scope retires cleanly.
        return state.lifecycleMutationBodyDepth != 0;
    }

    /** Monotonic lifecycle update for an installed tracker receipt. */
    private static void updateTrackingTopologyGapForLifecycleLocked(
            PlayerState state,
            RegionalPlayerEntityTicksV2.LifecycleMutationKind kind) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException(
                    "A5 topology-gap lifecycle update without state lock");
        }
        TrackingTopologyGap receipt = state.trackingTopologyGap;
        if (receipt == null) return;
        if (kind == RegionalPlayerEntityTicksV2.LifecycleMutationKind.SOFT_HANDOFF) {
            if (receipt.publicationDisposition
                    != TrackingPublicationDisposition.SUPPRESSED) {
                receipt.publicationDisposition = TrackingPublicationDisposition.OWED;
            }
        } else {
            receipt.publicationDisposition =
                    TrackingPublicationDisposition.SUPPRESSED;
        }
    }

    /**
     * Central lifecycle bridge. The caller already owns this A5 unit or has fenced
     * every island on physical Main. An in-body invalidation is deferred until the
     * irreversible body terminalizes; a quiescent session begins its ordered FIFO
     * handoff immediately.
     */
    static void invalidateForLifecycleMutation(
            ServerPlayer player,
            RegionalPlayerEntityTicksV2.LifecycleMutationKind kind) {
        if (kind == null) {
            throw new IllegalArgumentException("A5 lifecycle mutation kind is required");
        }
        if (!(player instanceof AsyncIslandPlayerAccess)) return;
        PlayerState state = state(player);
        synchronized (state) {
            IslandPlayerTurnSession.View view = state.session.view();
            if (view.owner() == null) {
                updateTrackingTopologyGapForLifecycleLocked(state, kind);
                state.eligible = false;
                state.lifecycleInvalidated = false;
                state.auditedLifecycleMutationPending = false;
                state.hardLifecycleExitPending = false;
                state.deathLateLifecycleEpoch = -1L;
                IslandRegistry.PIsland listed = state.listedIsland;
                if (listed != null) listed.playerStates.remove(state);
                if (state.listedIsland == listed) state.listedIsland = null;
                return;
            }
            state.lifecycleInvalidated = true;
            state.auditedLifecycleMutationPending = true;
            if (kind == RegionalPlayerEntityTicksV2.LifecycleMutationKind.HARD_EXIT) {
                state.hardLifecycleExitPending = true;
                state.deathLateLifecycleEpoch = -1L;
            } else if (kind == RegionalPlayerEntityTicksV2.LifecycleMutationKind.DEATH) {
                if (state.hardLifecycleExitPending) {
                    // A second hard origin cannot borrow the first death authority.
                    state.deathLateLifecycleEpoch = -1L;
                } else {
                    long lifecycle = ((PlayerTickPhaseStamp) player)
                            .fabrolia$playerLifecycleEpoch();
                    if (lifecycle == Long.MAX_VALUE) {
                        throw new IllegalStateException(
                                "A5 death lifecycle epoch exhausted");
                    }
                    state.hardLifecycleExitPending = true;
                    state.deathLateLifecycleEpoch = lifecycle + 1L;
                }
            }
            updateTrackingTopologyGapForLifecycleLocked(state, kind);
            if (state.mainDisconnectPending) {
                state.eligible = false;
                return;
            }
            TurnContext turn = TURN.get();
            if (shouldArmLifecycleFallback(
                    turn != null && turn.player == player,
                    turn != null && turn.late,
                    turn != null && turn.serverPlayerTickEntered)) {
                state.fallbackEntityAfterHandoff = true;
            }
            IslandPlayerTurnSession.Phase phase = view.phase();
            if (phase == IslandPlayerTurnSession.Phase.ISLAND_READY
                    || isMainHandoffPhase(phase)) {
                scheduleMainHandoffLocked(state, player, false);
            }
        }
    }

    /** Only an explicit pre-early-body mutation may request a replacement Main body. */
    static boolean shouldArmLifecycleFallback(
            boolean exactTurn, boolean late, boolean serverPlayerTickEntered) {
        return exactTurn && !late && !serverPlayerTickEntered;
    }

    private static ReplayDisposition replayReservedPackets(TurnContext context) {
        List<IslandPlayerTurnSession.PacketEnvelope<InboundPacket>> packets =
                context.state.session.takeIslandHeadPackets(context.permit)
                        .orElseThrow(() -> new IllegalStateException(
                                "A5 packet cutoff was already consumed"));
        if (packets.isEmpty()) return ReplayDisposition.CONTINUE_ENTITY;
        // The exact turn is also the replay capability. Reusing it distinguishes
        // an entered envelope from an untouched suffix without allocating after
        // the session has detached the reserved FIFO prefix.
        TurnContext replay = context;
        for (int index = 0; index < packets.size(); index++) {
            if (context.state.mainDisconnectPending) {
                restoreIslandReplaySuffix(context, packets, index, false);
                return ReplayDisposition.DISCONNECT;
            }
            if (explicitLifecycleMutationPending(context.state)) {
                restoreIslandReplaySuffix(context, packets, index, true);
                return ReplayDisposition.LIFECYCLE_HANDOFF;
            }
            if (packetSessionChanged(context)) {
                throw failIslandReplay(context, packets, index,
                        new IllegalStateException(
                                "A5 player session changed inside reserved packet replay"));
            }
            IslandPlayerTurnSession.PacketEnvelope<InboundPacket> envelope =
                    packets.get(index);
            InboundPacket inbound = envelope.packet();
            if (inbound.listener != context.owner.listenerIdentity()
                    || context.player.connection != inbound.listener
                    || !envelope.owner().matchesExactly(context.owner)) {
                throw failIslandReplay(context, packets, index,
                        new IllegalStateException(
                                "stale A5 packet session reached owner replay"));
            }
            if (PACKET_REPLAY.get() != null) {
                throw failIslandReplay(context, packets, index,
                        new IllegalStateException("nested A5 packet replay"));
            }
            PACKET_REPLAY.set(replay);
            Throwable failure = null;
            int restoreFrom = -1;
            replay.packetBodyEntered = false;
            try {
                RegionScheduler.runSuccessBodyIfHealthy(() -> {
                    if (!context.state.session.enterIslandPacketBody(
                            context.permit)) {
                        throw new IllegalStateException(
                                "A5 packet body lost its exact reserved claim");
                    }
                    if (AsyncIslandPacketPolicy.requiresMainInteraction(
                            inbound.packet, context.player)) {
                        synchronized (context.state) {
                            if (context.state.mainInteractionPacket != null) {
                                throw new IllegalStateException(
                                        "A5 interaction replaced an owed Main packet");
                            }
                            context.state.mainInteractionPacket = inbound;
                        }
                        return;
                    }
                    replay.packetBodyEntered = true;
                    handle(inbound.packet, inbound.listener);
                });
                if (replay.packetBodyEntered) {
                    RegionScheduler.runSuccessBodyIfHealthy(
                            PACKETS_REPLAYED::increment);
                }
            } catch (RunningOnDifferentThreadException wrongThread) {
                // PacketUtils may already have published work before throwing this
                // sentinel. Select W before preserving only the untouched suffix.
                failure = publishFatalFailure(wrongThread);
                restoreFrom = replay.packetBodyEntered ? index + 1 : index;
            } catch (Throwable packetFailure) {
                // handle() already contained what vanilla's PacketProcessor
                // contains; anything reaching here (Fabrolia invariant, Error, OOM
                // report, a peer W refusing the lambda) is terminal for this prefix.
                failure = publishFatalFailure(packetFailure);
                restoreFrom = replay.packetBodyEntered ? index + 1 : index;
            }
            try {
                PACKET_REPLAY.remove();
            } catch (Throwable cleanup) {
                failure = publishFatalFailure(merge(failure, cleanup));
                if (restoreFrom < 0) restoreFrom = index + 1;
            }
            if (restoreFrom >= 0) {
                try {
                    restoreIslandReplaySuffix(
                            context, packets, restoreFrom, false);
                } catch (Throwable cleanup) {
                    failure = publishFatalFailure(merge(failure, cleanup));
                }
            }
            if (failure != null) throwUnchecked(publishFatalFailure(failure));
            if (context.state.mainDisconnectPending) {
                restoreIslandReplaySuffix(context, packets, index + 1, false);
                return ReplayDisposition.DISCONNECT;
            }
            if (context.state.mainInteractionPacket == inbound) {
                // No packet.handle (including mod wrappers) has entered. Preserve
                // this exact envelope, the reserved suffix and the concurrent tail
                // in their original order until physical Main owns the handoff.
                restoreIslandReplaySuffix(context, packets, index, true);
                return ReplayDisposition.PACKET_HANDOFF;
            }
            if (explicitLifecycleMutationPending(context.state)) {
                restoreIslandReplaySuffix(context, packets, index + 1, true);
                return ReplayDisposition.LIFECYCLE_HANDOFF;
            }
            if (packetSessionChanged(context)) {
                throw failIslandReplay(context, packets, index + 1,
                        new IllegalStateException(
                                "A5 player session changed after an entered packet body"));
            }
        }
        return ReplayDisposition.CONTINUE_ENTITY;
    }

    /** Select W before restoring any never-entered suffix of an owned replay token. */
    private static RuntimeException failIslandReplay(
            TurnContext context,
            List<IslandPlayerTurnSession.PacketEnvelope<InboundPacket>> packets,
            int restoreFrom,
            Throwable candidate) {
        Throwable failure = publishFatalFailure(candidate);
        if (restoreFrom >= 0) {
            try {
                restoreIslandReplaySuffix(
                        context, packets, restoreFrom, false);
            } catch (Throwable cleanup) {
                failure = publishFatalFailure(merge(failure, cleanup));
            }
        }
        return AsyncIslandPlayerTicks.<RuntimeException>rethrowAs(
                publishFatalFailure(failure));
    }

    private static boolean packetSessionChanged(TurnContext context) {
        ServerGamePacketListenerImpl connection = context.player.connection;
        return connection == null
                || connection != context.owner.listenerIdentity()
                || connection.player != context.player;
    }

    private static boolean explicitLifecycleMutationPending(PlayerState state) {
        return state.auditedLifecycleMutationPending;
    }

    private static void restoreIslandReplaySuffix(
            TurnContext context,
            List<IslandPlayerTurnSession.PacketEnvelope<InboundPacket>> packets,
            int fromIndex, boolean oweEntityBody) {
        int bounded = Math.max(0, Math.min(fromIndex, packets.size()));
        synchronized (context.state) {
            if (bounded < packets.size()
                    && !context.state.session.restoreIslandHeadSuffix(
                    context.permit, packets.subList(bounded, packets.size()))) {
                throw new IllegalStateException(
                        "A5 could not restore the unhandled packet suffix");
            }
            context.state.lifecycleInvalidated = true;
            if (oweEntityBody && !context.serverPlayerTickEntered
                    && !context.state.mainDisconnectPending) {
                context.state.fallbackEntityAfterHandoff = true;
            }
        }
    }

    /**
     * One entered packet body under vanilla's
     * {@code PacketProcessor.ListenerAndPacket.handle} contract: an ordinary
     * Exception from vanilla or another mod is reported through
     * {@code listener.onPacketError} (vanilla logs "Failed to handle packet …,
     * suppressing error" and records it as a suppressed exception) and the
     * caller continues with the next envelope, exactly as vanilla continues
     * with the next queued packet. Still fail-stop: a ReportedException caused
     * by OutOfMemoryError (vanilla rethrows it too), any Error, the A5 routing
     * sentinel, a failure raised by Fabrolia code, and any failure while a
     * Fabrolia fatal is already selected.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void handle(Packet packet, PacketListener listener) {
        try {
            packet.handle(listener);
        } catch (RunningOnDifferentThreadException wrongThread) {
            throw wrongThread;
        } catch (Exception failure) {
            if (!vanillaContainsPacketFailure(failure)) throw failure;
            listener.onPacketError(packet, failure);
        }
    }

    public static boolean vanillaContainsPacketFailure(Exception failure) {
        if (failure instanceof net.minecraft.ReportedException reported
                && reported.getCause() instanceof OutOfMemoryError) {
            return false;
        }
        if (RegionScheduler.workerFailureOrNull() != null) return false;
        return !raisedByFabrolia(failure);
    }

    /**
     * Runs the vanilla part of a packet handler ({@code original.call}) through
     * Fabrolia's own routing/claim wrappers ({@code route}). Those wrappers are
     * success-only and select W for anything their body throws, so an exception
     * vanilla's PacketProcessor would contain is caught inside the body, the
     * wrappers finish normally (claims released), and it is rethrown afterwards
     * — out of the handler, exactly where vanilla's handler would have thrown it.
     */
    public static void runVanillaPacketBody(java.util.function.Consumer<Runnable> route,
                                            Runnable vanillaBody) {
        Exception[] contained = new Exception[1];
        route.accept(() -> {
            try {
                vanillaBody.run();
            } catch (RunningOnDifferentThreadException wrongThread) {
                throw wrongThread;
            } catch (Exception failure) {
                if (!vanillaContainsPacketFailure(failure)) throw failure;
                contained[0] = failure;
            }
        });
        if (contained[0] != null) throwUnchecked(contained[0]);
    }

    /**
     * Whether this throwable, a cause or a suppressed failure was raised by
     * Fabrolia: a Fabrolia exception type, or a first frame outside the JDK and
     * shared libraries that is Fabrolia code or a mixin member merged into a
     * target class (those carry the {@code fabrolia$} prefix). Frames of the
     * {@code fabrolia.api} collections count as library frames: they throw the
     * same contract exceptions for their caller that the JDK collections they
     * replace would.
     */
    static boolean raisedByFabrolia(Throwable failure) {
        java.util.Set<Throwable> seen =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.ArrayDeque<Throwable> pending = new java.util.ArrayDeque<>();
        pending.add(failure);
        while (!pending.isEmpty() && seen.size() < 64) {
            Throwable current = pending.poll();
            if (!seen.add(current)) continue;
            if (current.getClass().getName().startsWith("com.axalotl.fabrolia.")) return true;
            for (StackTraceElement frame : current.getStackTrace()) {
                String owner = frame.getClassName();
                if (isLibraryFrame(owner)) continue;
                if (owner.startsWith("com.axalotl.fabrolia.")
                        || frame.getMethodName().contains("fabrolia$")) {
                    return true;
                }
                break;
            }
            if (current.getCause() != null) pending.add(current.getCause());
            java.util.Collections.addAll(pending, current.getSuppressed());
        }
        return false;
    }

    private static boolean isLibraryFrame(String owner) {
        return owner.startsWith("java.") || owner.startsWith("javax.")
                || owner.startsWith("jdk.") || owner.startsWith("sun.")
                || owner.startsWith("com.sun.") || owner.startsWith("it.unimi.dsi.fastutil.")
                || owner.startsWith("com.google.") || owner.startsWith("com.mojang.serialization.")
                || owner.startsWith("com.llamalad7.") || owner.startsWith("org.spongepowered.")
                || owner.startsWith("com.axalotl.fabrolia.api.");
    }

    /**
     * Shared attach/reuse cut for listener-lifetime work published before an island
     * reservation. Caller must hold {@code state}; packet-policy compatibility may
     * extend this one predicate without opening a second admission window.
     */
    static boolean hasOwnerAdmissionCutLocked(PlayerState state) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException("A5 owner-admission cut read without state lock");
        }
        return state.mainIngressScheduled || state.mainEntityFallbackPending
                || state.mainDisconnectPending || state.netherReservation != null
                || state.lifecycleMutationBodyDepth != 0
                || state.lifecycleInvalidated
                || state.trackingTopologyGap != null
                || !state.mainIngress.isEmpty();
    }

    private static IslandPlayerTurnSession.OwnerStamp ensureOwner(
            PlayerState state, ServerPlayer player, ServerGamePacketListenerImpl listener,
            RegionizedServer rs, IslandRegistry.PIsland island, EpochDomain domain,
            IslandPlayerTurnSession.PlayerKind kind) {
        if (listener == null) return null;
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException("A5 owner admission without state lock");
        }
        AsyncIslandListenerAccess listenerAccess = (AsyncIslandListenerAccess) listener;
        PlayerState previouslyBound = listenerAccess.fabrolia$asyncIslandPlayerState();
        if (previouslyBound != null && previouslyBound != state) return null;
        boolean newlyBound = previouslyBound == null;
        if (!listenerAccess.fabrolia$bindAsyncIslandPlayerState(state)) return null;
        if (state.listenerGate != null && state.listenerGate != listener) {
            if (newlyBound) listenerAccess.fabrolia$unbindAsyncIslandPlayerState(state);
            return null;
        }
        state.listenerGate = listener;
        if (state.mainExecutionLeases != 0) return null;
        if (state.netherReservation != null) return null;

        IslandPlayerTurnSession.View view = state.session.view();
        // A completed Main prefix may leave an island-safe suffix at the head of
        // the listener FIFO. Reattach exactly that prefix instead of executing it
        // on physical Main. Every other live Main/fallback/disconnect obligation
        // remains a hard admission cut.
        boolean resumeIslandPrefix = canResumeIslandPrefixLocked(
                state, listener, player, view);
        if (hasOwnerAdmissionCutLocked(state) && !resumeIslandPrefix) return null;

        IslandPlayerTurnSession.OwnerStamp current = view.owner();
        long lifecycle = ((PlayerTickPhaseStamp) player).fabrolia$playerLifecycleEpoch();
        if (current != null && ownerFieldsMatch(current, player, listener, island,
                domain, lifecycle, kind)) {
            publishListing(state, island);
            state.eligible = true;
            return current;
        }

        long sessionEpoch = allocateSessionEpoch(state, current);
        IslandPlayerTurnSession.OwnerStamp next = new IslandPlayerTurnSession.OwnerStamp(
                player, listener, island, island.id, island.gen, domain,
                lifecycle, sessionEpoch, kind);
        boolean installed;
        if (current == null) {
            installed = state.session.attach(next);
        } else {
            var transfer = state.session.beginTransfer(current);
            installed = transfer.isPresent() && state.session.completeTransfer(
                    transfer.get(), next, IslandPlayerTurnSession.PacketDisposition.CARRY);
            if (!installed && transfer.isPresent()) {
                state.session.abortTransfer(transfer.get());
            }
        }
        if (!installed) {
            if (newlyBound) listenerAccess.fabrolia$unbindAsyncIslandPlayerState(state);
            return null;
        }
        if (current == null) {
            state.lifecycleInvalidated = false;
            state.auditedLifecycleMutationPending = false;
            state.hardLifecycleExitPending = false;
            state.deathLateLifecycleEpoch = -1L;
        }
        publishListing(state, island);
        state.eligible = true;
        if (resumeIslandPrefix) {
            moveIslandPrefixToOwnerLocked(state, listener, next);
        }
        return next;
    }

    /** Caller holds state; only a quiescent exact MAIN listener may resume this suffix. */
    private static boolean canResumeIslandPrefixLocked(
            PlayerState state, ServerGamePacketListenerImpl listener,
            ServerPlayer player, IslandPlayerTurnSession.View view) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException("A5 island-prefix admission without state lock");
        }
        return !state.mainIngressScheduled
                && islandPrefixHeadMayResumeLocked(state, listener, player, view);
    }

    /**
     * Exact quiescent-Main predicate shared by the drain release edge and the next
     * island attachment. Unlike {@link #canResumeIslandPrefixLocked}, this method is
     * also called while the old Main task still owns {@code mainIngressScheduled};
     * that marker is deliberately ignored so the task can publish its release.
     */
    private static boolean islandPrefixHeadMayResumeLocked(
            PlayerState state, ServerGamePacketListenerImpl listener,
            ServerPlayer player, IslandPlayerTurnSession.View view) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException("A5 island-prefix decision without state lock");
        }
        if (view.phase() != IslandPlayerTurnSession.Phase.MAIN
                || view.owner() != null
                || state.mainIngressHandlerActive
                || state.mainEntityFallbackPending
                || state.mainDisconnectPending
                || state.trackingTopologyGap != null
                || state.lifecycleInvalidated
                || state.fallbackEntityAfterHandoff
                || state.causalRoutedCutoff != 0L
                || listener.player != player
                || state.mainIngress.isEmpty()) {
            return false;
        }
        // A player body is the tick boundary after the transferred packets. It may
        // not overtake a later Main-required packet which was already accepted into
        // this same pre-body FIFO. Therefore resume only when the complete retained
        // queue is island-safe, not merely its current head.
        for (InboundPacket inbound : state.mainIngress) {
            if (inbound.listener != listener || requiresMainIngress(state, inbound)) {
                return false;
            }
        }
        return islandVenueAvailable(player);
    }

    /** Moves the complete island-safe retained FIFO into the newly attached owner. */
    private static void moveIslandPrefixToOwnerLocked(
            PlayerState state, ServerGamePacketListenerImpl listener,
            IslandPlayerTurnSession.OwnerStamp owner) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException("A5 island-prefix transfer without state lock");
        }
        while (true) {
            InboundPacket inbound = state.mainIngress.peekFirst();
            if (inbound == null) return;
            if (requiresMainIngress(state, inbound)) {
                throw new IllegalStateException(
                        "A5 island-prefix transfer crossed a Main boundary");
            }
            if (inbound.listener != listener) {
                throw new IllegalStateException(
                        "A5 island-prefix transfer crossed listener identity");
            }
            if (state.session.enqueuePacket(owner, inbound).isEmpty()) {
                throw new IllegalStateException(
                        "A5 island-prefix transfer lost its attached owner");
            }
            if (state.mainIngress.pollFirst() != inbound) {
                throw new IllegalStateException("A5 island-prefix FIFO changed under state lock");
            }
        }
    }

    private static long allocateSessionEpoch(PlayerState state,
                                             IslandPlayerTurnSession.OwnerStamp current) {
        while (true) {
            long observed = state.nextSessionEpoch.get();
            if (observed < 0L || observed == Long.MAX_VALUE
                    || current != null && current.sessionEpoch() == Long.MAX_VALUE) {
                throw new IllegalStateException("A5 player session epoch exhausted");
            }
            long minimum = current == null ? observed
                    : Math.max(observed, current.sessionEpoch() + 1L);
            if (minimum == Long.MAX_VALUE) {
                throw new IllegalStateException("A5 player session epoch exhausted");
            }
            if (state.nextSessionEpoch.compareAndSet(observed, minimum + 1L)) {
                return minimum;
            }
        }
    }

    private static void publishListing(PlayerState state, IslandRegistry.PIsland island) {
        IslandRegistry.PIsland old = state.listedIsland;
        if (old == island) return;
        if (old != null) old.playerStates.remove(state);
        island.playerStates.add(state);
        state.listedIsland = island;
    }

    private static boolean ownerFieldsMatch(IslandPlayerTurnSession.OwnerStamp owner,
                                            ServerPlayer player,
                                            ServerGamePacketListenerImpl listener,
                                            IslandRegistry.PIsland island,
                                            EpochDomain domain,
                                            long lifecycle,
                                            IslandPlayerTurnSession.PlayerKind kind) {
        return owner.playerIdentity() == player
                && owner.listenerIdentity() == listener
                && owner.islandIdentity() == island
                && owner.islandId() == island.id
                && owner.islandGeneration() == island.gen
                && owner.domainIdentity() == domain
                && owner.lifecycleEpoch() == lifecycle
                && owner.kind() == kind;
    }

    private static boolean stillCurrent(IslandPlayerTurnSession.OwnerStamp owner,
                                        ServerPlayer player, RegionizedServer rs,
                                        IslandRegistry.PIsland island, EpochDomain domain) {
        return stillCurrent(owner, player, rs, island, domain, false);
    }

    private static boolean stillCurrent(IslandPlayerTurnSession.OwnerStamp owner,
                                        ServerPlayer player, RegionizedServer rs,
                                        IslandRegistry.PIsland island, EpochDomain domain,
                                        boolean allowAuditedLifecycleEpochAdvance) {
        long currentLifecycle = ((PlayerTickPhaseStamp) player)
                .fabrolia$playerLifecycleEpoch();
        if (!lifecycleEpochMatchesPendingAuthority(
                currentLifecycle, owner.lifecycleEpoch(),
                allowAuditedLifecycleEpochAdvance)) {
            return false;
        }
        long lifecycle = allowAuditedLifecycleEpochAdvance
                ? owner.lifecycleEpoch()
                : currentLifecycle;
        return rs != null && enabledForAsync(rs) && rs.level == player.level()
                && !island.retired && ownerFieldsMatch(owner, player, player.connection,
                island, domain, lifecycle,
                classifyPlayer(player))
                && ownerLiveWithoutRoster(
                        owner, player, allowAuditedLifecycleEpochAdvance);
    }

    private static boolean ownerLiveWithoutRoster(
            IslandPlayerTurnSession.OwnerStamp owner,
            ServerPlayer player,
            boolean allowAuditedLifecycleEpochAdvance) {
        long currentLifecycle = ((PlayerTickPhaseStamp) player)
                .fabrolia$playerLifecycleEpoch();
        if (!RegionalPlayerEntityTicksV2.enabled() || owner.playerIdentity() != player
                || owner.listenerIdentity() != player.connection
                || player.connection == null || player.connection.player != player
                || player.level() != ((IslandRegistry.PIsland) owner.islandIdentity()).domainLevel()
                || ((IslandRegistry.PIsland) owner.islandIdentity()).retired
                || owner.domainIdentity() != ((IslandRegistry.PIsland) owner.islandIdentity()).domain
                || !lifecycleEpochMatchesPendingAuthority(
                currentLifecycle, owner.lifecycleEpoch(),
                allowAuditedLifecycleEpochAdvance)
                || (player.isRemoved() && (!allowAuditedLifecycleEpochAdvance
                || player.getRemovalReason() != null))
                || player.isChangingDimension()) {
            return false;
        }
        return isCarpetFake(player)
                || (player.connection.hasClientLoaded() && !player.hasDisconnected());
    }

    /** Generic topology keeps exact epoch; an audited live mutation must advance it. */
    static boolean lifecycleEpochMatchesPendingAuthority(
            long currentLifecycle, long ownerLifecycle,
            boolean allowAuditedLifecycleEpochAdvance) {
        return allowAuditedLifecycleEpochAdvance
                ? currentLifecycle > ownerLifecycle
                : currentLifecycle == ownerLifecycle;
    }

    private static boolean eligible(ServerPlayer player, ServerLevel level,
                                    IslandPlayerTurnSession.PlayerKind kind) {
        PortalProcessor portalProcess = player.portalProcess;
        if (player.level() != level || player.isRemoved() || player.isChangingDimension()
                || ParallelProcessor.isForcedMainThread(player)
                || player.getVehicle() != null || !player.getPassengers().isEmpty()
                || player.getCamera() != player
                || (portalProcess != null
                && !(portalProcess.portal instanceof NetherPortalBlock))
                || player.connection == null || player.connection.player != player
                || !PlayerTickKnownModEnvelope.idleForAsync(player, kind)) {
            return false;
        }
        if (player instanceof ParallelProcessor.TickGuard guard && guard.fabrolia$isPortalPending()) {
            return false;
        }
        return kind == IslandPlayerTurnSession.PlayerKind.CARPET
                || (player.connection.hasClientLoaded() && !player.hasDisconnected());
    }

    /**
     * Quiescent-Main snapshot used only to decide whether an island-safe FIFO head can
     * be handed back to the autonomous owner.  This mirrors the physical-listener gate:
     * a stale/missing topology or compatibility envelope simply keeps that packet on
     * Main; it never grants a speculative worker owner.
     */
    private static boolean islandVenueAvailable(ServerPlayer player) {
        if (player == null || !(player.level() instanceof ServerLevel level)) return false;
        RegionizedServer runtime = RegionScheduler.forLevel(level);
        IslandPlayerTurnSession.PlayerKind kind = classifyPlayer(player);
        if (kind == null || !enabledForAsync(runtime) || !eligible(player, level, kind)) {
            return false;
        }
        long chunk = player.chunkPosition().pack();
        IslandRegistry.PIsland island = runtime.islandOwnerOfChunk(chunk);
        Region region = runtime.regionizer().peekRegionForChunk(chunk);
        return island != null && !island.retired && island.domain != null
                && island.domain.owner == island && region != null
                && region.state() == RegionState.ACTIVE
                && region.domain == island.domain
                && runtime.regionMatches(region, player.chunkPosition());
    }

    /** Internal routed mutations and audited Main packets may never resume on an island. */
    private static boolean requiresMainIngress(PlayerState state, InboundPacket inbound) {
        return inbound == null || isMainEntityOrdinal(inbound) || inbound.listenerTick != null
                || inbound == state.mainInteractionPacket || inbound.isRoutedMutation()
                || hasInvalidBlockAckSequence(inbound.packet)
                || AsyncIslandPacketPolicy.usesMainFifo(inbound.packet);
    }

    /**
     * @return true when an ordered ISLAND->MAIN handoff owns this ordinal or an
     * authenticated disconnect cancels it before entry; neither may reach MAIN_SYNC.
     */
    private static boolean markMainFallback(ServerPlayer player, boolean scheduleEntityTick) {
        MAIN_FALLBACKS.increment();
        if (!(player instanceof AsyncIslandPlayerAccess) || !enabledForAsync()) return false;
        PlayerState state = state(player);
        ServerGamePacketListenerImpl listener = player.connection;
        if (listener == null || listener.player != player) return false;
        PlayerState bound = bindListenerState(listener);
        if (bound != null) state = bound;
        synchronized (state) {
            IslandPlayerTurnSession.View view = state.session.view();
            if (view.phase() == IslandPlayerTurnSession.Phase.DISCONNECTED) {
                if (consumeExactTerminalPreBodyOrdinalLocked(
                        state, player, listener)) {
                    return true;
                }
                state.eligible = false;
                throw new IllegalStateException(
                        "A5 entity ordinal reached an unauthenticated terminal session");
            }
            if (state.mainDisconnectPending) {
                state.eligible = false;
                if (isExactPendingPreBodyDisconnectLocked(state, player, listener, view)) {
                    // Cadence can reach this still-listed player before its queued
                    // callback runs, including while the channel is still closing,
                    // or while physical teardown awaits its existing handoff closer.
                    // Consume only this ordinal; preserve the suffix and callback.
                    return true;
                }
                throw new IllegalStateException(
                        "A5 entity ordinal overlapped an unauthenticated pending player disconnect");
            }
            if (view.owner() == null) {
                state.eligible = false;
                if (!scheduleEntityTick) return false;
                if (view.phase() != IslandPlayerTurnSession.Phase.MAIN) {
                    throw new IllegalStateException(
                            "A5 detached Main fallback observed phase " + view.phase());
                }
                if (state.listenerGate != null && state.listenerGate != listener) {
                    throw new IllegalStateException(
                            "A5 detached Main fallback crossed listener identity");
                }
                state.listenerGate = listener;
                enqueueMainIngressLocked(state, listener,
                        newMainEntityOrdinalLocked(state, listener, player));
                if (!state.mainIngressScheduled
                        && !(state.netherReservation != null
                        && state.netherReservation.player == player
                        && state.netherReservation.listener == listener
                        && state.mainIngress.peekFirst() == state.netherReservation.marker)) {
                    throw new IllegalStateException(
                            "A5 Main listener chain could not carry its owed entity body");
                }
                return true;
            }

            // A later autonomous epoch is legal while the unique handoff closer is
            // waiting/running.  The old island prefix will be prepended before this E
            // marker; packets accepted after this monitor edge append behind it.
            if (isMainHandoffPhase(view.phase())) {
                enqueueMainIngressLocked(state, listener,
                        newMainEntityOrdinalLocked(state, listener, player));
                return true;
            }

            // An entered island body is never retried on Main.  A distinct island unit
            // cannot legitimately reach the same player until that body terminalizes.
            if (isActiveIslandTurnPhase(view.phase())) {
                throw new IllegalStateException(
                        "A5 entity ordinal overlapped another island body");
            }

            // Publish the exact E boundary before preparing the closer.  That closes
            // packet admission into the old session FIFO at this precise ordinal edge.
            enqueueMainIngressLocked(state, listener,
                    newMainEntityOrdinalLocked(state, listener, player));
            if (netherOwnsQuarantinedSuffixLocked(state, player)) return true;
            if (scheduleMainHandoffLocked(state, player, false)) {
                return true;
            }
            state.eligible = false;
            throw new IllegalStateException(
                    "A5 owed entity ordinal could not enter its ordered Main handoff");
        }
    }

    /** Allocate one exact ordinal marker while the listener-state monitor is held. */
    private static InboundPacket newMainEntityOrdinalLocked(
            PlayerState state, ServerGamePacketListenerImpl listener,
            ServerPlayer player) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException(
                    "A5 Main entity ordinal allocation without state lock");
        }
        if (state.owedMainEntityOrdinals >= MAX_OWED_MAIN_ENTITY_ORDINALS) {
            throw new IllegalStateException(
                    "A5 owed Main entity ordinal FIFO exceeded "
                            + MAX_OWED_MAIN_ENTITY_ORDINALS + " entries");
        }
        long ordinal = state.nextMainEntityOrdinal;
        if (ordinal <= 0L || ordinal == Long.MAX_VALUE) {
            throw new IllegalStateException("A5 Main entity ordinal sequence exhausted");
        }
        state.nextMainEntityOrdinal = ordinal + 1L;
        return InboundPacket.entityOrdinal(listener, player, ordinal);
    }

    private static boolean isMainEntityOrdinal(InboundPacket inbound) {
        return inbound != null && inbound.entityOrdinal();
    }

    private static ServerPlayer fallbackPlayer(InboundPacket inbound) {
        if (!isMainEntityOrdinal(inbound) || inbound.fallbackPlayer() == null) {
            throw new IllegalStateException("A5 Main entity marker lost its player");
        }
        return inbound.fallbackPlayer();
    }

    private static void accountMainEntityOrdinalAppendLocked(
            PlayerState state, InboundPacket inbound) {
        if (!isMainEntityOrdinal(inbound)) return;
        if (state.owedMainEntityOrdinals >= MAX_OWED_MAIN_ENTITY_ORDINALS) {
            throw new IllegalStateException(
                    "A5 owed Main entity ordinal accounting overflow");
        }
        state.owedMainEntityOrdinals++;
    }

    private static void accountMainEntityOrdinalCompletionLocked(
            PlayerState state, InboundPacket inbound) {
        if (!isMainEntityOrdinal(inbound) || state.owedMainEntityOrdinals <= 0) {
            throw new IllegalStateException(
                    "A5 owed Main entity ordinal accounting underflow");
        }
        state.owedMainEntityOrdinals--;
    }

    private static boolean isActiveMainEntityOrdinalLocked(
            PlayerState state, InboundPacket inbound) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException(
                    "A5 active Main entity ordinal read without state lock");
        }
        return state.mainEntityFallbackPending
                && state.activeMainEntityFallback == inbound
                && state.mainIngress.peekFirst() == inbound;
    }

    private static void convertDeferredFallbackToMarkerLocked(
            PlayerState state, ServerGamePacketListenerImpl listener) {
        if (!Thread.holdsLock(state) || !state.fallbackEntityAfterHandoff) return;
        ServerPlayer current = listener.player;
        if (current == null) {
            throw new IllegalStateException(
                    "A5 deferred entity fallback lost its listener player");
        }
        state.fallbackEntityAfterHandoff = false;
        enqueueMainIngressLocked(state, listener,
                newMainEntityOrdinalLocked(state, listener, current));
    }

    private static void verifyMainEntityOrdinalAccountingLocked(PlayerState state) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException(
                    "A5 Main entity ordinal audit without state lock");
        }
        int observed = 0;
        for (InboundPacket inbound : state.mainIngress) {
            if (isMainEntityOrdinal(inbound)) observed++;
        }
        if (observed != state.owedMainEntityOrdinals) {
            throw new IllegalStateException(
                    "A5 Main entity ordinal ledger diverged: queue=" + observed
                            + ", owed=" + state.owedMainEntityOrdinals);
        }
        if (state.mainEntityFallbackPending
                != (state.activeMainEntityFallback != null)) {
            throw new IllegalStateException(
                    "A5 Main entity ordinal active marker diverged");
        }
        if (state.activeMainEntityFallback != null
                && state.mainIngress.peekFirst() != state.activeMainEntityFallback) {
            throw new IllegalStateException(
                    "A5 active Main entity ordinal left the FIFO head");
        }
        if (state.mainEntityFallbackAdmitted
                && !state.mainEntityFallbackPending) {
            throw new IllegalStateException(
                    "A5 admitted Main entity ordinal lost its active marker");
        }
    }

    private static void completeMainEntityOrdinalLocked(
            PlayerState state, InboundPacket inbound) {
        if (!isActiveMainEntityOrdinalLocked(state, inbound)) {
            throw new IllegalStateException(
                    "A5 Main entity ordinal completion lost its FIFO head");
        }
        if (state.mainIngress.pollFirst() != inbound) {
            throw new IllegalStateException(
                    "A5 Main entity ordinal FIFO changed under state lock");
        }
        accountMainEntityOrdinalCompletionLocked(state, inbound);
        state.mainEntityFallbackAdmitted = false;
        state.mainEntityFallbackPending = false;
        state.activeMainEntityFallback = null;
    }

    private static boolean scheduleMainHandoff(PlayerState state, ServerPlayer player,
                                               boolean scheduleEntityTick) {
        synchronized (state) {
            return scheduleMainHandoffLocked(state, player, scheduleEntityTick);
        }
    }

    private static boolean scheduleMainHandoffLocked(
            PlayerState state, ServerPlayer player, boolean scheduleEntityTick) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException("A5 Main handoff publication without state lock");
        }
        RegionScheduler.throwWorkerFailureIfAny();
        IslandPlayerTurnSession.View view = state.session.view();
        if (isMainHandoffPhase(view.phase())) {
            if (scheduleEntityTick) state.fallbackEntityAfterHandoff = true;
            return true;
        }
        IslandPlayerTurnSession.OwnerStamp owner = view.owner();
        if (owner == null || owner.playerIdentity() != player) return false;
        Optional<IslandPlayerTurnSession.MainHandoff<InboundPacket>> prepared =
                state.session.prepareMainHandoff(owner);
        if (prepared.isEmpty()) return false;
        return publishPreparedMainHandoffLocked(
                state, player, prepared.get(), scheduleEntityTick);
    }

    private static boolean schedulePacketPrefixMainHandoffLocked(
            PlayerState state, ServerPlayer player,
            IslandPlayerTurnSession.EarlyPermit<InboundPacket> permit) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException(
                    "A5 packet-prefix handoff publication without state lock");
        }
        RegionScheduler.throwWorkerFailureIfAny();
        if (state.mainDisconnectPending
                || !explicitLifecycleMutationPending(state)
                && state.mainInteractionPacket == null) {
            return false;
        }
        Optional<IslandPlayerTurnSession.MainHandoff<InboundPacket>> prepared =
                state.session.preparePacketPrefixMainHandoff(permit);
        if (prepared.isEmpty()) return false;
        state.fallbackEntityAfterHandoff = true;
        return publishPreparedMainHandoffLocked(
                state, player, prepared.get(), true);
    }

    private static boolean publishPreparedMainHandoffLocked(
            PlayerState state, ServerPlayer player,
            IslandPlayerTurnSession.MainHandoff<InboundPacket> handoff,
            boolean scheduleEntityTick) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException(
                    "A5 prepared Main handoff publication without state lock");
        }
        MainHandoffContinuation closer = null;
        try {
            var server = ParallelProcessor.getServer();
            if (server == null) throw new IllegalStateException("A5 handoff lost its server");
            Object sourceIdentity = handoff.oldOwner().islandIdentity();
            if (!(sourceIdentity instanceof IslandRegistry.PIsland source)) {
                throw new IllegalStateException(
                        "A5 Main handoff lost its source island identity");
            }
            validateMainHandoffSource(handoff.oldOwner(), source);
            closer = new MainHandoffContinuation(
                    server, source, state, player, handoff, scheduleEntityTick);
            if (state.lifecycleMutationBodyDepth != 0) {
                if (state.pendingLifecycleHandoff != null) {
                    throw new IllegalStateException(
                            "A5 lifecycle body retained two Main handoff continuations");
                }
                MainHandoffContinuation staged = closer;
                RegionScheduler.commitSuccessIfHealthy(() -> {
                    acceptPreparedMainHandoffLocked(state, handoff);
                    state.pendingLifecycleHandoff = staged;
                });
                return true;
            }
            closer.publishOnce();
            return true;
        } catch (Throwable publicationFailure) {
            // cancelBeforePublication serializes an enqueue-then-throw scheduler
            // with its possibly queued Runnable before the exact session rollback.
            Throwable failure = publishFatalFailure(publicationFailure);
            if (closer == null) {
                try {
                    failure = abortUnfinishedMainHandoff(state, handoff, failure);
                } catch (Throwable cleanup) {
                    failure = merge(failure, cleanup);
                }
            } else {
                failure = cancelAndAbortMainHandoff(closer, failure);
            }
            throw AsyncIslandPlayerTicks.<RuntimeException>rethrowAs(
                    publishFatalFailure(failure));
        }
    }

    /** Fixed, allocation-free session CAS used only from a scheduler healthy cut. */
    private static void acceptPreparedMainHandoffLocked(
            PlayerState state,
            IslandPlayerTurnSession.MainHandoff<InboundPacket> handoff) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException(
                    "A5 Main handoff acceptance without PlayerState lock");
        }
        if (!state.session.acceptMainHandoff(handoff)) {
            throw new IllegalStateException(
                    "A5 prepared Main handoff lost its exact source frame");
        }
    }

    /**
     * Current-tick OFFERING receipt for a terminal player handoff.  The queued task
     * may be physically visible before the terminal success cut, but cannot enter its
     * handoff body until that cut publishes ACCEPTED.  Cancellation is a terminal
     * no-body cleanup edge; the caller already selected and propagates its exact W.
     */
    private static final class TurnHandoffReceipt implements Runnable {
        private final MinecraftServer server;
        private final PlayerState state;
        private final ServerPlayer player;
        /** Non-null selects the post-physics close; null selects pending ingress. */
        private final PendingLate postPhysics;
        private volatile int executionState;
        private Throwable cancellationFailure;
        private Thread publisherThread;
        private volatile boolean inlineAttempt;

        private static final int NEW = 0;
        private static final int OFFERING = 1;
        private static final int ACCEPTED = 2;
        private static final int RUNNING = 3;
        private static final int TERMINAL = 4;
        private static final int CANCELLED = 5;

        private TurnHandoffReceipt(
                MinecraftServer server, PlayerState state,
                ServerPlayer player, PendingLate postPhysics) {
            this.server = Objects.requireNonNull(server, "server");
            this.state = Objects.requireNonNull(state, "state");
            this.player = Objects.requireNonNull(player, "player");
            this.postPhysics = postPhysics;
        }

        /** Fallible queue offer; caller still owns PlayerState and the running permit. */
        private synchronized void preparePublication() {
            if (executionState != NEW) {
                throw new IllegalStateException(
                        "A5 terminal handoff receipt offered more than once");
            }
            publisherThread = Thread.currentThread();
            executionState = OFFERING;
            try {
                server.schedule(new TickTask(server.getTickCount(), this));
            } catch (Throwable publicationFailure) {
                // Keep the receipt OFFERING until the owner installs this exact
                // failure as W; only its subsequent cancelPublication may wake an
                // enqueue-then-throw task into the no-body CANCELLED edge.
                throw AsyncIslandPlayerTicks.<RuntimeException>rethrowAs(
                        publicationFailure);
            }
            if (executionState != OFFERING) {
                throw new IllegalStateException(
                        "A5 terminal handoff ran before receipt acceptance");
            }
        }

        /** Fixed receipt write used only as the last operation of the healthy cut. */
        private synchronized void acceptPublication() {
            if (executionState != OFFERING) {
                throw new IllegalStateException(
                        "A5 terminal handoff lost its OFFERING receipt");
            }
            executionState = ACCEPTED;
        }

        /** Failure-path no-body cancellation; preserves the caller's selected W. */
        private synchronized Throwable cancelPublication(Throwable primary) {
            if (executionState == NEW || executionState == OFFERING) {
                cancellationFailure = primary;
                executionState = CANCELLED;
                return primary;
            }
            if (executionState == CANCELLED || executionState == TERMINAL) {
                return primary;
            }
            return merge(primary, new IllegalStateException(
                    "A5 terminal handoff cancellation crossed accepted body entry"));
        }

        private void runInlineIfNeeded() {
            if (inlineAttempt) run();
        }

        @Override
        public void run() {
            if (executionState == OFFERING
                    && Thread.currentThread() == publisherThread) {
                // Defensive direct scheduler: the physical offer returns first; the
                // publisher invokes us once outside PlayerState/lifecycle locks.
                inlineAttempt = true;
                return;
            }
            while (executionState == OFFERING) {
                Thread.onSpinWait();
            }

            Throwable failure = null;
            synchronized (this) {
                if (executionState == CANCELLED) {
                    executionState = TERMINAL;
                    return;
                }
                if (executionState != ACCEPTED) {
                    failure = publishFatalFailure(new IllegalStateException(
                            "A5 terminal handoff receipt executed more than once"));
                    executionState = TERMINAL;
                } else {
                    executionState = RUNNING;
                }
            }

            if (failure == null) {
                try {
                    RegionScheduler.runSuccessBodyIfHealthy(() -> {
                        if (postPhysics != null) {
                            schedulePostPhysicsMainHandoff(postPhysics);
                        } else {
                            schedulePendingMainIngressHandoff(state, player);
                        }
                    });
                } catch (Throwable thrown) {
                    failure = publishFatalFailure(thrown);
                }
            }
            synchronized (this) {
                executionState = TERMINAL;
            }
            if (failure == null) {
                try {
                    // TERMINAL is mandatory task cleanup.  A peer W selected
                    // while it was published still forbids this physical-Main
                    // continuation from returning normally to BlockableEventLoop.
                    RegionScheduler.commitSuccessIfHealthy(() -> {});
                } catch (Throwable peerFailure) {
                    failure = publishFatalFailure(peerFailure);
                }
            }
            if (failure != null) {
                throwUnchecked(publishFatalFailure(failure));
            }
        }
    }

    /**
     * One unique physical-Main continuation for a quiescent island-to-Main close.
     * MAIN_HANDOFF is already the exact per-player exclusion edge: after the owner
     * body terminalizes, no later island turn can reserve that player. A lifecycle
     * closer is therefore retained until its exact wrapper-body edge; every other
     * closer is published only after beginMainHandoff observed ISLAND_READY. The
     * physical-Main Runnable executes once without polling the whole source-island
     * claim and without returning a retry successor.
     */
    private static final class LifecycleHandoffBatch implements Runnable {
        private final MinecraftServer server;
        private final List<LifecycleBodyToken> tokens;
        private final int markedBodyCount;
        private final int readyCount;
        /** Writes are monitor-serialized; volatile lets an offered Main task await its receipt. */
        private volatile int executionState;
        private Throwable cancellationFailure;
        private Thread publisherThread;
        private volatile boolean inlineAttempt;

        private static final int NEW = 0;
        private static final int PUBLISHING = 1;
        private static final int PUBLISHED = 2;
        private static final int RUNNING = 3;
        private static final int TERMINAL = 4;
        private static final int CANCELLED = 5;

        private LifecycleHandoffBatch(
                MinecraftServer server, List<LifecycleBodyToken> tokens,
                int markedBodyCount, int readyCount) {
            this.server = Objects.requireNonNull(server, "server");
            this.tokens = Objects.requireNonNull(tokens, "tokens");
            this.markedBodyCount = markedBodyCount;
            this.readyCount = readyCount;
        }

        private void publishOnce() {
            preparePublication();
            try {
                RegionScheduler.commitSuccessIfHealthy(this::acceptPublication);
            } catch (Throwable publicationFailure) {
                Throwable winner = publishFatalFailure(publicationFailure);
                winner = cancelAndAbortAll(winner);
                throw AsyncIslandPlayerTicks.<RuntimeException>rethrowAs(winner);
            }
            if (inlineAttempt) run();
        }

        private synchronized void preparePublication() {
            if (executionState != NEW) {
                throw new IllegalStateException(
                        "A5 lifecycle handoff batch published more than once");
            }
            publisherThread = Thread.currentThread();
            executionState = PUBLISHING;
            try {
                server.schedule(new TickTask(server.getTickCount(), this));
            } catch (Throwable publicationFailure) {
                Throwable winner = publishFatalFailure(publicationFailure);
                if (executionState == PUBLISHING) {
                    cancellationFailure = winner;
                    executionState = CANCELLED;
                }
                throw AsyncIslandPlayerTicks.<RuntimeException>rethrowAs(
                        winner);
            }
            if (executionState != PUBLISHING) {
                throw new IllegalStateException(
                        "A5 lifecycle handoff batch ran before publication acceptance");
            }
        }

        /** Fixed receipt CAS used only inside the scheduler success-vs-W cut. */
        private synchronized void acceptPublication() {
            if (executionState != PUBLISHING) {
                throw new IllegalStateException(
                        "A5 lifecycle handoff batch lost its publication receipt");
            }
            executionState = PUBLISHED;
        }

        @Override
        public void run() {
            if (executionState == PUBLISHING
                    && Thread.currentThread() == publisherThread) {
                // Defensive direct scheduler: return from schedule() without
                // body entry, then replay exactly once after receipt acceptance.
                inlineAttempt = true;
                return;
            }
            while (executionState == PUBLISHING) {
                Thread.onSpinWait();
            }
            Throwable failure = null;
            synchronized (this) {
                if (executionState == CANCELLED) {
                    failure = cancellationFailure;
                    if (failure == null) {
                        failure = new IllegalStateException(
                                "A5 cancelled lifecycle handoff batch lost its exact failure");
                    }
                    // Even a structurally impossible cancellation state is W before
                    // the first staged closer is touched by terminal rollback.
                    failure = publishFatalFailure(failure);
                } else if (executionState != PUBLISHED) {
                    failure = publishFatalFailure(new IllegalStateException(
                            "A5 lifecycle handoff batch executed more than once"));
                } else {
                    executionState = RUNNING;
                }
                try {
                    if (failure == null) {
                        RegionScheduler.throwWorkerFailureIfAny();
                        if (!server.isSameThread()) {
                            throw new IllegalStateException(
                                    "A5 lifecycle handoff batch ran off physical Main");
                        }
                        int executed = 0;
                        for (int index = 0; index < markedBodyCount; index++) {
                            LifecycleBodyToken token = tokens.get(index);
                            MainHandoffContinuation closer = token.completedHandoff;
                            if (closer == null) continue;
                            // Keep the token rollback-capable until this exact
                            // closer wins body entry against generation W.  If W
                            // won first, the lambda is never entered and the
                            // unconditional finally below cancels/aborts it.
                            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                                closer.admitLifecycleBatchExecution();
                                token.completedHandoff = null;
                                closer.run();
                            });
                            RegionScheduler.throwWorkerFailureIfAny();
                            executed++;
                        }
                        if (executed != readyCount) {
                            throw new IllegalStateException(
                                    "A5 lifecycle handoff batch lost a staged continuation");
                        }
                    }
                } catch (Throwable thrown) {
                    // Close the shared start gate before terminal rollback. Main
                    // cannot run another task here, and workers must not start a
                    // successor while this batch exhausts its remaining closers.
                    failure = publishFatalFailure(thrown);
                } finally {
                    failure = cancelAndAbortAllLocked(failure);
                    executionState = TERMINAL;
                }
            }
            if (failure == null) {
                try {
                    // Rollback exhaustion and TERMINAL publication are mandatory
                    // cleanup.  Linearize the task's normal return only afterward.
                    RegionScheduler.commitSuccessIfHealthy(() -> {});
                } catch (Throwable peerFailure) {
                    failure = publishFatalFailure(peerFailure);
                }
            }
            if (failure != null) {
                throw AsyncIslandPlayerTicks.<RuntimeException>rethrowAs(
                        publishFatalFailure(failure));
            }
        }

        private synchronized Throwable cancelAndAbortAll(Throwable primary) {
            if (executionState == NEW || executionState == PUBLISHING
                    || executionState == PUBLISHED) {
                cancellationFailure = primary;
                executionState = CANCELLED;
            }
            return cancelAndAbortAllLocked(primary);
        }

        private Throwable cancelAndAbortAllLocked(Throwable primary) {
            return cancelStagedLifecycleHandoffs(tokens, markedBodyCount, primary);
        }
    }

    private static final class MainHandoffContinuation implements Runnable {
        private final MinecraftServer server;
        private final IslandRegistry.PIsland source;
        private final PlayerState state;
        private final ServerPlayer player;
        private final IslandPlayerTurnSession.MainHandoff<InboundPacket> handoff;
        private final boolean scheduleEntityTick;
        /** Monitor writes; volatile lets a physically visible task await its receipt. */
        private volatile int executionState;
        /** Exact publication/body failure carried by a terminally cancelled task. */
        private Throwable cancellationFailure;
        private Thread publisherThread;
        private volatile boolean inlineAttempt;

        private static final int NEW = 0;
        private static final int OFFERING = 1;
        private static final int ACCEPTED = 2;
        private static final int RUNNING = 3;
        private static final int TERMINAL = 4;
        private static final int CANCELLED = 5;

        private MainHandoffContinuation(
                MinecraftServer server, IslandRegistry.PIsland source,
                PlayerState state, ServerPlayer player,
                IslandPlayerTurnSession.MainHandoff<InboundPacket> handoff,
                boolean scheduleEntityTick) {
            this.server = server;
            this.source = source;
            this.state = state;
            this.player = player;
            this.handoff = handoff;
            this.scheduleEntityTick = scheduleEntityTick;
        }

        /** Marks a staged closer immediately before its unique batch invocation. */
        private synchronized void admitLifecycleBatchExecution() {
            if (executionState != NEW) {
                throw new IllegalStateException(
                        "A5 lifecycle handoff continuation lost its staged state");
            }
            executionState = ACCEPTED;
        }

        /**
         * Offer exactly once, then accept both the prepared session transaction and
         * this Runnable receipt in one generation success-vs-W cut.  The physical
         * task may observe OFFERING, but cannot enter the handoff body until the cut
         * writes ACCEPTED last.  Inline/offer-then-throw ambiguity stays OFFERING so
         * the caller can select exact W, cancel, and roll back without replay.
         */
        private void publishOnce() {
            synchronized (this) {
                if (executionState != NEW) {
                    throw new IllegalStateException(
                            "A5 Main handoff continuation published more than once");
                }
                publisherThread = Thread.currentThread();
                executionState = OFFERING;
                server.schedule(new TickTask(server.getTickCount(), this));
                if (inlineAttempt) {
                    throw new IllegalStateException(
                            "A5 Main handoff queue executed inline before acceptance");
                }
                if (executionState != OFFERING) {
                    throw new IllegalStateException(
                            "A5 Main handoff ran before receipt acceptance");
                }
            }
            RegionScheduler.commitSuccessIfHealthy(this::acceptPublication);
        }

        /** Fixed receipt write, deliberately last in the shared healthy cut. */
        private synchronized void acceptPublication() {
            if (executionState != OFFERING) {
                throw new IllegalStateException(
                        "A5 Main handoff lost its OFFERING receipt");
            }
            acceptPreparedMainHandoffLocked(state, handoff);
            executionState = ACCEPTED;
        }

        @Override
        public void run() {
            if (executionState == OFFERING
                    && Thread.currentThread() == publisherThread) {
                // A direct scheduler is an ambiguous offer, never authority to run
                // inline under PlayerState. The publisher selects W and cancels.
                inlineAttempt = true;
                return;
            }
            while (executionState == OFFERING) {
                Thread.onSpinWait();
            }
            Throwable failure = null;
            synchronized (this) {
                if (executionState == CANCELLED) {
                    failure = cancellationFailure;
                    if (failure == null) {
                        failure = new IllegalStateException(
                                "A5 cancelled Main handoff lost its exact failure");
                    }
                    // The cancellation invariant is authoritative before session
                    // rollback can expose any ownership state to another body.
                    failure = publishFatalFailure(failure);
                } else if (executionState != ACCEPTED) {
                    failure = publishFatalFailure(new IllegalStateException(
                            "A5 Main handoff continuation executed more than once"));
                } else {
                    executionState = RUNNING;
                }
                try {
                    if (failure == null) {
                        RegionScheduler.throwWorkerFailureIfAny();
                        if (!server.isSameThread()) {
                            throw new IllegalStateException(
                                    "A5 Main handoff continuation ran off physical Main");
                        }
                        if (Thread.holdsLock(state)) {
                            throw new IllegalStateException(
                                    "A5 Main handoff continuation executed inline under state lock");
                        }
                        if (lifecycleMutationBodyActive(state)) {
                            throw new IllegalStateException(
                                    "A5 Main handoff continuation overtook its lifecycle body");
                        }
                        validateMainHandoffSource(handoff.oldOwner(), source);
                        RegionScheduler.runSuccessBodyIfHealthy(() ->
                                finishMainHandoff(
                                        state, player, handoff, scheduleEntityTick));
                    }
                } catch (Throwable thrown) {
                    // Publish before rollback so no other Fabrolia body can enter
                    // while this exact continuation exhausts terminal cleanup.
                    failure = publishFatalFailure(thrown);
                } finally {
                    // A failure from the first FIFO-copy step can leave
                    // MAIN_HANDOFF intact. Roll it back before this unique Runnable
                    // becomes terminal; successful MAIN publication is a no-op here.
                    try {
                        failure = containMainHandoffFailure(
                                state, handoff, failure);
                    } catch (Throwable cleanup) {
                        failure = merge(failure, cleanup);
                    }
                    executionState = TERMINAL;
                }
            }
            if (failure == null) {
                try {
                    // Successful containment is a no-op, but TERMINAL remains
                    // mandatory cleanup.  Do not let a peer W in that tail turn
                    // this queued Main continuation into a normal task return.
                    RegionScheduler.commitSuccessIfHealthy(() -> {});
                } catch (Throwable peerFailure) {
                    failure = publishFatalFailure(peerFailure);
                }
            }
            if (failure != null) {
                throw AsyncIslandPlayerTicks.<RuntimeException>rethrowAs(
                        publishFatalFailure(failure));
            }
        }

        /** Cancel an unentered publication, including an accepted failed-generation carrier. */
        private synchronized void cancelBeforePublication(Throwable primary) {
            if (executionState == NEW
                    || executionState == OFFERING
                    || executionState == ACCEPTED) {
                cancellationFailure = primary;
                executionState = CANCELLED;
                return;
            }
            if (executionState != CANCELLED && executionState != TERMINAL) {
                throw new IllegalStateException(
                        "A5 Main handoff cancellation crossed its active body");
            }
        }
    }

    /**
     * Cancel a never-entered continuation, then exhaust the exact session rollback.
     * The caller's failure always remains primary; cancellation/rollback failures
     * are diagnostic suppressed state only.
     */
    private static Throwable cancelStagedLifecycleHandoffs(
            List<LifecycleBodyToken> tokens, int markedBodyCount, Throwable primary) {
        Throwable failure = primary;
        for (int index = markedBodyCount - 1; index >= 0; index--) {
            try {
                LifecycleBodyToken token = tokens.get(index);
                MainHandoffContinuation closer = token.completedHandoff;
                token.completedHandoff = null;
                if (closer != null) {
                    failure = cancelAndAbortMainHandoff(closer, failure);
                }
            } catch (Throwable cleanup) {
                failure = merge(failure, cleanup);
            }
        }
        return failure;
    }

    private static Throwable cancelAndAbortMainHandoff(
            MainHandoffContinuation closer, Throwable primary) {
        Throwable failure = primary;
        try {
            closer.cancelBeforePublication(failure);
        } catch (Throwable cleanup) {
            failure = merge(failure, cleanup);
        }
        try {
            failure = abortUnfinishedMainHandoff(
                    closer.state, closer.handoff, failure);
        } catch (Throwable cleanup) {
            failure = merge(failure, cleanup);
        }
        return failure;
    }

    private static void validateMainHandoffSource(
            IslandPlayerTurnSession.OwnerStamp owner,
            IslandRegistry.PIsland source) {
        if (owner.islandIdentity() != source
                || owner.islandId() != source.id
                || owner.islandGeneration() != source.gen
                || owner.domainIdentity() != source.domain) {
            throw new IllegalStateException(
                    "A5 Main handoff crossed its captured source island generation/domain");
        }
    }

    /** Roll back an exact failed closer before its failure escapes Main. */
    private static Throwable containMainHandoffFailure(
            PlayerState state,
            IslandPlayerTurnSession.MainHandoff<InboundPacket> handoff,
            Throwable failure) {
        if (failure == null) return null;
        return abortUnfinishedMainHandoff(state, handoff, failure);
    }

    /** Allocation-free exact rollback used only on a terminal failure path. */
    private static Throwable abortUnfinishedMainHandoff(
            PlayerState state,
            IslandPlayerTurnSession.MainHandoff<InboundPacket> handoff,
            Throwable failure) {
        synchronized (state) {
            if (state.session.abortMainHandoff(handoff)) {
                // Only the exact MAIN_HANDOFF -> ISLAND_READY rollback needs an
                // admission cut while the caller reports the failed closer.
                state.eligible = false;
                state.lifecycleInvalidated = true;
                return failure;
            }
            IslandPlayerTurnSession.Phase phase = state.session.phase();
            if (phase == IslandPlayerTurnSession.Phase.MAIN_HANDOFF) {
                return merge(failure, new IllegalStateException(
                        "A5 rejected Main-handoff continuation stranded its current claim"));
            }
            if (phase == IslandPlayerTurnSession.Phase.MAIN_DRAINING) {
                return merge(failure, new IllegalStateException(
                        "A5 Main-handoff failure stranded its detached FIFO before MAIN"));
            }
            // MAIN/DISCONNECTED (or an already rolled-back stale claim) is a
            // terminal result owned by another exact edge. Never poison it with
            // lifecycleInvalidated merely because a later publication step threw.
            return failure;
        }
    }

    /**
     * Append to the listener-lifetime boundary queue. While an island or its closer
     * owns the session, the queue itself is the admission cut; scheduling a probing
     * Main task here would let that task run before the closer and add one guaranteed
     * physical-server tick of latency. Once the session is quiescent MAIN, only an
     * exact Main-required head arms the drain.
     */
    private static void enqueueMainIngressLocked(PlayerState state,
                                                 ServerGamePacketListenerImpl listener,
                                                 InboundPacket inbound) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException("A5 Main ingress publication without state lock");
        }
        if (state.listenerGate != null && state.listenerGate != listener) {
            throw new IllegalStateException("A5 Main ingress crossed listener identity");
        }
        if (isMainEntityOrdinal(inbound)) {
            if (inbound.listener() != listener
                    || fallbackPlayer(inbound).connection != listener) {
                throw new IllegalStateException(
                        "A5 Main entity ordinal crossed listener/player identity");
            }
            if (state.owedMainEntityOrdinals >= MAX_OWED_MAIN_ENTITY_ORDINALS) {
                throw new IllegalStateException(
                        "A5 owed Main entity ordinal FIFO exceeded "
                                + MAX_OWED_MAIN_ENTITY_ORDINALS + " entries");
            }
        }
        state.listenerGate = listener;
        state.mainIngress.addLast(inbound);
        accountMainEntityOrdinalAppendLocked(state, inbound);
        armMainIngressIfRequiredLocked(state, listener);
    }

    private static void prependMainIngressLocked(PlayerState state,
                                                 ServerGamePacketListenerImpl listener,
                                                 List<IslandPlayerTurnSession.PacketEnvelope<InboundPacket>> suffix) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException("A5 Main ingress prepend without state lock");
        }
        if (state.listenerGate != null && state.listenerGate != listener) {
            throw new IllegalStateException("A5 Main ingress suffix crossed listener identity");
        }
        state.listenerGate = listener;
        for (IslandPlayerTurnSession.PacketEnvelope<InboundPacket> envelope : suffix) {
            InboundPacket inbound = envelope.packet();
            if (isMainEntityOrdinal(inbound) || inbound.listenerTick != null
                    || inbound.listener != listener || inbound.isRoutedMutation()) {
                throw new IllegalStateException(
                        "A5 Main ingress suffix has stale/internal listener work");
            }
        }
        for (int index = suffix.size() - 1; index >= 0; index--) {
            InboundPacket inbound = suffix.get(index).packet();
            state.mainIngress.addFirst(inbound);
        }
        // The handoff is still MAIN_DRAINING here. Its closer first publishes MAIN,
        // then makes one scheduling decision from the complete detached FIFO.
    }

    /** True only while a routed mutation covered by the active causal cutoff exists. */
    private static boolean hasCausalRoutedCandidateLocked(PlayerState state) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException("A5 causal ingress decision without state lock");
        }
        if (state.causalRoutedCutoff == 0L) return false;
        for (InboundPacket candidate : state.mainIngress) {
            if (state.netherReservation != null
                    && candidate == state.netherReservation.marker) return false;
            if (candidate.isRoutedMutation()
                    && candidate.routedSequence() <= state.causalRoutedCutoff) {
                return true;
            }
        }
        state.causalRoutedCutoff = 0L;
        return false;
    }

    /** Caller holds state and the session has already published quiescent MAIN. */
    private static boolean shouldRunMainIngressHeadLocked(
            PlayerState state, ServerGamePacketListenerImpl listener) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException("A5 Main ingress route without state lock");
        }
        if (state.netherReservation != null
                && state.mainIngress.peekFirst() == state.netherReservation.marker) return false;
        InboundPacket head = state.mainIngress.peekFirst();
        if (head == null) return false;
        if (hasCausalRoutedCandidateLocked(state) || requiresMainIngress(state, head)) return true;
        ServerPlayer player = listener.player;
        return !islandPrefixHeadMayResumeLocked(
                state, listener, player, state.session.view());
    }

    /**
     * Main may inspect the live venue before arming. A decoded Netty producer
     * instead publishes one Main decision task without reading player state;
     * that task returns an island-safe suffix to the next exact island attach.
     */
    private static void armMainIngressIfRequiredLocked(
            PlayerState state, ServerGamePacketListenerImpl listener) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException("A5 Main ingress arm without state lock");
        }
        if (state.netherReservation != null
                && state.mainIngress.peekFirst() == state.netherReservation.marker) return;
        if (state.mainIngressScheduled || state.mainIngress.isEmpty()
                || state.session.view().phase() != IslandPlayerTurnSession.Phase.MAIN
                || RegionalPlayerEntityTicksV2.isPhysicalServerThread(
                ParallelProcessor.getServer())
                && !shouldRunMainIngressHeadLocked(state, listener)) {
            return;
        }
        state.mainIngressScheduled = true;
        scheduleMainIngressTaskLocked(state, listener);
    }

    private static void scheduleMainIngressTaskLocked(PlayerState state,
                                                      ServerGamePacketListenerImpl listener) {
        if (!Thread.holdsLock(state) || !state.mainIngressScheduled) {
            throw new IllegalStateException("A5 Main ingress schedule without ownership");
        }
        try {
            var server = ParallelProcessor.getServer();
            if (server == null) {
                throw new IllegalStateException("A5 Main ingress lost its server");
            }
            server.schedule(new TickTask(server.getTickCount(),
                    () -> runFailStopMainTask(() -> drainMainIngress(state, listener))));
        } catch (Throwable publicationFailure) {
            // This call hands off the sole listener-chain task. A rejected server
            // schedule may never leave the admission marker permanently closed with
            // no runnable able to observe it.
            Throwable failure = publishFatalFailure(publicationFailure);
            try {
                state.mainIngressScheduled = false;
            } catch (Throwable cleanup) {
                failure = publishFatalFailure(merge(failure, cleanup));
            }
            throwUnchecked(publishFatalFailure(failure));
        }
    }

    /**
     * Synchronous publication receipt for the tagged task installed at vanilla's
     * original {@code MinecraftServer.execute} callsite. The packet may sit behind
     * an older listener envelope (and this check normally runs from the currently
     * active outer handler), so identity presence rather than FIFO-head position is
     * the authority. Once retained, a pending disconnect cannot turn this result
     * false and manufacture a second vanilla task.
     */
    static boolean ownsQueuedMainCommand(
            PlayerState expectedState, ServerGamePacketListenerImpl listener,
            Packet<?> packet) {
        if (expectedState == null || listener == null || packet == null) return false;
        int ownership;
        synchronized (expectedState) {
            ownership = queuedMainCommandOwnershipLocked(
                    expectedState, listener, packet);
        }
        if (ownership < 0) {
            failExactMainCommand(
                    "A5 retained Main command marker crossed its exact listener state");
            return false;
        }
        return ownership > 0;
    }

    /** 0 absent, 1 exact retained marker, -1 retained but structurally corrupt. */
    private static int queuedMainCommandOwnershipLocked(
            PlayerState state, ServerGamePacketListenerImpl listener,
            Packet<?> packet) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException(
                    "A5 queued Main command ownership read without state lock");
        }
        if (!AsyncIslandPacketContinuations.isMainCommandPacket(packet)) return 0;

        int matches = 0;
        boolean exactEnvelope = false;
        for (InboundPacket inbound : state.mainIngress) {
            if (inbound.packet() != packet) continue;
            matches++;
            exactEnvelope = !inbound.entityOrdinal()
                    && !inbound.isRoutedMutation()
                    && inbound.listener() == listener;
        }
        if (matches == 0) return 0;
        if (matches != 1 || !exactEnvelope
                || !(listener instanceof AsyncIslandListenerAccess access)
                || access.fabrolia$asyncIslandPlayerState() != state
                || state.listenerGate != listener) {
            return -1;
        }
        return 1;
    }

    /** 0 absent, 1 exact retained terminal marker, -1 duplicate/corrupt. */
    private static int terminalMainCommandOwnershipLocked(
            PlayerState state, ServerGamePacketListenerImpl listener,
            Packet<?> packet) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException(
                    "A5 terminal Main command ownership read without state lock");
        }
        int matches = 0;
        for (Packet<?> retained : state.terminalMainCommands) {
            if (retained == packet) matches++;
        }
        if (matches == 0) return 0;
        if (matches != 1
                || !AsyncIslandPacketContinuations.isMainCommandPacket(packet)
                || state.listenerGate != listener
                || state.session.view().phase()
                != IslandPlayerTurnSession.Phase.DISCONNECTED
                || !(listener instanceof AsyncIslandListenerAccess access)
                || access.fabrolia$asyncIslandPlayerState() != state) {
            return -1;
        }
        return 1;
    }

    /**
     * A running exact command may wait only behind an already-active E marker or
     * an older exact command marker in the same listener FIFO. Ordinary envelopes
     * are not blockers: its physical-Main slot drains those predecessors directly.
     */
    private static boolean exactMainCommandBlockedLocked(
            PlayerState state, ServerGamePacketListenerImpl listener,
            Packet<?> packet) {
        if (!Thread.holdsLock(state)
                || queuedMainCommandOwnershipLocked(state, listener, packet) != 1) {
            throw new IllegalStateException(
                    "A5 exact Main command blocker read lost its retained marker");
        }
        verifyMainEntityOrdinalAccountingLocked(state);
        InboundPacket active = state.activeMainEntityFallback;
        if (active != null) {
            if (!isActiveMainEntityOrdinalLocked(state, active)) {
                throw new IllegalStateException(
                        "A5 exact Main command found a detached active E predecessor");
            }
            return true;
        }
        for (InboundPacket inbound : state.mainIngress) {
            if (state.netherReservation != null
                    && inbound == state.netherReservation.marker) return true;
            if (inbound.packet() == packet) return false;
            if (AsyncIslandPacketContinuations
                    .isMainCommandPacket(inbound.packet())) {
                if (inbound.entityOrdinal() || inbound.isRoutedMutation()
                        || inbound.listener() != listener) {
                    throw new IllegalStateException(
                            "A5 exact Main command predecessor was structurally corrupt");
                }
                return true;
            }
        }
        throw new IllegalStateException(
                "A5 exact Main command blocker scan missed its target marker");
    }

    /** The original/deferred tagged TickTask itself is the retained drain owner. */
    private static void retainExactMainCommandDrainLocked(
            PlayerState state, ServerGamePacketListenerImpl listener) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException(
                    "A5 exact Main command drain claim without state lock");
        }
        if (state.mainIngressScheduled) return;
        if (state.listenerGate != listener
                || state.mainIngress.isEmpty()
                || state.session.view().phase()
                != IslandPlayerTurnSession.Phase.MAIN) {
            throw new IllegalStateException(
                    "A5 exact Main command could not retain its Main drain marker");
        }
        state.mainIngressScheduled = true;
    }

    /** The current tagged task may drain an older island-safe ordinary prefix on Main. */
    private static boolean exactMainCommandTargetRetainedLocked(
            PlayerState state, ServerGamePacketListenerImpl listener) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException(
                    "A5 exact Main command target scan without state lock");
        }
        for (InboundPacket inbound : state.mainIngress) {
            if (AsyncIslandPacketContinuations.exactMainCommandTaskActive(
                    inbound.packet(), listener)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Publish only the first command marker's deferred baton. A later deferred
     * command can never overtake an earlier command whose original Main slot has
     * not run yet.
     */
    private static void scheduleFirstDeferredMainCommand(
            PlayerState state, ServerGamePacketListenerImpl listener) {
        Packet<?> deferred = null;
        synchronized (state) {
            if (state.listenerGate != listener) return;
            if (state.session.view().phase()
                    == IslandPlayerTurnSession.Phase.DISCONNECTED) {
                Packet<?> first = state.terminalMainCommands.peekFirst();
                if (first != null
                        && AsyncIslandPacketContinuations.deferredExactMainCommand(
                        first, listener)) {
                    deferred = first;
                }
            } else {
                for (InboundPacket inbound : state.mainIngress) {
                    if (state.netherReservation != null
                            && inbound == state.netherReservation.marker) break;
                    if (!AsyncIslandPacketContinuations
                            .isMainCommandPacket(inbound.packet())) {
                        continue;
                    }
                    if (inbound.entityOrdinal() || inbound.isRoutedMutation()
                            || inbound.listener() != listener) {
                        throw new IllegalStateException(
                                "A5 deferred Main command scan found a corrupt marker");
                    }
                    if (AsyncIslandPacketContinuations.deferredExactMainCommand(
                            inbound.packet(), listener)) {
                        deferred = inbound.packet();
                        retainExactMainCommandDrainLocked(state, listener);
                    }
                    break;
                }
            }
        }
        if (deferred != null) {
            AsyncIslandPacketContinuations.scheduleDeferredExactMainCommand(
                    deferred, listener);
        }
    }

    /** Consume or defer one exact command against the retained terminal FIFO. */
    private static void drainTerminalExactMainCommand(
            PlayerState state, ServerGamePacketListenerImpl listener,
            Packet<?> packet) {
        synchronized (state) {
            if (terminalMainCommandOwnershipLocked(state, listener, packet) != 1) {
                failExactMainCommand(
                        "A5 terminal Main command lost its retained order receipt");
                return;
            }
            if (state.terminalMainCommands.peekFirst() != packet) {
                AsyncIslandPacketContinuations.deferExactMainCommandTask(
                        packet, listener);
                return;
            }
            if (state.terminalMainCommands.pollFirst() != packet) {
                failExactMainCommand(
                        "A5 terminal Main command FIFO changed under state lock");
                return;
            }
        }
        runFailStopMainTask(() -> {
            handle(packet, listener);
            PACKETS_REPLAYED.increment();
        });
        AsyncIslandPacketContinuations.completeExactMainCommandTask(
                packet, listener);
        scheduleFirstDeferredMainCommand(state, listener);
    }

    /**
     * Body of the one exact globally-queued command task. A retained marker is
     * consumed through the ordinary listener FIFO; a marker removed by an exact
     * terminal disconnect still executes its original vanilla task body directly.
     * Every other absence is a lost-body invariant and therefore fail-stop.
     */
    static void drainExactMainCommand(
            PlayerState state, ServerGamePacketListenerImpl listener,
            Packet<?> packet, MinecraftServer server) {
        if (state == null || listener == null || packet == null || server == null
                || !RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)
                || !AsyncIslandPacketContinuations.isMainCommandPacket(packet)
                || !AsyncIslandPacketContinuations.exactMainCommandTaskActive(
                        packet, listener)) {
            failExactMainCommand(
                    "A5 exact Main command task escaped its physical-Main capability");
            return;
        }

        boolean queued;
        boolean terminallyRetained;
        boolean corrupt;
        boolean blocked;
        synchronized (state) {
            int ownership = queuedMainCommandOwnershipLocked(state, listener, packet);
            int terminalOwnership = ownership == 0
                    ? terminalMainCommandOwnershipLocked(state, listener, packet) : 0;
            queued = ownership > 0;
            terminallyRetained = terminalOwnership > 0;
            corrupt = ownership < 0 || terminalOwnership < 0;
            if (queued) retainExactMainCommandDrainLocked(state, listener);
            blocked = queued
                    && exactMainCommandBlockedLocked(state, listener, packet);
        }

        if (corrupt) {
            failExactMainCommand(
                    "A5 exact Main command task found a corrupt retained FIFO marker");
            return;
        }
        if (terminallyRetained) {
            drainTerminalExactMainCommand(state, listener, packet);
            return;
        }
        if (blocked) {
            synchronized (state) {
                if (queuedMainCommandOwnershipLocked(state, listener, packet) != 1
                        || !exactMainCommandBlockedLocked(
                        state, listener, packet)) {
                    failExactMainCommand(
                            "A5 exact Main command blocker changed before deferral");
                    return;
                }
                AsyncIslandPacketContinuations.deferExactMainCommandTask(
                        packet, listener);
            }
            return;
        }
        if (queued) {
            drainMainIngress(state, listener);
            boolean markerRemains;
            boolean nowBlocked;
            boolean terminalRetained;
            synchronized (state) {
                int ownership = queuedMainCommandOwnershipLocked(
                        state, listener, packet);
                int terminalOwnership = ownership == 0
                        ? terminalMainCommandOwnershipLocked(
                        state, listener, packet) : 0;
                if (ownership < 0 || terminalOwnership < 0) {
                    failExactMainCommand(
                            "A5 exact Main command drain corrupted its FIFO marker");
                    return;
                }
                markerRemains = ownership > 0;
                nowBlocked = markerRemains
                        && exactMainCommandBlockedLocked(state, listener, packet);
                terminalRetained = terminalOwnership > 0;
            }
            if (markerRemains) {
                if (!nowBlocked) {
                    failExactMainCommand(
                            "A5 exact Main command task returned before consuming its marker");
                    return;
                }
                synchronized (state) {
                    if (queuedMainCommandOwnershipLocked(state, listener, packet) != 1
                            || !exactMainCommandBlockedLocked(
                            state, listener, packet)) {
                        failExactMainCommand(
                                "A5 exact Main command blocker changed before late deferral");
                        return;
                    }
                    AsyncIslandPacketContinuations.deferExactMainCommandTask(
                            packet, listener);
                }
                return;
            }
            if (!AsyncIslandPacketContinuations.exactMainCommandBodyEntered(
                    packet, listener)) {
                if (!terminalRetained) {
                    failExactMainCommand(
                            "A5 exact Main command marker disappeared before body entry");
                    return;
                }
                drainTerminalExactMainCommand(state, listener, packet);
                return;
            }
            AsyncIslandPacketContinuations.completeExactMainCommandTask(
                    packet, listener);
            scheduleFirstDeferredMainCommand(state, listener);
            return;
        }
        failExactMainCommand(
                "A5 exact Main command task lost its retained FIFO marker");
    }

    private static void failExactMainCommand(String message) {
        throwUnchecked(publishFatalFailure(new IllegalStateException(message)));
    }

    /** Candidate selected by pollMainIngressLocked, without removing its body. */
    private static InboundPacket peekMainIngressLocked(PlayerState state) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException("A5 Main ingress peek without state lock");
        }
        if (state.causalRoutedCutoff != 0L) {
            for (InboundPacket candidate : state.mainIngress) {
                if (state.netherReservation != null
                        && candidate == state.netherReservation.marker) break;
                if (candidate.isRoutedMutation()
                        && candidate.routedSequence() <= state.causalRoutedCutoff) {
                    return candidate;
                }
            }
        }
        return state.mainIngress.peekFirst();
    }

    /**
     * Selects the next Main envelope. During one handler, routed mutations receive a
     * causal cutoff: every routed entry accepted up to that cutoff (including an older
     * command already sitting behind ordinary packets) runs before the next ordinary
     * envelope. This preserves both routed-command FIFO and synchronous handler effects.
     */
    private static InboundPacket pollMainIngressLocked(PlayerState state) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException("A5 Main ingress poll without state lock");
        }
        if (state.causalRoutedCutoff != 0L) {
            var iterator = state.mainIngress.iterator();
            while (iterator.hasNext()) {
                InboundPacket candidate = iterator.next();
                if (state.netherReservation != null
                        && candidate == state.netherReservation.marker) break;
                if (candidate.isRoutedMutation()
                        && candidate.routedSequence() <= state.causalRoutedCutoff) {
                    iterator.remove();
                    accountRoutedRemovalLocked(state, candidate);
                    return candidate;
                }
            }
            state.causalRoutedCutoff = 0L;
        }
        InboundPacket inbound = state.mainIngress.peekFirst();
        // An accepted entity ordinal remains the authoritative FIFO head until its
        // guarded vanilla body succeeds.  Packet entries are removed before handle
        // entry so a refused scheduler permit can restore them as before.
        if (isMainEntityOrdinal(inbound)) return inbound;
        inbound = state.mainIngress.pollFirst();
        accountRoutedRemovalLocked(state, inbound);
        return inbound;
    }

    private static void accountRoutedRemovalLocked(PlayerState state,
                                                   InboundPacket inbound) {
        if (inbound == null || !inbound.isRoutedMutation()) return;
        if (state.routedIngressCount <= 0) {
            throw new IllegalStateException("A5 routed ingress accounting underflow");
        }
        state.routedIngressCount--;
    }

    /**
     * Physical-Main packet replay for compatibility/lifecycle intervals. The task
     * marker remains published through the exact Main-required prefix; when an
     * island-safe suffix reaches the head, the marker is released under the same
     * state lock while the non-empty queue remains the next attach admission token.
     */
    private static void drainMainIngress(PlayerState state,
                                         ServerGamePacketListenerImpl listener) {
        boolean[] packetBodyEntered = new boolean[1];
        Throwable failure = null;
        ServerPlayer entityFallback = null;
        // Island claim this drain holds across consecutive own-venue heads (see
        // retargetMainIngressVenueClaim); released before any other head and
        // before every exit of the loop.
        IslandRegistry.PIsland venueClaim = null;
        boolean disconnectedReturn = false;
        try {
        while (true) {
            InboundPacket inbound;
            boolean ownVenueHead;
            synchronized (state) {
                if (state.netherReservation != null
                        && state.mainIngress.peekFirst() == state.netherReservation.marker) {
                    state.mainIngressScheduled = false;
                    break;
                }
                if (!state.mainIngressScheduled || state.listenerGate != listener) {
                    if (state.listenerGate == listener
                            && state.session.view().phase()
                            == IslandPlayerTurnSession.Phase.DISCONNECTED) {
                        disconnectedReturn = true;
                        break;
                    }
                    failure = publishFatalFailure(merge(failure,
                            new IllegalStateException(
                                    "A5 Main ingress lost its listener gate")));
                    break;
                }
                IslandPlayerTurnSession.Phase phase = state.session.view().phase();
                if (phase != IslandPlayerTurnSession.Phase.MAIN) {
                    // This task was admitted against a MAIN token. Observing any
                    // other phase here means its publication overtook the unique
                    // handoff/terminal edge. Returning normally and replaying the
                    // same ingress on tick+1 would hide that ordering failure and
                    // manufacture a skipped-tick successor.
                    failure = publishFatalFailure(merge(failure,
                            new IllegalStateException(
                                    "A5 Main ingress overtook its MAIN ownership edge: "
                                            + phase)));
                    break;
                }
                if (!state.mainIngress.isEmpty()
                        && !shouldRunMainIngressHeadLocked(state, listener)
                        && !exactMainCommandTargetRetainedLocked(
                        state, listener)) {
                    // The exact Main-required prefix is complete. Publish the task
                    // marker open while retaining the island-safe suffix itself as
                    // the attach admission token; the next region ordinal transfers
                    // that suffix before entering the player body.
                    state.mainIngressScheduled = false;
                    break;
                }
                InboundPacket selected = peekMainIngressLocked(state);
                if (selected != null
                        && AsyncIslandPacketContinuations
                        .isMainCommandPacket(selected.packet())
                        && !AsyncIslandPacketContinuations.exactMainCommandTaskActive(
                                selected.packet(), listener)) {
                    // Only the task occupying vanilla's original global executor
                    // slot may remove this marker. Keep the listener-chain schedule
                    // closed; the tagged task will drain older causal predecessors
                    // before consuming this exact packet identity.
                    break;
                }
                inbound = pollMainIngressLocked(state);
                if (inbound == null) {
                    if (state.fallbackEntityAfterHandoff) {
                        // Legacy lifecycle containment is single-shot, but it still
                        // becomes a real FIFO marker before any body can be published.
                        convertDeferredFallbackToMarkerLocked(state, listener);
                        continue;
                    } else {
                        state.mainIngressScheduled = false;
                    }
                    break;
                }
                if (inbound.entityOrdinal()) {
                    if (state.mainIngressHandlerActive) {
                        failure = publishFatalFailure(merge(failure,
                                new IllegalStateException(
                                        "A5 Main entity ordinal overlapped a packet handler")));
                        break;
                    }
                    try {
                        if (state.mainIngress.peekFirst() != inbound
                                || state.mainEntityFallbackPending
                                || state.activeMainEntityFallback != null
                                || state.owedMainEntityOrdinals <= 0) {
                            throw new IllegalStateException(
                                    "A5 Main entity ordinal activation lost its FIFO boundary");
                        }
                        state.activeMainEntityFallback = inbound;
                        state.mainEntityFallbackAdmitted = false;
                        state.mainEntityFallbackPending = true;
                        entityFallback = fallbackPlayer(inbound);
                    } catch (Throwable activationFailure) {
                        failure = publishFatalFailure(merge(
                                failure, activationFailure));
                    }
                    break;
                }
                if (state.mainIngressHandlerActive) {
                    failure = publishFatalFailure(merge(failure,
                            new IllegalStateException(
                                    "A5 Main ingress entered two handler bodies concurrently")));
                    break;
                }
                if (state.mainEntityFallbackPending
                        || state.activeMainEntityFallback != null) {
                    failure = publishFatalFailure(merge(failure,
                            new IllegalStateException(
                                    "A5 Main packet handler overlapped an entity ordinal")));
                    break;
                }
                ownVenueHead = state.mainInteractionPacket != inbound
                        && isOwnVenueIngressHead(inbound);
                state.mainIngressHandlerActive = true;
            }

            Throwable packetFailure = null;
            packetBodyEntered[0] = false;
            try {
                IslandRegistry.PIsland venue = ownVenueHead
                        ? ownVenueIsland(inbound, listener) : null;
                if (venueClaim != null && venueClaim != venue) {
                    IslandRegistry.PIsland released = venueClaim;
                    venueClaim = null;
                    IslandCommands.mainRelease(released);
                }
                boolean terminalDuringClaim = false;
                if (venue != null && venueClaim == null && !IslandCommands.mainHolds(venue)) {
                    // The same blocking claim the head's own owner wrapper would
                    // take; nested mainClaimScoped calls inside the body inherit it.
                    IslandCommands.mainClaim(venue);
                    venueClaim = venue;
                    // The wait may pump a routed disconnect or a routed move. A
                    // clean terminal disconnect ends a deferred listener tick
                    // without its body (exactly the wrapper's post-claim rule); a
                    // changed venue hands the head back to its own wrapper.
                    synchronized (state) {
                        terminalDuringClaim = inbound.listenerTick != null
                                && isExactCleanTerminalDisconnectLocked(
                                state, listener.player, listener);
                    }
                    if (!terminalDuringClaim && ownVenueIsland(inbound, listener) != venue) {
                        venueClaim = null;
                        IslandCommands.mainRelease(venue);
                    }
                }
                Runnable body = () -> {
                    packetBodyEntered[0] = true;
                    if (inbound.listenerTick != null) inbound.listenerTick.run();
                    else handle(inbound.packet, inbound.listener);
                };
                if (inbound.listenerTick == null
                        && hasInvalidBlockAckSequence(inbound.packet)
                        && listener.hasClientLoaded()) {
                    // Match PacketProcessor's connection-local rejection, before
                    // admitting packet.handle (including block-break mutation).
                    // Entered handlers report like PacketProcessor through handle().
                    RegionScheduler.throwWorkerFailureIfAny();
                    inbound.listener.onPacketError(inbound.packet,
                            new IllegalArgumentException("Expected packet sequence nr >= 0"));
                } else if (inbound.listenerTick != null) {
                    if (!terminalDuringClaim) replayMainListenerTick(state, listener, body);
                } else if (state.mainInteractionPacket == inbound) {
                    runFencedMainMutation("ordered interaction packet", body);
                } else {
                    RegionScheduler.runSuccessBodyIfHealthy(body);
                }
                if (inbound.listenerTick == null) {
                    RegionScheduler.runSuccessBodyIfHealthy(PACKETS_REPLAYED::increment);
                }
            } catch (RunningOnDifferentThreadException wrongThread) {
                packetFailure = publishFatalFailure(wrongThread);
            } catch (Throwable thrown) {
                // handle() already contained what vanilla's PacketProcessor
                // contains. What reaches here is a Fabrolia invariant, an Error, an
                // OOM report or a refused admission: select W; onPacketError and
                // the next FIFO envelope are forbidden successors.
                packetFailure = publishFatalFailure(thrown);
            }
            synchronized (state) {
                if (!state.mainIngressHandlerActive) {
                    packetFailure = publishFatalFailure(merge(packetFailure,
                            new IllegalStateException(
                                    "A5 Main ingress handler marker disappeared")));
                }
                state.mainIngressHandlerActive = false;
                if (packetFailure == null && state.mainInteractionPacket == inbound) {
                    state.mainInteractionPacket = null;
                }
                if (packetFailure != null && !packetBodyEntered[0]) {
                    try {
                        restoreMainIngressBeforeBodyLocked(state, inbound);
                    } catch (Throwable cleanup) {
                        packetFailure = publishFatalFailure(
                                merge(packetFailure, cleanup));
                    }
                }
            }
            if (packetFailure != null) {
                failure = publishFatalFailure(merge(failure, packetFailure));
                try {
                    FAILURES.increment();
                } catch (Throwable cleanup) {
                    failure = publishFatalFailure(merge(failure, cleanup));
                }
                // Retain the listener-chain admission marker and untouched FIFO.
                // The fail-stop boundary, not a successor task/body, owns the suffix.
                break;
            }
        }
        } catch (Throwable escaping) {
            // An invariant failure in the loop head must not retain the venue
            // claim: every later all-island quiesce would spin on Main's own claim.
            if (venueClaim != null) {
                IslandRegistry.PIsland held = venueClaim;
                venueClaim = null;
                try {
                    IslandCommands.mainRelease(held);
                } catch (Throwable releaseFailure) {
                    escaping.addSuppressed(releaseFailure);
                }
            }
            throw escaping;
        }
        try {
            if (venueClaim != null) IslandCommands.mainRelease(venueClaim);
        } catch (Throwable releaseFailure) {
            failure = publishFatalFailure(merge(failure, releaseFailure));
        }
        if (disconnectedReturn) {
            if (failure != null) throwUnchecked(publishFatalFailure(failure));
            return;
        }
        if (failure != null) failure = publishFatalFailure(failure);
        if (failure == null && entityFallback != null) {
            try {
                publishMainEntityFallback(state, listener, entityFallback);
            } catch (Throwable publicationFailure) {
                failure = publishFatalFailure(merge(failure, publicationFailure));
            }
        }
        if (failure != null) throwUnchecked(publishFatalFailure(failure));
        if (entityFallback == null) {
            scheduleFirstDeferredMainCommand(state, listener);
            finishReadyTrackingTopologyGap(state);
        }
    }

    /**
     * Heads whose only island venue is the receiving player's own body: the
     * deferred listener tick (claims the player's chunk island) and the vehicle
     * control packets (claim the root vehicle's chunk island). A MAIN-phase rider
     * sends both every tick; each used to take — and wait for — that same island
     * claim separately, and the island could start a whole new unit in every gap
     * between two heads. One drain now holds the claim across such a run.
     */
    private static boolean isOwnVenueIngressHead(InboundPacket inbound) {
        if (inbound.listenerTick != null) return true;
        Packet<?> packet = inbound.packet;
        return packet instanceof net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket
                || packet instanceof net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket;
    }

    /** The live island an own-venue head's body would claim, or null when it would take none. */
    private static IslandRegistry.PIsland ownVenueIsland(InboundPacket inbound,
                                                         ServerGamePacketListenerImpl listener) {
        ServerPlayer player = listener.player;
        if (player == null || !(player.level() instanceof ServerLevel level)) return null;
        RegionizedServer runtime = RegionScheduler.forLevel(level);
        if (runtime == null || !runtime.asyncActive || runtime.currentThreadHoldsAllClaims()) {
            return null;
        }
        Entity venue = inbound.listenerTick != null ? player : player.getRootVehicle();
        IslandRegistry.PIsland owner = runtime.islandOwnerOfChunk(venue.chunkPosition().pack());
        return owner == null || owner.retired ? null : owner;
    }

    /** Restore only a Main envelope whose scheduler permit refused before handle entry. */
    private static void restoreMainIngressBeforeBodyLocked(
            PlayerState state, InboundPacket inbound) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException(
                    "A5 Main ingress pre-body restore without state lock");
        }
        if (isMainEntityOrdinal(inbound)) {
            throw new IllegalStateException(
                    "A5 Main entity ordinal reached packet-envelope restore");
        }
        state.mainIngress.addFirst(inbound);
        if (inbound.isRoutedMutation()) {
            if (state.routedIngressCount >= MAX_ROUTED_MUTATIONS) {
                throw new IllegalStateException(
                        "A5 routed ingress accounting overflow during pre-body restore");
            }
            state.routedIngressCount++;
        }
    }

    /**
     * Hands the listener-lifetime chain token to MAIN_SYNC.  The state marker was
     * published before this call; its completion is the only edge which may expose
     * an empty lane to a later island reservation.
     */
    private static void publishMainEntityFallback(
            PlayerState state, ServerGamePacketListenerImpl listener,
            ServerPlayer player) {
        if (!RegionScheduler.isActive()) {
            throw new IllegalStateException(
                    "A5 accepted Main entity ordinal lost its live region pool");
        }
        synchronized (state) {
            InboundPacket active = state.activeMainEntityFallback;
            if (!isActiveMainEntityOrdinalLocked(state, active)
                    || fallbackPlayer(active) != player
                    || active.listener() != listener) {
                throw new IllegalStateException(
                        "A5 Main entity ordinal publication lost its exact FIFO marker");
            }
        }
        try {
            RegionScheduler.queueMainSyncGuardedTick(player,
                    () -> admitMainEntityFallback(state, listener, player),
                    () -> finishMainEntityFallback(state, listener));
        } catch (Throwable publicationFailure) {
            Throwable failure = publishFatalFailure(publicationFailure);
            try {
                synchronized (state) {
                    // W owns the listener chain.  The accepted E marker deliberately
                    // stays active at the FIFO head; no cleanup may turn publication
                    // failure into a successfully consumed/skipped tick.
                    if (!isActiveMainEntityOrdinalLocked(
                            state, state.activeMainEntityFallback)) {
                        throw new IllegalStateException(
                                "A5 failed Main entity publication lost its marker");
                    }
                    verifyMainEntityOrdinalAccountingLocked(state);
                }
            } catch (Throwable cleanup) {
                failure = publishFatalFailure(merge(failure, cleanup));
            }
            throwUnchecked(publishFatalFailure(failure));
        }
    }

    /**
     * Main-side ownership admission for one exact E marker. Entity liveness is not
     * part of this protocol: {@link RegionScheduler#drainMainSyncTicks()} owns the
     * vanilla removed-entity gate after this check. In particular {@code /kill}
     * publishes {@code RemovalReason.KILLED} without a listener disconnect; rejecting
     * that player here would turn vanilla's no-body result into a false lost-ordinal
     * failure. Only the completion edge may consume a refused marker, after proving
     * either an authenticated disconnect or an exact removed-player replacement.
     */
    private static boolean admitMainEntityFallback(
            PlayerState state, ServerGamePacketListenerImpl listener,
            ServerPlayer player) {
        synchronized (state) {
            InboundPacket active = state.activeMainEntityFallback;
            boolean admitted = state.mainEntityFallbackPending
                    && state.mainIngressScheduled
                    && !state.mainDisconnectPending
                    && state.listenerGate == listener
                    && listener.player == player
                    && active != null
                    && state.mainIngress.peekFirst() == active
                    && active.listener() == listener
                    && fallbackPlayer(active) == player
                    && state.session.view().phase()
                    == IslandPlayerTurnSession.Phase.MAIN;
            if (admitted) {
                if (state.mainEntityFallbackAdmitted) {
                    throw new IllegalStateException(
                            "A5 Main entity ordinal was admitted twice");
                }
                state.mainEntityFallbackAdmitted = true;
            }
            return admitted;
        }
    }

    /** Physical-Main completion edge for one ordered fallback body. */
    private static void finishMainEntityFallback(
            PlayerState state, ServerGamePacketListenerImpl listener) {
        boolean drainInline = false;
        synchronized (state) {
            try {
                if (!state.mainEntityFallbackPending
                        && !state.mainIngressScheduled
                        && isExactCleanTerminalDisconnectLocked(
                        state, listener.player, listener)) {
                    return;
                }
                if (!state.mainEntityFallbackPending
                        || !state.mainIngressScheduled
                        || state.listenerGate != listener) {
                    throw new IllegalStateException(
                            "A5 Main entity fallback lost its listener-lifetime chain token");
                }
                if (!state.mainEntityFallbackAdmitted) {
                    // A physical disconnect can finish its vanilla body while the
                    // already-owned MAIN_HANDOFF closer is still the only actor which
                    // may publish DISCONNECTED. In that exact window the queued E
                    // body did not enter and must stay retained for that closer to
                    // clear; reopening/consuming it here would be the real skipped
                    // tick. This is an ownership transfer to the terminal closer,
                    // not a broad removed/pending suppression rule.
                    if (physicalDisconnectHandoffOwnsOrdinalLocked(
                            state, listener.player, listener)) {
                        TERMINAL_PRE_BODY_CANCELLATIONS.increment();
                        return;
                    }
                    if (playerReplacementRetiresOrdinalLocked(state, listener)) {
                        REPLACEMENT_PRE_BODY_CANCELLATIONS.increment();
                    } else {
                        // Every other refusal would silently consume an accepted tick.
                        throw new IllegalStateException(
                                "A5 Main entity ordinal lost exact cancellation authority");
                    }
                }
                InboundPacket completed = state.activeMainEntityFallback;
                completeMainEntityOrdinalLocked(state, completed);
                if (state.fallbackEntityAfterHandoff) {
                    convertDeferredFallbackToMarkerLocked(state, listener);
                }
                if (state.mainIngress.isEmpty()) {
                    state.mainIngressScheduled = false;
                } else if (shouldRunMainIngressHeadLocked(state, listener)) {
                    // Completion runs on physical Main inside the MAIN_SYNC fixed
                    // point. Drain the next FIFO prefix inline so a backlog can catch
                    // up; scheduling only one successor per wall tick would leave a
                    // permanent 20 Hz backlog behind a 20 Hz island cadence.
                    drainInline = true;
                } else {
                    state.mainIngressScheduled = false;
                }
            } catch (Throwable failure) {
                // This callback follows an entered MAIN_SYNC entity body.  Keep the
                // listener-chain monitor held until its exact W is globally visible.
                throwUnchecked(publishFatalFailure(failure));
            }
        }
        if (drainInline) drainMainIngress(state, listener);
        finishReadyTrackingTopologyGap(state);
    }

    /**
     * Exact no-body receipt for vanilla respawn/shadow replacement. The listener
     * lifetime remains the same, but its live player pointer now names a different
     * entity and the old marker target has reached a permanent removed state.
     * Removal alone is not authority: {@code /kill} keeps the same listener player
     * until respawn and therefore continues through the normal removed-entity gate.
     */
    private static boolean playerReplacementRetiresOrdinalLocked(
            PlayerState state, ServerGamePacketListenerImpl listener) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException(
                    "A5 player replacement ordinal receipt checked without state lock");
        }
        InboundPacket active = state.activeMainEntityFallback;
        if (listener == null
                || state.mainEntityFallbackAdmitted
                || !state.mainIngressScheduled
                || state.mainDisconnectPending
                || state.listenerGate != listener
                || !isActiveMainEntityOrdinalLocked(state, active)
                || active.listener() != listener
                || state.session.view().phase()
                != IslandPlayerTurnSession.Phase.MAIN) {
            return false;
        }
        ServerPlayer replaced = fallbackPlayer(active);
        ServerPlayer replacement = listener.player;
        return replaced.connection == listener
                && replaced.isRemoved()
                && replacement != null
                && replacement != replaced
                && replacement.connection == listener;
    }

    /**
     * Exact non-entered E ownership retained by a physical disconnect whose vanilla
     * body has completed but whose pre-existing MAIN_HANDOFF transaction still owns
     * the sole terminalization edge. The marker deliberately remains in the FIFO;
     * the handoff closer publishes MAIN and then clears the complete listener
     * generation through {@code terminalizeMainListenerLocked}.
     */
    private static boolean physicalDisconnectHandoffOwnsOrdinalLocked(
            PlayerState state, ServerPlayer player,
            ServerGamePacketListenerImpl listener) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException(
                    "A5 physical disconnect ordinal receipt checked without state lock");
        }
        if (player == null || listener == null
                || !(listener instanceof AsyncIslandListenerAccess access)
                || access.fabrolia$asyncIslandPlayerState() != state
                || state.listenerGate != listener
                || listener.player != player || player.connection != listener
                || !state.mainDisconnectPending
                || state.mainDisconnectOrigin != MainDisconnectOrigin.PHYSICAL_MAIN
                || state.terminalDisconnectListener != null
                || state.terminalDisconnectPlayer != null) {
            return false;
        }
        IslandPlayerTurnSession.View view = state.session.view();
        IslandPlayerTurnSession.OwnerStamp owner = view.owner();
        return isMainHandoffPhase(view.phase())
                && owner != null
                && owner.playerIdentity() == player
                && owner.listenerIdentity() == listener;
    }

    private static void finishMainHandoff(
            PlayerState state, ServerPlayer player,
            IslandPlayerTurnSession.MainHandoff<InboundPacket> handoff,
            boolean scheduleEntityTick) {
        List<IslandPlayerTurnSession.PacketEnvelope<InboundPacket>> packets;
        synchronized (state) {
            packets = state.session.completeMainHandoff(handoff).orElse(null);
            if (packets == null) {
                throwUnchecked(publishFatalFailure(new IllegalStateException(
                        "A5 Main closer lost its exact MAIN_HANDOFF transaction")));
            }
        }

        Throwable failure = null;
        TrackingTopologyGap topologyGap = null;
        ServerGamePacketListenerImpl listener =
                handoff.oldOwner().listenerIdentity()
                        instanceof ServerGamePacketListenerImpl game ? game : null;
        synchronized (state) {
            if (listener == null) {
                failure = publishFatalFailure(merge(failure, new IllegalStateException(
                        "A5 MAIN handoff lost its play-listener identity")));
            } else {
                // Do not replay the old island prefix in a private side loop. Moving
                // it to the front of the one listener-lifetime boundary queue makes
                // continuations and routed /player mutations share one causal FIFO;
                // the routing edge below then selects only its exact Main prefix.
                try {
                    prependMainIngressLocked(state, listener, packets);
                } catch (Throwable cleanup) {
                    failure = publishFatalFailure(merge(failure, cleanup));
                }
            }

            boolean publishedMain = false;
            try {
                if (!state.session.finishMainHandoff(handoff)) {
                    throw new IllegalStateException(
                            "A5 MAIN handoff could not publish its final MAIN owner");
                }
                publishedMain = true;
                TrackingTopologyGap candidate = state.trackingTopologyGap;
                if (candidate != null) {
                    if (candidate.state != state
                            || candidate.player != player
                            || candidate.listener != listener
                            || candidate.tracker != state.trackingPublication.tracker()
                            || candidate.sessionEpoch
                            != handoff.oldOwner().sessionEpoch()) {
                        throw new IllegalStateException(
                                "A5 MAIN handoff crossed its topology-gap receipt");
                    }
                    topologyGap = candidate;
                }
                clearTrackingPublicationBindingLocked(state);
            } catch (Throwable cleanup) {
                failure = publishFatalFailure(merge(failure, cleanup));
            }

            if (failure != null) {
                // Closing MAIN_DRAINING is mandatory ownership cleanup; arming an
                // ingress task, disconnect body or entity fallback is not. Keep the
                // admission cut closed until the outer continuation publishes fatal.
                state.eligible = false;
                state.lifecycleInvalidated = true;
            }

            boolean terminalDisconnect = false;
            if (failure == null && publishedMain
                    && state.mainDisconnectPending && listener != null) {
                try {
                    if (!terminalizeMainListenerLocked(state, listener)) {
                        throw new IllegalStateException(
                                "A5 handoff could not close a pending disconnect");
                    }
                    terminalDisconnect = true;
                } catch (Throwable cleanup) {
                    // Accepted routed work reports a fail-stop only after the helper
                    // has published DISCONNECTED and cleaned every admission marker.
                    terminalDisconnect = state.session.view().phase()
                            == IslandPlayerTurnSession.Phase.DISCONNECTED;
                    failure = publishFatalFailure(merge(failure, cleanup));
                }
            }

            if (failure == null && !terminalDisconnect) {
                state.eligible = false;
                boolean publishEntityFallback = scheduleEntityTick
                        || state.fallbackEntityAfterHandoff;
                state.lifecycleInvalidated = false;
                state.auditedLifecycleMutationPending = false;
                state.hardLifecycleExitPending = false;
                state.deathLateLifecycleEpoch = -1L;
                state.fallbackEntityAfterHandoff = false;

                if (publishedMain && listener != null) {
                    if (publishEntityFallback) {
                        // Convert the legacy lifecycle replacement request into the
                        // same ordered E envelope used by autonomous cadence.  From
                        // here on no boolean represents an owed vanilla body.
                        try {
                            enqueueMainIngressLocked(state, listener,
                                    newMainEntityOrdinalLocked(
                                            state, listener, listener.player));
                        } catch (Throwable cleanup) {
                            failure = publishFatalFailure(merge(failure, cleanup));
                        }
                    }
                    if (failure == null && !state.mainIngress.isEmpty()) {
                        // A real lifecycle/body fallback keeps the whole detached
                        // queue on Main. A packet-policy-only handoff drains through
                        // every already-accepted Main boundary (including older I
                        // entries before it), then returns the wholly island-safe
                        // remainder to the next autonomous attachment.
                        try {
                            armMainIngressIfRequiredLocked(state, listener);
                        } catch (Throwable cleanup) {
                            failure = publishFatalFailure(merge(failure, cleanup));
                        }
                    }
                }
                IslandRegistry.PIsland listed = state.listedIsland;
                if (listed != null) listed.playerStates.remove(state);
                if (state.listedIsland == listed) state.listedIsland = null;
            }
            if (failure != null || terminalDisconnect) topologyGap = null;
        }
        if (failure != null) throwUnchecked(publishFatalFailure(failure));
        if (topologyGap != null) {
            IslandEntityTracking.finishTopologyGapPublicationOnMain(topologyGap);
        }
    }

    /**
     * Re-check one retained gap receipt after an ordered Main prefix completes.
     * The tracker-side helper owns the authoritative readiness check under the
     * canonical tracker -> PlayerState edge.
     */
    private static void finishReadyTrackingTopologyGap(PlayerState state) {
        TrackingTopologyGap receipt;
        synchronized (state) {
            receipt = state.trackingTopologyGap;
        }
        if (receipt != null) {
            IslandEntityTracking.finishTopologyGapPublicationOnMain(receipt);
        }
    }

    private static boolean isMainHandoffPhase(IslandPlayerTurnSession.Phase phase) {
        return phase == IslandPlayerTurnSession.Phase.MAIN_HANDOFF
                || phase == IslandPlayerTurnSession.Phase.MAIN_DRAINING;
    }

    private static boolean isEnteredPlayerBodyPhase(
            IslandPlayerTurnSession.Phase phase) {
        return phase == IslandPlayerTurnSession.Phase.EARLY_RUNNING
                || phase == IslandPlayerTurnSession.Phase.EARLY_DONE
                || phase == IslandPlayerTurnSession.Phase.LATE_RUNNING
                || phase == IslandPlayerTurnSession.Phase.MAIN_FALLBACK_RUNNING;
    }

    private static void clearTrackingPublicationBindingLocked(PlayerState state) {
        if (!Thread.holdsLock(state)) {
            throw new IllegalStateException(
                    "A5 tracking binding cleanup without state lock");
        }
        state.trackingPublication.clear();
    }

    static boolean isActiveIslandTurnPhase(IslandPlayerTurnSession.Phase phase) {
        return phase == IslandPlayerTurnSession.Phase.EARLY_RESERVED
                || phase == IslandPlayerTurnSession.Phase.EARLY_RUNNING
                || phase == IslandPlayerTurnSession.Phase.EARLY_DONE
                || phase == IslandPlayerTurnSession.Phase.LATE_RUNNING
                || phase == IslandPlayerTurnSession.Phase.TRANSFERRING;
    }

    static boolean isIslandListenerOwnedPhase(
            IslandPlayerTurnSession.Phase phase) {
        return phase == IslandPlayerTurnSession.Phase.ISLAND_READY
                || phase == IslandPlayerTurnSession.Phase.EARLY_RESERVED
                || phase == IslandPlayerTurnSession.Phase.EARLY_RUNNING
                || phase == IslandPlayerTurnSession.Phase.EARLY_DONE
                || phase == IslandPlayerTurnSession.Phase.LATE_RUNNING
                || phase == IslandPlayerTurnSession.Phase.MAIN_HANDOFF
                || phase == IslandPlayerTurnSession.Phase.MAIN_DRAINING
                || phase == IslandPlayerTurnSession.Phase.TRANSFERRING;
    }

    private static Throwable merge(Throwable primary, Throwable secondary) {
        if (primary == null) return secondary;
        if (secondary != null && secondary != primary) {
            try { primary.addSuppressed(secondary); } catch (Throwable ignored) {}
        }
        return primary;
    }

    /**
     * Vanilla's BlockableEventLoop logs ordinary task exceptions and continues.
     * Publish the exact primary into Fabrolia's first-wins server-fatal slot before
     * rethrowing so the physical tick boundary cannot treat this as a skipped task.
     */
    static Throwable publishFatalFailure(Throwable primary) {
        if (primary == null) {
            return new NullPointerException("A5 fatal failure is required");
        }
        return RegionScheduler.publishFatalAndSelectWinner(primary);
    }

    private static void runFailStopMainTask(Runnable task) {
        try {
            RegionScheduler.runSuccessBodyIfHealthy(task);
        } catch (Throwable failure) {
            throwUnchecked(publishFatalFailure(failure));
        }
    }

    /**
     * Vanish publishes its active listener player at tick HEAD and removes it only at
     * RETURN. An exceptional A5 listener turn still has to clean that published
     * state before the original failure is propagated to the fail-stop boundary;
     * do the exact missing cleanup without linking the optional mod on normal paths.
     */
    private static Throwable cleanupKnownListenerFailureState(ServerPlayer player,
                                                              Throwable primary) {
        try {
            Class<?> arguments = Class.forName("me.drex.vanish.util.Arguments", false,
                    AsyncIslandPlayerTicks.class.getClassLoader());
            Object value = arguments.getField("ACTIVE_ENTITY").get(null);
            if (value instanceof ThreadLocal<?> active && active.get() == player) {
                active.remove();
            }
        } catch (ClassNotFoundException ignored) {
            // Optional compatibility branch.
        } catch (Throwable cleanup) {
            primary = merge(primary, cleanup);
        }
        return primary;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T rethrowAs(Throwable failure) throws T {
        throw (T) failure;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable failure) throws T {
        throw (T) failure;
    }
}
