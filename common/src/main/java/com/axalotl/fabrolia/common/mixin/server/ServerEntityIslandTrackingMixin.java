package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.IslandEntityTracking;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Routes the state-publication half of entity tracking onto island L. */
@Mixin(ServerEntity.class)
public abstract class ServerEntityIslandTrackingMixin {

    @Shadow @Final private Entity entity;
    @Shadow @Final private ServerEntity.Synchronizer synchronizer;

    @WrapMethod(method = "sendChanges")
    private void fabrolia$routeAutonomousEntityPublication(Operation<Void> original) {
        // The synchronizer is the exact ChunkMap.TrackedEntity instance. Main
        // publication, island publication and pairing teardown all share this
        // monitor, so a venue transition cannot overlap two ServerEntity state
        // machines (tickCount/codec/lastSent/dirty clears).
        synchronized (this.synchronizer) {
            switch (IslandEntityTracking.route(this.synchronizer, this.entity)) {
                case MAIN -> {
                    if (!IslandEntityTracking.beginMainPublication(
                            this.synchronizer, this.entity)) {
                        IslandEntityTracking.deferDuplicateMainPublication(
                                this.synchronizer);
                        break;
                    }
                    original.call();
                    if (this.synchronizer instanceof
                            com.axalotl.fabrolia.common.regions.IslandTrackedEntityAccess tracker) {
                        tracker.fabrolia$markIslandTrackingPublished();
                    }
                    IslandEntityTracking.commitPendingPairingsAfterMain(
                            this.synchronizer);
                    IslandEntityTracking.finishMainPublication(
                            this.synchronizer);
                }
                case ISLAND, DROP -> {
                    // ISLAND is published at the island tail; DROP is a stale
                    // tracker or an active tracker without any recipient.
                }
            }
        }
    }
}
