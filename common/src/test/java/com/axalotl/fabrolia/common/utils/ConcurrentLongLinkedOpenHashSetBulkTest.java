package com.axalotl.fabrolia.common.utils;

import com.axalotl.fabrolia.api.fastutil.ConcurrentLongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongCollection;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConcurrentLongLinkedOpenHashSetBulkTest {
    @TestFactory
    Stream<DynamicTest> bulkOperationsCompleteAndPreserveOrder() {
        return Stream.of("add-boxed", "add-primitive", "remove-boxed", "remove-primitive",
                "retain-boxed", "retain-primitive", "membership-boxed").map(operation -> DynamicTest.dynamicTest(operation, () -> {
            String classpath = String.join(File.pathSeparator,
                    location(Probe.class), location(ConcurrentLongLinkedOpenHashSet.class), location(LongCollection.class));
            Process process = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", classpath, Probe.class.getName(), operation).redirectErrorStream(true).start();
            try {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), operation + " deadlocked");
                assertEquals(0, process.exitValue(),
                        () -> new String(readOutput(process), StandardCharsets.UTF_8));
            } finally {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "bulk probe did not terminate");
            }
        }));
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private static byte[] readOutput(Process process) {
        try {
            return process.getInputStream().readAllBytes();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    // A separate process lets the pre-fix uninterruptible StampedLock deadlock be killed cleanly.
    public static final class Probe {
        public static void main(String[] args) {
            boolean primitive = args[0].endsWith("primitive");
            var set = new ConcurrentLongLinkedOpenHashSet(List.of(3L, 1L, 2L));
            boolean changed;
            switch (args[0].split("-")[0]) {
                case "add" -> {
                    changed = primitive ? set.addAll(LongArrayList.of(2L, 4L, 0L))
                            : set.addAll(List.of(2L, 4L, 0L));
                    check(changed, "addition did not report change");
                    checkOrder(set, 3, 1, 2, 4, 0);
                    changed = primitive ? set.addAll((LongCollection) set)
                            : set.addAll((Collection<Long>) set);
                    check(!changed, "self-add changed membership");
                    checkOrder(set, 3, 1, 2, 4, 0);
                }
                case "remove" -> {
                    changed = primitive ? set.removeAll(LongArrayList.of(1L, 9L))
                            : set.removeAll(List.of(1L, "not a long", 9L));
                    check(changed, "removal did not report change");
                    checkOrder(set, 3, 2);
                    changed = primitive ? set.removeAll((LongCollection) set)
                            : set.removeAll((Collection<?>) set);
                    check(changed, "self-remove did not report change");
                    checkOrder(set);
                }
                case "retain" -> {
                    changed = primitive ? set.retainAll(LongArrayList.of(2L, 3L, 9L))
                            : set.retainAll(List.of(2L, 3L, "not a long", 9L));
                    check(changed, "retention did not report change");
                    checkOrder(set, 3, 2);
                    changed = primitive ? set.retainAll((LongCollection) set)
                            : set.retainAll((Collection<?>) set);
                    check(!changed, "self-retain changed membership");
                    checkOrder(set, 3, 2);
                }
                case "membership" -> {
                    var retained = new java.util.TreeSet<Long>((left, right) -> 0);
                    retained.add(3L);
                    check(!set.retainAll((Collection<?>) retained), "retainAll ignored source membership semantics");
                    checkOrder(set, 3, 1, 2);
                }
                default -> throw new AssertionError(args[0]);
            }
        }

        private static void checkOrder(ConcurrentLongLinkedOpenHashSet set, long... expected) {
            check(Arrays.equals(expected, set.toLongArray()), "wrong membership/order: " + set);
        }

        private static void check(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
    }
}
