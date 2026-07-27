package com.axalotl.fabrolia.common.mixin.lithium;

import com.axalotl.fabrolia.common.parallelised.utils.FabroliaSafeNavigation;
import com.llamalad7.mixinextras.sugar.Local;
import net.caffeinemc.mods.lithium.common.entity.NavigatingEntity;
import net.caffeinemc.mods.lithium.common.world.ServerWorldExtended;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.WritableLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(value = ServerLevel.class, priority = 1500)
public abstract class LithiumServerLevel extends Level implements WorldGenLevel, ServerWorldExtended,
        com.axalotl.fabrolia.common.parallelised.utils.FabroliaNavAudit {

    @Unique
    private final Set<PathNavigation> fabrolia$activeNavigationsOver = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Snapshot of {@link #fabrolia$activeNavigationsOver} for the sendBlockUpdated hot loop:
     *  that loop runs PER CHANGED BLOCK (a /fill = thousands of calls), and iterating a CHM
     *  key set costs ∝ table CAPACITY, not size — 7.4% of the server thread was pure
     *  ConcurrentHashMap$Traverser.advance during the TNT-polygon fills (BY3YNeuoXI,
     *  2026-07-02). Membership changes (mob starts/stops navigating) are orders of magnitude
     *  rarer than block updates, so rebuild-on-write + plain array scan on read. A racing
     *  add publishes its own rebuild right after (same weakly-consistent window the direct
     *  CHM iteration had). */
    @Unique
    private volatile PathNavigation[] fabrolia$activeNavSnapshot = new PathNavigation[0];

    @Unique
    private void fabrolia$rebuildNavSnapshot() {
        fabrolia$activeNavSnapshot = fabrolia$activeNavigationsOver.toArray(new PathNavigation[0]);
    }

    @Unique
    private volatile boolean fabrolia$suppressNavigationUpdates = false;

    @Unique
    private final Set<PathNavigation> fabrolia$pendingRecompute = Collections.newSetFromMap(new ConcurrentHashMap<>());

    protected LithiumServerLevel(WritableLevelData levelData, ResourceKey<Level> dimension, RegistryAccess registryAccess, Holder<DimensionType> dimensionTypeRegistration, boolean isClientSide, boolean isDebug, long biomeZoomSeed, int maxChainedNeighborUpdates) {
        super(levelData, dimension, registryAccess, dimensionTypeRegistration, isClientSide, isDebug, biomeZoomSeed, maxChainedNeighborUpdates);
    }

    @Inject(
            method = "sendBlockUpdated",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Set;iterator()Ljava/util/Iterator;"
            )
    )
    private void updateActiveListeners(BlockPos pos, BlockState oldState, BlockState newState, int arg3, CallbackInfo ci, @Local(ordinal = 0) List<PathNavigation> list) {
        PathNavigation[] navs = fabrolia$activeNavSnapshot;
        if (fabrolia$suppressNavigationUpdates) {
            for (int i = 0, n = navs.length; i < n; i++) {
                PathNavigation nav = navs[i];
                net.minecraft.world.entity.Mob m = ((FabroliaSafeNavigation) nav).fabrolia$navMob();
                if (m == null || m.isRemoved()) continue; // leaked entry (task #24): dead mobs get no notify
                if (((FabroliaSafeNavigation) nav).fabrolia$shouldRecomputePathSafe(pos)) {
                    fabrolia$pendingRecompute.add(nav);
                }
            }
            return;
        }
        for (int i = 0, n = navs.length; i < n; i++) {
            PathNavigation nav = navs[i];
            net.minecraft.world.entity.Mob m = ((FabroliaSafeNavigation) nav).fabrolia$navMob();
            if (m == null || m.isRemoved()) continue; // leaked entry (task #24)
            if (((FabroliaSafeNavigation) nav).fabrolia$shouldRecomputePathSafe(pos)) {
                list.add(nav);
            }
        }
    }

    @Override
    public void lithium$setNavigationActive(Mob mobEntity) {
        PathNavigation nav = ((NavigatingEntity) mobEntity).lithium$getRegisteredNavigation();
        if (nav != null && fabrolia$activeNavigationsOver.add(nav)) {
            fabrolia$rebuildNavSnapshot();
        }
    }

    @Override
    public void lithium$setNavigationInactive(Mob mobEntity) {
        PathNavigation nav = ((NavigatingEntity) mobEntity).lithium$getRegisteredNavigation();
        if (nav != null && fabrolia$activeNavigationsOver.remove(nav)) {
            fabrolia$rebuildNavSnapshot();
        }
    }

    @Override
    public String fabrolia$navStats() {
        PathNavigation[] navs = fabrolia$activeNavSnapshot;
        int total = navs.length;
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
                + " setSize=" + fabrolia$activeNavigationsOver.size()
                + " pendingRecompute=" + fabrolia$pendingRecompute.size();
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
        boolean changed = false;
        net.minecraft.server.level.ServerLevel self = (net.minecraft.server.level.ServerLevel) (Object) this;
        for (PathNavigation nav : fabrolia$activeNavigationsOver) {
            net.minecraft.world.entity.Mob m = ((FabroliaSafeNavigation) nav).fabrolia$navMob();
            if (com.axalotl.fabrolia.common.parallelised.utils.NavAuditSupport.gone(self, m)) {
                if (fabrolia$activeNavigationsOver.remove(nav)) {
                    changed = true;
                    fabrolia$pendingRecompute.remove(nav);
                }
            }
        }
        if (changed) {
            fabrolia$rebuildNavSnapshot();
        }
    }

    @Unique
    public void fabrolia$setSuppress(boolean suppress) {
        this.fabrolia$suppressNavigationUpdates = suppress;
    }

    @Unique
    public void fabrolia$recomputeAllNavigations() {
        if (fabrolia$pendingRecompute.isEmpty()) return;
        for (PathNavigation nav : fabrolia$pendingRecompute) {
            nav.recomputePath();
        }
        fabrolia$pendingRecompute.clear();
    }
}