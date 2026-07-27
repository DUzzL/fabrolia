package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.api.fastutil.Int2ObjectConcurrentHashMap;
import com.axalotl.fabrolia.common.regions.ChunkRegionKeyed;
import com.axalotl.fabrolia.common.regions.LevelTicksIslandAccess;
import com.axalotl.fabrolia.common.regions.TickDomains;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gameevent.GameEventListenerRegistry;
import net.minecraft.world.ticks.LevelChunkTicks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin implements ChunkRegionKeyed {

    @Mutable
    @Shadow
    @Final
    private Int2ObjectMap<GameEventListenerRegistry> gameEventListenerRegistrySections;

    @Shadow
    @Final
    private Level level;

    @Shadow
    @Final
    private LevelChunkTicks<Block> blockTicks;

    /** Lazily cached region-grid key (position is immutable for the chunk's
     *  lifetime). Plain fields: the grouping loop runs one-at-a-time per
     *  level, and a racy first-compute on another thread just recomputes the
     *  same pure function. */
    @Unique private long fabrolia$regionKey;
    @Unique private int fabrolia$regionShift = -1;

    @Inject(method = "<init>*", at = @At("RETURN"))
    private void init(CallbackInfo ci) {
        gameEventListenerRegistrySections = new Int2ObjectConcurrentHashMap<>();
    }

    @WrapMethod(method = "getListenerRegistry")
    private synchronized GameEventListenerRegistry getListenerRegistry(int section, Operation<GameEventListenerRegistry> original) {
        return original.call(section);
    }

    /**
     * UNPACK-ANCHOR FIX (2026-07-27 scheduled-tick lifecycle audit): the
     * relative pending delays loaded from disk must be anchored against the
     * container's TAGGED clock domain, not against whatever clock the
     * promoting thread happens to read. Vanilla passes
     * {@code ServerLevel.getGameTime()} from {@code startTickingChunk} —
     * main's WALL clock — but registration (which runs at the earlier FULL
     * transition) has already tagged the container to its owner island, whose
     * L clock can trail or lead wall by an arbitrary, unbounded offset (rate
     * sessions, {@code /tick sprint}, sustained lag). Anchoring W-values into
     * an L-tagged container future-dated every loaded tick by (W − L) island
     * epochs — the "machine frozen for hours after a sprint" field bug — and
     * no consumer heal could fire because tag == consuming lane. See
     * {@link TickDomains#unpackBasis}.
     *
     * <p>Runs under the owning lane's structural lock (the same monitor every
     * schedule/rebase/retag takes): the basis resolution and the queue
     * population are one atomic step, so a concurrent consumer-lane retag
     * cannot interleave between them, and the vanilla PriorityQueue/HashSet
     * mutations inside {@code unpack} cannot race a worker-side
     * {@code scheduleTick} into the same (already registered) container.
     */
    @WrapOperation(method = "unpackTicks",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/ticks/LevelChunkTicks;unpack(J)V"))
    private void fabrolia$unpackOnTaggedClock(LevelChunkTicks<?> container, long currentTick,
                                              Operation<Void> original) {
        if (!(this.level instanceof ServerLevel sl)) {
            original.call(container, currentTick);
            return;
        }
        LevelTicksIslandAccess<?> lane = (LevelTicksIslandAccess<?>)
                (container == this.blockTicks ? sl.getBlockTicks() : sl.getFluidTicks());
        synchronized (lane.fabrolia$tickLock()) {
            original.call(container, TickDomains.unpackBasis(sl, container, currentTick));
        }
    }

    @Override
    public long fabrolia$regionKey(int shift) {
        if (fabrolia$regionShift != shift) {
            ChunkPos p = ((ChunkAccess) (Object) this).getPos();
            long rx = (p.x() >> shift);
            long rz = (p.z() >> shift);
            fabrolia$regionKey = (rx & 0xFFFFFFFFL) | ((rz & 0xFFFFFFFFL) << 32);
            fabrolia$regionShift = shift;
        }
        return fabrolia$regionKey;
    }
}