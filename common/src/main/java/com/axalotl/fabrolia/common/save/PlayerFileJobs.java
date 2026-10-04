package com.axalotl.fabrolia.common.save;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonIOException;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;
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
 * level.dat saves. The snapshot is taken by the saving thread exactly where
 * vanilla reads the live state; the job produces the bytes vanilla's
 * synchronous code would have written and keeps vanilla's failure handling.
 */
public final class PlayerFileJobs {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-Save");
    /** {@code NbtIo.SYNC_OUTPUT_OPTIONS}. */
    private static final OpenOption[] SYNC_OUTPUT_OPTIONS = {
            StandardOpenOption.SYNC, StandardOpenOption.WRITE,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};

    private PlayerFileJobs() {}

    /**
     * The uncompressed stream {@code NbtIo.writeCompressed} feeds its gzip
     * stream, serialized now in the tag's current iteration order so later
     * mutation of live (possibly shared) sub-tags cannot reach the file. Deflate
     * output depends on how its input is chunked, so the chunks that vanilla's
     * {@code DataOutputStream(BufferedOutputStream(gzip))} hands to the gzip
     * stream are recorded as well and replayed by {@link #gzip}.
     */
    public static NbtSnapshot nbtBytes(CompoundTag tag) {
        ChunkRecorder recorder = new ChunkRecorder();
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(recorder))) {
            NbtIo.write(tag, output);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return new NbtSnapshot(recorder.bytes.toByteArray(), recorder.chunkLengths());
    }

    /** Uncompressed NBT plus the write boundaries vanilla's gzip stream would see. */
    public record NbtSnapshot(byte[] bytes, int[] chunks) {}

    private static final class ChunkRecorder extends OutputStream {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(8192);
        private int[] lengths = new int[16];
        private int count;

        @Override
        public void write(int b) {
            bytes.write(b);
            add(1);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            if (len == 0) return;
            bytes.write(b, off, len);
            add(len);
        }

        private void add(int length) {
            if (count == lengths.length) lengths = Arrays.copyOf(lengths, count * 2);
            lengths[count++] = length;
        }

        int[] chunkLengths() {
            return Arrays.copyOf(lengths, count);
        }
    }

    /**
     * {@code NbtIo.writeCompressed(tag, tmpFile)} + {@code Util.safeReplaceFile},
     * as in PlayerDataStorage.save and LevelStorageAccess.saveLevelData: the same
     * gzip stream, written with vanilla's synchronous open options in one write.
     */
    public static AsyncSaveWriter.Job nbtFile(Path tmpFile, NbtSnapshot nbt, Path target, Path backup,
                                              Consumer<Exception> onFailure) {
        return () -> {
            try {
                Files.write(tmpFile, gzip(nbt), SYNC_OUTPUT_OPTIONS);
                Util.safeReplaceFile(target, tmpFile, backup);
            } catch (Exception e) {
                onFailure.accept(e);
            }
        };
    }

    static byte[] gzip(NbtSnapshot nbt) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream(Math.max(64, nbt.bytes().length / 4));
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            int offset = 0;
            for (int length : nbt.chunks()) {
                gzip.write(nbt.bytes(), offset, length);
                offset += length;
            }
        }
        return compressed.toByteArray();
    }

    /** {@code PlayerAdvancements.save} after {@code asData()}. */
    public static AsyncSaveWriter.Job advancements(Path file, Codec<PlayerAdvancements.Data> codec,
                                                   PlayerAdvancements.Data snapshot, Gson gson) {
        return () -> {
            JsonElement json = codec.encodeStart(JsonOps.INSTANCE, snapshot).getOrThrow();
            writeJson(file, json, gson, "Couldn't save player advancements to {}");
        };
    }

    /** {@code ServerStatsCounter.save}: {@code toJson()} of the snapshot, then the write. */
    public static AsyncSaveWriter.Job stats(Path file, Codec<Map<Stat<?>, Integer>> codec,
                                            Map<Stat<?>, Integer> snapshot, Gson gson) {
        return () -> writeJson(file, statsJson(codec, snapshot), gson, "Couldn't save stats to {}");
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
     * Vanilla's JSON save ({@code createDirectoriesSafe}, then
     * {@code gson.toJson(json, gson.newJsonWriter(...))} through a strict UTF-8
     * writer, IOException/JsonIOException logged). The same bytes go to a
     * sibling temp file that atomically replaces the target, so a process kill
     * during a background write leaves the previous file instead of a truncated
     * one that the next login would fail to parse.
     */
    static void writeJson(Path file, JsonElement json, Gson gson, String failureMessage) {
        Path tmp = null;
        try {
            FileUtil.createDirectoriesSafe(file.getParent());
            StringWriter text = new StringWriter();
            gson.toJson(json, gson.newJsonWriter(text));
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text.getBuffer()));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            tmp = Files.createTempFile(file.getParent(), file.getFileName() + "-", ".tmp");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
        } catch (IOException | JsonIOException e) {
            LOGGER.error(failureMessage, file, e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException cleanup) {
                    LOGGER.error("Couldn't delete temporary save file {}", tmp, cleanup);
                }
            }
        }
    }
}
