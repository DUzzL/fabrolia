package com.axalotl.fabrolia.common.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import static net.minecraft.commands.Commands.literal;

public class FabroliaCommand {
    public final static Component prefix = Component.literal("§8[§f\uD83C\uDF00§8]§7 ");

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> main = literal("fabrolia");
        main = ConfigCommand.registerConfig(main);
        main = StatsCommand.registerStatus(main);
        main = RegionCommand.register(main);
        dispatcher.register(main);
        // Region-aware /tick extension (2026-07-14): brigadier merges
        // same-name roots, so this adds island scopes to vanilla's /tick.
        dispatcher.register(literal("tick")
                .requires(com.axalotl.fabrolia.common.platform.PlatformPermission.require("command.regions.toggle", 3))
                .then(literal("rate").then(literal("island").then(literal("here")
                        .then(net.minecraft.commands.Commands.argument("tps",
                                com.mojang.brigadier.arguments.FloatArgumentType.floatArg(0.1f, 10000f))
                                .executes(RegionCommand::tickRateIslandHere)))))
                .then(literal("sprint").then(literal("island").then(literal("here")
                        .then(net.minecraft.commands.Commands.argument("ticks",
                                com.mojang.brigadier.arguments.LongArgumentType.longArg(1, 1000000))
                                .executes(RegionCommand::tickSprintIslandHere))))));
    }
}