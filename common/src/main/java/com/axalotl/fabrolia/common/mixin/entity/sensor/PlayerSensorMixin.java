package com.axalotl.fabrolia.common.mixin.entity.sensor;

import com.axalotl.fabrolia.common.parallelised.utils.FastBitRadixSort;
import com.axalotl.fabrolia.common.regions.NativePlayerObservations;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.PlayerSensor;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;
import java.util.List;

import static net.minecraft.world.entity.ai.sensing.Sensor.isEntityAttackable;
import static net.minecraft.world.entity.ai.sensing.Sensor.isEntityTargetable;

@Mixin(value = PlayerSensor.class, priority = 1500)
public abstract class PlayerSensorMixin {

    @Shadow
    protected abstract double getFollowDistance(LivingEntity body);
    @Unique
    private static final FastBitRadixSort playerSort = new FastBitRadixSort();

    @WrapMethod(method = "doTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;)V")
    private void doTick(ServerLevel level, LivingEntity body, Operation<Void> original) {
        // Unknown/global producers keep every native virtual callback. Local
        // producers were admitted before their root body with the complete live
        // player scan and current rich-candidate dependency closure.
        if (RegionScheduler.workerCount() == 0 || RegionScheduler.ownsExclusiveGameplay()) {
            original.call(level, body);
            return;
        }
        double followDist = this.getFollowDistance(body);

        Object[] arr = level.players().stream()
                .filter(p -> NativePlayerObservations.testPredicate(p, EntitySelector.NO_SPECTATORS))
                .filter(p -> body.position().closerThan(NativePlayerObservations.position(p), followDist))
                // Sorting reads live coordinates; the narrow observer surface
                // does not authorize it or either later targeting callback.
                .peek(NativePlayerObservations::requireGameplayObservation)
                .toArray();

        playerSort.sort(arr, arr.length, body.position());

        List<Player> list = new ArrayList<>(arr.length);
        for (Object o : arr) list.add((Player) o);

        Brain<?> brain = body.getBrain();
        brain.setMemory(MemoryModuleType.NEAREST_PLAYERS, list);

        List<Player> list1 = list.stream()
                .filter(p -> isEntityTargetable(level, body, p))
                .toList();

        brain.setMemory(MemoryModuleType.NEAREST_VISIBLE_PLAYER, list1.isEmpty() ? null : list1.getFirst());

        List<Player> list2 = list1.stream()
                .filter(p -> isEntityAttackable(level, body, p))
                .toList();

        brain.setMemory(MemoryModuleType.NEAREST_VISIBLE_ATTACKABLE_PLAYERS, list2);
        brain.setMemory(MemoryModuleType.NEAREST_VISIBLE_ATTACKABLE_PLAYER, list2.isEmpty() ? null : list2.getFirst());
    }
}
