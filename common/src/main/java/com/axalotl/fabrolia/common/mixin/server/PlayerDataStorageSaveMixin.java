package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.save.AsyncSaveWriter;
import com.axalotl.fabrolia.common.save.PlayerFileJobs;
import com.axalotl.fabrolia.common.save.SaveDurability;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla's save body still builds the player NBT and the temp file on the
 * calling (owner) thread; the NBT is serialized there, while gzip, the synchronous
 * write and the {@code .dat}/{@code .dat_old} rotation run on the save writer.
 * Loads wait for that player's queued write first.
 */
@Mixin(PlayerDataStorage.class)
public abstract class PlayerDataStorageSaveMixin {
    @Shadow @Final private File playerDir;
    @Shadow @Final private static Logger LOGGER;

    @WrapOperation(method = "save(Lnet/minecraft/world/entity/player/Player;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/nbt/NbtIo;writeCompressed(Lnet/minecraft/nbt/CompoundTag;Ljava/nio/file/Path;)V"),
            require = 1, allow = 1)
    private void fabrolia$serializeOnOwner(CompoundTag tag, Path tmpFile, Operation<Void> original,
                                           @Share("fabrolia$nbt") LocalRef<PlayerFileJobs.NbtSnapshot> nbt) {
        nbt.set(PlayerFileJobs.nbtBytes(tag));
    }

    @WrapOperation(method = "save(Lnet/minecraft/world/entity/player/Player;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/Util;safeReplaceFile(Ljava/nio/file/Path;Ljava/nio/file/Path;Ljava/nio/file/Path;)V"),
            require = 1, allow = 1)
    private void fabrolia$writeOnSaveWriter(Path realFile, Path tmpFile, Path oldFile, Operation<Void> original,
                                            @Share("fabrolia$nbt") LocalRef<PlayerFileJobs.NbtSnapshot> nbt,
                                            @Local(argsOnly = true) Player player) {
        PlayerFileJobs.NbtSnapshot bytes = nbt.get();
        nbt.set(null);
        if (bytes == null) {
            throw new IllegalStateException("Player data for " + player.getPlainTextName()
                    + " reached the file replace without a serialized snapshot");
        }
        String name = player.getPlainTextName();
        AsyncSaveWriter writer = AsyncSaveWriter.INSTANCE;
        AsyncSaveWriter.Ticket ticket = writer.submit(realFile, PlayerFileJobs.nbtFile(
                tmpFile, bytes, realFile, oldFile,
                failure -> LOGGER.warn("Failed to save player data for {}", name, failure)));
        SaveDurability.afterSubmit(writer, ticket);
    }

    @Inject(method = "load(Lnet/minecraft/server/players/NameAndId;)Ljava/util/Optional;",
            at = @At("HEAD"), require = 1, allow = 1)
    private void fabrolia$awaitQueuedWrite(NameAndId nameAndId,
                                           CallbackInfoReturnable<Optional<CompoundTag>> cir) {
        AsyncSaveWriter.INSTANCE.awaitFile(this.playerDir.toPath().resolve(nameAndId.id() + ".dat"));
    }
}
