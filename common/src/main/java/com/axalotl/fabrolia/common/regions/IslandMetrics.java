package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ServerPlayer;

/**
 * Reader-side island metrics (2026-07-14). TPS windows live entirely on the
 * READER (main-thread commands/HUD): actual TPS = Δ(unitsCompleted)/Δt using
 * a cached per-island window — the unit hot path only bumps a counter and two
 * EWMAs. Reads within one window are idempotent, so a command cannot consume
 * the HUD's sample (or make the next reader observe 0 TPS). Never clamped;
 * correct at any target rate and during sprint.
 */
public final class IslandMetrics {

    private IslandMetrics() {}

    /** Main thread only. NaN until the island's first complete window. */
    public static double actualTps(IslandRegistry.PIsland p) {
        p.tpsWindow.update(p.unitsCompleted, System.nanoTime());
        return p.tpsWindow.value();
    }

    /** Human label (cached ~5s; recomputed on demand, never in the unit hot
     *  path). Priority: real players → players+bots → bots → Spawn →
     *  "No players @blockX,blockZ". Manual alias renders as "Alias [auto]". The
     *  label is DISPLAY ONLY — identity stays (dimension, id, gen, handle). */
    public static String label(RegionizedServer rs, IslandRegistry.PIsland p) {
        long now = System.nanoTime();
        String cached = p.labelCache;
        if (cached != null && now - p.labelCacheNanos < 5_000_000_000L) {
            return cached;
        }
        String auto = autoLabel(rs, p);
        String out = p.alias != null ? p.alias + " [" + auto + "]" : auto;
        p.labelCache = out;
        p.labelCacheNanos = now;
        return out;
    }

    /** Online players standing on the island, real players first, then
     *  Carpet bots. Main thread only. */
    public static java.util.List<ServerPlayer> playersOn(RegionizedServer rs, IslandRegistry.PIsland p) {
        java.util.ArrayList<ServerPlayer> real = new java.util.ArrayList<>(4);
        java.util.ArrayList<ServerPlayer> bots = new java.util.ArrayList<>(4);
        var server = RegionScheduler.getServer();
        if (server != null) {
            for (ServerPlayer pl : server.getPlayerList().getPlayers()) {
                if (pl.level() != rs.level) {
                    continue;
                }
                if (rs.islandOwnerOfChunk(pl.chunkPosition().pack()) == p) {
                    (isBot(pl) ? bots : real).add(pl);
                }
            }
        }
        real.addAll(bots);
        return real;
    }

    public static boolean isBot(ServerPlayer pl) {
        return pl.getClass().getName().contains("Fake");
    }

    private static String autoLabel(RegionizedServer rs, IslandRegistry.PIsland p) {
        java.util.ArrayList<String> real = new java.util.ArrayList<>(4);
        java.util.ArrayList<String> bots = new java.util.ArrayList<>(4);
        for (ServerPlayer pl : playersOn(rs, p)) {
            (isBot(pl) ? bots : real).add(pl.getName().getString());
        }
        java.util.ArrayList<String> parts = new java.util.ArrayList<>(4);
        parts.addAll(real);
        parts.addAll(bots);
        if (!parts.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            int shown = Math.min(parts.size(), real.isEmpty() ? 2 : Math.max(2, real.size()));
            shown = Math.min(shown, 3);
            for (int i = 0; i < shown; i++) {
                if (i > 0) sb.append('+');
                sb.append(parts.get(i));
            }
            if (parts.size() > shown) {
                sb.append('+').append(parts.size() - shown).append(" more");
            }
            return sb.toString();
        }
        // Spawn detection: the level's spawn chunk inside this island.
        try {
            var spawn = rs.level.getRespawnData().pos();
            var owner = rs.islandOwnerOfChunk(net.minecraft.world.level.ChunkPos.pack(spawn));
            if (owner == p) {
                return "Spawn";
            }
        } catch (Throwable ignored) {
        }
        var it = p.cells.iterator();
        if (it.hasNext()) {
            long cell = it.nextLong();
            RegionGrid grid = RegionScheduler.grid();
            int shift = grid == null ? 3 : grid.shift;
            int bx = (int) cell << (shift + 4);
            int bz = (int) (cell >>> 32) << (shift + 4);
            return "No players @" + bx + "," + bz;
        }
        return "Empty#" + p.id;
    }

    /** Stable session handle, e.g. "ow-7g42". */
    public static String handle(RegionizedServer rs, IslandRegistry.PIsland p) {
        String dim = rs.level.dimension().identifier().getPath();
        String pre = switch (dim) {
            case "overworld" -> "ow";
            case "the_nether" -> "ne";
            case "the_end" -> "end";
            default -> dim.length() >= 2 ? dim.substring(0, 2) : dim;
        };
        return pre + "-" + p.id + "g" + p.gen;
    }

    /** Resolve a handle across all levels; null when absent or gen-stale. */
    public static Object[] byHandle(String handle) {
        for (var rs : RegionScheduler.allLevels()) {
            if (!rs.asyncActive) continue;
            for (var p : rs.islandRegistry.islands()) {
                if (handle(rs, p).equals(handle)) {
                    return new Object[]{rs, p};
                }
            }
        }
        return null;
    }

