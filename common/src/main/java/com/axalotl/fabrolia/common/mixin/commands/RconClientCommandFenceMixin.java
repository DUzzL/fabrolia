package com.axalotl.fabrolia.common.mixin.commands;

import com.axalotl.fabrolia.common.regions.RconCommandFenceScope;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.ServerInterface;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.rcon.thread.RconClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Marks only the authenticated vanilla RCON interface call. */
@Mixin(RconClient.class)
public abstract class RconClientCommandFenceMixin {

    @WrapOperation(
            method = "run()V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/ServerInterface;"
                            + "runCommand(Ljava/lang/String;)Ljava/lang/String;"),
            require = 1,
            allow = 1)
    private String fabrolia$markExactRconClientCommand(
            ServerInterface server, String command, Operation<String> original) {
        if (!(server instanceof DedicatedServer)) {
            return original.call(server, command);
        }
        return RconCommandFenceScope.runRconClientCommand(
                server, command, () -> original.call(server, command));
    }
}
