package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.ChunkPos;

import java.util.function.LongFunction;

/**
 * Player-held references into world state that the player's own tick and menu
 * packets resolve later, possibly after a same-level teleport moved the player to
 * another island:
 * <ul>
 *   <li>the open container menu. {@code ServerPlayer.tick} and {@code doTick}
 *   call {@code broadcastChanges} (reads the container) and {@code stillValid}
 *   (reads the block-entity map or block at the menu's block); once the player
 *   is out of range, {@code closeContainer} runs {@code menu.removed}, which
 *   writes there (barrel {@code open=false}, chest/shulker block event, trapped
 *   chest neighbour updates, merchant {@code setTradingPlayer(null)}). The menu
 *   packets (click, close, button, trade, recipe, rename, beacon) touch the same
 *   container;</li>
 *   <li>the block-break progress of {@code ServerPlayerGameMode}: every tick
 *   {@code gameMode.tick} reads the block at {@code destroyPos} or
 *   {@code delayedDestroyPos}, and a delayed destroy breaks that block when its
 *   progress completes, even with the player far away (vanilla does).</li>
 * </ul>
 * Vanilla resolves both on the player's next tick at its new position. When the
 * referenced chunk belongs to another island than the player's, that tick must
 * run where both islands are owned: the player gets no island venue
 * ({@code AsyncIslandPlayerTicks.eligible}) and every Main-side player body and
 * menu packet runs under the global island fence
 * ({@link RegionScheduler#runPlayerTickWithMainClaim}, the MAIN_SYNC drain,
 * {@code PlayerInteractionOwnerMixin}). The vanilla body itself is unchanged;
 * only the place of the player's tick within the tick moves to the fenced
 * MAIN_SYNC lane, like every other Main fallback tick.
 *
 * <p>The menu's own block or entity is not reachable through a common vanilla
 * API, so the anchor is the chunk the player stood in when the menu was last
 * known valid: when it was opened ({@code ServerPlayer.initMenu}) and whenever
 * {@code stillValid} passed. Every vanilla world-bound menu enforces its range
 * there (block interaction range + 4, entity range 4), and a position within
 * one 128-block cell of a populated cell belongs to that cell's island (core or
 * halo), so the anchor chunk names the island of the menu's block or entity. An
 * open menu without a record for exactly that menu counts as outside.
 */
public final class PlayerRemoteState {

    /** Diagnostics name of the fence a player body takes for remote state. */
    public static final String FENCE_OPERATION = "player state in another island";

    private PlayerRemoteState() {}

    /** Duck interface of {@code ServerPlayerMixin}. */
    public interface Access {
        MenuAnchor fabrolia$menuAnchor();
    }

    /** The chunk the player stood in when {@code menu} was last known valid. */
    public record MenuAnchor(AbstractContainerMenu menu, long chunk) {
    }

    /**
     * True when the tick or menu packets of {@code player} would touch a chunk
     * that {@code island} does not own. {@code island} is the island whose
     * ownership the caller holds or is about to take (null: none, the player is
     * in the global lane).
     */
    public static boolean reachesOutsideIsland(ServerPlayer player, RegionizedServer runtime,
                                               IslandRegistry.PIsland island) {
        if (runtime == null || player == null) return false;
        AbstractContainerMenu menu = player.containerMenu;
        boolean menuOpen = menu != null && menu != player.inventoryMenu;
        // Both duck types are mixed into every live player; an object built without
        // its constructor or mixins (unit tests) carries no such state.
        boolean destroying = false;
        boolean delayed = false;
        long destroyChunk = 0L;
        long delayedChunk = 0L;
        if (player.gameMode
                instanceof com.axalotl.fabrolia.common.mixin.server.ServerPlayerGameModeDestroyAccessor
                gameMode) {
            destroying = gameMode.fabrolia$isDestroyingBlock();
            delayed = gameMode.fabrolia$hasDelayedDestroy();
            if (destroying) destroyChunk = ChunkPos.pack(gameMode.fabrolia$destroyPos());
            if (delayed) delayedChunk = ChunkPos.pack(gameMode.fabrolia$delayedDestroyPos());
        }
        if (!menuOpen && !destroying && !delayed) return false;
        MenuAnchor anchor = menuOpen && player instanceof Access access
                ? access.fabrolia$menuAnchor() : null;
        boolean anchorKnown = anchor != null && anchor.menu() == menu;
        return reachesOutside(island, runtime::islandOwnerOfChunk,
                menuOpen, anchorKnown, anchorKnown ? anchor.chunk() : 0L,
                destroying, destroyChunk, delayed, delayedChunk);
    }

    /** {@link #reachesOutsideIsland} for the island that owns the player's chunk. */
    public static boolean reachesOutsideOwnIsland(ServerPlayer player) {
        if (player == null || !(player.level() instanceof ServerLevel level)) return false;
        RegionizedServer runtime = RegionScheduler.forLevel(level);
        if (runtime == null) return false;
        return reachesOutsideIsland(player, runtime,
                runtime.islandOwnerOfChunk(player.chunkPosition().pack()));
    }

    /**
     * True when a player of {@code root}'s ride tree reaches outside the island
     * that owns {@code root}'s chunk, the claim a Main body of that tree takes.
     */
    public static boolean treeReachesOutsideIsland(Entity root) {
        if (root == null || !(root.level() instanceof ServerLevel level)) return false;
        if (!com.axalotl.fabrolia.common.ParallelProcessor.containsServerPlayerTick(root)) return false;
        RegionizedServer runtime = RegionScheduler.forLevel(level);
        if (runtime == null) return false;
        return treeReachesOutside(root, runtime,
                runtime.islandOwnerOfChunk(root.chunkPosition().pack()));
    }

    private static boolean treeReachesOutside(Entity entity, RegionizedServer runtime,
                                              IslandRegistry.PIsland island) {
        if (entity instanceof ServerPlayer player
                && reachesOutsideIsland(player, runtime, island)) {
            return true;
        }
        for (Entity passenger : entity.getPassengers()) {
            if (treeReachesOutside(passenger, runtime, island)) return true;
        }
        return false;
    }

    /**
     * Pure decision. A reference reaches outside when its chunk's owner is not
     * {@code island}; an open menu whose anchor is unknown counts as outside.
     */
    static <I> boolean reachesOutside(I island, LongFunction<I> ownerOfChunk,
                                      boolean menuOpen, boolean menuAnchorKnown,
                                      long menuAnchorChunk,
                                      boolean destroying, long destroyChunk,
                                      boolean delayedDestroy, long delayedDestroyChunk) {
        if (menuOpen && (!menuAnchorKnown || ownerOfChunk.apply(menuAnchorChunk) != island)) {
            return true;
        }
        if (destroying && ownerOfChunk.apply(destroyChunk) != island) return true;
        return delayedDestroy && ownerOfChunk.apply(delayedDestroyChunk) != island;
    }
}
