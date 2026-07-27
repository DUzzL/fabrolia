package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.IslandRegistry;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;

import java.util.concurrent.locks.LockSupport;

/**
 * A2 deadline drive — owner-safe PLAYER DATA saves. playerdata NBT is
 * serialized from the LIVE entity (disconnect, autosave saveAll); with async
 * islands the player's island may be mid-unit — a concurrent tick while
 * saveWithoutId walks inventories/vehicles would tear the NBT. Take the owning
 * island's unit claim for the serialization (blocking: a player save MUST
 * happen; bounded by one unit ≈ tens of ms worst case, and the claim window
 * between units makes the spin rare). Inside a main quiesce window main
 * already holds every claim — proceed directly.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @WrapMethod(method = "save(Lnet/minecraft/server/level/ServerPlayer;)V")
    private void fabrolia$ownerSafePlayerSave(ServerPlayer player, Operation<Void> original) {
        if (player.level() instanceof ServerLevel sl) {
            RegionizedServer rs = RegionScheduler.forLevel(sl);
            if (rs != null && rs.asyncActive && !rs.mainHoldsAllClaims()) {
                IslandRegistry.PIsland owner =
                        rs.islandOwnerOfChunk(player.chunkPosition().pack());
                if (owner != null) {
                    com.axalotl.fabrolia.common.regions.IslandCommands.mainClaim(owner);
                    try {
                        original.call(player);
                    } finally {
                        com.axalotl.fabrolia.common.regions.IslandCommands.mainRelease(owner);
                    }
                    return;
                }
            }
        }
        original.call(player);
    }
}
