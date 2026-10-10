package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.utils.DragonEntityIds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;

/** Shared destination body for a live staged arrival and its saved recovery. */
public final class StagedEntityArrival {
    private static final java.util.concurrent.atomic.AtomicLong DEAD_ARRIVAL_LOG_NANOS =
            new java.util.concurrent.atomic.AtomicLong();
    private StagedEntityArrival() {}

    public static void publish(ServerLevel self, long chunkKey, Entity entity) {
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
            long last = DEAD_ARRIVAL_LOG_NANOS.get();
            if (now - last > 300_000_000_000L
                    && DEAD_ARRIVAL_LOG_NANOS.compareAndSet(last, now)) {
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
    }
}
