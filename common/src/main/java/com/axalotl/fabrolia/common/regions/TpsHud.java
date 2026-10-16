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
 * {@code /fabrolia regions tps}; an action-bar line updates once per
 * second with two metric groups:
 *
 * <pre>Main 20.0 TPS 4.8 ms | Island [Alice Bob] 19.7 TPS 2.1 ms</pre>
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
 * Ownerless positions show "Here: main thread" explicitly — never a stale island line.
 *
 * <p>With Carpet installed, its {@code /log tps} tab footer also shows the
 * player's island ({@link CarpetTpsLines}). This action-bar HUD works without
 * Carpet and adds the island label, roster and cross-dimension coupling.
 */
public final class TpsHud {

    private static final Set<UUID> SUBSCRIBERS = ConcurrentHashMap.newKeySet();
    private static final TpsWindow MAIN_TPS_WINDOW = new TpsWindow(TpsWindow.ONE_SECOND_NANOS, 20.0, 5);
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
        IslandMetrics.sampleAll();
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
    public static net.minecraft.ChatFormatting dimColor(
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
        var rate = p.level().getServer().tickRateManager();
        net.minecraft.network.chat.MutableComponent out = grey("Main ")
                .append(tpsValue(mainTps, rate.tickrate()))
                .append(grey(" "))
                .append(msValue(mainEmaNanos / 1_000_000.0, rate.millisecondsPerTick()));
        if (!(p.level() instanceof ServerLevel sl)) {
            return out;
        }
        RegionizedServer rs = RegionScheduler.forLevel(sl);
        if (rs == null || !rs.asyncActive) {
            out.append(grey(" | Islands off"));
            return out;
        }
        IslandRegistry.PIsland isl = rs.islandOwnerOfChunk(p.chunkPosition().pack());
        if (isl == null) {
            out.append(grey(" | Here: main thread"));
            return out;
        }
        // The roster always holds the viewer, so it names the island; a manual
        // alias goes in front of it.
        out.append(grey(" | Island"));
        if (isl.alias != null) {
            out.append(Component.literal(" " + isl.alias).withStyle(net.minecraft.ChatFormatting.WHITE));
        }
        appendRoster(out, roster.get(isl));
        double target = IslandMetrics.targetTps(rs, isl);
        out.append(grey(" "))
                .append(tpsValue(IslandMetrics.actualTps(isl), target))
                .append(grey(" "))
                .append(msValue(isl.emaUnitNanos / 1_000_000.0, 1000.0 / target));

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
        out.append(grey(String.format(java.util.Locale.ROOT,
                " | Linked x%d @%.1f TPS",
                c.members.size() - 1,
                c.sharedTargetNanos > 0 ? 1_000_000_000.0 / c.sharedTargetNanos : 20.0)));
        // The partners' rosters — the whole point of the request: standing in the
        // Overworld half you see the Nether half's occupants (in red) and vice
        // versa. Iterating the coupling's member list is a snapshot read of a
        // main-written ArrayList; this runs on main, so no copy is needed.
        // A coupling is a connected group and can hold many islands (every
        // Overworld island above one populated Nether cell): at most two
        // partner rosters fit the action bar.
        int shown = 0;
        int more = 0;
        for (int i = 0; i < c.members.size(); i++) {
            IslandRegistry.PIsland m = c.members.get(i);
            java.util.List<ServerPlayer> players = roster.get(m);
            if (m == isl || players == null || players.isEmpty()) {
                continue;
            }
            if (shown < 2) {
                appendRoster(out, players);
                shown++;
            } else {
                more++;
            }
        }
        if (more > 0) {
            out.append(grey(" +" + more));
        }
        return out;
    }

    private static net.minecraft.network.chat.MutableComponent grey(String text) {
        return Component.literal(text).withStyle(net.minecraft.ChatFormatting.GRAY);
    }

    /** "19.7 TPS" in Carpet's heat colours; "-- TPS" before the first window. */
    private static net.minecraft.network.chat.MutableComponent tpsValue(double tps, double target) {
        return Component.literal((Double.isNaN(tps) ? "--"
                        : String.format(java.util.Locale.ROOT, "%.1f", tps)) + " TPS")
                .withStyle(CarpetTpsLines.tpsColor(tps, target));
    }

    private static net.minecraft.network.chat.MutableComponent msValue(double ms, double budgetMs) {
        return Component.literal(String.format(java.util.Locale.ROOT, "%.1f ms", ms))
                .withStyle(CarpetTpsLines.msptColor(ms, budgetMs));
    }
}
