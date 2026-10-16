package raidprobe.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import raidprobe.RaidProbe;

import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public class ServerTickMixin {
    @Inject(method = "tickServer", at = @At("HEAD"))
    private void probe$server(BooleanSupplier haveTime, CallbackInfo ci) {
        RaidProbe.server = (MinecraftServer) (Object) this;
    }
}
