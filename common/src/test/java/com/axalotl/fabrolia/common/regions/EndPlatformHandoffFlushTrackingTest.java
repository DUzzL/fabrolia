package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * audit13 — flush-path handoff registration ({@link EndPlatformHandoff#trackAll}).
 *
 * <p>The residual hole: {@code CrossDimArrivals.flushLevel} (the per-level
 * autosave hook) publishes gate-HELD machine cohorts past the End-platform
 * handoff gate by design — delivering a queued entity into its level before
 * serialization beats losing it. But pre-fix the flush never {@code track()}ed
 * the flushed falling-block members, so the machine's NEXT cohort found no
 * airborne record and released while the flushed members were still falling:
 * its {@code createEndPlatform} destroyed nothing (the blocks weren't placed
 * yet), the previous cohort landed late, and the next one collided with the
 * late blocks — the exact scatter the audit12 gate exists to prevent, with a
 * window of one hold per save per level.
 *
 * <p>{@code trackAll} is the flush counterpart of the registration
 * {@link EndPlatformHandoff#selectReleasable} performs on release: same
 * maximal (sourceDomain, sourceTick) cohort grouping, same gate-eligibility
 * rule (a regen op AND at least one landing handle), registration only — it
 * decides nothing and never holds. These tests drive it with the same pure
 * harness style as {@link EndPlatformHandoffTest}.
 */
class EndPlatformHandoffFlushTrackingTest {

    private static final long PLATFORM_CHUNK = 0x0006_0000L;

    /** A published falling block: settles (isRemoved) on the tick it lands. */
    private static final class Fbe {
        boolean settled;
    }

    private record Op(long domain, long tick, long chunk, boolean regen, Object handle) {}

    private static EndPlatformHandoff.Ops view(List<Op> ops) {
        return new EndPlatformHandoff.Ops() {
            @Override public int size() { return ops.size(); }
            @Override public long sourceDomain(int i) { return ops.get(i).domain(); }
            @Override public long sourceTick(int i) { return ops.get(i).tick(); }
            @Override public long chunkKey(int i) { return ops.get(i).chunk(); }
            @Override public boolean isRegen(int i) { return ops.get(i).regen(); }
            @Override public Object landingHandle(int i) { return ops.get(i).handle(); }
        };
    }

    private static EndPlatformHandoff.Tracker tracker() {
        return new EndPlatformHandoff.Tracker(
                h -> ((Fbe) h).settled, () -> 0L, EndPlatformHandoff.DEFAULT_TTL_NANOS);
    }

    private static List<Op> machineCohort(long tick, int members, List<Fbe> handlesOut) {
        ArrayList<Op> ops = new ArrayList<>(members * 2);
        for (int i = 0; i < members; i++) {
            Fbe f = new Fbe();
            handlesOut.add(f);
            ops.add(new Op(7L, tick, PLATFORM_CHUNK, true, null));
            ops.add(new Op(7L, tick, PLATFORM_CHUNK, false, f));
        }
        return ops;
    }

    @Test
    void aFlushedMachineCohortGatesTheSuccessorUntilItsMembersSettle() {
        EndPlatformHandoff.Tracker t = tracker();
        ArrayList<Fbe> flushedMembers = new ArrayList<>();
        List<Op> flushed = machineCohort(100, 12, flushedMembers);

        // The save flush: published unconditionally, but REGISTERED.
        EndPlatformHandoff.trackAll(view(flushed), t);
        assertTrue(t.airborne(PLATFORM_CHUNK),
                "flushed falling-block members must be registered as airborne");

        // The machine's next cohort arrives while the flushed one still falls.
        List<Op> successor = machineCohort(107, 12, new ArrayList<>());
        boolean[] rel = EndPlatformHandoff.selectReleasable(view(successor), t);
        for (int i = 0; i < rel.length; i++) {
            assertFalse(rel[i], "successor op " + i + " released while the FLUSHED "
                    + "cohort was still airborne — the regen would run mid-fall");
        }

        // The flushed members land (placement + removal, one atomic tick) —
        // the gate opens on the event itself, no tick constant anywhere.
        for (Fbe f : flushedMembers) {
            f.settled = true;
        }
        rel = EndPlatformHandoff.selectReleasable(view(successor), t);
        for (int i = 0; i < rel.length; i++) {
            assertTrue(rel[i], "successor op " + i + " must release once every "
                    + "flushed member has settled");
        }
    }

    @Test
    void nonMachineFlushedOpsAreNeverTracked() {
        EndPlatformHandoff.Tracker t = tracker();

        // A player crossing: regen op, no falling-block member.
        EndPlatformHandoff.trackAll(view(List.of(
                new Op(7L, 100, PLATFORM_CHUNK, true, null))), t);
        // An item/mob transfer: member-less AND regen-less at another chunk;
        // and a lone falling block with NO regen in its cohort (not a machine
        // activation).
        EndPlatformHandoff.trackAll(view(List.of(
                new Op(7L, 101, PLATFORM_CHUNK, false, null),
                new Op(8L, 55, PLATFORM_CHUNK, false, new Fbe()))), t);

        assertEquals(0, t.trackedCount(),
                "only cohorts with BOTH a regen and a landing handle are machine "
                        + "cohorts — anything else must not be tracked (a stray "
                        + "registration would delay a later machine cohort for no reason)");
        assertFalse(t.airborne(PLATFORM_CHUNK));
    }

    @Test
    void cohortGroupingMatchesTheGate() {
        // One flushed list holding [machine cohort][player crossing][machine
        // cohort]: the two machine cohorts register, the crossing does not —
        // the grouping is the same maximal (domain, tick) run rule the gate
        // uses, so a flush and a release can never disagree about what a
        // cohort is.
        EndPlatformHandoff.Tracker t = tracker();
        ArrayList<Op> flushed = new ArrayList<>();
        ArrayList<Fbe> members = new ArrayList<>();
        flushed.addAll(machineCohort(100, 3, members));
        flushed.add(new Op(0L, 200, PLATFORM_CHUNK, true, null)); // player, wall clock
        flushed.addAll(machineCohort(107, 3, members));

        EndPlatformHandoff.trackAll(view(flushed), t);
        assertEquals(6, t.trackedCount(),
                "exactly the six machine members register — not the player regen");
    }

    @Test
    void nullTrackerAndEmptyListAreNoOps() {
        EndPlatformHandoff.trackAll(view(List.of()), tracker());
        EndPlatformHandoff.trackAll(view(List.of(
                new Op(7L, 100, PLATFORM_CHUNK, true, new Fbe()))), null);
        // Reaching here without throwing is the assertion.
    }
}
