package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.api.utils.ConcurrentCollections;
import com.axalotl.fabrolia.common.regions.IslandEntityTracking;
import com.axalotl.fabrolia.common.regions.IslandTrackedEntityAccess;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.TrackedEntityPassAccess;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceLinkedOpenHashMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Mixin(value = ChunkMap.TrackedEntity.class)
public abstract class ChunkMapTrackedEntityMixin
        implements com.axalotl.fabrolia.common.regions.IslandTrackedEntityAccess,
        TrackedEntityPassAccess {

    /** Written under the tracker monitor; volatile for the tracker tick's lock-free reads. */
    @Unique
    private volatile int fabrolia$islandTrackingState;

    /** Written under the tracker monitor; volatile for the tracker tick's lock-free reads. */
    @Unique
    private volatile com.axalotl.fabrolia.common.regions.IslandRegistry.PIsland
            fabrolia$islandTrackingOwner;

    /**
     * Staged membership work visible to the lock-free tracker tick: pending
     * records + the bulk bit + an in-flight commit. Republished under the monitor
     * after every change; zero proves no owner-tail commit can still change seenBy.
     */
    @Unique
    private volatile int fabrolia$stagedMembershipWork;

    @Unique
    private int fabrolia$membershipCommitDepth;

    /** Tracker-tick snapshot; written by the pre-pass, read after its join. */
    @Unique
    private Vec3 fabrolia$passPreviousPos;

    @Unique
    private Vec3 fabrolia$passPos;

    @Unique
    private boolean fabrolia$passMoved;

    @Unique
    private int fabrolia$passRange;

    @Unique
    private long fabrolia$passSectionChunk;

    @Unique
    private AtomicLong fabrolia$passTickClaim;

    /** Position + 1 in the VMP tracker's dense area-index mirror; 0 = absent.
     *  Guarded by the tracker index lock. */
    @Unique
    private int fabrolia$denseSlot;

    /**
     * Set when VMP's removeEntityTracker is entered for this generation, before
     * the removal is queued or applied: a concurrent (or queued) insertion into
     * the area index observed after it must not resurrect the tracker.
     */
    @Unique
    private volatile boolean fabrolia$vmpRemoved;

    @Unique
    private boolean fabrolia$mainTrackingScheduled;

    @Unique
    private long fabrolia$mainTrackingScheduleSequence;

    @Unique
    private long fabrolia$mainTrackingScheduleToken;

    @Unique
    private boolean fabrolia$pendingBulkPairingRefresh;

    @Unique
    private int fabrolia$lastMainTrackingServerTick;

    @Unique
    private long fabrolia$islandTrackingPublicationGeneration;

    /**
     * Insertion-ordered FIFO of at most one staged obligation per viewer
     * identity (never by Entity.equals: a respawned ServerPlayer keeps its id).
     * Membership is coalesced per identity, so viewer removal must be O(1);
     * one player in a dense mob crowd removes from thousands of trackers.
     */
    @Unique
    private Reference2ReferenceLinkedOpenHashMap<ServerPlayer,
            com.axalotl.fabrolia.common.regions.IslandPendingPairing>
            fabrolia$pendingIslandPairings;

    @Shadow
    private final Set<ServerGamePacketListenerImpl> seenBy = ConcurrentCollections.newHashSet();

    @Shadow
    @Final
    public Entity entity;

    @Shadow @Final private ServerEntity serverEntity;

    @Shadow public abstract void updatePlayer(ServerPlayer player);

    @Shadow public abstract void removePlayer(ServerPlayer player);

    @Shadow public abstract void updatePlayers(List<ServerPlayer> players);

    @Shadow @Final private int range;

    @Shadow private SectionPos lastSectionPos;

    @Shadow
    private int getEffectiveRange() {
        throw new AssertionError("shadow");
    }

    @Shadow
    private int scaledRange(int range) {
        throw new AssertionError("shadow");
    }

    /**
     * Mixin does not merge the Java instance-initializer body into the target
     * constructor.  Initialise every non-default tracker value from an exact
     * target-constructor injection so a freshly created tracker can stage its
     * first login before any publication path observes it.
     */
    @Inject(
            method = "<init>(Lnet/minecraft/server/level/ChunkMap;"
                    + "Lnet/minecraft/world/entity/Entity;ILnet/minecraft/world/entity/UpdateInterval;Z)V",
            at = @At("RETURN"),
            require = 1)
    private void fabrolia$initializeIslandTrackingState(CallbackInfo ci) {
        this.fabrolia$islandTrackingState =
                IslandTrackedEntityAccess.TRACKING_ACTIVE;
        this.fabrolia$lastMainTrackingServerTick = Integer.MIN_VALUE;
        this.fabrolia$pendingIslandPairings = new Reference2ReferenceLinkedOpenHashMap<>(2);
        this.fabrolia$passTickClaim = new AtomicLong();
        IslandEntityTracking.registerPlayerTracker(this);
    }

    /** Caller holds this tracker's monitor. */
    @Unique
    private void fabrolia$publishStagedMembershipWork() {
        this.fabrolia$stagedMembershipWork =
                (this.fabrolia$pendingBulkPairingRefresh ? 1 : 0)
                        + this.fabrolia$pendingIslandPairings.size()
                        + this.fabrolia$membershipCommitDepth;
    }

    @Override
    public Entity fabrolia$trackedEntity() {
        return this.entity;
    }

    @Override
    public synchronized int fabrolia$islandTrackingState() {
        return this.fabrolia$islandTrackingState;
    }

    @Override
    public synchronized boolean fabrolia$hasIslandTrackingViewers() {
        return !this.seenBy.isEmpty();
    }

    @Override
    public synchronized boolean fabrolia$hasPendingIslandPairings() {
        return this.fabrolia$pendingBulkPairingRefresh
                || !this.fabrolia$pendingIslandPairings.isEmpty();
    }

    @Override
    public synchronized boolean fabrolia$queueIslandBulkPairingRefresh() {
        if (this.fabrolia$islandTrackingState
                == IslandTrackedEntityAccess.TRACKING_RETIRED) return false;
        this.fabrolia$pendingBulkPairingRefresh = true;
        this.fabrolia$publishStagedMembershipWork();
        return true;
    }

    @Override
    public synchronized boolean fabrolia$queueIslandPairing(ServerPlayer player) {
        if (this.fabrolia$islandTrackingState
                == IslandTrackedEntityAccess.TRACKING_RETIRED
                || player == null || player == this.entity
                || player.connection == null) return false;
        com.axalotl.fabrolia.common.regions.IslandPendingPairing.Intent intent =
                this.seenBy.contains(player.connection)
                        ? com.axalotl.fabrolia.common.regions.IslandPendingPairing.Intent.REVALIDATE
                        : com.axalotl.fabrolia.common.regions.IslandPendingPairing.Intent.ADD;
        com.axalotl.fabrolia.common.regions.IslandPendingPairing staged =
                this.fabrolia$pendingIslandPairings.get(player);
        if (staged == null || !staged.matchesExactly(player)) {
            // Defensive only: vanilla assigns ServerPlayer.connection once per
            // instance and every stager authenticates connection.player, so a
            // record of another session for this identity is not reachable
            // from vanilla. Should a mod rebind the listener in place, the
            // stale record would be dropped at commit anyway; the fresh
            // session takes the tail like any new obligation.
            this.fabrolia$pendingIslandPairings.putAndMoveToLast(player,
                    new com.axalotl.fabrolia.common.regions.IslandPendingPairing(
                            player, player.connection, intent));
        } else if (staged.intent() != intent) {
            // Same queue position: the session's obligation was already ordered.
            this.fabrolia$pendingIslandPairings.put(player,
                    new com.axalotl.fabrolia.common.regions.IslandPendingPairing(
                            player, player.connection, intent));
        }
        this.fabrolia$publishStagedMembershipWork();
        return true;
    }

    @Override
    public synchronized boolean fabrolia$queueIslandRemoval(ServerPlayer player) {
        if (this.fabrolia$islandTrackingState
                == IslandTrackedEntityAccess.TRACKING_RETIRED
                || player == null || player.connection == null) return false;
        this.fabrolia$pendingIslandPairings.remove(player);
        if (this.seenBy.contains(player.connection)) {
            this.fabrolia$pendingIslandPairings.put(player,
                    new com.axalotl.fabrolia.common.regions.IslandPendingPairing(
                            player, player.connection,
                            com.axalotl.fabrolia.common.regions.IslandPendingPairing.Intent.REMOVE));
        }
        this.fabrolia$publishStagedMembershipWork();
        return true;
    }

    @Override
    public synchronized void fabrolia$cancelIslandPairing(ServerPlayer player) {
        this.fabrolia$pendingIslandPairings.remove(player);
        this.fabrolia$publishStagedMembershipWork();
    }

    @Override
    public synchronized void fabrolia$commitIslandPairings() {
        // Nothing staged: provably a no-op. Do not publish an in-flight commit,
        // which would make every tail publication defeat the tracker tick's filter.
        if (!this.fabrolia$pendingBulkPairingRefresh
                && this.fabrolia$pendingIslandPairings.isEmpty()) return;
        // The lock-free tracker tick may skip a no-op updatePlayer only while no
        // commit can still change seenBy; an in-flight commit counts as staged work.
        this.fabrolia$membershipCommitDepth++;
        this.fabrolia$publishStagedMembershipWork();
        try {
            if (this.fabrolia$islandTrackingState
                    == IslandTrackedEntityAccess.TRACKING_RETIRED) {
                this.fabrolia$pendingBulkPairingRefresh = false;
                this.fabrolia$pendingIslandPairings.clear();
                return;
            }
            if (this.fabrolia$pendingBulkPairingRefresh
                    && this.entity.level() instanceof ServerLevel level) {
                // Keep the bit published until the exact transformed bulk method
                // finishes. Success consumes it after expanding the current
                // SnapshotList under owner/tracker authority. Failure escapes
                // immediately; this method neither catches it nor runs/requeues a
                // recovery body. The pre-existing bit is consumed only on success.
                IslandEntityTracking.commitBulkPairingRefresh(this,
                        () -> this.updatePlayers(level.players()));
                this.fabrolia$pendingBulkPairingRefresh = false;
            }
            // Try each identity at most once per publication generation. A normal
            // exact-range ADD decline is terminal: installed VMP's staging window
            // calls updatePlayers for every coarse candidate again, and retaining
            // every decline would turn 12k trackers x 100 players into a permanent
            // pending-record/Main-task storm. A transformed body failure escapes
            // immediately and therefore never advances to another pending identity.
            int attempts = this.fabrolia$pendingIslandPairings.size();
            for (int attempt = 0; attempt < attempts; attempt++) {
                boolean consumed = RegionScheduler.callSuccessBooleanIfHealthy(() -> {
                    if (this.fabrolia$pendingIslandPairings.isEmpty()) return false;
                    // Detach, validate and enter the exact transformed body under
                    // one admission. commitPairingInAdmittedBody deliberately has
                    // no nested W gate which could refuse this already-owned item.
                    com.axalotl.fabrolia.common.regions.IslandPendingPairing pending =
                            this.fabrolia$pendingIslandPairings.removeFirst();
                    boolean removal = pending.intent()
                            == com.axalotl.fabrolia.common.regions.IslandPendingPairing.Intent.REMOVE;
                    if (removal) {
                        // Desired-ABSENT remains valid after disconnect, respawn or a
                        // dimension move. Those lifecycle edges are exactly when VMP
                        // invokes removePlayer and the old listener can still be in
                        // seenBy. Only the captured player/listener identity matters.
                        if (!pending.matchesRemovalPlayer(pending.player())) return true;
                    } else if (!pending.matchesExactly(pending.player())
                            || pending.player().isRemoved()
                            || pending.player().hasDisconnected()
                            || this.entity.isRemoved()
                            || pending.player().level() != this.entity.level()) {
                        return true;
                    }
                    // Re-enter the exact transformed vanilla/VMP updatePlayer only at
                    // the owner tail. It revalidates range, visibility and chunk
                    // tracking, then preserves seenBy -> bundle -> callback -> debug
                    // order. The commit scope suppresses VMP's redundant pre-add
                    // tickAlways because the cache refresh just completed above.
                    if (removal) {
                        IslandEntityTracking.commitPairingInAdmittedBody(
                                this, pending,
                                () -> this.fabrolia$removeCapturedPairing(pending));
                    } else {
                        IslandEntityTracking.commitPairingInAdmittedBody(
                                this, pending, () -> this.updatePlayer(pending.player()));
                    }
                    if (fabrolia$mustRetry(pending)
                            && this.fabrolia$islandTrackingState
                            != IslandTrackedEntityAccess.TRACKING_RETIRED) {
                        // This is a post-body successor, not cleanup. A peer W
                        // selected while the transformed body ran forbids re-arm.
                        RegionScheduler.runSuccessBodyIfHealthy(
                                () -> this.fabrolia$restagePairing(pending));
                    }
                    return true;
                });
                if (!consumed) break;
            }
        } finally {
            this.fabrolia$membershipCommitDepth--;
            this.fabrolia$publishStagedMembershipWork();
        }
    }

    /**
     * Re-arms a detached REMOVE at the tail. Every stager validates that the
     * viewer's session is current, and a staged ADD never re-enters seenBy, so
     * a second record for the retried identity is a broken invariant.
     */
    @Unique
    private void fabrolia$restagePairing(
            com.axalotl.fabrolia.common.regions.IslandPendingPairing pending) {
        if (this.fabrolia$pendingIslandPairings.putIfAbsent(
                pending.player(), pending) != null) {
            throw new IllegalStateException(
                    "island pairing retry found a second record for its viewer identity");
        }
    }

    @Unique
    private boolean fabrolia$mustRetry(
            com.axalotl.fabrolia.common.regions.IslandPendingPairing pending) {
        return switch (pending.intent()) {
            case ADD, REVALIDATE -> false;
            case REMOVE -> this.seenBy.contains(pending.listener());
        };
    }

    /**
     * Uses vanilla's transformed removePlayer while the captured listener is
     * still current. If a respawn already rebound the player, remove exactly
     * the old listener instead of accidentally removing/sending to the new
     * session.
     */
    @Unique
    private void fabrolia$removeCapturedPairing(
            com.axalotl.fabrolia.common.regions.IslandPendingPairing pending) {
        if (pending.player().connection == pending.listener()) {
            this.removePlayer(pending.player());
            return;
        }
        this.fabrolia$removeCapturedPairingDirect(pending);
    }

    @Unique
    private void fabrolia$removeCapturedPairingDirect(
            com.axalotl.fabrolia.common.regions.IslandPendingPairing pending) {
        if (!this.seenBy.remove(pending.listener())) return;
        // Preserve the transformed vanilla order and failure boundary exactly.
        // If a callback throws, packet/debug publication after that callback did
        // not happen in vanilla either and must not be invented as recovery work.
        this.entity.stopSeenByPlayer(pending.player());
        pending.listener().send(new ClientboundRemoveEntitiesPacket(
                this.entity.getId()));
        if (this.seenBy.isEmpty() && this.entity.level() instanceof ServerLevel level) {
            level.debugSynchronizers().dropEntity(this.entity);
        }
    }

    @Override
    public synchronized com.axalotl.fabrolia.common.regions.IslandRegistry.PIsland
    fabrolia$islandTrackingOwner() {
        return this.fabrolia$islandTrackingOwner;
    }

    @Override
    public synchronized void fabrolia$setIslandTrackingOwner(
            com.axalotl.fabrolia.common.regions.IslandRegistry.PIsland island) {
        this.fabrolia$islandTrackingOwner = island;
    }

    @Override
    public synchronized void fabrolia$deactivateIslandTracking() {
        this.fabrolia$islandTrackingState = IslandTrackedEntityAccess.TRACKING_RETIRED;
        this.fabrolia$mainTrackingScheduled = false;
        this.fabrolia$mainTrackingScheduleToken = 0L;
        this.fabrolia$pendingBulkPairingRefresh = false;
        this.fabrolia$pendingIslandPairings.clear();
        this.fabrolia$publishStagedMembershipWork();
    }

    @Override
    public synchronized void fabrolia$quarantineIslandTracking() {
        if (this.fabrolia$islandTrackingState == IslandTrackedEntityAccess.TRACKING_ACTIVE) {
            this.fabrolia$islandTrackingState =
                    IslandTrackedEntityAccess.TRACKING_QUARANTINED;
        }
    }

    @Override
    public synchronized long fabrolia$tryScheduleMainTracking() {
        if (this.fabrolia$islandTrackingState
                == IslandTrackedEntityAccess.TRACKING_RETIRED
                || this.fabrolia$mainTrackingScheduled) return 0L;
        long token = ++this.fabrolia$mainTrackingScheduleSequence;
        if (token == 0L) token = ++this.fabrolia$mainTrackingScheduleSequence;
        this.fabrolia$mainTrackingScheduleToken = token;
        this.fabrolia$mainTrackingScheduled = true;
        return token;
    }

    @Override
    public synchronized void fabrolia$finishMainTrackingSchedule(long token) {
        if (token == 0L || this.fabrolia$mainTrackingScheduleToken != token) return;
        this.fabrolia$mainTrackingScheduleToken = 0L;
        this.fabrolia$mainTrackingScheduled = false;
    }

    @Override
    public synchronized boolean fabrolia$tryBeginMainTrackingPublication(
            int serverTick) {
        if (this.fabrolia$lastMainTrackingServerTick == serverTick) return false;
        this.fabrolia$lastMainTrackingServerTick = serverTick;
        return true;
    }

    @Override
    public synchronized long fabrolia$islandTrackingPublicationGeneration() {
        return this.fabrolia$islandTrackingPublicationGeneration;
    }

    @Override
    public synchronized void fabrolia$markIslandTrackingPublished() {
        this.fabrolia$islandTrackingPublicationGeneration++;
    }

    @Override
    public synchronized boolean fabrolia$sendIslandChanges() {
        if (this.fabrolia$islandTrackingState
                == IslandTrackedEntityAccess.TRACKING_RETIRED) return false;
        long before = this.fabrolia$islandTrackingPublicationGeneration;
        this.serverEntity.sendChanges();
        return this.fabrolia$islandTrackingPublicationGeneration != before;
    }

    @Override
    public boolean fabrolia$passSnapshot(boolean areaChunkChanged) {
        Entity tracked = this.entity;
        Vec3 pos = tracked.position();
        Vec3 previous = this.fabrolia$passPreviousPos;
        // The area chunk was read before this position: a re-indexed tracker
        // counts as moved even if the snapshot position already matches.
        boolean moved = areaChunkChanged || previous == null || pos.x != previous.x
                || pos.y != previous.y || pos.z != previous.z;
        this.fabrolia$passPreviousPos = pos;
        this.fabrolia$passPos = pos;
        this.fabrolia$passMoved = moved;
        // Vanilla updatePlayer's range for this tick (installed VMP caches the
        // same getEffectiveRange value per server tick).
        this.fabrolia$passRange = tracked.getPassengers().isEmpty()
                ? this.scaledRange(this.range) : this.getEffectiveRange();
        BlockPos block = tracked.blockPosition();
        int sectionX = SectionPos.blockToSectionCoord(block.getX());
        int sectionY = SectionPos.blockToSectionCoord(block.getY());
        int sectionZ = SectionPos.blockToSectionCoord(block.getZ());
        SectionPos last = this.lastSectionPos;
        if (last == null || last.x() != sectionX || last.y() != sectionY
                || last.z() != sectionZ) {
            this.lastSectionPos = SectionPos.of(sectionX, sectionY, sectionZ);
        }
        this.fabrolia$passSectionChunk = ChunkPos.pack(sectionX, sectionZ);
        return moved;
    }

    @Override
    public boolean fabrolia$passMoved() {
        return this.fabrolia$passMoved;
    }

    @Override
    public long fabrolia$passSectionChunk() {
        return this.fabrolia$passSectionChunk;
    }

    @Override
    public boolean fabrolia$passClaimTick(long stamp) {
        AtomicLong claim = this.fabrolia$passTickClaim;
        long seen = claim.get();
        return seen != stamp && claim.compareAndSet(seen, stamp);
    }

    /**
     * Exact vanilla visibility (installed VMP redirects isChunkTracked to true)
     * on this tick's snapshots. Any later move of either side is a move on the
     * next tick, which re-evaluates the pair.
     */
    @Override
    public boolean fabrolia$passPairingCurrent(ServerPlayer viewer, Vec3 viewerPos,
                                               int viewDistance) {
        if (this.fabrolia$islandTrackingState != IslandTrackedEntityAccess.TRACKING_ACTIVE
                || this.fabrolia$stagedMembershipWork != 0) return false;
        Entity tracked = this.entity;
        if (viewer == tracked) return true;
        ServerGamePacketListenerImpl connection = viewer.connection;
        Vec3 pos = this.fabrolia$passPos;
        if (pos == null || connection == null || connection.player != viewer
                || tracked.isRemoved() || viewer.isRemoved() || viewer.hasDisconnected()
                || viewer.level() != tracked.level()) return false;
        boolean inRange = com.axalotl.fabrolia.common.regions.TrackingViewerPass.inVanillaRange(
                viewerPos.x - pos.x, viewerPos.z - pos.z, this.fabrolia$passRange, viewDistance);
        boolean seen = this.seenBy.contains(connection);
        if (!inRange) return !seen;
        return seen == tracked.broadcastToPlayer(viewer);
    }

    @Override
    public boolean fabrolia$passAbsentAndIdle(ServerPlayer viewer) {
        if (this.fabrolia$stagedMembershipWork != 0) return false;
        ServerGamePacketListenerImpl connection = viewer.connection;
        return connection != null && !this.seenBy.contains(connection);
    }

    @Override
    public int fabrolia$islandTrackingStateRelaxed() {
        return this.fabrolia$islandTrackingState;
    }

    @Override
    public void fabrolia$passSendChanges() {
        this.serverEntity.sendChanges();
    }

    @Override
    public com.axalotl.fabrolia.common.regions.IslandRegistry.PIsland
    fabrolia$islandTrackingOwnerRelaxed() {
        return this.fabrolia$islandTrackingOwner;
    }

    @Override
    public boolean fabrolia$passViewersSettled(
            java.util.function.Consumer<ServerPlayer> viewerSink) {
        if (this.fabrolia$stagedMembershipWork != 0) return false;
        for (ServerGamePacketListenerImpl connection : this.seenBy) {
            ServerPlayer viewer = connection.getPlayer();
            if (viewer != null) viewerSink.accept(viewer);
        }
        return true;
    }

    @Override
    public void fabrolia$markVmpRemoved() {
        this.fabrolia$vmpRemoved = true;
    }

    @Override
    public boolean fabrolia$vmpRemoved() {
        return this.fabrolia$vmpRemoved;
    }

    @Override
    public int fabrolia$denseSlot() {
        return this.fabrolia$denseSlot;
    }

    @Override
    public void fabrolia$setDenseSlot(int slot) {
        this.fabrolia$denseSlot = slot;
    }

    @Inject(method = "updatePlayer(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), cancellable = true)
    private void updatePlayerNull(ServerPlayer player, CallbackInfo ci) {
        // entity.isRemoved(): tracked entity is being torn down — don't pair anyone new to it
        // player.isRemoved(): player is mid-dim-change. setRemoved(CHANGED_DIMENSION) fired,
        //   but ServerLevel$EntityCallbacks.onTrackingEnd calls chunkSource.removeEntity
        //   BEFORE players.remove(player) — so VMP NearbyEntityTracking.removeEntityTracker
        //   sees the player still in world.getPlayers() and its recursive tick0 →
        //   tickStaging re-adds the leaving player to seenBy of every staged tracker in
        //   range, leaking cross-dimension entity visibility until the connection drops.
        // player.level() mismatch: defence-in-depth for any other stale-path seenBy mutation.
        if (this.entity.isRemoved()
                || player.isRemoved()
                || player.level() != this.entity.level()) {
            ci.cancel();
        }
    }

    @WrapMethod(method = "updatePlayer(Lnet/minecraft/server/level/ServerPlayer;)V")
    private void updatePlayer(ServerPlayer player, Operation<Void> original) {
        synchronized (this) {
            // A stale VMP operation may outlive broadcastRemoved even though
            // the entity itself remains loaded. RETIRED is authoritative: the
            // old tracker generation can never pair again. Invalid/rebound
            // viewers likewise fail closed here; falling through would bypass
            // staging and run addPairing directly on a VMP worker.
            if (this.fabrolia$islandTrackingState
                    == IslandTrackedEntityAccess.TRACKING_RETIRED
                    || player == null || player == this.entity
                    || this.entity.isRemoved() || player.isRemoved()
                    || player.hasDisconnected() || player.connection == null
                    || player.connection.player != player
                    || player.level() != this.entity.level()) return;
            // Bulk and exact pairing commits must not expose a new viewer before Respawn.
            if (IslandEntityTracking.deferArrivalPairing(this, player)) return;
            if (IslandEntityTracking.isBulkPairingCommit(this)) {
                original.call(player);
            } else if (IslandEntityTracking.isPairingCommit(this, player)) {
                if (!IslandEntityTracking.pairingCommitStillCurrent(this, player)) return;
                original.call(player);
            } else {
                if (IslandEntityTracking.stagePairingIfOwned(this, player)) return;
                original.call(player);
            }
        }
        IslandEntityTracking.registerFromPairing(this);
    }

    @WrapMethod(method = "updatePlayers(Ljava/util/List;)V")
    private void fabrolia$stageBulkUpdatePlayers(
            List<ServerPlayer> players, Operation<Void> original) {
        synchronized (this) {
            if (this.fabrolia$islandTrackingState
                    == IslandTrackedEntityAccess.TRACKING_RETIRED) return;
            if (IslandEntityTracking.isBulkPairingCommit(this)) {
                original.call(players);
                return;
            }
            if (IslandEntityTracking.stageBulkPairingRefreshIfOwned(this)) return;
            original.call(players);
        }
    }

    /**
     * VMP may remove a pairing from one tracking worker while another worker is
     * inside {@link #updatePlayer}.  Use the exact same per-tracker intrinsic
     * monitor for both directions of the seenBy/serverEntity transition; the
     * concurrent seenBy set alone cannot make the multi-step pairing protocol
     * atomic.
     */
    @WrapMethod(method = "removePlayer(Lnet/minecraft/server/level/ServerPlayer;)V")
    private void fabrolia$removePlayer(ServerPlayer player,
                                       Operation<Void> original) {
        synchronized (this) {
            if (this.fabrolia$islandTrackingState
                    == IslandTrackedEntityAccess.TRACKING_RETIRED) {
                // broadcastRemoved already sent this viewer its removal (vanilla
                // leaves seenBy intact). A late VMP cleanup must not send a second
                // remove packet: it would hide a successor tracker of the same
                // entity id (respawned player, reloaded chunk) on that client.
                if (player.connection != null) this.seenBy.remove(player.connection);
                return;
            }
            if (!IslandEntityTracking.isBulkPairingCommit(this)
                    && !IslandEntityTracking.isRemovalCommit(this, player)
                    && IslandEntityTracking.stageRemovalIfOwned(this, player)) {
                IslandEntityTracking.unregisterIfIdle(this);
                return;
            }
            original.call(player);
            IslandEntityTracking.unregisterIfIdle(this);
        }
    }

    /** Serialises teardown of every pairing against update/remove on this tracker. */
    @WrapMethod(method = "broadcastRemoved()V")
    private synchronized void fabrolia$broadcastRemoved(Operation<Void> original) {
        // One teardown per generation: VMP's staged removal and ChunkMap's own
        // removal both call this; the second call must not re-send removals.
        if (this.fabrolia$islandTrackingState
                == IslandTrackedEntityAccess.TRACKING_RETIRED) return;
        // Chunk visibility can stop tracking without setting Entity.removalReason.
        // Retire this exact tracker generation before any remove packet is sent;
        // a later tracking start creates a different TrackedEntity instance.
        this.fabrolia$deactivateIslandTracking();
        IslandEntityTracking.retire(this);
        original.call();
    }
}
