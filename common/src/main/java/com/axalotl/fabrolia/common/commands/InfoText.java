package com.axalotl.fabrolia.common.commands;

import com.axalotl.fabrolia.common.regions.IslandMetrics;
import com.axalotl.fabrolia.common.regions.TpsHud;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Locale;

/**
 * Layout of the {@code /fabrolia} info commands: one message per command with a
 * title, blank-line separated sections and indented {@code key: value} lines.
 *
 * <p>Chat uses a proportional font, so nothing here relies on space-padded
 * columns; a row that needs more figures than fit on one line is split into a
 * name line and an indented figures line instead.
 */
final class InfoText {

    private static final String INDENT = "  ";

    private final MutableComponent out;

    private InfoText(MutableComponent out) {
        this.out = out;
    }

    static InfoText titled(String title) {
        return new InfoText(FabroliaCommand.prefix.copy()
                .append(Component.literal(title).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
    }

    /** A blank line, then the section name. */
    InfoText section(String name) {
        out.append(Component.literal("\n\n" + name).withStyle(ChatFormatting.YELLOW));
        return this;
    }

    InfoText line(String key, Component value) {
        out.append(Component.literal("\n" + INDENT + key + ": ").withStyle(ChatFormatting.GRAY))
                .append(value);
        return this;
    }

    /** A line whose key carries its own style (e.g. a dimension colour). */
    InfoText line(Component key, Component value) {
        out.append(Component.literal("\n" + INDENT)).append(key)
                .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                .append(value);
        return this;
    }

    InfoText line(Component content) {
        out.append(Component.literal("\n" + INDENT)).append(content);
        return this;
    }

    /** A row's second line, indented under its first. */
    InfoText detail(Component content) {
        out.append(Component.literal("\n" + INDENT + "   ")).append(content);
        return this;
    }

    /** A dark-grey explanation line. */
    InfoText note(String text) {
        out.append(Component.literal("\n" + INDENT + text).withStyle(ChatFormatting.DARK_GRAY));
        return this;
    }

    /** "More: cmd · cmd" with each command clickable into the chat input. */
    InfoText more(String... commands) {
        out.append(Component.literal("\n\n" + INDENT + "More: ").withStyle(ChatFormatting.DARK_GRAY));
        for (int i = 0; i < commands.length; i++) {
            if (i > 0) out.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));
            out.append(command(commands[i]));
        }
        return this;
    }

    MutableComponent build() {
        return out;
    }

    // ---- values -------------------------------------------------------

    static MutableComponent text(String text, ChatFormatting... style) {
        return Component.literal(text).withStyle(style);
    }

    static MutableComponent onOff(boolean on) {
        return on ? text("on", ChatFormatting.GREEN) : text("off", ChatFormatting.RED);
    }

    /** Joins value parts with a grey " · ". */
    static MutableComponent join(Component... parts) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) out.append(text(" · ", ChatFormatting.DARK_GRAY));
            out.append(parts[i]);
        }
        return out;
    }

    /** A command suggestion: click puts it into the chat input. */
    static MutableComponent command(String command) {
        return Component.literal(command).withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent.SuggestCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to insert"))));
    }

    static MutableComponent hover(MutableComponent text, String hover) {
        return text.withStyle(style -> style.withHoverEvent(
                new HoverEvent.ShowText(Component.literal(hover))));
    }

    /** "19.8 TPS" coloured against the target; "measuring…" before the first sample. */
    static MutableComponent tps(double tps, double targetTps) {
        if (Double.isNaN(tps)) return text("measuring…", ChatFormatting.GRAY);
        return text(String.format(Locale.ROOT, "%.1f TPS", tps), tpsColor(tps, targetTps));
    }

    /** "12.3 ms" coloured against the tick budget. */
    static MutableComponent mspt(double mspt, double budgetMs, String suffix) {
        return text(String.format(Locale.ROOT, "%.1f ms", mspt) + suffix, msptColor(mspt, budgetMs));
    }

    static ChatFormatting tpsColor(double tps, double targetTps) {
        if (tps >= 0.95 * targetTps) return ChatFormatting.GREEN;
        if (tps >= 0.75 * targetTps) return ChatFormatting.YELLOW;
        return ChatFormatting.RED;
    }

    static ChatFormatting msptColor(double mspt, double budgetMs) {
        if (mspt > budgetMs) return ChatFormatting.RED;
        if (mspt > 0.6 * budgetMs) return ChatFormatting.YELLOW;
        return ChatFormatting.GREEN;
    }

    static MutableComponent state(IslandMetrics.State state) {
        ChatFormatting color = switch (state) {
            case HEALTHY -> ChatFormatting.GREEN;
            case LAGGING -> ChatFormatting.YELLOW;
            case STALLED -> ChatFormatting.RED;
            case IDLE, MEASURING -> ChatFormatting.GRAY;
        };
        return text(state.label(), color);
    }

    static String count(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    static String plural(long n, String one, String many) {
        return count(n) + " " + (n == 1 ? one : many);
    }

    /** "Overworld", "Nether", "End", or the identifier of a modded dimension. */
    static String dimension(ResourceKey<Level> dimension) {
        if (Level.OVERWORLD.equals(dimension)) return "Overworld";
        if (Level.NETHER.equals(dimension)) return "Nether";
        if (Level.END.equals(dimension)) return "End";
        return dimension.identifier().toString();
    }

    /** The HUD's dimension colours: Overworld green, Nether red, End purple. */
    static ChatFormatting dimensionColor(ResourceKey<Level> dimension) {
        return TpsHud.dimColor(dimension);
    }
}
