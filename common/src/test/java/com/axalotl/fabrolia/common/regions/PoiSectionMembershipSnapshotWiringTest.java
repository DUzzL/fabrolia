package com.axalotl.fabrolia.common.regions;

import net.caffeinemc.mods.lithium.common.world.interests.PointOfInterestSetExtended;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source and dependency-census pins for the 2026-08-28 POI membership CME.
 * Vanilla and Lithium both used lazy/live iterators over PoiSection's plain
 * HashMap/HashSet indexes while C2ME or another region worker mutated them.
 */
class PoiSectionMembershipSnapshotWiringTest {

    private static final String VANILLA_MIXIN =
            "common/src/main/java/com/axalotl/fabrolia/common/mixin/entity/movement/PoiSectionMixin.java";
    private static final String LITHIUM_MIXIN =
            "common/src/main/java/com/axalotl/fabrolia/common/mixin/lithium/"
                    + "FabroliaLithiumPoiSectionSnapshotMixin.java";
    private static final String GROUPED_SNAPSHOT =
            "common/src/main/java/com/axalotl/fabrolia/common/utils/GroupedSnapshot.java";
    private static final String POI_SNAPSHOT =
            "common/src/main/java/com/axalotl/fabrolia/common/utils/PoiSectionReadSnapshot.java";
    private static final String MIXIN_CONFIG =
            "common/src/main/resources/fabrolia.common.mixins.json";
    private static final String MIXIN_CANCELLER =
            "common/src/main/java/com/axalotl/fabrolia/common/mixin/utils/MixinCanceller.java";

    @Test
    void genericCaptureEagerlyCopiesRawGroupsBeforeAnyCallbackSurface() throws Exception {
        String source = read(GROUPED_SNAPSHOT);
        String capture = methodBlock(source,
                "public static <K, V> GroupedSnapshot<K, V> capture");

        int monitor = capture.indexOf("synchronized (monitor)");
        int outerCopy = capture.indexOf("source.entrySet()");
        int nestedCopy = capture.indexOf("new ArrayList<>(entry.getValue())");
        int publication = capture.indexOf("return new GroupedSnapshot<>(groups)");
        assertTrue(monitor >= 0 && outerCopy > monitor && nestedCopy > outerCopy
                        && publication > nestedCopy,
                "capture must copy the live map and every nested collection under one exact monitor");
        assertFalse(capture.contains("Predicate") || capture.contains("Consumer")
                        || capture.contains(".test(") || capture.contains(".accept("),
                "capture must snapshot raw membership; arbitrary callbacks belong after monitor release");
        assertFalse(capture.contains("Stream") || capture.contains("Iterator"),
                "capture must not publish a lazy view or live iterator");
        assertNoSuppressionOrRetry(capture, "generic grouped snapshot capture");

        String stream = methodBlock(source, "public Stream<V> stream");
        assertTrue(stream.contains("this.groups.stream()")
                        && stream.contains("keyPredicate.test(group.key())")
                        && stream.contains("valuePredicate"),
                "all predicates must consume only detached groups after capture returned");
        assertFalse(stream.contains("synchronized"),
                "snapshot predicates must never execute while holding the mutation monitor");
    }

    @Test
    void poiReadViewCapturesEveryRawIndexAtOneMonitorInstant() throws Exception {
        String source = read(POI_SNAPSHOT);
        String capture = methodBlock(source,
                "public static PoiSectionReadSnapshot capture");
        int monitor = capture.indexOf("synchronized (monitor)");
        int grouped = capture.indexOf("GroupedSnapshot.capture(monitor, byType)");
        int ordered = capture.indexOf("new ArrayList<>(records.values())");
        int positional = capture.indexOf("new Short2ObjectOpenHashMap<>(records)");
        int valid = capture.indexOf(
                "groupedRecords, orderedRecords, recordsByPosition, valid");
        assertTrue(monitor >= 0 && grouped > monitor && ordered > grouped
                        && positional > ordered && valid > positional,
                "grouped, pack-order, positional and validity reads must share one capture instant");
        assertFalse(capture.contains("Predicate") || capture.contains("Consumer")
                        || capture.contains(".test(") || capture.contains(".accept("),
                "snapshot publication must copy raw state without caller callbacks");
        assertFalse(capture.contains("Stream") || capture.contains("Iterator"),
                "the cached read view must contain detached collections, not lazy live traversal");
        assertNoSuppressionOrRetry(capture, "PoiSection cached view capture");

        for (String reader : new String[]{
                "public GroupedSnapshot<Holder<PoiType>, PoiRecord> groupedRecords()",
                "public List<PoiRecord> records()",
                "public PoiRecord recordAt(BlockPos pos)",
                "public boolean isValid()"}) {
            String body = methodBlock(source, reader);
            assertFalse(body.contains("synchronized"),
                    reader + " must be a lock-free immutable-view read");
        }
    }

