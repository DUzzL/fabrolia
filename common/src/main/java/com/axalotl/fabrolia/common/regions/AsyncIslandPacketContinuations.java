package com.axalotl.fabrolia.common.regions;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.TaskChainer;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Ownership bridge for vanilla play-packet work which crosses an asynchronous
 * completion boundary after its ingress envelope has returned.
 *
 * <p>A {@link ContinuationOwner} authenticates the exact listener, player object,
 * listener-lifetime A5 sequencer and cooperative lifecycle generation which
 * started the work.  A respawn, disconnect or listener replacement therefore
 * turns an old completion into a no-op instead of letting it mutate the successor.
 *
 * <p>Player-bound completions use internal packets in the same sticky physical-Main
 * listener FIFO as decoded Main packets. Unlike owed routed mutations, these
 * completions may be discarded when their captured player lifetime ends. Their
 * classes are deliberately absent from {@link AsyncIslandPacketPolicy}'s audited
 * vanilla allow-list, so the exact-class fail-closed rule places them in the Main
 * FIFO. Publication happens before the current Main drain can expose its empty
 * marker and keeps a new island reservation behind the continuation.
 *
 * <p>The internal packet is never registered with a protocol codec and can never be
 * received from a client.  It exists only as a typed entry in the in-process A5
 * listener lane.
 */
public final class AsyncIslandPacketContinuations {

    private static final PacketType<MainContinuationPacket> MAIN_CONTINUATION_TYPE =
            new PacketType<>(PacketFlow.SERVERBOUND,
                    Identifier.fromNamespaceAndPath(
                            "fabrolia", "a5_main_packet_continuation"));
    private static final PacketType<MainCommandContinuationPacket>
            MAIN_COMMAND_CONTINUATION_TYPE =
            new PacketType<>(PacketFlow.SERVERBOUND,
                    Identifier.fromNamespaceAndPath(
                            "fabrolia", "a5_main_command_continuation"));
    private static final PacketType<ChatSessionPublicationPacket> CHAT_SESSION_TYPE =
            new PacketType<>(PacketFlow.SERVERBOUND,
                    Identifier.fromNamespaceAndPath(
                            "fabrolia", "a5_chat_session_publication"));
    private static final ThreadLocal<MainCommandTaskFrame> MAIN_COMMAND_TASK =
            new ThreadLocal<>();

    private AsyncIslandPacketContinuations() {}

    /** Opaque exact-lifecycle capability captured before an async boundary. */
    public static final class ContinuationOwner {
        private final ServerGamePacketListenerImpl listener;
        private final ServerPlayer player;
        private final AsyncIslandPlayerTicks.PlayerState state;
        private final ServerLevel level;
        private final RegionizedServer runtime;
        private final long lifecycleEpoch;

        private ContinuationOwner(ServerGamePacketListenerImpl listener,
                                  ServerPlayer player,
                                  AsyncIslandPlayerTicks.PlayerState state,
                                  ServerLevel level,
                                  RegionizedServer runtime,
                                  long lifecycleEpoch) {
            this.listener = listener;
            this.player = player;
            this.state = state;
            this.level = level;
            this.runtime = runtime;
            this.lifecycleEpoch = lifecycleEpoch;
        }
    }

    /**
     * Captures the exact A5 listener/player generation, or {@code null} when this
     * handler is running outside A5's supported player envelope.  A null token is
     * an instruction to preserve the unmodified vanilla continuation path.
     */
    public static ContinuationOwner capture(
            ServerGamePacketListenerImpl listener) {
        if (listener == null
                || !(listener instanceof AsyncIslandListenerAccess listenerAccess)) {
            return null;
        }
        ServerPlayer player = listener.player;
        if (!(player instanceof PlayerTickPhaseStamp lifecycle)
                || AsyncIslandPlayerTicks.classifyPlayer(player) == null
                || player.connection != listener
                || !(player.level() instanceof ServerLevel level)) {
            return null;
        }
        RegionizedServer runtime = RegionScheduler.forLevel(level);
        if (!AsyncIslandPlayerTicks.enabledForAsync(runtime)) return null;
        AsyncIslandPlayerTicks.PlayerState state =
                listenerAccess.fabrolia$asyncIslandPlayerState();
        if (state == null) return null;
        return new ContinuationOwner(listener, player, state, level, runtime,
                lifecycle.fabrolia$playerLifecycleEpoch());
    }

