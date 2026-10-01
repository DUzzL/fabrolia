package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.api.fastutil.Long2ObjectConcurrentHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.level.entity.EntitySectionStorage;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings({"rawtypes", "unchecked"})
@Mixin(PersistentEntitySectionManager.class)
public abstract class PersistentEntitySectionManagerMixin<T extends EntityAccess> implements AutoCloseable,
        com.axalotl.fabrolia.common.parallelised.utils.TickingChunksAccess,
        com.axalotl.fabrolia.common.parallelised.utils.LoadedPearlIndexAccess {

    @Mutable
    @Shadow
    @Final
    private Long2ObjectMap<Visibility> chunkVisibility;

    @Mutable
    @Shadow
    @Final
    private Long2ObjectMap chunkLoadStatuses;

    @Mutable
    @Shadow
    @Final
    private Set<UUID> knownUuids;

    @Mutable
    @Shadow
    @Final
    private LongSet chunksToUnload;

    @Shadow
    @Final
    public EntitySectionStorage<T> sectionStorage;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void fabrolia$replaceMaps(CallbackInfo ci) {
        // Explicit ctor initialization: never depend on Mixin merging source
        // instance-initializer bytecode for target-owned @Unique fields.
        this.fabrolia$tickingChunks =
                new com.axalotl.fabrolia.api.fastutil.Long2IntConcurrentHashMap();
        this.fabrolia$loadedPearls = new java.util.concurrent.ConcurrentHashMap<>();

        Long2ObjectConcurrentHashMap<Visibility> concurrentVisibility = new Long2ObjectConcurrentHashMap<>();
        concurrentVisibility.defaultReturnValue(Visibility.HIDDEN);
        concurrentVisibility.putAll(this.chunkVisibility);
        this.chunkVisibility = concurrentVisibility;

        this.sectionStorage.intialSectionVisibility = concurrentVisibility;

        Long2ObjectConcurrentHashMap concurrentStatuses = new Long2ObjectConcurrentHashMap();
        concurrentStatuses.defaultReturnValue(this.chunkLoadStatuses.defaultReturnValue());
        concurrentStatuses.putAll(this.chunkLoadStatuses);
        this.chunkLoadStatuses = concurrentStatuses;

        Set<UUID> concurrentUuids = ConcurrentHashMap.newKeySet();
        concurrentUuids.addAll(this.knownUuids);
        this.knownUuids = concurrentUuids;

        this.chunksToUnload = LongSets.synchronize(new LongOpenHashSet(this.chunksToUnload));
    }

    /**
     * Incremental mirror of "which chunk positions are entity-TICKING" — the
     * exact predicate {@code canPositionTick} answers, maintained at its single
     * mutation funnel (both {@code updateChunkStatus} overloads delegate here).
     * The per-tick spawn gate used to re-derive this for every spawning chunk
     * (accessor → concurrent-map get → enum check, ~1.6 ms/tick at ~2.5k chunks,
     * 2026-07-11 profiles); reading this set is one lock-free probe. The gate
     * itself stays a frozen per-tick snapshot — whole-phase coherence for the
     * spawn pass is the point of its existence — only its build gets cheap.
     */
    @org.spongepowered.asm.mixin.Unique
    private com.axalotl.fabrolia.api.fastutil.Long2IntConcurrentHashMap fabrolia$tickingChunks;

    /** Includes HIDDEN-but-loaded pearls; unlike public Level.getEntity(UUID),
     * membership follows successful PESM add through the exact removal callback. */
    @org.spongepowered.asm.mixin.Unique
    private java.util.concurrent.ConcurrentHashMap<java.util.UUID,
            net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl>
            fabrolia$loadedPearls;

    @Override
    public com.axalotl.fabrolia.api.fastutil.Long2IntConcurrentHashMap fabrolia$tickingChunks() {
        return fabrolia$tickingChunks;
    }

    @Override
    @SuppressWarnings("unchecked")
    public net.minecraft.world.level.entity.EntitySectionStorage<net.minecraft.world.level.entity.EntityAccess> fabrolia$sectionStorage() {
        return (net.minecraft.world.level.entity.EntitySectionStorage<net.minecraft.world.level.entity.EntityAccess>) this.sectionStorage;
    }

    @Override
    public net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl
    fabrolia$loadedPearl(java.util.UUID uuid) {
        var pearl = fabrolia$loadedPearls.get(uuid);
        if (pearl == null) {
            return null;
        }
        if (pearl.isRemoved()) {
            fabrolia$loadedPearls.remove(uuid, pearl);
            return null;
        }

        // Callback.onRemove is the normal index drain, but C2ME can drop a
        // section without every vanilla callback. Validate against the
        // callback's authoritative currentSection/currentSectionKey pair.
        // Its pearl-only nonblocking lock brackets vanilla onMove, avoiding
        // the common position-updated-before-section-transfer false negative
        // without ever parking a login thread. HIDDEN deliberately counts.
        net.minecraft.world.level.entity.EntityInLevelCallback callback =
                ((com.axalotl.fabrolia.common.mixin.entity.EntityLevelCallbackAccessor)
                        (Object) pearl).fabrolia$getLevelCallback();
        return callback instanceof
                com.axalotl.fabrolia.common.parallelised.utils.PearlSectionMembershipAccess access
                && access.fabrolia$isResidentPearl(
                        (PersistentEntitySectionManager<?>) (Object) this, pearl)
                ? pearl : null;
    }

    @Override
    public void fabrolia$forEachResidentPearl(
            java.util.function.Consumer<net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl>
                    action) {
        for (var candidate : fabrolia$loadedPearls.values()) {
            if (candidate == null || candidate.isRemoved()) {
                continue;
            }
            // Do not trust a leaked C2ME index entry: the same strict
            // callback/current-section proof used by UUID adoption remains
            // authoritative. The logout caller owns every island claim, so a
            // legitimate pearl cannot be mid-move while this pass runs.
            if (fabrolia$loadedPearl(candidate.getUUID()) == candidate) {
                action.accept(candidate);
            }
        }
    }

    @Override
    public boolean fabrolia$hasLoadedPearlInChunk(long packedChunkPos) {
        for (var pearl : fabrolia$loadedPearls.values()) {
            // This is the last predicate before ticket destruction, not an
            // adoption authority. A callback can be exactly between old/new
            // sections (and hold the pearl-only move lock), so requiring a
            // strict residency proof here recreates the clean false-negative
            // that freezes the pearl. The hold is capped at 6000 passes;
            // therefore a stale index entry causes only a bounded false hold,
            // while a false negative can lose the loader permanently.
            if (pearl != null && !pearl.isRemoved()
                    && pearl.chunkPosition().pack() == packedChunkPos) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void fabrolia$indexLoadedPearl(
            net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl pearl) {
        fabrolia$loadedPearls.put(pearl.getUUID(), pearl);
    }

    @Override
    public void fabrolia$unindexLoadedPearl(
            net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl pearl) {
        fabrolia$loadedPearls.remove(pearl.getUUID(), pearl);
    }

    @WrapMethod(method = "updateChunkStatus(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/entity/Visibility;)V")
    private void fabrolia$trackTickingChunks(net.minecraft.world.level.ChunkPos pos, Visibility vis,
                                             Operation<Void> original) {
        original.call(pos, vis);
        long key = pos.pack();
        if (vis != null && vis.isTicking()) {
            fabrolia$tickingChunks.put(key, 1);
        } else {
            fabrolia$tickingChunks.remove(key);
        }
    }

    @WrapMethod(method = "getEffectiveStatus")
    private static <T extends EntityAccess> Visibility getEffectiveStatus(T entity, Visibility status, Operation<Visibility> original) {
        Visibility result = original.call(entity, status);
        if (result == null) {
            return entity.isAlwaysTicking() ? Visibility.TICKING : Visibility.TRACKED;
        }
        return result;
    }

    /**
     * Live-entry feed for {@link com.axalotl.fabrolia.common.parallelised.utils.HardColliderIndex}.
     *
     * <p>HEAD-reads the entity from the {@code (entity, boolean)} private {@code addEntity}
     * overload (the single funnel for both freshly-added entities and chunk-load restoration —
     * {@code addNewEntity} delegates to it). Returns at TAIL with the boolean result, so we
     * only register on success — duplicate-UUID rejections don't pollute the index.
     *
     * <p>Note: {@link com.axalotl.fabrolia.common.parallelised.utils.SpawnIndex} is intentionally
     * NOT fed here — it tracks visibility-gated entities (mirroring vanilla
     * {@code EntityLookup.byId}) via the {@code startTracking}/{@code stopTracking} hooks
     * below. Hooking {@code addEntity} would over-count entities sitting in HIDDEN-but-loaded
     * chunks (stasis-pearl chunkloaders, force-loaded farms, c2me non-ticking-vd holds).
     */
    @Inject(method = "addEntity(Lnet/minecraft/world/level/entity/EntityAccess;Z)Z", at = @At("RETURN"))
    private void fabrolia$onAddEntity(EntityAccess entityAccess, boolean newEntity,
                                   CallbackInfoReturnable<Boolean> cir) {
        if (!Boolean.TRUE.equals(cir.getReturnValue())) return;
        if (!(entityAccess instanceof net.minecraft.world.entity.Entity entity)) return;
        if (entity.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            com.axalotl.fabrolia.common.parallelised.utils.HardColliderIndex.onAdd(serverLevel, entity);
            // A concurrent setRemoved may have run Callback.onRemove before the
            // entry above was published. Both sides publish (latch CAS / volatile
            // placement) before reading the other's, so one of them unfiles it.
            if (!(((com.axalotl.fabrolia.common.mixin.entity.EntityLevelCallbackAccessor) entity)
                    .fabrolia$getLevelCallback()
                    instanceof com.axalotl.fabrolia.common.regions.EntityResidencyAccess callback)
                    || callback.fabrolia$removalStarted()) {
                com.axalotl.fabrolia.common.parallelised.utils.HardColliderIndex.onRemove(entity);
            }
        }
        if (entity instanceof net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl pearl) {
            fabrolia$indexLoadedPearl(pearl);
        }
    }

    /**
     * Visibility-start feed for {@link com.axalotl.fabrolia.common.parallelised.utils.SpawnIndex}.
     *
     * <p>Vanilla {@code PESM.startTracking} fires when an entity enters an accessible section
     * (initial add into a {@code TRACKED}/{@code TICKING} section, chunk-visibility transition
     * from HIDDEN, or section move toward visibility). Vanilla then adds to
     * {@code visibleEntityStorage.byId} — which is exactly what {@code level.getAllEntities()}
     * iterates — so by mirroring this seam we get bit-equivalent membership to vanilla's
     * spawn-cap iterator. HEAD-inject so we register before any vanilla side effects.
     */
    @Inject(method = "startTracking(Lnet/minecraft/world/level/entity/EntityAccess;)V", at = @At("HEAD"))
    private void fabrolia$onStartTracking(EntityAccess entityAccess, CallbackInfo ci) {
        if (!(entityAccess instanceof net.minecraft.world.entity.Entity entity)) return;
        if (entity.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            com.axalotl.fabrolia.common.parallelised.utils.SpawnIndex.onTrackingStart(serverLevel, entity);
        }
    }

    /**
     * Visibility-end feed for {@link com.axalotl.fabrolia.common.parallelised.utils.SpawnIndex}.
     *
     * <p>Pairs with {@code fabrolia$onStartTracking}. Vanilla {@code PESM.stopTracking} fires
     * when an entity leaves accessibility — chunk-visibility transition to HIDDEN, entity
     * death in an accessible section ({@code Callback.onRemove}), or section move toward
     * inaccessibility. Death in a HIDDEN section never calls {@code stopTracking} (vanilla
     * branches on {@code status.isAccessible()}), but the entity wasn't in our set anyway.
     */
    @Inject(method = "stopTracking(Lnet/minecraft/world/level/entity/EntityAccess;)V", at = @At("HEAD"))
    private void fabrolia$onStopTracking(EntityAccess entityAccess, CallbackInfo ci) {
        if (!(entityAccess instanceof net.minecraft.world.entity.Entity entity)) return;
        if (entity.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            com.axalotl.fabrolia.common.parallelised.utils.SpawnIndex.onTrackingEnd(serverLevel, entity);
        }
    }
}
