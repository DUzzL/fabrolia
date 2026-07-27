package com.axalotl.fabrolia.common.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.atomic.AtomicBoolean;

@Mixin(Animal.class)
public abstract class AnimalMixin extends Entity {

    @Unique
    private final AtomicBoolean breedingFlag = new AtomicBoolean(false);
    @Unique
    private final AtomicBoolean breedingBabyFlag = new AtomicBoolean(false);

    public AnimalMixin(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    @WrapMethod(method = "spawnChildFromBreeding")
    private void breed(ServerLevel level, Animal partner, Operation<Void> original) {
        if (this.getId() > partner.getId()) return;
        AnimalMixin otherMixin = (AnimalMixin) (Object) partner;
        // && short-circuits: if OUR CAS wins and the partner's loses, the old code
        // never entered the try, so our flag stayed true FOREVER and this animal
        // could never breed again. Nested acquisition releases each flag on the
        // exact path that took it. Audit 2026-07-26.
        if (!this.breedingFlag.compareAndSet(false, true)) return;
        try {
            if (!otherMixin.breedingFlag.compareAndSet(false, true)) return;
            try {
                original.call(level, partner);
            } finally {
                otherMixin.breedingFlag.set(false);
            }
        } finally {
            this.breedingFlag.set(false);
        }
    }

    @WrapMethod(method = "finalizeSpawnChildFromBreeding")
    private void breed(ServerLevel level, Animal partner, AgeableMob offspring, Operation<Void> original) {
        if (this.getId() > partner.getId()) return;
        AnimalMixin otherMixin = (AnimalMixin) (Object) partner;
        // && short-circuits: if OUR CAS wins and the partner's loses, the old code
        // never entered the try, so our flag stayed true FOREVER and this animal
        // could never breed again. Nested acquisition releases each flag on the
        // exact path that took it. Audit 2026-07-26.
        if (!this.breedingBabyFlag.compareAndSet(false, true)) return;
        try {
            if (!otherMixin.breedingBabyFlag.compareAndSet(false, true)) return;
            try {
                original.call(level, partner, offspring);
            } finally {
                otherMixin.breedingBabyFlag.set(false);
            }
        } finally {
            this.breedingBabyFlag.set(false);
        }
    }
}