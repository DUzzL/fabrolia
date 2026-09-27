package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The region owner's live own-chunk gate must agree with vanilla tick-list
 * membership: an entity is ticked only while its own holder still exposes a
 * FULL chunk. Every "no" case below is a holder state c2me leaves behind after
 * an asynchronous downgrade that our owner-claim queue has not yet delivered
 * as {@code stopTicking}.
 */
final class OwnChunkFullGateTest {
    private static final long POS = ChunkPos.pack(3, -7);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void missingHolderIsNotFull() throws Exception {
        Fixture f = new Fixture();
        assertFalse(RegionizedServer.fabrolia$ownChunkFull(f.source, POS));
    }

    @Test
    void completedFullFutureAdmitsTheTick() throws Exception {
        Fixture f = new Fixture();
        ChunkAccess chunk = (LevelChunk) unsafe().allocateInstance(LevelChunk.class);
        f.holder(ChunkStatus.FULL, CompletableFuture.completedFuture(ChunkResult.of(chunk)));
        assertTrue(RegionizedServer.fabrolia$ownChunkFull(f.source, POS));
    }

    @Test
    void clearedFullFutureIsNotFull() throws Exception {
        Fixture f = new Fixture();
        f.holder(ChunkStatus.FULL, null);
        assertFalse(RegionizedServer.fabrolia$ownChunkFull(f.source, POS));
    }

    @Test
    void pendingFullFutureIsNotFull() throws Exception {
        Fixture f = new Fixture();
        f.holder(ChunkStatus.FULL, new CompletableFuture<>());
        assertFalse(RegionizedServer.fabrolia$ownChunkFull(f.source, POS));
    }

    @Test
    void holderDowngradedBelowFullIsNotFullEvenWithAStaleFuture() throws Exception {
        Fixture f = new Fixture();
        ChunkAccess chunk = (LevelChunk) unsafe().allocateInstance(LevelChunk.class);
        f.holder(ChunkStatus.EMPTY, CompletableFuture.completedFuture(ChunkResult.of(chunk)));
        assertFalse(RegionizedServer.fabrolia$ownChunkFull(f.source, POS));
    }

    @Test
    void readIsLivePerCall() throws Exception {
        Fixture f = new Fixture();
        ChunkAccess chunk = (LevelChunk) unsafe().allocateInstance(LevelChunk.class);
        f.holder(ChunkStatus.FULL, CompletableFuture.completedFuture(ChunkResult.of(chunk)));
        assertTrue(RegionizedServer.fabrolia$ownChunkFull(f.source, POS));
        f.visible.remove(POS);
        assertFalse(RegionizedServer.fabrolia$ownChunkFull(f.source, POS));
    }

    private static final class Fixture {
        final ServerChunkCache source;
        final Long2ObjectLinkedOpenHashMap<ChunkHolder> visible = new Long2ObjectLinkedOpenHashMap<>();

        Fixture() throws Exception {
            sun.misc.Unsafe unsafe = unsafe();
            source = (ServerChunkCache) unsafe.allocateInstance(ServerChunkCache.class);
            ChunkMap map = (ChunkMap) unsafe.allocateInstance(ChunkMap.class);
            set(ChunkMap.class, map, "visibleChunkMap", visible);
            set(ServerChunkCache.class, source, "chunkMap", map);
        }

        /** Installs a holder at {@link #POS} allowed up to {@code highest} whose FULL slot holds {@code full}. */
        void holder(ChunkStatus highest, CompletableFuture<ChunkResult<ChunkAccess>> full) throws Exception {
            ChunkHolder holder = (ChunkHolder) unsafe().allocateInstance(ChunkHolder.class);
            AtomicReferenceArray<CompletableFuture<ChunkResult<ChunkAccess>>> futures =
                    new AtomicReferenceArray<>(ChunkStatus.FULL.getIndex() + 1);
            futures.set(ChunkStatus.FULL.getIndex(), full);
            set(GenerationChunkHolder.class, holder, "futures", futures);
            set(GenerationChunkHolder.class, holder, "highestAllowedStatus", highest);
            visible.put(POS, holder);
        }
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }
}
