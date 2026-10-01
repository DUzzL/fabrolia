package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommandSuggestionsProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 26.3 moved command-suggestion parsing out of the packet handler into
 * {@code ServerCommandSuggestionsProvider}, driven from
 * {@code ServerGamePacketListenerImpl#tick}. Under async islands that listener
 * tick runs in the player's island slot, so dispatcher parsing, {@code requires}
 * predicates and suggestion providers (player lists, permissions, mod
 * providers) would run on an island worker concurrently with Main and other
 * islands. Vanilla parses on the server thread: re-post the attempt there.
 * The request slot is an AtomicReference and the budget flag is volatile, so a
 * re-posted attempt sees the same request and budget the tick granted.
 */
@Mixin(ServerCommandSuggestionsProvider.class)
public abstract class ServerCommandSuggestionsProviderMixin {
    @Shadow @Final private ServerPlayer player;

    @WrapMethod(method = "tryProcessRequest")
    private void fabrolia$parseOnServerThread(Operation<Void> original) {
        MinecraftServer server = this.player.level().getServer();
        if (server != null && !RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)) {
            server.execute(() -> original.call());
            return;
        }
        original.call();
    }
}
