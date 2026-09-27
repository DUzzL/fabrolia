package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionWorkerThread;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.monster.Endermite;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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
 * <p>When the pearl ticks on a region worker and its owner is a live player,
 * the vanilla teleport branch is short-circuited at the
 * {@code isAcceptingMessages} admission check and the remainder of the vanilla
 * player branch (endermite roll, portal cooldown, teleport, damage, sound) is
 * re-run as a continuation on physical Main. The continuation re-validates the
 * owner's listener and lifecycle before acting. Flight and stasis ticks retain
 * their existing island venue.
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
        return RegionScheduler.isActive()
                ? Math.min(timer, 30L) : timer;
    }

    @WrapOperation(method = "onHit",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;isAcceptingMessages()Z"))
    private boolean fabrolia$routePlayerTeleportToMain(ServerGamePacketListenerImpl connection,
                                                       Operation<Boolean> original) {
        if (!(Thread.currentThread() instanceof RegionWorkerThread)) {
            return original.call(connection);
        }

        ThrownEnderpearl self = (ThrownEnderpearl) (Object) this;
        if (!(self.level() instanceof ServerLevel level)
                || !(self.getOwner() instanceof ServerPlayer player)) {
            return original.call(connection);
        }

        if (!original.call(connection)) {
            return false;
        }
        if (!fabrolia$ownerTeleportAllowed(player, level)) {
            return false;
        }

        Vec3 teleportPos = self.oldPosition();
        boolean portalCooldown = self.isOnPortalCooldown();
        float endermiteRoll = self.getRandom().nextFloat();
        Runnable mainContinuation = () -> {
            if (player.connection != connection
                    || !connection.isAcceptingMessages()
                    || !fabrolia$ownerTeleportAllowed(player, level)) {
                throw new IllegalStateException(
                        "accepted deferred pearl teleport lost its player/listener lifecycle: pearl="
                                + self.getUUID()
                                + " owner=" + player.getUUID()
                                + " source=" + level.dimension().identifier()
                                + " current=" + player.level().dimension().identifier());
            }

            if (endermiteRoll < 0.05F
                    && level.isSpawningMonsters()
                    && level.getLevelData().getDifficulty() != Difficulty.PEACEFUL) {
                Endermite endermite = EntityTypes.ENDERMITE.create(level, EntitySpawnReason.TRIGGERED);
                if (endermite != null) {
                    endermite.snapTo(player.getX(), player.getY(), player.getZ(),
                            player.getYRot(), player.getXRot());
                    level.addFreshEntity(endermite);
                }
            }

            if (portalCooldown) {
                player.setPortalCooldown();
            }

            ServerPlayer newOwner = player.teleport(new TeleportTransition(
                    level, teleportPos, Vec3.ZERO, 0.0F, 0.0F,
                    Relative.union(Relative.ROTATION, Relative.DELTA),
                    TeleportTransition.DO_NOTHING));
            if (newOwner != null) {
                newOwner.resetFallDistance();
                newOwner.resetCurrentImpulseContext();
                newOwner.hurtServer(player.level(), self.damageSources().enderPearl(), 5.0F);
            }

            level.playSound(null, teleportPos.x, teleportPos.y, teleportPos.z,
                    SoundEvents.PLAYER_TELEPORT, SoundSource.PLAYERS);
        };
        RegionScheduler.enqueueGlobalMainOnly(mainContinuation);
        return false;
    }

    @Unique
    private static boolean fabrolia$ownerTeleportAllowed(ServerPlayer player, ServerLevel sourceLevel) {
        return player.level() == sourceLevel
                ? player.isAlive() && !player.isSleeping()
                : player.isAlive() && player.canUsePortal(true);
    }

}
