package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.api.utils.SyncItemPickup;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import org.spongepowered.asm.mixin.Mixin;

/**
 * SulfurCube (MC 26.2 new mob) pickUpItem race fix.
 *
 * <p>Vanilla body of {@code pickUpItem(ServerLevel, ItemEntity)}:
 * <pre>
 *   if (canHoldItem(stack) && pickupTimer <= 0) {
 *       onItemPickup(itemEntity);
 *       setItemSlot(BODY, stack.split(1));    // mutates source ItemStack count, NOT atomic
 *       setGuaranteedDrop(BODY);
 *       take(itemEntity, 1);
 *   }
 * </pre>
 *
 * <p>Race: two SulfurCubes from adjacent regions both targeting the same ItemEntity in
 * their SulfurCubeSearchForItemsGoal (8-block radius — almost always crosses a region
 * border). Both pass {@code canHoldItem} and {@code pickupTimer <= 0} concurrently; both
 * call {@code stack.split(1)} which is plain {@code count -= n; return new ItemStack(item, n)}
 * with no CAS — both read count=1, decrement, return ItemStack(count=1). Two cubes get the
 * stack, source ItemEntity is removed once. Dupe. The other failure mode: both pass the
 * checks but one's {@code setItemSlot(BODY, ...)} overwrites the other's — stack lost.
 *
 * <p>Same shape as Fox/Piglin/Allay pickup races [[feedback_async_items_blacklisted]] —
 * we use the same {@link SyncItemPickup#wrap} primitive (identity guard + availability check).
 * Once one thread completes the transaction, the second observes {@code itemEntity.isRemoved()}
 * and skips its action entirely.
 *
 * <p>This sync wrap is REQUIRED, not optional, regardless of regional sync — the
 * SulfurCubeSearchForItemsGoal radius of 8 blocks crosses region boundaries trivially, and
 * region-thread sync would still let two different regions race. Exact item
 * ownership also protects Main fallback callers and survives cell crossings.
 * Portal capture cannot wait on that guard while retaining its own island claim.
 * The same admission primitive covers every other mob's pickUpItem wrapper.
 *
 * <p>Fuse-explosion path is NOT handled here — {@code tickFuse}→{@code level.explode(...)}
 * already serialises through {@code ServerLevelMixin.createExplosion}'s {@code @WrapMethod}
 * on {@code explode(...)} ({@code synchronized(FabroliaLocks.tickLockOr(fabrolia$explosionLock))}).
 * Same path Creeper uses — no need to drag SulfurCube into {@code synchronizedEntities}.
 */
@Mixin(SulfurCube.class)
public class SulfurCubeMixin {

    @WrapMethod(method = "pickUpItem")
    private void fabrolia$pickUpItem(ServerLevel level, ItemEntity entity, Operation<Void> original) {
        SyncItemPickup.wrap(level, entity, () -> original.call(level, entity));
    }
}
