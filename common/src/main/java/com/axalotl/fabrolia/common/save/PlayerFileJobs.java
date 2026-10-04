package com.axalotl.fabrolia.common.save;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonIOException;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.CriterionProgress;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.stats.Stat;
import net.minecraft.util.FileUtil;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detached snapshots and the background halves of vanilla's player-file and
 * level.dat saves. Each job body is vanilla's IO sequence with vanilla's own
 * catch clause and message; the bytes written are those vanilla's synchronous
 * method would have produced from the same state.
 */
public final class PlayerFileJobs {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-Save");

    private PlayerFileJobs() {}

    /** {@code PlayerDataStorage.save} after {@code buildResult()}. */
    public static AsyncSaveWriter.Job playerData(Path playerDir, String uuid, String playerName,
                                                 CompoundTag snapshot) {
        return state -> {
            try {
                Path tmpFile = Files.createTempFile(playerDir, uuid + "-", ".dat");
                NbtIo.writeCompressed(snapshot, tmpFile);
                Path realFile = playerDir.resolve(uuid + ".dat");
                Path oldFile = playerDir.resolve(uuid + ".dat_old");
                Util.safeReplaceFile(realFile, tmpFile, oldFile);
            } catch (Exception e) {
                LOGGER.warn("Failed to save player data for {}", playerName, e);
            }
        };
    }

    /** {@code LevelStorageAccess.saveLevelData(CompoundTag)}. */
    public static AsyncSaveWriter.Job levelData(Path worldDir, Path currentFile, Path oldDataFile,
                                                CompoundTag root) {
        return state -> {
            try {
                Path dataFile = Files.createTempFile(worldDir, "level", ".dat");
                NbtIo.writeCompressed(root, dataFile);
                Util.safeReplaceFile(currentFile, dataFile, oldDataFile);
            } catch (Exception e) {
                LOGGER.error("Failed to save level {}", worldDir, e);
            }
        };
    }

    /** {@code PlayerAdvancements.save} after {@code asData()}. */
    public static AsyncSaveWriter.Job advancements(Path file, Codec<PlayerAdvancements.Data> codec,
                                                   PlayerAdvancements.Data snapshot, Gson gson) {
        return state -> {
            JsonElement json = codec.encodeStart(JsonOps.INSTANCE, snapshot).getOrThrow();
            writeJson(file, json, gson, state, "Couldn't save player advancements to {}");
        };
    }

    /** {@code ServerStatsCounter.save}: {@code toJson()} of the snapshot, then the write. */
    public static AsyncSaveWriter.Job stats(Path file, Codec<Map<Stat<?>, Integer>> codec,
                                            Map<Stat<?>, Integer> snapshot, Gson gson) {
        return state -> writeJson(file, statsJson(codec, snapshot), gson, state,
                "Couldn't save stats to {}");
    }

    /** Body of {@code ServerStatsCounter.toJson()} over a detached map. */
    public static JsonElement statsJson(Codec<Map<Stat<?>, Integer>> codec, Map<Stat<?>, Integer> stats) {
        JsonObject result = new JsonObject();
        result.add("stats", codec.encodeStart(JsonOps.INSTANCE, stats).getOrThrow());
        result.addProperty("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        return result;
    }

    /**
     * Copy of {@code StatsCounter.stats} in the order {@code toJson()} reads it.
     * The codec groups {@code stats.entrySet().stream()} into hash maps whose
     * colliding keys keep that encounter order, and a fastutil hash map streams
     * in a different order than it iterates, so the copy follows the stream.
     * The map is {@code Object2IntMaps.synchronize}d: traversal holds its monitor.
     */
    public static Map<Stat<?>, Integer> snapshotStats(Map<Stat<?>, Integer> stats) {
        Map<Stat<?>, Integer> copy = new LinkedHashMap<>();
        synchronized (stats) {
            stats.entrySet().stream().forEachOrdered(entry -> copy.put(entry.getKey(), entry.getValue()));
        }
        return copy;
    }

    /**
     * Copy of {@code asData()}'s result that no later award/revoke can reach.
     * The advancement map keeps its iteration order (the map codec iterates it);
     * each progress gets fresh criteria in the live map's stream order (the
     * criteria codec streams them, keeping the obtained ones, into a hash map) and
     * the live requirements ({@code done}); an empty map stays empty (omitted).
     */
    public static PlayerAdvancements.Data detach(PlayerAdvancements.Data live) {
        Map<Identifier, AdvancementProgress> map = new LinkedHashMap<>();
        for (Map.Entry<Identifier, AdvancementProgress> entry : live.map().entrySet()) {
            map.put(entry.getKey(), detach(entry.getValue()));
        }
        return new PlayerAdvancements.Data(map);
    }

    static AdvancementProgress detach(AdvancementProgress live) {
        Map<String, CriterionProgress> criteria = new LinkedHashMap<>();
        live.criteria.entrySet().stream().forEachOrdered(criterion ->
                criteria.put(criterion.getKey(), new CriterionProgress(criterion.getValue().getObtained())));
        AdvancementProgress copy = new AdvancementProgress(criteria);
        copy.requirements = live.requirements;
        return copy;
    }

    /**
     * Vanilla's in-place JSON write ({@code createDirectoriesSafe} +
     * {@code newBufferedWriter} + {@code gson.toJson(json, gson.newJsonWriter(w))}),
     * skipped when the file still holds exactly the bytes this writer last put there.
     */
    static void writeJson(Path file, JsonElement json, Gson gson, AsyncSaveWriter.FileState state,
                          String failureMessage) {
        try {
            StringWriter text = new StringWriter();
            gson.toJson(json, gson.newJsonWriter(text));
            byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
            if (unchangedOnDisk(file, bytes, state)) return;
            state.forget();
            FileUtil.createDirectoriesSafe(file.getParent());
            Files.write(file, bytes);
            remember(file, bytes, state);
        } catch (IOException | JsonIOException e) {
            state.forget();
            LOGGER.error(failureMessage, file, e);
        }
    }

    private static boolean unchangedOnDisk(Path file, byte[] bytes, AsyncSaveWriter.FileState state) {
        if (state.lastBytes == null || !Arrays.equals(state.lastBytes, bytes)) return false;
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            return attributes.isRegularFile()
                    && attributes.size() == state.lastSize
                    && attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS) == state.lastModifiedNanos;
        } catch (IOException missingOrUnreadable) {
            return false;
        }
    }

    private static void remember(Path file, byte[] bytes, AsyncSaveWriter.FileState state) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            state.lastSize = attributes.size();
            state.lastModifiedNanos = attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS);
            state.lastBytes = bytes;
        } catch (IOException unreadable) {
            state.forget();
        }
    }
}
