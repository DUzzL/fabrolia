package com.axalotl.fabrolia.common.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Map;
import java.util.Set;

/**
 * Vanilla's modifier maps (EnumMap of Object2ObjectOpenHashMap per operation,
 * Object2ObjectArrayMap by id) are kept, so iteration order — and with it
 * calculateValue's floating-point summation order — is exactly vanilla's.
 * Every method that reads or writes those maps runs under the instance
 * monitor instead. A dirty getValue recomputes and clears {@code dirty} under
 * the monitor too, so a concurrent modifier change cannot be overwritten by a
 * stale recompute; the clean path (cached value) takes no lock.
 * The monitor is a leaf apart from the instance's own onDirty callback,
 * which only records the instance in its AttributeMap's concurrent sets.
 */
@Mixin(AttributeInstance.class)
public class AttributeInstanceMixin {

    @org.spongepowered.asm.mixin.Shadow
    private boolean dirty;

    @org.spongepowered.asm.mixin.Shadow
    private double cachedValue;

    /** Equal identity hashes of two instances: one global order for replaceFrom. */
    @org.spongepowered.asm.mixin.Unique
    private static final Object fabrolia$REPLACE_TIE = new Object();

    @WrapMethod(method = "getValue")
    private double fabrolia$getValue(Operation<Double> original) {
        if (!this.dirty) return this.cachedValue;
        synchronized (this) {
            return original.call();
        }
    }

    @WrapMethod(method = "setBaseValue")
    private synchronized void fabrolia$setBaseValue(double value, Operation<Void> original) {
        original.call(value);
    }

    @WrapMethod(method = "getModifiers(Lnet/minecraft/world/entity/ai/attributes/AttributeModifier$Operation;)Ljava/util/Map;")
    private synchronized Map<Identifier, AttributeModifier> fabrolia$modifiersFor(
            AttributeModifier.Operation operation, Operation<Map<Identifier, AttributeModifier>> original) {
        return original.call(operation);
    }

    @WrapMethod(method = "getModifiers()Ljava/util/Set;")
    private synchronized Set<AttributeModifier> fabrolia$modifiers(Operation<Set<AttributeModifier>> original) {
        return original.call();
    }

    @WrapMethod(method = "getPermanentModifiers")
    private synchronized Set<AttributeModifier> fabrolia$permanentModifiers(Operation<Set<AttributeModifier>> original) {
        return original.call();
    }

    @WrapMethod(method = "getModifier")
    private synchronized AttributeModifier fabrolia$modifier(Identifier id, Operation<AttributeModifier> original) {
        return original.call(id);
    }

    @WrapMethod(method = "hasModifier")
    private synchronized boolean fabrolia$hasModifier(Identifier id, Operation<Boolean> original) {
        return original.call(id);
    }

    @WrapMethod(method = "addModifier")
    private synchronized void fabrolia$addModifier(AttributeModifier modifier, Operation<Void> original) {
        original.call(modifier);
    }

    @WrapMethod(method = "addOrUpdateTransientModifier")
    private synchronized void fabrolia$addOrUpdateTransient(AttributeModifier modifier, Operation<Void> original) {
        original.call(modifier);
    }

    @WrapMethod(method = "addOrReplacePermanentModifier")
    private synchronized void fabrolia$addOrReplacePermanent(AttributeModifier modifier, Operation<Void> original) {
        original.call(modifier);
    }

    @WrapMethod(method = "addPermanentModifier")
    private synchronized void fabrolia$addPermanent(AttributeModifier modifier, Operation<Void> original) {
        original.call(modifier);
    }

    @WrapMethod(method = "removeModifier(Lnet/minecraft/resources/Identifier;)Z")
    private synchronized boolean fabrolia$removeModifier(Identifier id, Operation<Boolean> original) {
        return original.call(id);
    }

    @WrapMethod(method = "removeModifiers")
    private synchronized void fabrolia$removeModifiers(Operation<Void> original) {
        original.call();
    }

    @WrapMethod(method = "calculateValue")
    private synchronized double fabrolia$calculateValue(Operation<Double> original) {
        return original.call();
    }

    @WrapMethod(method = "pack")
    private synchronized AttributeInstance.Packed fabrolia$pack(Operation<AttributeInstance.Packed> original) {
        return original.call();
    }

    @WrapMethod(method = "apply")
    private synchronized void fabrolia$apply(AttributeInstance.Packed packed, Operation<Void> original) {
        original.call(packed);
    }

    /** Both maps are read/written: take the two monitors in a fixed order. */
    @WrapMethod(method = "replaceFrom")
    private void fabrolia$replaceFrom(AttributeInstance other, Operation<Void> original) {
        Object self = this;
        if (other == self) {
            synchronized (self) {
                original.call(other);
            }
            return;
        }
        int selfHash = System.identityHashCode(self);
        int otherHash = System.identityHashCode(other);
        if (selfHash == otherHash) {
            synchronized (fabrolia$REPLACE_TIE) {
                synchronized (self) {
                    synchronized (other) {
                        original.call(other);
                    }
                }
            }
            return;
        }
        Object first = selfHash < otherHash ? self : other;
        Object second = first == self ? other : self;
        synchronized (first) {
            synchronized (second) {
                original.call(other);
            }
        }
    }
}