    /**
     * A key installation belongs to the listener's ordered chat chain, not to the
     * player lifecycle or the latest decoder key. A newer decoder may already be
     * installed while this key still precedes signed messages in FutureChain.
     * Vanilla's callback uses the current player, including a respawn successor.
     */
    public static final class ChatSessionOwner {
        private final ServerGamePacketListenerImpl listener;
        private final AsyncIslandPlayerTicks.PlayerState state;

        private ChatSessionOwner(ContinuationOwner owner) {
            this.listener = owner.listener;
            this.state = owner.state;
        }
    }

    public static ChatSessionOwner captureChatSession(ContinuationOwner owner) {
        return owner == null ? null : new ChatSessionOwner(owner);
    }

    /** Keeps the original FutureChain ordering while publishing into the Main FIFO. */
    public static boolean enqueueChatSession(ChatSessionOwner owner,
                                              Runnable continuation) {
        Objects.requireNonNull(continuation, "continuation");
        if (owner == null) return false;
        if (!isCurrent(owner, owner.listener)) return true;
        if (AsyncIslandPlayerTicks.enqueuePacket(
                new ChatSessionPublicationPacket(owner, continuation), owner.listener)) {
            return true;
        }
        // Disabled A5 may return the exact vanilla executor its body, but a
        // concurrent listener replacement must never regain inline work.
        return !isCurrent(owner, owner.listener);
    }

    private static boolean isCurrent(ChatSessionOwner owner,
                                     ServerGamePacketListenerImpl listener) {
        if (listener != owner.listener || listener.player == null
                || listener.player.connection != listener
                || !listener.isAcceptingMessages()
                || !(listener instanceof AsyncIslandListenerAccess access)
                || access.fabrolia$asyncIslandPlayerState() != owner.state) {
            return false;
        }
        synchronized (owner.state) {
            return !owner.state.mainDisconnectPending
                    && owner.state.session.view().phase()
                    != IslandPlayerTurnSession.Phase.DISCONNECTED;
        }
    }

    /**
     * Appends a player/world/global/listener completion to the exact listener's
     * sticky Main FIFO. Publication is synchronous; execution remains ordered and
     * asynchronous with respect to the producer. The completion is cancelled if
     * its captured player lifetime ends before execution.
     *
     * @return {@code true} when A5 owns the completion, including a stale token;
     *         {@code false} returns the body to the original vanilla execution path.
     */
    public static boolean enqueueMain(ContinuationOwner owner, String operation,
                                      Runnable continuation) {
        Objects.requireNonNull(continuation, "continuation");
        if (owner == null) return false;
        if (!isCurrent(owner, owner.listener)) return true;
        if (disconnected(owner)) return true;
        if (!venueActive(owner)) return false;
        boolean accepted = AsyncIslandPlayerTicks.enqueuePacket(
                new MainContinuationPacket(owner, normalized(operation), continuation),
                owner.listener);
        if (accepted) return true;
        return rejectedPublication(owner, "Main");
    }

    /**
     * Publishes the exact command marker in the listener FIFO before returning the
     * tagged task which must occupy vanilla's original global executor slot.
     *
     * <p>A null result means the marker was not retained (including a synchronous
     * disconnect/terminalization race) and the caller must use the untouched
     * vanilla {@code server.execute} path. Once a task is returned the command is
     * owned exactly once by A5: lifecycle changes may alter how the marker is
     * consumed, but may never discard its body.</p>
     */
    public static Runnable enqueueMainCommand(
            ContinuationOwner owner, String operation, Runnable continuation) {
        Objects.requireNonNull(continuation, "continuation");
        if (owner == null
                || !isCurrent(owner, owner.listener)
                || disconnected(owner)
                || !venueActive(owner)) {
            return null;
        }
        MainCommandContinuationPacket packet =
                new MainCommandContinuationPacket(
                        owner, normalized(operation), continuation);
        MainCommandTask task = new MainCommandTask(owner, packet);
        packet.bindTask(task);
        boolean accepted = AsyncIslandPlayerTicks.enqueuePacket(
                packet, owner.listener);
        if (!accepted) {
            rejectedPublication(owner, "Main command");
            return null;
        }
        if (!AsyncIslandPlayerTicks.ownsQueuedMainCommand(
                owner.state, owner.listener, packet)) {
            if (!isCurrent(owner, owner.listener)
                    || disconnected(owner)
                    || !venueActive(owner)) {
                return null;
            }
            throw new IllegalStateException(
                    "A5 accepted a current Main command continuation without "
                            + "retaining its exact marker");
        }
        return task;
    }

