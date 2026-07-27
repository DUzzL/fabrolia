package com.axalotl.fabrolia.common.utils;

import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pluggable provider for the LOCAL per-player mob cap ({@code LocalMobCapCalculator} —
 * {@code canSpawn} / {@code addMob} / the {@code playersNearChunk} resolution). Oxidizium
 * registers a provider backed by an off-heap Send+Sync Rust arena (built once per level tick,
 * read / atomically incremented lock-free by the parallel spawn workers — no concurrent map on
 * the hot path, so the {@code getPlayersNear} computeIfAbsent race class cannot exist).
 *
 * <p>Symbiosis, mirror of {@link SensorCandidateBridge}: Fabrolia15 alone runs its Java
 * {@code LocalMobCapCalculator} (correct via the {@code Long2ObjectConcurrentHashMap}
 * computeIfAbsent overload fix). With Oxidizium present + enabled, the local cap runs on the
 * Rust arena. The GLOBAL cap always stays in vanilla Java (ServerCore's mob-cap multiplier +
 * phantom-spawning's wrap keep applying). No provider / decline / throw → Java fallback.
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
 *       0 capped/no-near-player, -1 error (caller falls back to Java);</li>
 *   <li>{@link Provider#addMob} — vanilla {@code addMob}: local +1 to every near player;</li>
 *   <li>{@link Provider#free} — reclaim a handle (tick end).</li>
 * </ul>
 * A throw drops the provider for the session (same containment as {@link SensorCandidateBridge}).
 */
public final class LocalCapBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalCapBridge.class);

    public interface Provider {
        long buildArena(ServerLevel level, long[] mobChunks, int[] mobCategories, int mobCount);

        int canSpawn(long handle, int categoryOrdinal, int chunkX, int chunkZ);

        void addMob(long handle, int categoryOrdinal, int chunkX, int chunkZ);

        void free(long handle);
    }

    private static volatile Provider provider;

    private LocalCapBridge() {}

    /** Register the (single) local-cap provider. Last registration wins. */
    public static void register(Provider p) {
        provider = p;
        LOGGER.info("Local mob-cap provider registered: {}", p.getClass().getName());
    }

    /** The provider, or {@code null} when absent (callers use the Java {@code LocalMobCapCalculator}). */
    public static Provider provider() {
        return provider;
    }

    /** Drop a broken provider for the rest of the session (a throw crossed the seam). */
    public static void disable(Throwable cause) {
        provider = null;
        LOGGER.error("Local mob-cap provider threw — disabled for this session", cause);
    }
}
