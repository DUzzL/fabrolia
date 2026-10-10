package com.axalotl.fabrolia.common.regions;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EndArrivalSaveRegressionTest {
    @Test void savedPayloadKeepsOrderAndAirborneIdentityAcrossCodecRoundTrip() {
        CompoundTag snapshot = EndArrivalSavedData.emptySnapshot();
        ListTag pending = new ListTag();
        for (String kind : List.of("regen", "entity", "regen", "entity")) {
            CompoundTag op = new CompoundTag();
            op.putString("kind", kind); op.putLong("domain", 7); op.putLong("tick", pending.size() / 2);
            op.putLong("chunk", 123); op.putString("opaque_entity_test_payload", "must survive");
            pending.add(op);
        }
        snapshot.put("pending", pending);
        ListTag airborne = new ListTag();
        CompoundTag landing = new CompoundTag();
        landing.putString("uuid", "00000000-0000-0000-0000-000000000123");
        landing.putLong("platform", 123); landing.putLong("chunk", 456);
        airborne.add(landing); snapshot.put("airborne", airborne);
        EndArrivalSavedData data = new EndArrivalSavedData(snapshot);
        var codec = EndArrivalSavedData.TYPE.codec();
        var encoded = codec.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
        var decoded = codec.parse(NbtOps.INSTANCE, encoded).getOrThrow();
        assertEquals(encoded, codec.encodeStart(NbtOps.INSTANCE, decoded).getOrThrow());
        snapshot.getListOrEmpty("pending").clear();
        assertEquals(4, ((CompoundTag) encoded).getListOrEmpty("pending").size(), "snapshot must own its NBT");
    }

    @Test void snapshotOfPredecessorsKeepsGateClosedUntilTheirActualLanding() {
        boolean[] settled = {false};
        Object handle = new Object();
        var tracker = new EndPlatformHandoff.Tracker(ignored -> settled[0]);
        tracker.track(123, handle);
        var saved = tracker.snapshot();
        var restored = new EndPlatformHandoff.Tracker(ignored -> settled[0]);
        saved.forEach(landing -> restored.track(landing.chunkKey(), landing.handle()));
        assertTrue(restored.airborne(123));
        assertSame(handle, saved.getFirst().handle());
        settled[0] = true;
        assertFalse(restored.airborne(123));
        assertTrue(restored.snapshot().isEmpty());
    }

    @Test void saveAndShutdownUseTheLandingGateAndSaveBeforeVanillaDataCollection() throws Exception {
        Path root = Path.of("src/main/java/com/axalotl/fabrolia/common");
        String arrivals = Files.readString(root.resolve("regions/CrossDimArrivals.java"));
        String save = arrivals.substring(arrivals.indexOf("private static void publishLevelPending"));
        assertFalse(save.contains("EndPlatformHandoff.trackAll"), "save must not force held cohorts past the gate");
        assertTrue(save.contains("extractForSave(p.pendingArrivals, rs.platformHandoff)"));
        assertTrue(save.contains("rs.globalArrivals.addAll(pending)"), "held global prefix must stay queued");
        String level = Files.readString(root.resolve("mixin/world/ServerLevelMixin.java"));
        String wrapper = level.substring(level.indexOf("private void fabrolia$quiesceIslandsForLevelSave"));
        assertTrue(wrapper.indexOf(".beforeLevelSave(this.getLevel())")
                < wrapper.indexOf("original.call(listener, flush, skipSave)"));
        String server = Files.readString(root.resolve("mixin/server/MinecraftServerMixin.java"));
        String all = server.substring(server.indexOf("private boolean fabrolia$quiesceIslandsForServerSave"));
        assertTrue(all.indexOf(".flushForServerSave()") < all.indexOf("original.call(silent, flush, force)"));
        String stop = Files.readString(root.resolve("regions/RegionScheduler.java"));
        assertTrue(stop.contains("EndArrivalSavedData.detachToFallback(rs)"));
        String tickHead = Files.readString(root.resolve("mixin/world/ServerLevelNearestPlayerMixin.java"));
        assertTrue(tickHead.indexOf("EndArrivalSavedData.restoreInto(rs)")
                < tickHead.indexOf("rs.onLevelTickStart()"), "lazy dimension runtimes must restore before ticking");
        String recovery = Files.readString(root.resolve("regions/EndArrivalSavedData.java"));
        assertTrue(recovery.contains("return !manager(level).isLoaded(restored.uuid)"),
                "a hidden loaded predecessor is not a completed landing");
    }
}
