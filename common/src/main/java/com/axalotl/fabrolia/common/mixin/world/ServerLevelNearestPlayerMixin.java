package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.parallelised.utils.HardColliderIndex;
import com.axalotl.fabrolia.common.parallelised.utils.PlayerChunkIndex;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Player-proximity traversal backed by the per-tick {@link PlayerChunkIndex}
 * when it is usable, falling back to the vanilla live traversal otherwise.
 * Also owns the level's incrementally maintained {@link HardColliderIndex}.
 */
@Mixin(value = ServerLevel.class, priority = 900)
public abstract class ServerLevelNearestPlayerMixin implements HardColliderIndex.Holder {

    @Unique
    private final HardColliderIndex.LevelIndex fabrolia$hardColliderIndex =
            new HardColliderIndex.LevelIndex();

    @Shadow public abstract List<ServerPlayer> players();

    @Override
    public HardColliderIndex.LevelIndex fabrolia$hardColliderIndex() {
        return this.fabrolia$hardColliderIndex;
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void fabrolia$rebuildPlayerIndex(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            ServerLevel level = (ServerLevel) (Object) this;
            PlayerChunkIndex.rebuild(level);
            // Queued tracker add/remove of the inter-tick interval are applied on
            // the tick pool while Main runs time, raids and chunk work.
            com.axalotl.fabrolia.common.regions.VmpTrackerIndexApply.scheduleFor(level);
            RegionizedServer rs = RegionScheduler.forLevel(level);
            if (rs != null) {
                rs.onLevelTickStart();
                rs.evictIdleRegions();
                rs.rebuildIslandsIfDirty();
            }
        });
        // MAIN_SYNC safe point outside the entity phase (no claim, fence or
        // accepted body held here): an island stopped by a service request is
        // served now instead of after this level's chunk tick. Async levels
        // only — exact-ordinal levels drain completely in their entity phase.
        RegionizedServer rs = RegionScheduler.forLevel((ServerLevel) (Object) this);
        if (rs != null && rs.asyncActive) {
            if (com.axalotl.fabrolia.common.regions.IslandCommands.hasRoutedPending()) {
                com.axalotl.fabrolia.common.regions.IslandCommands.drainRoutedBatch();
            }
            RegionScheduler.drainMainSyncOutOfPhase();
            // Parked island waiters (escalated function heads, routed commands)
            // are served at this fence-free point too, not only at the pumps.
            if (com.axalotl.fabrolia.common.regions.IslandCommands.hasRoutedPending()) {
                com.axalotl.fabrolia.common.regions.IslandCommands.drainRoutedBatch();
            }
        }
    }

    /**
     * EntityGetter#getNearestPlayer(x, y, z, maxDistance, predicate) — the
     * base of every predicate-variant chain, including checkDespawn's
     * unbounded {@code maxDistance = -1} (one complete live traversal).
     */
    @SuppressWarnings("unused")
    public @Nullable Player getNearestPlayer(double x, double y, double z, double maxDistance,
                                             @Nullable Predicate<Entity> predicate) {
        ServerLevel self = (ServerLevel) (Object) this;
        PlayerChunkIndex idx = PlayerChunkIndex.usable(self);
        if (idx != null) {
            return idx.nearestPlayer(x, y, z, maxDistance, predicate);
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
     */
    @SuppressWarnings({"unused", "unchecked"})
    public <T extends LivingEntity> @Nullable T getNearestEntity(List<? extends T> entities,
                                                                 TargetingConditions conditions,
                                                                 @Nullable LivingEntity source,
                                                                 double x, double y, double z) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (entities == this.players()) {
            double range = ((TargetingConditionsAccessor) conditions).fabrolia$range();
            PlayerChunkIndex idx;
            if (range > 0.0 && (idx = PlayerChunkIndex.usable(self)) != null) {
                ServerPlayer result = idx.nearestPlayer(self, conditions, source, range, x, y, z);
                return (T) result;
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
