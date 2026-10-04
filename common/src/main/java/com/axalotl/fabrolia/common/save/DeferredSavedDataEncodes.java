package com.axalotl.fabrolia.common.save;

import com.google.common.collect.MapMaker;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.scores.ScoreboardSaveData;
import org.jspecify.annotations.Nullable;

/**
 * Per-{@code SavedDataStorage} table of SavedData encodes moved from the
 * collecting thread to the storage's write task.
 *
 * <p>Only {@link ScoreboardSaveData} qualifies: it holds nothing but an
 * immutable {@code Packed} snapshot that {@code ServerScoreboard} swaps in
 * under the all-island quiesce, so encoding the captured snapshot later gives
 * the bytes an encode at collect time would have. Every other SavedData
 * encodes live mutable state and is encoded immediately.
 *
 * <p>The collector receives an empty placeholder tag that stands for the
 * deferred encode; {@link #resolve} on the writing thread swaps the real tag in.
 * Placeholders are weak identity keys: {@link CompoundTag} equality is by
 * content (every placeholder is empty), and a placeholder whose write task is
 * dropped (an earlier write in the storage's chain failed) frees its snapshot
 * with it.
 */
public final class DeferredSavedDataEncodes {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Fabrolia-Save");

    private final Map<CompoundTag, Encode> pending = new MapMaker().weakKeys().makeMap();
    private final Consumer<Throwable> fatalSink;

    public DeferredSavedDataEncodes() {
        this(DeferredSavedDataEncodes::publishFatal);
    }

    DeferredSavedDataEncodes(Consumer<Throwable> fatalSink) {
        this.fatalSink = fatalSink;
    }

    /** Encodes on {@code resolve}: {@code encoder} receives a snapshot-only copy of {@code data}. */
    public CompoundTag encodeOrDefer(SavedData data, Function<SavedData, CompoundTag> encoder) {
        if (data.getClass() != ScoreboardSaveData.class) {
            return encoder.apply(data);
        }
        ScoreboardSaveData live = (ScoreboardSaveData) data;
        CompoundTag placeholder = new CompoundTag();
        pending.put(placeholder, new Encode(live, live.getData(), encoder));
        return placeholder;
    }

    /**
     * The tag to write for {@code tag}: the deferred encode it stands for, or
     * {@code tag} itself; {@code null} means write nothing. An encode that
     * throws leaves the file as it is and is published fatal; the live
     * SavedData is marked dirty again, as vanilla leaves it when its encode
     * throws at collect time, so the stop's flushing save encodes it again.
     * Not rethrown: that would break the storage's write chain for every other
     * server SavedData.
     */
    public @Nullable CompoundTag resolve(CompoundTag tag) {
        Encode encode = pending.remove(tag);
        if (encode == null) {
            return tag;
        }
        try {
            return encode.encoder.apply(new ScoreboardSaveData(encode.snapshot));
        } catch (Throwable failure) {
            encode.live.setDirty();
            fatalSink.accept(failure);
            return null;
        }
    }

    private static void publishFatal(Throwable failure) {
        Throwable winner = com.axalotl.fabrolia.common.regions.RegionScheduler
                .publishFatalAndSelectWinner(failure);
        LOGGER.error("Deferred SavedData encode failed; vanilla would have thrown on the saving thread", winner);
    }

    private record Encode(ScoreboardSaveData live, ScoreboardSaveData.Packed snapshot,
                          Function<SavedData, CompoundTag> encoder) {
    }
}
