package com.axalotl.fabrolia.common.mixin.entity.breed;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.sniffer.Sniffer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.atomic.AtomicBoolean;

@Mixin(Sniffer.class)
public abstract class SnifferMixin extends Animal {

    @Unique
    private final AtomicBoolean breedingFlag = new AtomicBoolean(false);

    protected SnifferMixin(EntityType<? extends Animal> entityType, Level world) {
        super(entityType, world);
    }

    @WrapMethod(method = "spawnChildFromBreeding")
    private void breed(ServerLevel level, Animal partner, Operation<Void> original) {
        if (this.getId() > partner.getId()) return;
        SnifferMixin otherMixin = (SnifferMixin) partner;
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
}