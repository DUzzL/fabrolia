package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.TickDomains;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Scheduled-tick save-time translation (2026-07-17 stuck-tripwire root, H1).
 *
 * <p>Vanilla packs pending block/fluid ticks RELATIVE to the clock it is
 * handed: {@code copyOf} passes {@code level.getGameTime()} — the WALL clock.
 * Under async islands the triggers in an island-owned chunk are expressed in
 * that island's LOGICAL clock L, and a rate/sprint session leaves L
 * permanently offset from the wall clock (units only ever do L++). Packing
 * L-based triggers against W wrote {@code delay = trigger − W = realDelay +
 * (L − W)}: after the island retired and was reborn wall-anchored, the
 * reloaded tick was future-dated by the whole sprint offset — a powered
 * tripwire whose re-check was parked hours out (and the mirror-image
 * OVERDUE-BURST storms for islands running BEHIND the wall clock). Handing
 * pack the OWNER's clock makes the saved delay timeline-correct; the load
 * side re-anchors against the container's TAGGED domain clock (LevelChunkMixin
 * → {@code TickDomains.unpackBasis} — vanilla's wall-clock anchor was the
 * matching load-side hole, 2026-07-27 audit), so the delay lands correctly on
 * whichever timeline the chunk reloads into.
 *
 * <p>ATOMICITY (2026-08-06): under c2me this {@code copyOf} runs on IO
 * threads while main concurrently rebases containers (assignDomain at
 * rebuild, region on/off collapses). Resolving the basis in one instant and
 * packing the triggers in another let a rebase land between them — the exact
 * (W − L) mistranslation above, persisted to disk as a self-consistent
 * record no load-side heal can see. The whole basis-resolution + pack
 * therefore runs as one atomic step under the same exclusion every retag
 * takes; see {@link TickDomains#packTicksConsistent} for the lock discipline.
 * The basis itself is computed inside that wrap, so the vanilla
 * {@code getGameTime()} value passed by {@code copyOf} now serves only as the
 * WALL fallback.
 */
@Mixin(SerializableChunkData.class)
public abstract class SerializableChunkDataMixin {

    @WrapOperation(method = "copyOf",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getTicksForSerialization(J)Lnet/minecraft/world/level/chunk/ChunkAccess$PackedTicks;"))
    private static ChunkAccess.PackedTicks fabrolia$packTicksConsistent(ChunkAccess self, long wall,
                                                                        Operation<ChunkAccess.PackedTicks> original,
                                                                        ServerLevel level, ChunkAccess chunk) {
        return TickDomains.packTicksConsistent(level, chunk, wall,
                basis -> original.call(self, basis));
    }
}
