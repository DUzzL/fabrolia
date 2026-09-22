package com.axalotl.fabrolia.common.mixin.server;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;
import net.minecraft.server.level.PlayerMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Set;

/**
 * Component-local guard for vanilla's player membership map.
 *
 * <p>Exact 26.2 {@link PlayerMap} stores membership and the ignored bit in one plain
 * fastutil {@code Object2BooleanOpenHashMap}. Player movement can update it from an
 * island worker while block/light publication and spawning read it from other workers.
 * Protect every map operation with the PlayerMap instance monitor and detach the sole
 * live-view API, {@link PlayerMap#getAllPlayers()}, before releasing that monitor. The
 * detached set preserves player identity and the source iteration order; {@code Set.copyOf}
 * does not promise either property.</p>
 *
 * <p>This deliberately does <strong>not</strong> use ChunkMap's monitor. A C2ME distance
 * update owns ChunkMap and then enters a chunk-holder monitor, while a holder broadcast
 * calls the viewer provider while already owning the holder. Making that viewer read
 * enter ChunkMap creates the opposite holder-to-ChunkMap edge and deadlocks. VMP replaces
 * this vanilla lookup path with its area watching manager, which has its own equivalent
 * writer guards and detached reader snapshots.</p>
 */
@Mixin(value = PlayerMap.class, priority = 1500)
public abstract class PlayerMapMixin {

    @WrapMethod(method = "getAllPlayers")
    private synchronized Set<ServerPlayer> fabrolia$snapshotAllPlayers(
            Operation<Set<ServerPlayer>> original) {
        Set<ServerPlayer> live = original.call();
        ReferenceLinkedOpenHashSet<ServerPlayer> snapshot =
                new ReferenceLinkedOpenHashSet<>(live.size());
        snapshot.addAll(live);
        return snapshot;
    }

    @WrapMethod(method = "addPlayer")
    private synchronized void fabrolia$lockedAddPlayer(
            ServerPlayer player, boolean ignored, Operation<Void> original) {
        original.call(player, ignored);
    }

    @WrapMethod(method = "removePlayer")
    private synchronized void fabrolia$lockedRemovePlayer(
            ServerPlayer player, Operation<Void> original) {
        original.call(player);
    }

    @WrapMethod(method = "ignorePlayer")
    private synchronized void fabrolia$lockedIgnorePlayer(
            ServerPlayer player, Operation<Void> original) {
        original.call(player);
    }

    @WrapMethod(method = "unIgnorePlayer")
    private synchronized void fabrolia$lockedUnIgnorePlayer(
            ServerPlayer player, Operation<Void> original) {
        original.call(player);
    }

    @WrapMethod(method = "ignoredOrUnknown")
    private synchronized boolean fabrolia$lockedIgnoredOrUnknown(
            ServerPlayer player, Operation<Boolean> original) {
        return original.call(player);
    }

    @WrapMethod(method = "ignored")
    private synchronized boolean fabrolia$lockedIgnored(
            ServerPlayer player, Operation<Boolean> original) {
        return original.call(player);
    }
}
