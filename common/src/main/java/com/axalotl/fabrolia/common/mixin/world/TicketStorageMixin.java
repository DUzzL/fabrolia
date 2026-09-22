package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.mixin.server.TicketAccessor;
import com.axalotl.fabrolia.common.regions.IslandRegistry;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
import com.axalotl.fabrolia.common.utils.FabroliaDebug;
import com.axalotl.fabrolia.common.utils.PearlTrace;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.TicketStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chunk-loader stall grace (2026-07-18 production hardening). Vanilla
 * decrements ticket timeouts once per MAIN tick (purgeStaleTickets), while
 * the machine that RENEWS a loader ticket (an entity cycling a portal, a
 * hopper line refreshing) runs at its owning ISLAND's cadence. A temporary
 * island stall therefore froze renewal but not expiry: main burned the
 * timeout down and a perfectly healthy loader unloaded mid-stall. (A main
 * stall is inherently safe in vanilla and here — no ticks, no decrements.)
 *
 * <p>Repair: while a chunk's owner island is BEHIND the wall clock, its
 * tickets do not decrement — timeout and renewal then count the same clock
 * (island ticks), the exact rule every other relative mechanic follows under
 * the L-clock model. A healthy island with a genuinely dead loader expires
 * normally (L keeps pace, decrements run). Leak bound: an island more than
 * {@link #STALL_GRACE_CAP_TICKS} behind is not a lag spike — decrements
 * resume so a permanently wedged island cannot pin tickets forever.
 *
 * <p>Also feeds the pearl-ticket EXPIRY trace (2026-08-18): this inject sees
 * every ticket vanilla is about to decrement, with its chunk pos — the one
 * place an {@code ENDER_PEARL} ticket's death is observable without lambda
 * targeting. {@code ticksLeft} reaches 0 pre-decrement on the removing pass
 * ({@code isTimedOut} fires at {@code < 0} after the decrement). Actual
 * logging lives in {@code PearlTrace.expired} — anomaly-only (silent unless
 * a LIVE pearl is stranded in the chunk) and rate-limited, always on.
 */
@Mixin(TicketStorage.class)
public abstract class TicketStorageMixin {

    @org.spongepowered.asm.mixin.Unique
    private static final long STALL_GRACE_CAP_TICKS = 6000;

    /** Per-chunk count of consecutive purge passes an ENDER_PEARL ticket has
     *  been held at zero (leak bound bookkeeping for {@code PearlTicketHold}).
     *  Purge runs on main only — a plain map is enough. Entries are written
     *  only while a hold is active and removed on release/renewal/expiry. */
    @org.spongepowered.asm.mixin.Unique
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap fabrolia$pearlHoldCounts =
            new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();

    /** One-shot probe attached to the first hold of a session (2026-08-19
     *  prod cycle: repeated holds on one chunk, tracker never dropped the
     *  level — freeze trace silent). Discriminates the two remaining
     *  explanations in one line: holder-side promotion (c2me async —
     *  {@code simLevel=31} but {@code holderTicking=false}) vs a starved
     *  owner island ({@code island=#id behind=BIG} — renewal cadence too
     *  slow for the burn). All reads are best-effort: probe failure must
     *  never break the hold. */
    @org.spongepowered.asm.mixin.Unique
    private String fabrolia$pearlProbe(ChunkMap chunkMap,
                                       net.minecraft.server.level.ServerLevel lvl, long chunkPos) {
        try {
            StringBuilder sb = new StringBuilder();
            try {
                var dm = ((com.axalotl.fabrolia.common.mixin.server.DistanceManagerAccessor)
                        chunkMap.getDistanceManager()).fabrolia$simulationChunkTracker();
                int simLevel = dm instanceof com.axalotl.fabrolia.common.regions.CompletedSimulationView cv
                        ? cv.fabrolia$completedLevel(chunkPos) : -999;
                sb.append("simLevel=").append(simLevel & 0xFF);
            } catch (Throwable t) {
                sb.append("simLevel=?");
            }
            try {
                var holder = lvl.getChunkSource().getVisibleChunkIfPresent(chunkPos);
                sb.append(" holder=").append(holder == null ? "null"
                        : (holder.getTickingChunk() != null ? "ticking" : "nonTicking"));
            } catch (Throwable t) {
                sb.append(" holder=?");
            }
            try {
                RegionizedServer rs = RegionScheduler.forLevel(lvl);
                if (rs == null) {
                    sb.append(" island=none(regions-off?)");
                } else {
                    IslandRegistry.PIsland owner = rs.islandOwnerOfChunk(chunkPos);
                    if (owner == null) {
                        sb.append(" island=none");
                    } else {
                        long wall = com.axalotl.fabrolia.common.regions.IslandClock.wallTime(lvl);
                        sb.append(" island=#").append(owner.id)
                                .append(" behind=").append(wall - owner.logicalTick);
                    }
                }
            } catch (Throwable t) {
                sb.append(" island=?");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "probe-failed";
        }
    }

    @Inject(method = "canTicketExpire", at = @At("RETURN"), cancellable = true)
    private void fabrolia$stallGrace(ChunkMap chunkMap, Ticket ticket, long chunkPos,
                                     CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return;
        }
        boolean pearl = ticket.getType() == TicketType.ENDER_PEARL;
        long ticksLeft = pearl ? ((TicketAccessor) ticket).fabrolia$getTicksLeft() : 0;
        boolean suppress = false;
        com.axalotl.fabrolia.common.regions.LevelAccess la =
                chunkMap instanceof com.axalotl.fabrolia.common.regions.LevelAccess l ? l : null;
        // ENDER_PEARL ticket hold (2026-08-19, production "pearls stop loading
        // chunks when main lags"). Vanilla's ticket survives
        // on a TWO-purge margin (renewal every 39 pearl ticks vs expiry after
        // 41 purges) only because purge and the pearl's inline renewal share
        // one thread with a fixed within-tick order. With the pearl ticking on
        // a region worker its renewal is deferred to main and can land after
        // the next purge — the margin collapses to one pass, and any jitter
        // flaps the ticket: the chunk drops out of entity-ticking, the pearl
        // freezes (no ticks → no renewals) and the ticket dies for good.
        // Instead of widening the margin (the 2026-08-18 half-burn hack —
        // rarer flaps, still fatal), the hold removes the death spiral: a
        // burned-down ticket whose chunk still hosts a LIVE pearl is not
        // decremented further — the frozen pearl keeps the chunk, the chunk
        // re-promotes, the pearl resumes and renews. Budget-bounded
        // (PearlTicketHold.HOLD_BUDGET) so a pearl wedged by some other bug
        // eventually releases the loader column and dies to disk, vanilla's
        // own recovery path. Policy is unit-tested in PearlTicketHoldTest.
        if (pearl && la != null && ticksLeft <= 0 && RegionScheduler.isActive()) {
            if (PearlTrace.hasLivePearlInChunk(la.fabrolia$level(), chunkPos)) {
                int held = fabrolia$pearlHoldCounts.get(chunkPos);
                if (com.axalotl.fabrolia.common.utils.PearlTicketHold.shouldHold(
                        true, ticksLeft, true, held)) {
                    fabrolia$pearlHoldCounts.put(chunkPos, held + 1);
                    cir.setReturnValue(false);
                    if (held == 0) {
                        PearlTrace.holdEngaged(la.fabrolia$level(), chunkPos,
                                fabrolia$pearlProbe(chunkMap, la.fabrolia$level(), chunkPos));
                    }
                    return;
                }
                fabrolia$pearlHoldCounts.remove(chunkPos);
                PearlTrace.holdReleased(la.fabrolia$level(), chunkPos, held);
                // fall through — vanilla expiry takes the ticket now
            }
        } else if (pearl && ticksLeft > 0) {
            int held = fabrolia$pearlHoldCounts.remove(chunkPos); // renewed — fresh countdown
            if (held > 0 && la != null) {
                PearlTrace.holdHealed(la.fabrolia$level(), chunkPos, held);
            }
        }
        // Single-exit decision block: every break means "vanilla expiry proceeds".
        do {
            if (la == null) {
                break;
            }
            RegionizedServer rs = RegionScheduler.forLevel(la.fabrolia$level());
            if (rs == null || !rs.asyncActive) {
                break;
            }
            IslandRegistry.PIsland owner = rs.islandOwnerOfChunk(chunkPos);
            if (owner == null) {
                break;
            }
            long wall = com.axalotl.fabrolia.common.regions.IslandClock.wallTime(la.fabrolia$level());
            long behind = wall - owner.logicalTick;
            if (behind <= 0 || behind >= STALL_GRACE_CAP_TICKS) {
                break; // caught up (vanilla expiry) / wedged beyond the leak bound
            }
            // Expiry counts ISLAND ticks, not wall ticks: decrement only on
            // passes where the owner's L advanced since the last purge. A stall
            // (L frozen) freezes expiry exactly as long as it freezes renewal; a
            // deliberately RATED island expires proportionally slower — matching
            // its renewal cadence — instead of freezing forever (v1 froze any
            // behind island, which leaked tickets on permanently-rated islands).
            if (owner.purgeSeenWall != wall) {
                owner.purgeAdvanced = owner.logicalTick != owner.purgeSeenL;
                owner.purgeSeenL = owner.logicalTick;
                owner.purgeSeenWall = wall;
            }
            suppress = !owner.purgeAdvanced;
        } while (false);
        if (suppress) {
            cir.setReturnValue(false);
        } else if (pearl && ticksLeft <= 0 && la != null) {
            fabrolia$pearlHoldCounts.remove(chunkPos);
            if (FabroliaDebug.PEARL_TRACE) {
                // Reaching expiry WITH a live pearl now means the hold budget
                // was exhausted (or regions toggled mid-hold) — a genuine
                // anomaly worth its coordinates.
                PearlTrace.expired(la.fabrolia$level(), chunkPos);
            }
        }
    }
}
