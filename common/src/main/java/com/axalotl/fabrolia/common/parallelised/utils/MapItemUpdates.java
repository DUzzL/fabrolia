package com.axalotl.fabrolia.common.parallelised.utils;

import com.axalotl.fabrolia.common.regions.AsyncIslandPacketContinuations;
import com.axalotl.fabrolia.common.regions.AsyncIslandPlayerTicks;
import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Serialises every server-side map transaction by its exact saved map. An
 * island player's own turn runs it in place, as vanilla does, when the pixel
 * pass reads only chunks that turn owns; otherwise the complete pixel
 * transaction, never a partially mutated map, moves to fenced Main.
 */
public final class MapItemUpdates {
    private MapItemUpdates() {}

    /** The pixel pass this thread's island turn proved owned, while it runs. */
    private static final ThreadLocal<OwnedPass> OWNED_PIXEL_PASS = new ThreadLocal<>();

    /**
     * Chunks a held map's pixel pass reads, captured when ownership was proved.
     * Ownership keeps them from unloading during the turn, but a FULL-status
     * demotion may still run on Main; answering such a miss from the capture
     * keeps the worker from waiting on Main while it holds the map monitor,
     * which Main itself takes for global-lane holders.
     */
    static final class OwnedPass {
        final ServerLevel level;
        final MapItemSavedData data;
        final int minChunkX, minChunkZ, maxChunkX, maxChunkZ;
        final LevelChunk[] chunks;

        OwnedPass(ServerLevel level, MapItemSavedData data, int[] bounds, LevelChunk[] chunks) {
            this.level = level;
            this.data = data;
            this.minChunkX = bounds[0];
            this.minChunkZ = bounds[1];
            this.maxChunkX = bounds[2];
            this.maxChunkZ = bounds[3];
            this.chunks = chunks;
        }

        LevelChunk chunk(int chunkX, int chunkZ) {
            if (chunkX < minChunkX || chunkX > maxChunkX
                    || chunkZ < minChunkZ || chunkZ > maxChunkZ) return null;
            return chunks[(chunkZ - minChunkZ) * (maxChunkX - minChunkX + 1)
                    + (chunkX - minChunkX)];
        }
    }

    public static void inventoryTick(ItemStack stack, ServerLevel level, Entity entity,
                                     EquipmentSlot slot, Runnable update) {
        if (!requiresMain(level, entity)) {
            serialized(MapItem.getSavedData(stack, level), update);
            return;
        }
        ServerPlayer player = (ServerPlayer) entity;
        if (AsyncIslandPlayerTicks.isIslandPlayerTickOf(player)) {
            MapItemSavedData data = MapItem.getSavedData(stack, level);
            // Vanilla's pixel half runs only for an unlocked map in a hand slot;
            // the carried-by/decoration half reads no chunks.
            if (data == null || data.locked || slot == null
                    || slot.getType() != EquipmentSlot.Type.HAND) {
                serialized(data, update);
                return;
            }
            OwnedPass pass = ownedPixelPass(level, player, data);
            if (pass != null) {
                runOwned(pass, update);
                return;
            }
        }
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
        OwnedPass active = OWNED_PIXEL_PASS.get();
        if (data != null && active != null && active.data == data && active.level == level) {
            // The enclosing inventory tick's pass, already proved and captured.
            serialized(data, update);
            return;
        }
        OwnedPass pass = ownedPixelPass(serverLevel, (ServerPlayer) entity, data);
        if (pass != null) {
            runOwned(pass, update);
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
        // have no inventory map mutation either.
        if (!(entity instanceof Player)
                || RegionalPlayerEntityTicksV2.isPhysicalServerThread(level.getServer())) return false;
        if (!(entity instanceof ServerPlayer player)
                || !AsyncIslandPlayerTicks.supportsIslandPlayerType(player)) {
            throw new IllegalStateException("map update entered on an unauthenticated player worker");
        }
        return true;
    }

    static void runOwned(OwnedPass pass, Runnable update) {
        OwnedPass outer = OWNED_PIXEL_PASS.get();
        OWNED_PIXEL_PASS.set(pass);
        try {
            serialized(pass.data, update);
        } finally {
            OWNED_PIXEL_PASS.set(outer);
        }
    }

    /**
     * Chunk-cache miss seam (worker getChunk, after its ready-chunk lookups):
     * the captured chunk when this thread runs an owned pixel pass over it.
     */
    public static LevelChunk ownedPassChunk(ServerLevel level, int chunkX, int chunkZ) {
        OwnedPass pass = OWNED_PIXEL_PASS.get();
        return pass == null || pass.level != level ? null : pass.chunk(chunkX, chunkZ);
    }

    /**
     * The pass when this player's island turn owns, loaded, every chunk
     * {@code MapItem.update} can read for it: pixel columns x in
     * [px-range+1, px+range) ∩ [0,128), rows z in [pz-range-1, pz+range) ∩
     * [-1,128), each pixel reading only its own scale×scale block square
     * (heightmap, states, banner) inside one chunk column.
     */
    static OwnedPass ownedPixelPass(ServerLevel level, ServerPlayer player,
                                    MapItemSavedData data) {
        int[] bounds = data == null || level.dimension() != data.dimension ? null
                : pixelChunkBounds(data.scale, data.centerX, data.centerZ,
                        player.getX(), player.getZ(), level.dimensionType().hasCeiling());
        if (bounds == null) bounds = new int[] {0, 0, -1, -1}; // vanilla reads no chunk
        LevelChunk[] chunks = AsyncIslandPlayerTicks.turnOwnedLoadedChunks(
                player, level, bounds[0], bounds[1], bounds[2], bounds[3]);
        return chunks == null ? null : new OwnedPass(level, data, bounds, chunks);
    }

    /** {minChunkX, minChunkZ, maxChunkX, maxChunkZ}, or null when no pixel is read. */
    static int[] pixelChunkBounds(int scaleShift, int centerX, int centerZ,
                                  double x, double z, boolean hasCeiling) {
        int scale = 1 << scaleShift;
        int range = 128 / scale;
        if (hasCeiling) range /= 2;
        int px = Mth.floor(x - centerX) / scale + 64;
        int pz = Mth.floor(z - centerZ) / scale + 64;
        int minPixelX = Math.max(0, px - range + 1);
        int maxPixelX = Math.min(127, px + range - 1);
        int minPixelZ = Math.max(-1, pz - range - 1);
        int maxPixelZ = Math.min(127, pz + range - 1);
        if (minPixelX > maxPixelX || minPixelZ > maxPixelZ) return null;
        int originX = centerX / scale - 64;
        int originZ = centerZ / scale - 64;
        return new int[] {
                ((originX + minPixelX) * scale) >> 4,
                ((originZ + minPixelZ) * scale) >> 4,
                ((originX + maxPixelX) * scale + scale - 1) >> 4,
                ((originZ + maxPixelZ) * scale + scale - 1) >> 4};
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
