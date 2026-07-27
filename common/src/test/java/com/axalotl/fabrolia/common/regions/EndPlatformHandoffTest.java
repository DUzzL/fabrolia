package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-portal falling-block handoff barrier ({@link EndPlatformHandoff}) —
 * the phase-drift fix.
 *
 * <p>The machine invariant under test, stated event-wise (no tick constants):
 * <blockquote>an End-platform regeneration belonging to a falling-block cohort
 * never executes while a previously published falling-block cohort for that
 * platform still has airborne members.</blockquote>
 *
 * <p>The fall duration is NOT assumed anywhere: the first test DERIVES it from
 * the vanilla FallingBlockEntity lifecycle (gravity 0.04, drag 0.98 applied
 * after the move, non-player End arrival at y=50.0 over the obsidian top at
 * y=49.0), and the simulation tests then use that derived value — if Mojang
 * changes the physics or the platform, one assertion moves and everything else
 * follows.
 */
class EndPlatformHandoffTest {

    // ------------------------------------------------------------------
    // Vanilla lifecycle derivation
    // ------------------------------------------------------------------

    /** Ticks a vanilla falling block needs to ground after a non-player End
     *  arrival: spawn y=50.0, floor top y=49.0, per tick v -= 0.04; y += v;
     *  v *= 0.98 (gravity, move, post-move drag). */
    private static int derivedLandingTicks() {
        double y = 50.0;
        double v = 0.0;
        int ticks = 0;
        while (y > 49.0) {
            v -= 0.04;
            y += v;
            v *= 0.98;
            ticks++;
            assertTrue(ticks < 1000, "falling block never grounded — physics model broken");
        }
        return ticks;
    }

    @Test
    void vanillaEndArrivalFallLastsSevenEntityTicks() {
        // The operator-verified figure, derived rather than assumed. This is the
        // ONLY place the number 7 appears; the gate itself never uses it.
        assertEquals(7, derivedLandingTicks());
    }

    // ------------------------------------------------------------------
    // Simulation harness: a destination island's heads + entity phases
    // ------------------------------------------------------------------

    private static final long PLATFORM_CHUNK = 0x0006_0000L; // End platform's chunk
    private static final long OTHER_CHUNK = 0x0042_0007L;    // unrelated destination

    /** A published falling block: settles (isRemoved) on the entity tick it
     *  lands — placement and removal are the same atomic tick. */
    private static final class Fbe {
        int ticksToLand;
        boolean settled;
        Fbe(int ticksToLand) { this.ticksToLand = ticksToLand; }
        void entityTick() {
            if (!settled && --ticksToLand <= 0) {
                settled = true;
            }
        }
    }

    private record Op(long domain, long tick, long chunk, boolean regen, Object handle) {}

    /** One machine activation: per member, vanilla order [regen, entity]
     *  (createEndPlatform runs once per entity per crossing). */
    private static List<Op> machineCohort(long domain, long tick, int members, int landingTicks) {
        ArrayList<Op> ops = new ArrayList<>(members * 2);
        for (int i = 0; i < members; i++) {
            ops.add(new Op(domain, tick, PLATFORM_CHUNK, true, null));
            ops.add(new Op(domain, tick, PLATFORM_CHUNK, false, new Fbe(landingTicks)));
        }
        return ops;
    }

    private static final class Sim {
        final ArrayList<Op> queue = new ArrayList<>();
        final ArrayList<Fbe> airborne = new ArrayList<>();
        final EndPlatformHandoff.Tracker tracker;
        long nanoNow;

        Sim() {
            this(EndPlatformHandoff.DEFAULT_TTL_NANOS);
        }

        Sim(long ttlNanos) {
            tracker = new EndPlatformHandoff.Tracker(
                    h -> ((Fbe) h).settled, () -> nanoNow, ttlNanos);
        }

        EndPlatformHandoff.Ops view() {
            return new EndPlatformHandoff.Ops() {
                @Override public int size() { return queue.size(); }
                @Override public long sourceDomain(int i) { return queue.get(i).domain(); }
                @Override public long sourceTick(int i) { return queue.get(i).tick(); }
                @Override public long chunkKey(int i) { return queue.get(i).chunk(); }
                @Override public boolean isRegen(int i) { return queue.get(i).regen(); }
                @Override public Object landingHandle(int i) { return queue.get(i).handle(); }
            };
        }

