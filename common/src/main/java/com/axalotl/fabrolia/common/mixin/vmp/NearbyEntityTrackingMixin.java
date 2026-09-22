package com.axalotl.fabrolia.common.mixin.vmp;

import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.axalotl.fabrolia.common.parallelised.utils.FabroliaTrackingDirtyFlag;
import com.axalotl.fabrolia.common.regions.IslandEntityTracking;
import com.axalotl.fabrolia.common.regions.IslandTrackedEntityAccess;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.ishland.vmp.common.playerwatching.ServerPlayerEntityExtension;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


/**
 * Keeps VMP's native tracker and packet order on the caller's gameplay owner.
 * Idle ticks may be skipped; island-owned bulk pairing still uses its owner tail.
 */
@Pseudo
@Mixin(targets = "com.ishland.vmp.common.playerwatching.NearbyEntityTracking", remap = false)
public abstract class NearbyEntityTrackingMixin {

    @Shadow @Final
    private Reference2ReferenceLinkedOpenHashMap<ServerPlayer, ReferenceLinkedOpenHashSet<ChunkMap.TrackedEntity>> playerTrackers;


    @Unique
    private final Object fabrolia$lock = new Object();

    /** Last value of dirty counter seen at tick0 entry (used for skip predicate). */
    @Unique
    private long fabrolia$lastSeenDirty = -1;

    @WrapOperation(method = "tickStaging",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkMap$TrackedEntity;updatePlayers(Ljava/util/List;)V"))
    private void fabrolia$captureStagedUpdate(ChunkMap.TrackedEntity tracker,
                                           java.util.List<ServerPlayer> players,
                                           Operation<Void> original) {
        if (tracker instanceof IslandTrackedEntityAccess access
                && IslandEntityTracking.stageBulkPairingRefreshIfOwned(access)) {
            return;
        }
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> original.call(tracker, players));
    }

    /** VMP's addEntityTracker loops over every player one-by-one. Collapse the
     *  whole initial burst into the same one-bit owner-tail bulk refresh. */
    @WrapOperation(method = "addEntityTracker",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkMap$TrackedEntity;updatePlayer("
                            + "Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void fabrolia$captureInitialTrackerPlayers(
            ChunkMap.TrackedEntity tracker, ServerPlayer player,
            Operation<Void> original) {
        if (tracker instanceof IslandTrackedEntityAccess access
                && IslandEntityTracking.stageBulkPairingRefreshIfOwned(access)) {
            return;
        }
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> original.call(tracker, player));
    }


