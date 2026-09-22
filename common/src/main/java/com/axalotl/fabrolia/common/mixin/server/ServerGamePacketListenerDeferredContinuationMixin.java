package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.AsyncIslandPacketContinuations;
import com.axalotl.fabrolia.common.regions.PlayerCommandFenceScope;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSigningContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.FutureChain;
import net.minecraft.util.TaskChainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Restores A5 ownership after the six mutating continuation callsites in the
 * exact Minecraft 26.2 play listener.
 *
 * <p>The ingress policy keeps the initial handler in MAIN_FIFO, but vanilla's
 * executor/future callbacks otherwise outlive that FIFO's empty marker.  Each
 * wrapper captures the relevant player or chat-session lifetime and republishes
 * the eventual body through {@link AsyncIslandPacketContinuations}. Vanilla execution is
 * unchanged when A5 is inactive or cannot authenticate the exact listener.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerDeferredContinuationMixin {

    @SuppressWarnings({"rawtypes", "unchecked"})
    @WrapOperation(
            method = "handleEditBook(Lnet/minecraft/network/protocol/game/"
                    + "ServerboundEditBookPacket;)V",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/concurrent/CompletableFuture;"
                            + "thenAcceptAsync(Ljava/util/function/Consumer;"
                            + "Ljava/util/concurrent/Executor;)"
                            + "Ljava/util/concurrent/CompletableFuture;"),
            require = 1,
            allow = 1)
    private CompletableFuture fabrolia$routeBookCompletion(
            CompletableFuture future, Consumer consumer, Executor executor,
            Operation<CompletableFuture> original) {
        AsyncIslandPacketContinuations.ContinuationOwner owner =
                AsyncIslandPacketContinuations.capture(self());
        Consumer routed = value -> RegionScheduler.runSuccessBodyIfHealthy(() -> {
            Runnable body = () -> consumer.accept(value);
            if (!AsyncIslandPacketContinuations.enqueueMain(
                    owner, "edit-book-filter", body)) {
                consumer.accept(value);
            }
        });
        return AsyncIslandPacketContinuations.register(owner,
                () -> original.call(future, routed, executor));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @WrapOperation(
            method = "handleSignUpdate(Lnet/minecraft/network/protocol/game/"
                    + "ServerboundSignUpdatePacket;)V",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/concurrent/CompletableFuture;"
                            + "thenAcceptAsync(Ljava/util/function/Consumer;"
                            + "Ljava/util/concurrent/Executor;)"
                            + "Ljava/util/concurrent/CompletableFuture;"),
            require = 1,
            allow = 1)
    private CompletableFuture fabrolia$routeSignCompletion(
            CompletableFuture future, Consumer consumer, Executor executor,
            Operation<CompletableFuture> original) {
        AsyncIslandPacketContinuations.ContinuationOwner owner =
                AsyncIslandPacketContinuations.capture(self());
        Consumer routed = value -> RegionScheduler.runSuccessBodyIfHealthy(() -> {
            Runnable body = () -> consumer.accept(value);
            if (!AsyncIslandPacketContinuations.enqueueMain(
                    owner, "sign-filter", body)) {
                consumer.accept(value);
            }
        });
        return AsyncIslandPacketContinuations.register(owner,
                () -> original.call(future, routed, executor));
    }

    /**
     * Unlike the future wrappers, this replaces server.execute while A5 owns the
     * handler: publication must happen now, before drainMainIngress can publish its
     * empty marker.  Calling the vanilla executor first would recreate the gap.
     */
    @WrapOperation(
            method = "tryHandleChat(Ljava/lang/String;ZLjava/lang/Runnable;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;"
                            + "execute(Ljava/lang/Runnable;)V"),
            require = 1,
            allow = 1)
    private void fabrolia$publishChatStageOneBeforeMainEmpty(
            MinecraftServer server, Runnable handler, Operation<Void> original,
            String message, boolean command, Runnable requestedHandler) {
        AsyncIslandPacketContinuations.ContinuationOwner owner =
                AsyncIslandPacketContinuations.capture(self());
        Runnable guardedHandler = () ->
                RegionScheduler.runSuccessBodyIfHealthy(handler);
        AsyncIslandPacketContinuations.register(owner, () -> {
            boolean owned;
            if (command) {
                Runnable commandTask =
                        AsyncIslandPacketContinuations.enqueueMainCommand(
                                owner, "chat-command-stage-one", handler);
                owned = commandTask != null;
                if (owned) {
                    RegionScheduler.scheduleTrackedMainControlTask(server, commandTask);
                }
            } else {
                owned = AsyncIslandPacketContinuations.enqueueMain(
                        owner, "chat-stage-one", handler);
            }
            if (!owned) {
                original.call(server, guardedHandler);
            }
            return null;
        });
    }

    @WrapMethod(
            method = "performUnsignedChatCommand(Ljava/lang/String;)V",
            require = 1,
            allow = 1)
    private void fabrolia$frameUnsignedPlayerCommand(
            String command, Operation<Void> original) {
        PlayerCommandFenceScope.runPlayerCommandEntry(
                self(), () -> original.call(command));
    }

    @WrapMethod(
            method = "performSignedChatCommand("
                    + "Lnet/minecraft/network/protocol/game/"
                    + "ServerboundChatCommandSignedPacket;"
                    + "Lnet/minecraft/network/chat/LastSeenMessages;)V",
            require = 1,
            allow = 1)
    private void fabrolia$frameSignedPlayerCommand(
            ServerboundChatCommandSignedPacket packet, LastSeenMessages lastSeen,
            Operation<Void> original) {
        PlayerCommandFenceScope.runPlayerCommandEntry(
                self(), () -> original.call(packet, lastSeen));
    }

    @WrapOperation(
            method = "performUnsignedChatCommand(Ljava/lang/String;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/commands/Commands;"
                            + "performCommand(Lcom/mojang/brigadier/ParseResults;"
                            + "Ljava/lang/String;)V"),
            require = 1,
            allow = 1)
    private void fabrolia$frameUnsignedParsedCommand(
            Commands commands, ParseResults<CommandSourceStack> parsed, String command,
            Operation<Void> original) {
        PlayerCommandFenceScope.runParsedCommandEdge(
                self(), commands, parsed, command,
                () -> original.call(commands, parsed, command));
    }

    @WrapOperation(
            method = "performSignedChatCommand("
                    + "Lnet/minecraft/network/protocol/game/"
                    + "ServerboundChatCommandSignedPacket;"
                    + "Lnet/minecraft/network/chat/LastSeenMessages;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/commands/Commands;"
                            + "performCommand(Lcom/mojang/brigadier/ParseResults;"
                            + "Ljava/lang/String;)V"),
            require = 1,
            allow = 1)
    private void fabrolia$frameSignedParsedCommand(
            Commands commands, ParseResults<CommandSourceStack> parsed, String command,
            Operation<Void> original) {
        PlayerCommandFenceScope.runParsedCommandEdge(
                self(), commands, parsed, command,
                () -> original.call(commands, parsed, command));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @WrapOperation(
            method = "lambda$handleChat$0(Lnet/minecraft/network/protocol/game/"
                    + "ServerboundChatPacket;Ljava/util/Optional;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/FutureChain;"
                            + "append(Ljava/util/concurrent/CompletableFuture;"
                            + "Ljava/util/function/Consumer;)V"),
            require = 1,
            allow = 1)
    private void fabrolia$routeFilteredChatCompletion(
            FutureChain chain, CompletableFuture future, Consumer consumer,
            Operation<Void> original) {
        AsyncIslandPacketContinuations.ContinuationOwner owner =
                AsyncIslandPacketContinuations.capture(self());
        Consumer routed = value -> RegionScheduler.runSuccessBodyIfHealthy(() -> {
            Runnable body = () -> consumer.accept(value);
            if (!AsyncIslandPacketContinuations.enqueueMain(
                    owner, "chat-filter-broadcast", body)) {
                consumer.accept(value);
            }
        });
        AsyncIslandPacketContinuations.register(owner, () -> {
            original.call(chain, future, routed);
            return null;
        });
    }

    @WrapOperation(
            method = "resetPlayerChatState(Lnet/minecraft/network/chat/"
                    + "RemoteChatSession;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/FutureChain;"
                            + "append(Ljava/lang/Runnable;)V"),
            require = 1,
            allow = 1)
    private void fabrolia$routeChatSessionPublication(
            FutureChain chain, Runnable continuation, Operation<Void> original) {
        AsyncIslandPacketContinuations.ContinuationOwner registrationOwner =
                AsyncIslandPacketContinuations.capture(self());
        AsyncIslandPacketContinuations.ChatSessionOwner owner =
                AsyncIslandPacketContinuations.captureChatSession(registrationOwner);
        Runnable routed = () -> RegionScheduler.runSuccessBodyIfHealthy(() -> {
            if (!AsyncIslandPacketContinuations.enqueueChatSession(
                    owner, continuation)) {
                continuation.run();
            }
        });
        AsyncIslandPacketContinuations.register(registrationOwner, () -> {
            original.call(chain, routed);
            return null;
        });
    }

    /** Signed command arguments append their own filter/decorator continuations. */
    @WrapOperation(
            method = "lambda$performSignedChatCommand$0("
                    + "Lnet/minecraft/commands/CommandSigningContext;"
                    + "Lnet/minecraft/commands/CommandSourceStack;)"
                    + "Lnet/minecraft/commands/CommandSourceStack;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/commands/CommandSourceStack;"
                            + "withSigningContext("
                            + "Lnet/minecraft/commands/CommandSigningContext;"
                            + "Lnet/minecraft/util/TaskChainer;)"
                            + "Lnet/minecraft/commands/CommandSourceStack;"),
            require = 1,
            allow = 1)
    private CommandSourceStack fabrolia$routeSignedArgumentContinuations(
            CommandSourceStack source, CommandSigningContext signingContext,
            TaskChainer vanilla, Operation<CommandSourceStack> original) {
        AsyncIslandPacketContinuations.ContinuationOwner owner =
                AsyncIslandPacketContinuations.capture(self());
        TaskChainer routed = AsyncIslandPacketContinuations.mainTaskChainer(
                owner, vanilla, "signed-command-chat-argument");
        return AsyncIslandPacketContinuations.register(owner,
                () -> original.call(source, signingContext, routed));
    }


    private ServerGamePacketListenerImpl self() {
        return (ServerGamePacketListenerImpl) (Object) this;
    }
}
