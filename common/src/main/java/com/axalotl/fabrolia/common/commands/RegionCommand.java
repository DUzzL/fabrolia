package com.axalotl.fabrolia.common.commands;

import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.axalotl.fabrolia.common.platform.PlatformPermission;
import com.axalotl.fabrolia.common.platform.PlatformUtils;
import com.axalotl.fabrolia.common.regions.Region;
import com.axalotl.fabrolia.common.regions.RegionGrid;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionViz;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
import com.axalotl.fabrolia.common.regions.FailStops;
import com.axalotl.fabrolia.common.regions.MainFallbackStats;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static com.axalotl.fabrolia.common.commands.FabroliaCommand.prefix;
import static net.minecraft.commands.Commands.literal;

/**
 * {@code /fabrolia regions} — async-island region threading.
 *
 * <pre>
 *   /fabrolia regions                    — status summary
 *   /fabrolia regions list               — per-island rows (tps, mspt, lag)
 *   /fabrolia regions top [count]        — heaviest islands
 *   /fabrolia regions async              — toggle async islands
 *   /fabrolia regions async debug on|off — diagnostics gate (burst detectors,
 *                                          invariant ring dumps; default OFF)
 *   /fabrolia regions rate|sprint|name … — temporal control / island naming
 *   /fabrolia regions crossdim [on|off]  — Overworld↔Nether link listing +
 *                                          cadence-coupling toggle
 *   /fabrolia regions playerticks on|off — autonomous island player ownership when
 *                                          async is active; synchronous mode always
 *                                          ticks players on physical main
 *   /fabrolia regions tps                — action-bar TPS HUD (player)
 *   /fabrolia regions on|off|toggle      — master region-threading switch
 *   /fabrolia regions debug …            — everything diagnostic: flightrec,
 *                                          chunksender, islanddebug,
 *                                          crossdomain, popcheck, beindex,
 *                                          feature flags, fail-closed drill
 * </pre>
 */
public final class RegionCommand {

    private RegionCommand() {}

    private static final int LIST_TOP = 15;

    /**
     * Fault-injection and stress subcommands under {@code regions debug} (wedgemain,
     * failunittail, offmainpkt, chunksender inject/stresstp) are registered only when the
     * JVM runs with {@code -Dfabrolia.debugCommands=true}.
     */
    private static final boolean DEBUG_COMMANDS = Boolean.getBoolean("fabrolia.debugCommands");

