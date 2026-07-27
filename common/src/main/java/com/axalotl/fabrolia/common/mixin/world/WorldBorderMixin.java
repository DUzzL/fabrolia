package com.axalotl.fabrolia.common.mixin.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Serialise {@link WorldBorder#addListener} writes (2026-06-14).
 *
 * <p>Vanilla {@code WorldBorder.listeners} is a plain {@code ArrayList} and
 * {@code addListener} just does {@code list.add(...)}. Lithium's
 * {@code addSimpleListenerOnce} handler replaces that with a single
 * {@code WorldBorderPositionListenerMulti} (backed by a
 * {@code WeakHashMap<WorldBorderListenerOnce, Object>}) shared by every
 * caller — meaning every parallel call into {@code addListener} from a
 * worker BE-tick funnels into the same non-thread-safe map.
 *
 * <p>Symptom under island-parallel BE ticks (server livelock observed
 * 2026-06-14 11:40 thread dump): every {@code BoundTickingBlockEntity.tick}
 * calls {@code startWorldBorderCaching} → {@code WorldBorder.addListener}.
 * Several workers racing {@code WeakHashMap.put} corrupted the table —
 * the server thread (which joined later) parked inside {@code put}'s
 * unbounded resize loop on the corrupted bucket chain.
 *
 * <p>Targeted sync on the high-priority entry point covers both vanilla's
 * {@code ArrayList.add} and Lithium's WeakHashMap path. {@code addListener}
 * is called once per BE per chunk-load, ~rare; {@code removeListener} is
 * the inverse and also rare — same sync. Reads (border position queries
 * inside {@code canCollideWith}, {@code isWithinBounds}) stay UNLOCKED to
 * preserve the original Lithium win (these are very hot on hopper / item
 * pickup paths).
 */
@Mixin(value = WorldBorder.class, priority = 1500)
public abstract class WorldBorderMixin {

    @Unique
    private static final Object fabrolia$listenerLock = new Object();

    @WrapMethod(method = "addListener")
    private void fabrolia$syncAddListener(BorderChangeListener listener, Operation<Void> original) {
        synchronized (fabrolia$listenerLock) {
            original.call(listener);
        }
    }

    @WrapMethod(method = "removeListener")
    private void fabrolia$syncRemoveListener(BorderChangeListener listener, Operation<Void> original) {
        synchronized (fabrolia$listenerLock) {
            original.call(listener);
        }
    }
}
