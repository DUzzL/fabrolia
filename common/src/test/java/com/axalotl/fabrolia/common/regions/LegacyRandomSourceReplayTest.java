package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.utils.ThreadSafeLegacyRandomSourceMixin;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.BitRandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.MarsagliaPolarGaussian;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Runs the production overwrites: ordinary JUnit does not weave Minecraft mixins. */
@Timeout(15)
class LegacyRandomSourceReplayTest {
    private static final long SEED = 0x4E4348414E54L;

    @Test
    void sameSeedReplaysTheVanillaSequenceOnTheSameWorker() throws Exception {
        Source source = new Source(SEED);
        int[] expected = draws(new LegacyRandomSource(SEED));
        onWorker(() -> {
            source.setSeed(SEED);
            int[] preview = draws(source);
            source.setSeed(SEED);
            int[] application = draws(source);
            assertArrayEquals(preview, application, "enchanting replays its seed before applying the choice");
            assertArrayEquals(expected, application, "worker venue must not change a seeded calculation");
            return null;
        });
    }

    @Test
    void serializedMainAndWorkerHandoffsContinueTheSameInstanceStream() throws Exception {
        Source source = new Source(SEED);
        RandomSource expected = new LegacyRandomSource(SEED);
        assertArrayEquals(draws(expected), draws(source));
        assertArrayEquals(draws(expected), onWorker(() -> draws(source)));
        assertArrayEquals(draws(expected), onWorker(() -> draws(source)));
        assertArrayEquals(draws(expected), draws(source));
    }

    @Test
    void explicitReseedRestoresReplayAfterSharedWorldEntropy() throws Exception {
        Source source = new Source(1);
        source.fabrolia$markSharedWorldRandom();
        int[] expected = draws(new LegacyRandomSource(SEED));
        onWorker(() -> {
            draws(source);
            source.setSeed(SEED);
            assertArrayEquals(expected, draws(source));
            source.setSeed(SEED);
            assertArrayEquals(expected, draws(source), "old world stripes must not reappear after reseeding");
            return null;
        });
    }

    private static int[] draws(RandomSource source) {
        int[] values = new int[32];
        for (int i = 0; i < values.length; i += 4) {
            values[i] = source.nextInt();
            values[i + 1] = source.nextInt(31);
            values[i + 2] = Float.floatToIntBits(source.nextFloat());
            values[i + 3] = source.nextInt(10_000);
        }
        return values;
    }

    private static <T> T onWorker(Callable<T> body) throws Exception {
        FutureTask<T> result = new FutureTask<>(body);
        Thread worker = new RegionWorkerThread(result, "seeded-random-regression");
        worker.start();
        try {
            return result.get(5, TimeUnit.SECONDS);
        } finally {
            worker.join(5_000);
            assertFalse(worker.isAlive(), "random operation did not finish");
        }
    }

    private static final class Source extends ThreadSafeLegacyRandomSourceMixin implements BitRandomSource {
        Source(long seed) {
            shadow("seed", new AtomicLong());
            shadow("gaussianSource", new MarsagliaPolarGaussian(this));
            setSeed(seed);
        }

        private void shadow(String name, Object value) {
            try {
                Field field = ThreadSafeLegacyRandomSourceMixin.class.getDeclaredField(name);
                field.setAccessible(true);
                field.set(this, value);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }

        @Override
        public RandomSource fork() {
            return new Source(nextLong());
        }

        @Override
        public PositionalRandomFactory forkPositional() {
            return new LegacyRandomSource.LegacyPositionalRandomFactory(nextLong());
        }
    }
}
