package com.axalotl.fabrolia.common.commands;

import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.axalotl.fabrolia.common.platform.PlatformPermission;
import com.axalotl.fabrolia.common.platform.PlatformUtils;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

import java.util.Set;

import static com.axalotl.fabrolia.common.commands.FabroliaCommand.prefix;
import static net.minecraft.commands.Commands.literal;

public class ConfigCommand {

    public static LiteralArgumentBuilder<CommandSourceStack> registerConfig(LiteralArgumentBuilder<CommandSourceStack> root) {
        LiteralArgumentBuilder<CommandSourceStack> config = literal("config")
                .requires(PlatformPermission.require("command.config", 4))
                .then(buildToggleCommand())
                .then(buildReloadCommand())
                .then(buildSynchronizedEntitiesCommand())
                .then(buildFabroliaEntitySpawnCommand())
                .then(buildFabroliaRandomTicksCommand())
                .then(buildFabroliaDespawnCommand());
        if (PlatformUtils.isModLoaded("vmp")) {
            config = config.then(buildFabroliaVmpTrackingCommand());
        }
        return root.then(config);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildReloadCommand() {
        return literal("reload")
                .executes(ctx -> {
                    if (!com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                            .isPhysicalServerThread(ctx.getSource().getServer())) {
                        MutableComponent msg = prefix.copy()
                                .append(Component.literal(
                                        "Configuration may only be reloaded on the server thread.")
                                        .withStyle(style -> style.withColor(ChatFormatting.RED)));
                        ctx.getSource().sendFailure(msg);
                        return 0;
                    }
                    LifecycleKeys live = LifecycleKeys.capture();
                    PlatformUtils.reloadConfig();
                    String kept = live.restoreChanged();

                    boolean playerPreferenceApplied = com.axalotl.fabrolia.common.regions
                            .RegionalPlayerEntityTicksV2.applyConfiguredPreference(
                                    ctx.getSource().getServer());
                    if (kept != null) {
                        MutableComponent keptMsg = prefix.copy()
                                .append(Component.literal("Kept the running " + kept
                                        + ": these change only through their runtime commands "
                                        + "(/fabrolia config toggle, /fabrolia regions on|off) "
                                        + "or at the next restart.")
                                        .withStyle(style -> style.withColor(ChatFormatting.YELLOW)));
                        ctx.getSource().sendSuccess(() -> keptMsg, true);
                    }
                    if (FabroliaConfig.enablePlayerTickActor && !playerPreferenceApplied) {
                        String reason = com.axalotl.fabrolia.common.regions
                                .RegionalPlayerEntityTicksV2.refusalReason(
                                        ctx.getSource().getServer());
                        MutableComponent msg = prefix.copy()
                                .append(Component.literal(
                                        "Configuration reloaded, but saved player ticks remain "
                                                + "fail-closed: " + String.valueOf(reason))
                                        .withStyle(style -> style.withColor(ChatFormatting.YELLOW)));
                        ctx.getSource().sendFailure(msg);
                        return 0;
                    }

                    MutableComponent msg = prefix.copy()
                            .append(Component.literal("Configuration reloaded successfully.")
                                    .withStyle(style -> style.withColor(ChatFormatting.GREEN)));
                    ctx.getSource().sendSuccess(() -> msg, true);
                    return 1;
                });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildToggleCommand() {
        return literal("toggle").executes(ctx -> {
            // Region workers read disabled on every tick while the scheduler runs;
            // flipping it under them switches off their guards before the async
            // collapse at the next level head (audit D-02). Stop regions first.
            if (!FabroliaConfig.disabled && com.axalotl.fabrolia.common.regions.RegionScheduler.isActive()) {
                sendErrorMessage(ctx, "Region threading is running: ", "run /fabrolia regions off first",
                        ", then toggle.");
                return 0;
            }
            FabroliaConfig.disabled = !FabroliaConfig.disabled;
            PlatformUtils.saveConfig();

            sendMessage(ctx, "Fabrolia is now ",
                    FabroliaConfig.disabled ? "disabled" : "enabled",
                    true);
            return 1;
        });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildSynchronizedEntitiesCommand() {
        return literal("synchronizedEntities")
                .executes(ctx -> {
                    displaySynchronizedEntities(ctx);
                    return 1;
                })
                .then(buildAddEntityCommand())
                .then(buildRemoveEntityCommand());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildAddEntityCommand() {
        return literal("add")
                .then(Commands.argument("entity", IdentifierArgument.id())
                        .suggests((_, builder) -> {
                            BuiltInRegistries.ENTITY_TYPE.keySet().forEach(
                                    id -> builder.suggest(id.toString())
                            );
                            BuiltInRegistries.ENTITY_TYPE.keySet().stream()
                                    .map(Identifier::getNamespace)
                                    .distinct()
                                    .forEach(ns -> builder.suggest(ns + ":*"));
                            return builder.buildFuture();
                        })
                        .executes(ConfigCommand::addEntity)
                )
                .then(Commands.argument("namespace", StringArgumentType.greedyString())
                        .executes(ConfigCommand::addNamespace)
                );
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildRemoveEntityCommand() {
        return literal("remove")
                .then(Commands.argument("entity", IdentifierArgument.id())
                        .suggests((_, builder) -> {
                            FabroliaConfig.synchronizedEntities.forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .executes(ConfigCommand::removeEntity)
                )
                .then(Commands.argument("namespace", StringArgumentType.greedyString())
                        .executes(ConfigCommand::removeNamespace)
                );
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildFabroliaEntitySpawnCommand() {
        return literal("setFabroliaEntitySpawn")
                .executes(ctx -> {
                    sendMessage(ctx, "Current value of fabrolia entity spawn: ",
                            String.valueOf(FabroliaConfig.enableFabroliaSpawn),
                            false);
                    return 1;
                })
                .then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(ctx -> {
                            boolean value = BoolArgumentType.getBool(ctx, "value");
                            FabroliaConfig.enableFabroliaSpawn = value;

                            sendMessage(ctx, "Fabrolia Entity Spawn set to ",
                                    value + " (runtime only, not saved)",
                                    true);
                            return 1;
                        })
                );
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildFabroliaRandomTicksCommand() {
        return literal("setFabroliaRandomTicks")
                .executes(ctx -> {
                    sendMessage(ctx, "Current value of fabrolia random ticks: ",
                            String.valueOf(FabroliaConfig.enableFabroliaRandomTicks),
                            false);
                    return 1;
                })
                .then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(ctx -> {
                            boolean value = BoolArgumentType.getBool(ctx, "value");
                            FabroliaConfig.enableFabroliaRandomTicks = value;

                            sendMessage(ctx, "Fabrolia Random Ticks set to ",
                                    value + " (runtime only, not saved)",
                                    true);
                            return 1;
                        })
                );
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildFabroliaDespawnCommand() {
        return literal("setFabroliaDespawn")
                .executes(ctx -> {
                    sendMessage(ctx, "Current value of fabrolia despawn: ",
                            String.valueOf(FabroliaConfig.enableFabroliaDespawn),
                            false);
                    return 1;
                })
                .then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(ctx -> {
                            boolean value = BoolArgumentType.getBool(ctx, "value");
                            FabroliaConfig.enableFabroliaDespawn = value;

                            sendMessage(ctx, "Fabrolia Despawn set to ",
                                    value + " (runtime only, not saved)",
                                    true);
                            return 1;
                        })
                );
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildFabroliaVmpTrackingCommand() {
        return literal("setFabroliaVmpTracking")
                .executes(ctx -> {
                    sendMessage(ctx, "Current value of fabrolia VMP tracking: ",
                            String.valueOf(FabroliaConfig.enableFabroliaVmpTracking),
                            false);
                    return 1;
                })
                .then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(ctx -> {
                            boolean value = BoolArgumentType.getBool(ctx, "value");
                            FabroliaConfig.enableFabroliaVmpTracking = value;
                            PlatformUtils.saveConfig();

                            sendMessage(ctx, "Fabrolia VMP Tracking set to ",
                                    String.valueOf(value),
                                    true);
                            return 1;
                        })
                );
    }

    private static void displaySynchronizedEntities(CommandContext<CommandSourceStack> ctx) {
        Set<String> entities = FabroliaConfig.synchronizedEntities;
        MutableComponent message = prefix.copy()
                .append(Component.literal("Synchronized Entities: ")
                        .withStyle(style -> style.withColor(ChatFormatting.WHITE)));

        if (entities.isEmpty()) {
            message.append(Component.literal("No entities synchronized.")
                    .withStyle(style -> style.withColor(ChatFormatting.RED)));
        } else {
            message.append(Component.literal("\n"));
            entities.forEach(entity ->
                    message.append(Component.literal("- ").withStyle(style -> style.withColor(ChatFormatting.GREEN)))
                            .append(Component.literal(entity).withStyle(style -> style.withColor(ChatFormatting.YELLOW)))
                            .append(Component.literal("\n"))
            );
        }

        ctx.getSource().sendSuccess(() -> message, false);
    }

    private static int addEntity(CommandContext<CommandSourceStack> ctx) {
        Identifier id = IdentifierArgument.getId(ctx, "entity");

        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            sendErrorMessage(ctx, "Error entity class ", id.toString(), " does not exist.");
            return 0;
        }

        if (FabroliaConfig.isEntitySynchronized(id)) {
            sendErrorMessage(ctx, "Error entity class ", id.toString(), " is already synchronized.");
            return 0;
        }

        FabroliaConfig.syncEntity(id.toString());
        sendMessage(ctx, "Entity class ", id.toString(),
                " has been added to the synchronized list.");
        return 1;
    }

    private static int addNamespace(CommandContext<CommandSourceStack> ctx) {
        String namespace = StringArgumentType.getString(ctx, "namespace");

        if (!FabroliaConfig.matchesExistingNamespaceWildcard(namespace)) {
            sendErrorMessage(ctx, "Error namespace ", namespace, " does not exist.");
            return 0;
        }
        if (FabroliaConfig.synchronizedEntities.contains(namespace)) {
            sendErrorMessage(ctx, "Error namespace ", namespace, " is already synchronized.");
            return 0;
        }
        FabroliaConfig.syncEntity(namespace);
        sendMessage(ctx, "All entities with namespace ", namespace,
                " have been added to the synchronized list.");
        return 1;
    }

    private static int removeEntity(CommandContext<CommandSourceStack> ctx) {
        Identifier id = IdentifierArgument.getId(ctx, "entity");

        // Only an exact entry can be removed. An entity that is synchronized
        // through a namespace wildcard stays synchronized until the wildcard
        // itself is removed, so reporting success here would be false.
        if (!FabroliaConfig.synchronizedEntities.contains(id.toString())) {
            String wildcard = id.getNamespace() + ":*";
            if (FabroliaConfig.synchronizedEntities.contains(wildcard)) {
                sendErrorMessage(ctx, "Error entity class ", id.toString(),
                        " is synchronized through " + wildcard + "; remove that entry instead.");
            } else {
                sendErrorMessage(ctx, "Error entity class ", id.toString(),
                        " is not in the synchronized list.");
            }
            return 0;
        }

        FabroliaConfig.removeEntity(id.toString());
        sendMessage(ctx, "Entity class ", id.toString(),
                " has been removed from synchronized list.");
        return 1;
    }

    private static int removeNamespace(CommandContext<CommandSourceStack> ctx) {
        String namespace = StringArgumentType.getString(ctx, "namespace");

        if (!FabroliaConfig.synchronizedEntities.contains(namespace)) {
            sendErrorMessage(ctx, "Error namespace ", namespace, " is not in the synchronized list.");
            return 0;
        }

        FabroliaConfig.removeEntity(namespace);
        sendMessage(ctx, "All entities with namespace ", namespace,
                " have been removed from synchronized list.");
        return 1;
    }

    /**
     * Keys a reload must not flip under a running server: workers and the
     * scheduler read them live, and their safe transitions are the runtime
     * commands (audit D-02). A file edit of them takes effect at the next start.
     */
    record LifecycleKeys(boolean disabled, boolean enableRegionThreading, int regionWorkerCount) {
        static LifecycleKeys capture() {
            return new LifecycleKeys(FabroliaConfig.disabled, FabroliaConfig.enableRegionThreading,
                    FabroliaConfig.regionWorkerCount);
        }

        /** Restores every key the reload changed; returns their names, or null. */
        String restoreChanged() {
            java.util.ArrayList<String> kept = new java.util.ArrayList<>();
            if (FabroliaConfig.disabled != disabled) kept.add("disabled=" + disabled);
            if (FabroliaConfig.enableRegionThreading != enableRegionThreading) {
                kept.add("enableRegionThreading=" + enableRegionThreading);
            }
            if (FabroliaConfig.regionWorkerCount != regionWorkerCount) kept.add("regionWorkerCount=" + regionWorkerCount);
            if (kept.isEmpty()) return null;
            FabroliaConfig.disabled = disabled;
            FabroliaConfig.enableRegionThreading = enableRegionThreading;
            FabroliaConfig.regionWorkerCount = regionWorkerCount;
            FabroliaConfig.onConfigLoaded();
            return String.join(", ", kept);
        }
    }

    private static void sendMessage(CommandContext<CommandSourceStack> ctx, String prefix,
                                    String highlight, boolean broadcast) {
        MutableComponent message = FabroliaCommand.prefix.copy()
                .append(Component.literal(prefix).withStyle(style -> style.withColor(ChatFormatting.WHITE)))
                .append(Component.literal(highlight).withStyle(style -> style.withColor(ChatFormatting.GREEN)));
        ctx.getSource().sendSuccess(() -> message, broadcast);
    }

    private static void sendMessage(CommandContext<CommandSourceStack> ctx, String prefix,
                                    String highlight, String suffix) {
        MutableComponent message = FabroliaCommand.prefix.copy()
                .append(Component.literal(prefix).withStyle(style -> style.withColor(ChatFormatting.WHITE)))
                .append(Component.literal(highlight).withStyle(style -> style.withColor(ChatFormatting.GREEN)))
                .append(Component.literal(suffix).withStyle(style -> style.withColor(ChatFormatting.WHITE)));
        ctx.getSource().sendSuccess(() -> message, true);
    }

    private static void sendErrorMessage(CommandContext<CommandSourceStack> ctx, String prefix,
                                         String error, String suffix) {
        MutableComponent message = FabroliaCommand.prefix.copy()
                .append(Component.literal(prefix).withStyle(style -> style.withColor(ChatFormatting.RED)))
                .append(Component.literal(error).withStyle(style -> style.withColor(ChatFormatting.RED)))
                .append(Component.literal(suffix).withStyle(style -> style.withColor(ChatFormatting.RED)));
        // A refused request is reported to its sender only, as a failure; it
        // used to be broadcast to every operator as a success message.
        ctx.getSource().sendFailure(message);
    }
}
