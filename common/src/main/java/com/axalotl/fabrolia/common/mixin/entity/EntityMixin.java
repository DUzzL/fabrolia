package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.parallelised.utils.FabroliaTrackingDirtyFlag;
import com.axalotl.fabrolia.common.regions.EntityTickLease;
import com.axalotl.fabrolia.common.regions.FishingHookRemovalTrace;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.WorldGenEntityConstruction;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicLong;
import com.axalotl.fabrolia.common.regions.RegionKeyed;

@Mixin(Entity.class)
public abstract class EntityMixin implements ParallelProcessor.TickGuard, RegionKeyed,
        com.axalotl.fabrolia.common.parallelised.utils.SpawnCensus.Ledger,
        WorldGenEntityConstruction.Constructed,
        com.axalotl.fabrolia.common.regions.NativePlayerObservations.RidingMetadata {

    @Shadow
    private Entity vehicle;

    @Override
    public final Entity fabrolia$queryVehicle() {
        return vehicle;
    }

    @Unique
    private volatile long fabrolia$constructionScope = WorldGenEntityConstruction.currentScope();

    @Override
    public long fabrolia$constructionScope() {
        return fabrolia$constructionScope;
    }

    @Override
    public void fabrolia$clearConstructionScope() {
        fabrolia$constructionScope = 0;
    }

    @Inject(method = "setLevelCallback", at = @At("HEAD"))
    private void fabrolia$publishConstruction(EntityInLevelCallback callback, CallbackInfo ci) {
        if (callback != EntityInLevelCallback.NULL) fabrolia$clearConstructionScope();
    }

    private final Object fabrolia$censusLock = new Object();
    private boolean fabrolia$censusTracked;
    private long fabrolia$censusChunk;
    private int fabrolia$censusState;

    @Override
    public Object fabrolia$censusLock() {
        return fabrolia$censusLock;
    }

    @Override
    public boolean fabrolia$censusTracked() {
        return fabrolia$censusTracked;
    }

    @Override
    public void fabrolia$setCensusTracked(boolean tracked) {
        fabrolia$censusTracked = tracked;
    }

    @Override
    public long fabrolia$censusChunk() {
        return fabrolia$censusChunk;
    }

    @Override
    public void fabrolia$setCensusChunk(long chunkKey) {
        fabrolia$censusChunk = chunkKey;
    }

    @Override
    public int fabrolia$censusState() {
        return fabrolia$censusState;
    }

    @Override
    public void fabrolia$setCensusState(int state) {
        fabrolia$censusState = state;
    }


    @org.spongepowered.asm.mixin.Unique
    private volatile boolean fabrolia$portalPending;

    @Override
    public boolean fabrolia$isPortalPending() {
        return fabrolia$portalPending;
    }

    @Override
    public void fabrolia$setPortalPending(boolean pending) {
        // Transaction visibility only. The eventual ServerPlayer.teleport wrapper
        // owns the real lifecycle mutation after the source island claim retires.
        if (pending) fabrolia$clearConstructionScope();
        fabrolia$portalPending = pending;
    }

    @Override
    public boolean fabrolia$isPortalResolutionOwner() {
        return com.axalotl.fabrolia.common.regions.NetherPortalTransfers.isResolvingSource(this);
    }

    /** Hook point NOTE: baseTick is NOT reached by every entity type —
     *  AbstractMinecart.tick() never calls super.tick() (bytecode-verified
     *  26.2: hurt/damage → checkBelowWorld → computeSpeed → handlePortal →
     *  behavior.tick). setOldPosAndRot is the first statement of
     *  ServerLevel.tickNonPassenger/tickPassenger for EVERY ticked entity. */
    @Inject(method = "setOldPosAndRot()V", at = @At("HEAD"))
    private void fabrolia$stampLastTick(CallbackInfo ci) {
        // Last-tick stamp (stuck-in-portal probe): setOldPosAndRot is the first
        // statement of tickNonPassenger/tickPassenger for every ticked entity,
        // so this records "was ticked at wall-nanos T". A cart trapped in a
        // portal but STILL ticking (island self-drive) stamps every tick; a
        // stranded/unticked one goes stale — the discriminator between the two.
        // Wall nanos (not game-time): a level's game-time reads as the island L
        // during island ticking, which trails wall — comparing that to wall
        // game-time made a healthy cart look many ticks stale.
        fabrolia$lastTickNanos = System.nanoTime();
    }

    @Unique
    private volatile long fabrolia$lastTickNanos = 0L;

    @Override
    public long fabrolia$lastTickNanos() {
        return fabrolia$lastTickNanos;
    }

    @Shadow
    public net.minecraft.world.entity.PortalProcessor portalProcess;

    @Shadow
    public abstract boolean isOnPortalCooldown();

    @Shadow
    public abstract int getPortalCooldown();

    @Override
    public String fabrolia$portalStateInfo() {
        net.minecraft.world.entity.PortalProcessor pp = this.portalProcess;
        StringBuilder sb = new StringBuilder(48);
        sb.append("cooldown=").append(this.getPortalCooldown());
        if (pp == null) {
            sb.append(" portalProcess=null");
        } else {
            sb.append(" portalProcess{entry=").append(pp.getEntryPosition().toShortString())
              .append(" expired=").append(pp.hasExpired()).append('}');
        }
        return sb.toString();
    }


    @Unique
    private volatile long fabrolia$regionKey = RegionKeyed.NO_REGION;

    @Override
    public long fabrolia$getRegionKey() { return fabrolia$regionKey; }

    @Override
    public void fabrolia$setRegionKey(long key) {
        if (key != RegionKeyed.NO_REGION) fabrolia$clearConstructionScope();
        fabrolia$regionKey = key;
    }

    @Unique
    private final AtomicLong fabrolia$lastRegionTickToken = new AtomicLong(Long.MIN_VALUE);

    @Override
    public boolean fabrolia$carryRegionTickMark(long expectedFrom, long newToken) {
        // Pure CAS: succeeds only when the mark is EXACTLY the predecessor's current
        // token, so an entity that never ticked under `expectedFrom` (any other
        // value, including the initial one) is left untouched and stays eligible.
        if (expectedFrom == newToken) return false;
        return fabrolia$lastRegionTickToken.compareAndSet(expectedFrom, newToken);
    }

    @Override
    public boolean fabrolia$tryMarkRegionTick(long token) {
        long observed;
        do {
            observed = fabrolia$lastRegionTickToken.get();
            if (observed == token) {
                return false;
            }
        } while (!fabrolia$lastRegionTickToken.compareAndSet(observed, token));
        return true;
    }


    @Shadow
    public abstract Level level();

    @Shadow public abstract double getX();
    @Shadow public abstract double getY();
    @Shadow public abstract double getZ();


    /**
     * Bumps the tracking dirty flag whenever this entity's position actually changes.
     * Used by {@code NearbyEntityTrackingMixin.fabrolia$maybeSkipTick0} to decide whether VMP's
     * {@code tick0} can be skipped: if no entity moved (and no player moved sub-section) since
     * the last tick0, all of VMP's tracking iteration is unnecessary.
     *
     * <p>Cost: ~3 double compares + one atomic increment when the entity actually moved.
     * For 1000 moving entities ≈ 20k atomic ops/sec (negligible). For stationary bots →
     * setPos called with current coords (or not at all) → flag never bumps → tick0 skips.
     *
     * <p>Without this hook we'd miss in-section entity moves (mobs roaming within a chunk,
     * items spinning) and the skip would be observably non-vanilla — nearby players would see
     * those entities freeze until something else bumped the flag.
     */
    @Inject(method = "setPos(DDD)V", at = @At("HEAD"))
    private void fabrolia$markMovedIfChanged(double x, double y, double z, CallbackInfo ci) {
        if (this.getX() != x || this.getY() != y || this.getZ() != z) {
            FabroliaTrackingDirtyFlag.markDirty();
        }
    }

    @Unique
    private final Object fabrolia$lock = new Object();

    @Unique
    private final EntityTickLease fabrolia$tickGuard = new EntityTickLease();

    @Override
    public EntityTickLease fabrolia$tickLease() {
        return fabrolia$tickGuard;
    }

    @Unique
    @SuppressWarnings("unchecked")
    private <T> T fabrolia$withPlayerLifecycleLease(
            com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2.LifecycleMutationKind
                    kind,
            Entity[] participants, boolean generationPrivateRide, java.util.function.Supplier<T> body) {
        if (generationPrivateRide) {
            // Callers prove both private construction and event-free ride bookkeeping.
            // This is not authority for game events, removal hooks or shared world state.
            try {
                RegionScheduler.commitSuccessIfHealthy(() -> {});
                T result = body.get();
                RegionScheduler.commitSuccessIfHealthy(() -> {});
                return result;
            } catch (Throwable thrown) {
                EntityMixin.<RuntimeException>fabrolia$throwLifecycleFailure(
                        RegionScheduler.publishFatalAndSelectWinner(thrown));
                throw new AssertionError("unreachable");
            }
        }
        com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2.LifecycleLeaseScope scope =
                com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                        .beginLifecycleMutation(kind, participants);
        Object[] result = new Object[1];
        Throwable failure = null;
        try {
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> result[0] = com.axalotl.fabrolia.common.regions.NetherPortalPlayerAccess
                            .withRidePublication(participants, body));
        } catch (Throwable thrown) {
            // A vanilla/Fabric lifecycle mutation has entered. Close the
            // shared start gate before releasing any participant lease.
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        try {
            failure = scope.finish(failure);
        } catch (Throwable cleanup) {
            // LifecycleLeaseScope normally exhausts its own release ledger;
            // this is only its terminal cleanup escape backstop.
            failure = fabrolia$mergeLifecycleFailure(failure, cleanup);
        }
        if (failure == null) {
            try {
                RegionScheduler.commitSuccessIfHealthy(() -> {});
            } catch (Throwable peerFailure) {
                failure = RegionScheduler.publishFatalAndSelectWinner(peerFailure);
            }
        }
        if (failure != null) {
            // finish() can also discover a cleanup-only failure. Re-select the
            // generation winner so this wrapper never re-prefers a later one.
            failure = RegionScheduler.publishFatalAndSelectWinner(failure);
        }
        if (failure != null) EntityMixin.<RuntimeException>fabrolia$throwLifecycleFailure(failure);
        return (T) result[0];
    }

    @Unique
    private static Throwable fabrolia$mergeLifecycleFailure(
            Throwable primary, Throwable cleanup) {
        if (primary == null) return cleanup;
        if (cleanup != null && cleanup != primary) primary.addSuppressed(cleanup);
        return primary;
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void fabrolia$throwLifecycleFailure(
            Throwable failure) throws T {
        throw (T) failure;
    }

    @Unique
    private byte fabrolia$syncCache = -1;

    @Override
    public byte fabrolia$getSyncCache() { return fabrolia$syncCache; }

    @Override
    public void fabrolia$setSyncCache(byte v) { fabrolia$syncCache = v; }

    /**
     * Preserve vanilla departure visibility through the submitting claim.
     * Players end this view with their own tick before late/listener work.
     *
     * <p>The shared {@code portalPending} reservation deliberately does not
     * make {@code isRemoved()} globally true: save/index/pearl registries use
     * that predicate to destructively cull entities. External mutation paths
     * instead reject a pending source explicitly, while the exact Main resolver
     * is authenticated by identity and thread.
     */
    @WrapMethod(method = "isRemoved")
    private boolean fabrolia$removedWhileDeparted(Operation<Boolean> original) {
        if (original.call()) return true;
        return fabrolia$portalPending
                && com.axalotl.fabrolia.common.regions.NetherPortalTransfers.hasDepartedThisTick(this);
    }

    @WrapMethod(method = "setRemoved(Lnet/minecraft/world/entity/Entity$RemovalReason;)V")
    private void setRemoved(Entity.RemovalReason reason, Operation<Void> original) {
        Entity self = (Entity) (Object) this;
        if (fabrolia$portalPending && !(self instanceof ServerPlayer)
                && !com.axalotl.fabrolia.common.regions.NetherPortalTransfers.isResolvingSource(self)) {
            // Once vanilla's portal transfer wins the serial order, later
            // gameplay kill/discard attempts cannot consume the old source.
            // Destructive gameplay entry points must reject the reservation
            // before damage, inventory and drop effects. This final removal gate
            // also protects direct kill/discard calls.
            if (reason == Entity.RemovalReason.KILLED
                    || reason == Entity.RemovalReason.DISCARDED) {
                return;
            }
            throw new IllegalStateException(
                    "portal-pending source received non-resolver removal: entity="
                            + self + " reason=" + reason);
        }
        fabrolia$withPlayerLifecycleLease(
                com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                        .LifecycleMutationKind.HARD_EXIT,
                new Entity[]{self}, false, () -> {
            synchronized (fabrolia$lock) {
                if (com.axalotl.fabrolia.common.utils.FabroliaDebug.FISHING_TRACE
                        && self instanceof FishingHookRemovalTrace trace) {
                    trace.fabrolia$traceRemoval(reason);
                }
                original.call(reason);
            }
            return null;
        });
    }

    /**
     * A queued portal source is no longer a collision participant in vanilla.
     * Its independently indexed passengers also reach this generic push path.
     * Exclude both endpoints' reserved roots without reserving the passengers
     * themselves; the exact resolver remains permitted to complete the transfer.
     */
    @WrapMethod(method = "push(Lnet/minecraft/world/entity/Entity;)V")
    private void fabrolia$skipPortalReservedPush(
            Entity other, Operation<Void> original) {
        Entity self = (Entity) (Object) this;
        if (com.axalotl.fabrolia.common.regions.NetherPortalTransfers.isSourceMutationBlocked(self)
                || com.axalotl.fabrolia.common.regions.NetherPortalTransfers.isSourceMutationBlocked(other)
                || com.axalotl.fabrolia.common.regions.NetherPortalTransfers.isSourceMutationBlocked(self.getRootVehicle())
                || com.axalotl.fabrolia.common.regions.NetherPortalTransfers.isSourceMutationBlocked(other.getRootVehicle())) {
            return;
        }
        original.call(other);
    }

    @WrapMethod(method = "addPassenger(Lnet/minecraft/world/entity/Entity;)V")
    private void addPassenger(Entity passenger, Operation<Void> original) {
        Entity self = (Entity) (Object) this;
        Entity[] participants = new Entity[]{self, passenger};
        fabrolia$withPlayerLifecycleLease(
                com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                        .LifecycleMutationKind.SOFT_HANDOFF,
                participants, WorldGenEntityConstruction.owns(participants), () -> {
            synchronized (fabrolia$lock) {
                original.call(passenger);
            }
            return null;
        });
    }

    @WrapMethod(method = "removePassenger(Lnet/minecraft/world/entity/Entity;)V")
    private void removePassenger(Entity passenger, Operation<Void> original) {
        Entity self = (Entity) (Object) this;
        Entity[] participants = new Entity[]{self, passenger};
        fabrolia$withPlayerLifecycleLease(
                com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                        .LifecycleMutationKind.SOFT_HANDOFF,
                participants, WorldGenEntityConstruction.owns(participants), () -> {
            synchronized (fabrolia$lock) {
                original.call(passenger);
            }
            return null;
        });
    }

    @WrapMethod(method = "ejectPassengers()V")
    private void ejectPassengers(Operation<Void> original) {
        Entity self = (Entity) (Object) this;
        fabrolia$withPlayerLifecycleLease(
                com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                        .LifecycleMutationKind.SOFT_HANDOFF,
                new Entity[]{self}, false, () -> {
            synchronized (fabrolia$lock) {
                original.call();
            }
            return null;
        });
    }

    @WrapMethod(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z")
    private boolean startRiding(Entity entityToRide, boolean force, boolean sendEventAndTriggers, Operation<Boolean> original) {
        Entity self = (Entity) (Object) this;
        Entity[] participants = new Entity[]{self, entityToRide};
        com.axalotl.fabrolia.common.regions.NativePlayerObservations
                .requireSupportedRideTransition(entityToRide);
        boolean generationPrivateRide = !sendEventAndTriggers
                && WorldGenEntityConstruction.owns(participants) && self.getVehicle() == null;
        return fabrolia$withPlayerLifecycleLease(
                com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                        .LifecycleMutationKind.SOFT_HANDOFF,
                participants, generationPrivateRide, () -> {
            synchronized (fabrolia$lock) {
                return original.call(entityToRide, force, sendEventAndTriggers);
            }
        });
    }

    /**
     * VEHICLE-SPOOF GUARD (2026-08-06 minecart-passenger separation fix):
     * vanilla {@code Entity.baseTick} dismounts a passenger whose vehicle
     * {@code isRemoved()}. Under the deferred portal transfer the source
     * vehicle only LOOKS removed on the submitting worker (the
     * {@link #fabrolia$removedWhileDeparted} spoof) while main is still
     * carrying its riders through the teleport — and vanilla itself removes
     * the source vehicle only AFTER the passenger recursion. Reading the
     * spoof as real removal dismounted the passenger mid-handoff: the cart
     * arrived alone, the mob stayed behind (or followed on its own slow
     * portal timer), and it lost {@code requiresCustomPersistence}'s
     * isPassenger protection on arrival. Report the real removal state for
     * this check: a spoofed reading has no {@code RemovalReason}; a genuine
     * removal always carries one.
     */
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "baseTick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;isRemoved()Z"))
    private boolean fabrolia$baseTickVehicleRealRemoval(Entity vehicle, Operation<Boolean> original) {
        boolean removed = original.call(vehicle);
        if (removed && vehicle.getRemovalReason() == null
                && vehicle instanceof ParallelProcessor.TickGuard tg
                && tg.fabrolia$isPortalPending()) {
            return false;
        }
        return removed;
    }

    @WrapMethod(method = "removeVehicle()V")
    private void removeVehicle(Operation<Void> original) {
        Entity self = (Entity) (Object) this;
        Entity[] participants = new Entity[]{self};
        boolean generationPrivateRide = WorldGenEntityConstruction.owns(participants)
                && self.getVehicle() == null;
        fabrolia$withPlayerLifecycleLease(
                com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                        .LifecycleMutationKind.SOFT_HANDOFF,
                participants, generationPrivateRide, () -> {
            synchronized (fabrolia$lock) {
                original.call();
            }
            return null;
        });
    }
}
