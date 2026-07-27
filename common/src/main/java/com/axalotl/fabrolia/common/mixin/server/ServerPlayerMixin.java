package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementBuffer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin extends Player {
    public ServerPlayerMixin(Level level, GameProfile gameProfile) {
        super(level, gameProfile);
    }

    @org.spongepowered.asm.mixin.Shadow
    @org.spongepowered.asm.mixin.Final
    @org.spongepowered.asm.mixin.Mutable
    private java.util.Set<net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl> enderPearls;

    /**
     * enderPearls is the one player collection mutated by a FOREIGN entity's tick: a
     * thrown pearl is an independent entity that registers/deregisters itself into its
     * owner's set ({@code registerEnderPearl}/{@code deregisterEnderPearl}) from whatever
     * island the PEARL is in — landing, void-discard, chunk load/unload all happen on the
     * pearl island's worker while the owner lives elsewhere. Any same-time iteration
     * (saveEnderPearls during NBT-predicate eval — production CME 2026-07-20 — or any
     * other save path) tears a plain HashSet. A concurrent set makes membership atomic
     * and iteration weakly consistent for every consumer; vanilla's HashSet iteration
     * order was already unspecified, so no observable semantics change.
     */
    @org.spongepowered.asm.mixin.injection.Inject(method = "<init>", at = @org.spongepowered.asm.mixin.injection.At("TAIL"))
    private void fabrolia$concurrentEnderPearlSet(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        this.enderPearls = java.util.concurrent.ConcurrentHashMap.newKeySet();
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
        original.call(pearl);
        if (fabrolia$pearlNoLongerBelongs(pearl)) {
            this.enderPearls.remove(pearl);
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
     * <p>Iteration is over the ConcurrentHashMap key set: weakly consistent, so
     * it never throws and never needs a snapshot copy.
     */
    @WrapMethod(method = "saveEnderPearls")
    private void fabrolia$saveEnderPearlsAtomically(
            net.minecraft.world.level.storage.ValueOutput output, Operation<Void> original) {
        if (this.enderPearls.isEmpty()) return;

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
            pearl.save(child);
            child.store("ender_pearl_dimension", net.minecraft.world.level.Level.RESOURCE_KEY_CODEC,
                    pearl.level().dimension());
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

    // ------------------------------------------------------------------
    // REGRESSION RESTORE (operator report: sculk sensors deaf to players since
    // audit2). Commit 7859a3d ("end the source tick at handoff; own the pearl
    // save loop") rewrote this file for the saveEnderPearls fix and, by
    // accident, dropped every wrap below — none of them is mentioned in that
    // commit's message or the audit2 README. The one-sided result: main's
    // tickPlayer kept SKIPPING doTick for region-eligible players
    // (ServerGamePacketRoutingMixin.fabrolia$regionOwnedDoTick still called
    // RegionalPlayerTicks.mainShouldSkipDoTick), while the worker-side doTick
    // call in the tick() wrap tail no longer existed — so with
    // `/fabrolia regions playerticks on` a player's SIMULATION tick ran on NO
    // thread at all: no aiStep, no hunger, and none of the doTick-driven game
    // events sculk listens for. The bodies below are restored verbatim from
    // audit1 (8e9098d), the last commit where players ticked on the island
    // path.
    // ------------------------------------------------------------------

    @WrapMethod(method = "die")
    private synchronized void die(DamageSource source, Operation<Void> original) {
        original.call(source);
    }

    /**
     * Opens the advancement buffering window for the duration of this player's tick.
     * Every {@code CriteriaTriggers.X.trigger(this)} that fires inside the original tick
     * (notably TICK in {@code tick} and LOCATION in {@code doTick}) gets snapshotted by
     * {@link AdvancementBuffer#tryBuffer} instead of running its full listener loop inline.
     *
     * <p>The buffer is frame-based (per-thread): this wrap works identically whether the
     * player ticks on main (vanilla fabrolia-advancements mode) or on a region worker
     * (enableRegionPlayerTicks). On main, {@link AdvancementBuffer#drain} fans the eval
     * phase out to the fabrolia pool and applies grants serially right here; on a region
     * worker, drain evaluates inline on the worker and queues matched grants for the
     * main-thread apply at entity-batch end (AdvancementBuffer.applyDeferred). The
     * try/finally drain guarantees frame state never leaks across ticks.
     */
    /** task #15 (production CME): ServerPlayer.tick's own advancement flush
     *  iterates progressChanged/rootsToUpdate/visible plain HashSets — on a
     *  REGION WORKER under regional player ticking, concurrently with MAIN
     *  legally mutating the same sets via award()/revoke() (buffer drains,
     *  packet-time triggers, commands, other players' reward cascades). Route
     *  the flush to the main batch-end drain instead: one flush per player
     *  per tick, after the batch-end grants — advancement state is now
     *  touched by exactly one thread (main). Main-ticked players keep the
     *  vanilla inline call. */
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
            method = "tick()V",
            at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
                    target = "Lnet/minecraft/server/PlayerAdvancements;flushDirty(Lnet/minecraft/server/level/ServerPlayer;Z)V"))
    private void fabrolia$mainOwnFlush(net.minecraft.server.PlayerAdvancements advancements,
                                       net.minecraft.server.level.ServerPlayer player, boolean force,
                                       Operation<Void> original) {
        if (Thread.currentThread() instanceof com.axalotl.fabrolia.common.regions.RegionWorkerThread) {
            AdvancementBuffer.queueFlush(player);
            return;
        }
        original.call(advancements, player, force);
    }

    @WrapMethod(method = "tick()V")
    private void fabrolia$bufferAdvancementsDuringTick(Operation<Void> original) {
        AdvancementBuffer.enter();
        try {
            original.call();
            // EXPERIMENTAL regional player ticking: on a region worker that owns
            // this player's island, run the SIMULATION tick right here — same
            // frame, same claim, same timeline as the island's mobs, block
            // entities and sculk sensors (the carpet fake-player shape). Main's
            // tickPlayer skips doTick for region-owned players; the per-gameTime
            // stamp in shouldWorkerDoTick makes the tick exactly-once.
            ServerPlayer self = (ServerPlayer) (Object) this;
            if (com.axalotl.fabrolia.common.regions.RegionalPlayerTicks.shouldWorkerDoTick(self)) {
                self.doTick();
            }
        } finally {
            AdvancementBuffer.drain();
        }
    }

    /**
     * Second (nested-safe) buffering window on doTick. Carpet's EntityPlayerMPFake
     * OVERRIDES tick() and calls doTick() directly — the tick() wrap above never
     * runs for fake players, so their every-tick triggers fell through to the
     * vanilla inline path under the SyncAll monitor and convoyed every region
     * worker on the PlayerTrigger lock (50-bot load test, 2026-06-12). The frame
     * is depth-counted: real players (tick→doTick) drain once at the outer exit,
     * fake players (bare doTick) drain here.
     */
    @WrapMethod(method = "doTick()V")
    private void fabrolia$bufferAdvancementsDuringDoTick(Operation<Void> original) {
        com.axalotl.fabrolia.common.regions.PlayerOwnership
                .assertSimulationOwner((ServerPlayer) (Object) this, "doTick");
        // validation probe only (debug playerticks fakes on): unit-identity
        // accounting for carpet fakes, which already simulate regionally
        com.axalotl.fabrolia.common.regions.RegionalPlayerTicks
                .stampFakeForValidation((ServerPlayer) (Object) this);
        AdvancementBuffer.enter();
        try {
            original.call();
        } finally {
            AdvancementBuffer.drain();
        }
    }

    /** Phase-0 ownership census (task #30): report-only assertions on the
     *  dangerous player mutation seams. Each violation names a path that must
     *  be routed through the handoff protocol before player simulation moves
     *  to region workers. Zero behavior change. */
    @org.spongepowered.asm.mixin.injection.Inject(
            method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;",
            at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
    private void fabrolia$ownerCensusTeleport(net.minecraft.world.level.portal.TeleportTransition transition,
                                              org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<ServerPlayer> cir) {
        com.axalotl.fabrolia.common.regions.PlayerOwnership
                .assertSimulationOwner((ServerPlayer) (Object) this, "teleport");
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "setGameMode", at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
    private void fabrolia$ownerCensusGameMode(net.minecraft.world.level.GameType gameMode,
                                              org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        com.axalotl.fabrolia.common.regions.PlayerOwnership
                .assertSimulationOwner((ServerPlayer) (Object) this, "setGameMode");
    }
}
