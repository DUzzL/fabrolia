package com.axalotl.fabrolia.common.utils;

/**
 * Island-tick ticket expiry ({@code TicketStorageMixin}): how many decrements a
 * purge pass owes a ticket of an island that is behind the wall clock. One per
 * island tick since the previous pass, measured only against a memo from the
 * directly preceding wall tick: an island may go minutes without a timed
 * ticket (its memo is then stale), and counting all of those ticks at once
 * would expire a fresh ticket on its first pass.
 */
public final class IslandTicketSteps {

    private IslandTicketSteps() {}

    /**
     * @param seenWall wall time of the memo (0 = never)
     * @param seenL    island L in the memo
     * @param wall     wall time of this pass
     * @param l        island L now
     * @return decrements this pass owes (0 while the island stands still)
     */
    public static long steps(long seenWall, long seenL, long wall, long l) {
        if (seenWall == 0L || wall - seenWall != 1L || l < seenL) {
            return 1L; // no usable memo: one pass, one tick (vanilla's rule)
        }
        // Main and the island are not in phase: one pass can see 0 island ticks
        // and the next 2. More means Main stalled while the island ran; a ticket
        // renewed during those units (pearl renewals are routed to Main) must
        // not lose them all at once, so the pass owes at most 2. Deviation: a
        // ticket can outlive a Main stall by its length, never expire early.
        return Math.min(l - seenL, 2L);
    }
}
