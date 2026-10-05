package com.axalotl.fabrolia.common.mixin.lithium;

import com.axalotl.fabrolia.common.vmp.PublishedLong2ObjectMap;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.caffeinemc.mods.lithium.common.world.GameEventDispatcherStorage;
import net.minecraft.world.level.gameevent.GameEventListenerRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Lithium's per-level chunk → game-event dispatcher map is a plain fastutil map.
 * Chunk load/unload/replace stay serialized under one monitor; every game event
 * (steps, block changes, projectile hits) reads a published mirror the writers
 * update inside that monitor, so dispatch on the region workers never contends
 * on it. The mirror holds exactly the storage's entry for every written key.
 */
@Mixin(value = GameEventDispatcherStorage.class, remap = false)
public class LithiumGameEventDispatcherStorage {

    @Unique
    private final Object lock = new Object();

    @Unique
    private final PublishedLong2ObjectMap<Int2ObjectMap<GameEventListenerRegistry>> fabrolia$published =
            new PublishedLong2ObjectMap<>();

    @Unique
    private void fabrolia$publish(long pos) {
        Int2ObjectMap<GameEventListenerRegistry> current =
                ((GameEventDispatcherStorage) (Object) this).storage().get(pos);
        if (current == null) {
            fabrolia$published.remove(pos);
        } else {
            fabrolia$published.put(pos, current);
        }
    }

    @WrapMethod(method = "addChunk")
    private void addChunk(long pos, Int2ObjectMap<GameEventListenerRegistry> dispatchers, Operation<Void> original) {
        synchronized (lock) {
            try {
                original.call(pos, dispatchers);
            } finally {
                fabrolia$publish(pos);
            }
        }
    }

    @WrapMethod(method = "removeChunk")
    private void removeChunk(long pos, Operation<Void> original) {
        synchronized (lock) {
            try {
                original.call(pos);
            } finally {
                fabrolia$publish(pos);
            }
        }
    }

    @WrapMethod(method = "replace")
    private void replace(long pos, Int2ObjectMap<GameEventListenerRegistry> dispatchers, Operation<Void> original) {
        synchronized (lock) {
            try {
                original.call(pos, dispatchers);
            } finally {
                fabrolia$publish(pos);
            }
        }
    }

    @WrapMethod(method = "get")
    private Int2ObjectMap<GameEventListenerRegistry> get(long pos, Operation<Int2ObjectMap<GameEventListenerRegistry>> original) {
        return fabrolia$published.get(pos);
    }
}
