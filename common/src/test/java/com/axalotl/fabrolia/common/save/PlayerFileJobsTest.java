package com.axalotl.fabrolia.common.save;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
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
        PlayerFileJobs.advancements(file, codec, snapshot, gson).run();

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
        PlayerFileJobs.stats(asyncFile, codec, snapshot, gson).run();
        assertArrayEquals(Files.readAllBytes(vanillaFile), Files.readAllBytes(asyncFile));
    }

    @Test
    void nbtSnapshotGzipsToTheBytesOfVanillaWriteCompressedAndRotates() throws Exception {
        CompoundTag first = new CompoundTag();
        first.putString("save", "first");
        CompoundTag second = new CompoundTag();
        for (int i = 0; i < 400; i++) {
            CompoundTag item = new CompoundTag();
            item.putString("id", "minecraft:item_" + (i * 7919 % 1000));
            item.putInt("count", i % 64);
            second.put("slot_" + i, item);
        }
        // Removals leave a larger table than a fresh copy would have.
        for (int i = 0; i < 400; i += 3) second.remove("slot_" + i);
        second.putIntArray("payload", IntStream.range(0, 20_000).map(i -> i * 31 % 977).toArray());

        Path reference = dir.resolve("reference.nbt");
        NbtIo.writeCompressed(second, reference);
        byte[] vanilla = Files.readAllBytes(reference);
        Files.delete(reference);

        for (CompoundTag tag : List.of(first, second)) {
            Path tmp = Files.createTempFile(dir, "uuid-", ".dat");
            PlayerFileJobs.nbtFile(tmp, PlayerFileJobs.nbtBytes(tag), dir.resolve("uuid.dat"),
                    dir.resolve("uuid.dat_old"), failure -> { throw new AssertionError(failure); }).run();
        }

        assertArrayEquals(vanilla, Files.readAllBytes(dir.resolve("uuid.dat")));
        assertEquals(first, NbtIo.readCompressed(dir.resolve("uuid.dat_old"), NbtAccounter.unlimitedHeap()));
        try (var files = Files.list(dir)) {
            assertEquals(List.of("uuid.dat", "uuid.dat_old"),
                    files.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }
}
