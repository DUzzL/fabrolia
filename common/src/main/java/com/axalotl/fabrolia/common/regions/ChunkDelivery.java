package com.axalotl.fabrolia.common.regions;

import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * THE authoritative full-chunk delivery pipeline (2026-07-14 rewrite).
 *
 * <p>Replaces the layered sender patches (bounded claim attempts, main-thread
 * fallback, take-once snapshot store, deferred collect/reinsert, ack-liveness
 * reset, batch exception belts) with ONE explicit per-player state machine:
 *
 * <pre>
 * TRACKED → REQUESTED(leased to owner) → READY(sealed @L) → RESERVED →
 * ADMITTED(receipt ordered) → ENQUEUED(real send returned) → ACKNOWLEDGED
 * (terminal: CANCELLED / REPLACED)
 * </pre>
 *
 * Ownership contract:
 * <ul>
 *   <li>Vanilla/VMP decide DESIRED visibility (markChunkPendingToSend /
 *       dropChunk — VMP has no sender mixins, audited 2026-07-14, so this
 *       boundary covers both). Fabrolia owns everything after.</li>
 *   <li>Mutable chunk data is captured ONLY by its current owner: the owning
 *       island's unit builds the packet at end-of-unit under its own claim
 *       (seal), stamping {@code sealL = logicalTick}. Ownerless (global-
 *       domain) chunks build inline on main — main IS their executor, so
 *       that is the vanilla path, not a race.</li>
 *   <li>The sender/main NEVER acquires an island claim and never spins.</li>
 * </ul>
 *
 * Revision fence (no lost increments, no overtaking): selection first checks
 * the packet's per-chunk write generation, then the final enqueue takes that
 * exact generation's narrow commit reservation. Main never waits for a chunk
 * writer. If Main commits first, one immutable batch task is already in the
 * connection EventLoop FIFO before the next writer can publish its incremental
 * update; if the writer wins, Main rejects the stale snapshot. A receipt keeps
 * that queued task closed until every exact admission commits. Main never
 * awaits the real send body.
 * On failure the
 * lease is re-issued (bounded); after {@link #ESCALATE_AFTER} failures the
 * record escalates to a fenced main build when Main gets the island claim
 * without waiting (tryMainClaim); a busy island is leased again, so the sender
 * never spins on a running unit.
 *
 * Batches are transactional: reserve → offer closed receipt → admit → real
 * EventLoop Start/full/Finished → Main completion intent. Every Throwable
 * selects the global first winner before exhaustive terminal cleanup. Vanilla
 * pacing and success metrics advance only when Main consumes a real successful
 * completion, so an acknowledgement obligation can never leak. Acks apply to an explicit
 * outstanding-batch record (FIFO per connection); surplus acks are logged
 * and ignored. No heuristic reset exists in normal operation.
 */
public final class ChunkDelivery {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-Delivery");

    /** Fence failures before escalating to a fenced main build. */
    static final int ESCALATE_AFTER = 3;
    /** REQUESTED lease age before escalating past a non-draining (idle/retired)
     *  owner mailbox. 1s = ~20 healthy unit completions worth of patience. */
    static final long REQUESTED_TIMEOUT_NANOS = 1_000_000_000L;
    /** Max chunks sealed per island unit (claim-tail bound; fairness via FIFO). */
    static final int SEALS_PER_UNIT = 32;
    /** Snapshot cache byte budget (payload estimate) — evict-at-zero-refs LRU. */
    static final long CACHE_BYTE_BUDGET = 96L * 1024 * 1024;

    // ------------------------------------------------------------------
    // states
    // ------------------------------------------------------------------
    public enum St {
        TRACKED,            // desired; no build lease yet
        REQUESTED,          // leased to an owner mailbox (or main inline)
        READY,              // sealed packet available (shared cache entry)
        RESERVED,           // selected into an in-flight batch transaction
        ADMITTED,           // ordered receipt committed; real send still pending
        ENQUEUED,           // the exact full listener send returned successfully
        ACKNOWLEDGED,       // client acked the containing batch (terminal)
        CANCELLED,          // untracked / disconnected (terminal)
        REPLACED            // superseded by a newer record (terminal)
    }

    // ------------------------------------------------------------------
    // per-(player,chunk) delivery record
    // ------------------------------------------------------------------
    public static final class Rec {
        final long chunkKey;
        final long connGen;
        final long trackingGen;
        final PlayerState owner;
        volatile St st = St.TRACKED;
        volatile Entry entry;          // sealed shared snapshot (READY..ENQUEUED)
        volatile int fenceFails;
        volatile long stateNanos = System.nanoTime();
        /**
         * Sampled before this record's owner-mailbox lease. Only an entry built
         * after it can answer that request; an older cached entry is the one
         * lease() already rejected (stale write witness or unloaded chunk).
         */
        volatile long leaseNanos;
        /**
         * Leaf lifecycle/publication gate. A successful full-packet EventLoop
         * offer and every terminal retirement of this exact record are one
         * transaction. The gate may nest only the chunk fence's zero-time
         * generation try-reservation; it is never held across world work,
         * Connection.send, compatibility callbacks, or network backpressure.
         */
        final Object transportRetireGate = new Object();
        /** Guarded by {@link #transportRetireGate}. */
        boolean clientMayHaveChunk;

        private static final java.lang.invoke.VarHandle ST;
        static {
            try {
                ST = java.lang.invoke.MethodHandles.lookup()
                        .findVarHandle(Rec.class, "st", St.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        Rec(PlayerState ps, long chunkKey, long trackingGen) {
            this.owner = ps;
            this.chunkKey = chunkKey;
            this.connGen = ps.connGen;
            this.trackingGen = trackingGen;
        }

        /**
         * CONCURRENT lifecycle (2026-07-20 redesign): every state edge is a CAS;
         * whichever thread wins a transition owns exactly the cleanup attached to
         * that edge. Reference rules — who holds a strong Entry ref per state:
         * TRACKED/REQUESTED none; READY one (the record's); RESERVED two (record
         * deferred + reservation); ADMITTED/ENQUEUED keep both until the real
         * batch succeeds on EventLoop, then one record ref until ACK; terminal none.
         * Payload-publishing edges (TRACKED/REQUESTED → READY) set {@code
         * entry} BEFORE the CAS and undo on CAS failure, so an observer that
         * reads state READY is guaranteed a non-null entry; only the winner of a
         * ref-releasing edge nulls {@code entry}.
         */
        boolean cas(St from, St next) {
            if (ST.compareAndSet(this, from, next)) {
                stateNanos = System.nanoTime();
                return true;
            }
            return false;
        }

        boolean isTerminal() {
            St s = st;
            return s == St.CANCELLED || s == St.REPLACED || s == St.ACKNOWLEDGED;
        }

        /** ATOMIC READY→RESERVED: the CAS claims the record; the payload is
         *  captured ONCE into an immutable Reservation with its own strong
         *  cache reference. A concurrent retire() can only run AFTER losing
         *  this CAS (it then waits only for the exact record's bounded
         *  publication transaction, marks RESERVED terminal, and the sender's
         *  enqueue validation releases both references). */
        Reservation tryReserve() {
            if (!cas(St.READY, St.RESERVED)) {
                return null;
            }
            Entry e = entry;
            if (e == null) {
                INVALID_PAYLOAD.incrementAndGet();
                owner.nullEntryRecoveries++;
                IllegalStateException failure = new IllegalStateException(
                        "RESERVED chunk-delivery record has no payload: player="
                                + owner.player.getUUID() + " chunk=["
                                + ChunkPos.getX(chunkKey) + "," + ChunkPos.getZ(chunkKey)
                                + "] connGen=" + connGen + " trackingGen=" + trackingGen);
                Throwable winner = publishFatalPreserving(failure);
                cas(St.RESERVED, St.CANCELLED);
                ChunkDelivery.<RuntimeException>rethrow(winner);
                return null;
            }
            e.refs.incrementAndGet(); // reservation's own strong reference
            return new Reservation(this, e);
        }
    }

    /**
     * Exactly-once retirement from ANY thread (untrack, quit, dimension change,
     * replacement): CAS-loop to the terminal state; the winner releases the
     * reference the prior state held. RESERVED is special — the in-flight
     * sender owns the cleanup: its gated enqueue validation sees the terminal
     * state and {@link Reservation#abandonCancelled} releases both refs.
     * Returns whether a full-packet task for this exact lifecycle was already
     * accepted, which is the authoritative drop/Forget decision.
     */
    static boolean retire(Rec r, St terminal) {
        synchronized (r.transportRetireGate) {
            boolean clientMayHaveChunk = r.clientMayHaveChunk;
            for (;;) {
                St s = r.st;
                if (s == St.CANCELLED || s == St.REPLACED
                        || s == St.ACKNOWLEDGED) {
                    return clientMayHaveChunk;
                }
                if (r.cas(s, terminal)) {
                    if (s == St.READY || s == St.ADMITTED || s == St.ENQUEUED) {
                        Entry e = r.entry;
                        r.entry = null;
                        if (e != null) e.refs.decrementAndGet();
                    }
                    return clientMayHaveChunk;
                }
            }
        }
    }

    /** Immutable reservation: the sender operates on THIS object only —
     *  never on the record's mutable entry field. entry is final non-null. */
    public static final class Reservation {
        final Rec rec;
        final Entry entry;
        /** Retained until the real EventLoop batch body succeeds or fails. */
        boolean reservationReferenceHeld = true;

        Reservation(Rec rec, Entry entry) {
            this.rec = rec;
            this.entry = entry;
        }

        /** Release the temporary reservation ref exactly once. Success keeps
         * the separate record ref pinned until ACK; terminal cleanup releases
         * that record ref independently. */
        void releaseReservationReference() {
            if (reservationReferenceHeld) {
                reservationReferenceHeld = false;
                entry.refs.decrementAndGet();
            }
        }

        /** Not sent, record still live: back to READY keeping its payload. */
        void rollback() {
            synchronized (rec.transportRetireGate) {
                releaseReservationReference();
                if (rec.cas(St.RESERVED, St.READY)) {
                    return;
                }
                // A retirement which won while RESERVED deliberately deferred
                // the record ref to this reservation.
                Entry recordEntry = rec.entry;
                rec.entry = null;
                if (recordEntry != null) recordEntry.refs.decrementAndGet();
            }
        }

        /** Record was cancelled/replaced while reserved: skip the send and
         *  release both references (reservation + the record's deferred one). */
        void abandonCancelled() {
            synchronized (rec.transportRetireGate) {
                releaseReservationReference();
                Entry recordEntry = rec.entry;
                rec.entry = null;
                if (recordEntry != null) recordEntry.refs.decrementAndGet();
            }
        }

        /** Fatal batch cleanup: no READY successor and no replay. */
        void terminalizeFailure() {
            synchronized (rec.transportRetireGate) {
                for (;;) {
                    St state = rec.st;
                    if (state == St.CANCELLED || state == St.REPLACED
                            || state == St.ACKNOWLEDGED) {
                        break;
                    }
                    if (rec.cas(state, St.CANCELLED)) {
                        break;
                    }
                }
                Entry recordEntry = rec.entry;
                rec.entry = null;
                if (recordEntry != null) recordEntry.refs.decrementAndGet();
                releaseReservationReference();
            }
        }

        /**
         * Final generation commit after the receipt-gated EventLoop task has
         * been offered but before that task may enter Start/full/Finished. The
         * already-ordered task is the publication edge; the zero-time write
         * reservation proves the selected immutable snapshot was current at
         * that edge. Main never waits for an island writer or the EventLoop.
         */
        EnqueueResult admitAfterBatchOffer() {
            synchronized (rec.transportRetireGate) {
                if (rec.st != St.RESERVED) {
                    return EnqueueResult.CANCELLED;
                }
                FabroliaWriteGen writes = (FabroliaWriteGen) entry.chunk;
                if (!writes.fabrolia$tryReserveFullChunkEnqueue(entry.writeGenAtSeal)) {
                    return EnqueueResult.STALE;
                }
                Throwable failure = null;
                try {
                    if (!rec.cas(St.RESERVED, St.ADMITTED)) {
                        throw new IllegalStateException(
                                "chunk publication gate lost RESERVED after batch offer: "
                                        + "player=" + rec.owner.player.getUUID() + " chunk=["
                                        + ChunkPos.getX(rec.chunkKey) + ","
                                        + ChunkPos.getZ(rec.chunkKey) + "] state=" + rec.st);
                    }
                    // A concurrent retirement now necessarily offers Forget
                    // after the already-queued, still receipt-blocked batch.
                    rec.clientMayHaveChunk = true;
                } catch (Throwable bodyFailure) {
                    failure = publishFatalPreserving(bodyFailure);
                }
                try {
                    writes.fabrolia$releaseFullChunkEnqueue(entry.writeGenAtSeal);
                } catch (Throwable cleanupFailure) {
                    failure = publishFatalPreserving(
                            mergeFailure(failure, cleanupFailure));
                }
                if (failure != null) {
                    ChunkDelivery.<RuntimeException>rethrow(
                            publishFatalPreserving(failure));
                }
                return EnqueueResult.SENT;
            }
        }

        /** Real listener send returned. A concurrent retirement may already
         * have won ADMITTED and queued Forget behind this batch task. */
        void markRealSendSucceeded() {
            synchronized (rec.transportRetireGate) {
                if (rec.cas(St.ADMITTED, St.ENQUEUED)) {
                    return;
                }
                St state = rec.st;
                if (state != St.CANCELLED && state != St.REPLACED
                        && state != St.ACKNOWLEDGED) {
                    IllegalStateException failure = new IllegalStateException(
                            "real full send lost ADMITTED record state: player="
                                    + rec.owner.player.getUUID() + " chunk=["
                                    + ChunkPos.getX(rec.chunkKey) + ","
                                    + ChunkPos.getZ(rec.chunkKey) + "] state=" + state);
                    ChunkDelivery.<RuntimeException>rethrow(
                            publishFatalPreserving(failure));
                }
            }
        }
    }

    enum EnqueueResult { SENT, STALE, CANCELLED }

    // ------------------------------------------------------------------
    // shared sealed snapshot (refcounted, coalesced across players)
    // ------------------------------------------------------------------
    public static final class Entry {
        final LevelChunk chunk;                 // identity = lifecycle witness
        final ClientboundLevelChunkWithLightPacket packet;
        final long sealL;
        /** Witness sampled before copying block/BE/light payload. A light update
         *  may publish concurrently with an owner-held seal; a post-copy sample
         *  would incorrectly bless the older payload with its newer generation.
         *  Admission compares this witness with the live per-chunk generation. */
        final long writeGenAtSeal;
        final PIslandRef ownerRef;
        final AtomicInteger refs = new AtomicInteger();
        final long bytes;
        final long builtNanos = System.nanoTime();

        Entry(LevelChunk chunk, long writeGenBeforeCopy, ClientboundLevelChunkWithLightPacket packet,
              long sealL, IslandRegistry.PIsland island) {
            this.chunk = chunk;
            this.packet = packet;
            this.sealL = sealL;
            this.writeGenAtSeal = writeGenBeforeCopy;
            this.ownerRef = new PIslandRef(island);
            this.bytes = 40_000; // section-payload class estimate for budget math
        }
    }

    record PIslandRef(IslandRegistry.PIsland island) {}

    // ------------------------------------------------------------------
    // per-player state
    // ------------------------------------------------------------------
    public static final class PlayerState {
        /** The listener's current player: a respawn replaces the ServerPlayer
         *  but keeps its connection and therefore this delivery state. */
        volatile ServerPlayer player;
        final long connGen;
        /** Bumped by trackers (any thread under the concurrent model). */
        final AtomicLong trackingGen = new AtomicLong();
        /** CONCURRENT (2026-07-20 redesign): structural mutation from any
         *  thread; iteration/copies are weakly consistent and can never throw.
         *  Record lifecycle safety lives in the per-record CAS transitions. */
        final ConcurrentHashMap<Long, Rec> records = new ConcurrentHashMap<>();
        final ChunkDeliveryCandidates<Rec> candidates = new ChunkDeliveryCandidates<>(
                records.values(), r -> r.chunkKey, Rec::isTerminal);
        /** Batch obligations stay physical-Main-owned: island ingress records
         *  only bounded ACK intent; PlayerChunkSender drains it here from its
         *  Main send slot. */
        final ArrayDeque<Batch> outstanding = new ArrayDeque<>();
        /** At most one offered batch awaits real EventLoop completion/Main commit. */
        volatile BatchPublication activePublication;
        volatile boolean closed;
        volatile ServerLevel level;
        // metrics mutated by trackers (any thread):
        final AtomicLong created = new AtomicLong(), cancelled = new AtomicLong(),
                replaced = new AtomicLong();
        // metrics owned by the main batcher/ack path:
        long enqueued, acked, retriesFence,
                escalations, coalesced, staleAcks, batches;
        long nullEntryRecoveries, enqueueFails, cancelledInBatch, requestedTimeouts;
        volatile long lastFullSendNanos;
        long lastAckNanos, lastLivenessReportNanos;
        long bytesEnqueued;

        PlayerState(ServerPlayer p, long gen) {
            this.player = p;
            this.connGen = gen;
        }
    }

    public static final class Batch {
        private static final int OFFERING = 0;
        private static final int ACCEPTED = 1;
        private static final int CANCELLED = 2;

        final long id;
        final long connGen;
        final long createdNanos = System.nanoTime();
        /** Exact identities successfully enqueued by THIS batch. A chunk key
         *  is reusable after untrack, replacement or dimension change, so a
         *  key lookup at ACK time could retire a newer record. */
        final Rec[] records;
        final int count;
        /** The deque node is visible before success commits, but ACK may consume
         *  it only after the same lifecycle cut accepts this receipt. */
        final AtomicInteger publication = new AtomicInteger(OFFERING);

        Batch(long id, long connGen, Rec[] records, int count) {
            this.id = id;
            this.connGen = connGen;
            this.records = records;
            this.count = count;
        }

        void acceptPublication() {
            if (!publication.compareAndSet(OFFERING, ACCEPTED)) {
                throw new IllegalStateException(
                        "chunk ACK batch accepted from publication state "
                                + publication.get());
            }
        }

        void cancelPublication() {
            for (;;) {
                int observed = publication.get();
                if (observed == CANCELLED) return;
                if (publication.compareAndSet(observed, CANCELLED)) return;
            }
        }

        boolean publicationAccepted() {
            return publication.get() == ACCEPTED;
        }
    }

    /**
     * One receipt-gated EventLoop transaction. Main offers this object once,
     * finalizes exact record/write-generation admission while the queued task
     * is blocked on this monitor, then returns without waiting. The EventLoop
     * performs the real Start/full/Finished bodies. Only a later Main drain may
     * convert SUCCEEDED into pacing, metrics and an ACK skeleton.
     */
    private static final class BatchPublication
            implements FabroliaFullChunkPublisher.FullChunkBatchPublication {
        private static final int PREPARED = 0;
        private static final int OFFERING = 1;
        private static final int ADMITTED = 2;
        private static final int RUNNING = 3;
        private static final int SUCCEEDED = 4;
        private static final int EMPTY = 5;
        private static final int APPLIED = 6;
        private static final int FAILED = 7;
        private static final int DISCARDED = 8;

        final PlayerState owner;
        final Reservation[] reservations;
        final Reservation[] admitted;
        final Rec[] admittedRecords;
        final boolean injectStartFailure;
        final boolean injectFullFailure;
        int admittedCount;
        int state = PREPARED;
        Throwable failure;

        BatchPublication(PlayerState owner, Reservation[] reservations,
                         boolean injectStartFailure, boolean injectFullFailure) {
            this.owner = owner;
            this.reservations = reservations;
            this.admitted = new Reservation[reservations.length];
            this.admittedRecords = new Rec[reservations.length];
            this.injectStartFailure = injectStartFailure;
            this.injectFullFailure = injectFullFailure;
        }

        void publish(ServerPlayer player) {
            finishPublicationOnMain(admitPublication(player));
        }

        private Throwable admitPublication(ServerPlayer player) {
            Throwable publicationFailure = null;
            synchronized (this) {
                if (state != PREPARED) {
                    publicationFailure = publishFatalPreserving(
                            new IllegalStateException(
                                    "full-chunk batch publication entered twice"));
                } else {
                    try {
                        RegionScheduler.commitSuccessIfHealthy(
                                () -> state = OFFERING);
                        RegionScheduler.runSuccessBodyIfHealthy(() ->
                                ((FabroliaFullChunkPublisher) player.connection)
                                        .fabrolia$enqueueFullChunkBatch(this));
                    } catch (Throwable offerFailure) {
                        // The queued Runnable may already exist. Publish W
                        // before cancelling its receipt and touching records.
                        publicationFailure = publishFatalPreserving(offerFailure);
                    }
                }

                if (publicationFailure == null) {
                    for (int i = 0; i < reservations.length; i++) {
                        Reservation reservation = reservations[i];
                        EnqueueResult[] result = new EnqueueResult[1];
                        try {
                            // Admission and its result publication are distinct
                            // bodies. If W wins while the reservation is being
                            // admitted, its terminal permit prevents any SENT /
                            // retry accounting and the next reservation entry.
                            RegionScheduler.runSuccessBodyIfHealthy(() ->
                                    result[0] = reservation.admitAfterBatchOffer());
                        } catch (Throwable admissionFailure) {
                            publicationFailure = publishFatalPreserving(admissionFailure);
                            break;
                        }
                        try {
                            if (result[0] == EnqueueResult.SENT) {
                                RegionScheduler.commitSuccessIfHealthy(() -> {
                                    admitted[admittedCount] = reservation;
                                    admittedRecords[admittedCount] = reservation.rec;
                                    admittedCount++;
                                });
                            } else if (result[0] == EnqueueResult.STALE) {
                                RegionScheduler.runSuccessBodyIfHealthy(() -> {
                                    reservation.rollback();
                                    if (demoteReady(reservation.rec)) {
                                        reservation.rec.fenceFails++;
                                        owner.retriesFence++;
                                        FENCE_RETRIES.incrementAndGet();
                                    }
                                });
                            } else {
                                RegionScheduler.runSuccessBodyIfHealthy(() -> {
                                    reservation.abandonCancelled();
                                    owner.cancelledInBatch++;
                                });
                            }
                        } catch (Throwable resultFailure) {
                            publicationFailure = publishFatalPreserving(resultFailure);
                            break;
                        }
                    }
                }

                if (publicationFailure == null
                        && (injectStartFailure || injectFullFailure)
                        && admittedCount == 0) {
                    publicationFailure = publishFatalPreserving(
                            new IllegalStateException(
                                    "injected batch failure without an admitted snapshot"));
                }
                if (publicationFailure == null) {
                    try {
                        RegionScheduler.commitSuccessIfHealthy(() -> {
                            state = admittedCount == 0 ? EMPTY : ADMITTED;
                            // EMPTY has no packet or ACK obligation. Detach its
                            // exact sender slot before EventLoop consumption can
                            // replace EMPTY with APPLIED.
                            if (state == EMPTY && owner.activePublication == this) {
                                owner.activePublication = null;
                            }
                        });
                    } catch (Throwable successFailure) {
                        publicationFailure = publishFatalPreserving(successFailure);
                    }
                }
                if (publicationFailure != null) {
                    publicationFailure = terminalizeReservations(
                            reservations, publicationFailure);
                    failure = publishFatalPreserving(publicationFailure);
                    state = FAILED;
                } else {
                    failure = null;
                }
            }
            return publicationFailure;
        }

        private void finishPublicationOnMain(Throwable publicationFailure) {
            if (publicationFailure != null) {
                ChunkDelivery.<RuntimeException>rethrow(
                        publishFatalPreserving(publicationFailure));
            }
        }

        @Override
        public void runPublished(FabroliaFullChunkPublisher.PacketSender sender) {
            Throwable terminalFailure = null;
            boolean invariantCleanup = false;
            boolean emptyPublication = false;
            synchronized (this) {
                if (state == ADMITTED) {
                    if (owner.closed) {
                        state = DISCARDED;
                    } else {
                        try {
                            RegionScheduler.commitSuccessIfHealthy(
                                    () -> state = RUNNING);
                        } catch (Throwable startFailure) {
                            terminalFailure = publishFatalPreserving(startFailure);
                            failure = terminalFailure;
                            state = FAILED;
                            invariantCleanup = true;
                        }
                    }
                } else if (state == FAILED) {
                    terminalFailure = failure;
                } else if (state == EMPTY) {
                    emptyPublication = true;
                } else if (state != DISCARDED) {
                    terminalFailure = publishFatalPreserving(
                            new IllegalStateException(
                                    "full-chunk receipt entered illegal state " + state));
                    failure = terminalFailure;
                    state = FAILED;
                    invariantCleanup = true;
                }
            }
            if (terminalFailure != null) {
                if (invariantCleanup) {
                    terminalFailure = terminalizeReservations(
                            reservations, terminalFailure);
                    failure = publishFatalPreserving(terminalFailure);
                }
                ChunkDelivery.<RuntimeException>rethrow(terminalFailure);
            }
            if (emptyPublication) {
                try {
                    synchronized (this) {
                        RegionScheduler.commitSuccessIfHealthy(() -> state = APPLIED);
                    }
                    return;
                } catch (Throwable successFailure) {
                    terminalFailure = publishFatalPreserving(successFailure);
                }
                terminalFailure = terminalizeReservations(reservations, terminalFailure);
                synchronized (this) {
                    failure = publishFatalPreserving(terminalFailure);
                    state = FAILED;
                }
                ChunkDelivery.<RuntimeException>rethrow(failure);
            }
            if (state == DISCARDED) {
                terminalizeReservationsWithoutFailure(reservations);
                return;
            }

            try {
                RegionScheduler.runSuccessBodyIfHealthy(() -> {
                    if (injectStartFailure) {
                        throw new IllegalStateException("injected failstart");
                    }
                    sender.send(ClientboundChunkBatchStartPacket.INSTANCE);
                });
                for (int i = 0; i < admittedCount; i++) {
                    int entry = i;
                    RegionScheduler.runSuccessBodyIfHealthy(() -> {
                        if (injectFullFailure
                                && entry == Math.min(1, admittedCount - 1)) {
                            throw new IllegalStateException("injected failenqueue");
                        }
                        sender.send(admitted[entry].entry.packet);
                        admitted[entry].markRealSendSucceeded();
                    });
                }
                RegionScheduler.runSuccessBodyIfHealthy(() ->
                        sender.send(new ClientboundChunkBatchFinishedPacket(admittedCount)));
            } catch (Throwable bodyFailure) {
                // No cleanup, successor or Finished may run before W exists.
                terminalFailure = publishFatalPreserving(bodyFailure);
            }

            if (terminalFailure == null) {
                try {
                    // Keep the established lock order (publication -> lifecycle).
                    // Either this exact success state wins the global cut, or an
                    // already-selected peer W prevents the raw SUCCEEDED write.
                    synchronized (this) {
                        RegionScheduler.commitSuccessIfHealthy(() -> {
                            if (owner.closed) {
                                for (int i = 0; i < admittedCount; i++) {
                                    admitted[i].releaseReservationReference();
                                }
                                state = DISCARDED;
                            } else {
                                state = SUCCEEDED;
                            }
                        });
                    }
                } catch (Throwable successFailure) {
                    terminalFailure = publishFatalPreserving(successFailure);
                }
            }

            if (terminalFailure != null) {
                terminalFailure = terminalizeReservations(reservations, terminalFailure);
                synchronized (this) {
                    failure = publishFatalPreserving(terminalFailure);
                    state = FAILED;
                }
                ChunkDelivery.<RuntimeException>rethrow(failure);
            }
        }

        int applySuccessOnMain() {
            synchronized (this) {
                if (state == FAILED) {
                    ChunkDelivery.<RuntimeException>rethrow(
                            publishFatalPreserving(failure));
                }
                if (state != SUCCEEDED) {
                    return 0;
                }

                Throwable applyFailure = null;
                Batch completedBatch = null;
                Batch[] completedSlot = new Batch[1];
                long[] completedNanos = new long[1];
                long[] completedBytes = new long[1];
                try {
                    RegionScheduler.runSuccessBodyIfHealthy(() -> {
                        Batch prepared = new Batch(BATCH_IDS.incrementAndGet(), owner.connGen,
                                admittedRecords, admittedCount);
                        long bytes = 0L;
                        for (int i = 0; i < admittedCount; i++) {
                            bytes += admitted[i].entry.bytes;
                        }
                        // Fallible deque growth happens while the Batch receipt
                        // is OFFERING. ACK cannot consume it before the commit.
                        owner.outstanding.addLast(prepared);
                        completedSlot[0] = prepared;
                        completedNanos[0] = System.nanoTime();
                        completedBytes[0] = bytes;
                    });
                    completedBatch = completedSlot[0];
                    final Batch batchCommit = completedBatch;
                    final long nanosCommit = completedNanos[0];
                    final long bytesCommit = completedBytes[0];
                    RegionScheduler.commitSuccessIfHealthy(() -> {
                        batchCommit.acceptPublication();
                        for (int i = 0; i < admittedCount; i++) {
                            Reservation reservation = admitted[i];
                            reservation.releaseReservationReference();
                        }
                        owner.enqueued += admittedCount;
                        owner.bytesEnqueued += bytesCommit;
                        owner.lastFullSendNanos = nanosCommit;
                        owner.batches++;
                        state = APPLIED;
                        owner.activePublication = null;
                    });
                } catch (Throwable bodyFailure) {
                    applyFailure = publishFatalPreserving(bodyFailure);
                }
                if (applyFailure != null) {
                    if (completedBatch != null) {
                        try {
                            completedBatch.cancelPublication();
                        } catch (Throwable cleanupFailure) {
                            applyFailure = publishFatalPreserving(
                                    mergeFailure(applyFailure, cleanupFailure));
                        }
                        try {
                            owner.outstanding.removeLastOccurrence(completedBatch);
                        } catch (Throwable cleanupFailure) {
                            applyFailure = publishFatalPreserving(
                                    mergeFailure(applyFailure, cleanupFailure));
                        }
                    }
                    applyFailure = terminalizeReservations(
                            reservations, applyFailure);
                    failure = publishFatalPreserving(applyFailure);
                    state = FAILED;
                    ChunkDelivery.<RuntimeException>rethrow(failure);
                }
                return admittedCount;
            }
        }

        void discard() {
            synchronized (this) {
                if (state == ADMITTED || state == SUCCEEDED) {
                    terminalizeReservationsWithoutFailure(reservations);
                    state = DISCARDED;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // global state
    // ------------------------------------------------------------------
    private static final ConcurrentHashMap<java.util.UUID, PlayerState> PLAYERS =
            new ConcurrentHashMap<>();
    private static final AtomicLong CONN_GENS = new AtomicLong();
    private static final AtomicLong BATCH_IDS = new AtomicLong();
    /** Shared sealed snapshots: chunkKey → entry (per level). Coalesced:
     *  every interested player's record references the same entry. */
    private static final ConcurrentHashMap<ServerLevel, ConcurrentHashMap<Long, Entry>> CACHE =
            new ConcurrentHashMap<>();
    private static final AtomicLong CACHE_BYTES = new AtomicLong();
    // aggregate metrics
    public static final AtomicLong SEALS = new AtomicLong();
    public static final AtomicLong SEAL_NANOS = new AtomicLong();
    public static final AtomicLong INLINE_BUILDS = new AtomicLong();
    public static final AtomicLong FENCE_RETRIES = new AtomicLong();
    public static final AtomicLong ESCALATED = new AtomicLong();
    public static final AtomicLong REUSED = new AtomicLong();
    public static final AtomicLong REROUTED = new AtomicLong();
    public static final AtomicLong INVALID_PAYLOAD = new AtomicLong();
    public static final AtomicLong REQUESTED_TIMEOUTS = new AtomicLong();
    /** rate-limited log gate: key → last emit nanos */
    private static long livenessEmaNanos;
    // trace
    private static volatile long traceKey = Long.MIN_VALUE;
    private static final ConcurrentLinkedQueue<String> TRACE = new ConcurrentLinkedQueue<>();
    // fault injection (test-only, /fabrolia regions chunksender inject)
    public static volatile String faults = "";

    private ChunkDelivery() {}

    // ------------------------------------------------------------------
    // Concurrency model (2026-07-20 v2 — concurrent redesign): delivery state
    // is mutated from ANY thread. PlayerState.records is a ConcurrentHashMap
    // (weakly-consistent iteration — copying can never throw); every record
    // state edge is a CAS with edge-owned cleanup (see Rec.cas/retire); the
    // shared snapshot cache was already concurrent+refcounted. The batcher,
    // batch obligations and acks remain server-thread activities (vanilla
    // drives them there), but nothing REQUIRES main for tracking mutations —
    // a pearl-teleport view recenter running on a region worker is legal
    // against this layer. (The teleport itself still crosses to main because
    // vanilla ChunkMap/VMP watching structures are main-owned — that boundary
    // belongs to player-session ownership, not to this class.)
    // ------------------------------------------------------------------

    static boolean fault(String f) {
        return !faults.isEmpty() && faults.contains(f);
    }

    private static void trace(String msg) {
        TRACE.add(System.nanoTime() / 1_000_000 + "ms " + msg);
        while (TRACE.size() > 256) TRACE.poll();
    }

    // ------------------------------------------------------------------
    // Stage 1: tracking decisions (main thread, from the sender mixin)
    // ------------------------------------------------------------------
    public static PlayerState join(ServerPlayer p) {
        PlayerState ps = new PlayerState(p, CONN_GENS.incrementAndGet());
        PLAYERS.put(p.getUUID(), ps);
        return ps;
    }

    public static void quit(ServerPlayer p) {
        PlayerState ps = PLAYERS.remove(p.getUUID());
        if (ps == null) return;
        ps.closed = true;
        BatchPublication publication = ps.activePublication;
        if (publication != null) {
            publication.discard();
        }
        // Exactly-once retirement per record from ANY thread: the state CAS
        // arbitrates against concurrent reservation/enqueue; refs release on
        // the winning edge only. A currently running EventLoop publication
        // observes closed at its terminal edge and releases its retained
        // reservation references without publishing a success intent.
        for (Rec r : ps.records.values()) {
            retire(r, St.CANCELLED);
        }
        ps.records.clear();
        ps.candidates.changed();
        ps.outstanding.clear(); // connection gone: obligations die with it
    }

    private static PlayerState ps(ServerPlayer p) {
        PlayerState ps = PLAYERS.get(p.getUUID());
        if (ps == null) return join(p);
        // Respawn: release the dead predecessor (diagnostics resolve by identity).
        if (ps.player != p) ps.player = p;
        return ps;
    }

    /** markChunkPendingToSend: the chunk is desired and server-ready.
     *  Same-level record mutation is callable from any thread: the per-key
     *  CHM transaction is linearizable, and replacement retires the displaced
     *  record exactly once. A level transition is a connection-wide epoch cut
     *  and therefore requires physical Main before retiring the old map; its
     *  outstanding ACK skeletons remain in Main-owned FIFO order. */
    public static void track(ServerPlayer p, LevelChunk chunk) {
        PlayerState ps = ps(p);
        ServerLevel lvl = (ServerLevel) chunk.getLevel();
        if (ps.level != null && ps.level != lvl) {
            if (!RegionalPlayerEntityTicksV2.isPhysicalServerThread(lvl.getServer())) {
                throw new IllegalStateException(
                        "chunk-delivery level transition entered off physical Main");
            }
            // dimension change: everything from the old level is obsolete —
            // retire by generation, not by thread timing: the trackingGen
            // bump invalidates in-flight work even before the sweep lands.
            ps.trackingGen.incrementAndGet();
            for (Rec r0 : ps.records.values()) {
                retire(r0, St.CANCELLED);
            }
            ps.records.clear();
            ps.candidates.changed();
            // RETAIN old batch skeletons in this connection's FIFO. Vanilla's
            // PlayerChunkSender survives respawn with the matching unacked
            // count, and a late old-world ACK must consume its old skeleton —
            // never the first new-world batch. Each skeleton pins exact retired
            // Rec identities, so reused chunk keys cannot retire new records.
            // fresh delivery epoch: the old enqueue timestamp belongs to the
            // previous view and must not age the new one (liveness heuristics)
            ps.lastFullSendNanos = System.nanoTime();
        }
        ps.level = lvl;
        long key = chunk.getPos().pack();
        long trackingGen = ps.trackingGen.get();
        Rec[] installed = new Rec[1];
        boolean[] replacedLive = new boolean[1];
        ps.records.compute(key, (ignored, old) -> {
            Rec next = new Rec(ps, key, trackingGen);
            if (old != null) {
                // The CHM key transaction prevents drop from observing the
                // replacement before publication exposure has transferred.
                // retire() serializes against an old full-task offer on that
                // exact Rec; a later drop of `next` therefore still sends the
                // one Forget required by the older lifecycle.
                replacedLive[0] = !old.isTerminal();
                next.clientMayHaveChunk = retire(old, St.REPLACED);
            }
            installed[0] = next;
            return next;
        });
        ps.candidates.changed();
        Rec r = installed[0];
        if (replacedLive[0]) {
            ps.replaced.incrementAndGet();
        }
        ps.created.incrementAndGet();
        if (traceKey == key) trace("(" + p.getName().getString() + ") TRACKED gen=" + r.trackingGen);
    }

    /**
     * dropChunk replacement: no longer desired. The CHM key transaction spans
     * exact-record retirement and, when a full task already won, the bounded
     * Forget task offer. A concurrent same-key re-track cannot publish its new
     * full task in the remove/null window: it enters this bin afterwards, so
     * the one EventLoop order is old-full → Forget → new-full.
     */
    public static boolean untrack(ServerPlayer p, ChunkPos pos) {
        return untrack(p, pos.pack(), pos);
    }

    /** Internal cancellation seam used before a packet can have been offered. */
    private static boolean untrack(ServerPlayer p, long chunkKey) {
        return untrack(p, chunkKey, null);
    }

    private static boolean untrack(ServerPlayer p, long chunkKey, ChunkPos forgetPos) {
        PlayerState ps = PLAYERS.get(p.getUUID());
        if (ps == null) return false;
        ClientboundForgetLevelChunkPacket forget = forgetPos != null && p.isAlive()
                ? new ClientboundForgetLevelChunkPacket(forgetPos) : null;
        boolean[] removed = new boolean[1];
        boolean[] clientMayHaveChunk = new boolean[1];
        Throwable[] offerFailure = new Throwable[1];
        ps.records.compute(chunkKey, (ignored, r) -> {
            if (r == null) {
                return null;
            }
            removed[0] = true;
            clientMayHaveChunk[0] = retire(r, St.CANCELLED);
            if (clientMayHaveChunk[0] && forget != null) {
                try {
                    ((FabroliaChunkForgetPublisher) p.connection)
                            .fabrolia$enqueueChunkForget(forget);
                } catch (Throwable failure) {
                    // The receipt publisher already cancelled any accepted
                    // Runnable. Select W before the CHM retirement cleanup,
                    // then rethrow it after the key transaction is released.
                    offerFailure[0] = publishFatalPreserving(failure);
                }
            }
            return null;
        });
        if (removed[0]) ps.candidates.changed();
        if (offerFailure[0] != null) {
            ChunkDelivery.<RuntimeException>rethrow(
                    publishFatalPreserving(offerFailure[0]));
        }
        if (removed[0]) {
            ps.cancelled.incrementAndGet();
        }
        return clientMayHaveChunk[0];
    }

    /** Movement burst boundary: one generation per tracking update batch. */
    public static void bumpTrackingGen(ServerPlayer p) {
        PlayerState ps = PLAYERS.get(p.getUUID());
        if (ps != null) ps.trackingGen.incrementAndGet();
    }

    /** Vanilla isPending gate: excluded from increment broadcasts until the
     *  receipt-gated full batch is ordered ahead of them (the enqueue flip +
     *  generation fence make that hand-off lossless). */
    public static boolean isPending(ServerPlayer p, long chunkKey) {
        PlayerState ps = PLAYERS.get(p.getUUID());
        if (ps == null) return false;
        Rec r = ps.records.get(chunkKey);
        return r != null && r.st.ordinal() < St.ADMITTED.ordinal();
    }

    /** Batcher-side READY→TRACKED demotion (stale witness / fence fail):
     *  the CAS win releases the READY reference exactly once; a concurrent
     *  retirement simply wins instead and this returns false. */
    private static boolean demoteReady(Rec r) {
        Entry e = r.entry;
        if (!r.cas(St.READY, St.TRACKED)) return false;
        r.entry = null;
        if (e != null) e.refs.decrementAndGet();
        return true;
    }

    // ------------------------------------------------------------------
    // Stage 2: owner capture. Main leases; the island seals at unit end.
    // ------------------------------------------------------------------

    /** Called from the batcher (main): ensure a build is underway. */
    private static void lease(ServerLevel level, RegionizedServer rs, Rec r) {
        long key = r.chunkKey;
        Entry cached = cache(level).get(key);
        LevelChunk live = level.getChunkSource().getChunkNow(
                net.minecraft.world.level.ChunkPos.getX(key), net.minecraft.world.level.ChunkPos.getZ(key));
        if (live == null) {
            return; // not server-ready anymore; tracking will re-fire
        }
        if (cached != null && cached.chunk == live) {
            if (((FabroliaWriteGen) live).fabrolia$writeGen() == cached.writeGenAtSeal) {
                // fresh shared seal — reuse, no new build
                cached.refs.incrementAndGet();
                r.entry = cached;
                if (!r.cas(St.TRACKED, St.READY)) {
                    r.entry = null;
                    cached.refs.decrementAndGet(); // retired concurrently
                    return;
                }
                r.owner.coalesced++;
                REUSED.incrementAndGet();
                return;
            }
        }
        IslandRegistry.PIsland island = rs == null ? null : rs.islandOwnerOfChunk(key);
        if (island == null || !rs.asyncActive) {
            // Ownerless / sync: main IS the executor of the global domain —
            // building here is the vanilla path (zero fence gap by
            // construction, exactly like sendNextChunks always did).
            if (fault("failinline")) return;
            long t0 = System.nanoTime();
            Entry e = new Entry(live, ((FabroliaWriteGen) live).fabrolia$writeGen(),
                    new ClientboundLevelChunkWithLightPacket(live,
                            level.getLightEngine(), null, null),
                    island == null ? 0 : island.logicalTick, island);
            INLINE_BUILDS.incrementAndGet();
            SEAL_NANOS.addAndGet(System.nanoTime() - t0);
            publish(level, key, e);
            e.refs.incrementAndGet();
            r.entry = e;
            if (!r.cas(St.TRACKED, St.READY)) {
                r.entry = null;
                e.refs.decrementAndGet();
            }
            return;
        }
        boolean heldByMain = r.fenceFails >= ESCALATE_AFTER && IslandCommands.mainHolds(island);
        if (r.fenceFails >= ESCALATE_AFTER
                && (heldByMain || IslandCommands.tryMainClaim(island, "chunk-send escalation"))) {
            // Escalation: Main builds the packet itself, but only when it gets the
            // island without waiting. mainClaim spun until the running unit ended
            // (37-100 ms in production) on every chunk a hopper or furnace dirties
            // between seal and send (audit A-11). A busy island keeps the normal
            // lease below and is tried again on the next pass; the sender never spins.
            ESCALATED.incrementAndGet();
            r.owner.escalations++;
            boolean took = !heldByMain;
            Throwable failure = null;
            try {
                Entry e = new Entry(live, ((FabroliaWriteGen) live).fabrolia$writeGen(),
                        new ClientboundLevelChunkWithLightPacket(live,
                                level.getLightEngine(), null, null),
                        island.logicalTick, island);
                publish(level, key, e);
                e.refs.incrementAndGet();
                r.entry = e;
                r.fenceFails = 0;
                if (!r.cas(St.TRACKED, St.READY)) {
                    r.entry = null;
                    e.refs.decrementAndGet();
                }
            } catch (Throwable bodyFailure) {
                failure = RegionScheduler.publishFatalAndSelectWinner(bodyFailure);
            }
            if (took) {
                try {
                    IslandCommands.mainRelease(island);
                } catch (Throwable cleanupFailure) {
                    failure = RegionScheduler.publishFatalAndSelectWinner(
                            mergeFailure(failure, cleanupFailure));
                }
            }
            if (failure != null) {
                ChunkDelivery.<RuntimeException>rethrow(failure);
            }
            return;
        }
        // normal path: lease to the owner's mailbox (coalesced by key). The
        // stamp precedes the mailbox add: a coalesced pending key is removed
        // from the set before its owner builds the entry, so that seal is
        // always younger than the stamp.
        r.leaseNanos = System.nanoTime();
        if (island.pendingSnapshotKeys.add(key)) {
            island.pendingChunkSnapshots.add(key);
        }
        r.cas(St.TRACKED, St.REQUESTED); // no-op if retired concurrently
    }

    /** Island unit end, under the unit claim: seal up to SEALS_PER_UNIT
     *  pending leases. FIFO queue = age promotion; per-player fairness comes
     *  from coalescing (one seal serves every waiting player). */
    public static void sealPending(RegionizedServer rs, IslandRegistry.PIsland island) {
        if (island.pendingChunkSnapshots.isEmpty()) return;
        ServerLevel level = rs.level;
        int budget = SEALS_PER_UNIT;
        ArrayList<Long> notReady = new ArrayList<>();
        int[] outcome = new int[1]; // 0=empty, 1=consumed, 2=sealed
        while (budget > 0) {
            outcome[0] = 0;
            // Detach and execute one exact snapshot/publication body in the
            // same admission. A nested post-poll admission could be refused by
            // peer W after the key had left the FIFO while its coalescing bit
            // remained set, permanently orphaning that accepted request.
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                Long key = island.pendingChunkSnapshots.poll();
                if (key == null) return;
                long k = key;
                outcome[0] = 1;
                island.pendingSnapshotKeys.remove(k);
                // fire-time validation: still owned by THIS island?
                if (rs.islandOwnerOfChunk(k) != island) {
                    REROUTED.incrementAndGet();
                    requeueToCurrentOwner(rs, k); // owner moved: re-lease, never lost
                    return;
                }
                LevelChunk chunk = level.getChunkSource().getChunkNow(
                        net.minecraft.world.level.ChunkPos.getX(k),
                        net.minecraft.world.level.ChunkPos.getZ(k));
                if (chunk == null) {
                    // not-ready wait state: readiness returns via tracking/batcher,
                    // NOT by per-tick recollection through the sender
                    return;
                }
                if (fault("delayseal") && (k & 1) == 0) {
                    notReady.add(k);
                    return;
                }
                long t0 = System.nanoTime();
                Entry e = new Entry(chunk, ((FabroliaWriteGen) chunk).fabrolia$writeGen(),
                        new ClientboundLevelChunkWithLightPacket(chunk,
                                level.getLightEngine(), null, null),
                        island.logicalTick + 1, island); // unit runs AT L+1 (unitGameTime)
                publish(level, k, e);
                SEALS.incrementAndGet();
                SEAL_NANOS.addAndGet(System.nanoTime() - t0);
                outcome[0] = 2;
            });
            if (outcome[0] == 0) break;
            if (outcome[0] == 2) budget--;
        }
        for (Long k : notReady) {
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                if (island.pendingSnapshotKeys.add(k)) island.pendingChunkSnapshots.add(k);
            });
        }
    }

    /** Publication commits at most once per (chunk lifecycle, revision): a
     *  newer entry simply replaces in the map; records validate identity +
     *  fence at reservation, so a late publish from an obsolete owner can
     *  never satisfy a newer request — it is just an unreferenced entry. */
    private static void publish(ServerLevel level, long key, Entry e) {
        Entry prev = cache(level).put(key, e);
        if (prev != null) CACHE_BYTES.addAndGet(-prev.bytes);
        CACHE_BYTES.addAndGet(e.bytes);
        long budget = fault("tinybudget") ? 2L * 1024 * 1024 : CACHE_BYTE_BUDGET;
        if (CACHE_BYTES.get() > budget) {
            evict(level);
        }
        if (traceKey == key) trace("SEALED L=" + e.sealL + " island="
                + (e.ownerRef.island() == null ? "-" : e.ownerRef.island().id));
    }

    private static void evict(ServerLevel level) {
        var map = cache(level);
        long now = System.nanoTime();
        for (var en : map.entrySet()) {
            Entry e = en.getValue();
            // evict only unreferenced entries — eviction can NEVER cancel a
            // delivery; a record holding a ref keeps its entry alive. The
            // conditional remove never drops a newer seal published meanwhile
            // (publish already uncounted the entry it replaced).
            if (e.refs.get() == 0 && now - e.builtNanos > SNAPSHOT_MIN_AGE_NANOS
                    && map.remove(en.getKey(), e)) {
                CACHE_BYTES.addAndGet(-e.bytes);
            }
            if (CACHE_BYTES.get() <= (fault("tinybudget") ? 1L * 1024 * 1024 : CACHE_BYTE_BUDGET / 2)) break;
        }
    }

    /** Unreferenced entries younger than this may still answer a REQUESTED
     *  record (requests re-lease after {@link #REQUESTED_TIMEOUT_NANOS}). */
    private static final long SNAPSHOT_MIN_AGE_NANOS = 2_000_000_000L;

    /**
     * Main, ~1/s: drop unreferenced shared snapshots that no request can ever
     * use again — their chunk was unloaded or replaced (lease/adopt require the
     * identical LevelChunk) or written since the seal (the write generation
     * only grows). Without this, budget-bound eviction kept up to ~2,400 dead
     * packets resident, each pinning its unloaded LevelChunk.
     */
    public static void pruneDeadSnapshots() {
        long now = System.nanoTime();
        for (var levelCache : CACHE.entrySet()) {
            var chunks = levelCache.getKey().getChunkSource();
            var map = levelCache.getValue();
            for (var en : map.entrySet()) {
                Entry e = en.getValue();
                if (e.refs.get() != 0 || now - e.builtNanos <= SNAPSHOT_MIN_AGE_NANOS) continue;
                long key = en.getKey();
                LevelChunk live = chunks.getChunkNow(
                        ChunkPos.getX(key), ChunkPos.getZ(key));
                if ((live != e.chunk
                        || ((FabroliaWriteGen) live).fabrolia$writeGen() != e.writeGenAtSeal)
                        && map.remove(key, e)) {
                    CACHE_BYTES.addAndGet(-e.bytes);
                }
            }
        }
    }

    /** Topology rebuild carry-state: re-lease every stranded mailbox key to
     *  the chunk's CURRENT owner (or leave for main-inline). Never lost,
     *  never duplicated (pendingSnapshotKeys dedups per island). */
    public static void requeueToCurrentOwner(RegionizedServer rs, long chunkKey) {
        IslandRegistry.PIsland owner = rs.islandOwnerOfChunk(chunkKey);
        if (owner != null && !owner.retired) {
            if (owner.pendingSnapshotKeys.add(chunkKey)) {
                owner.pendingChunkSnapshots.add(chunkKey);
            }
        }
        // ownerless: the batcher's next pass takes the main-inline path
    }

    private static ConcurrentHashMap<Long, Entry> cache(ServerLevel level) {
        return CACHE.computeIfAbsent(level, l -> new ConcurrentHashMap<>());
    }

    public static void dropLevelCache(ServerLevel level) {
        var m = CACHE.remove(level);
        if (m != null) for (Entry e : m.values()) CACHE_BYTES.addAndGet(-e.bytes);
    }

    // ------------------------------------------------------------------
    // Stage 4+5: per-player transactional batching + acks (main thread)
    // ------------------------------------------------------------------

    /** Replaces vanilla sendNextChunks entirely. Returns the number of full
     *  chunk packets synchronously completed. Publication is asynchronous, so
     *  this method returns zero after offering a batch; the next Main sender
     *  pass obtains the real completed count from
     *  {@link #applyCompletedChunkBatch}. */
    public static int sendNextChunks(ServerPlayer p, ServerLevel level, int maxToSend) {
        RegionScheduler.throwWorkerFailureIfAny();
        PlayerState ps = PLAYERS.get(p.getUUID());
        if (ps == null || ps.closed || ps.activePublication != null
                || ps.records.isEmpty() || maxToSend <= 0) return 0;
        RegionizedServer[] runtime = new RegionizedServer[1];
        @SuppressWarnings("unchecked")
        ArrayList<Rec>[] candidateSlot = new ArrayList[1];
        // Preparation is one admitted body; every selected candidate below is
        // then admitted independently.
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            runtime[0] = RegionScheduler.forLevel(level);
            long playerPos = p.chunkPosition().pack();
            candidateSlot[0] = ps.candidates.prepare(playerPos);
        });
        RegionizedServer rs = runtime[0];
        ArrayList<Rec> candidates = candidateSlot[0];
        if (candidates.isEmpty()) return 0;
        // 1) drive TRACKED → lease, and collect fence-valid READY records
        ArrayList<Reservation> reservations = new ArrayList<>(Math.min(maxToSend, 64));
        try {
            for (Rec r : candidates) {
                if (reservations.size() >= maxToSend) break;
                // Each candidate may enter lease/snapshot/mod code.  A single
                // method-head check cannot cover this loop: every candidate
                // owns a generation permit whose terminal edge stops the next.
                RegionScheduler.runSuccessBodyIfHealthy(
                        () -> selectCandidate(level, rs, ps, r, reservations));
            }
        } catch (Throwable selectionFailure) {
            Throwable failure = publishFatalPreserving(selectionFailure);
            failure = terminalizeReservations(reservations, failure);
            ChunkDelivery.<RuntimeException>rethrow(
                    publishFatalPreserving(failure));
            return 0;
        }
        if (reservations.isEmpty()) return 0;
        if (RegionScheduler.callSuccessBooleanIfHealthy(
                () -> fault("cancelmidbatch"))) {
            // deterministic test hook: re-entrant cancellation between
            // selection and enqueue (the production drain re-entrancy)
            try {
                RegionScheduler.runSuccessBodyIfHealthy(() ->
                        untrack(p, reservations.get(0).rec.chunkKey));
            } catch (Throwable cancellationFailure) {
                Throwable failure = publishFatalPreserving(cancellationFailure);
                failure = terminalizeReservations(reservations, failure);
                ChunkDelivery.<RuntimeException>rethrow(
                        publishFatalPreserving(failure));
            }
        }
        // 2) Offer one receipt-gated transaction. The EventLoop cannot enter
        // Start until publish() has either admitted every exact snapshot or
        // terminalized the complete reservation set with W.
        BatchPublication[] publicationSlot = new BatchPublication[1];
        try {
            RegionScheduler.runSuccessBodyIfHealthy(() ->
                    publicationSlot[0] = new BatchPublication(ps,
                            reservations.toArray(Reservation[]::new),
                            fault("failstart"), fault("failenqueue")));
        } catch (Throwable constructionFailure) {
            Throwable failure = publishFatalPreserving(constructionFailure);
            failure = terminalizeReservations(reservations, failure);
            ChunkDelivery.<RuntimeException>rethrow(
                    publishFatalPreserving(failure));
            return 0;
        }
        BatchPublication publication = publicationSlot[0];
        RegionScheduler.commitSuccessIfHealthy(
                () -> ps.activePublication = publication);
        publication.publish(p);
        return 0;
    }

    private static void selectCandidate(ServerLevel level,
                                        RegionizedServer rs,
                                        PlayerState ps,
                                        Rec r,
                                        ArrayList<Reservation> reservations) {
        if (r.st == St.TRACKED) {
            lease(level, rs, r);
        }
        // Owner mailbox owns a REQUESTED record — but ONLY a ticking island
        // drains its mailbox. A lease into an idle island (no entities → the
        // drive never submits units → sealPending never runs) stalled forever,
        // hence the bounded escalation. A seal that already answered the
        // request is adopted first: a sender paused past the bound by the
        // unacked-batch cap has nothing to escalate. Escalation remains in the
        // same admitted candidate.
        if (r.st == St.REQUESTED && !adoptSeal(level, r)
                && System.nanoTime() - r.stateNanos > REQUESTED_TIMEOUT_NANOS
                && r.cas(St.REQUESTED, St.TRACKED)) {
            r.fenceFails = ESCALATE_AFTER;
            r.owner.requestedTimeouts++;
            REQUESTED_TIMEOUTS.incrementAndGet();
            lease(level, rs, r);
        }
        if (r.st != St.READY) {
            return;
        }
        Entry e = r.entry;
        if (e == null) {
            r.cas(St.READY, St.TRACKED);
            return;
        }
        // lifecycle witness: same LevelChunk object still installed?
        LevelChunk live = level.getChunkSource().getChunkNow(
                net.minecraft.world.level.ChunkPos.getX(r.chunkKey),
                net.minecraft.world.level.ChunkPos.getZ(r.chunkKey));
        if (live == null || live != e.chunk) {
            demoteReady(r);
            return;
        }
        // Write-witness fence: the chunk itself must be untouched since seal.
        if (((FabroliaWriteGen) live).fabrolia$writeGen() != e.writeGenAtSeal) {
            if (demoteReady(r)) {
                r.fenceFails++;
                ps.retriesFence++;
                FENCE_RETRIES.incrementAndGet();
            }
            return;
        }
        // Reserve immediately; no later candidate may enter until this
        // reservation and its permit are terminal.
        Reservation res = r.tryReserve();
        if (res == null) return;
        try {
            reservations.add(res);
        } catch (Throwable appendFailure) {
            Throwable failure = publishFatalPreserving(appendFailure);
            try {
                res.terminalizeFailure();
            } catch (Throwable cleanupFailure) {
                failure = publishFatalPreserving(
                        mergeFailure(failure, cleanupFailure));
            }
            ChunkDelivery.<RuntimeException>rethrow(
                    publishFatalPreserving(failure));
        }
    }

    /**
     * Main-side terminal edge for a real EventLoop success. Installs the ACK
     * skeleton and success accounting exactly once; an offered/running batch
     * has no completion to apply and cannot start a successor batch.
     */
    public static int applyCompletedChunkBatch(ServerPlayer p) {
        RegionScheduler.throwWorkerFailureIfAny();
        PlayerState ps = PLAYERS.get(p.getUUID());
        if (ps == null) return 0;
        BatchPublication publication = ps.activePublication;
        return publication == null ? 0 : publication.applySuccessOnMain();
    }

    /** REQUESTED record: adopt a seal the owner published for this request.
     *  An entry built before the lease cannot be fresher than the one lease()
     *  rejected; adopting it would only count a false fence failure and, after
     *  {@link #ESCALATE_AFTER}, force a fenced Main build. */
    private static boolean adoptSeal(ServerLevel level, Rec r) {
        Entry e = cache(level).get(r.chunkKey);
        if (e == null || e.builtNanos - r.leaseNanos < 0) return false;
        e.refs.incrementAndGet();
        r.entry = e;
        if (!r.cas(St.REQUESTED, St.READY)) {
            r.entry = null;
            e.refs.decrementAndGet(); // retired (or escalated) concurrently
            return false;
        }
        return true;
    }

    /** Client acked one batch: retire the OLDEST outstanding record set.
     *  Called only by PlayerChunkSender's physical-Main intent drain; packet
     *  ingress itself never touches this deque. */
    public static void onAck(ServerPlayer p) {
        PlayerState ps = PLAYERS.get(p.getUUID());
        if (ps == null) return;
        if (fault("dropack")) return;
        Batch b = ps.outstanding.pollFirst();
        if (b == null) {
            ps.staleAcks++;
            LOGGER.warn("surplus chunk-batch ack from {} (no outstanding batch) — ignored",
                    p.getName().getString());
            return;
        }
        if (!b.publicationAccepted()) {
            RegionScheduler.throwWorkerFailureIfAny();
            Throwable failure = publishFatalPreserving(new IllegalStateException(
                    "chunk ACK observed an uncommitted batch publication"));
            b.cancelPublication();
            ChunkDelivery.<RuntimeException>rethrow(failure);
        }
        ps.lastAckNanos = System.nanoTime();
        for (int i = 0; i < b.count; i++) {
            Rec r = b.records[i];
            if (r.cas(St.ENQUEUED, St.ACKNOWLEDGED)) {
                Entry e = r.entry;
                r.entry = null;
                if (e != null) e.refs.decrementAndGet();
                ps.acked++;
            }
        }
    }

    // ------------------------------------------------------------------
    // liveness invariant + observability
    // ------------------------------------------------------------------

    /** Acknowledgement capability (2026-07-14 fake-player spam fix): a player
     *  who has EVER acked a chunk batch is proven real; otherwise class-name
     *  detection covers carpet's EntityPlayerMPFake (and forks) with no
     *  carpet compile dependency. Real clients before their first ack remain
     *  WARN-eligible on purpose — a real client failing its FIRST batch ack
     *  is a real stall. */
    static boolean ackCapable(PlayerState ps) {
        if (ps.lastAckNanos != 0) {
            return true;
        }
        return !ps.player.getClass().getName().contains("Fake");
    }

    private static int lastFakeCapped = -1;
    private static long lastFakeSummaryNanos;

    /** Main, ~1/s: a connected ACK-CAPABLE player with pending records but no
     *  enqueue for 10s → one WARN naming the oldest blocked state (re-report
     *  every ~30s via its own backoff field — never by mutating metrics).
     *  Non-ack-capable players (carpet bots) are expected to park at the
     *  vanilla batch cap: excluded from WARNs, surfaced once as an aggregate
     *  DEBUG when the count changes (≥60s apart) and in chunksender status. */
    public static void liveness() {
        long t0 = System.nanoTime();
        int fakeCapped = 0;
        for (PlayerState ps : PLAYERS.values()) {
            if (ps.records.isEmpty()) continue;
            boolean pending = false;
            Rec oldest = null;
            for (Rec r : ps.records.values()) {
                if (r.st.ordinal() < St.ENQUEUED.ordinal()) {
                    pending = true;
                    if (oldest == null || r.stateNanos < oldest.stateNanos) oldest = r;
                }
            }
            if (!pending) continue;
            if (!ackCapable(ps)) {
                fakeCapped++;
                continue;
            }
            long now = System.nanoTime();
            // Primary signal = the RECORD's own state age. The old heuristic
            // keyed on lastFullSendNanos, which survives dimension changes and
            // tracking rebuilds — a 44ms-old REQUESTED record was reported as
            // "no enqueue for 65s" (production, 2026-07-20) because the
            // timestamp belonged to a previous, fully-delivered view. A young
            // pipeline is healthy no matter how old the last enqueue is.
            long oldestAgeMs = (now - oldest.stateNanos) / 1_000_000;
            if (oldestAgeMs > 10_000
                    && now - ps.lastLivenessReportNanos > 30_000_000_000L) {
                ps.lastLivenessReportNanos = now;
                Batch ob = ps.outstanding.peekFirst();
                String diagnosis;
                if (!ps.player.connection.isAcceptingMessages()) {
                    diagnosis = "STALE SESSION — connection not accepting, quit() never ran (invariant breach)";
                } else {
                    diagnosis = switch (oldest.st) {
                        case TRACKED -> {
                            ServerLevel lvl = ps.level;
                            LevelChunk live = lvl == null ? null : lvl.getChunkSource().getChunkNow(
                                    net.minecraft.world.level.ChunkPos.getX(oldest.chunkKey),
                                    net.minecraft.world.level.ChunkPos.getZ(oldest.chunkKey));
                            yield live == null
                                    ? "waiting for chunk load/generation (server-side, not a delivery stall)"
                                    : "loaded but never selected — batch quota/unacked-cap starvation";
                        }
                        // selectCandidate escalates a REQUESTED record past the 1s
                        // bound whenever it reaches it; the distance-ordered pass
                        // stops at the batch quota (and does not run while the
                        // unacked cap pauses the sender), so a far record waits
                        // while nearer chunks fill every batch, as in vanilla.
                        case REQUESTED -> "leased to owner mailbox, not reselected since — "
                                + "batch quota/unacked-cap starvation (escalates on reselection)";
                        case READY -> "sealed payload awaiting batch quota (unacked cap or quota starvation)";
                        case RESERVED -> "stuck inside a batch transaction (actionable)";
                        default -> "unexpected pending state";
                    };
                }
                long sinceSendMs = ps.lastFullSendNanos == 0 ? -1
                        : (now - ps.lastFullSendNanos) / 1_000_000;
                LOGGER.warn("[delivery-liveness] {} oldest pending chunk [{}, {}] state={} for {}ms "
                        + "({}); fenceFails={} requestedTimeouts={} lastEnqueue={}ms ago; outstanding "
                        + "batches={} oldest=#{} age={}ms; records={} — no auto-reset",
                        ps.player.getName().getString(),
                        net.minecraft.world.level.ChunkPos.getX(oldest.chunkKey),
                        net.minecraft.world.level.ChunkPos.getZ(oldest.chunkKey),
                        oldest.st, oldestAgeMs, diagnosis, oldest.fenceFails, ps.requestedTimeouts,
                        sinceSendMs, ps.outstanding.size(), ob == null ? 0 : ob.id,
                        ob == null ? 0 : (now - ob.createdNanos) / 1_000_000,
                        ps.records.size());
            }
        }
        long now = System.nanoTime();
        if (fakeCapped != lastFakeCapped && now - lastFakeSummaryNanos > 60_000_000_000L) {
            lastFakeCapped = fakeCapped;
            lastFakeSummaryNanos = now;
            if (fakeCapped > 0) {
                LOGGER.debug("Chunk delivery: {} non-acknowledging fake players are paused at the "
                        + "vanilla batch cap (expected carpet behavior; details in chunksender status)",
                        fakeCapped);
            }
        }
        livenessEmaNanos = (livenessEmaNanos * 7 + (System.nanoTime() - t0)) >> 3;
    }

    public static String status(ServerPlayer one) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("delivery: seals=").append(SEALS.get())
          .append(" sealMs=").append(SEAL_NANOS.get() / 1_000_000)
          .append(" inline=").append(INLINE_BUILDS.get())
          .append(" reuse=").append(REUSED.get())
          .append(" fenceRetries=").append(FENCE_RETRIES.get())
          .append(" escalated=").append(ESCALATED.get())
          .append(" rerouted=").append(REROUTED.get())
          .append(" cacheBytes=").append(CACHE_BYTES.get() / 1024).append("k")
          .append(" invalidPayload=").append(INVALID_PAYLOAD.get())
          .append(" requestedTimeouts=").append(REQUESTED_TIMEOUTS.get())
          .append(" livenessUs=").append(livenessEmaNanos / 1_000)
          .append(" fakeCapped=").append(Math.max(0, lastFakeCapped))
          .append(" faults='").append(faults).append('\'');
        for (PlayerState ps : PLAYERS.values()) {
            if (one != null && ps.player != one) continue;
            int[] byState = new int[St.values().length];
            Rec oldest = null;
            for (Rec r : ps.records.values()) {
                byState[r.st.ordinal()]++;
                if (r.st.ordinal() < St.ENQUEUED.ordinal()
                        && (oldest == null || r.stateNanos < oldest.stateNanos)) oldest = r;
            }
            long now = System.nanoTime();
            sb.append("\n ").append(ps.player.getName().getString())
              .append(ackCapable(ps) ? " [real]" : " [fake:no-ack]")
              .append(" connGen=").append(ps.connGen)
              .append(" trackGen=").append(ps.trackingGen.get())
              .append(" recs=").append(ps.records.size());
            for (St s : St.values()) {
                if (byState[s.ordinal()] > 0) sb.append(' ').append(s).append('=').append(byState[s.ordinal()]);
            }
            sb.append(" | created=").append(ps.created.get())
              .append(" cancelled=").append(ps.cancelled.get())
              .append(" replaced=").append(ps.replaced.get())
              .append(" enqueued=").append(ps.enqueued)
              .append(" acked=").append(ps.acked)
              .append(" batches=").append(ps.batches)
              .append(" outstanding=").append(ps.outstanding.size())
              .append(" fenceRetries=").append(ps.retriesFence)
              .append(" escalations=").append(ps.escalations)
              .append(" coalesced=").append(ps.coalesced)
              .append(" staleAcks=").append(ps.staleAcks)
              .append(" nullEntryRecov=").append(ps.nullEntryRecoveries)
              .append(" enqueueFails=").append(ps.enqueueFails)
              .append(" cancelledInBatch=").append(ps.cancelledInBatch)
              .append(" requestedTimeouts=").append(ps.requestedTimeouts)
              .append(" kB=").append(ps.bytesEnqueued / 1024)
              .append(" lastSend=").append(ps.lastFullSendNanos == 0 ? "-" : (now - ps.lastFullSendNanos) / 1_000_000 + "ms")
              .append(" lastAck=").append(ps.lastAckNanos == 0 ? "-" : (now - ps.lastAckNanos) / 1_000_000 + "ms");
            if (oldest != null) {
                sb.append(" | oldestPending=[").append(net.minecraft.world.level.ChunkPos.getX(oldest.chunkKey))
                  .append(',').append(net.minecraft.world.level.ChunkPos.getZ(oldest.chunkKey))
                  .append("] ").append(oldest.st).append(' ')
                  .append((now - oldest.stateNanos) / 1_000_000).append("ms");
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // deterministic extreme-displacement harness (2026-07-14: the Lunge-255
    // incident moved ~5000 blocks in ~1s — far beyond elytra; the state
    // machine must survive tracking-window replacement storms). Seeded PRNG
    // path, fixed step cadence, per-second metrics; assertions run on stop.
    // ------------------------------------------------------------------
    private static ServerPlayer stressPlayer;
    private static long stressSeed;
    private static int stressTicksLeft;
    private static int stressBlocksPerTick;
    private static long stressLastReport;
    private static long baseCancelled, baseCreated, baseEnqueued, baseBatches;

    public static String stressStart(ServerPlayer p, int blocksPerSec, int seconds, long seed) {
        stressPlayer = p;
        stressSeed = seed;
        stressTicksLeft = seconds * 20;
        stressBlocksPerTick = Math.max(1, blocksPerSec / 20);
        PlayerState ps = ps(p);
        baseCancelled = ps.cancelled.get();
        baseCreated = ps.created.get();
        baseEnqueued = ps.enqueued;
        baseBatches = ps.batches;
        return "stresstp armed: " + blocksPerSec + " blocks/s for " + seconds + "s seed=" + seed
                + " (" + stressBlocksPerTick + " blocks/tick)";
    }

    /** Main tick hook. */
    public static void stressTick() {
        ServerPlayer p = stressPlayer;
        if (p == null) return;
        if (stressTicksLeft-- <= 0 || p.isRemoved()) {
            PlayerState ps = PLAYERS.get(p.getUUID());
            stressPlayer = null;
            if (ps != null) {
                LOGGER.info("[stresstp] DONE created={} cancelled={} enqueued={} batches={} outstanding={} "
                        + "recs={} fenceRetries={} escalations={} — nonterminal-stuck={}",
                        ps.created.get() - baseCreated, ps.cancelled.get() - baseCancelled,
                        ps.enqueued - baseEnqueued, ps.batches - baseBatches,
                        ps.outstanding.size(), ps.records.size(), ps.retriesFence, ps.escalations,
                        countStuck(ps));
            }
            return;
        }
        // xorshift on the seed → deterministic direction changes, incl. full
        // reversals (the production incident's worst pattern)
        stressSeed ^= stressSeed << 13;
        stressSeed ^= stressSeed >>> 7;
        stressSeed ^= stressSeed << 17;
        int dir = (int) (stressSeed & 3);
        double dx = dir == 0 ? stressBlocksPerTick : dir == 1 ? -stressBlocksPerTick : 0;
        double dz = dir == 2 ? stressBlocksPerTick : dir == 3 ? -stressBlocksPerTick : 0;
        p.teleportTo(p.getX() + dx, 200, p.getZ() + dz);
        bumpTrackingGen(p);
        long now = System.nanoTime();
        if (now - stressLastReport > 1_000_000_000L) {
            stressLastReport = now;
            PlayerState ps = PLAYERS.get(p.getUUID());
            if (ps != null) {
                LOGGER.info("[stresstp] t-{}s recs={} created={} cancelled={} enq={} out={} trackGen={}",
                        stressTicksLeft / 20, ps.records.size(), ps.created.get() - baseCreated,
                        ps.cancelled.get() - baseCancelled, ps.enqueued - baseEnqueued,
                        ps.outstanding.size(), ps.trackingGen.get());
            }
        }
    }

    private static int countStuck(PlayerState ps) {
        long now = System.nanoTime();
        int stuck = 0;
        for (Rec r : ps.records.values()) {
            if (r.st.ordinal() < St.ENQUEUED.ordinal() && now - r.stateNanos > 30_000_000_000L) stuck++;
        }
        return stuck;
    }

    private static Throwable terminalizeReservations(
            Reservation[] reservations, Throwable primary) {
        Throwable failure = publishFatalPreserving(primary);
        for (Reservation reservation : reservations) {
            try {
                reservation.terminalizeFailure();
            } catch (Throwable cleanupFailure) {
                failure = publishFatalPreserving(
                        mergeFailure(failure, cleanupFailure));
            }
        }
        return publishFatalPreserving(failure);
    }

    private static Throwable terminalizeReservations(
            Iterable<Reservation> reservations, Throwable primary) {
        Throwable failure = publishFatalPreserving(primary);
        for (Reservation reservation : reservations) {
            try {
                reservation.terminalizeFailure();
            } catch (Throwable cleanupFailure) {
                failure = publishFatalPreserving(
                        mergeFailure(failure, cleanupFailure));
            }
        }
        return publishFatalPreserving(failure);
    }

    private static void terminalizeReservationsWithoutFailure(
            Reservation[] reservations) {
        Throwable failure = null;
        for (Reservation reservation : reservations) {
            try {
                reservation.terminalizeFailure();
            } catch (Throwable cleanupFailure) {
                failure = failure == null
                        ? publishFatalPreserving(cleanupFailure)
                        : publishFatalPreserving(
                                mergeFailure(failure, cleanupFailure));
            }
        }
        if (failure != null) {
            ChunkDelivery.<RuntimeException>rethrow(
                    publishFatalPreserving(failure));
        }
    }

    private static Throwable mergeFailure(Throwable primary, Throwable secondary) {
        if (primary == null) return secondary;
        if (secondary != null && secondary != primary) {
            try {
                primary.addSuppressed(secondary);
            } catch (Throwable suppressionFailure) {
                // Suppression is cleanup too: keep the already-published W,
                // but publish this cleanup failure before returning W.
                return publishFatalPreserving(suppressionFailure);
            }
        }
        return primary;
    }

    /** Fatal publication is mandatory diagnostics, never a replacement for
     * the exact simulation/transport primary. */
    private static Throwable publishFatalPreserving(Throwable primary) {
        return RegionScheduler.publishFatalAndSelectWinner(primary);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T) failure;
    }

    public static String traceCmd(ServerPlayer p, int cx, int cz) {
        traceKey = net.minecraft.world.level.ChunkPos.pack(cx, cz);
        StringBuilder sb = new StringBuilder("trace armed for [" + cx + "," + cz + "]; recent:");
        PlayerState ps = p == null ? null : PLAYERS.get(p.getUUID());
        if (ps != null) {
            Rec r = ps.records.get(traceKey);
            if (r != null) {
                sb.append("\n live record: state=").append(r.st)
                  .append(" fenceFails=").append(r.fenceFails)
                  .append(" trackGen=").append(r.trackingGen)
                  .append(" entry=").append(r.entry == null ? "-" : "L" + r.entry.sealL);
            }
        }
        for (String s : TRACE) sb.append("\n ").append(s);
        return sb.toString();
    }
}
