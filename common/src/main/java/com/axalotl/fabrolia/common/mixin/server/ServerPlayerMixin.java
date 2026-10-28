package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementCacheContext;
import com.axalotl.fabrolia.common.regions.FailStops;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin extends Player implements
        com.axalotl.fabrolia.common.parallelised.utils.PearlOwnerSessionAccess,
        com.axalotl.fabrolia.common.regions.PlayerTickPhaseStamp,
        com.axalotl.fabrolia.common.regions.AsyncIslandPlayerAccess {
    public ServerPlayerMixin(Level level, GameProfile gameProfile) {
        super(level, gameProfile);
    }

    @org.spongepowered.asm.mixin.Shadow
    @org.spongepowered.asm.mixin.Final
    @org.spongepowered.asm.mixin.Mutable
    private java.util.Set<net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl> enderPearls;

    /**
     * Published when PlayerList replaces this lifecycle object with another
     * ServerPlayer of the same UUID. Volatile is required: pearl entities tick
     * on foreign region workers and can already hold {@code this} in a local
     * variable while main removes it from the old level.
     */
    @org.spongepowered.asm.mixin.Unique
    private volatile ServerPlayer fabrolia$pearlSessionSuccessor;

    /** Written before the volatile successor publication. This captures the
     * old lifecycle's live/dead state on main, avoiding a second racy read of
     * its plain removal fields from the pearl worker. */
    @org.spongepowered.asm.mixin.Unique
    private volatile boolean fabrolia$pearlSessionReplacementWasLive;

    /** Written only while the shared entity lifecycle lease is held. */
    @org.spongepowered.asm.mixin.Unique
    private volatile long fabrolia$playerLifecycleEpoch;

    @org.spongepowered.asm.mixin.Unique
    private final com.axalotl.fabrolia.common.regions.AsyncIslandPlayerTicks.PlayerState
            fabrolia$asyncIslandPlayerState =
            new com.axalotl.fabrolia.common.regions.AsyncIslandPlayerTicks.PlayerState();

    @Override
    public com.axalotl.fabrolia.common.regions.AsyncIslandPlayerTicks.PlayerState
    fabrolia$asyncIslandPlayerState() {
        return fabrolia$asyncIslandPlayerState;
    }

    @Override
    public long fabrolia$playerLifecycleEpoch() {
        return fabrolia$playerLifecycleEpoch;
    }

    @Override
    public void fabrolia$bumpPlayerLifecycleEpoch() {
        fabrolia$playerLifecycleEpoch++;
    }

    @WrapMethod(method = "setCamera(Lnet/minecraft/world/entity/Entity;)V")
    private void fabrolia$publishNetherCameraLink(Entity camera, Operation<Void> original) {
        com.axalotl.fabrolia.common.regions.NetherPortalPlayerAccess.changeCamera(
                (ServerPlayer) (Object) this, camera, () -> original.call(camera));
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "setServerLevel",
            at = @org.spongepowered.asm.mixin.injection.At("RETURN"))
    private void fabrolia$publishCameraLevel(net.minecraft.server.level.ServerLevel level,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        com.axalotl.fabrolia.common.regions.NetherPortalPlayerAccess.publishCamera(
                (ServerPlayer) (Object) this);
    }

    /** 0=fresh, 1=inside PlayerList.remove, 2=completed. Guarded by this. */
    @org.spongepowered.asm.mixin.Unique
    private int fabrolia$playerListRemovalState;

    /** Guarded by this; only Carpet's direct predecessor may consume it. */
    @org.spongepowered.asm.mixin.Unique
    private boolean fabrolia$pearlHandoffSaveConsumed;

    /** Exact identities successfully serialized by the current
     * PlayerList.remove save; null until that write completes. */
    @org.spongepowered.asm.mixin.Unique
    private volatile java.util.Set<net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl>
            fabrolia$pearlLogoutSnapshot;

    @Override
    public ServerPlayer fabrolia$pearlSessionSuccessor() {
        return this.fabrolia$pearlSessionSuccessor;
    }

    @Override
    public synchronized void fabrolia$publishPearlSessionSuccessor(
            ServerPlayer successor, boolean liveHandoff) {
        // A later belt publication for the same successor must not erase the
        // live state captured before ServerLevel force-removes the old player.
        this.fabrolia$pearlSessionReplacementWasLive = liveHandoff
                || (this.fabrolia$pearlSessionSuccessor == successor
                    && this.fabrolia$pearlSessionReplacementWasLive);
        this.fabrolia$pearlSessionSuccessor = successor;
    }

    @Override
    public boolean fabrolia$pearlSessionReplacementWasLive() {
        return this.fabrolia$pearlSessionReplacementWasLive;
    }

    @Override
    public synchronized boolean fabrolia$beginPlayerListRemoval() {
        if (this.fabrolia$playerListRemovalState != 0) {
            return false;
        }
        this.fabrolia$playerListRemovalState = 1;
        return true;
    }

    @Override
    public synchronized void fabrolia$finishPlayerListRemoval(boolean completed) {
        this.fabrolia$playerListRemovalState = completed ? 2 : 0;
    }

    @Override
    public synchronized boolean fabrolia$pearlHandoffSaveConsumed() {
        return this.fabrolia$pearlHandoffSaveConsumed;
    }

    @Override
    public synchronized boolean fabrolia$tryConsumePearlHandoffSave() {
        if (this.fabrolia$pearlHandoffSaveConsumed) {
            return false;
        }
        this.fabrolia$pearlHandoffSaveConsumed = true;
        return true;
    }

    @Override
    public void fabrolia$publishPearlLogoutSnapshot(
            java.util.Set<net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl>
                    savedPearls) {
        this.fabrolia$pearlLogoutSnapshot = savedPearls;
    }

    @Override
    public java.util.Set<net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl>
    fabrolia$pearlLogoutSnapshot() {
        return this.fabrolia$pearlLogoutSnapshot;
    }

    /**
     * enderPearls is the one player collection mutated by a FOREIGN entity's tick: a
     * thrown pearl is an independent entity that registers/deregisters itself into its
     * owner's set ({@code registerEnderPearl}/{@code deregisterEnderPearl}) from whatever
     * island the PEARL is in — landing, void-discard, chunk load/unload all happen on the
     * pearl island's worker while the owner lives elsewhere. Any same-time iteration
     * (saveEnderPearls during NBT-predicate eval — production CME 2026-07-20 — or any
     * other save path) tears a plain HashSet.  A normal concurrent hash set is not
     * sufficient: Entity.hashCode is derived from the mutable numeric entity id, and
     * staged cross-dimension publication changes that id after owner registration.
     * The identity set keeps the lifecycle object's hash stable, permits the source and
     * destination copies to overlap, and removes exactly the object whose onRemoval ran.
     */
    @org.spongepowered.asm.mixin.injection.Inject(method = "<init>", at = @org.spongepowered.asm.mixin.injection.At("TAIL"))
    private void fabrolia$concurrentEnderPearlSet(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        this.enderPearls =
                new com.axalotl.fabrolia.common.parallelised.utils.ConcurrentIdentitySet<>();
    }

    /**
     * Do not construct a playerdata pearl whose UUID is already present in the
     * live entity manager.  Minecraft loads Owner (and therefore calls
     * registerEnderPearl) before its later addWithUUID collision check; without
     * this preflight every rejected clone remains as an owner-registry ghost and
     * is saved again.  A processor belt below covers a collision that becomes
     * visible after this check, including C2ME HIDDEN entities which are present
     * in knownUuids but absent from public lookup.
     */
    @org.spongepowered.asm.mixin.injection.Inject(
            method = "loadAndSpawnEnderPearl",
            at = @org.spongepowered.asm.mixin.injection.At("HEAD"),
            cancellable = true)
    private void fabrolia$reuseLoadedPearlByUuid(
            net.minecraft.world.level.storage.ValueInput input,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        var dimension = input.read("ender_pearl_dimension",
                net.minecraft.world.level.Level.RESOURCE_KEY_CODEC);
        var uuid = input.read("UUID", net.minecraft.core.UUIDUtil.CODEC);
        net.minecraft.world.entity.EntityReference<net.minecraft.world.entity.Entity> ownerRef =
                net.minecraft.world.entity.EntityReference.read(input, "Owner");
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (dimension.isEmpty() || uuid.isEmpty() || ownerRef == null
                || !ownerRef.getUUID().equals(self.getUUID())) {
            return; // preserve vanilla's malformed-entry handling
        }
        net.minecraft.server.level.ServerLevel savedLevel =
                self.level().getServer().getLevel(dimension.get());
        if (savedLevel == null) {
            return; // preserve vanilla's missing-level warning
        }
        net.minecraft.world.entity.Entity existing =
                com.axalotl.fabrolia.common.parallelised.utils.PearlLoadRecovery
                        .loadedEntity(savedLevel, uuid.get());
        if (existing != null) {
            com.axalotl.fabrolia.common.parallelised.utils.PearlLoadRecovery
                    .preexisting(self, existing);
            ci.cancel();
            return;
        }
        // A player save may still point at the old dimension after the pearl
        // already crossed a portal. Entity UUID is logical identity across that
        // copy; only adopt an other-level candidate when type and owner UUID
        // prove that it is the migrated canonical object. An unrelated UUID
        // collision in another level must not suppress the saved-level load.
        for (net.minecraft.server.level.ServerLevel candidateLevel
                : self.level().getServer().getAllLevels()) {
            if (candidateLevel == savedLevel) continue;
            net.minecraft.world.entity.Entity candidate =
                    com.axalotl.fabrolia.common.parallelised.utils.PearlLoadRecovery
                            .loadedEntity(candidateLevel, uuid.get());
            if (candidate != null
                    && com.axalotl.fabrolia.common.parallelised.utils.PearlLoadRecovery
                            .migratedPreexisting(self, candidate)) {
                ci.cancel();
                return;
            }
        }
    }

    /**
     * The input entity registers itself with its owner while NBT is read.  Wrap
     * the vanilla processor so a freshly deserialized object rejected by
     * addWithUUID is detached immediately.  Scoping this to the playerdata load
     * call is important: a global addWithUUID RETURN hook cannot distinguish a
     * rejected clone from a harmless second add attempt for the canonical entity.
     */
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
            method = "loadAndSpawnEnderPearl",
            at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/EntityType;loadEntityRecursive("
                            + "Lnet/minecraft/world/level/storage/ValueInput;"
                            + "Lnet/minecraft/world/level/Level;"
                            + "Lnet/minecraft/world/entity/EntitySpawnReason;"
                            + "Lnet/minecraft/world/entity/EntityProcessor;)"
                            + "Lnet/minecraft/world/entity/Entity;"))
    private net.minecraft.world.entity.Entity fabrolia$cleanRejectedPearlLoad(
            net.minecraft.world.level.storage.ValueInput input,
            net.minecraft.world.level.Level level,
            net.minecraft.world.entity.EntitySpawnReason reason,
            net.minecraft.world.entity.EntityProcessor processor,
            Operation<net.minecraft.world.entity.Entity> original) {
        net.minecraft.world.entity.EntityProcessor guarded = candidate -> {
            net.minecraft.world.entity.Entity accepted = processor.process(candidate);
            if (accepted == null && level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                com.axalotl.fabrolia.common.parallelised.utils.PearlLoadRecovery
                        .rejected(serverLevel, candidate);
            }
            return accepted;
        };
        return original.call(input, level, reason, guarded);
    }

    // ------------------------------------------------------------------
    // task #28 — "Trying to save removed ender pearl, skipping" console spam.
    //
    // Making the set concurrent stopped it TEARING, but not the ordering bug
    // underneath. A pearl registers itself from its OWN tick
    // (ThrownEnderpearl.tick -> registerAndUpdateEnderPearlTicket ->
    // registerEnderPearl) on the pearl island's worker, guarded only by
    // `if (this.isAlive())`. Removal comes from a DIFFERENT thread — the
    // chunk-unload path or another worker's setRemoved (see the note on
    // PersistentEntitySectionManagerCallbackMixin.fabrolia$onRemoveIdempotent:
    // "setRemoved can be invoked concurrently from worker threads (entity tick)
    // and the chunk-unload path"). Interleave them:
    //
    //   worker: isAlive() -> true
    //   other:  setRemoved(...) -> onRemoval -> deregisterEnderPearl(P)  [removes]
    //   worker: registerEnderPearl(P)                                    [re-adds]
    //
    // P is now a REMOVED pearl living permanently in the set: deregistration
    // only ever runs from onRemoval, which already fired and will not fire
    // again. Vanilla cannot reach this — its removal and its tick are ordered
    // on one thread, which is exactly why isAlive() is a sufficient guard there.
    //
    // Consequences: vanilla's saveEnderPearls warns once per stale pearl per
    // serialization, and Fabrolia serializes players FAR more often than vanilla
    // saves them — advancement NBT predicates funnel through
    // ServerPlayer.saveWithoutId (see NbtPredicateMixin), on main and on pool
    // threads, which is the observed spam and its thread mix. The stale entries
    // also retain removed Entity objects (and through them their Level) for the
    // lifetime of the player's session.
    //
    // Invariant restored: the set never holds a pearl vanilla would have
    // deregistered. Enforced at the ONLY place entries are added, plus a heal on
    // the consumer, so it holds regardless of which thread raced.
    //
    // UNLOADED_WITH_PLAYER is deliberately excluded from BOTH checks — vanilla's
    // onRemoval keeps those registered on purpose (they belong to the player's
    // saved pearl list, not to the level), and dropping them here would delete
    // players' in-flight ender pearls. This mirrors vanilla's own rule exactly
    // rather than inventing a policy.
    // ------------------------------------------------------------------

    @org.spongepowered.asm.mixin.Unique
    private static final java.util.concurrent.atomic.AtomicLong fabrolia$pearlHealLogNanos =
            new java.util.concurrent.atomic.AtomicLong();

    @org.spongepowered.asm.mixin.Unique
    private static final java.util.concurrent.atomic.LongAdder fabrolia$pearlsHealed =
            new java.util.concurrent.atomic.LongAdder();

    /** True iff vanilla's {@code ThrownEnderpearl.onRemoval} would have
     *  deregistered this pearl — i.e. it no longer belongs in the owner's set. */
    @org.spongepowered.asm.mixin.Unique
    private static boolean fabrolia$pearlNoLongerBelongs(
            net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl pearl) {
        return com.axalotl.fabrolia.common.parallelised.utils.PearlRegistration
                .noLongerBelongsToOwner(pearl.getRemovalReason());
    }

    /** Follow the volatile old-session -> replacement chain without touching
     * PlayerList's plain HashMap from a region worker. The chain is forward-only
     * in normal operation; the bound and self checks contain corrupt/modded
     * cycles without allocating on this hot registration path. */
    @org.spongepowered.asm.mixin.Unique
    private static ServerPlayer fabrolia$latestPearlSession(ServerPlayer player) {
        ServerPlayer cursor = player;
        ServerPlayer slow = player;
        ServerPlayer fast = player;
        for (int hop = 0; hop < 8; hop++) {
            ServerPlayer next =
                    ((com.axalotl.fabrolia.common.parallelised.utils.PearlOwnerSessionAccess) cursor)
                            .fabrolia$pearlSessionSuccessor();
            if (next == null) {
                return cursor;
            }
            if (next == cursor) {
                return null;
            }
            cursor = next;

            slow = ((com.axalotl.fabrolia.common.parallelised.utils.PearlOwnerSessionAccess) slow)
                    .fabrolia$pearlSessionSuccessor();
            fast = ((com.axalotl.fabrolia.common.parallelised.utils.PearlOwnerSessionAccess) fast)
                    .fabrolia$pearlSessionSuccessor();
            if (fast != null) {
                fast = ((com.axalotl.fabrolia.common.parallelised.utils.PearlOwnerSessionAccess) fast)
                        .fabrolia$pearlSessionSuccessor();
            }
            if (slow != null && fast != null && slow == fast) {
                return null;
            }
        }
        return null;
    }

    /**
     * Never let a pearl tick resurrect a pearl that a concurrent removal already
     * deregistered. The re-check after the add closes the window in which the
     * removal landed between our first check and the insertion: {@code setRemoved}
     * publishes the removal reason before calling {@code onRemoval}, so if the
     * deregistration ran at all, the second read sees the reason and we undo our
     * own insertion.
     */
    @WrapMethod(method = "registerEnderPearl")
    private void fabrolia$dontResurrectRemovedPearl(
            net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl pearl,
            Operation<Void> original) {
        if (fabrolia$pearlNoLongerBelongs(pearl)) return;
        ServerPlayer self = (ServerPlayer) (Object) this;
        ServerPlayer published = fabrolia$latestPearlSession(self);
        if (published != null && published != self) {
            published.registerEnderPearl(pearl);
            return;
        }
        original.call(pearl);
        if (fabrolia$pearlNoLongerBelongs(pearl)) {
            this.enderPearls.remove(pearl);
            return;
        }
        ServerPlayer publishedAfter = fabrolia$latestPearlSession(self);
        if (publishedAfter != null && publishedAfter != self) {
            this.enderPearls.remove(pearl);
            publishedAfter.registerEnderPearl(pearl);
            return;
        }
    }

    /**
     * Replaces vanilla's {@code saveEnderPearls} loop wholesale.
     *
     * <p>The previous HEAD-inject pruned stale entries and then delegated to the
     * vanilla loop — which cannot work, and the operator reproduced it on audit1
     * by throwing thousands of pearls: vanilla re-reads {@code isRemoved()} per
     * pearl INSIDE its own loop, so any pearl removed between our prune and that
     * read still warns. There is no way to close a check-then-serialize window
     * from outside the loop, because the check we need to be atomic with is
     * vanilla's, not ours.
     *
     * <p>Under Fabrolia this window is wide and constantly open: a pearl is
     * removed by its OWN island worker (landing, void, chunk unload) while the
     * player is serialized on main AND on pool threads — advancement NBT
     * predicates funnel through {@code ServerPlayer.saveWithoutId}, so
     * serializations also overlap each other. Vanilla has one thread and cannot
     * reach this state at all.
     *
     * <p>Owning the loop lets the liveness decision and the write be adjacent,
     * with the decision re-taken immediately before {@code save}. The on-disk
     * result is a SUBSET of what vanilla writes and never contains a removed
     * pearl — {@link PearlRegistration#serializable} is the single shared
     * definition of vanilla's rule, so the output cannot drift. Stale entries
     * (those vanilla's {@code onRemoval} would have deregistered) are dropped
     * from the live set in the same pass, which also releases the retained
     * entity. {@code UNLOADED_WITH_PLAYER} is neither dropped nor written —
     * exactly vanilla.
     *
     * <p>Iteration is over the concurrent identity set: weakly consistent, so
     * it never throws and never needs a snapshot copy.  Unlike Entity's own
     * equals/hashCode, its key remains valid when cross-dimension publication
     * assigns a new numeric entity id.
     */
    @WrapMethod(method = "saveEnderPearls")
    private void fabrolia$saveEnderPearlsAtomically(
            net.minecraft.world.level.storage.ValueOutput output, Operation<Void> original) {
        if (this.enderPearls.isEmpty()) return;
        // A fire-time advancement compare never references the pearls (one that does
        // is routed before serializing); they may live on any island or level.
        if (AdvancementCacheContext.excludesEnderPearls()) return;

        int healed = 0;
        java.util.EnumMap<net.minecraft.world.entity.Entity.RemovalReason, Integer> byReason = null;
        net.minecraft.world.level.storage.ValueOutput.ValueOutputList list = null;

        var it = this.enderPearls.iterator();
        while (it.hasNext()) {
            var pearl = it.next();
            var reason = pearl.getRemovalReason();

            if (com.axalotl.fabrolia.common.parallelised.utils.PearlRegistration
                    .noLongerBelongsToOwner(reason)) {
                if (byReason == null) {
                    byReason = new java.util.EnumMap<>(
                            net.minecraft.world.entity.Entity.RemovalReason.class);
                }
                byReason.merge(reason, 1, Integer::sum);
                it.remove();
                healed++;
                continue;
            }
            if (!com.axalotl.fabrolia.common.parallelised.utils.PearlRegistration
                    .serializable(reason)) {
                continue; // registered but not written (UNLOADED_WITH_PLAYER) — vanilla
            }
            // Re-read immediately before writing: a concurrent removal between the
            // read above and here is the whole bug. A pearl that dies in THIS gap is
            // simply not written, which is what vanilla would have done too.
            if (pearl.isRemoved()) continue;

            if (list == null) list = output.childrenList("ender_pearls");
            net.minecraft.world.level.storage.ValueOutput child = list.addChild();
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> savedDimension =
                    pearl.level().dimension();
            if (!pearl.save(child) || pearl.isRemoved()) {
                // A cross-dimension source can be removed after the liveness
                // check. Do not leave a malformed child. Every successfully
                // serialized identity is captured below and is therefore the
                // exact identity vanilla may subsequently mark UWP; suppressing
                // another live object merely because it shares this UUID would
                // break that save-to-removal coupling.
                list.discardLast();
                continue;
            }
            child.store("ender_pearl_dimension", net.minecraft.world.level.Level.RESOURCE_KEY_CODEC,
                    savedDimension);
            com.axalotl.fabrolia.common.parallelised.utils.PearlLogoutSaveCapture
                    .record((ServerPlayer) (Object) this, pearl);
        }

        if (healed == 0) return;
        fabrolia$pearlsHealed.add(healed);
        long now = System.nanoTime();
        long last = fabrolia$pearlHealLogNanos.get();
        if (now - last > 300_000_000_000L && fabrolia$pearlHealLogNanos.compareAndSet(last, now)) {
            org.slf4j.LoggerFactory.getLogger("Fabrolia-Pearl").warn(
                    "Dropped {} stale ender pearl registration(s) {} from {} that a concurrent "
                            + "removal failed to deregister ({} total this session). RUNTIME race "
                            + "(task #28), unrelated to the vanilla duplicate-UUID pearl behaviour "
                            + "seen at world/player LOAD. The saved pearl list is unaffected: "
                            + "vanilla never writes a removed pearl either.",
                    healed, byReason, this.getName().getString(), fabrolia$pearlsHealed.sum());
        }
    }

    @WrapMethod(method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V")
    private void die(DamageSource source, Operation<Void> original) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        fabrolia$withPlayerLifecycleLease(
                com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                        .LifecycleMutationKind.DEATH,
                new Entity[]{self}, () -> {
            // Acquire the entity lease before the historical player monitor.  The
            // inverse order (monitor -> lease) permits a worker/main ABBA at death.
            synchronized (self) {
                original.call(source);
            }
            return null;
        });
    }

    /**
     * Exact A5 turn accounting around ServerPlayer.tick. Advancement triggers in
     * the body evaluate and award on the player's owner at fire time
     * (AdvancementBuffer.trigger), so the body's own flushDirty sends their progress
     * in vanilla order.
     */
    @WrapMethod(method = "tick()V")
    private void fabrolia$accountPlayerTick(Operation<Void> original) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        com.axalotl.fabrolia.common.regions.AsyncIslandPlayerTicks
                .markServerPlayerTickEntered(self);
        Throwable failure = null;
        try {
            original.call();
        } catch (Throwable bodyFailure) {
            failure = com.axalotl.fabrolia.common.regions.RegionScheduler
                    .publishFatalAndSelectWinner(bodyFailure);
        }
        if (failure != null) FailStops.<RuntimeException>rethrow(failure);
    }

    @WrapMethod(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z")
    private boolean fabrolia$publishMountedCamera(Entity vehicle, boolean force,
                                                 boolean sendEventAndTriggers, Operation<Boolean> original) {
        return fabrolia$withPlayerLifecycleLease(
                com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                        .LifecycleMutationKind.SOFT_HANDOFF,
                new Entity[]{(Entity) (Object) this, vehicle},
                () -> original.call(vehicle, force, sendEventAndTriggers));
    }

    @WrapMethod(method = "removeVehicle()V")
    private void fabrolia$publishDismountedCamera(Operation<Void> original) {
        fabrolia$withPlayerLifecycleLease(
                com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                        .LifecycleMutationKind.SOFT_HANDOFF,
                new Entity[]{(Entity) (Object) this}, () -> {
                    original.call();
                    return null;
                });
    }

    @org.spongepowered.asm.mixin.Unique
    @SuppressWarnings("unchecked")
    private <T> T fabrolia$withPlayerLifecycleLease(
            com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2.LifecycleMutationKind
                    kind,
            Entity[] participants, java.util.function.Supplier<T> body) {
        com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2.LifecycleLeaseScope scope =
                com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                        .beginLifecycleMutation(kind, participants);
        Object[] result = new Object[1];
        Throwable failure = null;
        try {
            com.axalotl.fabrolia.common.regions.RegionScheduler
                    .runSuccessBodyIfHealthy(() -> result[0] =
                            com.axalotl.fabrolia.common.regions.NetherPortalPlayerAccess
                                    .withRidePublication(participants, body));
        } catch (Throwable thrown) {
            failure = com.axalotl.fabrolia.common.regions.RegionScheduler
                    .publishFatalAndSelectWinner(thrown);
        } finally {
            Throwable primary = failure;
            try {
                failure = scope.finish(primary);
            } catch (Throwable cleanup) {
                Throwable candidate = FailStops.mergeFailureCapped(primary, cleanup);
                failure = com.axalotl.fabrolia.common.regions.RegionScheduler
                        .publishFatalAndSelectWinner(candidate);
            }
        }
        if (failure == null) {
            try {
                com.axalotl.fabrolia.common.regions.RegionScheduler
                        .commitSuccessIfHealthy(() -> {});
            } catch (Throwable peerFailure) {
                failure = com.axalotl.fabrolia.common.regions.RegionScheduler
                        .publishFatalAndSelectWinner(peerFailure);
            }
        }
        if (failure != null) {
            FailStops.<RuntimeException>rethrow(failure);
        }
        return (T) result[0];
    }

    /** Publish the exact cross-dimension lifecycle window to region entities.
     * Identity-keyed synchronized membership supplies the happens-before edge
     * that the player's plain removal/death fields do not provide. */
    @WrapMethod(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)"
            + "Lnet/minecraft/server/level/ServerPlayer;")
    private ServerPlayer fabrolia$markPlayerDimensionTransition(
            net.minecraft.world.level.portal.TeleportTransition transition,
            Operation<ServerPlayer> original) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        var mutationKind = transition.newLevel() == self.level()
                ? com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                        .LifecycleMutationKind.SOFT_HANDOFF
                : com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                        .LifecycleMutationKind.HARD_EXIT;
        return fabrolia$withPlayerLifecycleLease(mutationKind, new Entity[]{self}, () -> {
            if (transition.newLevel() == self.level()) {
                return original.call(transition);
            }
            com.axalotl.fabrolia.common.parallelised.utils.PlayerDimensionTransition.enter(self);
            ServerPlayer result = null;
            Throwable failure = null;
            try {
                result = original.call(transition);
            } catch (Throwable thrown) {
                // The marker and lifecycle lease still exclude successor
                // ownership here. Select exact W before either cleanup opens
                // that window; the outer lease boundary cannot do this in time.
                failure = com.axalotl.fabrolia.common.regions.RegionScheduler
                        .publishFatalAndSelectWinner(thrown);
            } finally {
                try {
                    com.axalotl.fabrolia.common.parallelised.utils.PlayerDimensionTransition.exit(self);
                } catch (Throwable cleanup) {
                    Throwable candidate = FailStops.mergeFailureCapped(failure, cleanup);
                    failure = com.axalotl.fabrolia.common.regions.RegionScheduler
                            .publishFatalAndSelectWinner(candidate);
                }
            }
            if (failure != null) {
                FailStops.<RuntimeException>rethrow(failure);
            }
            return result;
        });
    }

    /** Respawn/shadow copy mutates both exact session objects from its first bytecode. */
    @WrapMethod(method = "restoreFrom(Lnet/minecraft/server/level/ServerPlayer;Z)V")
    private void fabrolia$leaseRestoreFrom(ServerPlayer oldPlayer, boolean alive,
                                           Operation<Void> original) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        fabrolia$withPlayerLifecycleLease(
                com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                        .LifecycleMutationKind.HARD_EXIT,
                new Entity[]{self, oldPlayer}, () -> {
            original.call(oldPlayer, alive);
            return null;
        });
    }

}
