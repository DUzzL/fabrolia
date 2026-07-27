package com.axalotl.fabrolia.common.parallelised.utils;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUDIT1-vs-AUDIT2 DISCRIMINATOR for the End-portal falling-block duplication.
 *
 * <p>These tests encode the two vanilla 26.2 continuation gates verbatim and then
 * run both candidate handoff mechanisms against them. They exist because audit2
 * shipped a mechanism that satisfied the projectile requirement while silently
 * destroying the falling-block one.
 *
 * <p>Vanilla gates:
 * <pre>
 * ThrowableProjectile.tick():61-62
 *     super.tick();                                     // reaches handlePortal
 *     if (result != MISS &amp;&amp; this.isAlive())             // must be FALSE after handoff
 *         hitTargetOrDeflectSelf(result);               //   -&gt; discard() in source dim
 *
 * FallingBlockEntity.tick():158-159
 *     this.handlePortal();
 *     if (level instanceof ServerLevel
 *             &amp;&amp; (this.isAlive() || this.forceTickAfterTeleportToDuplicate))
 *         ... place block / spawnAtLocation ...          // must still RUN after handoff
 * </pre>
 *
 * {@code forceTickAfterTeleportToDuplicate} is set at FallingBlockEntity.java:360 as
 * {@code newEntity != null &amp;&amp; fromOrToEnd} — i.e. deliberate End-portal duplication.
 *
 * <p>The decisive property: aborting the tick is NOT equivalent to being removed.
 * Both make {@code isAlive()} irrelevant for the projectile, but only the
 * removed-state mechanism preserves the {@code ||} the duper depends on.
 */
class PostTeleportContinuationTest {

    /** How the source entity is treated for the remainder of its tick after handoff. */
    private enum Mechanism {
        /** audit2: throw out of handlePortal, unwinding the rest of tick(). */
        ABORT_TICK,
        /** audit3: report vanilla's post-handlePortal state (removed) and keep running. */
        REPORT_REMOVED
    }

    /** Vanilla ThrowableProjectile.tick() continuation, parameterised by mechanism. */
    private static List<String> projectileTick(Mechanism m, boolean hit) {
        List<String> ran = new ArrayList<>();
        ran.add("move");
        // handlePortal() -> deferred submit
        if (m == Mechanism.ABORT_TICK) return ran;           // stack unwound here
        boolean isAlive = false;                             // removed => isAlive() == false
        if (hit && isAlive) ran.add("hitTargetOrDeflectSelf/discard");
        return ran;
    }

    /** Vanilla FallingBlockEntity.tick() continuation, parameterised by mechanism. */
    private static List<String> fallingBlockTick(Mechanism m, boolean forceTickAfterTeleportToDuplicate) {
        List<String> ran = new ArrayList<>();
        ran.add("applyGravity");
        ran.add("move");
        // handlePortal() -> teleport (End) / deferred submit
        if (m == Mechanism.ABORT_TICK) return ran;           // stack unwound here
        boolean isAlive = false;                             // removed => isAlive() == false
        if (isAlive || forceTickAfterTeleportToDuplicate) {
            ran.add("placeBlockInSourceDimension");          // <-- the duplication
        }
        return ran;
    }

    // ---------------- the requirement that must keep holding ----------------

    @Test
    void projectileMustNotResolveItsHitInTheSourceDimension() {
        assertFalse(projectileTick(Mechanism.REPORT_REMOVED, true)
                        .contains("hitTargetOrDeflectSelf/discard"),
                "a pearl that handed off its transfer must not hit and discard in the source "
                        + "dimension — that is the 'removed-while-queued' reproduction");
    }

    // ---------------- the requirement audit2 broke ----------------

    @Test
    void fallingBlockMustStillPlaceItsBlockAfterAnEndTeleport() {
        assertTrue(fallingBlockTick(Mechanism.REPORT_REMOVED, true)
                        .contains("placeBlockInSourceDimension"),
                "vanilla's forceTickAfterTeleportToDuplicate exists precisely so the block is "
                        + "still placed after an End teleport — this IS the sand duper");
    }

    /**
     * The discriminator proper: the audit2 mechanism passes the projectile test and
     * FAILS the falling-block one. If this ever stops holding, the two mechanisms have
     * become indistinguishable and these tests no longer protect anything.
     */
    @Test
    void abortingTheTickSatisfiesTheProjectileButBreaksTheDuper() {
        assertFalse(projectileTick(Mechanism.ABORT_TICK, true)
                        .contains("hitTargetOrDeflectSelf/discard"),
                "sanity: aborting the tick does stop the projectile hit");

        assertFalse(fallingBlockTick(Mechanism.ABORT_TICK, true)
                        .contains("placeBlockInSourceDimension"),
                "aborting the tick unwinds past FallingBlockEntity's placement, so the duper "
                        + "dies. This asymmetry is exactly why audit2 regressed and why the "
                        + "mechanism must report REMOVED rather than abort.");
    }

    @Test
    void bothMechanismsAgreeOnEverythingBeforeTheHandoff() {
        assertEquals(List.of("applyGravity", "move"),
                fallingBlockTick(Mechanism.ABORT_TICK, true).subList(0, 2));
        assertEquals(List.of("applyGravity", "move"),
                fallingBlockTick(Mechanism.REPORT_REMOVED, true).subList(0, 2));
    }

    @Test
    void aNonEndTeleportDoesNotDuplicate() {
        // fromOrToEnd == false => flag stays false => vanilla does NOT continue.
        assertFalse(fallingBlockTick(Mechanism.REPORT_REMOVED, false)
                        .contains("placeBlockInSourceDimension"),
                "duplication is End-specific in vanilla; a nether-portal falling block must "
                        + "not place its block after handing off");
    }
}
