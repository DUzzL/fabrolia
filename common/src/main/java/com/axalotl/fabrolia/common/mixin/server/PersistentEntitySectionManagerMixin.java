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
        com.axalotl.fabrolia.common.parallelised.utils.TickingChunksAccess {

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
    private final com.axalotl.fabrolia.api.fastutil.Long2IntConcurrentHashMap fabrolia$tickingChunks =
            new com.axalotl.fabrolia.api.fastutil.Long2IntConcurrentHashMap();

    @Override
    public com.axalotl.fabrolia.api.fastutil.Long2IntConcurrentHashMap fabrolia$tickingChunks() {
        return fabrolia$tickingChunks;
    }

    @Override
    @SuppressWarnings("unchecked")
    public net.minecraft.world.level.entity.EntitySectionStorage<net.minecraft.world.level.entity.EntityAccess> fabrolia$sectionStorage() {
        return (net.minecraft.world.level.entity.EntitySectionStorage<net.minecraft.world.level.entity.EntityAccess>) this.sectionStorage;
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