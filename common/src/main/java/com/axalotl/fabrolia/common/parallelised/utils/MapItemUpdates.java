package com.axalotl.fabrolia.common.parallelised.utils;

import com.axalotl.fabrolia.common.regions.AsyncIslandPacketContinuations;
import com.axalotl.fabrolia.common.regions.AsyncIslandPlayerTicks;
import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Moves a complete pixel transaction, never a partially mutated map, to Main. */
public final class MapItemUpdates {
    private MapItemUpdates() {}

    public static void inventoryTick(ItemStack stack, ServerLevel level, Entity entity,
                                     Runnable update) {
        if (!requiresMain(level, entity)) {
            serialized(MapItem.getSavedData(stack, level), update);
            return;
        }
        ServerPlayer player = (ServerPlayer) entity;
        var owner = capture(player);
        var mapId = stack.get(DataComponents.MAP_ID);
        var item = stack.getItem();
        enqueue(transaction -> publish(owner, () -> {
            // The listener capability excludes a replacement player. Inventory
            // identity and map id exclude a replacement item/map in that player.
            if (player.level() != level || !carriesExactStack(player, stack)
                    || stack.getItem() != item
                    || !Objects.equals(mapId, stack.get(DataComponents.MAP_ID))) return;
            transaction.run();
        }), () -> MapItem.getSavedData(mapId, level), update);
    }

    public static void update(Level level, Entity entity, MapItemSavedData data,
                              Runnable update) {
        if (!(level instanceof ServerLevel serverLevel) || !requiresMain(serverLevel, entity)) {
            serialized(data, update);
            return;
        }
        var owner = capture((ServerPlayer) entity);
        // A direct caller supplied this exact saved-data instance. Never resolve
        // a different map at replay or replay the surrounding inventory loop.
        enqueue(transaction -> publish(owner, () -> {
            if (entity.level() == level) transaction.run();
        }), () -> data, update);
    }

    private static boolean requiresMain(ServerLevel level, Entity entity) {
        // Vanilla's direct update only samples chunks for Player. Nonplayers
        // have no inventory map mutation either. The synchronous exact actor
        // already owns the logical server turn with physical Main parked.
        if (!(entity instanceof Player)
                || RegionalPlayerEntityTicksV2.isPhysicalServerThread(level.getServer())
                || RegionalPlayerEntityTicksV2.inExactPlayerActor()) return false;
        if (!(entity instanceof ServerPlayer player)
                || !AsyncIslandPlayerTicks.supportsIslandPlayerType(player)) {
            throw new IllegalStateException("map update entered on an unauthenticated player worker");
        }
        return true;
    }

    private static AsyncIslandPacketContinuations.ContinuationOwner capture(ServerPlayer player) {
        var owner = AsyncIslandPacketContinuations.capture(player.connection);
        if (owner == null) {
            throw new IllegalStateException("worker map update lost its exact player listener");
        }
        return owner;
    }

    private static void publish(AsyncIslandPacketContinuations.ContinuationOwner owner,
                                Runnable transaction) {
        if (!AsyncIslandPacketContinuations.enqueueMain(owner, "map update", () ->
                AsyncIslandPlayerTicks.runFencedMainMutation("map update", transaction))) {
            throw new IllegalStateException("worker map update lost its Main continuation route");
        }
    }

    private static boolean carriesExactStack(ServerPlayer player, ItemStack stack) {
        var inventory = player.getInventory();
        for (int index = 0; index < inventory.getContainerSize(); index++) {
            if (inventory.getItem(index) == stack) return !stack.isEmpty();
        }
        return false;
    }

    /** Queue publication precedes even saved-data lookup, much less its monitor. */
    static void enqueue(Consumer<Runnable> mainQueue, Supplier<?> savedMap, Runnable update) {
        mainQueue.accept(() -> serialized(savedMap.get(), update));
    }

    static void serialized(Object savedMap, Runnable update) {
        if (savedMap == null) {
            update.run();
            return;
        }
        synchronized (savedMap) {
            update.run();
        }
    }
}
