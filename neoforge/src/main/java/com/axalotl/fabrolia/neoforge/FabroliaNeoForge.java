package com.axalotl.fabrolia.neoforge;

import com.axalotl.fabrolia.common.FabroliaCommon;
import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.commands.FabroliaCommand;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.neoforge.platform.NeoForgePermissions;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.javafmlmod.FMLModContainer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import org.slf4j.Logger;

import static com.axalotl.fabrolia.common.config.FabroliaConfig.getParallelism;
import static com.axalotl.fabrolia.neoforge.config.FabroliaConfig.SPEC;
import static com.axalotl.fabrolia.neoforge.config.FabroliaConfig.loadConfig;

@Mod(FabroliaNeoForge.MOD_ID)
public class FabroliaNeoForge extends FabroliaCommon {
    public static final String MOD_ID = "fabrolia";
    public static final Logger LOGGER = LogUtils.getLogger();

    public FabroliaNeoForge(FMLModContainer container) {
        LOGGER.info("Initializing Fabrolia...");
        NeoForge.EVENT_BUS.register(this);
        LOGGER.info("Initializing Fabrolia Config...");
        container.registerConfig(ModConfig.Type.COMMON, SPEC, "fabrolia.toml");
        LOGGER.info("Fabrolia Initialized Successfully!");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Fabrolia Setting up thread-pool...");
        this.initialize();
        loadConfig();
        com.axalotl.fabrolia.neoforge.config.FabroliaConfig.registerPersister();
        ParallelProcessor.setServer(event.getServer());
        ParallelProcessor.setupThreadPool(getParallelism());
        RegionScheduler.start(event.getServer());
    }

    @SubscribeEvent
    public void registerCommandsEvent(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        FabroliaCommand.register(dispatcher);
    }

    /** Per-level teardown parity with the Fabric loader (2026-07-17 audit:
     *  NeoForge wired nothing — precompute slot, spawn index, and the native
     *  cap arena retained unloaded ServerLevels forever). */
    /** Event-driven FULL-chunk tracking, loader parity (audit #10): Fabric
     *  wires ServerChunkEvents.CHUNK_LOAD/UNLOAD because the vanilla
     *  updateChunkScheduling seam is c2me-bypassed — without these the
     *  NeoForge side ran on the single mixin seam and region population
     *  drifted (islands stuck merged / eviction reclaiming nothing). */
    @SubscribeEvent
    public void onChunkLoad(net.neoforged.neoforge.event.level.ChunkEvent.Load event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel sl
                && event.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk lc) {
            com.axalotl.fabrolia.common.regions.RegionScheduler.onChunkFullChange(
                    sl, lc.getPos().pack(), true, lc);
        }
    }

    @SubscribeEvent
    public void onChunkUnload(net.neoforged.neoforge.event.level.ChunkEvent.Unload event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel sl
                && event.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk lc) {
            com.axalotl.fabrolia.common.regions.RegionScheduler.onChunkFullChange(
                    sl, lc.getPos().pack(), false, lc);
        }
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel sl) {
            com.axalotl.fabrolia.common.parallelised.utils.ParallelSpawnHelper.onLevelUnload(sl);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        ParallelProcessor.stopServerRuntime();
    }

    @SubscribeEvent
    public void handlePermissionNodesGather(PermissionGatherEvent.Nodes event) {
        NeoForgePermissions.addNodes(event);
    }
}
