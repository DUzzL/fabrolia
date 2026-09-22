package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.config.FabroliaConfig;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Task #43 — cross-dimension island linking + deadline-anchor cadence coupling
 * (the review's "smaller mechanism"; the {@code BridgeTickUnit} drive takeover
 * is DEFERRED pending a maintainer decision — its routed-command fence
 * interaction deadlocks, review D1 — and nothing here takes two island claims
 * for the duration of any tick work).
 *
 * <p>The problem: an Overworld portal farm and its Nether catcher live on two
 * islands whose deadlines are independent, so their phase offset drifts without
 * bound — every stall either side absorbs permanently shifts it, and the
 * observed transfer latency wanders between 1 and 2 destination ticks. The fix
 * shipped here is scheduling-layer only:
 *
 * <ol>
 *   <li><b>Link detection (observation-first)</b>: per rebuild pass, every
 *       Overworld island's populated cell set ({@code PIsland.cells}; halo
 *       cells are a write-legality closure, not activity, and are excluded)
 *       is down-projected through {@link CrossDimProjection} into Nether cell
 *       space — scale read live from
 *       {@link DimensionType#getTeleportationScale}, never hardcoded — and
 *       intersected with every Nether island's cells. Intersecting pairs
 *       LINK immediately; a link UNLINKS only after its intersection has been
 *       absent for {@link FabroliaConfig#crossDimUnlinkGraceTicks} wall ticks
 *       (grace-100, the region-eviction precedent), so population flicker
 *       never thrashes it.</li>
 *   <li><b>Cadence coupling</b>: linked members advance their
 *       {@code nextDeadlineNanos} from ONE shared anchor at ONE shared rate.
 *       The anchor is written once at link time (min of the members' pending
 *       deadlines — nobody's obligation is ever postponed, so linking cannot
 *       add latency); the shared rate is lower-TPS-wins
 *       (max of the members' {@link TemporalControl#effectiveTargetNanos})
 *       and is consumed by {@code IslandTickUnit.complete()} through the
 *       {@link Coupling} published on each member. Clocks are PHASE-LOCKED,
 *       never value-merged: each island keeps its own {@code logicalTick};
 *       only the wall-clock cadence is shared, so the pre-link L offset stays
 *       constant (dΔ/dunit == 0) and unlink is Δ=0 by construction — no
 *       double tick, no skipped tick, no clock writes anywhere.</li>
 * </ol>
 *
 * <h2>Single authority, and the safe points (review D6/D7)</h2>
 * The two levels rebuild at different times on the same main thread. This
 * class is the ONE authority for link state: static, main-confined except for
 * the two published-to-workers surfaces ({@code PIsland.crossDimCoupling} and
 * {@link Coupling#sharedTargetNanos}, both volatile). Both levels' rebuilds
 * call {@link #onRebuild} INSIDE their quiesce window (before
 * {@code releaseAllIslands} — review D7), so the rebuilding level's members
 * are main-held there; the PARTNER level's members are touched only under a
 * try-CAS of their {@code unitClaim} (never held across any wait — a failed
 * CAS defers the anchor to {@link #mainTick}, which retries claim-free-first
 * each tick). {@code nextDeadlineNanos} is only ever written with the member's
 * claim held (or main-held via quiesce), so it can never race the
 * read-modify-write in {@code IslandTickUnit.complete()}.
 *
 * <h2>Pacer inertness (review D3)</h2>
 * Every anchor write bumps each member's {@code pacingGen}: a pacer
 * {@code DelayQueue} entry scheduled before the link fires with a stale gen
 * and goes inert by the existing mechanism ({@code TemporalControl.Pacer}
 * fire-time check), so no pre-link cadence authority can solo-tick a coupled
 * member at its old rate.
 *
 * <h2>Coherent pins and local sprints outrank coupling (review D7)</h2>
 * A coherent-pinned member with L &lt; W can never reach L == W under a shared
 * cadence (its catch-up chaining would have to outrun the shared rate), and a
 * local sprint is an explicit "run THIS island fast now" order. Both are
 * operator sessions; silently weakening their contracts is worse than not
 * coupling. Policy: the link stays (geometric observation), but cadence
 * coupling SUSPENDS while any member is covered by a coherent or sprint
 * session, and resumes (with a fresh anchor) when the session ends.
 *
 * <h2>The End is excluded by construction</h2>
 * The link table is explicit config whose default and only supported value is
 * {@code minecraft:overworld=minecraft:the_nether}. The pair is resolved by
 * matching {@link Level#OVERWORLD}/{@link Level#NETHER} keys ONLY — an End
 * level can never become a pair member, so {@code EndPlatformHandoff} (which
 * has no dimension check of its own and is End-scoped purely by op payload)
 * is untouched structurally, not by promise. Config entries naming the End
 * (or anything else) are refused at parse time, and a defensive guard refuses
 * an End level even if handed one directly. This class never touches the
 * transfer lane: no reference to CrossDimArrivals, NetherPortalTransfers
 * or EndPlatformHandoff exists here.
 *
 * <h2>Honest limits</h2>
 * The anchor removes DRIFT; transfer readiness and endpoint acquisition remain
 * the separate Nether transfer lane's responsibility. If one member genuinely
 * overruns (unit cost &gt; shared period), no cadence can
 * keep completion counts in lockstep — its deadline re-anchors to completion
 * (the existing overload rule) and the phase offset shifts once, then holds;
 * {@link #mainTick}'s heal re-anchors the pair when the deadlines break more
 * than two periods apart (post-sprint, post-overrun).
 */
public final class CrossDimLinks {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-CrossDim");

    private CrossDimLinks() {}

    /** The one supported link-table entry, in both spellings. */
    static final String SUPPORTED_PAIR = "minecraft:overworld=minecraft:the_nether";
    static final String SUPPORTED_PAIR_REVERSED = "minecraft:the_nether=minecraft:overworld";

    /**
     * Cadence coupling published to {@code IslandTickUnit.complete()} via
     * {@code PIsland.crossDimCoupling}. Members share one deadline anchor and
     * one rate; everything else about the islands (clock, domain, epoch,
     * arrivals, sessions) stays per-island.
     */
    public static final class Coupling {
        /** Shared pacing target (nanos per logical tick); lower-TPS-wins over
         *  the members, refreshed on main each tick. 0 = not yet resolved
         *  (readers fall back to their own target). */
        public volatile long sharedTargetNanos;
        /** Members in fixed global order (dimension order, then id). Built on
         *  main, never mutated after publication (replaced wholesale). */
        final ArrayList<IslandRegistry.PIsland> members = new ArrayList<>(2);
        /** Main-only: the shared anchor has been applied to every member. */
        boolean anchored;
    }

    /** One observed Overworld↔Nether island intersection, with hysteresis. */
    static final class Link {
        IslandRegistry.PIsland ow;
        IslandRegistry.PIsland nether;
        /** Member cell snapshots at the last intersecting pass — lineage
         *  re-resolution after a member retires (registry cell-voting). */
        final LongOpenHashSet owCells = new LongOpenHashSet(8);
        final LongOpenHashSet netherCells = new LongOpenHashSet(8);
        /** The intersection itself, Nether cell space (diagnostics). */
        final LongOpenHashSet sharedCells = new LongOpenHashSet(4);
        long linkedAtWall;
        long lastIntersectWall;

        Link(IslandRegistry.PIsland ow, IslandRegistry.PIsland nether, long wallNow) {
            this.ow = ow;
            this.nether = nether;
            this.linkedAtWall = wallNow;
            this.lastIntersectWall = wallNow;
        }
    }

    // ---- authority state: main-confined ------------------------------------
    private static final ArrayList<Link> LINKS = new ArrayList<>(2);
    private static final ArrayList<Coupling> COUPLINGS = new ArrayList<>(2);
    private static RegionizedServer lastOwRs;
    private static RegionizedServer lastNetherRs;
    /** Reused projection scratch (island rebuild once had Long boxing as its
     *  single largest allocation source; this runs on the same path). */
    private static final LongOpenHashSet PROJ_SCRATCH = new LongOpenHashSet(64);
    private static final LongOpenHashSet POPULATED_SCRATCH = new LongOpenHashSet(64);
    private static final java.util.HashSet<String> WARNED_PAIR_ENTRIES = new java.util.HashSet<>(2);

    // ---- counters (main-written; read by the status command on main) -------
    static long linksFormed;
    static long linksDropped;
    static long anchorsApplied;
    static long anchorsDeferred;
    static long couplingsSuspended;
    static long refusedPairEntries;
    static long endRefusals;

    // ---- entry points ------------------------------------------------------

    /**
     * Main thread, called at the tail of {@code rebuildIslandsBody} — INSIDE
     * the rebuild quiesce window for {@code rs} (review D7). Recomputes the
     * link table against the partner level's last-committed topology.
     * A failure propagates through the physical Main tick; a partially-mutated
     * coupling table is not safe input for later simulation.
     */
    public static void onRebuild(RegionizedServer rs) {
        if (rs == null) {
            return;
        }
        onRebuildInner(rs);
    }

    /**
     * Main thread, drive head (next to {@code TemporalControl.tickAcks}):
     * eligibility + shared-rate refresh, deferred-anchor retries, phase heal,
     * and the both-async precondition belt. Never recomputes geometry —
     * intersections only change when cells change, which latches a rebuild.
     */
    public static void mainTick() {
        mainTickInner();
    }

    /** Scheduler stop: drop every link and published coupling. Counters are
     *  lifetime diagnostics and survive (the TemporalControl precedent). */
    public static synchronized void reset() {
        for (Coupling c : COUPLINGS) {
            for (int i = 0; i < c.members.size(); i++) {
                IslandRegistry.PIsland m = c.members.get(i);
                if (m.crossDimCoupling == c) {
                    m.crossDimCoupling = null;
                }
            }
        }
        COUPLINGS.clear();
        LINKS.clear();
        lastOwRs = null;
        lastNetherRs = null;
        WARNED_PAIR_ENTRIES.clear(); // fresh scheduler: re-warn once per session
    }

    // ---- rebuild pass ------------------------------------------------------

    private static void onRebuildInner(RegionizedServer rs) {
        // Defensive End refusal (belt — the pair resolution below can never
        // select an End level, but the exclusion must not depend on that).
        if (rs.level != null && rs.level.dimension() == Level.END) {
            endRefusals++;
            return;
        }
        if (!FabroliaConfig.fabroliaCrossDimCoupling || !pairConfigured()) {
            dropAll("coupling disabled or no valid dimension pair configured");
            return;
        }
        RegionizedServer owRs = null;
        RegionizedServer netherRs = null;
        for (RegionizedServer r : RegionScheduler.allLevels()) {
            if (r.level == null) {
                continue;
            }
            if (r.level.dimension() == Level.OVERWORLD) {
                owRs = r;
            } else if (r.level.dimension() == Level.NETHER) {
                netherRs = r;
            }
        }
        if (owRs == null || netherRs == null) {
            return; // partner not registered yet (boot) — nothing to observe
        }
        if (rs != owRs && rs != netherRs) {
            return; // unrelated dimension's rebuild
        }
        if (!owRs.asyncActive || !netherRs.asyncActive) {
            // Precondition: both levels async (the global lane is already
            // wall-locked on both sides — the old, stricter coupling).
            dropAll("precondition lost: both levels must be async");
            return;
        }
        lastOwRs = owRs;
        lastNetherRs = netherRs;
        long wallNow = rs.level.getGameTime();
        recompute(owRs, netherRs, wallNow);
        refreshCouplings(owRs, netherRs);
    }

    private static void recompute(RegionizedServer owRs, RegionizedServer netherRs, long wallNow) {
        // Structural End guard (cannot trip by construction of the pair
        // resolution; kept so the exclusion survives refactors).
        if (owRs.level.dimension() == Level.END || netherRs.level.dimension() == Level.END) {
            endRefusals++;
            return;
        }
        // 1. Lineage: a retired member is replaced by the live island covering
        //    the most of its last-known cells (the registry's cell-voting
        //    rule) — a rebuild that reshaped a member must not read as "the
        //    intersection disappeared".
        for (int i = 0; i < LINKS.size(); i++) {
            Link l = LINKS.get(i);
            if (l.ow.retired) {
                IslandRegistry.PIsland s = successorByCells(owRs, l.owCells);
                if (s != null) {
                    FlightRecorder.record("CROSSDIM-LINEAGE ow island#" + l.ow.id + "/g" + l.ow.gen
                            + " -> island#" + s.id + "/g" + s.gen);
                    l.ow = s;
                }
            }
            if (l.nether.retired) {
                IslandRegistry.PIsland s = successorByCells(netherRs, l.netherCells);
                if (s != null) {
                    FlightRecorder.record("CROSSDIM-LINEAGE nether island#" + l.nether.id + "/g" + l.nether.gen
                            + " -> island#" + s.id + "/g" + s.gen);
                    l.nether = s;
                }
            }
        }
        // 2. Geometry over live islands. Scale is read per pass — a datapack
        //    reload can change coordinateScale; never hardcode 8.
        double scale = DimensionType.getTeleportationScale(
                owRs.level.dimensionType(), netherRs.level.dimensionType());
        if (!(scale > 0.0) || !Double.isFinite(scale)) {
            IslandCommands.publishAndPropagate(new IllegalStateException(
                    "invalid cross-dimension teleportation scale " + scale
                            + " between " + owRs.level.dimension().identifier()
                            + " and " + netherRs.level.dimension().identifier()));
            throw new AssertionError("fatal cross-dimension scale publication returned");
        }
        int dilation = CrossDimProjection.dilationInDestBlocks(scale);
        int shift = gridShift();
        POPULATED_SCRATCH.clear();
        for (IslandRegistry.PIsland ne : netherRs.islandRegistry.islands()) {
            if (!ne.retired) {
                POPULATED_SCRATCH.addAll(ne.cells);
            }
        }
        for (IslandRegistry.PIsland ow : owRs.islandRegistry.islands()) {
            if (ow.retired || ow.cells.isEmpty()) {
                continue;
            }
            PROJ_SCRATCH.clear();
            CrossDimProjection.projectOnto(ow.cells, POPULATED_SCRATCH,
                    shift, scale, dilation, PROJ_SCRATCH);
            for (IslandRegistry.PIsland ne : netherRs.islandRegistry.islands()) {
                if (ne.retired || ne.cells.isEmpty()) {
                    continue;
                }
                if (!CrossDimProjection.intersects(PROJ_SCRATCH, ne.cells)) {
                    continue;
                }
                Link l = findLink(ow, ne);
                if (l == null) {
                    l = new Link(ow, ne, wallNow);
                    LINKS.add(l);
                    linksFormed++;
                    FlightRecorder.record("CROSSDIM-LINK ow#" + ow.id + "/g" + ow.gen
                            + " <-> ne#" + ne.id + "/g" + ne.gen + " wall=" + wallNow);
                }
                l.lastIntersectWall = wallNow;
                refill(l.owCells, ow.cells);
                refill(l.netherCells, ne.cells);
                l.sharedCells.clear();
                LongIterator it = ne.cells.iterator();
                while (it.hasNext()) {
                    long k = it.nextLong();
                    if (PROJ_SCRATCH.contains(k)) {
                        l.sharedCells.add(k);
                    }
                }
            }
        }
        // 3. Hysteresis drop: absence must persist for the grace period of
        //    WALL ticks; any reappearance above refreshed lastIntersectWall.
        long grace = Math.max(0, FabroliaConfig.crossDimUnlinkGraceTicks);
        for (int i = LINKS.size() - 1; i >= 0; i--) {
            Link l = LINKS.get(i);
            if (l.lastIntersectWall == wallNow) {
                continue;
            }
            if (wallNow - l.lastIntersectWall >= grace) {
                LINKS.remove(i);
                linksDropped++;
                FlightRecorder.record("CROSSDIM-UNLINK ow#" + l.ow.id + "/g" + l.ow.gen
                        + " <-> ne#" + l.nether.id + "/g" + l.nether.gen
                        + " absent " + (wallNow - l.lastIntersectWall) + "t (grace " + grace + ")");
                // Deadlines are deliberately NOT touched: each member keeps
                // the shared-cadence deadline as its exact next obligation —
                // no double tick, no skipped tick (Δ=0 split, design §5.2).
                l.ow.pacingGen++;
                l.nether.pacingGen++;
            }
        }
    }

    // ---- coupling maintenance (rebuild tail + every main tick) -------------

    private static void mainTickInner() {
        if (LINKS.isEmpty() && COUPLINGS.isEmpty()) {
            return;
        }
        RegionizedServer owRs = lastOwRs;
        RegionizedServer netherRs = lastNetherRs;
        if (owRs == null || netherRs == null) {
            return;
        }
        if (!FabroliaConfig.fabroliaCrossDimCoupling || !pairConfigured()
                || !owRs.asyncActive || !netherRs.asyncActive) {
            dropAll("coupling disabled or async precondition lost");
            return;
        }
        refreshCouplings(owRs, netherRs);
        // Phase heal: a coupled pair whose deadlines broke more than two
        // shared periods apart (sprint stop, overload reset) is re-anchored.
        // Steady-state pairs are never touched — re-anchoring every pass
        // would repeatedly yank the later member's phase.
        for (int i = 0; i < COUPLINGS.size(); i++) {
            Coupling c = COUPLINGS.get(i);
            long shared = c.sharedTargetNanos;
            if (!c.anchored || shared <= 0 || c.members.size() < 2) {
                continue;
            }
            long min = Long.MAX_VALUE;
            long max = Long.MIN_VALUE;
            for (int m = 0; m < c.members.size(); m++) {
                long d = c.members.get(m).nextDeadlineNanos;
                if (d == 0) {
                    continue;
                }
                min = Math.min(min, d);
                max = Math.max(max, d);
            }
            if (max != Long.MIN_VALUE && max - min > 2 * shared) {
                tryAnchor(c, owRs, netherRs);
            }
        }
    }

    /**
     * Rebuild {@link #COUPLINGS} from the live links: connected components
     * (an Overworld island touching two Nether islands couples all three —
     * one causal cadence, exactly like same-level merges), operator-session
     * eligibility, shared-rate resolution, and anchor application. Coupling
     * objects are CARRIED when the member set is unchanged so a mid-flight
     * {@code complete()} keeps a live rate and no re-anchor churn happens.
     */
    private static void refreshCouplings(RegionizedServer owRs, RegionizedServer netherRs) {
        // Connected components over live links.
        IdentityHashMap<IslandRegistry.PIsland, ArrayList<IslandRegistry.PIsland>> groupOf =
                new IdentityHashMap<>();
        for (int i = 0; i < LINKS.size(); i++) {
            Link l = LINKS.get(i);
            if (l.ow.retired || l.nether.retired) {
                continue; // a half with no live successor pairs with nobody
            }
            ArrayList<IslandRegistry.PIsland> ga = groupOf.get(l.ow);
            ArrayList<IslandRegistry.PIsland> gb = groupOf.get(l.nether);
            if (ga == null && gb == null) {
                ArrayList<IslandRegistry.PIsland> g = new ArrayList<>(2);
                g.add(l.ow);
                g.add(l.nether);
                groupOf.put(l.ow, g);
                groupOf.put(l.nether, g);
            } else if (ga == null) {
                gb.add(l.ow);
                groupOf.put(l.ow, gb);
            } else if (gb == null) {
                ga.add(l.nether);
                groupOf.put(l.nether, ga);
            } else if (ga != gb) {
                for (int m = 0; m < gb.size(); m++) {
                    IslandRegistry.PIsland p = gb.get(m);
                    ga.add(p);
                    groupOf.put(p, ga);
                }
            }
        }
        ArrayList<Coupling> fresh = new ArrayList<>(2);
        java.util.IdentityHashMap<Coupling, Boolean> freshSet = new java.util.IdentityHashMap<>();
        for (ArrayList<IslandRegistry.PIsland> g : new java.util.LinkedHashSet<>(groupOf.values())) {
            if (g.size() < 2) {
                continue;
            }
            sortMembers(g, owRs);
            // Operator sessions outrank coupling: coherent pin or local
            // sprint on any member suspends the group's cadence share.
            boolean suspended = false;
            for (int m = 0; m < g.size() && !suspended; m++) {
                IslandRegistry.PIsland p = g.get(m);
                RegionizedServer prs = rsOf(p, owRs, netherRs);
                if (prs == null || TemporalControl.isCoherent(prs, p)
                        || TemporalControl.isSprinting(prs, p)) {
                    suspended = true;
                }
            }
            if (suspended) {
                boolean wasCoupled = false;
                for (int m = 0; m < g.size(); m++) {
                    if (g.get(m).crossDimCoupling != null) {
                        g.get(m).crossDimCoupling = null;
                        wasCoupled = true;
                    }
                }
                if (wasCoupled) {
                    couplingsSuspended++;
                    FlightRecorder.record("CROSSDIM-SUSPEND coupling of " + g.size()
                            + " islands: operator session (coherent pin / sprint) outranks coupling");
                }
                continue;
            }
            Coupling c = carryOrNew(g);
            long shared = 0;
            for (int m = 0; m < g.size(); m++) {
                IslandRegistry.PIsland p = g.get(m);
                RegionizedServer prs = rsOf(p, owRs, netherRs);
                long t = prs != null
                        ? TemporalControl.effectiveTargetNanos(prs, p)
                        : TemporalControl.globalIslandTargetNanos;
                shared = Math.max(shared, t); // larger nanos = lower TPS wins
            }
            c.sharedTargetNanos = shared;
            for (int m = 0; m < g.size(); m++) {
                if (g.get(m).crossDimCoupling != c) {
                    g.get(m).crossDimCoupling = c;
                }
            }
            if (!c.anchored) {
                tryAnchor(c, owRs, netherRs);
            }
            fresh.add(c);
            freshSet.put(c, Boolean.TRUE);
        }
        // Clear islands that left every coupling (unlink, retire, suspend
        // handled above; this is the belt for membership churn).
        for (int i = 0; i < COUPLINGS.size(); i++) {
            Coupling old = COUPLINGS.get(i);
            if (freshSet.containsKey(old)) {
                continue;
            }
            for (int m = 0; m < old.members.size(); m++) {
                IslandRegistry.PIsland p = old.members.get(m);
                if (p.crossDimCoupling == old) {
                    p.crossDimCoupling = null;
                }
            }
        }
        COUPLINGS.clear();
        COUPLINGS.addAll(fresh);
    }

    /** Reuse the existing coupling when the member set is unchanged. */
    private static Coupling carryOrNew(ArrayList<IslandRegistry.PIsland> members) {
        Coupling prev = members.get(0).crossDimCoupling;
        if (prev != null && prev.members.size() == members.size()) {
            boolean same = true;
            for (int i = 0; i < members.size() && same; i++) {
                same = prev.members.get(i) == members.get(i);
            }
            if (same) {
                return prev;
            }
        }
        Coupling c = new Coupling();
        c.members.addAll(members);
        return c;
    }

    /**
     * Write every member's {@code nextDeadlineNanos} from one shared anchor —
     * the MINIMUM of the members' pending deadlines, so no member's obligation
     * is ever postponed (linking can remove drift but never add latency), and
     * bump every member's {@code pacingGen} so pre-link pacer entries go inert
     * (review D3). Write-safety: a member is written only while its claim is
     * held — either main already holds it (rebuild quiesce window) or we
     * try-CAS it here, in fixed global order (dimension order, then id), and
     * BACK OUT on any failure (never wait; a deferred anchor retries from
     * {@link #mainTick}). {@code complete()}'s read-modify-write of the
     * deadline runs strictly before its claim release, so a successful CAS
     * proves no concurrent roll.
     */
    private static boolean tryAnchor(Coupling c, RegionizedServer owRs, RegionizedServer netherRs) {
        IslandRegistry.PIsland[] members =
                c.members.toArray(IslandRegistry.PIsland[]::new);
        ArrayList<IslandRegistry.PIsland> claimed = new ArrayList<>(members.length);
        try {
            for (IslandRegistry.PIsland member : members) {
                RegionizedServer memberRuntime = rsOf(member, owRs, netherRs);
                if (memberRuntime != null
                        && memberRuntime.currentThreadHoldsAllClaims()) {
                    continue;
                }
                if (!member.tryClaimAutonomousWork()) {
                    // Pre-body refusal: release every claim already accepted;
                    // no anchor state or pacing successor was published.
                    releaseAnchorClaims(claimed, null);
                    try {
                        // Claim cleanup is part of the refusal result. A peer W
                        // selected during release must win before this normal
                        // retry signal becomes visible to the caller.
                        RegionScheduler.commitSuccessIfHealthy(
                                () -> anchorsDeferred++);
                    } catch (Throwable peerFailure) {
                        CrossDimLinks.<RuntimeException>rethrow(
                                RegionScheduler.publishFatalAndSelectWinner(peerFailure));
                    }
                    return false;
                }
                // Record ownership before the fallible ledger stamp so this
                // exact claim is still released if the stamp fails.
                claimed.add(member);
                member.noteClaim("crossdim-anchor");
            }

            long anchor = 0;
            for (IslandRegistry.PIsland member : members) {
                long d = member.nextDeadlineNanos;
                if (d != 0 && (anchor == 0 || d < anchor)) {
                    anchor = d;
                }
            }
            StringBuilder sb = new StringBuilder("CROSSDIM-ANCHOR members=[");
            for (int i = 0; i < members.length; i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append('#').append(members[i].id).append("/g").append(members[i].gen);
            }
            sb.append("] anchor=").append(anchor)
              .append(" sharedNanos=").append(c.sharedTargetNanos);
            // Diagnostics are prepared and emitted before the plain-field
            // commit; they cannot turn an already anchored coupling into W.
            FlightRecorder.record(sb.toString());

            final long committedAnchor = anchor;
            RegionScheduler.commitSuccessIfHealthy(() -> {
                if (committedAnchor != 0) {
                    for (IslandRegistry.PIsland member : members) {
                        member.nextDeadlineNanos = committedAnchor;
                    }
                }
                for (IslandRegistry.PIsland member : members) member.pacingGen++;
                c.anchored = true;
                anchorsApplied++;
            });
        } catch (Throwable thrown) {
            Throwable failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
            releaseAnchorClaims(claimed, failure);
            CrossDimLinks.<RuntimeException>rethrow(failure);
        }
        releaseAnchorClaims(claimed, null);
        try {
            // The anchor commit precedes mandatory release. Close the peer-W
            // window opened by that cleanup before reporting normal success.
            RegionScheduler.commitSuccessIfHealthy(() -> {});
        } catch (Throwable peerFailure) {
            CrossDimLinks.<RuntimeException>rethrow(
                    RegionScheduler.publishFatalAndSelectWinner(peerFailure));
        }
        return true;
    }

    private static void releaseAnchorClaims(ArrayList<IslandRegistry.PIsland> claimed,
                                            Throwable primary) {
        Throwable failure = primary;
        for (int r = claimed.size() - 1; r >= 0; r--) {
            IslandRegistry.PIsland member;
            try {
                // Consume the receipt BEFORE releasing it. Refusal cleanup can
                // re-enter here after a late W; the physical claim may already
                // belong to a different worker by then.
                member = claimed.remove(r);
            } catch (Throwable cleanup) {
                failure = RegionScheduler.publishFatalAndSelectWinner(
                        mergeFailure(failure, cleanup));
                continue;
            }
            try {
                member.clearClaim();
            } catch (Throwable cleanup) {
                failure = RegionScheduler.publishFatalAndSelectWinner(
                        mergeFailure(failure, cleanup));
            }
            try {
                // Exact permit release is independent: a ledger-cleanup
                // Throwable must never retain the physical claim.
                member.unitClaim.set(false);
            } catch (Throwable cleanup) {
                failure = RegionScheduler.publishFatalAndSelectWinner(
                        mergeFailure(failure, cleanup));
            }
        }
        if (failure != null) CrossDimLinks.<RuntimeException>rethrow(failure);
    }

    // ---- helpers -----------------------------------------------------------

    private static Link findLink(IslandRegistry.PIsland ow, IslandRegistry.PIsland ne) {
        for (int i = 0; i < LINKS.size(); i++) {
            Link l = LINKS.get(i);
            if (l.ow == ow && l.nether == ne) {
                return l;
            }
        }
        return null;
    }

    /** Registry cell-vote: the live island covering the most of {@code cells}. */
    private static IslandRegistry.PIsland successorByCells(RegionizedServer rs, LongOpenHashSet cells) {
        IdentityHashMap<IslandRegistry.PIsland, Integer> votes = new IdentityHashMap<>(4);
        LongIterator it = cells.iterator();
        while (it.hasNext()) {
            IslandRegistry.PIsland p = rs.islandRegistry.byCell(it.nextLong());
            if (p != null && !p.retired) {
                votes.merge(p, 1, Integer::sum);
            }
        }
        IslandRegistry.PIsland best = null;
        int bestVotes = 0;
        for (var e : votes.entrySet()) {
            if (e.getValue() > bestVotes
                    || (e.getValue() == bestVotes && best != null && e.getKey().id < best.id)) {
                best = e.getKey();
                bestVotes = e.getValue();
            }
        }
        return best;
    }

    private static Throwable mergeFailure(Throwable primary, Throwable secondary) {
        if (primary == null) return secondary;
        if (secondary != null && secondary != primary) {
            try {
                primary.addSuppressed(secondary);
            } catch (Throwable ignored) {
                // Suppression is diagnostic only; W remains authoritative.
            }
        }
        return primary;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T) failure;
    }

    private static void refill(LongOpenHashSet dst, LongOpenHashSet src) {
        dst.clear();
        dst.addAll(src);
    }

    private static RegionizedServer rsOf(IslandRegistry.PIsland p,
                                         RegionizedServer owRs, RegionizedServer netherRs) {
        if (owRs != null && p.level == owRs.level) {
            return owRs;
        }
        if (netherRs != null && p.level == netherRs.level) {
            return netherRs;
        }
        return null;
    }

    /** Fixed global order: dimension order (Overworld first), then island id. */
    private static void sortMembers(ArrayList<IslandRegistry.PIsland> g, RegionizedServer owRs) {
        ServerLevel owLevel = owRs == null ? null : owRs.level;
        g.sort((a, b) -> {
            int da = a.level == owLevel ? 0 : 1;
            int db = b.level == owLevel ? 0 : 1;
            if (da != db) {
                return Integer.compare(da, db);
            }
            return Long.compare(a.id, b.id);
        });
    }

    private static int gridShift() {
        RegionGrid g = RegionScheduler.grid();
        return g != null ? g.shift : RegionGrid.DEFAULT_SHIFT;
    }

    /**
     * The link table is explicit config; default and only supported value is
     * {@code minecraft:overworld=minecraft:the_nether}. Anything else —
     * including ANY entry naming the End — is refused with a one-shot warning.
     */
    static boolean pairConfigured() {
        List<String> table = FabroliaConfig.crossDimLinkedPairs;
        if (table == null) {
            return false;
        }
        boolean ok = false;
        for (int i = 0; i < table.size(); i++) {
            String raw = table.get(i);
            String norm = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace(" ", "");
            if (norm.equals(SUPPORTED_PAIR) || norm.equals(SUPPORTED_PAIR_REVERSED)) {
                ok = true;
            } else if (WARNED_PAIR_ENTRIES.add(norm)) {
                refusedPairEntries++;
                LOGGER.warn("[crossdim] refusing link-table entry '{}' — the only supported pair is "
                        + "'{}' (the End must never appear: the End-platform handoff gate has no "
                        + "dimension check of its own)", raw, SUPPORTED_PAIR);
            }
        }
        return ok;
    }

    private static void dropAll(String reason) {
        if (LINKS.isEmpty() && COUPLINGS.isEmpty()) {
            return;
        }
        for (int i = 0; i < LINKS.size(); i++) {
            Link l = LINKS.get(i);
            l.ow.pacingGen++;
            l.nether.pacingGen++;
        }
        linksDropped += LINKS.size();
        LINKS.clear();
        for (int i = 0; i < COUPLINGS.size(); i++) {
            Coupling c = COUPLINGS.get(i);
            for (int m = 0; m < c.members.size(); m++) {
                if (c.members.get(m).crossDimCoupling == c) {
                    c.members.get(m).crossDimCoupling = null;
                }
            }
        }
        COUPLINGS.clear();
        FlightRecorder.record("CROSSDIM-DROP-ALL " + reason);
        LOGGER.info("[crossdim] all links dropped: {}", reason);
    }

    // ---- diagnostics -------------------------------------------------------

    /** {@code /fabrolia regions crossdim} listing. Main thread. */
    public static String status() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("crossdim coupling: ")
          .append(FabroliaConfig.fabroliaCrossDimCoupling ? "ON" : "off")
          .append(" pair=").append(pairConfigured() ? SUPPORTED_PAIR : "<none valid>")
          .append(" grace=").append(FabroliaConfig.crossDimUnlinkGraceTicks).append('t')
          .append(" | links=").append(LINKS.size())
          .append(" formed=").append(linksFormed)
          .append(" dropped=").append(linksDropped)
          .append(" anchors=").append(anchorsApplied)
          .append("(+").append(anchorsDeferred).append(" deferred)")
          .append(" suspended=").append(couplingsSuspended)
          .append(" refusedEntries=").append(refusedPairEntries)
          .append(" endRefusals=").append(endRefusals);
        RegionizedServer owRs = lastOwRs;
        RegionizedServer netherRs = lastNetherRs;
        long wallNow = owRs != null && owRs.level != null ? owRs.level.getGameTime() : 0;
        for (int i = 0; i < LINKS.size(); i++) {
            Link l = LINKS.get(i);
            sb.append("\n  ").append(memberTag(owRs, l.ow))
              .append(" <-> ").append(memberTag(netherRs, l.nether))
              .append(" cells∩=").append(l.sharedCells.size());
            if (!l.sharedCells.isEmpty()) {
                sb.append('[');
                LongIterator it = l.sharedCells.iterator();
                int shown = 0;
                while (it.hasNext() && shown < 4) {
                    long k = it.nextLong();
                    if (shown > 0) {
                        sb.append(' ');
                    }
                    sb.append('(').append(CrossDimProjection.cellX(k)).append(',')
                      .append(CrossDimProjection.cellZ(k)).append(')');
                    shown++;
                }
                if (l.sharedCells.size() > shown) {
                    sb.append(" +").append(l.sharedCells.size() - shown);
                }
                sb.append(']');
            }
            sb.append(" age=").append(wallNow - l.linkedAtWall).append('t')
              .append(" lastSeen=").append(wallNow - l.lastIntersectWall).append("t ago");
            Coupling c = l.ow.crossDimCoupling;
            if (c != null && c == l.nether.crossDimCoupling) {
                long shared = c.sharedTargetNanos;
                sb.append(" COUPLED sharedTps=")
                  .append(String.format(Locale.ROOT, "%.1f",
                          shared > 0 ? 1_000_000_000.0 / shared : 0.0))
                  .append(c.anchored ? " anchored" : " anchor-pending");
                long da = l.ow.nextDeadlineNanos;
                long db = l.nether.nextDeadlineNanos;
                if (da != 0 && db != 0) {
                    sb.append(" Δdeadline=")
                      .append(String.format(Locale.ROOT, "%.2fms", (da - db) / 1_000_000.0));
                }
                sb.append(" ΔL=").append(l.ow.logicalTick - l.nether.logicalTick);
            } else {
                sb.append(" uncoupled (suspended or pending)");
            }
        }
        if (LINKS.isEmpty()) {
            sb.append("\n  no links");
        }
        return sb.toString();
    }

    private static String memberTag(RegionizedServer rs, IslandRegistry.PIsland p) {
        String handle = rs != null ? IslandMetrics.handle(rs, p)
                : ("island#" + p.id + "g" + p.gen);
        return handle + (p.retired ? "(retired)" : "");
    }

    // ---- test seams (package-private; main-thread test harness only) -------

    static List<Link> linksView() {
        return LINKS;
    }

    static List<Coupling> couplingsView() {
        return COUPLINGS;
    }
}
