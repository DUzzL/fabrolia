package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.FabroliaTickDomain;
import com.axalotl.fabrolia.common.regions.CausalScheduledTickIndex;
import com.axalotl.fabrolia.common.regions.ScheduledTickIslandAdmission;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.SavedTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Clock-domain tag storage (see {@link FabroliaTickDomain}). Volatile: the
 *  tag is written at safe points (main rebuild fence, container registration
 *  under the LevelTicks lock, shutdown collapse) and read from save/runner
 *  threads.
 *
 *  <p><b>audit15 (2026-07-27 production NPE):</b> this mixin previously also
 *  installed a packed-ticks accessor duck ({@code FabroliaPackedTicksAccess})
 *  shadowing the private {@code pendingTicks} list and {@code ticksPerPosition}
 *  dedup set so the bulk repair facility could mutate FULL-but-not-ticking
 *  containers. That was removed, deliberately and permanently:
 *
 *  <ul>
 *    <li>Vanilla's scheduler internals are NOT a stable surface across mods.
 *        Lithium ({@code mixin.world.tick_scheduler.LevelChunkTicksMixin})
 *        REPLACES them: its constructor hook derives an {@code allTicks} hash
 *        set from {@code pendingTicks} and then sets {@code ticksPerPosition}
 *        and {@code tickQueue} to null on EVERY container — reading
 *        {@code ticksPerPosition} NPEs (the production crash), and rewriting
 *        {@code pendingTicks} silently desyncs {@code allTicks} (removed
 *        entries keep blocking {@code hasScheduledTick}/dedup forever). There
 *        is no field a mixin could touch that is correct on both vanilla and
 *        Lithium.</li>
 *    <li>The PUBLIC container API ({@code getAll}/{@code schedule}/
 *        {@code hasScheduledTick}/{@code peek}/{@code poll}/{@code count}/
 *        {@code pack}/{@code unpack}) is overwritten by Lithium consistently
 *        and is the only mutation surface Fabrolia uses now. Packed
 *        (deserialized, not-yet-unpacked) state is intentionally unreachable
 *        through that API, so packed containers are never mutated.</li>
 *    <li><b>task #37 (2026-07-28 22:10 production NPE):</b> the one API
 *        member the audit trusted too much is {@code removeIf} — Lithium's
 *        overwrite iterates ABSOLUTE indices {@code 0..size()} over its
 *        poll-compacted OrderedTickQueue ring and NPEs on any
 *        partially-polled bucket (island tick slicing produces those
 *        routinely; vanilla reaches the state only via MAX_TICKS budget
 *        interrupts — their latent {@code /fill}-crash). The wrap below
 *        replaces every {@code removeIf} body with the poll-drain
 *        equivalent ({@link com.axalotl.fabrolia.common.regions.TickDomains#drainRemoveIf}),
 *        which uses only the methods both implementations keep correct.</li>
 *    <li><b>task #41 (2026-08-01, Lithium audit part 4):</b> {@code removeIf}
 *        was not the only member classified too generously. {@code getAll} and
 *        {@code pack} are read-SHAPED and write-BEHAVING on Lithium — both
 *        route through {@code OrderedTickQueue.iterator() -> sort() ->
 *        handleCompaction()}, which reallocates the queue array and resets
 *        {@code firstIndex} under a concurrent drainer — and {@code getAll}
 *        does it LAZILY, at the caller's terminal operation, so the blanket
 *        {@code SyncAllMixin} monitor never covered it at all. {@code poll} is
 *        where Lithium's {@code updateNextTickQueue(true)} surfaces, having
 *        removed a bucket of live ticks before discovering it removed the
 *        wrong one. {@code getAll} and {@code pack} are wrapped below;
 *        {@code poll} deliberately is not (hot path, its original already holds
 *        the container monitor).</li>
 *  </ul>
 */
@Mixin(value = LevelChunkTicks.class, priority = 2000)
public abstract class LevelChunkTicksMixin implements FabroliaTickDomain,
        CausalScheduledTickIndex, com.axalotl.fabrolia.common.regions.AcceptedTickAccess<Object> {

    @Unique
    private boolean fabrolia$restoringAccepted;

    @Override
    public void fabrolia$restoreAccepted(net.minecraft.world.ticks.ScheduledTick<Object> tick) {
        synchronized (this) {
            fabrolia$restoringAccepted = true;
            try {
                ((LevelChunkTicks<Object>) (Object) this).schedule(tick);
            } finally {
                fabrolia$restoringAccepted = false;
            }
        }
    }

    // Consume the one-call bypass at admission, BEFORE queue insertion invokes
    // onTickAdded. Any reentrant new schedule still uses ordinary dedup.
    @org.spongepowered.asm.mixin.injection.Group(name = "fabrolia$acceptedAdmission", min = 1, max = 1)
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
            method = "schedule", at = @At(value = "INVOKE",
            target = "Ljava/util/Set;add(Ljava/lang/Object;)Z"), require = 0)
    private boolean fabrolia$restoreVanillaAdmission(java.util.Set<Object> set, Object tick,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<Boolean> original) {
        boolean restore = fabrolia$restoringAccepted;
        fabrolia$restoringAccepted = false;
        return original.call(set, tick) | restore;
    }

    @org.spongepowered.asm.mixin.injection.Group(name = "fabrolia$acceptedAdmission", min = 1, max = 1)
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
            method = "schedule", at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/ints/IntOpenHashSet;add(I)Z", remap = false),
            require = 0)
    private boolean fabrolia$restoreLithiumAdmission(
            it.unimi.dsi.fastutil.ints.IntOpenHashSet set, int tick,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<Boolean> original) {
        boolean restore = fabrolia$restoringAccepted;
        fabrolia$restoringAccepted = false;
        return original.call(set, tick) | restore;
    }

    /** Monotonic by design; see {@link CausalScheduledTickIndex}. */
    @Unique
    private volatile boolean fabrolia$mayContainPulseSensitiveTick;

    @Inject(method = "<init>(Ljava/util/List;)V", at = @At("RETURN"))
    private void fabrolia$indexPackedPulseTicks(List<SavedTick<?>> pendingTicks,
                                                CallbackInfo ci) {
        for (SavedTick<?> tick : pendingTicks) {
            if (ScheduledTickIslandAdmission.requiresPulseCoherence(tick.type())) {
                fabrolia$markPulseSensitiveTick();
                return;
            }
        }
    }

    @Override
    public boolean fabrolia$mayContainPulseSensitiveTick() {
        return fabrolia$mayContainPulseSensitiveTick;
    }

    @Override
    public void fabrolia$markPulseSensitiveTick() {
        fabrolia$mayContainPulseSensitiveTick = true;
    }

    /** Priority 2000: must wrap the FINAL body (after Lithium's @Overwrite at
     *  1000). Never calls {@code original} — the drain IS the replacement
     *  semantics, identical for vanilla and required for Lithium.
     *
     *  <p>ONE EXCEPTION (task #41): {@link com.axalotl.fabrolia.common.regions.TickDomains#COMPACT_ONLY}
     *  is forwarded to the underlying implementation. It is the constant-false,
     *  null-tolerant predicate, so the forward removes nothing and cannot hit
     *  Lithium's absolute-index NPE — but on Lithium it is the only public
     *  operation that evicts stale-EMPTY buckets and re-derives
     *  {@code nextTickQueue}, which is the repair a wedged container needs. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "removeIf")
    private void fabrolia$implAgnosticRemoveIf(java.util.function.Predicate<net.minecraft.world.ticks.ScheduledTick<?>> predicate,
                                               com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        if (predicate == com.axalotl.fabrolia.common.regions.TickDomains.COMPACT_ONLY) {
            original.call(predicate);
            return;
        }
        com.axalotl.fabrolia.common.regions.TickDomains.drainRemoveIfErased(
                (LevelChunkTicks<?>) (Object) this, (java.util.function.Predicate) predicate);
    }

    // ------------------------------------------------------------------
    // Task #41 — the two read-SHAPED but write-BEHAVING members of the
    // container API, plus containment for Lithium's data-destroying invariant
    // check. The wraps stay thin and delegate to TickDomains on purpose: none
    // of the reasoning below may be true only inside a mixin environment,
    // because that is exactly what cannot be unit-tested.
    //
    // WHY A WRAP AND NOT A `synchronized` KEYWORD SOMEWHERE: SynchronisePlugin's
    // postApply runs after ALL mixins of the target are applied, so it sees this
    // mixin's merged handlers too, and the blanket ACC_SYNCHRONIZED bit lands on
    // them as well (they are not excluded). The bit on a WrapMethod handler is
    // not what protects the lazy Lithium mutation, though: getAll/pack take the
    // container monitor explicitly around the underlying call and the snapshot,
    // reentrantly over the bit their originals carry; poll deliberately does NOT
    // wrap, because it is a hot path and its original already holds the same
    // monitor - see its javadoc.
    // ------------------------------------------------------------------

    /** {@code getAll()} MUTATES on Lithium, and does it lazily — after this
     *  frame returns. Snapshot inside the monitor. See
     *  {@link com.axalotl.fabrolia.common.regions.TickDomains#snapshotAll}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "getAll")
    private java.util.stream.Stream<net.minecraft.world.ticks.ScheduledTick<?>> fabrolia$snapshotGetAll(
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<java.util.stream.Stream<net.minecraft.world.ticks.ScheduledTick<?>>> original) {
        fabrolia$assertBlanketSyncOnce();
        java.util.function.Supplier supplier = original::call;
        return com.axalotl.fabrolia.common.regions.TickDomains.snapshotAll(
                (LevelChunkTicks) (Object) this, supplier);
    }

    /** {@code pack()} MUTATES on Lithium (same compaction, eagerly), and chunk
     *  serialisation calls it off the server thread under c2me. See
     *  {@link com.axalotl.fabrolia.common.regions.TickDomains#packUnderMonitor}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "pack")
    private java.util.List<net.minecraft.world.ticks.SavedTick<?>> fabrolia$packUnderMonitor(
            long currentTick,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<java.util.List<net.minecraft.world.ticks.SavedTick<?>>> original) {
        fabrolia$assertBlanketSyncOnce();
        java.util.function.Supplier supplier = () -> original.call(currentTick);
        return com.axalotl.fabrolia.common.regions.TickDomains.packUnderMonitor(
                (LevelChunkTicks) (Object) this, supplier);
    }

    @Unique
    private static final java.util.concurrent.atomic.AtomicBoolean fabrolia$BLANKET_CHECKED =
            new java.util.concurrent.atomic.AtomicBoolean();

    @Unique
    private static final java.util.List<String> fabrolia$BLANKET_METHODS =
            java.util.List.of("schedule", "peek", "poll", "hasScheduledTick", "unpack");

    @Unique
    private static final org.slf4j.Logger fabrolia$LOGGER =
            org.slf4j.LoggerFactory.getLogger("Fabrolia/TickContainer");

    /**
     * One-shot loud check that {@code LevelChunkTicks} is still in the
     * {@code SyncAllMixin} blanket set. Everything above assumes ONE monitor
     * over the whole container API; the wraps here can only take it for the
     * methods they cover, and {@code schedule}/{@code peek}/{@code poll}/
     * {@code hasScheduledTick}/{@code unpack} — the other half of every
     * invariant Lithium's cached head pointer depends on — get it from the
     * blanket alone. Removing the {@code SyncAllMixin} entry without replacing
     * that coverage re-arms Lithium audit §4.4/§4.5/§4.6 silently, so make it
     * loud instead.
     *
     * <p>Checks the named container API ({@code fabrolia$BLANKET_METHODS}) by
     * declared method name: 26.x runtime names are the Mojang names. A named
     * method that is missing or lacks the bit is reported, so a blanket that
     * vanished from only some methods is caught as well. Runs off the cold
     * {@code getAll}/{@code pack} paths, never from {@code poll}.
     */
    @Unique
    private static void fabrolia$assertBlanketSyncOnce() {
        if (fabrolia$BLANKET_CHECKED.get() || !fabrolia$BLANKET_CHECKED.compareAndSet(false, true)) {
            return;
        }
        java.util.List<String> unguarded = new java.util.ArrayList<>();
        for (String name : fabrolia$BLANKET_METHODS) {
            boolean found = false;
            boolean allSynchronized = true;
            for (java.lang.reflect.Method m : LevelChunkTicks.class.getDeclaredMethods()) {
                int mod = m.getModifiers();
                if (!m.getName().equals(name) || java.lang.reflect.Modifier.isStatic(mod)
                        || m.isSynthetic() || m.isBridge()) {
                    continue;
                }
                found = true;
                allSynchronized &= java.lang.reflect.Modifier.isSynchronized(mod);
            }
            if (!found || !allSynchronized) {
                unguarded.add(name);
            }
        }
        if (!unguarded.isEmpty()) {
            fabrolia$LOGGER.error("LevelChunkTicks container API is NO LONGER blanket-synchronized for {} — "
                    + "the SyncAllMixin entry was removed or a method renamed without replacing the "
                    + "per-container monitor. These methods are now unguarded against concurrent drains, which "
                    + "re-arms Lithium's cached-head hazards (audit §4.4 silent chunk death, §4.5 bucket "
                    + "destruction, §4.6 compaction under a drainer). Restore the entry or wrap the remaining API.",
                    unguarded);
        } else {
            fabrolia$LOGGER.info("LevelChunkTicks container monitor in force on {}", fabrolia$BLANKET_METHODS);
        }
    }

    @Unique private volatile long fabrolia$domainIsland = FabroliaTickDomain.WALL;
    @Unique private volatile long fabrolia$domainGen = -1;

    @Override
    public long fabrolia$domainIsland() {
        return fabrolia$domainIsland;
    }

    @Override
    public long fabrolia$domainGen() {
        return fabrolia$domainGen;
    }

    @Override
    public void fabrolia$setDomain(long islandId, long gen) {
        this.fabrolia$domainIsland = islandId;
        this.fabrolia$domainGen = gen;
    }
}
