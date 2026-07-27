package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.parallelised.utils.PlayerChunkIndex;
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

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Player-proximity queries through the per-tick chunk-bucket index
 * ({@link PlayerChunkIndex}) instead of vanilla's linear scan over EVERY
 * player. Class methods win over interface defaults, so these overrides catch
 * every {@code getNearestPlayer} chain (EntityGetter's predicate variants,
 * ServerEntityGetter's TargetingConditions variants via
 * {@code getNearestEntity}, Mob.checkDespawn's unbounded query).
 *
 * <p>Selection reproduces vanilla bit-for-bit: predicate/conditions tested
 * per candidate (pure — players the buckets prune would have failed the
 * distance check anyway), strict-{@code <} distance comparison, ties resolved
 * by {@code players()} order. Distances are live; bucket membership is the
 * tick-start snapshot (the established snapshot doctrine). No usable index —
 * boot, foreign list, zero/negative conditions range — falls back to an
 * inlined copy of the vanilla loop.
 */
@Mixin(value = ServerLevel.class, priority = 900)
public abstract class ServerLevelNearestPlayerMixin {

    @Shadow public abstract List<ServerPlayer> players();

    @Inject(method = "tick", at = @At("HEAD"))
    private void fabrolia$rebuildPlayerIndex(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        PlayerChunkIndex.rebuild((ServerLevel) (Object) this);
        // Hard-collider cell snapshot for anyNear's bucketed enumeration —
        // tick-head positions, O(collider count), every tick.
        com.axalotl.fabrolia.common.parallelised.utils.HardColliderIndex
                .rebuildCells((ServerLevel) (Object) this);
        // R4 color-DAG epoch boundary — one bump per level tick, before any
        // parallel phase can claim regions for the new tick.
        var rs = com.axalotl.fabrolia.common.regions.RegionScheduler.forLevel((ServerLevel) (Object) this);
        if (rs != null) {
            rs.onLevelTickStart();
            // Reclaim regions left behind by exploration BEFORE the island rebuild
            // scans the set — lazily-created regions were never removed, so the map
            // (and every O(regions) scan over it) grew unbounded and mspt climbed
            // monotonically on long flights. Workers are parked at tick HEAD, so this
            // read-then-reclaim does not race a concurrent populate.
            rs.evictIdleRegions();
            // F1 islands: refresh connected components before any phase
            // consumes the partition (µs-scale, only on ticket-churn ticks).
            rs.rebuildIslandsIfDirty();
        }
    }

    /**
     * EntityGetter#getNearestPlayer(x, y, z, maxDistance, predicate) — the
     * base of every predicate-variant chain, including checkDespawn's
     * unbounded {@code maxDistance = -1} (answered by expanding ring search).
     */
    @SuppressWarnings("unused")
    public @Nullable Player getNearestPlayer(double x, double y, double z, double maxDistance,
                                             @Nullable Predicate<Entity> predicate) {
        ServerLevel self = (ServerLevel) (Object) this;
        PlayerChunkIndex idx = PlayerChunkIndex.usable(self);
        if (idx != null) {
            ArrayList<ServerPlayer> candidates = maxDistance < 0.0
                    ? idx.nearestRing(x, y, z)
                    : idx.candidates(x, z, maxDistance);
            double best = -1.0;
            int bestOrd = Integer.MAX_VALUE;
            ServerPlayer result = null;
            for (int i = 0, n = candidates.size(); i < n; i++) {
                ServerPlayer p = candidates.get(i);
                if (predicate == null || predicate.test(p)) {
                    double d = p.distanceToSqr(x, y, z);
                    if (maxDistance < 0.0 || d < maxDistance * maxDistance) {
                        int ord = idx.ordinalOf(p);
                        if (result == null || d < best || (d == best && ord < bestOrd)) {
                            best = d;
                            bestOrd = ord;
                            result = p;
                        }
                    }
                }
            }
            return result;
        }
        // vanilla EntityGetter#getNearestPlayer, verbatim
        double best = -1.0;
        Player result = null;
        for (Player player : this.players()) {
            if (predicate == null || predicate.test(player)) {
                double d = player.distanceToSqr(x, y, z);
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
     * Index path only when the list IS the player list and the conditions
     * carry a positive range (the range check lives inside test(); scaling
     * factors only shrink it, so the bucket radius is an upper bound).
     */
    @SuppressWarnings("unused")
    public <T extends LivingEntity> @Nullable T getNearestEntity(List<? extends T> entities,
                                                                 TargetingConditions conditions,
                                                                 @Nullable LivingEntity source,
                                                                 double x, double y, double z) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (entities == this.players()) {
            double range = ((TargetingConditionsAccessor) conditions).fabrolia$range();
            PlayerChunkIndex idx;
            if (range > 0.0 && (idx = PlayerChunkIndex.usable(self)) != null) {
                ArrayList<ServerPlayer> candidates = idx.candidates(x, z, Math.max(range, 2.0));
                double best = -1.0;
                int bestOrd = Integer.MAX_VALUE;
                ServerPlayer result = null;
                for (int i = 0, n = candidates.size(); i < n; i++) {
                    ServerPlayer p = candidates.get(i);
                    if (conditions.test(self, source, p)) {
                        double d = p.distanceToSqr(x, y, z);
                        int ord = idx.ordinalOf(p);
                        if (result == null || d < best || (d == best && ord < bestOrd)) {
                            best = d;
                            bestOrd = ord;
                            result = p;
                        }
                    }
                }
                @SuppressWarnings("unchecked")
                T cast = (T) result;
                return cast;
            }
        }
        // vanilla ServerEntityGetter#getNearestEntity, verbatim
        double best = -1.0;
        T result = null;
        for (T t : entities) {
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
