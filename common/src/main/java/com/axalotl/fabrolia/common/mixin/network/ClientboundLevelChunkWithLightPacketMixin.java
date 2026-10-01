package com.axalotl.fabrolia.common.mixin.network;

import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.IslandRegistry;
import com.axalotl.fabrolia.common.regions.OwnerBusyException;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
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
 *
 * <p>26.3 made the packet a record: the chunk data is now built in the
 * delegating constructor's {@code this(...)} argument list, before the
 * instance exists, so the handler is static.
 */
@Mixin(ClientboundLevelChunkWithLightPacket.class)
public abstract class ClientboundLevelChunkWithLightPacketMixin {

    @WrapOperation(
            method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/lighting/LevelLightEngine;Ljava/util/BitSet;Ljava/util/BitSet;)V",
            at = @At(value = "NEW", target = "(Lnet/minecraft/world/level/chunk/LevelChunk;)Lnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;")
    )
    private static ClientboundLevelChunkPacketData fabrolia$stableChunkData(LevelChunk chunk,
                                                                 Operation<ClientboundLevelChunkPacketData> original) {
        IslandRegistry.PIsland owner = null;
        boolean mainClaimed = false;
        boolean poolClaimed = false;
        ClientboundLevelChunkPacketData[] result = null;
        Throwable failure = null;
        try {
            if (chunk.getLevel() instanceof ServerLevel sl) {
                RegionizedServer rs = RegionScheduler.forLevel(sl);
                if (rs != null && rs.asyncActive
                        && !rs.currentThreadHoldsAllClaims()) {
                    owner = rs.islandOwnerOfChunk(chunk.getPos().pack());
                    if (owner != null && RegionScheduler.isOwningCoordinator(owner)) {
                        // The OWNING unit itself is building (ChunkPacketSnapshots
                        // end-of-unit drain): already exclusive, no claim needed.
                        owner = null;
                    }
                    if (owner != null) {
                        MinecraftServer srv = sl.getServer();
                        if (srv != null
                                && RegionalPlayerEntityTicksV2
                                        .isPhysicalServerThread(srv)) {
                            mainClaimed = IslandCommands.mainClaimScoped(owner);
                        } else {
                            long spinUntil = System.nanoTime() + 200_000L;
                            while (!(poolClaimed = owner.tryClaimAutonomousWork())) {
                                if (System.nanoTime() >= spinUntil) {
                                    throw OwnerBusyException.INSTANCE;
                                }
                                Thread.onSpinWait();
                            }
                            // poolClaimed is already true before this first
                            // fallible post-CAS operation, so the outer failure
                            // envelope owns and releases the exact accepted claim.
                            owner.noteClaim("pktPool");
                        }
                    }
                }
            }
            // Allocation belongs to the accepted-claim envelope too: an OOME
            // here must select W and release, never strand pktPool ownership.
            ClientboundLevelChunkPacketData[] admittedResult =
                    new ClientboundLevelChunkPacketData[1];
            result = admittedResult;
            LevelChunkSection[] sections = chunk.getSections();
            int locked = fabrolia$readLockSections(sections);
            try {
                RegionScheduler.runSuccessBodyIfHealthy(
                        () -> admittedResult[0] = original.call(chunk));
            } catch (Throwable bodyFailure) {
                failure = RegionScheduler.publishFatalAndSelectWinner(bodyFailure);
            }
            try {
                fabrolia$readUnlockSections(sections, locked);
            } catch (Throwable cleanupFailure) {
                failure = fabrolia$publishMergedFailure(failure, cleanupFailure);
            }
        } catch (OwnerBusyException refusal) {
            // A bounded pool refusal owns no claim and is the documented
            // caller-visible signal to retry construction on Main.
            if (!mainClaimed && !poolClaimed) {
                throw refusal;
            }
            failure = RegionScheduler.publishFatalAndSelectWinner(refusal);
        } catch (Throwable setupFailure) {
            failure = fabrolia$publishMergedFailure(failure, setupFailure);
        }
        if (mainClaimed) {
            try {
                IslandCommands.mainRelease(owner);
            } catch (Throwable cleanupFailure) {
                failure = fabrolia$publishMergedFailure(failure, cleanupFailure);
            }
        } else if (poolClaimed) {
            try {
                owner.clearClaim();
            } catch (Throwable cleanupFailure) {
                failure = fabrolia$publishMergedFailure(failure, cleanupFailure);
            }
            try {
                owner.unitClaim.set(false);
            } catch (Throwable cleanupFailure) {
                failure = fabrolia$publishMergedFailure(failure, cleanupFailure);
            }
        }
        if (failure == null) {
            try {
                // A peer may select W during mandatory unlock/claim release;
                // that generation has no legal packet-snapshot success return.
                RegionScheduler.commitSuccessIfHealthy(() -> {});
            } catch (Throwable peerFailure) {
                failure = RegionScheduler.publishFatalAndSelectWinner(peerFailure);
            }
        }
        if (failure != null) {
            ClientboundLevelChunkWithLightPacketMixin.<RuntimeException>fabrolia$rethrow(failure);
        }
        return result[0];
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
            t = RegionScheduler.publishFatalAndSelectWinner(t);
            try {
                fabrolia$readUnlockSections(sections, locked);
            } catch (Throwable cleanupFailure) {
                t = fabrolia$publishMergedFailure(t, cleanupFailure);
            }
            ClientboundLevelChunkWithLightPacketMixin.<RuntimeException>fabrolia$rethrow(t);
            return locked;
        }
    }

    @Unique
    private static void fabrolia$readUnlockSections(LevelChunkSection[] sections, int locked) {
        Throwable failure = null;
        for (int i = locked - 1; i >= 0; i--) {
            try {
                ((FabroliaPalettedContainerLock) (Object) sections[i].getStates())
                        .fabrolia$readUnlock();
            } catch (Throwable cleanupFailure) {
                failure = fabrolia$publishMergedFailure(failure, cleanupFailure);
            }
        }
        if (failure != null) {
            ClientboundLevelChunkWithLightPacketMixin.<RuntimeException>fabrolia$rethrow(failure);
        }
    }

    @Unique
    private static Throwable fabrolia$mergeFailure(Throwable primary, Throwable cleanup) {
        if (primary == null) return cleanup;
        if (cleanup != null && cleanup != primary) {
            try {
                primary.addSuppressed(cleanup);
            } catch (Throwable ignored) {
                // Cleanup diagnostics cannot replace the packet-construction primary.
            }
        }
        return primary;
    }

    @Unique
    private static Throwable fabrolia$publishMergedFailure(
            Throwable primary, Throwable cleanup) {
        return RegionScheduler.publishFatalAndSelectWinner(
                fabrolia$mergeFailure(primary, cleanup));
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void fabrolia$rethrow(Throwable failure) throws T {
        throw (T) failure;
    }
}
