package com.axalotl.fabrolia.common.parallelised.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PortalTeleportationManager {

    private static final Logger LOGGER = LogManager.getLogger("Fabrolia-Portal");

    private static volatile MinecraftServer server;
    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    private static final Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final Queue<Runnable> PENDING = new ConcurrentLinkedQueue<>();

    /** Throttle for the (expected-never) snapshot-torn canary in executeSnapshot. */
    private static final java.util.concurrent.atomic.AtomicLong LAST_TORN_LOG =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.LongAdder TORN_SUPPRESSED =
            new java.util.concurrent.atomic.LongAdder();

    private PortalTeleportationManager() {}

    public static void init(MinecraftServer mcServer) {
        if (initialized.compareAndSet(false, true)) {
            server = mcServer;
            LOGGER.info("Portal teleportation manager initialized");
        }
    }

    public static void shutdown() {
        if (initialized.compareAndSet(true, false)) {
            server = null;
            // RegionScheduler.stop() drains PENDING before the arrival flush, so
            // this should be empty. If it is not, transfers ARE being lost — say
            // so loudly rather than clearing in silence as this used to.
            int dropped = PENDING.size();
            if (dropped > 0) {
                LOGGER.error("Portal teleportation manager shutting down with {} UNRESOLVED "
                        + "transfer(s) — those entities stay in their source level with portal "
                        + "cooldown armed and may be trapped on reload", dropped);
            }
            IN_FLIGHT.clear();
            PENDING.clear();
            // These retain ServerLevel references; leaving them populated pins the
            // whole level graph across an in-JVM server restart (single-player
            // quit -> rejoin), since init() re-arms via compareAndSet.
            PENDING_SOURCE.clear();
            PORTAL_ACTIVE.clear();
            STUCK_LOGGED.clear();
            LOGGER.info("Portal teleportation manager shut down");
        }
    }

    public static boolean submit(Entity entity, Portal portal, BlockPos entryPos, ServerLevel sourceLevel) {
        MinecraftServer srv = sourceLevel.getServer();
        if (srv == null || Thread.currentThread() == srv.getRunningThread()) {
            // task #26 same-portal-twice guard: a worker submission may already
            // be queued for this entity (region ownership handed to the global
            // lane mid-window). The QUEUED transfer owns the entity — a second
            // inline execution here would process the portal twice.
            if (IN_FLIGHT.contains(entity.getUUID())) {
                return true; // consumed: the queued transfer resolves it
            }
            // On main: vanilla-style synchronous teleport, no snapshot needed —
            // entity position hasn't been mutated since handlePortal fired.
            executeInline(entity, portal, entryPos, sourceLevel);
            return true;
        }

        final UUID id = entity.getUUID();
        if (!IN_FLIGHT.add(id)) {
            return false;
        }
        // task #19 causality: the teleport is now a MAIN-owned dependency.
        // (a) The entity stops participating immediately — vanilla removes it
        // from the source the same tick portal-time fires; letting it keep
        // ticking while queued produced source-side double-motion and, under
        // main backlog, whole extra source epochs of history. tickOne skips
        // portal-pending entities; the flag clears when main resolves it.
        // (b) Capture the SOURCE SIMULATION EPOCH here: the deferred stage
        // runs in MAIN context, which stamped every burst member with the
        // same wall tick — CrossDimArrivals' spacing then saw delta=0 and
        // delivered logically-distinct transfers into one destination epoch
        // (the 50-bot stacked-sand cohort: two FBEs placing at y=49 AND y=50
        // in one epoch). The carried epoch restores the true causal stamp.
        ((com.axalotl.fabrolia.common.ParallelProcessor.TickGuard) entity).fabrolia$setPortalPending(true);
        // task #26 (xfer12): from here to publishDeferred the entity is FROZEN
        // (portalPending suppresses its ticks) but no task exists yet to unfreeze
        // it. A throw anywhere in this window — clock/domain resolution, trace
        // formatting, an allocation failure — used to escape leaving
        // portalPending=true and the UUID stuck in IN_FLIGHT forever: the entity
        // never ticks again AND every future transfer for it dedups away. That
        // is a permanent freeze indistinguishable from the bug we are fixing.
        // Unwind the whole reservation on any failure and let the entity retry.
        try {
            final long sourceEpoch;
            final long sourceDomain;
            {
                long ctx = com.axalotl.fabrolia.common.regions.IslandClock.contextTime();
                if (ctx >= 0) {
                    sourceEpoch = ctx;
                    sourceDomain = com.axalotl.fabrolia.common.regions.CrossDimArrivals.currentDomainKey();
                } else {
                    sourceEpoch = com.axalotl.fabrolia.common.regions.IslandClock.wallTime(sourceLevel);
                    sourceDomain = 0L;
                }
            }

            // Snapshot at submit time — the pose at PORTAL-TIME, which is what
            // vanilla teleports from. It still matters even though the source is
            // now frozen for the rest of its tick and the transfer is invisible
            // to main until that tick returns: the entity may sit queued across
            // main ticks, and the inline/abort paths restore from it.
            // portal.getPortalDestination reads entity.getX/Y/Z for
            // the dimension-scale calculation (overworld×8 ↔ nether), so without
            // a snapshot the destination coords reflect drift instead of the
            // portal-entry pose. For chunk-loader designs that fling pumpkin
            // drops through a nether portal at an anchor, the drift lands the
            // item meters away from the anchor — observed 2026-06-13 by user.
            final Vec3 snapPos = entity.position();
            final Vec3 snapMotion = entity.getDeltaMovement();
            final float snapYRot = entity.getYRot();
            final float snapXRot = entity.getXRot();

            // task #26 transaction pin: while the handoff is queued the entity is
            // FROZEN in the source world (portal-pending) — its chunk must not be
            // unloaded out from under the transaction. The lease re-arm walks this
            // registry each main tick alongside the destination leases.
            PENDING_SOURCE.put(id, new PendingSrc(sourceLevel,
                    net.minecraft.world.level.ChunkPos.pack(entryPos)));
            publishDeferred(entity, () -> {
                com.axalotl.fabrolia.common.regions.CrossDimArrivals.CARRIED_SOURCE
                        .set(new long[]{sourceEpoch, sourceDomain});
                try {
                    executeSnapshot(entity, portal, entryPos, sourceLevel,
                            snapPos, snapMotion, snapYRot, snapXRot);
                } catch (Throwable t) {
                    LOGGER.error("Deferred portal teleport failed for entity {}", id, t);
                    makeRetryable(entity);
                } finally {
                    com.axalotl.fabrolia.common.regions.CrossDimArrivals.CARRIED_SOURCE.remove();
                    ((com.axalotl.fabrolia.common.ParallelProcessor.TickGuard) entity).fabrolia$setPortalPending(false);
                    PENDING_SOURCE.remove(id);
                    IN_FLIGHT.remove(id);
                }
            });
        } catch (Throwable t) {
            // Reservation failed before the task existed — release everything we
            // took so the entity resumes ticking and can retry next tick.
            ((com.axalotl.fabrolia.common.ParallelProcessor.TickGuard) entity)
                    .fabrolia$setPortalPending(false);
            PENDING_SOURCE.remove(id);
            IN_FLIGHT.remove(id);
            makeRetryable(entity);
            LOGGER.error("Portal transfer submission failed for {} — reservation released, "
                    + "entity will retry", id, t);
            return false;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // task #26 (xfer12) TRANSFER-VISIBILITY BARRIER — the structural fix.
    //
    // submit() is called from deep inside the SOURCE entity's tick
    // (AbstractMinecart.tick -> handlePortal -> submit), on an island worker.
    // Under async islands main does NOT await island units — RegionizedServer
    // .dispatchOwnedEntityTicks explicitly dispatches them and returns only the
    // global-lane future ("do NOT await them — each unit's own coordinator is
    // its barrier"), and ServerLevelMixin then calls onEntityTickBatchEnd ->
    // drainPending() on MAIN. So the instant this task became visible in
    // PENDING, main could run the WHOLE teleport concurrently with the rest of
    // that worker tick:
    //
    //   worker: ... handlePortal() -> submit() [task visible] -> return
    //           -> behavior.tick()        <-- applyGravity + Entity.move: writes position
    //           -> updateFluidInteraction()  <-- fluid push: writes deltaMovement
    //   main:   drainPending() -> executeSnapshot()
    //           -> setPos(snapPos) ... getPortalDestination() reads entity.getY()
    //
    // NetherPortalBlock.getPortalDestination reads the LIVE entity pose
    // (verified 26.2: `entity.getX() * scale, entity.getY(), entity.getZ()`,
    // then the exit offset within the portal rectangle), so a worker write
    // landing between main's setPos and that read silently produces a
    // destination Y off by the amount the source drifted. For a rail cart the
    // drift is exactly the rail offset — OldMinecartBehavior parks a riding
    // cart at `floor(y) + 0.0625`, and coming off track drops it to the block
    // top — which is precisely the operator's failing yChain
    // (snapPos=131.0625 -> preDestCalc=131.0 -> arrival 116.0 instead of
    // 116.0625). Off the rail on arrival, OldMinecartBehavior.tick applies
    // gravity BEFORE the rail check, so the cart falls and portal contact
    // re-arms its cooldown forever: the trapped loader.
    //
    // Freezing the source (xfer11) chased the writers one at a time and can
    // never be proven exhaustive — behavior.tick() is only the first of them.
    // The fix instead removes the CONCURRENCY: the task is held thread-locally
    // and published to PENDING only once the worker's outermost entity tick has
    // RETURNED (ParallelProcessor.tickEntity's finally). Main therefore cannot
    // observe the transfer until every write that tick could make has happened
    // — no writer can race the destination calc, by construction, whether or
    // not we know its name. Submissions from outside an entity tick publish
    // immediately (there is no tick to wait for).
    // ------------------------------------------------------------------

    private static final TransferVisibilityBarrier BARRIER =
            new TransferVisibilityBarrier(PENDING);

    /** True iff {@code entity} submitted a portal transfer earlier in the tick
     *  frame currently executing on this thread — i.e. vanilla would already
     *  have teleported and removed it, and nothing further in this tick should
     *  move it. Unlike the portalPending flag (which MAIN clears when it
     *  resolves the transfer, possibly before this worker reaches its next
     *  statement) this cannot be un-set underneath a running tick. */
    public static boolean hasDepartedThisTick(Object entity) {
        return BARRIER.hasDeparted(entity);
    }

    private static void publishDeferred(Entity entity, Runnable task) {
        BARRIER.submit(entity, task,
                com.axalotl.fabrolia.common.ParallelProcessor.IN_ENTITY_TICK.get());
    }

    /**
     * Publish transfers submitted during the entity tick that just finished on
     * this thread. Called from {@link com.axalotl.fabrolia.common.ParallelProcessor#tickEntity}'s
     * {@code finally} at the OUTERMOST tick boundary only, so it also runs when
     * the tick threw. Until this point main cannot see the transfer at all.
     */
    public static void publishTickLocalTransfers() {
        BARRIER.publishStaged();
    }

    // ------------------------------------------------------------------
    // audit11 — PER-REGION-CLAIM publication frames.
    //
    // Root cause of the split-activation failures: a source portal
    // activation's transfers entered PENDING one per entity, as each
    // entity's tick returned. Main's fixed 20 Hz drain could therefore
    // BISECT one activation into two sealed batches that publish at
    // different destination epochs (for falling blocks, a publication
    // spacing of 1..6 destination epochs breaks the 7-tick fall).
    // Global sprint never split precisely because main awaits every
    // island unit BEFORE the drain runs — PENDING is always complete at
    // drain time. These frames replicate that property at normal rates:
    // while a region worker holds a region claim, every transfer its
    // entity phase stages accumulates thread-locally and publishes ONCE,
    // in the region task's finally at claim release — one queue element
    // (see TransferVisibilityBarrier.publishStaged), atomic against the
    // drain. Purely causal: no timing, no waiting, no pacing.
    //
    // Scope: opened only by the region-claim task bodies
    // (RegionizedServer.processGroup and RegionWorkerPool.workOnce's
    // claim path) — always on RegionWorkerThreads. Main and the
    // global/flat-pool lanes never open a frame and keep the per-entity
    // cadence; they cannot split (main pumps regionAll to completion
    // before its drain). Dense-region HELP batches run on threads that
    // hold no claim, so they also keep the per-entity cadence rather
    // than stranding their stage until that thread's next claim.
    // ------------------------------------------------------------------

    private static final ThreadLocal<int[]> REGION_CLAIM_FRAME_DEPTH =
            ThreadLocal.withInitial(() -> new int[1]);

    /** Open a region-claim publication frame on this thread. MUST be paired
     *  with {@link #endRegionClaimFrame()} in a finally. */
    public static void beginRegionClaimFrame() {
        REGION_CLAIM_FRAME_DEPTH.get()[0]++;
    }

    /** Close the frame and, at the outermost close, publish everything the
     *  claim's entity phase staged — one atomic queue element. Runs from the
     *  region task's finally, so a throwing phase still publishes. */
    public static void endRegionClaimFrame() {
        int[] depth = REGION_CLAIM_FRAME_DEPTH.get();
        if (depth[0] > 0 && --depth[0] == 0) {
            BARRIER.publishStaged();
        }
    }

    /** True while a region-claim publication frame is open on this thread —
     *  tickEntity's finally then leaves the stage to the frame's close. */
    public static boolean inRegionClaimFrame() {
        return REGION_CLAIM_FRAME_DEPTH.get()[0] > 0;
    }

    private record PendingSrc(ServerLevel level, long chunkKey) {}

    private static final ConcurrentHashMap<UUID, PendingSrc> PENDING_SOURCE = new ConcurrentHashMap<>();

    /** task #26: SOURCE chunks of queued handoffs for the per-tick lease
     *  re-arm — both transaction endpoints stay pinned while in flight. */
    public static void forEachPendingSourceChunk(ServerLevel level, java.util.function.LongConsumer sink) {
        for (PendingSrc s : PENDING_SOURCE.values()) {
            if (s.level == level) {
                sink.accept(s.chunkKey);
            }
        }
    }

    /**
     * task #26 async-island portal-loader fix (2026-07-24): the chunk of every
     * PORTAL-ACTIVE entity — one inside a portal (portalProcess set) or still on
     * portal cooldown after a recent crossing — recorded on the entity's own
     * (island-clock) tick, so the MAIN-thread lease re-arm can hold that chunk
     * loaded on WALL cadence.
     *
     * <p>Why this matters under async ISLANDS specifically: the destination
     * chunk of a portal loader is held only by the PORTAL ticket the last
     * crossing placed (300 MAIN ticks ≈ 15 s wall). Renewal happens on the next
     * crossing — which is produced by the loader ISLAND'S tick, not the wall
     * clock. When that island is scheduling-starved (many islands, few workers)
     * or otherwise runs below wall rate, the wall-timed ticket can expire before
     * the island produces the next crossing: the chunk unloads, the cart is
     * serialized mid-cycle and, on the reload a later visit forces, reappears at
     * rest inside the portal where vanilla's cooldown re-arm traps it. With
     * async islands OFF the whole level ticks once per main tick, so this rate
     * gap cannot open — matching the operator's async-on-breaks / async-off-works
     * discriminator. The wall-paced re-arm restores vanilla's guarantee that a
     * portal chunk stays loaded while an entity is actively using it, decoupled
     * from the owning island's tick rate. Records self-expire once the entity
     * stops being portal-active. */
    private record ActiveChunk(ServerLevel level, long chunkKey, long firstNanos, long lastNanos) {}
    private static final ConcurrentHashMap<UUID, ActiveChunk> PORTAL_ACTIVE = new ConcurrentHashMap<>();
    private static final java.util.Set<UUID> STUCK_LOGGED = ConcurrentHashMap.newKeySet();
    private static final long PORTAL_ACTIVE_TTL_NANOS = 3_000_000_000L;
    /** A normal crossing clears cooldown in ~10 ticks; continuously portal-active
     *  for 15 s (3× the PORTAL ticket life) is unambiguously a stuck/trapped cart
     *  — auto-capture threshold. */
    private static final long PORTAL_STUCK_NANOS = 15_000_000_000L;

    /** Called from the handlePortal wrap (owning worker) each tick the entity is
     *  inside a portal or on portal cooldown. Preserves firstNanos so the
     *  main-thread scan can measure how long it has been continuously active. */
    public static void notePortalActive(Entity entity, ServerLevel level) {
        long now = System.nanoTime();
        long chunkKey = entity.chunkPosition().pack();
        PORTAL_ACTIVE.compute(entity.getUUID(), (u, prev) ->
                new ActiveChunk(level, chunkKey, prev == null ? now : prev.firstNanos, now));
    }

    /** Main-thread lease re-arm: portal-active chunks for this level, pruning
     *  records the entity stopped refreshing (left the portal, cooldown ended),
     *  and AUTO-CAPTURING any entity stuck portal-active far past a normal
     *  crossing — the operator's field failure logs itself, no command needed. */
    public static void forEachPortalActiveChunk(ServerLevel level, java.util.function.LongConsumer sink) {
        long now = System.nanoTime();
        var it = PORTAL_ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            var en = it.next();
            ActiveChunk a = en.getValue();
            if (now - a.lastNanos > PORTAL_ACTIVE_TTL_NANOS) {
                it.remove();
                STUCK_LOGGED.remove(en.getKey()); // recovered → re-arm auto-capture
                continue;
            }
            if (a.level == level) {
                sink.accept(a.chunkKey);
                // Auto-capture only a REAL stuck episode (at rest / out of lookup),
                // not a healthy cart that is merely continuously portal-active.
                // Mark logged only if it actually fired, so a later genuine trap of
                // the same entity is still captured.
                if (now - a.firstNanos > PORTAL_STUCK_NANOS && !STUCK_LOGGED.contains(en.getKey())) {
                    if (com.axalotl.fabrolia.common.regions.PortalStuckProbe
                            .autoCaptureIfStuck(level, en.getKey(),
                                    (now - a.firstNanos) / 1_000_000_000L)) {
                        STUCK_LOGGED.add(en.getKey());
                    }
                }
            }
        }
    }

    public static int pendingCount() {
        return PENDING.size();
    }

    /**
     * Per-level teardown (dynamic dimension unload). PENDING_SOURCE and
     * PORTAL_ACTIVE both hold a ServerLevel inside their value records and were
     * cleared only at {@link #shutdown()}, so unloading a level while the server
     * kept running retained that level — and everything it references — for the
     * rest of the process. Any transfer still queued FROM this level resolves
     * itself safely (executeSnapshot aborts on isRemoved / level mismatch), but
     * it is worth saying so rather than dropping the registrations silently.
     */
    public static void onLevelUnload(ServerLevel level) {
        int pending = 0;
        for (var en : PENDING_SOURCE.entrySet()) {
            if (en.getValue().level() == level) {
                PENDING_SOURCE.remove(en.getKey());
                pending++;
            }
        }
        for (var en : PORTAL_ACTIVE.entrySet()) {
            if (en.getValue().level() == level) {
                PORTAL_ACTIVE.remove(en.getKey());
                STUCK_LOGGED.remove(en.getKey());
            }
        }
        if (pending > 0) {
            LOGGER.warn("Level {} unloaded with {} portal transfer(s) still queued from it; "
                            + "those transfers will abort when main drains them (their source "
                            + "level is gone)",
                    level.dimension().identifier(), pending);
        }
    }

    /** Wall-clock of the last unconditional PORTAL_ACTIVE prune. */
    private static final java.util.concurrent.atomic.AtomicLong LAST_ACTIVE_PRUNE =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * task #26 (xfer12): PORTAL_ACTIVE / STUCK_LOGGED are written unconditionally
     * by the handlePortal wrap for every entity in a portal or on cooldown, but
     * were pruned ONLY inside forEachPortalActiveChunk — whose only caller,
     * CrossDimArrivals.rearmPendingLeases, early-returns unless {@code asyncActive}.
     * With region threading off, or regions on but async islands off, the map grew
     * without bound and every entry strongly retained a ServerLevel. Prune on a
     * wall cadence from the (always-run) main drain instead, so lifetime no longer
     * depends on which scheduler mode happens to be enabled.
     */
    private static void pruneStalePortalActive() {
        long now = System.nanoTime();
        long last = LAST_ACTIVE_PRUNE.get();
        if (now - last < 1_000_000_000L || !LAST_ACTIVE_PRUNE.compareAndSet(last, now)) return;
        var it = PORTAL_ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            var en = it.next();
            if (now - en.getValue().lastNanos() > PORTAL_ACTIVE_TTL_NANOS) {
                it.remove();
                STUCK_LOGGED.remove(en.getKey());
            }
        }
    }

    public static void drainPending() {
        pruneStalePortalActive();
        Runnable task;
        while ((task = PENDING.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                LOGGER.error("Portal teleport drain error", t);
            }
        }
    }

    /**
     * Make an entity whose transfer failed for a NON-VANILLA reason able to try
     * again. handlePortal armed the cooldown before submitting; vanilla
     * {@code Entity.setAsInsidePortal} then re-arms that cooldown every tick the
     * entity remains inside the portal and never mints a fresh PortalProcessor
     * while it is non-zero — so "it will simply cross on a later tick" is FALSE
     * for anything sitting in a portal, which is precisely the portal-loader
     * case. Clearing the cooldown lets the next setAsInsidePortal take the
     * else-branch and mint a new processor.
     *
     * <p>Deliberately NOT applied to the three vanilla-equivalent aborts
     * (null-transition, blocked, canTeleport-false): vanilla leaves the cooldown
     * armed there and we must stay faithful.
     */
    private static void makeRetryable(Entity entity) {
        try {
            entity.setPortalCooldown(0);
        } catch (Throwable ignored) {
            // never let recovery bookkeeping mask the original failure
        }
    }

    /** Same-thread (main) entry — no drift to compensate for. */
    private static void executeInline(Entity entity, Portal portal, BlockPos entryPos, ServerLevel sourceLevel) {
        if (entity.isRemoved()) {
            return;
        }
        TeleportTransition transition = portal.getPortalDestination(sourceLevel, entity, entryPos);
        if (transition == null) {
            LOGGER.warn("Portal teleport failed: null transition for entity {} at {}",
                    entity.getId(), entryPos);
            return;
        }
        finishTeleport(entity, sourceLevel, transition);
    }

    /**
     * Deferred-on-main entry. Restore the entity to its submit-time pose so
     * getPortalDestination's dimension-scale read of entity.getX/Y/Z matches
     * vanilla's "teleport at the tick portal-time fires" semantics. The
     * teleport(transition) call overwrites pos with destination coords, so
     * the restored snapshot is purely input fixup for the destination calc.
     * On any path that does NOT teleport (null transition, blocked, etc.)
     * we put the entity back where main found it — never lose ticks of
     * motion to a failed teleport.
     */
    private static void executeSnapshot(Entity entity, Portal portal, BlockPos entryPos,
                                        ServerLevel sourceLevel,
                                        Vec3 snapPos, Vec3 snapMotion,
                                        float snapYRot, float snapXRot) {
        // task #26 transaction validity: the queued transfer is only valid
        // while the entity is STILL IN THE SOURCE LEVEL it was submitted from.
        // Removed (unload/kill/despawn while queued) → abort, entity persists
        // in the source save — loud, never silent. Moved to another level by
        // any other path (/tp, another portal processed inline) → this stale
        // transfer must NOT re-teleport it through the old portal (the
        // "same portal twice" class).
        if (entity.isRemoved() || entity.level() != sourceLevel) {
            String why = entity.isRemoved() ? "removed-while-queued" : "left-source-while-queued";
            LOGGER.warn("Deferred portal teleport ABORTED ({}) for {} ({}): removal={} level={} submittedFrom={}",
                    why, entity.getUUID(), entity.getType().toString(),
                    entity.getRemovalReason(), entity.level().dimension().identifier(),
                    sourceLevel.dimension().identifier());
            return;
        }

        final Vec3 curPos = entity.position();
        final Vec3 curMotion = entity.getDeltaMovement();
        final float curYRot = entity.getYRot();
        final float curXRot = entity.getXRot();

        // Apply snapshot for the destination computation.
        entity.setPos(snapPos.x, snapPos.y, snapPos.z);
        entity.setDeltaMovement(snapMotion);
        entity.setYRot(snapYRot);
        entity.setXRot(snapXRot);

        boolean teleported = false;
        try {
            // task #26 (xfer12) SNAPSHOT INTEGRITY CANARY. With the visibility
            // barrier in place no worker can still be writing this entity, so
            // the pose we just wrote must be the pose we read back. If it is
            // not, some writer we have not accounted for is live, and
            // getPortalDestination would compute the destination from a corrupt
            // pose and silently land the entity off-target (the trapped-cart
            // class). Fail the transfer instead: the entity keeps its
            // portalProcess and simply crosses on a later tick. Always on —
            // three double compares, no I/O, timing-neutral.
            Vec3 applied = entity.position();
            if (applied.x != snapPos.x || applied.y != snapPos.y || applied.z != snapPos.z) {
                long now = System.nanoTime();
                long last = LAST_TORN_LOG.get();
                if (now - last > 10_000_000_000L && LAST_TORN_LOG.compareAndSet(last, now)) {
                    LOGGER.error("Deferred portal teleport ABORTED (snapshot-torn) for {} ({}): "
                                    + "wrote {} but read back {} — a concurrent writer is still "
                                    + "mutating the source entity; transfer deferred to a later "
                                    + "tick ({} suppressed in the last 10s)",
                            entity.getUUID(), entity.getType().toString(), snapPos, applied,
                            TORN_SUPPRESSED.sumThenReset());
                } else {
                    TORN_SUPPRESSED.increment();
                }
                makeRetryable(entity);
                return;
            }
            TeleportTransition transition = portal.getPortalDestination(sourceLevel, entity, entryPos);
            if (transition == null) {
                LOGGER.warn("Portal teleport failed: null transition for entity {} at {}",
                        entity.getId(), entryPos);
                return;
            }
            teleported = finishTeleport(entity, sourceLevel, transition);
        } finally {
            // Did not teleport → restore main's view of the entity. Teleported
            // → finishTeleport already wrote destination coords; leave alone.
            if (!teleported && !entity.isRemoved()) {
                entity.setPos(curPos.x, curPos.y, curPos.z);
                entity.setDeltaMovement(curMotion);
                entity.setYRot(curYRot);
                entity.setXRot(curXRot);
            }
        }
    }

    /** Returns true iff entity.teleport(transition) was actually called. */
    private static boolean finishTeleport(Entity entity, ServerLevel sourceLevel, TeleportTransition transition) {
        ServerLevel newLevel = transition.newLevel();
        if (!sourceLevel.isAllowedToEnterPortal(newLevel)) {
            LOGGER.warn("Portal teleport blocked: not allowed to enter {} for entity {}",
                    newLevel.dimension().identifier(), entity.getId());
            return false;
        }
        if (newLevel.dimension() == sourceLevel.dimension() || entity.canTeleport(sourceLevel, newLevel)) {
            entity.teleport(transition);
            return true;
        }
        LOGGER.warn("Portal teleport blocked: canTeleport returned false for entity {} ({} -> {})",
                entity.getId(), sourceLevel.dimension().identifier(), newLevel.dimension().identifier());
        return false;
    }
}