    /**
     * Registers a guarded callback at its original vanilla asynchronous boundary.
     * An unowned Netty publisher is not a simulation body in the regional pool:
     * disabling A5 must not make vanilla executor/chain registration self-refuse
     * behind Main's exclusive turn. The eventual callback still needs its own
     * atomic body admission, including when a ready future executes it inline.
     * Registration failures and a concurrent selected failure remain fatal.
     */
    public static <T> T register(ContinuationOwner owner, Supplier<T> registration) {
        Objects.requireNonNull(registration, "registration");
        if (owner == null) {
            RegionScheduler.throwWorkerFailureIfAny();
            try {
                T result = registration.get();
                RegionScheduler.throwWorkerFailureIfAny();
                return result;
            } catch (Throwable failure) {
                IslandCommands.publishAndPropagate(failure);
                throw new AssertionError("unreachable");
            }
        }
        AtomicReference<T> result = new AtomicReference<>();
        RegionScheduler.runSuccessBodyIfHealthy(() -> result.set(registration.get()));
        return result.get();
    }

    /**
     * Wraps the signed-command chat task chainer so argument filtering/decorating
     * callbacks also re-enter the listener's Main FIFO.  The underlying chain still
     * establishes vanilla's completion order; only the eventual consumer's owner is
     * changed.
     */
    public static TaskChainer mainTaskChainer(ContinuationOwner owner,
                                              TaskChainer vanilla,
                                              String operation) {
        Objects.requireNonNull(vanilla, "vanilla");
        String label = normalized(operation);
        return new TaskChainer() {
            @Override
            public <T> void append(CompletableFuture<T> future,
                                   Consumer<T> consumer) {
                Objects.requireNonNull(future, "future");
                Objects.requireNonNull(consumer, "consumer");
                Consumer<T> routed = value ->
                        RegionScheduler.runSuccessBodyIfHealthy(() -> {
                            Runnable body = () -> consumer.accept(value);
                            if (!enqueueMain(owner, label, body)) {
                                consumer.accept(value);
                            }
                        });
                register(owner, () -> {
                    vanilla.append(future, routed);
                    return null;
                });
            }
        };
    }

    private static boolean isCurrent(ContinuationOwner owner,
                                     ServerGamePacketListenerImpl listener) {
        if (listener != owner.listener || listener.player != owner.player
                || owner.player.connection != listener || owner.player.isRemoved()
                || owner.player.level() != owner.level
                || RegionScheduler.forLevel(owner.level) != owner.runtime
                || !(listener instanceof AsyncIslandListenerAccess listenerAccess)
                || listenerAccess.fabrolia$asyncIslandPlayerState() != owner.state
                || !(owner.player instanceof PlayerTickPhaseStamp lifecycle)) {
            return false;
        }
        return lifecycle.fabrolia$playerLifecycleEpoch() == owner.lifecycleEpoch;
    }

    private static boolean venueActive(ContinuationOwner owner) {
        return owner.player.level() == owner.level
                && RegionScheduler.forLevel(owner.level) == owner.runtime
                && AsyncIslandPlayerTicks.enabledForAsync(owner.runtime);
    }

    /** Revalidates a false lower-seam result without converting stale work to inline. */
    private static boolean rejectedPublication(ContinuationOwner owner,
                                               String lane) {
        if (!isCurrent(owner, owner.listener) || disconnected(owner)) return true;
        if (!venueActive(owner)) return false;
        throw new IllegalStateException(
                "A5 rejected a current active " + lane + " packet continuation");
    }

    private static boolean disconnected(ContinuationOwner owner) {
        synchronized (owner.state) {
            return owner.state.mainDisconnectPending
                    || owner.state.session.view().phase()
                    == IslandPlayerTurnSession.Phase.DISCONNECTED;
        }
    }

    private static String normalized(String operation) {
        return operation == null || operation.isBlank()
                ? "packet-continuation" : operation;
    }

    /** Exact in-process marker recognized by A5 before it removes a FIFO entry. */
    static boolean isMainCommandPacket(Packet<?> packet) {
        return packet instanceof MainCommandContinuationPacket;
    }

    /** Exact task-frame proof used by A5 to release one command marker. */
    static boolean exactMainCommandTaskActive(
            Packet<?> packet, ServerGamePacketListenerImpl listener) {
        MainCommandTaskFrame frame = MAIN_COMMAND_TASK.get();
        return frame != null
                && frame.packet == packet
                && frame.listener == listener
                && frame.task.baton.running();
    }

