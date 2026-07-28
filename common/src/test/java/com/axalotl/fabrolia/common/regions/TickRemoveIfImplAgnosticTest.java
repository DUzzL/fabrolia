package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task #37 — the 2026-07-28 22:10 production NPE
 * ({@code ScheduledTick.pos()} on a null from Lithium's {@code removeIf}).
 *
 * <p>Two halves: (a) a documentation repro of the UPSTREAM Lithium bug on the
 * real {@code OrderedTickQueue} (absolute-index iteration over a
 * poll-compacted ring reads nulled slots); (b) correctness of Fabrolia's
 * replacement — {@link TickDomains#drainRemoveIf} / {@link TickDomains#shiftAll}
 * — on a REAL vanilla container in exactly the partially-polled state that
 * crashed production.
 */
class TickRemoveIfImplAgnosticTest {

    private static ScheduledTick<String> tick(String type, int x, long trigger, long subTick) {
        return new ScheduledTick<>(type, new BlockPos(x, 64, 0), trigger, TickPriority.NORMAL, subTick);
    }

    /** The upstream defect, demonstrated on Lithium's own class: after a
     *  partial poll of a sorted queue, live entries occupy
     *  [firstIndex, lastIndexExclusive) but index 0..size() reads the nulled
     *  polled slot — exactly what Lithium's removeIf dereferences. */
    @Test
    void lithiumOrderedTickQueuePartialPollLeavesNullAtAbsoluteIndexZero() {
        Class<?> qClass;
        try {
            qClass = Class.forName("net.caffeinemc.mods.lithium.common.world.scheduler.OrderedTickQueue");
        } catch (ClassNotFoundException e) {
            Assumptions.assumeTrue(false, "lithium not on test classpath");
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            java.util.Queue<ScheduledTick<String>> q =
                    (java.util.Queue<ScheduledTick<String>>) qClass.getDeclaredConstructor().newInstance();
            q.offer(tick("a", 0, 100, 0));
            q.offer(tick("b", 1, 100, 1));
            q.offer(tick("c", 2, 100, 2));
            // sort() so the queue is in the state removeIf's getTickAtIndex allows
            qClass.getMethod("sort").invoke(q);
            assertEquals(tick("a", 0, 100, 0).pos(), q.poll().pos()); // partial poll → slot 0 nulled
            assertEquals(2, q.size(), "two live ticks remain");
            Object atZero = qClass.getMethod("getTickAtIndex", int.class).invoke(q, 0);
            assertNull(atZero,
                    "absolute index 0 reads the polled (nulled) slot while size()==2 — "
                            + "Lithium's removeIf loop 'for (i=0; i<size(); i++) getTickAtIndex(i)' "
                            + "dereferences this null: the production NPE");
        } catch (ReflectiveOperationException e) {
            Assumptions.assumeTrue(false, "lithium OrderedTickQueue surface changed: " + e);
        }
    }

    /** drainRemoveIf on a REAL vanilla container in the partially-polled
     *  state: removes by predicate, keeps survivors, dedup set stays
     *  consistent (same pos+type re-schedules cleanly). */
    @Test
    void drainRemoveIfSurvivesPartialPollAndKeepsDedupConsistent() {
        LevelChunkTicks<String> c = new LevelChunkTicks<>();
        c.schedule(tick("a", 0, 100, 0));
        c.schedule(tick("b", 1, 100, 1));
        c.schedule(tick("c", 2, 200, 2));
        c.schedule(tick("d", 3, 200, 3));
        assertEquals("a", c.poll().type()); // the crashed state: mid-drain container

        int removed = TickDomains.drainRemoveIf(c, t -> t.type().equals("c"));
        assertEquals(1, removed);
        assertEquals(2, c.count(), "b and d survive");

        // dedup consistency: the removed (pos, type) can be scheduled again
        c.schedule(tick("c", 2, 300, 9));
        assertEquals(3, c.count(), "removed tick's dedup slot must be free again");

        List<String> order = new ArrayList<>();
        for (ScheduledTick<String> t; (t = c.poll()) != null; ) order.add(t.type());
        assertEquals(List.of("b", "d", "c"), order, "trigger/subTick order preserved");
    }

    /** shiftAll on a partially-polled container: survivors shifted by delta,
     *  order preserved, nothing lost or duplicated — the exact rebase that
     *  crashed through Lithium's removeIf. */
    @Test
    void shiftAllSurvivesPartialPoll() {
        LevelChunkTicks<String> c = new LevelChunkTicks<>();
        c.schedule(tick("a", 0, 100, 0));
        c.schedule(tick("b", 1, 150, 1));
        c.schedule(tick("c", 2, 200, 2));
        c.poll(); // partial

        int moved = TickDomains.shiftAll(c, 1000);
        assertEquals(2, moved);
        ScheduledTick<String> first = c.poll();
        ScheduledTick<String> second = c.poll();
        assertNull(c.poll());
        assertEquals("b", first.type());
        assertEquals(1150, first.triggerTick());
        assertEquals(1, first.subTickOrder(), "subTickOrder preserved exactly");
        assertEquals("c", second.type());
        assertEquals(1200, second.triggerTick());
    }

    /** Task #37b (2026-07-29 01:35 regression, 'Failed to load chunk'):
     *  Lithium's poll() Overwrite dereferences its nextTickQueue field and
     *  NPEs on an EMPTY container — their contract is peek-first (peek IS
     *  null-safe). A PACKED (deserialized, not yet unpacked) Lithium
     *  container reports count()>0 while the live queues are empty, so the
     *  count()>0 gate does not protect the drain. This emulation mirrors
     *  that exact contract: the drain must peek-guard and no-op, preserving
     *  the audit15 'packed state stays untouched' invariant. */
    static final class LithiumContractChunkTicks<T> extends LevelChunkTicks<T> {
        boolean emulatePacked;

        @Override public ScheduledTick<T> poll() {
            if (super.peek() == null) {
                throw new NullPointerException(
                        "emulated Lithium poll(): this.nextTickQueue is null");
            }
            return super.poll();
        }

        @Override public int count() {
            return emulatePacked ? 3 : super.count();
        }
    }

    @Test
    void drainAndShiftPeekGuardTheLithiumEmptyPollContract() {
        LithiumContractChunkTicks<String> packed = new LithiumContractChunkTicks<>();
        packed.emulatePacked = true; // count()>0, live queue empty — the crash state
        assertEquals(0, TickDomains.drainRemoveIf(packed, t -> true),
                "packed container must be a NO-OP, not an NPE");
        assertEquals(0, TickDomains.shiftAll(packed, 1000),
                "packed container rebase must retag-only, live queue untouched");

        LithiumContractChunkTicks<String> live = new LithiumContractChunkTicks<>();
        live.schedule(tick("a", 0, 100, 0));
        live.schedule(tick("b", 1, 200, 1));
        assertEquals(2, TickDomains.shiftAll(live, 50),
                "live entries still drain fully through the peek guard");
        assertEquals(150, live.poll().triggerTick());
        assertEquals(250, live.poll().triggerTick());
    }

    /** Source pin: no Fabrolia code may call LevelChunkTicks.removeIf
     *  directly — every removal goes through the drain helper. */
    @Test
    void noDirectRemoveIfCallSitesRemain() throws Exception {
        java.nio.file.Path root = java.nio.file.Paths.get("").toAbsolutePath();
        java.nio.file.Path src = null;
        for (int i = 0; i < 6 && root != null; i++, root = root.getParent()) {
            java.nio.file.Path direct = root.resolve("src/main/java/com/axalotl/fabrolia");
            java.nio.file.Path viaCommon = root.resolve("common/src/main/java/com/axalotl/fabrolia");
            if (java.nio.file.Files.isDirectory(direct)) { src = direct; break; }
            if (java.nio.file.Files.isDirectory(viaCommon)) { src = viaCommon; break; }
        }
        Assumptions.assumeTrue(src != null, "source tree not available");
        List<String> offenders = new ArrayList<>();
        try (var walk = java.nio.file.Files.walk(src)) {
            for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) walk
                    .filter(f -> f.toString().endsWith(".java"))::iterator) {
                String text = java.nio.file.Files.readString(p);
                if (!text.contains("removeIf(")) continue;
                // ignore collection removeIf and the drain helper itself
                for (String line : text.split("\n")) {
                    if (line.contains(".removeIf(") && (line.contains("erased.removeIf")
                            || line.matches(".*\\bc\\.removeIf\\(.*"))) {
                        offenders.add(p.getFileName() + ": " + line.trim());
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "LevelChunkTicks.removeIf is broken under Lithium (task #37) — use "
                        + "TickDomains.drainRemoveIf. Offenders: " + offenders);
    }
}
