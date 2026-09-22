package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.regions.RegionWorkerThread;
import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Pearl-landing player teleports must not run on the PEARL's island worker.
 *
 * <p>A thrown pearl ticks in whatever island it currently occupies; its owner
 * is usually elsewhere (main-owned today). Vanilla {@code onHit} runs the FULL
 * player teleport inline — {@code ServerPlayer.teleport} → view recenter →
 * {@code ChunkMap} tracking update → mass {@code dropChunk} /
 * {@code markChunkPendingToSend} — which under async islands executed on the
 * pearl worker and structurally mutated per-player delivery/tracking maps
 * concurrently with main's {@code sendNextChunks} copy (production fastutil
 * iterator NPE at ChunkDelivery:614, 2026-07-20). VMP's area watching maps and
 * vanilla's {@code pendingChunks} were equally exposed.
 *
 * <p>The complete native {@code onHit} producer runs on physical Main under the
 * source unit's typed writer rendezvous. Its original owner admission, RNG,
 * teleport, damage, sound and discard finish before the worker resumes. No
 * accepted player branch is detached from the lifecycle state that admitted it.
 * Flight and stasis ticks retain their existing island venue.
 */
@Mixin(ThrownEnderpearl.class)
public abstract class ThrownEnderpearlMixin {

    @org.spongepowered.asm.mixin.Unique
    private static final java.util.concurrent.atomic.AtomicLong fabrolia$ownerAliveLogNanos =
            new java.util.concurrent.atomic.AtomicLong();

    @org.spongepowered.asm.mixin.Unique
    private static final java.util.concurrent.atomic.LongAdder fabrolia$ownerAlivePrevented =
            new java.util.concurrent.atomic.LongAdder();

    /**
     * The owner-is-dead branch in {@code tick} calls Entity.isAlive.  During a
     * cross-dimension teleport a live ServerPlayer is temporarily marked
     * CHANGED_DIMENSION between removePlayerImmediately and unsetRemoved.
     * Vanilla cannot tick a pearl inside that interval; region workers can,
     * and a whole owner batch then mistakes the transient removal for death
     * and discards every pearl.  The teleport wrapper publishes that exact
     * interval through a concurrent identity marker, providing a real
     * happens-before edge instead of guessing from the player's plain removal
     * fields. Real death, logout and the main path remain vanilla.
     */
    @WrapOperation(method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;isAlive()Z"))
    private boolean fabrolia$ownerRemovalIsNotDeath(Entity owner, Operation<Boolean> original) {
        boolean vanillaAlive = original.call(owner);
        if (vanillaAlive
                || !(Thread.currentThread() instanceof RegionWorkerThread)
                || !(owner instanceof ServerPlayer player)) {
            return vanillaAlive;
        }

        boolean dimensionTransition =
                com.axalotl.fabrolia.common.parallelised.utils.PlayerDimensionTransition
                        .contains(player)
                        && !player.isDeadOrDying();
        ServerPlayer successor = null;
        ServerPlayer cursor = player;
        boolean liveSuccessorChain = true;
        java.util.IdentityHashMap<ServerPlayer, Boolean> seen = new java.util.IdentityHashMap<>();
        seen.put(player, Boolean.TRUE);
        // A pearl tick can retain session A while main replaces A -> B -> C.
        // Bound the chase defensively; normal login/shadow is exactly one hop.
        for (int hop = 0; !dimensionTransition && hop < 8; hop++) {
            var access =
                    (com.axalotl.fabrolia.common.parallelised.utils.PearlOwnerSessionAccess) cursor;
            ServerPlayer next = access.fabrolia$pearlSessionSuccessor();
            if (next == null) break;
            if (next == cursor || seen.put(next, Boolean.TRUE) != null) {
                liveSuccessorChain = false;
                successor = null;
                break;
            }
            liveSuccessorChain &= access.fabrolia$pearlSessionReplacementWasLive();
            successor = next;
            cursor = next;
        }
        boolean liveReplacement = liveSuccessorChain
                && successor != null
                && ((successor.isAlive() && !successor.isDeadOrDying())
                    || (com.axalotl.fabrolia.common.parallelised.utils.PlayerDimensionTransition
                            .contains(successor) && !successor.isDeadOrDying()));
        if (!dimensionTransition && !liveReplacement) {
            return vanillaAlive;
        }

        fabrolia$ownerAlivePrevented.increment();
        long now = System.nanoTime();
        long last = fabrolia$ownerAliveLogNanos.get();
        if (now - last > 1_000_000_000L
                && fabrolia$ownerAliveLogNanos.compareAndSet(last, now)) {
            org.slf4j.LoggerFactory.getLogger("Fabrolia-Pearl").warn(
                    "[pearl] prevented owner-wide false-death cleanup for live player {} ({}) "
                            + "removal={} changingDimension={} health={} successor={} thread={} total={}",
                    player.getName().getString(), player.getUUID(), player.getRemovalReason(),
                    player.isChangingDimension(), player.getHealth(),
                    successor == null ? "<dimension-transition>"
                            : successor.getName().getString() + "/" + successor.getUUID(),
                    Thread.currentThread().getName(), fabrolia$ownerAlivePrevented.sum());
        }
        return true;
    }

    /**
     * Renewal cadence under region threading (2026-08-19 margin race; prod
     * probe: island behind=2-5, simLevel=31, holder ticking — the pearl
     * cycled hold→heal every ~20s forever). Vanilla renews every 39 pearl
     * ticks ({@code timeout-1}) vs expiry after 41 purges — margin TWO,
     * safe only because vanilla renews inline on the same thread that
     * purges. A worker-ticked pearl's renewal is deferred to main (slip up
     * to ~2 main ticks) and its island may run a hair behind main — the
     * margin goes negative and the ticket burns to zero every cycle
     * (PearlTicketHold catches it, but the cycle is log noise one unlucky
     * flap from a wedge). Renew at &le;30 pearl ticks while regions are
     * active: margin becomes ~9 purge passes, the slip can no longer reach
     * expiry, and the hold stays as the wedge-only net. Regions off =
     * vanilla 39 (inline renewal is exact there).
     */
    @WrapOperation(method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;registerAndUpdateEnderPearlTicket(Lnet/minecraft/world/entity/projectile/throwableitemprojectile/ThrownEnderpearl;)J"))
    private long fabrolia$tightenPearlRenewal(ServerPlayer owner, ThrownEnderpearl pearl,
                                              Operation<Long> original) {
        long timer = original.call(owner, pearl);
        return com.axalotl.fabrolia.common.regions.RegionScheduler.isActive()
                ? Math.min(timer, 30L) : timer;
    }

    @WrapMethod(method = "onHit(Lnet/minecraft/world/phys/HitResult;)V",
            require = 1, allow = 1)
    private void fabrolia$runCompleteImpactOnMain(HitResult hit, Operation<Void> original) {
        if (Thread.currentThread() instanceof RegionWorkerThread) {
            IslandCommands.runPearlImpactOnMain(
                    (ThrownEnderpearl) (Object) this, () -> original.call(hit));
        } else {
            original.call(hit);
        }
    }

}
