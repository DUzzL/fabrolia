package com.axalotl.fabrolia.common.mixin.lithium;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.caffeinemc.mods.lithium.common.util.deduplication.LithiumInterner;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Lithium's interner is a plain ObjectOpenHashSet. Vanilla/Lithium only touch it
 * from the server thread, but island block-entity ticks can make many hoppers
 * initialise inventory movement trackers concurrently. Without this monitor two
 * concurrent addOrGet/remove operations can corrupt fastutil's open-addressing
 * table during rehash.
 */
@Mixin(value = LithiumInterner.class, remap = false)
public abstract class FabroliaLithiumInternerMixin {

    @WrapMethod(method = "getCanonical")
    private synchronized Object fabrolia$syncGetCanonical(Object value, Operation<Object> original) {
        return original.call(value);
    }

    @WrapMethod(method = "deleteCanonical")
    private synchronized void fabrolia$syncDeleteCanonical(Object value, Operation<Void> original) {
        original.call(value);
    }
}
