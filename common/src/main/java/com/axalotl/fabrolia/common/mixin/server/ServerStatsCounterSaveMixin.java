package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.save.AsyncSaveWriter;
import com.axalotl.fabrolia.common.save.PlayerFileJobs;
import com.axalotl.fabrolia.common.save.SaveDurability;
import com.google.gson.Gson;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.serialization.Codec;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatsCounter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * The stat map is copied on the calling (owner) thread in the order
 * {@code toJson()} reads it; encoding and the write run on the save writer.
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
}
