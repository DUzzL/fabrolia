package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scheduled-tick clock-domain lifecycle (2026-07-27 audit) — THE invariant:
 *
 * <p><b>"A delay of N logical ticks executes after N owner-island epochs,
 * exactly once, regardless of tick rate, ownership transition, save/reload,
 * or clock-domain replacement."</b>
 *
 * <p>Containers are REAL vanilla {@link LevelChunkTicks} (schedule / peek /
 * poll / pack / unpack / dedup are the genuine data structures); the shift
 * algorithm is the REAL {@link TickDomains#shiftAll}. Only the pieces
 * the mixins add at runtime are mirrored statement-for-statement, following
 * the suite convention (see VibrationClockDomainTest):
 *
 * <ul>
 *   <li>{@link Tagged} — the {@code FabroliaTickDomain} tag fields
 *       LevelChunkTicksMixin adds to every container;</li>
 *   <li>{@link Clocks} — {@code TickDomains.resolveNow}'s resolution order
 *       (live island → retired last-L snapshot → MIN_VALUE);</li>
 *   <li>{@link #rebaseTo} — {@code TickDomains.rebaseTo} (same-id shortcut,
 *       unresolvable-tag adopt, delta shift via the production shiftAll);</li>
 *   <li>{@link #rebaseSchedule} — {@code IslandCommands.rebaseScheduledTick}'s
 *       delta math with the audited ctx-identity resolution;</li>
 *   <li>{@link #unpackBasis} — {@code TickDomains.unpackBasis}, the fix that
 *       anchors loaded relative delays on the TAGGED clock instead of the
 *       promoting thread's clock;</li>
 *   <li>{@link #saveTicks}/{@link #loadTicks} — the SerializableChunkDataMixin
 *       save basis and the register(retag)-then-unpack(anchor) load seam;</li>
 *   <li>{@link #stepIsland}/{@link #stepWall} — a unit collects at
 *       {@code L+1} then completes L (IslandTickUnit), the wall lane advances
 *       then collects (ServerLevel.tickTime), draining due heads in vanilla
 *       remove-then-execute order (IslandScheduledTicks.runSlice).</li>
 * </ul>
 */
class ScheduledTickClockDomainTest {

    private static final long WALL = FabroliaTickDomain.WALL;
    /** One game hour in ticks — the "scheduled hours into the future" bar. */
    private static final long HOUR = 72_000;

    // ------------------------------------------------------------------
    // Mirrors
    // ------------------------------------------------------------------

    /** Real vanilla container + mirror of the mixin's domain-tag fields. */
    private static final class Tagged<T> {
        final LevelChunkTicks<T> ticks;
        long tagId = WALL;
        long tagGen = -1;

        Tagged() {
            this.ticks = new LevelChunkTicks<>();
        }

        Tagged(List<SavedTick<T>> pending) {
            this.ticks = new LevelChunkTicks<>(pending);
        }
    }

    /** Mirror clock world; resolution order mirrors TickDomains.resolveNow. */
    private static final class Clocks {
        long wall;
        final Map<Long, long[]> live = new HashMap<>();   // islandId -> {L}
        final Map<Long, Long> lastL = new HashMap<>();    // retired final clocks

        Clocks(long wall) {
            this.wall = wall;
        }

        long addIsland(long id, long bornL) {
            live.put(id, new long[]{bornL});
            return id;
        }

        long resolve(long id) {
            if (id == WALL) return wall;
            long[] l = live.get(id);
            if (l != null) return l[0];
            Long last = lastL.get(id);
            return last != null ? last : Long.MIN_VALUE;
        }

        void retire(long id) {
            long[] l = live.remove(id);
            if (l != null) lastL.put(id, l[0]);
        }
    }

    /** Mirror of TickDomains.rebaseTo; the shift is the PRODUCTION shiftAll. */
    private static int rebaseTo(Clocks c, Tagged<?> t, long targetId, long targetGen, long targetNow) {
        if (t.tagId == targetId) {
            t.tagGen = targetGen;
            return 0;
        }
        long tagNow = c.resolve(t.tagId);
        if (tagNow == Long.MIN_VALUE) {
            t.tagId = targetId;
            t.tagGen = targetGen;
            return 0;
        }
        long delta = targetNow - tagNow;
        int moved = 0;
        if (delta != 0 && t.ticks.count() > 0) {
            moved = shiftErased(t.ticks, delta);
        }
        t.tagId = targetId;
        t.tagGen = targetGen;
        return moved;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int shiftErased(LevelChunkTicks<?> c, long delta) {
        return TickDomains.shiftAll((LevelChunkTicks) c, delta);
    }

    /** Mirror of IslandCommands.rebaseScheduledTick's conversion: delay is
     *  clock-invariant, newTrigger = trigger + (tagNow − ctxNow); same-domain
     *  calls return the tick untouched. */
    private static <T> ScheduledTick<T> rebaseSchedule(Clocks c, Tagged<T> tc, long ctxId, ScheduledTick<T> tick) {
        if (tc.tagId == ctxId) return tick;
        long tagNow = c.resolve(tc.tagId);
        if (tagNow == Long.MIN_VALUE) return tick;
        long ctxNow = c.resolve(ctxId);
        long delta = tagNow - ctxNow;
        if (delta == 0) return tick;
        return new ScheduledTick<>(tick.type(), tick.pos(), tick.triggerTick() + delta,
                tick.priority(), tick.subTickOrder());
    }

    private long subTick = 0;

    /** Mirror of LevelAccessor.createTick (trigger = ctxNow + delay on the
     *  CALLER's clock) + LevelTicksMixin's atomic rebase-and-insert. */
    private <T> ScheduledTick<T> schedule(Clocks c, Tagged<T> tc, long ctxId, T type, BlockPos pos, int delay) {
        ScheduledTick<T> t = new ScheduledTick<>(type, pos, c.resolve(ctxId) + delay,
                TickPriority.NORMAL, subTick++);
        ScheduledTick<T> rebased = rebaseSchedule(c, tc, ctxId, t);
        tc.ticks.schedule(rebased);
        return rebased;
    }

    /** Mirror of TickDomains.unpackBasis (the load-anchor fix). */
    private static long unpackBasis(Clocks c, Tagged<?> tc, long callerNow) {
        if (tc.tagId == WALL) return c.wall;
        long now = c.resolve(tc.tagId);
        return now == Long.MIN_VALUE ? callerNow : now;
    }

    /** Mirror of the save seam: pack against the TAGGED domain's now
     *  (SerializableChunkDataMixin -> TickDomains.saveBasis). */
    private static <T> List<SavedTick<T>> saveTicks(Clocks c, Tagged<T> tc) {
        long basis = c.resolve(tc.tagId);
        if (basis == Long.MIN_VALUE) basis = c.wall;
        return tc.ticks.pack(basis);
    }

    /** Mirror of the load seam: construct →
     *  register into owner territory (onContainerInstalled: retag only — the
     *  queue is empty, pending delays are relative) → unpack anchored on the
     *  TAGGED clock (the fix). {@code vanillaCallerNow} is what vanilla's
     *  startTickingChunk would have passed (main's wall clock). */
    private static <T> Tagged<T> loadTicks(Clocks c, List<SavedTick<T>> saved, long ownerId, long ownerGen) {
        Tagged<T> tc = new Tagged<>(saved);
        if (ownerId != WALL) {
            int moved = rebaseTo(c, tc, ownerId, ownerGen, c.resolve(ownerId));
            // registration must never shift RELATIVE pending delays
            assertEquals(0, moved, "install rebase of a fresh deserialized container must only retag");
        }
        tc.ticks.unpack(unpackBasis(c, tc, c.wall));
        return tc;
    }

    /** Vanilla collect+run essentials scoped to one container: drain due heads
     *  in DRAIN_ORDER, remove-then-execute (IslandScheduledTicks.runSlice). */
    private static <T> List<ScheduledTick<T>> runDue(Tagged<T> tc, long laneNow) {
        List<ScheduledTick<T>> ran = new ArrayList<>();
        for (ScheduledTick<T> p; (p = tc.ticks.peek()) != null && p.triggerTick() <= laneNow; ) {
            ran.add(tc.ticks.poll());
        }
        return ran;
    }

    /** One island unit: collect at L+1 (unitGameTime), then L++. Returns the
     *  unit time the collection ran at. */
    @SafeVarargs
    private static <T> long stepIsland(Clocks c, long id, List<ScheduledTick<T>> fired, Tagged<T>... containers) {
        long unitTime = c.live.get(id)[0] + 1;
        for (Tagged<T> tc : containers) {
            fired.addAll(runDue(tc, unitTime));
        }
        c.live.get(id)[0] = unitTime;
        return unitTime;
    }

    /** One wall tick: gameTime++, then the wall lane collects at gameTime. */
    @SafeVarargs
    private static <T> long stepWall(Clocks c, List<ScheduledTick<T>> fired, Tagged<T>... containers) {
        c.wall++;
        for (Tagged<T> tc : containers) {
            fired.addAll(runDue(tc, c.wall));
        }
        return c.wall;
    }

    /** Runs island units until the single expected tick fires; asserts it
     *  fires at exactly {@code expectedUnit}, exactly once, and never again
     *  within {@code extraUnits} further units. */
    private static <T> void assertFiresOnceAt(Clocks c, long islandId, Tagged<T> tc,
                                              long expectedUnit, int extraUnits) {
        List<ScheduledTick<T>> fired = new ArrayList<>();
        long guard = expectedUnit + extraUnits;
        while (fired.isEmpty()) {
            long unit = stepIsland(c, islandId, fired, tc);
            assertTrue(unit <= guard, "tick failed to fire by unit " + guard);
            if (!fired.isEmpty()) {
                assertEquals(expectedUnit, unit, "fired at wrong island epoch");
            }
        }
        assertEquals(1, fired.size(), "must fire exactly once");
        for (int i = 0; i < extraUnits; i++) {
            List<ScheduledTick<T>> again = new ArrayList<>();
            stepIsland(c, islandId, again, tc);
            assertEquals(0, again.size(), "tick fired twice");
        }
    }

    // ------------------------------------------------------------------
    // 1–2: delays × lanes × rates on the owner island
    // ------------------------------------------------------------------

    @Test
    void delaysExecuteAfterExactlyNOwnerEpochs_blockAndFluidLanes() {
        for (int delay : new int[]{0, 1, 2, 20, 5000}) {
            Clocks c = new Clocks(500);              // wall diverged from L
            long island = c.addIsland(7, 1000);
            Tagged<String> block = new Tagged<>();
            Tagged<String> fluid = new Tagged<>();
            block.tagId = island;
            fluid.tagId = island;
            schedule(c, block, island, "repeater", new BlockPos(1, 64, 1), delay);
            schedule(c, fluid, island, "water", new BlockPos(2, 64, 2), delay);
            long trigger = 1000 + delay;
            long expected = Math.max(trigger, 1001); // delay 0: next collection
            List<ScheduledTick<String>> fired = new ArrayList<>();
            long unit = 0;
            while (fired.isEmpty()) {
                unit = stepIsland(c, island, fired, block, fluid);
                assertTrue(unit <= expected, "delay " + delay + " missed its epoch");
            }
            assertEquals(expected, unit, "delay " + delay + " fired at the wrong epoch");
            assertEquals(2, fired.size(), "block AND fluid lanes fire together");
            assertEquals(List.of("repeater", "water"),
                    fired.stream().map(ScheduledTick::type).toList());
            for (int i = 0; i < 10; i++) stepIsland(c, island, fired, block, fluid);
            assertEquals(2, fired.size(), "exactly once per lane");
        }
    }

    @Test
    void delayCountsOwnerEpochsRegardlessOfTickRate() {
        // 10 / 20 / 40 TPS = 1 unit per 2 wall ticks / 1 per 1 / 2 per 1.
        // The SAME delay-20 tick fires after exactly 20 UNITS on each island;
        // only the wall time elapsed differs (40 / 20 / 10 wall ticks).
        int[][] rates = {{1, 2}, {1, 1}, {2, 1}};     // {units, per wall ticks}
        long[] expectedWallElapsed = {40, 20, 10};
        for (int r = 0; r < rates.length; r++) {
            Clocks c = new Clocks(10_000);
            long island = c.addIsland(3, 70_000);     // heavily diverged clock
            Tagged<String> tc = new Tagged<>();
            tc.tagId = island;
            schedule(c, tc, island, "repeater", new BlockPos(0, 64, 0), 20);
            List<ScheduledTick<String>> fired = new ArrayList<>();
            long wall0 = c.wall;
            int units = 0;
            outer:
            while (true) {
                for (int w = 0; w < rates[r][1]; w++) c.wall++;
                for (int u = 0; u < rates[r][0]; u++) {
                    stepIsland(c, island, fired, tc);
                    units++;
                    if (!fired.isEmpty()) break outer;
                }
            }
            assertEquals(20, units, "delay 20 must consume exactly 20 owner epochs");
            assertEquals(expectedWallElapsed[r], c.wall - wall0);
            assertEquals(1, fired.size());
        }
    }

    // ------------------------------------------------------------------
    // 3–4: cross-domain scheduling (main ↔ island, island ↔ island)
    // ------------------------------------------------------------------

    @Test
    void mainScheduledIntoIslandContainerFiresAfterNIslandEpochs() {
        // Wall far AHEAD of island L (post-/tick-sprint shape). An unrebased
        // main-clock trigger would park (W − L) + 3 = 90_003 island epochs out
        // — beyond the hours bar. The rebase must place it at L + 3.
        Clocks c = new Clocks(200_000);
        long island = c.addIsland(5, 110_000);
        Tagged<String> tc = new Tagged<>();
        tc.tagId = island;
        ScheduledTick<String> t = schedule(c, tc, WALL, "observer", new BlockPos(3, 64, 3), 3);
        assertEquals(110_003, t.triggerTick(), "trigger must be re-expressed on the owner clock");
        assertTrue(200_000 + 3 - 110_000 > HOUR, "unrebased trigger would be hours-class late");
        assertFiresOnceAt(c, island, tc, 110_003, 10);
    }

    @Test
    void islandScheduledIntoWallContainerFiresAfterNWallTicks() {
        Clocks c = new Clocks(4_000);
        long island = c.addIsland(9, 250_000);        // island far ahead of wall
        Tagged<String> tc = new Tagged<>();           // WALL-tagged (gap chunk)
        ScheduledTick<String> t = schedule(c, tc, island, "piston", new BlockPos(4, 64, 4), 5);
        assertEquals(4_005, t.triggerTick());
        List<ScheduledTick<String>> fired = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            stepWall(c, fired, tc);
        }
        assertEquals(1, fired.size());
        assertEquals(4_005, c.wall);
    }

    @Test
    void sourceAndDestinationIslandsAtDifferentRatesAndClocks() {
        Clocks c = new Clocks(1_000);
        long src = c.addIsland(1, 5_000);             // fast island (clock arbitrary)
        long dst = c.addIsland(2, 90_000);            // slow island, huge divergence
        Tagged<String> tc = new Tagged<>();
        tc.tagId = dst;
        ScheduledTick<String> t = schedule(c, tc, src, "comparator", new BlockPos(5, 64, 5), 4);
        assertEquals(90_004, t.triggerTick(), "delay is clock-invariant across islands");
        assertFiresOnceAt(c, dst, tc, 90_004, 10);
    }

    // ------------------------------------------------------------------
    // 5–6: rate change while pending; rebase idempotence
    // ------------------------------------------------------------------

    @Test
    void rateChangeWhilePendingNeverMovesTheTrigger() {
        Clocks c = new Clocks(0);
        long island = c.addIsland(11, 100);
        Tagged<String> tc = new Tagged<>();
        tc.tagId = island;
        schedule(c, tc, island, "repeater", new BlockPos(6, 64, 6), 20);
        List<ScheduledTick<String>> fired = new ArrayList<>();
        // 5 units at "20 TPS" (1 wall tick each)
        for (int i = 0; i < 5; i++) {
            c.wall++;
            stepIsland(c, island, fired, tc);
            assertEquals(120, tc.ticks.peek().triggerTick(), "pending trigger must never move");
        }
        // rate change: island now at "40 TPS" (2 units per wall tick)
        while (fired.isEmpty()) {
            c.wall++;
            stepIsland(c, island, fired, tc);
            if (fired.isEmpty()) stepIsland(c, island, fired, tc);
        }
        assertEquals(1, fired.size());
        assertEquals(120, fired.get(0).triggerTick(), "fired at L0+20 despite the rate change");
    }

    @Test
    void alreadyRebasedContainerIsNeverRebasedTwice() {
        Clocks c = new Clocks(1_000);
        long island = c.addIsland(4, 50_000);
        Tagged<String> tc = new Tagged<>();
        tc.tagId = island;
        schedule(c, tc, island, "torch", new BlockPos(7, 64, 7), 2);
        assertEquals(50_002, tc.ticks.peek().triggerTick());
        // Same-id rebase (any targetNow, any gen churn): tag-equality shortcut
        // — 0 moved, trigger bit-identical. This is the anti-double-rebase
        // property gen-changing island rebuilds rely on.
        assertEquals(0, rebaseTo(c, tc, island, 99, 123_456));
        assertEquals(50_002, tc.ticks.peek().triggerTick());
        // Round trip island → WALL → island preserves the remaining delay
        // exactly (delta out + delta back cancel).
        rebaseTo(c, tc, WALL, -1, c.wall);
        assertEquals(1_002, tc.ticks.peek().triggerTick());
        rebaseTo(c, tc, island, 4, c.resolve(island));
        assertEquals(50_002, tc.ticks.peek().triggerTick());
        // Schedule-side shortcut: same-domain calls return the identical tick.
        ScheduledTick<String> raw = new ScheduledTick<>("torch2", new BlockPos(8, 64, 8),
                50_010, TickPriority.NORMAL, 77);
        assertSame(raw, rebaseSchedule(c, tc, island, raw));
    }

    // ------------------------------------------------------------------
    // 7–9: merge, split, retire/reborn (island identity replacement)
    // ------------------------------------------------------------------

    @Test
    void mergeRebasesAbsorbedOntoSurvivorClockAndLeavesSurvivorAlone() {
        Clocks c = new Clocks(0);
        long survivor = c.addIsland(1, 5_000);
        long absorbed = c.addIsland(2, 5_600);        // ahead: negative delta
        Tagged<String> sc = new Tagged<>();
        Tagged<String> ac = new Tagged<>();
        sc.tagId = survivor;
        ac.tagId = absorbed;
        schedule(c, sc, survivor, "s", new BlockPos(9, 64, 9), 1);    // 5_001
        schedule(c, ac, absorbed, "a", new BlockPos(10, 64, 10), 2);  // 5_602
        // MERGE: survivor keeps its clock; absorbed members rebase onto it
        // (assignDomain). Negative deltas preserve the relative delay.
        int moved = rebaseTo(c, ac, survivor, 1, c.resolve(survivor));
        assertEquals(1, moved);
        assertEquals(5_002, ac.ticks.peek().triggerTick());
        assertEquals(5_001, sc.ticks.peek().triggerTick(), "survivor container untouched");
        c.retire(absorbed);
        List<ScheduledTick<String>> fired = new ArrayList<>();
        assertEquals(5_001, stepIsland(c, survivor, fired, sc, ac));
        assertEquals(List.of("s"), fired.stream().map(ScheduledTick::type).toList());
        assertEquals(5_002, stepIsland(c, survivor, fired, sc, ac));
        assertEquals(List.of("s", "a"), fired.stream().map(ScheduledTick::type).toList());
    }

    @Test
    void splitFragmentInheritsSourceClockWithoutShiftingTriggers() {
        Clocks c = new Clocks(0);
        long source = c.addIsland(1, 7_000);
        Tagged<String> tc = new Tagged<>();
        tc.tagId = source;
        schedule(c, tc, source, "s", new BlockPos(11, 64, 11), 3);    // 7_003
        // SPLIT: fragment born with the SOURCE's logicalTick (IslandRegistry
        // fragment inheritance); the fragment-side rebase is delta 0.
        long fragment = c.addIsland(6, c.resolve(source));
        assertEquals(0, rebaseTo(c, tc, fragment, 6, c.resolve(fragment)));
        assertEquals(7_003, tc.ticks.peek().triggerTick());
        // clocks then diverge freely; the tick fires on the fragment's clock
        c.live.get(source)[0] += 500;                  // source races ahead
        assertFiresOnceAt(c, fragment, tc, 7_003, 10);
    }

    @Test
    void retireCollapseAndRebornIslandPreserveRemainingDelayViaLastL() {
        Clocks c = new Clocks(10_000);
        long x = c.addIsland(3, 40_000);
        Tagged<String> tc = new Tagged<>();
        tc.tagId = x;
        schedule(c, tc, x, "s", new BlockPos(12, 64, 12), 5);         // 40_005
        // island retires; its final L is only reachable through lastL
        c.retire(x);
        // region collapses to the global domain (assignDomain → WALL):
        // resolution goes through the retired snapshot, delay preserved.
        assertEquals(1, rebaseTo(c, tc, WALL, -1, c.wall));
        assertEquals(10_005, tc.ticks.peek().triggerTick());
        // two wall ticks pass; a NEW island is born wall-anchored
        // (bornGameTime) and adopts the chunk: delta = 0 at adoption time.
        List<ScheduledTick<String>> fired = new ArrayList<>();
        stepWall(c, fired, tc);
        stepWall(c, fired, tc);
        assertEquals(0, fired.size());
        long reborn = c.addIsland(8, c.wall);          // L = bornGameTime = 10_002
        assertEquals(0, rebaseTo(c, tc, reborn, 8, c.resolve(reborn)));
        assertEquals(10_005, tc.ticks.peek().triggerTick(), "old trigger vs new origin: no shift needed, no shift applied");
        assertFiresOnceAt(c, reborn, tc, 10_005, 10);  // 2 wall + 3 island epochs = 5 total
    }

    // ------------------------------------------------------------------
    // 10–12: unload/save/reload, the unpack-anchor regression, full restart
    // ------------------------------------------------------------------

    @Test
    void unloadSaveReloadPreservesRemainingDelayOnDivergedIsland() {
        Clocks c = new Clocks(10_000);
        long island = c.addIsland(2, 90_000);          // sprint-ahead island
        Tagged<String> tc = new Tagged<>();
        tc.tagId = island;
        schedule(c, tc, island, "s", new BlockPos(13, 64, 13), 100);  // 90_100
        List<ScheduledTick<String>> fired = new ArrayList<>();
        for (int i = 0; i < 30; i++) stepIsland(c, island, fired, tc);
        assertEquals(0, fired.size());
        // save: packed against the TAGGED clock — true remaining delay
        List<SavedTick<String>> saved = saveTicks(c, tc);
        assertEquals(1, saved.size());
        assertEquals(70, saved.get(0).delay(), "saved delay must be the true remaining delay");
        // reload into the same island: register retags, unpack anchors on L
        Tagged<String> reloaded = loadTicks(c, saved, island, 2);
        assertEquals(island, reloaded.tagId);
        assertEquals(90_100, reloaded.ticks.peek().triggerTick());
        assertFiresOnceAt(c, island, reloaded, 90_100, 10);
    }

    @Test
    void unpackAnchorsOnTaggedClockNotOnPromotingThreadClock() {
        // THE hours-in-the-future regression. Island L far BEHIND wall
        // (island lagging / wall sprinted). Saved delay 3 reloads into the
        // island; vanilla anchored against main's wall clock, producing a
        // trigger (W − L) + 3 = 90_003 island epochs out — hours-class — that
        // no consumer heal can see (tag == lane). The fix anchors on the tag.
        Clocks c = new Clocks(200_000);
        long island = c.addIsland(5, 110_000);
        List<SavedTick<String>> saved = List.of(
                new SavedTick<>("repeater", new BlockPos(14, 64, 14), 3, TickPriority.NORMAL));
        long vanillaAnchor = c.wall;                   // what startTickingChunk passes
        long wrongTrigger = vanillaAnchor + 3;
        assertTrue(wrongTrigger - c.resolve(island) > HOUR,
                "the vanilla anchor parks this tick hours out on the island clock");
        Tagged<String> tc = loadTicks(c, new ArrayList<>(saved), island, 5);
        assertEquals(110_003, tc.ticks.peek().triggerTick(),
                "pending delays must anchor on the TAGGED clock");
        assertFiresOnceAt(c, island, tc, 110_003, 10);
    }

    @Test
    void fullRestartCollapseSaveAndReloadKeepDelayExact() {
        Clocks c = new Clocks(20_000);
        long island = c.addIsland(7, 55_555);
        Tagged<String> tc = new Tagged<>();
        tc.tagId = island;
        schedule(c, tc, island, "s", new BlockPos(15, 64, 15), 42);   // 55_597
        // shutdown: collapseAllToWall while the tag is still resolvable
        assertEquals(1, rebaseTo(c, tc, WALL, -1, c.wall));
        assertEquals(20_042, tc.ticks.peek().triggerTick());
        List<SavedTick<String>> saved = saveTicks(c, tc);
        assertEquals(42, saved.get(0).delay());
        // fresh session: gameTime persists, no islands yet, WALL default tag
        Clocks restarted = new Clocks(20_000);
        Tagged<String> reloaded = loadTicks(restarted, saved, WALL, -1);
        assertEquals(20_042, reloaded.ticks.peek().triggerTick());
        List<ScheduledTick<String>> fired = new ArrayList<>();
        for (int i = 0; i < 42; i++) stepWall(restarted, fired, reloaded);
        assertEquals(1, fired.size());
        assertEquals(20_042, restarted.wall);
    }

    // ------------------------------------------------------------------
    // 13–14: no multiplication through transitions; exactly-once under churn
    // ------------------------------------------------------------------

    @Test
    void remainingDelayIsInvariantThroughTheFullTransitionGauntlet() {
        Clocks c = new Clocks(1_000);
        long a = c.addIsland(1, 1_000_000);
        long b = c.addIsland(2, 2_000_000);
        Tagged<String> tc = new Tagged<>();
        tc.tagId = a;
        schedule(c, tc, a, "s", new BlockPos(16, 64, 16), 20);
        long consumed = 0;
        // 4 epochs on A
        List<ScheduledTick<String>> fired = new ArrayList<>();
        for (int i = 0; i < 4; i++) stepIsland(c, a, fired, tc);
        consumed += 4;
        // merge into B
        rebaseTo(c, tc, b, 2, c.resolve(b));
        assertEquals(20 - consumed, tc.ticks.peek().triggerTick() - c.resolve(b));
        // 3 epochs on B, then save/reload into B
        for (int i = 0; i < 3; i++) stepIsland(c, b, fired, tc);
        consumed += 3;
        Tagged<String> tc2 = loadTicks(c, saveTicks(c, tc), b, 2);
        assertEquals(20 - consumed, tc2.ticks.peek().triggerTick() - c.resolve(b));
        // collapse to wall (async off) and finish there
        rebaseTo(c, tc2, WALL, -1, c.wall);
        long remaining = tc2.ticks.peek().triggerTick() - c.wall;
        assertEquals(20 - consumed, remaining, "delay must never be scaled, only consumed");
        assertTrue(remaining < HOUR, "no hours-long inflation anywhere in the chain");
        for (int i = 0; i < remaining; i++) stepWall(c, fired, tc2);
        assertEquals(1, fired.size());
        assertEquals(0, fired.get(0).triggerTick() - c.wall);
    }

    @Test
    void exactlyOnceUnderRepeatedDomainPingPong() {
        Clocks c = new Clocks(3_000);
        long island = c.addIsland(6, 44_000);
        Tagged<String> tc = new Tagged<>();
        tc.tagId = island;
        schedule(c, tc, island, "s", new BlockPos(17, 64, 17), 3);
        List<ScheduledTick<String>> fired = new ArrayList<>();
        for (int i = 0; i < 50 && fired.isEmpty(); i++) {
            // hostile churn: bounce the container's domain between epochs
            rebaseTo(c, tc, WALL, -1, c.wall);
            rebaseTo(c, tc, island, 6, c.resolve(island));
            stepIsland(c, island, fired, tc);
        }
        assertEquals(1, fired.size(), "tick lost or duplicated under domain churn");
        assertEquals(44_003, fired.get(0).triggerTick());
        for (int i = 0; i < 10; i++) stepIsland(c, island, fired, tc);
        assertEquals(1, fired.size());
    }

    // ------------------------------------------------------------------
    // 15: ordering — subTickOrder/priority preserved by shift and save/load
    // ------------------------------------------------------------------

    @Test
    void shiftAndSaveLoadPreserveSubTickOrderAndPriority() {
        Tagged<String> tc = new Tagged<>();
        // same trigger, mixed priorities, distinct sub-tick orders
        tc.ticks.schedule(new ScheduledTick<>("n0", new BlockPos(0, 0, 0), 100, TickPriority.NORMAL, 10));
        tc.ticks.schedule(new ScheduledTick<>("h1", new BlockPos(1, 0, 0), 100, TickPriority.HIGH, 11));
        tc.ticks.schedule(new ScheduledTick<>("l2", new BlockPos(2, 0, 0), 100, TickPriority.LOW, 12));
        tc.ticks.schedule(new ScheduledTick<>("n3", new BlockPos(3, 0, 0), 100, TickPriority.NORMAL, 13));
        // production shift: bit-exact (trigger+delta, priority, subTickOrder)
        assertEquals(4, TickDomains.shiftAll(tc.ticks, 12_345));
        List<ScheduledTick<String>> all = tc.ticks.getAll()
                .sorted(ScheduledTick.DRAIN_ORDER).toList();
        assertEquals(List.of("h1", "n0", "n3", "l2"),
                all.stream().map(ScheduledTick::type).toList());
        assertEquals(List.of(11L, 10L, 13L, 12L),
                all.stream().map(ScheduledTick::subTickOrder).toList(), "subTickOrder preserved verbatim");
        assertTrue(all.stream().allMatch(t -> t.triggerTick() == 12_445));
        // save/load: vanilla renumbers subTickOrder but must preserve the
        // DRAIN ORDER (priority saved; list packed in sub-tick order)
        List<SavedTick<String>> saved = tc.ticks.pack(12_445 - 7);
        assertTrue(saved.stream().allMatch(t -> t.delay() == 7));
        LevelChunkTicks<String> reloaded = new LevelChunkTicks<>(saved);
        reloaded.unpack(93);                                     // trigger = 100 again
        List<String> drained = new ArrayList<>();
        for (ScheduledTick<String> t; (t = reloaded.poll()) != null; ) drained.add(t.type());
        assertEquals(List.of("h1", "n0", "n3", "l2"), drained, "drain order preserved across save/load");
    }

    // ------------------------------------------------------------------
    // 16–21: arbitrary long VALID delays — preserved EXACTLY, never rewritten
    // (operator directive 2026-07-27: a large delay is not inherently
    // corrupted; modded schedulers may legitimately use values like
    // 99_999_999. No heuristic re-dating exists anywhere; these tests pin
    // exact-equality preservation through every lifecycle transition.)
    // ------------------------------------------------------------------

    private static final int LONG_DELAY = 99_999_999;

    @Test
    void longValidDelayPreservedExactlyThroughCrossThreadSchedulingAndTransitions() {
        Clocks c = new Clocks(200_000);
        long a = c.addIsland(1, 110_000);
        long b = c.addIsland(2, 3_000_000);
        Tagged<String> tc = new Tagged<>();
        tc.tagId = a;
        // main thread (WALL ctx) schedules the long delay into island A
        ScheduledTick<String> t1 = schedule(c, tc, WALL, "modded_a", new BlockPos(30, 64, 30), LONG_DELAY);
        assertEquals(110_000L + LONG_DELAY, t1.triggerTick());
        // region worker (island-A ctx) schedules into its own container
        ScheduledTick<String> t2 = schedule(c, tc, a, "modded_b", new BlockPos(31, 64, 31), LONG_DELAY);
        assertEquals(110_000L + LONG_DELAY, t2.triggerTick());
        long sub1 = t1.subTickOrder(), sub2 = t2.subTickOrder();
        // MERGE into island B (delta +2_890_000): both triggers shift exactly
        assertEquals(2, rebaseTo(c, tc, b, 2, c.resolve(b)));
        List<ScheduledTick<String>> all = tc.ticks.getAll().sorted(ScheduledTick.DRAIN_ORDER).toList();
        assertEquals(2, all.size());
        for (ScheduledTick<String> t : all) {
            assertEquals(3_000_000L + LONG_DELAY, t.triggerTick());
        }
        assertEquals(List.of(sub1, sub2),
                all.stream().map(ScheduledTick::subTickOrder).toList(), "subTickOrder bit-identical through rebase");
        // SPLIT: fragment inherits B's clock — delta 0, nothing moves
        long frag = c.addIsland(9, c.resolve(b));
        assertEquals(0, rebaseTo(c, tc, frag, 9, c.resolve(frag)));
        assertEquals(3_000_000L + LONG_DELAY, tc.ticks.peek().triggerTick());
        // REBUILD (retire → collapse to wall through lastL): delay exact
        c.retire(frag);
        rebaseTo(c, tc, WALL, -1, c.wall);
        assertEquals(c.wall + (long) LONG_DELAY, tc.ticks.peek().triggerTick());
        assertEquals(2, tc.ticks.count(), "nothing executed, nothing lost");
    }

    @Test
    void longValidDelaySurvivesRateChangeWhilePending() {
        Clocks c = new Clocks(0);
        long island = c.addIsland(3, 500);
        Tagged<String> tc = new Tagged<>();
        tc.tagId = island;
        schedule(c, tc, island, "modded", new BlockPos(32, 64, 32), LONG_DELAY);
        long trigger = 500L + LONG_DELAY;
        List<ScheduledTick<String>> fired = new ArrayList<>();
        // 5 units at 20 TPS (1 unit per wall tick)
        for (int i = 0; i < 5; i++) {
            c.wall++;
            stepIsland(c, island, fired, tc);
        }
        // rate change to 40 TPS (2 units per wall tick), 6 more units
        for (int i = 0; i < 3; i++) {
            c.wall++;
            stepIsland(c, island, fired, tc);
            stepIsland(c, island, fired, tc);
        }
        assertEquals(0, fired.size());
        assertEquals(trigger, tc.ticks.peek().triggerTick(), "trigger must never move under rate change");
        assertEquals(LONG_DELAY - 11, tc.ticks.peek().triggerTick() - c.resolve(island),
                "remaining delay = original minus epochs consumed, exactly");
    }

    @Test
    void longValidDelaySurvivesSaveReloadAndFullRestartExactly() {
        // save/reload on a live diverged island
        Clocks c = new Clocks(10_000);
        long island = c.addIsland(2, 90_000);
        Tagged<String> tc = new Tagged<>();
        tc.tagId = island;
        schedule(c, tc, island, "modded", new BlockPos(33, 64, 33), LONG_DELAY);
        List<SavedTick<String>> saved = saveTicks(c, tc);
        assertEquals(LONG_DELAY, saved.get(0).delay(), "persisted delay exact");
        Tagged<String> re = loadTicks(c, saved, island, 2);
        assertEquals(90_000L + LONG_DELAY, re.ticks.peek().triggerTick());
        // full restart: collapse to wall, save, load into a fresh session
        rebaseTo(c, re, WALL, -1, c.wall);
        List<SavedTick<String>> shutdownSave = saveTicks(c, re);
        assertEquals(LONG_DELAY, shutdownSave.get(0).delay());
        Clocks restarted = new Clocks(10_000);
        Tagged<String> reborn = loadTicks(restarted, shutdownSave, WALL, -1);
        assertEquals(10_000L + LONG_DELAY, reborn.ticks.peek().triggerTick());
        assertEquals("modded", reborn.ticks.peek().type());
    }

    @Test
    void repeatedSavesWithoutExecutionHaveZeroCumulativeDrift() {
        // The drift trap: N save/load cycles with NO execution, clocks
        // diverging further every cycle. The remaining delay must be EXACT
        // after every cycle — original minus epochs consumed, never ±1.
        Clocks c = new Clocks(10_000);
        long island = c.addIsland(2, 90_000);
        Tagged<String> tc = new Tagged<>();
        tc.tagId = island;
        schedule(c, tc, island, "modded", new BlockPos(34, 64, 34), LONG_DELAY);
        List<ScheduledTick<String>> fired = new ArrayList<>();
        long consumed = 0;
        for (int cycle = 0; cycle < 50; cycle++) {
            for (int i = 0; i < 7; i++) stepIsland(c, island, fired, tc);
            consumed += 7;
            c.wall += 13;                     // wall diverges independently
            List<SavedTick<String>> saved = saveTicks(c, tc);
            assertEquals(1, saved.size());
            assertEquals(LONG_DELAY - consumed, saved.get(0).delay(),
                    "cycle " + cycle + ": persisted remaining delay must be exact");
            tc = loadTicks(c, saved, island, 2);
            assertEquals(c.resolve(island) + (LONG_DELAY - consumed), tc.ticks.peek().triggerTick(),
                    "cycle " + cycle + ": reloaded trigger must be exact");
        }
        assertEquals(0, fired.size());
        assertEquals(LONG_DELAY - 350, tc.ticks.peek().triggerTick() - c.resolve(island));
    }

    @Test
    void largestRepresentableDelaysRoundTripWithoutOverflowOrSaturation() {
        // SAVE/LOAD chain bound: vanilla persists delays as int
        // (SavedTick.delay; ScheduledTick.toSavedTick casts trigger − basis).
        // Integer.MAX_VALUE is therefore the largest remaining delay that can
        // round-trip serialization — assert it does so bit-exactly.
        Clocks c = new Clocks(1_000_000);
        Tagged<String> tc = new Tagged<>();   // WALL container
        long maxSaveTrigger = Math.addExact(c.wall, (long) Integer.MAX_VALUE);
        tc.ticks.schedule(new ScheduledTick<>("int_max", new BlockPos(35, 64, 35),
                maxSaveTrigger, TickPriority.NORMAL, 0));
        List<SavedTick<String>> saved = saveTicks(c, tc);
        assertEquals(Integer.MAX_VALUE, saved.get(0).delay(), "no truncation at the int bound");
        Tagged<String> re = loadTicks(c, saved, WALL, -1);
        assertEquals(maxSaveTrigger, re.ticks.peek().triggerTick(), "no saturation on reload");

        // IN-MEMORY rebase chain bound: triggers are longs; the constraint is
        // only that trigger + (largest positive rebase delta) stays below
        // Long.MAX_VALUE. With clocks bounded by 8_000_000 here, the largest
        // safe trigger is Long.MAX_VALUE − 1 when the round trip dips first
        // (island L > wall → island→WALL shifts DOWN, then back up). Expected
        // values are computed with Math.addExact so any overflow in the chain
        // fails the test itself.
        long island = c.addIsland(4, 8_000_000L);
        Tagged<String> mem = new Tagged<>();
        mem.tagId = island;
        long bigTrigger = Long.MAX_VALUE - 1;
        mem.ticks.schedule(new ScheduledTick<>("long_max", new BlockPos(36, 64, 36),
                bigTrigger, TickPriority.NORMAL, 1));
        long down = Math.addExact(bigTrigger, c.wall - 8_000_000L);   // island → WALL
        assertEquals(1, rebaseTo(c, mem, WALL, -1, c.wall));
        assertEquals(down, mem.ticks.peek().triggerTick());
        assertTrue(mem.ticks.peek().triggerTick() > 0, "no wraparound");
        assertEquals(1, rebaseTo(c, mem, island, 4, c.resolve(island)));   // back
        assertEquals(bigTrigger, mem.ticks.peek().triggerTick(),
                "round-trip rebase restores the near-Long.MAX trigger bit-exactly");
    }

    @Test
    void longDelayFieldsPreservedExactlyThroughRebaseAndSaveLoad() {
        // block AND fluid lanes; type / position / priority / subTickOrder.
        Clocks c = new Clocks(2_000);
        long island = c.addIsland(5, 60_000);
        Tagged<String> block = new Tagged<>();
        Tagged<String> fluid = new Tagged<>();
        block.tagId = island;
        fluid.tagId = island;
        BlockPos pObs = new BlockPos(37, 64, 37);
        BlockPos pRep = new BlockPos(38, 12, -7);
        BlockPos pWat = new BlockPos(-39, 200, 39);
        block.ticks.schedule(new ScheduledTick<>("observer", pObs, 60_000L + LONG_DELAY, TickPriority.HIGH, 5));
        block.ticks.schedule(new ScheduledTick<>("repeater", pRep, 60_000L + LONG_DELAY, TickPriority.NORMAL, 6));
        fluid.ticks.schedule(new ScheduledTick<>("water", pWat, 60_000L + LONG_DELAY, TickPriority.LOW, 7));
        // rebase both lanes to WALL: every field except the shifted trigger
        // is bit-identical (absolute subTickOrder included)
        rebaseTo(c, block, WALL, -1, c.wall);
        rebaseTo(c, fluid, WALL, -1, c.wall);
        List<ScheduledTick<String>> bs = block.ticks.getAll().sorted(ScheduledTick.DRAIN_ORDER).toList();
        assertEquals(List.of("observer", "repeater"), bs.stream().map(ScheduledTick::type).toList());
        assertEquals(List.of(pObs, pRep), bs.stream().map(ScheduledTick::pos).toList());
        assertEquals(List.of(TickPriority.HIGH, TickPriority.NORMAL),
                bs.stream().map(ScheduledTick::priority).toList());
        assertEquals(List.of(5L, 6L), bs.stream().map(ScheduledTick::subTickOrder).toList());
        assertTrue(bs.stream().allMatch(t -> t.triggerTick() == 2_000L + LONG_DELAY));
        // save/reload both lanes: delay, type, pos, priority exact; drain
        // order preserved (vanilla renumbers absolute subTickOrder on unpack
        // — by list position — which is exactly what keeps the order)
        Tagged<String> rb = loadTicks(c, saveTicks(c, block), WALL, -1);
        Tagged<String> rf = loadTicks(c, saveTicks(c, fluid), WALL, -1);
        ScheduledTick<String> first = rb.ticks.poll();
        ScheduledTick<String> second = rb.ticks.poll();
        assertEquals("observer", first.type());
        assertEquals(pObs, first.pos());
        assertEquals(TickPriority.HIGH, first.priority());
        assertEquals(2_000L + LONG_DELAY, first.triggerTick());
        assertEquals("repeater", second.type());
        assertEquals(pRep, second.pos());
        assertEquals(TickPriority.NORMAL, second.priority());
        assertEquals(2_000L + LONG_DELAY, second.triggerTick());
        assertTrue(first.subTickOrder() < second.subTickOrder(), "relative sub-tick order preserved");
        ScheduledTick<String> f = rf.ticks.poll();
        assertEquals("water", f.type());
        assertEquals(pWat, f.pos());
        assertEquals(TickPriority.LOW, f.priority());
        assertEquals(2_000L + LONG_DELAY, f.triggerTick());
    }
}
