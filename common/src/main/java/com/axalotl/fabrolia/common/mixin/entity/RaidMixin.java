package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.api.utils.ConcurrentCollections;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Raiders of one raid tick in different regions (up to 112 blocks from the
 * centre; parallel with islandEntityParallel) and Main loads raiders from disk,
 * so the raid's shared state (wave sets, leaders, heroes, total health, boss
 * bar) is mutated from several threads. One monitor per raid serialises those
 * mutators (audit D-15: the former region tick lock differed per region). They
 * are leaves: nothing under this monitor takes a region lock or waits for Main.
 * {@code Raid#tick} itself runs in the owner island's raid slot, never beside
 * that island's own entity phase.
 */
@Mixin(Raid.class)
public class RaidMixin {

    @Shadow
    private final Map<Integer, Set<Raider>> groupRaiderMap = ConcurrentCollections.newHashMap();

    @WrapMethod(method = "addWaveMob(Lnet/minecraft/server/level/ServerLevel;ILnet/minecraft/world/entity/raid/Raider;Z)Z")
    private boolean addWaveMob(ServerLevel level, int wave, Raider raider, boolean updateHealth, Operation<Boolean> original) {
        synchronized (this) {
            return original.call(level, wave, raider, updateHealth);
        }
    }

    @WrapMethod(method = "addWaveMob(Lnet/minecraft/server/level/ServerLevel;ILnet/minecraft/world/entity/raid/Raider;)Z")
    private boolean addWaveMob(ServerLevel level, int wave, Raider raider, Operation<Boolean> original) {
        synchronized (this) {
            return original.call(level, wave, raider);
        }
    }

    @WrapMethod(method = "removeFromRaid")
    private void fabrolia$removeFromRaid(ServerLevel level, Raider raider, boolean removeFromTotalHealth,
                                         Operation<Void> original) {
        synchronized (this) {
            original.call(level, raider, removeFromTotalHealth);
        }
    }

    @WrapMethod(method = "setLeader")
    private void fabrolia$setLeader(int wave, Raider raider, Operation<Void> original) {
        synchronized (this) {
            original.call(wave, raider);
        }
    }

    @WrapMethod(method = "removeLeader")
    private void fabrolia$removeLeader(int wave, Operation<Void> original) {
        synchronized (this) {
            original.call(wave);
        }
    }

    @WrapMethod(method = "addHeroOfTheVillage")
    private void fabrolia$addHero(Entity killer, Operation<Void> original) {
        synchronized (this) {
            original.call(killer);
        }
    }

    @WrapMethod(method = "updateBossbar")
    private void fabrolia$updateBossbar(Operation<Void> original) {
        synchronized (this) {
            original.call();
        }
    }

    @Redirect(method = "addWaveMob(Lnet/minecraft/server/level/ServerLevel;ILnet/minecraft/world/entity/raid/Raider;Z)Z", at =
    @At(value = "INVOKE", target = "Ljava/util/Map;computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"))
    private Object redirectComputeIfAbsent(Map<Integer, Set<Raider>> instance, Object key, Function<?, ?> mappingFunction) {
        return instance.computeIfAbsent((Integer) key, _ -> ConcurrentCollections.newHashSet());
    }
}
