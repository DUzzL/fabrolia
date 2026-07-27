package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.parallelised.utils.FabroliaTrackingDirtyFlag;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import com.axalotl.fabrolia.common.regions.RegionKeyed;

@Mixin(Entity.class)
public abstract class EntityMixin implements ParallelProcessor.TickGuard, RegionKeyed,
        com.axalotl.fabrolia.common.parallelised.utils.SpawnCensus.Ledger {

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
        fabrolia$portalPending = pending;
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
    public void fabrolia$setRegionKey(long key) { fabrolia$regionKey = key; }

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
    private final AtomicBoolean fabrolia$tickGuard = new AtomicBoolean(false);

    @Override
    public boolean fabrolia$tryBeginTick() {
        return fabrolia$tickGuard.compareAndSet(false, true);
    }

    @Override
    public void fabrolia$endTick() {
        fabrolia$tickGuard.set(false);
    }

    @Unique
    private byte fabrolia$syncCache = -1;

    @Override
    public byte fabrolia$getSyncCache() { return fabrolia$syncCache; }

    @Override
    public void fabrolia$setSyncCache(byte v) { fabrolia$syncCache = v; }

    /**
     * task #26 — the source entity must LOOK REMOVED for the remainder of the tick
     * in which it handed a cross-dimension transfer to main.
     *
     * <p>Vanilla's {@code handlePortal} teleports inline and runs
     * {@code removeAfterChangingDimensions()} before returning (Entity.java:3088),
     * so for the rest of that tick the source is {@code CHANGED_DIMENSION}-removed.
     * Every type's continuation is then gated on that state — and crucially, some
     * vanilla logic deliberately runs ANYWAY:
     *
     * <pre>
     * FallingBlockEntity.tick():158-159
     *     this.handlePortal();
     *     if (this.level() instanceof ServerLevel sl
     *             &amp;&amp; (this.isAlive() || this.forceTickAfterTeleportToDuplicate)) { ... place ... }
     * </pre>
     *
     * {@code forceTickAfterTeleportToDuplicate} (set at FallingBlockEntity.java:360 as
     * {@code newEntity != null &amp;&amp; fromOrToEnd}) exists precisely so a falling block
     * KEEPS placing its block after an End teleport — that is the End-portal sand
     * duper, and it is deliberate vanilla behaviour.
     *
     * <p>This is why audit2's approach was wrong. Aborting the rest of the tick
     * (throwing a control-flow signal out of handlePortal) is NOT equivalent to
     * "the entity is removed": it also destroys the {@code ||} that vanilla relies
     * on, so the block is never placed and the duper dies. Reporting the vanilla
     * STATE instead lets every entity type's own vanilla logic decide, including the
     * one type that deliberately opts back in. No entity-type special-case is added
     * here — the special case is Mojang's own field.
     *
     * <p>Scope is exact: {@code hasDepartedThisTick} is a thread-confined identity
     * marker set at submit and cleared when that tick frame ends, so this reads true
     * ONLY on the source worker, ONLY between the handoff and the end of that tick.
     * Main's deferred drain sees the real value, which is essential — {@code
     * Entity.teleport} refuses a removed entity (Entity.java:3037), so it must not
     * observe this. Cost is one volatile read (false for effectively every entity)
     * before the thread-local is ever consulted.
     */
    @WrapMethod(method = "isRemoved")
    private boolean fabrolia$removedWhileDeparted(Operation<Boolean> original) {
        if (original.call()) return true;
        return fabrolia$portalPending
                && com.axalotl.fabrolia.common.parallelised.utils.PortalTeleportationManager
                        .hasDepartedThisTick(this);
    }

    @WrapMethod(method = "setRemoved")
    private void setRemoved(Entity.RemovalReason reason, Operation<Void> original) {
        synchronized (fabrolia$lock) {
            original.call(reason);
        }
    }

    @WrapMethod(method = "getInBlockState")
    private BlockState wrapGetInBlockState(Operation<BlockState> original) {
        BlockState blockState;
        try {
            blockState = original.call();
        } catch (IllegalStateException ise) {
            String msg = ise.getMessage();
            if (msg != null && msg.startsWith("Should always be able to create a chunk")) {
                return Blocks.AIR.defaultBlockState();
            }
            throw ise;
        }
        return blockState != null ? blockState : Blocks.AIR.defaultBlockState();
    }


    @WrapMethod(method = "addPassenger")
    private void addPassenger(Entity passenger, Operation<Void> original) {
        synchronized (fabrolia$lock) {
            original.call(passenger);
        }
    }

    @WrapMethod(method = "removePassenger")
    private void removePassenger(Entity passenger, Operation<Void> original) {
        synchronized (fabrolia$lock) {
            original.call(passenger);
        }
    }

    @WrapMethod(method = "ejectPassengers")
    private void ejectPassengers(Operation<Void> original) {
        synchronized (fabrolia$lock) {
            original.call();
        }
    }

    @WrapMethod(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z")
    private boolean startRiding(Entity entityToRide, boolean force, boolean sendEventAndTriggers, Operation<Boolean> original) {
        synchronized (fabrolia$lock) {
            return original.call(entityToRide, force, sendEventAndTriggers);
        }
    }

    @WrapMethod(method = "removeVehicle")
    private void removeVehicle(Operation<Void> original) {
        synchronized (fabrolia$lock) {
            original.call();
        }
    }
}
