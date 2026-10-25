package com.axalotl.fabrolia.common.mixin.commands;

import com.axalotl.fabrolia.common.regions.FunctionPlayerSnapshot;
import com.axalotl.fabrolia.common.regions.IslandFunctionScope;
import com.axalotl.fabrolia.common.regions.RegionWorkerThread;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.objectweb.asm.Opcodes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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

    /**
     * Positional sounds and level events ({@code ServerLevel#playSeededSound},
     * {@code #levelEvent}), a worker-side death message and its team lookups
     * iterate the player list on the island worker, while Main adds and removes
     * Main-lane players without a fence. On a worker each of these methods reads
     * one published copy, pinned for the whole call: their index loops read the
     * field once per iteration, and a republish in between must not shrink it.
     */
    @Unique
    private static final ThreadLocal<List<ServerPlayer>> fabrolia$pinnedPlayers = new ThreadLocal<>();

    @Unique
    private static <T> T fabrolia$withPinnedPlayers(java.util.function.Supplier<T> body) {
        if (!(Thread.currentThread() instanceof RegionWorkerThread)) return body.get();
        List<ServerPlayer> previous = fabrolia$pinnedPlayers.get();
        fabrolia$pinnedPlayers.set(previous != null ? previous : FunctionPlayerSnapshot.players());
        try {
            return body.get();
        } finally {
            if (previous == null) fabrolia$pinnedPlayers.remove();
        }
    }

    @WrapMethod(method = "broadcast(Lnet/minecraft/world/entity/player/Player;DDDDLnet/minecraft/resources/ResourceKey;"
            + "Lnet/minecraft/network/protocol/Packet;)V")
    private void fabrolia$pinBroadcast(Player except, double x, double y, double z, double range,
                                       ResourceKey<Level> dimension, Packet<?> packet, Operation<Void> original) {
        fabrolia$withPinnedPlayers(() -> original.call(except, x, y, z, range, dimension, packet));
    }

    @WrapMethod(method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Ljava/util/function/Function;Z)V")
    private void fabrolia$pinSystemMessage(Component message, Function<ServerPlayer, Component> playerMessages,
                                           boolean overlay, Operation<Void> original) {
        fabrolia$withPinnedPlayers(() -> original.call(message, playerMessages, overlay));
    }

    @WrapMethod(method = "broadcastSystemToAllExceptTeam")
    private void fabrolia$pinAllExceptTeam(Player player, Component message, Operation<Void> original) {
        fabrolia$withPinnedPlayers(() -> original.call(player, message));
    }

    @WrapMethod(method = "broadcastSystemToTeam")
    private void fabrolia$pinTeam(Player player, Component message, Operation<Void> original) {
        fabrolia$withPinnedPlayers(() -> original.call(player, message));
    }

    @WrapMethod(method = "getPlayerByName")
    private ServerPlayer fabrolia$pinPlayerByName(String name, Operation<ServerPlayer> original) {
        return fabrolia$withPinnedPlayers(() -> original.call(name));
    }

    @WrapOperation(method = {
            "broadcast(Lnet/minecraft/world/entity/player/Player;DDDDLnet/minecraft/resources/ResourceKey;"
                    + "Lnet/minecraft/network/protocol/Packet;)V",
            "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Ljava/util/function/Function;Z)V",
            "broadcastSystemToAllExceptTeam", "getPlayerByName"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/server/players/PlayerList;players:Ljava/util/List;",
                    opcode = Opcodes.GETFIELD))
    private List<ServerPlayer> fabrolia$readPinnedPlayers(PlayerList self, Operation<List<ServerPlayer>> original) {
        List<ServerPlayer> pinned = fabrolia$pinnedPlayers.get();
        return pinned != null ? pinned : original.call(self);
    }

    @Inject(method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;"
            + "Ljava/util/function/Function;Z)V", at = @At("HEAD"))
    private void fabrolia$guardChatBroadcast(Component message, Function<ServerPlayer, Component> playerMessages,
                                             boolean overlay, CallbackInfo ci) {
        IslandFunctionScope.forbid("a global chat broadcast");
    }
}