    @Test
    void writerPublicationIsVolatileAndPrecedesExactlyThreeDirtyCallbacks()
            throws Exception {
        String source = read(VANILLA_MIXIN);
        assertTrue(source.contains(
                        "private volatile PoiSectionReadSnapshot fabrolia$readSnapshot;"),
                "readers need one safely published cached immutable view");
        assertTrue(source.contains(
                        "method = \"<init>(Ljava/lang/Runnable;ZLjava/util/List;)V\"")
                        && source.contains("at = @At(\"RETURN\")")
                        && source.contains("require = 1")
                        && source.contains("allow = 1"),
                "the exact full PoiSection constructor must publish its initial view once");

        String initial = methodBlock(source, "fabrolia$publishInitialReadSnapshot");
        assertTrue(initial.contains("synchronized (this)")
                        && initial.contains("this.fabrolia$publishReadSnapshotLocked()"),
                "constructor publication must use the same monitor as every structural writer");
        String publisher = methodBlock(source,
                "private void fabrolia$publishReadSnapshotLocked()");
        int lockProof = publisher.indexOf("Thread.holdsLock(this)");
        int assignment = publisher.indexOf("this.fabrolia$readSnapshot =");
        int capture = publisher.indexOf("PoiSectionReadSnapshot.capture(");
        assertTrue(lockProof >= 0 && assignment > lockProof && capture > assignment,
                "every cached view must be rebuilt only while its mutation monitor is held");
        assertNoSuppressionOrRetry(publisher, "PoiSection view publication");

        assertTrue(source.contains("@WrapOperation(")
                        && source.contains("\"add(Lnet/minecraft/core/BlockPos;"
                                + "Lnet/minecraft/core/Holder;)\"")
                        && source.contains("\"remove(Lnet/minecraft/core/BlockPos;)V\"")
                        && source.contains("\"refresh(Ljava/util/function/Consumer;)V\"")
                        && source.contains("target = \"Ljava/lang/Runnable;run()V\"")
                        && source.contains("require = 3")
                        && source.contains("allow = 3"),
                "add/remove/refresh must each republish once at the exact vanilla dirty edge");
        String dirty = methodBlock(source, "fabrolia$publishBeforeDirty");
        int republish = dirty.indexOf("this.fabrolia$publishReadSnapshotLocked()");
        int callback = dirty.indexOf("original.call(dirtyCallback)");
        assertTrue(republish >= 0 && callback > republish,
                "derived-state consumers may see the dirty callback only after the new view is volatile-published");
        assertNoSuppressionOrRetry(dirty, "PoiSection pre-dirty publication");
    }

