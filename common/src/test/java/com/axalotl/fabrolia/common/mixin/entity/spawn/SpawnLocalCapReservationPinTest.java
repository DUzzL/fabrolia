package com.axalotl.fabrolia.common.mixin.entity.spawn;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task #32 endgame pins: the per-candidate local-cap recheck (which starved
 * farms sitting AT the cap — wither-skeleton-farm report) is REPLACED by
 * atomic pack reservation at chunk-pass approval
 * ({@code NaturalSpawnerMixin.fabrolia$approveAndReservePack} +
 * {@code fabrolia$releasePackAfterSpawn} over
 * {@code LocalCapPackReservation}).
 *
 * <p>Two pin layers, because mixins do not apply in unit tests:
 * <ul>
 *   <li><b>Vanilla bytecode</b> — {@code NaturalSpawner.spawnForChunk} must
 *       still contain the two INVOKEs the {@code @WrapOperation}s bind to
 *       (approval + pack execution); an MC reshape fails here at build time,
 *       not as a silently-unapplied wrap in production.</li>
 *   <li><b>Mixin source</b> — the recheck must stay gone (its return would
 *       re-starve farms) and the reserve/release pair must stay wired (its
 *       loss reopens the task-#32 parallel overshoot window).</li>
 * </ul>
 */
class SpawnLocalCapReservationPinTest {

    private static final String SPAWN_STATE = "net/minecraft/world/level/NaturalSpawner$SpawnState";
    private static final String NATURAL_SPAWNER = "net/minecraft/world/level/NaturalSpawner";

    private static Path findSource(String rel) {
        Path p = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++, p = p.getParent()) {
            Path direct = p.resolve(rel);
            if (Files.exists(direct)) return direct;
            Path viaCommon = p.resolve("common").resolve(rel);
            if (Files.exists(viaCommon)) return viaCommon;
        }
        return null;
    }

    private static ClassNode load(String classResource) throws IOException {
        ClassNode node = new ClassNode();
        try (InputStream in = SpawnLocalCapReservationPinTest.class.getResourceAsStream(classResource)) {
            assertNotNull(in, classResource + " must be on the test classpath");
            new ClassReader(in).accept(node, 0);
        }
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no method " + name + " on " + node.name));
    }

    private static boolean callsMethod(MethodNode m, String owner, String name, String desc) {
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi
                    && mi.owner.equals(owner) && mi.name.equals(name) && mi.desc.equals(desc)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void vanillaSeamsTheWrapsBindToStillExist() throws IOException {
        ClassNode ns = load("/net/minecraft/world/level/NaturalSpawner.class");
        MethodNode spawnForChunk = method(ns, "spawnForChunk");

        assertTrue(callsMethod(spawnForChunk, SPAWN_STATE, "canSpawnForCategoryLocal",
                        "(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/world/level/ChunkPos;)Z"),
                "spawnForChunk must still approve per (chunk, category) via canSpawnForCategoryLocal "
                        + "— the fused approve+reserve wrap binds here");
        assertTrue(callsMethod(spawnForChunk, NATURAL_SPAWNER, "spawnCategoryForChunk",
                        "(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;"
                                + "Lnet/minecraft/world/level/chunk/LevelChunk;"
                                + "Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;"
                                + "Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V"),
                "spawnForChunk must still run the pack via spawnCategoryForChunk — the pack-end "
                        + "release wrap binds here");
    }

    @Test
    void perCandidateRecheckStaysRemoved() {
        Path spawnStateMixin = findSource(
                "src/main/java/com/axalotl/fabrolia/common/mixin/entity/spawn/SpawnStateMixin.java");
        Assumptions.assumeTrue(spawnStateMixin != null, "source tree not available");
        String src;
        try {
            src = Files.readString(spawnStateMixin);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        assertFalse(src.contains("fabrolia$parallelLocalCapRecheck"),
                "the per-candidate local-cap recheck is back — it starves farms sitting AT the cap "
                        + "(pack spawning degrades to a trickle); the reservation replaces it");
        assertFalse(src.matches("(?s).*method\\s*=\\s*\"canSpawn\\(Lnet/minecraft/world/entity/EntityType"
                        + ".{0,200}?cancellable\\s*=\\s*true.*"),
                "canSpawn must never be cancellable again — no per-candidate local-cap rejection "
                        + "inside an approved pack");
        assertFalse(src.contains("fabroliaSpawnLocalCapRecheck"),
                "the recheck config flag was removed with the recheck");
    }

    @Test
    void reservationIsWired() {
        Path nsMixin = findSource(
                "src/main/java/com/axalotl/fabrolia/common/mixin/entity/spawn/NaturalSpawnerMixin.java");
        Assumptions.assumeTrue(nsMixin != null, "source tree not available");
        String src;
        try {
            src = Files.readString(nsMixin);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        assertTrue(src.contains("fabrolia$approveAndReservePack"),
                "chunk-pass approval must be the fused atomic check+reserve — without it every "
                        + "parallel pass approves in the same open window (task #32, 2-7x local cap)");
        assertTrue(src.contains("fabrolia$releasePackAfterSpawn"),
                "the pack-end release must stay wired — leaked reservations ratchet the counts up "
                        + "and strangle later approvals");
        int releaseMethod = src.indexOf("private static void fabrolia$releasePackAfterSpawn");
        int releaseEnd = src.indexOf("\n    }\n", releaseMethod);
        String releaseBody = src.substring(releaseMethod, releaseEnd);
        int noToken = releaseBody.indexOf("if (token == 0)");
        int noTokenAdmission = releaseBody.indexOf(
                "RegionScheduler.runSuccessBodyIfHealthy(", noToken);
        int tokenClear = releaseBody.indexOf("reservation.set(0)");
        int packAdmission = releaseBody.indexOf(
                "RegionScheduler.runSuccessBodyIfHealthy(", tokenClear);
        int pack = releaseBody.indexOf(
                "original.call(category, level, chunk, extraTest, spawnCallback)", packAdmission);
        int bodyCatch = releaseBody.indexOf("catch (Throwable thrown)", pack);
        int selectWinner = releaseBody.indexOf(
                "failure = RegionScheduler.publishFatalAndSelectWinner(thrown)", bodyCatch);
        int release = releaseBody.indexOf("fabrolia$releaseLocalPack", selectWinner);
        int cleanupCatch = releaseBody.indexOf("catch (Throwable cleanup)", release);
        int merge = releaseBody.indexOf("FailStops.mergeFailure(failure, cleanup)", cleanupCatch);
        int terminal = releaseBody.indexOf(
                "RegionScheduler.commitSuccessIfHealthy(() -> {})", merge);
        int rethrow = releaseBody.indexOf("FailStops.<RuntimeException>rethrow(failure)", terminal);
        assertTrue(releaseMethod >= 0 && releaseEnd > releaseMethod
                        && noToken >= 0 && noTokenAdmission > noToken
                        && tokenClear > noTokenAdmission && packAdmission > tokenClear
                        && pack > packAdmission && bodyCatch > pack
                        && selectWinner > bodyCatch && release > selectWinner
                        && cleanupCatch > release && merge > cleanupCatch
                        && terminal > merge && rethrow > terminal,
                "a throwing entered pack must select W before exhaustive reservation release, "
                        + "merge cleanup failure, pass a post-cleanup success cut and rethrow W");
        assertFalse(releaseBody.contains("throwWorkerFailureIfAny"),
                "pack entry must not use a check-then-original window");
        assertFalse(releaseBody.substring(tokenClear, rethrow).contains("finally"),
                "a throwing finally cleanup could replace the exact pack-body primary");
        assertTrue(src.contains("@Share(\"fabrolia$packReservation\")"),
                "reserve and release must pair through the shared per-invocation token (never "
                        + "re-derived from config — a mid-tick toggle flip must not leak or "
                        + "double-release)");
    }

    @Test
    void reservationAlgorithmKeepsItsContract() {
        Path helper = findSource(
                "src/main/java/com/axalotl/fabrolia/common/parallelised/utils/LocalCapPackReservation.java");
        Assumptions.assumeTrue(helper != null, "source tree not available");
        String src;
        try {
            src = Files.readString(helper);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        assertTrue(src.contains("getAndAdd"),
                "the fused check+reserve must stay a single atomic RMW per player — a separate "
                        + "read-then-add reopens the approval window");
        assertTrue(src.contains("PACK_RESERVE = 4"),
                "reservation width is vanilla's max spawn cluster (4); changing it changes the "
                        + "overshoot bound — re-argue the bound if this moves");
    }

    /** 2026-10-24 tunnel-bore review: the weighted seed must choose players
     *  with the INSTALLED addMob (the rule approval and post-spawn addMob use;
     *  VMP overwrites both), never with a separate getPlayersNear walk. */
    @Test
    void weightedSeedChoosesPlayersThroughTheInstalledAddMob() throws IOException {
        Path calcMixin = findSource(
                "src/main/java/com/axalotl/fabrolia/common/mixin/entity/spawn/LocalMobCapCalculatorMixin.java");
        Path countsMixin = findSource(
                "src/main/java/com/axalotl/fabrolia/common/mixin/entity/spawn/LocalMobCapCalculatorMobCountsMixin.java");
        Assumptions.assumeTrue(calcMixin != null && countsMixin != null, "source tree not available");
        String calc = Files.readString(calcMixin);
        int start = calc.indexOf("public void fabrolia$addMobs(");
        assertTrue(start >= 0, "weighted seed entry is gone");
        String body = calc.substring(start, calc.indexOf("\n    }", start));
        assertTrue(body.contains("beginSeedRecording()") && body.contains(".addMob(pos, cat)")
                        && body.contains("endSeedRecording()") && body.contains("fabrolia$addCopies"),
                "the seed must record one real addMob and replay the copies onto its counts");
        assertFalse(body.contains("getPlayersNear"),
                "a separate near-player walk in the seed disagrees with the installed approval rule");

        String counts = Files.readString(countsMixin);
        int add = counts.indexOf("private void fabrolia$atomicAdd(");
        assertTrue(add >= 0, "MobCounts.add wrap is gone");
        assertTrue(counts.substring(add, counts.indexOf("\n    }", add)).contains("recordSeedIncrement"),
                "every MobCounts.add must report to the seed recorder, or copies are lost");
    }
}
