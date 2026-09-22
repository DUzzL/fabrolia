package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.NetherPortalPreparation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import java.util.concurrent.CompletableFuture;

@Mixin(value = ServerChunkCache.class, priority = 3000)
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

    @WrapMethod(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;")
    private ChunkAccess fabrolia$ownedPortalRead(int x, int z, ChunkStatus status,
                                               boolean load, Operation<ChunkAccess> original) {
        if (NetherPortalPreparation.readScopeActive()) {
            return NetherPortalPreparation.readChunk(this.level, x, z, status, load);
        }
        NetherPortalPreparation.retainGenerationChunk(this.level, x, z, status, load);
        return original.call(x, z, status, load);
    }
}
