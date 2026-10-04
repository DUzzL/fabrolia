package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.level.ChunkPos;

/**
 * Raid-area island consolidation (2026-08-13 stacked-raid-farm feature,
 * operator directive: raids merge their neighbourhood the way chunk loaders
 * do).
 *
 * <p>An island-owned raid is ticked on its island (RaidsTickMixin), but its
 * side effects reach beyond the center's cell: the wave spawn ring searches
 * up to ~96 blocks out, raiders path inward through several regions. If an
 * unpopulated gap cuts the raid's area into two islands, those sides tick on
 * different timelines and the spawn/AI boundary crosses domains. Folia's
 * answer (and Fabrolia's existing one for chunk loaders) is structural:
 * population drives topology — a live chunk cluster with no ≥2-cell gap IS
 * one island by construction.
 *
 * <p>So while a raid is active we re-arm vanilla's PORTAL ticket (the same
 * type vanilla uses for entity portal arrivals — transient, never persisted,
 * lapses 300 ticks after the last re-arm, crash-safe by construction) across
 * the center + spawn ring. The tickets make every involved cell populated,
 * the next rebuild closes the gap and the raid's whole area collapses into
 * ONE island with one clock — no merge API, no forced topology writes, just
 * the documented population channel. When the raid ends the tickets lapse
 * and the natural split rules re-apply.
 *
 * <p>Documented deviation: an active raid now keeps its area loaded even if
 * every player leaves (vanilla parks the raid via {@code hasChunkAt(center)}
 * = false). For raid farms (a player is always present) behaviour is
 * identical; the lapse keeps a forgotten raid from pinning chunks forever.
 */
public final class RaidIslandMerge {

    private RaidIslandMerge() {}

    /** Spawn ring ≈96 blocks → 6 chunks of radius covers it with margin. */
    private static final int TICKET_RADIUS = 6;

    /** Re-arm the raid-area ticket. Called from the raid dispatch on main
     *  (ticket structures are main-confined — never call off-main). Cheap:
     *  one map update per existing ticket. A raid that is inactive or whose
     *  center chunk is not loaded (e.g. saved active, loaded with its area
     *  unloaded) gets no ticket: as in vanilla it stays parked until its center
     *  loads by itself, instead of being revived by an island's halo. While an
     *  active raid keeps re-arming, its ticket keeps the center loaded. */
    public static void onRaidDispatched(ServerLevel level, Raid raid) {
        RaidOwnerAccess.View view = ((RaidOwnerAccess) raid).fabrolia$raidView();
        if (view.stopped() || !view.active() || !level.hasChunkAt(view.center())) {
            return;
        }
        level.getChunkSource().addTicketWithRadius(TicketType.PORTAL,
                ChunkPos.containing(view.center()), TICKET_RADIUS);
    }
}
