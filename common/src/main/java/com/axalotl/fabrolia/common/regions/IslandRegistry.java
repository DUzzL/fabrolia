package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A1 — persistent island identity + shadow clocks (async-islands stage 1).
 *
 * <p>The BFS islands produced by {@link RegionizedServer#rebuildIslandsIfDirty}
 * are EPHEMERAL: ids permute on every rebuild, so nothing about an island can
 * persist — no per-island logical clock, no merge/split transaction, no
 * ownership handoff. This registry gives islands STABLE identities across
 * rebuilds by diffing the new BFS result against the previous cell→island
 * assignment:
 *
 * <ul>
 *   <li>a new island whose cells all belonged to one prior island keeps that
 *       identity;</li>
 *   <li>cells from several prior islands in one new island = a MERGE — the
 *       prior island contributing the most cells survives (ties: lowest id),
 *       the others retire into it;</li>
 *   <li>a prior island whose cells land in several new islands = a SPLIT —
 *       the largest fragment keeps the identity, the rest are born fresh;</li>
 *   <li>cells never seen before join their island or found a new one.</li>
 * </ul>
 *
 * <p>SHADOW CLOCKS: every island carries the logical tick counter and
 * deadline accounting it WOULD have if it ticked independently at its own
 * measured cost (Σ member-region entity-phase EWMAs vs the 50 ms budget).
 * Stage 1 changes NOTHING observable — all islands still execute inside the
 * global tick — but the shadow data validates the deadline scheduler model
 * against real workloads and feeds {@code /fabrolia islands}. Later stages
 * (see docs/ASYNC-ISLANDS.md) swap the shadow advance for real per-island
 * drive; the merge/split events recorded here are exactly the transaction
 * boundaries that will need quiesce/transfer.
 *
 * <p>Threading: mutated only on the server thread at level-tick HEAD
 * (rebuild diff + shadow advance); read by the command on the same thread.
 * Self-checking: after every rebuild the registry's cell→id view must map
 * 1:1 onto the BFS result — a mismatch logs and rebuilds the registry from
 * scratch (identities reset, observable behavior unaffected).
 */
public final class IslandRegistry {

    static final java.util.concurrent.atomic.AtomicLong GEN_COUNTER =
            new java.util.concurrent.atomic.AtomicLong();

    /** Dimension tag for flight-recorder records — island ids are per-level
     *  and collide across dimensions (user report 2026-07-13: "island#1" was
     *  ambiguous). Set once by the owning RegionizedServer. */
    volatile String dim = "?";

    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-Islands");
    private static final long TICK_BUDGET_NANOS = 50_000_000L;

    /**
     * Allocation-free result of the main-side chunk-phase arm gate.  GLOBAL and
     * SKIP are process singletons; every island owns one stable token carrying
     * its exact identity.  A queued task retains that token across topology
     * replacement, so its terminal always closes the reservation on the island
     * which admitted it rather than whichever island owns the cell later.
     */
    public static final class ArmGate {
        private static final ArmGate GLOBAL = new ArmGate(null, false);
        private static final ArmGate SKIP = new ArmGate(null, true);

        private final PIsland owner;
        private final boolean skip;

        private ArmGate(PIsland owner, boolean skip) {
            this.owner = owner;
            this.skip = skip;
        }

        static ArmGate global() {
            return GLOBAL;
        }

        static ArmGate skip() {
            return SKIP;
        }

        public boolean isSkipped() {
            return skip;
        }

        public boolean isIslandArm() {
            return owner != null;
        }

        PIsland owner() {
            return owner;
        }
    }

    /** Persistent island state. */
    public static final class PIsland {
        public final long id;
        public final LongOpenHashSet cells = new LongOpenHashSet(8);
        /** Logical tick — shadow in sync mode, REAL once async is live (unit
         *  bookkeeping is the single writer then; volatile for command/API
         *  readers on other threads). */
        public volatile long logicalTick;
        /** Accumulated lag (nanos of deadline debt at its own cost). */
        public volatile long lagNanos;
        /** Last cost (shadow: Σ member EWMAs; async: measured unit wall time). */
        public volatile long costNanos;
        /** Main-only sort scratch: {@link #costNanos} frozen at the head of
         *  the deadline drive. Sorting directly on the volatile crashed the
         *  server (TimSort "Comparison method violates its general contract",
         *  2026-07-17): completing units rewrite costNanos concurrently with
         *  main's sort, so the comparator saw a key move mid-sort. */
        public long driveSortCost;
        public final long bornGameTime;
        public long lastEventGameTime;

        // ---- A2 real scheduler state (consumed by IslandTickUnit) ----
        /** This island's scheduling timeline — its epoch advances once per tick
         *  unit. Follows the persistent identity through merges/splits (survivor
         *  keeps its domain; fresh fragments start a fresh one); member regions
         *  are re-pointed by RegionizedServer's rebuild assignment pass. */
        final EpochDomain domain = new EpochDomain();
        /** BEs registered from THIS island's own unit execution (worker
         *  context, under LevelMixin's registration lock) — ticked by the
         *  unit's BE pass the same tick, exactly like vanilla's direct-list
         *  add during the entity phase. Guarded by its own monitor (cold). */
        final ArrayList<net.minecraft.world.level.block.entity.TickingBlockEntity> pendingUnitBes =
                new ArrayList<>(4);

        public record StampedEvent(net.minecraft.world.level.BlockEventData ev, long gen) {}

        private final ArrayList<StampedEvent> pendingStampedEvents = new ArrayList<>(8);
        final ArrayList<net.minecraft.world.level.BlockEventData> nextUnitEvents = new ArrayList<>(4);
        /** Dedup for the drive+lane duplicate race: a queued-but-not-started
         *  unit must not be enqueued again. The flag stays set through the
         *  task's claim attempt so opportunistic c2me incremental saves yield
         *  to pending simulation work. {@link IslandUnitAdmission} covers the
         *  second window — a task that started while its predecessor held the
         *  claim but reaches the claim only after that predecessor rolled L. */
        public int[] eventStashDepths() { // [stamped, nextUnit] — diagnostics
            synchronized (pendingBlockEvents) {
                return new int[]{pendingStampedEvents.size(), nextUnitEvents.size()};
            }
        }
        public final java.util.concurrent.atomic.AtomicBoolean submitQueued =
                new java.util.concurrent.atomic.AtomicBoolean();
        /**
         * Exact publication cut between the autonomous pacer and one global
         * {@code /tick sprint} frame. Every writer of {@link #submitQueued}
         * in {@link IslandTickUnit} holds this monitor. Main installs the
         * per-island sprint fence through this monitor; the server-wide fence
         * crosses this monitor once per island before trusting submitQueued.
         * Thus an old pacer observation lands wholly before the handshake (and
         * is drained) or wholly after it (and is refused).
         */
        final Object unitAdmissionLock = new Object();
        volatile boolean globalSprintFenced;
        volatile IslandTickUnit.GlobalSprintRequest globalSprintRequest;
        private final ArrayList<net.minecraft.world.level.BlockEventData> pendingBlockEvents =
                new ArrayList<>(4);

        void stashBlockEvent(net.minecraft.world.level.BlockEventData ev, long gen) {
            synchronized (pendingBlockEvents) {
                pendingStampedEvents.add(new StampedEvent(ev, gen));
            }
        }

        ArrayList<StampedEvent> drainDueEventsEntered(long unitL) {
            synchronized (pendingBlockEvents) {
                if (pendingStampedEvents.isEmpty()) return null;
                ArrayList<StampedEvent> out = new ArrayList<>(pendingStampedEvents);
                pendingStampedEvents.clear();
                return out;
            }
        }

        /** Retry stamp (informational only under the drain-all model). */
        long genForNextUnit(long unitL) {
            return unitL + 1;
        }

        void restashBlockEvent(net.minecraft.world.level.BlockEventData ev, long gen) {
            synchronized (pendingBlockEvents) {
                pendingStampedEvents.add(new StampedEvent(ev, gen));
            }
        }

        void stashNextUnitEvent(net.minecraft.world.level.BlockEventData ev) {
            synchronized (pendingBlockEvents) {
                nextUnitEvents.add(ev);
            }
        }

        ArrayList<net.minecraft.world.level.BlockEventData> drainNextUnitEventsEntered() {
            synchronized (pendingBlockEvents) {
                if (nextUnitEvents.isEmpty()) return null;
                ArrayList<net.minecraft.world.level.BlockEventData> out = new ArrayList<>(nextUnitEvents);
                nextUnitEvents.clear();
                return out;
            }
        }

        ArrayList<StampedEvent> takeAllStampedEvents(long nowGen) {
            synchronized (pendingBlockEvents) {
                ArrayList<StampedEvent> out = null;
                if (!pendingStampedEvents.isEmpty()) {
                    out = new ArrayList<>(pendingStampedEvents);
                    pendingStampedEvents.clear();
                }
                if (!nextUnitEvents.isEmpty()) {
                    if (out == null) out = new ArrayList<>();
                    for (net.minecraft.world.level.BlockEventData ev : nextUnitEvents) {
                        out.add(new StampedEvent(ev, nowGen));
                    }
                    nextUnitEvents.clear();
                }
                return out;
            }
        }

        void stashStamped(StampedEvent se) {
            synchronized (pendingBlockEvents) {
                pendingStampedEvents.add(se);
            }
        }

        ArrayList<net.minecraft.world.level.BlockEventData> drainPendingBlockEvents() {
            synchronized (pendingBlockEvents) {
                if (pendingStampedEvents.isEmpty()) return null;
                ArrayList<net.minecraft.world.level.BlockEventData> out =
                        new ArrayList<>(pendingStampedEvents.size());
                for (StampedEvent se : pendingStampedEvents) {
                    out.add(se.ev());
                }
                pendingStampedEvents.clear();
                return out;
            }
        }

        ArrayList<net.minecraft.world.level.BlockEventData> takeAllBlockEvents() {
            return drainPendingBlockEvents();
        }

        /** Coordinator, once per unit: swap out mid-unit registrations. */
        /** Coordinator-only: fresh BEs still waiting for MAIN's beCells fold
         *  (pendingBeAdds drains at MAIN cadence). An island running faster
         *  than main (per-island rate, sprint, /tick rate ramps) must keep
         *  ticking them island-side or their lifecycle stretches to main's
         *  pace — caravan at island rate 40 paced at 20; stricter engines
         *  broke one stroke in (2026-07-17). */
        final ArrayList<net.minecraft.world.level.block.entity.TickingBlockEntity> carryBes =
                new ArrayList<>(4);

        ArrayList<net.minecraft.world.level.block.entity.TickingBlockEntity> drainPendingUnitBes() {
            synchronized (pendingUnitBes) {
                if (pendingUnitBes.isEmpty()) return null;
                ArrayList<net.minecraft.world.level.block.entity.TickingBlockEntity> out =
                        new ArrayList<>(pendingUnitBes);
                pendingUnitBes.clear();
                return out;
            }
        }

        /** Chunk-packet snapshot requests (2026-07-14 sender redesign): keys of
         *  chunks whose full packet the sender wants. The unit drains these at
         *  end-of-unit — everything is coherent under the unit claim, so packet
         *  construction needs NO per-packet claim acquisition (the old design
         *  parked MAIN behind island units per send: a flying real client left
         *  main at ~30ms until disconnect). Deduped via the companion set. */
        final java.util.concurrent.ConcurrentLinkedQueue<Long> pendingChunkSnapshots =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        final java.util.Set<Long> pendingSnapshotKeys =
                java.util.concurrent.ConcurrentHashMap.newKeySet();

        /** Main-side claim spins announce interest here so rate/sprint
         *  chaining yields (2026-07-14 fence-starvation fix). Every writer
         *  saves and restores it around one synchronous spin (LIFO scopes).
         *  Yield decisions must OR it with {@link #statusClaimWanted}. */
        public volatile boolean claimWanted;
        /** Second, independent interest flag raised and lowered ONLY by the
         *  entity-status tick-head drain (escalation after repeated deferrals,
         *  see PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions).
         *  Never saved/restored by anyone, so no mainClaim scope can strand
         *  it; read wherever {@link #claimWanted} decides a yield. */
        public volatile boolean statusClaimWanted;
        /** Diagnostics: units launched by chaining/pacer since creation. */
        public volatile long chainedUnits;

        /** Monotonic creation generation — ids are reused, gen never is. */
        public final long gen = GEN_COUNTER.incrementAndGet();
        /** Operator alias (optional; survives with this identity through
         *  rebuilds; on split it stays with the identity-keeping fragment). */
        public volatile String alias;
        public volatile String labelCache;
        public volatile long labelCacheNanos;
        /** Set when a rebuild removes this identity (merged away, split-replaced,
         *  or emptied). A retired object must never be dispatched again: the
         *  pacer, chaining, and submitUnit all check it — its cells belong to
         *  OTHER islands now and dispatching would double-drive them. */
        public volatile boolean retired;
        /** Unit-claim ledger: who took unitClaim and when (retained-claim
         *  forensics — the 2026-07-14 silent fence freezes; extended for the
         *  2026-07-23 all-workers-idle production wedges, task #25). Set on
         *  every successful acquire via {@link #noteClaim}, cleared on release
         *  via {@link #clearClaim} — never written any other way, so the
         *  stuck reports can name the exact acquisition site, the holder
         *  thread (and whether it is still alive), and the wall-clock
         *  acquisition time. */
        public volatile String claimSite;
        public volatile long claimSiteNanos;
        public volatile Thread claimHolder;
        public volatile long claimWallMillis;

        /** Record the successful acquisition of {@link #unitClaim} by the
         *  CURRENT thread. Must be the first statement after every winning
         *  CAS; pure field writes — cannot throw. */
        public void noteClaim(String site) {
            claimSite = site;
            claimSiteNanos = System.nanoTime();
            claimWallMillis = System.currentTimeMillis();
            claimHolder = Thread.currentThread();
        }

        /** Clear the ledger; call immediately BEFORE {@code unitClaim.set(false)}
         *  so no reader ever sees a released flag with stale holder data. */
        public void clearClaim() {
            claimSite = null;
            claimHolder = null;
            claimSiteNanos = 0;
        }

        /** One-line human report of the current ledger for the stuck logs and
         *  {@code regions debug claims}: site, holder identity + liveness +
         *  thread state, age, wall acquisition time. Reads are racy by design
         *  (forensics of a possibly-corrupt state must not synchronize). */
        public String claimReport() {
            String site = claimSite;
            Thread h = claimHolder;
            long acqN = claimSiteNanos;
            long wall = claimWallMillis;
            StringBuilder sb = new StringBuilder(96);
            sb.append("flag=").append(unitClaim.get())
              .append(" site='").append(site).append('\'');
            if (h == null) {
                sb.append(" holder=<none-recorded>");
            } else {
                sb.append(" holder='").append(h.getName())
                  .append("' alive=").append(h.isAlive())
                  .append(" state=").append(h.getState());
            }
            sb.append(" ageMs=").append(acqN == 0 ? -1 : (System.nanoTime() - acqN) / 1_000_000L)
              .append(" acquiredAt=").append(wall == 0 ? "-" : java.time.Instant.ofEpochMilli(wall).toString());
            return sb.toString();
        }

        /** Holder's LIVE stack (what it is doing NOW — an idle worker here is
         *  the proof of a leaked release), bounded frames. Empty when no
         *  holder is recorded or the thread is gone. */
        public String holderLiveStack(int maxFrames) {
            Thread h = claimHolder;
            if (h == null || !h.isAlive()) {
                return "<no live holder thread>";
            }
            StackTraceElement[] st = h.getStackTrace();
            StringBuilder sb = new StringBuilder(256);
            for (int i = 0; i < st.length && i < maxFrames; i++) {
                sb.append("\n      at ").append(st[i]);
            }
            return sb.length() == 0 ? "<no frames>" : sb.toString();
        }
        /** Pacing generation: bumped by every temporal-session change affecting
         *  this island. complete() publishes appliedPacingGen so commands can
         *  confirm the running unit observed the new pacing at a unit boundary. */
        public volatile long pacingGen;
        public volatile long appliedPacingGen;
        /** Task #43 — cross-dimension cadence coupling (CrossDimLinks): when
         *  set, {@code IslandTickUnit.complete()} rolls the deadline at the
         *  coupling's shared rate instead of this island's own target, so
         *  linked Overworld↔Nether members advance in drift-free lockstep.
         *  Written on main only (link manager); null = uncoupled. */
        public volatile CrossDimLinks.Coupling crossDimCoupling;
        /** Frontier halo cells owned by this island for WRITE-DOMAIN purposes
         *  (1-cell closure around the populated core). Regions here carry the
         *  island's domain so vanilla edge mutations (fluid spread, fire, piston
         *  heads) stay inside one timeline. Empty halo cells contribute no tick
         *  work; a newly FULL halo supplies scheduled work only with its own
         *  complete owned write halo, or after rebuild adopts it as core.
         *  Rebuilt every topology pass on main. */
        public final LongOpenHashSet haloCells = new LongOpenHashSet();
        /** Cross-dimension arrival cohorts routed to this island; published
         *  whole at the unit head (monitor-guarded, rebuild-carried). */
        public final java.util.ArrayList<CrossDimArrivals.StagedOp> pendingArrivals =
                new java.util.ArrayList<>(4);

        // Profiler metrics (single-writer: the unit coordinator).
        public volatile long emaUnitNanos;
        public volatile long maxUnitNanos;
        public volatile long unitsCompleted;
        // Reader-side TPS window state (main-thread command/HUD readers only).
        // Cached so one reader never consumes/reset the sample for another.
        final TpsWindow tpsWindow = new TpsWindow(TpsWindow.ONE_SECOND_NANOS, 0.0);

        /** task #19 arrival spacing chain: {lastSourceTick, lastDeliveryL, valid}.
         *  Guarded by the pendingArrivals monitor. */
        /** Per-source-clock-domain delivery chains (task #20): domain key →
         *  {lastSourceTick, lastDestTick}. Guarded by the pendingArrivals
         *  monitor. Cleared when the queue drains (idle re-anchor). */
        public final java.util.HashMap<Long, long[]> arrivalChains = new java.util.HashMap<>(4);

        /** Next tick deadline (nanos, System.nanoTime domain); 0 = unscheduled. */
        public volatile long nextDeadlineNanos;
        /** Unit claim: true while this island's tick unit executes or topology/
         *  save operations hold the island quiesced. One unit at a time, ever. */
        public final java.util.concurrent.atomic.AtomicBoolean unitClaim =
                new java.util.concurrent.atomic.AtomicBoolean();
        /** Non-Main claim admission for one autonomous unit/body. */
        public boolean tryClaimAutonomousWork() {
            synchronized (unitAdmissionLock) {
                return !NetherPortalTransfers.blocksAutonomousUnit(this)
                        && unitClaim.compareAndSet(false, true);
            }
        }
        /** Real missed-deadline count once async is live (diagnostics). */
        public volatile long missedDeadlines;
        /** A2 deadline drive, arm gate: game time of the last chunk-phase arm
         *  targeting this island (main-written), and the arm the last unit
         *  consumed (coordinator-written). Gate: arm again only same-tick
         *  (second phase of one tick) or once the previous arm was consumed —
         *  a lagging island MISSES spawn/random cycles exactly like a lagging
         *  vanilla server instead of accumulating them. */
        /** Ticket-purge progress memo (main-only; TicketStorageMixin): the
         *  island's L at the last purge pass + whether it advanced — expiry
         *  decrements only on island-tick progress, so timeouts count the
         *  SAME clock the renewing machine runs on. */
        public long purgeSeenL;
        public long purgeSeenWall;
        public boolean purgeAdvanced = true;
        public volatile long armStampGameTime;
        public volatile long armConsumedGameTime;
        /**
         * Exact gate-1 reservation accounting.  Queue admission can land after
         * an entity-dispatch snapshot; until every task admitted for the arm has
         * reached a run/cancel terminal, a unit must not consume the stamp and a
         * later physical game-time must not arm another chunk cycle.  All three
         * fields are authoritative only under {@link #armReservationLock}.
         */
        final Object armReservationLock = new Object();
        int armReservations;
        /**
         * Monotonic arm debt. A same-gameTime phase may arrive after a unit has
         * already consumed an earlier cohort with the same stamp, so the stamp
         * pair alone cannot distinguish "still consumed" from "owed again".
         * Every gate-1 admission advances the reservation generation; only a
         * successful unit commit with no live tasks catches consumption up.
         */
        long armReservationGeneration;
        long armConsumedGeneration;
        private final ArmGate armGate = new ArmGate(this, false);
        /** Island-dispatched work (tick-tag functions and navigation updates),
         *  serviced only at this island's cadence under its coordinator claim. */
        public final java.util.ArrayList<Runnable> pendingIslandTasks = new java.util.ArrayList<>(2);
        /** Candidate island-L whose cadence-bound unit-head tasks were last
         *  applied. A fail-closed scheduled-tick HOLD retries the same L; the
         *  stamp prevents wall-dispatched tick functions from running twice
         *  in that one logical tick while preserving command-before-scheduled
         *  semantics on the first attempt. Coordinator-only under unitClaim. */
        public long unitHeadAppliedL = Long.MIN_VALUE;
        /** Candidate island-L of the current fail-closed scheduled-tick HOLD
         *  streak and how many consecutive unit attempts held at it. Detection
         *  only ({@link IslandScheduledTicks#SCHEDULED_HOLD_STUCK_STREAK}): a
         *  readiness hole that never heals (2026-09-27: deferred entity status
         *  kept areEntitiesLoaded false for minutes) is reported loudly with
         *  its refused leg, while the unit keeps holding until the cause is
         *  fixed. Coordinator-only under unitClaim. */
        public long scheduledHoldL = Long.MIN_VALUE;
        public int scheduledHoldStreak;
        /** Coalescing keys for pendingIslandTasks (per-function dedup): a
         *  slow island runs a tick function once per ITS tick — missed
         *  server-tick dispatches drop, they never queue up into bursts
         *  (same semantics as the chunk-work arm gate). Same monitor. */
        public final java.util.HashSet<Object> pendingIslandTaskKeys = new java.util.HashSet<>(2);

        /** Raids assigned to this island by the main-side Raids.tick dispatch
         *  (RaidIslandDispatch): ticked ONCE PER UNIT at the vanilla raid
         *  slot — 1:1 with this island's scheduled ticks, independent of
         *  main's rate (sprint/catch-up safe). Concurrent identity set:
         *  main adds, the coordinator reads/removes at the slot. */
        public final java.util.Set<net.minecraft.world.entity.raid.Raid> assignedRaids =
                java.util.concurrent.ConcurrentHashMap.newKeySet();

        /** A5 persistent player owners currently mapped to this island. */
        final java.util.Set<AsyncIslandPlayerTicks.PlayerState> playerStates =
                java.util.concurrent.ConcurrentHashMap.newKeySet();
        /** Real-player early halves awaiting their listener/tickPlayer tail. */
        final java.util.concurrent.ConcurrentLinkedQueue<AsyncIslandPlayerTicks.PendingLate>
                pendingPlayerLate = new java.util.concurrent.ConcurrentLinkedQueue<>();

        /**
         * Chunk holders dirtied by this island since its previous publication
         * slot. The same holder also remains in ServerChunkCache's global set;
         * the two drains arbitrate with remove-under-holder-lock, so exactly one
         * publisher wins and a concurrent change is never erased by clear().
         */
        public final java.util.Set<net.minecraft.server.level.ChunkHolder>
                pendingBlockUpdates = java.util.concurrent.ConcurrentHashMap.newKeySet();

        /** Persistent entity trackers whose sendChanges clock is this island's L. */
        public final java.util.Set<IslandTrackedEntityAccess> pendingEntityUpdates =
                new com.axalotl.fabrolia.common.parallelised.utils.ConcurrentIdentitySet<>();

        /**
         * Serial lane for ItemEntity ticks in this island. Item merging takes
         * stable identity monitors {@code self -> candidate}; without one
         * outer lane two item ticks could acquire {@code A -> B} and
         * {@code B -> A}. Distinct live islands are separated beyond vanilla
         * item merge reach, so they retain independent lanes.
         */
        public final Object itemTickLane = new Object();

        /** A8: this island's coordinator is PARKED waiting for a routed
         *  command result (IslandCommands). While true the island is exactly
         *  as quiescent as a fence-claimed one — command fences treat it as
         *  safe without taking the claim; topology/save spins instead drain
         *  the routed batch so the waiter can resume and finish its unit. */
        public volatile boolean waitingOnRoutedCommand;
        /** Diagnostics ONLY: artificial per-unit cost injected after the
         *  entity barrier (/fabrolia regions islanddebug <id> <ms>) — a
         *  deterministic overload for scheduler differentials (TPS slip,
         *  deadline behavior) without entity-load noise. 0 = off. */
        public volatile long debugCostNanos;

        /** Owning level (set by RegionizedServer at domain assignment; the
         *  detector resolves rs from a coordinator context through it). */
        volatile net.minecraft.server.level.ServerLevel level;

        public net.minecraft.server.level.ServerLevel domainLevel() {
            return level;
        }

        PIsland(long id, long bornGameTime) {
            this.id = id;
            this.bornGameTime = bornGameTime;
            this.lastEventGameTime = bornGameTime;
            // L anchored to game time at birth: absolute L-lane values already
            // in memory or re-anchored from disk (vanilla saves scheduled ticks
            // and cooldowns RELATIVE) stay coherent when the island's timeline
            // takes over getGameTime for its members. A healthy island keeps
            // L == gameTime; only real overruns open a gap (per-island TPS).
            this.logicalTick = bornGameTime;
            this.domain.owner = this;
        }

        /** Main-side arm decision for one target region at {@code gameTime}. */
        ArmGate reserveChunkArm(long gameTime) {
            synchronized (armReservationLock) {
                // Several per-region/per-phase tasks from one physical level
                // tick share the stamp and are all admitted independently.
                if (armStampGameTime == gameTime) {
                    armReservations++;
                    armReservationGeneration++;
                    return armGate;
                }
                // A later wall tick may not stack another chunk cycle over an
                // unconsumed stamp or over a late task retained for the next unit.
                if (armReservations != 0
                        || armReservationGeneration != armConsumedGeneration
                        || armStampGameTime != armConsumedGameTime) {
                    return ArmGate.skip();
                }
                armStampGameTime = gameTime;
                armReservations++;
                armReservationGeneration++;
                return armGate;
            }
        }

        /** Run/cancel terminal for exactly one accepted gate-1 task. */
        void finishChunkArm() {
            synchronized (armReservationLock) {
                if (armReservations <= 0) {
                    throw new IllegalStateException(
                            "island chunk-arm reservation completed twice or without admission");
                }
                armReservations--;
            }
        }

        /**
         * Unit success publication.  A task admitted after the dispatch snapshot
         * remains represented by {@code armReservations}; leave the stamp owed so
         * main cannot publish the next physical cycle before that task drains.
         */
        void consumeChunkArmIfQuiescent() {
            synchronized (armReservationLock) {
                if (armReservations == 0) {
                    armConsumedGameTime = armStampGameTime;
                    armConsumedGeneration = armReservationGeneration;
                }
            }
        }

        int chunkArmReservations() {
            synchronized (armReservationLock) {
                return armReservations;
            }
        }

        /** Shadow effective TPS at current cost (20 when under budget). */
        public double shadowTps() {
            long c = Math.max(1L, costNanos);
            return c <= TICK_BUDGET_NANOS ? 20.0 : Math.min(20.0, 1.0e9 / c);
        }
    }

    /** Two LongIterators in sequence (unit member collection: core + halo). */
    public static final class DualLongIterator implements it.unimi.dsi.fastutil.longs.LongIterator {
        private final it.unimi.dsi.fastutil.longs.LongIterator a;
        private final it.unimi.dsi.fastutil.longs.LongIterator b;

        public DualLongIterator(it.unimi.dsi.fastutil.longs.LongIterator a,
                                it.unimi.dsi.fastutil.longs.LongIterator b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public boolean hasNext() {
            return a.hasNext() || b.hasNext();
        }

        @Override
        public long nextLong() {
            return a.hasNext() ? a.nextLong() : b.nextLong();
        }
    }

    /** Merge/split/born/retire event for diagnostics + (later) transactions. */
    public record IslandEvent(long gameTime, String type, long survivorId,
                              long[] otherIds, int cells, String reason) {}

    private long nextId = 1;
    private final Long2LongOpenHashMap cellToIsland = new Long2LongOpenHashMap(256);
    { cellToIsland.defaultReturnValue(-1L); }
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<PIsland> islands =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private final ArrayDeque<IslandEvent> events = new ArrayDeque<>(128);
    private long lastAdvanceGameTime = Long.MIN_VALUE;

    public java.util.Collection<PIsland> islands() { return islands.values(); }

    /** Structurally immutable identities for foreign ownership probes. Published
     * only after a rebuild commits; claim fields remain live on each identity. */
    private volatile List<PIsland> identitySnapshot = List.of();

    public List<PIsland> identitySnapshot() { return identitySnapshot; }

    public List<IslandEvent> recentEvents() { return new ArrayList<>(events); }

    /** A2 thread-safety: immutable cell→island snapshot, republished after
     *  every rebuild (transition ticks only, a few hundred entries). byCell is
     *  on the mod-facing API surface (FabroliaIslandApi) and the deferral/save
     *  routing paths, all of which may run off-main — reading the live maps
     *  mid-rebuild would tear. Null until the first rebuild (main-only boot
     *  window: fall back to the live view). */
    private volatile it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<PIsland> cellSnapshot;

    public PIsland byCell(long cellKey) {
        var snap = cellSnapshot;
        if (snap != null) {
            return snap.get(cellKey);
        }
        long id = cellToIsland.get(cellKey);
        return id < 0 ? null : islands.get(id);
    }

    private void logEvent(long gameTime, String type, long survivor, long[] others, int cells, String reason) {
        StringBuilder lbl = new StringBuilder();
        PIsland sv = islands.get(survivor);
        lbl.append(sv != null && sv.labelCache != null ? sv.labelCache : ("island#" + survivor));
        for (long o : others) {
            PIsland po = islands.get(o);
            lbl.append(" + ").append(po != null && po.labelCache != null ? po.labelCache : ("island#" + o));
        }
        FlightRecorder.record("TOPO " + dim + " " + type + " [" + lbl + "] island#" + survivor + " others="
                + java.util.Arrays.toString(others) + " cells=" + cells + " gt=" + gameTime + " (" + reason + ")");
        // Topology changed: force label recompute on next display (rebuild-time
        // only — never the unit hot path). The ring record above keeps the OLD
        // composition; the next list/HUD line shows the new one.
        if (sv != null) sv.labelCache = null;
        for (long o : others) {
            PIsland po = islands.get(o);
            if (po != null) po.labelCache = null;
        }
        if (events.size() >= 128) events.pollFirst();
        events.addLast(new IslandEvent(gameTime, type, survivor, others, cells, reason));
    }

    /**
     * Main thread, called from rebuildIslandsIfDirty with the fresh BFS result.
     * Diffs against the previous assignment, emits merge/split/born/retire
     * events, keeps identities stable, then verifies 1:1 coverage.
     */
    public void applyRebuild(List<RegionizedServer.Island> bfsIslands, long gameTime) {
        ArrayList<LongArrayList> cellSets = new ArrayList<>(bfsIslands.size());
        for (RegionizedServer.Island b : bfsIslands) {
            LongArrayList cells = new LongArrayList(b.members().size());
            for (int m = 0; m < b.members().size(); m++) cells.add(b.members().get(m).gridKey);
            cellSets.add(cells);
        }
        applyRebuildCells(cellSets, gameTime);
    }

    /** Pure core (no MC types): unit/fuzz-testable transaction spine. */
    public void applyRebuildCells(List<LongArrayList> bfsIslands, long gameTime) {
        // --- pass 1: for each BFS island, count prior identities among its cells
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<LongOpenHashSet> newCellsById =
                new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        long[] chosen = new long[bfsIslands.size()];
        it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap fragmentsPerPrior = new Long2LongOpenHashMap();
        for (int i = 0; i < bfsIslands.size(); i++) {
            LongArrayList bfs = bfsIslands.get(i);
            Long2LongOpenHashMap votes = new Long2LongOpenHashMap();
            votes.defaultReturnValue(0);
            LongOpenHashSet cells = new LongOpenHashSet(bfs.size());
            for (int m = 0; m < bfs.size(); m++) {
                long cell = bfs.getLong(m);
                cells.add(cell);
                long prior = cellToIsland.get(cell);
                if (prior >= 0) votes.addTo(prior, 1);
            }
            long best = -1, bestCount = -1;
            LongArrayList contributors = new LongArrayList(2);
            for (var e : votes.long2LongEntrySet()) {
                contributors.add(e.getLongKey());
                if (e.getLongValue() > bestCount
                        || (e.getLongValue() == bestCount && e.getLongKey() < best)) {
                    best = e.getLongKey(); bestCount = e.getLongValue();
                }
            }
            if (best < 0) {
                best = nextId++;
                islands.put(best, new PIsland(best, gameTime));
                logEvent(gameTime, "BORN", best, new long[0], cells.size(), "new populated area");
            } else if (contributors.size() > 1) {
                contributors.rem(best);
                long[] merged = contributors.toLongArray();
                PIsland survivor = islands.get(best);
                for (long o : merged) {
                    PIsland gone = islands.get(o);
                    if (gone != null && survivor != null) {
                        // MERGE-CLOCK FIX (2026-07-13 field incident): the
                        // survivor KEEPS ITS OWN logical clock. The old rule
                        // (max(survivor, gone)) teleported a lagged survivor's
                        // clock forward WITHOUT any domain change for its own
                        // regions — assignDomain's trigger rebase never ran, so
                        // every pending scheduled tick on the surviving island
                        // became (jump) ticks overdue and fired in ONE phase:
                        // every loaded machine on the island broke at once.
                        // Incident shape: generation load lags the machine
                        // island; the player's loaded corridor into fresh
                        // terrain (new islands are BORN at raw game time)
                        // merges into it; the survivor jumped by the lag.
                        // Time-reversal for the ABSORBED side is handled by
                        // assignDomain, which translates its pending triggers
                        // onto the survivor's clock exactly (newTrigger =
                        // trigger + Lsurvivor − Lgone) — negative deltas are
                        // fine: relative delays are preserved either way.
                        FlightRecorder.record("MERGE-CLOCK " + dim + " survivor#" + survivor.id
                                + " L=" + survivor.logicalTick + " keeps clock; absorbed#"
                                + gone.id + " L=" + gone.logicalTick
                                + " delta=" + (survivor.logicalTick - gone.logicalTick));
                    }
                }
                logEvent(gameTime, "MERGE", best, merged, cells.size(), "cell adjacency (≤2-cell gap closed)");
                PIsland s = islands.get(best);
                if (s != null) s.lastEventGameTime = gameTime;
            }
            chosen[i] = best;
            newCellsById.put(best, cells);
            fragmentsPerPrior.addTo(best, 0); // touch
            for (var e : votes.long2LongEntrySet()) fragmentsPerPrior.addTo(e.getLongKey(), 0);
        }
        // --- duplicate winners = a prior identity claimed by several BFS islands (split)
        Long2LongOpenHashMap claimCount = new Long2LongOpenHashMap();
        claimCount.defaultReturnValue(0);
        for (long c : chosen) claimCount.addTo(c, 1);
        for (int i = 0; i < chosen.length; i++) {
            if (claimCount.get(chosen[i]) > 1) {
                // keep the identity on the LARGEST fragment (ties: first index)
                int keeper = -1, keeperSize = -1;
                for (int j = 0; j < chosen.length; j++) {
                    if (chosen[j] == chosen[i]) {
                        int size = bfsIslands.get(j).size();
                        if (size > keeperSize) { keeper = j; keeperSize = size; }
                    }
                }
                long prior = chosen[i];
                LongArrayList fresh = new LongArrayList(2);
                for (int j = 0; j < chosen.length; j++) {
                    if (chosen[j] == prior && j != keeper) {
                        long id = nextId++;
                        PIsland p = new PIsland(id, gameTime);
                        PIsland src = islands.get(prior);
                        if (src != null) { // fragments inherit the source clock
                            p.logicalTick = src.logicalTick;
                            p.lagNanos = src.lagNanos;
                            // A split can land between a fail-closed HOLD and
                            // its same-L retry. Carry the unit-head stamp with
                            // the inherited clock so the fresh fragment cannot
                            // replay a cadence task already applied at this L.
                            p.unitHeadAppliedL = src.unitHeadAppliedL;
                            // audit10: and its tick identity, so entities migrated into
                            // this fresh fragment keep their already-ticked state for the
                            // current epoch instead of ticking a second time.
                            p.domain.adoptTickIdentityFrom(src.domain);
                        }
                        islands.put(id, p);
                        LongOpenHashSet cells = newCellsById.remove(prior);
                        // re-associate: recompute cells for this fragment
                        LongOpenHashSet frag = new LongOpenHashSet();
                        for (int m = 0; m < bfsIslands.get(j).size(); m++) {
                            frag.add(bfsIslands.get(j).getLong(m));
                        }
                        newCellsById.put(id, frag);
                        if (cells != null) newCellsById.put(prior, cells);
                        chosen[j] = id;
                        fresh.add(id);
                    }
                }
                if (!fresh.isEmpty()) {
                    logEvent(gameTime, "SPLIT", prior, fresh.toLongArray(),
                            keeperSize, "connectivity lost (gap ≥3 cells)");
                    PIsland s = islands.get(prior);
                    if (s != null) s.lastEventGameTime = gameTime;
                }
            }
        }
        // --- commit: rebuild cell map, update member sets, retire vanished ids
        cellToIsland.clear();
        LongOpenHashSet live = new LongOpenHashSet(chosen.length);
        for (int i = 0; i < chosen.length; i++) {
            long id = chosen[i];
            live.add(id);
            PIsland p = islands.get(id);
            if (p == null) { // defensive — should not happen
                p = new PIsland(id, gameTime);
                islands.put(id, p);
            }
            p.cells.clear();
            for (int m = 0; m < bfsIslands.get(i).size(); m++) {
                long cell = bfsIslands.get(i).getLong(m);
                p.cells.add(cell);
                cellToIsland.put(cell, id);
            }
        }
        var it = islands.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            var e = it.next();
            if (!live.contains(e.getLongKey())) {
                logEvent(gameTime, "RETIRE", e.getLongKey(), new long[0],
                        e.getValue().cells.size(), "no populated cells left");
                synchronized (e.getValue().pendingIslandTasks) {
                    e.getValue().retired = true;
                }
                it.remove();
            }
        }
        // --- self-check: 1:1 coverage vs BFS
        int bfsCells = 0;
        for (LongArrayList b : bfsIslands) bfsCells += b.size();
        if (cellToIsland.size() != bfsCells || islands.size() != bfsIslands.size()) {
            IllegalStateException failure = new IllegalStateException(
                    "island registry coverage drift: cells " + cellToIsland.size()
                            + " != " + bfsCells + " or islands " + islands.size()
                            + " != " + bfsIslands.size());
            IslandCommands.publishAndPropagate(failure);
        }
        // --- publish the immutable off-main view (see byCell)
        var snap = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<PIsland>(
                cellToIsland.size() * 2);
        for (var e : cellToIsland.long2LongEntrySet()) {
            PIsland p = islands.get(e.getLongValue());
            if (p != null) snap.put(e.getLongKey(), p);
        }
        cellSnapshot = snap;
        identitySnapshot = List.copyOf(islands.values());
    }

    /**
     * Main thread, level tick head: advance shadow clocks one global tick.
     * Cost estimate = Σ member-region entity-phase EWMAs (the measured
     * long-pole feed). Under budget → the island keeps pace (logical tick
     * tracks global); over budget → it accrues lag exactly as an independent
     * deadline scheduler would, and its shadow TPS drops below 20.
     */
    public void advanceShadow(RegionizedServer rs, long gameTime) {
        if (gameTime == lastAdvanceGameTime) return;
        lastAdvanceGameTime = gameTime;
        for (PIsland p : islands.values()) {
            long cost = 0;
            var itc = p.cells.iterator();
            while (itc.hasNext()) {
                Region r = rs.regionizer().peekByGridKey(itc.nextLong());
                if (r != null) cost += r.emaPhaseNanos;
            }
            p.costNanos = cost;
            if (cost <= TICK_BUDGET_NANOS) {
                // catches up at most one tick per global tick (no time travel)
                p.logicalTick++;
                p.lagNanos = Math.max(0, p.lagNanos - (TICK_BUDGET_NANOS - cost));
            } else {
                p.lagNanos += cost - TICK_BUDGET_NANOS;
                if (p.lagNanos >= cost) {
                    p.lagNanos -= cost; // deadline slipped: this global tick, no logical advance
                } else {
                    p.logicalTick++;
                }
            }
        }
    }
}
