package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Route worker-thread ticket writes to main (2026-08-15 ender-pearl chunk
 * loading incident).
 *
 * <p><b>Incident.</b> A thrown ender pearl ticks on whatever island worker
 * currently owns its chunk; once per {@code ENDER_PEARL} timeout (and on
 * every section crossing) its tick calls
 * {@code ServerChunkCache.addTicketWithRadius} — a raw write into
 * {@code TicketStorage}'s plain {@code Long2ObjectOpenHashMap}s. Main
 * iterates and rewrites those maps every tick (purgeStaleTickets /
 * consolidation), so a worker-side add racing a rehash is silently LOST —
 * on a busy server the pearl's renewals mostly never land, the ticket
 * expires, the pearl freezes mid-flight in an unloading chunk, and "the
 * pearl loads nothing" (operator report: game server; the quiet test stand
 * wins the race often enough to look healthy). This is the same hazard
 * class {@code PortalTicketMixin} already fixed for
 * {@code Entity.placePortalTicket}; the pearl path was simply never covered.
 *
 * <p><b>Fix.</b> Arbitrary workers re-invoke the ticket add on the dedicated
 * Main-only queue (same pattern as {@code PortalTicketMixin}: re-invocation,
 * not a captured {@code Operation}, so the wrap re-enters and runs inline on
 * Main). Region workers cannot poll that lane; allowing them to poll it would
 * make the re-entered wrapper publish itself forever.
 *
 * <p>The existing ticket-storage monitor and downstream compatibility owner
 * checks remain in force.
 *
 * <p>Debug logging of each deferral was removed (2026-08-18, operator
 * request — per-renewal lines flood a live server; the incident forensics now
 * live in {@code PearlTrace}'s anomaly-only expiry/freeze lines).
 */
@Mixin(ServerChunkCache.class)
public abstract class WorkerTicketRouteMixin {

    @Shadow @Final private ServerLevel level;

    @WrapMethod(method = "addTicketWithRadius")
    private void fabrolia$routeWorkerRadiusTicketToMain(TicketType type, ChunkPos pos,
                                                        int radius, Operation<Void> original) {
        if (RegionalPlayerEntityTicksV2.isPhysicalServerThread(
                this.level.getServer())) {
            original.call(type, pos, radius);
            return;
        }
        ServerChunkCache self = (ServerChunkCache) (Object) this;
        RegionScheduler.enqueueGlobalMainOnly(
                () -> self.addTicketWithRadius(type, pos, radius));
    }

    @WrapMethod(method = "addTicket")
    private void fabrolia$routeWorkerTicketToMain(Ticket ticket, ChunkPos pos,
                                                  Operation<Void> original) {
        if (RegionalPlayerEntityTicksV2.isPhysicalServerThread(
                this.level.getServer())) {
            original.call(ticket, pos);
            return;
        }
        ServerChunkCache self = (ServerChunkCache) (Object) this;
        RegionScheduler.enqueueGlobalMainOnly(() -> self.addTicket(ticket, pos));
    }
}
