package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.world.CatSpawnerTickAccessor;
import com.axalotl.fabrolia.common.mixin.world.PhantomSpawnerTickAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.CatSpawner;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Audit C-02: a vanilla custom spawner's world-work tick runs under the global fence. */
final class CustomSpawnerFenceTest {

    @Test
    void onlyTheCountdownTickWorks() {
        Cat cat = new Cat();
        cat.next = 5;
        assertFalse(CustomSpawnerFence.worksThisTick(null, cat, true));
        cat.next = 1;
        assertTrue(CustomSpawnerFence.worksThisTick(null, cat, true), "this call decrements to zero");
        Phantom phantom = new Phantom();
        phantom.next = 1;
        assertFalse(CustomSpawnerFence.worksThisTick(null, phantom, false),
                "phantoms do not count down without enemy spawning");
        CustomSpawner modded = (level, spawnEnemies) -> { };
        assertFalse(CustomSpawnerFence.worksThisTick(null, modded, true), "unknown spawners keep their path");
    }

    @Test
    void withoutARuntimeTheSpawnerRunsInline() {
        AtomicBoolean ran = new AtomicBoolean();
        CustomSpawnerFence.tick(null, new Cat(), true, () -> ran.set(true));
        assertTrue(ran.get());
    }

    @Test
    void mixinsRouteTheSpawnersAndSiegeSpawns() throws Exception {
        assertTrue(source("common/mixin/world/CustomSpawnerFenceMixin.java")
                .contains("CustomSpawnerFence.tick(level, spawner, spawnEnemies, () -> original.call(spawner, level, spawnEnemies))"));
        String siege = source("common/mixin/world/VillageSiegeSpawnFenceMixin.java");
        assertTrue(siege.contains("@WrapMethod(method = \"trySpawn\")")
                && siege.contains("AsyncIslandPlayerTicks.runFencedMainMutation(\"village siege spawn\""));
    }

    private static final class Cat extends CatSpawner implements CatSpawnerTickAccessor {
        int next = 1200;
        @Override public int fabrolia$nextTick() { return next; }
    }

    private static final class Phantom extends PhantomSpawner implements PhantomSpawnerTickAccessor {
        int next;
        @Override public int fabrolia$nextTick() { return next; }
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
