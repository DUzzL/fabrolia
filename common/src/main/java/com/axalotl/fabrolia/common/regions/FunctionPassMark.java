package com.axalotl.fabrolia.common.regions;

/**
 * Per-entity alternation between per-tick function passes and entity ticks.
 *
 * <p>Vanilla runs every per-tick root once over every entity between two of
 * its ticks. Partition membership can change between a pass and a tick (island
 * merge/split, stale-domain heal, gap adoption, lazy region index moves, async
 * ON/OFF), so the pass is claimed on the entity: a head opens a pass id and
 * takes an entity only by moving its mark {@code OWED → id}; the entity's next
 * tick resets the mark to {@code OWED}. Two different passes therefore never
 * both run an entity between two of its ticks, whatever partitions they belong
 * to; the roots of one pass (Main runs #tick roots and loops at different
 * points of one tick under one id) all see the same members.
 */
public interface FunctionPassMark {

    long OWED = 0L;

    enum Claim { NEW, SAME, TAKEN }

    /** Move {@code OWED → passId}; SAME when this exact pass already holds it. */
    Claim fabrolia$claimFunctionPass(long passId);

    /** Tick boundary: the next interval owes a pass again. */
    void fabrolia$functionPassTick();
}
