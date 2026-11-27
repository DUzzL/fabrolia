package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source pins for routed island command blocks (night campaign 2026-11-18).
 *
 * <p>R1: the routed batch has exactly one execution lane, under the all-island
 * fence. A selector-free {@code scoreboard} line once ran fenceless on Main
 * while island workers iterated the same objective lists.
 */
class RoutedCommandBlockWiringTest {

    @Test
    void routedBatchExecutesNoBodyBeforeTheAllIslandFence() throws Exception {
        String commands = source("common/regions/IslandCommands.java");
        assertFalse(commands.contains("isGlobalNoFence"),
                "no routed command may be classified as fenceless");
        assertFalse(commands.contains("boolean fenceless") || commands.contains(".fenceless"),
                "the routed batch has no fenceless lane");
        String drain = section(commands, "private static void fabrolia$drainRoutedBatchEntered",
                "private static void execute");
        int fence = drain.indexOf("quiesceAllExcept(null)");
        int firstExecute = drain.indexOf("execute(");
        assertTrue(fence > 0, "the routed batch takes the all-island fence");
        assertTrue(firstExecute > fence,
                "every routed body executes after the all-island fence was taken");
        assertEquals(1, count(drain, "execute("),
                "the fenced loop is the only routed execution site");
    }

    @Test
    void routedBodiesBindTheRoutingIslandClockOnMain() throws Exception {
        String commands = source("common/regions/IslandCommands.java");
        String park = section(commands, "private static boolean routeAndPark",
                "private static boolean parkCommandWaiterForGlobalFence");
        int capture = park.indexOf("long ownerTime = IslandClock.contextTime();");
        int publish = park.indexOf("ROUTED.add(new RoutedCmd(inv, body, fut, null, ownerTime))");
        assertTrue(capture >= 0 && publish > capture,
                "the coordinator captures the clock its inline bodies read before publishing");
        String execute = section(commands, "private static void execute(RoutedCmd rc)",
                "private static Throwable failAcceptedRouted");
        assertTrue(execute.contains(
                        "rc.inv.result = IslandClock.callWithRoutedOwnerTime(rc.ownerTime, rc.body) ? 1 : 0;"),
                "Main binds the routing island's clock around exactly the routed body");
        assertEquals(1, count(commands, "rc.body.getAsBoolean()") + count(commands, "rc.body)"),
                "the routed body has a single entry point");
    }

    @Test
    void commandBlockTickGuardAndGametimeQueryReadTheBoundIslandClock() throws Exception {
        String block = source("common/mixin/commands/BaseCommandBlockMixin.java");
        String guard = section(block, "@WrapOperation(method = \"performCommand\"", "\n    }\n");
        assertTrue(guard.contains("target = \"Lnet/minecraft/server/level/ServerLevel;getGameTime()J\"")
                        && guard.contains("require = 2, allow = 2")
                        && guard.contains("long owner = IslandClock.routedOwnerTime();")
                        && guard.contains("return owner >= 0L ? owner : original.call(level);"),
                "both reads of vanilla's once-per-tick guard use the routed island clock");

        String time = source("common/mixin/commands/TimeCommandMixin.java");
        String query = section(time, "@WrapOperation(method = \"queryGameTime\"", "\n    }\n");
        assertTrue(query.contains("target = \"Lnet/minecraft/server/level/ServerLevel;getGameTime()J\"")
                        && query.contains("require = 1, allow = 1")
                        && query.contains("private static long fabrolia$routedOwnerGameTime")
                        && query.contains("return owner >= 0L ? owner : original.call(level);"),
                "`time query gametime` reads the routed island clock");
        assertTrue(resource("fabrolia.common.mixins.json").contains("\"commands.TimeCommandMixin\""),
                "the gametime query mixin is registered for both loaders");

        // W stays W: schedule (W-ticked TimerQueue) and worldborder (global) are not wrapped.
        String clock = source("common/regions/IslandClock.java");
        String contextTime = section(clock, "public static long contextTime()", "\n    }\n");
        assertFalse(contextTime.contains("routedOwnerTime") || contextTime.contains("ROUTED_OWNER_TIME"),
                "the routed binding never becomes Main's context clock");
    }

    @Test
    void vanillaReadsTheWrappedGameTimeExactlyWhereThePinsExpect() throws Exception {
        assertEquals(2, gameTimeReads("net/minecraft/world/level/BaseCommandBlock", "performCommand"));
        assertEquals(1, gameTimeReads("net/minecraft/server/commands/TimeCommand", "queryGameTime"));
    }

    private static int gameTimeReads(String owner, String method) throws Exception {
        byte[] bytes;
        try (var in = RoutedCommandBlockWiringTest.class.getClassLoader()
                .getResourceAsStream(owner + ".class")) {
            assertNotNull(in, owner);
            bytes = in.readAllBytes();
        }
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
        int reads = 0;
        boolean found = false;
        for (org.objectweb.asm.tree.MethodNode m : node.methods) {
            if (!m.name.equals(method)) continue;
            found = true;
            for (var insn : m.instructions) {
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
                        && call.owner.equals("net/minecraft/server/level/ServerLevel")
                        && call.name.equals("getGameTime") && call.desc.equals("()J")) {
                    reads++;
                }
            }
        }
        assertTrue(found, () -> owner + "." + method + " not found");
        return reads;
    }

    private static String resource(String name) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null) {
            Path direct = root.resolve("src/main/resources").resolve(name);
            Path common = root.resolve("common/src/main/resources").resolve(name);
            if (Files.isRegularFile(direct)) return Files.readString(direct);
            if (Files.isRegularFile(common)) return Files.readString(common);
            root = root.getParent();
        }
        throw new IllegalStateException("cannot locate resource: " + name);
    }

    private static String section(String text, String startNeedle, String endNeedle) {
        int start = text.indexOf(startNeedle);
        int end = text.indexOf(endNeedle, Math.max(0, start + startNeedle.length()));
        assertTrue(start >= 0 && end > start,
                () -> "source section not found: " + startNeedle + " .. " + endNeedle);
        return text.substring(start, end);
    }

    private static int count(String text, String needle) {
        int matches = 0;
        for (int at = 0; (at = text.indexOf(needle, at)) >= 0; at += needle.length()) {
            matches++;
        }
        return matches;
    }

    private static String source(String relative) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null) {
            Path direct = root.resolve("src/main/java/com/axalotl/fabrolia").resolve(relative);
            Path common = root.resolve("common/src/main/java/com/axalotl/fabrolia")
                    .resolve(relative);
            if (Files.isRegularFile(direct)) return Files.readString(direct);
            if (Files.isRegularFile(common)) return Files.readString(common);
            root = root.getParent();
        }
        throw new IllegalStateException("cannot locate source: " + relative);
    }
}
