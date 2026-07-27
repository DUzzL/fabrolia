package com.axalotl.fabrolia.common.regions;

/**
 * Implemented by EntityMixin. Stores the grid key of the region whose owned-entity set
 * currently contains this entity — O(1) removal on despawn/unload and cheap re-route checks
 * in the region tick loop, instead of a map lookup per event.
 *
 * <p>Write sites: index add (main or spawn worker), index move (the owning region's worker),
 * index remove (any thread, via PESM callbacks). A stale read can at worst leave the entity
 * in an old region's set for one tick — the drain's {@code isRemoved} lazy cleanup and
 * {@link #fabrolia$tryMarkRegionTick(long)} make that benign (skip or single-tick ownership
 * handoff, never a double tick).
 */
public interface RegionKeyed {
    long NO_REGION = Long.MIN_VALUE;

    long fabrolia$getRegionKey();

    void fabrolia$setRegionKey(long key);

    /**
     * Region-owned dispatch de-dupe. Unlike the vanilla tick guard, this stamp
     * survives after {@code tickNonPassenger} returns, so an entity moved into
     * another region's weakly-consistent iterator cannot tick twice in one
     * region epoch.
     */
    boolean fabrolia$tryMarkRegionTick(long token);

    /**
     * audit10 — ATOMICALLY move an existing tick mark from one domain's token to
     * another's, and do nothing otherwise.
     *
     * <p>Needed because an island MERGE cannot preserve every predecessor's tick
     * identity: the survivor keeps its own, so entities re-indexed in from a
     * merged-away island still carry that island's token and would read as
     * un-ticked under the survivor — a second tick in the same wall tick.
     *
     * <p>The backing field is mutated concurrently (index add on main or a spawn
     * worker, index move on the owning region's worker, the drain's own stamp), so
     * this MUST be a compare-and-set. A read followed by
     * {@link #fabrolia$tryMarkRegionTick} would mark an entity that was never
     * ticked under {@code expectedFrom} if the field changed in between — which
     * suppresses a tick the entity is owed, i.e. the freeze failure mode.
     *
     * @param expectedFrom the predecessor domain's current token; the mark is moved
     *                     ONLY if it is exactly this
     * @param newToken     the successor domain's current token
     * @return true iff the mark was carried
     */
    boolean fabrolia$carryRegionTickMark(long expectedFrom, long newToken);
}
