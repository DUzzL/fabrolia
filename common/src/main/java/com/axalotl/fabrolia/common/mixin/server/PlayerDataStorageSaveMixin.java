package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.save.AsyncSaveWriter;
import com.axalotl.fabrolia.common.save.PlayerFileJobs;
import com.axalotl.fabrolia.common.save.SaveDurability;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.io.File;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.minecraft.world.level.storage.TagValueOutput;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * The player NBT is still built on the calling (owner) thread; gzip, the temp
 * file and the {@code .dat}/{@code .dat_old} rotation run on the save writer.
 * The deep copy detaches sub-tags that encoders may share with live state.
 */
@Mixin(PlayerDataStorage.class)
public abstract class PlayerDataStorageSaveMixin {
    @Shadow @Final private File playerDir;
    @Shadow @Final private static Logger LOGGER;

    @WrapMethod(method = "save(Lnet/minecraft/world/entity/player/Player;)V", require = 1, allow = 1)
    private void fabrolia$snapshotThenWriteAsync(Player player, Operation<Void> original) {
        CompoundTag snapshot;
        try (ProblemReporter.ScopedCollector reporter =
                     new ProblemReporter.ScopedCollector(player.problemPath(), LOGGER)) {
            TagValueOutput output = TagValueOutput.createWithContext(reporter, player.registryAccess());
            player.saveWithoutId(output);
            snapshot = output.buildResult().copy();
        } catch (Exception ignored) {
            LOGGER.warn("Failed to save player data for {}", player.getPlainTextName());
            return;
        }
        Path dir = this.playerDir.toPath();
        String uuid = player.getStringUUID();
        AsyncSaveWriter writer = AsyncSaveWriter.INSTANCE;
        AsyncSaveWriter.Ticket ticket = writer.submit(dir.resolve(uuid + ".dat"),
                PlayerFileJobs.playerData(dir, uuid, player.getPlainTextName(), snapshot));
        SaveDurability.afterSubmit(writer, ticket);
    }
}
