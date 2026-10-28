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
import java.util.List;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.EntityTickListSnapshotAccess;

@Mixin(EntityTickList.class)
public class EntityTickListMixin implements EntityTickListSnapshotAccess {
    @Shadow private Int2ObjectMap<Entity> active;
    @Shadow private Int2ObjectMap<Entity> passive;
    @Shadow private Int2ObjectMap<Entity> iterated;

    /** Bumped by every membership mutation, under this monitor. */
    @Unique
    private long fabrolia$membershipVersion;

    /** Last immutable snapshot and the membership version it was taken at.
     *  No initializers: the defaults (null, 0) mean "no snapshot yet". */
    @Unique
    private List<Entity> fabrolia$snapshot;

    @Unique
    private long fabrolia$snapshotVersion;

    @Override
    public List<Entity> fabrolia$snapshotEntities() {
        synchronized (this) {
            // Independent island cuts must not enter vanilla's single-iterator
            // slot. Copy under the same monitor used by add/remove so births
            // after this cut cannot enter its finite pass.
            //
            // Every island unit takes this whole-level cut, so islands that run
            // between two membership changes share one immutable copy instead of
            // copying ~10k references each. Identical content and order: the
            // list can only change through add/remove, which bump the version.
            List<Entity> cached = this.fabrolia$snapshot;
            if (cached != null && this.fabrolia$snapshotVersion == this.fabrolia$membershipVersion) {
                return cached;
            }
            List<Entity> fresh = List.copyOf(this.active.values());
            this.fabrolia$snapshot = fresh;
            this.fabrolia$snapshotVersion = this.fabrolia$membershipVersion;
            return fresh;
        }
    }

    /**
     * Preserve Mojang's exact active/passive linked-map semantics while making the
     * state transition safe. The consumer itself must run outside this monitor: it
     * may legitimately add/remove an entity before returning.
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
            this.fabrolia$membershipVersion++;
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
            this.fabrolia$membershipVersion++;
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
