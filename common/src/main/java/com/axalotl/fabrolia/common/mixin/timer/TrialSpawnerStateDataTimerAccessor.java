package com.axalotl.fabrolia.common.mixin.timer;

import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerStateData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Timer clock fix: trial spawner cooldown end and next spawn ({@code TimerClocks}). */
@Mixin(TrialSpawnerStateData.class)
public interface TrialSpawnerStateDataTimerAccessor {
    @Accessor("cooldownEndsAt")
    long fabrolia$cooldownEndsAt();

    @Accessor("cooldownEndsAt")
    void fabrolia$setCooldownEndsAt(long value);

    @Accessor("nextMobSpawnsAt")
    long fabrolia$nextMobSpawnsAt();

    @Accessor("nextMobSpawnsAt")
    void fabrolia$setNextMobSpawnsAt(long value);
}
