package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.utils.CopyOnWriteInt2ObjectMap;
import com.axalotl.fabrolia.common.regions.RaidIslandDispatch;
import com.axalotl.fabrolia.common.regions.RaidOwnerAccess;
import com.axalotl.fabrolia.common.regions.RaidOwnership;
import com.axalotl.fabrolia.common.regions.RaidRegistryAccess;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.Hash;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.objectweb.asm.Opcodes;

import java.util.List;

/** Publish lookup metadata to island readers and run native raid creation synchronously. */
@Mixin(Raids.class)
public abstract class RaidsOmenRouteMixin implements RaidRegistryAccess {
    @Shadow @Final @Mutable private Int2ObjectMap<Raid> raidMap;

    // Install before the codec constructor inserts saved raids. Copying an
    // already populated concurrent map would inherit its different tie order.
    @Inject(method = "<init>*", at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/entity/raid/Raids;raidMap:Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;",
            opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER), require = 2)
    private void fabrolia$publishRegistry(CallbackInfo ci) {
        if (!raidMap.isEmpty()) throw new IllegalStateException("Raid registry must be installed before population");
        // Same table size, insertions and iterator removals as vanilla. Equal
        // distances in getNearbyRaid select the first table entry, not any raid.
        raidMap = new CopyOnWriteInt2ObjectMap<>(Hash.DEFAULT_INITIAL_SIZE);
    }

    @WrapMethod(method = "createOrExtendRaid")
    private Raid fabrolia$createOnOwner(ServerPlayer player, BlockPos position, Operation<Raid> original) {
        ServerLevel level = player.level();
        Raid[] result = new Raid[1];
        RaidOwnership.runFenced(level, "create or extend raid", () -> {
            result[0] = original.call(player, position);
            if (result[0] != null) RaidIslandDispatch.assignCreated(level, result[0]);
        });
        return result[0];
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
        return fabrolia$nearby(position, maxDistanceSquared);
    }

    @WrapMethod(method = "getRaidCentersInChunk")
    private List<BlockPos> fabrolia$publishedCenters(ChunkPos chunk, Operation<List<BlockPos>> original) {
        return raidMap.values().stream().map(raid -> ((RaidOwnerAccess) raid).fabrolia$raidView().center())
                .filter(chunk::contains).toList();
    }

    @Override
    @SuppressWarnings("unchecked")
    public java.util.Iterator<Raid> fabrolia$raidIterator() {
        return ((CopyOnWriteInt2ObjectMap<Raid>) raidMap).mutableValuesIterator(this);
    }

    @Override
    public boolean fabrolia$isRegistered(Raid raid) {
        return raidMap.containsValue(raid);
    }
}
