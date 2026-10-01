package com.axalotl.fabrolia.common.mixin.entity.spawn;

import com.axalotl.fabrolia.api.fastutil.AtomicMobCategoryCounts;
import com.axalotl.fabrolia.api.spawn.FabroliaSpawnStateView;
import com.axalotl.fabrolia.common.ParallelProcessor;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.PotentialCalculator;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
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

    // The task-#32 per-candidate LOCAL-cap recheck used to live here
    // (@Inject at canSpawn HEAD, cancellable). It bounded the parallel
    // overshoot but STARVED farms sitting AT the cap: after the cap filled,
    // every further candidate was rejected individually, degrading vanilla's
    // complete-the-pack behaviour to a trickle (wither-skeleton farm report).
    // Replaced by atomic pack RESERVATION at chunk-pass approval —
    // NaturalSpawnerMixin.fabrolia$approveAndReservePack — which restores the
    // sequential visibility the recheck was faking without rejecting any
    // candidate inside an already-approved pack. canSpawn retains vanilla's
    // potential-energy check, with invocation-owned spawn costs instead of a
    // shared last-candidate memo.

    /**
     * @author Fabrolia
     * @reason A SpawnState spans region workers; candidate costs must remain method-local.
     */
    @Overwrite
    private boolean canSpawn(EntityType<?> type, Level level, BlockPos testPos, ChunkAccess chunk) {
        MobSpawnSettings.MobSpawnCost cost = level.environmentAttributes()
                .getValue(EnvironmentAttributes.NATURAL_MOB_SPAWNS, testPos)
                .getMobSpawnCost(type);
        if (cost == null) {
            return true;
        }
        double charge = cost.charge();
        double energyChange = this.spawnPotential.getPotentialEnergyChange(testPos, charge);
        return energyChange <= cost.energyBudget();
    }

    /**
     * @author Fabrolia
     * @reason Select the accepted mob's own spawn cost, never another worker's last check.
     */
    @Overwrite
    private void afterSpawn(Mob mob, ChunkAccess chunk) {
        EntityType<?> type = mob.getType();
        BlockPos pos = mob.blockPosition();
        // Re-read at the accepted position: a prior check may belong to another
        // region, type or biome (and its BlockPos may itself have been mutable).
        MobSpawnSettings.MobSpawnCost cost = mob.level().environmentAttributes()
                .getValue(EnvironmentAttributes.NATURAL_MOB_SPAWNS, pos)
                .getMobSpawnCost(type);
        double charge = cost == null ? 0.0 : cost.charge();
        this.spawnPotential.addCharge(pos, charge);
        MobCategory category = type.getCategory();
        this.mobCategoryCounts.addTo(category, 1);
        this.localMobCapCalculator.addMob(ChunkPos.containing(pos), category);
    }

    /** Cadence instrumentation only (was fused with the removed recheck):
     *  every candidate CHECK, per category — /fabrolia regions debug spawnrate
     *  reads checks vs accepts. Never cancels. */
    @Inject(method = "canSpawn(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/chunk/ChunkAccess;)Z",
            at = @At("HEAD"))
    private void fabrolia$countCheck(net.minecraft.world.entity.EntityType<?> type,
            Level level, net.minecraft.core.BlockPos pos, net.minecraft.world.level.chunk.ChunkAccess chunk,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (com.axalotl.fabrolia.common.config.FabroliaConfig.disabled
                || !com.axalotl.fabrolia.common.config.FabroliaConfig.enableFabroliaSpawn
                || ParallelProcessor.executor == null) {
            return;
        }
        MobCategory cat = type.getCategory();
        if (cat == MobCategory.MISC) {
            return;
        }
        if (level instanceof net.minecraft.server.level.ServerLevel sl) {
            com.axalotl.fabrolia.common.parallelised.utils.SpawnRateDiag.onCheck(sl, cat);
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

    @Override
    public int fabrolia$tryReserveLocalPack(MobCategory cat, long packedChunkPos) {
        return ((com.axalotl.fabrolia.api.spawn.FabroliaLocalCapView) this.localMobCapCalculator)
                .fabrolia$tryReservePack(packedChunkPos, cat.ordinal(),
                        com.axalotl.fabrolia.common.parallelised.utils.LocalCapPackReservation.PACK_RESERVE);
    }

    @Override
    public void fabrolia$releaseLocalPack(MobCategory cat, long packedChunkPos, int token) {
        ((com.axalotl.fabrolia.api.spawn.FabroliaLocalCapView) this.localMobCapCalculator)
                .fabrolia$releasePack(packedChunkPos, cat.ordinal(), token);
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
