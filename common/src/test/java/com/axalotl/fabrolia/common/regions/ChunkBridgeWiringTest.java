package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task #34 structural pins — the mixin half of the chunk-bridge liveness fix
 * lives in shadow-bound {@code @Unique} methods a unit test cannot invoke, so
 * the review-driven hardenings are pinned at source level (same rationale as
 * {@link RegionalPlayerTickWiringTest}).
 */
class ChunkBridgeWiringTest {

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

    @Test
    void tierThreeUsesTheBoundedBridgeWithMemoAndRetries() throws IOException {
        Path mixin = findSource(
                "src/main/java/com/axalotl/fabrolia/common/mixin/server/ServerChunkCacheMixin.java");
        Assumptions.assumeTrue(mixin != null, "source tree not available");
        String src = Files.readString(mixin);

        assertTrue(src.contains("ChunkBridgeWait.await"),
                "Tier-3 must wait through ChunkBridgeWait (bounded load=false / "
                        + "self-healing load=true) — a raw unbounded park is the "
                        + "2026-07-28 06:56 watchdog");
        assertTrue(src.contains("fabrolia$softNullUntil.put"),
                "a load=false budget expiry must feed the soft-null memo — without "
                        + "it a persistent strand costs a full budget PER LOOKUP "
                        + "(N mobs × collisions/tick × 200ms inside the entity barrier)");
        assertTrue(src.contains("fabrolia$softNullUntil.get"),
                "Tier-3 must consult the soft-null memo before bridging");
        assertTrue(src.contains("LOAD_TRUE_MAX_RESUBMITS"),
                "a COMPLETED-but-empty load=true outcome (churn UNLOADED result or "
                        + "exceptional stale future winning the applyToEither race) must "
                        + "be retried with a fresh ticket+schedule before the vanilla "
                        + "failure throw — first-completion-wins alone crashes the "
                        + "entity tick on a transient race");
        // The memo must be consulted only AFTER the ready-probes so a chunk
        // that became available is never masked by a stale negative entry.
        int probe = src.indexOf("fabrolia$extractReady(holder, targetStatus)");
        int memo = src.indexOf("fabrolia$softNullUntil.get");
        assertTrue(probe > 0 && memo > probe,
                "soft-null memo consult must come after the Tier-1 ready probe");
    }
}
