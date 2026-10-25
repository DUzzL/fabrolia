package com.axalotl.fabrolia.api.spawn;

/**
 * Mixed into {@code LocalMobCapCalculator}. An attached arena binds its allocation to
 * its creating provider. Every native operation holds an acquired arena lease; closing
 * refuses new leases, and native reclamation waits for existing leases. A missing or
 * closed arena selects the seeded Java shadow. Only the local cap moves off heap;
 * global cap integration remains in vanilla Java.
 */
public interface FabroliaLocalCapView {
    /** Publish an arena, or detach it after its owner has closed acquisition. */
    void fabrolia$setCapArena(NativeArena arena);

    /** Confine this freshly constructed calculator to ONE sequential chunk
     *  pass (an island lane's coordinator): vanilla's plain maps replace the
     *  concurrent ones and approval is vanilla's plain check, exact there.
     *  Must be called before any use; never combined with an arena. */
    void fabrolia$useSerialLane();

    /** Whether {@link #fabrolia$useSerialLane} confined this calculator. */
    boolean fabrolia$serialLane();

    /** Native operations require a successful acquisition, paired with release in finally. */
    interface NativeArena {
        boolean tryAcquire();

        /** Release the lease, retaining an entered operation failure over cleanup failure. */
        void release(Throwable primary);

        long handle();

        int canSpawn(int categoryOrdinal, int chunkX, int chunkZ);

        void addMob(int categoryOrdinal, int chunkX, int chunkZ);

        int tryReservePack(int categoryOrdinal, int chunkX, int chunkZ, int count);

        void releasePack(int categoryOrdinal, int chunkX, int chunkZ, int count);
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
     *  to {@code count} installed {@code addMob(chunk, category)} calls — one
     *  real call picks the players, the rest are replayed onto their counts. */
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