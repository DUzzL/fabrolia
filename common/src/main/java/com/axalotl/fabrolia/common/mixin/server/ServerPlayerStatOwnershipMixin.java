package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementBuffer;
import com.axalotl.fabrolia.common.regions.PlayerTurnOwnership;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Stats are owner-local player state. A foreign thread awarding one (an animal
 * bred on another island naming the player as love cause, kill credit, the raid
 * victory of a hero ticking elsewhere) raced the owner's own increments on the
 * non-atomic counter, its dirty set and the stat scoreboard objectives (audit
 * D-12). Off the owner the change is routed into the player's FIFO.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerStatOwnershipMixin {

    @WrapMethod(method = "awardStat(Lnet/minecraft/stats/Stat;I)V")
    private void fabrolia$awardStatOnOwner(Stat<?> stat, int count, Operation<Void> original) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (PlayerTurnOwnership.currentThreadOwns(self)) {
            original.call(stat, count);
        } else {
            AdvancementBuffer.routeStat(self, stat, count, false);
        }
    }

    @WrapMethod(method = "resetStat(Lnet/minecraft/stats/Stat;)V")
    private void fabrolia$resetStatOnOwner(Stat<?> stat, Operation<Void> original) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (PlayerTurnOwnership.currentThreadOwns(self)) {
            original.call(stat);
        } else {
            AdvancementBuffer.routeStat(self, stat, 0, true);
        }
    }
}
