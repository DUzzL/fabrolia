package com.axalotl.fabrolia.fabric.mixin.utils;

import com.axalotl.fabrolia.api.utils.ConcurrentCollections;
import com.axalotl.fabrolia.api.utils.ConcurrentList;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.util.ClassInstanceMultiMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collector;

@Mixin(value = ClassInstanceMultiMap.class, priority = 1100)
public abstract class ClassInstanceMultiMapMixin<T> extends AbstractCollection<T> {

    @Final
    @Mutable
    @Shadow
    private Map<Class<?>, List<T>> byClass;

    @Final
    @Mutable
    @Shadow
    private List<T> allInstances;

    @Inject(method = "<init>(Ljava/lang/Class;)V", at = @At("RETURN"))
    private void fabrolia$forceConcurrent(Class<T> baseClass, CallbackInfo ci) {
        this.allInstances = new ConcurrentList<>();
        this.byClass = new ConcurrentHashMap<>();
        this.byClass.put(baseClass, this.allInstances);
    }

    /** Zero-wrapper iteration (2026-07-12): the unmodifiable-iterator shim
     *  allocated two objects per section visit per entity query — 2.6 core-ms
     *  self on the 2026-07-11 worker profiles. Same list, same order; the
     *  read-only contract is unchanged (no vanilla caller removes through it). */
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "iterator")
    private Iterator<T> fabrolia$directIterator(Operation<Iterator<T>> original) {
        return this.allInstances.iterator();
    }

    /** find() paid CHM.computeIfAbsent + an unmodifiable wrapper on EVERY call
     *  (1.7 core-ms self). Hit path is now a plain get returning the live
     *  concurrent list (callers only iterate); misses build via vanilla. */
    @SuppressWarnings("unchecked")
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "find")
    private <S> java.util.Collection<S> fabrolia$findFast(Class<S> index, Operation<java.util.Collection<S>> original) {
        List<T> hit = this.byClass.get(index);
        if (hit != null) {
            return (java.util.Collection<S>) hit;
        }
        synchronized (this) {
            // Keep the canonical snapshot and typed-index publication atomic
            // with complete mutations, including Lithium RETURN callbacks.
            return original.call(index);
        }
    }


    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "add")
    private boolean fabrolia$serializeAdd(T entity, Operation<Boolean> original) {
        synchronized (this) {
            return original.call(entity);
        }
    }

    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "remove")
    private boolean fabrolia$serializeRemove(Object entity, Operation<Boolean> original) {
        synchronized (this) {
            return original.call(entity);
        }
    }

    @ModifyArg(method = "lambda$find$0", at = @At(value = "INVOKE", target = "Ljava/util/stream/Stream;collect(Ljava/util/stream/Collector;)Ljava/lang/Object;"))
    private Collector<T, ?, List<T>> overwriteCollectToList(Collector<T, ?, List<T>> collector) {
        return ConcurrentCollections.toList();
    }

    @WrapOperation(method = "remove", at = @At(value = "INVOKE", target = "Ljava/util/List;remove(Ljava/lang/Object;)Z"))
    private boolean fabrolia$nullSafeListRemove(List<T> list, Object obj, Operation<Boolean> original) {
        return list != null && original.call(list, obj);
    }

    @WrapOperation(method = "add", at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z"))
    private boolean fabrolia$nullSafeListAdd(List<T> list, Object obj, Operation<Boolean> original) {
        return list != null && original.call(list, obj);
    }
}
