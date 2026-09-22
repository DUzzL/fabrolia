package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.CrossDimArrivals;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionWorkerThread;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import com.axalotl.fabrolia.common.utils.DragonEntityIds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
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

    @org.spongepowered.asm.mixin.Unique
    private static final java.util.concurrent.atomic.AtomicLong fabrolia$deadArrivalLogNanos =
            new java.util.concurrent.atomic.AtomicLong();

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
        CrossDimArrivals.stage(self, chunkKey, () -> {
            // POPULATION SUSTAIN (task #26): the in-flight lease pins the
            // destination only while the op is QUEUED — from publication until
            // the arrival's own activity refreshes tickets, nothing held the
            // chunk, so the destination could demote/unload (and its island
            // retire, populated-ness being FULL-chunk based) under a freshly
            // published entity. Place the same PORTAL ticket vanilla's arrival
            // path uses, AT publication, for every staged entity arrival —
            // ticket structures are main-confined, so defer off-main (the
            // PortalTicketMixin discipline).
            Runnable sustain = () -> self.getChunkSource().addTicketWithRadius(
                    net.minecraft.server.level.TicketType.PORTAL,
                    new net.minecraft.world.level.ChunkPos(
                            net.minecraft.world.level.ChunkPos.getX(chunkKey),
                            net.minecraft.world.level.ChunkPos.getZ(chunkKey)), 3);
            if (RegionalPlayerEntityTicksV2
                    .isPhysicalServerThread(self.getServer())) {
                sustain.run();
            } else {
                RegionScheduler.enqueueGlobalMainOnly(sustain);
            }
            // The arrival copy can be KILLED between staging and publication.
            // Vanilla inserts the copy (Entity.teleportCrossDimension:3090) and only
            // THEN runs the type's post-insertion continuation — ItemEntity.teleport
            // calls mergeWithNeighbours() at ItemEntity.java:365, and merge() ends in
            // fromItem.discard(). Because staging cancels the insert and defers it,
            // that continuation runs here on an entity that belongs to no level, so a
            // merge can discard the arrival before it is ever inserted. Publishing it
            // anyway is what produces vanilla's
            //   "Tried to add entity minecraft:item but it was marked as removed already"
            // (ServerLevel.java:994) from a region worker. Drop the op instead of
            // handing a dead entity to addEntity, and say so once per 5 min with the
            // removal reason so a recurrence is classifiable.
            if (entity.isRemoved()) {
                long now = System.nanoTime();
                long last = fabrolia$deadArrivalLogNanos.get();
                if (now - last > 300_000_000_000L
                        && fabrolia$deadArrivalLogNanos.compareAndSet(last, now)) {
                    org.slf4j.LoggerFactory.getLogger("Fabrolia-XDim").warn(
                            "Dropped a cross-dimension arrival that was removed ({}) between "
                                    + "staging and publication: {} into {}. The entity died in the "
                                    + "window where it belongs to no level; it is NOT inserted. "
                                    + "Throttled to one line per 5 min.",
                            entity.getRemovalReason(), entity.getType().toString(),
                            self.dimension().identifier());
                }
                return;
            }
            if (entity instanceof EnderDragon dragon) {
                DragonEntityIds.assignUnpublished(self, dragon);
            } else {
                entity.setId(self.getNextEntityId());
            }
            self.addDuringTeleport(entity);
        }, "entity", entity);
        ci.cancel();
    }
}