    @Test
    void vanillaReadersAreLockFreeCachedAndNeverEnterOriginalBodies() throws Exception {
        String source = read(VANILLA_MIXIN);
        String[] handlers = {
                "fabrolia$snapshotSectionPack",
                "fabrolia$snapshotSectionRecords",
                "fabrolia$snapshotSectionRecordLookup",
                "fabrolia$snapshotSectionValidityRead"
        };
        for (String name : handlers) {
            String handler = methodBlock(source, name);
            assertTrue(handler.contains("fabrolia$poiReadSnapshot()"),
                    name + " must use the cached volatile view");
            assertFalse(handler.contains("synchronized"),
                    name + " must never block behind a section writer");
            assertFalse(handler.contains("PoiSectionReadSnapshot.capture"),
                    name + " must not rebuild the view per query");
            assertFalse(handler.contains("original.call"),
                    name + " must not touch a live vanilla HashMap/HashSet/records map");
            assertNoSuppressionOrRetry(handler, name);
        }

        String access = methodBlock(source,
                "public PoiSectionReadSnapshot fabrolia$poiReadSnapshot()");
        assertTrue(access.contains("PoiSectionReadSnapshot snapshot = this.fabrolia$readSnapshot"),
                "each query must take one coherent volatile view reference");
        assertFalse(access.contains("synchronized")
                        || access.contains("PoiSectionReadSnapshot.capture"),
                "cached view access itself must stay lock-free and allocation-free");
        assertEquals(1, occurrences(source, "PoiSectionReadSnapshot.capture("),
                "only the writer publication helper may rebuild a read view");

        assertTrue(methodBlock(source, "fabrolia$snapshotSectionRecords")
                        .contains(".groupedRecords()\n                .stream(typePredicate, occupancy.getTest())"),
                "vanilla getRecords must expose only a lazy stream over immutable cached lists");
        assertTrue(methodBlock(source, "fabrolia$snapshotSectionPack")
                        .contains("snapshot.records().stream().map(PoiRecord::pack).toList()"),
                "pack must preserve the cached raw records encounter order without taking the writer monitor");
        assertTrue(methodBlock(source, "fabrolia$snapshotSectionRecordLookup")
                        .contains("recordAt(pos)"));
        assertTrue(methodBlock(source, "fabrolia$snapshotSectionValidityRead")
                        .contains("fabrolia$poiReadSnapshot().isValid()"));
    }

