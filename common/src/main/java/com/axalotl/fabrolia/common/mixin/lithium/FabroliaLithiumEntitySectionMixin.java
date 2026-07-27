package com.axalotl.fabrolia.common.mixin.lithium;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.caffeinemc.mods.lithium.common.entity.PositionedEntityTrackingSection;
import net.caffeinemc.mods.lithium.common.tracking.entity.EntityMovementTrackerSection;
import net.caffeinemc.mods.lithium.common.tracking.entity.MovementTrackerHelper;
import net.caffeinemc.mods.lithium.common.tracking.entity.SectionedEntityMovementTracker;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.Visibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

@Mixin(value = EntitySection.class, priority = 1500)
public abstract class FabroliaLithiumEntitySectionMixin<T extends EntityAccess>
        implements EntityMovementTrackerSection, PositionedEntityTrackingSection {

    @Shadow
    private Visibility chunkStatus;

    @Shadow
    public abstract boolean isEmpty();

    @Unique
    private final Set<SectionedEntityMovementTracker<?>> fabrolia$visibilityListeners = ConcurrentHashMap.newKeySet();

    @Unique
    private final AtomicReferenceArray<ConcurrentLinkedQueue<SectionedEntityMovementTracker<?>>> fabrolia$onceListeners = new AtomicReferenceArray<>(MovementTrackerHelper.NUM_MOVEMENT_NOTIFYING_CLASSES);

    @Unique
    private final AtomicLongArray fabrolia$lastMovementByType =
            new AtomicLongArray(MovementTrackerHelper.NUM_MOVEMENT_NOTIFYING_CLASSES);

    @Unique
    private ConcurrentLinkedQueue<SectionedEntityMovementTracker<?>> fabrolia$onceQueue(int trackedClass) {
        ConcurrentLinkedQueue<SectionedEntityMovementTracker<?>> q = this.fabrolia$onceListeners.get(trackedClass);
        if (q != null) return q;
        ConcurrentLinkedQueue<SectionedEntityMovementTracker<?>> fresh = new ConcurrentLinkedQueue<>();
        return this.fabrolia$onceListeners.compareAndSet(trackedClass, null, fresh) ? fresh : this.fabrolia$onceListeners.get(trackedClass);
    }

    @Override
    public void lithium$addListener(SectionedEntityMovementTracker<?> listener) {
        this.fabrolia$visibilityListeners.add(listener);
        if (this.chunkStatus.isAccessible()) {
            listener.onSectionEnteredRange(this);
        }
    }

    @Override
    public void lithium$removeListener(EntitySectionStorage<?> sectionedEntityCache, SectionedEntityMovementTracker<?> listener) {
        boolean removed = this.fabrolia$visibilityListeners.remove(listener);
        if (this.chunkStatus.isAccessible() && removed) {
            listener.onSectionLeftRange(this);
        }
        if (this.isEmpty()) {
            sectionedEntityCache.remove(this.lithium$getPos());
        }
    }

    @Override
    public void lithium$trackEntityMovement(int notificationMask, long time) {
        int size = this.fabrolia$lastMovementByType.length();
        int mask;
        for (int idx = Integer.numberOfTrailingZeros(notificationMask); idx < size; ) {
            this.fabrolia$lastMovementByType.set(idx, time);

            ConcurrentLinkedQueue<SectionedEntityMovementTracker<?>> q = this.fabrolia$onceListeners.get(idx);
            if (q != null) {
                SectionedEntityMovementTracker<?> listener;
                while ((listener = q.poll()) != null) {
                    listener.emitEntityMovement(notificationMask, this);
                }
            }

            mask = 0xffff_fffe << idx;
            idx = Integer.numberOfTrailingZeros(notificationMask & mask);
        }
    }

    @Override
    public long lithium$getChangeTime(int trackedClass) {
        return this.fabrolia$lastMovementByType.get(trackedClass);
    }

    @ModifyReturnValue(method = "isEmpty()Z", at = @At(value = "RETURN"))
    public boolean modifyIsEmpty(boolean previousIsEmpty) {
        return previousIsEmpty && this.fabrolia$visibilityListeners.isEmpty();
    }

    @Inject(method = "updateChunkStatus(Lnet/minecraft/world/level/entity/Visibility;)Lnet/minecraft/world/level/entity/Visibility;", at = @At("HEAD"))
    public void fabrolia$notifyVisibilityListeners(Visibility newStatus, CallbackInfoReturnable<Visibility> cir) {
        if (this.chunkStatus.isAccessible() == newStatus.isAccessible()) return;
        if (newStatus.isAccessible()) {
            for (SectionedEntityMovementTracker<?> listener : this.fabrolia$visibilityListeners) {
                listener.onSectionEnteredRange(this);
            }
        } else {
            for (SectionedEntityMovementTracker<?> listener : this.fabrolia$visibilityListeners) {
                listener.onSectionLeftRange(this);
            }
        }
    }

    @Override
    public <S, E extends EntityAccess> void lithium$listenToMovementOnce(SectionedEntityMovementTracker<E> listener, int trackedClass) {
        this.fabrolia$onceQueue(trackedClass).add(listener);
    }

    @Override
    public <S, E extends EntityAccess> void lithium$removeListenToMovementOnce(SectionedEntityMovementTracker<E> listener, int trackedClass) {
        ConcurrentLinkedQueue<SectionedEntityMovementTracker<?>> q = this.fabrolia$onceListeners.get(trackedClass);
        if (q != null) {
            q.remove(listener);
        }
    }
}