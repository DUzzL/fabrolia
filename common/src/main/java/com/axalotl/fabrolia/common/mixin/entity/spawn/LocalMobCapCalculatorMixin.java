package com.axalotl.fabrolia.common.mixin.entity.spawn;

import com.axalotl.fabrolia.api.fastutil.Long2ObjectConcurrentHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LocalMobCapCalculator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(value = LocalMobCapCalculator.class, priority = 1100)
public abstract class LocalMobCapCalculatorMixin implements com.axalotl.fabrolia.api.spawn.FabroliaLocalCapView {

    /** Capture first, then acquire atomically against the arena owner's close. */
    @org.spongepowered.asm.mixin.Unique
    private volatile NativeArena fabrolia$capArena;

    @Override
    public void fabrolia$setCapArena(NativeArena arena) {
        this.fabrolia$capArena = arena;
    }

    /** Set once, before first use, by the island lane that owns this calculator. */
    @org.spongepowered.asm.mixin.Unique
    private boolean fabrolia$serial;

    /** An island lane's calculator lives and dies inside one coordinator's
     *  sequential chunk pass, the exact setting vanilla's plain maps are
     *  correct in: the concurrent swap below exists only for Main's parallel
     *  lane and would make every near-player lookup box and probe a CHM. */
    @Override
    public void fabrolia$useSerialLane() {
        if (this.fabrolia$capArena != null || !this.playerMobCounts.isEmpty()
                || !this.playersNearChunk.isEmpty()) {
            throw new IllegalStateException(
                    "serial local-cap lane requires a fresh calculator without an arena");
        }
        this.playerMobCounts = new java.util.HashMap<>();
        this.playersNearChunk = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        this.fabrolia$serial = true;
    }

    @Override
    public boolean fabrolia$serialLane() {
        return this.fabrolia$serial;
    }

    @Inject(method = "canSpawn", at = @At("HEAD"), cancellable = true)
    private void fabrolia$rustCanSpawn(net.minecraft.world.entity.MobCategory category, ChunkPos pos,
                                    CallbackInfoReturnable<Boolean> cir) {
        NativeArena arena = this.fabrolia$capArena;
        if (arena == null || !arena.tryAcquire()) return;
        Throwable failure = null;
        try {
            // vanilla canSpawn — ∃ near non-spectator player with room.
            int r = arena.canSpawn(category.ordinal(), pos.x(), pos.z());
            if (r < 0) {
                throw new IllegalStateException("native local-cap canSpawn failed");
            }
            cir.setReturnValue(r == 1);
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            arena.release(failure);
        }
    }

    /** Statics live in {@link com.axalotl.fabrolia.common.parallelised.utils.LocalCapSeedBridge} —
     *  a static initializer in THIS mixin silently broke the target's instance
     *  {@code @Unique} field init (2026-07-21 crash). Do not
     *  add initialized static fields here. */
    /** Runs of equal (chunk, category) entries collapse into one weighted
     *  seed step: the incremental census emits its entries grouped that way,
     *  so Main replays one real {@code addMob} per occupied chunk and category
     *  instead of one per mob. */
    @Override
    public void fabrolia$seedJavaShadow(long[] chunks, int[] cats, int n) {
        int i = 0;
        while (i < n) {
            long chunk = chunks[i];
            int cat = cats[i];
            int end = i + 1;
            while (end < n && chunks[end] == chunk && cats[end] == cat) {
                end++;
            }
            this.fabrolia$addMobs(chunk, cat, end - i);
            i = end;
        }
    }

    @Inject(method = "addMob", at = @At("HEAD"), cancellable = true)
    private void fabrolia$rustAddMob(ChunkPos pos, net.minecraft.world.entity.MobCategory category,
                                  CallbackInfo ci) {
        if (com.axalotl.fabrolia.common.parallelised.utils.LocalCapSeedBridge.SEED_BYPASS.get() != null) return;
        NativeArena arena = this.fabrolia$capArena;
        if (arena == null || !arena.tryAcquire()) return;
        Throwable failure = null;
        try {
            arena.addMob(category.ordinal(), pos.x(), pos.z());
            ci.cancel();
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            arena.release(failure);
        }
    }

    @Shadow
    protected abstract List<ServerPlayer> getPlayersNear(ChunkPos pos);

