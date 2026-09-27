package com.axalotl.fabrolia.common.mixin.advancement;

import com.axalotl.fabrolia.common.parallelised.advancement.NbtPredicateFastPath;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.advancements.predicates.entity.EntityNbtPredicate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Fast-path parity for 26.2's entity-subpredicate entry shape. {@code EntityNbtPredicate}
 * calls the static {@code NbtPredicate.getEntityTagToCompare} directly — it never goes
 * through {@code NbtPredicate.matches(Entity)} — so without this wrap the direct-accessor
 * fast path ({@link NbtPredicateFastPath}) was dead for every criterion parsed into this
 * shape (BACAP's hot using_item / enter_block predicates included). Fast-path
 * hits avoid serialization; other shapes use the original fetch guarded by
 * {@link NbtPredicateMixin}. Neither path reuses state from an earlier predicate.
 */
@Mixin(EntityNbtPredicate.class)
public abstract class EntityNbtPredicateMixin {

    @WrapMethod(method = "matches")
    private boolean fabrolia$tryFastMatch(Entity entity, ServerLevel level, @Nullable Vec3 position,
                                          Operation<Boolean> original) {
        CompoundTag expected = ((EntityNbtPredicate) (Object) this).nbt().tag();
        if (NbtPredicateFastPath.isHandled(expected)) {
            Boolean fast = NbtPredicateFastPath.tryMatch(entity, expected);
            if (fast != null) return fast;
        }
        return original.call(entity, level, position);
    }
}
