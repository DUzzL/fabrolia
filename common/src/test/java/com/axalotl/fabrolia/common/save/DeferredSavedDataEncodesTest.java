package com.axalotl.fabrolia.common.save;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.FixedFormat;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.ScoreboardSaveData;
import net.minecraft.world.scores.Team;
import net.minecraft.world.scores.TeamColor;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(120)
class DeferredSavedDataEncodesTest {
    private static RegistryOps<Tag> ops;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ops = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
                .createSerializationContext(NbtOps.INSTANCE);
    }

    /** {@code ServerScoreboard.store()} without a server: the same protected packers. */
    private static final class PackingScoreboard extends Scoreboard {
        ScoreboardSaveData.Packed store() {
            return new ScoreboardSaveData.Packed(
                    packObjectives(), packPlayerScores(), packDisplaySlots(), packPlayerTeams());
        }
    }

    /** Vanilla {@code SavedDataStorage.encodeUnchecked} for the scoreboard type. */
    private static CompoundTag vanillaEncode(SavedData data) {
        CompoundTag tag = new CompoundTag();
        tag.put("data", ScoreboardSaveData.TYPE.codec().encodeStart(ops, (ScoreboardSaveData) data).getOrThrow());
        NbtUtils.addCurrentDataVersion(tag);
        return tag;
    }

    private static byte[] fileBytes(CompoundTag tag) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, out);
        return out.toByteArray();
    }

    private static PackingScoreboard populated() {
        PackingScoreboard board = new PackingScoreboard();
        Objective kills = board.addObjective("kills", ObjectiveCriteria.DUMMY,
                Component.literal("Kills"), ObjectiveCriteria.RenderType.INTEGER, true,
                new FixedFormat(Component.literal("-")));
        Objective trigger = board.addObjective("menu", ObjectiveCriteria.TRIGGER,
                Component.translatable("menu.title"), ObjectiveCriteria.RenderType.HEARTS, false, null);
        for (int i = 0; i < 200; i++) {
            board.getOrCreatePlayerScore(ScoreHolder.forNameOnly("player" + i), kills).set(i * 7);
            if (i % 3 == 0) {
                board.getOrCreatePlayerScore(ScoreHolder.forNameOnly("player" + i), trigger).set(-i);
            }
        }
        board.getOrCreatePlayerScore(ScoreHolder.forNameOnly("locked"), trigger).lock();
        board.getOrCreatePlayerScore(ScoreHolder.forNameOnly("shown"), kills).display(Component.literal("Shown"));
        board.setDisplayObjective(DisplaySlot.SIDEBAR, kills);
        board.setDisplayObjective(DisplaySlot.LIST, trigger);
        PlayerTeam red = board.addPlayerTeam("red");
        red.setColor(Optional.of(TeamColor.RED));
        red.setDisplayName(Component.literal("Red Team"));
        red.setPlayerPrefix(Component.literal("[R] "));
        red.setAllowFriendlyFire(false);
        red.setCollisionRule(Team.CollisionRule.PUSH_OTHER_TEAMS);
        red.setNameTagVisibility(Team.Visibility.HIDE_FOR_OTHER_TEAMS);
        board.addPlayerToTeam("player1", red);
        board.addPlayerToTeam("player2", red);
        board.addPlayerTeam("blue").setSeeFriendlyInvisibles(false);
        return board;
    }

    private static void mutate(PackingScoreboard board) {
        Objective kills = board.getObjective("kills");
        board.getOrCreatePlayerScore(ScoreHolder.forNameOnly("player5"), kills).set(999);
        board.getOrCreatePlayerScore(ScoreHolder.forNameOnly("late"), kills).set(1);
        board.addPlayerToTeam("player3", board.getPlayerTeam("red"));
        board.getPlayerTeam("red").setDisplayName(Component.literal("Renamed"));
        board.setDisplayObjective(DisplaySlot.SIDEBAR, null);
        board.removeObjective(board.getObjective("menu"));
    }

    @Test
    void deferredScoreboardEncodeWritesTheCollectTimeSnapshot() throws IOException {
        PackingScoreboard board = populated();
        ScoreboardSaveData saveData = new ScoreboardSaveData(board.store());
        byte[] atCollect = fileBytes(vanillaEncode(saveData));

        List<SavedData> encoded = new ArrayList<>();
        DeferredSavedDataEncodes encodes = new DeferredSavedDataEncodes(failure -> {
            throw new AssertionError(failure);
        });
        CompoundTag placeholder = encodes.encodeOrDefer(saveData, data -> {
            encoded.add(data);
            return vanillaEncode(data);
        });
        assertTrue(encoded.isEmpty(), "scoreboard encode must not run at collect time");

        // The next store() swaps a new snapshot into the live SavedData before the write runs.
        mutate(board);
        saveData.setData(board.store());

        byte[] written = fileBytes(encodes.resolve(placeholder));
        assertArrayEquals(atCollect, written);
        assertFalse(java.util.Arrays.equals(written, fileBytes(vanillaEncode(saveData))),
                "the mutation must change the encoding, or this test proves nothing");
        assertEquals(1, encoded.size());
    }

    @Test
    void pendingSavesResolveToTheirOwnSnapshotsInEitherOrder() throws IOException {
        // Two saves collected before either write ran: both placeholders are empty (equal by
        // content), each must resolve to the snapshot captured at its own collect.
        PackingScoreboard board = populated();
        ScoreboardSaveData saveData = new ScoreboardSaveData(board.store());
        DeferredSavedDataEncodes encodes = new DeferredSavedDataEncodes(failure -> {
            throw new AssertionError(failure);
        });
        byte[] first = fileBytes(vanillaEncode(saveData));
        CompoundTag firstTag = encodes.encodeOrDefer(saveData, DeferredSavedDataEncodesTest::vanillaEncode);

        mutate(board);
        saveData.setData(board.store());
        byte[] second = fileBytes(vanillaEncode(saveData));
        CompoundTag secondTag = encodes.encodeOrDefer(saveData, DeferredSavedDataEncodesTest::vanillaEncode);
        assertEquals(firstTag, secondTag);

        assertArrayEquals(second, fileBytes(encodes.resolve(secondTag)));
        assertArrayEquals(first, fileBytes(encodes.resolve(firstTag)));
    }

    @Test
    void otherSavedDataEncodesLiveStateImmediately() {
        SavedData live = new SavedData() {
        };
        CompoundTag expected = new CompoundTag();
        DeferredSavedDataEncodes encodes = new DeferredSavedDataEncodes(failure -> {
            throw new AssertionError(failure);
        });
        List<SavedData> encoded = new ArrayList<>();
        CompoundTag tag = encodes.encodeOrDefer(live, data -> {
            encoded.add(data);
            return expected;
        });
        assertSame(expected, tag);
        assertEquals(List.of(live), encoded);
        assertSame(expected, encodes.resolve(tag));
    }

    @Test
    void encodeFailureWritesNothingRedirtiesAndIsPublishedFatal() {
        List<Throwable> published = new ArrayList<>();
        DeferredSavedDataEncodes encodes = new DeferredSavedDataEncodes(published::add);
        IllegalStateException boom = new IllegalStateException("encode failed");
        ScoreboardSaveData live = new ScoreboardSaveData(ScoreboardSaveData.Packed.EMPTY);
        CompoundTag placeholder = encodes.encodeOrDefer(live, data -> {
            throw boom;
        });
        live.setDirty(false); // collectDirtyTagsToSave retires the bit after the (placeholder) encode

        assertNull(encodes.resolve(placeholder));
        assertEquals(List.of(boom), published);
        assertTrue(live.isDirty(), "the next save must encode the scoreboard again, as in vanilla");
    }
}
