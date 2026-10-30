package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Audit D-14: Main rejoining a loaded raider to its raid holds the raid island's claim. */
final class RaiderLoadClaimTest {

    @Test
    void aRaiderWithoutRaidLoadsInline() {
        AtomicBoolean loaded = new AtomicBoolean();
        RaiderLoadClaim.runLoad(null, OptionalInt.empty(), () -> loaded.set(true));
        assertTrue(loaded.get());
    }

    @Test
    void loadClaimsTheRaidIslandOnMain() throws Exception {
        String mixin = source("common/mixin/entity/RaiderMixin.java");
        assertTrue(mixin.contains("@WrapMethod(method = \"readAdditionalSaveData\")")
                && mixin.contains("input.getInt(\"RaidId\")")
                && mixin.contains("RaiderLoadClaim.runLoad(level,"));
        String helper = source("common/regions/RaiderLoadClaim.java");
        int owner = helper.indexOf("rs.islandOwnerOfChunk(ChunkPos.pack(raid.getCenter()))");
        int held = helper.indexOf("IslandCommands.mainFenceOwns(owner)", owner);
        int claim = helper.indexOf("IslandCommands.mainClaim(owner);", held);
        int load = helper.indexOf("load.run();", claim);
        int release = helper.indexOf("IslandCommands.mainRelease(owner);", load);
        assertTrue(owner >= 0 && held > owner && claim > held && load > claim && release > load);
    }

    /**
     * 2026-10-30 wedge: a raider load pumped from a still-ACQUIRING global fence
     * found its island claimed by this very thread (frame ledger, not MAIN_HELD,
     * and fenceWindowHeld is false before HELD) and spun in mainClaim until the
     * watchdog killed the server.
     */
    @Test
    void anAcquiringFenceFrameOwnsItsClaimedIslandsExactly() throws Exception {
        Class<?> frameClass = Class.forName(IslandCommands.class.getName() + "$GlobalFenceFrame");
        var ctor = frameClass.getDeclaredConstructor(Thread.class, IslandRegistry.PIsland.class);
        ctor.setAccessible(true);
        Object frame = ctor.newInstance(Thread.currentThread(), null);
        var claims = frameClass.getDeclaredField("claims");
        claims.setAccessible(true);
        var active = IslandCommands.class.getDeclaredField("ACTIVE_FENCE");
        active.setAccessible(true);

        IslandRegistry.PIsland claimed = new IslandRegistry.PIsland(901L, 0L);
        IslandRegistry.PIsland other = new IslandRegistry.PIsland(902L, 0L);
        Object previous = active.get(null);
        try {
            assertFalse(IslandCommands.mainFenceOwns(claimed), "no frame owns nothing");
            active.set(null, frame);
            assertFalse(IslandCommands.mainFenceOwns(claimed), "not claimed yet");
            claimed.unitClaim.set(true);
            claimed.noteClaim("fence");
            @SuppressWarnings("unchecked")
            java.util.List<IslandRegistry.PIsland> list = (java.util.List<IslandRegistry.PIsland>) claims.get(frame);
            list.add(claimed);
            // Still ACQUIRING: the window is not held, yet the claimed island is ours.
            assertFalse(IslandCommands.fenceWindowHeld());
            assertTrue(IslandCommands.mainFenceOwns(claimed));
            assertFalse(IslandCommands.mainFenceOwns(other), "an island the frame did not claim");
            // The non-reentrant claim must fail loud instead of waiting on itself.
            assertThrows(IllegalStateException.class, () -> IslandCommands.mainClaim(claimed));
            assertFalse(IslandCommands.mainClaimScoped(claimed), "scoped claim inherits the fence");
            // A foreign thread never borrows the frame.
            boolean[] foreign = new boolean[1];
            Thread t = new Thread(() -> foreign[0] = IslandCommands.mainFenceOwns(claimed));
            t.start();
            t.join();
            assertFalse(foreign[0]);
        } finally {
            active.set(null, previous);
            claimed.clearClaim();
            claimed.unitClaim.set(false);
        }
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && root != null; i++, root = root.getParent()) {
            Path direct = root.resolve("src/main/java/com/axalotl/fabrolia").resolve(relative);
            Path viaCommon = root.resolve("common/src/main/java/com/axalotl/fabrolia").resolve(relative);
            if (Files.isRegularFile(direct)) return Files.readString(direct);
            if (Files.isRegularFile(viaCommon)) return Files.readString(viaCommon);
        }
        throw new AssertionError("source tree not available");
    }
}
