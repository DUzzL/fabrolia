package com.axalotl.fabrolia.common.mixin.advancement;

import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementCaptureOwnership;
import com.axalotl.fabrolia.common.parallelised.advancement.NbtPredicateFastPath;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.advancements.predicates.NbtPredicate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Two layers on the NBT-predicate entity path:
 *
 * <ol>
 *   <li><b>Per-key direct-accessor fast path</b> ({@link NbtPredicateFastPath}) on
 *       {@code matches(Entity)} — predicates referencing only fields we can read off the
 *       entity directly (TicksFrozen, ShoulderEntityLeft/Right with just {@code id}) skip
 *       {@code saveWithoutId} entirely. Covers BACAP's hot triggers (using_item /
 *       enter_block). The same fast path is applied to 26.2's other entry shape in
 *       {@link EntityNbtPredicateMixin}.</li>
 *   <li><b>Exclusive producer guard</b> on the static
 *       {@code getEntityTagToCompare(Entity)}. A live player is serialized only
 *       under authenticated execution ownership. Every fetch observes current
 *       state; a preceding predicate may have changed it.</li>
 * </ol>
 */
@Mixin(NbtPredicate.class)
public abstract class NbtPredicateMixin {

    @WrapMethod(method = "matches(Lnet/minecraft/world/entity/Entity;)Z")
    private boolean fabrolia$tryFastMatch(Entity entity, Operation<Boolean> original) {
        AdvancementCaptureOwnership.requireEntityObservationOwnership(entity);
        CompoundTag expected = ((NbtPredicate) (Object) this).tag();

        if (NbtPredicateFastPath.isHandled(expected)) {
            Boolean fast = NbtPredicateFastPath.tryMatch(entity, expected);
            if (fast != null) return fast;
        }

        // The original body uses the ownership-checked, uncached fetch below.
        return original.call(entity);
    }

    @WrapMethod(method = "getEntityTagToCompare")
    private static CompoundTag fabrolia$guardedTagFetch(Entity entity, Operation<CompoundTag> original) {
        AdvancementCaptureOwnership.requireEntitySerializationOwnership(entity);
        return original.call(entity);
    }
}
