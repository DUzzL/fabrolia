package com.axalotl.fabrolia.common.mixin.commands;

import com.axalotl.fabrolia.common.regions.ConsoleCommandFenceScope;
import com.axalotl.fabrolia.common.regions.RconCommandFenceScope;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Marks Minecraft 26.2's exact top-level dedicated-console dispatch and the
 * separately proven vanilla RCON chain. Player commands, direct/mod calls,
 * nested commands, and RCON tasks pumped from a nested managedBlock retain the
 * bounded unknown-caller path.
 */
@Mixin(DedicatedServer.class)
public abstract class DedicatedServerConsoleCommandMixin {

    @WrapMethod(
            method = "runCommand(Ljava/lang/String;)Ljava/lang/String;",
            require = 1,
            allow = 1)
    private String fabrolia$frameExactRconRunCommand(
            String command, Operation<String> original) {
        DedicatedServer server = (DedicatedServer) (Object) this;
        return RconCommandFenceScope.runDedicatedRconCommandEntry(
                server, command, () -> original.call(command));
    }

    @WrapOperation(
            method = "runCommand(Ljava/lang/String;)Ljava/lang/String;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/dedicated/DedicatedServer;"
                            + "executeBlocking(Ljava/lang/Runnable;)V"),
            require = 1,
            allow = 1)
    private void fabrolia$wrapExactRconMainTask(
            DedicatedServer server, Runnable body, Operation<Void> original) {
        Runnable wrapped = RconCommandFenceScope.wrapDedicatedRconExecuteTask(
                server, body);
        original.call(server, wrapped);
    }

    @WrapMethod(
            method = "lambda$runCommand$0(Ljava/lang/String;)V",
            require = 1,
            allow = 1)
    private void fabrolia$frameExactRconLambda(
            String command, Operation<Void> original) {
        DedicatedServer server = (DedicatedServer) (Object) this;
        RconCommandFenceScope.runDedicatedRconLambdaEntry(
                server, command, () -> original.call(command));
    }

    @WrapOperation(
            method = "lambda$runCommand$0(Ljava/lang/String;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/commands/Commands;"
                            + "performPrefixedCommand("
                            + "Lnet/minecraft/commands/CommandSourceStack;"
                            + "Ljava/lang/String;)V"),
            require = 1,
            allow = 1)
    private void fabrolia$markExactRconCommand(
            Commands commands, CommandSourceStack source, String command,
            Operation<Void> original) {
        RconCommandFenceScope.runDedicatedRconPrefixedCommand(
                (DedicatedServer) (Object) this, commands, source, command,
                () -> original.call(commands, source, command));
    }

    @WrapMethod(method = "tickConnection()V", require = 1, allow = 1)
    private void fabrolia$frameExactTickConnectionEntry(Operation<Void> original) {
        DedicatedServer server = (DedicatedServer) (Object) this;
        ConsoleCommandFenceScope.runDedicatedTickConnectionEntry(
                server, () -> original.call());
    }

    @WrapOperation(
            method = "tickConnection()V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/dedicated/DedicatedServer;"
                            + "handleConsoleInputs()V"),
            require = 1,
            allow = 1)
    private void fabrolia$markExactConsoleInputDrain(
            DedicatedServer server, Operation<Void> original) {
        ConsoleCommandFenceScope.runDedicatedConsoleInputs(
                server, () -> original.call(server));
    }

    @WrapMethod(method = "handleConsoleInputs()V", require = 1, allow = 1)
    private void fabrolia$frameExactConsoleInputEntry(Operation<Void> original) {
        DedicatedServer server = (DedicatedServer) (Object) this;
        ConsoleCommandFenceScope.runDedicatedConsoleInputsEntry(
                server, () -> original.call());
    }

    @WrapOperation(
            method = "handleConsoleInputs()V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/commands/Commands;"
                            + "performPrefixedCommand("
                            + "Lnet/minecraft/commands/CommandSourceStack;"
                            + "Ljava/lang/String;)V"),
            require = 1,
            allow = 1)
    private void fabrolia$markExactConsoleCommand(
            Commands commands, CommandSourceStack source, String command,
            Operation<Void> original) {
        ConsoleCommandFenceScope.runDedicatedConsolePrefixedCommand(
                (DedicatedServer) (Object) this, commands, source, command,
                () -> original.call(commands, source, command));
    }
}
