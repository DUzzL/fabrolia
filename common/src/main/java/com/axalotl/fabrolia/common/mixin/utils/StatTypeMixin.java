package com.axalotl.fabrolia.common.mixin.utils;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatFormatter;
import net.minecraft.stats.StatType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Protects the shared lazy cache, not the player predicate that consumes it. */
@Mixin(StatType.class)
public abstract class StatTypeMixin<T> {
    @Shadow @Final private Map<T, Stat<T>> map;

    @WrapMethod(
            method = "get(Ljava/lang/Object;Lnet/minecraft/stats/StatFormatter;)Lnet/minecraft/stats/Stat;",
            require = 1, allow = 1)
    private Stat<T> fabrolia$canonicalStat(T value, StatFormatter formatter,
                                          Operation<Stat<T>> original) {
        synchronized (this.map) {
            // Keep native IdentityHashMap semantics and the first creator's formatter.
            // The one-argument get and stream decoder delegate to this overload.
            return original.call(value, formatter);
        }
    }

    @WrapMethod(method = "contains(Ljava/lang/Object;)Z", require = 1, allow = 1)
    private boolean fabrolia$containsStat(T value, Operation<Boolean> original) {
        synchronized (this.map) {
            return original.call(value);
        }
    }

    @WrapMethod(method = "iterator()Ljava/util/Iterator;", require = 1, allow = 1)
    private Iterator<Stat<T>> fabrolia$statSnapshot(Operation<Iterator<Stat<T>>> original) {
        synchronized (this.map) {
            // Never expose the live values iterator after releasing the cache monitor.
            // Removal is unsupported: consumers cannot invalidate canonical Stats.
            return List.copyOf(this.map.values()).iterator();
        }
    }
}