    @WrapMethod(method = "addEntityTracker")
    private void fabrolia$lockAdd(ChunkMap.TrackedEntity tracker, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            synchronized (fabrolia$lock) { original.call(tracker); }
            FabroliaTrackingDirtyFlag.markDirty();
        });
    }

    @WrapMethod(method = "removeEntityTracker")
    private void fabrolia$lockRemove(ChunkMap.TrackedEntity tracker, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            synchronized (fabrolia$lock) { original.call(tracker); }
            FabroliaTrackingDirtyFlag.markDirty();
        });
    }

    /**
     * Reflective handles for VMP's private {@code vmpTracking$prev{X,Y,Z}} fields on
     * {@code ServerPlayer}. We don't have a public reset API in VMP and we don't want
     * to fork VMP for one method, so we set the fields directly. Resolved once at
     * class init; null if VMP isn't loaded or the field names changed upstream.
     */
    @Unique
    private static final java.lang.reflect.Field fabrolia$VMP_PREV_X;
    @Unique
    private static final java.lang.reflect.Field fabrolia$VMP_PREV_Y;
    @Unique
    private static final java.lang.reflect.Field fabrolia$VMP_PREV_Z;
    static {
        java.lang.reflect.Field x = null, y = null, z = null;
        try {
            Class<?> cls = ServerPlayer.class;
            x = cls.getDeclaredField("vmpTracking$prevX");
            y = cls.getDeclaredField("vmpTracking$prevY");
            z = cls.getDeclaredField("vmpTracking$prevZ");
            x.setAccessible(true);
            y.setAccessible(true);
            z.setAccessible(true);
        } catch (NoSuchFieldException | SecurityException ignored) {
            // VMP missing or field names changed — fabrolia$resetVmpPositionCache becomes a no-op.
            x = y = z = null;
        }
        fabrolia$VMP_PREV_X = x;
        fabrolia$VMP_PREV_Y = y;
        fabrolia$VMP_PREV_Z = z;
    }

    /**
     * Forces {@code vmpTracking$isPositionUpdated()} to return true on the next tick0 by
     * resetting the cached prev coords to NaN (NaN != anything, including itself). Called
     * on every {@code NET.addPlayer} so a player who lands in a new dim — including at the
     * same XYZ they had in the old dim — gets picked up by every stationary tracker in
     * range on their first post-teleport tick instead of being blind to non-moving
     * entities until they nudge sub-section.
     */
    @Unique
    private static void fabrolia$resetVmpPositionCache(ServerPlayer player) {
        if (fabrolia$VMP_PREV_X == null) return;
        try {
            fabrolia$VMP_PREV_X.setDouble(player, Double.NaN);
            fabrolia$VMP_PREV_Y.setDouble(player, Double.NaN);
            fabrolia$VMP_PREV_Z.setDouble(player, Double.NaN);
        } catch (IllegalAccessException failure) {
            NearbyEntityTrackingMixin.<RuntimeException>fabrolia$rethrow(failure);
        }
    }

    @WrapMethod(method = "addPlayer")
    private void fabrolia$lockAddPlayer(ServerPlayer player, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            synchronized (fabrolia$lock) { original.call(player); }
            fabrolia$resetVmpPositionCache(player);
            FabroliaTrackingDirtyFlag.markDirty();
        });
    }

    @WrapMethod(method = "removePlayer")
    private void fabrolia$lockRemovePlayer(ServerPlayer player, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            synchronized (fabrolia$lock) { original.call(player); }
            FabroliaTrackingDirtyFlag.markDirty();
        });
    }

    /**
     * Skip-when-idle check at tick0 HEAD. Cancels tick0 entirely if nothing has changed since
     * the last tick. Cheap: one atomic read + O(players) field compares.
     */
    @Inject(method = "tick0", at = @At("HEAD"), cancellable = true)
    private void fabrolia$maybeSkipTick0(CallbackInfo ci) {
        if (FabroliaConfig.disabled || !FabroliaConfig.enableFabroliaVmpTracking) return;

        long counter = FabroliaTrackingDirtyFlag.current();
        if (counter != this.fabrolia$lastSeenDirty) {
            this.fabrolia$lastSeenDirty = counter;
            return;
        }
        // Counter unchanged — no entity moved, no structural change. Confirm no player moved
        // (even sub-section): vmpTracking$isPositionUpdated is a 3-double compare against
        // cached prev coords, trivially fast.
        for (ServerPlayer p : this.playerTrackers.keySet()) {
            try {
                if (((ServerPlayerEntityExtension) p).vmpTracking$isPositionUpdated()) return;
            } catch (ClassCastException ignored) {
                return;
            }
        }
        ci.cancel();
    }

    /** The lock protects native tracker iteration; it does not confer gameplay authority. */
    @WrapMethod(method = "tick")
    private void fabrolia$lockTick(ChunkMap.DistanceManager ticketManager, Operation<Void> original) {
        synchronized (fabrolia$lock) {
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> original.call(ticketManager));
        }
    }

    @WrapOperation(method = "handleTracker", at = @At(value = "INVOKE", target = "Lcom/ishland/vmp/common/playerwatching/NearbyEntityTracking;tryTickTracker(Lnet/minecraft/server/level/ChunkMap$TrackedEntity;)V"))
    private void fabrolia$runTryTick(ChunkMap.TrackedEntity tracker, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(tracker));
    }

    @WrapOperation(method = "handleTracker", at = @At(value = "INVOKE", target = "Lcom/ishland/vmp/common/playerwatching/NearbyEntityTracking;tryUpdateTracker(Lnet/minecraft/server/level/ChunkMap$TrackedEntity;Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void fabrolia$runTryUpdate(ChunkMap.TrackedEntity tracker, ServerPlayer player, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> original.call(tracker, player));
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void fabrolia$rethrow(Throwable failure) throws T {
        throw (T) failure;
    }

}
