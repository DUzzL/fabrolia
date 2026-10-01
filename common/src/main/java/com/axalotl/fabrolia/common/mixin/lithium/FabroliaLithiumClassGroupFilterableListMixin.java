package com.axalotl.fabrolia.common.mixin.lithium;

import com.axalotl.fabrolia.common.parallelised.utils.OrderedGroupSnapshot;
import net.caffeinemc.mods.lithium.common.entity.EntityClassGroup;
import net.caffeinemc.mods.lithium.common.world.chunk.ClassGroupFilterableList;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.AbstractCollection;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(value = ClassInstanceMultiMap.class, priority = 1500)
public abstract class FabroliaLithiumClassGroupFilterableListMixin<T> extends AbstractCollection<T>
        implements ClassGroupFilterableList<T> {

    @Shadow
    @Final
    private Class<T> baseClass;

    @Final
    @Shadow
    private List<T> allInstances;

    /**
     * Published per-group member lists, in {@code allInstances} order (vanilla
     * and Lithium's ReferenceLinkedOpenHashSet iterate in insertion order).
     * Every mutation runs under {@code synchronized (this)}: the loader mixins
     * wrap {@code add}/{@code remove} in this monitor and the RETURN callbacks
     * below run inside those wrapped bodies, so each snapshot replacement is
     * atomic with the {@code allInstances} mutation it mirrors.
     */
    @Unique
    private final Map<EntityClassGroup, OrderedGroupSnapshot<T>> fabrolia$entitiesByGroup =
            new ConcurrentHashMap<>();

    @Unique
    private boolean fabrolia$applicable() {
        return Entity.class.isAssignableFrom(this.baseClass);
    }

    @Unique
    private OrderedGroupSnapshot<T> fabrolia$createGroupSnapshot(EntityClassGroup group) {
        // Caller holds this monitor, so no mutation can interleave with the copy.
        Object[] members = new Object[this.allInstances.size()];
        int size = 0;
        for (T candidate : this.allInstances) {
            if (candidate != null && group.contains((Entity) candidate)) {
                if (size == members.length) members = java.util.Arrays.copyOf(members, size * 2 + 1);
                members[size++] = candidate;
            }
        }
        return OrderedGroupSnapshot.ofOwned(members, size);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Collection<T> lithium$getAllOfGroupType(EntityClassGroup group) {
        if (!fabrolia$applicable()) return (Collection<T>) java.util.Collections.emptyList();
        OrderedGroupSnapshot<T> existing = this.fabrolia$entitiesByGroup.get(group);
        if (existing != null) return existing;
        synchronized (this) {
            existing = this.fabrolia$entitiesByGroup.get(group);
            if (existing == null) {
                existing = fabrolia$createGroupSnapshot(group);
                this.fabrolia$entitiesByGroup.put(group, existing);
            }
            return existing;
        }
    }

    @Inject(method = "add", at = @At("RETURN"))
    private void fabrolia$onAdd(T entity, CallbackInfoReturnable<Boolean> cir) {
        if (!fabrolia$applicable()) return;
        synchronized (this) {
            if (this.fabrolia$entitiesByGroup.isEmpty()) return;
            Entity asEntity = (Entity) entity;
            for (Map.Entry<EntityClassGroup, OrderedGroupSnapshot<T>> e
                    : this.fabrolia$entitiesByGroup.entrySet()) {
                if (e.getKey().contains(asEntity)) {
                    e.setValue(e.getValue().with(entity));
                }
            }
        }
    }

    @Inject(method = "remove", at = @At("RETURN"))
    private void fabrolia$onRemove(Object entity, CallbackInfoReturnable<Boolean> cir) {
        if (!fabrolia$applicable() || !cir.getReturnValueZ()) return;
        synchronized (this) {
            if (this.fabrolia$entitiesByGroup.isEmpty()) return;
            for (Map.Entry<EntityClassGroup, OrderedGroupSnapshot<T>> e
                    : this.fabrolia$entitiesByGroup.entrySet()) {
                OrderedGroupSnapshot<T> current = e.getValue();
                OrderedGroupSnapshot<T> next = current.without(entity);
                if (next != current) e.setValue(next);
            }
        }
    }
}
