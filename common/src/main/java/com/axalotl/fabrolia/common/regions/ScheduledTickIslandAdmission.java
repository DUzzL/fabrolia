package com.axalotl.fabrolia.common.regions;

import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CommandBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.DetectorRailBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.TargetBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.TripWireHookBlock;

/**
 * Pure policy for an island scheduled-tick eligibility hole.
 *
 * <p>{@code LevelTicks.tickCheck} is deliberately stricter than the distance
 * manager's block-ticking answer: vanilla also requires loaded entities and a
 * ready ticking-chunk future.  The stricter answer must therefore never be
 * replaced with the distance-manager answer.  The latter only distinguishes a
 * normal dormant simulation fringe from a transient publication gap inside an
 * otherwise block-ticking island:
 *
 * <ul>
 *   <li>exact true — collect the container, exactly as vanilla;</li>
 *   <li>exact false and block-DM false — leave this dormant container pending,
 *       but let the rest of the island run, exactly as vanilla;</li>
 *   <li>exact false and block-DM true for an ordinary world tick — make the
 *       same per-container vanilla refusal.  Leaves, sand and gravel must not
 *       freeze an otherwise healthy island while terrain is publishing;</li>
 *   <li>the same transient refusal for a unique redstone pulse tick — hold the
 *       complete island unit at the same logical tick.  A later block-event,
 *       entity or block-entity phase must not reach the same Observer/Diode
 *       while its old pos+type entry still occupies vanilla's dedup set.</li>
 * </ul>
 */
public final class ScheduledTickIslandAdmission {

    enum Decision {
        ADMIT,
        DORMANT,
        HOLD
    }

    private ScheduledTickIslandAdmission() {}

    /** {@code entityTickingRange}: the distance manager's simulation-level
     *  answer (level {@code <= 31}), a narrowing heuristic only. It is NOT
     *  vanilla's exact check, which also needs the ready BLOCK_TICKING future
     *  and loaded entities. Outside that range vanilla refuses for distance
     *  alone, so the refusal is stable and stays DORMANT; inside it the exact
     *  refusal is a readiness hole, held for a bounded number of retries
     *  ({@code IslandScheduledTicks.SCHEDULED_HOLD_MAX_STREAK}) by the caller. */
    static Decision classify(boolean exactTickCheck, boolean entityTickingRange,
                             boolean pulseCoherenceRequired) {
        if (exactTickCheck) return Decision.ADMIT;
        return entityTickingRange && pulseCoherenceRequired
                ? Decision.HOLD : Decision.DORMANT;
    }

    /**
     * Scheduled block families whose vanilla pos+type de-duplication represents
     * a stateful redstone edge rather than merely redundant maintenance work.
     * If an old due entry from one of these families is retained, advancing any
     * later producer phase can irreversibly swallow the next edge.
     */
    public static boolean requiresPulseCoherence(Object type) {
        return type instanceof DiodeBlock
                || type instanceof ObserverBlock
                || type instanceof RedstoneTorchBlock
                || type instanceof DetectorRailBlock
                || type instanceof DispenserBlock
                || type instanceof CrafterBlock
                || type instanceof BasePressurePlateBlock
                || type instanceof ButtonBlock
                || type instanceof CommandBlock
                || type instanceof LecternBlock
                || type instanceof LightningRodBlock
                || type instanceof RedstoneLampBlock
                || type instanceof SculkSensorBlock
                || type instanceof TargetBlock
                || type instanceof TripWireBlock
                || type instanceof TripWireHookBlock;
    }

    /** Cadence-bound unit-head work runs once per candidate logical tick even
     *  when fail-closed admission retries that same candidate several times. */
    static boolean shouldApplyUnitHead(long appliedCandidateL, long candidateL) {
        return appliedCandidateL != candidateL;
    }

    /** Pure half of the block-frontier precommit belt. Identity is intentional:
     *  an equal replacement is still a different canonical tick/container.
     *  Null/null represents a stably absent container or empty head. */
    static boolean preparedShapeStable(Object preparedContainer, Object currentContainer,
                                       Object preparedHead, Object currentHead) {
        return preparedContainer == currentContainer
                && preparedHead == currentHead;
    }
}
