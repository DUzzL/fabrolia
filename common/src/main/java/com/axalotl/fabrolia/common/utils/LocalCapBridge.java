package com.axalotl.fabrolia.common.utils;

import com.axalotl.fabrolia.api.spawn.FabroliaLocalCapView;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.axalotl.fabrolia.common.regions.FailStops;

/**
 * Pluggable provider for the LOCAL per-player mob cap ({@code LocalMobCapCalculator} —
 * {@code canSpawn} / {@code addMob} / the {@code playersNearChunk} resolution). Rustmi
 * registers a provider backed by an off-heap Send+Sync Rust arena (built once per level tick,
 * read / atomically incremented lock-free by the parallel spawn workers — no concurrent map on
 * the hot path, so the {@code getPlayersNear} computeIfAbsent race class cannot exist).
 *
 * <p>Symbiosis, mirror of {@link SensorCandidateBridge}: Fabrolia15 alone runs its Java
 * {@code LocalMobCapCalculator} (correct via the {@code Long2ObjectConcurrentHashMap}
 * computeIfAbsent overload fix). With Rustmi present + enabled, the local cap runs on the
 * Rust arena. The GLOBAL cap always stays in vanilla Java (ServerCore's mob-cap multiplier +
 * phantom-spawning's wrap keep applying). No provider or an explicit decline uses Java;
 * an exception after provider entry propagates through the current tick.
 *
 * <p>Provider contract (vanilla-exact):
 * <ul>
 *   <li>{@link Provider#buildArena} — build this tick's arena for {@code level} (reads its own
 *       players + per-category caps) and SEED the local counts from the {@code mobCount}
 *       initial mobs (packed {@code ChunkPos} + {@code MobCategory} ordinal, parallel arrays)
 *       exactly as vanilla createState's addMob replay would — one native pass instead of a
 *       per-entry FFI addMob replay on main (that replay was 4.1% of the server thread at 51
 *       players, xMDQKeDhXO 2026-07-02). Return a handle (&gt;0) or 0 to decline — the caller
 *       then runs its own Java replay;</li>
     *   <li>{@link Provider#canSpawn} — vanilla {@code LocalMobCapCalculator.canSpawn}: 1 allowed,
     *       0 capped/no-near-player, -1 provider failure (the current tick fails);</li>
 *   <li>{@link Provider#addMob} — vanilla {@code addMob}: local +1 to every near player;</li>
 *   <li>{@link Provider#free} — reclaim a handle (tick end).</li>
 * </ul>
 */
