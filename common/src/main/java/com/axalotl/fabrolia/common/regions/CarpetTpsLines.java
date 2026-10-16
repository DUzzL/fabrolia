package com.axalotl.fabrolia.common.regions;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Carpet {@code /log tps} with the player's island. For a subscriber standing on an
 * async island, Carpet's single tab-footer line ({@code TPS: … MSPT: …}) becomes
 *
 * <pre>
 * Main  │ TPS: 20.0  MSPT: 12.3
 * Local │ TPS: 19.7  MSPT:  3.4
 * </pre>
 *
 * in Carpet's own style (grey labels, heat-coloured values). Main TPS is measured
 * (overworld game ticks per real second, {@link TpsHud}) instead of Carpet's
 * {@code 1000 / max(mspt, 50)} estimate, which reads 20.0 whenever MSPT stays under
 * 50 ms even if ticks are being lost. Local is the island's completed units per real
 * second and its unit time ({@link IslandMetrics}). Players on Main's lane (no island
 * owner, or async islands off) keep Carpet's line unchanged.
 *
 * <p>The tab list centres every footer line on its own, so the two lines line up only
 * when each column has the same pixel width in both. Columns are padded to a common
 * width in vanilla's default font: labels left-aligned, values right-aligned. The font
 * has two blank advances, a space (4 px) and a bold space (5 px), so a column grows
 * to the narrowest width every line can reach with them. A value column is also sized
 * for every shorter decimal of the same digit count, so it holds still while the
 * values change and moves only when a value gains a digit. A client resource pack
 * that replaces the default font can still misalign the lines.
 *
 * <p>Carpet is not a compile dependency: the hook is Carpet's public HUD extension
 * point ({@code HUDController.register}), which runs on Main once per second after
 * Carpet's loggers filled {@code player_huds} and before the footers are sent.
 */
public final class CarpetTpsLines {
    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia");
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final String CARPET_TPS_PREFIX = "TPS: ";
    /** Minimum blank pixels between a column's text and the next column. */
    private static final int GAP = 4;
    private static final int SPACE_ADVANCE = 4;
    /**
     * Vanilla advances (glyph width + 1) of {@code '!'..'~'} from
     * {@code textures/font/ascii.png} in the 26.3 client.
     */
    private static final String ASCII_ADVANCES =
            "2466662444626266666666666225656766666666466666666666666666464663666665662653666666646666664247";

    /** One line's figures: tick rate against its target, tick time against its budget. */
    record Reading(String label, double tps, double targetTps, double mspt, double msPerTick) {}

    private static Map<ServerPlayer, List<Component>> playerHuds;
    private static Method playerSubscriptions;
    private static Field tpsLoggerActive;

    private CarpetTpsLines() {}

    /** Physical Main at scheduler start. Carpet's listener list is static: install once per JVM. */
    @SuppressWarnings("unchecked")
    static void install() {
        if (!INSTALLED.compareAndSet(false, true)) return;
        Class<?> hud;
        try {
            hud = Class.forName("carpet.logging.HUDController");
        } catch (ClassNotFoundException absent) {
            return;
        }
        try {
            Class<?> registry = Class.forName("carpet.logging.LoggerRegistry");
            playerHuds = (Map<ServerPlayer, List<Component>>) hud.getField("player_huds").get(null);
            playerSubscriptions = registry.getMethod("getPlayerSubscriptions", String.class);
            tpsLoggerActive = registry.getField("__tps");
            Consumer<MinecraftServer> listener = CarpetTpsLines::onHudUpdate;
            hud.getMethod("register", Consumer.class).invoke(null, listener);
        } catch (ReflectiveOperationException | ClassCastException incompatible) {
            // A display-only integration against an unexpected Carpet build: Carpet's
            // own line keeps working, so report once instead of failing the server.
            LOGGER.warn("[fabrolia] Carpet /log tps island line disabled: unsupported Carpet HUD API", incompatible);
        }
    }

