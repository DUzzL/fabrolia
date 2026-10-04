package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.save.AsyncSaveWriter;
import com.axalotl.fabrolia.common.save.PlayerFileJobs;
import com.axalotl.fabrolia.common.save.SaveDurability;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Every level.dat write goes through the save writer so writes stay ordered:
 * the tag is serialized on the calling thread, gzip/write/rotation are queued.
 * Outside the world-save pass the write is awaited at once (vanilla timing).
 */
@Mixin(LevelStorageSource.LevelStorageAccess.class)
public abstract class LevelStorageAccessSaveMixin {
    @Unique private static final Logger FABROLIA$LOGGER = LoggerFactory.getLogger("Fabrolia-Save");

    @WrapOperation(method = "saveLevelData(Lnet/minecraft/nbt/CompoundTag;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/nbt/NbtIo;writeCompressed(Lnet/minecraft/nbt/CompoundTag;Ljava/nio/file/Path;)V"),
            require = 1, allow = 1)
    private void fabrolia$serializeOnCaller(CompoundTag root, Path dataFile, Operation<Void> original,
                                            @Share("fabrolia$nbt") LocalRef<PlayerFileJobs.NbtSnapshot> nbt) {
        nbt.set(PlayerFileJobs.nbtBytes(root));
    }

    @WrapOperation(method = "saveLevelData(Lnet/minecraft/nbt/CompoundTag;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/Util;safeReplaceFile(Ljava/nio/file/Path;Ljava/nio/file/Path;Ljava/nio/file/Path;)V"),
            require = 1, allow = 1)
    private void fabrolia$writeOnSaveWriter(Path currentFile, Path dataFile, Path oldDataFile,
                                            Operation<Void> original,
                                            @Share("fabrolia$nbt") LocalRef<PlayerFileJobs.NbtSnapshot> nbt) {
        PlayerFileJobs.NbtSnapshot bytes = nbt.get();
        nbt.set(null);
        if (bytes == null) {
            throw new IllegalStateException("level.dat reached the file replace without a serialized snapshot");
        }
        Path worldDir = dataFile.getParent();
        AsyncSaveWriter writer = AsyncSaveWriter.INSTANCE;
        AsyncSaveWriter.Ticket ticket = writer.submit(currentFile, PlayerFileJobs.nbtFile(
                dataFile, bytes, currentFile, oldDataFile,
                failure -> FABROLIA$LOGGER.error("Failed to save level {}", worldDir, failure)));
        SaveDurability.afterSubmit(writer, ticket);
    }
}
