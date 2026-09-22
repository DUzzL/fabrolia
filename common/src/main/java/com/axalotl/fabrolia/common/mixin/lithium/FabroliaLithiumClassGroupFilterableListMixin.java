package com.axalotl.fabrolia.common.mixin.lithium;

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
import java.util.Set;
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

    @Unique
    private final Map<EntityClassGroup, Set<T>> fabrolia$entitiesByGroup = new ConcurrentHashMap<>();

    @Unique
    private boolean fabrolia$applicable() {
        return Entity.class.isAssignableFrom(this.baseClass);
    }

    @Unique
    private Set<T> fabrolia$createGroupSet(EntityClassGroup group) {
        // Both loader mixins install ConcurrentList here, so iteration is an
        // internally locked snapshot. The outer monitor pairs that snapshot's
        // publication with add/remove RETURN callbacks: an overlapping mutation
        // is either in the snapshot or patched into every published group set
        // before the mutating ClassInstanceMultiMap operation returns.
        Set<T> set = ConcurrentHashMap.newKeySet();
        for (T candidate : this.allInstances) {
            if (candidate != null && group.contains((Entity) candidate)) {
                set.add(candidate);
            }
        }
        return set;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Collection<T> lithium$getAllOfGroupType(EntityClassGroup group) {
        if (!fabrolia$applicable()) return (Collection<T>) java.util.Collections.emptyList();
        Set<T> existing = this.fabrolia$entitiesByGroup.get(group);
        if (existing != null) return existing;
        synchronized (this) {
            existing = this.fabrolia$entitiesByGroup.get(group);
            if (existing == null) {
                existing = fabrolia$createGroupSet(group);
                this.fabrolia$entitiesByGroup.put(group, existing);
            }
            return existing;
        }
    }

    @Inject(method = "add", at = @At("RETURN"))
    private void fabrolia$onAdd(T entity, CallbackInfoReturnable<Boolean> cir) {
        if (!fabrolia$applicable()) return;
        // Serialize only mutations with a first group snapshot. The established
        // query path remains a lock-free CHM get + weakly-consistent set walk.
        synchronized (this) {
            if (this.fabrolia$entitiesByGroup.isEmpty()) return;
            Entity asEntity = (Entity) entity;
            for (Map.Entry<EntityClassGroup, Set<T>> e
                    : this.fabrolia$entitiesByGroup.entrySet()) {
                if (e.getKey().contains(asEntity)) {
                    e.getValue().add(entity);
                }
            }
        }
    }

    @Inject(method = "remove", at = @At("RETURN"))
    private void fabrolia$onRemove(Object entity, CallbackInfoReturnable<Boolean> cir) {
        if (!fabrolia$applicable()) return;
        synchronized (this) {
            if (this.fabrolia$entitiesByGroup.isEmpty()) return;
            for (Set<T> set : this.fabrolia$entitiesByGroup.values()) {
                set.remove(entity);
            }
        }
    }
}
