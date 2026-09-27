package com.axalotl.fabrolia.common.regions;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fabrolia TPS/MSPT HUD (2026-07-14). Subscribe via
 * {@code /fabrolia regions log tps}; an action-bar line updates once per
 * second with two metric groups:
 *
 * <pre>Main: 20.0 TPS / 4.8 MSPT | Island Alice: 19.7 TPS / 2.1 MSPT</pre>
 *
 * Metric definitions: island MSPT = successful-unit EWMA from body start
 * to completion preparation (before arrival sealing/successor preparation).
 * Deadline/queue wait and unsuccessful attempts are excluded; in-body
 * barrier waiting and coordinator help work are included.
 * Island TPS = completed logical units per real second (cached reader-side window
 * over the unit counter — never derived from main's tick counter, never
 * clamped, correct during sprint and rates above 20). Main TPS = Δoverworld
 * gameTime per real second; Main MSPT = the server's own average tick time
 * ({@code MinecraftServer#getAverageTickTimeNanos}).
 * Ownerless positions show GLOBAL explicitly — never a stale island line.
 *
 * <p>Carpet note: carpet is not a compile dependency of Fabrolia, so
 * carpet's {@code /log tps} tab entry is not replaced; it keeps showing the
 * main loop only. This HUD provides the required per-island data with the
 * same subscribe-once UX regardless of carpet's presence.
 */
public final class TpsHud {

    private static final Set<UUID> SUBSCRIBERS = ConcurrentHashMap.newKeySet();
    private static final TpsWindow MAIN_TPS_WINDOW = new TpsWindow(TpsWindow.ONE_SECOND_NANOS, 20.0);
    private static long mainEmaNanos;
    private static double mainTps = 20.0;

    private TpsHud() {}

    public static long mainMsptNanos() {
        return mainEmaNanos;
    }

    public static double mainTpsValue() {
        return mainTps;
    }

    public static boolean toggle(ServerPlayer player) {
        UUID id = player.getUUID();
        if (SUBSCRIBERS.remove(id)) {
            return false;
        }
        SUBSCRIBERS.add(id);
        return true;
    }

    /** Main thread, once per overworld tick (called from the drive head). */
    public static void tick(MinecraftServer server, ServerLevel overworld) {
        if (!net.minecraft.world.level.Level.OVERWORLD.equals(overworld.dimension())) {
            return; // one sample stream: the overworld drive only
        }
        long now = System.nanoTime();
        mainEmaNanos = server.getAverageTickTimeNanos();
        long gt = overworld.getGameTime();
        boolean mainSampleUpdated = MAIN_TPS_WINDOW.update(gt, now);
        mainTps = MAIN_TPS_WINDOW.value();
        if (!mainSampleUpdated) {
            return;
        }
        if (SUBSCRIBERS.isEmpty()) {
            return;
        }
        // Roster once per update, not once per subscriber: islandOwnerOfChunk per
        // player per subscriber is O(subscribers x players) and this room runs
        // 50 carpet bots.
        java.util.IdentityHashMap<IslandRegistry.PIsland, java.util.List<ServerPlayer>> roster =
                new java.util.IdentityHashMap<>();
        for (ServerPlayer pl : server.getPlayerList().getPlayers()) {
            if (!(pl.level() instanceof ServerLevel plLevel)) {
                continue;
            }
            RegionizedServer plRs = RegionScheduler.forLevel(plLevel);
            if (plRs == null || !plRs.asyncActive) {
                continue;
            }
            IslandRegistry.PIsland owner = plRs.islandOwnerOfChunk(pl.chunkPosition().pack());
            if (owner != null) {
                roster.computeIfAbsent(owner, k -> new java.util.ArrayList<>(4)).add(pl);
            }
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!SUBSCRIBERS.contains(p.getUUID())) {
                continue;
            }
            p.connection.send(new ClientboundSetActionBarTextPacket(buildLine(p, roster)));
        }
    }

    /**
     * Dimension colour for a player name. Resolved from the player's CURRENT
     * level on every HUD update, so walking through a portal recolours the name
     * on the next line without any cached state to invalidate.
     */
    private static net.minecraft.ChatFormatting dimColor(ServerPlayer pl) {
        return pl.level() instanceof ServerLevel sl
                ? dimColor(sl.dimension())
                : net.minecraft.ChatFormatting.WHITE;
    }

    /**
     * Overworld green, Nether red, End light purple, anything modded white.
     *
     * <p>Package-private and keyed on the dimension rather than the player so the
     * mapping is testable without standing up a level.
     */
    static net.minecraft.ChatFormatting dimColor(
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim) {
        if (net.minecraft.world.level.Level.OVERWORLD.equals(dim)) {
            return net.minecraft.ChatFormatting.GREEN;
        }
        if (net.minecraft.world.level.Level.NETHER.equals(dim)) {
            return net.minecraft.ChatFormatting.RED;
        }
        if (net.minecraft.world.level.Level.END.equals(dim)) {
            return net.minecraft.ChatFormatting.LIGHT_PURPLE;
        }
        return net.minecraft.ChatFormatting.WHITE;
    }

    /** " [Alice Bob +2]" with each name coloured by its player's dimension. */
    private static void appendRoster(net.minecraft.network.chat.MutableComponent out,
                                     java.util.List<ServerPlayer> players) {
        if (players == null || players.isEmpty()) {
            return;
        }
        out.append(Component.literal(" ["));
        int shown = Math.min(players.size(), 3);
        for (int i = 0; i < shown; i++) {
            ServerPlayer pl = players.get(i);
            if (i > 0) {
                out.append(Component.literal(" "));
            }
            out.append(Component.literal(pl.getName().getString())
                    .withStyle(dimColor(pl)));
        }
        if (players.size() > shown) {
            out.append(Component.literal(" +" + (players.size() - shown)));
        }
        out.append(Component.literal("]"));
    }

    private static Component buildLine(ServerPlayer p,
            java.util.IdentityHashMap<IslandRegistry.PIsland, java.util.List<ServerPlayer>> roster) {
        net.minecraft.network.chat.MutableComponent out = Component.literal(String.format(
                java.util.Locale.ROOT, "Main: %.1f TPS / %.1f MSPT", mainTps, mainEmaNanos / 1_000_000.0));
        if (!(p.level() instanceof ServerLevel sl)) {
            return out;
        }
        RegionizedServer rs = RegionScheduler.forLevel(sl);
        if (rs == null || !rs.asyncActive) {
            out.append(Component.literal(" | Island: SYNC"));
            return out;
        }
        IslandRegistry.PIsland isl = rs.islandOwnerOfChunk(p.chunkPosition().pack());
        if (isl == null) {
            out.append(Component.literal(" | Island: GLOBAL"));
            return out;
        }
        out.append(Component.literal(String.format(java.util.Locale.ROOT,
                " | Island %s: %.1f TPS / %.2f MSPT",
                IslandMetrics.label(rs, isl),
                IslandMetrics.actualTps(isl),
                isl.emaUnitNanos / 1_000_000.0)));
        appendRoster(out, roster.get(isl));

        // Cross-dimension coupling (task #43). Standing at a portal farm the one
        // thing worth knowing is whether this half is actually locked to the
        // other one, and who is over there — reading that off
        // `/fabrolia regions crossdim` means leaving the spot. Both fields are
        // the volatiles IslandTickUnit consumes; a stale read is a display
        // artefact only.
        CrossDimLinks.Coupling c = isl.crossDimCoupling;
        if (c == null) {
            return out;
        }
        out.append(Component.literal(String.format(java.util.Locale.ROOT,
                " | XDIM: linked x%d @%.1f TPS",
                c.members.size(),
                c.sharedTargetNanos > 0 ? 1_000_000_000.0 / c.sharedTargetNanos : 20.0)));
        // The partners' rosters — the whole point of the request: standing in the
        // Overworld half you see the Nether half's occupants (in red) and vice
        // versa. Iterating the coupling's member list is a snapshot read of a
        // main-written ArrayList; this runs on main, so no copy is needed.
        for (int i = 0; i < c.members.size(); i++) {
            IslandRegistry.PIsland m = c.members.get(i);
            if (m == isl) {
                continue;
            }
            appendRoster(out, roster.get(m));
        }
        return out;
    }
}
