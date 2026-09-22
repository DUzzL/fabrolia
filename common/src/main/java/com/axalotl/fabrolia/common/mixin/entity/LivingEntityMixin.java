package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.utils.EffectMap;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.*;

@Mixin(value = LivingEntity.class)
public abstract class LivingEntityMixin extends Entity {

    // Owned iteration expires the current registration even when its callback
    // inserts another effect, then stops before visiting the new registration.
    // Removed/replaced registrations must never receive stale expiration callbacks.
    // Keep remote reads nonblocking: a callback can query an entity whose own
    // tick holds its mutation monitor on another region worker.
    @Shadow
    final private Map<Holder<MobEffect>, MobEffectInstance> activeEffects =
            new EffectMap<>();

    // Share the map mutex so raw Map.remove/clear cannot bypass an entered tick.
    @Unique
    private final Object fabrolia$lock = this.activeEffects;

    /** isAlive() = !removed && getHealth()>0, and getHealth() is a SynchedEntityData
     *  map/array read — 5.2 core-ms/tick on the 2026-07-11 worker profiles, mostly
     *  from pushability checks (isPushable -> isAlive per neighbor pair). Health
     *  writes all funnel through SynchedEntityData.set -> onSyncedDataUpdated, so a
     *  plain cached sign bit maintained there is observably identical at every read
     *  point. Volatile: read from region workers, written wherever health changes. */
    @Unique
    private volatile boolean fabrolia$healthPositive = true;

    @Shadow
    @org.spongepowered.asm.mixin.Final
    private static net.minecraft.network.syncher.EntityDataAccessor<Float> DATA_HEALTH_ID;

    @Shadow
    public abstract float getHealth();

    @Inject(method = "onSyncedDataUpdated", at = @At("HEAD"))
    private void fabrolia$trackHealthSign(net.minecraft.network.syncher.EntityDataAccessor<?> accessor,
                                          org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (DATA_HEALTH_ID.equals(accessor)) {
            fabrolia$healthPositive = this.getHealth() > 0.0F;
        }
    }

    @WrapMethod(method = "isAlive")
    private boolean fabrolia$isAliveCached(Operation<Boolean> original) {
        return !this.isRemoved() && fabrolia$healthPositive;
    }


    public LivingEntityMixin(EntityType<?> type, Level world) {
        super(type, world);
    }

    @WrapMethod(method = "die")
    private synchronized void die(DamageSource source, Operation<Void> original) {
        original.call(source);
    }

    @WrapMethod(method = "dropFromLootTable(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;Z)V")
    private synchronized void dropFromLootTable(ServerLevel level, DamageSource source, boolean playerKilled, Operation<Void> original) {
        original.call(level, source, playerKilled);
    }

    @WrapMethod(method = "knockback(DDDLnet/minecraft/world/damagesource/DamageSource;FZ)V")
    private void knockback(double power, double xd, double zd, DamageSource source, float verticalLimit, boolean strong, Operation<Void> original) {
        synchronized (this.fabrolia$lock) {
            original.call(power, xd, zd, source, verticalLimit, strong);
        }
    }

    @WrapMethod(method = "tickEffects")
    private void tickStatusEffects(Operation<Void> original) {
        synchronized (this.fabrolia$lock) {
            original.call();
        }
    }


    @WrapMethod(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z")
    private boolean addEffect(MobEffectInstance newEffect, Entity source, Operation<Boolean> original) {
        synchronized (this.fabrolia$lock) {
            return newEffect != null ? original.call(newEffect, source) : false;
        }
    }

    @WrapMethod(method = "removeEffect")
    private boolean removeEffect(Holder<MobEffect> effect, Operation<Boolean> original) {
        synchronized (this.fabrolia$lock) {
            return effect != null ? original.call(effect) : false;
        }
    }

    @WrapMethod(method = "hasEffect")
    public boolean hasEffect(Holder<MobEffect> effect, Operation<Boolean> original) {
        return effect != null ? original.call(effect) : false;
    }

    @WrapMethod(method = "removeAllEffects")
    private boolean removeAllEffects(Operation<Boolean> original) {
        synchronized (this.fabrolia$lock) {
            return original.call();
        }
    }

    @Inject(method = "causeFallDamage", at = @At("HEAD"), cancellable = true)
    private void causeFallDamage(double fallDistance, float damageModifier, DamageSource damageSource, CallbackInfoReturnable<Boolean> cir) {
        BlockPos pos = new BlockPos(Mth.floor(this.getX()), Mth.floor(this.getY()), Mth.floor(this.getZ()));
        BlockState currentBlock = this.level().getBlockState(pos);

        if (currentBlock.is(BlockTags.CLIMBABLE)) {
            cir.setReturnValue(false);
        }
    }

    @Redirect(method = "refreshDirtyAttributes", at = @At(value = "INVOKE", target = "Ljava/util/Set;iterator()Ljava/util/Iterator;"))
    private Iterator<AttributeInstance> snapshotIterator(Set<AttributeInstance> set) {
        Set<AttributeInstance> snapshot;
        synchronized (this.fabrolia$lock) {
            snapshot = new HashSet<>(set);
            set.clear();
        }
        return snapshot.iterator();
    }
}
