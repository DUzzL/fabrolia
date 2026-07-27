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
 * Metric definitions: island MSPT = EWMA wall cost of one COMPLETE island
 * unit (scheduled→events→entities→replay→BE→owned maintenance), park time
 * excluded by construction (measured start→completion inside the unit).
 * Island TPS = completed logical units per real second (reader-side window
 * over the unit counter — never derived from main's tick counter, never
 * clamped, correct during sprint and rates above 20). Main TPS = Δoverworld
 * gameTime per real second; main MSPT = EWMA of measured main tick spans.
 * Ownerless positions show GLOBAL explicitly — never a stale island line.
 *
 * <p>Carpet note: carpet is not a compile dependency of Fabrolia, so
 * carpet's {@code /log tps} tab entry is not replaced; it keeps showing the
 * main loop only. This HUD provides the required per-island data with the
 * same subscribe-once UX regardless of carpet's presence.
 */
public final class TpsHud {

    private static final Set<UUID> SUBSCRIBERS = ConcurrentHashMap.newKeySet();
    private static long prevGameTime;
    private static long prevNanos;
    private static long lastTickNanos;
    private static long mainEmaNanos;
    private static double mainTps = 20.0;

    private TpsHud() {}

    /** Main MSPT (nanos) as shown in the HUD — vanilla's measured tick span. */
    public static long mainMsptNanos() {
        return mainEmaNanos;
    }

    /** Main TPS as shown in the HUD (ΔgameTime per real second, unclamped). */
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
        // MAIN MSPT FIX (2026-07-14 user report: HUD showed ~49 MSPT while
        // carpet showed 11-12): the old EWMA measured TICK-TO-TICK SPACING —
        // the tick PERIOD (≈50ms at 20 TPS including deadline sleep), not the
        // tick body. Main MSPT now reads vanilla's own measurement
        // (MinecraftServer.getAverageTickTimeNanos — the recorded execution
        // span of tickServer, the same source carpet and the profiler use):
        // active work only, sleep excluded, correct at any target rate.
        // Tick PERIOD/TPS stays independently measured from ΔgameTime.
        mainEmaNanos = server.getAverageTickTimeNanos();
        lastTickNanos = now;
        long gt = overworld.getGameTime();
        if (gt % 20 != 0) {
            prevSample(gt, now);
            return;
        }
        // TPS updates unconditionally (command surfaces read it too, not just
        // HUD subscribers) — only the action-bar send below is subscriber-gated.
        if (prevNanos != 0 && now > prevNanos) {
            mainTps = (gt - prevGameTime) * 1_000_000_000.0 / (now - prevNanos);
        }
        prevSample(gt, now);
        if (SUBSCRIBERS.isEmpty()) {
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!SUBSCRIBERS.contains(p.getUUID())) {
                continue;
            }
            p.connection.send(new ClientboundSetActionBarTextPacket(
                    Component.literal(buildLine(p))));
        }
    }

    private static void prevSample(long gt, long now) {
        if (prevNanos == 0 || now - prevNanos > 900_000_000L) {
            prevGameTime = gt;
            prevNanos = now;
        }
    }

    private static String buildLine(ServerPlayer p) {
        StringBuilder sb = new StringBuilder(96);
        sb.append(String.format(java.util.Locale.ROOT, "Main: %.1f TPS / %.1f MSPT",
                mainTps, mainEmaNanos / 1_000_000.0));
        if (p.level() instanceof ServerLevel sl) {
            RegionizedServer rs = RegionScheduler.forLevel(sl);
            if (rs != null && rs.asyncActive) {
                IslandRegistry.PIsland isl =
                        rs.islandOwnerOfChunk(p.chunkPosition().pack());
                if (isl == null) {
                    sb.append(" | Island: GLOBAL");
                } else {
                    sb.append(String.format(java.util.Locale.ROOT,
                            " | Island %s: %.1f TPS / %.2f MSPT",
                            IslandMetrics.label(rs, isl),
                            IslandMetrics.actualTps(isl),
                            isl.emaUnitNanos / 1_000_000.0));
                }
            } else {
                sb.append(" | Island: SYNC");
            }
        }
        return sb.toString();
    }
}
