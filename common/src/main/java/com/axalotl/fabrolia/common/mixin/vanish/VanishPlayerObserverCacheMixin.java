package com.axalotl.fabrolia.common.mixin.vanish;

import com.axalotl.fabrolia.common.regions.NativePlayerObservations;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Vanish 1.6.15's merged lazy getter writes vanished$vanished and vanished$dirty;
 * its dirty mutator must participate in the same private cache protocol. The
 * original getter only reads PDA storage: even its cold load does not insert into
 * PDA's maps. Lifecycle/storage writers still need real global exclusion.
 * A final-class certificate may publish those native fields and bypass this
 * monitor for clean reads only; unknown method graphs keep both full wrappers.
 *
 * <p>Like the Lithium merged-method adapters, this is gated by MixinCanceller
 * and applied after the provider. Never synchronize on the player, hold this lock
 * around setVanished/events, or turn it into authority for a PDA storage write.
 */
@Mixin(value = ServerPlayer.class, priority = 1500)
public abstract class VanishPlayerObserverCacheMixin implements NativePlayerObservations.VanishCache {
    @Unique
    private final Object fabrolia$vanishObserverCacheLock = new Object();

    @WrapMethod(method = "vanish$isVanished()Z", remap = false, require = 1)
    private boolean fabrolia$readVanishObserverCache(Operation<Boolean> original) {
        synchronized (fabrolia$vanishObserverCacheLock) {
            return original.call();
        }
    }

    @WrapMethod(method = "vanish$setDirty()V", remap = false, require = 1)
    private void fabrolia$dirtyVanishObserverCache(Operation<Void> original) {
        synchronized (fabrolia$vanishObserverCacheLock) {
            original.call();
        }
    }
}
