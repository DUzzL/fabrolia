package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.save.AsyncSaveWriter;
import com.axalotl.fabrolia.common.save.PlayerFileJobs;
import com.axalotl.fabrolia.common.save.SaveDurability;
import com.google.gson.Gson;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.serialization.Codec;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.At;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatsCounter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * The stat map is copied on the calling (owner) thread in the order
 * {@code toJson()} reads it; encoding and the write run on the save writer.
 * Loading a stats file waits for its queued write first.
 */
@Mixin(ServerStatsCounter.class)
public abstract class ServerStatsCounterSaveMixin extends StatsCounter {
    @Shadow @Final private Path file;
    @Shadow @Final private static Gson GSON;
    @Shadow @Final private static Codec<Map<Stat<?>, Integer>> STATS_CODEC;

    @WrapMethod(method = "save()V", require = 1, allow = 1)
    private void fabrolia$snapshotThenWriteAsync(Operation<Void> original) {
        Map<Stat<?>, Integer> snapshot = PlayerFileJobs.snapshotStats(this.stats);
        AsyncSaveWriter writer = AsyncSaveWriter.INSTANCE;
        AsyncSaveWriter.Ticket ticket = writer.submit(this.file,
                PlayerFileJobs.stats(this.file, STATS_CODEC, snapshot, GSON));
        SaveDurability.afterSubmit(writer, ticket);
    }

    @WrapOperation(method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Ljava/nio/file/Files;isRegularFile(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;)Z"),
            require = 1, allow = 1)
    private boolean fabrolia$awaitQueuedWrite(Path path, LinkOption[] options, Operation<Boolean> original) {
        AsyncSaveWriter.INSTANCE.awaitFile(path);
        return original.call(path, options);
    }
}
