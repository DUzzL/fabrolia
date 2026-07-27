package com.axalotl.fabrolia.common.mixin.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;

import java.util.function.Predicate;

@Mixin(LevelChunkSection.class)
public abstract class LevelChunkSectionMixin {

    /**
     * {@code maybeHas} is the heuristic vanilla's collision iterator (and
     * {@code findBlockSections}, fluid spread, light propagation, etc.) uses to
     * skip a 16³ section that doesn't contain any block matching the predicate.
     * A {@code false} return tells the caller "this section has nothing
     * interesting, don't iterate it" — vanilla collision then never examines
     * any block inside it.
     *
     * <p>Under fabrolia ticking, a region worker reading a section's storage can
     * observe a {@code null} BlockState mid-write — vanilla
     * {@link net.minecraft.world.level.chunk.PalettedContainer} swaps its
     * volatile {@code data} field on resize (palette grow / shrink), and other
     * scheduled-tick workers / island slices write block changes (piston
     * extension, dispenser swap, water flow) in parallel with our collision
     * iteration.
     *
     * <p>Previous fix returned {@code false} on null state — the conservative
     * "predicate failed" answer that prevents {@link NullPointerException}
     * inside vanilla predicates. But for collision-class predicates that
     * answer is WORSE than crash: the section gets skipped wholesale, the
     * solid block that's mid-write goes invisible to the entity's collision
     * check, and items shoot through the wall. Reproduced 2026-06-13 by user
     * — shulker-box drops fly through item sorters whose pistons/dispensers
     * are scheduling block changes in adjacent island slices.
     *
     * <p>Correct conservative answer is {@code true}: "I can't prove this
     * section is empty under the predicate, so iterate everything inside it."
     * The caller then walks the section block-by-block via
     * {@link net.minecraft.world.level.chunk.LevelChunk#getBlockState}, which
     * goes through the chunk's normal read path and observes a coherent state
     * (either pre- or post-write, never null). Cost: a wasted scan of one
     * section in the rare null-state window. Vanilla-correct collision.
     */
    @WrapMethod(method = "maybeHas")
    private boolean fabrolia$nullSafeMaybeHas(Predicate<BlockState> predicate, Operation<Boolean> original) {
        return original.call((Predicate<BlockState>) state -> state == null || predicate.test(state));
    }
}