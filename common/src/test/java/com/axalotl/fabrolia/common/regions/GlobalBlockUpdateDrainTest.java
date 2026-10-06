package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Main's global block-update pass must never read a chunk an island unit may be writing: an island
 * holder is published only under that island's claim, otherwise the island publishes it itself.
 */
class GlobalBlockUpdateDrainTest {

    /** Records every lane call; {@code busy} islands refuse the claim. */
    private static final class Recorder implements GlobalBlockUpdateDrain.Lane<String, String> {
        final Map<String, String> writers;
        final Set<String> busy = new HashSet<>();
        final Set<String> mainHeld = new HashSet<>();
        final Set<String> claimed = new HashSet<>();
        final List<String> log = new ArrayList<>();
        String failOn;

        Recorder(Map<String, String> writers) {
            this.writers = writers;
        }

        @Override public String writer(String holder) { return writers.get(holder); }
        @Override public boolean mainHolds(String island) { return mainHeld.contains(island); }

        @Override
        public boolean tryClaim(String island) {
            if (busy.contains(island)) return false;
            assertTrue(claimed.add(island), "claimed twice in one pass: " + island);
            log.add("claim " + island);
            return true;
        }

        @Override
        public void release(String island) {
            assertTrue(claimed.remove(island), "released without a claim: " + island);
            log.add("release " + island);
        }

        @Override public void handOff(String island, String holder) { log.add("handOff " + island + " " + holder); }

        @Override
        public void publish(String holder) {
            String island = writers.get(holder);
            assertTrue(island == null || claimed.contains(island) || mainHeld.contains(island),
                    "published " + holder + " of island " + island + " without its claim");
            log.add("publish " + holder);
            if (holder.equals(failOn)) throw new IllegalStateException("broadcast failed");
        }
    }

    @Test
    void busyIslandsHoldersAreHandedToTheIslandAndNeverReadByMain() {
        Recorder lane = new Recorder(Map.of("b1", "B", "b2", "B"));
        lane.busy.add("B");

        GlobalBlockUpdateDrain.drain(List.of("g1", "b1", "b2"), lane);

        assertEquals(List.of("publish g1", "handOff B b1", "handOff B b2"), lane.log);
    }

    @Test
    void idleIslandsHoldersArePublishedUnderOneClaimThatIsReleased() {
        Recorder lane = new Recorder(Map.of("a1", "A", "a2", "A"));

        GlobalBlockUpdateDrain.drain(List.of("a1", "g1", "a2"), lane);

        assertEquals(List.of("publish g1", "claim A", "publish a1", "publish a2", "release A"), lane.log);
        assertTrue(lane.claimed.isEmpty());
    }

    @Test
    void aClaimMainAlreadyHoldsIsUsedWithoutTakingAnother() {
        Recorder lane = new Recorder(Map.of("a1", "A"));
        lane.mainHeld.add("A");

        GlobalBlockUpdateDrain.drain(List.of("a1"), lane);

        assertEquals(List.of("publish a1"), lane.log);
    }

    @Test
    void aFailedBroadcastReleasesTheClaimAndPropagates() {
        Recorder lane = new Recorder(Map.of("a1", "A"));
        lane.failOn = "a1";

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> GlobalBlockUpdateDrain.drain(List.of("a1"), lane));

        assertEquals("broadcast failed", thrown.getMessage());
        assertEquals(List.of("claim A", "publish a1", "release A"), lane.log);
        assertTrue(lane.claimed.isEmpty());
    }
}