    @Test
    void everyLithiumAbstractReaderSurfaceIsIntercepted() throws Exception {
        Set<String> abstractReaders = Arrays.stream(
                        PointOfInterestSetExtended.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Modifier.isAbstract(method.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toSet());
        Set<String> expected = Set.of(
                "lithium$collectMatchingPoints",
                "lithium$getL2ClosestMatchingPoint",
                "lithium$collectMatchingPointsL2Limited",
                "lithium$getFirstMatchingPoint",
                "lithium$getAt",
                "lithium$iterate");
        assertEquals(expected, abstractReaders,
                "Lithium changed its abstract PoiSection reader surface; audit and wrap every new method");
        assertEquals(6, Arrays.stream(PointOfInterestSetExtended.class.getDeclaredMethods())
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .filter(method -> Modifier.isAbstract(method.getModifiers()))
                        .count(),
                "an overload was hidden by the name-only reader census");

        String source = read(LITHIUM_MIXIN);
        assertEquals(6, occurrences(source, "@WrapMethod("),
                "the compatibility mixin must intercept all six abstract public readers");
        for (String reader : expected) {
            assertTrue(source.contains("method = \"" + reader),
                    "missing exact Lithium reader route for " + reader);
        }
        assertTrue(source.contains("method = \"lithium$getL2ClosestMatchingPoint\"\n"
                        + "                    + \"(Lnet/minecraft/core/BlockPos;Ljava/util/function/Predicate;\""),
                "the L2 route must bind the abstract record-predicate overload, not its default wrapper");
        assertTrue(source.contains("method = \"lithium$collectMatchingPointsL2Limited\"\n"
                        + "                    + \"(Lnet/minecraft/core/BlockPos;JLjava/util/function/Predicate;\""),
                "the limited route must bind the abstract record-predicate overload, not its default wrapper");
    }

    @Test
    void lithiumReadersOnlyUseTheCachedViewAndNeverEnterOriginalOrAMonitor()
            throws Exception {
        String source = read(LITHIUM_MIXIN);
        String[] readerHandlers = {
                "fabrolia$snapshotLithiumMatchingPoints",
                "fabrolia$snapshotLithiumClosestPoint",
                "fabrolia$snapshotLithiumLimitedPoints",
                "fabrolia$snapshotLithiumFirstPoint",
                "fabrolia$snapshotLithiumRecordLookup",
                "fabrolia$snapshotLithiumIterator"
        };
        for (String name : readerHandlers) {
            String handler = methodBlock(source, name);
            assertTrue(handler.contains("fabrolia$lithiumPoiSnapshot()"),
                    name + " must consume the cached immutable view");
            assertFalse(handler.contains("original.call"),
                    name + " must not re-enter Lithium's live collections");
            assertFalse(handler.contains("synchronized"),
                    name + " must not block on a concurrent section writer");
            assertFalse(handler.contains("PoiSectionReadSnapshot.capture")
                            || handler.contains("GroupedSnapshot.capture"),
                    name + " must not rebuild membership per query");
            assertNoSuppressionOrRetry(handler, name);
        }
        assertEquals(6, occurrences(source, "this.fabrolia$lithiumPoiSnapshot()"),
                "all six Lithium readers need one cached view read");
        assertEquals(0, occurrences(source, "original.call("),
                "no Lithium live reader body may remain reachable");

        String helper = methodBlock(source,
                "private PoiSectionReadSnapshot fabrolia$lithiumPoiSnapshot()");
        assertTrue(helper.contains("FabroliaPoiSectionReadAccess")
                        && helper.contains(".fabrolia$poiReadSnapshot()"),
                "Lithium readers must share vanilla's one volatile PoiSection view");
        assertFalse(helper.contains("synchronized") || helper.contains("capture("),
                "Lithium snapshot access must be a cached lock-free read");

        String iterator = methodBlock(source, "fabrolia$snapshotLithiumIterator");
        assertTrue(iterator.contains(".stream(typePredicate, record -> true)")
                        && iterator.contains(".iterator()"),
                "Lithium's escaping iterator must be derived exclusively from detached lists");
    }

    @Test
    void lithiumSnapshotMixinIsRegisteredAndStrictlyLithiumGated() throws Exception {
        String simpleName = "FabroliaLithiumPoiSectionSnapshotMixin";
        String config = read(MIXIN_CONFIG);
        assertEquals(1, occurrences(config,
                        "\"lithium." + simpleName + "\""),
                "the Lithium PoiSection reader replacement must be registered exactly once");

        String canceller = read(MIXIN_CANCELLER);
        int gate = canceller.indexOf(
                "mixinClassName.endsWith(\"com.axalotl.fabrolia.common.mixin.lithium."
                        + simpleName + "\")");
        int decision = canceller.indexOf("return !LITHIUM;", gate);
        assertTrue(gate >= 0 && decision > gate,
                "the mixin references Lithium-merged method names and must disappear without Lithium");

        String source = read(LITHIUM_MIXIN);
        assertTrue(source.contains("@Mixin(value = PoiSection.class, priority = 2000)"),
                "Fabrolia must wrap the final Lithium-merged public methods at higher priority");
        assertFalse(source.contains("require = 0"),
                "with Lithium present, a changed reader target must fail loudly");
    }

    private static void assertNoSuppressionOrRetry(String body, String route) {
        assertFalse(Pattern.compile("\\bcatch\\s*\\(").matcher(body).find(),
                route + " must propagate failures instead of suppressing them");
        assertFalse(Pattern.compile("\\bretry\\b", Pattern.CASE_INSENSITIVE)
                        .matcher(body).find(),
                route + " must not replay a partially entered query");
    }

    private static String methodBlock(String source, String marker) {
        int markerIndex = source.indexOf(marker);
        if (markerIndex < 0) {
            throw new AssertionError("method marker not found: " + marker);
        }
        int open = source.indexOf('{', markerIndex);
        if (open < 0) {
            throw new AssertionError("method body not found: " + marker);
        }
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') depth++;
            if (character == '}' && --depth == 0) {
                return source.substring(open, index + 1);
            }
        }
        throw new AssertionError("unterminated method body: " + marker);
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        for (int index = 0; (index = source.indexOf(needle, index)) >= 0;
             index += needle.length()) {
            count++;
        }
        return count;
    }

    private static String read(String relative) throws Exception {
        return Files.readString(locate(relative));
    }

    private static Path locate(String relative) {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path base : new Path[]{cwd, cwd.getParent(),
                cwd.getParent() == null ? null : cwd.getParent().getParent()}) {
            if (base == null) continue;
            Path candidate = base.resolve(relative).normalize();
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException("source tree not found from " + cwd + ": " + relative);
    }
}
