package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MC-free schedule model for remove-under-holder-monitor publication. */
class IslandBlockPublicationRaceModelTest {

    @Test
    void redirtyAfterClaimSurvivesForTheNextPublication() {
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            Set<Holder> pending = ConcurrentHashMap.newKeySet();
            Holder holder = new Holder();
            holder.change(pending); // epoch 1

            CountDownLatch claimed = new CountDownLatch(1);
            CountDownLatch producerStarted = new CountDownLatch(1);
            CountDownLatch allowPublication = new CountDownLatch(1);
            try (ExecutorService threads = Executors.newThreadPerTaskExecutor(
                    Thread.ofPlatform().name("block-publication-model-", 0).factory())) {
                Future<Boolean> drain = threads.submit(() ->
                        holder.drain(pending, claimed, allowPublication));
                assertTrue(claimed.await(2, java.util.concurrent.TimeUnit.SECONDS));

                Future<?> producer = threads.submit(() -> {
                    producerStarted.countDown();
                    holder.change(pending); // waits for the holder monitor, then epoch 2
                });
                assertTrue(producerStarted.await(2, java.util.concurrent.TimeUnit.SECONDS));
                allowPublication.countDown();

                assertTrue(drain.get());
                producer.get();
            }

            assertEquals(1, holder.publishedEpoch.get());
            assertEquals(2, holder.dirtyEpoch.get());
            assertTrue(pending.contains(holder),
                    "the post-claim change must reinsert the holder after publication");

            assertTrue(holder.drain(pending, null, null));
            assertEquals(2, holder.publishedEpoch.get());
            assertFalse(pending.contains(holder));
        });
    }

    @Test
    void mainAndIslandContendersPublishOneDirtyGenerationExactlyOnce() {
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            Set<ModelHolder> pending = ConcurrentHashMap.newKeySet();
            ModelHolder holder = new ModelHolder();
            holder.dirty = true;
            holder.tickingChunk = true;
            pending.add(holder);
            CountDownLatch start = new CountDownLatch(1);

            try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
                Future<Boolean> main = threads.submit(() -> {
                    start.await();
                    return publish(pending, holder);
                });
                Future<Boolean> island = threads.submit(() -> {
                    start.await();
                    return publish(pending, holder);
                });
                start.countDown();
                assertTrue(main.get());
                assertTrue(island.get(),
                        "the loser observes clean state, proving the winner completed");
            }

            assertEquals(1, holder.publications,
                    "the holder monitor serializes one dirty generation exactly once");
            assertFalse(holder.dirty);
            assertTrue(pending.isEmpty());
        });
    }

    @Test
    void orphanDirtyHolderWithoutGlobalTokenStillPublishes() {
        Set<ModelHolder> globalTokens = ConcurrentHashMap.newKeySet();
        ModelHolder holder = new ModelHolder();
        holder.dirty = true;
        holder.tickingChunk = true;

        assertTrue(publish(globalTokens, holder),
                "a missing token may mean a competing drain claimed it, not success");
        assertEquals(1, holder.publications);
        assertFalse(holder.dirty);
        assertFalse(globalTokens.contains(holder));
    }

    @Test
    void islandDemotionConsumesObserverHintsWithoutHoldingActorAckHostage() {
        Set<ModelHolder> globalTokens = ConcurrentHashMap.newKeySet();
        Set<ModelHolder> islandHints = ConcurrentHashMap.newKeySet();
        ModelHolder holder = new ModelHolder();
        holder.dirty = true;
        holder.tickingChunk = false;
        globalTokens.add(holder);
        islandHints.add(holder);

        // The actor-specific correction is queued independently before this
        // observer publication slot, so a legal holder demotion cannot defer ACK.
        int actorCorrections = 1;
        flushIslandHint(globalTokens, islandHints, holder);
        int predictionAcks = actorCorrections;

        assertFalse(globalTokens.contains(holder),
                "vanilla consumes the global token when the holder is no longer ticking");
        assertFalse(islandHints.contains(holder),
                "a demoted generation must not become an immortal island hint");
        assertTrue(holder.dirty);
        assertEquals(0, holder.publications);
        assertEquals(1, predictionAcks,
                "the direct actor correction remains the update-before-ACK authority");
    }

    private static void flushIslandHint(Set<ModelHolder> globalTokens,
                                        Set<ModelHolder> islandHints,
                                        ModelHolder holder) {
        if (islandHints.remove(holder) && !publish(globalTokens, holder)) {
            islandHints.add(holder);
        }
    }

    private static boolean publish(Set<ModelHolder> globalTokens,
                                   ModelHolder holder) {
        synchronized (holder) {
            globalTokens.remove(holder);
            if (!holder.dirty) return true;
            if (!holder.tickingChunk) {
                return true;
            }
            holder.publications++;
            holder.dirty = false;
            return true;
        }
    }

    private static final class ModelHolder {
        boolean dirty;
        boolean tickingChunk;
        int publications;
    }

    private static final class Holder {
        final AtomicInteger dirtyEpoch = new AtomicInteger();
        final AtomicInteger publishedEpoch = new AtomicInteger();
        final AtomicInteger publicationCount = new AtomicInteger();

        void change(Set<Holder> pending) {
            synchronized (this) {
                dirtyEpoch.incrementAndGet();
                pending.add(this);
            }
        }

        boolean drain(Set<Holder> pending, CountDownLatch claimed,
                      CountDownLatch allowPublication) throws InterruptedException {
            synchronized (this) {
                if (!pending.remove(this)) return false;
                if (claimed != null) claimed.countDown();
                if (allowPublication != null) allowPublication.await();
                publishedEpoch.set(dirtyEpoch.get());
                publicationCount.incrementAndGet();
                return true;
            }
        }
    }
}