    public static LiteralArgumentBuilder<CommandSourceStack> register(LiteralArgumentBuilder<CommandSourceStack> root) {
        // Primary tree — intentionally small: status, island list/top,
        // async (+ diagnostics gate), temporal controls, HUD, master
        // switch. Every diagnostic/forensic/feature-flag command lives
        // under "debug" so the first-level completion stays readable.
        LiteralArgumentBuilder<CommandSourceStack> regionsCmd = literal("regions")
                .requires(PlatformPermission.require("command.regions", 0))
                .executes(ctx -> { summary(ctx.getSource()); return 1; });
        regionsCmd.then(literal("list").executes(ctx -> islandList(ctx.getSource())));
        // One-shot local TPS check (the HUD and Carpet's /log tps are subscriptions).
        regionsCmd.then(literal("here").executes(ctx -> islandHere(ctx.getSource(), null)));
        regionsCmd.then(literal("player").requires(PlatformPermission.require("command.regions.player", 2))
                .then(Commands.argument("target", net.minecraft.commands.arguments.EntityArgument.player())
                        .executes(ctx -> islandHere(ctx.getSource(),
                                net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "target")))));
        LiteralArgumentBuilder<CommandSourceStack> topCmd = literal("top")
                .executes(ctx -> islandTop(ctx.getSource(), 10, "mspt", false))
                .then(literal("verbose")
                        .executes(ctx -> islandTop(ctx.getSource(), 10, "mspt", true))
                        .then(net.minecraft.commands.Commands.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 100))
                                .executes(ctx -> islandTop(ctx.getSource(),
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count"),
                                        "mspt", true))))
                .then(net.minecraft.commands.Commands.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 100))
                        .executes(ctx -> islandTop(ctx.getSource(),
                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count"),
                                "mspt", false))
                        .then(net.minecraft.commands.Commands.argument("sort", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .suggests((c, b) -> {
                                    b.suggest("mspt"); b.suggest("maxmspt"); b.suggest("lag"); b.suggest("tps");
                                    return b.buildFuture();
                                })
                                .executes(ctx -> islandTop(ctx.getSource(),
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count"),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "sort"),
                                        false))));
        regionsCmd.then(topCmd);
        regionsCmd.then(literal("ticks").requires(PlatformPermission.require("command.regions.toggle", 4))
                .then(literal("inspect").then(net.minecraft.commands.Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                        .executes(ctx -> {
                            var pos = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(ctx, "pos");
                            send(ctx, "", com.axalotl.fabrolia.common.regions.TickInspector.inspect(ctx.getSource().getLevel(), pos), false);
                            return 1;
                        })))
                .then(literal("status").then(net.minecraft.commands.Commands.argument("chunkX", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                        .then(net.minecraft.commands.Commands.argument("chunkZ", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                        .executes(ctx -> {
                            send(ctx, "", com.axalotl.fabrolia.common.regions.TickInspector.status(ctx.getSource().getLevel(),
                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "chunkX"),
                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "chunkZ")), false);
                            return 1;
                        }))))
                .then(literal("rebuild").then(net.minecraft.commands.Commands.argument("chunkX", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                        .then(net.minecraft.commands.Commands.argument("chunkZ", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                        .executes(ctx -> {
                            send(ctx, "", com.axalotl.fabrolia.common.regions.TickInspector.rebuild(ctx.getSource().getLevel(),
                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "chunkX"),
                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "chunkZ")), false);
                            return 1;
                        }))))
                .then(literal("scan").then(net.minecraft.commands.Commands.argument("chunkX", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                        .then(net.minecraft.commands.Commands.argument("chunkZ", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                        .then(net.minecraft.commands.Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 16))
                        .executes(ctx -> {
                            send(ctx, "", com.axalotl.fabrolia.common.regions.TickInspector.scan(ctx.getSource().getLevel(),
                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "chunkX"),
                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "chunkZ"),
                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "radius")), false);
                            return 1;
                        })))))
                .then(literal("clear-stale")
                        // SAFETY CONTRACT (2026-07-27 review): identical to the
                        // per-position form below — every bare form is a DRY RUN;
                        // only an explicit trailing 'confirm' removes anything.
                        .then(literal("chunkhere")
                                .executes(ctx -> {
                                    var src = ctx.getSource();
                                    int cx = (int) Math.floor(src.getPosition().x) >> 4;
                                    int cz = (int) Math.floor(src.getPosition().z) >> 4;
                                    String out = com.axalotl.fabrolia.common.regions.TickInspector.clearStaleChunk(src.getLevel(), cx, cz, false, true);
                                    send(ctx, "", out, false);
                                    return 1;
                                })
                                .then(literal("confirm").executes(ctx -> {
                                    var src = ctx.getSource();
                                    int cx = (int) Math.floor(src.getPosition().x) >> 4;
                                    int cz = (int) Math.floor(src.getPosition().z) >> 4;
                                    String out = com.axalotl.fabrolia.common.regions.TickInspector.clearStaleChunk(src.getLevel(), cx, cz, false, false);
                                    send(ctx, "", out, false);
                                    return 1;
                                }))
                                .then(literal("fire")
                                        .executes(ctx -> {
                                            var src = ctx.getSource();
                                            int cx = (int) Math.floor(src.getPosition().x) >> 4;
                                            int cz = (int) Math.floor(src.getPosition().z) >> 4;
                                            String out = com.axalotl.fabrolia.common.regions.TickInspector.clearStaleChunk(src.getLevel(), cx, cz, true, true);
                                            send(ctx, "", out, false);
                                            return 1;
                                        })
                                        .then(literal("confirm").executes(ctx -> {
                                            var src = ctx.getSource();
                                            int cx = (int) Math.floor(src.getPosition().x) >> 4;
                                            int cz = (int) Math.floor(src.getPosition().z) >> 4;
                                            String out = com.axalotl.fabrolia.common.regions.TickInspector.clearStaleChunk(src.getLevel(), cx, cz, true, false);
                                            send(ctx, "", out, false);
                                            return 1;
                                        })))
                                .then(literal("dry").executes(ctx -> {
                                    var src = ctx.getSource();
                                    int cx = (int) Math.floor(src.getPosition().x) >> 4;
                                    int cz = (int) Math.floor(src.getPosition().z) >> 4;
                                    String out = com.axalotl.fabrolia.common.regions.TickInspector.clearStaleChunk(src.getLevel(), cx, cz, false, true);
                                    send(ctx, "", out, false);
                                    return 1;
                                })))
                        .then(net.minecraft.commands.Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                        .executes(ctx -> {
                            var pos = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(ctx, "pos");
                            send(ctx, "", com.axalotl.fabrolia.common.regions.TickInspector.clearStale(ctx.getSource().getLevel(), pos, false), false);
                            return 1;
                        })
                        .then(literal("confirm").executes(ctx -> {
                            var pos = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(ctx, "pos");
                            send(ctx, "", com.axalotl.fabrolia.common.regions.TickInspector.clearStale(ctx.getSource().getLevel(), pos, true), false);
                            return 1;
                        }))))
                .then(literal("reschedule").then(net.minecraft.commands.Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                        .executes(ctx -> {
                            var pos = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(ctx, "pos");
                            send(ctx, "", com.axalotl.fabrolia.common.regions.TickInspector.reschedule(ctx.getSource().getLevel(), pos, 1), false);
                            return 1;
                        })
                        .then(net.minecraft.commands.Commands.argument("delay", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 72000))
                        .executes(ctx -> {
                            var pos = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(ctx, "pos");
                            send(ctx, "", com.axalotl.fabrolia.common.regions.TickInspector.reschedule(ctx.getSource().getLevel(), pos,
                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "delay")), false);
                            return 1;
                        })))));

        // Task #43: cross-dimension link listing + coupling toggle (runtime
        // command + config persistence — never a JVM flag).
        regionsCmd.then(literal("crossdim").requires(PlatformPermission.require("command.regions.toggle", 4))
                .executes(ctx -> {
                    send(ctx, "", com.axalotl.fabrolia.common.regions.CrossDimLinks.status(), false);
                    return 1;
                })
                .then(literal("on").executes(ctx -> {
                    com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaCrossDimCoupling = true;
                    PlatformUtils.saveConfig();
                    send(ctx, "crossdim coupling: ",
                            "ON — linked Overworld↔Nether islands share one deadline anchor + rate "
                            + "(links form at the next rebuild pass)", false);
                    return 1;
                }))
                .then(literal("off").executes(ctx -> {
                    com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaCrossDimCoupling = false;
                    PlatformUtils.saveConfig();
                    send(ctx, "crossdim coupling: ",
                            "off — islands pace independently; existing links drop at the next tick",
                            false);
                    return 1;
                })));

        // Entity phase of async island units: serial Vanilla insertion order or
        // ColorGate-parallel regions. Each unit reads the mode once at dispatch.
        regionsCmd.then(literal("islandentities").requires(PlatformPermission.require("command.regions.toggle", 4))
                .executes(ctx -> {
                    send(ctx, "island entity phase: ", FabroliaConfig.islandEntityParallel
                            ? "parallel (ColorGate regions; islands with TNT or minecarts stay serial)"
                            : "vanilla (one serial pass per island in insertion order)", false);
                    return 1;
                })
                .then(literal("vanilla").executes(ctx -> {
                    FabroliaConfig.islandEntityParallel = false;
                    PlatformUtils.saveConfig();
                    send(ctx, "island entity phase: ",
                            "vanilla — each island ticks its entities in one serial pass "
                            + "in insertion order (from its next unit)", true);
                    return 1;
                }))
                .then(literal("parallel").executes(ctx -> {
                    FabroliaConfig.islandEntityParallel = true;
                    PlatformUtils.saveConfig();
                    send(ctx, "island entity phase: ",
                            "parallel — island regions tick their entities in parallel under the "
                            + "ColorGate; islands holding primed TNT or any minecart stay serial "
                            + "(from their next unit)", true);
                    return 1;
                })));

        LiteralArgumentBuilder<CommandSourceStack> asyncCmd = literal("async")
                .requires(PlatformPermission.require("command.regions.toggle", 4))
                .then(literal("debug")
                        .executes(ctx -> {
                            send(ctx, "diagnostics: ", com.axalotl.fabrolia.common.regions.FlightRecorder.DEBUG
                                    ? "ON (burst detectors + invariant ring dumps)" : "OFF", false);
                            return 1;
                        })
                        .then(literal("on").executes(ctx -> {
                            com.axalotl.fabrolia.common.regions.FlightRecorder.DEBUG = true;
                            send(ctx, "diagnostics: ", "ON — burst detectors + invariant ring dumps enabled", true);
                            return 1;
                        }))
                        .then(literal("off").executes(ctx -> {
                            com.axalotl.fabrolia.common.regions.FlightRecorder.DEBUG = false;
                            send(ctx, "diagnostics: ", "OFF — corrective actions still log one concise line", true);
                            return 1;
                        })));
        // The live mode flip is the A/B control for "is this bug async-specific?":
        // it exists only with -Dfabrolia.debugCommands=true and is never saved.
        // Normal servers start synchronous only through -Dfabrolia.syncMode=true.
        if (DEBUG_COMMANDS) {
            asyncCmd.executes(ctx -> setAsyncIslands(ctx, !FabroliaConfig.asyncIslands))
                    .then(literal("on").executes(ctx -> setAsyncIslands(ctx, true)))
                    .then(literal("off").executes(ctx -> setAsyncIslands(ctx, false)));
        }
        regionsCmd.then(asyncCmd);

        regionsCmd.then(literal("playerticks")
                .requires(PlatformPermission.require("command.regions.toggle", 4))
                .executes(ctx -> {
                    send(ctx, "regional player entity ticks v2: ",
                            com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                                    .status(ctx.getSource().getServer()), false);
                    return 1;
                })
                .then(literal("on").executes(ctx -> setPlayerEntityTicks(ctx, true)))
                .then(literal("off").executes(ctx -> setPlayerEntityTicks(ctx, false))));
        LiteralArgumentBuilder<CommandSourceStack> coherenceCmd = literal("coherence")
                .requires(PlatformPermission.require("command.regions.toggle", 4))
                .then(literal("add").executes(ctx -> {
                    var src = ctx.getSource();
                    var rs = com.axalotl.fabrolia.common.regions.RegionScheduler.forLevel(src.getLevel());
                    if (rs == null || !rs.asyncActive) {
                        src.sendFailure(net.minecraft.network.chat.Component.literal("async islands not active here"));
                        return 0;
                    }
                    long ck = net.minecraft.world.level.ChunkPos.pack(
                            ((int) src.getPosition().x) >> 4, ((int) src.getPosition().z) >> 4);
                    var island = rs.islandOwnerOfChunk(ck);
                    if (island == null) {
                        src.sendFailure(net.minecraft.network.chat.Component.literal("no island owns this position"));
                        return 0;
                    }
                    if (com.axalotl.fabrolia.common.regions.TemporalControl.isCoherent(rs, island)) {
                        src.sendFailure(net.minecraft.network.chat.Component.literal("island#" + island.id + " already coherence-pinned"));
                        return 0;
                    }
                    com.axalotl.fabrolia.common.regions.TemporalControl.addCoherentSession(rs, island,
                            "coherence@" + ((int) src.getPosition().x) + "," + ((int) src.getPosition().z));
                    src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                            "island#" + island.id + " pinned to the global tick phase (rate/sprint inert here)"), true);
                    return 1;
                }))
                .then(literal("remove").executes(ctx -> {
                    var src = ctx.getSource();
                    var rs = com.axalotl.fabrolia.common.regions.RegionScheduler.forLevel(src.getLevel());
                    if (rs == null) {
                        src.sendFailure(net.minecraft.network.chat.Component.literal("async islands not active here"));
                        return 0;
                    }
                    long ck = net.minecraft.world.level.ChunkPos.pack(
                            ((int) src.getPosition().x) >> 4, ((int) src.getPosition().z) >> 4);
                    var island = rs.islandOwnerOfChunk(ck);
                    if (island == null) {
                        src.sendFailure(net.minecraft.network.chat.Component.literal("no island owns this position"));
                        return 0;
                    }
                    boolean removed = rs.temporalSessions.removeIf(sn -> sn.coherent
                            && com.axalotl.fabrolia.common.regions.TemporalControl.intersectsPublic(sn.cells, island.cells));
                    boolean r0 = removed;
                    src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                            r0 ? "coherence pin removed for island#" + island.id : "no pin here"), true);
                    return removed ? 1 : 0;
                }))
                .then(literal("list").executes(ctx -> {
                    var src = ctx.getSource();
                    var rs = com.axalotl.fabrolia.common.regions.RegionScheduler.forLevel(src.getLevel());
                    if (rs == null) {
                        src.sendFailure(net.minecraft.network.chat.Component.literal("async islands not active here"));
                        return 0;
                    }
                    StringBuilder sb = new StringBuilder("coherence pins:");
                    int n = 0;
                    for (var sn : rs.temporalSessions) {
                        if (!sn.coherent) continue;
                        n++;
                        sb.append("\n  session#").append(sn.id).append(' ').append(sn.label);
                        for (var isl : rs.islandRegistry.islands()) {
                            if (com.axalotl.fabrolia.common.regions.TemporalControl.intersectsPublic(sn.cells, isl.cells)) {
                                long drift = com.axalotl.fabrolia.common.regions.IslandClock
                                        .wallTime(src.getLevel()) - isl.logicalTick;
                                sb.append(" -> island#").append(isl.id).append(" drift=").append(drift);
                            }
                        }
                    }
                    if (n == 0) sb.append(" none");
                    String out = sb.toString();
                    src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(out), false);
                    return n;
                }));
        regionsCmd.then(coherenceCmd);

        regionsCmd.then(literal("rate").requires(PlatformPermission.require("command.regions.toggle", 4))
                                .then(literal("here").then(net.minecraft.commands.Commands.argument("tps",
                                        com.mojang.brigadier.arguments.FloatArgumentType.floatArg(0.1f, 10000f))
                                        .executes(ctx -> islandRateHere(ctx.getSource(),
                                                com.mojang.brigadier.arguments.FloatArgumentType.getFloat(ctx, "tps")))))
                                .then(literal("player").then(net.minecraft.commands.Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .then(net.minecraft.commands.Commands.argument("tps", com.mojang.brigadier.arguments.FloatArgumentType.floatArg(0.1f, 10000f))
                                        .executes(ctx -> applyRate(ctx.getSource(), islandByPlayer(ctx.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name")),
                                                com.mojang.brigadier.arguments.FloatArgumentType.getFloat(ctx, "tps"))))))
                                .then(literal("handle").then(net.minecraft.commands.Commands.argument("handle", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .then(net.minecraft.commands.Commands.argument("tps", com.mojang.brigadier.arguments.FloatArgumentType.floatArg(0.1f, 10000f))
                                        .executes(ctx -> applyRate(ctx.getSource(), com.axalotl.fabrolia.common.regions.IslandMetrics.byHandle(
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "handle")),
                                                com.mojang.brigadier.arguments.FloatArgumentType.getFloat(ctx, "tps"))))))
                                .then(literal("id").then(net.minecraft.commands.Commands.argument("dim", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .then(net.minecraft.commands.Commands.argument("id", com.mojang.brigadier.arguments.LongArgumentType.longArg(0))
                                        .then(net.minecraft.commands.Commands.argument("gen", com.mojang.brigadier.arguments.LongArgumentType.longArg(0))
                                        .then(net.minecraft.commands.Commands.argument("tps", com.mojang.brigadier.arguments.FloatArgumentType.floatArg(0.1f, 10000f))
                                        .executes(ctx -> applyRate(ctx.getSource(), islandById(ctx.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "dim"),
                                                com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "id"),
                                                com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "gen")),
                                                com.mojang.brigadier.arguments.FloatArgumentType.getFloat(ctx, "tps")))))))));
        regionsCmd.then(literal("sprint").requires(PlatformPermission.require("command.regions.toggle", 4))
                                .then(literal("here").then(net.minecraft.commands.Commands.argument("ticks",
                                        com.mojang.brigadier.arguments.LongArgumentType.longArg(1, 1000000))
                                        .executes(ctx -> islandSprintHere(ctx.getSource(),
                                                com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "ticks")))))
                                .then(literal("player").then(net.minecraft.commands.Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .then(net.minecraft.commands.Commands.argument("ticks", com.mojang.brigadier.arguments.LongArgumentType.longArg(1, 1000000))
                                        .executes(ctx -> applySprint(ctx.getSource(), islandByPlayer(ctx.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name")),
                                                com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "ticks"))))))
                                .then(literal("handle").then(net.minecraft.commands.Commands.argument("handle", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .then(net.minecraft.commands.Commands.argument("ticks", com.mojang.brigadier.arguments.LongArgumentType.longArg(1, 1000000))
                                        .executes(ctx -> applySprint(ctx.getSource(), com.axalotl.fabrolia.common.regions.IslandMetrics.byHandle(
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "handle")),
                                                com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "ticks"))))))
                                .then(literal("id").then(net.minecraft.commands.Commands.argument("dim", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .then(net.minecraft.commands.Commands.argument("id", com.mojang.brigadier.arguments.LongArgumentType.longArg(0))
                                        .then(net.minecraft.commands.Commands.argument("gen", com.mojang.brigadier.arguments.LongArgumentType.longArg(0))
                                        .then(net.minecraft.commands.Commands.argument("ticks", com.mojang.brigadier.arguments.LongArgumentType.longArg(1, 1000000))
                                        .executes(ctx -> applySprint(ctx.getSource(), islandById(ctx.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "dim"),
                                                com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "id"),
                                                com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "gen")),
                                                com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "ticks")))))))));
        regionsCmd.then(literal("name").requires(PlatformPermission.require("command.regions.toggle", 4))
                                .then(literal("here").then(net.minecraft.commands.Commands.argument("alias", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(ctx -> {
                                            final net.minecraft.server.level.ServerPlayer pl;
                                            try {
                                                pl = ctx.getSource().getPlayerOrException();
                                            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                                                send(ctx, "name: ", "player required", false);
                                                return 0;
                                            }
                                            var rsN = com.axalotl.fabrolia.common.regions.RegionScheduler.forLevel(pl.level());
                                            var islN = rsN == null ? null : rsN.islandOwnerOfChunk(pl.chunkPosition().pack());
                                            if (islN == null) { send(ctx, "name: ", "no island here", false); return 0; }
                                            islN.alias = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "alias");
                                            islN.labelCache = null;
                                            send(ctx, "name: ", islN.alias + " [" + com.axalotl.fabrolia.common.regions.IslandMetrics.handle(rsN, islN) + "]", false);
                                            return 1;
                                        })))
                                .then(literal("clear").then(literal("here").executes(ctx -> {
                                    final net.minecraft.server.level.ServerPlayer pl;
                                    try {
                                        pl = ctx.getSource().getPlayerOrException();
                                    } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                                        send(ctx, "name: ", "player required", false);
                                        return 0;
                                    }
                                    var rsN = com.axalotl.fabrolia.common.regions.RegionScheduler.forLevel(pl.level());
                                    var islN = rsN == null ? null : rsN.islandOwnerOfChunk(pl.chunkPosition().pack());
                                    if (islN != null) { islN.alias = null; islN.labelCache = null; }
                                    send(ctx, "name: ", "cleared", false);
                                    return 1;
                                }))));
        // Local-cap pack reservation (task #32 endgame). Approval of a chunk
        // pass atomically reserves the pack's worth of per-player slots, so
        // parallel passes see them taken (overshoot ≈ cap + one pack, vanilla's
        // class) while packs still complete like vanilla (no per-candidate
        // rejection — the farm-starve fix). OFF = vanilla plain approval:
        // packs complete, the parallel overshoot window returns, and a
        // reservation-blind native arena may attach again. Measure with
        // /fabrolia regions debug spawnrate.
        regionsCmd.then(literal("localcapreserve")
                .requires(PlatformPermission.require("command.regions.toggle", 4))
                .executes(ctx -> { localCapReserveReport(ctx); return 1; })
                .then(literal("on").executes(ctx -> {
                    FabroliaConfig.fabroliaSpawnLocalCapReservation = true;
                    PlatformUtils.saveConfig();
                    localCapReserveReport(ctx);
                    return 1;
                }))
                .then(literal("off").executes(ctx -> {
                    FabroliaConfig.fabroliaSpawnLocalCapReservation = false;
                    PlatformUtils.saveConfig();
                    localCapReserveReport(ctx);
                    return 1;
                })));
        regionsCmd.then(literal("tps").executes(ctx -> {
                    final net.minecraft.server.level.ServerPlayer player;
                    try {
                        player = ctx.getSource().getPlayerOrException();
                    } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                        send(ctx, "tps hud: ", "player required", false);
                        return 0;
                    }
                    boolean on = com.axalotl.fabrolia.common.regions.TpsHud.toggle(player);
                    send(ctx, "TPS display: ",
                            on ? "on: Main and island TPS now show above your hotbar (run again to hide)"
                               : "off", false);
                    return 1;
                }));
        regionsCmd.then(literal("on").requires(PlatformPermission.require("command.regions.toggle", 4))
                        .executes(ctx -> { setEnabled(ctx.getSource(), true); return 1; }));
        regionsCmd.then(literal("off").requires(PlatformPermission.require("command.regions.toggle", 4))
                        .executes(ctx -> { setEnabled(ctx.getSource(), false); return 1; }));
        regionsCmd.then(literal("toggle").requires(PlatformPermission.require("command.regions.toggle", 4))
                        .executes(ctx -> {
                            setEnabled(ctx.getSource(), !FabroliaConfig.enableRegionThreading);
                            return 1;
                        }));
        LiteralArgumentBuilder<CommandSourceStack> dbg = literal("debug")
                .requires(PlatformPermission.require("command.regions.toggle", 4));
        dbg.then(literal("regionlist").executes(ctx -> { list(ctx.getSource()); return 1; }));
        dbg.then(literal("islandsummary").executes(ctx -> { islands(ctx.getSource()); return 1; }));
        dbg.then(literal("islandsraw").requires(PlatformPermission.require("command.regions", 0))
                        .executes(ctx -> {
                            var server = ctx.getSource().getServer();
                            StringBuilder sb = new StringBuilder();
                            for (net.minecraft.server.level.ServerLevel lvl : server.getAllLevels()) {
                                var rs = com.axalotl.fabrolia.common.regions.RegionScheduler.forLevel(lvl);
                                if (rs == null) continue;
                                long gt = lvl.getGameTime();
                                var list = new java.util.ArrayList<>(rs.islandRegistry.islands());
                                java.util.IdentityHashMap<Object, Long> costK = new java.util.IdentityHashMap<>();
                                for (var p2 : list) costK.put(p2, p2.costNanos);
                                list.sort((a, b) -> Long.compare(costK.get(b), costK.get(a)));
                                for (var p2 : list) {
                                    long ents = 0;
                                    var itc = p2.cells.iterator();
                                    while (itc.hasNext()) {
                                        var r = rs.regionizer().peekByGridKey(itc.nextLong());
                                        if (r != null) ents += r.entityCount.get();
                                    }
                                    sb.append(String.format(
                                            "%s island#%d cells=%d ent=%d cost=%.2fms %sTPS=%.1f logical=%d offset=%d lag=%.1fms miss=%d age=%d%n",
                                            lvl.dimension().identifier(), p2.id, p2.cells.size(), ents,
                                            p2.costNanos / 1e6, rs.asyncActive ? "" : "shadow", p2.shadowTps(), p2.logicalTick,
                                            gt - p2.logicalTick, p2.lagNanos / 1e6, p2.missedDeadlines, gt - p2.bornGameTime));
                                }
                                for (var ev : rs.islandRegistry.recentEvents()) {
                                    sb.append(String.format("  [t=%d] %s #%d %s cells=%d (%s)%n",
                                            ev.gameTime(), ev.type(), ev.survivorId(),
                                            java.util.Arrays.toString(ev.otherIds()), ev.cells(), ev.reason()));
                                }
                            }
                            String out = sb.length() == 0 ? "islands: region scheduler inactive" : sb.toString();
                            send(ctx, "", out, false);
                            org.slf4j.LoggerFactory.getLogger("fabrolia").info("ISLANDS\n" + out);
                            return 1;
                        }));
        dbg.then(literal("dump").executes(ctx -> { dump(ctx.getSource()); return 1; }));
        dbg.then(literal("show").requires(PlatformPermission.require("command.regions.show", 2))
                        .executes(ctx -> { show(ctx.getSource()); return 1; }));
        dbg.then(literal("temporal").executes(ctx -> {
                    for (net.minecraft.server.level.ServerLevel lvl : ctx.getSource().getServer().getAllLevels()) {
                        var rsT = com.axalotl.fabrolia.common.regions.RegionScheduler.forLevel(lvl);
                        if (rsT != null && rsT.asyncActive) {
                            send(ctx, lvl.dimension().identifier() + ": ",
                                    com.axalotl.fabrolia.common.regions.TemporalControl.status(rsT), false);
                        }
                    }
                    return 1;
                }));
        dbg.then(literal("regtop").requires(PlatformPermission.require("command.regions", 0))
                        .executes(ctx -> {
                            var server = ctx.getSource().getServer();
                            StringBuilder sb = new StringBuilder();
                            for (net.minecraft.server.level.ServerLevel lvl : server.getAllLevels()) {
                                var rs = com.axalotl.fabrolia.common.regions.RegionScheduler.forLevel(lvl);
                                if (rs == null) continue;
                                var regions = new java.util.ArrayList<>(rs.snapshot());
                                regions.removeIf(r -> r.emaPhaseNanos == 0);
                                java.util.IdentityHashMap<Object, Long> emaK = new java.util.IdentityHashMap<>();
                                for (var r : regions) emaK.put(r, r.emaPhaseNanos);
                                regions.sort((a, b) -> Long.compare(emaK.get(b), emaK.get(a)));
                                for (int i = 0; i < Math.min(8, regions.size()); i++) {
                                    var r = regions.get(i);
                                    int rx = (int) r.gridKey, rz = (int) (r.gridKey >>> 32);
                                    sb.append(String.format("%s cell(%d,%d) ent=%d ema=%.2fms last=%.2fms%n",
                                            lvl.dimension().identifier(), rx, rz, r.entityCount.get(),
                                            r.emaPhaseNanos / 1e6, r.lastPhaseNanos / 1e6));
                                }
                            }
                            sb.append(String.format("main-steal: %d help batches, %.1f ms total",
                                    com.axalotl.fabrolia.common.regions.RegionWorkerPool.MAIN_HELP_BATCHES.get(),
                                    com.axalotl.fabrolia.common.regions.RegionWorkerPool.MAIN_HELP_NANOS.get() / 1e6));
                            send(ctx, "", sb.toString(), false);
                            org.slf4j.LoggerFactory.getLogger("fabrolia").info("REGTOP\n" + sb);
                            return 1;
                        }));
        dbg.then(literal("beindex").requires(PlatformPermission.require("command.regions", 0))
                        .executes(ctx -> {
                            var server = ctx.getSource().getServer();
                            StringBuilder sb = new StringBuilder();
                            for (net.minecraft.server.level.ServerLevel lvl : server.getAllLevels()) {
                                var rs = com.axalotl.fabrolia.common.regions.RegionScheduler.forLevel(lvl);
                                if (rs != null) {
                                    if (sb.length() > 0) sb.append('\n');
                                    sb.append(lvl.dimension().identifier()).append(' ').append(rs.beIndexVerify());
                                }
                            }
                            String out = sb.length() == 0 ? "beindex: region scheduler inactive" : sb.toString();
                            send(ctx, "", out, false);
                            return 1;
                        }));
        dbg.then(literal("popcheck").requires(PlatformPermission.require("command.regions", 0))
                        .executes(ctx -> {
                            var server = ctx.getSource().getServer();
                            StringBuilder sb = new StringBuilder();
                            for (net.minecraft.server.level.ServerLevel lvl : server.getAllLevels()) {
                                var rs = com.axalotl.fabrolia.common.regions.RegionScheduler.forLevel(lvl);
                                if (rs != null) {
                                    if (sb.length() > 0) sb.append('\n');
                                    sb.append(lvl.dimension().identifier()).append(' ').append(rs.popCheck());
                                }
                            }
                            String out = sb.length() == 0 ? "popcheck: inactive" : sb.toString();
                            send(ctx, "", out, false);
                            org.slf4j.LoggerFactory.getLogger("fabrolia").info("POPCHECK " + out);
                            return 1;
                        }));
        dbg.then(literal("spawncap").requires(PlatformPermission.require("command.regions", 0))
                        .then(net.minecraft.commands.Commands.argument("cx", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                        .then(net.minecraft.commands.Commands.argument("cz", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                        .executes(ctx -> {
                            int cx = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "cx");
                            int cz = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "cz");
                            var lvl = ctx.getSource().getServer().overworld();
                            var st = lvl.getChunkSource().getLastSpawnState();
                            String out;
                            if (st == null) {
                                out = "SPAWNCAP no lastSpawnState";
                            } else {
                                Object calc = ((com.axalotl.fabrolia.api.spawn.FabroliaSpawnStateView) (Object) st).fabrolia$localCalc();
                                out = "SPAWNCAP chunk[" + cx + "," + cz + "] "
                                        + (calc == null ? "no calc"
                                        : ((com.axalotl.fabrolia.api.spawn.FabroliaLocalCapView) calc)
                                                .fabrolia$debugReport(net.minecraft.world.level.ChunkPos.pack(cx, cz)));
                            }
                            send(ctx, "", out, false);
                            org.slf4j.LoggerFactory.getLogger("fabrolia").info(out);
                            return 1;
                        }))));
        dbg.then(literal("spawnrate").requires(PlatformPermission.require("command.regions", 0))
                        .then(literal("off").executes(ctx -> {
                            boolean was = com.axalotl.fabrolia.common.parallelised.utils.SpawnRateDiag.disarm();
                            send(ctx, "spawnrate: ", was ? "recording stopped" : "recording was not armed", false);
                            return 1;
                        }))
                        .then(net.minecraft.commands.Commands.argument("ticks", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 128))
                        .executes(ctx -> {
                            String out = com.axalotl.fabrolia.common.parallelised.utils.SpawnRateDiag.report(
                                    ctx.getSource().getLevel(),
                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "ticks"));
                            send(ctx, "", out, false);
                            org.slf4j.LoggerFactory.getLogger("fabrolia").info(out);
                            return 1;
                        })))
        ;
        if (DEBUG_COMMANDS) dbg.then(literal("wedgemain").requires(PlatformPermission.require("command.regions", 2))
                .then(net.minecraft.commands.Commands.argument("ms",
                        com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 120000))
                        .executes(ctx -> {
                            int ms = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "ms");
                            // Always enqueue the park behind this command. CommandsMixin
                            // holds the all-island command fence around the current dispatch;
                            // parking inline would therefore freeze the islands too and make
                            // this autonomy probe invalid. TickTask starts only after the
                            // wrapper returns and releases that fence.
                            net.minecraft.server.MinecraftServer server =
                                    ctx.getSource().getServer();
                            RegionScheduler.runSuccessBodyIfHealthy(() ->
                                    server.schedule(new net.minecraft.server.TickTask(
                                            server.getTickCount(),
                                            () -> fabrolia$runWedgeMainTask(ms))));
                            return 1;
                        })));
        dbg.then(literal("claims").requires(PlatformPermission.require("command.regions", 0))
                        .executes(ctx -> {
                            // task #25: retained-claim state on demand — fence
                            // marks, reentrancy-ledger sizes, full forensics
                            // (holder identity/aliveness/live stack, site, age)
                            // for every island whose flag is set.
                            String out = com.axalotl.fabrolia.common.regions.IslandCommands.claimsDump();
                            send(ctx, "", out, false);
                            org.slf4j.LoggerFactory.getLogger("fabrolia").info("[claims]\n{}", out);
                            return 1;
                        }));
        if (DEBUG_COMMANDS) dbg.then(literal("failunittail").requires(PlatformPermission.require("command.regions", 2))
                .then(net.minecraft.commands.Commands.argument("n",
                        com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 64))
                        .executes(ctx -> {
                            int n = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "n");
                            // task #25 rig fault injection: the next n island
                            // units throw in the finally TAIL — verifies the
                            // contained-tail + unskippable-release chain.
                            com.axalotl.fabrolia.common.regions.IslandTickUnit.setDebugFailTail(n);
                            send(ctx, "", "failunittail armed for next " + n + " units", false);
                            org.slf4j.LoggerFactory.getLogger("fabrolia").warn(
                                    "[failunittail] armed for next {} units (debug injector)", n);
                            return 1;
                        })));
        if (DEBUG_COMMANDS) dbg.then(literal("offmainpkt").requires(PlatformPermission.require("command.regions", 2))
                        .executes(ctx -> {
                            // task #25 reproduction hook: dispatch a REAL
                            // use-item packet handler invocation from a
                            // non-main thread — the production entry the two
                            // watchdog dumps show (Netty epoll inside the
                            // interaction wrap). Correct behavior: the wrap
                            // passes through, vanilla's handler head throws
                            // RunningOnDifferentThreadException and requeues
                            // to main, and NO off-main claim/belt fires.
                            final net.minecraft.server.level.ServerPlayer sp;
                            try {
                                sp = ctx.getSource().getPlayerOrException();
                            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                                send(ctx, "", "needs a player source", false);
                                return 0;
                            }
                            final var log = org.slf4j.LoggerFactory.getLogger("fabrolia");
                            final var pkt = new net.minecraft.network.protocol.game.ServerboundUseItemPacket(
                                    net.minecraft.world.InteractionHand.MAIN_HAND, 0,
                                    sp.getYRot(), sp.getXRot());
                            Thread t = new Thread(() -> {
                                try {
                                    sp.connection.handleUseItem(pkt);
                                    log.error("[offmainpkt] handler RETURNED NORMALLY off-main — "
                                            + "wrap did not pass through (defect present)");
                                } catch (Throwable th) {
                                    if (th instanceof net.minecraft.server.RunningOnDifferentThreadException) {
                                        log.info("[offmainpkt] vanilla requeue observed off-main "
                                                + "(pass-through correct) — replay lands on main");
                                    } else {
                                        // handleUseItem was entered. An unexpected failure may
                                        // follow partial vanilla/mod simulation and must close the
                                        // generation before this debug thread can return normally.
                                        com.axalotl.fabrolia.common.regions.IslandCommands
                                                .publishAndPropagate(th);
                                        throw new AssertionError(
                                                "fatal off-main packet publication returned");
                                    }
                                }
                            }, "fabrolia-offmainpkt-test");
                            t.start();
                            send(ctx, "", "off-main use-item dispatched — check log for requeue verdict", false);
                            return 1;
                        }));
        dbg.then(literal("xfer").requires(PlatformPermission.require("command.regions", 0))
                        .executes(ctx -> {
                            // task #26: cross-dimension arrival pipeline
                            // counters + queued PTM handoffs.
                            String out = com.axalotl.fabrolia.common.regions.CrossDimArrivals.status()
                                    + " ptmPending=" + com.axalotl.fabrolia.common.regions.NetherPortalTransfers.pendingCount();
                            send(ctx, "", out, false);
                            org.slf4j.LoggerFactory.getLogger("fabrolia").info("[xfer]\n{}", out);
                            return 1;
                        }));
        dbg.then(literal("portalstuck").requires(PlatformPermission.require("command.regions", 0))
                        .executes(ctx -> {
                            // task #26: inspect the CURRENT world for any entity
                            // trapped/resting in a portal — the completed-then-
                            // stuck cart the live xfer trace can't see. Answers
                            // all eight failure discriminators per entity.
                            String out = com.axalotl.fabrolia.common.regions.PortalStuckProbe
                                    .scan(ctx.getSource().getServer());
                            send(ctx, "", out, false);
                            org.slf4j.LoggerFactory.getLogger("fabrolia").info("[portalstuck]\n{}", out);
                            return 1;
                        }));
        dbg.then(literal("navstats").requires(PlatformPermission.require("command.regions", 0))
                        .executes(ctx -> {
                            StringBuilder sb = new StringBuilder();
                            for (net.minecraft.server.level.ServerLevel lvl : ctx.getSource().getServer().getAllLevels()) {
                                if (sb.length() > 0) sb.append('\n');
                                sb.append(lvl.dimension().identifier()).append(' ');
                                if (lvl instanceof com.axalotl.fabrolia.common.parallelised.utils.FabroliaNavAudit audit) {
                                    sb.append(audit.fabrolia$navStats());
                                } else {
                                    sb.append("no lithium nav path");
                                }
                            }
                            String out = sb.toString();
                            send(ctx, "", out, false);
                            org.slf4j.LoggerFactory.getLogger("fabrolia").info(out);
                            return 1;
                        }));
        dbg.then(literal("advstats").requires(PlatformPermission.require("command.regions", 0))
                        .executes(ctx -> {
                            String out = com.axalotl.fabrolia.common.parallelised.advancement.AdvancementDiag.report();
                            send(ctx, "", out, false);
                            org.slf4j.LoggerFactory.getLogger("fabrolia").info(out);
                            return 1;
                        }));
        dbg.then(literal("spawncensus").requires(PlatformPermission.require("command.regions", 0))
                        .executes(ctx -> {
                            var server = ctx.getSource().getServer();
                            StringBuilder sb = new StringBuilder();
                            for (net.minecraft.server.level.ServerLevel lvl : server.getAllLevels()) {
                                if (sb.length() > 0) sb.append('\n');
                                sb.append(lvl.dimension().identifier()).append(' ');
                                try {
                                    sb.append(com.axalotl.fabrolia.common.parallelised.utils.SpawnCensusDiag.report(lvl));
                                } catch (Throwable t) {
                                    sb.append("report failed: ").append(t);
                                }
                            }
                            String out = sb.toString();
                            send(ctx, "", out, false);
                            org.slf4j.LoggerFactory.getLogger("fabrolia").info(out);
                            return 1;
                        }));
        dbg.then(literal("fallbacks").requires(PlatformPermission.require("command.regions.toggle", 4))
                        .executes(ctx -> {
                            ctx.getSource().sendSystemMessage(fallbackReport());
                            return 1;
                        })
                        .then(literal("reset").executes(ctx -> {
                            MainFallbackStats.resetAll();
                            send(ctx, "main fallback counters: ", "reset", false);
                            return 1;
                        })));
        dbg.then(literal("cmdstats").requires(PlatformPermission.require("command.regions", 0))
                        .executes(ctx -> {
                            String out = com.axalotl.fabrolia.common.regions.IslandCommands.stats();
                            send(ctx, "", out, false);
                            org.slf4j.LoggerFactory.getLogger("fabrolia").info("CMDSTATS " + out);
                            return 1;
                        }));
        LiteralArgumentBuilder<CommandSourceStack> chunkSender = literal("chunksender")
                        .requires(PlatformPermission.require("command.regions.toggle", 4))
                        .then(literal("status")
                                .executes(ctx -> {
                                    send(ctx, "", com.axalotl.fabrolia.common.regions.ChunkDelivery.status(null), false);
                                    return 1;
                                })
                                .then(net.minecraft.commands.Commands.argument("player", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(ctx -> {
                                            var pl = ctx.getSource().getServer().getPlayerList().getPlayerByName(
                                                    com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "player"));
                                            send(ctx, "", com.axalotl.fabrolia.common.regions.ChunkDelivery.status(pl), false);
                                            return 1;
                                        })))
                        .then(literal("trace")
                                .then(net.minecraft.commands.Commands.argument("player", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .then(net.minecraft.commands.Commands.argument("cx", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .then(net.minecraft.commands.Commands.argument("cz", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                        .executes(ctx -> {
                                            var pl = ctx.getSource().getServer().getPlayerList().getPlayerByName(
                                                    com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "player"));
                                            send(ctx, "", com.axalotl.fabrolia.common.regions.ChunkDelivery.traceCmd(pl,
                                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "cx"),
                                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "cz")), false);
                                            return 1;
                                        })))));
        // Fault injection and teleport stress: only with -Dfabrolia.debugCommands=true.
        if (DEBUG_COMMANDS) {
            chunkSender
                        .then(literal("inject")
                                .then(net.minecraft.commands.Commands.argument("faults", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(ctx -> {
                                            String f = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "faults");
                                            com.axalotl.fabrolia.common.regions.ChunkDelivery.faults = "none".equals(f) ? "" : f;
                                            send(ctx, "delivery faults: ", "'" + com.axalotl.fabrolia.common.regions.ChunkDelivery.faults + "'", false);
                                            return 1;
                                        })))
                        .then(literal("stresstp")
                                .then(net.minecraft.commands.Commands.argument("player", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .then(net.minecraft.commands.Commands.argument("blocksPerSec", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 100000))
                                .then(net.minecraft.commands.Commands.argument("seconds", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 600))
                                .then(net.minecraft.commands.Commands.argument("seed", com.mojang.brigadier.arguments.LongArgumentType.longArg())
                                        .executes(ctx -> {
                                            var pl = ctx.getSource().getServer().getPlayerList().getPlayerByName(
                                                    com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "player"));
                                            if (pl == null) { send(ctx, "stresstp: ", "player not found", false); return 0; }
                                            send(ctx, "stresstp: ", com.axalotl.fabrolia.common.regions.ChunkDelivery.stressStart(pl,
                                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "blocksPerSec"),
                                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "seconds"),
                                                    com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "seed")), false);
                                            return 1;
                                        }))))));
        }
        dbg.then(chunkSender);
        dbg.then(literal("flightrec").requires(PlatformPermission.require("command.regions.toggle", 4))
                        .then(literal("status").executes(ctx -> {
                            send(ctx, "flightrec: ", com.axalotl.fabrolia.common.regions.FlightRecorder.status(), false);
                            return 1;
                        }))
                        .then(literal("dump").executes(ctx -> {
                            send(ctx, "flightrec: ", com.axalotl.fabrolia.common.regions.FlightRecorder.dumpNow(), false);
                            return 1;
                        }))
                        .then(literal("clear").executes(ctx -> {
                            send(ctx, "flightrec: ", com.axalotl.fabrolia.common.regions.FlightRecorder.clear(), false);
                            return 1;
                        })));
        dbg.then(literal("islanddebug").requires(PlatformPermission.require("command.regions.toggle", 4))
                        .then(net.minecraft.commands.Commands.argument("id", com.mojang.brigadier.arguments.LongArgumentType.longArg(0))
                        .then(net.minecraft.commands.Commands.argument("ms", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 10_000))
                        .executes(ctx -> {
                            long id = com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "id");
                            int ms = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "ms");
                            int hit = 0;
                            for (net.minecraft.server.level.ServerLevel lvl : ctx.getSource().getServer().getAllLevels()) {
                                var rs = com.axalotl.fabrolia.common.regions.RegionScheduler.forLevel(lvl);
                                if (rs == null) continue;
                                for (var p2 : rs.islandRegistry.islands()) {
                                    if (p2.id == id) { p2.debugCostNanos = ms * 1_000_000L; hit++; }
                                }
                            }
                            send(ctx, "islanddebug: ", hit > 0 ? ("island#" + id + " artificial cost " + ms + "ms") : "island id not found", false);
                            return hit;
                        }))));
        if (DEBUG_COMMANDS) dbg.then(literal("crossdomain").requires(PlatformPermission.require("command.regions.toggle", 4))
                        .executes(ctx -> {
                            // TEST HARNESS ONLY: schedules a deliberately
                            // ownership-violating block write (island A's
                            // coordinator writing into island B) to prove the
                            // detector + fail-closed path end to end.
                            var server = ctx.getSource().getServer();
                            for (net.minecraft.server.level.ServerLevel lvl : server.getAllLevels()) {
                                var rs = com.axalotl.fabrolia.common.regions.RegionScheduler.forLevel(lvl);
                                if (rs == null || !rs.asyncActive) continue;
                                var isles = new java.util.ArrayList<>(rs.islandRegistry.islands());
                                if (isles.size() < 2) continue;
                                var a = isles.get(0);
                                var b = isles.get(1);
                                long bCell = b.cells.iterator().nextLong();
                                int shift = rs.regionizer().grid.shift + 4;
                                int bx = ((int) bCell) << shift;
                                int bz = ((int) (bCell >>> 32)) << shift;
                                final net.minecraft.core.BlockPos target =
                                        new net.minecraft.core.BlockPos(bx + 2, 150, bz + 2);
                                com.axalotl.fabrolia.common.regions.CommandShards.enqueueIslandTask(a, null,
                                        () -> lvl.setBlock(target, net.minecraft.world.level.block.Blocks.BEDROCK.defaultBlockState(), 3));
                                send(ctx, "debugcrossdomain: ", "violation scheduled island#" + a.id + " -> island#" + b.id, false);
                                return 1;
                            }
                            send(ctx, "debugcrossdomain: ", "need async + 2 islands", false);
                            return 0;
                        }));
        dbg.then(boolToggle("eviction", "idle-region eviction",
                        () -> FabroliaConfig.fabroliaRegionEviction,
                        v -> FabroliaConfig.fabroliaRegionEviction = v));
        dbg.then(boolToggle("crossredstone", "cross-region redstone guard",
                        () -> FabroliaConfig.fabroliaRedstoneCrossRegionGuard,
                        v -> FabroliaConfig.fabroliaRedstoneCrossRegionGuard = v));
        // Turning the event scheduler off also collapses async islands to sync
        // (RegionScheduler.asyncModeWantedAtLevelHead), so it is a live mode flip.
        if (DEBUG_COMMANDS) dbg.then(boolToggle("eventsched", "R5 event-driven entity dispatch (off = legacy group dispatch)",
                        () -> FabroliaConfig.regionEventScheduler,
                        v -> FabroliaConfig.regionEventScheduler = v));
        dbg.then(boolToggle("setIslandBlockEntityTicks", "island block-entity ticks",
                        () -> FabroliaConfig.fabroliaIslandBlockEntityTicks,
                        v -> FabroliaConfig.fabroliaIslandBlockEntityTicks = v));
        dbg.then(literal("access").requires(PlatformPermission.require("command.regions.toggle", 4))
                        .executes(ctx -> {
                            String status = com.axalotl.fabrolia.common.regions.RegionAccessDetector.status()
                                    + " (detector " + (FabroliaConfig.fabroliaDebugRegionAccess ? "ON" : "OFF") + ")";
                            send(ctx, "Region access detector: ", status, false);
                            return 1;
                        })
                        .then(Commands.argument("value", BoolArgumentType.bool())
                                .executes(ctx -> {
                                    boolean v = BoolArgumentType.getBool(ctx, "value");
                                    FabroliaConfig.fabroliaDebugRegionAccess = v;
                                    send(ctx, "Region access detector: ", v ? "ON" : "OFF",
                                            v ? "" : "  " + com.axalotl.fabrolia.common.regions.RegionAccessDetector.status());
                                    return 1;
                                })));
        regionsCmd.then(dbg);
        return root.then(regionsCmd);
    }

    // ------------------------------------------------------------------
    // toggle builder — matches ConfigCommand.buildFabroliaEntitySpawnCommand
    // shape: bare command prints current value; `<true|false>` sets it.
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> boolToggle(
            String name, String displayName,
            BooleanSupplier getter, Consumer<Boolean> setter) {
        return literal(name)
                .requires(PlatformPermission.require("command.regions.toggle", 4))
                .executes(ctx -> {
                    send(ctx, "Current value of " + displayName + ": ",
                            String.valueOf(getter.getAsBoolean()), false);
                    return 1;
                })
                .then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(ctx -> {
                            boolean v = BoolArgumentType.getBool(ctx, "value");
                            setter.accept(v);
                            send(ctx, displayName + " set to ", v + " (diagnostic, not saved)", true);
                            return 1;
                        }));
    }

    // ------------------------------------------------------------------
    // messaging helpers — same shape as ConfigCommand.sendMessage
    // ------------------------------------------------------------------

    /**
     * Reservation state plus the per-level spawnable-chunk count — the number
     * the mob cap is scaled by, kept in the report as the one-command health
     * check for the whole spawn path (it reads the live proximity tracker
     * through the same getNaturalSpawnChunkCount the cap uses).
     */
    private static void localCapReserveReport(CommandContext<CommandSourceStack> ctx) {
        send(ctx, "local mob-cap pack reservation: ",
                FabroliaConfig.fabroliaSpawnLocalCapReservation
                        ? "ON — chunk-pass approval atomically reserves the pack's slots; packs "
                          + "complete like vanilla, parallel overshoot ≈ cap + one pack"
                        : "off (vanilla plain approval) — packs complete, but parallel chunk "
                          + "passes can overshoot the local cap again (task #32 cod runaway)",
                false);
        StringBuilder sb = new StringBuilder(120);
        var server = ctx.getSource().getServer();
        for (net.minecraft.server.level.ServerLevel sl : server.getAllLevels()) {
            int spawnable;
            try {
                spawnable = sl.getChunkSource().chunkMap.getDistanceManager().getNaturalSpawnChunkCount();
            } catch (Throwable t) {
                spawnable = -1;
            }
            sb.append(sb.isEmpty() ? "" : "\n").append("  ").append(sl.dimension().identifier())
              .append(": spawnableChunks=").append(spawnable);
            if (spawnable >= 0) {
                // Vanilla scales every category cap by chunks/289.
                sb.append(" (cap scale x").append(String.format(java.util.Locale.ROOT, "%.2f",
                        spawnable / 289.0)).append(')');
            }
        }
        send(ctx, "spawnable chunks: ", sb.toString(), false);
        send(ctx, "measure with: ", "/fabrolia regions debug spawnrate (checks vs accepts per category;"
                + " the first call starts recording)", false);
    }

    private static void send(CommandContext<CommandSourceStack> ctx, String head, String highlight, boolean broadcast) {
        MutableComponent message = prefix.copy()
                .append(Component.literal(head).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(highlight).withStyle(ChatFormatting.GREEN));
        if (broadcast) {
            ctx.getSource().sendSuccess(() -> message, true);
        } else {
            // Read-only replies: sendSuccess is dropped for players while the
            // sendCommandFeedback gamerule is off.
            ctx.getSource().sendSystemMessage(message);
        }
    }

    private static void send(CommandContext<CommandSourceStack> ctx, String head, String highlight, String suffix) {
        MutableComponent message = prefix.copy()
                .append(Component.literal(head).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(highlight).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(suffix).withStyle(ChatFormatting.WHITE));
        ctx.getSource().sendSuccess(() -> message, true);
    }

    // ------------------------------------------------------------------
    // status / list / dump / show
    // ------------------------------------------------------------------

    /** F1 islands: per-dimension component counts + the biggest islands. */
    private static void islands(CommandSourceStack src) {
        if (!RegionScheduler.isActive()) {
            src.sendSuccess(() -> prefix.copy()
                    .append(Component.literal("Region scheduler dormant").withStyle(ChatFormatting.GRAY)), false);
            return;
        }
        MinecraftServer server = src.getServer();
        MutableComponent msg = prefix.copy().append(Component.literal("Islands:").withStyle(ChatFormatting.WHITE));
        for (ServerLevel lvl : server.getAllLevels()) {
            RegionizedServer rs = RegionScheduler.forLevel(lvl);
            if (rs == null) continue;
            List<RegionizedServer.Island> islands = rs.islands();
            if (islands.isEmpty()) continue;
            long singletons = islands.stream().filter(i -> i.members().size() == 1).count();
            msg.append(Component.literal("\n " + lvl.dimension().identifier() + ": ")
                            .withStyle(ChatFormatting.AQUA))
               .append(Component.literal(islands.size() + " islands (" + singletons + " singleton)")
                            .withStyle(ChatFormatting.WHITE));
            final int CHUNKS_PER_REGION_MAX = 64;  // 8x8 grid
            islands.stream()
                    .sorted(Comparator.comparingLong(RegionizedServer.Island::entityLoad).reversed())
                    .limit(5)
                    .forEach(i -> {
                        // Cap per-region count at theoretical max — fullChunkCount
                        // is a running atomic that can drift positive over long
                        // sessions (region recreate without paired decrement).
                        // Display-side cap keeps the output sensible without
                        // touching the counter (which is also used as a "is
                        // populated" boolean elsewhere via >0).
                        int chunks = 0;
                        for (Region r : i.members()) {
                            chunks += Math.min(CHUNKS_PER_REGION_MAX, Math.max(0, r.fullChunkCount.get()));
                        }
                        final int fc = chunks;
                        msg.append(Component.literal(
                                "\n  #" + i.id() + ": " + i.members().size() + " regions, "
                                        + fc + " chunks, " + i.entityLoad() + " entities")
                                .withStyle(ChatFormatting.GRAY));
                    });
        }
        msg.append(Component.literal("\n separation: Chebyshev>=3 regions (>=16-chunk void)")
                .withStyle(ChatFormatting.DARK_GRAY));
        src.sendSuccess(() -> msg, false);
    }

    private static final int FALLBACK_TOP = 10;

    /** {@code regions debug fallbacks}: A5 Main handoffs, Main fallbacks, fences and Main waits by cause. */
    private static MutableComponent fallbackReport() {
        InfoText out = InfoText.titled("Main fallbacks by cause");
        fallbackSection(out, "Island-to-Main handoffs", MainFallbackStats.HANDOFFS);
        fallbackSection(out, "Main entity-tick fallbacks", MainFallbackStats.FALLBACKS);
        fallbackSection(out, "All-island fences", MainFallbackStats.FENCES);
        waitSection(out, "Main wait: all-island fences (quiesceAllExcept)",
                MainFallbackStats.FENCE_WAITS, true);
        waitSection(out, "Main wait: contended island claims (mainClaim)",
                MainFallbackStats.CLAIM_WAITS, false);
        waitSection(out, "Main wait: blocking island-bucket drains (waitForRemaining)",
                MainFallbackStats.BUCKET_WAITS, false);
        out.note("Wait = time Main spent blocked in the call, including the services it ran"
                + " while spinning; a fence's hold = time it kept every island stopped."
                + " Sections can nest: do not add them up.");
        out.note("Not covered: pool pause (pauseExactTurnAndAwait), per-level quiesceAllIslands,"
                + " domain-rebase and sprint claim waits.");
        out.note("Counted over the last " + String.format(Locale.ROOT, "%.0f",
                MainFallbackStats.windowNanos() / 1e9) + " s (since start or the last reset);"
                + " top " + FALLBACK_TOP + " per section.");
        out.more("/fabrolia regions debug fallbacks reset");
        return out.build();
    }

    private static void waitSection(InfoText out, String name, MainFallbackStats.WaitLedger ledger,
                                    boolean hold) {
        String totals = InfoText.count(ledger.events()) + ", wait " + ms(ledger.waitNanos())
                + (hold ? ", hold " + ms(ledger.holdNanos()) : "");
        out.section(name + " (" + totals + ")");
        List<MainFallbackStats.WaitEntry> top = ledger.top(FALLBACK_TOP);
        if (top.isEmpty()) {
            out.note("none");
            return;
        }
        for (MainFallbackStats.WaitEntry e : top) {
            out.line(InfoText.text(e.caller(), ChatFormatting.GRAY));
            out.detail(InfoText.text(InfoText.count(e.events()) + "x, wait " + ms(e.waitNanos())
                    + " (avg " + ms(e.waitNanos() / e.events()) + ", max " + ms(e.maxWaitNanos()) + ")"
                    + (hold ? ", hold " + ms(e.holdNanos()) : ""), ChatFormatting.WHITE));
        }
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.1f ms", nanos / 1e6);
    }

    private static void fallbackSection(InfoText out, String name,
                                        MainFallbackStats.CauseCounter counter) {
        out.section(name + " (" + InfoText.count(counter.total()) + ")");
        List<MainFallbackStats.Entry> top = counter.top(FALLBACK_TOP, MainFallbackStats::describe);
        if (top.isEmpty()) {
            out.note("none");
            return;
        }
        for (MainFallbackStats.Entry e : top) {
            out.line(e.cause(), InfoText.text(InfoText.count(e.count()), ChatFormatting.WHITE));
        }
    }

    private static void summary(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        boolean active = RegionScheduler.isActive();
        InfoText out = InfoText.titled("Fabrolia status");
        out.line("Region threading", active
                ? InfoText.join(InfoText.onOff(true),
                        InfoText.text(InfoText.plural(RegionScheduler.workerCount(), "worker", "workers"),
                                ChatFormatting.WHITE))
                : InfoText.onOff(false));
        out.line("Async islands", InfoText.onOff(active && RegionScheduler.anyAsyncActive()));
        out.line("Players tick on their island", InfoText.onOff(FabroliaConfig.enablePlayerTickActor));
        out.line("Island entities", InfoText.text(FabroliaConfig.islandEntityParallel
                ? "parallel" : "vanilla order (serial)", ChatFormatting.WHITE));
        out.line("Main thread", mainTick(server));
        if (!active) {
            out.note("Region threading is off: the whole server ticks on the main thread.");
            out.more("/fabrolia regions on", "/fabrolia stats");
            src.sendSystemMessage(out.build());
            return;
        }

        int islands = 0, lagging = 0, stalled = 0;
        RegionizedServer slowestLevel = null;
        com.axalotl.fabrolia.common.regions.IslandRegistry.PIsland slowest = null;
        long slowestNanos = -1;
        long totalRegions = 0, totalEntities = 0;
        out.section("Dimensions");
        for (ServerLevel lvl : server.getAllLevels()) {
            RegionizedServer rs = RegionScheduler.forLevel(lvl);
            if (rs == null) continue;
            List<Region> regions = rs.snapshot();
            long entities = 0;
            for (Region r : regions) entities += r.entityCount.get();
            int levelIslands = 0;
            if (rs.asyncActive) {
                for (var p : rs.islandRegistry.islands()) {
                    levelIslands++;
                    switch (com.axalotl.fabrolia.common.regions.IslandMetrics.state(rs, p)) {
                        case LAGGING -> lagging++;
                        case STALLED -> stalled++;
                        default -> { }
                    }
                    long ema = p.emaUnitNanos;
                    if (ema > slowestNanos) {
                        slowestNanos = ema;
                        slowest = p;
                        slowestLevel = rs;
                    }
                }
            }
            islands += levelIslands;
            totalRegions += regions.size();
            totalEntities += entities;
            out.line(InfoText.join(
                    InfoText.text(InfoText.dimension(lvl.dimension()), InfoText.dimensionColor(lvl.dimension())),
                    InfoText.text(InfoText.plural(levelIslands, "island", "islands"), ChatFormatting.WHITE),
                    InfoText.text(InfoText.plural(regions.size(), "region", "regions"), ChatFormatting.WHITE),
                    InfoText.text(InfoText.plural(entities, "entity", "entities"), ChatFormatting.WHITE)));
        }
        out.line("Total", InfoText.join(
                InfoText.text(InfoText.plural(totalRegions, "region", "regions"), ChatFormatting.WHITE),
                InfoText.text(InfoText.plural(totalEntities, "entity", "entities"), ChatFormatting.WHITE)));

        if (RegionScheduler.anyAsyncActive()) {
            out.section("Islands");
            out.line("Count", InfoText.join(
                    InfoText.text(InfoText.count(islands), ChatFormatting.WHITE),
                    InfoText.text(lagging + " lagging", lagging > 0 ? ChatFormatting.YELLOW : ChatFormatting.GREEN),
                    InfoText.text(stalled + " stalled", stalled > 0 ? ChatFormatting.RED : ChatFormatting.GREEN)));
            if (slowest != null) {
                out.line("Slowest", islandName(slowestLevel, slowest));
                out.detail(islandFigures(slowestLevel, slowest));
            }
        }
        out.more("/fabrolia regions top", "/fabrolia regions list", "/fabrolia stats entity");
        src.sendSystemMessage(out.build());
    }

    /** "20.0 TPS · 4.8 ms" for the server thread, against its tick rate. */
    private static MutableComponent mainTick(MinecraftServer server) {
        var rate = server.tickRateManager();
        return InfoText.join(
                InfoText.tps(com.axalotl.fabrolia.common.regions.TpsHud.mainTpsValue(), rate.tickrate()),
                InfoText.mspt(server.getAverageTickTimeNanos() / 1_000_000.0,
                        rate.millisecondsPerTick(), ""));
    }

    /** Island label plus its dimension; hover shows the handle for rate/sprint. */
    private static MutableComponent islandName(RegionizedServer rs, com.axalotl.fabrolia.common.regions.IslandRegistry.PIsland p) {
        MutableComponent name = InfoText.hover(
                InfoText.text(com.axalotl.fabrolia.common.regions.IslandMetrics.label(rs, p), ChatFormatting.WHITE),
                "handle " + com.axalotl.fabrolia.common.regions.IslandMetrics.handle(rs, p) + " · island #" + p.id + " gen " + p.gen
                        + clockNote(rs, p));
        return name.append(InfoText.text(" (" + InfoText.dimension(rs.level.dimension()) + ")",
                InfoText.dimensionColor(rs.level.dimension())));
    }

    /** Hover line for the island clock's offset to the world clock, if any. */
    private static String clockNote(RegionizedServer rs, com.axalotl.fabrolia.common.regions.IslandRegistry.PIsland p) {
        long behind = com.axalotl.fabrolia.common.regions.IslandMetrics.ticksBehind(rs, p);
        return behind > 0
                ? "\nIsland clock: " + InfoText.plural(behind, "tick", "ticks") + " behind the world clock"
                  + "\n(ticks lost to past slow moments; not current lag)"
                : "";
    }

    /** The island's TPS, or "paused" while the game is frozen. */
    private static MutableComponent islandTps(RegionizedServer rs, com.axalotl.fabrolia.common.regions.IslandRegistry.PIsland p) {
        return com.axalotl.fabrolia.common.regions.IslandMetrics.state(rs, p) == com.axalotl.fabrolia.common.regions.IslandMetrics.State.PAUSED
                ? InfoText.text("paused", ChatFormatting.GRAY)
                : InfoText.tps(com.axalotl.fabrolia.common.regions.IslandMetrics.actualTps(p), com.axalotl.fabrolia.common.regions.IslandMetrics.targetTps(rs, p));
    }

    /** {@code regions here} / {@code regions player <name>}: the TPS of the place a
     *  player stands, whether an island or the main thread runs it, and why. */
    private static int islandHere(CommandSourceStack src, ServerPlayer target) {
        ServerPlayer pl = target;
        if (pl == null) {
            try {
                pl = src.getPlayerOrException();
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                src.sendFailure(prefix.copy().append(Component.literal(
                        "Run this as a player, or use /fabrolia regions player <name>")
                        .withStyle(ChatFormatting.RED)));
                return 0;
            }
        }
        MinecraftServer server = src.getServer();
        ServerLevel lvl = pl.level();
        InfoText out = InfoText.titled(target == null ? "TPS where you are"
                : "TPS where " + pl.getName().getString() + " is");
        RegionizedServer rs = RegionScheduler.forLevel(lvl);
        boolean async = rs != null && rs.asyncActive;
        var isl = async ? rs.islandOwnerOfChunk(pl.chunkPosition().pack()) : null;
        if (isl == null) {
            out.line("Ticked by", InfoText.join(
                    InfoText.text("main thread", ChatFormatting.WHITE),
                    InfoText.text(InfoText.dimension(lvl.dimension()), InfoText.dimensionColor(lvl.dimension()))));
            out.line("TPS here", mainTick(server));
            out.note(async
                    ? "No island owns this spot, so the main thread ticks it at Main TPS."
                    : "Async islands are off: the whole dimension ticks at Main TPS.");
        } else {
            out.line("Island", islandName(rs, isl));
            out.line("TPS here", islandFigures(rs, isl));
            java.util.List<ServerPlayer> players = com.axalotl.fabrolia.common.regions.IslandMetrics.playersOn(rs, isl);
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < players.size() && i < 8; i++) {
                if (i > 0) names.append(", ");
                names.append(players.get(i).getName().getString());
            }
            if (players.size() > 8) names.append(" +").append(players.size() - 8);
            out.line("Players on it", InfoText.text(names.isEmpty() ? "none" : names.toString(),
                    ChatFormatting.WHITE));
            String coupling = com.axalotl.fabrolia.common.regions.IslandMetrics.couplingSummary(isl);
            if (coupling != null) {
                out.line("Portal link", InfoText.text(coupling, ChatFormatting.WHITE));
            }
            out.line("Main thread", mainTick(server));
            out.note("This island ticks on its own thread: its TPS is the speed of everything here");
            out.note("(farms, redstone, mobs). Main TPS covers the rest and the global systems.");
        }
        out.more("/fabrolia regions tps", "/fabrolia regions top");
        src.sendSystemMessage(out.build());
        return 1;
    }

    /** "Healthy · 19.9 TPS · 3.2 ms avg · 12.4 ms max · 14 cells", then lag and pacing notes. */
    private static MutableComponent islandFigures(RegionizedServer rs, com.axalotl.fabrolia.common.regions.IslandRegistry.PIsland p) {
        double budgetMs = 1000.0 / com.axalotl.fabrolia.common.regions.IslandMetrics.targetTps(rs, p);
        MutableComponent out = InfoText.join(
                InfoText.state(com.axalotl.fabrolia.common.regions.IslandMetrics.state(rs, p)),
                islandTps(rs, p),
                InfoText.mspt(p.emaUnitNanos / 1_000_000.0, budgetMs, " avg"),
                InfoText.mspt(p.maxUnitNanos / 1_000_000.0, budgetMs, " max"),
                InfoText.text(InfoText.plural(p.cells.size(), "cell", "cells"), ChatFormatting.GRAY));
        String tag = com.axalotl.fabrolia.common.regions.IslandMetrics.pacingTag(rs, p);
        if (tag != null) {
            out.append(InfoText.text(" · " + tag, ChatFormatting.LIGHT_PURPLE));
        }
        return out;
    }

    private static void list(CommandSourceStack src) {
        if (requireActive(src)) return;
        RegionGrid grid = RegionScheduler.grid();
        int blockShift = (grid != null ? grid.shift : RegionGrid.DEFAULT_SHIFT) + 4;

        for (ServerLevel lvl : src.getServer().getAllLevels()) {
            RegionizedServer rs = RegionScheduler.forLevel(lvl);
            if (rs == null) continue;
            List<Region> regions = rs.snapshot();
            regions.removeIf(r -> r.entityCount.get() == 0 && r.queue().isEmpty());
            if (regions.isEmpty()) continue;
            java.util.IdentityHashMap<Object, Long> entK = new java.util.IdentityHashMap<>();
            for (Region r : regions) entK.put(r, r.entityCount.get());
            regions.sort((a, b) -> Long.compare(entK.get(b), entK.get(a)));

            int shown = Math.min(LIST_TOP, regions.size());
            MutableComponent header = prefix.copy()
                    .append(Component.literal(lvl.dimension().identifier().toString()).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" — top " + shown + " of " + regions.size()
                            + " active regions by entities:").withStyle(ChatFormatting.GRAY));
            src.sendSuccess(() -> header, false);

            for (int i = 0; i < shown; i++) {
                Region r = regions.get(i);
                int rx = grid != null ? grid.regionX(r.gridKey) : 0;
                int rz = grid != null ? grid.regionZ(r.gridKey) : 0;
                Thread worker = r.currentWorker();
                long entities = r.entityCount.get();

                MutableComponent line = Component.literal("  #" + r.id).withStyle(ChatFormatting.DARK_AQUA)
                        .append(Component.literal("  cell[" + rx + "," + rz + "]").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(" @ " + (rx << blockShift) + "," + (rz << blockShift))
                                .withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal("  " + entities + " ent").withStyle(ChatFormatting.GOLD))
                        .append(Component.literal("  q" + r.queue().size()).withStyle(ChatFormatting.GRAY))
                        .append(worker != null
                                ? Component.literal("  \u2699 " + worker.getName()).withStyle(ChatFormatting.GREEN)
                                : Component.empty());
                src.sendSuccess(() -> line, false);
            }
        }
    }

    private static void dump(CommandSourceStack src) {
        if (requireActive(src)) return;
        for (ServerLevel lvl : src.getServer().getAllLevels()) {
            RegionizedServer rs = RegionScheduler.forLevel(lvl);
            if (rs == null) continue;
            for (Region r : rs.snapshot()) {
                long[] chunks = r.snapshotObservedChunks();
                StringBuilder sb = new StringBuilder();
                int max = Math.min(chunks.length, 8);
                for (int i = 0; i < max; i++) {
                    long pc = chunks[i];
                    if (i > 0) sb.append(", ");
                    sb.append('(').append((int) pc).append(',').append((int) (pc >> 32)).append(')');
                }
                if (chunks.length > max) sb.append(", …+").append(chunks.length - max);
                MutableComponent line = Component.literal(
                        "  " + lvl.dimension().identifier() + " #" + r.id
                                + " key=" + Long.toHexString(r.gridKey)
                                + " ent=" + r.entityCount.get()
                                + " chunks=" + chunks.length
                                + " [" + sb + "]").withStyle(ChatFormatting.GRAY);
                src.sendSuccess(() -> line, false);
            }
        }
    }

    private static void show(CommandSourceStack src) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        boolean on = RegionViz.toggle(player);
        MutableComponent msg = prefix.copy().append(Component.literal(
                on ? "Region boundary particles ON — red dust walls = grid lines, flame columns = cell corners (radius 96)"
                   : "Region boundary particles OFF")
                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        src.sendSuccess(() -> msg, false);
    }

    private static boolean requireActive(CommandSourceStack src) {
        if (RegionScheduler.isActive()) return false;
        src.sendFailure(prefix.copy().append(
                Component.literal("Region scheduler dormant — enable with /fabrolia regions on")
                        .withStyle(ChatFormatting.YELLOW)));
        return true;
    }

    /** Physical-Main debug task: atomic entry, then no normal diagnostic tail
     * may cross a peer generation failure selected while Main was parked. */
    private static void fabrolia$runWedgeMainTask(int ms) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            org.slf4j.LoggerFactory.getLogger("fabrolia").warn(
                    "[wedgemain] parking main for {} ms (debug injector)", ms);
            long until = System.nanoTime() + ms * 1_000_000L;
            while (System.nanoTime() < until) {
                java.util.concurrent.locks.LockSupport.parkNanos(20_000L);
            }
        });
        RegionScheduler.runSuccessBodyIfHealthy(() ->
                org.slf4j.LoggerFactory.getLogger("fabrolia").warn(
                        "[wedgemain] released"));
    }

    // ------------------------------------------------------------------
    // runtime toggle for the master region-threading switch
    // ------------------------------------------------------------------

    /**
     * Runtime toggle. Goes live on the NEXT tick — current in-flight ticks finish on
     * their current path. Persisted to config; idempotent.
     */
    private static void setEnabled(CommandSourceStack src, boolean enable) {
        if (!com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                .isPhysicalServerThread(src.getServer())) {
            src.sendFailure(prefix.copy().append(Component.literal(
                    "Region threading may only be changed on the server thread")
                    .withStyle(ChatFormatting.RED)));
            return;
        }
        if (!com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                .schedulerLifecycleTransitionAllowed(src.getServer())) {
            src.sendFailure(prefix.copy().append(Component.literal(
                    "Region threading cannot change inside an open server-tick phase; retry next tick")
                    .withStyle(ChatFormatting.RED)));
            return;
        }
        if (RegionScheduler.deferLifecycleTransitionFromCurrentBody(
                src.getServer(), () -> setEnabled(src, enable))) {
            // The current command is itself represented by a pool ExternalPermit.
            // Its raw Main baton begins only after this complete command stack
            // returns and retires that permit; stop() can then await every older
            // obligation without waiting for itself.
            return;
        }
        if (enable && FabroliaConfig.disabled) {
            // Workers would start with every Fabrolia guard switched off (audit D-02).
            src.sendFailure(prefix.copy().append(Component.literal(
                    "Fabrolia is disabled; run /fabrolia config toggle first")
                    .withStyle(ChatFormatting.RED)));
            return;
        }
        boolean previous = FabroliaConfig.enableRegionThreading;
        if (previous == enable) {
            MutableComponent msg = prefix.copy().append(Component.literal(
                    "Region threading already " + (enable ? "ON" : "OFF"))
                    .withStyle(ChatFormatting.YELLOW));
            src.sendSuccess(() -> msg, false);
            return;
        }

        FabroliaConfig.enableRegionThreading = enable;
        try {
            PlatformUtils.saveConfig();
        } catch (Throwable t) {
            Throwable winner = RegionScheduler.publishFatalAndSelectWinner(t);
            FabroliaConfig.enableRegionThreading = previous;
            com.axalotl.fabrolia.common.regions.IslandCommands
                    .publishAndPropagate(winner);
            throw new AssertionError("fatal config publication returned");
        }

        Throwable transitionFailure = null;
        try {
            if (enable) {
                RegionScheduler.start(src.getServer());
                if (!RegionScheduler.isActive()) {
                    throw new IllegalStateException(
                            "region scheduler start returned without an active runtime");
                }
            } else {
                com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                        .setEnabled(src.getServer(), false);
                RegionScheduler.stop();
                if (RegionScheduler.isActive()) {
                    throw new IllegalStateException(
                            "region scheduler stop returned with an active runtime");
                }
            }
        } catch (Throwable failure) {
            // start/stop may already have entered a runtime transition. Select its
            // exact W before any fallible preference rollback is attempted.
            transitionFailure = RegionScheduler.publishFatalAndSelectWinner(failure);
        }
        if (transitionFailure != null) {
            // Persistence rollback is mandatory cleanup only. A failed runtime
            // transition is never retried/re-enabled and never reported as a
            // recoverable command result: the exact failure stops this generation.
            FabroliaConfig.enableRegionThreading = previous;
            try {
                PlatformUtils.saveConfig();
            } catch (Throwable rollbackFailure) {
                transitionFailure = FailStops.mergeFailure(
                        transitionFailure, rollbackFailure);
            }
            com.axalotl.fabrolia.common.regions.IslandCommands
                    .publishAndPropagate(transitionFailure);
            throw new AssertionError("fatal region transition publication returned");
        }

        boolean nowActive = RegionScheduler.isActive();
        MutableComponent ok = prefix.copy()
                .append(Component.literal("Region threading ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(enable ? "ENABLED" : "DISABLED")
                        .withStyle(enable ? ChatFormatting.GREEN : ChatFormatting.GOLD))
                .append(Component.literal(" — scheduler " + (nowActive ? "active" : "dormant")
                        + " (next tick takes effect)").withStyle(ChatFormatting.GRAY));
        src.sendSuccess(() -> ok, true);
    }

    // ---- temporal control / profiling (2026-07-14) ----------------------

    /** Every async island, grouped by dimension, heaviest first. */
    private static int islandList(net.minecraft.commands.CommandSourceStack src) {
        InfoText out = InfoText.titled("Islands");
        int n = 0;
        for (net.minecraft.server.level.ServerLevel lvl : src.getServer().getAllLevels()) {
            var rs = com.axalotl.fabrolia.common.regions.RegionScheduler.forLevel(lvl);
            if (rs == null || !rs.asyncActive) continue;
            var islands = sortedIslands(rs, "mspt");
            if (islands.isEmpty()) continue;
            out.section(InfoText.dimension(lvl.dimension()) + " (" + islands.size() + ")");
            for (var p2 : islands) {
                // One line per island so large servers fit in the chat history;
                // the full figures are on hover and in /fabrolia regions top.
                out.line(islandName(rs, p2).append(InfoText.text(" · ", ChatFormatting.DARK_GRAY))
                        .append(InfoText.hover(islandTps(rs, p2), islandFigures(rs, p2).getString()))
                        .append(InfoText.text(" · ", ChatFormatting.DARK_GRAY))
                        .append(InfoText.mspt(p2.emaUnitNanos / 1_000_000.0,
                                1000.0 / com.axalotl.fabrolia.common.regions.IslandMetrics.targetTps(rs, p2), " avg")));
                n++;
            }
            String pacing = pacingSessions(rs);
            if (pacing != null) out.note(pacing);
        }
        if (n == 0) {
            out.line(InfoText.text("No async islands are running (async islands off?).", ChatFormatting.GRAY));
            out.more("/fabrolia regions");
        } else {
            out.note("Hover a name for its handle, the TPS for all figures.");
            out.more("/fabrolia regions top", "/fabrolia regions top verbose");
        }
        src.sendSystemMessage(out.build());
        return n;
    }

    /** The level's islands sorted by a snapshot key (never by live fields:
     *  TimSort contract, RegionizedServer:1528 crash, 2026-07-17). */
    private static List<com.axalotl.fabrolia.common.regions.IslandRegistry.PIsland> sortedIslands(RegionizedServer rs, String sort) {
        var list = new java.util.ArrayList<>(rs.islandRegistry.islands());
        java.util.IdentityHashMap<Object, Long> key = new java.util.IdentityHashMap<>();
        for (var p2 : list) key.put(p2, sortKey(p2, sort));
        boolean asc = "tps".equals(sort); // lowest tps first = the problems
        list.sort((a, b) -> asc ? Long.compare(key.get(a), key.get(b))
                : Long.compare(key.get(b), key.get(a)));
        return list;
    }

    private static long sortKey(com.axalotl.fabrolia.common.regions.IslandRegistry.PIsland p2, String sort) {
        return switch (sort) {
            case "maxmspt" -> p2.maxUnitNanos;
            case "lag" -> p2.lagNanos;
            case "tps" -> {
                // Not yet measured sorts last, not as "0 TPS" first.
                double tps = com.axalotl.fabrolia.common.regions.IslandMetrics.actualTps(p2);
                yield Double.isNaN(tps) ? Long.MAX_VALUE : (long) (tps * 1000);
            }
            default -> p2.emaUnitNanos; // mspt
        };
    }

    /** The level's active rate/sprint/pin sessions as one line, or null. */
    private static String pacingSessions(RegionizedServer rs) {
        StringBuilder sb = new StringBuilder();
        for (var sn : rs.temporalSessions) {
            sb.append(sb.isEmpty() ? "Pacing sessions: " : "; ").append(sn.label);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /** Debug control: applied live at the next level-tick HEAD (the per-level
     *  mode latch — between drives, forced rebuild before any dispatch stamps).
     *  Not persisted; a restart returns to the -Dfabrolia.syncMode default. */
    private static int setAsyncIslands(
            com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx,
            boolean turningOn) {
        if (!com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                .isPhysicalServerThread(ctx.getSource().getServer())) {
            send(ctx, "async islands: ",
                    "REFUSED — scheduler modes may only change on the server thread", false);
            return 0;
        }
        if (turningOn && (!FabroliaConfig.regionEventScheduler || !FabroliaConfig.useRegionColorGate())) {
            send(ctx, "async islands: ", "requires the event scheduler + color gate (both on by default)", false);
            return 0;
        }
        FabroliaConfig.asyncIslands = turningOn;
        send(ctx, "async islands: ", (turningOn ? "ON" : "OFF")
                + " (applies at next tick; not saved)", true);
        return 1;
    }

    private static int setPlayerEntityTicks(
            com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx,
            boolean turningOn) {
        MinecraftServer server = ctx.getSource().getServer();
        if (!com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                .isPhysicalServerThread(server)) {
            send(ctx, "regional player entity ticks v2: ",
                    "REFUSED — mode transitions may only run on the server thread", false);
            return 0;
        }
        boolean previousPreference = FabroliaConfig.enablePlayerTickActor;
        String refusal = turningOn
                ? com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                        .refusalReason(server)
                : null;
        if (refusal != null) {
            send(ctx, "regional player entity ticks v2: ",
                    "REFUSED — " + refusal, false);
            return 0;
        }

        FabroliaConfig.enablePlayerTickActor = turningOn;
        try {
            PlatformUtils.saveConfig();
        } catch (Throwable persistenceFailure) {
            Throwable winner = RegionScheduler.publishFatalAndSelectWinner(
                    persistenceFailure);
            FabroliaConfig.enablePlayerTickActor = previousPreference;
            com.axalotl.fabrolia.common.regions.IslandCommands
                    .publishAndPropagate(winner);
            throw new AssertionError("fatal player-config publication returned");
        }

        Throwable transitionFailure = null;
        try {
            if (!com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                    .setEnabled(server, turningOn)) {
                throw new IllegalStateException(
                        "player-tick runtime refused after its prerequisites were validated");
            }
        } catch (Throwable failure) {
            // setEnabled may have partially transferred player ownership. Publish
            // the exact transition W before fallible persisted-metadata cleanup.
            transitionFailure = RegionScheduler.publishFatalAndSelectWinner(failure);
        }
        if (transitionFailure != null) {
            // Restore only persisted preference metadata. Never retry or reverse a
            // runtime transition after its body failed: that would continue from an
            // unknown partial ownership state.
            FabroliaConfig.enablePlayerTickActor = previousPreference;
            try {
                PlatformUtils.saveConfig();
            } catch (Throwable cleanup) {
                transitionFailure = FailStops.mergeFailure(transitionFailure, cleanup);
            }
            com.axalotl.fabrolia.common.regions.IslandCommands
                    .publishAndPropagate(transitionFailure);
            throw new AssertionError("fatal player transition publication returned");
        }
        boolean asyncVenue = com.axalotl.fabrolia.common.regions.RegionScheduler.anyAsyncActive()
                || FabroliaConfig.asyncIslands;
        send(ctx, "regional player entity ticks: ", turningOn
                ? (asyncVenue
                    ? "ON — supported real players and exact Carpet fake players are owned by their autonomous island; "
                      + "island-safe packets, entity tick and real listener/tickPlayer use the island FIFO; block use runs on Main; "
                      + "saved and restored automatically after restart"
                    : "ON — async is currently off, so players tick on physical main; "
                      + "switching async on changes venue at the next safe island boundary; saved for restart")
                : "off — island player ownership is disabled; "
                  + "players return to MAIN_SYNC at the next safe boundary; saved for restart",
                true);
        return 1;
    }

    private static int islandTop(net.minecraft.commands.CommandSourceStack src, int count,
                                 String sort, boolean verbose) {
        if (!java.util.Set.of("mspt", "maxmspt", "lag", "tps").contains(sort)) {
            src.sendFailure(prefix.copy().append(Component.literal(
                    "Unknown sort '" + sort + "': use mspt, maxmspt, lag or tps").withStyle(ChatFormatting.RED)));
            return 0;
        }
        java.util.ArrayList<Object[]> rows = new java.util.ArrayList<>();
        for (net.minecraft.server.level.ServerLevel lvl : src.getServer().getAllLevels()) {
            var rs = com.axalotl.fabrolia.common.regions.RegionScheduler.forLevel(lvl);
            if (rs == null || !rs.asyncActive) continue;
            for (var p2 : rs.islandRegistry.islands()) rows.add(new Object[]{rs, p2});
        }
        if (rows.isEmpty()) {
            InfoText none = InfoText.titled("Islands")
                    .line(InfoText.text("No async islands are running (async islands off?).", ChatFormatting.GRAY))
                    .more("/fabrolia regions");
            src.sendSystemMessage(none.build());
            return 0;
        }
        // Sort keys are SNAPSHOTS (TimSort contract: never compare live
        // volatile fields — RegionizedServer:1528 crash, 2026-07-17).
        java.util.IdentityHashMap<Object, Long> key = new java.util.IdentityHashMap<>();
        for (Object[] row : rows) key.put(row, sortKey((com.axalotl.fabrolia.common.regions.IslandRegistry.PIsland) row[1], sort));
        boolean asc = "tps".equals(sort); // lowest tps first = the problems
        rows.sort((a, b) -> asc ? Long.compare(key.get(a), key.get(b))
                : Long.compare(key.get(b), key.get(a)));
        int shown = Math.min(count, rows.size());
        if (verbose) {
            // Diagnostic form, one plain line per island: the stand drivers and the
            // analysis docs read these rows (" L=", "island#id/gen", "[handle]").
            for (int rank = 1; rank <= shown; rank++) {
                Object[] row = rows.get(rank - 1);
                src.sendSystemMessage(Component.literal(com.axalotl.fabrolia.common.regions.IslandMetrics.row(
                        (RegionizedServer) row[0], (com.axalotl.fabrolia.common.regions.IslandRegistry.PIsland) row[1], rank)));
            }
            return shown;
        }
        String by = switch (sort) {
            case "maxmspt" -> "max MSPT";
            case "lag" -> "lag";
            case "tps" -> "lowest TPS";
            default -> "average MSPT";
        };
        InfoText out = InfoText.titled("Top " + shown + " of " + rows.size() + " islands by " + by);
        boolean anyLagging = false;
        for (int rank = 1; rank <= shown; rank++) {
            Object[] row = rows.get(rank - 1);
            var rs = (RegionizedServer) row[0];
            var p2 = (com.axalotl.fabrolia.common.regions.IslandRegistry.PIsland) row[1];
            anyLagging |= com.axalotl.fabrolia.common.regions.IslandMetrics.state(rs, p2) == com.axalotl.fabrolia.common.regions.IslandMetrics.State.LAGGING;
            out.line(InfoText.text(rank + ". ", ChatFormatting.GRAY).append(islandName(rs, p2)));
            out.detail(islandFigures(rs, p2));
        }
        out.note("Healthy = within 10 % of its target TPS (20); Lagging = below that.");
        if (anyLagging) {
            out.note("A lagging island slows only itself: everything on it runs at its TPS.");
        }
        out.more("/fabrolia regions top 10 tps", "/fabrolia regions top verbose");
        src.sendSystemMessage(out.build());
        return shown;
    }

    public static int tickRateIslandHere(
            com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx) {
        return islandRateHere(ctx.getSource(),
                com.mojang.brigadier.arguments.FloatArgumentType.getFloat(ctx, "tps"));
    }

    public static int tickSprintIslandHere(
            com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx) {
        return islandSprintHere(ctx.getSource(),
                com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "ticks"));
    }

    private static Object[] islandByPlayer(net.minecraft.commands.CommandSourceStack src, String name) {
        var pl = src.getServer().getPlayerList().getPlayerByName(name);
        if (pl == null || !(pl.level() instanceof net.minecraft.server.level.ServerLevel lvl)) return null;
        var rs = com.axalotl.fabrolia.common.regions.RegionScheduler.forLevel(lvl);
        var isl = rs == null ? null : rs.islandOwnerOfChunk(pl.chunkPosition().pack());
        return isl == null ? null : new Object[]{rs, isl};
    }

    private static Object[] islandById(net.minecraft.commands.CommandSourceStack src,
                                       String dim, long id, long gen) {
        for (net.minecraft.server.level.ServerLevel lvl : src.getServer().getAllLevels()) {
            if (!lvl.dimension().identifier().toString().equals(dim)
                    && !lvl.dimension().identifier().getPath().equals(dim)) continue;
            var rs = com.axalotl.fabrolia.common.regions.RegionScheduler.forLevel(lvl);
            if (rs == null || !rs.asyncActive) continue; // async off: a sprint/rate here would tick a unit against main's sync tick
            for (var p2 : rs.islandRegistry.islands()) {
                if (p2.id == id && p2.gen == gen) return new Object[]{rs, p2};
            }
        }
        return null;
    }

    private static int applyRate(net.minecraft.commands.CommandSourceStack src, Object[] t, float tps) {
        if (t == null) { src.sendSystemMessage(net.minecraft.network.chat.Component.literal("island not found (id reused? use handle from island list)")); return 0; }
        var rs = (com.axalotl.fabrolia.common.regions.RegionizedServer) t[0];
        var isl = (com.axalotl.fabrolia.common.regions.IslandRegistry.PIsland) t[1];
        long oldEff = com.axalotl.fabrolia.common.regions.TemporalControl.effectiveTargetNanos(rs, isl);
        long genBefore = isl.pacingGen;
        long[] replaced = com.axalotl.fabrolia.common.regions.TemporalControl.replaceRateSessions(rs, isl);
        var sess = com.axalotl.fabrolia.common.regions.TemporalControl.addRateSession(rs, isl,
                tps, "rate:" + com.axalotl.fabrolia.common.regions.IslandMetrics.label(rs, isl));
        long newEff = com.axalotl.fabrolia.common.regions.TemporalControl.effectiveTargetNanos(rs, isl);
        String label = com.axalotl.fabrolia.common.regions.IslandMetrics.label(rs, isl);
        String handle = com.axalotl.fabrolia.common.regions.IslandMetrics.handle(rs, isl);
        src.sendSystemMessage(net.minecraft.network.chat.Component.literal(String.format(java.util.Locale.ROOT,
                "%s [%s] island#%d/gen%d: rate request %.1f tps | effective %.1f -> %.1f tps | %s -> session#%d | pacingGen %d -> %d",
                label, handle, isl.id, isl.gen, tps,
                1.0e9 / oldEff, 1.0e9 / newEff,
                replaced.length == 0 ? "new" : "replaced session#" + java.util.Arrays.toString(replaced),
                sess.id, genBefore, isl.pacingGen)));
        com.axalotl.fabrolia.common.regions.TemporalControl.awaitCommit(rs, isl, src,
                "rate " + tps + " on " + label + " [" + handle + "]");
        return 1;
    }

    private static int applySprint(net.minecraft.commands.CommandSourceStack src, Object[] t, long ticks) {
        if (t == null) { src.sendSystemMessage(net.minecraft.network.chat.Component.literal("island not found (id reused? use handle from island list)")); return 0; }
        var rs = (com.axalotl.fabrolia.common.regions.RegionizedServer) t[0];
        var isl = (com.axalotl.fabrolia.common.regions.IslandRegistry.PIsland) t[1];
        long genBefore = isl.pacingGen;
        var sess = com.axalotl.fabrolia.common.regions.TemporalControl.addSprintSession(rs, isl, ticks, "sprint:" + com.axalotl.fabrolia.common.regions.IslandMetrics.label(rs, isl));
        com.axalotl.fabrolia.common.regions.IslandTickUnit.submitUnit(rs, isl);
        String label = com.axalotl.fabrolia.common.regions.IslandMetrics.label(rs, isl);
        String handle = com.axalotl.fabrolia.common.regions.IslandMetrics.handle(rs, isl);
        src.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                label + " [" + handle + "] island#" + isl.id + "/gen" + isl.gen + ": sprint "
                + ticks + " ticks (session#" + sess.id + ", pacingGen " + genBefore + " -> " + isl.pacingGen + ")"));
        com.axalotl.fabrolia.common.regions.TemporalControl.awaitCommit(rs, isl, src,
                "sprint " + ticks + " on " + label + " [" + handle + "]");
        return 1;
    }

    private static int islandRateHere(net.minecraft.commands.CommandSourceStack src, float tps) {
        net.minecraft.server.level.ServerPlayer pl;
        try {
            pl = src.getPlayerOrException();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            src.sendSystemMessage(net.minecraft.network.chat.Component.literal("player required"));
            return 0;
        }
        var lvl = pl.level();
        var rs = com.axalotl.fabrolia.common.regions.RegionScheduler.forLevel(lvl);
        var isl = rs == null ? null : rs.islandOwnerOfChunk(pl.chunkPosition().pack());
        if (isl == null) {
            src.sendSystemMessage(net.minecraft.network.chat.Component.literal("no island here (GLOBAL)"));
            return 0;
        }
        return applyRate(src, new Object[]{rs, isl}, tps);
    }

    private static int islandSprintHere(net.minecraft.commands.CommandSourceStack src, long ticks) {
        net.minecraft.server.level.ServerPlayer pl;
        try {
            pl = src.getPlayerOrException();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            src.sendSystemMessage(net.minecraft.network.chat.Component.literal("player required"));
            return 0;
        }
        var lvl = pl.level();
        var rs = com.axalotl.fabrolia.common.regions.RegionScheduler.forLevel(lvl);
        var isl = rs == null ? null : rs.islandOwnerOfChunk(pl.chunkPosition().pack());
        if (isl == null) {
            src.sendSystemMessage(net.minecraft.network.chat.Component.literal("no island here (GLOBAL)"));
            return 0;
        }
        return applySprint(src, new Object[]{rs, isl}, ticks);
    }

}
