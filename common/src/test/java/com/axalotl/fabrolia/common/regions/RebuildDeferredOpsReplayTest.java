package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit B-05: deferred cross-region effects collected from the islands at a
 * topology rebuild run under the rebuild quiesce, not later from the global
 * queue without a claim.
 */
final class RebuildDeferredOpsReplayTest {

    @Test
    void replayRunsInOrderUnderAllClaims() throws Exception {
        RegionizedServer runtime = (RegionizedServer) unsafe().allocateInstance(RegionizedServer.class);
        Class<?> ownershipType = Class.forName(RegionizedServer.class.getName() + "$MainAllClaimsOwnership");
        Constructor<?> constructor = ownershipType.getDeclaredConstructor(Object.class, Thread.class, boolean.class);
        constructor.setAccessible(true);
        Field ownership = RegionizedServer.class.getDeclaredField("mainAllClaimsOwnership");
        ownership.setAccessible(true);
        ownership.set(runtime, constructor.newInstance(new Object(), Thread.currentThread(), false));

        List<Integer> ran = new ArrayList<>();
        replay(runtime, List.of(() -> ran.add(1), () -> ran.add(2), () -> ran.add(3)));
        assertEquals(List.of(1, 2, 3), ran);
    }

    @Test
    void replayWithoutTheQuiesceFailsLoud() throws Exception {
        RegionizedServer runtime = (RegionizedServer) unsafe().allocateInstance(RegionizedServer.class);
        List<Integer> ran = new ArrayList<>();
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> replay(runtime, List.of(() -> ran.add(1))));
        assertInstanceOf(IllegalStateException.class, thrown.getCause());
        assertTrue(ran.isEmpty());
    }

    @Test
    void rebuildCollectsIslandOpsInsteadOfMovingThemToTheGlobalQueue() throws Exception {
        String text = source("common/regions/RegionizedServer.java");
        int body = text.indexOf("private void rebuildIslandsBody()");
        int poll = text.indexOf("while ((op = p.domain.deferredOps.poll()) != null)", body);
        int collect = text.indexOf("unstashedOps.add(op);", poll);
        int replay = text.indexOf("if (unstashedOps != null) replayIslandOpsUnderQuiesce(unstashedOps);", collect);
        int rebase = text.indexOf("ArrayList<Region> topologyRebaseBatch", body);
        assertTrue(body >= 0 && poll > body && collect > poll && replay > collect && rebase > replay,
                "island effects are replayed under the rebuild quiesce before the topology changes");
        assertTrue(text.indexOf("globalDomain.deferredOps.add(op)", body) < 0
                        || text.indexOf("globalDomain.deferredOps.add(op)", body) > rebase,
                "island effects must not be appended to the claimless global queue");
    }

    private static void replay(RegionizedServer runtime, List<Runnable> ops) throws Exception {
        Method method = RegionizedServer.class.getDeclaredMethod("replayIslandOpsUnderQuiesce", List.class);
        method.setAccessible(true);
        method.invoke(runtime, ops);
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

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }
}
