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

    /** Main thread only; NaN means no complete observation window yet. */
    public static double actualTps(IslandRegistry.PIsland p) {
        p.tpsWindow.update(p.unitsCompleted, System.nanoTime());
        return p.tpsWindow.value();
    }

    static String formatTps(double tps) {
        return Double.isNaN(tps) ? "warming"
                : String.format(java.util.Locale.ROOT, "%.1f", tps);
    }

    static long overdueNanos(IslandRegistry.PIsland p, long now) {
        long deadline = p.nextDeadlineNanos;
        return deadline == 0 ? 0 : Math.max(0, now - deadline);
    }

    static long completionAgeNanos(IslandRegistry.PIsland p, long now) {
        return p.unitsCompleted == 0 ? -1 : Math.max(0, now - p.lastCompletionNanos);
    }

    /** Completed pre-seal cost is historical; current delay is reported separately. */
    static String completedCostAndState(IslandRegistry.PIsland p, long now) {
        long age = completionAgeNanos(p, now);
        String cost = age < 0 ? "warming completed pre-seal MSPT"
                : String.format(java.util.Locale.ROOT,
                        "%.2f completed pre-seal MSPT (last=%dms)",
                        p.emaUnitNanos / 1_000_000.0, age / 1_000_000);
        StringBuilder out = new StringBuilder(cost);
        long overdue = overdueNanos(p, now);
        if (overdue > 0) out.append(" overdue=").append(overdue / 1_000_000).append("ms");
        if (p.submitQueued.get()) out.append(" queued");
        if (p.unitClaim.get()) {
            out.append(" claim-held");
            long acquired = p.claimSiteNanos;
            if (acquired != 0) out.append('=').append(Math.max(0, now - acquired) / 1_000_000).append("ms");
        }
        if (p.waitingOnRoutedCommand) out.append(" waiting-routed-command");
        return out.toString();
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

    /**
     * Reader-side progress classification. Claims can belong to Main, topology
     * or portals as well as a unit; a held claim is not an in-flight tick.
     * No complete rate window means warming, never a guessed 20 or measured 0.
     */
    public static String classify(RegionizedServer rs, IslandRegistry.PIsland p) {
        return classify(rs, p, actualTps(p));
    }

    private static String classify(RegionizedServer rs, IslandRegistry.PIsland p, double tps) {
        long now = System.nanoTime();
        long claimAge = p.unitClaim.get() && p.claimSiteNanos != 0
                ? Math.max(0, now - p.claimSiteNanos) : 0;
        return classifyProgress(tps, IslandClock.wallTime(rs.level) - p.logicalTick,
                pacingTargetNanos(rs, p), claimAge, overdueNanos(p, now));
    }

    static String classifyProgress(double tps, long behind, long targetNanos,
                                   long claimAge, long overdue) {
        if (claimAge > 3_000_000_000L) return "Claim-held";
        if (Double.isNaN(tps)) return "Warming";
        double target = 1_000_000_000.0 / targetNanos;
        if (tps < 0.5 && behind <= 2 && overdue <= targetNanos) return "Idle";
        if (behind > 40 || tps < target - 2.0 || (tps < 0.5 && behind > 2)
                || overdue > targetNanos) return "Lagging";
        return "Healthy";
    }

    /** Same final cadence override consumed by unit completion, not just the requested rate. */
    private static long pacingTargetNanos(RegionizedServer rs, IslandRegistry.PIsland p) {
        CrossDimLinks.Coupling coupling = p.crossDimCoupling;
        if (coupling != null) {
            long shared = coupling.sharedTargetNanos;
            if (shared > 0) return shared;
        }
        return TemporalControl.effectiveTargetNanos(rs, p);
    }

    /** Admin-facing default row: state, who/where, dimension, rates, cost,
     *  size. Internal identifiers/clocks/counters live in {@link #row}
     *  (the `top verbose` form). */
    public static String adminRow(RegionizedServer rs, IslandRegistry.PIsland p, int rank) {
        long targetNanos = pacingTargetNanos(rs, p);
        double tps = actualTps(p);
        String state = classify(rs, p, tps);
        String tag = TemporalControl.isSprinting(rs, p) ? " [sprint]"
                : TemporalControl.isCoherent(rs, p) ? " [pinned]"
                : targetNanos != TemporalControl.globalIslandTargetNanos ? " [rated]" : "";
        String tpsStr = String.format(java.util.Locale.ROOT, "%s/%.0f",
                formatTps(tps), 1_000_000_000.0 / targetNanos);
        return String.format(java.util.Locale.ROOT,
                "#%-2d %-10s %-24s %-11s %9s TPS  %s  %d cells%s",
                rank, state, trim(label(rs, p), 24),
                rs.level.dimension().identifier().getPath(),
                tpsStr, completedCostAndState(p, System.nanoTime()), p.cells.size(), tag);
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "\u2026";
    }

    /** One tpstop/list row (the `top verbose` diagnostic form). */
    public static String row(RegionizedServer rs, IslandRegistry.PIsland p, int rank) {
        long targetNanos = pacingTargetNanos(rs, p);
        return String.format(java.util.Locale.ROOT,
                "#%d %s [%s] %s island#%d/gen%d cells=%d+%dh L=%d gt=%d ev=%d/%d tps=%s/%.1f %s completed-pre-seal-decaying-max=%.2fms lag=%dms missed=%d%s%s",
                rank, label(rs, p), handle(rs, p), rs.level.dimension().identifier(), p.id, p.gen,
                p.cells.size(), p.haloCells.size(), p.logicalTick,
                IslandClock.wallTime(rs.level), p.eventStashDepths()[0], p.eventStashDepths()[1],
                formatTps(actualTps(p)), 1_000_000_000.0 / targetNanos,
                completedCostAndState(p, System.nanoTime()), p.maxUnitNanos / 1_000_000.0,
                p.lagNanos / 1_000_000, p.missedDeadlines,
                TemporalControl.isSprinting(rs, p) ? " SPRINT" : "",
                targetNanos != TemporalControl.globalIslandTargetNanos ? " RATE-OVERRIDE" : "");
    }
}