public final class LocalCapBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalCapBridge.class);

    public interface Provider {
        long buildArena(ServerLevel level, long[] mobChunks, int[] mobCategories, int mobCount);

        int canSpawn(long handle, int categoryOrdinal, int chunkX, int chunkZ);

        void addMob(long handle, int categoryOrdinal, int chunkX, int chunkZ);

        /** May run on the final reader thread after bounded retirement defers reclamation.
         * Entered exactly once, with no active arena readers; failures are never retried. */
        void free(long handle);

        /** Pack-reservation capability probe. While local-cap pack reservation
         *  is enabled (default), an arena is only BUILT when this returns true —
         *  a reservation-blind provider keeps the (fully reservation-capable)
         *  Java path instead of silently reopening the parallel local-cap
         *  window. Implementations must make {@link #tryReservePack} /
         *  {@link #releasePack} atomic against concurrent canSpawn/addMob. */
        default boolean supportsPackReservation() {
            return false;
        }

        /** Atomic fused check+reserve, the native twin of
         *  {@code LocalCapPackReservation.tryReserve}: ∃ near player with room
         *  (vanilla canSpawn) ⇒ reserve {@code count} slots on EVERY near
         *  player and return 1; no room ⇒ leave counts unchanged, return 0;
         *  -1 = provider failure after arena entry (the current tick fails).
         *  Unsupported providers must return {@code false} from
         *  {@link #supportsPackReservation()} before any arena is built. */
        default int tryReservePack(long handle, int categoryOrdinal, int chunkX, int chunkZ, int count) {
            return -1;
        }

        /** Return an unconsumed reservation made by {@link #tryReservePack}
         *  (−{@code count} on every near player of the chunk). */
        default void releasePack(long handle, int categoryOrdinal, int chunkX, int chunkZ, int count) {
        }
    }
    /**
     * One allocation and its creating provider. Closing and reader admission share
     * one CAS word, so a reader which captured this object before detach either
     * announces itself before close or refuses entry. No per-operation lease object.
     */
    public static final class Arena implements FabroliaLocalCapView.NativeArena {
        private static final int CLOSED = 1 << 31;
        private static final int DEFERRED = 1 << 30;
        private static final int FREE_ENTERED = 1 << 29;
        private static final int READERS = FREE_ENTERED - 1;

        private final Provider creator;
        private final long handle;
        private final AtomicInteger state = new AtomicInteger();
        private volatile Throwable retirementPrimary;

        public Arena(Provider creator, long handle) {
            this.creator = java.util.Objects.requireNonNull(creator);
            if (handle == 0L) throw new IllegalArgumentException("absent native arena");
            this.handle = handle;
        }

        @Override
        public boolean tryAcquire() {
            int observed = state.get();
            while ((observed & CLOSED) == 0) {
                if ((observed & READERS) == READERS) {
                    throw new IllegalStateException("native arena reader overflow");
                }
                if (state.compareAndSet(observed, observed + 1)) return true;
                observed = state.get();
            }
            return false;
        }

        public void close() {
            setFlag(CLOSED);
        }

        /** Returns false only while an announced reader still owns the allocation. */
        public boolean freeIfQuiescent() {
            int observed = state.get();
            for (;;) {
                if ((observed & CLOSED) == 0) {
                    throw new IllegalStateException("cannot free an open native arena");
                }
                if ((observed & FREE_ENTERED) != 0) return true;
                if ((observed & READERS) != 0) return false;
                if (state.compareAndSet(observed, observed | FREE_ENTERED)) {
                    creator.free(handle);
                    return true;
                }
                observed = state.get();
            }
        }

        /** Hand off reclamation after a bounded wait, including the last-reader race. */
        public void deferFree(Throwable primary) {
            if ((state.get() & (DEFERRED | FREE_ENTERED)) != 0) return;
            retirementPrimary = primary;
            setFlag(CLOSED | DEFERRED);
            freeIfQuiescent();
        }

        private void setFlag(int flag) {
            int observed = state.get();
            while (!state.compareAndSet(observed, observed | flag)) {
                observed = state.get();
            }
        }

        @Override
        public void release(Throwable primary) {
            int remaining = state.decrementAndGet();
            if ((remaining & (DEFERRED | READERS)) == DEFERRED) {
                if (retirementPrimary != null) {
                    if (primary != null && primary != retirementPrimary) {
                        try {
                            retirementPrimary.addSuppressed(primary);
                        } catch (Throwable ignored) {
                            // The retirement winner remains primary even if diagnostics fail.
                        }
                    }
                    primary = retirementPrimary;
                }
                try {
                    freeIfQuiescent();
                } catch (Throwable cleanup) {
                    if (primary == null) FailStops.<RuntimeException>rethrow(cleanup);
                    if (cleanup != primary) {
                        try {
                            primary.addSuppressed(cleanup);
                        } catch (Throwable ignored) {
                            // Cleanup cannot replace the exact entered operation failure.
                        }
                    }
                }
            }
        }

        @Override public long handle() { return handle; }
        @Override public int canSpawn(int category, int x, int z) {
            return creator.canSpawn(handle, category, x, z);
        }
        @Override public void addMob(int category, int x, int z) {
            creator.addMob(handle, category, x, z);
        }
        @Override public int tryReservePack(int category, int x, int z, int count) {
            return creator.tryReservePack(handle, category, x, z, count);
        }
        @Override public void releasePack(int category, int x, int z, int count) {
            creator.releasePack(handle, category, x, z, count);
        }
    }


    private static volatile Provider provider;

    private LocalCapBridge() {}

    /** Last registration wins for future builds. Existing arenas retain their creator. */
    public static void register(Provider p) {
        provider = p;
        LOGGER.info("Local mob-cap provider registered: {}", p.getClass().getName());
    }

    /** The provider, or {@code null} when absent (callers use the Java {@code LocalMobCapCalculator}). */
    public static Provider provider() {
        return provider;
    }

}
