package com.axalotl.fabrolia.common.save;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.stats.StatsCounter;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@Timeout(120)
class PlayerFileJobsTest {

    @TempDir
    Path dir;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @SuppressWarnings("unchecked")
    private static <T> T vanillaStatic(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(null);
    }

    /** PlayerAdvancements.save / ServerStatsCounter.save: GSON.toJson(json, GSON.newJsonWriter(w)). */
    private static byte[] vanillaJsonBytes(Gson gson, JsonElement json) throws java.io.IOException {
        StringWriter text = new StringWriter();
        gson.toJson(json, gson.newJsonWriter(text));
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void detachedAdvancementsEncodeToVanillaBytesAndIgnoreLaterAwards() throws Exception {
        Codec<PlayerAdvancements.Data> codec = DataFixTypes.ADVANCEMENTS.wrapCodec(
                PlayerAdvancements.Data.CODEC, DataFixers.getDataFixer(), 1343);
        Gson gson = vanillaStatic(PlayerAdvancements.class, "GSON");
        Map<Identifier, AdvancementProgress> live = new LinkedHashMap<>();
        for (int a = 0; a < 80; a++) {
            int seed = a;
            List<String> names = IntStream.range(0, 1 + a % 29)
                    .mapToObj(i -> "criterion_" + (i * 31 + seed)).toList();
            AdvancementRequirements requirements = a % 3 == 0
                    ? AdvancementRequirements.anyOf(names) : AdvancementRequirements.allOf(names);
            AdvancementProgress progress = new AdvancementProgress();
            progress.update(requirements);
            for (int i = 0; i < names.size(); i++) {
                if ((i + a) % 3 != 1) progress.grantProgress(names.get(i));
            }
            if (a % 4 == 0) {
                // Progress loaded from disk: a differently sized criteria table.
                JsonElement stored = AdvancementProgress.CODEC.encodeStart(JsonOps.INSTANCE, progress).getOrThrow();
                progress = AdvancementProgress.CODEC.parse(JsonOps.INSTANCE, stored).getOrThrow();
                progress.update(requirements);
            }
            if (progress.hasProgress()) {
                live.put(Identifier.fromNamespaceAndPath("fabrolia_test", "advancement_" + a), progress);
            }
        }
        PlayerAdvancements.Data liveData = new PlayerAdvancements.Data(live);
        byte[] vanilla = vanillaJsonBytes(gson, codec.encodeStart(JsonOps.INSTANCE, liveData).getOrThrow());

        PlayerAdvancements.Data snapshot = PlayerFileJobs.detach(liveData);
        for (AdvancementProgress progress : live.values()) {
            for (String remaining : progress.getRemainingCriteria()) progress.grantProgress(remaining);
            for (String completed : progress.getCompletedCriteria()) {
                progress.revokeProgress(completed);
                break;
            }
        }
        Path file = dir.resolve("advancements").resolve("player.json");
        PlayerFileJobs.advancements(file, codec, snapshot, gson).run(new AsyncSaveWriter.FileState());

        assertArrayEquals(vanilla, Files.readAllBytes(file));
        assertNotEquals(new String(vanilla, StandardCharsets.UTF_8),
                new String(vanillaJsonBytes(gson, codec.encodeStart(JsonOps.INSTANCE, liveData).getOrThrow()),
                        StandardCharsets.UTF_8));
    }

    @Test
    void statsSnapshotWritesTheBytesOfVanillaSave() throws Exception {
        Path vanillaFile = dir.resolve("vanilla").resolve("stats.json");
        ServerStatsCounter counter = new ServerStatsCounter(null, vanillaFile);
        int value = 1;
        for (Identifier custom : BuiltInRegistries.CUSTOM_STAT.stream().toList()) {
            counter.setValue(null, Stats.CUSTOM.get(custom), value++ * 7);
        }
        for (var block : BuiltInRegistries.BLOCK.stream().limit(300).toList()) {
            counter.setValue(null, Stats.BLOCK_MINED.get(block), value++);
        }
        for (var item : BuiltInRegistries.ITEM.stream().limit(300).toList()) {
            counter.setValue(null, Stats.ITEM_USED.get(item), value++);
        }
        for (var entity : BuiltInRegistries.ENTITY_TYPE.stream().toList()) {
            counter.setValue(null, Stats.ENTITY_KILLED.get(entity), value++);
        }
        counter.save();

        Field statsField = StatsCounter.class.getDeclaredField("stats");
        statsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Stat<?>, Integer> stats = (Map<Stat<?>, Integer>) statsField.get(counter);
        Map<Stat<?>, Integer> snapshot = PlayerFileJobs.snapshotStats(stats);
        Codec<Map<Stat<?>, Integer>> codec = vanillaStatic(ServerStatsCounter.class, "STATS_CODEC");
        Gson gson = vanillaStatic(ServerStatsCounter.class, "GSON");
        Path asyncFile = dir.resolve("async").resolve("stats.json");
        PlayerFileJobs.stats(asyncFile, codec, snapshot, gson).run(new AsyncSaveWriter.FileState());
        assertArrayEquals(Files.readAllBytes(vanillaFile), Files.readAllBytes(asyncFile));
    }

    @Test
    void playerDataRotatesDatAndDatOldWithoutLeftovers() throws Exception {
        CompoundTag first = new CompoundTag();
        first.putString("save", "first");
        CompoundTag second = new CompoundTag();
        second.putString("save", "second");
        second.putIntArray("payload", IntStream.range(0, 4096).toArray());

        PlayerFileJobs.playerData(dir, "uuid", "Player", first).run(new AsyncSaveWriter.FileState());
        PlayerFileJobs.playerData(dir, "uuid", "Player", second).run(new AsyncSaveWriter.FileState());

        Path reference = dir.resolve("reference.nbt");
        NbtIo.writeCompressed(second, reference);
        assertArrayEquals(Files.readAllBytes(reference), Files.readAllBytes(dir.resolve("uuid.dat")));
        assertEquals(first, NbtIo.readCompressed(dir.resolve("uuid.dat_old"), NbtAccounter.unlimitedHeap()));
        Files.delete(reference);
        try (var files = Files.list(dir)) {
            assertEquals(List.of("uuid.dat", "uuid.dat_old"),
                    files.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void unchangedJsonIsSkippedButExternalChangesAreOverwritten() throws Exception {
        Gson gson = vanillaStatic(PlayerAdvancements.class, "GSON");
        JsonObject a = new JsonObject();
        a.addProperty("value", "a");
        JsonObject b = new JsonObject();
        b.addProperty("value", "b");
        byte[] aBytes = vanillaJsonBytes(gson, a);
        Path file = dir.resolve("players").resolve("p.json");
        AsyncSaveWriter.FileState state = new AsyncSaveWriter.FileState();

        PlayerFileJobs.writeJson(file, a, gson, state, "{}");
        FileTime written = Files.getLastModifiedTime(file);
        Thread.sleep(50);
        PlayerFileJobs.writeJson(file, a, gson, state, "{}");
        assertEquals(written, Files.getLastModifiedTime(file));

        Files.writeString(file, "{\"edited\": true}");
        PlayerFileJobs.writeJson(file, a, gson, state, "{}");
        assertArrayEquals(aBytes, Files.readAllBytes(file));

        Files.delete(file);
        PlayerFileJobs.writeJson(file, a, gson, state, "{}");
        assertArrayEquals(aBytes, Files.readAllBytes(file));

        PlayerFileJobs.writeJson(file, b, gson, state, "{}");
        assertArrayEquals(vanillaJsonBytes(gson, b), Files.readAllBytes(file));
    }
}