        /** True iff, entering the most recent head, any PREVIOUSLY published
         *  member was still airborne — the state B's regen must never see. */
        boolean unsettledAtHead;

        /** One destination unit head: apply the gate, publish the released ops. */
        List<Op> head() {
            unsettledAtHead = anyUnsettledAirborne();
            boolean[] rel = EndPlatformHandoff.selectReleasable(view(), tracker);
            ArrayList<Op> published = new ArrayList<>();
            ArrayList<Op> held = new ArrayList<>();
            for (int i = 0; i < queue.size(); i++) {
                (rel[i] ? published : held).add(queue.get(i));
            }
            queue.clear();
            queue.addAll(held);
            for (Op op : published) {
                if (op.handle() instanceof Fbe f) {
                    airborne.add(f);
                }
            }
            return published;
        }

        /** The destination entity phase after the head. */
        void entityPhase() {
            for (Fbe f : airborne) {
                f.entityTick();
            }
        }

        boolean anyUnsettledAirborne() {
            for (Fbe f : airborne) {
                if (!f.settled) {
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean containsMachineRegen(List<Op> published, long tick) {
        for (Op op : published) {
            if (op.regen() && op.tick() == tick) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // (i) + the danger band: inter-cohort destination-phase spacing
    // ------------------------------------------------------------------

    @Test
    void interCohortDestinationSpacingCanNeverLandInTheDangerBand() {
        int landing = derivedLandingTicks();
        // Cohort B reaching the destination 0..10 epochs after cohort A covers
        // every phase-drift alignment, including all of the danger band [1..6].
        for (int arrivalGap = 0; arrivalGap <= 10; arrivalGap++) {
            Sim sim = new Sim();
            long pubA = -1;
            long pubB = -1;
            for (long epoch = 0; epoch <= 40; epoch++) {
                if (epoch == 0) {
                    sim.queue.addAll(machineCohort(7L, 100, 12, landing));
                }
                if (epoch == arrivalGap) {
                    sim.queue.addAll(machineCohort(7L, 100 + landing, 12, landing));
                }
                List<Op> published = sim.head();
                if (containsMachineRegen(published, 100 + landing)) {
                    // THE invariant: B's regen publishes only once every member
                    // of A has completed its landing tick (placed / broken /
                    // removed) — there is nothing left for the regen to miss.
                    assertFalse(sim.unsettledAtHead,
                            "cohort B's platform regen published while cohort A was still "
                                    + "airborne (arrivalGap=" + arrivalGap + ", epoch=" + epoch + ")");
                    pubB = epoch;
                }
                if (containsMachineRegen(published, 100)) {
                    pubA = epoch;
                }
                sim.entityPhase();
            }
            assertEquals(0, pubA, "cohort A publishes immediately");
            assertTrue(pubB >= 0, "cohort B must eventually publish (liveness), gap=" + arrivalGap);
            long delta = pubB - pubA;
            assertTrue(delta >= landing,
                    "publication spacing " + delta + " must be >= the full landing phase ("
                            + landing + "): distinct activations on distinct epochs, outside "
                            + "the danger band [1.." + (landing - 1) + "] (arrivalGap="
                            + arrivalGap + ")");
        }
    }

    // ------------------------------------------------------------------
    // (ii) source island faster than the End island
    // ------------------------------------------------------------------

    @Test
    void fastSourceBacklogDrainsInFifoOrderWithoutEverEnteringTheDangerBand() {
        int landing = derivedLandingTicks();
        Sim sim = new Sim();
        int cohorts = 8;
        long[] published = new long[cohorts];
        java.util.Arrays.fill(published, -1);
        int next = 0;
        // The source produces a cohort every 2 destination epochs — far faster
        // than the destination can safely consume them.
        for (long epoch = 0; epoch <= 200; epoch++) {
            if (next < cohorts && epoch == next * 2L) {
                sim.queue.addAll(machineCohort(7L, 100 + next * (long) landing, 12, landing));
                next++;
            }
            List<Op> now = sim.head();
            for (int c = 0; c < cohorts; c++) {
                if (containsMachineRegen(now, 100 + c * (long) landing)) {
                    assertFalse(sim.unsettledAtHead,
                            "cohort " + c + " regen ran over an airborne predecessor");
                    published[c] = epoch;
                }
            }
            sim.entityPhase();
        }
        for (int c = 0; c < cohorts; c++) {
            assertTrue(published[c] >= 0, "cohort " + c + " never published — backlog wedged");
            if (c > 0) {
                assertTrue(published[c] > published[c - 1],
                        "FIFO violated: cohort " + c + " overtook " + (c - 1));
                long delta = published[c] - published[c - 1];
                assertFalse(delta >= 1 && delta < landing,
                        "cohorts " + (c - 1) + "→" + c + " spaced " + delta
                                + " epochs — danger band");
            }
        }
        assertTrue(sim.queue.isEmpty(), "backlog fully drained once input stopped");
    }

    // ------------------------------------------------------------------
    // Same-head backlog: two cohorts delivered by one head (post-stall)
    // ------------------------------------------------------------------

    @Test
    void twoCohortsAtOneHeadDoNotDoublePublish() {
        int landing = derivedLandingTicks();
        Sim sim = new Sim();
        sim.queue.addAll(machineCohort(7L, 100, 12, landing));
        sim.queue.addAll(machineCohort(7L, 100 + landing, 12, landing));

        List<Op> first = sim.head();
        assertTrue(containsMachineRegen(first, 100), "cohort A releases at the first head");
        assertFalse(containsMachineRegen(first, 100 + landing),
                "cohort B must NOT publish at the same head — A's members are airborne "
                        + "from this very publication");
        assertFalse(sim.queue.isEmpty(), "B stays queued");
    }

    // ------------------------------------------------------------------
    // (iv) previous cohort placing after the newer cohort's regen
    // ------------------------------------------------------------------

    @Test
    void aHeldRegenReleasesExactlyWhenThePreviousCohortHasNoAirborneMembers() {
        int landing = derivedLandingTicks();
        Sim sim = new Sim();
        sim.queue.addAll(machineCohort(7L, 100, 12, landing));
        sim.head(); // A publishes at epoch 0
        sim.entityPhase();
        sim.queue.addAll(machineCohort(7L, 100 + landing, 12, landing)); // B arrives at epoch 1

        long releasedAt = -1;
        for (long epoch = 1; epoch <= 20 && releasedAt < 0; epoch++) {
            if (containsMachineRegen(sim.head(), 100 + landing)) {
                releasedAt = epoch;
                // Placement and removal are one atomic entity tick, so "no
                // airborne member" == "every placement A will ever make has
                // already happened" — A can never place AFTER B's regen.
                assertFalse(sim.unsettledAtHead);
            }
            sim.entityPhase();
        }
        assertEquals(landing, releasedAt,
                "B releases at the first head after A's landing phase completed — "
                        + "no earlier (danger band) and no later (no pacing debt)");
    }

    // ------------------------------------------------------------------
    // Unrelated traffic is never delayed
    // ------------------------------------------------------------------

    @Test
    void unrelatedArrivalsPassAHeldMachineCohortImmediately() {
        int landing = derivedLandingTicks();
        Sim sim = new Sim();
        sim.queue.addAll(machineCohort(7L, 100, 12, landing));
        sim.head(); // A airborne
        sim.entityPhase();

        // Queue at the next head: held machine cohort B, then behind it —
        // a nether-portal transfer (entities, no regen) to another chunk,
        // an item transfer (no regen) into the PLATFORM chunk itself,
        // and a mob/player-style End crossing (regen, no falling block).
        sim.queue.addAll(machineCohort(7L, 100 + landing, 12, landing));
        sim.queue.add(new Op(9L, 500, OTHER_CHUNK, false, null));
        sim.queue.add(new Op(9L, 501, PLATFORM_CHUNK, false, null));
        sim.queue.add(new Op(0L, 777, PLATFORM_CHUNK, true, null));

        List<Op> published = sim.head();
        assertFalse(containsMachineRegen(published, 100 + landing), "B is held");
        assertTrue(published.stream().anyMatch(o -> o.chunk() == OTHER_CHUNK),
                "a non-End-portal arrival must not be delayed by the gate");
        assertTrue(published.stream().anyMatch(o -> !o.regen() && o.tick() == 501),
                "a plain entity arrival into the platform chunk is not the machine "
                        + "and must not be delayed");
        assertTrue(published.stream().anyMatch(o -> o.regen() && o.tick() == 777),
                "a regen-only crossing (player/mob walking into the End) must not be "
                        + "held — its arrival is not staged, its platform must exist");
    }

    @Test
    void withNoMachineTrafficTheGateReleasesEverything() {
        Sim sim = new Sim();
        sim.queue.add(new Op(3L, 10, OTHER_CHUNK, false, null));
        sim.queue.add(new Op(3L, 11, OTHER_CHUNK, false, null));
        sim.queue.add(new Op(0L, -1, PLATFORM_CHUNK, false, null)); // unknown epoch
        sim.queue.add(new Op(0L, -1, PLATFORM_CHUNK, true, null));  // unknown-epoch regen
        assertEquals(4, sim.head().size(), "nothing to gate — behaviour identical to before");
        assertTrue(sim.queue.isEmpty());
    }

    // ------------------------------------------------------------------
    // Per-chunk FIFO between machine cohorts
    // ------------------------------------------------------------------

    @Test
    void aLaterMachineCohortNeverOvertakesAHeldOne() {
        int landing = derivedLandingTicks();
        Sim sim = new Sim();
        sim.queue.addAll(machineCohort(7L, 100, 12, landing));
        sim.head(); // A airborne
        sim.entityPhase();
        sim.queue.addAll(machineCohort(7L, 100 + landing, 12, landing));      // B
        sim.queue.addAll(machineCohort(7L, 100 + 2L * landing, 12, landing)); // C

        long pubB = -1;
        long pubC = -1;
        for (long epoch = 1; epoch <= 60; epoch++) {
            List<Op> now = sim.head();
            if (containsMachineRegen(now, 100 + landing)) pubB = epoch;
            if (containsMachineRegen(now, 100 + 2L * landing)) {
                pubC = epoch;
                assertTrue(pubB >= 0 && pubB < epoch,
                        "C published at or before B — same-platform FIFO violated");
            }
            sim.entityPhase();
        }
        assertTrue(pubB >= 0 && pubC >= 0, "both cohorts eventually publish");
        long delta = pubC - pubB;
        assertFalse(delta >= 1 && delta < landing, "B→C spacing in the danger band");
    }

    // ------------------------------------------------------------------
    // Liveness containment
    // ------------------------------------------------------------------

    @Test
    void aMemberThatCanNeverSettleCannotHoldTheGateForever() {
        // Pathological: a tracked member whose publish op failed before
        // insertion — it will never be removed. The TTL belt fails OPEN.
        Sim sim = new Sim(1_000L); // 1µs TTL for the test
        sim.queue.addAll(machineCohort(7L, 100, 1, Integer.MAX_VALUE)); // never lands
        sim.head();
        sim.queue.addAll(machineCohort(7L, 200, 1, 7));
        assertTrue(sim.head().isEmpty(), "held while the tracker entry is live");
        sim.nanoNow += 2_000L; // past TTL
        assertFalse(sim.head().isEmpty(),
                "after the containment TTL the gate must fail open — no unbounded holding");
    }

    @Test
    void trackerSweepPurgesSettledMembers() {
        Sim sim = new Sim();
        sim.queue.addAll(machineCohort(7L, 100, 12, 1));
        sim.head();
        assertEquals(12, sim.tracker.trackedCount());
        sim.entityPhase(); // all land
        sim.tracker.sweep();
        assertEquals(0, sim.tracker.trackedCount(),
                "a stopped machine's last cohort must not be retained");
    }
}
