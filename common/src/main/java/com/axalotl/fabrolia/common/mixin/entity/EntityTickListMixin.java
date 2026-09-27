package com.axalotl.fabrolia.common.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.function.Consumer;
import com.axalotl.fabrolia.common.regions.RegionScheduler;

@Mixin(EntityTickList.class)
public class EntityTickListMixin {
    @Shadow private Int2ObjectMap<Entity> active;
    @Shadow private Int2ObjectMap<Entity> passive;
    @Shadow private Int2ObjectMap<Entity> iterated;

    /**
     * Preserve Mojang's exact active/passive linked-map semantics while making the
     * state transition safe when one consumer body migrates to the player actor.
     * The consumer itself must run outside this monitor: main waits for the actor,
     * and that actor may legitimately add/remove an entity before returning.
     */
    @Unique
    private void fabrolia$ensureMutableActive() {
        if (this.iterated != this.active) return;
        this.passive.clear();
        for (Int2ObjectMap.Entry<Entity> entry
                : Int2ObjectMaps.fastIterable(this.active)) {
            this.passive.put(entry.getIntKey(), entry.getValue());
        }
        Int2ObjectMap<Entity> previous = this.active;
        this.active = this.passive;
        this.passive = previous;
    }

    @WrapMethod(method = "add")
    private void add(Entity entity, Operation<Void> original) {
        synchronized (this) {
            fabrolia$ensureMutableActive();
            this.active.put(entity.getId(), entity);
        }
        // Region-owned entity index mirrors EntityTickList membership (no-op when the
        // region scheduler is dormant).
        RegionScheduler.onEntityAdd(entity);
    }

    @WrapMethod(method = "remove")
    private void remove(Entity entity, Operation<Void> original) {
        synchronized (this) {
            fabrolia$ensureMutableActive();
            this.active.remove(entity.getId());
        }
        RegionScheduler.onEntityRemove(entity);
    }

    @WrapMethod(method = "contains")
    private boolean contains(Entity entity, Operation<Boolean> original) {
        synchronized (this) {
            return this.active.containsKey(entity.getId());
        }
    }

    @WrapMethod(method = "ensureActiveIsNotIterated")
    private void ensureActiveIsNotIterated(Operation<Void> original) {
        synchronized (this) {
            fabrolia$ensureMutableActive();
        }
    }

    @WrapMethod(method = "forEach")
    private void forEach(Consumer<Entity> output, Operation<Void> original) {
        final Int2ObjectMap<Entity> snapshot;
        synchronized (this) {
            if (this.iterated != null) {
                throw new UnsupportedOperationException("Only one concurrent iteration supported");
            }
            snapshot = this.active;
            this.iterated = snapshot;
        }
        try {
            for (Entity entity : snapshot.values()) {
                output.accept(entity);
            }
        } finally {
            synchronized (this) {
                if (this.iterated != snapshot) {
                    throw new IllegalStateException("EntityTickList iteration identity changed");
                }
                this.iterated = null;
            }
        }
    }
}
