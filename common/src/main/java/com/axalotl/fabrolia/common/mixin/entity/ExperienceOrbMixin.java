package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.api.utils.SyncItemPickup;
import com.axalotl.fabrolia.common.regions.FabroliaLocks;
import com.axalotl.fabrolia.common.regions.NetherPortalTransfers;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ExperienceOrb.class)
public abstract class ExperienceOrbMixin extends Entity implements SyncItemPickup.Guarded {
    @Unique
    private final SyncItemPickup.Guard fabrolia$guard = new SyncItemPickup.Guard();

    // One reusable slot per thread; each wrapper invocation saves its predecessor
    // on the Java stack so nested native awards cannot release an outer guard.
    @Unique
    private static final ThreadLocal<SyncItemPickup.Guard[]> fabrolia$selectedMerge =
            ThreadLocal.withInitial(() -> new SyncItemPickup.Guard[1]);

    protected ExperienceOrbMixin(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    public SyncItemPickup.Guard fabrolia$consumptionGuard() {
        return fabrolia$guard;
    }

    /** Pickup blocks only on this orb, never on an unrelated world's XP lane. */
    @WrapMethod(method = "playerTouch")
    private void fabrolia$syncOrbPickup(Player player, Operation<Void> original) {
        fabrolia$guard.enterConsumption();
        try {
            if (SyncItemPickup.isAvailable(this)) original.call(player);
        } finally {
            fabrolia$guard.exitConsumption();
        }
    }

    @WrapMethod(method = "tick")
    private void fabrolia$syncWholeOrbTick(Operation<Void> original) {
        if (level() instanceof ServerLevel serverLevel) {
            Object lane = FabroliaLocks.itemTickLaneOr(serverLevel, this, SyncItemPickup.fallback());
            synchronized (lane) {
                SyncItemPickup.wrapTick(this, original::call);
            }
        } else {
            original.call();
        }
    }

    /** All native merges take the lane before self and the candidate guard. */
    @WrapMethod(method = "merge")
    private void fabrolia$guardReservedMerge(ExperienceOrb other, Operation<Void> original) {
        Object lane = level() instanceof ServerLevel serverLevel
                ? FabroliaLocks.itemTickLaneOr(serverLevel, this, SyncItemPickup.fallback())
                : SyncItemPickup.fallback();
        synchronized (lane) {
            fabrolia$guard.enterConsumption();
            try {
                if (NetherPortalTransfers.isSourceMutationBlocked(this) || isRemoved()) return;
                SyncItemPickup.Guard candidate = SyncItemPickup.guard(other);
                candidate.enterConsumption();
                try {
                    if (!NetherPortalTransfers.isSourceMutationBlocked(other) && !other.isRemoved())
                        original.call(other);
                } finally {
                    candidate.exitConsumption();
                }
            } finally {
                fabrolia$guard.exitConsumption();
            }
        }
    }

    @Inject(method = "canMerge(Lnet/minecraft/world/entity/ExperienceOrb;II)Z",
            at = @At("HEAD"), cancellable = true)
    private static void fabrolia$filterReservedMerge(ExperienceOrb orb, int id, int value,
                                                    CallbackInfoReturnable<Boolean> cir) {
        if (NetherPortalTransfers.isSourceMutationBlocked(orb)) cir.setReturnValue(false);
    }

    @WrapMethod(method = "tryMergeToExisting")
    private static boolean fabrolia$syncExistingMerge(ServerLevel level, Vec3 pos, int value,
                                                      Operation<Boolean> original) {
        Object lane = FabroliaLocks.itemTickLaneOr(level,
                ChunkPos.pack(BlockPos.containing(pos)), SyncItemPickup.fallback());
        synchronized (lane) {
            SyncItemPickup.Guard[] selected = fabrolia$selectedMerge.get();
            SyncItemPickup.Guard previous = selected[0];
            selected[0] = null;
            try {
                return original.call(level, pos, value);
            } finally {
                SyncItemPickup.Guard acquired = selected[0];
                selected[0] = previous;
                if (acquired != null) acquired.exitConsumption();
            }
        }
    }

    // Selection remains native. The first count GETFIELD follows the selected
    // orb's ASTORE and precedes both count and age writes. Own that exact orb
    // before rechecking liveness/reservation, then release in the outer finally.
    @Inject(method = "tryMergeToExisting", at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/entity/ExperienceOrb;count:I",
            opcode = Opcodes.GETFIELD, ordinal = 0), cancellable = true)
    private static void fabrolia$guardSelectedMerge(ServerLevel level, Vec3 pos, int value,
            CallbackInfoReturnable<Boolean> cir, @Local ExperienceOrb orb) {
        SyncItemPickup.Guard guard = SyncItemPickup.guard(orb);
        guard.enterConsumption();
        fabrolia$selectedMerge.get()[0] = guard;
        if (NetherPortalTransfers.isSourceMutationBlocked(orb) || orb.isRemoved())
            cir.setReturnValue(false);
    }
}
