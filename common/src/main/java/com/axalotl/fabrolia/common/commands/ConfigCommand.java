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
                .then(buildFabroliaChunkSendCommand())
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
                    PlatformUtils.reloadConfig();

                    boolean playerPreferenceApplied = com.axalotl.fabrolia.common.regions
                            .RegionalPlayerEntityTicksV2.applyConfiguredPreference(
                                    ctx.getSource().getServer());
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
                            PlatformUtils.saveConfig();

                            sendMessage(ctx, "Fabrolia Entity Spawn set to ",
                                    String.valueOf(value),
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
                            PlatformUtils.saveConfig();

                            sendMessage(ctx, "Fabrolia Random Ticks set to ",
                                    String.valueOf(value),
                                    true);
                            return 1;
                        })
                );
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildFabroliaChunkSendCommand() {
        return literal("setFabroliaChunkSend")
                .executes(ctx -> {
                    sendMessage(ctx, "Current value of fabrolia chunk send: ",
                            String.valueOf(FabroliaConfig.enableFabroliaChunkSend),
                            false);
                    return 1;
                })
                .then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(ctx -> {
                            boolean value = BoolArgumentType.getBool(ctx, "value");
                            FabroliaConfig.enableFabroliaChunkSend = value;
                            PlatformUtils.saveConfig();

                            sendMessage(ctx, "Fabrolia Chunk Send set to ",
                                    String.valueOf(value),
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
                            PlatformUtils.saveConfig();

                            sendMessage(ctx, "Fabrolia Despawn set to ",
                                    String.valueOf(value),
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
            return 1;
        }

        if (FabroliaConfig.isEntitySynchronized(id)) {
            sendErrorMessage(ctx, "Error entity class ", id.toString(), " is already synchronized.");
            return 1;
        }

        FabroliaConfig.syncEntity(id.toString());
        sendMessage(ctx, "Entity class ", id.toString(),
                " has been added to the synchronized list.");
        return 1;
    }

    private static int addNamespace(CommandContext<CommandSourceStack> ctx) {
        String namespace = StringArgumentType.getString(ctx, "namespace");

        if (FabroliaConfig.matchesExistingNamespaceWildcard(namespace)) {
            FabroliaConfig.syncEntity(namespace);
            sendMessage(ctx, "All entities with namespace ", namespace,
                    " has been added to the synchronized list.");
        } else {
            sendErrorMessage(ctx, "Error namespace ", namespace, " does not exist.");
        }
        return 1;
    }

    private static int removeEntity(CommandContext<CommandSourceStack> ctx) {
        Identifier id = IdentifierArgument.getId(ctx, "entity");

        if (!FabroliaConfig.isEntitySynchronized(id)) {
            sendErrorMessage(ctx, "Error entity class ", id.toString(), " is not in the synchronized list.");
            return 1;
        }

        FabroliaConfig.removeEntity(id.toString());
        sendMessage(ctx, "Entity class ", id.toString(),
                " has been removed from synchronized list.");
        return 1;
    }

    private static int removeNamespace(CommandContext<CommandSourceStack> ctx) {
        String namespace = StringArgumentType.getString(ctx, "namespace");
        Identifier id = Identifier.tryParse(namespace);

        if (id != null) {
            return 1;
        }

        if (!FabroliaConfig.synchronizedEntities.contains(namespace)) {
            sendErrorMessage(ctx, "Error namespace ", namespace, " is not in the synchronized list.");
            return 1;
        }

        FabroliaConfig.removeEntity(namespace);
        sendMessage(ctx, "All entities with namespace ", namespace,
                " has been removed from synchronized list.");
        return 1;
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
        ctx.getSource().sendSuccess(() -> message, true);
    }
}
