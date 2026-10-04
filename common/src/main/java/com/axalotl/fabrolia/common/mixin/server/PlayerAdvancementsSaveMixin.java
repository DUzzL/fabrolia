package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.save.AsyncSaveWriter;
import com.axalotl.fabrolia.common.save.PlayerFileJobs;
import com.axalotl.fabrolia.common.save.SaveDurability;
import com.google.gson.Gson;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.serialization.Codec;
import java.nio.file.Path;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The progress map is detached on the calling (owner) thread under the same
 * monitor that guards insert-on-miss progress starts; codec encoding, JSON
 * printing and the write run on the save writer. A (re)load waits for this
 * player's queued write first: /reload re-reads right after a deferred saveAll.
 */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsSaveMixin {
    @Shadow @Final private Path playerSavePath;
    @Shadow @Final private Codec<PlayerAdvancements.Data> codec;
    @Shadow @Final private static Gson GSON;

    @Shadow
    private PlayerAdvancements.Data asData() {
        throw new AssertionError();
    }

    @WrapMethod(method = "save()V", require = 1, allow = 1)
    private void fabrolia$snapshotThenWriteAsync(Operation<Void> original) {
        PlayerAdvancements.Data snapshot;
        synchronized (this) {
            snapshot = PlayerFileJobs.detach(this.asData());
        }
        AsyncSaveWriter writer = AsyncSaveWriter.INSTANCE;
        AsyncSaveWriter.Ticket ticket = writer.submit(this.playerSavePath,
                PlayerFileJobs.advancements(this.playerSavePath, this.codec, snapshot, GSON));
        SaveDurability.afterSubmit(writer, ticket);
    }

    @Inject(method = "load(Lnet/minecraft/server/ServerAdvancementManager;)V",
            at = @At("HEAD"), require = 1, allow = 1)
    private void fabrolia$awaitQueuedWrite(ServerAdvancementManager manager, CallbackInfo ci) {
        AsyncSaveWriter.INSTANCE.awaitFile(this.playerSavePath);
    }
}
