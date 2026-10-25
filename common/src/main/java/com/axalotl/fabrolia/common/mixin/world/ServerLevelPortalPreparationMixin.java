package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.regions.NetherPortalPreparation;
import com.axalotl.fabrolia.common.regions.PoiPublicationFence;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public abstract class ServerLevelPortalPreparationMixin implements NetherPortalPreparation.PoiAccess {
    @Unique
    private final PoiPublicationFence fabrolia$portalPublications =
            new PoiPublicationFence(RegionScheduler::publishFatalAndSelectWinner);

    /** Positions with a queued nether_portal POI publication (count per position). */
    @Unique
    private final java.util.concurrent.ConcurrentHashMap<Long, Integer> fabrolia$pendingPortalPositions =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public PoiPublicationFence fabrolia$portalPublications() {
        return this.fabrolia$portalPublications;
    }

    /**
     * Only {@code nether_portal} POI add/remove callbacks enter the fence: the
     * portal search reads no other POI type. Beds, job sites, hives and
     * lodestones never wait for Main.
     *
     * <p>{@code server.execute} runs its callback inline on the ticking server
     * thread; it queues only off-thread or inside an event-loop task. 26.3
     * handles packets in {@code processQueuedPackets} and ticks levels outside
     * any task, so every in-game block change publishes its POI change before
     * {@code setBlock} returns. A Fabrolia simulation thread is that ticking
     * thread for its own chunks. Queueing there published the change at Main's
     * next task drain instead: after the island's later phases, and several
     * island ticks late while Main lags. Villagers could not claim a re-placed
     * job site in the same tick, and a raid's village check sampled a POI state
     * vanilla never shows (2026-10-13: raids stopped about a second after the
     * Raid Omen expired).
     */
    @WrapOperation(method = {"lambda$updatePOIOnBlockStateChange$0", "lambda$updatePOIOnBlockStateChange$2"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;execute(Ljava/lang/Runnable;)V"),
            require = 2, allow = 2)
    private void fabrolia$trackPortalPublication(MinecraftServer server, Runnable callback,
                                                Operation<Void> original,
                                                @Local(argsOnly = true) BlockPos pos,
                                                @Local(argsOnly = true) Holder<PoiType> poiType) {
        long key = pos.asLong();
        if (!poiType.is(PoiTypes.NETHER_PORTAL)) {
            if (this.fabrolia$pendingPortalPositions.containsKey(key)) {
                // A portal publication of this position is still queued (portal
                // frame broken, then a POI block placed, or one state change
                // portal -> other POI). Vanilla applies them in order; running this
                // one inline would let the queued portal remove delete the new
                // record (audit D-19). Queue it behind, never inline.
                server.schedule(new TickTask(server.getTickCount(), callback));
            } else if (ParallelProcessor.isServerExecutionThread()) {
                callback.run();
            } else {
                original.call(server, callback);
            }
            return;
        }
        this.fabrolia$pendingPortalPositions.merge(key, 1, Integer::sum);
        Runnable counted = () -> {
            try {
                callback.run();
            } finally {
                this.fabrolia$pendingPortalPositions.computeIfPresent(key, (k, v) -> v > 1 ? v - 1 : null);
            }
        };
        this.fabrolia$portalPublications.submit(counted, tracked -> {
            if (NetherPortalPreparation.deferGenerationPublication((ServerLevel) (Object) this)) {
                // execute may inline on Main even with older queued worldgen adds.
                // Do not let a portal replacement remove overtake its earlier add.
                server.schedule(new TickTask(server.getTickCount(), tracked));
            } else {
                original.call(server, tracked);
            }
        });
    }

    @Inject(method = "getWorldBorder", at = @At("HEAD"), cancellable = true)
    private void fabrolia$fixedPortalBorder(CallbackInfoReturnable<WorldBorder> cir) {
        WorldBorder border = NetherPortalPreparation.snapshotBorder((ServerLevel) (Object) this);
        if (border != null) cir.setReturnValue(border);
    }
}
