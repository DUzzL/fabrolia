package com.axalotl.fabrolia.common.regions;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Isolated
class NetherPortalPreparationTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void squareIsInclusiveAndDoesNotClipHeight() {
        BlockPos center = new BlockPos(-16, 0, -16);
        BlockPos corner = new BlockPos(-32, 400, 0);
        BlockPos outside = new BlockPos(-33, 0, -16);
        assertSame(corner, NetherPortalPreparation.closest(List.of(outside, corner), center, 16, pos -> true));
    }

    @Test
    void threeDimensionalDistancePrecedesHorizontalProximity() {
        BlockPos high = new BlockPos(0, 200, 0);
        BlockPos sideways = new BlockPos(16, 0, 16);
        assertSame(sideways, NetherPortalPreparation.closest(List.of(high, sideways), BlockPos.ZERO, 16, pos -> true));
    }

    @Test
    void lowerHeightWinsDistanceTieAndRemainingTiesKeepEncounterOrder() {
        BlockPos upper = new BlockPos(3, 4, 0);
        BlockPos lowerFirst = new BlockPos(3, -4, 0);
        BlockPos lowerSecond = new BlockPos(-3, -4, 0);
        assertSame(lowerFirst, NetherPortalPreparation.closest(
                List.of(upper, lowerFirst, lowerSecond), BlockPos.ZERO, 16, pos -> true));
        assertSame(lowerSecond, NetherPortalPreparation.closest(
                List.of(lowerSecond, lowerFirst, upper), BlockPos.ZERO, 16, pos -> true));
    }

    @Test
    void distanceDoesNotOverflowAnIntegerAndInvalidNearestIsSkipped() {
        BlockPos tall = new BlockPos(0, 100000, 0);
        BlockPos shorter = new BlockPos(0, 70000, 0);
        BlockPos invalid = BlockPos.ZERO;
        assertSame(shorter, NetherPortalPreparation.closest(
                List.of(tall, invalid, shorter), BlockPos.ZERO, 128, pos -> pos != invalid));
    }

    @Test
    void pendingPublicationDefersWithoutEarlyFlushingOrSearching() {
        PoiPublicationFence fence = new PoiPublicationFence(failure -> failure);
        ArrayDeque<Runnable> fifo = new ArrayDeque<>();
        List<String> effects = new ArrayList<>();
        fifo.add(() -> effects.add("older unrelated task"));
        fence.submit(() -> effects.add("portal visible"), fifo::add);
        assertFalse(NetherPortalPreparation.generationDecision(fence,
                () -> { fail("search ran before publication"); return false; },
                () -> fail("creation ran before publication")));
        assertEquals(List.of(), effects);
        fifo.remove().run();
        fifo.remove().run();
        assertEquals(List.of("older unrelated task", "portal visible"), effects);
        assertTrue(NetherPortalPreparation.generationDecision(fence, () -> true,
                () -> fail("existing portal was recreated")));
    }

    @Test
    void overlappingRequestsWaitForPublicationThenSearchTheirOwnCenter() {
        PoiPublicationFence fence = new PoiPublicationFence(failure -> failure);
        ArrayDeque<Runnable> fifo = new ArrayDeque<>();
        List<BlockPos> portals = new ArrayList<>();
        AtomicInteger creations = new AtomicInteger();
        BlockPos created = new BlockPos(4, 60, 0);
        Runnable create = () -> {
            creations.incrementAndGet();
            fence.submit(() -> portals.add(created), fifo::add);
        };
        assertTrue(NetherPortalPreparation.generationDecision(fence,
                () -> NetherPortalPreparation.closest(portals, new BlockPos(0, 60, 0), 16, pos -> true) != null,
                create));
        assertFalse(fence.isPublished());
        assertFalse(NetherPortalPreparation.generationDecision(fence,
                () -> fail("overlapping search ran before publication"),
                () -> fail("overlapping request duplicated a pending creation")));
        fifo.remove().run();
        assertTrue(NetherPortalPreparation.generationDecision(fence,
                () -> NetherPortalPreparation.closest(portals, new BlockPos(17, 60, 0), 16, pos -> true) != null,
                create));
        assertEquals(1, creations.get());
        // Overlapping squares are not equivalent: this center cannot use that portal.
        assertTrue(NetherPortalPreparation.generationDecision(fence,
                () -> NetherPortalPreparation.closest(portals, new BlockPos(25, 60, 0), 16, pos -> true) != null,
                () -> { creations.incrementAndGet(); fence.submit(() -> portals.add(new BlockPos(25, 60, 0)), fifo::add); }));
        assertEquals(2, creations.get());
        fifo.remove().run();
        assertEquals(List.of(created, new BlockPos(25, 60, 0)), portals);
    }

    @Test
    void nativeDecisionCannotHideAnExecutorSwallowedPublicationFailure() {
        PoiPublicationFence fence = new PoiPublicationFence(failure -> failure);
        IllegalStateException failure = new IllegalStateException("POI publication failed");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> NetherPortalPreparation.generationDecision(fence, () -> false,
                        () -> fence.submit(() -> { throw failure; }, callback -> {
                            try {
                                callback.run();
                            } catch (IllegalStateException swallowedByNativeExecutor) {
                                // The native event loop can log an ordinary exception and return.
                            }
                        }))));
    }

    @Test
    void deniedCreationStillWaitsForMainPreparation() throws Exception {
        TestLevel source = level();
        TestLevel destination = level();
        NetherPortalPreparation.Request request = deniedRequest(source, destination, true);
        assertFalse(request.readyToAttempt());
        assertFalse(request.resolve());

        field(NetherPortalPreparation.Request.class, "needsMain").setBoolean(request, false);
        assertTrue(request.readyToAttempt());
        assertTrue(request.resolve());
        assertNull(request.transition());
    }

    @Test
    void deniedCreationRechecksPublicationsFromEitherLevel() throws Exception {
        TestLevel source = level();
        TestLevel destination = level();
        ArrayDeque<Runnable> fifo = new ArrayDeque<>();
        for (TestLevel producer : List.of(source, destination)) {
            NetherPortalPreparation.Request request = deniedRequest(source, destination, false);
            producer.publications.submit(() -> {}, fifo::add);
            assertFalse(request.resolve());
            assertFalse(request.readyToAttempt());
            fifo.remove().run();
            field(NetherPortalPreparation.Request.class, "needsMain").setBoolean(request, false);
            assertTrue(request.resolve());
        }
    }

    @Test
    void deniedCreationCannotBypassFailedPublication() throws Exception {
        TestLevel source = level();
        TestLevel destination = level();
        NetherPortalPreparation.Request request = deniedRequest(source, destination, false);
        ArrayDeque<Runnable> fifo = new ArrayDeque<>();
        IllegalStateException failure = new IllegalStateException("destination POI publication failed");
        destination.publications.submit(() -> { throw failure; }, fifo::add);
        assertSame(failure, assertThrows(IllegalStateException.class, fifo.remove()::run));
        assertSame(failure, assertThrows(IllegalStateException.class, request::resolve));
    }

    private static NetherPortalPreparation.Request deniedRequest(
            TestLevel source, TestLevel destination, boolean needsMain) throws Exception {
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        var request = (NetherPortalPreparation.Request) unsafe.allocateInstance(NetherPortalPreparation.Request.class);
        field(NetherPortalPreparation.Request.class, "source").set(request, source);
        field(NetherPortalPreparation.Request.class, "destination").set(request, destination);
        field(NetherPortalPreparation.Request.class, "denied").setBoolean(request, true);
        field(NetherPortalPreparation.Request.class, "needsMain").setBoolean(request, needsMain);
        return request;
    }

    private static TestLevel level() throws Exception {
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        TestLevel level = (TestLevel) unsafe.allocateInstance(TestLevel.class);
        level.publications = new PoiPublicationFence(failure -> failure);
        return level;
    }

    private static final class TestLevel extends ServerLevel implements NetherPortalPreparation.PoiAccess {
        private PoiPublicationFence publications;
        private TestLevel() { super(null, null, null, null, null, null, false, 0L, null, false); }
        @Override public PoiPublicationFence fabrolia$portalPublications() { return publications; }
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
