package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * audit11 — {@code PUBLISHING} reset-not-restore.
 *
 * <p>The defect: {@code CrossDimArrivals.runPublished}'s finally did
 * {@code PUBLISHING.set(FALSE)} unconditionally. When an arrival op triggered a
 * NESTED publish (audit7's depth counter proved this happens in the field — a
 * teleport executed during publication stages-and-runs inline), the nested
 * call's finally cleared the flag for its still-running CALLER. The remainder
 * of the outer op then re-entered the staging mixins — which pass through only
 * while {@code PUBLISHING} is set — and was re-queued instead of running the
 * vanilla path: a machine-generated cohort split.
 *
 * <p>The fix saves the previous value and restores it. MC-free: the flag and
 * {@code runPublished} carry no Minecraft types.
 */
class CrossDimArrivalsPublishingScopeTest {

    @Test
    void aNestedPublishLeavesTheOuterPublishsFlagIntact() {
        assertFalse(CrossDimArrivals.PUBLISHING.get(), "flag must start clear");

        AtomicBoolean outerSawFlagAfterNested = new AtomicBoolean(false);
        AtomicBoolean innerSawFlag = new AtomicBoolean(false);

        CrossDimArrivals.runPublished(() -> {
            assertTrue(CrossDimArrivals.PUBLISHING.get());
            CrossDimArrivals.runPublished(() ->
                    innerSawFlag.set(CrossDimArrivals.PUBLISHING.get()));
            // THE defect: pre-fix, the nested finally reset the flag to FALSE
            // here, so the rest of this outer op re-entered the staging mixins.
            outerSawFlagAfterNested.set(CrossDimArrivals.PUBLISHING.get());
        });

        assertTrue(innerSawFlag.get(), "the nested publish runs under the flag");
        assertTrue(outerSawFlagAfterNested.get(),
                "a nested publish must NOT clear PUBLISHING for its caller — the "
                        + "remainder of the outer op would be re-staged and re-queued, "
                        + "a machine-generated cohort split");
        assertFalse(CrossDimArrivals.PUBLISHING.get(),
                "the OUTERMOST publish still clears the flag on exit");
    }

    @Test
    void deepNestingRestoresTheFlagAtEveryLevel() {
        AtomicInteger violations = new AtomicInteger();

        CrossDimArrivals.runPublished(() -> {
            CrossDimArrivals.runPublished(() ->
                    CrossDimArrivals.runPublished(() -> {}));
            if (!CrossDimArrivals.PUBLISHING.get()) violations.incrementAndGet();
            CrossDimArrivals.runPublished(() -> {});
            if (!CrossDimArrivals.PUBLISHING.get()) violations.incrementAndGet();
        });

        assertTrue(violations.get() == 0,
                "every return from a nested publish must leave the enclosing "
                        + "publish's flag set (violations=" + violations.get() + ")");
        assertFalse(CrossDimArrivals.PUBLISHING.get());
    }

    /** A throwing arrival body propagates the exact failure and still restores
     * the publication flag at every exited nesting level. */
    @Test
    void aThrowingNestedOpPropagatesExactlyAndRestoresEveryFlag() {
        IllegalStateException primary = new IllegalStateException("nested arrival op failed");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                CrossDimArrivals.runPublished(() -> {
            CrossDimArrivals.runPublished(() -> {
                throw primary;
            });
            throw new AssertionError("failed nested body must abort its outer successor");
        }));

        assertSame(primary, thrown, "the arrival boundary must preserve object identity");
        assertFalse(CrossDimArrivals.PUBLISHING.get());
    }
}
