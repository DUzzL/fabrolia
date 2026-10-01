package com.axalotl.fabrolia.common.mixin.lithium;

import com.axalotl.fabrolia.common.parallelised.utils.FabroliaPushableEntityQuery;
import com.axalotl.fabrolia.common.parallelised.utils.FabroliaSafeNavigation;
import com.axalotl.fabrolia.common.parallelised.utils.FabroliaNavigationBatch;
import com.axalotl.fabrolia.common.parallelised.utils.NavigationBatchState;
import com.llamalad7.mixinextras.sugar.Local;
import net.caffeinemc.mods.lithium.common.entity.NavigatingEntity;
import net.caffeinemc.mods.lithium.common.entity.pushable.PushableEntityClassGroup;
import net.caffeinemc.mods.lithium.common.world.ServerWorldExtended;
import net.caffeinemc.mods.lithium.common.world.WorldHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.storage.WritableLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = ServerLevel.class, priority = 1500)
public abstract class LithiumServerLevel extends Level implements WorldGenLevel, ServerWorldExtended,
        FabroliaPushableEntityQuery, FabroliaNavigationBatch,
        com.axalotl.fabrolia.common.parallelised.utils.FabroliaNavAudit {

    @Unique
    private final NavigationBatchState<PathNavigation> fabrolia$navigationState = new NavigationBatchState<>();

    protected LithiumServerLevel(WritableLevelData levelData, ResourceKey<Level> dimension, RegistryAccess registryAccess, Holder<DimensionType> dimensionTypeRegistration, boolean isClientSide, boolean isDebug, long biomeZoomSeed, int maxChainedNeighborUpdates) {
        super(levelData, dimension, registryAccess, dimensionTypeRegistration, isClientSide, isDebug, biomeZoomSeed, maxChainedNeighborUpdates);
    }

    /**
     * Lithium's adaptive push cache uses a mutable ReferenceMaskedList. The
     * Fabrolia class-group duck instead exposes an insertion-ordered
     * copy-on-write MAYBE_PUSHABLE snapshot: retain the caller's exact
     * per-instance predicate and vanilla's section/insertion order while
     * avoiding one full ClassInstanceMultiMap snapshot per living-entity push.
     */
    @Override
    public List<Entity> fabrolia$getPushableEntitiesOrNull(
            Entity pusher, AABB boundingBox, java.util.function.Predicate<? super Entity> selector) {
        var cache = WorldHelper.getEntityCacheOrNull(this);
        if (cache == null) {
            return null;
        }
        return WorldHelper.getEntitiesOfEntityGroupWithoutDragonPieces(
                cache, pusher, PushableEntityClassGroup.MAYBE_PUSHABLE,
                boundingBox, selector);
    }

    @Inject(
            method = "sendBlockUpdated",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Set;iterator()Ljava/util/Iterator;"
            )
    )
    private void updateActiveListeners(BlockPos pos, BlockState oldState, BlockState newState, int arg3, CallbackInfo ci, @Local(ordinal = 0) List<PathNavigation> list) {
        List<PathNavigation> navs = fabrolia$navigationState.activeSnapshot();
        for (int i = 0, n = navs.size(); i < n; i++) {
            PathNavigation nav = navs.get(i);
            Mob mob = ((FabroliaSafeNavigation) nav).fabrolia$navMob();
            if (mob == null || mob.isRemoved()) continue;
            if (((FabroliaSafeNavigation) nav).fabrolia$shouldRecomputePathSafe(pos)
                    && !fabrolia$navigationState.defer(nav)) {
                // A batch may have closed while the safe predicate ran. Its
                // notification still reaches the normal owner-routed list.
                list.add(nav);
            }
        }
    }

    @Override
    public void lithium$setNavigationActive(Mob mobEntity) {
        PathNavigation nav = ((NavigatingEntity) mobEntity).lithium$getRegisteredNavigation();
        if (nav != null) fabrolia$navigationState.activate(nav);
    }

    @Override
    public void lithium$setNavigationInactive(Mob mobEntity) {
        PathNavigation nav = ((NavigatingEntity) mobEntity).lithium$getRegisteredNavigation();
        if (nav != null) fabrolia$navigationState.deactivate(nav);
    }

    @Override
    public String fabrolia$navStats() {
        List<PathNavigation> navs = fabrolia$navigationState.activeSnapshot();
        int total = navs.size();
        int removed = 0;
        int sectionLeak = 0;
        int liveActive = 0;
        net.minecraft.server.level.ServerLevel selfLvl = (net.minecraft.server.level.ServerLevel) (Object) this;
        for (PathNavigation nav : navs) {
            net.minecraft.world.entity.Mob mob = ((FabroliaSafeNavigation) nav).fabrolia$navMob();
            if (mob == null || mob.isRemoved()) {
                removed++;
            } else if (com.axalotl.fabrolia.common.parallelised.utils.NavAuditSupport.gone(selfLvl, mob)) {
                sectionLeak++;
            } else {
                liveActive++;
            }
        }
        return "navs total=" + total + " live=" + liveActive
                + " removedLeak=" + removed + " sectionLeak=" + sectionLeak
                + " setSize=" + fabrolia$navigationState.activeSnapshot().size()
                + " pendingRecompute=" + fabrolia$navigationState.pendingCount();
    }

    /** Leak sweep (task #24 watchdog wedges): the lithium-compat active set
     *  only shrinks via the stop-navigation seam, which the c2me chunk system
     *  skips on unload (same class as the census phantom purges) — leaked
     *  navigations with never-done paths accumulate for the whole session and
     *  every setBlock scans them (production: sendBlockUpdated wedged past the
     *  60s watchdog on main AND inside worker units holding claims). Drop
     *  entries whose mob is removed or level-mismatched; runs every 256 gt
     *  from the batch-end hook — bounded leak window, vanilla-equivalent
     *  membership (vanilla's set only ever holds currently-ticking mobs). */
    @Override
    public void fabrolia$sweepNavLeaks() {
        List<PathNavigation> leaks = new ArrayList<>();
        net.minecraft.server.level.ServerLevel self = (net.minecraft.server.level.ServerLevel) (Object) this;
        for (PathNavigation nav : fabrolia$navigationState.activeSnapshot()) {
            Mob mob = ((FabroliaSafeNavigation) nav).fabrolia$navMob();
            if (com.axalotl.fabrolia.common.parallelised.utils.NavAuditSupport.gone(self, mob)) {
                leaks.add(nav);
            }
        }
        // Entity-section lookup above must never run under the publication lock.
        if (!leaks.isEmpty()) fabrolia$navigationState.deactivateAll(List.copyOf(leaks));
    }

    @Override
    public void fabrolia$beginNavigationBatch() {
        fabrolia$navigationState.beginBatch();
    }

    @Override
    public void fabrolia$endNavigationBatch() {
        fabrolia$navigationState.endBatch();
    }

    /** Task #32: the explosion drain runs on the exploding thread (owner of
     *  the blast region), but the collected navs may belong to OTHER islands
     *  whose workers hold their nav monitors — route each recompute through
     *  the owner instead of entering the monitor cross-thread. */
    @Override
    public void fabrolia$drainNavigationBatch() {
        for (PathNavigation nav : fabrolia$navigationState.detachReady()) {
            com.axalotl.fabrolia.common.regions.NavRecompute.recomputeOwnerSafe(
                    (net.minecraft.server.level.ServerLevel) (Object) this, nav);
        }
    }
}