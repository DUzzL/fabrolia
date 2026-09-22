package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.api.fastutil.ConcurrentLongLinkedOpenHashSet;
import com.axalotl.fabrolia.api.fastutil.LockedLong2ObjectMap;
import com.axalotl.fabrolia.api.fastutil.Long2ObjectConcurrentHashMap;
import com.axalotl.fabrolia.common.utils.FabroliaPoiLoadGate;
import com.axalotl.fabrolia.common.utils.PoiChunkLoadFence;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Task #38 — {@code SectionStorage} leaves the blanket ACC_SYNCHRONIZED set;
 * this mixin is the replacement for everything that monitor actually covered.
 *
 * <h3>Why the blanket bit had to go</h3>
 * It was one corner of the DistanceTracker/SectionStorage/PoiSection monitor
 * triangle behind the 2026-07-29 watchdog deadlocks (see 7e769ee), it
 * serialised every Lithium-optimised POI query in a level on one monitor, and
 * it never actually made reads safe — {@code getRecords} returns a lazy Stream
 * and Lithium's column methods lazy Iterables, all consumed after the monitor
 * exits.
 *
 * <h3>What replaces it, piece by piece</h3>
 * <ul>
 *   <li><b>{@code storage} map:</b> WRAPPED (never replaced) in a
 *       {@link LockedLong2ObjectMap} at {@code <init>} RETURN with
 *       {@code order = Integer.MAX_VALUE} — injector order, not mixin priority,
 *       is what deterministically runs after Lithium's own RETURN inject that
 *       installs its {@code ListeningLong2ObjectOpenHashMap}. The old
 *       {@code @Shadow} field initialiser was DEAD under Lithium (a field
 *       initialiser is not an injector and can never run after a RETURN
 *       inject); the concurrent map Fabrolia thought it had was never
 *       installed — the true root cause of the 2026-07-29 01:51 rehash AIOOBE.
 *       Wrapping keeps Lithium's {@code onEntryAdded}/{@code onEntryRemoved}
 *       listeners firing inside the write lock, which serialises the
 *       {@code columns} sidecar for free; if Fabrolia's map ever WON instead,
 *       {@code columns} would stay empty and every POI query would die on
 *       {@code "Failed to load POI section data!"}.</li>
 *   <li><b>unpack vs getOrLoad (D4):</b> {@link PoiChunkLoadFence} — losers of
 *       the {@code loadedChunks.add} race join a per-chunk future the winner
 *       completes only after the fan-out; main's {@code unpackPendingLoads}
 *       skips chunks a winner owns (otherwise the double fan-out would replace
 *       live sections with fresh disk copies — lost tickets, orphaned
 *       records). There is deliberately no post-entry {@code getOrLoad}
 *       retry: once a storage body has started, its exact failure propagates
 *       instead of replaying a potentially partial load. A column lease spans
 *       the subsequent required getter, so completing unpack does not admit
 *       unload before the result has been captured.</li>
 *   <li><b>{@code flushAll}/{@code tick} dirty-set (D8):</b> drain-loop
 *       semantics via {@code removeFirstLong()}; the vanilla
 *       {@code forEach + clear} silently discarded any {@code setDirty} that
 *       landed between the two once the monitor was gone.</li>
 *   <li><b>{@code getOrCreate}'s check-then-put:</b> same-section callers use
 *       a chunk stripe; the outer column lease additionally excludes unload
 *       until an absent section has been created and published.</li>
 *   <li><b>Lithium's {@code columns} + C2ME's {@code c2me$unloadPoi}:</b>
 *       {@code mixin/lithium/FabroliaLithiumPoiStorageMixin} and
 *       {@code mixin/c2me/PoiUnloadGuardMixin}.</li>
 * </ul>
 *
 * <h3>POI lock order (D7 — the corrected version; trust this over §6 of the
 * design doc, whose graph was wrong in three places)</h3>
 * <pre>
 *   STRIPE[64]  >  DT (tracker monitor)  >  loadLock  >  storageRW  >  {PS monitor, PoiRecord}
 * </pre>
 * with two load-bearing invariants the naive reading of that chain hides:
 * <ol>
 *   <li><b>No thread acquires DT while holding anything below it.</b>
 *       Guaranteed structurally by the tracker deferral
 *       ({@code PoiDistanceTrackerMixin}): every {@code * → DT} notification is
 *       an enqueue, drained only where nothing else is held (or already inside
 *       DT).</li>
 *   <li><b>No holder of loadLock/storageRW ever takes a PS or PoiRecord
 *       monitor.</b> This is NOT implied by the chain — the reverse edges
 *       exist and are legal: every PoiSection mutator (under the PS monitor)
 *       and every ticket op (under a stripe) runs {@code setDirty.run() →
 *       PoiManager.setDirty → super.setDirty → storage.get} →
 *       <b>storageRW.read</b>. So "PS/record → storageRW.read" is an upward
 *       edge, and the only reason it cannot cycle is that {@code writeChunk}'s
 *       packer ({@code PoiSection::pack}) and all section reads stay
 *       monitor-free forever. They read the immutable volatile view published
 *       by {@code PoiSectionMixin}; only structural writers briefly take PS to
 *       rebuild it. One {@code synchronized pack} would rebuild the
 *       flushAll-vs-release deadlock in RW-lock clothing. The design's "no
 *       upward lock" claim was false; this invariant is what actually bears
 *       the load.</li>
 * </ol>
 * Also part of the real graph: the {@code unpackChunk} fan-out calls
 * {@code setDirty} under {@code loadLock} in {@code unpackPendingLoads} ⇒
 * {@code loadLock → storageRW.read} as well as {@code → write}; C2ME's guarded
 * unload adds the same edges via {@code flush}. All consistent with
 * {@code loadLock > storageRW}. And the mirror rule for the RW lock itself
 * (review D1's aggravation): <b>never hold storageRW — either mode — across
 * anything that can reach {@code unpackChunk} or {@code loadLock}</b>. A
 * read-hold surviving into unpack is {@code storageRW → loadLock}, an AB-BA
 * against main's {@code loadLock → storageRW} in {@code unpackPendingLoads}
 * (StampedLock cannot upgrade, and readers block writer admission).
 */
@Mixin(value = SectionStorage.class, priority = 2000)
public abstract class SectionStorageMixin<R, P> implements AutoCloseable, FabroliaPoiLoadGate {

    @Unique
    private static final Logger fabrolia$LOGGER = LoggerFactory.getLogger("Fabrolia/PoiStorage");

    @Unique
    private static final AtomicBoolean fabrolia$STRUCTURES_LOGGED = new AtomicBoolean();

    /**
     * No initialiser: under Lithium the installed map is decided by Lithium's
     * {@code <init>} RETURN inject, and a {@code @Shadow} field initialiser is
     * dead code that only documents a lie. The wrap happens in
     * {@link #fabrolia$wrapStorage}.
     */
    @Mutable
    @Shadow
    @Final
    private Long2ObjectMap<Optional<R>> storage;

    @Shadow final private LongLinkedOpenHashSet dirtyChunks = new ConcurrentLongLinkedOpenHashSet();

    @Shadow final private Long2ObjectMap<CompletableFuture<Optional<SectionStorage.PackedChunk<P>>>> pendingLoads = new Long2ObjectConcurrentHashMap<>();

    @Shadow final private LongSet loadedChunks = new ConcurrentLongLinkedOpenHashSet();

    @Shadow
    @Final
    private Object loadLock;

    @Shadow
    protected abstract void writeChunk(ChunkPos chunkPos);

    @Shadow
    protected abstract void unpackPendingLoads();

    @Shadow
    protected abstract void unpackChunk(ChunkPos chunkPos);

    @Shadow
    protected abstract boolean outsideStoredRange(long sectionKey);


    @Shadow
    protected abstract Optional<R> getOrLoad(long sectionKey);
    @Unique
    private final PoiChunkLoadFence fabrolia$loadFence = new PoiChunkLoadFence();

    /**
     * Wrap whatever map is installed by the time every {@code <init>} RETURN
     * injector with a lower {@code order} has run: Lithium's listening map
     * when Lithium is present, the vanilla plain map otherwise. The
     * {@code instanceof} guard keeps double-application idempotent.
     */
    @Inject(method = "<init>", at = @At("RETURN"), order = Integer.MAX_VALUE)
    private void fabrolia$wrapStorage(CallbackInfo ci) {
        if (!(this.storage instanceof LockedLong2ObjectMap)) {
            this.storage = new LockedLong2ObjectMap<>(this.storage);
        }
    }


    @Override
    public <T> T fabrolia$acquirePoiColumn(long chunkKey, java.util.function.Supplier<T> body) {
        return this.fabrolia$loadFence.acquire(this.loadLock, chunkKey, body);
    }

    @WrapMethod(method = "close")
    private void fabrolia$closePoiStorage(Operation<Void> original) {
        try {
            original.call();
        } finally {
            // Vanilla shutdown has already flushed storage and quiesced its
            // users. No delayed callback may retain or re-enter closed IO.
            this.fabrolia$loadFence.discardUnloads(this.loadLock);
        }
    }

    @WrapMethod(method = "getOrCreate")
    private R fabrolia$acquireCreatedSection(long sectionKey, Operation<R> original) {
        if (this.outsideStoredRange(sectionKey)) return original.call(sectionKey);
        long chunkKey = ChunkPos.pack(net.minecraft.core.SectionPos.x(sectionKey),
                net.minecraft.core.SectionPos.z(sectionKey));
        return this.fabrolia$acquirePoiColumn(chunkKey, () -> original.call(sectionKey));
    }

    @Override
    public void fabrolia$unloadPoiColumn(long chunkKey, Runnable body) {
        this.fabrolia$loadFence.unload(this.loadLock, chunkKey, this::fabrolia$pendingPoiRead, body);
    }

    @Override
    public void fabrolia$beginPoiResidency(long chunkKey) {
        this.fabrolia$loadFence.cancelUnload(this.loadLock, chunkKey);
    }

    @Unique
    private boolean fabrolia$pendingPoiRead(long chunkKey) {
        CompletableFuture<?> pending = this.pendingLoads.get(chunkKey);
        return pending != null && !pending.isDone();
    }

    @WrapMethod(method = "getOrLoad")
    private Optional<R> fabrolia$acquireSectionResult(long sectionKey, Operation<Optional<R>> original) {
        if (this.outsideStoredRange(sectionKey)) return original.call(sectionKey);
        long chunkKey = ChunkPos.pack(net.minecraft.core.SectionPos.x(sectionKey),
                net.minecraft.core.SectionPos.z(sectionKey));
        return this.fabrolia$acquirePoiColumn(chunkKey, () -> {
            // A hot first getter can otherwise observe a concurrent unpack's
            // first published section before that unpack has completed.
            this.unpackChunk(ChunkPos.unpack(chunkKey));
            return original.call(sectionKey);
        });
    }


    @Override
    public Optional<com.axalotl.fabrolia.common.utils.PoiSectionReadSnapshot>
            fabrolia$portalSectionSnapshot(long sectionKey) {
        long chunkKey = net.minecraft.core.SectionPos.of(sectionKey).chunk().pack();
        synchronized (this.loadLock) {
            if (this.fabrolia$loadFence.isInFlight(chunkKey)) return null;
            Optional<R> section = this.storage.get(sectionKey);
            if (section == null) return null;
            return section.map(value ->
                    ((com.axalotl.fabrolia.common.utils.FabroliaPoiSectionReadAccess) value)
                            .fabrolia$poiReadSnapshot());
        }
    }

    @Override
    public void fabrolia$materializePortalColumn(ChunkPos chunk, int sectionY) {
        // The caller has observed prefetch completion and owns Main exclusion.
        this.getOrLoad(net.minecraft.core.SectionPos.of(chunk, sectionY).asLong());
    }

    /**
     * D4: per-chunk completion handshake around the vanilla unpack. The
     * vanilla body keeps its own {@code loadLock}-gated bookkeeping and does
     * the disk {@code join()} with no lock we hold — the fence only decides
     * who runs it and when losers may proceed. Replaces the previous
     * {@code synchronized} wrap, which serialised ALL chunk unpacks on the
     * instance monitor AND still left the loser window open (the loser
     * returned from inside vanilla's first loadLock block regardless of any
     * outer monitor).
     */
    @WrapMethod(method = "unpackChunk(Lnet/minecraft/world/level/ChunkPos;)V")
    private void fabrolia$unpackHandshake(ChunkPos chunkPos, Operation<Void> original) {
        this.fabrolia$loadFence.runExclusively(this.loadLock, chunkPos.pack(),
                this.loadedChunks::contains, () -> original.call(chunkPos));
    }

    /**
     * D4, main-thread half: {@code tick → unpackPendingLoads} iterates
     * {@code pendingLoads} under {@code loadLock} and fans out every completed
     * future. A winner mid-handshake has its disk future registered there too;
     * if main fanned it out as well, BOTH would run the fan-out and the second
     * pass would overwrite freshly-live sections with stale disk copies (lost
     * tickets — the double-bed class of bug). Returning a never-completed
     * future makes vanilla's {@code getNow(null)} skip the entry; the winner
     * removes it when it finishes. Runs inside vanilla's
     * {@code synchronized (loadLock)} block, which the fence requires.
     */
    @WrapOperation(
            method = "unpackPendingLoads",
            at = @At(value = "INVOKE",
                    target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap$Entry;getValue()Ljava/lang/Object;",
                    remap = false))
    private Object fabrolia$skipWinnersLoads(Long2ObjectMap.Entry<?> entry, Operation<Object> original) {
        if (this.fabrolia$loadFence.isInFlight(entry.getLongKey())) {
            return new CompletableFuture<>();
        }
        return original.call(entry);
    }

    /**
     * D8: {@code flushAll} is {@code forEach(...); clear()} — with the blanket
     * monitor gone, a worker's {@code setDirty} landing between the two was
     * silently discarded (unsaved POI mutation on autosave/shutdown). Drain
     * loop: a concurrent {@code setDirty} either lands before its
     * {@code removeFirstLong} (flushed now) or stays for the next flush.
     */
    @WrapMethod(method = "flushAll")
    private void fabrolia$drainFlushAll(Operation<Void> original) {
        while (!this.dirtyChunks.isEmpty()) {
            long pos;
            try {
                pos = this.dirtyChunks.removeFirstLong();
            } catch (NoSuchElementException raced) {
                break;
            }
            this.writeChunk(ChunkPos.unpack(pos));
        }
    }

    /**
     * D8, same defect in {@code tick}: vanilla's {@code iterator() + remove()}
     * interleaving a concurrent re-add of the same key can drop dirtiness.
     * Same drain loop, bounded by {@code haveTime} exactly like vanilla, then
     * vanilla's own {@code unpackPendingLoads} tail.
     */
    @WrapMethod(method = "tick")
    private void fabrolia$drainTick(BooleanSupplier haveTime, Operation<Void> original) {
        fabrolia$assertConcurrentStructuresOnce();
        while (haveTime.getAsBoolean() && !this.dirtyChunks.isEmpty()) {
            long pos;
            try {
                pos = this.dirtyChunks.removeFirstLong();
            } catch (NoSuchElementException raced) {
                break;
            }
            this.writeChunk(ChunkPos.unpack(pos));
        }
        this.unpackPendingLoads();
        this.fabrolia$loadFence.drainUnloads(this.loadLock, this::fabrolia$pendingPoiRead);
    }

    /**
     * One-shot loud assertion of the runtime classes of all four collections.
     * Lithium already demonstrated the failure mode this guards against: a
     * replacement another mod's injector silently defeats. Runs at first
     * {@code tick} (not at {@code <init>}) so it observes the final state
     * after every constructor injector, whatever their order.
     */
    @Unique
    private void fabrolia$assertConcurrentStructuresOnce() {
        if (!fabrolia$STRUCTURES_LOGGED.compareAndSet(false, true)) {
            return;
        }
        String storageDesc = this.storage instanceof LockedLong2ObjectMap<Optional<R>> locked
                ? "LockedLong2ObjectMap(" + locked.delegate().getClass().getName() + ")"
                : this.storage.getClass().getName();
        boolean ok = this.storage instanceof LockedLong2ObjectMap
                && this.dirtyChunks instanceof ConcurrentLongLinkedOpenHashSet
                && this.pendingLoads instanceof Long2ObjectConcurrentHashMap
                && this.loadedChunks instanceof ConcurrentLongLinkedOpenHashSet;
        if (ok) {
            fabrolia$LOGGER.info("POI storage concurrency structures live: storage={}, dirtyChunks={}, pendingLoads={}, loadedChunks={}",
                    storageDesc, this.dirtyChunks.getClass().getSimpleName(),
                    this.pendingLoads.getClass().getSimpleName(), this.loadedChunks.getClass().getSimpleName());
        } else {
            IllegalStateException failure = new IllegalStateException(
                    "POI storage concurrency structures defeated: storage=" + storageDesc
                            + ", dirtyChunks=" + this.dirtyChunks.getClass().getName()
                            + ", pendingLoads=" + this.pendingLoads.getClass().getName()
                            + ", loadedChunks=" + this.loadedChunks.getClass().getName());
            com.axalotl.fabrolia.common.regions.IslandCommands.publishAndPropagate(failure);
        }
    }
}
