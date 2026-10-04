package com.axalotl.fabrolia.common.mixin.entity.spawn;

import com.axalotl.fabrolia.api.spawn.FabroliaSpawnStateView;
import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.axalotl.fabrolia.common.parallelised.utils.ParallelSpawnHelper;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


@Mixin(value = NaturalSpawner.class, priority = 1100)
public abstract class NaturalSpawnerMixin {

    @Inject(method = "createState", at = @At("HEAD"), cancellable = true)
    private static void fabrolia$parallelCreateState(int spawnableChunkCount, ServerLevel level, NaturalSpawner.ChunkGetter chunkGetter, LocalMobCapCalculator localMobCapCalculator, CallbackInfoReturnable<NaturalSpawner.SpawnState> cir) {
        if (FabroliaConfig.disabled || !FabroliaConfig.enableFabroliaSpawn) return;
        if (ParallelProcessor.executor == null) return;
        cir.setReturnValue(ParallelSpawnHelper.buildState(spawnableChunkCount, level, chunkGetter, localMobCapCalculator));
    }

    /**
     * LOCAL-cap chunk-pass approval as an atomic pack RESERVATION (task #32
     * endgame). Vanilla checks the local cap once here and then completes a
     * pack of up to four without re-checking — sequential chunk passes make
     * that safe (overshoot ≈ one pack). Fabrolia's parallel chunk passes all
     * read the count in the same open window, so approval and visibility must
     * be one atomic step: the fused check+reserve takes the pack's worth of
     * slots on every near player the moment the pass is approved, and a
     * concurrent pass's own fused read already sees them. Inside the pack:
     * exactly vanilla, no candidate gate (the farm-starve fix). The paired
     * wrap below returns the unconsumed reservation at pack end.
     *
     * <p>Algorithm + "cap + one pack" bound argument:
     * {@link com.axalotl.fabrolia.common.parallelised.utils.LocalCapPackReservation}.
     *
     * <p>Rejection is final (no original re-check): the fused read is
     * vanilla's own ∃-room predicate over counts + in-flight reservations, so
     * re-consulting the raw counts would reopen the window in exactly the
     * contended case. Vanilla's DEBUG_IGNORE_LOCAL_MOB_CAP escape is
     * replicated. While the gate is ON, a mod overwrite of
     * canSpawnForCategoryLocal is bypassed (as the removed per-candidate
     * recheck effectively did); the vanilla path (gate off / no executor)
     * calls through unchanged.
     *
     * <p>An island lane's cookie ({@link FabroliaSpawnStateView#fabrolia$serialLocalCap})
     * is consumed by one sequential chunk pass, where the fused step reduces
     * to vanilla's plain check: it takes the vanilla call, with no
     * reservation to take or return.
     */
    @WrapOperation(method = "spawnForChunk",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/NaturalSpawner$SpawnState;canSpawnForCategoryLocal(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/world/level/ChunkPos;)Z"))
    private static boolean fabrolia$approveAndReservePack(NaturalSpawner.SpawnState state,
            MobCategory category, ChunkPos pos, Operation<Boolean> original,
            @Share("fabrolia$packReservation") LocalIntRef reservation) {
        if (FabroliaConfig.disabled || !FabroliaConfig.enableFabroliaSpawn
                || !FabroliaConfig.fabroliaSpawnLocalCapReservation
                || ParallelProcessor.executor == null
                || ((FabroliaSpawnStateView) state).fabrolia$serialLocalCap()) {
            reservation.set(0);
            return original.call(state, category, pos);
        }
        int token = ((FabroliaSpawnStateView) state).fabrolia$tryReserveLocalPack(category, pos.pack());
        reservation.set(token);
        return token != 0 || net.minecraft.SharedConstants.DEBUG_IGNORE_LOCAL_MOB_CAP;
    }

    /** Pack end: hand back the unconsumed reservation. The pack's actual
     *  spawns arrived through vanilla afterSpawn → addMob (+1 each), so the
     *  counts settle at exactly the spawned number — vanilla-identical final
     *  accounting, the reservation only existed for concurrent visibility
     *  while the pack was in flight. Token is captured per approval (never
     *  re-derived from config), so a mid-tick toggle flip can neither leak
     *  nor double-release. */
    @WrapOperation(method = "spawnForChunk",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/NaturalSpawner;spawnCategoryForChunk(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V"))
    private static void fabrolia$releasePackAfterSpawn(MobCategory category, ServerLevel level,
            LevelChunk chunk, NaturalSpawner.SpawnPredicate extraTest,
            NaturalSpawner.AfterSpawnCallback spawnCallback, Operation<Void> original,
            @Local(argsOnly = true) NaturalSpawner.SpawnState state,
            @Share("fabrolia$packReservation") LocalIntRef reservation) {
        int token = reservation.get();
        if (token == 0) {
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> original.call(category, level, chunk, extraTest, spawnCallback));
            return;
        }
        Throwable failure = null;
        try {
            reservation.set(0);
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> original.call(category, level, chunk, extraTest, spawnCallback));
        } catch (Throwable thrown) {
            // Whether marker clear, admission, or the vanilla pack failed,
            // select W before returning the reservation so no later pack can
            // enter through the gap.
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        // Reservation return is mandatory cleanup even when the pack was
        // refused before body entry or threw after partial vanilla effects.
        try {
            ((FabroliaSpawnStateView) state).fabrolia$releaseLocalPack(category, chunk.getPos().pack(), token);
        } catch (Throwable cleanup) {
            failure = RegionScheduler.publishFatalAndSelectWinner(
                    fabrolia$mergeFailure(failure, cleanup));
        }
        if (failure == null) {
            try {
                // Cleanup completion is not a success edge. Refuse a normal
                // return if peer W won while the reservation was released.
                RegionScheduler.commitSuccessIfHealthy(() -> {});
            } catch (Throwable peerFailure) {
                failure = RegionScheduler.publishFatalAndSelectWinner(peerFailure);
            }
        }
        if (failure != null) {
            NaturalSpawnerMixin.<RuntimeException>fabrolia$rethrow(failure);
        }
    }

    private static Throwable fabrolia$mergeFailure(Throwable primary, Throwable cleanup) {
        if (primary == null) return cleanup;
        if (cleanup != null && cleanup != primary) {
            try {
                primary.addSuppressed(cleanup);
            } catch (Throwable suppressionFailure) {
                // Suppression metadata cannot replace the exact pack failure.
            }
        }
        return primary;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void fabrolia$rethrow(Throwable failure) throws T {
        throw (T) failure;
    }

    // Deliberately NO getMobForSpawn cap bail and NO spawnForChunk state binding.
    // A previous pair of injects here cancelled mob CREATION (returned null) once
    // the global count crossed the cap mid-tick — and a null from getMobForSpawn
    // makes vanilla abort the chunk's ENTIRE spawn attempt (all three pack
    // groups), not just one mob. Vanilla's global cap is a tick-entry filter
    // (getFilteredSpawningCategories) and the within-tick overshoot it allows is
    // the burst-refill rate cap-saturated farms depend on; clamping it read as an
    // intermittent "mobcap saturation" slowdown, binding only under cap pressure
    // (gold-farm report, removed 2026-07-07 together with the canSpawn bail in
    // SpawnStateMixin and the SPAWN_STATE ScopedValue that only served this).
}
