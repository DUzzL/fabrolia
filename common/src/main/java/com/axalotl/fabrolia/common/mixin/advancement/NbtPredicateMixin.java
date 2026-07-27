package com.axalotl.fabrolia.common.mixin.advancement;

import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementBuffer;
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
 *   <li><b>Choke-point guard</b> on the static {@code getEntityTagToCompare(Entity)} —
 *       the single funnel EVERY full-serialization consumer goes through
 *       ({@code NbtPredicate.matches(Entity)}, {@code EntityNbtPredicate.matches}, and any
 *       future caller). Guarding only {@code matches(Entity)} was the 2026-07-20 production
 *       CME (player Sorayamii): {@code EntityNbtPredicate} calls the static fetch directly,
 *       so the concurrent-belt eval ran a live {@code ServerPlayer.saveWithoutId} against a
 *       pearl island mutating {@code enderPearls}. At the choke point:
 *       drain-scoped cache hit → return; concurrent-unsafe belt → {@code DeferToMainSignal}
 *       (pending re-evaluates in the fenced batch-end drain); safe scope → compute once via
 *       the ORIGINAL body and fill the cache (the original operation is passed down so the
 *       cache's computeIfAbsent cannot recurse through this wrap).</li>
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

        // Cache / defer discipline lives in the getEntityTagToCompare wrap below —
        // the original body funnels through it.
        return original.call(entity);
    }

    @WrapMethod(method = "getEntityTagToCompare")
    private static CompoundTag fabrolia$guardedTagFetch(Entity entity, Operation<CompoundTag> original) {
        boolean unsafe = AdvancementCacheContext.isConcurrentUnsafe();
        if (entity instanceof ServerPlayer player) {
            CompoundTag cached = AdvancementCacheContext.peekPlayerNbt(player);
            if (cached != null) return cached;
            if (!unsafe) {
                CompoundTag computed = AdvancementCacheContext.getOrComputePlayerNbt(
                        player, () -> original.call(entity));
                if (computed != null) return computed;
            }
        }
        if (unsafe) {
            // Concurrent belt overlaps live region ticking (this player's next unit, a
            // pearl island touching enderPearls, …) — a full save here is a data race,
            // not a cost problem. Defer the whole pending to the fenced batch-end drain.
            throw AdvancementBuffer.DeferToMainSignal.INSTANCE;
        }
        return original.call(entity);
    }
}
