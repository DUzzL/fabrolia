package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RaidOwnerAccess;
import com.axalotl.fabrolia.common.regions.RegionWorkerThread;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raid;
import com.axalotl.fabrolia.common.regions.RaidOwnership;
import net.minecraft.world.entity.raid.Raider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Map;
import java.util.Set;
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

    @Shadow private long ticksActive;
    @Shadow private int postRaidTicks;
    @Shadow @Final private Map<Integer, Set<Raider>> groupRaiderMap;
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
        var center = fabrolia$view.center();
        if (Thread.currentThread() instanceof RegionWorkerThread worker && worker.currentRegion == null
                && RegionScheduler.ownsCurrentContext(level, center) && !fabrolia$needsOtherOwners(level)) {
            RegionScheduler.runSuccessBodyIfHealthy(() -> fabrolia$tickAndPublish(level, original));
        } else {
            RaidOwnership.runFenced(level, "raid tick", () -> fabrolia$tickAndPublish(level, original));
        }
    }

    @Unique
    private boolean fabrolia$needsOtherOwners(ServerLevel level) {
        Raid raid = (Raid) (Object) this;
        if (raid.isOver() || raid.isStopped()) return false;
        // Victory iterates hero UUIDs, including living entities anywhere in the level.
        if (postRaidTicks >= 40) return level.hasChunkAt(fabrolia$view.center());
        if ((ticksActive + 1) % 20 != 0) return false;
        // updateRaiders reads the live entities and can clear their raid membership.
        // Owned members cannot move while their coordinator holds this unit.
        synchronized (this) {
            for (var wave : groupRaiderMap.values()) {
                for (Raider raider : wave) {
                    if (raider.level() != level || !RegionScheduler.ownsCurrentContext(level, raider.blockPosition())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
