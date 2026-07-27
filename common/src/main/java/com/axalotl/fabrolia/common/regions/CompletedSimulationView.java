package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;

/**
 * Duck installed on {@code SimulationChunkTracker}: simulation levels as of the
 * last COMPLETED propagation wave.
 *
 * <p>Why this exists (bombers "breaking differently" / TNT stutter, 2026-07-06):
 * the tracker's live {@code chunks} map is the propagator's WORKING STATE.
 * Vanilla {@code SimulationChunkTracker.setLevel} literally {@code remove()}s a
 * chunk's entry whenever a relaxation step passes level ≥ 33 — so mid-wave, a
 * fully-simulated chunk can read as ABSENT (= level 33, not simulated). Vanilla
 * never observes this (single-threaded causality: waves complete before any gate
 * reads); Fabrolia's workers read gates concurrently with waves run under the
 * chunkMap monitor by main's barrier pump and player-move consolidation. A gate
 * catching the transient froze one whole chunk for a tick while its neighbours
 * ran — a cross-chunk one-tick skew that tears contraptions straddling the
 * border and stutters falling-entity columns. Making reads per-chunk COHERENT
 * (the phase memos) removed intra-chunk splits but not this cross-chunk skew;
 * only completed states can be allowed out.
 *
 * <p>The view is republished at {@code runAllUpdates} RETURN (same monitor as
 * mutation) and is immutable after publish: every reader — worker or main —
 * observes only completed waves, restoring vanilla's "ticket changes apply at
 * defined points" semantics. Between waves, view == live.
 */
public interface CompletedSimulationView {

    /** Simulation level from the last completed wave; 33 (= not simulated) when absent. */
    int fabrolia$completedLevel(long chunkPos);

    /** The published snapshot itself (read-only — NEVER mutate). */
    Long2ByteMap fabrolia$completedView();
}
