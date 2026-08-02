package com.axalotl.fabrolia.common.mixin.entity.spawn;

import com.axalotl.fabrolia.api.fastutil.AtomicMobCategoryCounts;
import com.axalotl.fabrolia.api.spawn.FabroliaSpawnStateView;
import com.axalotl.fabrolia.common.ParallelProcessor;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.PotentialCalculator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicIntegerArray;

@Mixin(NaturalSpawner.SpawnState.class)
public abstract class SpawnStateMixin implements FabroliaSpawnStateView {

    @Shadow @Final private PotentialCalculator spawnPotential;
    @Shadow @Final private LocalMobCapCalculator localMobCapCalculator;
    @Shadow @Final private int spawnableChunkCount;

    @Mutable @Shadow @Final private Object2IntOpenHashMap<MobCategory> mobCategoryCounts;
    @Mutable @Shadow @Final private Object2IntMap<MobCategory> unmodifiableMobCategoryCounts;


    @Shadow
    private boolean canSpawnForCategoryGlobal(MobCategory mobCategory) {
        throw new AssertionError("shadow");
    }

    @Shadow
    private boolean canSpawnForCategoryLocal(MobCategory mobCategory, net.minecraft.world.level.ChunkPos pos) {
        throw new AssertionError("shadow");
    }

    /** PARALLEL LOCAL-CAP OVERSHOOT BOUND (task #32 cod runaway, root cause 3 —
     *  probe-proven): vanilla ticks spawning chunks SEQUENTIALLY, so chunk N's
     *  local-cap check sees chunks 1..N-1's spawns — overshoot is bounded at
     *  ~one pack. Fabrolia runs the chunk passes in parallel (pool / region
     *  workers): every eligible chunk reads the count in the same open window
     *  before any increment lands, approves together, and a whole
     *  neighborhood's packs land at once — measured 2-7× the local cap in a
     *  sawtooth (each despawn dip below cap re-opens the gate for another
     *  multi-chunk burst; production reached ~142 with the global cap ~166 as
     *  the only remaining bound). Re-checking the LOCAL cap per spawn
     *  CANDIDATE restores sequential-visibility semantics: after the cap
     *  fills, each concurrent chunk pass places at most one further mob
     *  (its next candidate sees the atomic counts) — total bound ≈ cap +
     *  parallel-lane count ≈ vanilla's cap + one pack. Predicate-false skips
     *  the candidate exactly like a failed potential/cost check (no
     *  chunk-attempt abort — the 2026-07-07 getMobForSpawn-null hazard class
     *  is not touched). Gated to the Fabrolia parallel path; the vanilla
     *  sequential path keeps vanilla's complete-the-pack behavior. The
     *  shadowed vanilla method embeds DEBUG_IGNORE_LOCAL_MOB_CAP, ServerCore
     *  overwrites, and the calculator routing (arena or Java) unchanged. */
    @Inject(method = "canSpawn(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/chunk/ChunkAccess;)Z",
            at = @At("HEAD"), cancellable = true)
    private void fabrolia$parallelLocalCapRecheck(net.minecraft.world.entity.EntityType<?> type,
            net.minecraft.core.BlockPos pos, net.minecraft.world.level.chunk.ChunkAccess chunk,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (com.axalotl.fabrolia.common.config.FabroliaConfig.disabled
                || !com.axalotl.fabrolia.common.config.FabroliaConfig.enableFabroliaSpawn
                || !com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaSpawnLocalCapRecheck
                || ParallelProcessor.executor == null) {
            return;
        }
        MobCategory cat = type.getCategory();
        if (cat == MobCategory.MISC) {
            return;
        }
        if (chunk instanceof net.minecraft.world.level.chunk.LevelChunk lc
                && lc.getLevel() instanceof net.minecraft.server.level.ServerLevel sl) {
            com.axalotl.fabrolia.common.parallelised.utils.SpawnRateDiag.onCheck(sl, cat);
        }
        if (!this.canSpawnForCategoryLocal(cat, chunk.getPos())) {
            cir.setReturnValue(false);
        }
    }

    /** Cadence instrumentation: every ACCEPTED natural spawn, per category. */
    @Inject(method = "afterSpawn", at = @At("HEAD"))
    private void fabrolia$countAccept(net.minecraft.world.entity.Mob mob,
            net.minecraft.world.level.chunk.ChunkAccess chunk,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (mob.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            com.axalotl.fabrolia.common.parallelised.utils.SpawnRateDiag.onAccept(sl, mob.getType().getCategory());
        }
    }

    @Unique private AtomicIntegerArray fabrolia$backing;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void fabrolia$initAtomicCounts(int spawnableChunkCount, Object2IntOpenHashMap<MobCategory> mobCategoryCounts, PotentialCalculator spawnPotential, LocalMobCapCalculator localMobCapCalculator, CallbackInfo ci) {
        MobCategory[] categories = ParallelProcessor.CATEGORIES;
        int n = categories.length;
        AtomicIntegerArray atomic = new AtomicIntegerArray(n);
        for (int i = 0; i < n; i++) {
            atomic.set(i, this.mobCategoryCounts.getInt(categories[i]));
        }

        AtomicMobCategoryCounts view = new AtomicMobCategoryCounts(atomic);
        this.mobCategoryCounts = view;
        this.unmodifiableMobCategoryCounts = Object2IntMaps.unmodifiable(view);
        this.fabrolia$backing = atomic;
    }

    @Override
    public AtomicIntegerArray fabrolia$countsBacking() {
        return this.fabrolia$backing;
    }

    @Override
    public Object fabrolia$localCalc() {
        return this.localMobCapCalculator;
    }

    @Override
    public boolean fabrolia$canSpawnForCategoryGlobal(MobCategory cat) {
        return this.canSpawnForCategoryGlobal(cat);
    }

    // Deliberately NO mid-tick global-cap bail in canSpawn. Vanilla evaluates the
    // global cap ONCE per tick (getFilteredSpawningCategories) and lets the tick
    // overshoot the cap as counts fill — that overshoot IS the burst-refill rate
    // cap-saturated farms are tuned around. A previous HEAD inject here re-checked
    // canSpawnForCategoryGlobal per position against the LIVE incrementing counts
    // and clamped spawning exactly at the cap boundary — measurably slower than
    // vanilla whenever the cap was the binding constraint, and only then, which
    // read as an intermittent "mobcap saturation" bug (gold-farm report,
    // removed 2026-07-07). Global-cap semantics belong to the tick-entry filter.
}