    /** Transfer the still-unentered exact body behind its current FIFO predecessor. */
    static void deferExactMainCommandTask(
            Packet<?> packet, ServerGamePacketListenerImpl listener) {
        MainCommandTaskFrame frame = MAIN_COMMAND_TASK.get();
        if (frame == null || frame.packet != packet || frame.listener != listener) {
            throw new IllegalStateException(
                    "A5 Main command deferral lost its exact running task");
        }
        frame.task.baton.deferRunning();
    }

    /** Close the baton only after the exact marker body returned successfully. */
    static void completeExactMainCommandTask(
            Packet<?> packet, ServerGamePacketListenerImpl listener) {
        MainCommandTaskFrame frame = MAIN_COMMAND_TASK.get();
        if (frame == null || frame.packet != packet || frame.listener != listener) {
            throw new IllegalStateException(
                    "A5 Main command completion lost its exact running task");
        }
        frame.task.baton.completeRunning();
    }

    /** Whether the exact continuation crossed its one irreversible body edge. */
    static boolean exactMainCommandBodyEntered(
            Packet<?> packet, ServerGamePacketListenerImpl listener) {
        MainCommandTaskFrame frame = MAIN_COMMAND_TASK.get();
        if (frame == null || frame.packet != packet || frame.listener != listener) {
            throw new IllegalStateException(
                    "A5 Main command body receipt lost its exact running task");
        }
        return frame.task.bodyEntered;
    }

    /** True only for a body which an earlier FIFO boundary has already deferred. */
    static boolean deferredExactMainCommand(Packet<?> packet,
                                            ServerGamePacketListenerImpl listener) {
        if (!(packet instanceof MainCommandContinuationPacket command)
                || command.owner.listener != listener) {
            return false;
        }
        MainCommandTask task = command.task;
        return task != null && task.baton.deferred();
    }

    /**
     * Reserve and publish one later physical-Main slot for a deferred, unentered
     * command body. The DEFERRED -> QUEUED CAS precedes publication, so two
     * predecessor completions cannot manufacture duplicate command tasks. A failed
     * schedule deliberately leaves QUEUED behind W; it is never reset/retried.
     */
    static void scheduleDeferredExactMainCommand(
            Packet<?> packet, ServerGamePacketListenerImpl listener) {
        if (!(packet instanceof MainCommandContinuationPacket command)
                || command.owner.listener != listener || command.task == null) {
            throw new IllegalStateException(
                    "A5 deferred Main command lost its packet/task identity");
        }
        command.task.scheduleDeferred();
    }

    /** Unknown-to-the-vanilla-policy in-process packet; exact lookup => MAIN_FIFO. */
    private record MainContinuationPacket(ContinuationOwner owner, String operation,
                                          Runnable continuation)
            implements Packet<ServerGamePacketListenerImpl> {

        @Override
        public PacketType<? extends Packet<ServerGamePacketListenerImpl>> type() {
            return MAIN_CONTINUATION_TYPE;
        }

        @Override
        public void handle(ServerGamePacketListenerImpl listener) {
            if (isCurrent(owner, listener) && !disconnected(owner)) {
                continuation.run();
            }
        }
    }

    private record ChatSessionPublicationPacket(ChatSessionOwner owner,
                                                 Runnable continuation)
            implements Packet<ServerGamePacketListenerImpl> {
        @Override
        public PacketType<? extends Packet<ServerGamePacketListenerImpl>> type() {
            return CHAT_SESSION_TYPE;
        }

        @Override
        public void handle(ServerGamePacketListenerImpl listener) {
            if (isCurrent(owner, listener)) continuation.run();
        }
    }

    /** Separate fail-closed marker which can only be consumed by its tagged task. */
    private static final class MainCommandContinuationPacket
            implements Packet<ServerGamePacketListenerImpl> {
        private final ContinuationOwner owner;
        @SuppressWarnings("unused")
        private final String operation;
        private final Runnable continuation;
        private volatile MainCommandTask task;

        private MainCommandContinuationPacket(
                ContinuationOwner owner, String operation, Runnable continuation) {
            this.owner = owner;
            this.operation = operation;
            this.continuation = continuation;
        }

        private void bindTask(MainCommandTask candidate) {
            if (candidate == null || task != null) {
                throw new IllegalStateException(
                        "A5 Main command packet task was bound more than once");
            }
            task = candidate;
        }

        @Override
        public PacketType<? extends Packet<ServerGamePacketListenerImpl>> type() {
            return MAIN_COMMAND_CONTINUATION_TYPE;
        }

        @Override
        public void handle(ServerGamePacketListenerImpl listener) {
            if (!exactMainCommandTaskActive(this, listener)) {
                throw new IllegalStateException(
                        "A5 Main command marker escaped its exact tagged task");
            }
            MainCommandTaskFrame frame = MAIN_COMMAND_TASK.get();
            frame.task.markBodyEntered();
            if (frame.cleanFence) {
                PlayerCommandFenceScope.runCleanMainTask(
                        frame.task.server, listener, this, continuation);
            } else {
                continuation.run();
            }
        }
    }

