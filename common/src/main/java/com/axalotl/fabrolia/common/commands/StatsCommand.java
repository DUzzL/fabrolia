package com.axalotl.fabrolia.common.commands;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.axalotl.fabrolia.common.platform.PlatformPermission;
import com.axalotl.fabrolia.common.platform.PlatformUtils;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.axalotl.fabrolia.common.ParallelProcessor.getPoolSize;
import static com.axalotl.fabrolia.common.commands.FabroliaCommand.prefix;
import static com.axalotl.fabrolia.common.utils.TickStats.resetEntityTickStats;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class StatsCommand {

    public static LiteralArgumentBuilder<CommandSourceStack> registerStatus(LiteralArgumentBuilder<CommandSourceStack> root) {
        return root.then(literal("stats").requires(PlatformPermission.require("command.statistics", 0))
                .executes(cmdCtx -> {
                    showGeneralStats(cmdCtx.getSource());
                    return 1;
                })
                .then(literal("entity")
                        .executes(cmdCtx -> {
                            showEntityStats(cmdCtx.getSource(), 0, false, 0);
                            return 1;
                        })
                        .then(argument("count", IntegerArgumentType.integer(1, 100))
                                .executes(cmdCtx -> {
                                    int count = IntegerArgumentType.getInteger(cmdCtx, "count");
                                    showEntityStats(cmdCtx.getSource(), count, false, 0);
                                    return 1;
                                })
                                .then(argument("ticks", IntegerArgumentType.integer())
                                        .executes(cmdCtx -> {
                                            int count = IntegerArgumentType.getInteger(cmdCtx, "count");
                                            int ticks = IntegerArgumentType.getInteger(cmdCtx, "ticks");
                                            startRecordingAndShow(cmdCtx.getSource(), count, ticks);
                                            return 1;
                                        })))));
    }

    private static void startRecordingAndShow(CommandSourceStack source, int topCount, int ticks) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> TickStats.startRecording(ticks));
        RegionScheduler.runSuccessBodyIfHealthy(() -> source.sendSuccess(
                () -> prefix.copy().append(Component.literal(
                        "Recording entity ticks for " + ticks + " ticks...")
                        .withStyle(ChatFormatting.YELLOW)), false));
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

        int threads = getPoolSize();

        boolean enabled = !FabroliaConfig.disabled;
        boolean fabroliaSpawn = FabroliaConfig.enableFabroliaSpawn;
        boolean fabroliaRandomTicks = FabroliaConfig.enableFabroliaRandomTicks;
        boolean fabroliaChunkSend = FabroliaConfig.enableFabroliaChunkSend;
        boolean fabroliaDespawn = FabroliaConfig.enableFabroliaDespawn;
        boolean vmpLoaded = PlatformUtils.isModLoaded("vmp");
        boolean fabroliaVmpTracking = FabroliaConfig.enableFabroliaVmpTracking;

        MutableComponent message = prefix.copy()
                .append(Component.literal("Performance Statistics").withStyle(ChatFormatting.GOLD))

                .append(Component.literal("\nStatus: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(enabled ? "Enabled" : "Disabled")
                        .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED))

                .append(Component.literal("\nFabrolia Spawn: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(fabroliaSpawn ? "Enabled" : "Disabled")
                        .withStyle(fabroliaSpawn ? ChatFormatting.GREEN : ChatFormatting.RED))

                .append(Component.literal("\nFabrolia Random Ticks: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(fabroliaRandomTicks ? "Enabled" : "Disabled")
                        .withStyle(fabroliaRandomTicks ? ChatFormatting.GREEN : ChatFormatting.RED))

                .append(Component.literal("\nFabrolia Chunk Send: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(fabroliaChunkSend ? "Enabled" : "Disabled")
                        .withStyle(fabroliaChunkSend ? ChatFormatting.GREEN : ChatFormatting.RED))

                .append(Component.literal("\nFabrolia Despawn: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(fabroliaDespawn ? "Enabled" : "Disabled")
                        .withStyle(fabroliaDespawn ? ChatFormatting.GREEN : ChatFormatting.RED));

        if (vmpLoaded) {
            message.append(Component.literal("\nFabrolia VMP Tracking: ").withStyle(ChatFormatting.WHITE))
                   .append(Component.literal(fabroliaVmpTracking ? "Enabled" : "Disabled")
                           .withStyle(fabroliaVmpTracking ? ChatFormatting.GREEN : ChatFormatting.RED));
        }

        message.append(Component.literal("\nEntities: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(String.valueOf(totalEntities)).withStyle(ChatFormatting.GREEN))

                .append(Component.literal("\nMax Threads: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(String.valueOf(threads)).withStyle(ChatFormatting.YELLOW));

        source.sendSuccess(() -> message, false);
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

            MutableComponent message = prefix.copy()
                    .append(Component.literal("Entity Statistics").withStyle(ChatFormatting.GOLD));

            server.getAllLevels().forEach(world -> {
                String worldName = world.dimensionTypeRegistration().getRegisteredName();
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

                message.append(Component.literal("\n" + worldName + ": ").withStyle(ChatFormatting.YELLOW))
                        .append(Component.literal(String.valueOf(worldCount.get())).withStyle(ChatFormatting.GREEN))
                        .append(Component.literal(" entities (").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(String.valueOf(fabroliaCount.get())).withStyle(ChatFormatting.AQUA))
                        .append(Component.literal(" fabrolia)").withStyle(ChatFormatting.GRAY));
            });

            message.append(Component.literal("\nTotal Entities: ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(String.valueOf(totalEntities.get())).withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(" (").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(String.valueOf(totalFabroliaEntities.get())).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" fabrolia)").withStyle(ChatFormatting.GRAY));

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

                message.append(Component.literal("\n\nTick Window: ").withStyle(ChatFormatting.GOLD))
                        .append(Component.literal(ticks + " ticks").withStyle(ChatFormatting.YELLOW));

                message.append(Component.literal("\nTotal entity ticks: ").withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(String.valueOf(syncCount + fabroliaCount)).withStyle(ChatFormatting.GOLD))
                        .append(Component.literal(" (").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(syncCount + " sync").withStyle(ChatFormatting.RED))
                        .append(Component.literal(" + ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(fabroliaCount + " fabrolia").withStyle(ChatFormatting.AQUA))
                        .append(Component.literal(String.format(", %.1f%% fabrolia", fabroliaShare)).withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(")").withStyle(ChatFormatting.GRAY));

                message.append(Component.literal("\nAvg entity MSPT: ").withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(String.format("%.3f ms", totalMspt)).withStyle(ChatFormatting.GREEN));

                message.append(Component.literal("\nBatch wall MSPT: ").withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(String.format("%.3f ms", wallMspt)).withStyle(ChatFormatting.GREEN))
                        .append(Component.literal(String.format(" (pool=%d, speedup=%.2fx)", pool, speedup)).withStyle(ChatFormatting.GRAY));
            }

            if (topCount > 0 && !entityTypeCounts.isEmpty()) {
                message.append(Component.literal("\n\nTop " + topCount + " Entity Types:").withStyle(ChatFormatting.GOLD));

                final int[] rank = {1};
                entityTypeCounts.entrySet().stream()
                        .sorted(Map.Entry.<EntityType<?>, Integer>comparingByValue().reversed())
                        .limit(topCount)
                        .forEach(entry -> {
                            EntityType<?> type = entry.getKey();
                            int count = entry.getValue();
                            boolean isFabrolia = entityTypeFabrolia.getOrDefault(type, false);

                            Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
                            String name = id.getPath();

                            message.append(Component.literal("\n" + rank[0] + ". ").withStyle(ChatFormatting.GRAY))
                                    .append(Component.literal(name).withStyle(ChatFormatting.YELLOW))
                                    .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                                    .append(Component.literal(String.valueOf(count)).withStyle(ChatFormatting.GREEN))
                                    .append(Component.literal(" [").withStyle(ChatFormatting.DARK_GRAY))
                                    .append(Component.literal(isFabrolia ? "fabrolia" : "sync")
                                            .withStyle(isFabrolia ? ChatFormatting.AQUA : ChatFormatting.RED))
                                    .append(Component.literal("]").withStyle(ChatFormatting.DARK_GRAY));

                            if (showTickStats && ticks > 0) {
                                double mspt = TickStats.getMSPTForType(type, ticks);
                                message.append(Component.literal(" "))
                                        .append(Component.literal(String.format("%.3fms avg", mspt)).withStyle(ChatFormatting.GREEN));
                            }

                            rank[0]++;
                        });
            }
            RegionScheduler.runSuccessBodyIfHealthy(() -> resetEntityTickStats());
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> source.sendSuccess(() -> message, false));
        });
    }
}
