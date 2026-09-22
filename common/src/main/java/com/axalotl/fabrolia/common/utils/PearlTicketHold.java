package com.axalotl.fabrolia.common.utils;

/**
 * Pure policy for the ENDER_PEARL ticket hold (2026-08-19, replaces the
 * half-burn margin hack). Extracted for unit testing — see
 * {@code PearlTicketHoldTest}. No Minecraft objects, no side effects.
 *
 * <p><b>Why a hold, not a wider margin.</b> Vanilla's pearl ticket survives
 * on a TWO-purge margin (renewal every 39 pearl ticks vs expiry after 41
 * purge passes) only because purge and the pearl's inline renewal share one
 * thread with a fixed within-tick order. With the pearl ticking on a region
 * worker its renewal is deferred to main and can land after the next purge —
 * the margin collapses to one pass, and any jitter (a skipped pearl tick, a
 * late drain) flaps the ticket: the chunk drops out of entity-ticking for a
 * whole main tick, the pearl freezes (no ticks → no renewals) and the ticket
 * dies for good. Widening the margin (the half-burn) only makes the flap
 * rarer; it stays fatal when it lands.
 *
 * <p><b>The hold removes the death spiral by construction:</b> an ENDER_PEARL
 * ticket that burned down to zero is NOT decremented further while a live
 * (not removed) pearl entity physically sits in the ticket's chunk. A frozen
 * pearl keeps its ticket, the chunk re-promotes, the pearl resumes ticking
 * and renews — self-healing instead of a one-way freeze. A genuinely dead /
 * landed / logged-out pearl has no live entity in the chunk and expires on
 * the vanilla schedule.
 *
 * <p><b>Leak bound.</b> A pearl wedged by some OTHER bug (e.g. a permanently
 * stalled c2me re-promote) would otherwise pin a 5×5 chunk-loader column
 * forever. After {@link #HOLD_BUDGET} suppressed purge passes the hold lets
 * go and the ticket expires normally — the pearl is unloaded to disk and
 * self-registers on the next load, vanilla's own recovery path.
 */
public final class PearlTicketHold {

    /** Max consecutive suppressed purge passes per ticket before the hold
     *  releases (~5 min at 20 TPS — mirrors the stall-grace cap). */
    public static final int HOLD_BUDGET = 6000;

    private PearlTicketHold() {}

    /**
     * @param regionsActive    region threading is ticking entities on workers
     *                         (off = vanilla single-thread, no deferral, no hold)
     * @param ticksLeft        ticket's remaining ticks BEFORE this purge's
     *                         decrement (the hold engages only at zero — a
     *                         burning ticket follows the vanilla countdown)
     * @param livePearlInChunk a non-removed pearl entity is present in the
     *                         ticket's chunk right now
     * @param suppressedSoFar  consecutive passes this ticket has already been
     *                         held at zero
     * @return true = suppress this purge's decrement (ticket survives at 0)
     */
    public static boolean shouldHold(boolean regionsActive, long ticksLeft,
                                     boolean livePearlInChunk, int suppressedSoFar) {
        return regionsActive
                && ticksLeft <= 0
                && livePearlInChunk
                && suppressedSoFar < HOLD_BUDGET;
    }
}
