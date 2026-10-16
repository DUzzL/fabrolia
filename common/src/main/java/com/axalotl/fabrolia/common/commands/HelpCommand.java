package com.axalotl.fabrolia.common.commands;

import com.axalotl.fabrolia.common.platform.PlatformPermission;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;

import java.util.List;
import java.util.function.Predicate;

/**
 * {@code /fabrolia} and {@code /fabrolia help}: what each command is for and
 * the three words the outputs use, listing only what the sender may run.
 */
final class HelpCommand {

    private HelpCommand() {}

    private record Entry(String command, String purpose, Predicate<CommandSourceStack> allowed) {}

    private static final Predicate<CommandSourceStack> EVERYONE_REGIONS =
            PlatformPermission.require("command.regions", 0);
    private static final Predicate<CommandSourceStack> EVERYONE_STATS =
            PlatformPermission.require("command.statistics", 0);
    private static final Predicate<CommandSourceStack> ADMIN =
            PlatformPermission.require("command.regions.toggle", 4);

    private static final List<Entry> CHECK = List.of(
            new Entry("/fabrolia regions here", "TPS of the spot you stand on", EVERYONE_REGIONS),
            new Entry("/fabrolia regions tps", "live Main + island TPS above the hotbar (on/off)", EVERYONE_REGIONS),
            new Entry("/fabrolia regions", "server overview: Main TPS, islands, dimensions", EVERYONE_REGIONS),
            new Entry("/fabrolia regions top", "the slowest islands with their figures", EVERYONE_REGIONS),
            new Entry("/fabrolia regions list", "every island, one line each", EVERYONE_REGIONS),
            new Entry("/fabrolia stats", "mod status and features", EVERYONE_STATS),
            new Entry("/fabrolia stats entity", "entity counts per dimension and type", EVERYONE_STATS),
            new Entry("/fabrolia regions player ", "TPS where another player is",
                    PlatformPermission.require("command.regions.player", 2)));

    private static final List<Entry> ADMINISTER = List.of(
            new Entry("/fabrolia regions top verbose", "raw island rows for diagnosis", ADMIN),
            new Entry("/fabrolia regions islandentities", "entity order on islands (vanilla/parallel)", ADMIN),
            new Entry("/fabrolia regions playerticks", "players tick on their island (on/off)", ADMIN),
            new Entry("/fabrolia regions rate here ", "slow down or speed up the island you stand on", ADMIN),
            new Entry("/fabrolia functions plan", "how datapack functions are split",
                    PlatformPermission.require("command.functions", 4)),
            new Entry("/fabrolia config reload", "reload config/fabrolia.toml",
                    PlatformPermission.require("command.config", 4)));

    static int show(CommandSourceStack src) {
        InfoText out = InfoText.titled("Fabrolia commands");
        section(out, src, "Check performance", CHECK);
        section(out, src, "Administration", ADMINISTER);
        out.section("Terms");
        out.line("Island", InfoText.text(
                "nearby loaded chunks that tick together on their own thread", ChatFormatting.WHITE));
        out.line("TPS", InfoText.text(
                "ticks per second; 20 is full speed", ChatFormatting.WHITE));
        out.line("MSPT / ms", InfoText.text(
                "time one tick takes; under 50 ms keeps up with 20 TPS", ChatFormatting.WHITE));
        out.note("Click a command to put it into the chat.");
        src.sendSystemMessage(out.build());
        return 1;
    }

    private static void section(InfoText out, CommandSourceStack src, String title, List<Entry> entries) {
        boolean any = false;
        for (Entry e : entries) {
            if (!e.allowed().test(src)) continue;
            if (!any) {
                out.section(title);
                any = true;
            }
            out.line(InfoText.command(e.command())
                    .append(InfoText.text(" — " + e.purpose(), ChatFormatting.GRAY)));
        }
    }
}
