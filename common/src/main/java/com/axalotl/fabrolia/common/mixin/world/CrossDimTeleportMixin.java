package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.CrossDimArrivals;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionWorkerThread;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cross-dimension arrival staging (2026-07-15 cohort atomicity): the
 * destination insertion half of a teleport is deferred to the destination
 * owner's next phase head instead of mutating destination entity structures
 * from the source thread mid-phase. Players pass through untouched (main-
 * owned, vanilla path); main-thread teleports into GLOBAL-owned destinations
 * also pass through — main is that domain's executor and level ticks are
 * sequential there, which IS the vanilla ordering.
 */
@Mixin(ServerLevel.class)
public abstract class CrossDimTeleportMixin {

    /** Vanilla's void teleport entry discards the admission result. A rejected
     * staged member is a failed publication, not an airborne entity to await. */
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
            method = "addDuringTeleport",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;addEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean fabrolia$observeArrivalAdmission(ServerLevel level, Entity entity,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<Boolean> original) {
        boolean accepted = original.call(level, entity);
        if (!accepted && CrossDimArrivals.PUBLISHING.get()) {
            throw new IllegalStateException("Destination rejected staged teleport arrival "
                    + entity.getUUID());
        }
        return accepted;
    }

    @Inject(method = "addDuringTeleport", at = @At("HEAD"), cancellable = true)
    private void fabrolia$stageArrival(Entity entity, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (com.axalotl.fabrolia.common.regions.NetherPortalTransfers.admitArrival(self, entity)) {
            return; // The complete Nether body owns both endpoints through every continuation.
        }
        long chunkKey = entity.chunkPosition().pack();
        if (CrossDimArrivals.PUBLISHING.get()) {
            return; // publish-time execution: run the real vanilla path
        }
        if (entity instanceof ServerPlayer) {
            return;
        }
        if (!RegionScheduler.isActive()) {
            if (com.axalotl.fabrolia.common.regions.EndArrivalSavedData.hasFallback(self)) {
                CrossDimArrivals.stage(self, chunkKey,
                        () -> com.axalotl.fabrolia.common.regions.StagedEntityArrival.publish(self, chunkKey, entity),
                        "entity", entity);
                ci.cancel();
            }
            return;
        }
        RegionizedServer destRs = RegionScheduler.forLevel(self);
        if (destRs == null) {
            return;
        }
        if (!(Thread.currentThread() instanceof RegionWorkerThread)) {
            if (!CrossDimArrivals.islandOwnedDestination(destRs, chunkKey)) {
                return; // main → global-owned destination: main is the executor
            }
        }
        // Re-mint the destination id AT PUBLISH, not at stage. Vanilla mints the
        // teleport copy's id (Level.getNextEntityId in the Entity constructor)
        // AFTER getPortalDestination has run its destination writes — for End
        // portals that includes createEndPlatform, whose destroyBlock item pops
        // mint ids first. Staging defers those writes but the copy was already
        // constructed, so its id lands BELOW the cohort's regen items — and the
        // region entity sets and the ordered serial lane tick in id order, which
        // flipped the vanilla item-before-FBE mutual order (2026-07-22 duper
        // trace: PUSH on the PLACE tick + doubled squeeze counts, the radial
        // velocity excess). Minting inside the FIFO restores vanilla creation
        // order: every staged op that vanilla ran before the copy existed has
        // already minted its ids by the time this closure runs.
        CrossDimArrivals.stage(self, chunkKey,
                () -> com.axalotl.fabrolia.common.regions.StagedEntityArrival.publish(self, chunkKey, entity),
                "entity", entity);
        ci.cancel();
    }
}
