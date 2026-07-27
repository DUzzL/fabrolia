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

    /** Oxidizium Rust arena handle for this tick's calculator (0 = Java path). When non-zero,
     *  canSpawn/addMob route to the off-heap arena — no concurrent map touched, no race. */
    @org.spongepowered.asm.mixin.Unique
    private volatile long fabrolia$capHandle = 0L;

    /** In-flight FFI counter — the native free waits for 0 after detach
     *  (2026-07-17 UAF audit; see ParallelSpawnHelper.fabrolia$detachAndFree). */
    @org.spongepowered.asm.mixin.Unique
    private final java.util.concurrent.atomic.AtomicInteger fabrolia$capFlight =
            new java.util.concurrent.atomic.AtomicInteger();

    @Override
    public int fabrolia$capInFlight() {
        // Null-tolerant belt (2026-07-21): a mixin-init anomaly must degrade to
        // "no in-flight tracking" (free defers via the 1ms deadline), never crash
        // the tick loop.
        java.util.concurrent.atomic.AtomicInteger f = fabrolia$capFlight;
        return f == null ? 0 : f.get();
    }

    @Override
    public void fabrolia$setCapHandle(long handle) {
        this.fabrolia$capHandle = handle;
    }

    @Override
    public long fabrolia$capHandle() {
        return this.fabrolia$capHandle;
    }

    @Inject(method = "canSpawn", at = @At("HEAD"), cancellable = true)
    private void fabrolia$rustCanSpawn(net.minecraft.world.entity.MobCategory category, ChunkPos pos,
                                    CallbackInfoReturnable<Boolean> cir) {
        long h = this.fabrolia$capHandle;
        if (h == 0L) return;
        com.axalotl.fabrolia.common.utils.LocalCapBridge.Provider p =
                com.axalotl.fabrolia.common.utils.LocalCapBridge.provider();
        if (p == null) return; // provider gone mid-session → Java path
        long cp = pos.pack();
        java.util.concurrent.atomic.AtomicInteger flight = fabrolia$capFlight;
        if (flight != null) flight.incrementAndGet();
        try {
            // vanilla canSpawn — ∃ near non-spectator player with room. -1 → fall to Java.
            int r = p.canSpawn(h, category.ordinal(), ChunkPos.getX(cp), ChunkPos.getZ(cp));
            if (r >= 0) {
                cir.setReturnValue(r == 1);
            }
        } catch (Throwable t) {
            com.axalotl.fabrolia.common.utils.LocalCapBridge.disable(t);
        } finally {
            if (flight != null) flight.decrementAndGet();
        }
    }

    /** Statics live in {@link com.axalotl.fabrolia.common.parallelised.utils.LocalCapSeedBridge} —
     *  a static initializer in THIS mixin silently broke the target's instance
     *  {@code @Unique} field init (capFlight NPE, 2026-07-21 crash). Do not
     *  add initialized static fields here. */
    @Override
    public void fabrolia$seedJavaShadow(long[] chunks, int[] cats, int n) {
        LocalMobCapCalculator self = (LocalMobCapCalculator) (Object) this;
        com.axalotl.fabrolia.common.parallelised.utils.LocalCapSeedBridge.SEED_BYPASS.set(Boolean.TRUE);
        try {
            for (int i = 0; i < n; i++) {
                self.addMob(new ChunkPos(ChunkPos.getX(chunks[i]), ChunkPos.getZ(chunks[i])),
                        com.axalotl.fabrolia.common.parallelised.utils.LocalCapSeedBridge.CATS[cats[i]]);
            }
        } finally {
            com.axalotl.fabrolia.common.parallelised.utils.LocalCapSeedBridge.SEED_BYPASS.remove();
        }
    }

    @Inject(method = "addMob", at = @At("HEAD"), cancellable = true)
    private void fabrolia$rustAddMob(ChunkPos pos, net.minecraft.world.entity.MobCategory category,
                                  CallbackInfo ci) {
        if (com.axalotl.fabrolia.common.parallelised.utils.LocalCapSeedBridge.SEED_BYPASS.get() != null) return;
        long h = this.fabrolia$capHandle;
        if (h == 0L) return;
        com.axalotl.fabrolia.common.utils.LocalCapBridge.Provider p =
                com.axalotl.fabrolia.common.utils.LocalCapBridge.provider();
        if (p == null) return; // provider gone → let vanilla Java addMob run
        // local +1 to every near player. Global stays in vanilla Java.
        long cp = pos.pack();
        java.util.concurrent.atomic.AtomicInteger flight = fabrolia$capFlight;
        if (flight != null) flight.incrementAndGet();
        try {
            p.addMob(h, category.ordinal(), ChunkPos.getX(cp), ChunkPos.getZ(cp));
            ci.cancel();
        } catch (Throwable t) {
            com.axalotl.fabrolia.common.utils.LocalCapBridge.disable(t);
        } finally {
            if (flight != null) flight.decrementAndGet();
        }
    }

    @Shadow
    protected abstract List<ServerPlayer> getPlayersNear(ChunkPos pos);

    /** Count-weighted Java-map seed (task #21): {@code count} vanilla addMob
     *  calls collapsed to one near-player lookup per chunk. Java maps only —
     *  the native arena has its own bulk seed at build. */
    @Override
    public void fabrolia$addMobs(long packedChunk, int categoryOrdinal, int count) {
        if (count <= 0) {
            return;
        }
        ChunkPos pos = new ChunkPos(ChunkPos.getX(packedChunk), ChunkPos.getZ(packedChunk));
        net.minecraft.world.entity.MobCategory cat =
                net.minecraft.world.entity.MobCategory.values()[categoryOrdinal];
        for (ServerPlayer player : this.getPlayersNear(pos)) {
            LocalMobCapCalculator.MobCounts mc = this.playerMobCounts
                    .computeIfAbsent(player, k -> new LocalMobCapCalculator.MobCounts());
            for (int i = 0; i < count; i++) {
                mc.add(cat);
            }
        }
    }

    /** Live probe for /fabrolia regions debug spawncap (task #32): which layer
     *  approves a spawn at this chunk — native arena, Java maps, or neither. */
    @Override
    public String fabrolia$debugReport(long packedChunk) {
        ChunkPos pos = new ChunkPos(ChunkPos.getX(packedChunk), ChunkPos.getZ(packedChunk));
        LocalMobCapCalculator self = (LocalMobCapCalculator) (Object) this;
        StringBuilder sb = new StringBuilder(256);
        long h = this.fabrolia$capHandle;
        sb.append("handle=").append(h);
        com.axalotl.fabrolia.common.utils.LocalCapBridge.Provider p =
                com.axalotl.fabrolia.common.utils.LocalCapBridge.provider();
        sb.append(" provider=").append(p != null);
        if (h != 0L && p != null) {
            try {
                sb.append(" nativeCanSpawn[wa]=").append(p.canSpawn(h,
                        net.minecraft.world.entity.MobCategory.WATER_AMBIENT.ordinal(),
                        ChunkPos.getX(packedChunk), ChunkPos.getZ(packedChunk)));
                sb.append(" nativeCanSpawn[monster]=").append(p.canSpawn(h,
                        net.minecraft.world.entity.MobCategory.MONSTER.ordinal(),
                        ChunkPos.getX(packedChunk), ChunkPos.getZ(packedChunk)));
            } catch (Throwable t) {
                sb.append(" nativeErr=").append(t);
            }
        }
        try {
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

    @Inject(method = "getPlayersNear", at = @At("RETURN"), cancellable = true)
    private void onGetMobSpawnablePlayers(ChunkPos pos, CallbackInfoReturnable<List<ServerPlayer>> cir) {
        if (cir.getReturnValue() == null) {
            cir.setReturnValue(List.of());
        }
    }
}
