package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.api.utils.SyncItemPickup;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Raider.class)
public class RaiderMixin {

    /** Main rejoining a loaded raider to its raid holds the raid island's claim (audit D-14). */
    @WrapMethod(method = "readAdditionalSaveData")
    private void fabrolia$loadUnderRaidOwner(net.minecraft.world.level.storage.ValueInput input,
                                             Operation<Void> original) {
        if (((Raider) (Object) this).level() instanceof ServerLevel level) {
            java.util.Optional<Integer> raidId = input.getInt("RaidId");
            com.axalotl.fabrolia.common.regions.RaiderLoadClaim.runLoad(level,
                    raidId.isPresent() ? java.util.OptionalInt.of(raidId.get()) : java.util.OptionalInt.empty(),
                    () -> original.call(input));
        } else {
            original.call(input);
        }
    }

    @WrapMethod(method = "pickUpItem")
    private void pickUpItem(ServerLevel level, ItemEntity entity, Operation<Void> original) {
        SyncItemPickup.wrap(level, entity, () -> {
            Raid raid = ((Raider) (Object) this).getCurrentRaid();
            // Vanilla constructs its ominous banner from a white banner. Two different
            // items can compete for one wave's vacant leader slot.
            if (raid != null && entity.getItem().is(Items.BANNER.white())) {
                synchronized (raid) {
                    original.call(level, entity);
                }
            } else {
                original.call(level, entity);
            }
        });
    }
}
