package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.regions.NetherPortalTransfers;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.animal.nautilus.AbstractNautilus;
import net.minecraft.world.entity.animal.panda.Panda;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.animal.squid.GlowSquid;
import net.minecraft.world.entity.animal.squid.Squid;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinBrute;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.raid.Raider;
import org.spongepowered.asm.mixin.Mixin;

/**
 * These vanilla overrides can mutate before/after super, or bypass it entirely (ArmorStand).
 * A base LivingEntity guard alone cannot reserve the complete polymorphic damage operation.
 */
@Mixin({Bat.class, Allay.class, Armadillo.class, Axolotl.class, Bee.class, AbstractHorse.class,
        IronGolem.class, AbstractNautilus.class, Panda.class, Parrot.class, GlowSquid.class, Squid.class,
        Wolf.class, WitherBoss.class, ArmorStand.class, Enderman.class, Ghast.class, Guardian.class,
        Shulker.class, Silverfish.class, Zoglin.class, Creaking.class, SulfurCube.class, Hoglin.class,
        Piglin.class, PiglinBrute.class, Warden.class, Zombie.class, Raider.class})
public abstract class LivingDamageOverridePortalReservationMixin {
    @WrapMethod(method = "hurtServer")
    private boolean fabrolia$guardReservedDamage(ServerLevel level, DamageSource source,
                                                float damage, Operation<Boolean> original) {
        if (NetherPortalTransfers.isSourceMutationBlocked(this)) return false;
        return original.call(level, source, damage);
    }
}
