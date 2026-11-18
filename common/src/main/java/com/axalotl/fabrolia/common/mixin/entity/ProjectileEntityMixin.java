package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.regions.RegionWorkerThread;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Consumer;
import com.axalotl.fabrolia.common.regions.FabroliaLocks;

@Mixin(Projectile.class)
public class ProjectileEntityMixin {
    @Unique
    private static final Object lock = new Object();

    /** The spawn's {@code applyOnProjectileSpawned}, held until its monitor is released. */
    @Unique
    private static final ScopedValue<Runnable[]> fabrolia$deferredOnSpawned = ScopedValue.newInstance();

    @WrapMethod(method = "spawnProjectile(Lnet/minecraft/world/entity/projectile/Projectile;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;Ljava/util/function/Consumer;)Lnet/minecraft/world/entity/projectile/Projectile;")
    private static <T extends Projectile> T spawn(T projectile, ServerLevel serverLevel, ItemStack itemStack, Consumer<T> shootFunction, Operation<T> original) {
        if (!(Thread.currentThread() instanceof RegionWorkerThread)) {
            synchronized (FabroliaLocks.tickLockOr(lock)) {
                return original.call(projectile, serverLevel, itemStack, shootFunction);
            }
        }
        // On a region worker, vanilla's last step (the weapon's projectile_spawned
        // enchantment effects) runs after this monitor is released, still before
        // the spawn returns: a run_function body that is not owner-local parks the
        // island coordinator until fenced Main ran it (EnchantmentFunctionRouting),
        // and outside a region this monitor is the fallback every coordinator and
        // Main share, so parking inside it would keep another island from reaching
        // the fence.
        Runnable[] deferred = new Runnable[1];
        T spawned = ScopedValue.where(fabrolia$deferredOnSpawned, deferred).call(() -> {
            synchronized (FabroliaLocks.tickLockOr(lock)) {
                return original.call(projectile, serverLevel, itemStack, shootFunction);
            }
        });
        if (deferred[0] != null) deferred[0].run();
        return spawned;
    }

    @WrapOperation(
            method = "spawnProjectile(Lnet/minecraft/world/entity/projectile/Projectile;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;Ljava/util/function/Consumer;)Lnet/minecraft/world/entity/projectile/Projectile;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/projectile/Projectile;applyOnProjectileSpawned("
                            + "Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;)V"),
            require = 1,
            allow = 1)
    private static void fabrolia$applyOnSpawnedOutsideMonitor(Projectile projectile, ServerLevel serverLevel,
                                                             ItemStack itemStack, Operation<Void> original) {
        if (fabrolia$deferredOnSpawned.isBound()) {
            Runnable[] slot = fabrolia$deferredOnSpawned.get();
            if (slot[0] == null) {
                slot[0] = () -> original.call(projectile, serverLevel, itemStack);
                return;
            }
        }
        original.call(projectile, serverLevel, itemStack);
    }
}
