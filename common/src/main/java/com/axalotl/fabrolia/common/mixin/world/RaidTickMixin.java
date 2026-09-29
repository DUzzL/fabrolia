package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.IslandRegistry;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
import com.axalotl.fabrolia.common.regions.RaidOwnerAccess;
import com.axalotl.fabrolia.common.regions.RegionWorkerThread;
import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


/**
 * Execute raid state changes under the owning island's quiescent coordinator
 * claim and publish an immutable view for registry selection. Assigned raids
 * tick once per island unit; only compatibility callers on physical Main
 * acquire the existing Main claim. Neighboring islands keep their own cadence.
 */
@Mixin(Raid.class)
public abstract class RaidTickMixin implements RaidOwnerAccess {

    @Shadow private int raidOmenLevel;
    @Shadow public abstract boolean isStarted();
    @Shadow public abstract int getMaxRaidOmenLevel();
    @Shadow public abstract boolean hasFirstWaveSpawned();
    @Unique private volatile RaidOwnerAccess.View fabrolia$view;
    @Unique private volatile Thread fabrolia$tickThread;

    @Override
    public RaidOwnerAccess.View fabrolia$raidView() {
        if (fabrolia$tickThread == Thread.currentThread()) {
            Raid raid = (Raid) (Object) this;
            RaidOwnerAccess.View published = fabrolia$view;
            if (published.center().equals(raid.getCenter()) && published.active() == raid.isActive()
                    && published.stopped() == raid.isStopped()) return published;
            // Reentrant vanilla queries see their own center/active writes, but
            // another owner cannot receive a migration until the body completes.
            return new RaidOwnerAccess.View(raid.getCenter().immutable(), raid.isActive(), raid.isStopped());
        }
        return fabrolia$view;
    }

    @Unique
    private void fabrolia$publishView() {
        Raid raid = (Raid) (Object) this;
        RaidOwnerAccess.View previous = fabrolia$view;
        if (previous != null && previous.center().equals(raid.getCenter())
                && previous.active() == raid.isActive() && previous.stopped() == raid.isStopped()) return;
        fabrolia$view = new RaidOwnerAccess.View(raid.getCenter().immutable(), raid.isActive(), raid.isStopped());
    }

    // Live creation and codec loading use independent constructors.
    @Inject(method = "<init>*", at = @At("RETURN"), require = 2)
    private void fabrolia$initializeView(CallbackInfo ci) {
        fabrolia$publishView();
    }

    @Inject(method = {"stop", "setCenter"}, at = @At("RETURN"))
    private void fabrolia$publishExternalMutation(CallbackInfo ci) {
        if (fabrolia$tickThread == null) fabrolia$publishView();
    }

    @Override
    public boolean fabrolia$absorbOmenIfEligible(ServerPlayer player, MobEffectInstance effect) {
        if (effect == null) return false;
        boolean award;
        // Same-island entity phases exclude the coordinator raid tick, but two
        // player regions may extend this raid together. Only scalar metadata is
        // serialized; advancement/stat callbacks never run under this monitor.
        synchronized (this) {
            if (isStarted() && raidOmenLevel >= getMaxRaidOmenLevel()) return false;
            raidOmenLevel = Mth.clamp(raidOmenLevel + effect.getAmplifier() + 1, 0, getMaxRaidOmenLevel());
            award = !hasFirstWaveSpawned();
        }
        if (award && player != null) {
            player.awardStat(Stats.RAID_TRIGGER);
            CriteriaTriggers.RAID_OMEN.trigger(player);
        }
        return true;
    }

    @WrapMethod(method = "getRaidOmenLevel")
    private int fabrolia$readOmenLevel(Operation<Integer> original) {
        synchronized (this) {
            return original.call();
        }
    }

    @WrapMethod(method = "setRaidOmenLevel")
    private void fabrolia$writeOmenLevel(int value, Operation<Void> original) {
        synchronized (this) {
            original.call(value);
        }
    }

    @Unique
    private void fabrolia$tickAndPublish(ServerLevel level, Operation<Void> original) {
        fabrolia$tickThread = Thread.currentThread();
        try {
            original.call(level);
        } finally {
            fabrolia$tickThread = null;
        }
        fabrolia$publishView();
    }

    @WrapMethod(method = "tick")
    private void fabrolia$ownerSafeRaidTick(ServerLevel level, Operation<Void> original) {
        RegionizedServer rs = RegionScheduler.forLevel(level);
        boolean coordinator = Thread.currentThread() instanceof RegionWorkerThread worker
                && worker.currentRegion == null;
        if (coordinator && RegionScheduler.ownsCurrentContext(level, fabrolia$view.center())) {
            RegionScheduler.runSuccessBodyIfHealthy(() -> fabrolia$tickAndPublish(level, original));
            return;
        }
        if (rs == null || !rs.asyncActive || rs.currentThreadHoldsAllClaims()) {
            RegionScheduler.runSuccessBodyIfHealthy(() -> fabrolia$tickAndPublish(level, original));
            return;
        }
        if (!RegionalPlayerEntityTicksV2.isPhysicalServerThread(level.getServer())) {
            throw new IllegalStateException("raid tick requires its quiescent island coordinator ("
                    + RegionScheduler.describeContext(level, fabrolia$view.center()) + ")");
        }
        IslandRegistry.PIsland owner =
                rs.islandOwnerOfChunk(ChunkPos.pack(fabrolia$view.center()));
        if (owner == null) {
            RegionScheduler.runSuccessBodyIfHealthy(() -> fabrolia$tickAndPublish(level, original));
            return;
        }
        IslandCommands.mainClaim(owner);
        Throwable failure = null;
        try {
            RegionScheduler.runSuccessBodyIfHealthy(() -> fabrolia$tickAndPublish(level, original));
        } catch (Throwable bodyFailure) {
            failure = RegionScheduler.publishFatalAndSelectWinner(bodyFailure);
        }
        try {
            IslandCommands.mainRelease(owner);
        } catch (Throwable cleanupFailure) {
            failure = RegionScheduler.publishFatalAndSelectWinner(
                    fabrolia$mergeFailure(failure, cleanupFailure));
        }
        if (failure == null) {
            try {
                // Claim release is mandatory cleanup, not a success edge.
                // Linearize the normal return against a peer generation W.
                RegionScheduler.commitSuccessIfHealthy(() -> {});
            } catch (Throwable peerFailure) {
                failure = RegionScheduler.publishFatalAndSelectWinner(peerFailure);
            }
        }
        if (failure != null) {
            IslandCommands.publishAndPropagate(failure);
        }
    }

    private static Throwable fabrolia$mergeFailure(Throwable primary, Throwable cleanup) {
        if (primary == null) return cleanup;
        if (cleanup != null && cleanup != primary) {
            try {
                primary.addSuppressed(cleanup);
            } catch (Throwable ignored) {
                // Already fail-stop; suppression must not replace the raid primary.
            }
        }
        return primary;
    }
}
