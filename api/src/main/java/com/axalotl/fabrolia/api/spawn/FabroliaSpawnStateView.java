package com.axalotl.fabrolia.api.spawn;

import net.minecraft.world.entity.MobCategory;

import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Interface mixed into {@code NaturalSpawner.SpawnState} by
 * {@code SpawnStateMixin}. Exposes:
 * <ul>
 *   <li>{@link #fabrolia$countsBacking()} — the {@link AtomicIntegerArray}
 *       that backs the overridden {@code mobCategoryCounts}, used by
 *       fast-bail injectors to read the live count without going through
 *       the {@code Object2IntMap} wrapper.</li>
 *   <li>{@link #fabrolia$canSpawnForCategoryGlobal(MobCategory)} — delegates
 *       to vanilla's private {@code SpawnState#canSpawnForCategoryGlobal}
 *       via {@code @Shadow}. Routing through the vanilla method means the
 *       cap decision respects <em>any</em> mixin chain touching the cap
 *       formula — e.g. ServerCore's mobcap multiplier that overwrites
 *       {@code canSpawnForCategoryGlobal} to multiply the budget.
 *       Precomputing {@code maxPerChunk × spawnableChunkCount / 289} on
 *       our side would bake in the vanilla formula and ignore their
 *       overwrite, under-spawning when their multiplier is &gt;1.</li>
 * </ul>
 */
public interface FabroliaSpawnStateView {
    AtomicIntegerArray fabrolia$countsBacking();
    boolean fabrolia$canSpawnForCategoryGlobal(MobCategory category);

    /** The cookie's LocalMobCapCalculator, for the spawncap debug probe (task #32). */
    default Object fabrolia$localCalc() {
        return null;
    }

    /** Chunk-pass local-cap approval as an atomic pack RESERVATION on this
     *  cookie's calculator (see {@link FabroliaLocalCapView#fabrolia$tryReservePack}).
     *  0 = rejected (vanilla's canSpawnForCategoryLocal false); non-zero =
     *  approved, value is the release token. */
    default int fabrolia$tryReserveLocalPack(MobCategory category, long packedChunkPos) {
        return 0;
    }

    /** Release an unconsumed pack reservation made by
     *  {@link #fabrolia$tryReserveLocalPack} (token passed through verbatim). */
    default void fabrolia$releaseLocalPack(MobCategory category, long packedChunkPos, int token) {
    }
}
