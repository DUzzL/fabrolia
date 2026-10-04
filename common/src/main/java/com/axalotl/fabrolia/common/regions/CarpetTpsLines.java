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
 * <p>Carpet is not a compile dependency: the hook is Carpet's public HUD extension
 * point ({@code HUDController.register}), which runs on Main once per second after
 * Carpet's loggers filled {@code player_huds} and before the footers are sent.
 */
public final class CarpetTpsLines {
    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia");
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final String CARPET_TPS_PREFIX = "TPS: ";

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
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                List<Component> lines = playerHuds.get(player);
                if (lines == null || !subscribedToTps(player)) continue;
                Component local = localLine(server, player);
                if (local != null) replaceCarpetTpsLine(lines, mainLine(server), local);
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

    private static Component mainLine(MinecraftServer server) {
        double msPerTick = server.tickRateManager().millisecondsPerTick();
        return line("Main ", TpsHud.mainTpsValue(), server.tickRateManager().tickrate(),
                server.getAverageTickTimeNanos() / 1_000_000.0, msPerTick);
    }

    /** {@code null} when the player is on Main's lane: Carpet's line already describes it. */
    private static Component localLine(MinecraftServer server, ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) return null;
        RegionizedServer runtime = RegionScheduler.forLevel(level);
        if (runtime == null || !runtime.asyncActive) return null;
        IslandRegistry.PIsland island = runtime.islandOwnerOfChunk(player.chunkPosition().pack());
        if (island == null) return null;
        return line("Local", IslandMetrics.actualTps(island), server.tickRateManager().tickrate(),
                island.emaUnitNanos / 1_000_000.0, server.tickRateManager().millisecondsPerTick());
    }

    static Component line(String label, double tps, double targetTps, double mspt, double msPerTick) {
        MutableComponent out = Component.literal(label).withStyle(ChatFormatting.WHITE);
        out.append(Component.literal(" │ ").withStyle(ChatFormatting.DARK_GRAY));
        out.append(Component.literal("TPS: ").withStyle(ChatFormatting.GRAY));
        out.append(Component.literal(String.format(Locale.US, "%.1f", tps)).withStyle(tpsColor(tps, targetTps)));
        out.append(Component.literal("  MSPT: ").withStyle(ChatFormatting.GRAY));
        out.append(Component.literal(String.format(Locale.US, "%.1f", mspt)).withStyle(msptColor(mspt, msPerTick)));
        return out;
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
        if (tps >= 0.95 * targetTps) return ChatFormatting.DARK_GREEN;
        if (tps >= 0.8 * targetTps) return ChatFormatting.YELLOW;
        if (tps >= 0.5 * targetTps) return ChatFormatting.RED;
        return ChatFormatting.LIGHT_PURPLE;
    }
}
