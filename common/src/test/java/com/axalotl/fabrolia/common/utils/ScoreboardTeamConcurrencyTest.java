package com.axalotl.fabrolia.common.utils;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scoreboard's team-by-member index and team member sets are changed by
 * island workers (dead team members) while other threads read and write them.
 */
final class ScoreboardTeamConcurrencyTest {

    @Test
    void mapKeepsTheFastutilContract() {
        Object2ObjectMap<String, String> map = new ConcurrentObject2ObjectMap<>();
        assertNull(map.get("a"));
        assertNull(map.get(null));
        assertNull(map.put("a", "team"));
        assertEquals("team", map.put("a", "other"));
        assertTrue(map.containsKey("a"));
        assertEquals(1, map.size());
        assertEquals(1, map.object2ObjectEntrySet().size());
        var entry = map.object2ObjectEntrySet().iterator().next();
        assertEquals("a", entry.getKey());
        assertEquals("other", entry.getValue());
        assertEquals("other", map.remove("a"));
        assertNull(map.remove("a"));
        assertFalse(map.containsKey("a"));
        assertTrue(map.isEmpty());
    }

    @Test
    void concurrentDistinctMembersAreNeverLost() throws Exception {
        Object2ObjectMap<String, String> map = new ConcurrentObject2ObjectMap<>();
        int threads = 8;
        int perThread = 5_000;
        CyclicBarrier start = new CyclicBarrier(threads);
        List<Thread> workers = new ArrayList<>();
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < threads; t++) {
            int id = t;
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) map.put(id + ":" + i, "team");
                    for (int i = 0; i < perThread; i += 2) {
                        if (!"team".equals(map.remove(id + ":" + i))) {
                            throw new AssertionError("lost member " + id + ":" + i);
                        }
                    }
                } catch (Throwable problem) {
                    failures.add(problem);
                }
            });
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) worker.join();
        assertTrue(failures.isEmpty(), () -> "concurrent team index failed: " + failures);
        assertEquals(threads * perThread / 2, map.size());
    }

    @Test
    void scoreboardMixinsInstallTheConcurrentTables() throws Exception {
        String scoreboard = source("common/mixin/world/ScoreboardMixin.java");
        assertTrue(scoreboard.contains(
                "Object2ObjectMap<String, PlayerTeam> teamsByPlayer = new ConcurrentObject2ObjectMap<>()"));
        String team = source("common/mixin/world/PlayerTeamMixin.java");
        assertTrue(team.contains("@Mixin(PlayerTeam.class)")
                && team.contains("Set<String> players = ConcurrentCollections.newHashSet()"));
        try (var input = getClass().getResourceAsStream("/fabrolia.common.mixins.json")) {
            String config = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(config.contains("\"world.PlayerTeamMixin\""), "the team mixin must be registered");
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
