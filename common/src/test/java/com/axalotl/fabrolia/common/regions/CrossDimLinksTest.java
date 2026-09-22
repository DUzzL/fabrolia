package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.config.FabroliaConfig;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.axalotl.fabrolia.common.regions.CrossDimProjection.cellKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task #43 — cross-dimension link detection + deadline-anchor cadence
 * coupling, against the REAL classes: real {@link IslandRegistry} topology
 * commits, real {@link DimensionType#getTeleportationScale} scale resolution,
 * real {@code IslandTickUnit.complete()} deadline rolls (reflection — it is
 * the production seam the coupling feeds).
 *
 * <p>Harness: the house pattern — Unsafe-allocated {@link ServerLevel}
 * subclasses (field initialisers do NOT run; everything is set in
 * {@code alloc()}), reflection into {@code RegionScheduler.PER_LEVEL/active}.
 *
 * <p>Oracles follow the review's D6 corrections: the lockstep oracle is
 * "the deadline offset stops growing" (dΔ/dunit == 0) — NOT ΔL == 0, which
 * is a value-merge assertion phase-lock deliberately does not provide.
 */
class CrossDimLinksTest {

    private static final sun.misc.Unsafe UNSAFE;

    static {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (sun.misc.Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    // ------------------------------------------------------------ harness

    static class LinkLevel extends ServerLevel {
        ResourceKey<Level> fabricatedDimension;
        DimensionType fabricatedType;
        long fabricatedGameTime;

        private LinkLevel() {
            super(null, null, null, null, null, null, false, 0L, null, false);
            throw new AssertionError("never constructed — allocated via Unsafe");
        }

        static LinkLevel alloc(ResourceKey<Level> dim, double coordinateScale) {
            try {
                LinkLevel l = (LinkLevel) UNSAFE.allocateInstance(LinkLevel.class);
                l.fabricatedDimension = dim;
                l.fabricatedType = dimType(coordinateScale);
                l.fabricatedGameTime = 1_000L;
                return l;
            } catch (InstantiationException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override public ResourceKey<Level> dimension() { return fabricatedDimension; }
        @Override public DimensionType dimensionType() { return fabricatedType; }
        @Override public long getGameTime() { return fabricatedGameTime; }
        @Override public MinecraftServer getServer() { return null; }
    }

    /** Real vanilla {@link DimensionType} through its canonical record ctor —
     *  the compact constructor validates only the numeric fields, so the
     *  reference args may stay null. The SCALE is the load-bearing part:
     *  {@code getTeleportationScale} divides the two coordinateScales. */
    static DimensionType dimType(double coordinateScale) {
        return new DimensionType(false, false, false, false, coordinateScale,
                0, 16, 16, null, 0f, null, null, null, null, null, Optional.empty());
    }

    private static void setStatic(Class<?> owner, String name, Object value)
            throws ReflectiveOperationException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T getStatic(Class<?> owner, String name) throws ReflectiveOperationException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(null);
    }

    private static void setField(Object target, Class<?> owner, String name, Object value)
            throws ReflectiveOperationException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static RegionizedServer allocRs(LinkLevel level) throws Exception {
        RegionizedServer rs = (RegionizedServer) UNSAFE.allocateInstance(RegionizedServer.class);
        setField(rs, RegionizedServer.class, "level", level);
        setField(rs, RegionizedServer.class, "islandRegistry", new IslandRegistry());
        setField(rs, RegionizedServer.class, "temporalSessions",
                new CopyOnWriteArrayList<TemporalControl.Session>());
        rs.asyncActive = true;
        return rs;
    }

    /** Commit a topology into the registry (real applyRebuildCells) and stamp
     *  the owning level, exactly as the production rebuild does. */
    private static void topo(RegionizedServer rs, long gameTime, long[]... islandsCells) {
        List<LongArrayList> sets = new ArrayList<>(islandsCells.length);
        for (long[] cells : islandsCells) {
            LongArrayList l = new LongArrayList(cells.length);
            for (long c : cells) l.add(c);
            sets.add(l);
        }
        rs.islandRegistry.applyRebuildCells(sets, gameTime);
        for (IslandRegistry.PIsland p : rs.islandRegistry.islands()) {
            p.level = rs.level;
        }
    }

    private static void invokeComplete(IslandRegistry.PIsland island, long startNanos)
            throws Exception {
        Method m = IslandTickUnit.class.getDeclaredMethod(
                "complete", IslandRegistry.PIsland.class, long.class);
        m.setAccessible(true);
        m.invoke(null, island, startNanos);
    }

    private LinkLevel owLevel;
    private LinkLevel neLevel;
    private RegionizedServer owRs;
    private RegionizedServer neRs;

    private boolean savedCoupling;
    private int savedGrace;
    private List<String> savedPairs;
    private long savedGlobalTarget;
    private boolean savedFailClosed;

    private long formed0;
    private long dropped0;
    private long anchors0;
    private long suspended0;
    private long refused0;
    private long endRefusals0;

    @BeforeEach
    void setUp() throws Exception {
        savedCoupling = FabroliaConfig.fabroliaCrossDimCoupling;
        savedGrace = FabroliaConfig.crossDimUnlinkGraceTicks;
        savedPairs = FabroliaConfig.crossDimLinkedPairs;
        savedGlobalTarget = TemporalControl.globalIslandTargetNanos;
        savedFailClosed = FabroliaConfig.asyncFailClosedTripped;
        FabroliaConfig.fabroliaCrossDimCoupling = true;
        FabroliaConfig.crossDimUnlinkGraceTicks = 100;
        FabroliaConfig.crossDimLinkedPairs = FabroliaConfig.defaultCrossDimLinkedPairs();
        TemporalControl.globalIslandTargetNanos = 50_000_000L;
        FabroliaConfig.asyncFailClosedTripped = false;

        owLevel = LinkLevel.alloc(Level.OVERWORLD, 1.0);
        neLevel = LinkLevel.alloc(Level.NETHER, 8.0);
        owRs = allocRs(owLevel);
        neRs = allocRs(neLevel);
        setStatic(RegionScheduler.class, "active", true);
        Map<ServerLevel, RegionizedServer> perLevel =
                getStatic(RegionScheduler.class, "PER_LEVEL");
        perLevel.clear();
        perLevel.put(owLevel, owRs);
        perLevel.put(neLevel, neRs);
        CrossDimLinks.reset();

        formed0 = CrossDimLinks.linksFormed;
        dropped0 = CrossDimLinks.linksDropped;
        anchors0 = CrossDimLinks.anchorsApplied;
        suspended0 = CrossDimLinks.couplingsSuspended;
        refused0 = CrossDimLinks.refusedPairEntries;
        endRefusals0 = CrossDimLinks.endRefusals;
    }

    @AfterEach
    void tearDown() throws Exception {
        CrossDimLinks.reset();
        CrossDimLinksTest.<Map<ServerLevel, RegionizedServer>>getStatic(
                RegionScheduler.class, "PER_LEVEL").clear();
        setStatic(RegionScheduler.class, "active", false);
        FabroliaConfig.fabroliaCrossDimCoupling = savedCoupling;
        FabroliaConfig.crossDimUnlinkGraceTicks = savedGrace;
        FabroliaConfig.crossDimLinkedPairs = savedPairs;
        TemporalControl.globalIslandTargetNanos = savedGlobalTarget;
        FabroliaConfig.asyncFailClosedTripped = savedFailClosed;
    }

    private void wall(long t) {
        owLevel.fabricatedGameTime = t;
        neLevel.fabricatedGameTime = t;
    }

    private IslandRegistry.PIsland owIsland(long cell) {
        return owRs.islandRegistry.byCell(cell);
    }

    private IslandRegistry.PIsland neIsland(long cell) {
        return neRs.islandRegistry.byCell(cell);
    }

    /** Overworld farm covering grid cells (0..1, 0..1); Nether pad at (0,0).
     *  Down-projection of the farm is cell (0,0)+dilation — they intersect. */
    private void standardIntersectingTopo() {
        topo(owRs, 1_000, new long[]{cellKey(0, 0), cellKey(1, 0), cellKey(0, 1), cellKey(1, 1)});
        topo(neRs, 1_000, new long[]{cellKey(0, 0)});
    }

    // ------------------------------------------------------------ tests

    @Test
    void lateAnchorFailureCannotReleaseAReacquiredClaim() throws Exception {
        standardIntersectingTopo();
        IslandRegistry.PIsland member = owIsland(cellKey(0, 0));
        assertTrue(member.tryClaimAutonomousWork());
        member.noteClaim("crossdim-anchor");
        ArrayList<IslandRegistry.PIsland> receipts = new ArrayList<>();
        receipts.add(member);
        Method release = CrossDimLinks.class.getDeclaredMethod(
                "releaseAnchorClaims", ArrayList.class, Throwable.class);
        release.setAccessible(true);

        // A later member refused admission: the accepted prefix is released.
        release.invoke(null, receipts, null);
        assertTrue(member.tryClaimAutonomousWork(), "another owner must be able to acquire");
        member.noteClaim("replacement-worker");
        Throwable lateFailure = new IllegalStateException("W before deferred-anchor commit");
        try {
            InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                    () -> release.invoke(null, receipts, lateFailure));
            assertSame(lateFailure, thrown.getCause());
            assertFalse(member.tryClaimAutonomousWork(),
                    "late failure cleanup must not make the replacement owner's claim available");
            assertEquals("replacement-worker", member.claimSite,
                    "late failure cleanup must not erase the replacement owner's ledger");
        } finally {
            member.clearClaim();
            member.unitClaim.set(false);
        }
    }

    @Test
    void extremeValidScaleLinksOnlyPopulatedIntersectingCells() {
        owLevel.fabricatedType = dimType(30_000_000.0);
        neLevel.fabricatedType = dimType(0.00001);
        topo(owRs, 1_000, new long[]{cellKey(0, 0)});
        topo(neRs, 1_000,
                new long[]{cellKey(-1, 0), cellKey(0, 0), cellKey(200_000, 200_000)},
                new long[]{cellKey(-2, 0)});

        CrossDimLinks.onRebuild(owRs);

        assertEquals(1, CrossDimLinks.linksView().size());
        CrossDimLinks.Link link = CrossDimLinks.linksView().get(0);
        assertSame(neIsland(cellKey(0, 0)), link.nether);
        assertEquals(neIsland(cellKey(0, 0)).cells, link.sharedCells);
        assertNotNull(link.nether.crossDimCoupling);
        assertNull(neIsland(cellKey(-2, 0)).crossDimCoupling);
    }

    /** Maintainer test 1: intersecting Overworld/Nether projections link,
     *  couple, and report through the diagnostic. */
    @Test
    void intersectingProjectionsLinkAndCouple() {
        standardIntersectingTopo();
        wall(1_000);
        CrossDimLinks.onRebuild(owRs);

        assertEquals(1, CrossDimLinks.linksView().size(), "one link must form");
        assertEquals(formed0 + 1, CrossDimLinks.linksFormed);
        IslandRegistry.PIsland ow = owIsland(cellKey(0, 0));
        IslandRegistry.PIsland ne = neIsland(cellKey(0, 0));
        assertNotNull(ow.crossDimCoupling, "overworld member must be coupled");
        assertSame(ow.crossDimCoupling, ne.crossDimCoupling, "one coupling for both members");
        assertEquals(TemporalControl.globalIslandTargetNanos,
                ow.crossDimCoupling.sharedTargetNanos, "no overrides -> global rate shared");
        assertEquals(anchors0 + 1, CrossDimLinks.anchorsApplied, "anchor applied at link");

    }

    /** Maintainer test 2: non-intersecting projections do not link. The pad
     *  at down-space cell (64,64) is far outside the farm's projection. */
    @Test
    void nonIntersectingProjectionsDoNotLink() {
        topo(owRs, 1_000, new long[]{cellKey(0, 0), cellKey(1, 0), cellKey(0, 1), cellKey(1, 1)});
        topo(neRs, 1_000, new long[]{cellKey(64, 64)});
        wall(1_000);
        CrossDimLinks.onRebuild(owRs);

        assertTrue(CrossDimLinks.linksView().isEmpty(), "no link may form");
        assertNull(owIsland(cellKey(0, 0)).crossDimCoupling);
        assertNull(neIsland(cellKey(64, 64)).crossDimCoupling);
    }

    /** Maintainer test 3 (wiring half; the geometry itself is
     *  CrossDimProjectionTest): the two levels rebuild at different times on
     *  the same main thread and there is ONE authority — a second rebuild
     *  from the partner side neither duplicates the link nor re-anchors it. */
    @Test
    void bothLevelsRebuildsSeeOneAuthority() {
        standardIntersectingTopo();
        wall(1_000);
        CrossDimLinks.onRebuild(owRs);
        assertEquals(1, CrossDimLinks.linksView().size());
        CrossDimLinks.Link link = CrossDimLinks.linksView().get(0);
        long anchorsAfterFirst = CrossDimLinks.anchorsApplied;

        wall(1_007); // nether's rebuild lands seven ticks later
        CrossDimLinks.onRebuild(neRs);

        assertEquals(1, CrossDimLinks.linksView().size(), "no duplicate from the partner's pass");
        assertSame(link, CrossDimLinks.linksView().get(0), "same link identity");
        assertEquals(1_000, link.linkedAtWall, "age preserved across the partner's pass");
        assertEquals(1_007, link.lastIntersectWall, "presence refreshed by the partner's pass");
        assertEquals(anchorsAfterFirst, CrossDimLinks.anchorsApplied,
                "an established coupling must not re-anchor every pass");
    }

    /** Maintainer test 6 (scheduling layer): the anchor honours the EARLIEST
     *  member obligation — linking never postpones either member's next unit,
     *  so it cannot add a tick of transfer latency over the pre-link
     *  baseline. (The anchor removes drift; it does not remove the existing
     *  ~1 destination-tick latency — asserted as no REGRESSION.) */
    @Test
    void anchorNeverDelaysEitherMembersNextUnit() {
        standardIntersectingTopo();
        long now = System.nanoTime();
        IslandRegistry.PIsland ow = owIsland(cellKey(0, 0));
        IslandRegistry.PIsland ne = neIsland(cellKey(0, 0));
        long owPre = now + 30_000_000L;
        long nePre = now + 70_000_000L;
        ow.nextDeadlineNanos = owPre;
        ne.nextDeadlineNanos = nePre;

        wall(1_000);
        CrossDimLinks.onRebuild(owRs);

        assertEquals(owPre, ow.nextDeadlineNanos, "earliest obligation is the anchor");
        assertEquals(owPre, ne.nextDeadlineNanos, "partner aligned to the same anchor");
        assertTrue(ow.nextDeadlineNanos <= owPre && ne.nextDeadlineNanos <= nePre,
                "neither member's deadline may move LATER at link time");
    }

    /**
     * Maintainer test 4, through the REAL {@code IslandTickUnit.complete()}:
     * both members roll their deadline from the shared anchor by the shared
     * lower-TPS-wins rate. The rate override sits on the OVERWORLD member
     * only, so the NETHER member advancing by 100 ms (not its own 50 ms) is
     * exactly the coupling hook's doing — with the hook reverted this fails.
     * Oracle per review D6: dΔ/dunit == 0 (equal deadlines after equal unit
     * counts), NOT ΔL == 0 — each island keeps its own clock.
     */
    @Test
    void realCompleteRollsBothMembersAtTheSharedRate() throws Exception {
        standardIntersectingTopo();
        IslandRegistry.PIsland ow = owIsland(cellKey(0, 0));
        IslandRegistry.PIsland ne = neIsland(cellKey(0, 0));
        TemporalControl.addRateSession(owRs, ow, 10.0, "test-10tps"); // 100ms/tick
        long now = System.nanoTime();
        ow.nextDeadlineNanos = now;
        ne.nextDeadlineNanos = now + 5_000_000L;

        wall(1_000);
        CrossDimLinks.onRebuild(owRs);

        CrossDimLinks.Coupling c = ow.crossDimCoupling;
        assertNotNull(c);
        assertEquals(100_000_000L, c.sharedTargetNanos,
                "lower-TPS-wins: max nanos over the members");
        long anchor = ow.nextDeadlineNanos;
        assertEquals(anchor, ne.nextDeadlineNanos, "anchored together");

        long lOwBefore = ow.logicalTick;
        long lNeBefore = ne.logicalTick;
        long start = System.nanoTime();
        invokeComplete(ow, start);
        invokeComplete(ne, start);

        assertEquals(anchor + 100_000_000L, ow.nextDeadlineNanos,
                "overworld member rolls at the shared rate");
        assertEquals(anchor + 100_000_000L, ne.nextDeadlineNanos,
                "nether member rolls at the SHARED rate, not its own 50ms — "
                        + "this is the coupling hook in complete()");
        assertEquals(0L, ow.nextDeadlineNanos - ne.nextDeadlineNanos,
                "dΔ/dunit == 0: the offset stops growing");
        assertEquals(lOwBefore + 1, ow.logicalTick, "phase-lock: own clock, own ++");
        assertEquals(lNeBefore + 1, ne.logicalTick, "phase-lock: own clock, own ++");
    }

    /** Negative control for the cadence test: two UNLINKED islands with
     *  different rates roll apart from the same anchor — the drift the
     *  coupling exists to remove. */
    @Test
    void unlinkedControlIslandsDriftApart() throws Exception {
        topo(owRs, 1_000, new long[]{cellKey(0, 0)});
        topo(neRs, 1_000, new long[]{cellKey(64, 64)}); // no intersection
        IslandRegistry.PIsland ow = owIsland(cellKey(0, 0));
        IslandRegistry.PIsland ne = neIsland(cellKey(64, 64));
        TemporalControl.addRateSession(owRs, ow, 10.0, "test-10tps");
        wall(1_000);
        CrossDimLinks.onRebuild(owRs);
        assertNull(ow.crossDimCoupling);

        long now = System.nanoTime();
        ow.nextDeadlineNanos = now;
        ne.nextDeadlineNanos = now;
        invokeComplete(ow, now);
        invokeComplete(ne, now);
        assertEquals(50_000_000L, Math.abs(ow.nextDeadlineNanos - ne.nextDeadlineNanos),
                "uncoupled members with different rates drift by the rate difference per unit");
    }

    /** Maintainer test 5: unlink only after the grace period, with no clock
     *  writes and no deadline writes — the carried deadline is each member's
     *  exact next obligation (no double tick, no skipped tick, Δ=0 split). */
    @Test
    void unlinkOnlyAfterGraceAndWithoutClockOrDeadlineWrites() {
        standardIntersectingTopo();
        wall(1_000);
        CrossDimLinks.onRebuild(owRs);
        assertEquals(1, CrossDimLinks.linksView().size());
        IslandRegistry.PIsland ow = owIsland(cellKey(0, 0));
        IslandRegistry.PIsland neOld = neIsland(cellKey(0, 0));

        // The pad unloads: the nether member retires (no successor).
        topo(neRs, 1_050, new long[]{cellKey(64, 64)});
        assertTrue(neOld.retired);
        long lOw = ow.logicalTick;
        long lNe = neOld.logicalTick;
        long dOw = ow.nextDeadlineNanos;
        long dNe = neOld.nextDeadlineNanos;

        wall(1_050);
        CrossDimLinks.onRebuild(neRs);
        assertEquals(1, CrossDimLinks.linksView().size(),
                "absence for 50t < grace 100t must NOT unlink");
        assertNull(ow.crossDimCoupling, "coupling suspends while a member is gone");

        wall(1_099);
        CrossDimLinks.onRebuild(neRs);
        assertEquals(1, CrossDimLinks.linksView().size(), "99t < 100t: still held");

        wall(1_100);
        CrossDimLinks.onRebuild(neRs);
        assertTrue(CrossDimLinks.linksView().isEmpty(), "grace expired: unlinked");
        assertEquals(dropped0 + 1, CrossDimLinks.linksDropped);

        assertEquals(lOw, ow.logicalTick, "no clock writes anywhere in link/unlink");
        assertEquals(lNe, neOld.logicalTick, "no clock writes anywhere in link/unlink");
        assertEquals(dOw, ow.nextDeadlineNanos,
                "unlink leaves the deadline as the exact next obligation");
        assertEquals(dNe, neOld.nextDeadlineNanos,
                "unlink leaves the deadline as the exact next obligation");
    }

    /** A flapping intersection (population flicker + island identity churn)
     *  must never thrash the link: hysteresis holds it, and lineage
     *  re-resolution re-attaches a reborn member to the SAME link. */
    @Test
    void flappingIntersectionAndMemberChurnNeverThrash() {
        standardIntersectingTopo();
        wall(1_000);
        CrossDimLinks.onRebuild(owRs);
        assertEquals(1, CrossDimLinks.linksView().size());
        CrossDimLinks.Link link = CrossDimLinks.linksView().get(0);

        long t = 1_000;
        for (int i = 0; i < 10; i++) {
            // pad vanishes (member retires — the harshest churn shape)
            t += 10;
            topo(neRs, t, new long[]{cellKey(64, 64)});
            wall(t);
            CrossDimLinks.onRebuild(neRs);
            assertEquals(1, CrossDimLinks.linksView().size(),
                    "flap " + i + ": link must survive inside the grace window");
            // pad returns as a NEW island identity covering the same cells
            t += 10;
            topo(neRs, t, new long[]{cellKey(0, 0)});
            wall(t);
            CrossDimLinks.onRebuild(neRs);
            assertEquals(1, CrossDimLinks.linksView().size());
            assertSame(link, CrossDimLinks.linksView().get(0),
                    "flap " + i + ": lineage re-attaches the reborn member to the SAME link");
            assertFalse(CrossDimLinks.linksView().get(0).nether.retired,
                    "flap " + i + ": member ref healed to the live island");
        }
        assertEquals(formed0 + 1, CrossDimLinks.linksFormed, "exactly one link ever formed");
        assertEquals(dropped0, CrossDimLinks.linksDropped, "zero unlink events under flapping");
    }

    /** Review D3: linking bumps pacingGen so a pacer DelayQueue entry
     *  scheduled before the link fires with a stale gen and goes inert —
     *  no pre-link cadence authority can tick a coupled member at its old
     *  rate ("submits a coupled member solo" in the smaller mechanism). */
    @Test
    void linkingBumpsPacingGenSoPreLinkPacerEntriesGoInert() {
        standardIntersectingTopo();
        IslandRegistry.PIsland ow = owIsland(cellKey(0, 0));
        IslandRegistry.PIsland ne = neIsland(cellKey(0, 0));
        long owGen = ow.pacingGen; // what a pre-link pacer Entry would capture
        long neGen = ne.pacingGen;

        wall(1_000);
        CrossDimLinks.onRebuild(owRs);

        assertTrue(ow.pacingGen > owGen,
                "link must bump the overworld member's pacingGen (Pacer fire-time check "
                        + "e.gen != island.pacingGen makes the stale entry inert)");
        assertTrue(ne.pacingGen > neGen, "and the nether member's");
    }

    /** Maintainer test 7 (structural half): the End can never appear. Parse
     *  refusal for any table entry naming it... */
    @Test
    void endPairEntriesAreRefusedAtParse() {
        FabroliaConfig.crossDimLinkedPairs =
                new ArrayList<>(List.of("minecraft:overworld=minecraft:the_end"));
        standardIntersectingTopo(); // geometry WOULD intersect
        wall(1_000);
        CrossDimLinks.onRebuild(owRs);

        assertTrue(CrossDimLinks.linksView().isEmpty(),
                "an End-bearing table must disable linking entirely");
        assertTrue(CrossDimLinks.refusedPairEntries > refused0, "entry counted as refused");
        assertNull(owIsland(cellKey(0, 0)).crossDimCoupling);
    }

    /** ...and by construction: an End level never resolves as a pair member,
     *  and handing one to the manager directly is refused outright. */
    @Test
    void endLevelIsRefusedByConstruction() throws Exception {
        LinkLevel endLevel = LinkLevel.alloc(Level.END, 1.0);
        RegionizedServer endRs = allocRs(endLevel);
        topo(endRs, 1_000, new long[]{cellKey(0, 0)});
        Map<ServerLevel, RegionizedServer> perLevel =
                getStatic(RegionScheduler.class, "PER_LEVEL");
        perLevel.put(endLevel, endRs);

        // The End sitting where the Nether would geometrically link: no link.
        topo(owRs, 1_000, new long[]{cellKey(0, 0), cellKey(1, 1)});
        topo(neRs, 1_000, new long[]{cellKey(64, 64)}); // nether elsewhere
        wall(1_000);
        CrossDimLinks.onRebuild(owRs);
        for (CrossDimLinks.Link l : CrossDimLinks.linksView()) {
            assertNotSame(endRs.level, l.nether.level, "an End island can never be a link member");
            assertNotSame(endRs.level, l.ow.level, "an End island can never be a link member");
        }
        for (IslandRegistry.PIsland p : endRs.islandRegistry.islands()) {
            assertNull(p.crossDimCoupling, "no End island may ever be coupled");
        }

        // Direct refusal, before any state is touched.
        CrossDimLinks.onRebuild(endRs);
        assertTrue(CrossDimLinks.endRefusals > endRefusals0, "direct End rebuild refused");
    }

    /** Review D7: a coherent-pinned member refuses coupling (the pin's
     *  L-tracks-W contract is incompatible with a shared cadence — under it
     *  a pinned member with L &lt; W could never close the gap); the link
     *  itself stays, and coupling resumes when the pin is lifted. */
    @Test
    void coherentPinSuspendsCouplingAndLiftResumesIt() {
        standardIntersectingTopo();
        IslandRegistry.PIsland ne = neIsland(cellKey(0, 0));
        TemporalControl.addCoherentSession(neRs, ne, "test-pin");

        wall(1_000);
        CrossDimLinks.onRebuild(owRs);
        assertEquals(1, CrossDimLinks.linksView().size(), "the geometric link still forms");
        assertNull(owIsland(cellKey(0, 0)).crossDimCoupling,
                "coupling must be refused while a member is coherent-pinned");
        assertNull(ne.crossDimCoupling);

        // Lift the pin: the drive-head pass resumes the coupling.
        neRs.temporalSessions.clear();
        wall(1_001);
        CrossDimLinks.mainTick();
        assertNotNull(ne.crossDimCoupling, "coupling resumes once the pin is lifted");
        assertSame(ne.crossDimCoupling, owIsland(cellKey(0, 0)).crossDimCoupling);
    }

    /** Local sprint is an operator session too — same refusal. */
    @Test
    void localSprintSuspendsCoupling() {
        standardIntersectingTopo();
        wall(1_000);
        CrossDimLinks.onRebuild(owRs);
        IslandRegistry.PIsland ow = owIsland(cellKey(0, 0));
        assertNotNull(ow.crossDimCoupling);

        TemporalControl.addSprintSession(owRs, ow, 100, "test-sprint");
        CrossDimLinks.mainTick();
        assertNull(ow.crossDimCoupling, "sprint outranks coupling");
        assertNull(neIsland(cellKey(0, 0)).crossDimCoupling);
        assertTrue(CrossDimLinks.couplingsSuspended > suspended0);
    }

    /** The runtime toggle (config-backed, per the house rule) drops all links
     *  at the next drive-head pass. */
    @Test
    void disablingTheToggleDropsEverything() {
        standardIntersectingTopo();
        wall(1_000);
        CrossDimLinks.onRebuild(owRs);
        assertEquals(1, CrossDimLinks.linksView().size());

        FabroliaConfig.fabroliaCrossDimCoupling = false;
        CrossDimLinks.mainTick();
        assertTrue(CrossDimLinks.linksView().isEmpty());
        assertNull(owIsland(cellKey(0, 0)).crossDimCoupling);
        assertNull(neIsland(cellKey(0, 0)).crossDimCoupling);
    }

}
