package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.api.fastutil.Int2ObjectConcurrentHashMap;
import com.axalotl.fabrolia.common.regions.RaidIslandDispatch;
import com.axalotl.fabrolia.common.regions.RaidOwnerAccess;
import com.axalotl.fabrolia.common.regions.RaidOwnership;
import com.axalotl.fabrolia.common.regions.RaidRegistryAccess;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** World callbacks run on their owner; only registry selection/publication holds the manager monitor. */
@Mixin(Raids.class)
public abstract class RaidsOmenRouteMixin implements RaidRegistryAccess {
    @Shadow @Final @Mutable private Int2ObjectMap<Raid> raidMap;
    @Shadow private int nextId;

    // The codec constructor must publish its populated registry too.
    @Inject(method = "<init>*", at = @At("RETURN"), require = 2)
    private void fabrolia$publishRegistry(CallbackInfo ci) {
        // Safety must not depend on the optional global collection transformer.
        if (!(raidMap instanceof Int2ObjectConcurrentHashMap<?>)) {
            raidMap = new Int2ObjectConcurrentHashMap<>(raidMap);
        }
    }

    @WrapMethod(method = "createOrExtendRaid")
    private Raid fabrolia$createOnOwner(ServerPlayer player, BlockPos position, Operation<Raid> original) {
        if (player.isSpectator()) return null;
        ServerLevel level = player.level();
        BlockPos immutablePosition = position.immutable();
        MobEffectInstance effect = player.getEffect(MobEffects.RAID_OMEN);
        if (RegionalPlayerEntityTicksV2.isPhysicalServerThread(level.getServer())) {
            return fabrolia$createFenced(level, player, immutablePosition, effect);
        }
        if (RaidOwnership.canCreate(player, immutablePosition)) {
            try {
                return fabrolia$create(level, player, immutablePosition, effect, false);
            } catch (RaidOwnership.ForeignOwner ignored) {
                // Only pre-mutation owner selection may request the fenced lane.
            }
        }
        // Vanilla removes RAID_OMEN immediately after this invocation. Capture its
        // value and source level now, not from a later player/level/effect lookup.
        MobEffectInstance captured = effect == null ? null : new MobEffectInstance(effect);
        RegionScheduler.enqueueGlobalMainOnly(() ->
                fabrolia$createFenced(level, player, immutablePosition, captured));
        return null;
    }

    // Selection can reject only before registration or any player/raid callback.
    @Unique
    private static final RaidOwnership.ForeignOwner fabrolia$foreignOwner = new RaidOwnership.ForeignOwner();

    @Unique
    private Raid fabrolia$createFenced(ServerLevel level, ServerPlayer player,
                                       BlockPos position, MobEffectInstance effect) {
        Raid[] result = new Raid[1];
        RaidOwnership.runMainFenced(() -> {
            // The trigger still belongs to its captured source level, but a
            // disconnected/replaced player object cannot receive new callbacks.
            ServerPlayer recipient = !player.isRemoved() && player.connection != null
                    && player.connection.player == player ? player : null;
            result[0] = fabrolia$create(level, recipient, position, effect, true);
        });
        return result[0];
    }

    @Unique
    private Raid fabrolia$create(ServerLevel level, ServerPlayer player, BlockPos position,
                                 MobEffectInstance effect, boolean fenced) {
        if (!level.getGameRules().get(GameRules.RAIDS)
                || !level.environmentAttributes().getValue(EnvironmentAttributes.CAN_START_RAID, position)) {
            return null;
        }
        List<PoiRecord> records = level.getPoiManager().getInRange(
                type -> type.is(PoiTypeTags.VILLAGE), position, 64, PoiManager.Occupancy.IS_OCCUPIED).toList();
        Vec3 total = Vec3.ZERO;
        for (PoiRecord record : records) {
            BlockPos pos = record.getPos();
            total = total.add(pos.getX(), pos.getY(), pos.getZ());
        }
        BlockPos center = records.isEmpty() ? position : BlockPos.containing(total.scale(1.0 / records.size()));
        if (!fenced && !RaidOwnership.ownsRaid(level, center)) throw fabrolia$foreignOwner;
        Raid selected;
        Raid candidate = null;
        while (true) {
            synchronized (this) {
                selected = fabrolia$nearby(center, 9216);
            }
            // Claims and constructors are deliberately outside the registry lock.
            if (selected != null && !fenced
                    && !RaidOwnership.ownsRaid(level, ((RaidOwnerAccess) selected).fabrolia$raidView().center())) {
                throw fabrolia$foreignOwner;
            }
            if (selected == null && candidate == null) candidate = new Raid(center, level.getDifficulty());
            synchronized (this) {
                if (selected != fabrolia$nearby(center, 9216)) continue;
                if (selected == null) {
                    selected = candidate;
                    raidMap.put(++nextId, selected);
                }
                break;
            }
        }
        ((RaidOwnerAccess) selected).fabrolia$absorbOmenIfEligible(player, effect);
        ((Raids) (Object) this).setDirty();
        RaidIslandDispatch.assignCreated(level, selected);
        return selected;
    }

    @Unique
    private Raid fabrolia$nearby(BlockPos position, int maxDistanceSquared) {
        Raid nearest = null;
        double distance = maxDistanceSquared;
        for (Raid raid : raidMap.values()) {
            RaidOwnerAccess.View view = ((RaidOwnerAccess) raid).fabrolia$raidView();
            double candidateDistance = view.center().distSqr(position);
            if (view.active() && candidateDistance < distance) {
                nearest = raid;
                distance = candidateDistance;
            }
        }
        return nearest;
    }

    @WrapMethod(method = "getNearbyRaid")
    private Raid fabrolia$lookupPublishedRaid(BlockPos position, int maxDistanceSquared, Operation<Raid> original) {
        synchronized (this) {
            return fabrolia$nearby(position, maxDistanceSquared);
        }
    }

    @WrapMethod(method = "getRaidCentersInChunk")
    private List<BlockPos> fabrolia$publishedCenters(ChunkPos chunk, Operation<List<BlockPos>> original) {
        return raidMap.values().stream().map(raid -> ((RaidOwnerAccess) raid).fabrolia$raidView().center())
                .filter(chunk::contains).toList();
    }

    @Override
    public void fabrolia$removeStoppedRaid(Raid raid) {
        synchronized (this) {
            for (Int2ObjectMap.Entry<Raid> entry : raidMap.int2ObjectEntrySet()) {
                if (entry.getValue() == raid) {
                    raidMap.remove(entry.getIntKey(), raid);
                    break;
                }
            }
        }
        ((Raids) (Object) this).setDirty();
    }
}
