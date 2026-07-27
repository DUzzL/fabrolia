package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.IslandClock;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * A2 L-clock redirect — the single choke point of {@code getGameTime()}.
 *
 * <p>{@code LevelAccessor.getGameTime()} (the default every entity/BE/tick
 * consumer calls through {@code Level}) resolves to
 * {@code levelData.getGameTime()}; {@code DerivedLevelData} (non-overworld
 * dimensions) delegates to the wrapped primary — so overwriting HERE covers
 * every L-lane read in every dimension with one hook. Execution contexts that
 * belong to an island (worker holding a member-region claim, unit-coordinator
 * segments) read the island's logical time; everything else — main thread,
 * chunk/IO workers, saves — reads the real counter, bit-for-bit vanilla.
 * The calendar (W) lane — 26.2 clock system, {@code getOverworldClockTime} —
 * is a different method family and is untouched.
 *
 * <p>Sync mode / async off: {@code IslandClock.contextTime()} can only answer
 * -1 (island domains never publish a unit time), so this is exactly
 * {@code return this.gameTime}. Cost on non-worker threads: one type check.
 */
@Mixin(PrimaryLevelData.class)
public abstract class PrimaryLevelDataMixin implements com.axalotl.fabrolia.common.regions.RawGameTime {

    @Shadow private long gameTime;

    /**
     * @author Fabrolia (A2 async islands)
     * @reason Route L-lane game-time reads through the island execution
     * context (docs/ASYNC-ISLANDS.md timing model). The vanilla body is
     * {@code return this.gameTime} — an injector would allocate a callback on
     * one of the hottest getters in the game; the overwrite keeps it to two
     * loads and a branch.
     */
    @Overwrite
    public long getGameTime() {
        long island = IslandClock.contextTime();
        return island >= 0 ? island : this.gameTime;
    }

    /** A8: the REAL counter regardless of execution context (clock-domain
     *  rebase math needs both clocks at once). */
    @Override
    public long fabrolia$rawGameTime() {
        return this.gameTime;
    }
}
