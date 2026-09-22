package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.NativeEntityDependencies;
import com.axalotl.fabrolia.common.regions.NativePlayerObservations;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Native player-proximity traversal with explicit observation authority.
 *
 * <p>Producer admission proves returned-player ownership before native entry.
 * Exact declared rich queries execute their complete traversal on Main's
 * query-only read pass; undeclared queries retain their ordinary authority.
 * Distances remain live, and ties use native player-list order. Every native
 * candidate reaches its predicate before distance filtering; index buckets
 * are preparation metadata, never a replacement callback domain.
 */
@Mixin(value = ServerLevel.class, priority = 1200)
public abstract class ServerLevelNearestPlayerMixin {

    @Shadow public abstract List<ServerPlayer> players();

    @Inject(method = "tick", at = @At("HEAD"))
    private void fabrolia$prepareLevelTick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        RegionScheduler.runMainControlBody(level.getServer(), () -> {
            // The index fences live reads, not empty concurrent metadata maintenance.
            com.axalotl.fabrolia.common.parallelised.utils.HardColliderIndex.rebuildCells(level);
            var rs = RegionScheduler.forLevel(level);
            if (rs != null) {
                rs.onLevelTickStart();
            }
        });
    }

    /**
     * EntityGetter#getNearestPlayer(x, y, z, maxDistance, predicate) — the
     * base of every predicate-variant chain, including checkDespawn's
     * unbounded {@code maxDistance = -1} (one complete live traversal).
     */
    @SuppressWarnings("unused")
    public @Nullable Player getNearestPlayer(double x, double y, double z, double maxDistance,
                                             @Nullable Predicate<Entity> predicate) {
        // vanilla EntityGetter#getNearestPlayer, verbatim
        double best = -1.0;
        Player result = null;
        for (ServerPlayer player : this.players()) {
            if (NativePlayerObservations.testPredicate(player, predicate)) {
                double d = NativePlayerObservations.distanceToSqr(player, x, y, z);
                if ((maxDistance < 0.0 || d < maxDistance * maxDistance) && (best == -1.0 || d < best)) {
                    best = d;
                    result = player;
                }
            }
        }
        return result;
    }

    /**
     * ServerEntityGetter#getNearestEntity — the funnel for every
     * TargetingConditions variant (Tempt/Avoid/NearestAttackableTarget…).
     * Unknown conditions retain their complete native callback domain. An exact
     * pre-admitted native producer may use its proven owned winner volume.
     */
    @SuppressWarnings("unused")
    public <T extends LivingEntity> @Nullable T getNearestEntity(List<? extends T> entities,
                                                                 TargetingConditions conditions,
                                                                 @Nullable LivingEntity source,
                                                                 double x, double y, double z) {
        ServerLevel self = (ServerLevel) (Object) this;
        var query = NativeEntityDependencies.declaredPlayerQuery(self, entities, conditions, source, x, y, z);
        if (query != null) {
            return query.findNearest(entities);
        }
        return fabrolia$nativeNearestEntity(entities, conditions, source, x, y, z);
    }

    @Unique
    private <T extends LivingEntity> @Nullable T fabrolia$nativeNearestEntity(List<? extends T> entities,
            TargetingConditions conditions, @Nullable LivingEntity source, double x, double y, double z) {
        ServerLevel self = (ServerLevel) (Object) this;
        // vanilla ServerEntityGetter#getNearestEntity, verbatim
        double best = -1.0;
        T result = null;
        for (T t : entities) {
            if (t instanceof ServerPlayer player) NativePlayerObservations.requireGameplayObservation(player);
            if (conditions.test(self, source, t)) {
                double d = t.distanceToSqr(x, y, z);
                if (best == -1.0 || d < best) {
                    best = d;
                    result = t;
                }
            }
        }
        return result;
    }

}
