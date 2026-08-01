package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.LongIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;

/**
 * A2 — one island's independent tick unit, executed as a COORDINATOR task on a
 * region worker (docs/ASYNC-ISLANDS.md; the a2 checkpoint tracks increments).
 *
 * <p>Per unit, all under the island's claim, all on the island's own
 * {@link EpochDomain} timeline (its epoch, its barrier, its entity tick token,
 * its L-clock via the getGameTime redirect):
 *
 * <ol>
 *   <li><b>entity phase</b> — member-region dispatch through the exact R5
 *       event-scheduler machinery (ColorGate eligibility, worker claims, owed
 *       phases, barrier contributions); region queues drain inside, so the
 *       spawn/random/block-tick tasks the chunk phase armed are covered. The
 *       coordinator help-pumps the pool while waiting (it runs anything except
 *       other units — see RegionWorkerPool.workOnce), so units never idle a
 *       worker slot.</li>
 *   <li><b>deferred cross-region replay</b> — effects that crossed member
 *       region borders during the phase, routed here by target; replayed
 *       single-threaded with every member region quiescent — the exact
 *       island-internal ordering the old main replay gave.</li>
 *   <li><b>BE phase</b> — the island's cells from the persistent BE index,
 *       ascending key order, plus BEs registered mid-unit (vanilla ticks
 *       those the same tick via its direct-list add). Hoppers & co. read the
 *       island's L from getGameTime.</li>
 *   <li><b>bookkeeping</b> — L++, real measured cost, deadline roll (overrun
 *       reschedules from completion: only this island slips below 20 TPS),
 *       claim release.</li>
 * </ol>
 *
 * <p>Increments 3–4 move block events and scheduled block/fluid ticks (then
 * spawn/random collection) into the sequence; the deadline drive then selects
 * WHICH islands run instead of running all every level tick.
 */
public final class IslandTickUnit {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-A2");
    static final long TICK_NANOS = 50_000_000L;

    private IslandTickUnit() {}

    /** True when enough of the unit exists for {@code asyncIslands} to be
     *  enabled (gates the /fabrolia regions async toggle). */
    public static boolean unitImplemented() {
        return true;
    }

