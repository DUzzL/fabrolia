package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.FabroliaTickDomain;
import net.minecraft.world.ticks.LevelChunkTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Clock-domain tag storage (see {@link FabroliaTickDomain}). Volatile: the
 *  tag is written at safe points (main rebuild fence, container registration
 *  under the LevelTicks lock, shutdown collapse) and read from save/runner
 *  threads.
 *
 *  <p><b>audit15 (2026-07-27 production NPE):</b> this mixin previously also
 *  installed a packed-ticks accessor duck ({@code FabroliaPackedTicksAccess})
 *  shadowing the private {@code pendingTicks} list and {@code ticksPerPosition}
 *  dedup set so the bulk repair facility could mutate FULL-but-not-ticking
 *  containers. That was removed, deliberately and permanently:
 *
 *  <ul>
 *    <li>Vanilla's scheduler internals are NOT a stable surface across mods.
 *        Lithium ({@code mixin.world.tick_scheduler.LevelChunkTicksMixin})
 *        REPLACES them: its constructor hook derives an {@code allTicks} hash
 *        set from {@code pendingTicks} and then sets {@code ticksPerPosition}
 *        and {@code tickQueue} to null on EVERY container — reading
 *        {@code ticksPerPosition} NPEs (the production crash), and rewriting
 *        {@code pendingTicks} silently desyncs {@code allTicks} (removed
 *        entries keep blocking {@code hasScheduledTick}/dedup forever). There
 *        is no field a mixin could touch that is correct on both vanilla and
 *        Lithium.</li>
 *    <li>The PUBLIC container API ({@code getAll}/{@code removeIf}/
 *        {@code schedule}/{@code hasScheduledTick}/{@code peek}/{@code poll}/
 *        {@code count}/{@code pack}/{@code unpack}) is overwritten by Lithium
 *        consistently and is the only mutation surface Fabrolia uses now.
 *        Packed (deserialized, not-yet-unpacked) state is intentionally
 *        unreachable through that API — vanilla's {@code removeIf} iterates
 *        only the live queue — so packed containers are never mutated; the
 *        repair facility defers them until the chunk unpacks naturally (see
 *        {@code TickRepair}).</li>
 *  </ul>
 */
@Mixin(LevelChunkTicks.class)
public abstract class LevelChunkTicksMixin implements FabroliaTickDomain {

    @Unique private volatile long fabrolia$domainIsland = FabroliaTickDomain.WALL;
    @Unique private volatile long fabrolia$domainGen = -1;

    @Override
    public long fabrolia$domainIsland() {
        return fabrolia$domainIsland;
    }

    @Override
    public long fabrolia$domainGen() {
        return fabrolia$domainGen;
    }

    @Override
    public void fabrolia$setDomain(long islandId, long gen) {
        this.fabrolia$domainIsland = islandId;
        this.fabrolia$domainGen = gen;
    }
}
