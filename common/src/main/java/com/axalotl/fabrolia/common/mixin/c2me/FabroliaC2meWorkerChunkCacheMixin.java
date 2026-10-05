package com.axalotl.fabrolia.common.mixin.c2me;

import com.axalotl.fabrolia.common.regions.WorkerChunkCache;
import com.bawnorton.mixinsquared.TargetHandler;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Region-worker FULL-chunk cache in front of C2ME's off-thread
 * {@code shortcutGetChunk} ({@link WorkerChunkCache} holds the exactness
 * argument). Injector order 950 places the lookup after the portal-scope
 * router (order 900, which must see every read) and before every
 * default-order HEAD handler, the first of which is C2ME's shortcut: a hit
 * returns exactly what that shortcut would return. Entries are captured only
 * at the shortcut's own return, from the answer it gave.
 */
@Mixin(value = ServerChunkCache.class, priority = 2000)
public abstract class FabroliaC2meWorkerChunkCacheMixin {

    @Inject(
            method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)"
                    + "Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"),
            cancellable = true,
            order = 950,
            require = 1,
            allow = 1)
    private void fabrolia$workerChunkCacheLookup(int x, int z, ChunkStatus status, boolean load,
                                                 CallbackInfoReturnable<ChunkAccess> cir) {
        if (status != ChunkStatus.FULL) return;
        WorkerChunkCache cache = WorkerChunkCache.forCurrentThread();
        if (cache == null) return;
        Object cached = cache.lookup(this, x, z);
        if (cached != null) {
            cir.setReturnValue((LevelChunk) cached);
            return;
        }
        cache.arm(this, x, z);
    }

    @Dynamic("C2ME injection handler selected through MixinSquared")
    @TargetHandler(
            mixin = "com.ishland.c2me.rewrites.chunksystem.mixin.MixinServerChunkManager",
            name = "shortcutGetChunk",
            prefix = "handler")
    @Inject(
            method = "@MixinSquared:Handler",
            at = @At("RETURN"),
            require = 1,
            remap = false)
    private void fabrolia$workerChunkCacheCapture(int x, int z, ChunkStatus status, boolean create,
                                                  CallbackInfoReturnable<ChunkAccess> shortcut,
                                                  CallbackInfo ci) {
        WorkerChunkCache cache = WorkerChunkCache.forCurrentThread();
        if (cache == null) return;
        Object answer = status == ChunkStatus.FULL && shortcut.isCancelled()
                && shortcut.getReturnValue() instanceof LevelChunk chunk ? chunk : null;
        cache.capture(this, x, z, answer);
    }
}
