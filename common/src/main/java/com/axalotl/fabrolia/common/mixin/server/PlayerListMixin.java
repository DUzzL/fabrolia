package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.IslandRegistry;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
import com.axalotl.fabrolia.common.regions.FailStops;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;
import java.util.UUID;

/**
 * A2 deadline drive — owner-safe PLAYER DATA saves. playerdata NBT is
 * serialized from the LIVE entity (disconnect, autosave saveAll); with async
 * islands the player's island may be mid-unit — a concurrent tick while
 * saveWithoutId walks inventories/vehicles would tear the NBT. Take the owning
 * island's unit claim for the serialization (blocking: a player save MUST
 * happen; bounded by one unit ≈ tens of ms worst case, and the claim window
 * between units makes the spin rare). Inside a main quiesce window main
 * already holds every claim — proceed directly.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Shadow @Final
    private Map<UUID, ServerPlayer> playersByUUID;

    @Shadow @Final
    private net.minecraft.server.MinecraftServer server;

    @Shadow @Final
    private java.util.List<ServerPlayer> players;

    @org.spongepowered.asm.mixin.Unique
    private static final com.axalotl.fabrolia.common.parallelised.utils.ConcurrentIdentitySet<ServerPlayer>
            fabrolia$activeRemovals =
            new com.axalotl.fabrolia.common.parallelised.utils.ConcurrentIdentitySet<>();

    @org.spongepowered.asm.mixin.Unique
    private static final class FabroliaRemovalContext {
        private static final ThreadLocal<ServerPlayer> CURRENT = new ThreadLocal<>();
    }

    @org.spongepowered.asm.mixin.Unique
    @SuppressWarnings("unchecked")
    private <T> T fabrolia$withPlayerLifecycleLease(
            net.minecraft.world.entity.Entity[] participants,
            java.util.function.Supplier<T> body) {
        var scope = com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                .beginLifecycleMutation(
                        com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                                .LifecycleMutationKind.HARD_EXIT,
                        participants);
        Object[] result = new Object[1];
        Throwable failure = null;
        try {
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> result[0] = body.get());
        } catch (Throwable thrown) {
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        try {
            failure = scope.finish(failure);
        } catch (Throwable cleanup) {
            failure = FailStops.publishMergedFailure(failure, cleanup);
        }
        if (failure == null) {
            try {
                // Scope release is mandatory cleanup.  Its normal return still
                // needs a fixed success-vs-W linearization edge.
                RegionScheduler.commitSuccessIfHealthy(() -> {});
            } catch (Throwable peerFailure) {
                failure = RegionScheduler.publishFatalAndSelectWinner(peerFailure);
            }
        }
        if (failure != null) {
            com.axalotl.fabrolia.common.regions.IslandCommands.publishAndPropagate(failure);
        }
        return (T) result[0];
    }

    @WrapMethod(method = "placeNewPlayer(Lnet/minecraft/network/Connection;"
            + "Lnet/minecraft/server/level/ServerPlayer;"
            + "Lnet/minecraft/server/network/CommonListenerCookie;)V")
    private void fabrolia$leasePlayerSessionPublication(
            net.minecraft.network.Connection connection, ServerPlayer player,
            net.minecraft.server.network.CommonListenerCookie cookie,
            Operation<Void> original) {
        ServerPlayer displaced = this.playersByUUID.get(player.getUUID());
        fabrolia$withPlayerLifecycleLease(
                new net.minecraft.world.entity.Entity[]{player, displaced}, () -> {
                    original.call(connection, player, cookie);
                    return null;
                });
    }

    /**
     * Publish replacement identity at the exact playersByUUID linearization
     * point. A pearl worker may have resolved the displaced object just before
     * this put and use it only later, after PlayerList.remove marks it UWP.
     * The successor field is volatile, unlike the vanilla HashMap.
     */
    @WrapOperation(method = "placeNewPlayer(Lnet/minecraft/network/Connection;"
            + "Lnet/minecraft/server/level/ServerPlayer;"
            + "Lnet/minecraft/server/network/CommonListenerCookie;)V",
            at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
            target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object fabrolia$publishPlayerSessionSuccessor(
            Map<?, ?> sessions, Object key, Object value, Operation<Object> original) {
        Object previous = original.call(sessions, key, value);
        if (previous instanceof ServerPlayer displaced
                && value instanceof ServerPlayer successor
                && displaced != successor) {
            boolean liveHandoff = displaced.isAlive() && !displaced.isDeadOrDying();
            ((com.axalotl.fabrolia.common.parallelised.utils.PearlOwnerSessionAccess) displaced)
                    .fabrolia$publishPearlSessionSuccessor(successor, liveHandoff);
            // Publish the existing live registry immediately. Waiting for the
            // displaced connection's late remove leaves an owner-wide window
            // in which the successor can log out and save an empty set.
            if (liveHandoff) {
                for (ThrownEnderpearl pearl : displaced.getEnderPearls()) {
                    if (!pearl.isRemoved()) {
                        successor.registerEnderPearl(pearl);
                    }
                }
            }
        }
        return previous;
    }

    @org.spongepowered.asm.mixin.Unique
    private boolean fabrolia$isCurrentPlayerSession(
            ServerPlayer player, boolean consumeShadowSave) {
        ServerPlayer current = this.playersByUUID.get(player.getUUID());
        if (current == player) {
            return true;
        }
        var access = (com.axalotl.fabrolia.common.parallelised.utils.PearlOwnerSessionAccess) player;
        if (fabrolia$activeRemovals.contains(player)
                && fabrolia$isDirectCarpetHandoff(player, current)
                && (consumeShadowSave
                    ? access.fabrolia$tryConsumePearlHandoffSave()
                    : !access.fabrolia$pearlHandoffSaveConsumed())) {
            return true;
        }
        com.axalotl.fabrolia.common.parallelised.utils.PearlLoadRecovery
                .stalePlayerSave(player, current);
        return false;
    }

    /** Carpet's /player shadow publishes the fake replacement before the old
     * connection's normal PlayerList.remove arrives. That old save is the
     * intended handoff, not a stale-session overwrite. Keep the exception
     * exact and optional-mod-safe instead of accepting every fake/offline
     * player with the same UUID. */
    @org.spongepowered.asm.mixin.Unique
    private static boolean fabrolia$isCarpetShadowSuccessor(ServerPlayer candidate) {
        if (candidate == null
                || !"carpet.patches.EntityPlayerMPFake".equals(candidate.getClass().getName())) {
            return false;
        }
        try {
            return candidate.getClass().getField("isAShadow").getBoolean(candidate);
        } catch (ReflectiveOperationException | SecurityException | LinkageError failure) {
            com.axalotl.fabrolia.common.regions.IslandCommands
                    .publishAndPropagate(failure);
            return false;
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private static boolean fabrolia$isDirectCarpetHandoff(
            ServerPlayer predecessor, ServerPlayer successor) {
        if (!fabrolia$isCarpetShadowSuccessor(successor)
                || predecessor.isDeadOrDying()) {
            return false;
        }
        net.minecraft.world.entity.Entity.RemovalReason reason = predecessor.getRemovalReason();
        return (reason == null
                || reason == net.minecraft.world.entity.Entity.RemovalReason.DISCARDED)
                && ((com.axalotl.fabrolia.common.parallelised.utils.PearlOwnerSessionAccess)
                        predecessor).fabrolia$pearlSessionSuccessor() == successor;
    }

    /** PlayerList.remove has no own idempotency fence. Serialize one lifecycle
     * object's removal exactly once so two modded/direct calls cannot run two
     * saves and let the second (already-cleared) shadow frame wipe playerdata. */
    @WrapMethod(method = "remove(Lnet/minecraft/server/level/ServerPlayer;)V")
    private void fabrolia$onePlayerRemoval(
            ServerPlayer player, Operation<Void> original) {
        if (!this.server.isSameThread()) {
            // PlayerList.remove is a Main-only exact lifecycle body. Returning
            // now and replaying it from a later TickTask would acknowledge the
            // current caller before save/UWP teardown happened.
            com.axalotl.fabrolia.common.regions.IslandCommands.publishAndPropagate(
                    new IllegalStateException(
                            "PlayerList.remove entered outside physical Main"));
            return;
        }
        var lifecycle = com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                .beginLifecycleMutation(
                        com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                                .LifecycleMutationKind.HARD_EXIT,
                        player);
        var access = (com.axalotl.fabrolia.common.parallelised.utils.PearlOwnerSessionAccess) player;
        Throwable lifecycleFailure = null;
        boolean removalBegan = false;
        boolean activePublished = false;
        boolean contextPublished = false;
        ServerPlayer previousRemoval = null;
        boolean[] completed = new boolean[1];
        boolean tookFence = false;
        try {
            if (!access.fabrolia$beginPlayerListRemoval()) {
                com.axalotl.fabrolia.common.parallelised.utils.PearlLoadRecovery
                        .duplicatePlayerRemoval(player, this.playersByUUID.get(player.getUUID()));
            } else {
                removalBegan = true;
                fabrolia$activeRemovals.add(player);
                activePublished = true;
                previousRemoval = FabroliaRemovalContext.CURRENT.get();
                FabroliaRemovalContext.CURRENT.set(player);
                contextPublished = true;
                // A player owns pearls in arbitrary levels/islands. Claiming only
                // the player's current island cannot make vanilla's
                // saveEnderPearls -> UWP-removal pair atomic: a remote pearl may
                // finish a cross-dimension copy between the weak save iteration
                // and the second getEnderPearls iteration.
                boolean fenceHeld = com.axalotl.fabrolia.common.regions.IslandCommands
                        .fenceWindowHeld();
                if (!fenceHeld && com.axalotl.fabrolia.common.regions.RegionScheduler.isActive()) {
                    tookFence = com.axalotl.fabrolia.common.regions.IslandCommands
                            .quiesceAllExcept(null);
                    fenceHeld = tookFence || com.axalotl.fabrolia.common.regions.IslandCommands
                            .fenceWindowHeld();
                }
                if (fenceHeld) {
                    com.axalotl.fabrolia.common.regions.CrossDimArrivals
                            .registerPendingPearls(player);
                }
                RegionScheduler.runSuccessBodyIfHealthy(() -> {
                    original.call(player);
                    completed[0] = true;
                });
            }
        } catch (Throwable thrown) {
            lifecycleFailure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        if (contextPublished) {
            try {
                if (previousRemoval == null) {
                    FabroliaRemovalContext.CURRENT.remove();
                } else {
                    FabroliaRemovalContext.CURRENT.set(previousRemoval);
                }
            } catch (Throwable cleanup) {
                lifecycleFailure = FailStops.publishMergedFailure(lifecycleFailure, cleanup);
            }
        }
        if (activePublished) {
            try {
                fabrolia$activeRemovals.remove(player);
            } catch (Throwable cleanup) {
                lifecycleFailure = FailStops.publishMergedFailure(lifecycleFailure, cleanup);
            }
        }
        if (removalBegan) {
            try {
                if (completed[0]) {
                    RegionScheduler.runSuccessBodyIfHealthy(
                            () -> access.fabrolia$finishPlayerListRemoval(true));
                } else {
                    // Failed-body terminal cleanup is mandatory and is not a
                    // normal lifecycle-success publication.
                    access.fabrolia$finishPlayerListRemoval(false);
                }
            } catch (Throwable cleanup) {
                lifecycleFailure = FailStops.publishMergedFailure(lifecycleFailure, cleanup);
            }
        }
        if (tookFence) {
            lifecycleFailure = com.axalotl.fabrolia.common.regions.IslandCommands
                    .releaseAllExcept(lifecycleFailure);
        }
        try {
            lifecycleFailure = lifecycle.finish(lifecycleFailure);
        } catch (Throwable cleanup) {
            lifecycleFailure = FailStops.publishMergedFailure(lifecycleFailure, cleanup);
        }
        if (lifecycleFailure == null) {
            try {
                RegionScheduler.commitSuccessIfHealthy(() -> {});
            } catch (Throwable peerFailure) {
                lifecycleFailure = RegionScheduler.publishFatalAndSelectWinner(peerFailure);
            }
        }
        if (lifecycleFailure != null) {
            com.axalotl.fabrolia.common.regions.IslandCommands
                    .publishAndPropagate(lifecycleFailure);
        }
    }

    @WrapMethod(method = "respawn(Lnet/minecraft/server/level/ServerPlayer;Z"
            + "Lnet/minecraft/world/entity/Entity$RemovalReason;)"
            + "Lnet/minecraft/server/level/ServerPlayer;")
    private ServerPlayer fabrolia$leasePlayerRespawn(
            ServerPlayer oldPlayer, boolean keepEverything,
            net.minecraft.world.entity.Entity.RemovalReason reason,
            Operation<ServerPlayer> original) {
        return fabrolia$withPlayerLifecycleLease(
                new net.minecraft.world.entity.Entity[]{oldPlayer},
                () -> original.call(oldPlayer, keepEverything, reason));
    }

    /**
     * A late old-session remove must not remove the live world's canonical
     * pearls after the replacement session has already adopted them. Vanilla
     * obtains this set after save and marks every member
     * UNLOADED_WITH_PLAYER; returning the old set here would make all pearls
     * disappear even though the stale disk write was blocked. Normal logout
     * still sees the exact player in playersByUUID and stays bit-for-bit
     * vanilla. Carpet shadow is deliberately protected by the same rule.
     */
    @WrapOperation(method = "remove(Lnet/minecraft/server/level/ServerPlayer;)V",
            at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;getEnderPearls()Ljava/util/Set;"))
    private java.util.Set<ThrownEnderpearl> fabrolia$staleSessionCannotUnloadCanonicalPearls(
            ServerPlayer removing, Operation<java.util.Set<ThrownEnderpearl>> original) {
        java.util.Set<ThrownEnderpearl> pearls = original.call(removing);
        ServerPlayer current = this.playersByUUID.get(removing.getUUID());
        if (current == removing) {
            // Couple UWP removal to the exact identities that the immediately
            // preceding playerdata write serialized. Any pearl registered or
            // cross-dimension-copied after that snapshot remains a normal
            // world-persistable entity instead of being removed unsaved.
            java.util.Set<ThrownEnderpearl> saved =
                    ((com.axalotl.fabrolia.common.parallelised.utils.PearlOwnerSessionAccess)
                            removing).fabrolia$pearlLogoutSnapshot();
            return saved == null ? java.util.Collections.emptySet() : saved;
        }
        if (current == null) {
            // Map removal occurs AFTER this vanilla loop, so null can never be
            // the first, current session's normal logout. It is a stale/modded
            // removal and has no authority to delete identities which were not
            // proven durable by this exact removal's save. In particular, a
            // worker paused in registerEnderPearl used to be caught here and
            // UWP-removed without ever entering playerdata.
            java.util.Set<ThrownEnderpearl> saved =
                    ((com.axalotl.fabrolia.common.parallelised.utils.PearlOwnerSessionAccess)
                            removing).fabrolia$pearlLogoutSnapshot();
            if (saved == null) {
                com.axalotl.fabrolia.common.parallelised.utils.PearlLoadRecovery
                        .stalePlayerPearlRemoval(removing, null, 0);
                return java.util.Collections.emptySet();
            }
            return saved;
        }
        int handedOff = 0;
        if (current != null) {
            // Belt for replacements published by a mod through a path other
            // than vanilla placeNewPlayer. Publish before old is marked UWP so
            // a pearl tick already holding `removing` cannot call that unload
            // a death after this method returns.
            ((com.axalotl.fabrolia.common.parallelised.utils.PearlOwnerSessionAccess) removing)
                    .fabrolia$publishPearlSessionSuccessor(current,
                            !removing.isDeadOrDying()
                                    && (removing.getRemovalReason() == null
                                        || removing.getRemovalReason()
                                            == net.minecraft.world.entity.Entity.RemovalReason.DISCARDED
                                        || removing.getRemovalReason()
                                            == net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_WITH_PLAYER));
            for (ThrownEnderpearl pearl : pearls) {
                if (!pearl.isRemoved()) {
                    current.registerEnderPearl(pearl);
                    handedOff++;
                }
            }
        }
        // PlayerList.remove is about to iterate the returned set and mark each
        // member UWP. Detach the stale registry after publishing every live
        // canonical into the replacement so that loop cannot erase them.
        pearls.clear();
        com.axalotl.fabrolia.common.parallelised.utils.PearlLoadRecovery
                .stalePlayerPearlRemoval(removing, current, handedOff);
        return java.util.Collections.emptySet();
    }

    /**
     * Autosave, plain save-all and reload: the snapshots are taken here and the
     * files are written by {@link com.axalotl.fabrolia.common.save.AsyncSaveWriter};
     * {@code save-all flush} and shutdown wait for them in saveAllChunks.
     * Every island the pass claims keeps Main's claim interest for the whole
     * pass ({@link com.axalotl.fabrolia.common.regions.IslandSaveGroup}), so no
     * unit runs between two saves of one island's players.
     */
    @WrapMethod(method = "saveAll()V", require = 1, allow = 1)
    private void fabrolia$deferPlayerFileWrites(Operation<Void> original) {
        com.axalotl.fabrolia.common.save.SaveDurability.runDeferred(() -> {
            com.axalotl.fabrolia.common.regions.IslandSaveGroup group =
                    com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                                    .isPhysicalServerThread(this.server)
                            ? com.axalotl.fabrolia.common.regions.IslandSaveGroup.open(
                                    this.players, player -> fabrolia$saveClaimOwner(player))
                            : null;
            if (group == null) {
                original.call();
                return;
            }
            Throwable failure = null;
            try {
                original.call();
            } catch (Throwable thrown) {
                failure = thrown;
            }
            // Mandatory withdrawal, then the body's own failure raw (a failed
            // withdrawal arrives published).
            failure = group.close(failure);
            if (failure != null) {
                FailStops.<RuntimeException>rethrow(failure);
            }
        });
    }

    /** The island whose claim {@code save(player)} takes, or null if it takes none. */
    @org.spongepowered.asm.mixin.Unique
    private static IslandRegistry.PIsland fabrolia$saveClaimOwner(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel sl)) return null;
        RegionizedServer rs = RegionScheduler.forLevel(sl);
        if (rs == null || !rs.asyncActive || rs.currentThreadHoldsAllClaims()) return null;
        IslandRegistry.PIsland owner = rs.islandOwnerOfChunk(player.chunkPosition().pack());
        return owner == null
                || com.axalotl.fabrolia.common.regions.IslandCommands.mainHolds(owner)
                ? null : owner;
    }

    @WrapMethod(method = "save(Lnet/minecraft/server/level/ServerPlayer;)V")
    private void fabrolia$ownerSafePlayerSave(ServerPlayer player, Operation<Void> original) {
        // Outside saveAll (logout, mods) the files must be on disk when save
        // returns, as in vanilla; wait for them after the owner claim is gone.
        com.axalotl.fabrolia.common.save.SaveDurability.Batch batch =
                com.axalotl.fabrolia.common.save.SaveDurability.openBatch(
                        FabroliaRemovalContext.CURRENT.get() == player);
        try {
            fabrolia$ownerSafePlayerSaveBody(player, original);
        } finally {
            batch.close();
        }
        batch.awaitDurable();
    }

    @org.spongepowered.asm.mixin.Unique
    private void fabrolia$ownerSafePlayerSaveBody(ServerPlayer player, Operation<Void> original) {
        // PlayerList.remove saves before removing the current map entry.  A
        // second remove sees null; a late old-session remove sees the new
        // ServerPlayer.  In both cases writing the stale object's now-empty
        // enderPearls list would atomically replace the current .dat and wipe
        // every saved pearl (as well as risking stale inventory/position data).
        if (!fabrolia$isCurrentPlayerSession(player, false)) {
            return;
        }
        if (player.level() instanceof ServerLevel sl) {
            RegionizedServer rs = RegionScheduler.forLevel(sl);
            if (rs != null && rs.asyncActive
                    && !rs.currentThreadHoldsAllClaims()) {
                IslandRegistry.PIsland owner =
                        rs.islandOwnerOfChunk(player.chunkPosition().pack());
                if (owner != null) {
                    boolean release =
                            com.axalotl.fabrolia.common.regions.IslandCommands.mainClaimScoped(owner);
                    Throwable failure = null;
                    try {
                        // The claim wait can outlive this session.  Revalidate at
                        // the actual disk-write linearization point so an old
                        // player cannot overwrite a newly logged-in instance.
                        RegionScheduler.runSuccessBodyIfHealthy(() -> {
                            if (fabrolia$isCurrentPlayerSession(player, true)) {
                                fabrolia$writePlayerAndCaptureLogoutPearls(player, original);
                            }
                        });
                    } catch (Throwable thrown) {
                        failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
                    }
                    if (release) {
                        try {
                            com.axalotl.fabrolia.common.regions.IslandCommands.mainRelease(owner);
                        } catch (Throwable cleanup) {
                            failure = FailStops.publishMergedFailure(failure, cleanup);
                        }
                    }
                    if (failure == null) {
                        try {
                            RegionScheduler.commitSuccessIfHealthy(() -> {});
                        } catch (Throwable peerFailure) {
                            failure = RegionScheduler.publishFatalAndSelectWinner(peerFailure);
                        }
                    }
                    if (failure != null) {
                        com.axalotl.fabrolia.common.regions.IslandCommands
                                .publishAndPropagate(failure);
                    }
                    return;
                }
            }
        }
        if (fabrolia$isCurrentPlayerSession(player, true)) {
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> fabrolia$writePlayerAndCaptureLogoutPearls(player, original));
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private static void fabrolia$writePlayerAndCaptureLogoutPearls(
            ServerPlayer player, Operation<Void> original) {
        if (FabroliaRemovalContext.CURRENT.get() != player) {
            RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(player));
            return;
        }
        var frame = com.axalotl.fabrolia.common.parallelised.utils.PearlLogoutSaveCapture
                .begin(player);
        boolean succeeded = false;
        Throwable failure = null;
        try {
            RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(player));
            succeeded = true;
        } catch (Throwable thrown) {
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        java.util.Set<ThrownEnderpearl> snapshot = null;
        try {
            snapshot =
                    com.axalotl.fabrolia.common.parallelised.utils.PearlLogoutSaveCapture
                            .finish(frame, succeeded);
        } catch (Throwable cleanup) {
            failure = FailStops.publishMergedFailure(failure, cleanup);
        }
        if (failure == null && succeeded) {
            try {
                java.util.Set<ThrownEnderpearl> committedSnapshot = snapshot;
                RegionScheduler.commitSuccessIfHealthy(() ->
                        ((com.axalotl.fabrolia.common.parallelised.utils.PearlOwnerSessionAccess) player)
                                .fabrolia$publishPearlLogoutSnapshot(committedSnapshot));
            } catch (Throwable publicationFailure) {
                failure = RegionScheduler.publishFatalAndSelectWinner(publicationFailure);
            }
        }
        if (failure != null) {
            com.axalotl.fabrolia.common.regions.IslandCommands
                    .publishAndPropagate(failure);
        }
    }
}
