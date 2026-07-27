package com.axalotl.fabrolia.common.mixin.lithium;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.caffeinemc.mods.lithium.common.tracking.entity.EntityMovementTrackerSection;
import net.caffeinemc.mods.lithium.common.tracking.entity.SectionedEntityMovementListener;
import net.caffeinemc.mods.lithium.common.tracking.entity.SectionedEntityMovementTracker;
import net.caffeinemc.mods.lithium.common.util.tuples.WorldSectionBox;
import net.caffeinemc.mods.lithium.common.world.LithiumData;
import net.caffeinemc.mods.lithium.mixin.util.entity_movement_tracking.PersistentEntitySectionManagerAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

@Mixin(value = SectionedEntityMovementTracker.class, remap = false)
public abstract class FabroliaLithiumEntityMovementTrackerMixin<E extends EntityAccess> {

    @Shadow
    @Final
    Object clazz;

    @Shadow
    @Final
    private int trackedIndex;

    @Shadow
    @Final
    WorldSectionBox trackedWorldSections;

    @Shadow
    ArrayList<EntitySection<E>> sortedSections;

    @Shadow
    boolean[] sectionVisible;

    @Shadow
    private int timesRegistered;

    @Shadow
    private long maxChangeTime;

    @Unique
    private ConcurrentLinkedQueue<EntityMovementTrackerSection> fabrolia$notListeningTo;

    @Unique
    private Set<SectionedEntityMovementListener> fabrolia$listeners;

    /**
     * Supersedes the shadowed plain {@code maxChangeTime} (kept only as the seed).
     * Canonical trackers are SHARED — every hopper whose pickup box lands in the
     * same section set interns the same instance, and a hopper MINECART checks its
     * tracker in the entity phase concurrently with item-movement emits on other
     * region workers. The plain field allowed (a) lost updates when two checks
     * drained sections concurrently (both read-then-wrote the max; the larger
     * time could be overwritten) and (b) reads with no happens-before at all
     * outside the phase-barrier chains — either way a later isUnchangedSince
     * could answer "unchanged" over a recorded change and the hopper/cart skips
     * its item search while items float past (autosorter unloader report,
     * 2026-07-06). CAS-max on an AtomicLong makes the merge lossless and every
     * read coherently ordered.
     */
    @Unique
    private AtomicLong fabrolia$maxChangeTime;

    @Unique
    private void fabrolia$setChanged(long atTime) {
        AtomicLong max = this.fabrolia$maxChangeTime;
        long cur;
        while (atTime > (cur = max.get())) {
            if (max.compareAndSet(cur, atTime)) {
                return;
            }
        }
    }

    @Unique
    private void fabrolia$notifyListeners() {
        Set<SectionedEntityMovementListener> listeners = this.fabrolia$listeners;
        if (!listeners.isEmpty()) {
            Iterator<SectionedEntityMovementListener> it = listeners.iterator();
            while (it.hasNext()) {
                SectionedEntityMovementListener listener = it.next();
                it.remove();
                listener.lithium$handleEntityMovement(this.clazz);
            }
        }
    }

