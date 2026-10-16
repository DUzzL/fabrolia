package com.axalotl.fabrolia.common.commands;

import com.axalotl.fabrolia.common.platform.PlatformPermission;
import com.axalotl.fabrolia.common.regions.FunctionAnalyzer;
import com.axalotl.fabrolia.common.regions.FunctionFenceDiag;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.slf4j.LoggerFactory;

import java.util.List;

import static com.axalotl.fabrolia.common.commands.FabroliaCommand.prefix;
import static net.minecraft.commands.Commands.literal;

/**
 * {@code /fabrolia functions plan} — each per-tick root's verdict (partitioned
 * across island heads or Main-resident) with every line's class and the exact
 * blocking line + reason; {@code /fabrolia functions fences [reset]} — per-tick
 * fence causes of datapack functions.
 */
public final class FunctionsCommand {

    private FunctionsCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> register(
            LiteralArgumentBuilder<CommandSourceStack> root) {
        return root.then(literal("functions")
                .requires(PlatformPermission.require("command.functions", 4))
                .then(literal("plan").executes(FunctionsCommand::plan))
                .then(literal("fences")
                        .executes(FunctionsCommand::fences)
                        .then(literal("reset").executes(FunctionsCommand::resetFences))));
    }

    private static int plan(CommandContext<CommandSourceStack> ctx) {
        List<String> lines = FunctionAnalyzer.plan().describe();
        print(ctx, "function plan", lines);
        return 1;
    }

    private static int fences(CommandContext<CommandSourceStack> ctx) {
        print(ctx, "function fences", FunctionFenceDiag.report(ctx.getSource().getServer().getTickCount()));
        return 1;
    }

    private static int resetFences(CommandContext<CommandSourceStack> ctx) {
        FunctionFenceDiag.reset(ctx.getSource().getServer().getTickCount());
        ctx.getSource().sendSuccess(() -> prefix.copy()
                .append(Component.literal("function fence counters reset").withStyle(ChatFormatting.WHITE)), false);
        return 1;
    }

    private static void print(CommandContext<CommandSourceStack> ctx, String title, List<String> lines) {
        LoggerFactory.getLogger("fabrolia").info("{}\n{}", title.toUpperCase(java.util.Locale.ROOT),
                String.join("\n", lines));
        ctx.getSource().sendSystemMessage(prefix.copy()
                .append(Component.literal(title).withStyle(ChatFormatting.WHITE)));
        for (String line : lines) {
            ctx.getSource().sendSystemMessage(Component.literal(line).withStyle(
                    line.startsWith("  ") ? ChatFormatting.GRAY : ChatFormatting.GREEN));
        }
    }
}
