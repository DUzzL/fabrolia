package com.axalotl.fabrolia.common.mixin.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Objects;

/**
 * Makes Entity's lazy feet-block cache one coherent publication protocol.
 *
 * <p>Vanilla computes a non-null state, writes it to {@code inBlockState}, then reads the
 * field again for the return. Fabrolia may run {@code baseTick}, {@code setPosRaw}, or
 * {@code setLevel} from a different region worker; an invalidator could put {@code null}
 * between those last two instructions.
 *
 * <p>The potentially blocking level lookup must not hold the cache monitor. In particular,
 * an off-main lookup may wait on Fabrolia's chunk bridge while the server thread concurrently
 * teleports the same entity. Holding this monitor across that wait makes the server thread
 * wait for the entity monitor while the worker waits for the server thread. Instead, a miss
 * snapshots position, level and revision under the monitor, computes outside it, then
 * publishes only if every input is still current. Position changes, level changes and
 * periodic invalidations all advance the same revision, so stale results retry.
 *
 * <p>{@code setPosRaw} originally publishes {@code blockPosition} and clears the cache in two
 * field writes. Its first redirect performs both writes and advances the revision atomically;
 * its old null write is then consumed rather than replayed after another thread may already
 * have populated the new position's cache. Lookup exceptions and genuine nulls propagate.
 * There is deliberately no AIR/null fallback and no catch-and-continue path.
 *
 * <p>This mixin applies before Lithium (priority 900 &lt; 1000). Lithium's
 * {@code INVOKE_ASSIGN} injector therefore binds to the one lookup call in this final
 * overwrite. Its pre-publication callback remains conservative; the exact cache publication
 * is guarded below.
 */
@Mixin(value = Entity.class, priority = 900)
public abstract class EntityInBlockStateMixin {

    @Shadow
    private BlockState inBlockState;

    @Shadow
    private BlockPos blockPosition;
    @Shadow
    private Level level;


    @Unique
    private final Object fabrolia$inBlockStateLock = new Object();
    @Unique
    private long fabrolia$inBlockStateRevision;


    /**
     * @author Fabrolia
     * @reason Keep lookup outside the publication monitor and reject stale results.
     */
    @Overwrite
    public BlockState getInBlockState() {
        while (true) {
            BlockPos position;
            Level level;
            long revision;
            synchronized (fabrolia$inBlockStateLock) {
                BlockState state = this.inBlockState;
                if (state != null) {
                    return state;
                }
                position = this.blockPosition;
                level = this.level;
                revision = this.fabrolia$inBlockStateRevision;
            }

            BlockState computed = Objects.requireNonNull(
                    level.getBlockState(position),
                    "Level.getBlockState returned null for Entity.getInBlockState");

            synchronized (fabrolia$inBlockStateLock) {
                if (this.fabrolia$inBlockStateRevision != revision
                        || this.level != level) {
                    continue;
                }
                BlockState state = this.inBlockState;
                if (state == null) {
                    this.inBlockState = computed;
                    state = computed;
                }
                return state;
            }
        }
    }

    @Redirect(
            method = "setLevel",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/entity/Entity;level:"
                            + "Lnet/minecraft/world/level/Level;",
                    opcode = Opcodes.PUTFIELD
            ),
            require = 1
    )
    private void fabrolia$publishLevelAndInvalidate(
            Entity instance, Level newLevel) {
        synchronized (fabrolia$inBlockStateLock) {
            this.level = newLevel;
            this.inBlockState = null;
            this.fabrolia$inBlockStateRevision++;
        }
    }

    @Redirect(
            method = "setPosRaw",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/entity/Entity;blockPosition:"
                            + "Lnet/minecraft/core/BlockPos;",
                    opcode = Opcodes.PUTFIELD
            ),
            require = 1
    )
    private void fabrolia$publishBlockPositionAndInvalidate(
            Entity instance, BlockPos position) {
        synchronized (fabrolia$inBlockStateLock) {
            this.blockPosition = position;
            this.inBlockState = null;
            this.fabrolia$inBlockStateRevision++;
        }
    }

    /**
     * The blockPosition redirect above has already performed this null publication atomically.
     * Replaying the original store would clear a freshly populated cache after publication.
     */
    @Redirect(
            method = "setPosRaw",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/entity/Entity;inBlockState:"
                            + "Lnet/minecraft/world/level/block/state/BlockState;",
                    opcode = Opcodes.PUTFIELD
            ),
            require = 1
    )
    private void fabrolia$consumeRelocatedSetPosInvalidation(
            Entity instance, BlockState value) {
        if (value != null) {
            throw new IllegalStateException(
                    "setPosRaw inBlockState invalidation became non-null");
        }
    }

    @Redirect(
            method = "baseTick",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/entity/Entity;inBlockState:"
                            + "Lnet/minecraft/world/level/block/state/BlockState;",
                    opcode = Opcodes.PUTFIELD
            ),
            require = 1
    )
    private void fabrolia$serializeBaseTickInBlockStateInvalidation(
            Entity instance, BlockState value) {
        synchronized (fabrolia$inBlockStateLock) {
            this.inBlockState = value;
            this.fabrolia$inBlockStateRevision++;
        }
    }
}
