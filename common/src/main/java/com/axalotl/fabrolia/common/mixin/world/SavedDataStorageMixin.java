package com.axalotl.fabrolia.common.mixin.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Island workers look up saved data concurrently: map items held by island
 * players and framed maps on island trackers call {@code MapItem.getSavedData},
 * and a new map allocates its id through {@code computeIfAbsent(MapIndex)}.
 * Vanilla's cache is a plain HashMap written on a miss.
 *
 * <p>Hits stay lock-free on a concurrent map. A miss (disk read), {@code set}
 * and {@code computeIfAbsent} run under the storage monitor, so one type is
 * loaded or created exactly once and a disk miss can never overwrite a newer
 * {@code set}. The save pass iterates the concurrent map (weakly consistent,
 * as vanilla's own single-threaded order is).
 */
@Mixin(SavedDataStorage.class)
public abstract class SavedDataStorageMixin {
    @Shadow
    private final Map<SavedDataType<?>, Optional<SavedData>> cache = new ConcurrentHashMap<>();

    @WrapMethod(method = "get")
    private <T extends SavedData> T fabrolia$lockFreeHit(SavedDataType<T> type, Operation<T> original) {
        if (this.cache.containsKey(type)) return original.call(type);
        synchronized (this) {
            return original.call(type);
        }
    }

    @WrapMethod(method = "computeIfAbsent")
    private synchronized <T extends SavedData> T fabrolia$serializedCreate(
            SavedDataType<T> type, Operation<T> original) {
        return original.call(type);
    }

    @WrapMethod(method = "set")
    private synchronized <T extends SavedData> void fabrolia$serializedSet(
            SavedDataType<T> type, T data, Operation<Void> original) {
        original.call(type, data);
    }
}
