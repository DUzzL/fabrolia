package com.axalotl.fabrolia.common.mixin.utils;

import com.axalotl.fabrolia.common.utils.StatCriteriaPublication;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.stats.Stat;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;
import java.util.Set;

/** Cache-only critical sections; never hold the criteria monitor while resolving a StatType. */
@Mixin(ObjectiveCriteria.class)
public abstract class ObjectiveCriteriaMixin implements StatCriteriaPublication {
    @Shadow @Final private static Map<String, ObjectiveCriteria> CRITERIA_CACHE;
    @Shadow @Final private static Map<String, ObjectiveCriteria> CUSTOM_CRITERIA;

    @Shadow @Final private String name;

    @WrapOperation(
            method = "<init>(Ljava/lang/String;ZLnet/minecraft/world/scores/criteria/ObjectiveCriteria$RenderType;)V",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
            require = 1, allow = 1)
    private Object fabrolia$publishCompleteCriteria(Map<String, ObjectiveCriteria> cache,
                                                   Object name, Object criterion,
                                                   Operation<Object> original) {
        // Native Stat's type/value/formatter are still uninitialized in its superclass
        // constructor. StatCriteriaMixin publishes it after those final fields are set.
        if (criterion instanceof Stat<?>) return null;
        synchronized (cache) {
            return original.call(cache, name, criterion);
        }
    }

    @Override
    public void fabrolia$publishInitializedStat() {
        synchronized (CRITERIA_CACHE) {
            CRITERIA_CACHE.put(this.name, (ObjectiveCriteria) (Object) this);
        }
    }

    @WrapOperation(
            method = "byName(Ljava/lang/String;)Ljava/util/Optional;",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"),
            require = 1, allow = 1)
    private static Object fabrolia$readCriteria(Map<String, ObjectiveCriteria> cache,
                                               Object name, Operation<Object> original) {
        synchronized (cache) {
            return original.call(cache, name);
        }
    }

    @WrapOperation(
            method = "registerCustom(Ljava/lang/String;ZLnet/minecraft/world/scores/criteria/ObjectiveCriteria$RenderType;)Lnet/minecraft/world/scores/criteria/ObjectiveCriteria;",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
            require = 1, allow = 1)
    private static Object fabrolia$registerCustomName(Map<String, ObjectiveCriteria> cache,
                                                     Object name, Object criterion,
                                                     Operation<Object> original) {
        synchronized (cache) {
            return original.call(cache, name, criterion);
        }
    }

    @WrapMethod(method = "getCustomCriteriaNames()Ljava/util/Set;", require = 1, allow = 1)
    private static Set<String> fabrolia$customNamesSnapshot(Operation<Set<String>> original) {
        synchronized (CUSTOM_CRITERIA) {
            return original.call();
        }
    }
}
