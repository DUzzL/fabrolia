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
 *    <li>The PUBLIC container API ({@code getAll}/{@code schedule}/
 *        {@code hasScheduledTick}/{@code peek}/{@code poll}/{@code count}/
 *        {@code pack}/{@code unpack}) is overwritten by Lithium consistently
 *        and is the only mutation surface Fabrolia uses now. Packed
 *        (deserialized, not-yet-unpacked) state is intentionally unreachable
 *        through that API, so packed containers are never mutated; the
 *        repair facility defers them until the chunk unpacks naturally (see
 *        {@code TickRepair}).</li>
 *    <li><b>task #37 (2026-07-28 22:10 production NPE):</b> the one API
 *        member the audit trusted too much is {@code removeIf} — Lithium's
 *        overwrite iterates ABSOLUTE indices {@code 0..size()} over its
 *        poll-compacted OrderedTickQueue ring and NPEs on any
 *        partially-polled bucket (island tick slicing produces those
 *        routinely; vanilla reaches the state only via MAX_TICKS budget
 *        interrupts — their latent {@code /fill}-crash). The wrap below
 *        replaces every {@code removeIf} body with the poll-drain
 *        equivalent ({@link com.axalotl.fabrolia.common.regions.TickDomains#drainRemoveIf}),
 *        which uses only the methods both implementations keep correct.</li>
 *  </ul>
 */
@Mixin(value = LevelChunkTicks.class, priority = 2000)
public abstract class LevelChunkTicksMixin implements FabroliaTickDomain {

    /** Priority 2000: must wrap the FINAL body (after Lithium's @Overwrite at
     *  1000). Never calls {@code original} — the drain IS the replacement
     *  semantics, identical for vanilla and required for Lithium. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "removeIf")
    private void fabrolia$implAgnosticRemoveIf(java.util.function.Predicate<net.minecraft.world.ticks.ScheduledTick<?>> predicate,
                                               com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        com.axalotl.fabrolia.common.regions.TickDomains.drainRemoveIfErased(
                (LevelChunkTicks<?>) (Object) this, (java.util.function.Predicate) predicate);
    }

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
