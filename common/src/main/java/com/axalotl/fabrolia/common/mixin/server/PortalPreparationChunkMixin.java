package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.NetherPortalPreparation;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.concurrent.CompletableFuture;

@Mixin(value = ServerChunkCache.class, priority = 500)
public abstract class PortalPreparationChunkMixin implements NetherPortalPreparation.ChunkAccessBridge {
    @Shadow @Final private net.minecraft.world.level.TicketStorage ticketStorage;

    @Override
    public void fabrolia$removePortalTicket(net.minecraft.server.level.Ticket ticket,
                                           net.minecraft.world.level.ChunkPos chunk) {
        this.ticketStorage.removeTicket(ticket, chunk);
    }
    @Shadow @Final private ServerLevel level;

    @Invoker("getVisibleChunkIfPresent")
    public abstract net.minecraft.server.level.ChunkHolder fabrolia$portalHolder(long key);

    @Override
    public ChunkAccess fabrolia$portalChunkNow(int x, int z, ChunkStatus status) {
        net.minecraft.server.level.ChunkHolder holder =
                this.fabrolia$portalHolder(net.minecraft.world.level.ChunkPos.pack(x, z));
        return holder == null ? null : holder.getChunkIfPresent(status);
    }

    @Override
    @Invoker("getChunkFutureMainThread")
    public abstract CompletableFuture<ChunkResult<ChunkAccess>> fabrolia$portalChunkFuture(
            int x, int z, ChunkStatus status, boolean load);

    /**
     * Portal read/generation scopes route this thread's chunk reads, so this
     * callback must run before every other HEAD callback of {@code getChunk}
     * (Fabrolia's off-Main bridge, c2me's off-Main shortcut): injector order
     * 900 &lt; the default 1000, and the low mixin priority applies it first
     * even where {@code order} were ignored. Outside any scope (the steady
     * state) the cost is one shared read — no per-call Operation, argument
     * boxing or ThreadLocal probe.
     */
    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"), cancellable = true, order = 900)
    private void fabrolia$ownedPortalRead(int x, int z, ChunkStatus status, boolean load,
                                         CallbackInfoReturnable<ChunkAccess> cir) {
        if (!NetherPortalPreparation.chunkScopesOpen()) return;
        if (NetherPortalPreparation.readScopeActive()) {
            cir.setReturnValue(NetherPortalPreparation.readChunk(this.level, x, z, status, load));
            return;
        }
        NetherPortalPreparation.retainGenerationChunk(this.level, x, z, status, load);
    }
}
