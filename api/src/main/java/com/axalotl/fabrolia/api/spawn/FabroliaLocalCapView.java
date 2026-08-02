package com.axalotl.fabrolia.api.spawn;

/**
 * Mixed into {@code LocalMobCapCalculator} by {@code LocalMobCapCalculatorMixin}. Carries the
 * Oxidizium Rust mob-cap arena handle for this tick's calculator; when non-zero, the
 * calculator's {@code canSpawn} / {@code addMob} route to the off-heap arena instead of the
 * Java {@code playerMobCounts} / {@code playersNearChunk} maps — bypassing the concurrent-map
 * path entirely (no race possible by construction). 0 → Java path (the standalone-correct
 * fallback). Only the LOCAL cap moves; the global cap stays in vanilla Java so ServerCore's
 * mob-cap multiplier and phantom-spawning's wrap keep applying.
 */
public interface FabroliaLocalCapView {
    void fabrolia$setCapHandle(long handle);

    long fabrolia$capHandle();

    /** Number of threads currently inside a native canSpawn/addMob on this
     *  calculator's handle — free-side quiescence check. */
    default int fabrolia$capInFlight() {
        return 0;
    }

    /** Seed the JAVA maps with the census initial mobs even though the arena
     *  consumed them natively (task #32 cod runaway, root cause 2): the arena
     *  handle is detached at the NEXT tick's build, but async-island spawn
     *  arms legitimately consume this cookie one-or-more ticks late — their
     *  canSpawn/addMob then fall back to the Java maps, which without this
     *  shadow seed were EMPTY. Vanilla {@code canSpawn} returns true for a
     *  near player with no counts entry, so the empty fallback silently
     *  DISABLED the per-player local cap for every island-armed chunk: the
     *  whole level's per-category budget could concentrate around one player
     *  (production: global WATER_AMBIENT cap ~166 at ~30 players, local cap
     *  20, observed 142 cod at one ocean player). Packed chunk keys +
     *  category ordinals, first {@code n} entries. */
    default void fabrolia$seedJavaShadow(long[] chunks, int[] cats, int n) {
    }

    /** Count-weighted Java-map seed (task #21 incremental census): equivalent
     *  to {@code count} vanilla {@code addMob(chunk, category)} calls — one
     *  near-player lookup per chunk instead of one per mob. */
    default void fabrolia$addMobs(long packedChunk, int categoryOrdinal, int count) {
    }

    /** Live local-cap probe for the spawncap debug command (task #32): reports
     *  the handle state, the real canSpawn verdicts, and the Java-map view for
     *  one chunk. Diagnostic only. */
    default String fabrolia$debugReport(long packedChunk) {
        return "no-impl";
    }

    /** Marker bit in a {@link #fabrolia$tryReservePack} token: the reservation
     *  landed in the NATIVE arena, so its release must go native too (never
     *  the Java maps — a path mismatch would drive Java counts negative). */
    int FABROLIA_NATIVE_RESERVATION = 1 << 30;

    /** Atomic chunk-pass approval + pack reservation (task #32 → farm-starve
     *  fix): vanilla's {@code canSpawn} ∃-room check and the pack's worth of
     *  {@code addMob} increments, fused into one atomic step so a CONCURRENT
     *  chunk pass immediately sees the slots taken. Returns 0 when no near
     *  player has room (vanilla reject — nothing left behind), otherwise the
     *  reserved count ({@code pack}, possibly OR'ed with
     *  {@link #FABROLIA_NATIVE_RESERVATION}) to hand back to
     *  {@link #fabrolia$releasePack} when the pack ends. */
    default int fabrolia$tryReservePack(long packedChunk, int categoryOrdinal, int pack) {
        return 0;
    }

    /** Return an unconsumed pack reservation ({@code token} exactly as
     *  {@link #fabrolia$tryReservePack} produced it). The pack's ACTUAL spawns
     *  arrive through vanilla {@code afterSpawn → addMob} as usual, so
     *  releasing the full reservation leaves precisely the spawned count. */
    default void fabrolia$releasePack(long packedChunk, int categoryOrdinal, int token) {
    }
}