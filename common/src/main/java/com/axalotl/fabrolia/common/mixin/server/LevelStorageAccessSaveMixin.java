package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.save.AsyncSaveWriter;
import com.axalotl.fabrolia.common.save.PlayerFileJobs;
import com.axalotl.fabrolia.common.save.SaveDurability;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Every level.dat write goes through the save writer so writes stay ordered;
 * outside the world-save pass it is awaited at once (vanilla timing).
 */
@Mixin(LevelStorageSource.LevelStorageAccess.class)
public abstract class LevelStorageAccessSaveMixin {
    @Shadow @Final private LevelStorageSource.LevelDirectory levelDirectory;

    @WrapMethod(method = "saveLevelData(Lnet/minecraft/nbt/CompoundTag;)V", require = 1, allow = 1)
    private void fabrolia$writeAsync(CompoundTag root, Operation<Void> original) {
        Path currentFile = this.levelDirectory.dataFile();
        AsyncSaveWriter writer = AsyncSaveWriter.INSTANCE;
        AsyncSaveWriter.Ticket ticket = writer.submit(currentFile, PlayerFileJobs.levelData(
                this.levelDirectory.path(), currentFile, this.levelDirectory.oldDataFile(), root.copy()));
        SaveDurability.afterSubmit(writer, ticket);
    }
}
