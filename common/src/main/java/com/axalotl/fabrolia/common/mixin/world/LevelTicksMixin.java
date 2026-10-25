package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.api.fastutil.Long2LongConcurrentHashMap;
import com.axalotl.fabrolia.api.fastutil.Long2ObjectConcurrentHashMap;
import com.axalotl.fabrolia.common.regions.IslandScheduledTicks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelTicks.class)
public abstract class LevelTicksMixin<T> implements LevelTickAccess<T>,
        com.axalotl.fabrolia.common.regions.LevelTicksIslandAccess<T> {
    @Shadow
    private final Long2ObjectMap<LevelChunkTicks<T>> allContainers = new Long2ObjectConcurrentHashMap<>();

    /** Keys indexed by region-grid cell: island units discover their due
     *  containers per owned cell (IslandScheduledTicks.scheduledChunksInCell). */
    @Shadow
    private final Long2LongMap nextTickForContainer = Util.make(
            new Long2LongConcurrentHashMap(64, IslandScheduledTicks::scheduledTickCell),
            p_193262_ -> p_193262_.defaultReturnValue(Long.MAX_VALUE));

    @Shadow
    @Final
    private java.util.function.LongPredicate tickCheck;

    @Shadow
    @Final
    private java.util.Queue<ScheduledTick<T>> toRunThisTick;

    @Shadow
    @Final
    private java.util.List<ScheduledTick<T>> alreadyRunThisTick;

    @Unique
    private final Object fabrolia$lock = new Object();

    @Unique
    private final IslandScheduledTicks.SliceRegistry fabrolia$sliceRegistry =
            new IslandScheduledTicks.SliceRegistry();

    @Override
    public Long2ObjectMap<LevelChunkTicks<T>> fabrolia$allContainers() {
        return this.allContainers;
    }

    @Override
    public Long2LongMap fabrolia$nextTickForContainer() {
        return this.nextTickForContainer;
    }

    @Override
    public java.util.function.LongPredicate fabrolia$tickCheck() {
        return this.tickCheck;
    }

    @Override
    public Object fabrolia$tickLock() {
        return fabrolia$lock;
    }

    @Override
    public IslandScheduledTicks.SliceRegistry fabrolia$sliceRegistry() {
        return fabrolia$sliceRegistry;
    }

    @Override
    public java.util.Queue<ScheduledTick<T>> fabrolia$vanillaToRunThisTick() {
        return toRunThisTick;
    }

    @Override
    public java.util.List<ScheduledTick<T>> fabrolia$vanillaAlreadyRunThisTick() {
        return alreadyRunThisTick;
    }

    @WrapMethod(method = "addContainer")
    private void wrapAddContainer(net.minecraft.world.level.ChunkPos pos, LevelChunkTicks<@NotNull T> container, Operation<Void> original) {
        synchronized (fabrolia$lock) {
            original.call(pos, container);
            // Task #31: a container registering inside island territory moves
            // its (vanilla wall-anchored) loaded triggers onto the island
            // clock immediately — the tag makes the domain explicit from the
            // first moment the runner can see it.
            com.axalotl.fabrolia.common.regions.RegionizedServer rsInstall =
                    com.axalotl.fabrolia.common.regions.RegionScheduler.forTicksInstance(
                            (net.minecraft.world.ticks.LevelTicks<T>) (Object) this);
            if (rsInstall != null) {
                com.axalotl.fabrolia.common.regions.TickDomains.onContainerInstalled(
                        rsInstall.level, pos.pack(), container);
            }
        }
    }

    @WrapMethod(method = "removeContainer")
    private void wrapRemoveContainer(net.minecraft.world.level.ChunkPos pos, Operation<Void> original) {
        synchronized (fabrolia$lock) {
            original.call(pos);
        }
    }

    /**
     * {@link LevelTicks#schedule} forwards to {@link LevelChunkTicks#schedule}
     * which mutates two non-thread-safe vanilla containers: a
     * {@code PriorityQueue<ScheduledTick>} ({@code tickQueue}) and a
     * {@code HashSet<ScheduledTick<?>>} ({@code ticksPerPosition}). Under
     * island scheduled-tick lanes two worker handlers can call
     * {@code level.scheduleTick} concurrently and corrupt those structures.
     *
     * <p>Cost: ~20ns sync per call; typical tick does O(100–1000) schedules
     * total &lt;0.1ms. The sync removed in commit f4bdaf4 as "redundant" was
     * redundant only in the pre-islandticks pool model.
     */
    @WrapMethod(method = "schedule")
    private void fabrolia$syncSchedule(ScheduledTick<T> tick, Operation<Void> original) {
        // A8 clock-domain rebase: a trigger time computed in one timeline
        // (caller's getGameTime — main W or another island's L) must be
        // re-expressed in the OWNER island's timeline or it fires early/late
        // by the clock offset (a console-placed redstone block scheduled an
        // impulse command block ~4 minutes late in the 2026-07-12 a8cmd rig:
        // W-trigger into an island whose L trailed gt by hundreds of ticks).
        // delay is clock-invariant: newTrigger = ownerNow + (trigger - ctxNow).
        //
        // task #27 ATOMICITY (2026-07-23 observer +3→+3941): the rebase and
        // the insertion must be ONE step under the container lock. Every
        // structural retag (install rebase, consume-lane heal, stale-tag
        // collapse, save sibling heal) runs under this same lock — computing
        // the shift OUTSIDE it let a schedule shifted against one clock land
        // in a container concurrently rebased to another, off by the full
        // wall↔island divergence (post-stall: thousands of ticks — an
        // island-basis +3 landing in a freshly wall-collapsed container reads
        // +3941 and fires minutes late; the converse fires immediately).
        // rebaseScheduledTick's own collapse path re-enters this monitor —
        // reentrancy is safe.
        synchronized (fabrolia$lock) {
            tick = com.axalotl.fabrolia.common.regions.IslandCommands
                    .rebaseScheduledTick((net.minecraft.world.ticks.LevelTicks<T>) (Object) this, tick);
            if (com.axalotl.fabrolia.common.regions.ScheduledTickIslandAdmission
                    .requiresPulseCoherence(tick.type())) {
                LevelChunkTicks<T> container = allContainers.get(ChunkPos.pack(tick.pos()));
                if (container != null) {
                    // Publish before the canonical insertion. A failed/deduped
                    // insert leaves only a harmless cold-path false positive;
                    // publishing afterwards could leave an admitted false
                    // negative racing the scheduled collector.
                    ((com.axalotl.fabrolia.common.regions.CausalScheduledTickIndex)
                            (Object) container).fabrolia$markPulseSensitiveTick();
                }
            }
            original.call(tick);
        }
    }

    /**
     * Slice-aware {@code willTickThisTick}. Consults the per-thread set
     * written by {@link IslandScheduledTicks#runSlice}; vanilla
     * {@code toRunThisTickSet} stays empty under islandticks because the
     * slice path bypasses {@code runCollectedTicks}, so without this hook
     * vanilla always returns {@code false} for pending ticks and
     * Comparator/Diode/RedstoneTorch enqueue a duplicate every neighbour
     * update. Falls through to vanilla when no slice is active on this
     * thread (the vanilla answer is also correct there).
     */
    @Inject(method = "willTickThisTick", at = @At("HEAD"), cancellable = true)
    private void fabrolia$willTickThisTickSliceAware(BlockPos pos, T type,
                                                   CallbackInfoReturnable<Boolean> cir) {
        Boolean collected = IslandScheduledTicks.willTickCollected(
                (LevelTicks<T>) (Object) this, pos, type);
        if (collected != null) {
            cir.setReturnValue(collected);
        }
    }

    /** Clear every scheduler-wide custom replica and vanilla durable/private
     *  state in one write-side visibility cut. */
    @WrapMethod(method = "clearArea")
    private void fabrolia$clearSliceArea(BoundingBox box, Operation<Void> original) {
        IslandScheduledTicks.clearCollectedArea(
                (LevelTicks<?>) (Object) this, box, () -> original.call(box));
    }

    /** Active custom slices are absent from vanilla's private queues. Snapshot
     *  all of them with durable state; otherwise run vanilla unchanged. */
    @SuppressWarnings("unchecked")
    @WrapMethod(method = "copyAreaFrom")
    private void fabrolia$copySliceAreaFrom(LevelTicks<T> source, BoundingBox box,
                                            Vec3i offset, Operation<Void> original) {
        IslandScheduledTicks.copyCollectedAreaFrom(
                (LevelTicks<T>) (Object) this, source, box, offset);
    }
}