    /**
     * Submit one tick unit for {@code island} to the pool's unit lane.
     * Returned future completes when the unit finishes (or was skipped because
     * a topology/save operation holds the claim) — it NEVER completes
     * exceptionally; failures are contained and logged so the driving pump
     * cannot wedge.
     */
    public static CompletableFuture<Void> submitUnit(RegionizedServer rs, IslandRegistry.PIsland island) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        // /tick freeze parity (2026-07-22): vanilla's frozen tick runs NO
        // simulation (tickTime, LevelTicks, runBlockEvents, tickChunks and the
        // BE pass are all runsNormally-gated), but island units self-drive on
        // real-time deadlines — a frozen server kept ticking islands at full
        // speed (probe: L 315->434 across a 6 s freeze while gt held 318;
        // observer pulses ran against runsNormally-frozen moving-piston BEs
        // and the oscillator wedged — the production flying-machine stop).
        // runsNormally() is the exact per-tick vanilla flag: false while
        // frozen, true during /tick step frames and sprint frames — so this
        // one choke point covers the deadline drive, coherent pin, local
        // sprint chaining, the rate pacer and the manual kick command alike.
        // Deadlines stay due through the freeze; the first post-unfreeze
        // drive resumes every island immediately. Nothing consumed or lost.
        var srv = RegionScheduler.getServer();
        if (srv != null && !srv.tickRateManager().runsNormally()) {
            done.complete(null);
            return done;
        }
        // DUPLICATE-QUEUE GUARD (2026-07-16 audit follow-up: the reset at the
        // top of runCoordinated existed but this CAS never landed — the guard
        // was dead): a queued-but-unstarted unit must not be enqueued again
        // (unbounded lane + free claim until the task starts → two back-to-
        // back units after pool backlog; via the BE pending/fold gap that
        // stretched a fresh moving-piston lifecycle by one unit).
        if (!island.submitQueued.compareAndSet(false, true)) {
            done.complete(null);
            return done;
        }
        RegionScheduler.enqueueUnit(() -> runCoordinated(rs, island, done));
        return done;
    }

    /** Rig fault injection (task #25, {@code regions debug failunittail <n>}):
     *  the next n units throw at the head of the finally TAIL — exercises the
     *  contained-tail + unskippable-release chain end to end. Default 0;
     *  never set outside explicit operator/rig use. */
    static volatile int debugFailTail;

    public static void setDebugFailTail(int n) {
        debugFailTail = n;
    }

    private static void runCoordinated(RegionizedServer rs, IslandRegistry.PIsland island,
                                       CompletableFuture<Void> done) {
        try {
            island.submitQueued.set(false); // task started: next submit may queue
            if (island.retired) {
                return; // rebuilt away — its cells are other islands' now
            }
            if (!island.unitClaim.compareAndSet(false, true)) {
                // An idle worker may hold the claim for ~µs of shard servicing
                // (RegionScheduler.serviceOneIslandLane) — spin it out briefly
                // so that race never costs the island a tick. A REAL hold
                // (topology/save/waiter) exceeds the budget → next drive.
                long spinUntil = System.nanoTime() + 200_000L;
                boolean claimed = false;
                while (System.nanoTime() < spinUntil) {
                    if (island.unitClaim.compareAndSet(false, true)) {
                        claimed = true;
                        break;
                    }
                    Thread.onSpinWait();
                }
                if (!claimed) {
                    return; // quiesced by topology/save — the next drive retries
                }
            }
            island.noteClaim("unit@" + Thread.currentThread().getName());
            long start = System.nanoTime();
            final long lAtStart = island.logicalTick;
            try {
                EpochDomain d = island.domain;
                // L-clock publication: everything executing under this domain
                // reads island time L+1 from getGameTime for this unit's
                // duration (vanilla's increment-at-head convention). Volatile
                // write ordered before the barrier arm/seed in the dispatch.
                d.unitGameTime = island.logicalTick + 1;

                RegionWorkerThread me = (RegionWorkerThread) Thread.currentThread();
                me.currentDomain = d;
                try {
                    // A8 Phase 2: incoming command shards apply FIRST — a
                    // defined point (before scheduled ticks), on the owner
                    // thread, at this island's cadence.
                    d.handlingTickPhase = true; // vanilla phases 0-6 window
                    IslandBlockEvents.beginUnitCapture(island);
                    CommandShards.serviceShards(rs, island);
                    CommandShards.serviceIslandTasks(island);
                    // Cross-dimension arrival cohorts: publish WHOLE batches
                    // at the unit head, before scheduled ticks and the entity
                    // phase — every member becomes tickable at this same
                    // boundary (vanilla: arrivals precede the destination's
                    // entire tick).
                    CrossDimArrivals.publishIsland(island);
                    // Scheduled block/fluid ticks collected at THIS island's
                    // time — a lagging island's repeaters keep its own cadence.
                    // First segment: vanilla order [scheduled → events →
                    // entities → BEs] island-relative.
                    IslandScheduledTicks.runIslandScheduled(rs, d, d.unitGameTime);
                    // Block events routed to this island by the main slot's
                    // partition — BEFORE the entity phase.
                    IslandBlockEvents.runIslandEvents(rs, island, d);
                    IslandBlockEvents.endUnitCapture(rs.level);

                    ArrayList<Region> members = new ArrayList<>(island.cells.size());
                    d.handlingTickPhase = false; // vanilla flips before entities
                    d.epoch++;
                    LongIterator it = island.cells.iterator();
                    while (it.hasNext()) {
                        long cellKey = it.nextLong();
                        Region r = rs.regionizer().peekByGridKey(cellKey);
                        if (r != null && r.state() == RegionState.ACTIVE && r.domain == d) {
                            members.add(r);
                        } else if (r != null && r.domain == d) {
                            // Same-domain but not dispatchable (state flipped
                            // since rebuild): stamp done so same-domain
                            // neighbors' color eligibility can't wait on it.
                            r.doneEpoch = d.epoch;
                        } else if (r != null && r.state() == RegionState.ACTIVE) {
                            // STALE-DOMAIN HEAL (task #26 — reproduced portal-cart
                            // inert arrival, 2026-07-23). This loop iterates THE
                            // ISLAND'S OWN core cells: a mismatched (or null)
                            // domain pointer on an ACTIVE region here is a
                            // rebuild-race artifact, not foreign ownership — and
                            // the old policy of "leave it to the global lane"
                            // abandoned it to NOBODY (main's remainder lane
                            // filters domain != global; the 2026-07-15 halo-parity
                            // class, resurfacing for core cells). Every entity in
                            // the region — including a freshly PUBLISHED cross-
                            // dimension arrival — froze until the next topology
                            // change: the production minecart stuck at the portal
                            // boundary until a player visit. Heal the pointer
                            // under the unit claim (rebuilds quiesce this unit;
                            // core-cell ownership is exclusive) and dispatch
                            // normally. Scheduled-tick clock tags self-heal at
                            // consume via the task-#31 lane enforcement; entity
                            // tick-token dedup is domain-prefix-unique, so no
                            // cross-domain token collision is possible.
                            FlightRecorder.record("STALE-DOMAIN-HEAL island#" + island.id
                                    + "/g" + island.gen + " cell=" + Long.toHexString(cellKey)
                                    + " oldDomain=" + (r.domain == null ? "null"
                                            : (r.domain.owner == null ? "global"
                                            : "island#" + r.domain.owner.id + "/g" + r.domain.owner.gen))
                                    + " entities=" + r.entities.size());
                            r.domain = d;
                            members.add(r);
                        }
                    }
                    // HALO PARTITION PARITY (2026-07-15, Folia V2 verdict —
                    // "the partition function for entities must equal the
                    // partition function for block events"): a halo-domain
                    // region's entities were ticked by NOBODY (not island
                    // membership, filtered out of main's remainder lane by
                    // domain != global) — a rebuild that flickered a machine
                    // cell to halo froze its falling blocks mid-air
                    // (rig storm: 72 falls, 0 portal contacts). Halo regions
                    // WITH work join the phase as members; empty ones keep
                    // the done-stamp (FRONTIER HALO epoch discipline,
                    // 2026-07-14 watchdogs: an unstamped same-domain halo
                    // neighbor blocks every core edge region's ColorGate
                    // eligibility forever).
                    LongIterator hstamp = island.haloCells.iterator();
                    while (hstamp.hasNext()) {
                        Region hr = rs.regionizer().peekByGridKey(hstamp.nextLong());
                        if (hr == null || hr.domain != d) {
                            continue;
                        }
                        if (hr.state() == RegionState.ACTIVE
                                && (!hr.entities.isEmpty() || !hr.queue().isEmpty())) {
                            members.add(hr); // ticks in THIS island's phase
                        } else {
                            hr.doneEpoch = d.epoch;
                        }
                    }
                    CompletableFuture<Void> barrier = rs.dispatchEntityPhase(d, members);

                    // Help-pump: run pool work (any island's region phases,
                    // help batches, global tasks — never another unit) until
                    // our own barrier falls. Never blocks while work exists.
                    // Note: a claim taken inside workOnce publishes ITS region
                    // (currentRegion wins over currentDomain in IslandClock),
                    // so helping another island's phase reads THAT island's L.
                    int idle = 0;
                    // Bounded on pool liveness: a barrier stranded across a
                    // shutdown must not spin this coordinator forever (the
                    // save-all quiesce means no unit is normally in flight by
                    // pool stop; this is the belt for abnormal teardown).
                    long idleSinceNanos = 0;
                    while (!barrier.isDone() && RegionScheduler.poolRunning()) {
                        if (RegionScheduler.workerHelpOnce()) {
                            idle = 0;
                            idleSinceNanos = 0;
                            continue;
                        }
                        // Idle with our barrier pending: run the domain-scoped
                        // liveness backstop (main's heal no longer covers us —
                        // see RegionizedServer.healIslandBarrier).
                        if (++idle >= 4) {
                            idle = 0;
                            rs.healIslandBarrier(members, d);
                        }
                        if (idleSinceNanos == 0) {
                            idleSinceNanos = System.nanoTime();
                        } else if (System.nanoTime() - idleSinceNanos > 3_000_000_000L
                                   && !anyMemberClaimHeld(island, d, members)) {
                            // LEAKED-CONTRIBUTION CONTAINMENT (2026-07-14 forensics
                            // 23:18: unit@worker held island#4 40s+, every worker
                            // idle, barrier pending — an ACTIVE member's phase task
                            // provably no longer exists anywhere in the pool, so
                            // its contribution is LEAKED, not late; waiting longer
                            // freezes main's next fence forever (watchdog). Dump
                            // the exact member state for the root-cause hunt, then
                            // force-complete: liveness of the whole server over one
                            // lost entity sub-tick (its work replays next unit via
                            // the deferred queues). Loud, never normal-path.
                            StringBuilder fb = new StringBuilder(256);
                            fb.append("BARRIER-LEAK island#").append(island.id)
                              .append(" L=").append(island.logicalTick)
                              .append(" pending=").append(d.entityPending.get())
                              .append(" epoch=").append(d.epoch).append(" members:");
                            for (int mi = 0; mi < members.size(); mi++) {
                                Region m = members.get(mi);
                                fb.append(" [").append(Long.toHexString(m.gridKey))
                                  .append(" grouped=").append(m.groupedEpoch == d.epoch ? "Y" : "n")
                                  .append(" done=").append(m.doneEpoch == d.epoch ? "Y" : "n")
                                  .append(" q=").append(m.queue().isEmpty() ? 0 : 1)
                                  .append(m.helpQueue.isEmpty() ? "" : " h+")
                                  .append(']');
                            }
                            FlightRecorder.record(fb.toString());
                            FlightRecorder.dump("island#" + island.id
                                    + " entity barrier leaked a contribution — forced completion (state above)");
                            LOGGER.error("[async-islands] {} — forced barrier completion after 3s of "
                                    + "provably-empty pool; report the BARRIER-LEAK record", fb);
                            barrier.complete(null);
                            break;
                        }
                        LockSupport.parkNanos(20_000L);
                    }
                    long debugCost = island.debugCostNanos;
                    if (debugCost > 0) {
                        // Diagnostics knob: park in a loop — pool wake permits
                        // land on this worker thread and return parkNanos
                        // early (the 2026-07-12 a2lagk run measured ~50ms of
                        // an injected 120ms), which would understate the
                        // simulated overload.
                        long until = System.nanoTime() + debugCost;
                        long left;
                        while ((left = until - System.nanoTime()) > 0) {
                            LockSupport.parkNanos(left);
                        }
                    }
                    // FRONTIER HALO drain (2026-07-14): halo regions carry
                    // this island's domain for edge-write legality but are
                    // NOT entity-phase members (feeding empty frontier
                    // regions into the barrier accounting deadlocked the
                    // pump — watchdog dump 04:38, main in mainClaim vs
                    // coordinator parked on a barrier that could never
                    // fall). Their routed task/help queues drain HERE, under
                    // the unit claim, with the region published for write
                    // attribution. Normally both queues are empty.
                    LongIterator hit = island.haloCells.iterator();
                    while (hit.hasNext()) {
                        Region hr = rs.regionizer().peekByGridKey(hit.nextLong());
                        if (hr == null || hr.state() != RegionState.ACTIVE || hr.domain != d) {
                            continue;
                        }
                        if (hr.queue().isEmpty() && hr.helpQueue.isEmpty()) {
                            continue;
                        }
                        if (!hr.tryClaim(Thread.currentThread())) {
                            continue; // helper mid-drain — it owns the queue
                        }
                        Region prevR = me.currentRegion;
                        me.currentRegion = hr;
                        try {
                            Runnable ht;
                            while ((ht = hr.queue().poll()) != null) {
                                try {
                                    ht.run();
                                } catch (Throwable t) {
                                    FlightRecorder.record("HALO-TASK-THREW " + t);
                                }
                            }
                            while ((ht = hr.helpQueue.poll()) != null) {
                                try {
                                    ht.run();
                                } catch (Throwable t) {
                                    FlightRecorder.record("HALO-HELP-THREW " + t);
                                }
                            }
                        } finally {
                            me.currentRegion = prevR;
                            hr.release(Thread.currentThread());
                        }
                    }
                    // Island-internal cross-region replay: single-threaded,
                    // every member region quiescent, other islands unreachable
                    // by the separation invariant.
                    RegionizedServer.drainDeferredOps(d);
                    // BE phase on the island's timeline.
                    IslandBlockEntityTicks.tickIslandCells(rs.level, rs, island);
                    // Chunk-packet snapshots LAST: the tick's final coherent
                    // state, still under the unit claim (sender redesign
                    // 2026-07-14 — no per-packet claims anywhere).
                    ChunkDelivery.sealPending(rs, island);
                } finally {
                    me.currentDomain = null;
                }
            } catch (Throwable t) {
                // Contained: one broken phase must never wedge the drive; the
                // scheduler stays coherent (claim released, deadline rolled).
                ThrottledLog.error(LOGGER, "islandUnit", t, "island unit failed for island#{} ({})",
                        island.id, rs.level.dimension().identifier());
            } finally {
                // TAIL HARDENING (task #25 — closes the gap the 2026-07-14
                // release hardening left): every statement in this finally
                // BEFORE the release chain could still skip the release by
                // throwing (an exception in a finally aborts the rest of the
                // block) — the clock-forensics build, the phase-flag write,
                // endUnitCapture's leftover flush. Contain them here; the
                // seal → complete → release chain below is unskippable.
                try {
                    // MACHINE INVARIANT: L must advance by exactly the unit's
                    // own ++ — any external mid-unit write (a regression of
                    // the merge clock-jump class) is the first divergence,
                    // caught here.
                    if (island.logicalTick != lAtStart) {
                        FlightRecorder.record("CLOCK-DISCONTINUITY island#" + island.id
                                + " L " + lAtStart + " -> " + island.logicalTick + " mid-unit");
                        FlightRecorder.dump("island#" + island.id + " logical clock changed mid-unit ("
                                + lAtStart + " -> " + island.logicalTick + ")");
                    }
                    if (debugFailTail > 0) {
                        debugFailTail--;
                        throw new IllegalStateException("debug: injected unit-tail failure");
                    }
                    island.domain.handlingTickPhase = false;
                    IslandBlockEvents.endUnitCapture(rs.level); // idempotent; flushes leftovers on abort
                } catch (Throwable tail) {
                    ThrottledLog.error(LOGGER, "unitTail", tail,
                            "island#{} unit TAIL failed pre-release (contained — claim still releases)",
                            island.id);
                } finally {
                    // RELEASE HARDENING (2026-07-14 retained-claim freezes): the
                    // claim release must survive ANY throw out of complete() —
                    // an exception inside a finally aborts the rest of the block,
                    // which silently retained the claim and parked every later
                    // main fence forever (watchdog dumps 05:08/05:09).
                    try {
                        // Seal this unit's cross-dimension stage into one cohort
                        // batch — in the finally chain so a contained phase
                        // throwable can never strand staged arrivals.
                        CrossDimArrivals.seal(island.domain);
                    } finally {
                        try {
                            complete(island, start);
                        } finally {
                            island.clearClaim();
                            island.unitClaim.set(false);
                        }
                    }
                }
            }
        } finally {
            // PHANTOM-HOLDER POST-CONDITION (task #25 operator checklist:
            // "worker returned to the idle loop while still recorded as
            // holder"): the release chain above makes this unreachable; if any
            // future path regresses, catch it HERE — on the owning thread with
            // the ledger intact — and release loudly instead of wedging every
            // later main claim/fence. Holder identity makes the check precise:
            // a flag re-claimed by someone else the instant we released never
            // matches this thread.
            if (island.unitClaim.get() && island.claimHolder == Thread.currentThread()) {
                LOGGER.error("[async-islands] unit exit still holds island#{}/gen{} claim — releasing "
                        + "(invariant breach): {}",
                        island.id, island.gen, IslandCommands.claimForensics(island));
                island.clearClaim();
                island.unitClaim.set(false);
            }
            done.complete(null);
        }
    }

    /**
     * Post-phase bookkeeping: advance the island's REAL logical clock, record
     * measured cost, roll the deadline (overrun → reschedule from completion:
     * only this island slips below 20 TPS).
     */
    private static void complete(IslandRegistry.PIsland island, long startNanos) {
        long end = System.nanoTime();
        island.logicalTick++;
        island.armConsumedGameTime = island.armStampGameTime; // arm gate reopens
        island.costNanos = end - startNanos;
        // Profiler metrics (2026-07-14): EWMA unit cost + decaying max + a
        // completion counter for reader-side TPS windows. Single writer (the
        // coordinator), plain volatile — zero hot-path locks/allocations.
        long cost = island.costNanos;
        long ema = island.emaUnitNanos;
        island.emaUnitNanos = ema == 0 ? cost : (ema * 3 + cost) >> 2;
        long mx = island.maxUnitNanos - (island.maxUnitNanos >> 6);
        island.maxUnitNanos = Math.max(cost, mx);
        island.unitsCompleted++;
        // Region-aware /tick rate: per-island pacing target (rate overrides →
        // lower-tps wins; else the global /tick rate target). Deadlines keep
        // their tick-count semantics — only the wall-clock step changes.
        RegionizedServer rsT = com.axalotl.fabrolia.common.regions.RegionScheduler
                .forLevel(island.level);
        long tickNanos = rsT != null
                ? TemporalControl.effectiveTargetNanos(rsT, island) : TICK_NANOS;
        // Task #43 — cross-dimension cadence coupling: a linked
        // Overworld↔Nether member rolls its deadline at the coupling's SHARED
        // rate (lower-TPS-wins over the members, resolved on main by
        // CrossDimLinks), from the shared anchor CrossDimLinks wrote at link
        // time. Both members therefore advance in lockstep and the pre-link
        // phase offset stops drifting (dΔ/dunit == 0). Clocks are untouched —
        // phase-lock, never value-merge.
        CrossDimLinks.Coupling coupling = island.crossDimCoupling;
        if (coupling != null) {
            long shared = coupling.sharedTargetNanos;
            if (shared > 0) {
                tickNanos = shared;
            }
        }
        long next = island.nextDeadlineNanos;
        next = (next == 0 ? startNanos : next) + tickNanos;
        if (end - next >= tickNanos) {
            // ≥1 FULL tick behind: genuine overload — count it and re-run
            // ASAP (deadline = completion time, still due at the next drive;
            // only this island slips). Sub-tick drift deliberately keeps the
            // rolled phase: a healthy unit completes ε after the 20Hz drive
            // point, and re-anchoring on that ε made the deadline oscillate
            // around the drive grid — the quiet-island starvation of the
            // 2026-07-12 a2lag run (offset=345, miss=262 on an EMPTY island).
            island.missedDeadlines++;
            island.lagNanos += end - next;
            next = end;
        } else if (next > end) {
            island.lagNanos = Math.max(0, island.lagNanos - (next - end));
        }
        // AHEAD-OF-SCHEDULE clamp (2026-07-20 sprint-stop 0-TPS fix): the roll
        // above adds one period per COMPLETED unit, but global sprint lockstep
        // completes units far faster than the period — /tick sprint banked
        // ~50ms of future deadline per sprint tick (a 10s sprint at ~2800 TPS
        // = ~23 MINUTES of debt), so after /tick sprint stop the drive saw no
        // island due until the debt drained: exactly 0 TPS, revived only by
        // the next sprint's deadline-bypassing branch. The deadline may never
        // sit more than one period past completion; the 12ms grace keeps the
        // clamp strictly outside the healthy path (drive submits up to 10ms
        // early — re-anchoring on that ε is the quiet-island starvation this
        // roll's phase-keeping exists to prevent), so it fires only under
        // genuine faster-than-target production (sprint lockstep).
        if (next > end + tickNanos + 12_000_000L) {
            next = end + tickNanos;
        }
        island.nextDeadlineNanos = next;
        // FENCE FAIRNESS (2026-07-14 rate-freeze fix): a standing queue of
        // chained units re-claimed the island the instant the claim released,
        // starving every main-side claim spin (tick-datapack fences, rebuilds,
        // saves) for MINUTES. Chaining and pacing now YIELD whenever main has
        // announced interest in this island's claim (claimWanted); the fence
        // completes and the deadline drive re-kicks the island afterwards
        // (deadline stays due — nothing is lost, only deferred).
        // Publish pacing observation FIRST: any session change made before
        // this point is visible to this read (volatile), so a command's
        // commit-ack fires only after a complete unit ran under (or after)
        // its generation.
        island.appliedPacingGen = island.pacingGen;
        boolean fenceWaiting = island.claimWanted || (rsT != null && rsT.mainHoldsAllClaims())
                || island.retired
                || com.axalotl.fabrolia.common.config.FabroliaConfig.asyncFailClosedTripped;
        // Local sprint: self-chain the next unit immediately (back-to-back on
        // the pool — as fast as safely possible; each iteration is a complete
        // unit with exact phase order). Paused during global sprint lockstep.
        if (rsT != null && !fenceWaiting && TemporalControl.onUnitComplete(rsT, island)) {
            island.nextDeadlineNanos = end; // due now
            island.chainedUnits++;
            submitUnit(rsT, island);
            return;
        }
        // Rate targets ABOVE 20 TPS: pacing is owned by the PACER thread — a
        // single dedicated scheduler that submits the unit exactly at its
        // deadline (no coordinator parks occupying workers, no standing unit
        // queue racing fences). Bounded catch-up already holds: an overrun
        // resets next = end above, so an island whose MSPT exceeds the target
        // period simply runs below target continuously — never a debt loop.
        if (rsT != null && !fenceWaiting && tickNanos < TICK_NANOS
                && !(RegionScheduler.getServer() != null
                        && RegionScheduler.getServer().tickRateManager().isSprinting())) {
            TemporalControl.PACER.schedule(rsT, island, next);
        }
    }

    /** BELT CLAIM-GUARD (2026-07-16 audit): "provably-empty pool" checked
     *  only the queues — a phase task mid-run on another worker is in NO
     *  queue, so a ≥3s stall (portal-ticket monitor, GC/OS pause, Tier-3
     *  park) was indistinguishable from a leaked contribution and the
     *  barrier was force-completed MID-PHASE: the resumed half of the
     *  entity list staged into the NEXT unit's seal → an End-portal
     *  cohort published as two destination batches (production 6/6
     *  half-and-half split). A held claim = stalled-in-flight, NOT a
     *  leak: keep waiting, record the holder + its stack (sampled from
     *  outside — a blocked thread cannot sample itself). The belt still
     *  fires for true leaks (no member claim held). */
    private static long lastStallSampleNanos;

    private static boolean anyMemberClaimHeld(IslandRegistry.PIsland island, EpochDomain d,
                                              java.util.ArrayList<Region> members) {
        boolean held = false;
        for (int i = 0; i < members.size(); i++) {
            Region m = members.get(i);
            Thread w = m.currentWorker();
            if (w == null || m.doneEpoch == d.epoch) {
                continue;
            }
            held = true;
            long now = System.nanoTime();
            if (now - lastStallSampleNanos > 3_000_000_000L) {
                lastStallSampleNanos = now;
                long ageMs = (now - m.claimAcquiredNanos) / 1_000_000L;
                StringBuilder sb = new StringBuilder(512);
                sb.append("STALLED-MEMBER island#").append(island.id)
                  .append(" L=").append(island.logicalTick)
                  .append(" region=").append(Long.toHexString(m.gridKey))
                  .append(" worker=").append(w.getName())
                  .append(" claimPath=").append(m.claimPath)
                  .append(" ageMs=").append(ageMs).append(" stack:");
                StackTraceElement[] st = w.getStackTrace();
                for (int f = 0; f < Math.min(st.length, 14); f++) {
                    sb.append(" <- ").append(st[f]);
                }
                FlightRecorder.record(sb.toString());
                LOGGER.warn("[async-islands] entity phase stalled in-flight (holding, not forcing): {}", sb);
            }
        }
        return held;
    }
}
