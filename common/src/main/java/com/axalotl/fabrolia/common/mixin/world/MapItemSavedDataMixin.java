package com.axalotl.fabrolia.common.mixin.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(MapItemSavedData.class)
public abstract class MapItemSavedDataMixin {

    @WrapMethod(method = "locked")
    private synchronized MapItemSavedData fabrolia$lockSnapshot(
            Operation<MapItemSavedData> original) {
        return original.call();
    }

    @WrapMethod(method = "tickCarriedBy")
    private synchronized void fabrolia$lockTickCarriedBy(Player player, ItemStack stack, @Nullable ItemFrame frame, Operation<Void> original) {
        original.call(player, stack, frame);
    }

    /** Shares one MapItemSavedData monitor with player updates and frame removal. */
    @WrapMethod(method = "getUpdatePacket")
    private synchronized Packet<?> fabrolia$lockGetUpdatePacket(
            MapId id, Player player, Operation<Packet<?>> original) {
        return original.call(id, player);
    }

    @WrapMethod(method = "removedFromFrame")
    private synchronized void fabrolia$lockRemovedFromFrame(
            BlockPos pos, int entityId, Operation<Void> original) {
        original.call(pos, entityId);
    }

    @WrapMethod(method = "toggleBanner")
    private synchronized boolean fabrolia$lockToggleBanner(
            LevelAccessor level, BlockPos pos, Operation<Boolean> original) {
        return original.call(level, pos);
    }

    @WrapMethod(method = "checkBanners")
    private synchronized void fabrolia$lockCheckBanners(
            BlockGetter level, int x, int z, Operation<Void> original) {
        original.call(level, x, z);
    }

    @WrapMethod(method = "updateColor")
    private synchronized boolean fabrolia$lockUpdateColor(
            int x, int z, byte color, Operation<Boolean> original) {
        return original.call(x, z, color);
    }

    @WrapMethod(method = "setColor")
    private synchronized void fabrolia$lockSetColor(
            int x, int z, byte color, Operation<Void> original) {
        original.call(x, z, color);
    }

    @WrapMethod(method = "isExplorationMap")
    private synchronized boolean fabrolia$lockExplorationMapRead(
            Operation<Boolean> original) {
        return original.call();
    }

    @WrapMethod(method = "isTrackedCountOverLimit")
    private synchronized boolean fabrolia$lockTrackedCountRead(
            int limit, Operation<Boolean> original) {
        return original.call(limit);
    }
}