    /** Count-weighted Java-map seed (task #21): {@code count} addMob calls
     *  collapsed to ONE real call through the installed addMob, whose touched
     *  counts get the other {@code count - 1} copies. Java maps only — the
     *  native arena has its own bulk seed at build.
     *
     *  <p>The real call matters (2026-10-24 tunnel-bore review): the seed used
     *  to pick players with vanilla {@code getPlayersNear} while approval and
     *  post-spawn {@code addMob} run the installed rule (VMP overwrites both
     *  with its area index). Any disagreement between the two rules leaves a
     *  near player without counts, and vanilla {@code canSpawn} treats a player
     *  without counts as "under cap". Seeding through the same addMob makes
     *  every rule — vanilla, VMP, or another mod — agree with itself. */
    @Override
    public void fabrolia$addMobs(long packedChunk, int categoryOrdinal, int count) {
        if (count <= 0) {
            return;
        }
        ChunkPos pos = new ChunkPos(ChunkPos.getX(packedChunk), ChunkPos.getZ(packedChunk));
        net.minecraft.world.entity.MobCategory cat =
                com.axalotl.fabrolia.common.parallelised.utils.LocalCapSeedBridge
                        .CATS[categoryOrdinal];
        java.util.ArrayList<LocalMobCapCalculator.MobCounts> touched =
                com.axalotl.fabrolia.common.parallelised.utils.LocalCapSeedBridge.beginSeedRecording();
        try {
            ((LocalMobCapCalculator) (Object) this).addMob(pos, cat);
        } finally {
            com.axalotl.fabrolia.common.parallelised.utils.LocalCapSeedBridge.endSeedRecording();
        }
        try {
            for (int i = 0, n = touched.size(); i < n; i++) {
                ((com.axalotl.fabrolia.api.spawn.FabroliaMobCountsView) (Object) touched.get(i))
                        .fabrolia$addCopies(categoryOrdinal, count - 1);
            }
        } finally {
            touched.clear();
        }
    }

    /** Chunk-pass approval fused with an atomic pack reservation (task #32
     *  endgame — replaces the per-candidate recheck that starved farms at the
     *  cap; algorithm + bound argument in {@link
     *  com.axalotl.fabrolia.common.parallelised.utils.LocalCapPackReservation}).
     *
     *  <p>An open arena uses its creating provider; closed arenas use the Java
     *  shadow. Arenas are built only when the provider supports reservations.
     *  Errors after provider entry remain fatal, never a Java fallback. */
    @Override
    public int fabrolia$tryReservePack(long packedChunk, int categoryOrdinal, int pack) {
        NativeArena arena = this.fabrolia$capArena;
        if (arena != null && arena.tryAcquire()) {
            Throwable failure = null;
            try {
                int r = arena.tryReservePack(categoryOrdinal,
                        ChunkPos.getX(packedChunk), ChunkPos.getZ(packedChunk), pack);
                if (r > 0) {
                    return pack | com.axalotl.fabrolia.api.spawn.FabroliaLocalCapView.FABROLIA_NATIVE_RESERVATION;
                }
                if (r == 0) return 0;
                throw new IllegalStateException("native local-cap reservation failed");
            } catch (Throwable t) {
                failure = t;
                throw t;
            } finally {
                arena.release(failure);
            }
        }
        java.util.List<ServerPlayer> players = this.getPlayersNear(
                new ChunkPos(ChunkPos.getX(packedChunk), ChunkPos.getZ(packedChunk)));
        int n = players.size();
        if (n == 0) {
            return 0; // vanilla canSpawn: no near player → false
        }
        java.util.concurrent.atomic.AtomicIntegerArray[] scratch =
                com.axalotl.fabrolia.common.parallelised.utils.LocalCapSeedBridge.reserveScratch(n);
        for (int i = 0; i < n; i++) {
            LocalMobCapCalculator.MobCounts mc = this.playerMobCounts
                    .computeIfAbsent(players.get(i), k -> new LocalMobCapCalculator.MobCounts());
            scratch[i] = ((com.axalotl.fabrolia.api.spawn.FabroliaMobCountsView) (Object) mc)
                    .fabrolia$countsArray();
        }
        int cap = com.axalotl.fabrolia.common.parallelised.utils.LocalCapSeedBridge
                .CATS[categoryOrdinal].getMaxInstancesPerChunk();
        return com.axalotl.fabrolia.common.parallelised.utils.LocalCapPackReservation
                .tryReserve(scratch, n, categoryOrdinal, cap, pack) ? pack : 0;
    }

