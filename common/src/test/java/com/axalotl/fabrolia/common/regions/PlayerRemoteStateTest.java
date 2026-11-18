package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Night campaign B3 P2/P2b/P2c/P2d: a player with an open barrel, or a delayed block
 * break, was moved to another island by a same-level teleport (RCON tp, warp pad,
 * pearl stasis). Its first tick on the new island closed the barrel / finished the
 * break in the old island: CROSS-DOMAIN fail-stop. The pure decision below names
 * exactly those references.
 */
final class PlayerRemoteStateTest {

    private static final String S1 = "island S1";
    private static final String S2 = "island S2";

    /** Barrel at (2,-60,0) in S1 (0,0); destination S2 (3000,0). */
    private static final long S1_CHUNK = ChunkPos.pack(new BlockPos(2, -60, 0));
    private static final long S1_OTHER_CHUNK = ChunkPos.pack(new BlockPos(2, -60, -3));
    private static final long S2_CHUNK = ChunkPos.pack(new BlockPos(3000, -60, 0));
    private static final long GAP_CHUNK = ChunkPos.pack(new BlockPos(1500, -60, 0));

    private static LongFunction<String> owners() {
        Map<Long, String> map = new HashMap<>();
        map.put(S1_CHUNK, S1);
        map.put(S1_OTHER_CHUNK, S1);
        map.put(S2_CHUNK, S2);
        return map::get;
    }

    private static boolean decide(String island, boolean menuOpen, boolean anchorKnown,
                                  long anchor, boolean destroying, long destroy,
                                  boolean delayed, long delayedPos) {
        return PlayerRemoteState.reachesOutside(island, owners(), menuOpen, anchorKnown,
                anchor, destroying, destroy, delayed, delayedPos);
    }

    @Test
    void noOpenReferenceStaysOnTheIsland() {
        assertFalse(decide(S2, false, false, S1_CHUNK, false, S1_CHUNK, false, S1_CHUNK),
                "closed menu and no destroy progress: stale positions are not references");
    }

    @Test
    void barrelOpenedOnTheOldIslandReachesOutsideTheNewOne() {
        assertTrue(decide(S2, true, true, S1_CHUNK, false, 0L, false, 0L),
                "P2: barrel opened in S1, player now ticks for S2");
        assertFalse(decide(S1, true, true, S1_CHUNK, false, 0L, false, 0L),
                "the same menu on its own island is local");
        assertFalse(decide(S2, true, true, S2_CHUNK, false, 0L, false, 0L),
                "a menu re-validated at the new position is local");
    }

    @Test
    void menuWithoutAnAnchorForThatMenuIsTreatedAsRemote() {
        assertTrue(decide(S1, true, false, S1_CHUNK, false, 0L, false, 0L),
                "an open menu opened outside initMenu has no known anchor");
    }

    @Test
    void globalLaneAnchorAndIslandAnchorDiffer() {
        assertTrue(decide(S2, true, true, GAP_CHUNK, false, 0L, false, 0L),
                "an anchor in an ownerless chunk is not owned by the player's island");
        assertTrue(decide(null, true, true, S1_CHUNK, false, 0L, false, 0L),
                "a player in the global lane does not own an island anchor");
        assertFalse(decide(null, true, true, GAP_CHUNK, false, 0L, false, 0L),
                "both ownerless: Main owns both without a claim");
    }

    @Test
    void delayedDestroyOnTheOldIslandReachesOutside() {
        assertTrue(decide(S2, false, false, 0L, false, 0L, true, S1_OTHER_CHUNK),
                "P2b: the delayed destroy at (2,-60,-3) completes in S1");
        assertFalse(decide(S1, false, false, 0L, false, 0L, true, S1_OTHER_CHUNK));
    }

    @Test
    void destroyProgressOnTheOldIslandReachesOutside() {
        assertTrue(decide(S2, false, false, 0L, true, S1_OTHER_CHUNK, false, 0L),
                "gameMode.tick reads the destroyPos block every tick");
        assertFalse(decide(S1, false, false, 0L, true, S1_OTHER_CHUNK, false, 0L));
    }
}