    @Unique
    @SuppressWarnings("unchecked")
    private void fabrolia$listenToAllSections() {
        SectionedEntityMovementTracker<E> self = (SectionedEntityMovementTracker<E>) (Object) this;
        EntityMovementTrackerSection section;
        while ((section = this.fabrolia$notListeningTo.poll()) != null) {
            // Re-arm FIRST, then read the change time (Lithium's order): a movement
            // after the re-arm re-emits into the queue, a movement before it is in
            // the time we read. Publish per section, not once after the drain — a
            // concurrent check on a shared tracker must never observe "queue empty"
            // while a polled section's newer time is still sitting in a local.
            section.lithium$listenToMovementOnce(self, this.trackedIndex);
            this.fabrolia$setChanged(section.lithium$getChangeTime(this.trackedIndex));
        }
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void fabrolia$init(CallbackInfo ci) {
        this.fabrolia$notListeningTo = new ConcurrentLinkedQueue<>();
        this.fabrolia$listeners = ConcurrentHashMap.newKeySet();
        this.fabrolia$maxChangeTime = new AtomicLong(this.maxChangeTime);
    }

    @WrapMethod(method = "notifyAllListeners")
    private void fabrolia$wrapNotifyAllListeners(Operation<Void> original) {
        fabrolia$notifyListeners();
    }

    @WrapMethod(method = "isUnchangedSince")
    private boolean fabrolia$wrapIsUnchangedSince(long lastCheckedTime, Operation<Boolean> original) {
        if (lastCheckedTime <= this.fabrolia$maxChangeTime.get()) {
            return false;
        }
        if (!this.fabrolia$notListeningTo.isEmpty()) {
            this.fabrolia$listenToAllSections();
            return lastCheckedTime > this.fabrolia$maxChangeTime.get();
        }
        return true;
    }

    @WrapMethod(method = "listenToEntityMovementOnce")
    private void fabrolia$wrapListenOnce(SectionedEntityMovementListener listener, Operation<Void> original) {
        this.fabrolia$listeners.add(listener);
        if (!this.fabrolia$notListeningTo.isEmpty()) {
            this.fabrolia$listenToAllSections();
        }
    }

    @WrapMethod(method = "emitEntityMovement")
    private void fabrolia$wrapEmitEntityMovement(int classMask, EntityMovementTrackerSection section, Operation<Void> original) {
        if ((classMask & (1 << this.trackedIndex)) != 0) {
            this.fabrolia$notifyListeners();
            this.fabrolia$notListeningTo.add(section);
        }
    }

    @WrapMethod(method = "onSectionEnteredRange")
    private void fabrolia$wrapOnSectionEnteredRange(EntityMovementTrackerSection section, Operation<Void> original) {
        this.fabrolia$setChanged(this.trackedWorldSections.world().getGameTime());
        int sectionIndex = this.sortedSections.lastIndexOf(section);
        this.sectionVisible[sectionIndex] = true;
        this.fabrolia$notListeningTo.add(section);
        this.fabrolia$notifyListeners();
    }

    @WrapMethod(method = "onSectionLeftRange")
    @SuppressWarnings("unchecked")
    private void fabrolia$wrapOnSectionLeftRange(EntityMovementTrackerSection section, Operation<Void> original) {
        this.fabrolia$setChanged(this.trackedWorldSections.world().getGameTime());
        int sectionIndex = this.sortedSections.lastIndexOf(section);
        this.sectionVisible[sectionIndex] = false;
        if (!this.fabrolia$notListeningTo.remove(section)) {
            SectionedEntityMovementTracker<E> self = (SectionedEntityMovementTracker<E>) (Object) this;
            section.lithium$removeListenToMovementOnce(self, this.trackedIndex);
            this.fabrolia$notifyListeners();
        }
    }

    /**
     * Lifecycle under the tracker's instance monitor. Canonical trackers are SHARED
     * (interned per section-box + class), and hoppers/hopper carts register and
     * unregister lazily from whatever worker ticks them. Lithium's {@code register}
     * body — plain {@code timesRegistered++}, the sortedSections/sectionVisible
     * build, per-section listener adds — previously ran completely unsynchronized,
     * so two concurrent registers (or a register racing the teardown below) could
     * corrupt the shared tracker: double-built section lists, lost listener
     * registrations, a permanently blind tracker whose hoppers then skip pickups
     * (autosorter unloader report, 2026-07-06). Lifecycle is cold (BE create/
     * destroy); the hot paths (isUnchangedSince / emitEntityMovement) stay
     * lock-free. No lock cycle: the monitor is only ever nested OUTSIDE the
     * section-storage write lock and the interner monitor, never inside either.
     */
    @WrapMethod(method = "register")
    private void fabrolia$wrapRegister(ServerLevel world, Operation<Void> original) {
        synchronized (this) {
            original.call(world);
        }
    }

    @WrapMethod(method = "unRegister")
    @SuppressWarnings("unchecked")
    private void fabrolia$wrapUnRegister(ServerLevel world, Operation<Void> original) {
        synchronized (this) {
            if (--this.timesRegistered > 0) {
                return;
            }

            SectionedEntityMovementTracker<E> self = (SectionedEntityMovementTracker<E>) (Object) this;

            EntitySectionStorage<E> cache = ((PersistentEntitySectionManagerAccessor<E>)
                    ((net.caffeinemc.mods.lithium.mixin.util.entity_movement_tracking.ServerLevelAccessor) world)
                            .getEntityManager()).getCache();

            ((LithiumData) world).lithium$getData().entityMovementTrackers().deleteCanonical(self);

            ArrayList<EntitySection<E>> sections = this.sortedSections;
            for (int i = sections.size() - 1; i >= 0; i--) {
                EntitySection<E> section = sections.get(i);
                EntityMovementTrackerSection sectionAccess = (EntityMovementTrackerSection) section;
                sectionAccess.lithium$removeListener(cache, self);
                if (!this.fabrolia$notListeningTo.remove(sectionAccess)) {
                    sectionAccess.lithium$removeListenToMovementOnce(self, this.trackedIndex);
                }
            }
            this.fabrolia$setChanged(world.getGameTime());
        }
    }
}