    @Override
    public void fabrolia$releasePack(long packedChunk, int categoryOrdinal, int token) {
        int pack = token & ~com.axalotl.fabrolia.api.spawn.FabroliaLocalCapView.FABROLIA_NATIVE_RESERVATION;
        if (pack <= 0) {
            return;
        }
        if ((token & com.axalotl.fabrolia.api.spawn.FabroliaLocalCapView.FABROLIA_NATIVE_RESERVATION) != 0) {
            NativeArena arena = this.fabrolia$capArena;
            // Retired native reservations must never be released into Java counts.
            if (arena == null || !arena.tryAcquire()) return;
            Throwable failure = null;
            try {
                arena.releasePack(categoryOrdinal,
                        ChunkPos.getX(packedChunk), ChunkPos.getZ(packedChunk), pack);
            } catch (Throwable t) {
                failure = t;
                throw t;
            } finally {
                arena.release(failure);
            }
            return;
        }
        java.util.List<ServerPlayer> players = this.getPlayersNear(
                new ChunkPos(ChunkPos.getX(packedChunk), ChunkPos.getZ(packedChunk)));
        int n = players.size();
        if (n == 0) {
            return;
        }
        java.util.concurrent.atomic.AtomicIntegerArray[] scratch =
                com.axalotl.fabrolia.common.parallelised.utils.LocalCapSeedBridge.reserveScratch(n);
        int m = 0;
        for (int i = 0; i < n; i++) {
            LocalMobCapCalculator.MobCounts mc = this.playerMobCounts.get(players.get(i));
            if (mc != null) {
                scratch[m++] = ((com.axalotl.fabrolia.api.spawn.FabroliaMobCountsView) (Object) mc)
                        .fabrolia$countsArray();
            }
        }
        com.axalotl.fabrolia.common.parallelised.utils.LocalCapPackReservation
                .release(scratch, m, categoryOrdinal, pack);
    }

    /** Live probe for /fabrolia regions debug spawncap (task #32): which layer
     *  approves a spawn at this chunk — native arena, Java maps, or neither. */
    @Override
    public String fabrolia$debugReport(long packedChunk) {
        ChunkPos pos = new ChunkPos(ChunkPos.getX(packedChunk), ChunkPos.getZ(packedChunk));
        StringBuilder sb = new StringBuilder(256);
        NativeArena arena = this.fabrolia$capArena;
        boolean acquired = arena != null && arena.tryAcquire();
        if (!acquired) sb.append("handle=0 provider=false");
        if (acquired) {
            Throwable failure = null;
            try {
                sb.append("handle=").append(arena.handle()).append(" provider=true");
                sb.append(" nativeCanSpawn[wa]=").append(arena.canSpawn(
                        net.minecraft.world.entity.MobCategory.WATER_AMBIENT.ordinal(),
                        ChunkPos.getX(packedChunk), ChunkPos.getZ(packedChunk)));
                sb.append(" nativeCanSpawn[monster]=").append(arena.canSpawn(
                        net.minecraft.world.entity.MobCategory.MONSTER.ordinal(),
                        ChunkPos.getX(packedChunk), ChunkPos.getZ(packedChunk)));
            } catch (Throwable t) {
                failure = t;
                sb.append(" nativeErr=").append(t);
            } finally {
                arena.release(failure);
            }
        }
        try {
            LocalMobCapCalculator self = (LocalMobCapCalculator) (Object) this;
            sb.append(" canSpawn[wa]=").append(self.canSpawn(
                    net.minecraft.world.entity.MobCategory.WATER_AMBIENT, pos));
        } catch (Throwable t) {
            sb.append(" canSpawnErr=").append(t);
        }
        try {
            List<ServerPlayer> near = this.getPlayersNear(pos);
            sb.append(" javaNear=").append(near.size());
            for (ServerPlayer sp : near) {
                LocalMobCapCalculator.MobCounts mc = this.playerMobCounts.get(sp);
                sb.append(" {").append(sp.getName().getString())
                  .append(" counts=").append(mc == null ? "NULL" : "present")
                  .append(mc == null ? "" : " roomWA=" + mc.canSpawn(
                          net.minecraft.world.entity.MobCategory.WATER_AMBIENT))
                  .append('}');
            }
        } catch (Throwable t) {
            sb.append(" javaErr=").append(t);
        }
        sb.append(" mapPlayers=").append(this.playerMobCounts.size());
        return sb.toString();
    }

    @Mutable
    @Final
    @Shadow
    private Map<ServerPlayer, LocalMobCapCalculator.MobCounts> playerMobCounts;

    @Mutable
    @Final
    @Shadow
    private Long2ObjectMap<List<ServerPlayer>> playersNearChunk;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void fabrolia$swapToConcurrentMaps(CallbackInfo ci) {
        ConcurrentHashMap<ServerPlayer, LocalMobCapCalculator.MobCounts> concurrentMobCounts = new ConcurrentHashMap<>();
        if (this.playerMobCounts != null && !this.playerMobCounts.isEmpty()) {
            concurrentMobCounts.putAll(this.playerMobCounts);
        }
        this.playerMobCounts = concurrentMobCounts;

        Long2ObjectConcurrentHashMap<List<ServerPlayer>> concurrentPlayersNear = new Long2ObjectConcurrentHashMap<>();
        if (this.playersNearChunk != null && !this.playersNearChunk.isEmpty()) {
            for (Long2ObjectMap.Entry<List<ServerPlayer>> e : this.playersNearChunk.long2ObjectEntrySet()) {
                concurrentPlayersNear.put(e.getLongKey(), e.getValue());
            }
        }
        this.playersNearChunk = concurrentPlayersNear;
    }

}
