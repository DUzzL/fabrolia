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

    /** Main thread only. */
    public static double actualTps(IslandRegistry.PIsland p) {
        p.tpsWindow.update(p.unitsCompleted, System.nanoTime());
        return p.tpsWindow.value();
    }

    /** Human label (cached ~5s; recomputed on demand, never in the unit hot
     *  path). Priority: real players → players+bots → bots → Spawn →
     *  Unowned@blockX,blockZ. Manual alias renders as "Alias [auto]". The
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

    private static String autoLabel(RegionizedServer rs, IslandRegistry.PIsland p) {
        java.util.ArrayList<String> real = new java.util.ArrayList<>(4);
        java.util.ArrayList<String> bots = new java.util.ArrayList<>(4);
        var server = RegionScheduler.getServer();
        if (server != null) {
            for (ServerPlayer pl : server.getPlayerList().getPlayers()) {
                if (pl.level() != rs.level) {
                    continue;
                }
                if (rs.islandOwnerOfChunk(pl.chunkPosition().pack()) == p) {
                    if (pl.getClass().getName().contains("Fake")) {
                        bots.add(pl.getName().getString());
                    } else {
                        real.add(pl.getName().getString());
                    }
                }
            }
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
            return "Unowned@" + bx + "," + bz;
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

    /** Reader-side progress classification. */
    private static String classify(RegionizedServer rs, IslandRegistry.PIsland p, double tps) {
        long now = System.nanoTime();
        if (p.unitClaim.get() && p.claimSiteNanos != 0 && now - p.claimSiteNanos > 3_000_000_000L) {
            return "Stalled";
        }
        long behind = rs.level.getGameTime() - p.logicalTick;
        long targetNanos = TemporalControl.effectiveTargetNanos(rs, p);
        double target = 1_000_000_000.0 / targetNanos;
        if (behind > 40 || (tps > 0.5 && tps < target - 2.0)) return "Lagging";
        return tps < 0.5 && behind <= 2 ? "Idle" : "Healthy";
    }

    /** Admin-facing default row: state, who/where, dimension, rates, cost,
     *  size. Internal identifiers/clocks/counters live in {@link #row}
     *  (the `top verbose` form). */
    public static String adminRow(RegionizedServer rs, IslandRegistry.PIsland p, int rank) {
        long targetNanos = TemporalControl.effectiveTargetNanos(rs, p);
        double tps = actualTps(p);
        String state = classify(rs, p, tps);
        String tag = TemporalControl.isSprinting(rs, p) ? " [sprint]"
                : TemporalControl.isCoherent(rs, p) ? " [pinned]"
                : targetNanos != TemporalControl.globalIslandTargetNanos ? " [rated]" : "";
        String tpsStr = "Idle".equals(state)
                ? String.format(java.util.Locale.ROOT, "idle/%.0f", 1_000_000_000.0 / targetNanos)
                : String.format(java.util.Locale.ROOT, "%.1f/%.0f", tps, 1_000_000_000.0 / targetNanos);
        return String.format(java.util.Locale.ROOT,
                "#%-2d %-8s %-24s %-11s %9s TPS  %5.2fms avg %5.2fms max  %d cells%s",
                rank, state, trim(label(rs, p), 24),
                rs.level.dimension().identifier().getPath(),
                tpsStr, p.emaUnitNanos / 1_000_000.0, p.maxUnitNanos / 1_000_000.0,
                p.cells.size(), tag);
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "\u2026";
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
