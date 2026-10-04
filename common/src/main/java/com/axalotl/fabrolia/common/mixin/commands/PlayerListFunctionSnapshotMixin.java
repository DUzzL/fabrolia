package com.axalotl.fabrolia.common.mixin.commands;

import com.axalotl.fabrolia.common.regions.FunctionPlayerSnapshot;
import com.axalotl.fabrolia.common.regions.IslandFunctionScope;
import com.axalotl.fabrolia.common.regions.RegionWorkerThread;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Function;

/**
 * {@code PlayerList.players} is a plain ArrayList that Main mutates at login,
 * logout and respawn. Main republishes an immutable copy after each of those
 * mutations ({@link FunctionPlayerSnapshot}); region workers iterate that copy:
 * owner-local function bodies get it from {@code getPlayers()} (vanilla's
 * default particle viewers), and worker-side broadcasts (scoreboard updates of
 * island-owned holders) send through it instead of racing the live list.
 * Global chat broadcasts are Main-only for owner-local bodies.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListFunctionSnapshotMixin {

    @Shadow @Final private List<ServerPlayer> players;

    @Inject(method = {"placeNewPlayer", "remove"}, at = @At("RETURN"))
    private void fabrolia$publishPlayers(CallbackInfo ci) {
        FunctionPlayerSnapshot.publish(this.players);
    }

    @Inject(method = "respawn", at = @At("RETURN"))
    private void fabrolia$publishRespawnedPlayers(CallbackInfoReturnable<ServerPlayer> cir) {
        FunctionPlayerSnapshot.publish(this.players);
    }

    @Inject(method = "getPlayers", at = @At("HEAD"), cancellable = true)
    private void fabrolia$snapshotForIslandFunctions(CallbackInfoReturnable<List<ServerPlayer>> cir) {
        if (Thread.currentThread() instanceof RegionWorkerThread
                && IslandFunctionScope.current() != null) {
            cir.setReturnValue(FunctionPlayerSnapshot.players());
        }
    }

    @Inject(method = "broadcastAll(Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"), cancellable = true)
    private void fabrolia$broadcastFromWorker(Packet<?> packet, CallbackInfo ci) {
        if (Thread.currentThread() instanceof RegionWorkerThread) {
            for (ServerPlayer player : FunctionPlayerSnapshot.players()) {
                player.connection.send(packet);
            }
            ci.cancel();
        }
    }

    @Inject(method = "broadcastAll(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/resources/ResourceKey;)V",
            at = @At("HEAD"), cancellable = true)
    private void fabrolia$broadcastDimensionFromWorker(Packet<?> packet, ResourceKey<Level> dimension,
                                                       CallbackInfo ci) {
        if (Thread.currentThread() instanceof RegionWorkerThread) {
            for (ServerPlayer player : FunctionPlayerSnapshot.players()) {
                if (player.level().dimension() == dimension) player.connection.send(packet);
            }
            ci.cancel();
        }
    }

    @Inject(method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;"
            + "Ljava/util/function/Function;Z)V", at = @At("HEAD"))
    private void fabrolia$guardChatBroadcast(Component message, Function<ServerPlayer, Component> playerMessages,
                                             boolean overlay, CallbackInfo ci) {
        IslandFunctionScope.forbid("a global chat broadcast");
    }
}
