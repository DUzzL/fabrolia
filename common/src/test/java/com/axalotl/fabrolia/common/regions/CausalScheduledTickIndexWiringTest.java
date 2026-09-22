package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Production-shape pins for the cold causal-container hint. */
class CausalScheduledTickIndexWiringTest {

    @Test
    void hintIsMonotonicAndCoversPackedAndRuntimeSchedules() throws Exception {
        String mixin = source("common/src/main/java/com/axalotl/fabrolia/common/mixin/world/LevelChunkTicksMixin.java");
        int field = mixin.indexOf("fabrolia$mayContainPulseSensitiveTick;");
        int packed = mixin.indexOf("fabrolia$indexPackedPulseTicks", field);
        int packedType = mixin.indexOf(
                "ScheduledTickIslandAdmission.requiresPulseCoherence(tick.type())", packed);
        int packedSet = mixin.indexOf(
                "fabrolia$markPulseSensitiveTick();", packedType);

        assertTrue(field >= 0 && packed > field && packedType > packed
                        && packedSet > packedType,
                "packed causal ticks must publish the hint before unpack");
        assertFalse(mixin.contains("fabrolia$mayContainPulseSensitiveTick = false"),
                "removal must never create a false negative; stale true is a cold false-positive");
        assertFalse(mixin.contains("WrapMethod(method = \"schedule\")"),
                "do not add a second allocation-bearing wrapper to the terrain schedule hot path");

        String levelTicks = source(
                "common/src/main/java/com/axalotl/fabrolia/common/mixin/world/LevelTicksMixin.java");
        int schedule = levelTicks.indexOf("void fabrolia$syncSchedule");
        int rebase = levelTicks.indexOf("rebaseScheduledTick", schedule);
        int scheduleType = levelTicks.indexOf("requiresPulseCoherence(tick.type())", rebase);
        int container = levelTicks.indexOf("allContainers.get(ChunkPos.pack(tick.pos()))",
                scheduleType);
        int scheduleSet = levelTicks.indexOf("fabrolia$markPulseSensitiveTick();", container);
        int original = levelTicks.indexOf("original.call(tick)", scheduleSet);
        assertTrue(schedule >= 0 && rebase > schedule && scheduleType > rebase
                        && container > scheduleType && scheduleSet > container
                        && original > scheduleSet,
                "the existing LevelTicks schedule wrapper must mark before canonical insertion");
    }

    @Test
    void predicateCoversEveryVanillaStatefulScheduledRedstoneFamily() throws Exception {
        String policy = source(
                "common/src/main/java/com/axalotl/fabrolia/common/regions/ScheduledTickIslandAdmission.java");
        for (String type : new String[]{
                "DiodeBlock", "ObserverBlock", "RedstoneTorchBlock",
                "DetectorRailBlock", "DispenserBlock", "CrafterBlock",
                "BasePressurePlateBlock", "ButtonBlock", "CommandBlock",
                "LecternBlock", "LightningRodBlock", "RedstoneLampBlock",
                "SculkSensorBlock", "TargetBlock", "TripWireBlock",
                "TripWireHookBlock"
        }) {
            assertTrue(policy.contains("type instanceof " + type),
                    type + " may carry a unique scheduled edge into a later producer phase");
        }
        assertFalse(policy.contains("type instanceof LeavesBlock")
                        || policy.contains("type instanceof FallingBlock"),
                "terrain publication storms must stay on vanilla per-container refusal");
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && root != null; i++, root = root.getParent()) {
            Path file = root.resolve(relative);
            if (Files.isRegularFile(file)) return Files.readString(file);
        }
        Assumptions.assumeTrue(false, relative + " not found");
        return "";
    }
}