    /** Reader-side progress state of one island. */
    public enum State {
        /** Within 10 % of its target rate. */
        HEALTHY("Healthy"),
        /** Below 90 % of its target rate over the last seconds. */
        LAGGING("Lagging"),
        /** A unit has held the island's claim for over 3 s. */
        STALLED("Stalled"),
        /** The game is frozen ({@code /tick freeze}): no ticks are expected. */
        PAUSED("Paused"),
        /** No complete one-second TPS window yet (new island). */
        MEASURING("Measuring");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Main thread only, once per second: keeps every async island's TPS
     *  window warm, so a reader never sees the empty window of an island
     *  nobody looked at before. */
    static void sampleAll() {
        for (RegionizedServer rs : RegionScheduler.allLevels()) {
            if (!rs.asyncActive) continue;
            for (IslandRegistry.PIsland p : rs.islandRegistry.islands()) {
                actualTps(p);
            }
        }
    }

    /** The island's target rate (20 unless rated, sprinting or coupled). */
    public static double targetTps(RegionizedServer rs, IslandRegistry.PIsland p) {
        return 1_000_000_000.0 / targetNanos(rs, p);
    }

    /** The step the island's units actually roll by: its own target, or the
     *  shared rate of its cross-dimension coupling (the same choice as
     *  {@code IslandTickUnit.prepareCompletion}). */
    static long targetNanos(RegionizedServer rs, IslandRegistry.PIsland p) {
        CrossDimLinks.Coupling c = p.crossDimCoupling;
        if (c != null) {
            long shared = c.sharedTargetNanos;
            if (shared > 0) return shared;
        }
        return TemporalControl.effectiveTargetNanos(rs, p);
    }

    /** Main thread only. */
    public static State state(RegionizedServer rs, IslandRegistry.PIsland p) {
        return classify(rs, p, actualTps(p));
    }

    /**
     * Judged on the current rate only. The island clock's distance to the
     * level's game time ({@link #ticksBehind}) is NOT a lag signal: an island
     * never makes up ticks it lost (AGENTS.md, known open items), so one slow
     * second at startup leaves a permanent offset on a healthy 20 TPS island.
     */
    static State classify(RegionizedServer rs, IslandRegistry.PIsland p, double tps) {
        long now = System.nanoTime();
        if (p.unitClaim.get() && p.claimSiteNanos != 0 && now - p.claimSiteNanos > 3_000_000_000L) {
            return State.STALLED;
        }
        if (Double.isNaN(tps)) return State.MEASURING;
        if (tps >= 0.9 * targetTps(rs, p)) return State.HEALTHY;
        var server = RegionScheduler.getServer();
        return server != null && server.tickRateManager().isFrozen() ? State.PAUSED : State.LAGGING;
    }

    /** "sprint", "pinned", "rate 10" or null for an island at the global rate. */
    public static String pacingTag(RegionizedServer rs, IslandRegistry.PIsland p) {
        if (TemporalControl.isSprinting(rs, p)) return "sprint";
        if (TemporalControl.isCoherent(rs, p)) return "pinned";
        long own = TemporalControl.effectiveTargetNanos(rs, p);
        long targetNanos = targetNanos(rs, p);
        if (targetNanos == TemporalControl.globalIslandTargetNanos) return null;
        return String.format(java.util.Locale.ROOT, "rate %.1f", 1_000_000_000.0 / targetNanos)
                + (targetNanos != own ? " (set on a portal-linked island)" : "");
    }

    /** "linked with 3 islands (2 Overworld, 1 Nether); all tick at 20.0 TPS",
     *  or null when the island is not coupled. A coupling is a connected group:
     *  two Overworld islands above one Nether island are coupled with each
     *  other too. Main thread only. */
    public static String couplingSummary(IslandRegistry.PIsland p) {
        CrossDimLinks.Coupling c = p.crossDimCoupling;
        if (c == null) return null;
        int overworld = 0, nether = 0;
        for (int i = 0; i < c.members.size(); i++) {
            IslandRegistry.PIsland m = c.members.get(i);
            if (m == p) continue;
            if (m.level != null && m.level.dimension() == net.minecraft.world.level.Level.NETHER) nether++;
            else overworld++;
        }
        int others = overworld + nether;
        double tps = c.sharedTargetNanos > 0 ? 1_000_000_000.0 / c.sharedTargetNanos : 20.0;
        return String.format(java.util.Locale.ROOT,
                "linked with %d island%s (%d Overworld, %d Nether); all tick together at %.1f TPS",
                others, others == 1 ? "" : "s", overworld, nether, tps);
    }

    /** Ticks the island's clock is behind the level's game time: ticks it lost
     *  to slow units since it formed, which it never makes up. Not current lag. */
    public static long ticksBehind(RegionizedServer rs, IslandRegistry.PIsland p) {
        return rs.level.getGameTime() - p.logicalTick;
    }

    /** One tpstop/list row (the `top verbose` diagnostic form). */
    public static String row(RegionizedServer rs, IslandRegistry.PIsland p, int rank) {
        long targetNanos = TemporalControl.effectiveTargetNanos(rs, p);
        return String.format(java.util.Locale.ROOT,
                "#%d %s [%s] %s island#%d/gen%d cells=%d+%dh L=%d gt=%d ev=%d/%d tps=%.1f/%.1f mspt=%.2f max=%.2f lag=%dms missed=%d%s%s",
                rank, label(rs, p), handle(rs, p), rs.level.dimension().identifier(), p.id, p.gen,
                p.cells.size(), p.haloCells.size(), p.logicalTick,
                rs.level.getGameTime(), p.eventStashDepths()[0], p.eventStashDepths()[1],
                actualTps(p), 1_000_000_000.0 / targetNanos,
                p.emaUnitNanos / 1_000_000.0, p.maxUnitNanos / 1_000_000.0,
                p.lagNanos / 1_000_000, p.missedDeadlines,
                TemporalControl.isSprinting(rs, p) ? " SPRINT" : "",
                targetNanos != TemporalControl.globalIslandTargetNanos ? " RATE-OVERRIDE" : "");
    }
}
