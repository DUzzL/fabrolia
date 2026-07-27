package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.api.utils.SyncItemPickup;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.function.Predicate;

@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {


    @WrapOperation(method = "mergeWithNeighbours", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
    private List<ItemEntity> fabrolia$classFilteredMergeLookup(Level level, Class<ItemEntity> cls, AABB box, Predicate<? super ItemEntity> pred, Operation<List<ItemEntity>> original) {
        return level.getEntities(EntityTypeTest.forClass(cls), box, pred);
    }

    /**
     * Player pickup goes through the same global pickup lock + isRemoved guard as
     * Mob/Villager pickUpItem (SyncItemPickup). Needed once players can tick on region
     * workers (enableRegionPlayerTicks): two players touching the same item on different
     * workers — or a player racing a mob's pickUpItem / the item's own main-thread tick —
     * is the classic dupe vector the items sync-blacklist exists to prevent. The wrap's
     * isRemoved re-check inside the lock makes the second contender see the already-taken
     * item and skip, exactly like vanilla's serial pickup.
     */
    @WrapMethod(method = "playerTouch")
    private void fabrolia$syncPlayerPickup(Player player, Operation<Void> original) {
        ItemEntity self = (ItemEntity) (Object) this;
        if (self.level() instanceof ServerLevel serverLevel) {
            SyncItemPickup.wrap(serverLevel, self, () -> original.call(player));
        } else {
            original.call(player);
        }
    }

    /**
     * Item-item merge under the global pickup lock. With region-owned ticking, items tick
     * serially WITHIN a region but two items straddling a grid line tick on different
     * workers — concurrent mergeWithNeighbours would race both stacks' setItem/discard
     * (dupe or loss). The global monitor + isRemoved guard serializes all merges; an item
     * consumed by a concurrent merge/pickup is seen as removed and skipped, exactly like
     * vanilla's serial "someone else got it first".
     */
    @WrapMethod(method = "mergeWithNeighbours")
    private void fabrolia$syncMerge(Operation<Void> original) {
        ItemEntity self = (ItemEntity) (Object) this;
        if (self.level() instanceof ServerLevel serverLevel) {
            SyncItemPickup.wrap(serverLevel, self, original::call);
        } else {
            original.call();
        }
    }
}
