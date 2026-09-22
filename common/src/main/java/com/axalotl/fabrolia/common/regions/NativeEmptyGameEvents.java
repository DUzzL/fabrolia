package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.api.fastutil.Int2ObjectConcurrentHashMap;
import com.axalotl.fabrolia.common.mixin.entity.NativeEntityDependencyAccessors;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.HashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gameevent.EuclideanGameEventListenerRegistry;
import net.minecraft.world.level.gameevent.GameEventDispatcher;
import net.minecraft.world.level.gameevent.GameEventListenerRegistry;

/** Inspects both audited dispatch stores without constructing registries or visiting listeners. */
final class NativeEmptyGameEvents {
    private NativeEmptyGameEvents() {}

    private static final boolean VANILLA_EVENTS = absent("net.neoforged.neoforge.common.CommonHooks");
    private static final boolean LITHIUM = !absent("net.caffeinemc.mods.lithium.common.world.LithiumData");

    private static boolean absent(String name) {
        try {
            Class.forName(name, false, ServerLevel.class.getClassLoader());
            return false;
        } catch (ClassNotFoundException absent) {
            return true;
        } catch (LinkageError incompatible) {
            return false;
        }
    }

    static boolean empty(NativeEntityMotion.Access access, NativeEntityMotion.Bounds bounds, int radius) {
        ServerLevel level = access.level();
        if (!VANILLA_EVENTS || level.getClass() != ServerLevel.class || !bounds.valid() || radius < 0) return false;
        var dispatcher = ((NativeEntityDependencyAccessors.EventLevel) level).fabrolia$gameEventDispatcher();
        if (dispatcher == null || dispatcher.getClass() != GameEventDispatcher.class
                || ((NativeEntityDependencyAccessors.EventDispatcher) dispatcher).fabrolia$eventLevel() != level) return false;
        // Native floors the origin BEFORE adding the integer radius. Check in
        // double before the int cast; overflow invalidates the interval proof.
        double x0 = Math.floor(bounds.minX) - radius, x1 = Math.floor(bounds.maxX) + radius;
        double y0 = Math.floor(bounds.minY) - radius, y1 = Math.floor(bounds.maxY) + radius;
        double z0 = Math.floor(bounds.minZ) - radius, z1 = Math.floor(bounds.maxZ) + radius;
        if (x0 < Integer.MIN_VALUE || y0 < Integer.MIN_VALUE || z0 < Integer.MIN_VALUE
                || x1 > Integer.MAX_VALUE || y1 > Integer.MAX_VALUE || z1 > Integer.MAX_VALUE
                || !access.readyColumns(x0, z0, x1, z1)) return false;
        int firstY = (int) y0 >> 4, lastY = (int) y1 >> 4;
        for (int x = (int) x0 >> 4; x <= ((int) x1 >> 4); x++) {
            for (int z = (int) z0 >> 4; z <= ((int) z1 >> 4); z++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null || chunk.getClass() != LevelChunk.class) return false;
                var nativeMap = ((NativeEntityDependencyAccessors.EventChunk) chunk).fabrolia$eventRegistries();
                if (nativeMap == null && !LITHIUM) return false;
                if (!emptyMap(nativeMap, level, firstY, lastY)) return false;
                if (LITHIUM) {
                    // Lithium can be present while its dispatch optimization is
                    // disabled. Inspect both distinct stores, not a presence guess.
                    try {
                        if (!Lithium.empty(level, ChunkPos.pack(x, z), nativeMap, firstY, lastY)) return false;
                    } catch (LinkageError incompatible) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean emptyMap(Int2ObjectMap<GameEventListenerRegistry> map,
            ServerLevel level, int firstY, int lastY) {
        if (map == null) return true; // Audited Lithium absent/lazy-map state.
        if (map.getClass() != Int2ObjectOpenHashMap.class && map.getClass() != Int2ObjectConcurrentHashMap.class) return false;
        for (int y = firstY; y <= lastY; y++) {
            GameEventListenerRegistry registry = map.get(y);
            if (registry == null || registry == GameEventListenerRegistry.NOOP) continue;
            if (registry.getClass() != EuclideanGameEventListenerRegistry.class) return false;
            var state = (NativeEntityDependencyAccessors.EventRegistry) registry;
            // Match the registry's existing synchronized mutation/visit methods.
            // Never hold the Lithium outer-store lock while taking this monitor.
            synchronized (registry) {
                var listeners = state.fabrolia$eventListeners();
                var additions = state.fabrolia$addedEventListeners();
                var removals = state.fabrolia$removedEventListeners();
                if (state.fabrolia$registryLevel() != level || state.fabrolia$registrySection() != y
                        || state.fabrolia$processingEvents()
                        || listeners == null || additions == null || removals == null
                        || listeners.getClass() != ArrayList.class || additions.getClass() != ArrayList.class
                        || removals.getClass() != HashSet.class
                        || !listeners.isEmpty() || !additions.isEmpty() || !removals.isEmpty()) return false;
            }
        }
        return true;
    }

    /** Keeps optional Lithium linkage outside the native-only execution path. */
    private static final class Lithium {
        static boolean empty(ServerLevel level, long chunk,
                Int2ObjectMap<GameEventListenerRegistry> nativeMap, int firstY, int lastY) {
            if (!(level instanceof net.caffeinemc.mods.lithium.common.world.LithiumData provider)) return false;
            var data = provider.lithium$getData();
            if (data == null || data.gameEventDispatchers() == null) return false;
            // Public get uses Fabrolia's existing private-lock adapter; raw
            // record storage accessors would bypass that synchronization.
            var map = data.gameEventDispatchers().get(chunk);
            return map == nativeMap || emptyMap(map, level, firstY, lastY);
        }
    }
}
