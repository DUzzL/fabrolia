package com.axalotl.fabrolia.common.mixin.advancement;

import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementCacheContext;
import com.axalotl.fabrolia.common.parallelised.advancement.NbtPredicateFastPath;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.advancements.predicates.NbtPredicate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
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
 *   <li><b>Per-evaluation player NBT</b> ({@link AdvancementCacheContext}) on the static
 *       {@code getEntityTagToCompare(Entity)}: while a trigger call's evaluation scope is
 *       bound, a live player's {@code saveWithoutId} tag is computed once and reused
 *       across its listeners; a fire-time compare that references the player's ender
 *       pearls routes the call to the fenced Main lane first.</li>
 * </ol>
 */
@Mixin(NbtPredicate.class)
public abstract class NbtPredicateMixin {

    @WrapMethod(method = "matches(Lnet/minecraft/world/entity/Entity;)Z")
    private boolean fabrolia$tryFastMatch(Entity entity, Operation<Boolean> original) {
        CompoundTag expected = ((NbtPredicate) (Object) this).tag();

        if (NbtPredicateFastPath.isHandled(expected)) {
            Boolean fast = NbtPredicateFastPath.tryMatch(entity, expected);
            if (fast != null) return fast;
        }

        // The original body uses the scope-cached fetch below.
        AdvancementCacheContext.checkFireTimeCompare(entity, expected);
        return original.call(entity);
    }

    @WrapMethod(method = "getEntityTagToCompare")
    private static CompoundTag fabrolia$guardedTagFetch(Entity entity, Operation<CompoundTag> original) {
        if (entity instanceof ServerPlayer player) {
            CompoundTag computed = AdvancementCacheContext.getOrComputePlayerNbt(
                    player, () -> original.call(entity));
            if (computed != null) return computed;
        }
        return original.call(entity);
    }
}
