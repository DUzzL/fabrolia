package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.FabroliaTickDomain;
import net.minecraft.world.ticks.LevelChunkTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

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
 *        through that API, so packed containers are never mutated; the
 *        repair facility defers them until the chunk unpacks naturally (see
 *        {@code TickRepair}).</li>
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
 *        wrong one. All three are wrapped below.</li>
 *  </ul>
 */
@Mixin(value = LevelChunkTicks.class, priority = 2000)
public abstract class LevelChunkTicksMixin implements FabroliaTickDomain {

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
    // WHY A WRAP AND NOT A `synchronized` KEYWORD SOMEWHERE: methods merged by
    // a priority-2000 mixin are applied AFTER SyncAllMixin and are therefore
    // invisible to SynchronisePlugin.postApply — a handler here never receives
    // the blanket ACC_SYNCHRONIZED bit (SynchronisePlugin's javadoc names this
    // class as the example). getAll/pack therefore take the container monitor
    // themselves, reentrantly over the blanket bit their originals still
    // carry; poll deliberately does NOT, because it is a hot path and its
    // original already holds the same monitor — see its javadoc.
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

    /** Contains Lithium's {@code updateNextTickQueue(true)} remove-before-check,
     *  which destroys a bucket of live ticks and then throws out of
     *  {@code LevelTicks.tick}. See
     *  {@link com.axalotl.fabrolia.common.regions.TickDomains#recoverFromBucketInvariantViolation}.
     *
     *  <p>HOT PATH — the {@code try} body stays bare and the retry supplier is
     *  built inside {@code catch}, so the happy path costs nothing: no extra
     *  monitor (the original body still carries the blanket bit), no capture,
     *  no allocation. Do not "tidy" this into a helper taking a supplier. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "poll")
    private net.minecraft.world.ticks.ScheduledTick<?> fabrolia$pollGuarded(
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<net.minecraft.world.ticks.ScheduledTick<?>> original) {
        try {
            return original.call();
        } catch (IllegalStateException e) {
            return (net.minecraft.world.ticks.ScheduledTick<?>)
                    com.axalotl.fabrolia.common.regions.TickDomains.recoverFromBucketInvariantViolation(
                            (LevelChunkTicks) (Object) this, e,
                            (java.util.function.Supplier) original::call);
        }
    }

    @Unique
    private static final java.util.concurrent.atomic.AtomicBoolean fabrolia$BLANKET_CHECKED =
            new java.util.concurrent.atomic.AtomicBoolean();

    @Unique
    private static final org.slf4j.Logger fabrolia$LOGGER =
            org.slf4j.LoggerFactory.getLogger("Fabrolia/TickContainer");

    /**
     * One-shot loud check that {@code LevelChunkTicks} is still in the
     * {@code SyncAllMixin} blanket set. Everything above assumes ONE monitor
     * over the whole container API; the wraps here can only take it for the
     * three methods they cover, and {@code schedule}/{@code peek}/
     * {@code hasScheduledTick}/{@code unpack} — the other half of every
     * invariant Lithium's cached head pointer depends on — get it from the
     * blanket alone. Removing the {@code SyncAllMixin} entry without replacing
     * that coverage re-arms Lithium audit §4.4/§4.5/§4.6 silently, so make it
     * loud instead.
     *
     * <p>Counts synchronized declared methods rather than naming one: runtime
     * method names are intermediary on Fabric, so a name lookup would be a
     * false alarm generator. The blanket sets the bit on every non-static
     * method of the class; without it essentially none carry it. Runs off the
     * cold {@code getAll}/{@code pack} paths, never from {@code poll}.
     */
    @Unique
    private static void fabrolia$assertBlanketSyncOnce() {
        if (fabrolia$BLANKET_CHECKED.get() || !fabrolia$BLANKET_CHECKED.compareAndSet(false, true)) {
            return;
        }
        int synced = 0, eligible = 0;
        for (java.lang.reflect.Method m : LevelChunkTicks.class.getDeclaredMethods()) {
            int mod = m.getModifiers();
            if (java.lang.reflect.Modifier.isStatic(mod) || m.isSynthetic() || m.isBridge()) {
                continue;
            }
            eligible++;
            if (java.lang.reflect.Modifier.isSynchronized(mod)) {
                synced++;
            }
        }
        if (synced == 0) {
            fabrolia$LOGGER.error("LevelChunkTicks is NO LONGER blanket-synchronized ({} eligible methods, 0 with the bit) — "
                    + "the SyncAllMixin entry was removed without replacing the per-container monitor. "
                    + "schedule()/peek()/unpack() are now unguarded against concurrent drains, which re-arms "
                    + "Lithium's cached-head hazards (audit §4.4 silent chunk death, §4.5 bucket destruction, "
                    + "§4.6 compaction under a drainer). Restore the entry or wrap the remaining API.", eligible);
        } else {
            fabrolia$LOGGER.info("LevelChunkTicks container monitor in force: {}/{} declared methods synchronized",
                    synced, eligible);
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
