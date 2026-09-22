package com.axalotl.fabrolia.common.regions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minecraft-free ownership transaction for one player connection.
 *
 * <p>An attached session remains {@link Phase#ISLAND_READY} between turns. A real
 * player turn is one CAS chain ({@code EARLY_RESERVED -> EARLY_RUNNING ->
 * EARLY_DONE -> LATE_RUNNING -> ISLAND_READY}); a Carpet fake commits its combined
 * body directly from {@code EARLY_RUNNING}. The reserved packet prefix executes
 * before {@code EARLY_RUNNING}. Before its first packet body enters the reservation
 * may fall back; afterwards it can only enter the entity body, fail-stop, disconnect,
 * or atomically hand the still-owed entity body to Main.
 *
 * <p>{@code turnToken} is an unsigned, strictly increasing value within one exact
 * {@link OwnerStamp}.  A transfer must install a greater session epoch and starts a
 * fresh token ledger.  This gives a bounded exact-once ledger without retaining a
 * token for every tick for the lifetime of a connection.
 *
 * <p>The packet queue is FIFO.  Reserving a turn snapshots its current tail as the
 * head cutoff, so packets offered later remain for the next turn. The reserved FIFO
 * prefix may be replayed before the irreversible body-entry CAS; a lifecycle change
 * can then hand the still-owed entity body to Main without pretending it ran. An
 * island to Main handoff closes through {@code MAIN_DRAINING}; this keeps replacement
 * attach impossible until physical Main finishes applying the detached prefix.
 */
public final class IslandPlayerTurnSession<P> {

    public enum PlayerKind { REAL, CARPET }

    public enum Phase {
        MAIN,
        ISLAND_READY,
        EARLY_RESERVED,
        EARLY_RUNNING,
        EARLY_DONE,
        LATE_RUNNING,
        MAIN_FALLBACK_RESERVED,
        MAIN_FALLBACK_RUNNING,
        MAIN_HANDOFF,
        MAIN_DRAINING,
        TRANSFERRING,
        DISCONNECTED
    }

    public enum PacketDisposition { CARRY, DROP }

    public enum AfterBodyResult { CONTAINED_NO_MAIN_FALLBACK, REJECTED }

    /**
     * Exact connection/island ownership stamp.  Identity-bearing components use
     * {@code ==}, deliberately ignoring user-defined {@code equals} methods.
     */
    public static final class OwnerStamp {
        private final Object playerIdentity;
        private final Object listenerIdentity;
        private final Object islandIdentity;
        private final long islandId;
        private final long islandGeneration;
        private final Object domainIdentity;
        private final long lifecycleEpoch;
        private final long sessionEpoch;
        private final PlayerKind kind;

        public OwnerStamp(Object playerIdentity,
                          Object listenerIdentity,
                          Object islandIdentity,
                          long islandId,
                          long islandGeneration,
                          Object domainIdentity,
                          long lifecycleEpoch,
                          long sessionEpoch,
                          PlayerKind kind) {
            this.playerIdentity = Objects.requireNonNull(playerIdentity, "playerIdentity");
            this.listenerIdentity = Objects.requireNonNull(listenerIdentity, "listenerIdentity");
            this.islandIdentity = Objects.requireNonNull(islandIdentity, "islandIdentity");
            this.domainIdentity = Objects.requireNonNull(domainIdentity, "domainIdentity");
            this.kind = Objects.requireNonNull(kind, "kind");
            if (islandGeneration < 0L) throw new IllegalArgumentException("negative islandGeneration");
            if (lifecycleEpoch < 0L) throw new IllegalArgumentException("negative lifecycleEpoch");
            if (sessionEpoch < 0L) throw new IllegalArgumentException("negative sessionEpoch");
            this.islandId = islandId;
            this.islandGeneration = islandGeneration;
            this.lifecycleEpoch = lifecycleEpoch;
            this.sessionEpoch = sessionEpoch;
        }

        public Object playerIdentity() { return playerIdentity; }
        public Object listenerIdentity() { return listenerIdentity; }
        public Object islandIdentity() { return islandIdentity; }
        public long islandId() { return islandId; }
        public long islandGeneration() { return islandGeneration; }
        public Object domainIdentity() { return domainIdentity; }
        public long lifecycleEpoch() { return lifecycleEpoch; }
        public long sessionEpoch() { return sessionEpoch; }
        public PlayerKind kind() { return kind; }

        public boolean matchesExactly(OwnerStamp other) {
            return other != null
                    && playerIdentity == other.playerIdentity
                    && listenerIdentity == other.listenerIdentity
                    && islandIdentity == other.islandIdentity
                    && islandId == other.islandId
                    && islandGeneration == other.islandGeneration
                    && domainIdentity == other.domainIdentity
                    && lifecycleEpoch == other.lifecycleEpoch
                    && sessionEpoch == other.sessionEpoch
                    && kind == other.kind;
        }

        /** Valid ownership replacement after a quiescent transfer. */
        public boolean canBeSucceededBy(OwnerStamp next) {
            if (next == null || playerIdentity != next.playerIdentity || kind != next.kind) {
                return false;
            }
            if (next.sessionEpoch <= sessionEpoch || next.lifecycleEpoch < lifecycleEpoch) {
                return false;
            }
            return listenerIdentity == next.listenerIdentity
                    || next.lifecycleEpoch > lifecycleEpoch;
        }

        @Override
        public boolean equals(Object value) {
            return value == this || value instanceof OwnerStamp other && matchesExactly(other);
        }

        @Override
        public int hashCode() {
            int result = System.identityHashCode(playerIdentity);
            result = 31 * result + System.identityHashCode(listenerIdentity);
            result = 31 * result + System.identityHashCode(islandIdentity);
            result = 31 * result + Long.hashCode(islandId);
            result = 31 * result + Long.hashCode(islandGeneration);
            result = 31 * result + System.identityHashCode(domainIdentity);
            result = 31 * result + Long.hashCode(lifecycleEpoch);
            result = 31 * result + Long.hashCode(sessionEpoch);
            return 31 * result + kind.hashCode();
        }
    }

    public record PacketEnvelope<P>(long sequence, OwnerStamp owner, P packet) {
        public PacketEnvelope {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(packet, "packet");
        }
    }

    public record LedgerSnapshot(boolean hasEarly,
                                 long lastEarlyToken,
                                 boolean hasLate,
                                 long lastLateToken,
                                 boolean hasTerminal,
                                 long lastTerminalToken) {}

    public record View(Phase phase,
                       OwnerStamp owner,
                       long activeTurnToken,
                       LedgerSnapshot ledger) {}

    /** Opaque claim for an island early/combined body. */
    public static final class EarlyPermit<P> {
        private final IslandPlayerTurnSession<P> session;
        private final OwnerStamp owner;
        private final long turnToken;
        private final long packetCutoff;
        private final AtomicBoolean packetsTaken = new AtomicBoolean();
        private final AtomicBoolean suffixRestored = new AtomicBoolean();
        private boolean packetBodyEntered;
        private List<PacketEnvelope<P>> takenPackets;

        private EarlyPermit(IslandPlayerTurnSession<P> session,
                            OwnerStamp owner,
                            long turnToken,
                            long packetCutoff) {
            this.session = session;
            this.owner = owner;
            this.turnToken = turnToken;
            this.packetCutoff = packetCutoff;
        }

        public OwnerStamp owner() { return owner; }
        public long turnToken() { return turnToken; }
        public long packetCutoff() { return packetCutoff; }
    }

    /** Exclusive main claim produced before any island body entered. */
    public static final class MainFallback<P> {
        private final IslandPlayerTurnSession<P> session;
        private final EarlyPermit<P> islandPermit;
        private final AtomicBoolean packetsTaken = new AtomicBoolean();

        private MainFallback(IslandPlayerTurnSession<P> session,
                             EarlyPermit<P> islandPermit) {
            this.session = session;
            this.islandPermit = islandPermit;
        }

        public OwnerStamp owner() { return islandPermit.owner; }
        public long turnToken() { return islandPermit.turnToken; }
        public long packetCutoff() { return islandPermit.packetCutoff; }
    }

    /**
     * Quiescent ISLAND -> MAIN ownership handoff.  Packet publication remains open
     * against the old exact owner until physical Main atomically closes this claim
     * and takes the complete FIFO.  That close happens from the already-enqueued
     * Main runnable. Closing first enters {@link Phase#MAIN_DRAINING}: new offers
     * use vanilla scheduling, but a replacement island owner cannot attach until
     * physical Main has applied the detached prefix and finishes the claim.
     */
    public static final class MainHandoff<P> {
        private final IslandPlayerTurnSession<P> session;
        private final OwnerStamp oldOwner;
        /** Exact FIFO-accepting frame observed while this transaction was prepared. */
        private final Frame<P> sourceFrame;
        private final Frame<P> readyFrame;
        private final Frame<P> handingOffFrame;
        private final Frame<P> drainingFrame;
        private final Frame<P> mainFrame;

        private MainHandoff(IslandPlayerTurnSession<P> session, Frame<P> sourceFrame) {
            this(session, sourceFrame, sourceFrame);
        }

        private MainHandoff(IslandPlayerTurnSession<P> session, Frame<P> sourceFrame,
                            Frame<P> terminalFrame) {
            this.session = session;
            this.oldOwner = sourceFrame.owner;
            this.sourceFrame = sourceFrame;
            // Allocate the complete state transaction before its first CAS. Once
            // MAIN_HANDOFF is visible, rollback/close/finish must be allocation-free:
            // an allocation failure may crash exactly like vanilla, but may never
            // strand an unreturnable ownership phase or detached listener FIFO.
            // Rollback also gets a distinct frame identity; restoring the exact
            // pre-CAS object would introduce an ABA-shaped future regression seam.
            this.readyFrame = terminalFrame.move(Phase.ISLAND_READY, null);
            this.handingOffFrame = terminalFrame.move(Phase.MAIN_HANDOFF, this);
            this.drainingFrame = terminalFrame.move(Phase.MAIN_DRAINING, this);
            this.mainFrame = new Frame<>(Phase.MAIN, null, null,
                    terminalFrame.hasEarly, terminalFrame.lastEarly,
                    terminalFrame.hasLate, terminalFrame.lastLate,
                    terminalFrame.hasTerminal, terminalFrame.lastTerminal);
        }

        public OwnerStamp oldOwner() { return oldOwner; }
    }

    /** Quiescent transfer claim.  Pending packets are immutable and remain FIFO. */
    public static final class Transfer<P> {
        private final IslandPlayerTurnSession<P> session;
        private final OwnerStamp oldOwner;
        private final List<PacketEnvelope<P>> pendingPackets;

        private Transfer(IslandPlayerTurnSession<P> session,
                         OwnerStamp oldOwner,
                         List<PacketEnvelope<P>> pendingPackets) {
            this.session = session;
            this.oldOwner = oldOwner;
            this.pendingPackets = List.copyOf(pendingPackets);
        }

        public OwnerStamp oldOwner() { return oldOwner; }
        public List<PacketEnvelope<P>> pendingPackets() { return pendingPackets; }
    }

    /** Packets invalidated by a terminal disconnect. */
    public record Disconnect<P>(OwnerStamp oldOwner,
                                List<PacketEnvelope<P>> pendingPackets) {
        public Disconnect {
            Objects.requireNonNull(oldOwner, "oldOwner");
            pendingPackets = List.copyOf(pendingPackets);
        }
    }

    private static final class Frame<P> {
        final Phase phase;
        final OwnerStamp owner;
        final Object claim;
        final boolean hasEarly;
        final long lastEarly;
        final boolean hasLate;
        final long lastLate;
        final boolean hasTerminal;
        final long lastTerminal;

        Frame(Phase phase, OwnerStamp owner, Object claim,
              boolean hasEarly, long lastEarly,
              boolean hasLate, long lastLate,
              boolean hasTerminal, long lastTerminal) {
            this.phase = phase;
            this.owner = owner;
            this.claim = claim;
            this.hasEarly = hasEarly;
            this.lastEarly = lastEarly;
            this.hasLate = hasLate;
            this.lastLate = lastLate;
            this.hasTerminal = hasTerminal;
            this.lastTerminal = lastTerminal;
        }

        Frame<P> move(Phase nextPhase, Object nextClaim) {
            return new Frame<>(nextPhase, owner, nextClaim,
                    hasEarly, lastEarly, hasLate, lastLate,
                    hasTerminal, lastTerminal);
        }

        Frame<P> earlyDone(EarlyPermit<P> permit) {
            return new Frame<>(Phase.EARLY_DONE, owner, permit,
                    true, permit.turnToken, hasLate, lastLate,
                    hasTerminal, lastTerminal);
        }

        Frame<P> terminal() {
            long token = activeToken(claim);
            return new Frame<>(Phase.ISLAND_READY, owner, null,
                    true, token, true, token, true, token);
        }

        private static long activeToken(Object claim) {
            if (claim instanceof EarlyPermit<?> permit) return permit.turnToken;
            if (claim instanceof MainFallback<?> fallback) return fallback.turnToken();
            throw new IllegalStateException("terminal frame without a turn claim");
        }
    }

    private final AtomicReference<Frame<P>> frame = new AtomicReference<>(
            new Frame<>(Phase.MAIN, null, null,
                    false, 0L, false, 0L, false, 0L));
    private final Object packetLock = new Object();
    private final ArrayDeque<PacketEnvelope<P>> packetQueue = new ArrayDeque<>();
    private long nextPacketSequence;

    /** Attach once.  A disconnected core is terminal and must not be recycled. */
    public boolean attach(OwnerStamp owner) {
        Objects.requireNonNull(owner, "owner");
        Frame<P> observed = frame.get();
        if (observed.phase != Phase.MAIN) return false;
        Frame<P> ready = new Frame<>(Phase.ISLAND_READY, owner, null,
                false, 0L, false, 0L, false, 0L);
        return frame.compareAndSet(observed, ready);
    }

    public View view() {
        Frame<P> observed = frame.get();
        return new View(observed.phase, observed.owner, activeTokenOrZero(observed.claim),
                new LedgerSnapshot(observed.hasEarly, observed.lastEarly,
                        observed.hasLate, observed.lastLate,
                        observed.hasTerminal, observed.lastTerminal));
    }

    /** Allocation-free phase probe for failure cleanup and invariant reporting. */
    public Phase phase() {
        return frame.get().phase;
    }

    /** Quiescent handoff admission may omit a replay venue only when its old FIFO is empty. */
    public boolean hasQueuedPackets() {
        synchronized (packetLock) { return !packetQueue.isEmpty(); }
    }

    /** Offer a packet to the current exact session. */
    public OptionalLong enqueuePacket(OwnerStamp expected, P packet) {
        Objects.requireNonNull(packet, "packet");
        synchronized (packetLock) {
            Frame<P> observed = frame.get();
            if (!acceptsPackets(observed.phase) || !sameOwner(observed, expected)) {
                return OptionalLong.empty();
            }
            if (nextPacketSequence == -1L) {
                throw new IllegalStateException("packet sequence exhausted");
            }
            long sequence = ++nextPacketSequence;
            PacketEnvelope<P> envelope = new PacketEnvelope<>(
                    sequence, observed.owner, packet);
            OptionalLong accepted = OptionalLong.of(sequence);
            packetQueue.addLast(envelope);
            return accepted;
        }
    }

    /** Atomically reserve this exact owner/token and snapshot the FIFO cutoff. */
    public Optional<EarlyPermit<P>> tryReserveEarly(OwnerStamp expected, long turnToken) {
        synchronized (packetLock) {
            Frame<P> observed = frame.get();
            if (observed.phase != Phase.ISLAND_READY || !sameOwner(observed, expected)
                    || !newerThanTerminal(observed, turnToken)) {
                return Optional.empty();
            }
            EarlyPermit<P> permit = new EarlyPermit<>(this, observed.owner,
                    turnToken, nextPacketSequence);
            Frame<P> reserved = observed.move(Phase.EARLY_RESERVED, permit);
            Optional<EarlyPermit<P>> accepted = Optional.of(permit);
            if (!frame.compareAndSet(observed, reserved)) {
                return Optional.empty();
            }
            return accepted;
        }
    }

    /** Irreversible entity-body entry; packet entry may have closed fallback earlier. */
    public boolean enterIslandBody(EarlyPermit<P> permit) {
        Frame<P> observed = frame.get();
        return isClaim(observed, Phase.EARLY_RESERVED, permit)
                && frame.compareAndSet(observed, observed.move(Phase.EARLY_RUNNING, permit));
    }

    /**
     * Move an unentered island reservation to an exclusive main transaction.
     * There is intentionally no equivalent transition from EARLY_RUNNING or later.
     */
    public Optional<MainFallback<P>> tryMoveToMainFallback(EarlyPermit<P> permit) {
        if (!belongsHere(permit)) return Optional.empty();
        synchronized (packetLock) {
            Frame<P> observed = frame.get();
            if (!isClaim(observed, Phase.EARLY_RESERVED, permit)
                    || permit.packetBodyEntered) {
                return Optional.empty();
            }
            MainFallback<P> fallback = new MainFallback<>(this, permit);
            Frame<P> reserved = observed.move(Phase.MAIN_FALLBACK_RESERVED, fallback);
            Optional<MainFallback<P>> accepted = Optional.of(fallback);
            if (!frame.compareAndSet(observed, reserved)) {
                return Optional.empty();
            }
            return accepted;
        }
    }

    public boolean enterMainFallbackBody(MainFallback<P> fallback) {
        Frame<P> observed = frame.get();
        return isClaim(observed, Phase.MAIN_FALLBACK_RESERVED, fallback)
                && frame.compareAndSet(observed,
                observed.move(Phase.MAIN_FALLBACK_RUNNING, fallback));
    }

    /** Cancel an unentered main claim; the token remains eligible for a later retry. */
    public boolean cancelMainFallbackBeforeBody(MainFallback<P> fallback) {
        if (!belongsHere(fallback)) return false;
        synchronized (packetLock) {
            Frame<P> observed = frame.get();
            return isClaim(observed, Phase.MAIN_FALLBACK_RESERVED, fallback)
                    && frame.compareAndSet(observed,
                    observed.move(Phase.ISLAND_READY, null));
        }
    }

    /**
     * Publish the first irreversible packet-body edge for this reservation.
     * Repeated calls authenticate later envelopes without changing the one-shot edge.
     */
    public boolean enterIslandPacketBody(EarlyPermit<P> permit) {
        if (!belongsHere(permit)) return false;
        synchronized (packetLock) {
            Frame<P> observed = frame.get();
            if (!isClaim(observed, Phase.EARLY_RESERVED, permit)
                    || !permit.packetsTaken.get()) {
                return false;
            }
            permit.packetBodyEntered = true;
            return true;
        }
    }

    /** Take the reserved FIFO prefix once while this exact early claim is live. */
    public Optional<List<PacketEnvelope<P>>> takeIslandHeadPackets(EarlyPermit<P> permit) {
        if (!belongsHere(permit)) return Optional.empty();
        synchronized (packetLock) {
            Frame<P> observed = frame.get();
            if (!isIslandReplayClaim(observed, permit)
                    || !permit.packetsTaken.compareAndSet(false, true)) {
                return Optional.empty();
            }
            List<PacketEnvelope<P>> taken = drainThrough(permit.packetCutoff);
            permit.takenPackets = taken;
            return Optional.of(taken);
        }
    }

    /**
     * Return the untouched suffix of an island packet batch after one of its
     * handlers initiated a lifecycle cut or failed. The original sequence numbers
     * are retained and the suffix is prepended ahead of packets published after the
     * turn cutoff, so a later Main handoff observes the original connection FIFO.
     *
     * <p>Validation is deliberately complete before either the queue or the
     * one-shot marker changes. A rejected caller can therefore retry with the exact
     * suffix, while a successful restore cannot duplicate the batch.
     */
    public boolean restoreIslandHeadSuffix(EarlyPermit<P> permit,
                                           List<PacketEnvelope<P>> suffix) {
        if (!belongsHere(permit) || suffix == null) return false;
        List<PacketEnvelope<P>> stableSuffix;
        try {
            stableSuffix = List.copyOf(suffix);
        } catch (RuntimeException invalidList) {
            return false;
        }
        synchronized (packetLock) {
            Frame<P> observed = frame.get();
            List<PacketEnvelope<P>> taken = permit.takenPackets;
            if (!isIslandReplayClaim(observed, permit)
                    || !permit.packetsTaken.get()
                    || permit.suffixRestored.get()
                    || taken == null
                    || stableSuffix.size() > taken.size()) {
                return false;
            }

            int takenOffset = taken.size() - stableSuffix.size();
            long previous = 0L;
            boolean hasPrevious = false;
            for (int index = 0; index < stableSuffix.size(); index++) {
                PacketEnvelope<P> candidate = stableSuffix.get(index);
                PacketEnvelope<P> original = taken.get(takenOffset + index);
                if (candidate == null
                        || !candidate.owner.matchesExactly(permit.owner)
                        || candidate.sequence != original.sequence
                        || !candidate.owner.matchesExactly(original.owner)
                        || candidate.packet != original.packet
                        || Long.compareUnsigned(candidate.sequence,
                        permit.packetCutoff) > 0
                        || hasPrevious
                        && Long.compareUnsigned(candidate.sequence, previous) <= 0) {
                    return false;
                }
                previous = candidate.sequence;
                hasPrevious = true;
            }
            if (hasPrevious && !packetQueue.isEmpty()
                    && Long.compareUnsigned(previous,
                    packetQueue.peekFirst().sequence) >= 0) {
                return false;
            }
            if (!permit.suffixRestored.compareAndSet(false, true)) return false;
            for (int index = stableSuffix.size() - 1; index >= 0; index--) {
                packetQueue.addFirst(stableSuffix.get(index));
            }
            return true;
        }
    }

    /** Take the same reserved FIFO prefix once, after the main body has entered. */
    public Optional<List<PacketEnvelope<P>>> takeMainHeadPackets(MainFallback<P> fallback) {
        if (!belongsHere(fallback)) return Optional.empty();
        synchronized (packetLock) {
            Frame<P> observed = frame.get();
            if (!isClaim(observed, Phase.MAIN_FALLBACK_RUNNING, fallback)
                    || !fallback.packetsTaken.compareAndSet(false, true)) {
                return Optional.empty();
            }
            return Optional.of(drainThrough(fallback.packetCutoff()));
        }
    }

    public boolean completeRealEarly(EarlyPermit<P> permit) {
        if (!belongsHere(permit) || permit.owner.kind != PlayerKind.REAL) return false;
        synchronized (packetLock) {
            Frame<P> observed = frame.get();
            return isClaim(observed, Phase.EARLY_RUNNING, permit)
                    && frame.compareAndSet(observed, observed.earlyDone(permit));
        }
    }

    /** Capture the original irreversible real E/L obligation; this is not body authority. */
    @SuppressWarnings("unchecked")
    EarlyPermit<P> enteredRealLateObligation() {
        Frame<P> observed = frame.get();
        if (observed.owner == null || observed.owner.kind != PlayerKind.REAL
                || observed.phase != Phase.EARLY_RUNNING && observed.phase != Phase.EARLY_DONE) {
            return null;
        }
        return (EarlyPermit<P>) observed.claim;
    }

    /** Exact, non-mutating proof that this real-player token owns the late slot. */
    boolean isAwaitingRealLate(EarlyPermit<P> permit) {
        if (!belongsHere(permit) || permit.owner.kind != PlayerKind.REAL) return false;
        return isClaim(frame.get(), Phase.EARLY_DONE, permit);
    }

    /** Exact, non-mutating proof that this real-player token owns the active late body. */
    boolean isRunningRealLate(EarlyPermit<P> permit) {
        if (!belongsHere(permit) || permit.owner.kind != PlayerKind.REAL) return false;
        return isClaim(frame.get(), Phase.LATE_RUNNING, permit);
    }

    public boolean beginRealLate(EarlyPermit<P> permit) {
        if (!belongsHere(permit) || permit.owner.kind != PlayerKind.REAL) return false;
        Frame<P> observed = frame.get();
        return isClaim(observed, Phase.EARLY_DONE, permit)
                && frame.compareAndSet(observed, observed.move(Phase.LATE_RUNNING, permit));
    }

    public boolean completeRealLate(EarlyPermit<P> permit) {
        if (!belongsHere(permit) || permit.owner.kind != PlayerKind.REAL) return false;
        synchronized (packetLock) {
            Frame<P> observed = frame.get();
            return isClaim(observed, Phase.LATE_RUNNING, permit)
                    && frame.compareAndSet(observed, observed.terminal());
        }
    }

    /** Commit Carpet's {@code super.tick()+doTick()} as one indivisible turn. */
    public boolean completeCarpetCombined(EarlyPermit<P> permit) {
        if (!belongsHere(permit) || permit.owner.kind != PlayerKind.CARPET) return false;
        synchronized (packetLock) {
            Frame<P> observed = frame.get();
            return isClaim(observed, Phase.EARLY_RUNNING, permit)
                    && frame.compareAndSet(observed, observed.terminal());
        }
    }

    public boolean completeMainFallback(MainFallback<P> fallback) {
        if (!belongsHere(fallback)) return false;
        synchronized (packetLock) {
            Frame<P> observed = frame.get();
            return isClaim(observed, Phase.MAIN_FALLBACK_RUNNING, fallback)
                    && frame.compareAndSet(observed, observed.terminal());
        }
    }

    /** Begin a quiescent handoff while continuing to accept the old session FIFO. */
    public Optional<MainHandoff<P>> beginMainHandoff(OwnerStamp expected) {
        synchronized (packetLock) {
            Frame<P> observed = frame.get();
            if (observed.phase != Phase.ISLAND_READY || !sameOwner(observed, expected)) {
                return Optional.empty();
            }
            MainHandoff<P> handoff = new MainHandoff<>(this, observed);
            Optional<MainHandoff<P>> accepted = Optional.of(handoff);
            return frame.compareAndSet(observed, handoff.handingOffFrame)
                    ? accepted : Optional.empty();
        }
    }

    /**
     * Preallocate a quiescent handoff without publishing {@link Phase#MAIN_HANDOFF}.
     * The returned transaction captures the exact ISLAND_READY frame, so a later
     * accept cannot overwrite an intervening turn which returned to the same-looking
     * owner.  All frames and the Optional are allocated before any lifecycle cut.
     */
    public Optional<MainHandoff<P>> prepareMainHandoff(OwnerStamp expected) {
        synchronized (packetLock) {
            Frame<P> observed = frame.get();
            if (observed.phase != Phase.ISLAND_READY || !sameOwner(observed, expected)) {
                return Optional.empty();
            }
            return Optional.of(new MainHandoff<>(this, observed));
        }
    }

    /**
     * Prepare the direct packet-prefix cut. The consumed prefix stays consumed;
     * the unrun entity body becomes one Main obligation, and the terminal token
     * ledger is published atomically with MAIN_HANDOFF.
     */
    public Optional<MainHandoff<P>> preparePacketPrefixMainHandoff(
            EarlyPermit<P> permit) {
        if (!belongsHere(permit)) return Optional.empty();
        synchronized (packetLock) {
            Frame<P> observed = frame.get();
            if (!isClaim(observed, Phase.EARLY_RESERVED, permit)
                    || !permit.packetBodyEntered) {
                return Optional.empty();
            }
            return Optional.of(new MainHandoff<>(
                    this, observed, observed.terminal()));
        }
    }

    /** Allocation-free publication of one previously prepared exact transaction. */
    public boolean acceptMainHandoff(MainHandoff<P> handoff) {
        if (!belongsHere(handoff)) return false;
        // The exact source and MAIN_HANDOFF frames both accept the old owner's FIFO,
        // so this frame CAS does not need packetLock. Keeping it monitor-free makes
        // it legal as a fixed write inside the scheduler lifecycle cut.
        return frame.compareAndSet(handoff.sourceFrame, handoff.handingOffFrame);
    }

    /**
     * Physical-Main close edge. New offers are rejected to vanilla after this method
     * removes the whole old prefix, while {@link Phase#MAIN_DRAINING} prevents a new
     * island turn from overtaking that prefix during its Main-side replay.
     */
    public Optional<List<PacketEnvelope<P>>> completeMainHandoff(MainHandoff<P> handoff) {
        if (!belongsHere(handoff)) return Optional.empty();
        synchronized (packetLock) {
            Frame<P> observed = frame.get();
            if (!isClaim(observed, Phase.MAIN_HANDOFF, handoff)) return Optional.empty();
            List<PacketEnvelope<P>> pending = List.copyOf(packetQueue);
            Optional<List<PacketEnvelope<P>>> completed = Optional.of(pending);
            if (!frame.compareAndSet(observed, handoff.drainingFrame)) return Optional.empty();
            packetQueue.clear();
            return completed;
        }
    }

    /**
     * Publish quiescent MAIN after the detached FIFO has been transferred to the
     * listener boundary queue. That queue may execute a Main-required prefix or
     * reattach an island-safe prefix, but no owner can observe both venues at once.
     */
    public boolean finishMainHandoff(MainHandoff<P> handoff) {
        if (!belongsHere(handoff)) return false;
        synchronized (packetLock) {
            Frame<P> observed = frame.get();
            if (!isClaim(observed, Phase.MAIN_DRAINING, handoff)) return false;
            return frame.compareAndSet(observed, handoff.mainFrame);
        }
    }

    /** Restore island ownership if publication of the physical-Main closer failed. */
    public boolean abortMainHandoff(MainHandoff<P> handoff) {
        if (!belongsHere(handoff)) return false;
        synchronized (packetLock) {
            Frame<P> observed = frame.get();
            return isClaim(observed, Phase.MAIN_HANDOFF, handoff)
                    && frame.compareAndSet(observed, handoff.readyFrame);
        }
    }

    /**
     * Contain a failed packet body before the entity body entered. The consumed
     * prefix is never retryable on Main; untouched suffix restoration is owned by
     * the coordinator before this terminal edge.
     */
    public AfterBodyResult containIslandReplayFailure(EarlyPermit<P> permit) {
        if (!belongsHere(permit)) return AfterBodyResult.REJECTED;
        synchronized (packetLock) {
            Frame<P> observed = frame.get();
            if (!isClaim(observed, Phase.EARLY_RESERVED, permit)
                    || !permit.packetBodyEntered) {
                return AfterBodyResult.REJECTED;
            }
            return frame.compareAndSet(observed, observed.terminal())
                    ? AfterBodyResult.CONTAINED_NO_MAIN_FALLBACK
                    : AfterBodyResult.REJECTED;
        }
    }

    /**
     * Consume a token whose body entered and then failed.  It becomes ready for a
     * later token, but this token cannot be retried on main.
     */
    public AfterBodyResult containIslandFailure(EarlyPermit<P> permit) {
        if (!belongsHere(permit)) return AfterBodyResult.REJECTED;
        synchronized (packetLock) {
            Frame<P> observed = frame.get();
            if (!isIslandEnteredClaim(observed, permit)) return AfterBodyResult.REJECTED;
            return frame.compareAndSet(observed, observed.terminal())
                    ? AfterBodyResult.CONTAINED_NO_MAIN_FALLBACK
                    : AfterBodyResult.REJECTED;
        }
    }

    public AfterBodyResult containMainFailure(MainFallback<P> fallback) {
        if (!belongsHere(fallback)) return AfterBodyResult.REJECTED;
        synchronized (packetLock) {
            Frame<P> observed = frame.get();
            if (!isClaim(observed, Phase.MAIN_FALLBACK_RUNNING, fallback)) {
                return AfterBodyResult.REJECTED;
            }
            return frame.compareAndSet(observed, observed.terminal())
                    ? AfterBodyResult.CONTAINED_NO_MAIN_FALLBACK
                    : AfterBodyResult.REJECTED;
        }
    }

    /** Begin an ownership transfer only after the current turn is quiescent. */
    public Optional<Transfer<P>> beginTransfer(OwnerStamp expected) {
        synchronized (packetLock) {
            Frame<P> observed = frame.get();
            if (observed.phase != Phase.ISLAND_READY || !sameOwner(observed, expected)) {
                return Optional.empty();
            }
            List<PacketEnvelope<P>> pending = List.copyOf(packetQueue);
            Transfer<P> transfer = new Transfer<>(this, observed.owner, pending);
            Frame<P> transferring = observed.move(Phase.TRANSFERRING, transfer);
            Optional<Transfer<P>> accepted = Optional.of(transfer);
            if (!frame.compareAndSet(observed, transferring)) {
                return Optional.empty();
            }
            packetQueue.clear();
            return accepted;
        }
    }

    /** Install a greater owner session, optionally retagging the pending FIFO. */
    public boolean completeTransfer(Transfer<P> transfer,
                                    OwnerStamp nextOwner,
                                    PacketDisposition packets) {
        Objects.requireNonNull(nextOwner, "nextOwner");
        Objects.requireNonNull(packets, "packets");
        if (!belongsHere(transfer) || !transfer.oldOwner.canBeSucceededBy(nextOwner)) {
            return false;
        }
        synchronized (packetLock) {
            Frame<P> observed = frame.get();
            if (!isClaim(observed, Phase.TRANSFERRING, transfer)) return false;
            Frame<P> ready = new Frame<>(Phase.ISLAND_READY, nextOwner, null,
                    false, 0L, false, 0L, false, 0L);
            if (!frame.compareAndSet(observed, ready)) return false;
            if (packets == PacketDisposition.CARRY) {
                for (PacketEnvelope<P> envelope : transfer.pendingPackets) {
                    packetQueue.addLast(new PacketEnvelope<>(
                            envelope.sequence, nextOwner, envelope.packet));
                }
            }
            return true;
        }
    }

    /** Abort before ownership changes and restore the old FIFO and token ledger. */
    public boolean abortTransfer(Transfer<P> transfer) {
        if (!belongsHere(transfer)) return false;
        synchronized (packetLock) {
            Frame<P> observed = frame.get();
            if (!isClaim(observed, Phase.TRANSFERRING, transfer)) return false;
            if (!frame.compareAndSet(observed,
                    observed.move(Phase.ISLAND_READY, null))) return false;
            packetQueue.addAll(transfer.pendingPackets);
            return true;
        }
    }

    /**
     * Invalidate a quiescent or not-yet-entered session.  An entered body must first
     * complete or be contained; disconnect never races it into a main retry.
     */
    public Optional<Disconnect<P>> disconnect(OwnerStamp expected) {
        synchronized (packetLock) {
            Frame<P> observed = frame.get();
            if (!sameOwner(observed, expected) || !disconnectible(observed.phase)) {
                return Optional.empty();
            }
            ArrayList<PacketEnvelope<P>> pending = new ArrayList<>();
            if (observed.claim instanceof Transfer<?> rawTransfer) {
                @SuppressWarnings("unchecked")
                Transfer<P> transfer = (Transfer<P>) rawTransfer;
                pending.addAll(transfer.pendingPackets);
            }
            pending.addAll(packetQueue);
            Frame<P> disconnected = new Frame<>(Phase.DISCONNECTED,
                    observed.owner, null,
                    observed.hasEarly, observed.lastEarly,
                    observed.hasLate, observed.lastLate,
                    observed.hasTerminal, observed.lastTerminal);
            Disconnect<P> result = new Disconnect<>(observed.owner, pending);
            Optional<Disconnect<P>> accepted = Optional.of(result);
            if (!frame.compareAndSet(observed, disconnected)) return Optional.empty();
            packetQueue.clear();
            return accepted;
        }
    }

    /**
     * Terminalize the listener after ownership has already reached quiescent MAIN.
     * This is the physical-Main disconnect counterpart to {@link #disconnect}: no
     * island owner or packet prefix remains in the core, but the terminal phase must
     * still prevent the connection-lifetime facade from attaching a successor turn.
     */
    public boolean disconnectMain() {
        synchronized (packetLock) {
            Frame<P> observed = frame.get();
            if (observed.phase == Phase.DISCONNECTED) return true;
            if (observed.phase != Phase.MAIN || observed.owner != null
                    || observed.claim != null || !packetQueue.isEmpty()) {
                return false;
            }
            Frame<P> disconnected = new Frame<>(Phase.DISCONNECTED,
                    null, null,
                    observed.hasEarly, observed.lastEarly,
                    observed.hasLate, observed.lastLate,
                    observed.hasTerminal, observed.lastTerminal);
            return frame.compareAndSet(observed, disconnected);
        }
    }

    private List<PacketEnvelope<P>> drainThrough(long cutoff) {
        ArrayList<PacketEnvelope<P>> drained = new ArrayList<>();
        while (!packetQueue.isEmpty()
                && Long.compareUnsigned(packetQueue.peekFirst().sequence, cutoff) <= 0) {
            drained.add(packetQueue.removeFirst());
        }
        return List.copyOf(drained);
    }

    private boolean belongsHere(EarlyPermit<P> permit) {
        return permit != null && permit.session == this;
    }

    private boolean belongsHere(MainFallback<P> fallback) {
        return fallback != null && fallback.session == this;
    }

    private boolean belongsHere(Transfer<P> transfer) {
        return transfer != null && transfer.session == this;
    }

    private boolean belongsHere(MainHandoff<P> handoff) {
        return handoff != null && handoff.session == this;
    }

    private static boolean newerThanTerminal(Frame<?> observed, long token) {
        return !observed.hasTerminal
                || Long.compareUnsigned(token, observed.lastTerminal) > 0;
    }

    private static boolean sameOwner(Frame<?> observed, OwnerStamp expected) {
        return observed.owner != null && observed.owner.matchesExactly(expected);
    }

    private static boolean acceptsPackets(Phase phase) {
        return phase == Phase.ISLAND_READY
                || phase == Phase.EARLY_RESERVED
                || phase == Phase.EARLY_RUNNING
                || phase == Phase.EARLY_DONE
                || phase == Phase.LATE_RUNNING
                || phase == Phase.MAIN_FALLBACK_RESERVED
                || phase == Phase.MAIN_FALLBACK_RUNNING
                || phase == Phase.MAIN_HANDOFF;
    }

    private static boolean disconnectible(Phase phase) {
        return phase == Phase.ISLAND_READY
                || phase == Phase.EARLY_RESERVED
                || phase == Phase.MAIN_FALLBACK_RESERVED
                || phase == Phase.TRANSFERRING;
    }

    private static boolean isIslandEnteredClaim(Frame<?> observed, Object permit) {
        return observed.claim == permit
                && (observed.phase == Phase.EARLY_RUNNING
                || observed.phase == Phase.EARLY_DONE
                || observed.phase == Phase.LATE_RUNNING);
    }

    private static boolean isIslandReplayClaim(Frame<?> observed, Object permit) {
        return observed.claim == permit
                && (observed.phase == Phase.EARLY_RESERVED
                || observed.phase == Phase.EARLY_RUNNING
                || observed.phase == Phase.EARLY_DONE
                || observed.phase == Phase.LATE_RUNNING);
    }

    private static boolean isClaim(Frame<?> observed, Phase phase, Object claim) {
        return observed.phase == phase && observed.claim == claim;
    }

    private static long activeTokenOrZero(Object claim) {
        if (claim instanceof EarlyPermit<?> permit) return permit.turnToken;
        if (claim instanceof MainFallback<?> fallback) return fallback.turnToken();
        return 0L;
    }
}
