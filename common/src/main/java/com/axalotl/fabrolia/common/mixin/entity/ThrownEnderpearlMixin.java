package com.axalotl.fabrolia.common.mixin.entity;

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
 * <p>Seam: the whole player branch of {@code onHit} is guarded by
 * {@code player.connection.isAcceptingMessages()}. On a region worker this
 * wrap returns {@code false} — vanilla skips the branch and immediately
 * {@code discard()}s the pearl on its own island (worker-legal, exactly-once)
 * — and the guarded block is replayed verbatim on the main task queue:
 * endermite roll, portal-cooldown propagation, teleport, fall-reset, impulse
 * reset, 5.0 ender-pearl damage, teleport sound. State captured at hit time:
 * teleport position (pearl's oldPosition) and portal-cooldown flag; the
 * accepting-messages/alive guards re-run ON MAIN so a disconnect between hit
 * and replay cleanly voids the teleport (vanilla's own guard semantics, just
 * evaluated at the execution site).
 *
 * <p>Non-player owners (mobs) keep the vanilla inline path — mob owners are
 * region entities and their teleport goes through the entity-motion machinery,
 * not player tracking.
 */
@Mixin(ThrownEnderpearl.class)
public abstract class ThrownEnderpearlMixin {

    @WrapOperation(method = "onHit",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;isAcceptingMessages()Z"))
    private boolean fabrolia$routePlayerTeleportToMain(ServerGamePacketListenerImpl connection,
                                                       Operation<Boolean> original) {
        if (!(Thread.currentThread() instanceof RegionWorkerThread)) {
            return original.call(connection);
        }
        ThrownEnderpearl self = (ThrownEnderpearl) (Object) this;
        if (!(self.level() instanceof ServerLevel level)) {
            return original.call(connection);
        }
        if (!(self.getOwner() instanceof ServerPlayer player)) {
            return original.call(connection);
        }
        final Vec3 teleportPos = self.oldPosition();
        final boolean portalCooldown = self.isOnPortalCooldown();
        final float endermiteRoll = self.getRandom().nextFloat();
        level.getServer().execute(() -> {
            // Vanilla isAllowedToTeleportOwner semantics, re-evaluated on main:
            // same dimension -> alive && not sleeping; DIFFERENT dimension ->
            // canUsePortal(true) — the pearl-through-portal case is LEGAL and
            // must teleport the owner across dimensions (2026-07-21 regression:
            // a same-level guard here silently dropped exactly that case).
            boolean allowed = player.level() == level
                    ? (player.isAlive() && !player.isSleeping())
                    : (player.isAlive() && player.canUsePortal(true));
            if (!player.connection.isAcceptingMessages() || !allowed) {
                org.slf4j.LoggerFactory.getLogger("Fabrolia-Pearl").warn(
                        "[pearl] deferred owner-teleport declined: pearl {} owner {} src-dim {} "
                        + "owner-dim {} accepting={} alive={} sleeping={} canUsePortal={}",
                        self.getUUID(), player.getName().getString(),
                        level.dimension().identifier(), player.level().dimension().identifier(),
                        player.connection.isAcceptingMessages(), player.isAlive(),
                        player.isSleeping(), player.canUsePortal(true));
                return;
            }
            if (endermiteRoll < 0.05F && level.isSpawningMonsters()
                    && level.getLevelData().getDifficulty() != Difficulty.PEACEFUL) {
                Endermite endermite = (Endermite) EntityTypes.ENDERMITE.create(level, EntitySpawnReason.TRIGGERED);
                if (endermite != null) {
                    endermite.snapTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
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
        });
        return false;
    }
}