    /** Carpet's HUD listener: physical Main, inside {@code HUDController.update_hud}. */
    private static void onHudUpdate(MinecraftServer server) {
        try {
            if (!tpsLoggerActive.getBoolean(null)) return;
            Reading main = null;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                List<Component> lines = playerHuds.get(player);
                if (lines == null || !subscribedToTps(player)) continue;
                Reading local = localReading(server, player);
                if (local == null) continue;
                if (main == null) main = mainReading(server);
                List<Component> aligned = lines(main, local);
                replaceCarpetTpsLine(lines, aligned.get(0), aligned.get(1));
            }
        } catch (ReflectiveOperationException incompatible) {
            throw new IllegalStateException("Carpet HUD API changed after install", incompatible);
        }
    }

    private static boolean subscribedToTps(ServerPlayer player) throws ReflectiveOperationException {
        Object subs = playerSubscriptions.invoke(null, player.getScoreboardName());
        return subs instanceof Map<?, ?> map && map.containsKey("tps");
    }

    /**
     * Replace Carpet's TPS entry of one footer with the two lines, keeping every other
     * entry (mobcaps, counters, scarpet footers) and Carpet's "\n" separators in order.
     *
     * @return whether Carpet's TPS entry was found
     */
    static boolean replaceCarpetTpsLine(List<Component> lines, Component main, Component local) {
        for (int i = 0, n = lines.size(); i < n; i++) {
            if (!lines.get(i).getString().startsWith(CARPET_TPS_PREFIX)) continue;
            lines.set(i, main);
            lines.add(i + 1, Component.literal("\n"));
            lines.add(i + 2, local);
            return true;
        }
        return false;
    }

    private static Reading mainReading(MinecraftServer server) {
        return new Reading("Main", TpsHud.mainTpsValue(), server.tickRateManager().tickrate(),
                server.getAverageTickTimeNanos() / 1_000_000.0, server.tickRateManager().millisecondsPerTick());
    }

    /** {@code null} when the player is on Main's lane: Carpet's line already describes it. */
    private static Reading localReading(MinecraftServer server, ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) return null;
        RegionizedServer runtime = RegionScheduler.forLevel(level);
        if (runtime == null || !runtime.asyncActive) return null;
        IslandRegistry.PIsland island = runtime.islandOwnerOfChunk(player.chunkPosition().pack());
        if (island == null) return null;
        return new Reading("Local", IslandMetrics.actualTps(island), server.tickRateManager().tickrate(),
                island.emaUnitNanos / 1_000_000.0, server.tickRateManager().millisecondsPerTick());
    }

    /** The readings as footer lines whose columns sit at the same pixel offsets. */
    static List<Component> lines(Reading... rows) {
        int n = rows.length;
        String[] labels = new String[n], tps = new String[n], mspt = new String[n];
        for (int i = 0; i < n; i++) {
            labels[i] = rows[i].label();
            // An island's first second has no complete window yet.
            tps[i] = Double.isNaN(rows[i].tps()) ? "--" : String.format(Locale.US, "%.1f", rows[i].tps());
            mspt[i] = String.format(Locale.US, "%.1f", rows[i].mspt());
        }
        int labelColumn = columnWidth(widths(labels));
        int tpsColumn = columnWidth(decimalColumnWidths(tps));
        int msptColumn = columnWidth(decimalColumnWidths(mspt));
        List<Component> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Reading row = rows[i];
            MutableComponent line = Component.literal(labels[i]).withStyle(ChatFormatting.WHITE);
            pad(line, labelColumn - width(labels[i]));
            line.append(Component.literal("│ ").withStyle(ChatFormatting.DARK_GRAY));
            line.append(Component.literal("TPS:").withStyle(ChatFormatting.GRAY));
            pad(line, tpsColumn - width(tps[i]));
            line.append(Component.literal(tps[i]).withStyle(tpsColor(row.tps(), row.targetTps())));
            line.append(Component.literal("  MSPT:").withStyle(ChatFormatting.GRAY));
            pad(line, msptColumn - width(mspt[i]));
            line.append(Component.literal(mspt[i]).withStyle(msptColor(row.mspt(), row.msPerTick())));
            out.add(line);
        }
        return out;
    }

    /** Text widths of a value column plus every plain decimal ({@code d.d}, {@code dd.d}, ...) up to its widest digit count. */
    private static int[] decimalColumnWidths(String[] values) {
        int digits = 0;
        for (String v : values) {
            if (v.chars().allMatch(c -> c == '.' || (c >= '0' && c <= '9'))) digits = Math.max(digits, v.length() - 1);
        }
        int[] own = widths(values);
        int[] out = Arrays.copyOf(own, own.length + Math.max(0, digits - 1));
        for (int d = 2; d <= digits; d++) out[own.length + d - 2] = width("0".repeat(d - 1) + ".0");
        return out;
    }

    private static int[] widths(String[] texts) {
        int[] out = new int[texts.length];
        for (int i = 0; i < texts.length; i++) out[i] = width(texts[i]);
        return out;
    }

    /** The narrowest width at least {@link #GAP} past the widest text that every text pads up to exactly. */
    static int columnWidth(int[] textWidths) {
        int widest = 0;
        for (int w : textWidths) widest = Math.max(widest, w);
        for (int column = widest + GAP; ; column++) {
            boolean fits = true;
            for (int w : textWidths) fits &= paddable(column - w);
            if (fits) return column;
        }
    }

    /** {@code px = 4a + 5b}: {@code b = px mod 4} bold spaces, the rest plain spaces. */
    static boolean paddable(int px) {
        return px >= 5 * (px % 4);
    }

    private static void pad(MutableComponent line, int px) {
        if (!paddable(px)) throw new IllegalStateException("cannot pad " + px + " px with spaces");
        int bold = px % 4;
        int plain = (px - 5 * bold) / SPACE_ADVANCE;
        if (plain > 0) line.append(Component.literal(" ".repeat(plain)));
        if (bold > 0) line.append(Component.literal(" ".repeat(bold)).withStyle(ChatFormatting.BOLD));
    }

    /** Pixel width of plain (not bold) printable ASCII in vanilla's default font. */
    static int width(String text) {
        int px = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ') px += SPACE_ADVANCE;
            else if (c > ' ' && c <= '~') px += ASCII_ADVANCES.charAt(c - '!') - '0';
            else throw new IllegalArgumentException("no vanilla advance for U+" + Integer.toHexString(c));
        }
        return px;
    }

    /** Carpet's {@code Messenger.heatmap_color} for tick time against the tick budget. */
    static ChatFormatting msptColor(double mspt, double msPerTick) {
        if (mspt > msPerTick) return ChatFormatting.LIGHT_PURPLE;
        if (mspt > 0.8 * msPerTick) return ChatFormatting.RED;
        if (mspt > 0.5 * msPerTick) return ChatFormatting.YELLOW;
        return ChatFormatting.DARK_GREEN;
    }

    /** The same four steps for the tick rate actually reached against the target rate. */
    static ChatFormatting tpsColor(double tps, double targetTps) {
        if (Double.isNaN(tps)) return ChatFormatting.GRAY;
        if (tps >= 0.95 * targetTps) return ChatFormatting.DARK_GREEN;
        if (tps >= 0.8 * targetTps) return ChatFormatting.YELLOW;
        if (tps >= 0.5 * targetTps) return ChatFormatting.RED;
        return ChatFormatting.LIGHT_PURPLE;
    }
}
