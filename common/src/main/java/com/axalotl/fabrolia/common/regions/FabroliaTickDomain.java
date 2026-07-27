package com.axalotl.fabrolia.common.regions;

/**
 * Explicit clock-domain tag carried by every {@code LevelChunkTicks} container
 * (2026-07-21 scheduled-tick architecture rework, task #31).
 *
 * <p>THE invariant: every trigger time inside a container is expressed in the
 * clock domain named by the container's tag — WALL (the real gameTime counter,
 * vanilla semantics, id = -1) or an island's logical clock (id/gen). All
 * domain transitions rebase the container's triggers by (targetNow − tagNow)
 * and retag in the same operation ({@link TickDomains#rebaseTo}); consumers
 * (save serialization, due selection, diagnostics) RESOLVE the tag — nothing
 * ever guesses a clock from current ownership again. Guessing was the
 * production farm killer: shutdown stopped the scheduler before the final
 * save, the save's ownership guess fell back to wall, and island-L triggers
 * (sprint-inflated by tens of thousands of ticks) were packed as huge future
 * delays — a permanently-parked tick whose pos+type dedup entry then rejected
 * every replacement schedule at that position across restarts.
 *
 * <p>Vanilla-constructed containers (world load, proto chunks) default to
 * WALL — exactly correct, because vanilla anchors loaded ticks against the
 * real gameTime at container registration.
 */
public interface FabroliaTickDomain {

    long WALL = -1;

    long fabrolia$domainIsland();

    long fabrolia$domainGen();

    void fabrolia$setDomain(long islandId, long gen);
}
