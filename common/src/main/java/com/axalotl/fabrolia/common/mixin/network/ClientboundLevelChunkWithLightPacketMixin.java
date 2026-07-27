package com.axalotl.fabrolia.common.mixin.network;

import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.IslandRegistry;
import com.axalotl.fabrolia.common.regions.OwnerBusyException;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
import com.axalotl.fabrolia.common.utils.FabroliaPalettedContainerLock;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Coherent chunk-packet construction (2026-07-13 production race):
 * {@code ClientboundLevelChunkPacketData}'s constructor is the snapshot — it
 * serializes sections AND iterates the chunk's LIVE block-entity map and
 * heightmaps at construction time. Under async islands those structures are
 * mutated by the owner island's unit (a moving piston creates/removes B36
 * block entities every stroke), and packet builds run on main's connections
 * phase or the prebuild pool — concurrently with units. Result in production:
 * FastUtil {@code Object2ObjectOpenHashMap$MapIterator} NPE ("wrapped is
 * null") on Fabrolia-Tick-Pool during login sends near active machines, and —
 * worse — silently torn packets when the race didn't throw.
 *
 * <p>Fix: construct the packet data UNDER THE OWNER ISLAND CLAIM. With the
 * claim held, no unit phase runs: block states, block entities, and
 * heightmaps are one coherent logical instant (the user-facing snapshot
 * requirement), and the constructor's full serialization IS the immutable
 * snapshot — afterwards the packet carries only its own buffers. On main the
 * claim uses the fence-aware {@code mainClaimScoped} spin (drains chunk
 * services — cycle-free); on a pool thread it is a BOUNDED try (~200µs spin)
 * that signals {@link OwnerBusyException} so the caller falls back to the
 * main-thread build instead of ever blocking a pool worker on a busy island.
 * The section read-locks below stay as the palette-level belt for sync mode
 * (no islands, no claims) and for gap-cell chunks with no owner.
 */
@Mixin(ClientboundLevelChunkWithLightPacket.class)
public abstract class ClientboundLevelChunkWithLightPacketMixin {

    @WrapOperation(
            method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/lighting/LevelLightEngine;Ljava/util/BitSet;Ljava/util/BitSet;)V",
            at = @At(value = "NEW", target = "(Lnet/minecraft/world/level/chunk/LevelChunk;)Lnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;")
    )
    private ClientboundLevelChunkPacketData fabrolia$stableChunkData(LevelChunk chunk,
                                                                 Operation<ClientboundLevelChunkPacketData> original) {
        IslandRegistry.PIsland owner = null;
        boolean mainClaimed = false;
        boolean poolClaimed = false;
        if (chunk.getLevel() instanceof ServerLevel sl) {
            RegionizedServer rs = RegionScheduler.forLevel(sl);
            if (rs != null && rs.asyncActive && !rs.mainHoldsAllClaims()) {
                owner = rs.islandOwnerOfChunk(chunk.getPos().pack());
                if (owner != null && RegionScheduler.isOwningCoordinator(owner)) {
                    // The OWNING unit itself is building (ChunkPacketSnapshots
                    // end-of-unit drain): already exclusive, no claim needed.
                    owner = null;
                }
                if (owner != null) {
                    MinecraftServer srv = sl.getServer();
                    if (srv != null && srv.isSameThread()) {
                        mainClaimed = IslandCommands.mainClaimScoped(owner);
                    } else {
                        long spinUntil = System.nanoTime() + 200_000L;
                        while (!(poolClaimed = owner.unitClaim.compareAndSet(false, true))) {
                            if (System.nanoTime() >= spinUntil) {
                                throw OwnerBusyException.INSTANCE;
                            }
                            Thread.onSpinWait();
                        }
                        owner.noteClaim("pktPool"); // pure field writes — cannot throw
                    }
                }
            }
        }
        try {
            LevelChunkSection[] sections = chunk.getSections();
            int locked = fabrolia$readLockSections(sections);
            try {
                return original.call(chunk);
            } finally {
                fabrolia$readUnlockSections(sections, locked);
            }
        } finally {
            if (mainClaimed) {
                IslandCommands.mainRelease(owner);
            } else if (poolClaimed) {
                owner.clearClaim();
                owner.unitClaim.set(false);
            }
        }
    }

    @Unique
    private static int fabrolia$readLockSections(LevelChunkSection[] sections) {
        int locked = 0;
        try {
            for (; locked < sections.length; locked++) {
                ((FabroliaPalettedContainerLock) (Object) sections[locked].getStates()).fabrolia$readLock();
            }
            return locked;
        } catch (Throwable t) {
            fabrolia$readUnlockSections(sections, locked);
            throw t;
        }
    }

    @Unique
    private static void fabrolia$readUnlockSections(LevelChunkSection[] sections, int locked) {
        for (int i = locked - 1; i >= 0; i--) {
            ((FabroliaPalettedContainerLock) (Object) sections[i].getStates()).fabrolia$readUnlock();
        }
    }
}
