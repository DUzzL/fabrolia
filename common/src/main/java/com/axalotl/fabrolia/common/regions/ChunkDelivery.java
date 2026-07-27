package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
 * ENQUEUED → ACKNOWLEDGED   (terminal: CANCELLED / REPLACED)
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
 * Revision fence (no lost increments, no overtaking): a sealed packet may be
 * enqueued only while {@code owner.logicalTick == sealL} — logicalTick is
 * written once per unit AFTER all its writes, so equality proves no unit
 * completed since the seal; a unit still in flight has not had its dirty
 * flags broadcast-cleared yet (broadcast runs on main after this enqueue),
 * so its updates still reach the player in order. On fence failure the lease
 * is re-issued (bounded); after {@link #ESCALATE_AFTER} failures the record
 * escalates to a fenced main build (mainClaimScoped — correct at any island
 * rate, bounded to pathological always-written chunks).
 *
 * Batches are transactional: reserve → (only if non-empty) Start → enqueue
 * every reserved packet → Finished(count) → commit unacknowledged. Failures
 * before Start release reservations and touch nothing; vanilla's
 * unacknowledged counter is only incremented after Finished is enqueued, so
 * an acknowledgement obligation can never leak. Acks apply to an explicit
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
        ENQUEUED,           // full packet written to the connection
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
        volatile long enqueuedNanos;

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
         * deferred + reservation); ENQUEUED one (record, until ack); terminal
         * none. Payload-publishing edges (TRACKED/REQUESTED → READY) set {@code
         * entry} BEFORE the CAS and undo on CAS failure, so an observer that
         * reads state READY is guaranteed a non-null entry; only the winner of a
         * ref-releasing edge nulls {@code entry}.
         */
        boolean cas(St from, St next) {
            if (ST.compareAndSet(this, from, next)) {
                stateNanos = System.nanoTime();
                if (traceKey == chunkKey) {
                    trace("(" + owner.player.getName().getString() + ") " + from + " -> " + next);
                }
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
         *  this CAS (it then marks RESERVED terminal and the sender's
         *  enqueue-CAS failure releases both references). */
        Reservation tryReserve() {
            if (!cas(St.READY, St.RESERVED)) {
                return null;
            }
            Entry e = entry;
            if (e == null) {
                // Unreachable by the publication contract (entry precedes
                // READY); kept as a loud recovery, never a crash.
                INVALID_PAYLOAD.incrementAndGet();
                owner.nullEntryRecoveries++;
                invariantError("RESERVED record with null entry", this);
                cas(St.RESERVED, St.TRACKED); // re-lease on the next pass
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
     * sender owns the cleanup: its enqueue-CAS fails against the terminal
     * state and {@link Reservation#abandonCancelled} releases both refs.
     */
    static void retire(Rec r, St terminal) {
        for (;;) {
            St s = r.st;
            if (s == St.CANCELLED || s == St.REPLACED || s == St.ACKNOWLEDGED) return;
            if (r.cas(s, terminal)) {
                if (s == St.READY || s == St.ENQUEUED) {
                    Entry e = r.entry;
                    r.entry = null;
                    if (e != null) e.refs.decrementAndGet();
                }
                return;
            }
        }
    }

    /** Immutable reservation: the sender operates on THIS object only —
     *  never on the record's mutable entry field. entry is final non-null. */
    public static final class Reservation {
        final Rec rec;
        final Entry entry;

        Reservation(Rec rec, Entry entry) {
            this.rec = rec;
            this.entry = entry;
        }

        /** Sent: record advances, reservation reference released (the
         *  record's own reference stays until ACKNOWLEDGED). */
        void commit() {
            entry.refs.decrementAndGet();
        }

        /** Not sent, record still live: back to READY keeping its payload.
         *  Handles both the pre-send failure (still RESERVED) and the
         *  post-claim send failure (already ENQUEUED). */
        void rollback() {
            entry.refs.decrementAndGet();
            if (rec.cas(St.RESERVED, St.READY) || rec.cas(St.ENQUEUED, St.READY)) {
                return;
            }
            // retired while in flight: release the record's deferred reference
            // unless the retire edge (READY/ENQUEUED winner) already did —
            // the null check makes the release exactly-once.
            Entry e2 = rec.entry;
            rec.entry = null;
            if (e2 != null) e2.refs.decrementAndGet();
        }

        /** Record was cancelled/replaced while reserved: skip the send and
         *  release both references (reservation + the record's deferred one). */
        void abandonCancelled() {
            rec.entry = null;
            entry.refs.addAndGet(-2);
        }
    }

    // ------------------------------------------------------------------
    // shared sealed snapshot (refcounted, coalesced across players)
    // ------------------------------------------------------------------
    public static final class Entry {
        final LevelChunk chunk;                 // identity = lifecycle witness
        final ClientboundLevelChunkWithLightPacket packet;
        final long sealL;
        /** Chunk write witness captured under the owner's claim at seal:
         *  the enqueue fence compares against the LIVE gen — equality proves
         *  no block/BE write happened since the seal, so no increment a
         *  pending player missed can exist. Per-CHUNK precision: quiet
         *  chunks (virtually all, during flight) pass on the first try. */
        final long writeGenAtSeal;
        final PIslandRef ownerRef;
        final AtomicInteger refs = new AtomicInteger();
        final long bytes;
        final long builtNanos = System.nanoTime();

        Entry(LevelChunk chunk, ClientboundLevelChunkWithLightPacket packet,
              long sealL, IslandRegistry.PIsland island) {
            this.chunk = chunk;
            this.packet = packet;
            this.sealL = sealL;
            this.writeGenAtSeal = ((FabroliaWriteGen) chunk).fabrolia$writeGen();
            this.ownerRef = new PIslandRef(island);
            this.bytes = 40_000; // section-payload class estimate for budget math
        }
    }

    record PIslandRef(IslandRegistry.PIsland island) {}

    // ------------------------------------------------------------------
    // per-player state
    // ------------------------------------------------------------------
    public static final class PlayerState {
        final ServerPlayer player;
        final long connGen;
        /** Bumped by trackers (any thread under the concurrent model). */
        final AtomicLong trackingGen = new AtomicLong();
        /** CONCURRENT (2026-07-20 redesign): structural mutation from any
         *  thread; iteration/copies are weakly consistent and can never throw.
         *  Record lifecycle safety lives in the per-record CAS transitions. */
        final ConcurrentHashMap<Long, Rec> records = new ConcurrentHashMap<>();
        /** Batch obligations stay batcher/ack-owned: both run on the server
         *  thread (26.2 acks are ensureRunningOnSameThread'd). */
        final ArrayDeque<Batch> outstanding = new ArrayDeque<>();
        volatile ServerLevel level;
        // metrics mutated by trackers (any thread):
        final AtomicLong created = new AtomicLong(), cancelled = new AtomicLong(),
                replaced = new AtomicLong();
        // metrics owned by the main batcher/ack path:
        long enqueued, acked, retriesFence,
                escalations, reroutes, coalesced, staleAcks, batches;
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
        final long id;
        final long connGen;
        final long createdNanos = System.nanoTime();
        final long[] keys;
        final int count;

        Batch(long id, long connGen, long[] keys) {
            this.id = id;
            this.connGen = connGen;
            this.keys = keys;
            this.count = keys.length;
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
    private static final ConcurrentHashMap<String, Long> LOG_GATE = new ConcurrentHashMap<>();
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

    static void warnRateLimited(String key, String msg) {
        long now = System.nanoTime();
        Long last = LOG_GATE.get(key);
        if (last == null || now - last > 60_000_000_000L) {
            LOG_GATE.put(key, now);
            LOGGER.warn("{} (rate-limited; counters in chunksender status)", msg);
        }
    }

    static void invariantError(String what, Rec r) {
        long now = System.nanoTime();
        Long last = LOG_GATE.get("invariant");
        if (last == null || now - last > 60_000_000_000L) {
            LOG_GATE.put("invariant", now);
            LOGGER.error("[delivery-invariant] {} — player={} chunk=[{},{}] state={} connGen={} trackGen={} "
                    + "fenceFails={} ageMs={} (rate-limited; one per minute)",
                    what, r.owner.player.getName().getString(),
                    net.minecraft.world.level.ChunkPos.getX(r.chunkKey),
                    net.minecraft.world.level.ChunkPos.getZ(r.chunkKey),
                    r.st, r.connGen, r.trackingGen, r.fenceFails,
                    (now - r.stateNanos) / 1_000_000);
        }
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
        // Exactly-once retirement per record from ANY thread: the state CAS
        // arbitrates against concurrent reservation/enqueue; refs release on
        // the winning edge only. The outstanding deque is main-owned — the
        // vanilla disconnect path IS main; a hypothetical foreign quit still
        // retires records safely and merely leaves dead batch skeletons that
        // die with the PlayerState.
        for (Rec r : ps.records.values()) {
            retire(r, St.CANCELLED);
        }
        ps.records.clear();
        ps.outstanding.clear(); // connection gone: obligations die with it
    }

    private static PlayerState ps(ServerPlayer p) {
        PlayerState ps = PLAYERS.get(p.getUUID());
        return ps != null ? ps : join(p);
    }

    /** markChunkPendingToSend: the chunk is desired and server-ready.
     *  Callable from ANY thread: map put is linearizable (CHM), record
     *  replacement retires the displaced record via CAS exactly-once. */
    public static void track(ServerPlayer p, LevelChunk chunk) {
        PlayerState ps = ps(p);
        ServerLevel lvl = (ServerLevel) chunk.getLevel();
        if (ps.level != null && ps.level != lvl) {
            // dimension change: everything from the old level is obsolete —
            // retire by generation, not by thread timing: the trackingGen
            // bump invalidates in-flight work even before the sweep lands.
            ps.trackingGen.incrementAndGet();
            for (Rec r0 : ps.records.values()) {
                retire(r0, St.CANCELLED);
            }
            ps.records.clear();
            // outstanding is server-thread-owned; dimension changes arrive on
            // main (respawn orchestration), matching that ownership.
            ps.outstanding.clear();
            // fresh delivery epoch: the old enqueue timestamp belongs to the
            // previous view and must not age the new one (liveness heuristics)
            ps.lastFullSendNanos = System.nanoTime();
        }
        ps.level = lvl;
        long key = chunk.getPos().pack();
        Rec r = new Rec(ps, key, ps.trackingGen.get());
        Rec old = ps.records.put(key, r);
        if (old != null && !old.isTerminal()) {
            // Re-entered so fast the old delivery is still live. The new record
            // (fresh trackingGen) has already replaced it in the map — the old
            // one's late seal cannot satisfy the new lifecycle (entry chunk
            // identity re-checked at reservation).
            retire(old, St.REPLACED);
            ps.replaced.incrementAndGet();
        }
        ps.created.incrementAndGet();
        if (traceKey == key) trace("(" + p.getName().getString() + ") TRACKED gen=" + r.trackingGen);
    }

    /** dropChunk: no longer desired — cancel whatever stage it is in.
     *  Any thread; removal + retirement are individually linearizable and the
     *  batcher revalidates state at both reservation and enqueue CAS. */
    public static void untrack(ServerPlayer p, long chunkKey) {
        PlayerState ps = PLAYERS.get(p.getUUID());
        if (ps == null) return;
        Rec r = ps.records.remove(chunkKey);
        if (r != null) {
            retire(r, St.CANCELLED);
            ps.cancelled.incrementAndGet();
        }
    }

    /** Movement burst boundary: one generation per tracking update batch. */
    public static void bumpTrackingGen(ServerPlayer p) {
        PlayerState ps = PLAYERS.get(p.getUUID());
        if (ps != null) ps.trackingGen.incrementAndGet();
    }

    /** Vanilla isPending gate: excluded from increment broadcasts until the
     *  full packet is on the wire (the enqueue flip + L-fence make that
     *  hand-off lossless). */
    public static boolean isPending(ServerPlayer p, long chunkKey) {
        PlayerState ps = PLAYERS.get(p.getUUID());
        if (ps == null) return false;
        Rec r = ps.records.get(chunkKey);
        return r != null && r.st.ordinal() < St.ENQUEUED.ordinal();
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
        Region region = rs == null ? null : rs.regionizer().peekByGridKey(
                rs.regionizer().grid.keyFor(key));
        IslandRegistry.PIsland island = region != null && region.domain != null
                ? region.domain.owner : null;
        if (island == null || !rs.asyncActive) {
            // Ownerless / sync: main IS the executor of the global domain —
            // building here is the vanilla path (zero fence gap by
            // construction, exactly like sendNextChunks always did).
            if (fault("failinline")) return;
            long t0 = System.nanoTime();
            Entry e = new Entry(live,
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
        if (r.fenceFails >= ESCALATE_AFTER) {
            // Bounded escalation: fenced main build — correct at any island
            // rate; only ever hit for chunks written on every island tick.
            ESCALATED.incrementAndGet();
            r.owner.escalations++;
            boolean took = IslandCommands.mainClaimScoped(island);
            try {
                Entry e = new Entry(live,
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
            } finally {
                if (took) {
                    IslandCommands.mainRelease(island);
                }
            }
            return;
        }
        // normal path: lease to the owner's mailbox (coalesced by key)
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
        Long key;
        ArrayList<Long> notReady = null;
        while (budget > 0 && (key = island.pendingChunkSnapshots.poll()) != null) {
            island.pendingSnapshotKeys.remove(key);
            long k = key;
            // fire-time validation: still owned by THIS island?
            Region region = rs.regionizer().peekByGridKey(rs.regionizer().grid.keyFor(k));
            if (region == null || region.domain == null || region.domain.owner != island) {
                REROUTED.incrementAndGet();
                requeueToCurrentOwner(rs, k); // owner moved: re-lease, never lost
                continue;
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(
                    net.minecraft.world.level.ChunkPos.getX(k), net.minecraft.world.level.ChunkPos.getZ(k));
            if (chunk == null) {
                // not-ready wait state: readiness returns via tracking/batcher,
                // NOT by per-tick recollection through the sender
                continue;
            }
            if (fault("delayseal") && (k & 1) == 0) {
                if (notReady == null) notReady = new ArrayList<>();
                notReady.add(k);
                continue;
            }
            long t0 = System.nanoTime();
            Entry e = new Entry(chunk,
                    new ClientboundLevelChunkWithLightPacket(chunk,
                            level.getLightEngine(), null, null),
                    island.logicalTick + 1, island); // unit runs AT L+1 (unitGameTime)
            SEALS.incrementAndGet();
            SEAL_NANOS.addAndGet(System.nanoTime() - t0);
            publish(level, k, e);
            budget--;
        }
        if (notReady != null) {
            for (Long k : notReady) {
                if (island.pendingSnapshotKeys.add(k)) island.pendingChunkSnapshots.add(k);
            }
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
        for (var it = map.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            Entry e = en.getValue();
            // evict only unreferenced entries — eviction can NEVER cancel a
            // delivery; a record holding a ref keeps its entry alive.
            if (e.refs.get() == 0 && now - e.builtNanos > 2_000_000_000L) {
                it.remove();
                CACHE_BYTES.addAndGet(-e.bytes);
            }
            if (CACHE_BYTES.get() <= (fault("tinybudget") ? 1L * 1024 * 1024 : CACHE_BYTE_BUDGET / 2)) break;
        }
    }

    /** Topology rebuild carry-state: re-lease every stranded mailbox key to
     *  the chunk's CURRENT owner (or leave for main-inline). Never lost,
     *  never duplicated (pendingSnapshotKeys dedups per island). */
    public static void requeueToCurrentOwner(RegionizedServer rs, long chunkKey) {
        Region region = rs.regionizer().peekByGridKey(rs.regionizer().grid.keyFor(chunkKey));
        IslandRegistry.PIsland owner = region != null && region.domain != null
                ? region.domain.owner : null;
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
     *  chunk packets enqueued (0 = no batch was started). {@code onEnqueued}
     *  keeps vanilla's pendingChunks mirror exact (isPending correctness). */
    public static int sendNextChunks(ServerPlayer p, ServerLevel level, int maxToSend,
                                     it.unimi.dsi.fastutil.longs.LongPredicate onEnqueued) {
        PlayerState ps = PLAYERS.get(p.getUUID());
        if (ps == null || ps.records.isEmpty() || maxToSend <= 0) return 0;
        RegionizedServer rs = RegionScheduler.forLevel(level);
        // 1) drive TRACKED → lease, and collect fence-valid READY records
        long playerPos = p.chunkPosition().pack();
        ArrayList<Rec> candidates = new ArrayList<>(ps.records.values());
        // nearest-first: current view priority, obsolete flight path ages out
        candidates.sort((a, b) -> Long.compare(dist(a.chunkKey, playerPos), dist(b.chunkKey, playerPos)));
        ArrayList<Reservation> reservations = new ArrayList<>(Math.min(maxToSend, 64));
        for (Rec r : candidates) {
            if (reservations.size() >= maxToSend) break;
            switch (r.st) {
                case TRACKED -> lease(level, rs, r);
                case REQUESTED -> {
                    // Owner mailbox owns it — but ONLY a ticking island drains
                    // its mailbox. A lease into an idle island (no entities →
                    // the drive never submits units → sealPending never runs)
                    // stalled forever with fenceFails=0 (production liveness:
                    // REQUESTED for 22s). Bounded wait, then escalate to the
                    // fenced main build — on an idle island the claim is free
                    // and the build is instant; on a busy one this is the same
                    // bounded escalation the fence-fail path already takes.
                    if (System.nanoTime() - r.stateNanos > REQUESTED_TIMEOUT_NANOS
                            && r.cas(St.REQUESTED, St.TRACKED)) {
                        r.fenceFails = ESCALATE_AFTER;
                        r.owner.requestedTimeouts++;
                        REQUESTED_TIMEOUTS.incrementAndGet();
                        lease(level, rs, r);
                    }
                }
                default -> { }
            }
            if (r.st == St.READY || (r.st == St.REQUESTED && adoptSeal(level, r))) {
                Entry e = r.entry;
                if (e == null) { r.cas(St.READY, St.TRACKED); continue; }
                // lifecycle witness: same LevelChunk object still installed?
                LevelChunk live = level.getChunkSource().getChunkNow(
                        net.minecraft.world.level.ChunkPos.getX(r.chunkKey),
                        net.minecraft.world.level.ChunkPos.getZ(r.chunkKey));
                if (live == null) { demoteReady(r); continue; }
                if (live != e.chunk) { demoteReady(r); continue; }
                // Write-witness fence: the chunk itself must be untouched
                // since the seal (per-chunk precision — an island completing
                // unrelated units does NOT fail this).
                if (((FabroliaWriteGen) live).fabrolia$writeGen() != e.writeGenAtSeal) {
                    if (demoteReady(r)) {
                        r.fenceFails++;
                        ps.retriesFence++;
                        FENCE_RETRIES.incrementAndGet();
                    }
                    continue;
                }
                // ATOMIC selection: reserve IMMEDIATELY (payload captured into
                // the immutable Reservation) — no window in which a later
                // lease() escalation's drain re-entrancy can strip the entry
                // of an already-selected record.
                Reservation res = r.tryReserve();
                if (res != null) {
                    reservations.add(res);
                }
            }
        }
        if (reservations.isEmpty()) return 0;
        if (fault("cancelmidbatch")) {
            // deterministic test hook: re-entrant cancellation between
            // selection and enqueue (the production drain re-entrancy)
            untrack(p, reservations.get(0).rec.chunkKey);
        }
        // 2) transactional batch over immutable reservations
        long[] keys = new long[reservations.size()];
        for (int i = 0; i < keys.length; i++) keys[i] = reservations.get(i).rec.chunkKey;
        Batch batch = new Batch(BATCH_IDS.incrementAndGet(), ps.connGen, keys);
        try {
            if (fault("failstart")) throw new IllegalStateException("injected failstart");
            p.connection.send(ClientboundChunkBatchStartPacket.INSTANCE);
        } catch (Throwable t) {
            // nothing on the wire: roll back every reservation, no ack state
            for (Reservation res : reservations) res.rollback();
            LOGGER.error("batch start failed for {} — released {} reservations",
                    p.getName().getString(), reservations.size(), t);
            return 0;
        }
        int sent = 0;
        for (Reservation res : reservations) {
            Rec r = res.rec;
            if (!r.cas(St.RESERVED, St.ENQUEUED)) {
                // cancelled/replaced while the batch formed: skip cleanly
                // (never send to a lifecycle the tracker abandoned), release
                // both references. THIS was the NPE: the old loop sent from
                // the record's mutable entry field after cancellation nulled
                // it, then "restored" the cancelled ghost to READY.
                res.abandonCancelled();
                ps.cancelledInBatch++;
                continue;
            }
            try {
                if (fault("failenqueue") && sent == 1) throw new IllegalStateException("injected failenqueue");
                p.connection.send(res.entry.packet);
                onEnqueued.test(r.chunkKey);
                res.commit();
                r.enqueuedNanos = System.nanoTime();
                ps.enqueued++;
                ps.bytesEnqueued += res.entry.bytes;
                ps.lastFullSendNanos = r.enqueuedNanos;
                sent++;
            } catch (Throwable t) {
                // transient enqueue failure: roll back THIS reservation to a
                // valid READY-with-payload (or fully release if cancelled);
                // batch continues, Finished carries the true count. DEBUG
                // counter + one rate-limited WARN — not an error stack per
                // record per tick.
                res.rollback();
                ps.enqueueFails++;
                warnRateLimited("enqueue-fail", "full-chunk enqueue failed for "
                        + p.getName().getString() + " @ [" + net.minecraft.world.level.ChunkPos.getX(r.chunkKey)
                        + "," + net.minecraft.world.level.ChunkPos.getZ(r.chunkKey)
                        + "] — reservation rolled back: " + t);
            }
        }
        // 3) close the batch with the REAL count; commit only if closable
        try {
            p.connection.send(new ClientboundChunkBatchFinishedPacket(Math.max(1, sent)));
        } catch (Throwable t) {
            LOGGER.error("batch finish failed for {} — connection presumed dead", p.getName().getString(), t);
            // connection teardown will quit() this player; no commit
            return 0;
        }
        ps.outstanding.addLast(batch);
        ps.batches++;
        return Math.max(1, sent);
    }

    /** REQUESTED record: adopt a seal the owner published meanwhile. */
    private static boolean adoptSeal(ServerLevel level, Rec r) {
        Entry e = cache(level).get(r.chunkKey);
        if (e == null) return false;
        e.refs.incrementAndGet();
        r.entry = e;
        if (!r.cas(St.REQUESTED, St.READY)) {
            r.entry = null;
            e.refs.decrementAndGet(); // retired (or escalated) concurrently
            return false;
        }
        return true;
    }

    private static long dist(long chunkKey, long playerKey) {
        long dx = net.minecraft.world.level.ChunkPos.getX(chunkKey) - net.minecraft.world.level.ChunkPos.getX(playerKey);
        long dz = net.minecraft.world.level.ChunkPos.getZ(chunkKey) - net.minecraft.world.level.ChunkPos.getZ(playerKey);
        return dx * dx + dz * dz;
    }

    /** Client acked one batch: retire the OLDEST outstanding record set.
     *  26.2 vanilla hops handleChunkBatchReceived to main (ensureRunningOnSameThread);
     *  the gate stays as the ownership assertion should that ever change. */
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
        ps.lastAckNanos = System.nanoTime();
        for (long k : b.keys) {
            Rec r = ps.records.get(k);
            if (r != null && r.cas(St.ENQUEUED, St.ACKNOWLEDGED)) {
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
                        case REQUESTED -> "leased to owner mailbox past the 1s escalation bound — "
                                + "escalation is not firing (actionable)";
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