    /** MC-free exact-once state machine for the unentered command body baton. */
    static final class MainCommandBaton {
        static final int NEW = 0;
        static final int RUNNING = 1;
        static final int DEFERRED = 2;
        static final int QUEUED = 3;
        static final int COMPLETE = 4;
        static final int CANCELLED = 5;

        private final AtomicInteger phase = new AtomicInteger(NEW);

        void beginRun() {
            int observed = phase.get();
            if ((observed != NEW && observed != QUEUED)
                    || !phase.compareAndSet(observed, RUNNING)) {
                throw new IllegalStateException(
                        "A5 Main command task entered from baton phase " + phase.get());
            }
        }

        void deferRunning() {
            if (!phase.compareAndSet(RUNNING, DEFERRED)) {
                throw new IllegalStateException(
                        "A5 Main command deferral lost its running baton");
            }
        }

        void reserveDeferredSchedule() {
            if (!phase.compareAndSet(DEFERRED, QUEUED)) {
                throw new IllegalStateException(
                        "A5 Main command schedule lost its deferred baton");
            }
        }

        void completeRunning() {
            if (!phase.compareAndSet(RUNNING, COMPLETE)) {
                throw new IllegalStateException(
                        "A5 Main command completion lost its running baton");
            }
        }

        void cancelQueued() {
            int observed = phase.get();
            if (observed == CANCELLED) return;
            if ((observed != NEW && observed != QUEUED)
                    || !phase.compareAndSet(observed, CANCELLED)) {
                throw new IllegalStateException(
                        "A5 Main command cancellation crossed body entry");
            }
        }

        boolean running() {
            return phase.get() == RUNNING;
        }

        boolean deferred() {
            return phase.get() == DEFERRED;
        }

        int phase() {
            return phase.get();
        }
    }

    /** One exact Main controller for the marker published at tryHandleChat. */
    private static final class MainCommandTask
            implements Runnable, RegionTaskFanIn.CancellableQueueEntry {
        private final ContinuationOwner owner;
        private final MainCommandContinuationPacket packet;
        private final MinecraftServer server;
        private final MainCommandBaton baton = new MainCommandBaton();
        private boolean bodyEntered;

        private MainCommandTask(ContinuationOwner owner,
                                MainCommandContinuationPacket packet) {
            this.owner = owner;
            this.packet = packet;
            this.server = Objects.requireNonNull(
                    owner.level.getServer(), "command server");
        }

        @Override
        public void run() {
            if (MAIN_COMMAND_TASK.get() != null) {
                throw new IllegalStateException("nested A5 Main command task");
            }
            baton.beginRun();
            MainCommandTaskFrame frame =
                    new MainCommandTaskFrame(packet, owner.listener, this,
                            RconCommandFenceScope.exactMonitorFreeMainTask(server));
            MAIN_COMMAND_TASK.set(frame);
            try {
                AsyncIslandPlayerTicks.drainExactMainCommand(
                        owner.state, owner.listener, packet, server);
                if (baton.running()) {
                    throw new IllegalStateException(
                            "A5 Main command task returned without completing or deferring");
                }
            } finally {
                MAIN_COMMAND_TASK.remove();
            }
        }

        private void scheduleDeferred() {
            baton.reserveDeferredSchedule();
            RegionScheduler.scheduleTrackedMainControlTask(server, this);
        }

        @Override
        public void cancelQueuedBeforeStart() {
            baton.cancelQueued();
        }

        private void markBodyEntered() {
            if (bodyEntered) {
                throw new IllegalStateException(
                        "A5 Main command continuation body entered more than once");
            }
            bodyEntered = true;
        }
    }

    private record MainCommandTaskFrame(
            MainCommandContinuationPacket packet,
            ServerGamePacketListenerImpl listener,
            MainCommandTask task,
            boolean cleanFence) {}
}
