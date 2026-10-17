package raidprobe;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

/** Local-only diagnostics: one tick-stamped line per raid/omen/POI event near the farm. */
public final class RaidProbe {
    public static volatile MinecraftServer server;
    private static PrintWriter out;

    private RaidProbe() {}

    /** Outside any mixin target, so the global constructor transformer cannot rewrite it. */
    public static it.unimi.dsi.fastutil.ints.Int2ObjectMap<Object> nativeIntMap() {
        return new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
    }

    public static String registryTable(Object map) {
        try {
            var snapshot = map.getClass().getDeclaredField("snapshot");
            snapshot.setAccessible(true);
            Object table = snapshot.get(map);
            var capacity = it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap.class.getDeclaredField("n");
            capacity.setAccessible(true);
            return table.getClass().getSimpleName() + "/" + capacity.getInt(table);
        } catch (NoSuchFieldException absent) {
            return map.getClass().getSimpleName();
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static boolean near(BlockPos pos) {
        return pos != null && pos.getX() > -80 && pos.getX() < 80 && pos.getZ() > -60 && pos.getZ() < 80;
    }

    public static long time() {
        MinecraftServer s = server;
        if (s == null) return -1;
        ServerLevel overworld = s.overworld();
        return overworld == null ? -1 : overworld.getGameTime();
    }

    private static java.lang.reflect.Field rawGameTime;

    /**
     * The overworld's real game-time counter, read from the PrimaryLevelData field.
     * Fabrolia redirects getGameTime() to the executing island's logical clock on
     * region workers; this bypasses that so both clocks can be logged side by side.
     */
    public static long rawTime() {
        MinecraftServer s = server;
        if (s == null || s.overworld() == null) return -1;
        try {
            Object data = s.overworld().getLevelData();
            if (rawGameTime == null) {
                java.lang.reflect.Field field = net.minecraft.world.level.storage.PrimaryLevelData.class.getDeclaredField("gameTime");
                field.setAccessible(true);
                rawGameTime = field;
            }
            return rawGameTime.getLong(data);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return -2;
        }
    }

    public static synchronized void log(String message) {
        if (out == null) {
            try {
                out = new PrintWriter(new FileWriter("raidprobe.log", true), true);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        out.println("t=" + time() + " th=" + Thread.currentThread().getName() + " " + message);
    }

    public static String caller(int skip, int count) {
        StackTraceElement[] trace = new Throwable().getStackTrace();
        StringBuilder sb = new StringBuilder();
        for (int i = skip; i < Math.min(trace.length, skip + count); i++) {
            if (sb.length() > 0) sb.append(" <- ");
            String cls = trace[i].getClassName();
            sb.append(cls.substring(cls.lastIndexOf('.') + 1)).append('.').append(trace[i].getMethodName())
                    .append(':').append(trace[i].getLineNumber());
        }
        return sb.toString();
    }
}
