package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.parallelised.utils.FabroliaTrackingDirtyFlag;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicBoolean;

@Mixin(PersistentEntitySectionManager.Callback.class)
public abstract class PersistentEntitySectionManagerCallbackMixin implements
        com.axalotl.fabrolia.common.parallelised.utils.PearlSectionMembershipAccess,
        com.axalotl.fabrolia.common.regions.EntityResidencyAccess {

    @Final
    @Shadow
    private EntityAccess entity;

    @Shadow
    private volatile long currentSectionKey;

    @Shadow
    private volatile EntitySection<EntityAccess> currentSection;

    @Unique
    private PersistentEntitySectionManager<?> fabrolia$outerManager;

    @Unique
    private volatile long fabrolia$residentSectionKey = NO_SECTION;

    @Unique
    private volatile boolean fabrolia$pendingRemoval = false;

    @Unique
    private final AtomicBoolean fabrolia$onRemoveFired = new AtomicBoolean();

    /** Allocated only for thrown pearls. Recovery lookups use tryLock so a
     * login/save thread never waits behind entity-move hooks and cannot form a
     * main/worker lock cycle. */
    @Unique
    private java.util.concurrent.locks.ReentrantLock fabrolia$pearlSectionLock;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void fabrolia$captureOuter(PersistentEntitySectionManager<?> entity, EntityAccess currentSectionKey, long currentSection, EntitySection<?> section, CallbackInfo ci) {
        this.fabrolia$outerManager = entity;
        if (this.entity instanceof
                net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl) {
            this.fabrolia$pearlSectionLock = new java.util.concurrent.locks.ReentrantLock();
        }
        this.fabrolia$residentSectionKey = this.currentSectionKey;
    }

    @Override
    public long fabrolia$residentSection(Entity expected) {
        if (expected != this.entity || this.fabrolia$onRemoveFired.get()
                || this.fabrolia$pendingRemoval || this.currentSection == null) return NO_SECTION;
        return this.fabrolia$residentSectionKey;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean fabrolia$isResident(Entity expected, long sectionKey) {
        if (sectionKey == NO_SECTION || fabrolia$residentSection(expected) != sectionKey) return false;
        EntitySection<EntityAccess> section = this.currentSection;
        EntitySectionStorage<EntityAccess> storage =
                (EntitySectionStorage<EntityAccess>) this.fabrolia$outerManager.sectionStorage;
        if (section == null || storage.getSection(sectionKey) != section) return false;
        boolean present = section.getEntities().anyMatch(candidate -> candidate == expected);
        return present && this.currentSection == section && storage.getSection(sectionKey) == section
                && fabrolia$residentSection(expected) == sectionKey;
    }

    /**
     * Idempotency guard for {@code Callback.onRemove}: setRemoved can be invoked
     * concurrently from worker threads (entity tick) and the chunk-unload path,
     * and the vanilla body is not re-entrant-safe (logs warnings on double-section-remove,
     * unbalances startTicking/stopTracking accounting on second call). The CAS ensures
     * exactly one execution of the vanilla body even under multi-threaded setRemoved races.
     *
     * <p>SpawnIndex is no longer drained here — it's now visibility-tracked via the
     * {@code startTracking}/{@code stopTracking} hooks in {@code PersistentEntitySectionManagerMixin}
     * (matching vanilla {@code EntityLookup.byId} semantics, removing the over-count of
     * entities in HIDDEN-but-loaded chunks). HardColliderIndex keeps the live-set
     * semantics (collision-set has no visibility gating in vanilla, since collisions
     * fire against all loaded entities in range).
     */
    @Inject(method = "onRemove", at = @At("HEAD"), cancellable = true)
    private void fabrolia$onRemoveIdempotent(Entity.RemovalReason reason, CallbackInfo ci) {
        if (!fabrolia$onRemoveFired.compareAndSet(false, true)) {
            ci.cancel();
            return;
        }
        this.fabrolia$residentSectionKey = NO_SECTION;
        if (this.entity instanceof Entity e) {
            com.axalotl.fabrolia.common.parallelised.utils.HardColliderIndex.onRemove(e);
        }
        if (this.entity instanceof net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl pearl
                && this.fabrolia$outerManager
                instanceof com.axalotl.fabrolia.common.parallelised.utils.LoadedPearlIndexAccess index) {
            index.fabrolia$unindexLoadedPearl(pearl);
        }
    }

    @SuppressWarnings("unchecked")
    @WrapOperation(method = "onRemove", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntitySection;remove(Lnet/minecraft/world/level/entity/EntityAccess;)Z"))
    private boolean fabrolia$wrapRemoveInOnRemove(EntitySection<EntityAccess> section, EntityAccess entity, Operation<Boolean> original) {
        this.fabrolia$pendingRemoval = true;

        if (original.call(section, entity)) {
            return true;
        }

        long actualKey = SectionPos.asLong(entity.blockPosition());
        if (actualKey != this.currentSectionKey) {
            EntitySectionStorage<EntityAccess> storage = (EntitySectionStorage<EntityAccess>) this.fabrolia$outerManager.sectionStorage;
            EntitySection<EntityAccess> actualSection = storage.getSection(actualKey);
            if (actualSection != null && actualSection != section) {
                if (original.call(actualSection, entity)) {
                    this.currentSection = actualSection;
                    this.currentSectionKey = actualKey;
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean fabrolia$isResidentPearl(PersistentEntitySectionManager<?> manager,
                                             Entity expected) {
        if (manager != this.fabrolia$outerManager || expected != this.entity
                || this.fabrolia$onRemoveFired.get()) {
            return false;
        }
        java.util.concurrent.locks.ReentrantLock lock = this.fabrolia$pearlSectionLock;
        if (lock == null || !lock.tryLock()) {
            return false;
        }
        try {
            if (this.fabrolia$onRemoveFired.get()) {
                return false;
            }
            EntitySection<EntityAccess> section = this.currentSection;
            long sectionKey = this.currentSectionKey;
            EntitySectionStorage<EntityAccess> storage =
                    (EntitySectionStorage<EntityAccess>) manager.sectionStorage;
            if (section != null && storage.getSection(sectionKey) == section) {
                boolean[] exact = {false};
                section.getEntities().forEach(candidate -> {
                    if (candidate == expected) exact[0] = true;
                });
                // onRemove is not required to take this monitor. Its
                // AtomicBoolean is therefore rechecked after the scan so
                // an entity entering removal cannot be adopted as live.
                return exact[0]
                        && storage.getSection(sectionKey) == section
                        && !this.fabrolia$onRemoveFired.get();
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    @WrapMethod(method = "onMove")
    private void fabrolia$onMove(Operation<Void> original) {
        if (this.fabrolia$pendingRemoval) {
            return;
        }
        if (this.entity instanceof
                net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl) {
            java.util.concurrent.locks.ReentrantLock lock = this.fabrolia$pearlSectionLock;
            lock.lock();
            try {
                // Only the vanilla section remove/add + key publication is
                // part of the residency transaction. Do not extend this lock
                // into census/tracking hooks which may acquire unrelated
                // subsystem locks.
                original.call();
            } finally {
                lock.unlock();
            }
        } else {
            original.call();
        }
        long sectionKey = this.currentSectionKey;
        if (this.fabrolia$residentSectionKey != sectionKey) this.fabrolia$residentSectionKey = sectionKey;
        fabrolia$afterMove();
    }

    @Unique
    private void fabrolia$afterMove() {
        // Entity crossed a section — VMP's NearbyEntityTracking tick0 phase 1 needs to
        // see this. Bump the global dirty counter so the next tick0 doesn't skip.
        FabroliaTrackingDirtyFlag.markDirty();

        if (this.fabrolia$pendingRemoval) {
            this.currentSection.remove(this.entity);
        }
        // Census membership on section crossings is healed inside original.call():
        // the transfer's EntitySection.add runs the EntitySectionAddIndexMixin
        // choke-point hook (task #32) — no separate re-add needed here.
        // Incremental census (task #21): the entity's CHUNK may have changed —
        // re-evaluate its ledgered contribution at the new position.
        if (this.entity instanceof Entity e
                && e.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            com.axalotl.fabrolia.common.parallelised.utils.SpawnCensus.update(serverLevel, e, null);
        }
    }
}
