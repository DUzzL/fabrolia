package com.axalotl.fabrolia.common.commands;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.axalotl.fabrolia.common.platform.PlatformPermission;
import com.axalotl.fabrolia.common.platform.PlatformUtils;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.TpsHud;
import com.axalotl.fabrolia.common.utils.TickStats;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.axalotl.fabrolia.common.ParallelProcessor.getPoolSize;
import static com.axalotl.fabrolia.common.commands.FabroliaCommand.prefix;
import static com.axalotl.fabrolia.common.utils.TickStats.resetEntityTickStats;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class StatsCommand {

    /** Five minutes at 20 TPS. */
    private static final int MAX_RECORDING_TICKS = 6000;

    public static LiteralArgumentBuilder<CommandSourceStack> registerStatus(LiteralArgumentBuilder<CommandSourceStack> root) {
        return root.then(literal("stats").requires(PlatformPermission.require("command.statistics", 0))
                .executes(cmdCtx -> {
                    showGeneralStats(cmdCtx.getSource());
                    return 1;
                })
                .then(literal("entity")
                        .executes(cmdCtx -> {
                            showEntityStats(cmdCtx.getSource(), 10, false, 0);
                            return 1;
                        })
                        .then(argument("count", IntegerArgumentType.integer(1, 100))
                                .executes(cmdCtx -> {
                                    int count = IntegerArgumentType.getInteger(cmdCtx, "count");
                                    showEntityStats(cmdCtx.getSource(), count, false, 0);
                                    return 1;
                                })
                                // Bounded: the stats command is open to every
                                // player, and recording polls on Main until it
                                // ends (an unbounded window never ended).
                                .then(argument("ticks", IntegerArgumentType.integer(1, MAX_RECORDING_TICKS))
                                        .executes(cmdCtx -> {
                                            int count = IntegerArgumentType.getInteger(cmdCtx, "count");
                                            int ticks = IntegerArgumentType.getInteger(cmdCtx, "ticks");
                                            startRecordingAndShow(cmdCtx.getSource(), count, ticks);
                                            return 1;
                                        })))));
    }

    private static void startRecordingAndShow(CommandSourceStack source, int topCount, int ticks) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> TickStats.startRecording(ticks));
        RegionScheduler.runSuccessBodyIfHealthy(() -> source.sendSystemMessage(
                prefix.copy().append(Component.literal(
                        "Recording entity ticks for " + ticks + " ticks (about "
                                + Math.max(1, ticks / 20) + " s)...")
                        .withStyle(ChatFormatting.YELLOW))));
        pollUntilDone(source, topCount, ticks);
    }

    private static void pollUntilDone(CommandSourceStack source, int topCount, int ticks) {
        MinecraftServer server = source.getServer();
        RegionScheduler.runSuccessBodyIfHealthy(() -> server.execute(
                () -> fabrolia$pollUntilDoneEntered(source, topCount, ticks)));
    }

    /** One raw MinecraftServer callback entry.  Its recursive publication is
     * separately admitted by pollUntilDone/showEntityStats, so a peer W cannot
     * create another callback after this stage returns normally. */
    private static void fabrolia$pollUntilDoneEntered(
            CommandSourceStack source, int topCount, int ticks) {
        if (RegionScheduler.callSuccessBooleanIfHealthy(TickStats::isRecording)) {
            pollUntilDone(source, topCount, ticks);
        } else {
            showEntityStats(source, topCount, true, ticks);
        }
    }

    private static void showGeneralStats(CommandSourceStack source) {
        MinecraftServer server = source.getServer();

        int totalEntities = 0;
        for (var world : server.getAllLevels()) {
            for (var entity : world.getAllEntities()) {
                if (entity.isAlive()) {
                    totalEntities++;
                }
            }
        }

        var rate = server.tickRateManager();
        InfoText out = InfoText.titled("Fabrolia stats");
        out.line("Fabrolia", FabroliaConfig.disabled
                ? InfoText.text("disabled", ChatFormatting.RED)
                : InfoText.text("enabled", ChatFormatting.GREEN));
        out.line("Main thread", InfoText.join(
                InfoText.tps(TpsHud.mainTpsValue(), rate.tickrate()),
                InfoText.mspt(server.getAverageTickTimeNanos() / 1_000_000.0, rate.millisecondsPerTick(), "")));
        out.line("Entities", InfoText.text(InfoText.count(totalEntities), ChatFormatting.WHITE));
        out.line("Threads", InfoText.text(String.valueOf(getPoolSize()), ChatFormatting.WHITE));

        out.section("Features");
        // These switches act on Main's lane and the flat (non-island) mode only: island units
        // always spawn and random-tick on their workers, so the labels name the lane.
        out.line("Main-lane spawning", InfoText.onOff(FabroliaConfig.enableFabroliaSpawn));
        out.line("Main-lane random ticks", InfoText.onOff(FabroliaConfig.enableFabroliaRandomTicks));
        out.line("Flat-mode despawn", InfoText.onOff(FabroliaConfig.enableFabroliaDespawn));
        if (PlatformUtils.isModLoaded("vmp")) {
            out.line("VMP tracking", InfoText.onOff(FabroliaConfig.enableFabroliaVmpTracking));
        }
        out.more("/fabrolia regions", "/fabrolia stats entity 10");
        source.sendSystemMessage(out.build());
    }

    private static void showEntityStats(CommandSourceStack source, int topCount, boolean showTickStats, int ticks) {
        MinecraftServer server = source.getServer();
        RegionScheduler.runSuccessBodyIfHealthy(() -> server.execute(
                () -> fabrolia$runEntityStatsTask(
                        server, source, topCount, showTickStats, ticks)));
    }

    /** Atomic entry for the queued scan.  The reset and command reply are
     * successor stages, not part of an already-entered scan body. */
    private static void fabrolia$runEntityStatsTask(
            MinecraftServer server, CommandSourceStack source, int topCount,
            boolean showTickStats, int ticks) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            Map<EntityType<?>, Integer> entityTypeCounts = new HashMap<>();
            Map<EntityType<?>, Boolean> entityTypeFabrolia = new HashMap<>();
            AtomicInteger totalEntities = new AtomicInteger(0);
            AtomicInteger totalFabroliaEntities = new AtomicInteger(0);

            InfoText out = InfoText.titled("Entity stats");
            out.section("Per dimension");

            server.getAllLevels().forEach(world -> {
                AtomicInteger worldCount = new AtomicInteger(0);
                AtomicInteger fabroliaCount = new AtomicInteger(0);

                world.getAllEntities().forEach(entity -> {
                    if (entity.isAlive()) {
                        EntityType<?> entityType = entity.getType();
                        worldCount.incrementAndGet();
                        totalEntities.incrementAndGet();
                        entityTypeCounts.merge(entityType, 1, Integer::sum);

                        // Region mode admits entities through requiresMainThread (RegionizedServer
                        // .tickOneAdmitted); the flat-pool sync policy (blocked classes, config
                        // synchronizedEntities) only applies when regions are off.
                        boolean isFabrolia = !FabroliaConfig.disabled
                                && (RegionScheduler.isActive() && RegionScheduler.forLevel(world) != null
                                        ? !ParallelProcessor.requiresMainThread(entity)
                                        : !ParallelProcessor.shouldTickSynchronously(entity));
                        entityTypeFabrolia.put(entityType, isFabrolia);

                        if (isFabrolia) {
                            fabroliaCount.incrementAndGet();
                            totalFabroliaEntities.incrementAndGet();
                        }
                    }
                });

                out.line(InfoText.text(InfoText.dimension(world.dimension()),
                                InfoText.dimensionColor(world.dimension())),
                        entitySplit(worldCount.get(), fabroliaCount.get()));
            });

            out.line("Total", entitySplit(totalEntities.get(), totalFabroliaEntities.get()));
            out.note("parallel = ticked off the main thread; main = needs the main thread");

            if (showTickStats && ticks > 0) {
                long syncCount = TickStats.getTotalSyncTicks();
                long fabroliaCount = TickStats.getTotalFabroliaTicks();
                double totalMspt = TickStats.getTotalMSPT(ticks);
                double wallMspt = TickStats.getBatchWallMSPT(ticks);
                double fabroliaShare = (syncCount + fabroliaCount) == 0 ? 0.0
                        : (fabroliaCount * 100.0) / (syncCount + fabroliaCount);

                int pool = getPoolSize();
                double speedup = wallMspt <= 0 ? 0.0
                        : (TickStats.TOTAL_SYNC_TIME_NS.get() + TickStats.TOTAL_FABROLIA_TIME_NS.get())
                          / (double) TickStats.TOTAL_BATCH_WALL_NS.get();

                out.section("Recorded " + ticks + " ticks");
                out.line("Entity ticks", InfoText.join(
                        InfoText.text(InfoText.count(syncCount + fabroliaCount), ChatFormatting.WHITE),
                        InfoText.text(InfoText.count(fabroliaCount) + " parallel", ChatFormatting.AQUA),
                        InfoText.text(InfoText.count(syncCount) + " main", ChatFormatting.RED),
                        InfoText.text(String.format(Locale.ROOT, "%.1f%% parallel", fabroliaShare),
                                ChatFormatting.GRAY)));
                out.line("Entity time per tick", InfoText.text(
                        String.format(Locale.ROOT, "%.3f ms", totalMspt), ChatFormatting.GREEN));
                out.line("Wall time per tick", InfoText.join(
                        InfoText.text(String.format(Locale.ROOT, "%.3f ms", wallMspt), ChatFormatting.GREEN),
                        InfoText.text(String.format(Locale.ROOT, "%d threads, %.2fx speedup", pool, speedup),
                                ChatFormatting.GRAY)));
            }

            if (topCount > 0 && !entityTypeCounts.isEmpty()) {
                out.section("Top " + Math.min(topCount, entityTypeCounts.size()) + " entity types");

                final int[] rank = {1};
                entityTypeCounts.entrySet().stream()
                        .sorted(Map.Entry.<EntityType<?>, Integer>comparingByValue().reversed())
                        .limit(topCount)
                        .forEach(entry -> {
                            EntityType<?> type = entry.getKey();
                            int count = entry.getValue();
                            boolean isFabrolia = entityTypeFabrolia.getOrDefault(type, false);

                            Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
                            MutableComponent row = InfoText.text(rank[0] + ". ", ChatFormatting.GRAY)
                                    .append(InfoText.text(id.getPath(), ChatFormatting.WHITE))
                                    .append(InfoText.text(": ", ChatFormatting.GRAY))
                                    .append(InfoText.text(InfoText.count(count), ChatFormatting.WHITE))
                                    .append(InfoText.text(" · ", ChatFormatting.DARK_GRAY))
                                    .append(isFabrolia
                                            ? InfoText.text("parallel", ChatFormatting.AQUA)
                                            : InfoText.text("main", ChatFormatting.RED));

                            if (showTickStats && ticks > 0) {
                                double mspt = TickStats.getMSPTForType(type, ticks);
                                row.append(InfoText.text(" · ", ChatFormatting.DARK_GRAY))
                                        .append(InfoText.text(String.format(Locale.ROOT, "%.3f ms/tick", mspt),
                                                ChatFormatting.GREEN));
                            }
                            out.line(row);

                            rank[0]++;
                        });
            }
            if (!showTickStats) {
                out.more("/fabrolia stats entity 10 200");
                out.note("(the second number records entity tick times for that many ticks)");
            }
            RegionScheduler.runSuccessBodyIfHealthy(() -> resetEntityTickStats());
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> source.sendSystemMessage(out.build()));
        });
    }

    /** "1,234 · 1,200 parallel · 34 main". */
    private static MutableComponent entitySplit(int total, int parallel) {
        return InfoText.join(
                InfoText.text(InfoText.count(total), ChatFormatting.WHITE),
                InfoText.text(InfoText.count(parallel) + " parallel", ChatFormatting.AQUA),
                InfoText.text(InfoText.count(total - parallel) + " main",
                        total - parallel > 0 ? ChatFormatting.RED : ChatFormatting.GRAY));
    }
}
