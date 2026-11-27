package com.axalotl.fabrolia.common.mixin.entity.sensor;

import com.axalotl.fabrolia.common.parallelised.utils.FastBitRadixSort;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.NearestItemSensor;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Mixin(value = NearestItemSensor.class, priority = 1500)
public class NearestItemSensorMixin {
    @Unique
    private static final FastBitRadixSort itemSorter = new FastBitRadixSort();
    @Unique
    private static final int fabrolia$maxRetainedCandidates = 256;
    // One idle snapshot per worker. Null means an outer sensor is still using it.
    @Unique
    private static final ThreadLocal<Object[]> fabrolia$idleCandidates = ThreadLocal.withInitial(() -> new Object[0]);

    @WrapMethod(method = "doTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Mob;)V")
    private void doTick(ServerLevel level, Mob body, Operation<Void> original) {
        Brain<?> brain = body.getBrain();
        List<ItemEntity> list = level.getEntitiesOfClass(
                ItemEntity.class,
                body.getBoundingBox().inflate(32.0F, 16.0F, 32.0F),
                (_) -> true
        );

        Optional<ItemEntity> optional = fabrolia$select(list, level, body);
        brain.setMemory(MemoryModuleType.NEAREST_VISIBLE_WANTED_ITEM, optional);
    }

    @Unique
    private static Optional<ItemEntity> fabrolia$select(List<ItemEntity> list, ServerLevel level, Mob body) {
        // Level#getEntities builds a fresh ArrayList. Never add size()/typed toArray()
        // observations to a modded list, even if it subclasses ArrayList.
        if (list.getClass() == ArrayList.class) {
            int size = list.size();
            if (size <= fabrolia$maxRetainedCandidates) {
                Object[] idle = fabrolia$idleCandidates.get();
                if (idle != null) {
                    Object[] scratch = idle.length < size ? new Object[size] : idle;
                    fabrolia$idleCandidates.set(null);
                    try {
                        Object[] candidates = list.toArray(scratch);
                        return fabrolia$firstWanted(candidates, candidates == scratch ? size : candidates.length, level, body);
                    } finally {
                        Arrays.fill(scratch, 0, size, null);
                        fabrolia$idleCandidates.set(scratch);
                    }
                }
            }
        }
        // Unknown list, overflow or nested borrow: take the original detached snapshot.
        Object[] candidates = list.toArray();
        return fabrolia$firstWanted(candidates, candidates.length, level, body);
    }

    @Unique
    private static Optional<ItemEntity> fabrolia$firstWanted(Object[] candidates, int size, ServerLevel level, Mob body) {
        itemSorter.sort(candidates, size, body.position());
        for (int i = 0; i < size; i++) {
            ItemEntity item = (ItemEntity) candidates[i];
            if (body.wantsToPickUp(level, item.getItem()) && item.closerThan(body, 32.0F) && body.hasLineOfSight(item)) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }
}